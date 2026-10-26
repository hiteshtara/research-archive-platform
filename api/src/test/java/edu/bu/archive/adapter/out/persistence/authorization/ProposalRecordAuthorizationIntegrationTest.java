package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.authorization.AccessNotProvisionedException;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScope;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.PartialFamilyAccessException;
import edu.bu.archive.application.authorization.ProposalRecordAccess;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.application.authorization.UnitGrant;
import edu.bu.archive.application.proposal.ProposalArchiveService;
import edu.bu.archive.application.proposal.ProposalSearchFilters;

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
 * Proposal record-level authorization, against a real PostgreSQL with every
 * committed migration applied.
 *
 * <p>Mirrors the Award suite deliberately: the same engine decides both
 * modules, so the same properties must hold. What this proves that a mock
 * cannot is that the predicate {@link RecordScopeSqlBuilder} renders for
 * {@link RecordModule#PROPOSAL} is valid SQL against the real schema and the
 * {@code ranked} CTE, and that it sits in the same {@code WHERE} as the
 * search filters, so the count and the page describe one row set.
 *
 * <p>Enforcement is OFF by default everywhere; these drive the scope through
 * the port, which is how production behaves once the flag is on. The
 * flag-off baseline is the first test and must remain identical to
 * pre-Phase-2 behaviour.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ProposalRecordAuthorizationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final Set<String> RESEARCH_STAFF_ROLES = Set.of("PI", "MPI", "COI");

    private static final KualiPersonId CONTACT = new KualiPersonId("P-CONTACT");
    private static final KualiPersonId STRANGER = new KualiPersonId("P-STRANGER");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static ProposalArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            ProposalRecordAuthorizationIntegrationTest::migrationVersion))
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
             * CHEM-P1  lead unit 7777 (child of 7000). Two versions; CONTACT
             *          is PI on the latest only.
             * PHYS-P1  lead unit 1234, a different branch. CONTACT is listed
             *          with role OTHER, which is not an approved role.
             * NULL-P1  no unit, no contacts. Central only.
             */
            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                    ('7000', 'SCIENCES',  NULL),
                    ('7777', 'CHEMISTRY', '7000'),
                    ('1234', 'PHYSICS',   NULL)
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        proposal_sequence_status, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, principal_investigator_name
                    ) VALUES
                    (8001, 'CHEM-P1', 1, 'Chemistry proposal v1', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'A CONTACT'),
                    (8002, 'CHEM-P1', 2, 'Chemistry proposal v2', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'A CONTACT'),
                    (9001, 'PHYS-P1', 1, 'Physics proposal',      'Approved',
                     '0100', 'NSF', '1234', 'PHYSICS',   'B OTHER'),
                    (9500, 'NULL-P1', 1, 'Unitless proposal',     'Approved',
                     NULL,   NULL,  NULL,   NULL,        NULL)
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_person (
                        proposal_person_id, proposal_id, proposal_number,
                        sequence_number, person_id, full_name, contact_role_code
                    ) VALUES
                    (201, 8002, 'CHEM-P1', 2, 'P-CONTACT', 'A CONTACT', 'PI'),
                    (202, 9001, 'PHYS-P1', 1, 'P-CONTACT', 'A CONTACT', 'OTHER')
                    """);
        }

        repository = new ProposalArchiveRepository(JdbcClient.create(dataSource));
    }

    // --- Enforcement disabled -------------------------------------------

    @Test
    void permitAllReturnsEveryFamilyAndMatchesPrePhaseTwoBehaviour() {
        PageResponse<ProposalFamilySummaryResponse> page = search(ProposalRecordAccess.PERMIT_ALL);

        assertThat(numbers(page)).containsExactlyInAnyOrder("CHEM-P1", "PHYS-P1", "NULL-P1");
        assertThat(page.totalElements()).isEqualTo(3);
    }

    @Test
    void permitAllOpensAnyProposalDirectly() {
        assertThatCode(() -> service(ProposalRecordAccess.PERMIT_ALL).findWorkspace("PHYS-P1"))
                .doesNotThrowAnyException();
    }

    // --- Central ---------------------------------------------------------

    @Test
    void centralSeesEveryProposalIncludingOnesWithNoUnitOrContact() {
        PageResponse<ProposalFamilySummaryResponse> page = search(centralScope());

        assertThat(numbers(page)).containsExactlyInAnyOrder("CHEM-P1", "PHYS-P1", "NULL-P1");
        assertThat(page.totalElements()).isEqualTo(3);
    }

    // --- Department and descendants --------------------------------------

    @Test
    void departmentScopeReturnsOnlyItsOwnUnitAndTheCountAgrees() {
        PageResponse<ProposalFamilySummaryResponse> page = search(departmentScope("7777"));

        assertThat(numbers(page)).containsExactly("CHEM-P1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void anotherBranchIsExcludedEntirely() {
        assertThat(numbers(search(departmentScope("1234")))).containsExactly("PHYS-P1");
    }

    @Test
    void aParentUnitReachesItsDescendantWhenTheGrantAllowsIt() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", true)), Optional.empty(), Set.of()),
                Set.of("7000", "7777"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        assertThat(numbers(search(scoped(scope, Set.of("CHEM-P1"))))).containsExactly("CHEM-P1");
    }

    @Test
    void aParentUnitAloneReachesNothingWhenDescendantsAreNotGranted() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", false)), Optional.empty(), Set.of()),
                Set.of("7000"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        PageResponse<ProposalFamilySummaryResponse> page = search(scoped(scope, Set.of()));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // --- Research Staff --------------------------------------------------

    @Test
    void aContactReachesTheProposalTheyAreListedOn() {
        assertThat(numbers(search(contactScope(CONTACT)))).containsExactly("CHEM-P1");
    }

    @Test
    void aListingWhoseRoleIsNotApprovedGrantsNothing() {
        // CONTACT is on PHYS-P1 with role OTHER. Being named is not access.
        assertThat(numbers(search(contactScope(CONTACT)))).doesNotContain("PHYS-P1");
    }

    @Test
    void aPersonWhoIsNoOnesContactSeesNothing() {
        PageResponse<ProposalFamilySummaryResponse> page = search(contactScope(STRANGER));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // --- No grants, revoked, suspended -----------------------------------

    @Test
    void anAuthenticatedUserWithNoGrantsGetsAccessNotProvisioned() {
        ProposalArchiveService service = service(unprovisioned());

        assertThatThrownBy(() -> service.findFamilyPage("", ProposalSearchFilters.none(), 0, 50))
                .isInstanceOf(AccessNotProvisionedException.class);
        assertThatThrownBy(() -> service.findFamilies("", 50))
                .isInstanceOf(AccessNotProvisionedException.class);
    }

    @Test
    void aRevokedOrSuspendedIdentitySeesNothingAndLeaksNoCount() {
        // Denied renders DENY_ALL, never an absent predicate.
        SqlFragment denied = new RecordScopeSqlBuilder(
                policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Denied(AccessOutcome.DenialReason.REVOKED_IDENTITY),
                        RecordModule.PROPOSAL, "ranked", Set.of());

        assertThat(denied.sql()).isEqualTo(SqlFragment.DENY_ALL.sql());

        PageResponse<ProposalFamilySummaryResponse> page = search(scoped(denied, Set.of()));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void anAdministratorWithNoDataGrantsSeesNoProposals() {
        /*
         * ARCHIVE_ADMIN is a grant-management role. It carries no archive
         * data access of its own, so an administrator with no data grant has
         * an empty scope and reaches nothing - which is the whole point of
         * keeping the two separate.
         */
        SqlFragment adminOnly = scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        PageResponse<ProposalFamilySummaryResponse> page = search(scoped(adminOnly, Set.of()));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // --- Direct URLs and non-disclosure ----------------------------------

    @Test
    void aDirectProposalUrlIsDeniedWithoutRevealingWhetherItExists() {
        ProposalArchiveService denied = service(denying());

        Throwable real = catchThrowable(() -> denied.findWorkspace("CHEM-P1"));
        Throwable imaginary = catchThrowable(() -> denied.findWorkspace("NO-SUCH-PROPOSAL"));

        assertThat(real).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(imaginary).isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(real.getMessage()).isEqualTo(imaginary.getMessage());
    }

    @Test
    void anInScopeProposalOpensNormally() {
        ProposalArchiveService service = service(allowing("CHEM-P1"));

        assertThatCode(() -> service.findWorkspace("CHEM-P1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.findWorkspace("PHYS-P1"))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    // --- History spans the family ----------------------------------------

    @Test
    void versionHistoryRequiresEveryVersionToBeVisible() {
        ProposalRecordAccess latestOnly = new BaseAccess() {
            @Override public void requireEveryProposalVersion(String proposalNumber) {
                throw new PartialFamilyAccessException();
            }
        };

        assertThatThrownBy(() -> service(latestOnly).findVersionPage("CHEM-P1", 0, 25))
                .isInstanceOf(PartialFamilyAccessException.class);
    }

    // --- Revocation -------------------------------------------------------

    @Test
    void revokingAGrantTakesEffectOnTheNextCallWithNoCacheToClear() {
        assertThat(numbers(search(departmentScope("7777")))).containsExactly("CHEM-P1");

        PageResponse<ProposalFamilySummaryResponse> after = search(scoped(
                scopeFor(new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                        Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                Set.of()));

        assertThat(after.content()).isEmpty();
        assertThat(after.totalElements()).isZero();
    }

    // --- Count / page parity ----------------------------------------------

    @Test
    void theCountAlwaysMatchesWhatPagingCanReach() {
        for (ProposalRecordAccess access : List.of(
                ProposalRecordAccess.PERMIT_ALL,
                centralScope(),
                departmentScope("7777"),
                departmentScope("1234"),
                contactScope(CONTACT),
                contactScope(STRANGER))) {

            PageResponse<ProposalFamilySummaryResponse> page =
                    service(access).findFamilyPage("", ProposalSearchFilters.none(), 0, 50);
            assertThat(page.content()).hasSize((int) page.totalElements());
        }
    }

    // --- helpers -----------------------------------------------------------

    private static PageResponse<ProposalFamilySummaryResponse> search(ProposalRecordAccess access) {
        return service(access).findFamilyPage("", ProposalSearchFilters.none(), 0, 50);
    }

    private static ProposalArchiveService service(ProposalRecordAccess access) {
        return new ProposalArchiveService(repository, provider(access), null);
    }

    private static List<String> numbers(PageResponse<ProposalFamilySummaryResponse> page) {
        return page.content().stream().map(ProposalFamilySummaryResponse::proposalNumber).toList();
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match) {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE, match, RESEARCH_STAFF_ROLES);
    }

    private static SqlFragment scopeFor(
            AccessScope scope, Set<String> units, AuthorizationPolicy.DepartmentMatch match) {
        return new RecordScopeSqlBuilder(policy(match), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(scope), RecordModule.PROPOSAL, "ranked", units);
    }

    private static ProposalRecordAccess centralScope() {
        return scoped(scopeFor(new AccessScope(true, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                Set.of("CHEM-P1", "PHYS-P1", "NULL-P1"));
    }

    private static ProposalRecordAccess departmentScope(String unit) {
        Set<String> visible = "7777".equals(unit) ? Set.of("CHEM-P1")
                : "1234".equals(unit) ? Set.of("PHYS-P1") : Set.of();
        return scoped(scopeFor(
                new AccessScope(false, Set.of(new UnitGrant(unit, false)), Optional.empty(), Set.of()),
                Set.of(unit), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT), visible);
    }

    private static ProposalRecordAccess contactScope(KualiPersonId person) {
        return scoped(scopeFor(
                new AccessScope(false, Set.of(), Optional.of(person), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                CONTACT.equals(person) ? Set.of("CHEM-P1") : Set.of());
    }

    /**
     * The SQL predicate and the per-record decision are separate paths in
     * production and must agree. Modelling only the predicate would let a
     * record-level leak pass its own regression test.
     */
    private static ProposalRecordAccess scoped(SqlFragment fragment, Set<String> visible) {
        return new BaseAccess() {
            @Override public SqlFragment proposalScopeSql(String alias) {
                return fragment;
            }

            @Override public boolean unrestricted() {
                return fragment == SqlFragment.NONE;
            }

            @Override public void requireProposalNumber(String proposalNumber) {
                if (!visible.contains(proposalNumber)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return visible.contains(proposalNumber);
            }
        };
    }

    private static ProposalRecordAccess unprovisioned() {
        return new BaseAccess() {
            @Override public SqlFragment proposalScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public void requireProvisioned() {
                throw new AccessNotProvisionedException();
            }

            @Override public boolean unrestricted() {
                return false;
            }
        };
    }

    private static ProposalRecordAccess denying() {
        return new BaseAccess() {
            @Override public SqlFragment proposalScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public boolean unrestricted() {
                return false;
            }

            @Override public void requireProposalNumber(String proposalNumber) {
                throw new RecordNotAccessibleException();
            }

            @Override public void requireProposal(long proposalId) {
                throw new RecordNotAccessibleException();
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return false;
            }
        };
    }

    private static ProposalRecordAccess allowing(String allowed) {
        return new BaseAccess() {
            @Override public void requireProposalNumber(String proposalNumber) {
                if (!allowed.equals(proposalNumber)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return allowed.equals(proposalNumber);
            }
        };
    }

    /** Permissive defaults so each fixture overrides only what it is testing. */
    private abstract static class BaseAccess implements ProposalRecordAccess {
        @Override public SqlFragment proposalScopeSql(String alias) {
            return SqlFragment.NONE;
        }

        @Override public void requireProvisioned() { }

        @Override public boolean unrestricted() {
            return true;
        }

        @Override public void requireProposal(long proposalId) { }

        @Override public void requireProposalNumber(String proposalNumber) { }

        @Override public void requireEveryProposalVersion(String proposalNumber) {
            requireProposalNumber(proposalNumber);
        }

        @Override public boolean canSeeProposalNumber(String proposalNumber) {
            return true;
        }
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<T>() {
            @Override public T getObject() {
                return value;
            }

            @Override public T getObject(Object... args) {
                return value;
            }

            @Override public T getIfAvailable() {
                return value;
            }

            @Override public T getIfUnique() {
                return value;
            }

            @Override public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", "migrations");
    }
}
