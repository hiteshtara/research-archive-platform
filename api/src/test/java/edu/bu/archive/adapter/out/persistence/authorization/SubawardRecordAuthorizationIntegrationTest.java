package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardFundingResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardPageResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardVersionSummaryResponse;
import edu.bu.archive.adapter.out.persistence.SubawardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.SubawardSemanticSummaryRow;
import edu.bu.archive.application.authorization.AccessNotProvisionedException;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScope;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.IdentityAccessDeniedException;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.RecordFactsRepository;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.application.authorization.SubawardRecordAccess;
import edu.bu.archive.application.authorization.UnitGrant;
import edu.bu.archive.application.subaward.SubawardArchiveService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subaward record-level authorization, against a real PostgreSQL with every
 * committed migration applied.
 *
 * <p>The approved rule (2026-10-26) is inheritance: <em>a user may access a
 * Subaward when they are authorized to view at least ONE of its funding
 * Awards</em>. ANY-match, not ALL-match. So there is no new rule to test -
 * there is an Award rule evaluated through {@code archive.subaward_funding},
 * and what has to be proven is that the two evaluations of it agree and that
 * neither one leaks on the way.
 *
 * <p>Two things here are real, not modelled, and that is the point of using
 * a container:
 * <ul>
 *   <li>the SQL predicate comes from the production
 *       {@link RecordScopeSqlBuilder} for {@link RecordModule#SUBAWARD} and
 *       is executed as valid SQL against the real schema; and</li>
 *   <li>the per-record decision resolves funding through the production
 *       {@link JdbcRecordFactsRepository#subawardFundingAwardIds}, exactly as
 *       {@code RecordAuthorizationService.canSeeSubaward} does.</li>
 * </ul>
 * A fixture that modelled only the predicate would let a record-level leak
 * pass its own regression test - which has happened once in this project
 * already.
 *
 * <p>Enforcement is OFF by default everywhere; these drive the scope through
 * the port, which is how production behaves once the flag is on. The
 * flag-off baseline is the first test and must stay identical to
 * pre-Phase-2 behaviour.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class SubawardRecordAuthorizationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final Set<String> RESEARCH_STAFF_ROLES = Set.of("PI", "MPI", "COI");

    private static final KualiPersonId FARRER = new KualiPersonId("P-FARRER");
    private static final KualiPersonId STRANGER = new KualiPersonId("P-STRANGER");

    // Award version ids, as the funding rows reference them.
    private static final long CHEM_V1 = 5001L;
    private static final long CHEM_V2 = 5002L;
    private static final long PHYS_V1 = 6001L;
    private static final long NULL_V1 = 7001L;
    private static final long NO_SUCH_AWARD = 999_999L;

    // Subaward version ids.
    private static final long SUB_CHEM = 9001L;
    private static final long SUB_BOTH = 9002L;
    private static final long SUB_NONE = 9003L;
    private static final long SUB_BROKEN = 9004L;
    private static final long SUB_OTHERS = 9005L;
    private static final long SUB_FAM_HISTORY = 9006L;
    private static final long SUB_FAM_ACTIVE = 9007L;
    private static final long SUB_CHEM_V1_ONLY = 9008L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static SubawardArchiveRepository repository;
    private static RecordFactsRepository facts;
    private static JdbcClient jdbcClient;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            SubawardRecordAuthorizationIntegrationTest::migrationVersion))
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

            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                    ('7000', 'SCIENCES',  NULL),
                    ('7777', 'CHEMISTRY', '7000'),
                    ('1234', 'PHYSICS',   NULL)
                    """);

            /*
             * CHEM-00001 has two versions in unit 7777; FARRER is PI on the
             * CURRENT one only, which is what makes SUB-CHEM-V1 below a real
             * test of the family wrap rather than a restatement.
             * PHYS-00001 is a different branch. NULL-00001 has no unit and
             * is reachable only by Central.
             */
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
                    (6001, 'PHYS-00001', 1, 'Physics one',      'Active',
                     '0100', 'NSF', '1234', 'PHYSICS',   DATE '2020-06-01', TRUE,  'DOC-PHYS-1'),
                    (7001, 'NULL-00001', 1, 'Unitless one',     'Active',
                     NULL,   NULL,  NULL,   NULL,        NULL,              TRUE,  'DOC-NULL-1')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 5002, 'CHEM-00001', 2, 'LINDSAY A FARRER', 'PI',    'P-FARRER'),
                    (102, 6001, 'PHYS-00001', 1, 'LINDSAY A FARRER', 'OTHER', 'P-FARRER')
                    """);

            /*
             * One Subaward per funding shape the approved rule names, so a
             * single scoped query answers several of the Phase 3 scenarios
             * at once.
             *
             *   SUB-CHEM     one funding Award (CHEM current)
             *   SUB-BOTH     two funding Awards, CHEM and PHYS
             *   SUB-NONE     no funding rows at all
             *   SUB-BROKEN   one funding row whose award_id resolves to nothing
             *   SUB-OTHERS   two funding Awards, PHYS and the unitless one
             *   SUB-FAM      two VERSIONS whose funding differs between them
             *   SUB-CHEMV1   funded by the NON-current CHEM version
             */
            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, subaward_code, sequence_number, document_number,
                        title, status_description, subaward_sequence_status,
                        organization_id, account_number, requisitioner_unit
                    ) VALUES
                    (9001, 'SUB-CHEM',   1, 'SDOC-9001', 'Chem subaward',        'Active', 'ACTIVE',  'ORG1', 'ACC1', '7777'),
                    (9002, 'SUB-BOTH',   1, 'SDOC-9002', 'Two funders subaward', 'Active', 'ACTIVE',  'ORG1', 'ACC2', '7777'),
                    (9003, 'SUB-NONE',   1, 'SDOC-9003', 'Unfunded subaward',    'Active', 'ACTIVE',  'ORG1', 'ACC3', '7777'),
                    (9004, 'SUB-BROKEN', 1, 'SDOC-9004', 'Dangling subaward',    'Active', 'ACTIVE',  'ORG1', 'ACC4', '7777'),
                    (9005, 'SUB-OTHERS', 1, 'SDOC-9005', 'Other funders',        'Active', 'ACTIVE',  'ORG2', 'ACC5', '1234'),
                    (9006, 'SUB-FAM',    1, 'SDOC-9006', 'Family v1',            'Active', 'HISTORY', 'ORG1', 'ACC6', '7777'),
                    (9007, 'SUB-FAM',    2, 'SDOC-9007', 'Family v2',            'Active', 'ACTIVE',  'ORG1', 'ACC6', '7777'),
                    (9008, 'SUB-CHEMV1', 1, 'SDOC-9008', 'Funded by old version','Active', 'ACTIVE',  'ORG1', 'ACC7', '7777')
                    """);

            /*
             * award_number is the denormalised business identifier the
             * funding card displays (V067); award_id is the raw version id
             * the rule is decided on. SUB-BROKEN deliberately carries both a
             * nonexistent award_id and a nonexistent award_number, which is
             * what a dangling reference looks like in the real data.
             */
            statement.execute("""
                    INSERT INTO archive.subaward_funding (
                        subaward_funding_source_id, subaward_id, subaward_code,
                        sequence_number, award_id, award_number
                    ) VALUES
                    (301, 9001, 'SUB-CHEM',   1, 5002,   'CHEM-00001'),
                    (302, 9002, 'SUB-BOTH',   1, 5002,   'CHEM-00001'),
                    (303, 9002, 'SUB-BOTH',   1, 6001,   'PHYS-00001'),
                    (304, 9004, 'SUB-BROKEN', 1, 999999, 'GONE-00001'),
                    (305, 9005, 'SUB-OTHERS', 1, 6001,   'PHYS-00001'),
                    (306, 9005, 'SUB-OTHERS', 1, 7001,   'NULL-00001'),
                    (307, 9006, 'SUB-FAM',    1, 5002,   'CHEM-00001'),
                    (308, 9007, 'SUB-FAM',    2, 6001,   'PHYS-00001'),
                    (309, 9008, 'SUB-CHEMV1', 1, 5001,   'CHEM-00001')
                    """);
        }

        jdbcClient = JdbcClient.create(dataSource);
        repository = new SubawardArchiveRepository(jdbcClient);
        facts = new JdbcRecordFactsRepository(jdbcClient, null);
    }

    // --- 19. Enforcement disabled ---------------------------------------

    @Test
    void scenario19_permitAllReturnsEverySubawardAndMatchesPrePhaseTwoBehaviour() {
        SubawardPageResponse page = search(SubawardRecordAccess.PERMIT_ALL);

        assertThat(codes(page)).containsExactlyInAnyOrder(
                "SUB-CHEM", "SUB-BOTH", "SUB-NONE", "SUB-BROKEN",
                "SUB-OTHERS", "SUB-FAM", "SUB-FAM", "SUB-CHEMV1");
        assertThat(page.totalElements()).isEqualTo(8);
    }

    @Test
    void scenario19_permitAllOpensAnySubawardDirectlyIncludingUnfundedOnes() {
        SubawardArchiveService service = service(SubawardRecordAccess.PERMIT_ALL);

        assertThatCode(() -> service.findWorkspace(SUB_NONE)).doesNotThrowAnyException();
        assertThatCode(() -> service.findWorkspace(SUB_BROKEN)).doesNotThrowAnyException();
        assertThatCode(() -> service.findWorkspace(SUB_OTHERS)).doesNotThrowAnyException();
    }

    @Test
    void scenario19_permitAllKeepsTheWholeFundingListIncludingUnresolvableRows() {
        // The broken row is real archived data and must keep showing when
        // nobody is being scoped. Only a SCOPED caller has anything denied.
        var view = service(SubawardRecordAccess.PERMIT_ALL).findFunding(SUB_BROKEN);

        assertThat(view.funding()).hasSize(1);
        assertThat(view.funding().getFirst().exactLinkedAwardId()).isEqualTo(NO_SUCH_AWARD);
        // Nothing was withheld, so the UI keeps its exact count heading.
        assertThat(view.restricted()).isFalse();
    }

    // --- 1-7. The funding shapes the rule names -------------------------

    @Test
    void scenario1_oneFundingAwardAuthorized() {
        assertThat(codes(search(departmentScope("7777")))).contains("SUB-CHEM");
        assertThatCode(() -> service(departmentScope("7777")).findWorkspace(SUB_CHEM))
                .doesNotThrowAnyException();
    }

    @Test
    void scenario2_oneFundingAwardUnauthorized() {
        // SUB-CHEM is funded only by a 7777 Award; a 1234 caller sees nothing.
        assertThat(codes(search(departmentScope("1234")))).doesNotContain("SUB-CHEM");
        assertThatThrownBy(() -> service(departmentScope("1234")).findWorkspace(SUB_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void scenario3_twoFundingAwardsOneAuthorizedIsEnough() {
        // ANY-match, from both directions: 7777 reaches SUB-BOTH through
        // CHEM, 1234 reaches the same Subaward through PHYS.
        assertThat(codes(search(departmentScope("7777")))).contains("SUB-BOTH");
        assertThat(codes(search(departmentScope("1234")))).contains("SUB-BOTH");
        assertThatCode(() -> service(departmentScope("1234")).findWorkspace(SUB_BOTH))
                .doesNotThrowAnyException();
    }

    @Test
    void scenario4_twoFundingAwardsNeitherAuthorized() {
        // SUB-OTHERS is funded by PHYS and the unitless Award; a 7777 caller
        // holds neither.
        assertThat(codes(search(departmentScope("7777")))).doesNotContain("SUB-OTHERS");
        assertThatThrownBy(() -> service(departmentScope("7777")).findWorkspace(SUB_OTHERS))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void scenario5_twoFundingAwardsBothAuthorizedIsStillOneRow() {
        // EXISTS stops at the first match, so a Subaward whose funding
        // Awards are ALL authorized must not appear twice on the page - the
        // mistake a plain JOIN would make.
        SubawardPageResponse page = search(departmentScope("7777", "1234"));

        assertThat(codes(page).stream().filter("SUB-BOTH"::equals)).hasSize(1);
        assertThat(page.content()).hasSize((int) page.totalElements());
    }

    @Test
    void scenario6_aSubawardWithNoFundingAwardIsDenied() {
        assertThat(codes(search(departmentScope("7777")))).doesNotContain("SUB-NONE");
        assertThatThrownBy(() -> service(departmentScope("7777")).findWorkspace(SUB_NONE))
                .isInstanceOf(RecordNotAccessibleException.class);
        assertThat(facts.subawardFundingAwardIds(SUB_NONE)).isEmpty();
    }

    @Test
    void scenario7_aBrokenFundingReferenceIsNotAuthorization() {
        // No foreign key exists on subaward_funding.award_id, so a dangling
        // id is possible. It must read as "no funding Award", never as
        // permission - in BOTH evaluations.
        assertThat(codes(search(departmentScope("7777")))).doesNotContain("SUB-BROKEN");
        assertThat(codes(search(centralScope()))).contains("SUB-BROKEN");
        assertThat(facts.subawardFundingAwardIds(SUB_BROKEN)).isEmpty();
        assertThatThrownBy(() -> service(departmentScope("7777")).findWorkspace(SUB_BROKEN))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    // --- 8. Department and descendant-unit grants ------------------------

    @Test
    void scenario8_aDepartmentGrantReachesOnlyItsOwnUnitsSubawards() {
        SubawardPageResponse page = search(departmentScope("7777"));

        assertThat(codes(page)).containsExactlyInAnyOrder(
                "SUB-CHEM", "SUB-BOTH", "SUB-FAM", "SUB-CHEMV1");
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    void scenario8_aParentUnitWithDescendantsReachesTheChildUnitsSubawards() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", true)), Optional.empty(), Set.of()),
                Set.of("7000", "7777"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        assertThat(codes(search(scoped(scope, Set.of(CHEM_V1, CHEM_V2)))))
                .containsExactlyInAnyOrder("SUB-CHEM", "SUB-BOTH", "SUB-FAM", "SUB-CHEMV1");
    }

    @Test
    void scenario8_aParentUnitWithoutDescendantsReachesNothing() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", false)), Optional.empty(), Set.of()),
                Set.of("7000"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        assertThat(codes(search(scoped(scope, Set.of())))).isEmpty();
    }

    // --- 9. Research Staff Award-family grants ---------------------------

    @Test
    void scenario9_aResearchStaffContactReachesTheSubawardsOfTheirAward() {
        assertThat(codes(search(contactScope(FARRER))))
                .containsExactlyInAnyOrder("SUB-CHEM", "SUB-BOTH", "SUB-FAM", "SUB-CHEMV1");
    }

    @Test
    void scenario9_aContactWithAnUnapprovedRoleReachesNothingThroughThatAward() {
        // FARRER is on PHYS-00001 with role OTHER, which is not a Research
        // Staff role, so SUB-OTHERS stays out of reach.
        assertThat(codes(search(contactScope(FARRER)))).doesNotContain("SUB-OTHERS");
        assertThat(codes(search(contactScope(STRANGER)))).isEmpty();
    }

    @Test
    void scenario9_familyWideContactAccessReachesASubawardFundedByANonCurrentVersion() {
        /*
         * SUB-CHEMV1's only funding row names award_id 5001, the version
         * FARRER is NOT named on; the PI row is on 5002. Under FAMILY_WIDE,
         * canSeeAward(5001) is true, so the SQL predicate must say the same.
         * A predicate that tested only the funded version would deny here
         * and disagree with the Java evaluator.
         */
        assertThat(codes(search(contactScope(FARRER)))).contains("SUB-CHEMV1");
    }

    // --- 10. Immediate revocation ----------------------------------------

    @Test
    void scenario10_revokingTheLastQualifyingGrantRemovesAccessAtOnce() {
        // The predicate is rebuilt per request and nothing is memoised, so
        // "immediately" means the next call, which is what this asserts.
        assertThat(codes(search(departmentScope("7777")))).contains("SUB-CHEM");

        SubawardRecordAccess revoked = departmentScope("9999");
        assertThat(codes(search(revoked))).isEmpty();
        assertThatThrownBy(() -> service(revoked).findWorkspace(SUB_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void scenario10_revokingOneOfTwoGrantsLeavesAccessThroughTheOther() {
        // SUB-BOTH survives losing the CHEM grant because PHYS still funds it.
        assertThat(codes(search(departmentScope("1234")))).contains("SUB-BOTH");
    }

    // --- 11-13. Identity states ------------------------------------------

    @Test
    void scenario11_aCallerWithNoGrantsGetsAnExplicitProblemNotAnEmptyArchive() {
        assertThatThrownBy(() -> search(unprovisioned()))
                .isInstanceOf(AccessNotProvisionedException.class);
    }

    @Test
    void scenario11_anEmptyScopeRendersFalseRatherThanNoPredicate() {
        // The dangerous failure is an empty scope rendering an empty
        // fragment, which would return the whole archive.
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        assertThat(scope.sql()).contains("FALSE");
        assertThat(codes(search(scoped(scope, Set.of())))).isEmpty();
    }

    @Test
    void scenario12_aSuspendedIdentityIsDeniedBeforeAnyQueryRuns() {
        assertThatThrownBy(() -> search(suspended()))
                .isInstanceOf(IdentityAccessDeniedException.class);
        assertThatThrownBy(() -> service(suspended()).findWorkspace(SUB_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void scenario13_archiveAdminWithoutDataGrantsSeesNoRecords() {
        // ARCHIVE_ADMIN is an administrative role, not a data grant: it
        // produces the same empty scope as any ungranted caller.
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        SubawardRecordAccess admin = scoped(scope, Set.of());

        assertThat(codes(search(admin))).isEmpty();
        assertThat(search(admin).totalElements()).isZero();
        assertThatThrownBy(() -> service(admin).findWorkspace(SUB_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    // --- Central ---------------------------------------------------------

    @Test
    void centralSeesEverySubawardIncludingUnfundedAndBrokenOnes() {
        SubawardPageResponse page = search(centralScope());

        assertThat(page.totalElements()).isEqualTo(8);
        assertThat(codes(page)).contains("SUB-NONE", "SUB-BROKEN", "SUB-OTHERS");
    }

    @Test
    void centralCanOpenASubawardThatHasNoFundingAwardAtAll() {
        /*
         * Regression for a disagreement this suite caught: Central's
         * predicate is NONE, so SUB-NONE is listed, but deciding the record
         * through funding inheritance answered false for it - a row on the
         * page that 404s when opened. Central is unrestricted in both paths.
         */
        assertThatCode(() -> service(centralScope()).findWorkspace(SUB_NONE))
                .doesNotThrowAnyException();
        assertThatCode(() -> service(centralScope()).findWorkspace(SUB_BROKEN))
                .doesNotThrowAnyException();
    }

    // --- 14. Direct URL denial -------------------------------------------

    @Test
    void scenario14_everySubawardKeyedEndpointRefusesADirectUrl() {
        SubawardArchiveService service = service(departmentScope("1234"));

        for (ThrowingCall call : List.<ThrowingCall>of(
                () -> service.findWorkspace(SUB_CHEM),
                () -> service.findVersions(SUB_CHEM, 0, 20),
                () -> service.findAmounts(SUB_CHEM),
                () -> service.findContacts(SUB_CHEM),
                () -> service.findCustomData(SUB_CHEM),
                () -> service.findFunding(SUB_CHEM),
                () -> service.findAttachments(SUB_CHEM),
                () -> service.downloadAttachment(SUB_CHEM, 1L),
                () -> service.findTemplateInfo(SUB_CHEM),
                () -> service.findCloseout(SUB_CHEM),
                () -> service.findReports(SUB_CHEM),
                () -> service.findNotepad(SUB_CHEM),
                () -> service.findNotifications(SUB_CHEM))) {

            assertThatThrownBy(call::run)
                    .isInstanceOf(RecordNotAccessibleException.class);
        }
    }

    @Test
    void scenario14_denialIsIndistinguishableFromAbsence() {
        // RecordNotAccessibleException extends NoSuchElementException, so
        // GlobalExceptionHandler answers 404. A 403 would confirm the
        // Subaward exists, which is itself the disclosure.
        assertThatThrownBy(() -> service(departmentScope("1234")).findWorkspace(SUB_CHEM))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service(SubawardRecordAccess.PERMIT_ALL).findWorkspace(123_456L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- 15. Search, count and pagination parity -------------------------

    @Test
    void scenario15_theCountAlwaysMatchesWhatPagingCanReach() {
        for (SubawardRecordAccess access : List.of(
                SubawardRecordAccess.PERMIT_ALL,
                centralScope(),
                departmentScope("7777"),
                departmentScope("1234"),
                departmentScope("7777", "1234"),
                contactScope(FARRER),
                contactScope(STRANGER))) {

            SubawardPageResponse page =
                    service(access).findPage("", SubawardSearchFilters.none(), 0, 50);
            assertThat(page.content()).hasSize((int) page.totalElements());
        }
    }

    @Test
    void scenario15_thePredicateSurvivesPagingAndFilters() {
        SubawardArchiveService service = service(departmentScope("7777"));

        SubawardPageResponse first = service.findPage("", SubawardSearchFilters.none(), 0, 2);
        SubawardPageResponse second = service.findPage("", SubawardSearchFilters.none(), 1, 2);

        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(first.content()).hasSize(2);
        assertThat(second.content()).hasSize(2);
        assertThat(codes(first)).doesNotContainAnyElementsOf(codes(second));
    }

    @Test
    void scenario15_aFreeTextSearchCannotReachOutOfScopeSubawards() {
        // The predicate sits in the same WHERE as the query, so naming a
        // Subaward directly does not retrieve it.
        SubawardPageResponse page =
                service(departmentScope("7777")).findPage("SUB-OTHERS", SubawardSearchFilters.none(), 0, 50);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void scenario15_theVersionListAndItsTotalAreBothScoped() {
        /*
         * SUB-FAM's two versions name different funding Awards: version 1
         * is funded by CHEM, version 2 by PHYS. A 7777 caller opens version
         * 1 and must not reach version 2 through the version list.
         */
        PageResponse<SubawardVersionSummaryResponse> versions =
                service(departmentScope("7777")).findVersions(SUB_FAM_HISTORY, 0, 20);

        assertThat(versions.content().stream().map(SubawardVersionSummaryResponse::subawardId))
                .containsExactly(SUB_FAM_HISTORY);
        assertThat(versions.totalElements()).isEqualTo(1);

        PageResponse<SubawardVersionSummaryResponse> unrestricted =
                service(SubawardRecordAccess.PERMIT_ALL).findVersions(SUB_FAM_HISTORY, 0, 20);
        assertThat(unrestricted.totalElements()).isEqualTo(2);
    }

    // --- 16. Attachment dual authorization --------------------------------

    @Test
    void scenario16_theRecordGateRefusesTheDownloadBeforeAnyStorageLookup() {
        /*
         * Two independent gates guard a download and both must hold. The
         * controller calls AttachmentAuthorizationService first (the
         * ArchiveAttachmentViewer role, unchanged by this work and covered
         * by its own tests); the service then applies the record rule, which
         * is what is asserted here. The storage is deliberately null: if the
         * gate ever stopped firing, this would NPE rather than pass.
         */
        assertThatThrownBy(() -> service(departmentScope("1234")).downloadAttachment(SUB_CHEM, 1L))
                .isInstanceOf(RecordNotAccessibleException.class);

        assertThatThrownBy(() -> service(departmentScope("7777")).downloadAttachment(SUB_CHEM, 1L))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(RecordNotAccessibleException.class);
    }

    // --- 17. Related Award information disclosure -------------------------

    @Test
    void scenario17_anUnauthorizedFundingAwardIsOmittedFromTheFundingList() {
        /*
         * The heart of the ANY-match rule's risk. Reaching SUB-BOTH needs
         * only ONE accessible funding Award, and its funding list carries
         * every funding Award's number, title, sponsor, status, amount and
         * link. A 7777 caller gets CHEM and must not learn PHYS exists.
         */
        var view = service(departmentScope("7777")).findFunding(SUB_BOTH);
        List<SubawardFundingResponse> funding = view.funding();

        assertThat(funding).hasSize(1);
        SubawardFundingResponse row = funding.getFirst();
        assertThat(row.exactLinkedAwardId()).isEqualTo(CHEM_V2);
        assertThat(row.awardNumber()).isEqualTo("CHEM-00001");

        assertThat(funding).noneMatch(each -> "PHYS-00001".equals(each.awardNumber()));
        assertThat(funding.toString()).doesNotContain("PHYS").doesNotContain("Physics");

        /*
         * And the caller is TOLD the list is partial. Without this the UI
         * counts what it received and renders "Associated Award", singular -
         * positively asserting this Subaward has one funding Award when it
         * has two. A partial list is a correct answer; a complete-looking
         * count over a partial list is a false one.
         */
        assertThat(view.restricted()).isTrue();
    }

    @Test
    void scenario17_omittedNotStubbed() {
        // Decision D5: no placeholder row. A stub saying "a funding Award
        // exists here that you may not see" still discloses the
        // relationship, which is most of what this card conveys.
        var view = service(departmentScope("1234")).findFunding(SUB_BOTH);

        assertThat(view.funding()).hasSize(1);
        assertThat(view.funding().getFirst().awardNumber()).isEqualTo("PHYS-00001");
        assertThat(view.restricted()).isTrue();
    }

    @Test
    void scenario17_aScopedCallerWhoSeesEveryFunderIsNotToldAnythingWasWithheld() {
        /*
         * `restricted` tracks what was actually removed, never merely "this
         * caller is scoped". A department user who can see both funders of
         * SUB-BOTH gets the exact count and learns nothing about
         * authorization at all - so the flag discloses only to the callers
         * for whom the alternative was a false count.
         */
        var view = service(departmentScope("7777", "1234")).findFunding(SUB_BOTH);

        assertThat(view.funding()).hasSize(2);
        assertThat(view.restricted()).isFalse();
    }

    @Test
    void scenario17_reachingASubawardGrantsNothingOnItsOtherFundingAwards() {
        // The Subaward is readable; the Awards keep their own gates.
        SubawardRecordAccess chemOnly = departmentScope("7777");

        assertThatCode(() -> service(chemOnly).findWorkspace(SUB_BOTH)).doesNotThrowAnyException();
        assertThat(chemOnly.canSeeAward(PHYS_V1)).isFalse();
        assertThat(chemOnly.canSeeAward(CHEM_V2)).isTrue();
    }

    // --- 18. Global Search and semantic isolation -------------------------

    @Test
    void scenario18_theKeywordLegIsScopedBecauseItGoesThroughTheSamePagedQuery() {
        // Global Search's Subaward leg calls findPage, so it inherits the
        // predicate rather than needing one of its own.
        SubawardPageResponse page =
                service(departmentScope("7777")).findPage("subaward", SubawardSearchFilters.none(), 0, 20);

        assertThat(codes(page)).doesNotContain("SUB-OTHERS", "SUB-NONE", "SUB-BROKEN");
    }

    @Test
    void scenario18_semanticEnrichmentYieldsNothingForAnOutOfScopeCode() {
        /*
         * Global Search drops a semantic Subaward row whose summary is
         * absent, so the lookup itself has to be scoped - otherwise an
         * unenriched row still carries the subaward_code and its link.
         */
        List<String> codes = List.of("SUB-CHEM", "SUB-OTHERS", "SUB-NONE");

        assertThat(service(departmentScope("7777")).findActiveSummariesForCodes(codes))
                .extracting(SubawardSemanticSummaryRow::subawardCode)
                .containsExactly("SUB-CHEM");

        assertThat(service(SubawardRecordAccess.PERMIT_ALL).findActiveSummariesForCodes(codes))
                .extracting(SubawardSemanticSummaryRow::subawardCode)
                .containsExactlyInAnyOrder("SUB-CHEM", "SUB-OTHERS", "SUB-NONE");
    }

    // --- The two evaluations must agree -----------------------------------

    @Test
    void theSqlPredicateAndThePerRecordDecisionAgreeOnEverySubaward() {
        /*
         * RecordScopeSqlBuilder decides lists; RecordAuthorizationService
         * decides single records. They are separate code paths over the same
         * rule, and a divergence is a leak in whichever direction it goes.
         */
        for (SubawardRecordAccess access : List.of(
                centralScope(),
                departmentScope("7777"),
                departmentScope("1234"),
                departmentScope("7777", "1234"),
                contactScope(FARRER),
                contactScope(STRANGER))) {

            List<Long> listed = service(access)
                    .findPage("", SubawardSearchFilters.none(), 0, 50)
                    .content().stream()
                    .map(SubawardSummaryResponse::subawardId)
                    .toList();

            for (long id : List.of(SUB_CHEM, SUB_BOTH, SUB_NONE, SUB_BROKEN,
                    SUB_OTHERS, SUB_FAM_HISTORY, SUB_FAM_ACTIVE, SUB_CHEM_V1_ONLY)) {
                assertThat(access.canSeeSubaward(id))
                        .as("subaward %d under %s", id, access)
                        .isEqualTo(listed.contains(id));
            }
        }
    }

    @Test
    void theFactsQueryReturnsOnlyResolvableFundingAwards() {
        assertThat(facts.subawardFundingAwardIds(SUB_BOTH))
                .containsExactlyInAnyOrder(CHEM_V2, PHYS_V1);
        assertThat(facts.subawardFundingAwardIds(SUB_OTHERS))
                .containsExactlyInAnyOrder(PHYS_V1, NULL_V1);
        assertThat(facts.subawardFundingAwardIds(SUB_BROKEN)).isEmpty();
        assertThat(facts.subawardFundingAwardIds(SUB_NONE)).isEmpty();
    }

    // --- Dashboard counts --------------------------------------------------

    @Test
    void theDashboardSubawardCountIsScopedAndTheStatementIsValidSql() {
        /*
         * The dashboard runs ONE statement carrying both the Award and the
         * Subaward predicate, and the Subaward one is the Award one applied
         * to a joined row - so they bind the same parameter names and are
         * prefixed apart. Nothing but a real database proves that worked:
         * a collision, a bad alias inside the scalar subquery, or an
         * unbound parameter would all pass a text assertion and fail here.
         */
        assertThat(dashboard(SubawardRecordAccess.PERMIT_ALL).subawards()).isEqualTo(7);
        assertThat(dashboard(centralScope()).subawards()).isEqualTo(7);

        // SUB-CHEM, SUB-BOTH, SUB-FAM, SUB-CHEMV1 - four codes, not the
        // archive-wide seven.
        assertThat(dashboard(departmentScope("7777")).subawards()).isEqualTo(4);
        assertThat(dashboard(departmentScope("1234")).subawards()).isEqualTo(3);
        assertThat(dashboard(contactScope(STRANGER)).subawards()).isZero();
    }

    // --- Drift-proof coverage ---------------------------------------------

    @Test
    void everySubawardKeyedServiceMethodRefusesADeniedCaller() {
        /*
         * The coverage guarantee, by reflection rather than by a list
         * someone must remember to update. Any public method on
         * SubawardArchiveService whose first parameter is a subaward_id
         * must refuse a caller with no access; add an unguarded one and
         * this fails on the next run. scenario14 above names today's 13
         * endpoints explicitly - this is what keeps the fourteenth covered.
         *
         * List-shaped methods are excluded BY NAME and tested separately:
         * they filter rows to nothing rather than throwing, which is right
         * for a search and wrong for a record read.
         */
        Set<String> listShaped = Set.of("findPage", "findActiveSummariesForCodes");

        SubawardArchiveService denied = service(departmentScope("1234"));
        List<String> unguarded = new java.util.ArrayList<>();
        List<String> exercised = new java.util.ArrayList<>();

        for (java.lang.reflect.Method method
                : SubawardArchiveService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || method.isSynthetic()
                    || listShaped.contains(method.getName())) {
                continue;
            }
            Object[] args = subawardKeyedArguments(method);
            if (args == null) {
                continue;
            }
            exercised.add(method.getName());
            try {
                method.invoke(denied, args);
                unguarded.add(method.getName() + " returned normally");
            } catch (java.lang.reflect.InvocationTargetException invoked) {
                Throwable cause = invoked.getCause();
                if (!(cause instanceof NoSuchElementException)) {
                    unguarded.add(method.getName() + " threw " + cause.getClass().getSimpleName());
                }
            } catch (IllegalAccessException unreachable) {
                throw new AssertionError(unreachable);
            }
        }

        assertThat(unguarded)
                .as("public SubawardArchiveService methods taking a subaward id that do "
                        + "not refuse a denied caller with NoSuchElementException")
                .isEmpty();

        // A reflective check that silently matched nothing would pass while
        // proving nothing, so the harness asserts it actually ran.
        assertThat(exercised)
                .as("subaward-keyed methods this check reached")
                .hasSizeGreaterThanOrEqualTo(13)
                .contains("findWorkspace", "findFunding", "downloadAttachment");
    }

    /** Dummy arguments for a method keyed by subaward id, or null if it is not. */
    private static Object[] subawardKeyedArguments(java.lang.reflect.Method method) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return null;
        }
        Class<?> first = parameters[0].getType();
        if (first != long.class && first != Long.class) {
            return null;
        }
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type == long.class || type == Long.class) {
                args[i] = i == 0 ? SUB_CHEM : 1L;
            } else if (type == int.class || type == Integer.class) {
                args[i] = i == 1 ? 0 : 25;
            } else if (type == String.class) {
                args[i] = "";
            } else if (type == boolean.class) {
                args[i] = false;
            } else {
                // A parameter shape this harness does not model; skip the
                // method rather than assert something meaningless.
                return null;
            }
        }
        return args;
    }

    // --- helpers -----------------------------------------------------------

    private interface ThrowingCall {
        void run();
    }

    private static SubawardPageResponse search(SubawardRecordAccess access) {
        return service(access).findPage("", SubawardSearchFilters.none(), 0, 50);
    }

    private static SubawardArchiveService service(SubawardRecordAccess access) {
        return new SubawardArchiveService(repository, null, access);
    }

    /** The real DashboardService, Award side left unrestricted. */
    private static edu.bu.archive.adapter.in.web.dto.DashboardDto dashboard(
            SubawardRecordAccess access) {
        return new edu.bu.archive.application.service.DashboardService(
                jdbcClient,
                provider(edu.bu.archive.application.authorization.AwardRecordAccess.PERMIT_ALL),
                provider(access),
                null)
                .counts();
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

    private static List<String> codes(SubawardPageResponse page) {
        return page.content().stream().map(SubawardSummaryResponse::subawardCode).toList();
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match) {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE, match, RESEARCH_STAFF_ROLES);
    }

    private static SqlFragment scopeFor(
            AccessScope scope, Set<String> units, AuthorizationPolicy.DepartmentMatch match) {
        return scopeFor(scope, units, match, "s");
    }

    private static SqlFragment scopeFor(
            AccessScope scope, Set<String> units,
            AuthorizationPolicy.DepartmentMatch match, String alias) {
        return new RecordScopeSqlBuilder(policy(match), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(scope), RecordModule.SUBAWARD, alias, units);
    }

    private static SubawardRecordAccess centralScope() {
        AccessScope scope = new AccessScope(true, Set.of(), Optional.empty(), Set.of());
        return scoped(
                alias -> scopeFor(scope, Set.of(),
                        AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, alias),
                Set.of(CHEM_V1, CHEM_V2, PHYS_V1, NULL_V1));
    }

    private static SubawardRecordAccess departmentScope(String... units) {
        Set<UnitGrant> grants = Stream.of(units).map(unit -> new UnitGrant(unit, false))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Long> awards = new java.util.LinkedHashSet<>();
        for (String unit : units) {
            if ("7777".equals(unit)) {
                awards.add(CHEM_V1);
                awards.add(CHEM_V2);
            }
            if ("1234".equals(unit)) {
                awards.add(PHYS_V1);
            }
        }
        AccessScope scope = new AccessScope(false, grants, Optional.empty(), Set.of());
        return scoped(
                alias -> scopeFor(scope, Set.of(units),
                        AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, alias),
                Set.copyOf(awards));
    }

    /**
     * FAMILY_WIDE contact access: FARRER is PI on CHEM-00001's current
     * version, which under this policy reaches EVERY version of that family,
     * version 5001 included. The visible-Award set says so explicitly rather
     * than deriving it, so the fixture cannot quietly agree with a bug.
     */
    private static SubawardRecordAccess contactScope(KualiPersonId person) {
        AccessScope scope = new AccessScope(false, Set.of(), Optional.of(person), Set.of());
        return scoped(
                alias -> scopeFor(scope, Set.of(),
                        AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, alias),
                FARRER.equals(person) ? Set.of(CHEM_V1, CHEM_V2) : Set.of());
    }

    /**
     * The SQL predicate and the per-record decision are separate paths in
     * production and must agree, so BOTH are modelled.
     *
     * <p>The record side resolves funding through the real
     * {@link JdbcRecordFactsRepository} query and then applies the ANY-match
     * over the visible Award ids - which is exactly what
     * {@code RecordAuthorizationService.canSeeSubaward} does. Only the Award
     * decision is stubbed; the Subaward logic under test is the real shape.
     */
    private static SubawardRecordAccess scoped(SqlFragment fragment, Set<Long> visibleAwards) {
        return scoped(alias -> fragment, visibleAwards);
    }

    /**
     * The alias is a parameter in production - the paged query asks for
     * {@code s}, the dashboard for {@code sub} - so the fixture renders per
     * alias rather than reusing one fragment, which would silently produce
     * "missing FROM-clause entry" SQL for the second caller.
     */
    private static SubawardRecordAccess scoped(
            java.util.function.Function<String, SqlFragment> fragmentFor,
            Set<Long> visibleAwards
    ) {
        return new BaseAccess() {
            @Override public SqlFragment subawardScopeSql(String alias) {
                return fragmentFor.apply(alias);
            }

            @Override public boolean unrestricted() {
                return fragmentFor.apply("s") == SqlFragment.NONE;
            }

            @Override public boolean canSeeAward(long awardId) {
                return visibleAwards.contains(awardId);
            }

            @Override public boolean canSeeSubaward(long subawardId) {
                // Mirrors RecordAuthorizationService: Central short-circuits,
                // because its predicate is NONE and lists every Subaward.
                return unrestricted()
                        || facts.subawardFundingAwardIds(subawardId).stream()
                                .anyMatch(this::canSeeAward);
            }

            @Override public void requireSubaward(long subawardId) {
                if (!canSeeSubaward(subawardId)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public String toString() {
                return "scoped" + visibleAwards;
            }
        };
    }

    private static SubawardRecordAccess unprovisioned() {
        return new BaseAccess() {
            @Override public SqlFragment subawardScopeSql(String alias) {
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

    private static SubawardRecordAccess suspended() {
        return new BaseAccess() {
            @Override public SqlFragment subawardScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public void requireProvisioned() {
                throw new IdentityAccessDeniedException("suspended");
            }

            @Override public boolean unrestricted() {
                return false;
            }

            @Override public void requireSubaward(long subawardId) {
                throw new RecordNotAccessibleException();
            }

            @Override public boolean canSeeSubaward(long subawardId) {
                return false;
            }
        };
    }

    /** Permissive defaults, so each fixture overrides only what it tests. */
    private abstract static class BaseAccess implements SubawardRecordAccess {
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
