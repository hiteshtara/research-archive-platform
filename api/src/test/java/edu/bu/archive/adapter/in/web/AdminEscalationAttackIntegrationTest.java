package edu.bu.archive.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcAccessGrantRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditSink;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminIdentityRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminRoleRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcIdentityLinkRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcRelinkApprovalRepository;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.RelinkApprovalRepository;

/**
 * The escalation paths independent QA proved against PR #161 (N-161-1,
 * N-161-2, and the administrator-suspension and self-unlink lockouts), as
 * attacks rather than as rule tests.
 *
 * <p>Written against only the API surface that exists on {@code main} as well
 * as on this branch - {@code relinkIdentity}, {@code setSuspended} and the
 * HTTP unlink endpoint - with approvals and prior unlinks seeded in SQL. That
 * is what lets the same class be run against unmodified {@code main} to show
 * each attack succeeds there (every "is refused" test fails), and against
 * this branch to show it is closed.
 *
 * <p>Every refusal asserts that identity_link, access_grant,
 * admin_role_member, person_status, relink_approval and access_audit are
 * unchanged. Synthetic identities, throwaway database.
 */
@Testcontainers
class AdminEscalationAttackIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_ATTACK";
    private static final String ADMIN = "SYN-ATK-ADMIN";
    private static final String ADMIN_B = "SYN-ATK-ADMIN-B";
    private static final String CAROL = "SYN-ATK-CAROL";
    private static final String REASON = "approved by the department chair";
    private static final List<String> TABLES = List.of(
            "identity_link", "access_grant", "admin_role_member", "person_status",
            "relink_approval", "access_audit");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static ArchiveAdminService service;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void setUp() throws Exception {
        List<Path> migrations;
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations") : Path.of("..", "database", "migrations");
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AdminEscalationAttackIntegrationTest::version))
                    .toList();
        }
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }
        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
        var target = new ArchiveAdminService(
                port(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class),
                port(new JdbcAdminAuditSink(jdbc), AdminAuditSink.class),
                port(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class),
                port(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class),
                new JdbcAdminAuditRepository(jdbc),
                port(new JdbcRelinkApprovalRepository(jdbc), RelinkApprovalRepository.class));
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        service = (ArchiveAdminService) factory.getProxy();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM authz.relink_approval").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.person_status").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();
        for (String who : List.of(ADMIN, ADMIN_B)) {
            jdbc.sql("""
                    INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                        institutional_identifier, method, status, verified_by, verified_at)
                    VALUES (:iss, :sub, :who, 'ADMIN_VERIFIED', 'ACTIVE', 'operator', now())
                    """).param("iss", ISSUER).param("sub", "sub-" + who).param("who", who).update();
            jdbc.sql("""
                    INSERT INTO authz.admin_role_member (institutional_identifier, status,
                        granted_by, granted_at, reason)
                    VALUES (:who, 'ACTIVE', 'BOOTSTRAP', now(), 'synthetic fixture')
                    """).param("who", who).update();
        }
    }

    // ---- N-161-1: making any Cognito user an administrator -------------

    @Test
    @DisplayName("N-161-1: relinking an unlinked administrator without approval is refused")
    void relinkingAnAdministratorWithoutApprovalIsRefused() {
        revokeLinksOf(ADMIN_B);
        Map<String, String> before = snapshot();

        Throwable refused = catchThrowable(() -> service.relinkIdentity(id(ADMIN), ISSUER,
                "ACCOMPLICE-SUB", id(ADMIN_B), null, null, REASON));

        assertThat(refused).as("a relink that hands over ARCHIVE_ADMIN needs a second approver")
                .isNotNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(resolve("ACCOMPLICE-SUB")).isNotInstanceOf(IdentityResolution.Mapped.class);
    }

    @Test
    @DisplayName("N-161-1: unlink then relink of another administrator is refused")
    void unlinkThenRelinkOfAnAdministratorIsRefused() throws Exception {
        mvc(ADMIN).perform(delete("/api/v1/admin/identities/" + ADMIN_B + "/links/"
                + activeLinkOf(ADMIN_B)).param("reason", "Cognito account compromised"));
        Map<String, String> before = snapshot();

        Throwable refused = catchThrowable(() -> service.relinkIdentity(id(ADMIN), ISSUER,
                "ACCOMPLICE-SUB", id(ADMIN_B), null, null, REASON));

        assertThat(refused).as("the accomplice must not become ARCHIVE_ADMIN").isNotNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(resolve("ACCOMPLICE-SUB")).isNotInstanceOf(IdentityResolution.Mapped.class);
        assertThat(count("relink_approval")).isZero();
    }

    @Test
    @DisplayName("an administrator cannot unlink themselves")
    void selfUnlinkIsRefused() throws Exception {
        Map<String, String> before = snapshot();
        int status = mvc(ADMIN).perform(delete("/api/v1/admin/identities/" + ADMIN + "/links/"
                        + activeLinkOf(ADMIN)).param("reason", REASON))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("one administrator cannot suspend another while restore needs approval")
    void suspendingAnotherAdministratorIsRefused() {
        Map<String, String> before = snapshot();
        Throwable refused = catchThrowable(() ->
                service.setSuspended(id(ADMIN), id(ADMIN_B), true, REASON));
        assertThat(refused).isNotNull();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---- N-161-2: an approval executed with a different principal ------

    @Test
    @DisplayName("N-161-2: an approved relink cannot change the Kuali person id")
    void relinkCannotChangeTheKualiPersonId() {
        seedApprovedRelinkForCarol();
        Map<String, String> before = snapshot();

        Throwable refused = catchThrowable(() -> service.relinkIdentity(id(ADMIN), ISSUER,
                "sub-carol-new", id(CAROL), new KualiPersonId("VICTIM-PI"), null, REASON));

        assertThat(refused).as("the approval did not cover VICTIM-PI").isNotNull();
        assertThat(snapshot()).as("approval not consumed, nothing linked").isEqualTo(before);

        // Non-vacuity: the approved operation, with the identity's own principal, executes.
        service.relinkIdentity(id(ADMIN), ISSUER, "sub-carol-new", id(CAROL),
                new KualiPersonId("P-CAROL"), null, REASON);
        assertThat(jdbc.sql("""
                SELECT kuali_person_id FROM authz.identity_link
                 WHERE institutional_identifier = :w AND status = 'ACTIVE'
                """).param("w", CAROL).query(String.class).single()).isEqualTo("P-CAROL");
    }

    @Test
    @DisplayName("N-161-2: a principal cannot be added (null to value) by a relink either")
    void relinkCannotAddAKualiPersonId() {
        jdbc.sql("""
                INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                    institutional_identifier, method, status, verified_by, verified_at,
                    revoked_by, revoked_at)
                VALUES (:iss, 'sub-carol-old', :w, 'ADMIN_VERIFIED', 'REVOKED',
                        'operator', now(), 'operator', now())
                """).param("iss", ISSUER).param("w", CAROL).update();
        Map<String, String> before = snapshot();

        Throwable refused = catchThrowable(() -> service.relinkIdentity(id(ADMIN), ISSUER,
                "sub-carol-new", id(CAROL), new KualiPersonId("VICTIM-PI"), null, REASON));

        assertThat(refused).isNotNull();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---- concurrency on one approval -----------------------------------

    @Test
    @DisplayName("two concurrent relinks against one APPROVED approval: exactly one succeeds")
    void oneApprovalAuthorisesExactlyOneRelink() throws Exception {
        long approvalId = seedApprovedRelinkForCarol();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> attempt = () -> {
                ready.countDown();
                go.await(20, TimeUnit.SECONDS);
                try {
                    service.relinkIdentity(id(ADMIN), ISSUER, "sub-carol-new", id(CAROL),
                            new KualiPersonId("P-CAROL"), null, REASON);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            };
            Future<Throwable> first = pool.submit(attempt);
            Future<Throwable> second = pool.submit(attempt);
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(
                    first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(java.util.Objects::isNull).count())
                    .as("exactly one relink succeeds: %s", outcomes).isEqualTo(1);
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM authz.identity_link
                     WHERE institutional_identifier = :w AND status = 'ACTIVE'
                    """).param("w", CAROL).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM authz.access_audit
                     WHERE action = 'IDENTITY_RELINKED'
                    """).query(Integer.class).single()).as("the loser wrote nothing").isEqualTo(1);
            assertThat(approvalStatus(approvalId)).isEqualTo("CONSUMED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a consumed approval cannot authorise a second relink")
    void aConsumedApprovalCannotBeReused() {
        long approvalId = seedApprovedRelinkForCarol();
        service.relinkIdentity(id(ADMIN), ISSUER, "sub-carol-new", id(CAROL),
                new KualiPersonId("P-CAROL"), null, REASON);
        assertThat(approvalStatus(approvalId)).isEqualTo("CONSUMED");

        revokeLinksOf(CAROL);
        Map<String, String> before = snapshot();
        Throwable refused = catchThrowable(() -> service.relinkIdentity(id(ADMIN), ISSUER,
                "sub-carol-new", id(CAROL), new KualiPersonId("P-CAROL"), null, REASON));
        assertThat(refused).isNotNull();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---- helpers -------------------------------------------------------

    /**
     * CAROL: a previous (revoked) link carrying principal P-CAROL, one active
     * grant, and an APPROVED relink to sub-carol-new requested by ADMIN and
     * approved by ADMIN_B. Seeded in SQL so this class runs unchanged on main.
     */
    private long seedApprovedRelinkForCarol() {
        jdbc.sql("""
                INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                    institutional_identifier, kuali_person_id, method, status, verified_by,
                    verified_at, revoked_by, revoked_at)
                VALUES (:iss, 'sub-carol-old', :w, 'P-CAROL', 'ADMIN_VERIFIED', 'REVOKED',
                        'operator', now(), 'operator', now())
                """).param("iss", ISSUER).param("w", CAROL).update();
        jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type,
                    unit_number, granted_by, reason)
                VALUES (:w, 'UNIT', '1234', 'seed', 'seeded grant for Carol')
                """).param("w", CAROL).update();
        return jdbc.sql("""
                INSERT INTO authz.relink_approval (institutional_identifier,
                    proposed_cognito_issuer, proposed_cognito_subject, requested_by,
                    grants_at_request, reason, approved_by, approved_at, status)
                VALUES (:w, :iss, 'sub-carol-new', :requester, 1, 'Cognito pool replaced',
                        :approver, now(), 'APPROVED')
                RETURNING approval_id
                """).param("w", CAROL).param("iss", ISSUER).param("requester", ADMIN)
                .param("approver", ADMIN_B).query(Long.class).single();
    }

    private String approvalStatus(long approvalId) {
        return jdbc.sql("SELECT status FROM authz.relink_approval WHERE approval_id = :id")
                .param("id", approvalId).query(String.class).single();
    }

    private void revokeLinksOf(String who) {
        jdbc.sql("""
                UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = 'operator',
                       revoked_at = now()
                 WHERE institutional_identifier = :w AND status = 'ACTIVE'
                """).param("w", who).update();
    }

    private long activeLinkOf(String who) {
        return jdbc.sql("""
                SELECT identity_link_id FROM authz.identity_link
                 WHERE institutional_identifier = :w AND status = 'ACTIVE'
                """).param("w", who).query(Long.class).single();
    }

    private IdentityResolution resolve(String cognitoSubject) {
        return new IdentityResolver(new JdbcIdentityLinkRepository(jdbc))
                .resolve(new ValidatedCognitoIdentity(ISSUER, cognitoSubject));
    }

    private Map<String, String> snapshot() {
        Map<String, String> digest = new LinkedHashMap<>();
        for (String table : TABLES) {
            digest.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' "
                            + "ORDER BY t::text)), '') FROM authz." + table + " t")
                    .query(String.class).single());
        }
        return digest;
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM authz." + table).query(Integer.class).single();
    }

    private MockMvc mvc(String actor) {
        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(false);
        properties.setAdministrationEnabled(true);
        properties.setAdministrationWritesEnabled(true);
        CurrentIdentityProvider caller = () ->
                Optional.of(new ValidatedCognitoIdentity(ISSUER, "sub-" + actor));
        var controller = new AdminAuthorizationController(service, caller,
                new IdentityResolver(new JdbcIdentityLinkRepository(jdbc)), properties,
                Clock.systemUTC());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static InstitutionalIdentifier id(String value) {
        return new InstitutionalIdentifier(value);
    }

    private static <T> T port(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
