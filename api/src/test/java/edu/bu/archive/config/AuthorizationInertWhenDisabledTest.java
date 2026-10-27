package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.EnrollmentOutcome;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

/**
 * The application still starts with the Phase 1 authorization code present,
 * and that code is inert with enforcement disabled.
 *
 * <p>Why this exists: before it, no {@code @SpringBootTest} existed anywhere in
 * the suite, so 1 252 passing tests said nothing about whether the real
 * application context still loads. A missing bean in
 * {@link AuthorizationConfiguration} would have failed every deployment while
 * the whole suite stayed green - this project has already hit exactly that
 * failure once ("No qualifying bean of type 'IoResolver'"). The end-to-end
 * classes that used to provide context coverage are Phase 2 work and are not
 * on this branch, so without this test that coverage is simply absent.
 *
 * <p>Enforcement is left at its default. Nothing here switches it on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class AuthorizationInertWhenDisabledTest {

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
                    .sorted(Comparator.comparingInt(
                            AuthorizationInertWhenDisabledTest::migrationVersion))
                    .toList();
        }
        try (Connection connection = postgres.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationProperties properties;

    @Test
    void theApplicationContextStartsWithTheAuthorizationCodePresent() {
        assertThat(context).isNotNull();
        // The wiring that had no coverage at all before this test.
        assertThat(context.getBean(RecordAuthorizationService.class)).isNotNull();
        assertThat(context.getBean(ArchiveAdminService.class)).isNotNull();
    }

    @Test
    void enforcementIsOffAndNoPolicyIsConfigured() {
        assertThat(properties.isEnforcementEnabled())
                .as("app.authorization.enforcement-enabled must default to false")
                .isFalse();
        assertThat(properties.policy())
                .as("no policy strategy may be configured by default")
                .isEmpty();
    }

    @Test
    void enrollmentIsDisabledSoNoIdentityIsLinkedAutomatically() {
        assertThat(properties.getEnrollment().isEnabled()).isFalse();
        assertThat(context.getBean(IdentityEnrollment.class).prepare(null))
                .isEqualTo(EnrollmentOutcome.DISABLED);
    }

    @Test
    void noAdministrativeOrAuthorizationEndpointIsExposed() throws Exception {
        // The administrative API exists now, but it must not be reachable while
        // enforcement is off: no grant is read by anything in that state, so an
        // administrative surface that appeared to work would accept changes
        // that have no effect. Paths that were never built are kept in the list
        // so an accidentally published one still fails here.
        for (String path : List.of(
                "/api/v1/admin/administrators",
                "/api/v1/admin/audit",
                "/api/v1/admin/identities?q=anyone",
                "/api/v1/admin/identities/inst-anyone",
                "/api/authz/admins", "/api/authz/grants", "/api/admin/archive-admins")) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void anOrdinaryApiRequestIsNotBlockedByAuthorization() throws Exception {
        // With app.security.enabled=false (local profile) this is permit-all,
        // exactly as it was before this branch; authorization adds no gate.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path fromModule = Path.of("..", "database", "migrations");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate database/migrations");
    }
}
