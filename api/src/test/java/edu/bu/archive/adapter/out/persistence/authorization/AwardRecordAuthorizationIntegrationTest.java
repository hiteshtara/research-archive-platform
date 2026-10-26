package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScope;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AwardRecordAccess;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.application.authorization.UnitGrant;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardSearchFilters;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Award record-level authorization, end to end against a real PostgreSQL
 * with every committed migration applied.
 *
 * <p>What this proves that a mock cannot: that the predicate
 * {@link RecordScopeSqlBuilder} renders is valid SQL against the real
 * schema, that it is applied inside the SAME {@code WHERE} as the search
 * filters, and therefore that {@code totalElements} and the returned page
 * describe the same row set. A count computed over rows the caller cannot
 * see would disclose their existence even with every row withheld, so
 * count/page parity under scope is tested explicitly rather than assumed.
 *
 * <p>Enforcement is OFF by default everywhere in this codebase; these tests
 * drive the scope directly through the {@link AwardRecordAccess} port, which
 * is how production behaves once the flag is on. The flag-off case is the
 * first test, and it must stay byte-identical to pre-Phase-2 behaviour.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class AwardRecordAuthorizationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** Matches the Kuali contact roles an installation would approve for Research Staff. */
    private static final Set<String> RESEARCH_STAFF_ROLES = Set.of("PI", "MPI", "COI");

    private static final KualiPersonId FARRER = new KualiPersonId("P-FARRER");
    private static final KualiPersonId STRANGER = new KualiPersonId("P-STRANGER");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static AwardArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AwardRecordAuthorizationIntegrationTest::migrationVersion))
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
             * Three Award families in two unit branches, plus one with no
             * unit at all.
             *
             *   CHEM-00001  lead unit 7777 (child of 7000). Two versions;
             *               FARRER is PI on the current one only.
             *   PHYS-00001  lead unit 1234 (a different branch entirely).
             *               FARRER is listed, but with role 'OTHER', which
             *               is NOT an approved Research Staff role.
             *   NULL-00001  no lead unit, no contacts. Reachable only by
             *               Central.
             */
            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                    ('7000', 'SCIENCES',  NULL),
                    ('7777', 'CHEMISTRY', '7000'),
                    ('1234', 'PHYSICS',   NULL)
                    """);
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title,
                        status_description, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, award_effective_date,
                        is_primary_current, workflow_document_number
                    ) VALUES
                    (5001, 'CHEM-00001', 1, 'Chemistry one v1', 'Closed',
                     '0200', 'NIH', '7777', 'CHEMISTRY', DATE '2019-01-01', FALSE, 'DOC-CHEM-1'),
                    (5002, 'CHEM-00001', 2, 'Chemistry one v2', 'Active',
                     '0200', 'NIH', '7777', 'CHEMISTRY', DATE '2020-01-01', TRUE,  'DOC-CHEM-2'),
                    (6001, 'PHYS-00001', 1, 'Physics one',     'Active',
                     '0100', 'NSF', '1234', 'PHYSICS',   DATE '2020-06-01', TRUE,  'DOC-PHYS-1'),
                    (7001, 'NULL-00001', 1, 'Unitless one',    'Active',
                     NULL,   NULL,  NULL,   NULL,        NULL,              TRUE,  'DOC-NULL-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 5002, 'CHEM-00001', 2, 'LINDSAY A FARRER', 'PI',    'P-FARRER'),
                    (102, 6001, 'PHYS-00001', 1, 'LINDSAY A FARRER', 'OTHER', 'P-FARRER'),
                    (103, 5002, 'CHEM-00001', 2, 'ANN JONES',        'COI',   'P-JONES')
                    """);
        }

        repository = new AwardArchiveRepository(JdbcClient.create(dataSource));
    }

    // --- Enforcement OFF -----------------------------------------------

    @Test
    void permitAllReturnsEveryFamilyAndIsIndistinguishableFromBeforePhaseTwo() {
        AwardSearchResponse page = search(AwardRecordAccess.PERMIT_ALL);

        assertThat(awardNumbers(page))
                .containsExactlyInAnyOrder("CHEM-00001", "PHYS-00001", "NULL-00001");
        assertThat(page.results().totalElements()).isEqualTo(3);
    }

    @Test
    void permitAllOpensAnyRecordDirectly() {
        AwardArchiveService service = service(AwardRecordAccess.PERMIT_ALL);

        assertThatCode(() -> service.findFamily("CHEM-00001")).doesNotThrowAnyException();
        assertThatCode(() -> service.findFamily("PHYS-00001")).doesNotThrowAnyException();
    }

    // --- Department scope (SEC-002) -------------------------------------

    @Test
    void departmentScopeReturnsOnlyItsOwnUnitAndTheCountAgrees() {
        AwardSearchResponse page = search(departmentScope("7777", false));

        assertThat(awardNumbers(page)).containsExactly("CHEM-00001");
        // The count must be scoped too: a total of 3 here would disclose
        // the existence of two Awards this caller may not see.
        assertThat(page.results().totalElements()).isEqualTo(1);
    }

    @Test
    void departmentScopeExcludesAnotherBranchEntirely() {
        assertThat(awardNumbers(search(departmentScope("1234", false))))
                .containsExactly("PHYS-00001");
    }

    @Test
    void parentUnitWithDescendantsReachesTheChildUnit() {
        // 7000 -> 7777 expansion is performed by the caller (production:
        // RecordAuthorizationService, via the unit hierarchy) and handed to
        // the builder, so the expanded set is what is asserted here.
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", true)), Optional.empty(), Set.of()),
                Set.of("7000", "7777"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        assertThat(awardNumbers(search(fixedScope(scope)))).containsExactly("CHEM-00001");
    }

    @Test
    void parentUnitWithoutDescendantsReachesNothing() {
        // No Award has lead unit 7000 itself.
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", false)), Optional.empty(), Set.of()),
                Set.of("7000"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        AwardSearchResponse page = search(fixedScope(scope));
        assertThat(page.results().content()).isEmpty();
        assertThat(page.results().totalElements()).isZero();
    }

    // --- Research Staff contact scope (SEC-003) -------------------------

    @Test
    void contactScopeReachesTheAwardTheyAreListedOn() {
        assertThat(awardNumbers(search(contactScope(FARRER))))
                .containsExactly("CHEM-00001");
    }

    @Test
    void contactScopeIgnoresAListingWhoseRoleIsNotApproved() {
        // FARRER is on PHYS-00001 with role 'OTHER'. Being named on a record
        // is not by itself access: the role must be one the policy approves.
        assertThat(awardNumbers(search(contactScope(FARRER))))
                .doesNotContain("PHYS-00001");
    }

    @Test
    void aPersonWhoIsNotAContactAnywhereSeesNothing() {
        AwardSearchResponse page = search(contactScope(STRANGER));
        assertThat(page.results().content()).isEmpty();
        assertThat(page.results().totalElements()).isZero();
    }

    // --- Central (SEC-001) ----------------------------------------------

    @Test
    void centralSeesEverythingIncludingRecordsWithNoUnitOrContact() {
        AwardSearchResponse page = search(fixedScope(
                scopeFor(new AccessScope(true, Set.of(), Optional.empty(), Set.of()),
                        Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT)));

        assertThat(awardNumbers(page))
                .containsExactlyInAnyOrder("CHEM-00001", "PHYS-00001", "NULL-00001");
        assertThat(page.results().totalElements()).isEqualTo(3);
    }

    // --- Fail closed (SEC-007) ------------------------------------------

    @Test
    void anUnresolvedOrDeniedIdentitySeesNothingAndLeaksNoCount() {
        // A Denied outcome renders DENY_ALL, not an absent predicate.
        SqlFragment denied = new RecordScopeSqlBuilder(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                IoSqlStrategy.NONE)
                .build(new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED),
                        RecordModule.AWARD, "av", Set.of());

        assertThat(denied.sql()).isEqualTo(SqlFragment.DENY_ALL.sql());

        AwardSearchResponse page = search(fixedScope(denied));
        assertThat(page.results().content()).isEmpty();
        assertThat(page.results().totalElements()).isZero();
    }

    @Test
    void aScopeWithNoUsableClauseDeniesRatherThanMatchingEverything() {
        SqlFragment empty = scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        AwardSearchResponse page = search(fixedScope(empty));
        assertThat(page.results().content()).isEmpty();
        assertThat(page.results().totalElements()).isZero();
    }

    // --- Direct URL access ----------------------------------------------

    @Test
    void aDirectRecordUrlIsDeniedForAnOutOfScopeAward() {
        AwardArchiveService service = service(denyingAccess());

        // NoSuchElementException, not a 403: an Award the caller may not see
        // must be indistinguishable from one that does not exist.
        assertThatThrownBy(() -> service.findFamily("CHEM-00001"))
                .isInstanceOf(RecordNotAccessibleException.class)
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aDirectRecordUrlIsAllowedForAnInScopeAward() {
        AwardArchiveService service = service(allowingNumber("CHEM-00001"));

        assertThatCode(() -> service.findFamily("CHEM-00001")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.findFamily("PHYS-00001"))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    // --- Revocation (SEC-006) -------------------------------------------

    @Test
    void revokingAGrantTakesEffectOnTheNextRequestWithNoCacheToClear() {
        // Before: the department grant reaches CHEM-00001.
        assertThat(awardNumbers(search(departmentScope("7777", false))))
                .containsExactly("CHEM-00001");

        // After revocation the caller's scope is simply empty, and the very
        // next call sees nothing. Nothing is memoised across requests: the
        // predicate is rebuilt from the scope every time.
        AwardSearchResponse afterRevocation = search(fixedScope(scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT)));

        assertThat(afterRevocation.results().content()).isEmpty();
        assertThat(afterRevocation.results().totalElements()).isZero();
    }

    // --- The exact workflow-document-number callout ---------------------

    @Test
    void theExactDocumentCalloutIsWithheldForAnOutOfScopeAward() {
        /*
         * REGRESSION. This callout is a SEPARATE statement from the paged
         * query, so the scope predicate on the page does not touch it. Left
         * unfiltered it hands anyone who knows or guesses a workflow document
         * number the awardId, awardNumber, title and status of an Award
         * outside their scope - a disclosure that no amount of row filtering
         * on the page would prevent.
         */
        AwardSearchResponse response = service(departmentScope("7777", false))
                .search("DOC-PHYS-1", AwardSearchFilters.none(), 0, 50);

        assertThat(response.exactDocumentMatch()).isNull();
        assertThat(response.results().content()).isEmpty();
        assertThat(response.results().totalElements()).isZero();
    }

    @Test
    void theExactDocumentCalloutStillWorksForAnInScopeAward() {
        AwardSearchResponse response = service(departmentScope("7777", false))
                .search("DOC-CHEM-2", AwardSearchFilters.none(), 0, 50);

        assertThat(response.exactDocumentMatch()).isNotNull();
        assertThat(response.exactDocumentMatch().awardNumber()).isEqualTo("CHEM-00001");
    }

    @Test
    void theExactDocumentCalloutIsUnchangedWhenEnforcementIsOff() {
        AwardSearchResponse response = service(AwardRecordAccess.PERMIT_ALL)
                .search("DOC-PHYS-1", AwardSearchFilters.none(), 0, 50);

        assertThat(response.exactDocumentMatch()).isNotNull();
        assertThat(response.exactDocumentMatch().awardNumber()).isEqualTo("PHYS-00001");
    }

    @Test
    void aContactScopedCallerCannotProbeForDocumentNumbersOutsideTheirAwards() {
        AwardSearchResponse response = service(contactScope(FARRER))
                .search("DOC-NULL-1", AwardSearchFilters.none(), 0, 50);

        assertThat(response.exactDocumentMatch()).isNull();
    }

    // --- Non-disclosure --------------------------------------------------

    @Test
    void anUnauthorizedRecordIsIndistinguishableFromOneThatDoesNotExist() {
        AwardArchiveService denied = service(denyingAccess());

        Throwable forReal = catchThrowable(() -> denied.findFamily("CHEM-00001"));
        Throwable forImaginary = catchThrowable(() -> denied.findFamily("NO-SUCH-AWARD"));

        // Same exception type, and nothing in the message distinguishes the
        // two - a caller cannot use the error to confirm a record exists.
        assertThat(forReal).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(forImaginary).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(forReal.getMessage()).isEqualTo(forImaginary.getMessage());
    }

    // --- Count / page parity --------------------------------------------

    @Test
    void theCountAlwaysMatchesWhatPagingCanActuallyReach() {
        for (AwardRecordAccess access : List.of(
                AwardRecordAccess.PERMIT_ALL,
                departmentScope("7777", false),
                departmentScope("1234", false),
                contactScope(FARRER),
                contactScope(STRANGER))) {

            AwardSearchResponse page = service(access).search("", AwardSearchFilters.none(), 0, 50);
            assertThat(page.results().content()).hasSize((int) page.results().totalElements());
        }
    }

    // --- helpers ---------------------------------------------------------

    private static AwardSearchResponse search(AwardRecordAccess access) {
        return service(access).search("", AwardSearchFilters.none(), 0, 50);
    }

    private static AwardArchiveService service(AwardRecordAccess access) {
        return new AwardArchiveService(repository, null, access);
    }

    private static List<String> awardNumbers(AwardSearchResponse page) {
        return page.results().content().stream().map(row -> row.awardNumber()).toList();
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match) {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE, match, RESEARCH_STAFF_ROLES);
    }

    private static SqlFragment scopeFor(
            AccessScope scope,
            Set<String> expandedUnits,
            AuthorizationPolicy.DepartmentMatch match
    ) {
        return new RecordScopeSqlBuilder(policy(match), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(scope), RecordModule.AWARD, "av", expandedUnits);
    }

    private static AwardRecordAccess departmentScope(String unit, boolean descendants) {
        return scopedAccess(scopeFor(
                new AccessScope(false, Set.of(new UnitGrant(unit, descendants)), Optional.empty(), Set.of()),
                Set.of(unit),
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                "7777".equals(unit) ? Set.of("CHEM-00001")
                        : "1234".equals(unit) ? Set.of("PHYS-00001") : Set.of());
    }

    private static AwardRecordAccess contactScope(KualiPersonId person) {
        return scopedAccess(scopeFor(
                new AccessScope(false, Set.of(), Optional.of(person), Set.of()),
                Set.of(),
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                // FARRER is PI on CHEM-00001; their PHYS-00001 listing has an
                // unapproved role, so it grants nothing.
                FARRER.equals(person) ? Set.of("CHEM-00001") : Set.of());
    }

    /**
     * A port that applies one prepared predicate to list queries AND answers
     * the per-record question consistently with it.
     *
     * <p>The two are genuinely separate code paths in production - the SQL
     * builder renders a predicate, the evaluator decides one record - and
     * they must agree. Modelling only the predicate here would have let the
     * unscoped exact-match callout pass its own regression test, which is
     * exactly what happened on the first run.
     */
    private static AwardRecordAccess scopedAccess(SqlFragment fragment, Set<String> visible) {
        return new AwardRecordAccess() {
            @Override public SqlFragment awardScopeSql(String alias) {
                return fragment;
            }

            @Override public void requireAward(long awardId) { }

            @Override public void requireAwardNumber(String awardNumber) {
                if (!visible.contains(awardNumber)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return visible.contains(awardNumber);
            }
        };
    }

    /** Predicate only; every record readable. For list-filtering assertions. */
    private static AwardRecordAccess fixedScope(SqlFragment fragment) {
        return scopedAccess(fragment, Set.of("CHEM-00001", "PHYS-00001", "NULL-00001"));
    }

    private static AwardRecordAccess denyingAccess() {
        return new AwardRecordAccess() {
            @Override public SqlFragment awardScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public void requireAward(long awardId) {
                throw new RecordNotAccessibleException();
            }

            @Override public void requireAwardNumber(String awardNumber) {
                throw new RecordNotAccessibleException();
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return false;
            }
        };
    }

    private static AwardRecordAccess allowingNumber(String allowed) {
        return new AwardRecordAccess() {
            @Override public SqlFragment awardScopeSql(String alias) {
                return SqlFragment.NONE;
            }

            @Override public void requireAward(long awardId) { }

            @Override public void requireAwardNumber(String awardNumber) {
                if (!allowed.equals(awardNumber)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return allowed.equals(awardNumber);
            }
        };
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return Path.of("..", "database", "migrations");
    }
}
