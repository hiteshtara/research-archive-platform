package edu.bu.archive.adapter.out.persistence.authorization;

import edu.bu.archive.adapter.in.web.dto.PageResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationAssociatedRecordResponse;
import edu.bu.archive.adapter.in.web.dto.negotiation.NegotiationSummaryResponse;
import edu.bu.archive.adapter.out.persistence.NegotiationArchiveRepository;
import edu.bu.archive.adapter.out.persistence.NegotiationSemanticSummaryRow;
import edu.bu.archive.application.authorization.AccessNotProvisionedException;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScope;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.IdentityAccessDeniedException;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.NegotiationRecordAccess;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;
import edu.bu.archive.application.authorization.RecordScopeSqlBuilder;
import edu.bu.archive.application.authorization.SqlFragment;
import edu.bu.archive.application.authorization.UnitGrant;
import edu.bu.archive.application.negotiation.NegotiationArchiveService;
import edu.bu.archive.application.negotiation.NegotiationSearchFilters;

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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Negotiation record authorization, against a real PostgreSQL with every
 * committed migration applied.
 *
 * <p>The approved rule (2026-10-27) is HYBRID, with three ALTERNATIVE
 * paths: the recorded lead unit, an explicitly {@code 'Award'}-typed
 * association, or an explicitly {@code 'Institutional Proposal'}-typed
 * association. Profiling showed inheritance alone would reach 20.7% of real
 * Negotiations, so the lead-unit path is what makes the rule usable - and
 * the type gate is what stops it being dangerous.
 *
 * <p>Negotiation is FLAT: one row per Negotiation, no versions. There is no
 * family question here, unlike every other module.
 *
 * <p>The most important tests in this class are the collision ones. In dev,
 * 8,533 Negotiations are typed {@code 'None'} and their
 * {@code associated_document_id} - their own document number - matches an
 * Award workflow document number 7,246 times and a proposal number 6,980
 * times. Those are coincidences. A rule that resolved the column without
 * gating on the association type would hand out thousands of records.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class NegotiationRecordAuthorizationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Set<String> RESEARCH_STAFF_ROLES = Set.of("PI", "MPI", "COI");
    private static final KualiPersonId FARRER = new KualiPersonId("P-FARRER");
    private static final KualiPersonId STRANGER = new KualiPersonId("P-STRANGER");

    // Negotiations.
    private static final long NEG_UNIT_CHEM = 100L;       // lead unit 7777, type None
    private static final long NEG_UNIT_PHYS = 101L;       // lead unit 1234, type None
    private static final long NEG_AWARD = 102L;           // type Award -> CHEM-00001
    private static final long NEG_PROPOSAL = 103L;        // type Institutional Proposal
    private static final long NEG_SUBAWARD = 104L;        // type Subaward, lead unit 1234
    private static final long NEG_COLLISION = 105L;       // type None, id collides
    private static final long NEG_NO_BASIS = 106L;        // no lead unit, type None
    private static final long NEG_AWARD_OTHER_UNIT = 107L; // Award CHEM, lead unit 1234

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static NegotiationArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            NegotiationRecordAuthorizationIntegrationTest::migrationVersion))
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
             * DOC-COLLIDE is the whole point of this fixture: it is both an
             * Award workflow_document_number AND a proposal_number, and it
             * is ALSO the associated_document_id of a type-None
             * Negotiation. Exactly the dev shape, at 1/8533 scale.
             */
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
                        is_primary_current, workflow_document_number
                    ) VALUES
                    (5002, 'CHEM-00001', 1, 'Chemistry award', 'Active',
                     '0200', 'NIH', '7777', 'CHEMISTRY', TRUE, 'DOC-COLLIDE'),
                    (6001, 'PHYS-00001', 1, 'Physics award',   'Active',
                     '0100', 'NSF', '1234', 'PHYSICS',   TRUE, 'DOC-PHYS')
                    """);
            statement.execute("""
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        full_name, contact_role_code, person_id
                    ) VALUES
                    (101, 5002, 'CHEM-00001', 1, 'L FARRER', 'PI', 'P-FARRER')
                    """);
            statement.execute("""
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number, title,
                        proposal_sequence_status, sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name, principal_investigator_name
                    ) VALUES
                    (8001, 'CHEM-P1',     1, 'Chemistry proposal', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'L FARRER'),
                    (8002, 'DOC-COLLIDE', 1, 'Colliding proposal', 'Approved',
                     '0200', 'NIH', '7777', 'CHEMISTRY', 'L FARRER')
                    """);

            statement.execute("""
                    INSERT INTO archive.negotiation (
                        negotiation_id, document_number,
                        negotiation_association_type_code,
                        negotiation_association_type_description,
                        associated_document_id, negotiator_person_id, negotiator_full_name
                    ) VALUES
                    (100, 'NDOC-100', 'NO',  'None',                   'NDOC-100',    'P-NEG', 'A Negotiator'),
                    (101, 'NDOC-101', 'NO',  'None',                   'NDOC-101',    'P-NEG', 'A Negotiator'),
                    (102, 'NDOC-102', 'AWD', 'Award',                  'CHEM-00001',  'P-NEG', 'A Negotiator'),
                    (103, 'NDOC-103', 'IP',  'Institutional Proposal', 'CHEM-P1',     'P-NEG', 'A Negotiator'),
                    (104, 'NDOC-104', 'SWD', 'Subaward',               'DOC-COLLIDE', 'P-NEG', 'A Negotiator'),
                    (105, 'NDOC-105', 'NO',  'None',                   'DOC-COLLIDE', 'P-NEG', 'A Negotiator'),
                    (106, 'NDOC-106', 'NO',  'None',                   'NDOC-106',    'P-NEG', 'A Negotiator'),
                    (107, 'NDOC-107', 'AWD', 'Award',                  'CHEM-00001',  'P-NEG', 'A Negotiator')
                    """);

            // 1:1 with archive.negotiation, FK-enforced. NEG_NO_BASIS has a
            // row but no lead unit - the real shape of the 57 such records.
            statement.execute("""
                    INSERT INTO archive.negotiation_search_attribute (
                        negotiation_id, attribute_source, title, lead_unit_number, lead_unit_name
                    ) VALUES
                    (100, 'UNASSOCIATED_DETAIL', 'Chem unassociated', '7777', 'CHEMISTRY'),
                    (101, 'UNASSOCIATED_DETAIL', 'Phys unassociated', '1234', 'PHYSICS'),
                    (102, 'AWARD',               'Award negotiation', '7777', 'CHEMISTRY'),
                    (103, 'UNASSOCIATED_DETAIL', 'Prop negotiation',  '7777', 'CHEMISTRY'),
                    (104, 'UNASSOCIATED_DETAIL', 'Subaward neg',      '1234', 'PHYSICS'),
                    (105, 'UNASSOCIATED_DETAIL', 'Collision neg',     '1234', 'PHYSICS'),
                    (106, 'NONE',                'No basis neg',      NULL,   NULL),
                    (107, 'AWARD',               'Award other unit',  '1234', 'PHYSICS')
                    """);
        }

        repository = new NegotiationArchiveRepository(JdbcClient.create(dataSource));
    }

    // --- Enforcement disabled ---------------------------------------------

    @Test
    void permitAllReturnsEveryNegotiationAndMatchesPreAuthorizationBehaviour() {
        PageResponse<NegotiationSummaryResponse> page = search(NegotiationRecordAccess.PERMIT_ALL);

        assertThat(page.totalElements()).isEqualTo(8);
        assertThat(page.content()).hasSize(8);
    }

    @Test
    void permitAllOpensEvenTheNegotiationWithNoBasis() {
        assertThatCode(() -> service(NegotiationRecordAccess.PERMIT_ALL).findWorkspace(NEG_NO_BASIS))
                .doesNotThrowAnyException();
    }

    @Test
    void permitAllKeepsTheAssociatedRecordResponseUnchanged() {
        NegotiationAssociatedRecordResponse associated =
                service(NegotiationRecordAccess.PERMIT_ALL).findAssociatedRecord(NEG_AWARD);

        assertThat(associated.associatedDocumentId()).isEqualTo("CHEM-00001");
        assertThat(associated.kind()).isEqualTo("AWARD");
    }

    // --- Central -----------------------------------------------------------

    @Test
    void centralSeesEveryNegotiationIncludingThoseWithNoBasis() {
        PageResponse<NegotiationSummaryResponse> page = search(centralScope());

        assertThat(page.totalElements()).isEqualTo(8);
        assertThatCode(() -> service(centralScope()).findWorkspace(NEG_NO_BASIS))
                .doesNotThrowAnyException();
    }

    // --- Department (the lead-unit path) -----------------------------------

    @Test
    void anAuthorizedDepartmentSeesItsOwnLeadUnitNegotiations() {
        PageResponse<NegotiationSummaryResponse> page = search(departmentScope("7777"));

        // 100 (unit), 102 (unit + Award), 103 (unit + Proposal),
        // 107 (Award CHEM although its own unit is 1234).
        assertThat(ids(page)).containsExactlyInAnyOrder(
                NEG_UNIT_CHEM, NEG_AWARD, NEG_PROPOSAL, NEG_AWARD_OTHER_UNIT);
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    void anUnauthorizedDepartmentSeesNoneOfThem() {
        assertThat(ids(search(departmentScope("9999")))).isEmpty();
        assertThatThrownBy(() -> service(departmentScope("9999")).findWorkspace(NEG_UNIT_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void aParentUnitWithDescendantsEnabledReachesTheChildUnit() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", true)), Optional.empty(), Set.of()),
                Set.of("7000", "7777"),
                AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);

        assertThat(ids(search(scoped(scope, Set.of("7000", "7777"), Set.of("CHEM-00001"), Set.of("CHEM-P1")))))
                .contains(NEG_UNIT_CHEM, NEG_AWARD, NEG_PROPOSAL);
    }

    @Test
    void aParentUnitWithoutDescendantsReachesNothing() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7000", false)), Optional.empty(), Set.of()),
                Set.of("7000"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        assertThat(ids(search(scoped(scope, Set.of("7000"), Set.of(), Set.of())))).isEmpty();
    }

    // --- Inheritance paths --------------------------------------------------

    @Test
    void anAwardAssociatedNegotiationIsReachableThroughItsAward() {
        // NEG_AWARD_OTHER_UNIT's own lead unit is 1234, so only the Award
        // path can explain a 7777 caller reaching it.
        assertThat(ids(search(departmentScope("7777")))).contains(NEG_AWARD_OTHER_UNIT);
        assertThatCode(() -> service(departmentScope("7777")).findWorkspace(NEG_AWARD_OTHER_UNIT))
                .doesNotThrowAnyException();
    }

    @Test
    void aProposalAssociatedNegotiationIsReachableThroughItsProposal() {
        assertThatCode(() -> service(departmentScope("7777")).findWorkspace(NEG_PROPOSAL))
                .doesNotThrowAnyException();
    }

    @Test
    void theTwoPathsAreAlternatives_eitherOneSuffices() {
        // 1234 reaches NEG_AWARD_OTHER_UNIT by LEAD UNIT; 7777 reaches the
        // same record by AWARD. Neither needs the other.
        assertThat(ids(search(departmentScope("1234")))).contains(NEG_AWARD_OTHER_UNIT);
        assertThat(ids(search(departmentScope("7777")))).contains(NEG_AWARD_OTHER_UNIT);
    }

    // --- The collision regression: THE critical tests -----------------------

    @Test
    void aTypeNoneNegotiationCannotAcquireAwardAccessThroughADocumentNumberCollision() {
        /*
         * NEG_COLLISION is typed 'None'. Its associated_document_id is
         * 'DOC-COLLIDE', which IS a real Award workflow_document_number
         * (CHEM-00001, unit 7777) and IS a real proposal_number (unit
         * 7777). Its own lead unit is 1234.
         *
         * A 7777 caller holds the colliding Award and Proposal. They must
         * NOT reach this Negotiation: the match is a coincidence, not an
         * association. In dev this shape occurs 7,246 and 6,980 times.
         */
        assertThat(ids(search(departmentScope("7777")))).doesNotContain(NEG_COLLISION);
        assertThatThrownBy(() -> service(departmentScope("7777")).findWorkspace(NEG_COLLISION))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void theCollidingNegotiationIsStillReachableByItsOwnLeadUnit() {
        // Denying the collision must not deny the record outright - the
        // department that owns it keeps its access.
        assertThat(ids(search(departmentScope("1234")))).contains(NEG_COLLISION);
    }

    @Test
    void aSubawardTypedAssociationGrantsNothingButTheLeadUnitStillDoes() {
        /*
         * NEG_SUBAWARD is typed 'Subaward' with the same colliding
         * identifier. No reliable Subaward key exists for these 16 records
         * archive-wide, so inheritance must grant nothing - but its lead
         * unit 1234 still does, which is the approved behaviour.
         */
        assertThat(ids(search(departmentScope("7777")))).doesNotContain(NEG_SUBAWARD);
        assertThat(ids(search(departmentScope("1234")))).contains(NEG_SUBAWARD);
    }

    @Test
    void theAwardPathResolvesAwardNumberAndNeverTheWorkflowDocumentNumber() {
        // Type-gated, award_number matched 2,223/2,223 in dev and the
        // workflow column matched none, so consulting the workflow column
        // could only ever add false hits. NEG_COLLISION proves it does not.
        SqlFragment fragment = scopeFor(
                new AccessScope(false, Set.of(new UnitGrant("7777", false)), Optional.empty(), Set.of()),
                Set.of("7777"), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

        assertThat(fragment.sql()).contains("az_na.award_number = TRIM");
        assertThat(fragment.sql()).doesNotContain("workflow_document_number");
    }

    // --- No basis -----------------------------------------------------------

    @Test
    void aNegotiationWithNoBasisIsCentralOnly() {
        assertThat(ids(search(departmentScope("7777")))).doesNotContain(NEG_NO_BASIS);
        assertThat(ids(search(departmentScope("1234")))).doesNotContain(NEG_NO_BASIS);
        assertThatThrownBy(() -> service(departmentScope("7777")).findWorkspace(NEG_NO_BASIS))
                .isInstanceOf(RecordNotAccessibleException.class);
        assertThat(ids(search(centralScope()))).contains(NEG_NO_BASIS);
    }

    // --- Identity states ----------------------------------------------------

    @Test
    void multipleGrantsUnionTheirReach() {
        PageResponse<NegotiationSummaryResponse> page = search(departmentScope("7777", "1234"));
        assertThat(page.totalElements()).isEqualTo(7);   // everything but NEG_NO_BASIS
        assertThat(ids(page)).doesNotContain(NEG_NO_BASIS);
    }

    @Test
    void revokingTheGrantRemovesAccessOnTheNextCall() {
        assertThat(ids(search(departmentScope("7777")))).contains(NEG_UNIT_CHEM);
        assertThat(ids(search(departmentScope("9999")))).isEmpty();
        assertThatThrownBy(() -> service(departmentScope("9999")).findWorkspace(NEG_UNIT_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void aSuspendedIdentityIsRefusedBeforeAnyQueryRuns() {
        assertThatThrownBy(() -> search(suspended())).isInstanceOf(IdentityAccessDeniedException.class);
        assertThatThrownBy(() -> service(suspended()).findWorkspace(NEG_UNIT_CHEM))
                .isInstanceOf(RecordNotAccessibleException.class);
    }

    @Test
    void aCallerWithNoPermissionsGetsAnExplicitProblemNotAnEmptyArchive() {
        assertThatThrownBy(() -> search(unprovisioned()))
                .isInstanceOf(AccessNotProvisionedException.class);
    }

    @Test
    void archiveAdminWithoutDataGrantsSeesNothing() {
        SqlFragment scope = scopeFor(
                new AccessScope(false, Set.of(), Optional.empty(), Set.of()),
                Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        NegotiationRecordAccess admin = scoped(scope, Set.of(), Set.of(), Set.of());

        assertThat(scope.sql()).contains("FALSE");
        assertThat(ids(search(admin))).isEmpty();
        assertThat(search(admin).totalElements()).isZero();
    }

    // --- Direct URL ---------------------------------------------------------

    @Test
    void everyNegotiationKeyedEndpointRefusesADirectUrl() {
        NegotiationArchiveService service = service(departmentScope("9999"));

        for (ThrowingCall call : List.<ThrowingCall>of(
                () -> service.findWorkspace(NEG_UNIT_CHEM),
                () -> service.findActivities(NEG_UNIT_CHEM),
                () -> service.findCustomData(NEG_UNIT_CHEM),
                () -> service.findNotifications(NEG_UNIT_CHEM),
                () -> service.findUnassociatedDetails(NEG_UNIT_CHEM),
                () -> service.findAttachments(NEG_UNIT_CHEM),
                () -> service.downloadAttachment(NEG_UNIT_CHEM, 1L),
                () -> service.findAssociatedRecord(NEG_UNIT_CHEM))) {
            assertThatThrownBy(call::run).isInstanceOf(RecordNotAccessibleException.class);
        }
    }

    @Test
    void denialIsIndistinguishableFromAbsence() {
        NegotiationArchiveService service = service(departmentScope("9999"));

        Throwable denied = catchThrowable(() -> service.findWorkspace(NEG_UNIT_CHEM));
        Throwable missing = catchThrowable(() -> service.findWorkspace(404_404L));

        assertThat(denied).isInstanceOf(NoSuchElementException.class);
        assertThat(missing).isInstanceOf(NoSuchElementException.class);
        assertThat(missing.getMessage()).isEqualTo(denied.getMessage());
    }

    // --- Search, count, pagination -----------------------------------------

    @Test
    void theCountAlwaysMatchesWhatPagingCanReach() {
        for (NegotiationRecordAccess access : List.of(
                NegotiationRecordAccess.PERMIT_ALL, centralScope(),
                departmentScope("7777"), departmentScope("1234"),
                departmentScope("7777", "1234"), contactScope(STRANGER))) {
            PageResponse<NegotiationSummaryResponse> page =
                    service(access).findPage(NegotiationSearchFilters.none(), 0, 50);
            assertThat(page.content()).hasSize((int) page.totalElements());
        }
    }

    @Test
    void thePredicateSurvivesPagingAndAFreeTextSearch() {
        NegotiationArchiveService service = service(departmentScope("7777"));

        PageResponse<NegotiationSummaryResponse> first =
                service.findPage(NegotiationSearchFilters.none(), 0, 2);
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.content()).hasSize(2);

        // Naming an out-of-scope record directly does not retrieve it.
        PageResponse<NegotiationSummaryResponse> named =
                service.findPage(NegotiationSearchFilters.ofQuery("NDOC-106"), 0, 50);
        assertThat(named.content()).isEmpty();
        assertThat(named.totalElements()).isZero();
    }

    // --- Related-record metadata isolation (D7) -----------------------------

    @Test
    void aRestrictedAssociatedRecordIsOmittedEntirelyFromTheAssociatedResponse() {
        /*
         * NEG_AWARD_OTHER_UNIT is reachable by a 1234 caller through its
         * LEAD UNIT, but its Award CHEM-00001 belongs to 7777. The caller
         * may open the Negotiation and must learn nothing about the Award:
         * no number, no resolved id, no link, no "restricted" marker.
         */
        NegotiationAssociatedRecordResponse associated =
                service(departmentScope("1234")).findAssociatedRecord(NEG_AWARD_OTHER_UNIT);

        assertThat(associated.associatedDocumentId()).isNull();
        assertThat(associated.navigableId()).isNull();
        assertThat(associated.kind()).isEqualTo("NONE");
        assertThat(associated.toString()).doesNotContain("CHEM-00001");
    }

    @Test
    void aRestrictedAssociationIsIndistinguishableFromNoAssociation() {
        // The degraded response must match what an unassociated Negotiation
        // produces, or the shape itself is the disclosure.
        NegotiationAssociatedRecordResponse restricted =
                service(departmentScope("1234")).findAssociatedRecord(NEG_AWARD_OTHER_UNIT);
        NegotiationAssociatedRecordResponse none =
                service(departmentScope("1234")).findAssociatedRecord(NEG_UNIT_PHYS);

        assertThat(restricted.kind()).isEqualTo(none.kind());
        assertThat(restricted.navigableId()).isEqualTo(none.navigableId());
        assertThat(restricted.clickable()).isEqualTo(none.clickable());
    }

    @Test
    void anAuthorizedAssociatedRecordIsStillReturnedInFull() {
        NegotiationAssociatedRecordResponse associated =
                service(departmentScope("7777")).findAssociatedRecord(NEG_AWARD);

        assertThat(associated.associatedDocumentId()).isEqualTo("CHEM-00001");
        assertThat(associated.kind()).isEqualTo("AWARD");
    }

    // --- Global Search / semantic -------------------------------------------

    @Test
    void semanticEnrichmentYieldsNothingForAnOutOfScopeDocumentNumber() {
        List<String> numbers = List.of("NDOC-100", "NDOC-106", "NDOC-105");

        assertThat(service(departmentScope("7777")).findSummariesForDocumentNumbers(numbers))
                .extracting(NegotiationSemanticSummaryRow::documentNumber)
                .containsExactly("NDOC-100");

        assertThat(service(NegotiationRecordAccess.PERMIT_ALL).findSummariesForDocumentNumbers(numbers))
                .extracting(NegotiationSemanticSummaryRow::documentNumber)
                .containsExactlyInAnyOrder("NDOC-100", "NDOC-106", "NDOC-105");
    }

    // --- The two paths must agree -------------------------------------------

    @Test
    void theSqlPredicateAndThePerRecordDecisionAgreeOnEveryNegotiation() {
        for (NegotiationRecordAccess access : List.of(
                centralScope(), departmentScope("7777"), departmentScope("1234"),
                departmentScope("7777", "1234"), contactScope(FARRER), contactScope(STRANGER))) {

            List<Long> listed = ids(service(access).findPage(NegotiationSearchFilters.none(), 0, 50));

            for (long id : List.of(NEG_UNIT_CHEM, NEG_UNIT_PHYS, NEG_AWARD, NEG_PROPOSAL,
                    NEG_SUBAWARD, NEG_COLLISION, NEG_NO_BASIS, NEG_AWARD_OTHER_UNIT)) {
                assertThat(access.canSeeNegotiation(id))
                        .as("negotiation %d under %s", id, access)
                        .isEqualTo(listed.contains(id));
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private interface ThrowingCall {
        void run();
    }

    private static PageResponse<NegotiationSummaryResponse> search(NegotiationRecordAccess access) {
        return service(access).findPage(NegotiationSearchFilters.none(), 0, 50);
    }

    private static NegotiationArchiveService service(NegotiationRecordAccess access) {
        return new NegotiationArchiveService(repository, null, access);
    }

    private static List<Long> ids(PageResponse<NegotiationSummaryResponse> page) {
        return page.content().stream().map(NegotiationSummaryResponse::negotiationId).toList();
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match) {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE, match, RESEARCH_STAFF_ROLES);
    }

    private static SqlFragment scopeFor(
            AccessScope scope, Set<String> units, AuthorizationPolicy.DepartmentMatch match) {
        return new RecordScopeSqlBuilder(policy(match), IoSqlStrategy.NONE)
                .build(new AccessOutcome.Scoped(scope), RecordModule.NEGOTIATION, "n", units);
    }

    private static NegotiationRecordAccess centralScope() {
        AccessScope scope = new AccessScope(true, Set.of(), Optional.empty(), Set.of());
        return scoped(scopeFor(scope, Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                Set.of(), Set.of("CHEM-00001", "PHYS-00001"), Set.of("CHEM-P1", "DOC-COLLIDE"));
    }

    private static NegotiationRecordAccess departmentScope(String... units) {
        Set<UnitGrant> grants = Stream.of(units).map(u -> new UnitGrant(u, false))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> unitSet = Set.of(units);
        Set<String> awards = new java.util.LinkedHashSet<>();
        Set<String> proposals = new java.util.LinkedHashSet<>();
        if (unitSet.contains("7777")) {
            awards.add("CHEM-00001");
            proposals.add("CHEM-P1");
            proposals.add("DOC-COLLIDE");
        }
        if (unitSet.contains("1234")) {
            awards.add("PHYS-00001");
        }
        AccessScope scope = new AccessScope(false, grants, Optional.empty(), Set.of());
        return scoped(scopeFor(scope, unitSet, AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                unitSet, awards, proposals);
    }

    private static NegotiationRecordAccess contactScope(KualiPersonId person) {
        AccessScope scope = new AccessScope(false, Set.of(), Optional.of(person), Set.of());
        return scoped(scopeFor(scope, Set.of(), AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                Set.of(), FARRER.equals(person) ? Set.of("CHEM-00001") : Set.of(), Set.of());
    }

    /**
     * Models BOTH decision paths, because production has both and they must
     * agree. The record side reproduces the service's own logic: lead unit
     * OR type-gated Award OR type-gated Proposal - and crucially it reads
     * the association from the fixture's TYPE, never by string match, which
     * is what the collision tests exercise.
     */
    private static NegotiationRecordAccess scoped(
            SqlFragment fragment, Set<String> units,
            Set<String> visibleAwards, Set<String> visibleProposals) {
        return new BaseAccess() {
            @Override public SqlFragment negotiationScopeSql(String alias) {
                return fragment;
            }

            @Override public boolean unrestricted() {
                return fragment == SqlFragment.NONE;
            }

            @Override public boolean canSeeAwardNumber(String awardNumber) {
                return visibleAwards.contains(awardNumber);
            }

            @Override public boolean canSeeProposalNumber(String proposalNumber) {
                return visibleProposals.contains(proposalNumber);
            }

            @Override public boolean canSeeNegotiation(long negotiationId) {
                if (unrestricted()) {
                    return true;
                }
                return repository.findById(negotiationId).map(row -> {
                    String unit = leadUnitOf(negotiationId);
                    if (unit != null && units.contains(unit)) {
                        return true;
                    }
                    String code = row.negotiationAssociationTypeCode();
                    String doc = row.associatedDocumentId();
                    if ("AWD".equals(code) && doc != null) {
                        return visibleAwards.contains(doc.trim());
                    }
                    if ("IP".equals(code) && doc != null) {
                        return visibleProposals.contains(doc.trim());
                    }
                    return false;   // 'SWD' and 'NO' grant nothing
                }).orElse(false);
            }

            @Override public void requireNegotiation(long negotiationId) {
                if (!canSeeNegotiation(negotiationId)) {
                    throw new RecordNotAccessibleException();
                }
            }

            @Override public String toString() {
                return "scoped" + units + visibleAwards;
            }
        };
    }

    /** The lead unit as recorded, read straight from the fixture table. */
    private static String leadUnitOf(long negotiationId) {
        return LEAD_UNITS.get(negotiationId);
    }

    private static final java.util.Map<Long, String> LEAD_UNITS = java.util.Map.of(
            100L, "7777", 101L, "1234", 102L, "7777", 103L, "7777",
            104L, "1234", 105L, "1234", 107L, "1234");

    private static NegotiationRecordAccess unprovisioned() {
        return new BaseAccess() {
            @Override public SqlFragment negotiationScopeSql(String alias) {
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

    private static NegotiationRecordAccess suspended() {
        return new BaseAccess() {
            @Override public SqlFragment negotiationScopeSql(String alias) {
                return SqlFragment.DENY_ALL;
            }

            @Override public void requireProvisioned() {
                throw new IdentityAccessDeniedException("suspended");
            }

            @Override public boolean unrestricted() {
                return false;
            }

            @Override public void requireNegotiation(long negotiationId) {
                throw new RecordNotAccessibleException();
            }

            @Override public boolean canSeeNegotiation(long negotiationId) {
                return false;
            }
        };
    }

    /** Permissive defaults, so each fixture overrides only what it tests. */
    private abstract static class BaseAccess implements NegotiationRecordAccess {
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
