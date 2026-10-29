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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.in.cli.ArchiveAdminBootstrapCli;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * The administrator bootstrap runs as a one-off task with
 * {@code spring.main.web-application-type=none}, so that no HTTP port is
 * opened while it holds the credentials to create an administrator. Its own
 * Javadoc prescribes exactly that.
 *
 * <p>It had never been executed that way. The first real attempt — an
 * isolated DEV Fargate task on 2026-10-29 — died before the runner was
 * reached:
 *
 * <pre>
 * APPLICATION FAILED TO START
 * Parameter 0 of method securityFilterChain in SecurityConfiguration
 * required a bean of type 'HttpSecurity' that could not be found.
 * </pre>
 *
 * <p>{@code HttpSecurity} only exists in a servlet web context, and both
 * security configurations declared a {@link SecurityFilterChain} without
 * being conditional on one. So the documented way to run the bootstrap
 * could never have worked.
 *
 * <p>This test starts the REAL application context with no web environment
 * and the {@code bootstrap-admin} profile active. Without
 * {@code @ConditionalOnWebApplication} on those configurations it fails at
 * context creation, exactly as DEV did.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.web-application-type=none",
        /*
         * @SpringBootTest runs ApplicationRunners, so these drive a REAL
         * dry run through the real CLI in a real non-web context - the
         * exact invocation the DEV task uses, minus --confirm.
         */
        args = {
            "--aws-account-id=770203350335",
            "--environment=dev",
            "--cognito-user-pool-id=us-east-1_VJ4ekQ27c",
            "--cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c",
            "--cognito-subject=b438a418-d0a1-7071-2169-023a374a4038",
            "--mint-archive-identifier",
            "--login-name=mukadder@bu.edu",
            "--operator=regression-test",
            "--reason=non-web startup regression test",
            "--dry-run",
        })
@ActiveProfiles({"local", "bootstrap-admin"})
@Testcontainers
@DisplayName("The bootstrap starts without a servlet context")
class BootstrapNonWebStartupTest {

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
                    .sorted(Comparator.comparingInt(BootstrapNonWebStartupTest::migrationVersion))
                    .toList();
        }
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
        }
    }

    @Test
    void theContextStartsWithNoWebEnvironment(ApplicationContext context) {
        // Reaching this assertion at all is the point: before the fix the
        // context could not be created and every test here failed to start.
        assertThat(context).isNotNull();
        assertThat(context.getEnvironment().getActiveProfiles())
                .contains("bootstrap-admin");
    }

    @Test
    void noSecurityFilterChainExistsInNonWebMode(ApplicationContext context) {
        assertThatThrownBy(() -> context.getBean(SecurityFilterChain.class))
                .as("a servlet filter chain has no meaning without a servlet context")
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void noServletWebServerIsStarted(ApplicationContext context) {
        assertThat(context)
                .as("a non-web context must not be a servlet web context")
                .isNotInstanceOf(
                        org.springframework.web.context.WebApplicationContext.class);
    }

    @Test
    void theBootstrapRunnerIsPresentButNothingExposesItOverHttp(
            ApplicationContext context) {
        assertThat(context.getBean(ArchiveAdminBootstrapCli.class))
                .as("the bootstrap-admin profile must still contribute its runner")
                .isNotNull();
        /*
         * Controller beans still exist - component scanning creates them
         * regardless. What matters is that nothing can REACH them: there is
         * no servlet container, so no port is bound and no request can
         * arrive. Asserting "no controller beans" would be asserting the
         * wrong thing, and it fails.
         */
        assertThatThrownBy(() -> context.getBean(
                org.springframework.boot.web.servlet.server.ServletWebServerFactory.class))
                .as("no servlet web server may exist, so no port is opened")
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void theAdminServiceIsStillTransactionallyProxied(ApplicationContext context) {
        Object bean = context.getBean(ArchiveAdminService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(bean))
                .as("the atomic bootstrap depends on this proxy existing")
                .isTrue();
        assertThat(java.util.Arrays.stream(
                        ((org.springframework.aop.framework.Advised) bean).getAdvisors())
                .anyMatch(a -> a.getAdvice()
                        instanceof org.springframework.transaction.interceptor.TransactionInterceptor))
                .isTrue();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private AuthorizationProperties authorizationProperties;

    @Test
    void enforcementIsStillOffByDefault() {
        assertThat(authorizationProperties.isEnforcementEnabled())
                .as("a bootstrap task must not switch enforcement on as a side effect")
                .isFalse();
    }

    @Test
    void theDryRunWroteNothingAtAll() throws Exception {
        /*
         * The runner has already executed by the time any test method runs,
         * with --dry-run and no --confirm. Reaching this point proves the
         * CLI did not throw, which it does on REFUSED - so the outcome was
         * DRY_RUN_OK. Nothing may have been written.
         */
        try (Connection c = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            for (String table : List.of("admin_role_member", "identity_link",
                    "access_grant", "access_audit", "relink_approval")) {
                var rs = s.executeQuery("SELECT count(*) FROM authz." + table);
                rs.next();
                assertThat(rs.getInt(1)).as("authz." + table + " after a dry run").isZero();
            }
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
