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
import java.util.concurrent.CyclicBarrier;
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

import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.bootstrap.ArchiveAdminBootstrapCommand;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapOutcome;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapRequest;
import edu.bu.archive.application.authorization.admin.bootstrap.BootstrapTarget;

/**
 * The operator bootstrap utility against a disposable PostgreSQL with every
 * committed migration applied.
 *
 * <p>Synthetic identities throughout. No real administrator is created, no
 * grant is issued, and the values below are invented - the real approved
 * identity is recorded in the activation runbook, not here.
 */
@Testcontainers
class ArchiveAdminBootstrapIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private static final String ACCOUNT = "000000000000";
    private static final String ENVIRONMENT = "dev";
    private static final String POOL = "us-east-1_SYNTHETIC";
    private static final String REGION = "us-east-1";
    private static final String ISSUER =
            "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SYNTHETIC";
    private static final String SUBJECT = "11111111-2222-3333-4444-555555555555";
    private static final String IDENTIFIER = "SYN-INSTITUTIONAL-A";
    private static final String OPERATOR = "synthetic-operator";
    private static final String REASON = "integration test";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PlatformTransactionManager txManager;
    private static ArchiveAdminService service;
    private static AdminRoleRepository adminRoles;
    private static AdminIdentityRepository identities;

    @BeforeAll
    static void applyMigrations() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            ArchiveAdminBootstrapIntegrationTest::migrationVersion))
                    .toList();
        }

        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
        }

        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);

        adminRoles = transactional(new JdbcAdminRoleRepository(jdbc),
                AdminRoleRepository.class, txManager);
        identities = transactional(new JdbcAdminIdentityRepository(jdbc),
                AdminIdentityRepository.class, txManager);
        service = transactionalClass(new ArchiveAdminService(
                adminRoles,
                transactional(new JdbcAdminAuditSink(jdbc),
                        edu.bu.archive.application.authorization.admin.AdminAuditSink.class,
                        txManager),
                transactional(new JdbcAccessGrantRepository(jdbc),
                        edu.bu.archive.application.authorization.AccessGrantRepository.class,
                        txManager),
                identities,
                new JdbcAdminAuditRepository(jdbc)),
                ArchiveAdminService.class, txManager);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM authz.access_grant").update();
        jdbc.sql("DELETE FROM authz.identity_link").update();
        jdbc.sql("DELETE FROM authz.admin_role_member").update();
        jdbc.sql("TRUNCATE authz.access_audit").update();
    }

    // ------------------------------------------------------------------

    @Test
    void bootstrapsTheFirstAdministratorAndItsIdentityLink() {
        BootstrapOutcome outcome = command().run(request().build());

        assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.APPLIED);
        assertThat(administrators()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT granted_by FROM authz.admin_role_member WHERE status='ACTIVE'
                        """).query(String.class).single())
                .as("the bootstrap literal, never an operator name, keys the row")
                .isEqualTo("BOOTSTRAP");
        assertThat(jdbc.sql("""
                        SELECT method FROM authz.identity_link WHERE status='ACTIVE'
                        """).query(String.class).single())
                .isEqualTo("ADMIN_VERIFIED");
    }

    @Test
    void theMembershipAndItsAuditRowArePairedAtomically() {
        command().run(request().build());

        List<String> actions = auditActions();
        assertThat(actions)
                .as("one bootstrap event and one link event, in that order")
                .containsExactly("ADMIN_ROLE_BOOTSTRAPPED", "IDENTITY_LINKED");
        assertThat(jdbc.sql("""
                        SELECT detail::text FROM authz.access_audit
                        WHERE action='ADMIN_ROLE_BOOTSTRAPPED'
                        """).query(String.class).single())
                .as("the operator is recorded, so the chain of custody starts somewhere")
                .contains(OPERATOR);
    }

    @Test
    void theMembershipAndItsAuditRowRollBackTogether() {
        /*
         * Run the bootstrap inside an outer transaction and roll it back. If
         * the membership and the audit row did not participate in one
         * transaction, one of them would survive. Driving a mid-write failure
         * would test the same property less directly, because the audit sink
         * is MANDATORY and therefore cannot be made to fail on its own.
         */
        var template = new org.springframework.transaction.support.TransactionTemplate(txManager);
        template.executeWithoutResult(status -> {
            service.bootstrapFirstAdministrator(
                    new edu.bu.archive.application.authorization.InstitutionalIdentifier(
                            "SYN-ROLLBACK"),
                    OPERATOR, REASON);
            assertThat(administrators())
                    .as("written inside the transaction")
                    .isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(administrators())
                .as("and gone again with it")
                .isZero();
        assertThat(auditActions())
                .as("the audit row must not survive a rolled-back membership")
                .isEmpty();
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        void aSecondBootstrapIsRefused() {
            assertThat(command().run(request().build()).result())
                    .isEqualTo(BootstrapOutcome.Result.APPLIED);

            BootstrapOutcome second = command().run(
                    request().identifier("SYN-INSTITUTIONAL-B")
                            .subject("99999999-8888-7777-6666-555555555555").build());

            assertThat(second.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(second.problems())
                    .anySatisfy(p -> assertThat(p).contains("already exists"));
            assertThat(administrators())
                    .as("the second attempt wrote nothing")
                    .isEqualTo(1);
        }

        @Test
        void aWrongAwsAccountIsRefused() {
            BootstrapOutcome outcome = command().run(request().account("999999999999").build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(outcome.problems()).anySatisfy(p -> assertThat(p).contains("AWS account"));
            assertThat(administrators()).isZero();
        }

        @Test
        void aWrongEnvironmentIsRefused() {
            BootstrapOutcome outcome = command().run(request().environment("test").build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(outcome.problems()).anySatisfy(p -> assertThat(p).contains("environment"));
            assertThat(administrators()).isZero();
        }

        @Test
        void aWrongUserPoolOrIssuerIsRefused() {
            assertThat(command().run(request().pool("us-east-1_OTHER").build()).problems())
                    .anySatisfy(p -> assertThat(p).contains("Cognito user pool"));
            assertThat(command().run(request()
                    .issuer("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_OTHER").build())
                    .problems())
                    .anySatisfy(p -> assertThat(p).contains("Cognito issuer"));
            assertThat(administrators()).isZero();
        }

        @Test
        void productionIsRefusedOutright() {
            BootstrapTarget production =
                    new BootstrapTarget(ACCOUNT, "prod", POOL, REGION);
            BootstrapOutcome outcome = new ArchiveAdminBootstrapCommand(
                    production, service, adminRoles, identities, null)
                    .run(request().environment("prod").build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(outcome.problems())
                    .anySatisfy(p -> assertThat(p).contains("refuses to run against production"));
            assertThat(administrators()).isZero();
        }

        @Test
        void aMissingOrMalformedIdentityIsRefused() {
            assertThat(command().run(request().subject(null).build()).problems())
                    .anySatisfy(p -> assertThat(p).contains("Cognito subject is required"));
            // A username or an email in the subject field is the likeliest slip.
            assertThat(command().run(request().subject("BUShibboleth_someone@bu.edu").build())
                    .problems())
                    .anySatisfy(p -> assertThat(p).contains("immutable 'sub' UUID"));
            assertThat(command().run(request().identifier(null).build()).problems())
                    .anySatisfy(p -> assertThat(p).contains("never derived from an email"));
            assertThat(administrators()).isZero();
        }

        @Test
        void anEmailShapedIdentifierNeedsAnExplicitAcknowledgement() {
            BootstrapOutcome refused =
                    command().run(request().identifier("someone@bu.edu").build());
            assertThat(refused.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(refused.problems())
                    .anySatisfy(p -> assertThat(p).contains("looks like an email address"));

            BootstrapOutcome accepted = command().run(
                    request().identifier("someone@bu.edu").acceptProvisional(true).build());
            assertThat(accepted.result()).isEqualTo(BootstrapOutcome.Result.APPLIED);
            assertThat(accepted.checks())
                    .anySatisfy(c -> assertThat(c).contains("PROVISIONAL"));
        }

        @Test
        void theCognitoSubjectIsRefusedAsTheInstitutionalIdentifier() {
            BootstrapOutcome outcome = command().run(request().identifier(SUBJECT).build());

            assertThat(outcome.problems())
                    .anySatisfy(p -> assertThat(p).contains("not a BU institutional identity"));
            assertThat(administrators()).isZero();
        }

        @Test
        void aMissingOperatorOrReasonIsRefused() {
            assertThat(command().run(request().operator(null).build()).problems())
                    .anySatisfy(p -> assertThat(p).contains("operator identity is required"));
            assertThat(command().run(request().reason(null).build()).problems())
                    .anySatisfy(p -> assertThat(p).contains("reason is required"));
            assertThat(administrators()).isZero();
        }
    }

    @Nested
    @DisplayName("Dry run and confirmation")
    class DryRunAndConfirmation {

        @Test
        void aDryRunValidatesAndWritesNothing() {
            BootstrapOutcome outcome = command().run(request().dryRun(true).build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.DRY_RUN_OK);
            assertThat(administrators()).isZero();
            assertThat(auditActions())
                    .as("a dry run must not even leave an audit row")
                    .isEmpty();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM authz.identity_link")
                    .query(Integer.class).single()).isZero();
        }

        @Test
        void anUnconfirmedRunIsTreatedAsADryRun() {
            // Forgetting --confirm must produce the safe outcome, not a write.
            BootstrapOutcome outcome = command().run(request().confirmed(false).build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.DRY_RUN_OK);
            assertThat(outcome.message()).contains("--confirm");
            assertThat(administrators()).isZero();
        }

        @Test
        void aDryRunStillReportsEveryFailedCheck() {
            BootstrapOutcome outcome =
                    command().run(request().dryRun(true).account("999999999999").build());

            assertThat(outcome.result()).isEqualTo(BootstrapOutcome.Result.REFUSED);
            assertThat(administrators()).isZero();
        }
    }

    @Test
    void concurrentBootstrapAttemptsLeaveExactlyOneAdministrator() throws Exception {
        int threads = 4;
        var barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> attempts = java.util.stream.IntStream.range(0, threads)
                    .<Callable<String>>mapToObj(i -> () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            return command().run(request()
                                    .identifier("SYN-CONCURRENT-" + i)
                                    .subject(String.format(
                                            "%08d-0000-0000-0000-000000000000", i))
                                    .build()).result().name();
                        } catch (RuntimeException refused) {
                            return "THREW";
                        }
                    })
                    .toList();

            for (Future<String> each : pool.invokeAll(attempts)) {
                each.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(administrators())
                .as("however the attempts interleave, the archive must end with exactly one")
                .isEqualTo(1);
        assertThat(auditActions().stream().filter("ADMIN_ROLE_BOOTSTRAPPED"::equals).count())
                .as("and exactly one bootstrap event")
                .isEqualTo(1);
    }

    @Test
    void bootstrappingGrantsNoResearchRecordAccess() {
        command().run(request().build());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM authz.access_grant")
                .query(Integer.class).single())
                .as("ARCHIVE_ADMIN confers no archive data access; this administrator "
                        + "correctly sees no records until a grant is issued")
                .isZero();
    }

    @Test
    void theUtilityIsNotReachableOverHttp() {
        /*
         * Structural, not behavioural: the runner is an ApplicationRunner
         * behind a profile, carries no request-mapping annotation of any
         * kind, and lives outside adapter/in/web. A web route to the first
         * administrator would bypass the whole model.
         */
        Class<?> cli = edu.bu.archive.adapter.in.cli.ArchiveAdminBootstrapCli.class;

        assertThat(org.springframework.boot.ApplicationRunner.class).isAssignableFrom(cli);
        assertThat(cli.getAnnotation(org.springframework.context.annotation.Profile.class).value())
                .containsExactly("bootstrap-admin");
        assertThat(cli.getAnnotation(org.springframework.web.bind.annotation.RestController.class))
                .isNull();
        assertThat(cli.getAnnotation(org.springframework.stereotype.Controller.class)).isNull();
        assertThat(cli.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class)).isNull();
        for (var method : cli.getDeclaredMethods()) {
            assertThat(method.getAnnotation(
                    org.springframework.web.bind.annotation.RequestMapping.class)).isNull();
            assertThat(method.getAnnotation(
                    org.springframework.web.bind.annotation.PostMapping.class)).isNull();
            assertThat(method.getAnnotation(
                    org.springframework.web.bind.annotation.GetMapping.class)).isNull();
        }
        assertThat(cli.getPackageName()).doesNotContain("adapter.in.web");
    }

    @Test
    void noOutcomeLineCarriesAnUnmaskedIdentifier() {
        BootstrapOutcome outcome = command().run(request().dryRun(true).build());

        assertThat(String.join("\n", outcome.checks()))
                .as("identifiers appear masked, so console output and any transcript of it "
                        + "cannot leak one")
                .doesNotContain(SUBJECT)
                .doesNotContain(IDENTIFIER);
    }

    // ------------------------------------------------------------------

    private static ArchiveAdminBootstrapCommand command() {
        return new ArchiveAdminBootstrapCommand(
                new BootstrapTarget(ACCOUNT, ENVIRONMENT, POOL, REGION),
                service, adminRoles, identities, new AuthorizationProperties());
    }

    private static int administrators() {
        return jdbc.sql("SELECT COUNT(*) FROM authz.admin_role_member WHERE status='ACTIVE'")
                .query(Integer.class).single();
    }

    private static List<String> auditActions() {
        return jdbc.sql("SELECT action FROM authz.access_audit ORDER BY audit_id")
                .query(String.class).list();
    }

    private static Req request() {
        return new Req();
    }

    /** Mutable builder so each test states only the field it is exercising. */
    private static final class Req {
        private String account = ACCOUNT;
        private String environment = ENVIRONMENT;
        private String pool = POOL;
        private String issuer = ISSUER;
        private String subject = SUBJECT;
        private String identifier = IDENTIFIER;
        private String operator = OPERATOR;
        private String reason = REASON;
        private boolean acceptProvisional;
        private boolean dryRun;
        private boolean confirmed = true;

        Req account(String v) { this.account = v; return this; }
        Req environment(String v) { this.environment = v; return this; }
        Req pool(String v) { this.pool = v; return this; }
        Req issuer(String v) { this.issuer = v; return this; }
        Req subject(String v) { this.subject = v; return this; }
        Req identifier(String v) { this.identifier = v; return this; }
        Req operator(String v) { this.operator = v; return this; }
        Req reason(String v) { this.reason = v; return this; }
        Req acceptProvisional(boolean v) { this.acceptProvisional = v; return this; }
        Req dryRun(boolean v) { this.dryRun = v; return this; }
        Req confirmed(boolean v) { this.confirmed = v; return this; }

        BootstrapRequest build() {
            return new BootstrapRequest(account, environment, pool, issuer, subject,
                    identifier, "BUShibboleth_synthetic", operator, reason,
                    acceptProvisional, dryRun, confirmed);
        }
    }

    private static <T> T transactional(
            Object target, Class<T> port, PlatformTransactionManager tx) {
        var factory = new ProxyFactory(target);
        factory.setInterfaces(port);
        factory.addAdvice(new TransactionInterceptor(
                tx, new AnnotationTransactionAttributeSource()));
        return port.cast(factory.getProxy());
    }

    private static <T> T transactionalClass(
            T target, Class<T> type, PlatformTransactionManager tx) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                tx, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
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
