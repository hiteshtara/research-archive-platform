package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSearchResultResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.application.award.AwardSearchFilters;
import edu.bu.archive.application.proposal.ProposalSearchFilters;
import edu.bu.archive.application.subaward.SubawardSearchFilters;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * PREPARED, NOT YET RUN. The structured search filters added on
 * feat/standardized-search-filters, executed against a real PostgreSQL
 * with every committed migration applied - the database-backed proof the
 * mock-based SQL-shape tests (AwardSearchFiltersRepositoryTest,
 * StructuredSearchFiltersRepositoryTest) cannot give: operator and type
 * resolution of null/typed parameters, ILIKE/regexp behaviour, inclusive
 * DATE bounds, count/page parity and stable paging.
 *
 * Skipped automatically when no Docker daemon is available
 * (disabledWithoutDocker), so it never fails a Docker-less build. It was
 * written without running it: fixture columns follow V011/V013/V015/V018/
 * V046 as read, but a later migration adding a NOT NULL column would
 * surface here first. Run it where Docker is permitted:
 *
 *   mvn test -Dtest=StructuredSearchFiltersIntegrationTest
 *
 * Fixtures are synthetic.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class StructuredSearchFiltersIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static AwardArchiveRepository awards;
    private static SubawardArchiveRepository subawards;
    private static ProposalArchiveRepository proposals;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(StructuredSearchFiltersIntegrationTest::migrationVersion))
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
             * Award families:
             *   900001 - current v2 has NO grant number; v1 carries grant
             *            50105698 (the grant-number-only regression case).
             *            Current: Approved Award, NIH, CHEM, PI SMITH,
             *            effective 2020-01-01. v1: Closed, NSF, PHYS.
             *   900002 - current v1: Inactive, NSF (code 9999), unit
             *            1234 PHYSICS, effective 2020-12-31; person
             *            SMITH with a NON-PI role.
             *   900003 - current v1: Active, NIH, CHEMISTRY, PI JONES,
             *            effective 2021-01-01.
             */
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title,
                        status_description, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, award_effective_date,
                        is_primary_current
                    ) VALUES
                    (1001, '900001-00001', 1, 'Family one v1', 'Closed',
                     '0100', 'National Science Foundation', '5555', 'PHYSICS',
                     DATE '2019-01-01', FALSE),
                    (1002, '900001-00001', 2, 'Family one v2', 'Approved Award',
                     '0200', 'National Institutes of Health', '7777', 'CHEMISTRY',
                     DATE '2020-01-01', TRUE),
                    (2001, '900002-00001', 1, 'Family two', 'Inactive',
                     '9999', 'National Science Foundation', '1234', 'PHYSICS',
                     DATE '2020-12-31', TRUE),
                    (3001, '900003-00001', 1, 'Family three', 'Active',
                     '0200', 'National Institutes of Health', '7777', 'CHEMISTRY',
                     DATE '2021-01-01', TRUE),
                    -- NULL status, sponsor, lead unit and date: matches only
                    -- when no filter touches those columns.
                    (4001, '900004-00001', 1, 'Family four', NULL,
                     NULL, NULL, NULL, NULL, NULL, TRUE)
                    """);
            // Duplicate relationships that must never duplicate a result:
            // two PI rows on one version (both match "smith"), and two
            // hierarchy rows for one Award number (no unique constraint).
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code
                    ) VALUES
                    (1, 1002, '900001-00001', 2, 'JANE SMITH', 'PI'),
                    (4, 1002, '900001-00001', 2, 'PAUL SMITHSON', 'PI'),
                    (2, 2001, '900002-00001', 1, 'JOHN SMITH', 'KP'),
                    (3, 3001, '900003-00001', 1, 'ANN JONES', 'PI')
                    """);
            // Req 12 (PI filter = PI or MPI): one person per role case,
            // added to existing versions only so family counts don't change.
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code
                    ) VALUES
                    (11, 1002, '900001-00001', 2, 'SAM SMITHERS', 'MPI'),
                    (12, 3001, '900003-00001', 1, 'LEE MULTI', 'MPI'),
                    (13, 3001, '900003-00001', 1, 'ROSS COINV', 'COI'),
                    (14, 1001, '900001-00001', 1, 'OLD MULTIPI', 'MPI'),
                    (15, 2001, '900002-00001', 1, 'PAT DUAL', 'MPI'),
                    (16, 3001, '900003-00001', 1, 'PAT DUAL', 'PI'),
                    (17, 4001, '900004-00001', 1, 'VAL SPACED', ' mpi ')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_extension (award_id, grant_number)
                    VALUES (1001, '50105698')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_hierarchy (
                        award_hierarchy_id, root_award_number, award_number,
                        parent_award_number, originating_award_number, active
                    ) VALUES
                    (1, '900001-00001', '900001-00001', '000000-00000', '900001-00001', 'N'),
                    (2, '900001-00001', '900001-00001', '000000-00000', '900001-00001', 'Y')
                    """);

            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, subaward_code, sequence_number, title,
                        status_description, organization_id,
                        award_sponsor_name, award_prime_sponsor_name,
                        start_date, end_date, purchase_order_num,
                        subaward_sequence_status
                    ) VALUES
                    (501, '4001', 1, 'Sub one', '07. Executed', 'ORG-1',
                     'National Cancer Institute', NULL,
                     DATE '2019-01-01', DATE '2020-12-31', '4500001111', 'ACTIVE'),
                    (502, '4002', 1, 'Sub two', '04. PI/DA', 'ORG-2',
                     NULL, 'National Cancer Institute',
                     DATE '2019-06-01', DATE '2021-06-30', NULL, 'ACTIVE'),
                    (503, '4003', 1, 'Sub three', '07. Executed', 'org-1',
                     'Other Sponsor', NULL,
                     DATE '2022-01-01', DATE '2023-01-01', NULL, 'ACTIVE'),
                    (504, '4004', 1, 'Sub four', NULL, NULL,
                     NULL, NULL, NULL, NULL, NULL, 'ACTIVE')
                    """);

            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
                        principal_investigator_name
                    ) VALUES
                    (7001, 'P0001', 1, 'Old title', '0100', 'National Science Foundation',
                     '5555', 'PHYSICS', 'OLD PI'),
                    (7002, 'P0001', 2, 'New title', '0200', 'National Institutes of Health',
                     '7777', 'CHEMISTRY', 'JANE SMITH'),
                    (7101, 'P0002', 1, 'Other', '0200', 'National Institutes of Health',
                     '7777', 'CHEMISTRY', 'ANN JONES'),
                    (7201, 'P0003', 1, 'No attributes', NULL, NULL, NULL, NULL, NULL),
                    -- PI known only from the denormalised name (no person rows):
                    -- exercises the temporary PI-name fallback.
                    (7301, 'P0004', 1, 'Name only', NULL, NULL, NULL, NULL, 'NAME ONLY'),
                    -- Stored PI name shared with a COI person row on the same version.
                    (7401, 'P0005', 1, 'Same name', NULL, NULL, NULL, NULL, 'LEE SAMENAME')
                    """);
            // Req 12 (PI filter = PI or MPI), joined by proposal_id only.
            statement.execute("""
                    INSERT INTO archive.proposal_person (
                        proposal_person_id, proposal_id, proposal_number, sequence_number,
                        full_name, contact_role_code
                    ) VALUES
                    (8001, 7002, 'P0001', 2, 'JANE SMITH', 'PI'),
                    (8002, 7002, 'P0001', 2, 'MIA MULTI', 'MPI'),
                    (8003, 7002, 'P0001', 2, 'SAM SMITHERS', 'MPI'),
                    (8004, 7001, 'P0001', 1, 'OLD MULTIPI', 'MPI'),
                    (8005, 7101, 'P0002', 1, 'ANN JONES', 'PI'),
                    (8006, 7101, 'P0002', 1, 'RAY COINV', 'COI'),
                    (8007, 7101, 'P0002', 1, 'KIT KEYPERSON', 'KP'),
                    (8008, 7101, 'P0002', 0, 'SEQ EARLIER', 'MPI'),
                    (8009, 7002, 'P0001', 2, 'PAT DUAL', 'MPI'),
                    (8010, 7101, 'P0002', 1, 'PAT DUAL', 'PI'),
                    (8011, 7201, 'P0003', 1, 'VAL SPACED', ' mpi '),
                    (8012, 7401, 'P0005', 1, 'LEE SAMENAME', 'COI')
                    """);
        }

        JdbcClient jdbc = JdbcClient.create(dataSource);
        awards = new AwardArchiveRepository(jdbc);
        subawards = new SubawardArchiveRepository(jdbc);
        proposals = new ProposalArchiveRepository(jdbc);
    }

    private static AwardSearchFilters award(
            String status, String sponsor, String pi, String unit, LocalDate from, LocalDate to) {
        return new AwardSearchFilters(status, sponsor, pi, unit, from, to);
    }

    private static List<String> familyNumbers(String pattern, String raw, AwardSearchFilters filters) {
        List<AwardSearchResultResponse> page = awards.searchAwards(pattern, raw, filters, 100, 0);
        assertThat(awards.countSearchAwards(pattern, raw, filters))
                .as("count equals the unpaged result size")
                .isEqualTo(page.size());
        return page.stream().map(AwardSearchResultResponse::awardNumber).toList();
    }

    // --- Award families ---------------------------------------------------

    @Test
    void aGrantNumberOnlyMatchIsReturnedAndCounted() {
        // Regression: the count used to omit the family-wide Grant Number
        // branch, so this family was returned but not counted.
        assertThat(familyNumbers("%50105698%", "50105698", AwardSearchFilters.none()))
                .containsExactly("900001-00001");
    }

    @Test
    void statusIsExactAndCaseInsensitiveNeverASubstring() {
        assertThat(familyNumbers("", "", award("approved award", null, null, null, null, null)))
                .containsExactly("900001-00001");
        assertThat(familyNumbers("", "", award("Active", null, null, null, null, null)))
                .containsExactly("900003-00001"); // not the "Inactive" family
    }

    @Test
    void sponsorAndLeadUnitMatchNameOrCode() {
        assertThat(familyNumbers("", "", award(null, "9999", null, null, null, null)))
                .containsExactly("900002-00001");
        assertThat(familyNumbers("", "", award(null, "institutes", null, null, null, null)))
                .containsExactly("900001-00001", "900003-00001");
        assertThat(familyNumbers("", "", award(null, null, null, "1234", null, null)))
                .containsExactly("900002-00001");
    }

    @Test
    void principalInvestigatorMatchesPiAndMpiButNotKeyPersons() {
        // JANE SMITH (PI) and SAM SMITHERS (MPI) on 900001; JOHN SMITH is KP.
        assertThat(familyNumbers("", "", award(null, null, "smith", null, null, null)))
                .containsExactly("900001-00001");
    }

    @Test
    void anMpiOnlyPersonMatchesThePrincipalInvestigatorFilter() {
        assertThat(familyNumbers("", "", award(null, null, "multi", null, null, null)))
                .containsExactly("900003-00001"); // LEE MULTI is MPI; OLD MULTIPI is on a non-current version
    }

    @Test
    void coInvestigatorsAndKeyPersonsDoNotMatchThePrincipalInvestigatorFilter() {
        assertThat(familyNumbers("", "", award(null, null, "coinv", null, null, null))).isEmpty();
        assertThat(familyNumbers("", "", award(null, null, "john smith", null, null, null))).isEmpty();
    }

    @Test
    void freeTextStillMatchesAPersonOfAnyRole() {
        assertThat(familyNumbers("%coinv%", "coinv", AwardSearchFilters.none()))
                .containsExactly("900003-00001");
        assertThat(familyNumbers("%john smith%", "john smith", AwardSearchFilters.none()))
                .containsExactly("900002-00001");
    }

    @Test
    void aPersonWhoIsMpiOnOneAwardAndPiOnAnotherMatchesBoth() {
        assertThat(familyNumbers("", "", award(null, null, "dual", null, null, null)))
                .containsExactly("900002-00001", "900003-00001");
    }

    @Test
    void roleCodesAreTrimmedAndCaseInsensitive() {
        assertThat(familyNumbers("", "", award(null, null, "spaced", null, null, null)))
                .containsExactly("900004-00001");
    }

    @Test
    void principalInvestigatorFilterPagesStablyWithCountParity() {
        // "a" matches PI or MPI names on all four current versions.
        AwardSearchFilters a = award(null, null, "a", null, null, null);
        List<String> first = awards.searchAwards("", "", a, 2, 0).stream()
                .map(AwardSearchResultResponse::awardNumber).toList();
        List<String> second = awards.searchAwards("", "", a, 2, 2).stream()
                .map(AwardSearchResultResponse::awardNumber).toList();
        assertThat(first).containsExactly("900001-00001", "900002-00001");
        assertThat(second).containsExactly("900003-00001", "900004-00001");
        assertThat(awards.countSearchAwards("", "", a)).isEqualTo(4);
    }

    @Test
    void projectStartDateBoundsAreInclusive() {
        LocalDate day = LocalDate.of(2020, 12, 31);
        assertThat(familyNumbers("", "", award(null, null, null, null, day, day)))
                .containsExactly("900002-00001");
        assertThat(familyNumbers("", "", award(null, null, null, null, day.plusDays(1), null)))
                .containsExactly("900003-00001");
        assertThat(familyNumbers("", "", award(null, null, null, null, null, day.minusDays(1))))
                .containsExactly("900001-00001");
    }

    @Test
    void familyFiltersApplyToTheCurrentVersionOnly() {
        // v1 of 900001 is Closed/PHYSICS, but the family is represented by v2.
        assertThat(familyNumbers("", "", award("Closed", null, null, null, null, null))).isEmpty();
    }

    @Test
    void filtersAndWithFreeTextAndWithEachOther() {
        assertThat(familyNumbers("%family%", "family",
                award(null, "institutes", null, "chem", null, null)))
                .containsExactly("900001-00001", "900003-00001");
        assertThat(familyNumbers("%family three%", "family three",
                award(null, "institutes", null, null, null, null)))
                .containsExactly("900003-00001");
    }

    @Test
    void familyPagingIsStableAndDisjoint() {
        AwardSearchFilters none = AwardSearchFilters.none();
        List<String> first = awards.searchAwards("", "", none, 3, 0).stream()
                .map(AwardSearchResultResponse::awardNumber).toList();
        List<String> second = awards.searchAwards("", "", none, 3, 3).stream()
                .map(AwardSearchResultResponse::awardNumber).toList();
        assertThat(first).containsExactly("900001-00001", "900002-00001", "900003-00001");
        assertThat(second).containsExactly("900004-00001");
        assertThat(awards.countSearchAwards("", "", none)).isEqualTo(4);
    }

    @Test
    void duplicateRelationshipsNeverDuplicateAFamily() {
        // Two PI rows and one MPI row on 900001's current version all match
        // "smith"; two hierarchy rows exist for 900001. Still exactly one
        // result, counted once.
        assertThat(familyNumbers("", "", award(null, null, "smith", null, null, null)))
                .containsExactly("900001-00001");
        List<AwardSearchResultResponse> rows =
                awards.searchAwards("%900001%", "900001", AwardSearchFilters.none(), 100, 0);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rootAwardNumber()).isEqualTo("900001-00001");
        assertThat(awards.countSearchAwards("%900001%", "900001", AwardSearchFilters.none())).isEqualTo(1);
    }

    @Test
    void nullAttributesNeverMatchAFilterButDoMatchNoFilter() {
        assertThat(familyNumbers("", "", AwardSearchFilters.none())).contains("900004-00001");
        assertThat(familyNumbers("", "", award(null, "a", null, null, null, null)))
                .doesNotContain("900004-00001");
        assertThat(familyNumbers("", "", award(null, null, null, null,
                LocalDate.of(1900, 1, 1), LocalDate.of(2100, 1, 1))))
                .doesNotContain("900004-00001");
    }

    @Test
    void aFilterThatMatchesNothingReturnsAnEmptyPageAndZeroCount() {
        assertThat(familyNumbers("", "", award("No Such Status", null, null, null, null, null))).isEmpty();
    }

    // --- Award versions ---------------------------------------------------

    @Test
    void versionFiltersApplyToEachVersion() {
        AwardSearchFilters closed = award("Closed", null, null, null, null, null);
        List<AwardVersionSearchResultResponse> rows = awards.searchAwardVersions(
                "", "", "", "", null, "all", closed,
                "ORDER BY av.sequence_number DESC, av.award_number, av.award_id DESC\n", 100, 0);
        assertThat(rows).extracting(AwardVersionSearchResultResponse::awardId).containsExactly(1001L);
        assertThat(awards.countSearchAwardVersions("", "", "", "", null, "all", closed)).isEqualTo(1);
        assertThat(awards.countSearchAwardVersions("", "", "", "", null, "current", closed)).isZero();
    }

    @Test
    void anMpiOnAnOlderVersionMatchesOnlyThatVersion() {
        AwardSearchFilters multipi = award(null, null, "multipi", null, null, null);
        // Family search represents 900001 by its current v2, which has no such person.
        assertThat(familyNumbers("", "", multipi)).isEmpty();
        List<AwardVersionSearchResultResponse> rows = awards.searchAwardVersions(
                "", "", "", "", null, "all", multipi,
                "ORDER BY av.sequence_number DESC, av.award_number, av.award_id DESC\n", 100, 0);
        assertThat(rows).extracting(AwardVersionSearchResultResponse::awardId).containsExactly(1001L);
        assertThat(awards.countSearchAwardVersions("", "", "", "", null, "all", multipi)).isEqualTo(1);
        assertThat(awards.countSearchAwardVersions("", "", "", "", null, "current", multipi)).isZero();
    }

    // --- Subawards --------------------------------------------------------

    private static List<String> subawardCodes(String query, SubawardSearchFilters filters) {
        List<SubawardSummaryResponse> page = subawards.findSubawards(query, filters, 100, 0);
        assertThat(subawards.countSubawards(query, filters)).isEqualTo(page.size());
        return page.stream().map(SubawardSummaryResponse::subawardCode).sorted().toList();
    }

    @Test
    void subawardStatusMatchesWithOrWithoutItsOrdinal() {
        SubawardSearchFilters words = new SubawardSearchFilters("executed", null, null, null, null, null, null);
        SubawardSearchFilters full = new SubawardSearchFilters("07. Executed", null, null, null, null, null, null);
        assertThat(subawardCodes(null, words)).containsExactly("4001", "4003");
        assertThat(subawardCodes(null, full)).containsExactly("4001", "4003");
    }

    @Test
    void subawardSponsorCoversSponsorAndPrimeSponsor() {
        assertThat(subawardCodes(null,
                new SubawardSearchFilters(null, "cancer", null, null, null, null, null)))
                .containsExactly("4001", "4002");
    }

    @Test
    void subawardOrganizationIsExactButCaseInsensitive() {
        assertThat(subawardCodes(null,
                new SubawardSearchFilters(null, null, "ORG-1", null, null, null, null)))
                .containsExactly("4001", "4003");
        assertThat(subawardCodes(null,
                new SubawardSearchFilters(null, null, "ORG", null, null, null, null)))
                .isEmpty();
    }

    @Test
    void subawardDateBoundsAreInclusive() {
        LocalDate end = LocalDate.of(2020, 12, 31);
        assertThat(subawardCodes(null,
                new SubawardSearchFilters(null, null, null, null, null, end, end)))
                .containsExactly("4001");
    }

    @Test
    void anFrnQueryStillMatchesAndIsNarrowedByFilters() {
        // FRN branch (9-10 digits) is preserved...
        assertThat(subawardCodes("4500001111", SubawardSearchFilters.none())).containsExactly("4001");
        // ...and a filter ANDs outside it rather than being OR-ed into it.
        assertThat(subawardCodes("4500001111",
                new SubawardSearchFilters("PI/DA", null, null, null, null, null, null)))
                .isEmpty();
    }

    // --- Proposals --------------------------------------------------------

    @Test
    void proposalFiltersApplyToTheLatestVersionWithCountParity() {
        ProposalSearchFilters nsf = new ProposalSearchFilters("science", null, null);
        assertThat(proposals.findFamilyPage(null, nsf, 25, 0)).isEmpty(); // only v1 was NSF
        ProposalSearchFilters smith = new ProposalSearchFilters(null, "smith", "7777");
        List<ProposalFamilySummaryResponse> rows = proposals.findFamilyPage(null, smith, 25, 0);
        assertThat(rows).extracting(ProposalFamilySummaryResponse::proposalNumber).containsExactly("P0001");
        assertThat(proposals.countFamilyPage(null, smith)).isEqualTo(1);
        assertThat(proposals.countFamilyPage(null, ProposalSearchFilters.none())).isEqualTo(5);
        assertThat(proposals.findFamilyPage(null, ProposalSearchFilters.none(), 2, 2))
                .extracting(ProposalFamilySummaryResponse::proposalNumber).containsExactly("P0003", "P0004");
        // NULL attributes never match a filter
        assertThat(proposals.countFamilyPage(null, new ProposalSearchFilters("a", null, null))).isEqualTo(2);
    }

    private static List<String> proposalNumbers(String query, ProposalSearchFilters filters) {
        List<ProposalFamilySummaryResponse> page = proposals.findFamilyPage(query, filters, 100, 0);
        assertThat(proposals.countFamilyPage(query, filters))
                .as("count equals the unpaged result size")
                .isEqualTo(page.size());
        return page.stream().map(ProposalFamilySummaryResponse::proposalNumber).toList();
    }

    private static ProposalSearchFilters pi(String name) {
        return new ProposalSearchFilters(null, name, null);
    }

    @Test
    void proposalPiFilterMatchesPiAndMpiOnTheLatestVersion() {
        assertThat(proposalNumbers(null, pi("jane smith"))).containsExactly("P0001"); // PI
        assertThat(proposalNumbers(null, pi("mia multi"))).containsExactly("P0001");  // MPI
    }

    @Test
    void proposalPiFilterExcludesCoInvestigatorsAndKeyPersons() {
        assertThat(proposalNumbers(null, pi("coinv"))).isEmpty();
        assertThat(proposalNumbers(null, pi("keyperson"))).isEmpty();
    }

    @Test
    void proposalPiFilterIgnoresPeopleOnOlderVersions() {
        assertThat(proposalNumbers(null, pi("multipi"))).isEmpty(); // MPI on P0001 v1 only
    }

    @Test
    void proposalPersonWithAnEarlierSequenceNumberStillMatches() {
        // Kuali relates PROPOSAL_PERSONS to a version by PROPOSAL_ID only.
        assertThat(proposalNumbers(null, pi("seq earlier"))).containsExactly("P0002");
    }

    @Test
    void proposalPersonWhoIsMpiOnOneProposalAndPiOnAnotherMatchesBoth() {
        assertThat(proposalNumbers(null, pi("dual"))).containsExactly("P0001", "P0002");
    }

    @Test
    void proposalRoleCodesAreTrimmedAndCaseInsensitive() {
        assertThat(proposalNumbers(null, pi("spaced"))).containsExactly("P0003");
    }

    @Test
    void proposalPiAndMpiOnTheSameVersionReturnOneResultCountedOnce() {
        // JANE SMITH (PI) and SAM SMITHERS (MPI) both match "smith" on P0001 v2.
        assertThat(proposalNumbers(null, pi("smith"))).containsExactly("P0001");
    }

    @Test
    void aCoInvestigatorRowAloneNeverQualifiesEvenThroughTheFallback() {
        // RAY COINV is COI on P0002, whose stored PI name is someone else.
        assertThat(proposalNumbers(null, pi("ray coinv"))).isEmpty();
    }

    @Test
    void aStoredPiNameMatchIsNotSuppressedByASameNamePersonRowWithAnotherRole() {
        // P0005's stored PI name matches; the only person row with that name is COI.
        // The fallback keeps today's match; the COI row neither adds nor removes it.
        assertThat(proposalNumbers(null, pi("samename"))).containsExactly("P0005");
    }

    @Test
    void proposalPiNameFallbackPreservesPiMatchesWithoutPersonRows() {
        // P0004 has no person rows; only its denormalised PI name matches.
        // The fallback is PI-only: no MPI can ever be found for such a version.
        assertThat(proposalNumbers(null, pi("name only"))).containsExactly("P0004");
    }

    @Test
    void proposalFreeTextIsUnchanged() {
        // Free text still reads principal_investigator_name only - never person rows.
        assertThat(proposalNumbers("coinv", ProposalSearchFilters.none())).isEmpty();
        assertThat(proposalNumbers("mia multi", ProposalSearchFilters.none())).isEmpty();
        assertThat(proposalNumbers("jane smith", ProposalSearchFilters.none())).containsExactly("P0001");
    }

    @Test
    void proposalPiFilterPagesStablyWithCountParity() {
        ProposalSearchFilters a = pi("a"); // PI/MPI names or PI-name fallback on all five families
        assertThat(proposals.findFamilyPage(null, a, 2, 0))
                .extracting(ProposalFamilySummaryResponse::proposalNumber).containsExactly("P0001", "P0002");
        assertThat(proposals.findFamilyPage(null, a, 2, 2))
                .extracting(ProposalFamilySummaryResponse::proposalNumber).containsExactly("P0003", "P0004");
        assertThat(proposals.countFamilyPage(null, a)).isEqualTo(5);
    }

    @Test
    void subawardNullValuesNeverMatchAFilter() {
        assertThat(subawardCodes(null, SubawardSearchFilters.none())).contains("4004");
        assertThat(subawardCodes(null,
                new SubawardSearchFilters(null, null, null,
                        LocalDate.of(1900, 1, 1), LocalDate.of(2100, 1, 1), null, null)))
                .doesNotContain("4004");
        assertThat(subawardCodes(null,
                new SubawardSearchFilters("PI/DA", null, null, null, null, null, null)))
                .containsExactly("4002");
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
