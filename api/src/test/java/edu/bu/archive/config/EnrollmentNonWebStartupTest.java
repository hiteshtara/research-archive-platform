package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.in.cli.GrantProvisioningCli;
import edu.bu.archive.adapter.in.cli.IdentityEnrollmentCli;
import edu.bu.archive.application.authorization.AuthorizationProperties;

/**
 * Identity enrollment runs as a one-off task with
 * {@code spring.main.web-application-type=none}: no port may be opened while
 * the task holds the ability to create an archive identity.
 *
 * <p>Asserting that from the enrollment unit tests would be asserting nothing
 * - they construct the command directly and never start a context. This test
 * starts the REAL application context with no web environment and the
 * {@code grant-admin} profile active, and lets the REAL CLI execute a dry run
 * through its {@link org.springframework.boot.ApplicationRunner}, which is the
 * exact invocation a DEV task would use minus {@code --confirm}.
 *
 * <p>It also covers the shared-profile hazard that the unit tests cannot see.
 * {@code grant-admin} contributes two runners and Spring runs every one of
 * them; before {@link GrantProvisioningCli#isEnrollment} existed, the grant
 * runner acted on an enrollment invocation too, refusing on absent grant
 * options and exiting 1 - either after the enrollment had already been
 * written, or instead of it, depending on runner order.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.web-application-type=none",
        args = {
            "--enrol-cognito-subject=0fa15e00-0000-4000-8000-000000000fab",
            "--aws-account-id=770203350335",
            "--environment=dev",
            "--actor=ARP-ADMN0001-00000001",
            "--cognito-user-pool-id=us-east-1_VJ4ekQ27c",
            "--cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c",
            "--login-name=someone@bu.edu",
            "--operator=regression-test",
            "--reason=non-web enrollment startup regression test",
            "--approval-reference=none - automated test",
            "--dry-run",
        })
@ActiveProfiles({"local", "grant-admin"})
@Testcontainers
@DisplayName("Identity enrollment starts without a servlet context")
class EnrollmentNonWebStartupTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void applyEveryCommittedMigration() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(EnrollmentNonWebStartupTest::migrationVersion))
                    .toList();
        }
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            /*
             * The acting administrator must exist before the runner executes,
             * and the runner executes during context startup - earlier than
             * any @BeforeEach. Inserting the membership row directly rather
             * than through the repository keeps authz.identity_link empty, so
             * the "wrote nothing" assertion below is unambiguous. The row is
             * then excluded from that assertion, since it is the fixture.
             */
            statement.execute("INSERT INTO authz.admin_role_member "
                    + "(institutional_identifier, status, granted_by, reason) "
                    + "VALUES ('ARP-ADMN0001-00000001', 'ACTIVE', 'test', 'fixture')");
        }
    }

    @Test
    void theContextStartsWithNoWebEnvironment(ApplicationContext context) {
        assertThat(context.getEnvironment().getActiveProfiles()).contains("grant-admin");
        assertThat(context)
                .as("a non-web context must not be a servlet web context")
                .isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
    }

    @Test
    void noHttpListenerStarts(ApplicationContext context) {
        /*
         * Controller beans still exist - component scanning creates them
         * regardless of web mode. What matters is that nothing can REACH
         * them: with no servlet web server factory there is no container, so
         * no port is bound and no request can arrive.
         */
        assertThatThrownBy(() -> context.getBean(
                org.springframework.boot.web.servlet.server.ServletWebServerFactory.class))
                .as("no servlet web server may exist, so no port is opened")
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean(SecurityFilterChain.class))
                .as("a servlet filter chain has no meaning without a servlet context")
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void bothOperatorRunnersExistButOnlyTheEnrollmentOneOwnsThisInvocation(
            ApplicationContext context) {
        assertThat(context.getBean(IdentityEnrollmentCli.class)).isNotNull();
        assertThat(context.getBean(GrantProvisioningCli.class))
                .as("the shared profile really does contribute both runners")
                .isNotNull();
        /*
         * Reaching this assertion is the evidence: the runners have already
         * run. The grant runner throws on a request with no grantee and no
         * revoke-grant-id, so had it acted on these arguments the context
         * would have failed to start and every test here would have errored.
         */
    }

    @Test
    void theDryRunWroteNothingAtAll() throws Exception {
        try (Connection c = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            // admin_role_member is excluded: its single row is the acting
            // administrator the fixture had to create before startup.
            for (String table : List.of("identity_link",
                    "access_grant", "access_audit", "relink_approval")) {
                var rs = s.executeQuery("SELECT count(*) FROM authz." + table);
                rs.next();
                assertThat(rs.getInt(1))
                        .as("authz." + table + " after a dry-run enrollment")
                        .isZero();
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private AuthorizationProperties authorizationProperties;

    @Test
    void enforcementIsStillOffByDefault() {
        assertThat(authorizationProperties.isEnforcementEnabled())
                .as("an enrollment task must not switch enforcement on as a side effect")
                .isFalse();
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
