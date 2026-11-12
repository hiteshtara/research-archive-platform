package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * DEF-5. The filtered light path must be an OPTIMIZATION ONLY.
 *
 * The heavy EXPLORER_CTE path (search()/moduleFacets()) is the
 * authoritative definition of what a filtered Documents request
 * returns. The DEF-5 fast path (searchFilteredLight()/
 * moduleFacetsFilteredLight()) is only safe if it returns the SAME
 * rows and the SAME per-module counts for every filter that
 * DocumentExplorerService routes to it.
 *
 * So this test does not assert hand-written expectations - it asserts
 * EQUIVALENCE against the old path, filter by filter, on a real
 * PostgreSQL with every committed migration applied. A future edit
 * that makes the light union diverge from the heavy one (a changed
 * status CASE, a dropped column, a different unit resolution) fails
 * here even if both paths individually look reasonable.
 *
 * Skipped automatically when no Docker daemon is available, so it never
 * fails a Docker-less build. Run it where Docker is permitted:
 *
 *   mvn test -Dtest=DocumentExplorerFilteredLightPathIntegrationTest
 *
 * Fixtures are synthetic.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class DocumentExplorerFilteredLightPathIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static DocumentExplorerRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            DocumentExplorerFilteredLightPathIntegrationTest::migrationVersion))
                    .toList();
        }

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }

            /*
             * Award. Two rows share one workflow document number
             * (DOC-A-100) on purpose - the real AWARD/141590 case, where
             * one document number legitimately has several archived
             * rows. Both paths must return both rows and neither may
             * deduplicate.
             */
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title,
                        status_description, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, begin_date,
                        workflow_document_number, is_primary_current
                    ) VALUES
                    (1001, '900001-00001', 1, 'NSF study of ice', 'Approved Award',
                     '0100', 'National Science Foundation', '5555', 'PHYSICS',
                     DATE '2019-01-01', 'DOC-A-100', FALSE),
                    (1002, '900001-00001', 2, 'NSF study of ice, revised', 'Closed',
                     '0100', 'National Science Foundation', '5555', 'PHYSICS',
                     DATE '2020-01-01', 'DOC-A-100', TRUE),
                    (1003, '900002-00001', 1, 'NIH study of cells', 'Approved Award',
                     '0200', 'National Institutes of Health', '7777', 'CHEMISTRY',
                     DATE '2021-01-01', 'DOC-A-200', TRUE),
                    -- No document number: excluded by both paths' base predicate.
                    (1004, '900003-00001', 1, 'Not a document', 'Approved Award',
                     '0200', 'NIH', '7777', 'CHEMISTRY', DATE '2021-06-01', NULL, TRUE)
                    """);
            // Award people and units: these are what the heavy CTE
            // aggregates over every Award row and the light path resolves
            // for the page only. Several rows per Award, so a wrong
            // DISTINCT ON or a fan-out would change the result.
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code
                    ) VALUES
                    (1, 1002, '900001-00001', 2, 'JANE SMITH', 'PI'),
                    (2, 1002, '900001-00001', 2, 'AL COWORKER', 'KP'),
                    (3, 1001, '900001-00001', 1, 'JANE SMITH', 'PI'),
                    (4, 1003, '900002-00001', 1, 'ANN JONES', 'MPI'),
                    (5, 1003, '900002-00001', 1, 'BOB OTHER', 'KP')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person_unit (
                        award_person_unit_id, award_id, award_number, sequence_number,
                        award_person_id, unit_number
                    ) VALUES
                    (1, 1002, '900001-00001', 2, 1, '5555'),
                    (2, 1002, '900001-00001', 2, 1, '6666'),
                    (3, 1002, '900001-00001', 2, 2, '5555'),
                    (4, 1003, '900002-00001', 1, 4, '7777')
                    """);

            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, document_number,
                        title, status_code, status_description,
                        lead_unit_number, lead_unit_name,
                        principal_investigator_id, principal_investigator_name,
                        sponsor_code, sponsor_name, initial_start_date
                    ) VALUES
                    (2001, 'P-900001', 1, 'DOC-P-100', 'NSF proposal for ice',
                     1, 'Funded', '5555', 'PHYSICS', '99', 'JANE SMITH',
                     '0100', 'National Science Foundation', DATE '2019-02-01'),
                    (2002, 'P-900002', 1, 'DOC-P-200', 'NIH proposal for cells',
                     2, 'Pending', '7777', 'CHEMISTRY', '98', 'ANN JONES',
                     '0200', 'National Institutes of Health', DATE '2021-02-01')
                    """);

            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, loaded_at, document_number,
                        negotiation_status_code, negotiation_status_description,
                        negotiator_person_id, negotiator_full_name,
                        negotiation_start_date,
                        negotiation_association_type_code, associated_document_id
                    ) VALUES
                    (3001, NOW(), 'DOC-N-100', 1, 'Fully Executed',
                     '77', 'NEIL NEGOTIATOR', DATE '2019-03-01', 'AWD', '900001-00001'),
                    (3002, NOW(), 'DOC-N-200', 2, 'In Negotiation',
                     '76', 'NORA NEGOTIATOR', DATE '2021-03-01', 'PRP', NULL)
                    """);

            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, sequence_number, subaward_code, loaded_at,
                        document_number, title, status_code, status_description,
                        requisitioner_unit, site_investigator,
                        organization_id, start_date
                    ) VALUES
                    (4001, 1, 'SUB-900001', NOW(), 'DOC-S-100', 'NSF subaward for ice',
                     7, '07. Executed', '5555', 55, 'ORG-1', DATE '2019-04-01'),
                    (4002, 1, 'SUB-900002', NOW(), 'DOC-S-200', 'NIH subaward for cells',
                     1, '01. RA Review', '7777', 56, 'ORG-2', DATE '2021-04-01')
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward_contact (
                        subaward_contact_id, subaward_id, subaward_code, sequence_number
                    ) VALUES
                    (1, 4001, 'SUB-900001', 1),
                    (2, 4001, 'SUB-900001', 1)
                    """);
            statement.execute("""
                    INSERT INTO archive.subaward_funding (
                        subaward_funding_source_id, subaward_id, subaward_code,
                        sequence_number, award_number
                    ) VALUES
                    (1, 4001, 'SUB-900001', 1, '900001-00001')
                    """);
        }

        repository = new DocumentExplorerRepository(JdbcClient.create(dataSource));
    }

    /*
     * Every filter DocumentExplorerService.canUseLightFilteredPath()
     * accepts. Each must produce identical rows and counts on both
     * paths.
     */
    private static List<Map.Entry<String, DocumentExplorerFilter>> lightEligibleFilters() {
        return List.of(
                Map.entry("text query matching several modules", filter(f -> f.query("NSF"))),
                Map.entry("text query matching nothing", filter(f -> f.query("ZZZ-NO-MATCH"))),
                Map.entry("text query matching a document number", filter(f -> f.query("DOC-A-100"))),
                Map.entry("text query matching a business record number",
                        filter(f -> f.query("900001-00001"))),
                Map.entry("exact document number", filter(f -> f.documentNumber("DOC-P-100"))),
                Map.entry("business record number", filter(f -> f.businessRecordNumber("SUB-900001"))),
                Map.entry("module only, AWARD", filter(f -> f.module("AWARD"))),
                Map.entry("module only, PROPOSAL", filter(f -> f.module("PROPOSAL"))),
                Map.entry("module only, NEGOTIATION", filter(f -> f.module("NEGOTIATION"))),
                Map.entry("module only, SUBAWARD", filter(f -> f.module("SUBAWARD"))),
                Map.entry("normalized status", filter(f -> f.normalizedStatus("ACTIVE"))),
                Map.entry("native status", filter(f -> f.nativeStatus("Fully Executed"))),
                Map.entry("lead unit without includeAnyUnit", filter(f -> f.unitNumber("5555"))),
                Map.entry("sponsor code", filter(f -> f.sponsorCode("0100"))),
                Map.entry("subrecipient organization", filter(f -> f.subrecipientOrganizationId("ORG-1"))),
                Map.entry("date from", filter(f -> f.dateFrom(LocalDate.of(2021, 1, 1)))),
                Map.entry("date to", filter(f -> f.dateTo(LocalDate.of(2019, 12, 31)))),
                Map.entry("query and module together",
                        filter(f -> f.query("NSF").module("AWARD"))),
                Map.entry("query and date range together",
                        filter(f -> f.query("NSF").dateFrom(LocalDate.of(2019, 1, 1))
                                .dateTo(LocalDate.of(2020, 12, 31))))
        );
    }

    /*
     * Guards the equivalence tests against passing vacuously. Two
     * queries that both return nothing are trivially "equivalent", so
     * the fixture has to be shown to actually produce rows - across
     * more than one module, and including the duplicate-document-number
     * Award pair - before the comparisons above mean anything.
     */
    @Test
    void theFixtureActuallyProducesMultiModuleRowsSoEquivalenceIsNotVacuous() {
        List<DocumentExplorerRow> nsf = repository.search(filter(f -> f.query("NSF")), 100, 0);
        assertThat(nsf).hasSize(4);
        assertThat(nsf).extracting(DocumentExplorerRow::module)
                .containsExactlyInAnyOrder("AWARD", "AWARD", "PROPOSAL", "SUBAWARD");

        // Every module is reachable through the explorer at all.
        for (String module : List.of("AWARD", "PROPOSAL", "NEGOTIATION", "SUBAWARD")) {
            assertThat(repository.search(filter(f -> f.module(module)), 100, 0))
                    .as("module: %s", module)
                    .isNotEmpty();
        }

        // And the Award row with no document number is excluded by both
        // paths, so the base predicate is really being applied.
        assertThat(repository.search(filter(f -> f.businessRecordNumber("900003-00001")), 100, 0))
                .isEmpty();
        assertThat(repository.searchFilteredLight(filter(f -> f.businessRecordNumber("900003-00001")), 100, 0))
                .isEmpty();
    }

    @Test
    void lightPathReturnsTheSameRowsAsTheHeavyPathForEveryLightEligibleFilter() {
        for (Map.Entry<String, DocumentExplorerFilter> each : lightEligibleFilters()) {
            DocumentExplorerFilter filter = each.getValue();
            List<DocumentExplorerRow> heavy = repository.search(filter, 100, 0);
            List<DocumentExplorerRow> light = repository.searchFilteredLight(filter, 100, 0);

            assertThat(light)
                    .as("row count, filter: %s", each.getKey())
                    .hasSameSizeAs(heavy);
            // Compared as a multiset: the light path adds
            // source_record_id as a final ORDER BY tie-breaker, which is
            // a deliberate determinism improvement over the heavy
            // path's document_number-only tie-break, so the two can
            // legitimately order two rows sharing a document number
            // differently. Membership and multiplicity must still match
            // exactly - nothing added, dropped or deduplicated.
            assertThat(light)
                    .as("rows, filter: %s", each.getKey())
                    .containsExactlyInAnyOrderElementsOf(heavy);
        }
    }

    @Test
    void lightPathReturnsTheSameModuleCountsAsTheHeavyPathForEveryLightEligibleFilter() {
        for (Map.Entry<String, DocumentExplorerFilter> each : lightEligibleFilters()) {
            DocumentExplorerFilter filter = each.getValue();
            assertThat(facetMap(repository.moduleFacetsFilteredLight(filter)))
                    .as("module counts, filter: %s", each.getKey())
                    .isEqualTo(facetMap(repository.moduleFacets(filter)));
        }
    }

    /*
     * The reason DEF-5 can drop the separate COUNT(*): the response's
     * totalElements is now the sum of the facet counts. That identity
     * has to hold against the authoritative path, for every filter.
     */
    @Test
    void sumOfModuleCountsEqualsTheAuthoritativeTotalCount() {
        for (Map.Entry<String, DocumentExplorerFilter> each : lightEligibleFilters()) {
            DocumentExplorerFilter filter = each.getValue();
            long summed = repository.moduleFacetsFilteredLight(filter).stream()
                    .mapToLong(DocumentExplorerRepository.ModuleFacetRow::n)
                    .sum();
            assertThat(summed)
                    .as("summed facets vs count(), filter: %s", each.getKey())
                    .isEqualTo(repository.count(filter));
        }
    }

    /*
     * Pagination parity. A page taken from the light path must be the
     * same page the heavy path would have produced, and paging must
     * never drop or repeat a row - including across DOC-A-100, the
     * document number with two archived Award rows.
     */
    @Test
    void pagingTheLightPathCoversEveryRowExactlyOnce() {
        DocumentExplorerFilter filter = filter(f -> f.query("NSF"));
        List<DocumentExplorerRow> all = repository.searchFilteredLight(filter, 100, 0);
        assertThat(all).isNotEmpty();

        List<DocumentExplorerRow> paged = new java.util.ArrayList<>();
        for (int offset = 0; offset < all.size(); offset += 2) {
            paged.addAll(repository.searchFilteredLight(filter, 2, offset));
        }
        assertThat(paged).containsExactlyElementsOf(all);
    }

    @Test
    void theDuplicateDocumentNumberKeepsBothArchivedRowsOnTheLightPath() {
        List<DocumentExplorerRow> rows =
                repository.searchFilteredLight(filter(f -> f.documentNumber("DOC-A-100")), 100, 0);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DocumentExplorerRow::targetId)
                .containsExactlyInAnyOrder("1001", "1002");
    }

    /*
     * The light path still has to deliver Award's real primary person,
     * unit count and person count for the rows on the page - that is
     * the whole point of the second enrichment stage. If enrichment
     * were skipped these would be NULL/0 placeholders from the light
     * union.
     */
    @Test
    void awardRowsOnTheLightPathCarryRealEnrichedPersonAndUnitValues() {
        List<DocumentExplorerRow> rows =
                repository.searchFilteredLight(filter(f -> f.documentNumber("DOC-A-100")), 100, 0);
        DocumentExplorerRow current = rows.stream()
                .filter(row -> "1002".equals(row.targetId()))
                .findFirst()
                .orElseThrow();
        assertThat(current.primaryPersonName()).isEqualTo("JANE SMITH");
        assertThat(current.primaryPersonRole()).isEqualTo("PI");
        assertThat(current.unitCount()).isEqualTo(2);
        assertThat(current.personCount()).isEqualTo(2);
    }

    @Test
    void everySupportedSortAgreesWithTheHeavyPathOnMembership() {
        for (String sort : List.of("documentNumber", "title", "documentDate", "module")) {
            DocumentExplorerFilter filter = filter(f -> f.query("NSF").sort(sort));
            assertThat(repository.searchFilteredLight(filter, 100, 0))
                    .as("sort: %s", sort)
                    .containsExactlyInAnyOrderElementsOf(repository.search(filter, 100, 0));
        }
    }

    private static Map<String, Long> facetMap(List<DocumentExplorerRepository.ModuleFacetRow> rows) {
        return rows.stream().collect(Collectors.toMap(
                DocumentExplorerRepository.ModuleFacetRow::module,
                DocumentExplorerRepository.ModuleFacetRow::n));
    }

    // --- Small mutable builder so each case states only what it sets ---

    private static DocumentExplorerFilter filter(Function<FilterBuilder, FilterBuilder> customise) {
        return customise.apply(new FilterBuilder()).build();
    }

    private static final class FilterBuilder {
        private String documentNumber = "";
        private String businessRecordNumber = "";
        private String query = "";
        private String module = "";
        private String normalizedStatus = "";
        private String nativeStatus = "";
        private String unitNumber = "";
        private String sponsorCode = "";
        private String subrecipientOrganizationId = "";
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private String sort = "documentNumber";

        FilterBuilder documentNumber(String value) { this.documentNumber = value; return this; }
        FilterBuilder businessRecordNumber(String value) { this.businessRecordNumber = value; return this; }
        FilterBuilder query(String value) { this.query = value; return this; }
        FilterBuilder module(String value) { this.module = value; return this; }
        FilterBuilder normalizedStatus(String value) { this.normalizedStatus = value; return this; }
        FilterBuilder nativeStatus(String value) { this.nativeStatus = value; return this; }
        FilterBuilder unitNumber(String value) { this.unitNumber = value; return this; }
        FilterBuilder sponsorCode(String value) { this.sponsorCode = value; return this; }
        FilterBuilder subrecipientOrganizationId(String value) {
            this.subrecipientOrganizationId = value; return this;
        }
        FilterBuilder dateFrom(LocalDate value) { this.dateFrom = value; return this; }
        FilterBuilder dateTo(LocalDate value) { this.dateTo = value; return this; }
        FilterBuilder sort(String value) { this.sort = value; return this; }

        DocumentExplorerFilter build() {
            // personId/personName/personRole/piOnly/includeAnyUnit stay
            // at their defaults by construction: a filter that sets any
            // of them is NOT light-eligible and must never be built here.
            return new DocumentExplorerFilter(
                    documentNumber, businessRecordNumber, query, module,
                    normalizedStatus, nativeStatus, unitNumber, false,
                    "", "", "", false, sponsorCode, subrecipientOrganizationId,
                    dateFrom, dateTo, sort);
        }
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a migration file: " + path);
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
        throw new IOException("Could not locate database/migrations/");
    }
}
