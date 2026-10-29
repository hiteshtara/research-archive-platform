package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.GrantOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.GrantProvisioningCommand;
import edu.bu.archive.application.authorization.admin.provisioning.GrantRequest;

/**
 * The operator-only grant utility, against a real schema.
 *
 * <p>It writes nothing itself — every change goes through
 * {@link ArchiveAdminService}, which owns the transaction and the audit
 * row. These tests therefore check two things: that the utility refuses
 * what it should, and that when it does act, the grant and its audit row
 * appear together and nothing else does.
 */
@Testcontainers
@DisplayName("Operator-only grant provisioning")
class GrantProvisioningIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ACCOUNT = "770203350335";
    private static final String ENVIRONMENT = "dev";
    private static final String ADMIN = "ARP-ADMN0001-00000001";
    private static final String GRANTEE = "ARP-GRANTEE1-00000001";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService service;
    private static AdminRoleRepository adminRoles;
    private static AccessGrantRepository grants;

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
                    .sorted(Comparator.comparingInt(GrantProvisioningIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }

        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
        adminRoles = proxy(new JdbcAdminRoleRepository(jdbc), AdminRoleRepository.class);
        grants = proxy(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        AdminIdentityRepository identities =
                proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class);
        service = proxyClass(new ArchiveAdminService(
                adminRoles,
                proxy(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                grants, identities, new JdbcAdminAuditRepository(jdbc)));
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();
        // A real administrator must exist; grants are issued BY one.
        service.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@bu.edu", "test fixture",
                "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST",
                "00000000-0000-0000-0000-000000000001", null, "admin@bu.edu");
        jdbc.sql("TRUNCATE authz.access_audit").update();   // ignore bootstrap rows
    }

    @Nested
    @DisplayName("Writes nothing unless confirmed")
    class DryRun {

        @Test
        void aDryRunCreatesNoGrantAndNoAuditRow() {
            GrantOutcome outcome = command().run(req().dryRun(true).build());
            assertThat(outcome.result())
                    .as("problems: %s", outcome.problems())
                    .isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(grantCount()).isZero();
            assertThat(auditCount()).isZero();
        }

        @Test
        void omittingConfirmIsTreatedAsADryRunNotAnError() {
            GrantOutcome outcome = command().run(req().build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.DRY_RUN_OK);
            assertThat(outcome.message()).contains("--confirm was not given");
            assertThat(grantCount()).isZero();
        }
    }

    @Nested
    @DisplayName("A confirmed grant")
    class Confirmed {

        @Test
        void createsTheGrantAndItsAuditRow() {
            GrantOutcome outcome = command().run(req().confirmed(true).build());
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.APPLIED);
            assertThat(outcome.grantId()).isNotNull();

            assertThat(grantCount()).isEqualTo(1);
            assertThat(jdbc.sql("""
                            SELECT grant_type FROM authz.access_grant
                            WHERE institutional_identifier = :g
                            """).param("g", GRANTEE).query(String.class).single())
                    .isEqualTo("UNIT");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");
        }

        @Test
        void theAuditRowCarriesOperatorAndApprovalReference() {
            command().run(req().confirmed(true).build());
            String note = jdbc.sql("SELECT detail ->> 'note' FROM authz.access_audit")
                    .query(String.class).single();
            assertThat(note)
                    .contains("operator=operator@bu.edu")
                    .contains("approval=APPROVAL-2026-10-29")
                    .contains("first DEV grant");
        }

        @Test
        void aRevocationIsAudited() {
            GrantOutcome granted = command().run(req().confirmed(true).build());
            service.revokeAccess(new InstitutionalIdentifier(ADMIN), granted.grantId(), "no longer needed");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_REVOKED");
        }

        @Test
        void aModificationIsAudited() {
            GrantOutcome granted = command().run(req().confirmed(true).build());
            service.modifyAccess(new InstitutionalIdentifier(ADMIN), granted.grantId(), null, true, "widen to descendants");
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED", "ACCESS_MODIFIED");
        }
    }

    @Nested
    @DisplayName("Refusals leave nothing behind")
    class Refusals {

        @Test
        void aWrongAwsAccountIsRefused() {
            assertRefused(req().account("999999999999").confirmed(true).build(), "AWS account");
        }

        @Test
        void aWrongEnvironmentIsRefused() {
            assertRefused(req().environment("test").confirmed(true).build(), "environment");
        }

        @Test
        void anUnknownGrantTypeIsRefused() {
            assertRefused(req().type("SUPERUSER").confirmed(true).build(), "unknown grant type");
        }

        @Test
        void anEmailAsTheGranteeIsRefused() {
            assertRefused(req().grantee("mukadder@bu.edu").confirmed(true).build(),
                    "looks like an email address");
        }

        @Test
        void aUnitGrantWithoutAUnitNumberIsRefused() {
            assertRefused(req().type("UNIT").unit(null).confirmed(true).build(),
                    "UNIT grants need --unit-number");
        }

        @Test
        void anIoValueOnAUnitGrantIsRefused() {
            assertRefused(req().type("UNIT").io("9500234095").confirmed(true).build(),
                    "meaningless for a UNIT grant");
        }

        @Test
        void aScopeArgumentOnACentralGrantIsRefused() {
            assertRefused(req().type("CENTRAL").unit("1250000000").confirmed(true).build(),
                    "CENTRAL grants take no scope");
        }

        @Test
        void aMissingApprovalReferenceIsRefused() {
            assertRefused(req().approval(null).confirmed(true).build(), "--approval-reference");
        }

        @Test
        void anActorWithNoAdministratorMembershipIsRefused() {
            assertRefused(req().actor("ARP-N0TADMN1-00000001").confirmed(true).build(),
                    "holds no ACTIVE ARCHIVE_ADMIN membership");
        }

        @Test
        void anIdenticalActiveGrantIsRefusedRatherThanDuplicated() {
            command().run(req().confirmed(true).build());
            assertThat(grantCount()).isEqualTo(1);

            GrantOutcome second = command().run(req().confirmed(true).build());
            assertThat(second.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", second.problems()))
                    .contains("identical active grant already exists");
            assertThat(grantCount()).as("still exactly one").isEqualTo(1);
            assertThat(auditActions()).containsExactly("ACCESS_GRANTED");
        }

        private void assertRefused(GrantRequest request, String expected) {
            GrantOutcome outcome = command().run(request);
            assertThat(outcome.result()).isEqualTo(GrantOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems())).contains(expected);
            assertThat(grantCount()).as("a refusal writes no grant").isZero();
            assertThat(auditCount()).as("a refusal writes no audit row").isZero();
        }
    }

    @Test
    void concurrentIdenticalRequestsCanRaceAndThisIsNotYetGuarded() throws Exception {
        /*
         * CHARACTERISATION, not a guarantee.
         *
         * The duplicate check is a read followed by a write, with no lock
         * between them and - critically - NO database constraint behind it:
         * authz.access_grant has only ix_access_grant_grantee, a plain
         * index. Two concurrent identical requests can therefore both pass
         * the check and both insert.
         *
         * Sequentially the check works, which is the realistic operator
         * case for a utility run by a person one grant at a time. But the
         * invariant "at most one active grant per grantee and scope" is NOT
         * enforced, and a later revocation cannot tell two identical rows
         * apart.
         *
         * The fix is a partial unique index (the same shape as
         * ux_admin_role_member_active and ux_identity_link_active_identity),
         * which is a migration and therefore out of scope here. This test
         * records today's behaviour so the gap is visible rather than
         * assumed away.
         */
        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<GrantOutcome>> jobs = java.util.Collections.nCopies(attempts,
                    () -> command().run(req().confirmed(true).build()));
            for (Future<GrantOutcome> f : pool.invokeAll(jobs)) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // a losing attempt may fail; the row count is what matters
                }
            }
            int active = activeGrantsFor(GRANTEE);
            assertThat(active).as("at least one succeeded").isGreaterThanOrEqualTo(1);
            if (active > 1) {
                System.out.println("NOTE: " + active + " identical active grants were created "
                        + "concurrently - no unique index prevents this today");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSequentialDuplicateCheckDoesHold() {
        command().run(req().confirmed(true).build());
        GrantOutcome second = command().run(req().confirmed(true).build());
        assertThat(second.result()).isEqualTo(GrantOutcome.Result.REFUSED);
        assertThat(activeGrantsFor(GRANTEE)).isEqualTo(1);
    }

    @Test
    void anAdministratorWithNoDataGrantStillHasNoGrants() {
        // Bootstrapping created the administrator in reset(). It must not
        // have given them any research-data access.
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = :g")
                .param("g", ADMIN).query(Integer.class).single())
                .isZero();
    }

    // ---------------- helpers ----------------

    private static GrantProvisioningCommand command() {
        return new GrantProvisioningCommand(ACCOUNT, ENVIRONMENT, service, adminRoles, grants);
    }

    private static Req req() {
        return new Req();
    }

    private static final class Req {
        private String account = ACCOUNT;
        private String environment = ENVIRONMENT;
        private String grantee = GRANTEE;
        private String actor = ADMIN;
        private String type = "UNIT";
        private String unit = "1250000000";
        private boolean descendants;
        private String io;
        private String operator = "operator@bu.edu";
        private String reason = "first DEV grant";
        private String approval = "APPROVAL-2026-10-29";
        private boolean dryRun;
        private boolean confirmed;

        Req account(String v) { this.account = v; return this; }
        Req environment(String v) { this.environment = v; return this; }
        Req grantee(String v) { this.grantee = v; return this; }
        Req actor(String v) { this.actor = v; return this; }
        Req type(String v) { this.type = v; return this; }
        Req unit(String v) { this.unit = v; return this; }
        Req io(String v) { this.io = v; return this; }
        Req approval(String v) { this.approval = v; return this; }
        Req dryRun(boolean v) { this.dryRun = v; return this; }
        Req confirmed(boolean v) { this.confirmed = v; return this; }

        GrantRequest build() {
            return new GrantRequest(account, environment, grantee, actor, type, unit,
                    descendants, io, operator, reason, approval, dryRun, confirmed);
        }
    }

    private static int grantCount() {
        return jdbc.sql("SELECT count(*) FROM authz.access_grant").query(Integer.class).single();
    }

    private static int activeGrantsFor(String grantee) {
        return jdbc.sql("""
                        SELECT count(*) FROM authz.access_grant
                        WHERE institutional_identifier = :g AND revoked_at IS NULL
                        """).param("g", grantee).query(Integer.class).single();
    }

    private static int auditCount() {
        return jdbc.sql("SELECT count(*) FROM authz.access_audit").query(Integer.class).single();
    }

    private static List<String> auditActions() {
        return jdbc.sql("SELECT action FROM authz.access_audit ORDER BY audit_id")
                .query(String.class).list();
    }

    private static <T> T proxy(Object target, Class<T> port) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    private static ArchiveAdminService proxyClass(ArchiveAdminService target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                txManager, new AnnotationTransactionAttributeSource()));
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
