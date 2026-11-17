package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.in.web.dto.DashboardDto;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchItemResponse;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchModuleTotal;
import edu.bu.archive.adapter.in.web.dto.GlobalSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardSearchResponse;
import edu.bu.archive.adapter.in.web.dto.award.AwardVersionSearchResultResponse;
import edu.bu.archive.adapter.out.persistence.AwardArchiveRepository;
import edu.bu.archive.adapter.out.persistence.AwardAttachmentStorage;
import edu.bu.archive.adapter.out.persistence.ProposalArchiveRepository;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AccessNotProvisionedException;
import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordNotAccessibleException;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.award.AwardArchiveService;
import edu.bu.archive.application.award.AwardSearchFilters;
import edu.bu.archive.application.service.DashboardService;
import edu.bu.archive.application.service.GlobalSearchService;
import edu.bu.archive.application.service.SearchTimingLog;
import edu.bu.archive.config.SemanticSearchProperties;

/**
 * The named BU acceptance scenarios, executed end to end against a real
 * PostgreSQL with every committed migration applied and record-level
 * enforcement ON.
 *
 * <h2>Where the scenarios come from</h2>
 *
 * <p>BU's "Security Admin User Stories" sheet names concrete test cases: a
 * Department user on lead unit 2573180018, a PI who can open 212128-00002
 * but not 211144-00007, and an Other Authorized Viewer (OAV) who can open
 * 209407-00002 by its account (IO) number but not 211144-00007. The record
 * SHAPES below mirror DEV as observed read-only on 2026-11-17: the same
 * award numbers, account numbers, lead units, unit parents and (roughly)
 * version counts. The PEOPLE are synthetic - no BU person's name, username
 * or Kuali PERSON_ID appears here - and the titles are invented.
 *
 * <h2>What is real in this test</h2>
 *
 * <p>Unlike the per-module authorization tests, which construct an access
 * port by hand, nothing on the decision path here is a stand-in:
 *
 * <pre>
 *   ArchiveAdminService           writes authz.identity_link / access_grant
 *   RecordAuthorizationService    the production port, enforcement ON
 *     -> IdentityResolver          (JdbcIdentityLinkRepository)
 *     -> AccessScopeResolver       (JdbcAccessGrantRepository, contacts, hierarchy)
 *     -> RecordScopeSqlBuilder     (with the production AwardAccountNumberIoSql)
 *     -> RecordAccessEvaluator     (JdbcRecordFactsRepository + AwardAccountNumberIoResolver)
 *   AwardArchiveService / DashboardService / GlobalSearchService
 * </pre>
 *
 * <p>Only the Cognito sign-in is simulated (a fixed {@link ValidatedCognitoIdentity}
 * per scenario) and S3 (an in-memory {@link AwardAttachmentStorage}).
 *
 * <h2>What is NOT covered here</h2>
 *
 * <p>The attachment GROUP gate (whether a caller may download attachments
 * at all) is enforced in the controller, not the service, and is covered by
 * {@code AwardV1ControllerDownloadSecurityTest} and
 * {@code AttachmentSearchControllerSecurityTest}. This class proves the
 * RECORD gate on the same calls: an attachment on an Award the caller may
 * not see is neither listed nor downloadable.
 *
 * <p>Nothing here touches DEV. Enforcement is on only inside this throwaway
 * container; no deployed service, grant, link or setting is affected.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("database")
@DisplayName("Named BU acceptance scenarios (Department, PI, OAV, Central)")
class NamedBuScenarioAuthorizationIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";

    // ── units, mirroring DEV ─────────────────────────────────────────

    private static final String DEPT_PARENT_UNIT = "2573180000";
    private static final String DEPT_UNIT = "2573180018";          // Biomedical Genetics
    private static final String OTHER_PARENT_UNIT = "1330000000";
    private static final String OTHER_UNIT = "1330420000";

    // ── Awards, mirroring DEV (award number : account number) ────────

    /** A: 212128-00002 : 9500316722, unit 2573180018, one version. */
    private static final String AWARD_A = "212128-00002";
    private static final long A_V1 = 7100001L;
    /** B: 211144-00007 : 9500317253, unit 2573180018, two versions. */
    private static final String AWARD_B = "211144-00007";
    private static final long B_V1 = 7200001L;
    private static final long B_V2 = 7200002L;
    /** C: 209407-00002 : 9500312705, unit 1330420000 (14 versions in DEV; three here). */
    private static final String AWARD_C = "209407-00002";
    private static final long C_V1 = 7300001L;
    private static final long C_V2 = 7300002L;
    private static final long C_V3 = 7300003L;
    /** D: synthetic. At C's PARENT unit, so a grant without descendants on C's unit cannot reach it. */
    private static final String AWARD_D = "990901-00001";
    private static final long D_V1 = 7400001L;

    private static final String ACCOUNT_A = "9500316722";
    private static final String ACCOUNT_B = "9500317253";
    private static final String ACCOUNT_C = "9500312705";

    /** One archived attachment on each Award's current version. */
    private static final long ATT_A = 61001L;
    private static final long ATT_B = 61002L;
    private static final long ATT_C = 61003L;
    private static final long ATT_D = 61004L;

    private static final String PROPOSAL_IN_DEPT = "SYN-PROP-A";
    private static final String PROPOSAL_ELSEWHERE = "SYN-PROP-C";

    // ── synthetic identities ─────────────────────────────────────────

    private static final String ADMIN = "ARP-SYN-ADMIN-0001";
    private static final String DEPT_USER = "ARP-SYN-DEPT-0001";
    private static final String PI_USER = "ARP-SYN-PI-0001";
    private static final String OAV_USER = "ARP-SYN-OAV-0001";
    private static final String CENTRAL_USER = "ARP-SYN-CEN-0001";
    private static final String TWO_UNIT_USER = "ARP-SYN-2UNIT-0001";
    private static final String NO_GRANT_USER = "ARP-SYN-NONE-0001";

    private static final String DEPT_PERSON = "SYN-P-DEPT-01";
    private static final String PI_PERSON = "SYN-P-PI-01";
    private static final String OTHER_PI_PERSON = "SYN-P-PI-02";
    private static final String C_PI_PERSON = "SYN-P-PI-03";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService admin;
    private static AuthorizationProperties properties;
    private static RecordAuthorizationService access;
    private static AwardArchiveService awards;
    private static DashboardService dashboard;
    private static GlobalSearchService globalSearch;

    /** Who is "signed in" for the current simulated request. */
    private static final AtomicReference<ValidatedCognitoIdentity> SIGNED_IN = new AtomicReference<>();

    @BeforeAll
    static void setUp() throws Exception {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrations())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(NamedBuScenarioAuthorizationIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }

        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
        seedArchive();

        AccessGrantRepository grants = proxy(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        admin = proxyClass(new ArchiveAdminService(
                proxy(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class),
                proxy(new JdbcAdminAuditSink(jdbc), AdminAuditSink.class),
                grants,
                proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class),
                new JdbcAdminAuditRepository(jdbc)));

        properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);
        /*
         * Research Staff roles = PI only, for the PI scenario as BU wrote it.
         * The REAL role set (PI only? PI + MPI + COI + KP?) is an OPEN BU
         * decision; this value is a test choice, not a recommendation.
         */
        properties.setResearchStaffRoles(Set.of("PI"));
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
        properties.setMaxSignInAge(Duration.ofHours(8));

        JdbcUnitHierarchy units = new JdbcUnitHierarchy(jdbc);
        access = new RecordAuthorizationService(
                properties,
                () -> Optional.ofNullable(SIGNED_IN.get()),
                new IdentityResolver(new JdbcIdentityLinkRepository(jdbc)),
                new AccessScopeResolver(grants, Clock.systemUTC(), properties::policy,
                        new JdbcContactRelationships(jdbc), new JdbcAwardHierarchy(jdbc)),
                new JdbcRecordFactsRepository(jdbc, new AwardAccountNumberIoResolver(jdbc)),
                units,
                units,
                new AwardAccountNumberIoSql(),
                IdentityEnrollment.DISABLED);

        AwardAttachmentStorage storage = attachment ->
                new AwardAttachmentStorage.StoredObject(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
        awards = new AwardArchiveService(new AwardArchiveRepository(jdbc), storage, access);

        dashboard = new DashboardService(jdbc, provider(access), provider(access), provider(access),
                provider(access), provider(properties));

        globalSearch = new GlobalSearchService(
                null,                       // IRB: never started for a module-filtered search
                awards,
                null,                       // Negotiation: not selected
                null,                       // Subaward: not selected
                new ProposalArchiveRepository(jdbc),
                null,                       // semantic search is off
                new SemanticSearchProperties(),
                null,
                new SearchTimingLog(false),
                provider(access),
                provider(access),
                provider(properties));

        provisionIdentitiesAndGrants();
    }

    @AfterEach
    void signOut() {
        SIGNED_IN.set(null);
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("guard: enforcement is really on, or every assertion below proves nothing")
    void enforcementIsOn() {
        assertThat(access.enforced()).isTrue();
        assertThat(as(DEPT_USER, access::outcome)).isInstanceOf(AccessOutcome.Scoped.class);
    }

    // ── Department: UNIT 2573180018, no descendants ──────────────────

    @Nested
    @DisplayName("Department user - UNIT grant on 2573180018, also listed as PAFO on C")
    class Department {

        @Test
        @DisplayName("Award search returns A and B only, and the total agrees")
        void search() {
            AwardSearchResponse page = as(DEPT_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50));

            assertThat(awardNumbers(page)).containsExactlyInAnyOrder(AWARD_A, AWARD_B);
            assertThat(page.results().totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("direct URL: A and B open; C is 404 despite the PAFO listing")
        void directUrl() {
            asVoid(DEPT_USER, () -> {
                assertThatCode(() -> awards.findFamily(AWARD_A)).doesNotThrowAnyException();
                assertThatCode(() -> awards.findFamily(AWARD_B)).doesNotThrowAnyException();
                assertDenied(() -> awards.findFamily(AWARD_C));
                assertDenied(() -> awards.findSummary(C_V3));
            });
        }

        @Test
        @DisplayName("the PAFO listing on C really exists - the denial is not a fixture gap")
        void pafoListingExists() {
            assertThat(jdbc.sql("""
                    SELECT COUNT(DISTINCT award_number) FROM archive.award_unit_contact
                    WHERE person_id = :p
                    """).param("p", DEPT_PERSON).query(Long.class).single())
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("version list: A's one and B's two versions; C's are refused")
        void versions() {
            asVoid(DEPT_USER, () -> {
                var rows = awards.searchVersions("", "", "", "", "all", "sequence", 0, 50);
                assertThat(rows.content()).extracting(AwardVersionSearchResultResponse::awardId)
                        .containsExactlyInAnyOrder(A_V1, B_V1, B_V2);
                assertThat(rows.totalElements()).isEqualTo(3);

                assertThat(awards.findVersions(B_V2, 0, 25).totalElements()).isEqualTo(2);
                assertDenied(() -> awards.findVersions(C_V3, 0, 25));
            });
        }

        @Test
        @DisplayName("attachments: listed and downloadable on A and B; refused on C")
        void attachments() {
            asVoid(DEPT_USER, () -> {
                assertThat(awards.findAttachments(A_V1, 0, 25).totalElements()).isEqualTo(1);
                assertThat(awards.findAttachments(B_V2, 0, 25).totalElements()).isEqualTo(1);
                assertThatCode(() -> awards.downloadAttachment(A_V1, ATT_A)).doesNotThrowAnyException();
                assertDenied(() -> awards.findAttachments(C_V3, 0, 25));
                assertDenied(() -> awards.downloadAttachment(C_V3, ATT_C));
            });
        }

        @Test
        @DisplayName("dashboard: 2 Awards, 3 historical Award records")
        void dashboardCount() {
            DashboardDto counts = as(DEPT_USER, dashboard::counts);
            assertThat(counts.awards()).isEqualTo(2);
            assertThat(counts.awardHistoryRecords()).isEqualTo(3);
        }
    }

    // ── PI: CONTACT_DERIVATION, PI on A only ─────────────────────────

    @Nested
    @DisplayName("PI - CONTACT_DERIVATION, PI on A only; B has a different PI in the same unit")
    class PrincipalInvestigator {

        @Test
        @DisplayName("Award search returns A only - B is absent although it shares A's unit")
        void search() {
            AwardSearchResponse page = as(PI_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50));

            assertThat(awardNumbers(page)).containsExactly(AWARD_A);
            assertThat(page.results().totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("direct URL: A opens; B is 404")
        void directUrl() {
            asVoid(PI_USER, () -> {
                assertThatCode(() -> awards.findFamily(AWARD_A)).doesNotThrowAnyException();
                assertDenied(() -> awards.findFamily(AWARD_B));
                assertDenied(() -> awards.findSummary(B_V2));
                assertDenied(() -> awards.findFamily(AWARD_C));
            });
        }

        @Test
        @DisplayName("version list: A's single version only")
        void versions() {
            asVoid(PI_USER, () -> {
                var rows = awards.searchVersions("", "", "", "", "all", "sequence", 0, 50);
                assertThat(rows.content()).extracting(AwardVersionSearchResultResponse::awardId)
                        .containsExactly(A_V1);
                assertThat(awards.findVersions(A_V1, 0, 25).totalElements()).isEqualTo(1);
                assertDenied(() -> awards.findVersions(B_V2, 0, 25));
            });
        }

        @Test
        @DisplayName("attachments: A's are available; B's are refused")
        void attachments() {
            asVoid(PI_USER, () -> {
                assertThat(awards.findAttachments(A_V1, 0, 25).totalElements()).isEqualTo(1);
                assertThatCode(() -> awards.downloadAttachment(A_V1, ATT_A)).doesNotThrowAnyException();
                assertDenied(() -> awards.findAttachments(B_V2, 0, 25));
                assertDenied(() -> awards.downloadAttachment(B_V2, ATT_B));
            });
        }

        @Test
        @DisplayName("dashboard: 1 Award, 1 historical Award record")
        void dashboardCount() {
            DashboardDto counts = as(PI_USER, dashboard::counts);
            assertThat(counts.awards()).isEqualTo(1);
            assertThat(counts.awardHistoryRecords()).isEqualTo(1);
        }
    }

    // ── OAV: IO grant 9500312705 ─────────────────────────────────────

    @Nested
    @DisplayName("OAV - IO grant on account 9500312705")
    class OtherAuthorizedViewer {

        @Test
        @DisplayName("Award search returns C only; B (9500317253) is absent")
        void search() {
            AwardSearchResponse page = as(OAV_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50));

            assertThat(awardNumbers(page)).containsExactly(AWARD_C);
            assertThat(page.results().totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("direct URL: C opens; B is 404")
        void directUrl() {
            asVoid(OAV_USER, () -> {
                assertThatCode(() -> awards.findFamily(AWARD_C)).doesNotThrowAnyException();
                assertDenied(() -> awards.findFamily(AWARD_B));
                assertDenied(() -> awards.findFamily(AWARD_A));
            });
        }

        @Test
        @DisplayName("version list: C's versions only")
        void versions() {
            asVoid(OAV_USER, () -> {
                var rows = awards.searchVersions("", "", "", "", "all", "sequence", 0, 50);
                assertThat(rows.content()).extracting(AwardVersionSearchResultResponse::awardNumber)
                        .containsOnly(AWARD_C);
                assertThat(awards.findVersions(C_V3, 0, 25).totalElements()).isEqualTo(3);
                assertDenied(() -> awards.findVersions(B_V2, 0, 25));
            });
        }

        @Test
        @DisplayName("attachments: C's are available; B's are refused")
        void attachments() {
            asVoid(OAV_USER, () -> {
                assertThat(awards.findAttachments(C_V3, 0, 25).totalElements()).isEqualTo(1);
                assertThatCode(() -> awards.downloadAttachment(C_V3, ATT_C)).doesNotThrowAnyException();
                assertDenied(() -> awards.findAttachments(B_V2, 0, 25));
                assertDenied(() -> awards.downloadAttachment(B_V2, ATT_B));
            });
        }

        @Test
        @DisplayName("dashboard: 1 Award")
        void dashboardCount() {
            DashboardDto counts = as(OAV_USER, dashboard::counts);
            assertThat(counts.awards()).isEqualTo(1);
            assertThat(counts.awardHistoryRecords())
                    .as("every version of C carries the account number")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("removing the IO authorization removes access on the next request (US-OAV-001 #3)")
        void revocation() {
            String user = "ARP-SYN-OAV-REVOKE-0001";
            link(user, null);
            long grantId = grant(user, new GrantSpecification(
                    GrantType.IO, null, false, ACCOUNT_C, null, null, "named scenario: OAV revocation"));

            asVoid(user, () -> assertThatCode(() -> awards.findFamily(AWARD_C)).doesNotThrowAnyException());

            admin.revokeAccess(new InstitutionalIdentifier(ADMIN), grantId, "named scenario: OAV revocation");

            assertThatThrownBy(() -> as(user, () -> awards.search("", AwardSearchFilters.none(), 0, 50)))
                    .as("with no live grant left the caller is unprovisioned, not shown an empty archive")
                    .isInstanceOf(AccessNotProvisionedException.class);
            // Decision 1: the direct URL answers the same way - no grant left
            // means no research data, stated explicitly.
            asVoid(user, () -> assertThatThrownBy(() -> awards.findFamily(AWARD_C))
                    .isInstanceOf(AccessNotProvisionedException.class));
        }
    }

    // ── Central ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Central - CENTRAL grant")
    class Central {

        @Test
        @DisplayName("Award search returns every Award, including A, B and C")
        void search() {
            AwardSearchResponse page = as(CENTRAL_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50));

            assertThat(awardNumbers(page)).containsExactlyInAnyOrder(AWARD_A, AWARD_B, AWARD_C, AWARD_D);
            assertThat(page.results().totalElements()).isEqualTo(4);
        }

        @Test
        @DisplayName("direct URL, versions and attachments open on A, B and C")
        void everythingOpens() {
            asVoid(CENTRAL_USER, () -> {
                for (String award : List.of(AWARD_A, AWARD_B, AWARD_C)) {
                    assertThatCode(() -> awards.findFamily(award)).doesNotThrowAnyException();
                }
                assertThat(awards.findVersions(C_V3, 0, 25).totalElements()).isEqualTo(3);
                assertThat(awards.searchVersions("", "", "", "", "all", "sequence", 0, 50).totalElements())
                        .isEqualTo(7);
                assertThatCode(() -> awards.downloadAttachment(B_V2, ATT_B)).doesNotThrowAnyException();
                assertThatCode(() -> awards.downloadAttachment(C_V3, ATT_C)).doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("dashboard: archive-wide totals")
        void dashboardCount() {
            DashboardDto counts = as(CENTRAL_USER, dashboard::counts);
            assertThat(counts.awards()).isEqualTo(4);
            assertThat(counts.awardHistoryRecords()).isEqualTo(7);
        }
    }

    // ── Part 2a: two persisted UNIT grants union their reach ─────────

    @Nested
    @DisplayName("Two persisted UNIT grants for one identity")
    class TwoUnitGrants {

        @Test
        @DisplayName("both grants really are stored as two live rows")
        void twoRows() {
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM authz.access_grant
                    WHERE institutional_identifier = :g AND grant_type = 'UNIT' AND revoked_at IS NULL
                    """).param("g", TWO_UNIT_USER).query(Long.class).single())
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("search, direct URL and dashboard reach both units - and nothing else")
        void unionWithoutWidening() {
            AwardSearchResponse page = as(TWO_UNIT_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50));
            assertThat(awardNumbers(page)).containsExactlyInAnyOrder(AWARD_A, AWARD_B, AWARD_C);
            assertThat(page.results().totalElements()).isEqualTo(3);

            asVoid(TWO_UNIT_USER, () -> {
                assertThatCode(() -> awards.findFamily(AWARD_A)).doesNotThrowAnyException();
                assertThatCode(() -> awards.findFamily(AWARD_C)).doesNotThrowAnyException();
                // D sits at C's PARENT unit. Neither grant has descendants and
                // neither names that unit, so the union must not reach it.
                assertDenied(() -> awards.findFamily(AWARD_D));
            });

            DashboardDto counts = as(TWO_UNIT_USER, dashboard::counts);
            assertThat(counts.awards()).isEqualTo(3);
            assertThat(counts.awardHistoryRecords()).isEqualTo(6);
        }
    }

    // ── Part 2b: Global Search, Award and Proposal keyword legs ──────

    @Nested
    @DisplayName("Global Search - Award and Proposal keyword legs are scoped")
    class GlobalSearchScope {

        @Test
        @DisplayName("Department: only in-unit Awards and Proposals; counts agree with the rows")
        void department() {
            GlobalSearchResponse response = as(DEPT_USER,
                    () -> globalSearch.search("SYNTHETIC", Set.of("AWARD", "PROPOSAL")));

            assertThat(response.failedModules())
                    .as("a failed leg would hide rows and make every scope assertion pass vacuously")
                    .isEmpty();
            assertThat(identifiers(response, "AWARD")).containsExactlyInAnyOrder(AWARD_A, AWARD_B);
            assertThat(identifiers(response, "PROPOSAL")).containsExactly(PROPOSAL_IN_DEPT);

            GlobalSearchModuleTotal awardTotal = total(response, "AWARD");
            assertThat(awardTotal.returned()).isEqualTo(2);
            assertThat(awardTotal.totalMatches())
                    .as("the matched-record count must be scoped too, or it discloses C")
                    .isEqualTo(2L);
            assertThat(total(response, "PROPOSAL").returned()).isEqualTo(1);
            assertThat(response.totalResults()).isEqualTo(3);
        }

        @Test
        @DisplayName("PI: only their own Award and Proposal")
        void principalInvestigator() {
            GlobalSearchResponse response = as(PI_USER,
                    () -> globalSearch.search("SYNTHETIC", Set.of("AWARD", "PROPOSAL")));

            assertThat(response.failedModules()).isEmpty();
            assertThat(identifiers(response, "AWARD")).containsExactly(AWARD_A);
            assertThat(identifiers(response, "PROPOSAL")).containsExactly(PROPOSAL_IN_DEPT);
            assertThat(total(response, "AWARD").totalMatches()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Central: every Award and Proposal (the control)")
        void central() {
            GlobalSearchResponse response = as(CENTRAL_USER,
                    () -> globalSearch.search("SYNTHETIC", Set.of("AWARD", "PROPOSAL")));

            assertThat(response.failedModules()).isEmpty();
            assertThat(identifiers(response, "AWARD"))
                    .containsExactlyInAnyOrder(AWARD_A, AWARD_B, AWARD_C, AWARD_D);
            assertThat(identifiers(response, "PROPOSAL"))
                    .containsExactlyInAnyOrder(PROPOSAL_IN_DEPT, PROPOSAL_ELSEWHERE);
            assertThat(total(response, "AWARD").totalMatches()).isEqualTo(4L);
        }
    }

    // ── negative control ─────────────────────────────────────────────

    @Test
    @DisplayName("No-grant control: a linked identity with no grant is told it is unprovisioned")
    void noGrantControl() {
        assertThatThrownBy(() -> as(NO_GRANT_USER, () -> awards.search("", AwardSearchFilters.none(), 0, 50)))
                .isInstanceOf(AccessNotProvisionedException.class);
        assertThatThrownBy(() -> as(NO_GRANT_USER, dashboard::counts))
                .isInstanceOf(AccessNotProvisionedException.class);
    }

    // ── helpers: the simulated request ───────────────────────────────

    /**
     * Runs {@code work} as one request by {@code identifier}: a fresh request
     * scope (the outcome is cached per request in production) and a freshly
     * signed-in Cognito identity.
     */
    private static <T> T as(String identifier, Supplier<T> work) {
        SIGNED_IN.set(new ValidatedCognitoIdentity(ISSUER, subjectFor(identifier), "synthetic", Instant.now()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return work.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
            SIGNED_IN.set(null);
        }
    }

    private static void asVoid(String identifier, Runnable work) {
        as(identifier, () -> {
            work.run();
            return null;
        });
    }

    /** A denial must be the same 404-shaped exception as a missing record. */
    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(RecordNotAccessibleException.class)
                .isInstanceOf(NoSuchElementException.class);
    }

    private static List<String> awardNumbers(AwardSearchResponse page) {
        return page.results().content().stream().map(row -> row.awardNumber()).toList();
    }

    private static List<String> identifiers(GlobalSearchResponse response, String module) {
        return response.results().stream()
                .filter(item -> module.equals(item.module()))
                .map(GlobalSearchItemResponse::identifier)
                .distinct()
                .toList();
    }

    private static GlobalSearchModuleTotal total(GlobalSearchResponse response, String module) {
        return response.moduleTotals().stream()
                .filter(t -> module.equals(t.module()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no module total for " + module));
    }

    // ── helpers: provisioning through the real administrative path ────

    private static void provisionIdentitiesAndGrants() {
        admin.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@example.invalid", "named scenarios",
                ISSUER, subjectFor(ADMIN), null, "admin@example.invalid");

        link(DEPT_USER, DEPT_PERSON);
        grant(DEPT_USER, new GrantSpecification(
                GrantType.UNIT, DEPT_UNIT, false, null, null, null, "named scenario: department"));

        link(PI_USER, PI_PERSON);
        grant(PI_USER, new GrantSpecification(
                GrantType.CONTACT_DERIVATION, null, false, null, null, null, "named scenario: PI"));

        link(OAV_USER, null);
        grant(OAV_USER, new GrantSpecification(
                GrantType.IO, null, false, ACCOUNT_C, null, null, "named scenario: OAV"));

        link(CENTRAL_USER, null);
        grant(CENTRAL_USER, new GrantSpecification(
                GrantType.CENTRAL, null, false, null, null, null, "named scenario: central"));

        link(TWO_UNIT_USER, null);
        grant(TWO_UNIT_USER, new GrantSpecification(
                GrantType.UNIT, DEPT_UNIT, false, null, null, null, "named scenario: two units (1)"));
        grant(TWO_UNIT_USER, new GrantSpecification(
                GrantType.UNIT, OTHER_UNIT, false, null, null, null, "named scenario: two units (2)"));

        link(NO_GRANT_USER, null);
    }

    private static void link(String identifier, String personId) {
        admin.linkIdentity(new InstitutionalIdentifier(ADMIN), ISSUER, subjectFor(identifier),
                new InstitutionalIdentifier(identifier),
                personId == null ? null : new KualiPersonId(personId),
                identifier.toLowerCase() + "@example.invalid", "named scenarios");
    }

    private static long grant(String grantee, GrantSpecification specification) {
        return admin.grantAccess(new InstitutionalIdentifier(ADMIN),
                new InstitutionalIdentifier(grantee), specification).id();
    }

    /** Deterministic, distinct Cognito subject per identity (same scheme as the acceptance pack). */
    private static String subjectFor(String identifier) {
        String hex = String.format("%08x", Math.abs(identifier.hashCode()));
        return hex + "-0000-0000-0000-" + String.format("%012d", Math.abs(identifier.hashCode() % 1000000));
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private static void seedArchive() {
        jdbc.sql("""
                INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number) VALUES
                ('2573180000', 'SYNTHETIC PARENT OF 2573180018', NULL),
                ('2573180018', 'BIOMEDICAL GENETICS', '2573180000'),
                ('1330000000', 'SYNTHETIC PARENT OF 1330420000', NULL),
                ('1330420000', 'SYNTHETIC UNIT 1330420000', '1330000000')
                """).update();

        jdbc.sql("""
                INSERT INTO archive.award_version (
                    award_id, award_number, sequence_number, title,
                    status_description, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, award_effective_date,
                    is_primary_current, workflow_document_number, account_number
                ) VALUES
                (7100001, '212128-00002', 1, 'SYNTHETIC Award A', 'Active', 'SYN', 'SYNTHETIC Sponsor',
                 '2573180018', 'BIOMEDICAL GENETICS', DATE '2021-01-01', TRUE, 'SYN-DOC-A1', '9500316722'),
                (7200001, '211144-00007', 1, 'SYNTHETIC Award B v1', 'Closed', 'SYN', 'SYNTHETIC Sponsor',
                 '2573180018', 'BIOMEDICAL GENETICS', DATE '2020-01-01', FALSE, 'SYN-DOC-B1', '9500317253'),
                (7200002, '211144-00007', 2, 'SYNTHETIC Award B', 'Active', 'SYN', 'SYNTHETIC Sponsor',
                 '2573180018', 'BIOMEDICAL GENETICS', DATE '2020-01-01', TRUE, 'SYN-DOC-B2', '9500317253'),
                (7300001, '209407-00002', 1, 'SYNTHETIC Award C v1', 'Closed', 'SYN', 'SYNTHETIC Sponsor',
                 '1330420000', 'SYNTHETIC UNIT 1330420000', DATE '2019-01-01', FALSE, 'SYN-DOC-C1', '9500312705'),
                (7300002, '209407-00002', 2, 'SYNTHETIC Award C v2', 'Closed', 'SYN', 'SYNTHETIC Sponsor',
                 '1330420000', 'SYNTHETIC UNIT 1330420000', DATE '2019-01-01', FALSE, 'SYN-DOC-C2', '9500312705'),
                (7300003, '209407-00002', 3, 'SYNTHETIC Award C', 'Active', 'SYN', 'SYNTHETIC Sponsor',
                 '1330420000', 'SYNTHETIC UNIT 1330420000', DATE '2019-01-01', TRUE, 'SYN-DOC-C3', '9500312705'),
                (7400001, '990901-00001', 1, 'SYNTHETIC Award D at a parent unit', 'Active', 'SYN',
                 'SYNTHETIC Sponsor', '1330000000', 'SYNTHETIC PARENT OF 1330420000', DATE '2022-01-01', TRUE,
                 'SYN-DOC-D1', NULL)
                """).update();

        // Key personnel. The PI user is PI on A only; B and C have other PIs.
        jdbc.sql("""
                INSERT INTO archive.award_person (
                    award_person_id, award_id, award_number, sequence_number,
                    full_name, contact_role_code, person_id
                ) VALUES
                (71001, 7100001, '212128-00002', 1, 'SYNTHETIC PI ONE',   'PI', :pi),
                (72001, 7200001, '211144-00007', 1, 'SYNTHETIC PI TWO',   'PI', :otherPi),
                (72002, 7200002, '211144-00007', 2, 'SYNTHETIC PI TWO',   'PI', :otherPi),
                (73003, 7300003, '209407-00002', 3, 'SYNTHETIC PI THREE', 'PI', :cPi)
                """)
                .param("pi", PI_PERSON)
                .param("otherPi", OTHER_PI_PERSON)
                .param("cPi", C_PI_PERSON)
                .update();

        /*
         * The department user is a central-admin / PAFO unit contact on A, B
         * AND C - the DEV shape BU described. The unit_contact_type value is
         * synthetic. What matters is that a unit-contact listing is not an
         * authorization basis: the engine reads lead unit, award_person
         * roles, account number and hierarchy, and never this table, so C
         * must stay out of reach of a UNIT grant on 2573180018.
         */
        jdbc.sql("""
                INSERT INTO archive.award_unit_contact (
                    award_unit_contact_id, award_id, award_number, sequence_number,
                    person_id, full_name, unit_contact_type
                ) VALUES
                (81001, 7100001, '212128-00002', 1, :p, 'SYNTHETIC PAFO ADMIN', 'ADMINISTRATOR'),
                (81002, 7200002, '211144-00007', 2, :p, 'SYNTHETIC PAFO ADMIN', 'ADMINISTRATOR'),
                (81003, 7300003, '209407-00002', 3, :p, 'SYNTHETIC PAFO ADMIN', 'ADMINISTRATOR')
                """).param("p", DEPT_PERSON).update();

        // One archived, uploaded attachment on each Award's current version.
        jdbc.sql("""
                INSERT INTO archive.attachment_object (
                    file_id, file_name, content_type, file_size_bytes, s3_bucket, s3_key, upload_status
                ) VALUES
                (91001, 'synthetic-a.pdf', 'application/pdf', 3, 'synthetic-bucket', 'a.pdf', 'UPLOADED'),
                (91002, 'synthetic-b.pdf', 'application/pdf', 3, 'synthetic-bucket', 'b.pdf', 'UPLOADED'),
                (91003, 'synthetic-c.pdf', 'application/pdf', 3, 'synthetic-bucket', 'c.pdf', 'UPLOADED'),
                (91004, 'synthetic-d.pdf', 'application/pdf', 3, 'synthetic-bucket', 'd.pdf', 'UPLOADED')
                """).update();
        jdbc.sql("""
                INSERT INTO archive.award_attachment (
                    award_attachment_id, award_id, award_number, sequence_number, file_id, description
                ) VALUES
                (61001, 7100001, '212128-00002', 1, 91001, 'SYNTHETIC attachment A'),
                (61002, 7200002, '211144-00007', 2, 91002, 'SYNTHETIC attachment B'),
                (61003, 7300003, '209407-00002', 3, 91003, 'SYNTHETIC attachment C'),
                (61004, 7400001, '990901-00001', 1, 91004, 'SYNTHETIC attachment D')
                """).update();

        // Proposals for the Global Search Proposal leg.
        jdbc.sql("""
                INSERT INTO archive.proposal_version (
                    proposal_id, proposal_number, version_number, title,
                    proposal_sequence_status, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, principal_investigator_name, document_number
                ) VALUES
                (8100001, 'SYN-PROP-A', 1, 'SYNTHETIC proposal in Biomedical Genetics', 'Approved',
                 'SYN', 'SYNTHETIC Sponsor', '2573180018', 'BIOMEDICAL GENETICS', 'SYNTHETIC PI ONE', 'SYN-PDOC-A'),
                (8300001, 'SYN-PROP-C', 1, 'SYNTHETIC proposal elsewhere', 'Approved',
                 'SYN', 'SYNTHETIC Sponsor', '1330420000', 'SYNTHETIC UNIT 1330420000', 'SYNTHETIC PI THREE',
                 'SYN-PDOC-C')
                """).update();
        jdbc.sql("""
                INSERT INTO archive.proposal_person (
                    proposal_person_id, proposal_id, proposal_number,
                    sequence_number, person_id, full_name, contact_role_code
                ) VALUES
                (82001, 8100001, 'SYN-PROP-A', 1, :pi, 'SYNTHETIC PI ONE',   'PI'),
                (82002, 8300001, 'SYN-PROP-C', 1, :cPi, 'SYNTHETIC PI THREE', 'PI')
                """)
                .param("pi", PI_PERSON)
                .param("cPi", C_PI_PERSON)
                .update();
    }

    // ── plumbing ─────────────────────────────────────────────────────

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
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

    private static <T> T proxy(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    private static ArchiveAdminService proxyClass(ArchiveAdminService target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (ArchiveAdminService) factory.getProxy();
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrations() {
        Path c = Path.of("database", "migrations");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations");
    }
}
