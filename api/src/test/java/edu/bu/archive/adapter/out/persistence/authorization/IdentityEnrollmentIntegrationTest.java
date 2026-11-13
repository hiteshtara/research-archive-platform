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
import org.springframework.transaction.support.TransactionTemplate;
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
import edu.bu.archive.application.authorization.admin.provisioning.EcsTaskMetadataRuntimeIdentity;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcIdentityLinkRepository;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityEnrollmentCommand;
import edu.bu.archive.application.authorization.admin.provisioning.EnrollmentRequest;
import edu.bu.archive.application.authorization.admin.provisioning.EnrollmentOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;
import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.admin.DuplicateActiveGrantException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

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
@DisplayName("Operator-only identity enrollment")
class IdentityEnrollmentIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ACCOUNT = "770203350335";
    private static final String ENVIRONMENT = "dev";
    private static final String ADMIN = "ARP-ADMN0001-00000001";
    private static final String GRANTEE = "ARP-GRANTEE1-00000001";
    private static final String OTHER_GRANTEE = "ARP-GRANTEE2-00000002";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService service;
    private static AdminRoleRepository adminRoles;
    private static AccessGrantRepository grants;
    private static AdminIdentityRepository identityAdmin;
    private static edu.bu.archive.application.authorization.IdentityLinkRepository links;

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
                    .sorted(Comparator.comparingInt(IdentityEnrollmentIntegrationTest::version))
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
        identityAdmin = identities;
        links = proxy(new JdbcIdentityLinkRepository(jdbc),
                edu.bu.archive.application.authorization.IdentityLinkRepository.class);
        service = proxyClass(new ArchiveAdminService(
                adminRoles,
                proxy(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                grants, identities, new JdbcAdminAuditRepository(jdbc)));
    }


    @BeforeEach
    void reset() {
        // DELETE is refused by tr_access_audit_append_only; the table is
        // append-only by trigger, so the fixture has to TRUNCATE it.
        jdbc.sql("TRUNCATE authz.access_audit").update();
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        /*
         * bootstrap() is @Transactional(propagation = MANDATORY), so the
         * fixture has to supply the transaction the production caller
         * normally would. Bootstrapping through the repository rather than
         * through ArchiveAdminService.bootstrapFirstAdministratorWithIdentity
         * is deliberate: it leaves authz.identity_link genuinely empty, so
         * "exactly one link" below means the enrolled identity and nothing
         * else.
         */
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                adminRoles.bootstrap(new InstitutionalIdentifier(ADMIN), "BOOTSTRAP", "test"));
    }

    private static final String ISSUER =
            "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c";
    private static final String POOL = "us-east-1_VJ4ekQ27c";
    /*
     * Deliberately a fabricated sub, not a real one from the DEV pool. The
     * administrator's own subject appears in BootstrapNonWebStartupTest, and
     * reusing it here would make the fixture for enrolling a NON-administrator
     * a copy-paste away from enrolling the administrator - the exact mistake
     * this whole utility exists to avoid.
     */
    private static final String SUBJECT = "0fa15e00-0000-4000-8000-000000000fab";

    /** A directory that reports one enabled, federated user for SUBJECT. */
    private static CognitoDirectory pool() {
        return (p, s) -> SUBJECT.equals(s)
                ? java.util.Optional.of(new CognitoDirectory.PoolUser(
                        s, "BUShibboleth_test@bu.edu", true, "EXTERNAL_PROVIDER"))
                : java.util.Optional.empty();
    }

    private static IdentityEnrollmentCommand command() {
        return command(pool());
    }

    private static IdentityEnrollmentCommand command(CognitoDirectory directory) {
        return new IdentityEnrollmentCommand(service, adminRoles, identityAdmin, links,
                runtime(ACCOUNT, "arn:aws:ecs:us-east-1:770203350335:cluster/"
                        + "research-archive-platform-dev-api"))
                .withDirectory(directory);
    }

    private static RuntimeIdentity runtime(String account, String cluster) {
        return new RuntimeIdentity() {
            @Override public java.util.Optional<String> awsAccountId() {
                return java.util.Optional.ofNullable(account);
            }
            @Override public java.util.Optional<String> clusterName() {
                return java.util.Optional.ofNullable(cluster);
            }
        };
    }

    private static Req req() {
        return new Req();
    }

    private static final class Req {
        private String account = ACCOUNT;
        private String environment = ENVIRONMENT;
        private String actor = ADMIN;
        private String issuer = ISSUER;
        private String subject = SUBJECT;
        private String pool = POOL;
        private String loginName = "test@bu.edu";
        private String operator = "operator@bu.edu";
        private String reason = "DEV lifecycle test grantee";
        private String approval = "DEV-AUTHZ-ENROL-20261103";
        private boolean dryRun;
        private boolean confirmed;

        Req actor(String v) { this.actor = v; return this; }
        Req issuer(String v) { this.issuer = v; return this; }
        Req subject(String v) { this.subject = v; return this; }
        Req pool(String v) { this.pool = v; return this; }
        Req approval(String v) { this.approval = v; return this; }
        Req dryRun(boolean v) { this.dryRun = v; return this; }
        Req confirmed(boolean v) { this.confirmed = v; return this; }

        EnrollmentRequest build() {
            return new EnrollmentRequest(account, environment, actor, issuer, subject, pool,
                    loginName, operator, reason, approval, dryRun, confirmed);
        }
    }

    // ------------------------------------------------------------------

    @Test
    void aDryRunWritesNothingAndMintsNoIdentifier() {
        EnrollmentOutcome outcome = command().run(req().dryRun(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.DRY_RUN_OK);
        assertThat(outcome.mintedIdentifier()).as("a dry run must not reserve an identifier").isNull();
        assertThat(linkCount()).isZero();
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void omittingConfirmIsADryRunNotAnError() {
        EnrollmentOutcome outcome = command().run(req().build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.DRY_RUN_OK);
        assertThat(linkCount()).isZero();
    }

    @Test
    void aConfirmedEnrollmentCreatesOneLinkAndOneAuditRow() {
        EnrollmentOutcome outcome = command().run(req().confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.APPLIED);
        assertThat(outcome.mintedIdentifier()).startsWith("ARP-");
        assertThat(linkCount()).isEqualTo(1);
        assertThat(auditActions()).containsExactly("IDENTITY_LINKED");

        assertThat(jdbc.sql("SELECT method FROM authz.identity_link").query(String.class).single())
                .isEqualTo("ADMIN_VERIFIED");
        assertThat(jdbc.sql("SELECT status FROM authz.identity_link").query(String.class).single())
                .isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT institutional_identifier FROM authz.identity_link")
                .query(String.class).single())
                .isEqualTo(outcome.mintedIdentifier());
    }

    @Test
    void enrollmentCreatesNoAdministratorMembershipAndNoGrant() {
        EnrollmentOutcome outcome = command().run(req().confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.APPLIED);

        assertThat(jdbc.sql("SELECT count(*) FROM authz.admin_role_member")
                .query(Integer.class).single())
                .as("only the bootstrapped administrator, no new membership").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = :i")
                .param("i", outcome.mintedIdentifier()).query(Integer.class).single())
                .as("an enrolled identity sees nothing until a grant is issued").isZero();
        assertThat(grantCount()).isZero();
    }

    @Test
    void aSecondEnrollmentOfTheSameSubjectIsRefused() {
        assertThat(command().run(req().confirmed(true).build()).result())
                .isEqualTo(EnrollmentOutcome.Result.APPLIED);

        EnrollmentOutcome again = command().run(req().confirmed(true).build());
        assertThat(again.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", again.problems())).contains("already linked");
        assertThat(linkCount()).as("still one link").isEqualTo(1);
        assertThat(auditActions()).containsExactly("IDENTITY_LINKED");
    }

    @Test
    void concurrentEnrollmentsOfOneSubjectProduceExactlyOneLink() throws Exception {
        int attempts = 8;
        ExecutorService exec = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<EnrollmentOutcome>> jobs = java.util.Collections.nCopies(attempts,
                    () -> command().run(req().confirmed(true).build()));
            int applied = 0;
            for (Future<EnrollmentOutcome> f : exec.invokeAll(jobs)) {
                try {
                    if (f.get().result() == EnrollmentOutcome.Result.APPLIED) {
                        applied++;
                    }
                } catch (ExecutionException ignored) {
                    // A loser may surface the repository's IllegalStateException;
                    // V085's ux_identity_link_active_cognito is the backstop and
                    // the row counts below are what matter.
                }
            }
            /*
             * Exactly one, not "at most one": a link is only ever written by
             * the APPLIED path, so one surviving row means one winner. "At
             * most one" would also pass if every attempt had failed.
             */
            assertThat(applied).as("exactly one enrollment wins").isEqualTo(1);
            assertThat(linkCount()).as("exactly one link however many raced").isEqualTo(1);
            assertThat(auditActions()).containsExactly("IDENTITY_LINKED");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void revokedLinkHistoryIsNotSilentlyInherited() {
        EnrollmentOutcome first = command().run(req().confirmed(true).build());
        assertThat(first.result()).isEqualTo(EnrollmentOutcome.Result.APPLIED);
        jdbc.sql("UPDATE authz.identity_link SET status = 'REVOKED', revoked_at = now(), "
                + "revoked_by = 'test' WHERE identity_link_id = :id")
                .param("id", first.identityLinkId()).update();

        EnrollmentOutcome again = command().run(req().confirmed(true).build());
        assertThat(again.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", again.problems()))
                .contains("revoked link(s) in its history");
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test
    void aSubjectAbsentFromThePoolIsRefused() {
        EnrollmentOutcome outcome = command()
                .run(req().subject("11111111-2222-3333-4444-555555555555").confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems())).contains("holds no user with subject");
        assertThat(linkCount()).isZero();
    }

    @Test
    void aDisabledPoolUserIsRefused() {
        CognitoDirectory disabled = (p, s) -> java.util.Optional.of(
                new CognitoDirectory.PoolUser(s, "BUShibboleth_x@bu.edu", false, "EXTERNAL_PROVIDER"));
        EnrollmentOutcome outcome = command(disabled).run(req().confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems())).contains("is disabled");
        assertThat(linkCount()).isZero();
    }

    @Test
    void anUnreadablePoolRefusesAWriteAndOnlyWarnsForADryRun() {
        EnrollmentOutcome write = command(CognitoDirectory.UNAVAILABLE)
                .run(req().confirmed(true).build());
        assertThat(write.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", write.problems())).contains("could not be read");
        assertThat(linkCount()).isZero();

        EnrollmentOutcome dry = command(CognitoDirectory.UNAVAILABLE)
                .run(req().dryRun(true).build());
        assertThat(dry.result()).isEqualTo(EnrollmentOutcome.Result.DRY_RUN_OK);
        assertThat(String.join(" ", dry.checks())).contains("could not be read");
    }

    @Test
    void anEmailOrUsernameAsTheSubjectIsRefused() {
        for (String bad : List.of("test@bu.edu", "BUShibboleth_test@bu.edu", "not-a-uuid")) {
            EnrollmentOutcome outcome = command().run(req().subject(bad).confirmed(true).build());
            assertThat(outcome.result()).as("subject %s", bad)
                    .isEqualTo(EnrollmentOutcome.Result.REFUSED);
        }
        assertThat(linkCount()).isZero();
    }

    @Test
    void anIssuerThatDoesNotNameTheGivenPoolIsRefused() {
        EnrollmentOutcome outcome = command()
                .run(req().pool("us-east-1_OTHERPOOL").confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems())).contains("does not name pool");
        assertThat(linkCount()).isZero();
    }

    @Test
    void aNonAdministratorCannotEnrol() {
        EnrollmentOutcome outcome = command()
                .run(req().actor("ARP-N0TADMN1-00000001").confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems()))
                .contains("holds no ACTIVE ARCHIVE_ADMIN membership");
        assertThat(linkCount()).isZero();
    }

    @Test
    void aMissingApprovalReferenceIsRefused() {
        EnrollmentOutcome outcome = command().run(req().approval(null).confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems())).contains("--approval-reference is required");
        assertThat(linkCount()).isZero();
    }

    @Test
    void productionIsRefusedEvenForADryRun() {
        IdentityEnrollmentCommand prod = new IdentityEnrollmentCommand(
                service, adminRoles, identityAdmin, links,
                runtime(ACCOUNT, "arn:aws:ecs:us-east-1:770203350335:cluster/"
                        + "research-archive-platform-prod-api")).withDirectory(pool());
        EnrollmentOutcome outcome = prod.run(req().dryRun(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems()))
                .contains("refuses to run against production");
        assertThat(linkCount()).isZero();
    }

    @Test
    void anUnverifiableRuntimeRefusesAConfirmedEnrollment() {
        IdentityEnrollmentCommand blind = new IdentityEnrollmentCommand(
                service, adminRoles, identityAdmin, links, RuntimeIdentity.UNKNOWN)
                .withDirectory(pool());
        EnrollmentOutcome outcome = blind.run(req().confirmed(true).build());
        assertThat(outcome.result()).isEqualTo(EnrollmentOutcome.Result.REFUSED);
        assertThat(String.join(" ", outcome.problems()))
                .contains("cannot determine the AWS account from the runtime");
        assertThat(linkCount()).isZero();
    }

    /** A failing audit sink must take the identity link down with it. */
    @Test
    void aFailedAuditWriteRollsBackTheIdentityLink() {
        ArchiveAdminService failing = proxyClass(new ArchiveAdminService(
                adminRoles,
                proxy(new edu.bu.archive.application.authorization.admin.AdminAuditSink() {
                    @Override public void record(String actor,
                            edu.bu.archive.application.authorization.admin.AdminAuditAction action,
                            InstitutionalIdentifier subject, String note) {
                        throw new IllegalStateException("audit sink unavailable");
                    }
                }, edu.bu.archive.application.authorization.admin.AdminAuditSink.class),
                grants, identityAdmin, new JdbcAdminAuditRepository(jdbc)));

        IdentityEnrollmentCommand command = new IdentityEnrollmentCommand(
                failing, adminRoles, identityAdmin, links,
                runtime(ACCOUNT, "arn:aws:ecs:us-east-1:770203350335:cluster/"
                        + "research-archive-platform-dev-api")).withDirectory(pool());

        assertThatThrownBy(() -> command.run(req().confirmed(true).build()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(linkCount()).as("no orphan identity survives a failed audit").isZero();
        assertThat(auditActions()).isEmpty();
    }

    // ---------------- helpers ----------------

    private static int linkCount() {
        return jdbc.sql("SELECT count(*) FROM authz.identity_link").query(Integer.class).single();
    }

    private static int grantCount() {
        return jdbc.sql("SELECT count(*) FROM authz.access_grant").query(Integer.class).single();
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
