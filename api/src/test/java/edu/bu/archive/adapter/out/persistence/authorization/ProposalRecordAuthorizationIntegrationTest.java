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
    private static JdbcClient jdbcClient;

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
                        lead_unit_number, lead_unit_name, principal_investigator_name,
                        document_number
                    ) VALUES
                    (8001, 'CHEM-P1', 1, 'Chemistry proposal v1', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'A CONTACT', 'DOC-CHEM-1'),
                    (8002, 'CHEM-P1', 2, 'Chemistry proposal v2', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'A CONTACT', 'DOC-CHEM-2'),
                    (9001, 'PHYS-P1', 1, 'Physics proposal',      'Approved',
                     '0100', 'NSF', '1234', 'PHYSICS',   'B OTHER',   'DOC-PHYS-1'),
                    (9500, 'NULL-P1', 1, 'Unitless proposal',     'Approved',
                     NULL,   NULL,  NULL,   NULL,        NULL,        'DOC-NULL-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.irb_protocol_version (
                        protocol_id, protocol_base, protocol_number, document_number
                    ) VALUES (7001, 'IRB-1', 'IRB-1-001', 'DOC-IRB-1')
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

        jdbcClient = JdbcClient.create(dataSource);
        repository = new ProposalArchiveRepository(jdbcClient);
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

    // --- Dashboard counts (D2) ------------------------------------------

    /*
     * Three figures come from archive.proposal_version and must move
     * together: the family count, the version-row count, and the PROPOSAL
     * branch of the document tally. Scoping a subset is worse than scoping
     * none - the dashboard would then contradict itself and the Proposal
     * search the same user can run.
     *
     * Fixture: CHEM-P1 (2 versions, unit 7777 under 7000, CONTACT is PI on
     * the later one), PHYS-P1 (1 version, unit 1234), NULL-P1 (1 version, no
     * unit, no contact). Four distinct proposal document numbers.
     *
     * Only the Proposal port is restricted in these tests, so any change in
     * the numbers can only come from the Proposal scope.
     */

    @Test
    void dashboardWithEnforcementOffCountsEveryProposalAsBefore() {
        var counts = dashboard(ProposalRecordAccess.PERMIT_ALL);

        assertThat(counts.proposals()).isEqualTo(3);
        assertThat(counts.proposalHistoryRecords()).isEqualTo(4);
        assertThat(counts.documents())
                .as("four PROPOSAL documents plus the one IRB document, which is "
                        + "still counted while enforcement is off")
                .isEqualTo(5);
    }

    @Test
    void dashboardForCentralCountsEveryProposal() {
        var counts = dashboard(dashboardScope(
                new AccessScope(true, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("CHEM-P1", "PHYS-P1", "NULL-P1")));

        assertThat(counts.proposals()).isEqualTo(3);
        assertThat(counts.proposalHistoryRecords()).isEqualTo(4);
        assertThat(counts.documents()).isEqualTo(5);
    }

    @Test
    void dashboardForADepartmentCountsOnlyItsOwnProposals() {
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(new UnitGrant("7777", false)), Optional.empty(), Set.of()),
                Set.of("7777"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("CHEM-P1")));

        assertThat(counts.proposals())
                .as("CHEM-P1 only; PHYS-P1 and NULL-P1 are out of scope")
                .isEqualTo(1);
        assertThat(counts.proposalHistoryRecords())
                .as("both CHEM-P1 versions, and neither of the other families")
                .isEqualTo(2);
        assertThat(counts.documents())
                .as("DOC-CHEM-1 and DOC-CHEM-2, plus the IRB document that "
                        + "enforcement-off still counts")
                .isEqualTo(3);
    }

    @Test
    void dashboardForAParentUnitWithDescendantsReachesTheChildUnit() {
        // 7000 holds no proposals itself; CHEM-P1 sits on 7777 beneath it.
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(new UnitGrant("7000", true)), Optional.empty(), Set.of()),
                Set.of("7000", "7777"), AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS,
                Set.of("CHEM-P1")));

        assertThat(counts.proposals()).isEqualTo(1);
        assertThat(counts.proposalHistoryRecords()).isEqualTo(2);
        assertThat(counts.documents()).isEqualTo(3);   // + the IRB document
    }

    @Test
    void dashboardForAParentUnitWithoutDescendantsReachesNothing() {
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(new UnitGrant("7000", false)), Optional.empty(), Set.of()),
                Set.of("7000"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of()));

        assertThat(counts.proposals()).isZero();
        assertThat(counts.proposalHistoryRecords()).isZero();
        assertThat(counts.documents())
                .as("no Proposal documents; the IRB one remains while enforcement is off")
                .isEqualTo(1);
    }

    @Test
    void dashboardForResearchStaffCountsTheFamilyTheyAreListedOn() {
        // CONTACT is PI on CHEM-P1 version 2 only, and OTHER on PHYS-P1.
        // The policy is FAMILY_WIDE, so the whole CHEM-P1 family counts and
        // PHYS-P1 does not: OTHER is not an approved research-staff role.
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(), Optional.of(CONTACT), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("CHEM-P1")));

        assertThat(counts.proposals()).isEqualTo(1);
        assertThat(counts.proposalHistoryRecords()).isEqualTo(2);
        assertThat(counts.documents()).isEqualTo(3);   // + the IRB document
    }

    @Test
    void dashboardForAnIdentityWithNoGrantCountsNoProposals() {
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of()));

        assertThat(counts.proposals()).isZero();
        assertThat(counts.proposalHistoryRecords()).isZero();
        assertThat(counts.documents())
                .as("no Proposal documents; the IRB one remains while enforcement is off")
                .isEqualTo(1);
    }

    @Test
    void dashboardForARevokedOrSuspendedIdentityCountsNoProposals() {
        // Both states resolve to a denying scope before any SQL is built, so
        // they are indistinguishable here - which is the intent.
        var counts = dashboard(denying());

        assertThat(counts.proposals()).isZero();
        assertThat(counts.proposalHistoryRecords()).isZero();
        assertThat(counts.documents())
                .as("no Proposal documents; the IRB one remains while enforcement is off")
                .isEqualTo(1);
    }

    @Test
    void dashboardRefusesEntirelyForAnUnprovisionedIdentity() {
        /*
         * counts() asks requireProvisioned() once, through the Award port.
         * That is sufficient rather than lax: in production all four ports
         * are the SAME RecordAuthorizationService instance, so an identity
         * that is unprovisioned is unprovisioned for every module. This test
         * therefore has to withhold provisioning where production would -
         * passing only an unprovisioned Proposal port would prove nothing,
         * because nothing asks it.
         *
         * The point being recorded: an unprovisioned caller gets an explicit
         * problem, never a dashboard of zeroes that looks like an empty
         * archive.
         */
        edu.bu.archive.application.authorization.AwardRecordAccess unprovisionedAward =
                new edu.bu.archive.application.authorization.AwardRecordAccess() {
                    @Override public SqlFragment awardScopeSql(String alias) {
                        return SqlFragment.DENY_ALL;
                    }

                    @Override public void requireProvisioned() {
                        throw new AccessNotProvisionedException();
                    }

                    @Override public boolean unrestricted() {
                        return false;
                    }

                    @Override public void requireAward(long awardId) {
                        throw new RecordNotAccessibleException();
                    }

                    @Override public void requireAwardNumber(String awardNumber) {
                        throw new RecordNotAccessibleException();
                    }

                    @Override public void requireEveryAwardVersion(String awardNumber) {
                        throw new RecordNotAccessibleException();
                    }

                    @Override public boolean canSeeAwardNumber(String awardNumber) {
                        return false;
                    }
                };

        assertThatThrownBy(() -> new edu.bu.archive.application.service.DashboardService(
                jdbcClient,
                provider(unprovisionedAward),
                provider(edu.bu.archive.application.authorization.SubawardRecordAccess.PERMIT_ALL),
                provider(edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL),
                provider(unprovisioned()),
                null)
                .counts())
                .isInstanceOf(AccessNotProvisionedException.class);
    }

    @Test
    void allThreeProposalFiguresAgreeWithTheAuthorizedProposalSearch() {
        /*
         * The regression that matters. A count built from a different row set
         * than the list is a disclosure even when every individual record is
         * correctly gated: the number itself reveals how much exists.
         *
         * All three figures are held against what the SAME user can actually
         * retrieve through the Proposal services - the family page for the
         * family count, the version page of each visible family for the
         * version-row count and for the documents those versions carry. A
         * figure that agreed with the predicate but not with the services
         * would still be wrong.
         */
        for (String unit : List.of("7777", "1234")) {
            ProposalRecordAccess access = departmentScope(unit);
            Set<String> visible = "7777".equals(unit) ? Set.of("CHEM-P1") : Set.of("PHYS-P1");

            PageResponse<ProposalFamilySummaryResponse> families = search(access);

            long versionsTheUserCanRetrieve = 0;
            for (String number : numbers(families)) {
                versionsTheUserCanRetrieve +=
                        service(access).findVersionPage(number, 0, 50).totalElements();
            }

            var counts = dashboard(dashboardScope(
                    new AccessScope(false, Set.of(new UnitGrant(unit, false)), Optional.empty(), Set.of()),
                    Set.of(unit), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, visible));

            assertThat(counts.proposals())
                    .as("family count must equal what unit %s can list", unit)
                    .isEqualTo(families.totalElements());
            assertThat(counts.proposalHistoryRecords())
                    .as("version-row count must equal the versions unit %s can open", unit)
                    .isEqualTo(versionsTheUserCanRetrieve);
            assertThat(counts.documents())
                    .as("every fixture version carries one document number, plus the "
                            + "one IRB document enforcement-off still counts, for unit %s", unit)
                    .isEqualTo(versionsTheUserCanRetrieve + 1);
        }
    }

    @Test
    void anOutOfScopeProposalCannotRaiseAnyProposalFigure() {
        /*
         * Directly: the archive holds 3 families, 4 versions and 4 proposal
         * document numbers, but a 1234 user must see only their own in every
         * one of the three figures. If any figure were left unscoped it would
         * report the archive-wide number here.
         */
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(new UnitGrant("1234", false)), Optional.empty(), Set.of()),
                Set.of("1234"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("PHYS-P1")));

        assertThat(counts.proposals()).isEqualTo(1).isNotEqualTo(3);
        assertThat(counts.proposalHistoryRecords()).isEqualTo(1).isNotEqualTo(4);
        assertThat(counts.documents())
                .as("one Proposal document plus the IRB one, not the archive-wide 5")
                .isEqualTo(2).isNotEqualTo(5);
    }

    @Test
    void theProposalScopeDoesNotDisturbTheOtherModulesParameters() {
        /*
         * The Proposal predicate binds az_units, exactly like the Award one,
         * so both fragments in this single statement would fight over the
         * name without the prop_ prefix. The Award side is PERMIT_ALL here,
         * so a collision would surface as a bad-parameter failure rather than
         * a wrong number - either way this would not pass.
         */
        var counts = dashboard(dashboardScope(
                new AccessScope(false, Set.of(new UnitGrant("7777", false)), Optional.empty(), Set.of()),
                Set.of("7777"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("CHEM-P1")));

        assertThat(counts.awards()).isZero();
        assertThat(counts.subawards()).isZero();
        assertThat(counts.negotiations()).isZero();
        assertThat(counts.proposals()).isEqualTo(1);
    }

    // --- IRB exclusion from the Dashboard (D9) ---------------------------

    /*
     * IRB has no record rule and will not get one. Rather than scope it,
     * enforcement removes it from this shared surface entirely, exactly as
     * shared Global Search already does - for EVERY caller, Central
     * included. Two removals, because IRB reaches the response twice: its
     * own four figures, and a branch of the single documents tally.
     */

    @Test
    void irbFiguresAreOmittedNotZeroedWhenEnforcementIsOn() {
        var counts = dashboardWithEnforcement(true, permissiveProposal());

        assertThat(counts.irb())
                .as("null, so the field leaves the JSON; 0 would assert the "
                        + "archive holds no protocols, which is false")
                .isNull();
        assertThat(counts.submissions()).isNull();
        assertThat(counts.fundingRecords()).isNull();
        assertThat(counts.timelineEvents()).isNull();
    }

    @Test
    void omittedIrbFiguresAreAbsentFromTheSerialisedResponse() throws Exception {
        // The contract the UI and any other client actually sees.
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(dashboardWithEnforcement(true, permissiveProposal()));

        assertThat(json)
                .doesNotContain("\"irb\"")
                .doesNotContain("\"submissions\"")
                .doesNotContain("\"fundingRecords\"")
                .doesNotContain("\"timelineEvents\"")
                .as("the non-IRB figures must still be present")
                .contains("\"proposals\"")
                .contains("\"documents\"");
    }

    @Test
    void theDocumentTallyCarriesNoIrbSubtotalWhenEnforcementIsOn() {
        /*
         * The leak that is easy to miss: documents is one number over five
         * modules. Scoping the four figures while leaving the IRB branch in
         * the union would keep an IRB subtotal inside a number the caller
         * can see and cannot reconcile.
         */
        long withIrb = dashboardWithEnforcement(false, permissiveProposal()).documents();
        long withoutIrb = dashboardWithEnforcement(true, permissiveProposal()).documents();

        assertThat(withIrb)
                .as("the fixture has 4 proposal documents and 1 IRB document")
                .isEqualTo(5);
        assertThat(withoutIrb)
                .as("the IRB document must not be counted once enforcement is on")
                .isEqualTo(4);
    }

    @Test
    void irbIsExcludedForCentralToo() {
        // Central is not an exception. The check reads the enforcement flag,
        // never unrestricted(), so a Central caller is treated identically.
        var counts = dashboardWithEnforcement(true, dashboardScope(
                new AccessScope(true, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("CHEM-P1", "PHYS-P1", "NULL-P1")));

        assertThat(counts.irb()).isNull();
        assertThat(counts.documents())
                .as("Central still sees every Proposal document, and no IRB one")
                .isEqualTo(4);
        assertThat(counts.proposals()).isEqualTo(3);
    }

    @Test
    void irbIsExcludedForEveryKindOfGrantEqually() {
        record Case(String name, ProposalRecordAccess access) { }
        List<Case> cases = List.of(
                new Case("unit", dashboardScope(
                        new AccessScope(false, Set.of(new UnitGrant("7777", false)),
                                Optional.empty(), Set.of()),
                        Set.of("7777"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                        Set.of("CHEM-P1"))),
                new Case("descendants", dashboardScope(
                        new AccessScope(false, Set.of(new UnitGrant("7000", true)),
                                Optional.empty(), Set.of()),
                        Set.of("7000", "7777"),
                        AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS,
                        Set.of("CHEM-P1"))),
                new Case("research staff", dashboardScope(
                        new AccessScope(false, Set.of(), Optional.of(CONTACT), Set.of()),
                        Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                        Set.of("CHEM-P1"))),
                new Case("no grant", dashboardScope(
                        new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                        Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, Set.of())),
                new Case("denied", denying()));

        for (Case each : cases) {
            var counts = dashboardWithEnforcement(true, each.access());
            assertThat(counts.irb()).as("%s: irb", each.name()).isNull();
            assertThat(counts.submissions()).as("%s: submissions", each.name()).isNull();
            assertThat(counts.fundingRecords()).as("%s: fundingRecords", each.name()).isNull();
            assertThat(counts.timelineEvents()).as("%s: timelineEvents", each.name()).isNull();
        }
    }

    @Test
    void enforcementOffLeavesEveryIrbFigureExactlyAsBefore() {
        var counts = dashboardWithEnforcement(false, permissiveProposal());

        // The four IRB count tables are empty in this fixture, so these are
        // 0 - and 0 is exactly the value that must NOT appear once
        // enforcement is on. "Present and zero" and "absent" are different
        // statements, and this pair is what pins the difference.
        assertThat(counts.irb()).isEqualTo(0L);
        assertThat(counts.submissions()).isEqualTo(0L);
        assertThat(counts.fundingRecords()).isEqualTo(0L);
        assertThat(counts.timelineEvents()).isEqualTo(0L);
        assertThat(counts.documents()).isEqualTo(5);
    }

    @Test
    void enforcementOffStillSerialisesTheIrbFields() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(dashboardWithEnforcement(false, permissiveProposal()));

        assertThat(json)
                .contains("\"irb\"")
                .contains("\"submissions\"")
                .contains("\"fundingRecords\"")
                .contains("\"timelineEvents\"");
    }

    @Test
    void excludingIrbDoesNotDisturbAnyNonIrbFigure() {
        var off = dashboardWithEnforcement(false, permissiveProposal());
        var on = dashboardWithEnforcement(true, permissiveProposal());

        assertThat(on.awards()).isEqualTo(off.awards());
        assertThat(on.awardHistoryRecords()).isEqualTo(off.awardHistoryRecords());
        assertThat(on.proposals()).isEqualTo(off.proposals());
        assertThat(on.proposalHistoryRecords()).isEqualTo(off.proposalHistoryRecords());
        assertThat(on.negotiations()).isEqualTo(off.negotiations());
        assertThat(on.subawards()).isEqualTo(off.subawards());
        assertThat(on.documents())
                .as("documents differs by exactly the one IRB document, nothing else")
                .isEqualTo(off.documents() - 1);
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

    /** The same predicate, rendered for a caller-supplied alias. */
    private static SqlFragment scopeFor(
            AccessScope scope, Set<String> units,
            AuthorizationPolicy.DepartmentMatch match, String alias) {
        return new RecordScopeSqlBuilder(policy(match), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(scope), RecordModule.PROPOSAL, alias, units);
    }

    /**
     * The real DashboardService with only the Proposal port restricted, so a
     * difference in the Proposal figures can only come from the Proposal
     * scope. Award, Subaward and Negotiation are PERMIT_ALL.
     */
    private static edu.bu.archive.adapter.in.web.dto.DashboardDto dashboard(
            ProposalRecordAccess access) {
        return new edu.bu.archive.application.service.DashboardService(
                jdbcClient,
                provider(edu.bu.archive.application.authorization.AwardRecordAccess.PERMIT_ALL),
                provider(edu.bu.archive.application.authorization.SubawardRecordAccess.PERMIT_ALL),
                provider(edu.bu.archive.application.authorization.NegotiationRecordAccess.PERMIT_ALL),
                provider(access),
                null)
                .counts();
    }

    /**
     * The dashboard with the enforcement flag set explicitly, so the D9
     * exclusion can be exercised in both directions. Everything but the
     * Proposal port is PERMIT_ALL.
     */
    private static edu.bu.archive.adapter.in.web.dto.DashboardDto dashboardWithEnforcement(
            boolean enforcementEnabled, ProposalRecordAccess access) {
        var properties = new edu.bu.archive.application.authorization.AuthorizationProperties();
        properties.setEnforcementEnabled(enforcementEnabled);
        /*
         * NOT the PERMIT_ALL constants. requireEnforceableWiring refuses to
         * start when enforcement is on and a port is still the permissive
         * default, which is exactly the guard working - so these stand-ins
         * are distinct objects that happen to impose no predicate, leaving
         * the Proposal port the only thing under test.
         */
        var award = new edu.bu.archive.application.authorization.AwardRecordAccess() {
            @Override public SqlFragment awardScopeSql(String alias) {
                return SqlFragment.NONE;
            }

            @Override public void requireProvisioned() { }

            @Override public boolean unrestricted() {
                return true;
            }

            @Override public void requireAward(long awardId) { }

            @Override public void requireAwardNumber(String awardNumber) { }

            @Override public void requireEveryAwardVersion(String awardNumber) { }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return true;
            }
        };
        var subaward = new edu.bu.archive.application.authorization.SubawardRecordAccess() {
            @Override public SqlFragment subawardScopeSql(String alias) {
                return SqlFragment.NONE;
            }

            @Override public void requireProvisioned() { }

            @Override public boolean unrestricted() {
                return true;
            }

            @Override public void requireSubaward(long subawardId) { }

            @Override public boolean canSeeSubaward(long subawardId) {
                return true;
            }

            @Override public boolean canSeeAward(long awardId) {
                return true;
            }
        };
        var negotiation = new edu.bu.archive.application.authorization.NegotiationRecordAccess() {
            @Override public SqlFragment negotiationScopeSql(String alias) {
                return SqlFragment.NONE;
            }

            @Override public void requireProvisioned() { }

            @Override public boolean unrestricted() {
                return true;
            }

            @Override public void requireNegotiation(long negotiationId) { }

            @Override public boolean canSeeNegotiation(long negotiationId) {
                return true;
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return true;
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return true;
            }
        };

        return new edu.bu.archive.application.service.DashboardService(
                jdbcClient,
                provider(award),
                provider(subaward),
                provider(negotiation),
                provider(access),
                provider(properties))
                .counts();
    }

    /**
     * Permissive, but NOT the PERMIT_ALL constant - the startup guard
     * refuses that combination once enforcement is on, by design.
     */
    private static ProposalRecordAccess permissiveProposal() {
        return new BaseAccess() { };
    }

    /** A Proposal scope rendered for the dashboard's own alias. */
    private static ProposalRecordAccess dashboardScope(
            AccessScope scope, Set<String> units,
            AuthorizationPolicy.DepartmentMatch match, Set<String> visible) {
        return scoped(scopeFor(scope, units, match, "pv"), visible);
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
