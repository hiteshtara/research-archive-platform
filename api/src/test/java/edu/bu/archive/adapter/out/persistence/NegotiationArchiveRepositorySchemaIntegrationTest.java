package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAttachmentResponse;

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
        assertThatCode(() -> repository.findNegotiations("420", 25, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void findActivitiesRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findActivities(420L))
                .doesNotThrowAnyException();
    }
}
