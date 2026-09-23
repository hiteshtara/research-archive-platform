package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/*
 * Applies every committed database/migrations/*.sql file, in order, to
 * a real ephemeral Postgres container, then runs
 * NegotiationArchiveRepository's actual SQL - including real row
 * mapping into NegotiationAttachmentResponse - against that real
 * schema and real fixture data. A mocked JdbcClient (every other
 * repository test in this codebase) never exercises Spring's real
 * RowMapper at all, so it can neither catch a column that doesn't
 * exist NOR a column whose alias doesn't match the DTO's property name
 * closely enough for RowMapper.mapRow() to bind it - both bugs reached
 * dev RDS live and undetected on 2026-08-14 for exactly this reason
 * (see docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md's
 * incident note):
 *
 *   1. legacy_restricted_flag genuinely didn't exist yet (V076 not
 *      applied) - BadSqlGrammarException, caught by the schema-only
 *      version of this test (an empty table still fails to even
 *      generate a SELECT plan if a column is missing).
 *   2. Once V076 was applied, a SECOND bug surfaced: three SELECT
 *      columns (archived_attachment_id, original_file_name, byte_size)
 *      were never aliased to match the DTO's attachmentId/fileName/
 *      fileSize property names, so RowMapper.mapRow() failed with
 *      "The column name attachment_id was not found in this
 *      ResultSet" - but ONLY once a real row existed to map. An empty
 *      ResultSet never calls mapRow() at all, so this suite's first
 *      version (0 rows inserted) passed cleanly despite the bug -
 *      exactly why every fixture below inserts real rows before
 *      asserting on them, not just an empty-table smoke test.
 *
 * pgvector/pgvector:pg17 (not the bare postgres:17 image) because
 * V069/V070's `CREATE EXTENSION vector` would otherwise fail - matches
 * the pgvector-enabled Postgres 17 this project runs on RDS.
 */
@Testcontainers
class NegotiationArchiveRepositorySchemaIntegrationTest {

    private static final Pattern MIGRATION_VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    private static NegotiationArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsSeedFixturesAndBuildRepository(
            @TempDir Path ignoredTempDir
    ) throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION
                            .matcher(path.getFileName().toString())
                            .matches())
                    .sorted(Comparator.comparingInt(
                            NegotiationArchiveRepositorySchemaIntegrationTest
                                    ::migrationVersion
                    ))
                    .toList();
        }
        assertThat(migrations)
                .as("expected to find the real database/migrations/*.sql "
                        + "files - resolved directory: " + migrationsDir)
                .isNotEmpty();

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                String sql = Files.readString(migration);
                statement.execute(sql);
            }

            /*
             * Real, live-verified dev RDS fixtures (2026-08-14).
             * Negotiation 257 deliberately gets NO row (the real
             * zero-attachment case). Negotiation 420 is the single-row
             * fixture this whole incident was about. Negotiation 786
             * is the real 5-row multi-attachment/multi-activity case
             * (activities 10293 and 10294, ordered by
             * findAttachments()'s own ORDER BY activity_id then
             * archived_attachment_id). One extra row (negotiation
             * 999, not a real ID - synthetic) covers the real
             * null-byte_size/MISSING/no-S3 case: 26,581 of the
             * 28,923 live Negotiation attachment rows have a NULL
             * byte_size (never archived), which findAttachments' own
             * `downloadable` boolean must compute as false, and
             * fileSize (a boxed Long, not a primitive) must map to
             * null rather than throw.
             */
            statement.execute("""
                    INSERT INTO archive.archived_attachment (
                        module_code, source_attachment_id, parent_record_id,
                        original_file_name, content_type, description,
                        byte_size, archive_status, s3_bucket, s3_key,
                        source_metadata, legacy_restricted_flag, source_file_id
                    ) VALUES (
                        'NEGOTIATION', 101, 420,
                        'kotton-proteostasis.pdf', 'application/pdf',
                        'Kotton Proteostasis',
                        1024, 'ARCHIVED', 'test-bucket', 'test/key/101.pdf',
                        '{"activity_id": "10134", "source_update_user": "jlrevvy"}'::jsonb,
                        'N', '24828'
                    )
                    """);

            statement.execute("""
                    INSERT INTO archive.archived_attachment (
                        module_code, source_attachment_id, parent_record_id,
                        original_file_name, content_type, description,
                        byte_size, archive_status, s3_bucket, s3_key,
                        source_metadata, legacy_restricted_flag, source_file_id
                    ) VALUES
                    ('NEGOTIATION', 283, 786, 'export-control.pdf', 'application/pdf',
                     'export control', 2048, 'ARCHIVED', 'test-bucket', 'test/key/283.pdf',
                     '{"activity_id": "10293", "source_update_user": "egibbs"}'::jsonb,
                     'N', '26598'),
                    ('NEGOTIATION', 284, 786, 'psf.pdf', 'application/pdf',
                     'PSF', 2048, 'ARCHIVED', 'test-bucket', 'test/key/284.pdf',
                     '{"activity_id": "10293", "source_update_user": "egibbs"}'::jsonb,
                     'N', '26599'),
                    ('NEGOTIATION', 384, 786, 'fe.pdf', 'application/pdf',
                     'FE', 2048, 'ARCHIVED', 'test-bucket', 'test/key/384.pdf',
                     '{"activity_id": "10293", "source_update_user": "egibbs"}'::jsonb,
                     'N', '27699'),
                    ('NEGOTIATION', 285, 786, 'draft-budget.pdf', 'application/pdf',
                     'Draft Budget', 2048, 'ARCHIVED', 'test-bucket', 'test/key/285.pdf',
                     '{"activity_id": "10294", "source_update_user": "egibbs"}'::jsonb,
                     'N', '26600'),
                    ('NEGOTIATION', 328, 786, 'budget-sent.pdf', 'application/pdf',
                     'Budget sent to sponsor', 2048, 'ARCHIVED', 'test-bucket', 'test/key/328.pdf',
                     '{"activity_id": "10294", "source_update_user": "egibbs"}'::jsonb,
                     'N', '27023')
                    """);

            // Real null-field case: no byte_size, no S3 object -
            // archive_status='MISSING' (source Oracle BLOB never
            // captured), which is true for 26,581 of the 28,923 real
            // Negotiation attachment rows.
            statement.execute("""
                    INSERT INTO archive.archived_attachment (
                        module_code, source_attachment_id, parent_record_id,
                        original_file_name, content_type, description,
                        byte_size, archive_status, s3_bucket, s3_key,
                        source_metadata, legacy_restricted_flag, source_file_id
                    ) VALUES (
                        'NEGOTIATION', 555, 999,
                        'missing-file.pdf', 'application/pdf', 'Missing binary example',
                        NULL, 'MISSING', NULL, NULL,
                        '{"activity_id": "20000", "source_update_user": "someone"}'::jsonb,
                        'Y', '99999'
                    )
                    """);

            /*
             * Negotiation search fixtures, shaped from the real
             * live-verified reconciliation record (Negotiation 120:
             * Fully Executed / Material Transfer Agreement / lead unit
             * 1242040000 ENG BIOMEDICAL ENG / sponsor 303630 Addgene /
             * PI AHMAD KHALIL) plus the two cases that the resolution
             * rule exists for.
             *
             * 2001 is the case this whole design turns on: an
             * Award-associated Negotiation with NO
             * negotiation_unassociated_detail row. Its PI and Sponsor
             * live only on the associated Award, so it is invisible to
             * any query that reads the detail table alone - which is
             * 20.6% of the real archive.
             *
             * 3001 shares 120's PI but has a different Sponsor, so a
             * PI+Sponsor filter that silently ORed instead of ANDed
             * would wrongly return it.
             */
            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_status_description,
                        negotiation_agreement_type_description,
                        negotiation_association_type_description,
                        negotiator_person_id, negotiator_full_name,
                        negotiation_start_date, negotiation_end_date,
                        associated_document_id, loaded_at
                    ) VALUES
                    (1201, '367521', 'Fully Executed',
                     'Material Transfer Agreement', 'None',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2014-05-24', DATE '2014-06-02', '119',
                     CURRENT_TIMESTAMP),
                    (2001, '400001', 'In Progress',
                     'Subaward', 'Award',
                     'U00000001', 'OTHER NEGOTIATOR',
                     DATE '2016-01-01', DATE '2016-06-01', '105698-00001',
                     CURRENT_TIMESTAMP),
                    (3001, '400002', 'Fully Executed',
                     'Material Transfer Agreement', 'None',
                     'U93001494', 'JESSICA L RIVIECCIO',
                     DATE '2018-03-01', DATE '2018-04-01', '3000',
                     CURRENT_TIMESTAMP)
                    """);

            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title,
                        principal_investigator_name,
                        principal_investigator_person_id,
                        sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name
                    ) VALUES
                    (1201, 'UNASSOCIATED_DETAIL', '168333',
                     'AHMAD KHALIL', 'U62893002',
                     '303630', 'Addgene',
                     '1242040000', 'ENG BIOMEDICAL ENG'),
                    (2001, 'AWARD', 'Autism Study',
                     'REAL AWARD PI', 'U222',
                     '301045', 'NIH/National Institute on Aging',
                     '1242040000', 'ENG BIOMEDICAL ENG'),
                    (3001, 'UNASSOCIATED_DETAIL', 'Other Study',
                     'AHMAD KHALIL', 'U62893002',
                     '999999', 'Some Other Sponsor',
                     '1240000000', 'ENG DEANS OFFICE')
                    """);
        }

        repository = new NegotiationArchiveRepository(
                JdbcClient.create(dataSource)
        );
    }

    private static int migrationVersion(Path path) {
        Matcher matcher =
                MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Not a migration file: " + path
            );
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
            candidate = candidate.getParent();
        }
        throw new IOException(
                "Could not locate database/migrations/ above "
                        + Path.of("").toAbsolutePath()
        );
    }

    /*
     * Real fixture: negotiation_id=257 has zero Oracle attachment
     * rows - the endpoint must succeed with an empty list, not error.
     */
    @Test
    void findAttachmentsReturnsEmptyListForANegotiationWithNoAttachments() {
        assertThatCode(() -> repository.findAttachments(257L))
                .doesNotThrowAnyException();
        assertThat(repository.findAttachments(257L)).isEmpty();
    }

    /*
     * Real fixture: negotiation_id=420, the exact record this
     * incident was about. Every field asserted against its real
     * live-verified value, proving RowMapper actually bound each
     * aliased column to the correct DTO property - not just "no
     * exception thrown".
     */
    @Test
    void findAttachmentsMapsTheRealNegotiation420FixtureCorrectly() {
        List<NegotiationAttachmentResponse> attachments =
                repository.findAttachments(420L);

        assertThat(attachments).hasSize(1);
        NegotiationAttachmentResponse attachment = attachments.get(0);

        assertThat(attachment.activityId()).isEqualTo(10134L);
        assertThat(attachment.oracleAttachmentId()).isEqualTo(101L);
        assertThat(attachment.oracleFileId()).isEqualTo("24828");
        assertThat(attachment.description()).isEqualTo("Kotton Proteostasis");
        assertThat(attachment.restrictedFlag()).isEqualTo("N");
        assertThat(attachment.fileName()).isEqualTo("kotton-proteostasis.pdf");
        assertThat(attachment.contentType()).isEqualTo("application/pdf");
        assertThat(attachment.fileSize()).isEqualTo(1024L);
        assertThat(attachment.archiveStatus()).isEqualTo("ARCHIVED");
        assertThat(attachment.sourceUpdateUser()).isEqualTo("jlrevvy");
        assertThat(attachment.downloadable()).isTrue();
        assertThat(attachment.attachmentId()).isNotNull();
    }

    /*
     * Real fixture: negotiation_id=786 has 5 attachment rows across
     * two activities (10293, 10294) - proves multi-row mapping, not
     * just a single lucky row, and proves the ORDER BY (activity_id,
     * then archived_attachment_id) is respected.
     */
    @Test
    void findAttachmentsMapsAllFiveRealNegotiation786Rows() {
        List<NegotiationAttachmentResponse> attachments =
                repository.findAttachments(786L);

        assertThat(attachments).hasSize(5);
        assertThat(attachments)
                .extracting(
                        NegotiationAttachmentResponse::oracleAttachmentId,
                        NegotiationAttachmentResponse::activityId,
                        NegotiationAttachmentResponse::description
                )
                .containsExactly(
                        tuple(283L, 10293L, "export control"),
                        tuple(284L, 10293L, "PSF"),
                        tuple(384L, 10293L, "FE"),
                        tuple(285L, 10294L, "Draft Budget"),
                        tuple(328L, 10294L, "Budget sent to sponsor")
                );
    }

    /*
     * Real null-field case (26,581 of 28,923 live rows): no S3
     * object, no byte_size - downloadable must compute false (never
     * throw on the NULL comparisons), and fileSize (a boxed Long)
     * must map to null rather than crash on primitive unboxing.
     */
    @Test
    void findAttachmentsHandlesRealNullByteSizeAndMissingStorageFields() {
        List<NegotiationAttachmentResponse> attachments =
                repository.findAttachments(999L);

        assertThat(attachments).hasSize(1);
        NegotiationAttachmentResponse attachment = attachments.get(0);

        assertThat(attachment.fileSize()).isNull();
        assertThat(attachment.archiveStatus()).isEqualTo("MISSING");
        assertThat(attachment.downloadable()).isFalse();
        assertThat(attachment.restrictedFlag()).isEqualTo("Y");
    }

    @Test
    void findByIdRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findById(420L))
                .doesNotThrowAnyException();
        assertThat(repository.findById(420L)).isEqualTo(Optional.empty());
    }

    @Test
    void findNegotiationsRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findNegotiations(
                NegotiationSearchFilters.ofQuery("420"), 25, 0))
                .doesNotThrowAnyException();
    }

    // --- structured multi-filter search -------------------------------

    private static NegotiationSearchFilters filters(
            String query, String status, String negotiator,
            String agreementType, String principalInvestigator,
            String sponsor, String leadUnit, String associationType,
            String associationId, java.time.LocalDate startFrom,
            java.time.LocalDate startTo, java.time.LocalDate endFrom,
            java.time.LocalDate endTo
    ) {
        return new NegotiationSearchFilters(
                query, status, negotiator, agreementType,
                principalInvestigator, sponsor, leadUnit, associationType,
                associationId, startFrom, startTo, endFrom, endTo);
    }

    private static List<Long> idsOf(
            List<edu.bu.archive.adapter.in.web.dto.negotiation
                    .NegotiationSummaryResponse> rows
    ) {
        return rows.stream()
                .map(r -> r.negotiationId())
                .filter(id -> id >= 1000L)
                .sorted()
                .toList();
    }

    @Test
    void noFiltersImposeNoConditionAndReturnEveryNegotiation() {
        assertThat(idsOf(repository.findNegotiations(
                NegotiationSearchFilters.none(), 25, 0)))
                .containsExactly(1201L, 2001L, 3001L);
    }

    @Test
    void principalInvestigatorAndSponsorCombineWithAndNotOr() {
        // The headline BU case: PI + Sponsor. 3001 shares the PI but
        // has a different Sponsor and must NOT come back.
        List<Long> ids = idsOf(repository.findNegotiations(filters(
                null, null, null, null, "KHALIL", "Addgene",
                null, null, null, null, null, null, null), 25, 0));

        assertThat(ids).containsExactly(1201L);
    }

    @Test
    void principalInvestigatorAloneMatchesBothOfThatPisNegotiations() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, "KHALIL", null,
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(1201L, 3001L);
    }

    @Test
    void threeFiltersAllApply() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, "Fully Executed", null, null, "KHALIL", "Addgene",
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(1201L);

        // Same three filters, but a status no matching row has.
        assertThat(idsOf(repository.findNegotiations(filters(
                null, "In Progress", null, null, "KHALIL", "Addgene",
                null, null, null, null, null, null, null), 25, 0)))
                .isEmpty();
    }

    @Test
    void anAwardSourcedNegotiationIsFindableByItsAwardsPiAndSponsor() {
        /*
         * The 20.6% regression guard. 2001 has no unassociated-detail
         * row at all - its PI and Sponsor come from the associated
         * Award. If the resolved attributes were ever dropped from the
         * query, this returns empty and a fifth of the archive becomes
         * unsearchable while the total count still looks plausible.
         */
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, "REAL AWARD PI", null,
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(2001L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, "NIH",
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(2001L);
    }

    @Test
    void leadUnitMatchesByNameAndByNumber() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null,
                "ENG BIOMEDICAL ENG", null, null,
                null, null, null, null), 25, 0)))
                .containsExactly(1201L, 2001L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null,
                "1242040000", null, null,
                null, null, null, null), 25, 0)))
                .containsExactly(1201L, 2001L);
    }

    @Test
    void sponsorMatchesByNameAndByCode() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, "Addgene",
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(1201L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, "303630",
                null, null, null, null, null, null, null), 25, 0)))
                .containsExactly(1201L);
    }

    @Test
    void dateRangeBoundsAreInclusiveOnBothEnds() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null, null, null, null,
                java.time.LocalDate.of(2014, 5, 24),
                java.time.LocalDate.of(2014, 5, 24),
                null, null), 25, 0)))
                .containsExactly(1201L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null, null, null, null,
                java.time.LocalDate.of(2016, 1, 1), null,
                null, null), 25, 0)))
                .containsExactly(2001L, 3001L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null, null, null, null,
                null, null,
                null, java.time.LocalDate.of(2014, 6, 2)), 25, 0)))
                .containsExactly(1201L);
    }

    @Test
    void associationTypeAndIdFilterExactly() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null, null,
                "Award", null, null, null, null, null), 25, 0)))
                .containsExactly(2001L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, null, null, null, null,
                null, "105698-00001", null, null, null, null), 25, 0)))
                .containsExactly(2001L);
    }

    @Test
    void agreementTypeAndNegotiatorFilter() {
        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, null, "Subaward", null, null, null,
                null, null, null, null, null, null), 25, 0)))
                .containsExactly(2001L);

        assertThat(idsOf(repository.findNegotiations(filters(
                null, null, "RIVIECCIO", null, null, null, null,
                null, null, null, null, null, null), 25, 0)))
                .containsExactly(1201L, 3001L);
    }

    @Test
    void freeTextIsAndedWithStructuredFiltersNotOred() {
        // "Addgene" alone finds 1201 through the resolved sponsor name.
        assertThat(idsOf(repository.findNegotiations(
                NegotiationSearchFilters.ofQuery("Addgene"), 25, 0)))
                .containsExactly(1201L);

        // Combined with a status that 1201 does not have, the result is
        // empty - it would be non-empty if free text were ORed in.
        assertThat(idsOf(repository.findNegotiations(filters(
                "Addgene", "In Progress", null, null, null, null, null,
                null, null, null, null, null, null), 25, 0)))
                .isEmpty();
    }

    @Test
    void countAndPageAgreeOnTheSameFilters() {
        NegotiationSearchFilters f = filters(
                null, null, null, null, "KHALIL", null,
                null, null, null, null, null, null, null);

        assertThat(repository.countNegotiations(f)).isEqualTo(2L);
        assertThat(idsOf(repository.findNegotiations(f, 25, 0)))
                .hasSize(2);
    }

    @Test
    void paginationPreservesFiltersAcrossPages() {
        NegotiationSearchFilters f = filters(
                null, null, null, null, "KHALIL", null,
                null, null, null, null, null, null, null);

        List<Long> first = idsOf(repository.findNegotiations(f, 1, 0));
        List<Long> second = idsOf(repository.findNegotiations(f, 1, 1));

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first).isNotEqualTo(second);
        assertThat(repository.countNegotiations(f)).isEqualTo(2L);
    }

    @Test
    void resolvedAttributesAreMappedOntoTheResponse() {
        var row = repository.findNegotiations(filters(
                null, null, null, null, null, "Addgene",
                null, null, null, null, null, null, null), 25, 0).get(0);

        assertThat(row.title()).isEqualTo("168333");
        assertThat(row.principalInvestigatorName()).isEqualTo("AHMAD KHALIL");
        assertThat(row.sponsorCode()).isEqualTo("303630");
        assertThat(row.sponsorName()).isEqualTo("Addgene");
        assertThat(row.leadUnitNumber()).isEqualTo("1242040000");
        assertThat(row.leadUnitName()).isEqualTo("ENG BIOMEDICAL ENG");
        assertThat(row.attributeSource()).isEqualTo("UNASSOCIATED_DETAIL");
    }

    @Test
    void aBlankFilterIsTreatedAsAbsentNotAsAnEmptyStringMatch() {
        assertThat(idsOf(repository.findNegotiations(filters(
                "   ", "", "  ", "", "", "", "", "", "",
                null, null, null, null), 25, 0)))
                .containsExactly(1201L, 2001L, 3001L);
    }

    @Test
    void findActivitiesRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findActivities(420L))
                .doesNotThrowAnyException();
    }
}
