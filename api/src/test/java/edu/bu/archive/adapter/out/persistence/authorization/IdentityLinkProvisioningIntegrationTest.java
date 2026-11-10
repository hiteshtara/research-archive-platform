package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkCommand;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkOutcome;
import edu.bu.archive.application.authorization.admin.provisioning.IdentityLinkRequest;
import edu.bu.archive.application.authorization.admin.provisioning.RuntimeIdentity;

/**
 * The operator-only identity-link utility, against a real schema.
 *
 * <p>This utility exists because the two routes that could create a link
 * could not create a test population: the administrative API 404s while
 * enforcement is off, and the bootstrap utility only links people it is also
 * making administrators. These tests hold the properties that make it a safe
 * substitute for the API rather than a way around it — it writes through
 * {@link ArchiveAdminService#linkIdentity}, the same method the API calls, so
 * there is one implementation and one audit row.
 */
@Testcontainers
@DisplayName("Operator-only identity linking")
class IdentityLinkProvisioningIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ACCOUNT = "770203350335";
    private static final String ENVIRONMENT = "dev";
    private static final String POOL = "us-east-1_TEST";
    private static final String REGION = "us-east-1";
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/" + POOL;
    private static final String ADMIN = "ARP-ADMN0001-00000001";
    private static final String SUBJECT = "ARP-PKT00001-00000001";
    private static final String SUB = "11111111-2222-3333-4444-555555555555";

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
                    .sorted(Comparator.comparingInt(
                            IdentityLinkProvisioningIntegrationTest::version))
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
        AccessGrantRepository grants =
                proxy(new JdbcAccessGrantRepository(jdbc), AccessGrantRepository.class);
        identities = proxy(new JdbcAdminIdentityRepository(jdbc), AdminIdentityRepository.class);
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
        // Links are created BY an administrator, so one must exist.
        service.bootstrapFirstAdministratorWithIdentity(
                new InstitutionalIdentifier(ADMIN), "operator@bu.edu", "test fixture",
                ISSUER, "00000000-0000-0000-0000-000000000001", null, "admin@bu.edu");
        jdbc.sql("TRUNCATE authz.access_audit").update();   // ignore bootstrap rows
    }

    // ── it writes, and writes exactly one thing ──────────────────────

    @Nested
    @DisplayName("A confirmed link")
    class ConfirmedLink {

        @Test
        void writesTheLinkAndItsAuditRowTogether() {
            IdentityLinkOutcome outcome = command().run(req().confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.APPLIED);
            assertThat(outcome.linkId()).isNotNull();
            assertThat(activeLinksFor(SUBJECT)).isEqualTo(1);
            assertThat(auditActions()).containsExactly("IDENTITY_LINKED");
        }

        @Test
        void recordsItAsAdministratorVerified() {
            /*
             * ADMIN_VERIFIED, not AUTO_VERIFIED. The distinction is the whole
             * basis for trusting the link: a person decided this mapping is
             * correct, and the method column is what says so later.
             */
            command().run(req().confirmed(true).build());

            assertThat(jdbc.sql("""
                            SELECT method FROM authz.identity_link
                            WHERE institutional_identifier = :s
                            """).param("s", SUBJECT).query(String.class).single())
                    .isEqualTo("ADMIN_VERIFIED");
        }

        @Test
        void storesTheKualiPersonIdWhenGiven() {
            command().run(req().kualiPersonId("1000570").confirmed(true).build());

            assertThat(jdbc.sql("""
                            SELECT kuali_person_id FROM authz.identity_link
                            WHERE institutional_identifier = :s
                            """).param("s", SUBJECT).query(String.class).single())
                    .isEqualTo("1000570");
        }

        @Test
        void confersNoAccessAtAll() {
            /*
             * The property the pilot depends on. A Grant Administrator must be
             * able to administer access without holding any, and a link is not
             * a grant - so a link on its own must leave the grant table empty.
             */
            command().run(req().confirmed(true).build());

            assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant")
                    .query(Integer.class).single())
                    .as("linking must never create a grant")
                    .isZero();
        }

        @Test
        void saysSoOnEveryRun() {
            IdentityLinkOutcome outcome = command().run(req().confirmed(true).build());

            assertThat(String.join(" ", outcome.checks()))
                    .contains("a link confers NO access to any record");
            assertThat(outcome.message()).contains("still hold no access until a grant is issued");
        }
    }

    // ── nothing happens without --confirm ────────────────────────────

    @Nested
    @DisplayName("Writes nothing unless confirmed")
    class Unconfirmed {

        @Test
        void theDefaultIsADryRun() {
            IdentityLinkOutcome outcome = command().run(req().build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.DRY_RUN_OK);
            assertThat(outcome.message()).contains("--confirm was not given");
            assertThat(activeLinksFor(SUBJECT)).isZero();
            assertThat(auditActions()).isEmpty();
        }

        @Test
        void anExplicitDryRunWritesNothingEither() {
            IdentityLinkOutcome outcome = command().run(req().dryRun(true).confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.DRY_RUN_OK);
            assertThat(activeLinksFor(SUBJECT)).isZero();
        }
    }

    // ── refusals ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Refusals leave nothing behind")
    class Refusals {

        @Test
        void aNonAdministratorCannotLinkAnyone() {
            IdentityLinkOutcome outcome = command().run(
                    req().actor("ARP-NTADMN09-00000009").confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("holds no ACTIVE ARCHIVE_ADMIN membership");
            assertThat(activeLinksFor(SUBJECT)).isZero();
            assertThat(auditActions()).isEmpty();
        }

        @Test
        void aLoginNameIsNotACognitoSubject() {
            /*
             * The mistake that actually gets made. A username or email is what
             * a person thinks of as the login, and both are mutable.
             */
            IdentityLinkOutcome outcome = command().run(
                    req().cognitoSubject("pilot@bu.edu").confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("must be the immutable 'sub' UUID");
        }

        @Test
        void anEmailIsNotAnArchiveIdentifier() {
            IdentityLinkOutcome outcome = command().run(
                    req().subjectIdentifier("pilot@bu.edu").confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("looks like an email address");
        }

        @Test
        void aMismatchedIssuerIsRefused() {
            IdentityLinkOutcome outcome = command().run(
                    req().issuer("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_OTHER")
                            .confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("Cognito issuer does not match this user pool");
        }

        @Test
        void aSecondActiveLinkForTheSameIdentityIsRefusedAsARelink() {
            command().run(req().confirmed(true).build());

            IdentityLinkOutcome second = command().run(
                    req().cognitoSubject("99999999-8888-7777-6666-555555555555")
                            .confirmed(true).build());

            assertThat(second.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", second.problems()))
                    .contains("already has an ACTIVE link")
                    .contains("that is a re-link");
            assertThat(activeLinksFor(SUBJECT))
                    .as("the first link must survive untouched")
                    .isEqualTo(1);
            assertThat(auditActions()).containsExactly("IDENTITY_LINKED");
        }

        @Test
        void oneCognitoSubjectCannotBeTwoPeople() {
            /*
             * The other partial unique index, and the direction this utility
             * cannot pre-check: nothing reads links by Cognito subject on the
             * administrative side, so the database is what catches it. It must
             * still come back as a sentence rather than a stack trace.
             */
            command().run(req().confirmed(true).build());

            IdentityLinkOutcome second = command().run(
                    req().subjectIdentifier("ARP-PKT00002-00000002").confirmed(true).build());

            assertThat(second.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(second.message()).doesNotContain("Exception");
            assertThat(activeLinksFor("ARP-PKT00002-00000002")).isZero();
        }

        @Test
        void provenanceIsRequired() {
            IdentityLinkOutcome outcome = command().run(
                    req().approval(null).confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("--approval-reference is required");
        }

        @Test
        void productionIsRefusedOutright() {
            IdentityLinkOutcome outcome = command().run(
                    req().environment("prod").confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("refuses to run against production");
            assertThat(activeLinksFor(SUBJECT)).isZero();
        }

        @Test
        void theArchiveIdentifierMustNotBeTheCognitoSubject() {
            IdentityLinkOutcome outcome = command().run(
                    req().subjectIdentifier(SUB).confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .containsAnyOf("not a well-formed archive identifier",
                            "must not be the Cognito subject");
        }

        @Test
        void aNonNumericKualiPersonIdIsRefused() {
            IdentityLinkOutcome outcome = command().run(
                    req().kualiPersonId("jdoe").confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(String.join(" ", outcome.problems()))
                    .contains("must be the numeric Kuali PERSON_ID");
        }
    }

    // ── the contact-derivation trap ──────────────────────────────────

    @Nested
    @DisplayName("Omitting the PERSON_ID is reported, not silent (SEC-003)")
    class ContactDerivationWarning {

        @Test
        void aLinkWithoutAPersonIdSaysWhatItCannotDo() {
            /*
             * Without this, a CONTACT_DERIVATION pilot identity is linked,
             * granted, and sees nothing - and the engine gets blamed.
             */
            IdentityLinkOutcome outcome = command().run(req().confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.APPLIED);
            assertThat(String.join(" ", outcome.checks()))
                    .contains("no Kuali PERSON_ID supplied")
                    .contains("CONTACT_DERIVATION does NOT");
        }

        @Test
        void aLinkWithOneConfirmsContactAccessCanResolve() {
            IdentityLinkOutcome outcome = command().run(
                    req().kualiPersonId("1000570").confirmed(true).build());

            assertThat(String.join(" ", outcome.checks()))
                    .contains("contact-derived access can resolve");
        }
    }

    // ── the runtime is checked, not taken on trust ───────────────────

    @Nested
    @DisplayName("The runtime is evidence; the flag is an intention")
    class RuntimeChecks {

        @Test
        void aWriteIntoTheWrongAccountIsRefused() {
            IdentityLinkOutcome outcome = command(runtime("999999999999", "x-dev-api"))
                    .run(req().confirmed(true).build());

            assertThat(outcome.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);
            assertThat(activeLinksFor(SUBJECT)).isZero();
        }

        @Test
        void anUnverifiableRuntimeRefusesAWriteButPermitsADryRun() {
            IdentityLinkOutcome write = command(runtime(null, null))
                    .run(req().confirmed(true).build());
            assertThat(write.result()).isEqualTo(IdentityLinkOutcome.Result.REFUSED);

            IdentityLinkOutcome dry = command(runtime(null, null)).run(req().build());
            assertThat(dry.result()).isEqualTo(IdentityLinkOutcome.Result.DRY_RUN_OK);
        }
    }

    // ---------------- helpers ----------------

    private static IdentityLinkCommand command() {
        return command(runtime(ACCOUNT, "research-archive-platform-dev-api"));
    }

    private static IdentityLinkCommand command(RuntimeIdentity runtime) {
        return new IdentityLinkCommand(ENVIRONMENT, service, adminRoles, identities, runtime);
    }

    private static RuntimeIdentity runtime(String account, String cluster) {
        return new RuntimeIdentity() {
            @Override
            public Optional<String> awsAccountId() {
                return Optional.ofNullable(account);
            }

            @Override
            public Optional<String> clusterName() {
                return Optional.ofNullable(cluster);
            }
        };
    }

    private static int activeLinksFor(String identifier) {
        return jdbc.sql("""
                        SELECT count(*) FROM authz.identity_link
                        WHERE institutional_identifier = :s AND status = 'ACTIVE'
                        """).param("s", identifier).query(Integer.class).single();
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

    private static Req req() {
        return new Req();
    }

    private static final class Req {
        private String account = ACCOUNT;
        private String environment = ENVIRONMENT;
        private String pool = POOL;
        private String region = REGION;
        private String issuer = ISSUER;
        private String cognitoSubject = SUB;
        private String subjectIdentifier = SUBJECT;
        private String actor = ADMIN;
        private String kualiPersonId;
        private String loginName = "pilot@bu.edu";
        private String operator = "operator@bu.edu";
        private String reason = "four-role authorization pilot";
        private String approval = "APPROVAL-2026-11-10";
        private boolean dryRun;
        private boolean confirmed;

        Req environment(String v) { this.environment = v; return this; }
        Req issuer(String v) { this.issuer = v; return this; }
        Req cognitoSubject(String v) { this.cognitoSubject = v; return this; }
        Req subjectIdentifier(String v) { this.subjectIdentifier = v; return this; }
        Req actor(String v) { this.actor = v; return this; }
        Req kualiPersonId(String v) { this.kualiPersonId = v; return this; }
        Req approval(String v) { this.approval = v; return this; }
        Req dryRun(boolean v) { this.dryRun = v; return this; }
        Req confirmed(boolean v) { this.confirmed = v; return this; }

        IdentityLinkRequest build() {
            return new IdentityLinkRequest(account, environment, pool, region, issuer,
                    cognitoSubject, subjectIdentifier, actor, kualiPersonId, loginName,
                    operator, reason, approval, dryRun, confirmed);
        }
    }
}
