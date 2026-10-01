package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.authorization.CognitoProfile;
import edu.bu.archive.application.authorization.CognitoProfileReader;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Enrollment in NameID mode (identifier-attribute: identities.userId, the production target) against
 * a pool in each username-case configuration. Cognito generates a federated username as
 * {@code <provider>_<NameID>} and lowercases it in a case-insensitive pool; the NameID itself is
 * matched against the crosswalk EXACTLY in both. Subclasses set
 * {@code app.authorization.enrollment.username-case-sensitive}. SYNTHETIC values; the pool is an
 * in-memory AdminGetUser that models Cognito's documented behaviour, not real Cognito.
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.security.enabled=false",
        "app.authorization.enforcement-enabled=true",
        "app.authorization.max-sign-in-age=PT12H",
        "app.authorization.version-scope=FAMILY_WIDE",
        "app.authorization.department-match=LEAD_UNIT_WITH_DESCENDANTS",
        "app.authorization.research-staff-roles=PI,MPI,COI",
        "app.authorization.contact-derivation=VERIFIED_PRINCIPAL",
        "app.authorization.enrollment.enabled=true",
        "app.authorization.enrollment.user-pool-id=us-east-1_SYNTHETIC",
        "app.authorization.enrollment.region=us-east-1",
        "app.authorization.enrollment.endpoint-override=http://127.0.0.1:9",
        "app.authorization.enrollment.saml-provider-name=SyntheticSaml",
        "app.authorization.enrollment.identifier-attribute=identities.userId",
        "app.authorization.enrollment.crosswalk-attribute-name=syntheticNameId",
        "app.authorization.enrollment.refusal-retry-seconds=0",
        "app.attachments.storage=local",
        "app.attachments.local-directory=target/authz-nameid-test-attachments",
        "app.search.semantic.enabled=false"
})
@AutoConfigureMockMvc
@Import(NameIdEnrollmentIntegrationTestBase.SyntheticFederation.class)
abstract class NameIdEnrollmentIntegrationTestBase {

    static final String ISSUER = "https://enroll.invalid/us-east-1_SYNTHETIC";
    static final String PROVIDER = "SyntheticSaml";
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    static final long SESSION_1 = Instant.now().minusSeconds(7200).getEpochSecond();
    static final long SESSION_2 = SESSION_1 + 3600;

    /** The fake user pool, keyed as the pool would look a username up. */
    static final Map<String, CognitoProfile> POOL = new ConcurrentHashMap<>();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void loadSchemaSeedAndCrosswalk() throws Exception {
        POOL.clear();
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("database/migrations"))) {
            root = root.getParent();
        }
        List<Path> migrations;
        try (Stream<Path> files = Files.list(root.resolve("database/migrations"))) {
            migrations = files.filter(p -> MIGRATION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(
                            MIGRATION.matcher(p.getFileName().toString()).replaceAll("$1"))))
                    .toList();
        }
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            for (Path migration : migrations) {
                s.execute(Files.readString(migration));
            }
            s.execute(Files.readString(root.resolve("api/src/test/resources/authz/synthetic-seed.sql")));
            // The crosswalk keys are NameIDs exactly as the IdP sends them (mixed case).
            s.execute("""
                    INSERT INTO authz.kim_principal (prncpl_id, entity_id, actv_ind, load_ref) VALUES
                      ('SYNP-PI-01', 'SYNE-01', 'Y', 'SYNTHETIC-LOAD'),
                      ('SYNP-OTHER-04', 'SYNE-04', 'Y', 'SYNTHETIC-LOAD');
                    INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by) VALUES
                      ('syntheticNameId', 'AbC-Pat-NameID', 'SYNP-PI-01', 'FICTIONAL-1', 'synthetic'),
                      ('syntheticNameId', 'abc-kim-nameid', 'SYNP-OTHER-04', 'FICTIONAL-2', 'synthetic');
                    """);
        }
    }

    /** TEST-ONLY sign-in replacement and user pool. */
    @TestConfiguration
    static class SyntheticFederation {
        @Bean
        @Primary
        CurrentIdentityProvider testIdentity() {
            return () -> Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(a -> ((ServletRequestAttributes) a).getRequest())
                    .filter(r -> r.getHeader("X-Test-Sub") != null)
                    .map(r -> new ValidatedCognitoIdentity(ISSUER, r.getHeader("X-Test-Sub"),
                            r.getHeader("X-Test-Username"),
                            Instant.ofEpochSecond(Long.parseLong(r.getHeader("X-Test-Auth-Time")))));
        }

        @Bean
        @Primary
        CognitoProfileReader testPool() {
            return username -> Optional.ofNullable(POOL.get(username));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    /** The pool's UsernameConfiguration.CaseSensitive, as configured on the API. */
    abstract boolean poolCaseSensitive();

    /** A federated profile as Cognito stores it; returns the token username. */
    String federated(String sub, String nameId) {
        String generated = PROVIDER + "_" + nameId;
        String username = poolCaseSensitive() ? generated : generated.toLowerCase(Locale.ROOT);
        POOL.put(username, new CognitoProfile(sub, username, true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, nameId)), Map.of()));
        return username;
    }

    int status(String sub, String username, long authTime, String path) throws Exception {
        return mvc.perform(get(path).header("X-Test-Sub", sub).header("X-Test-Username", username)
                .header("X-Test-Auth-Time", String.valueOf(authTime))).andReturn().getResponse().getStatus();
    }

    @Test
    void aMixedCaseNameIdEnrollsWithItsGeneratedUsernameAndKeepsTheLinkAcrossSessions() throws Exception {
        String user = federated("nid-pat", "AbC-Pat-NameID");
        assertThat(status("nid-pat", user, SESSION_1, "/api/v1/awards/9000102/summary")).isEqualTo(200);   // Pat is PI
        assertThat(status("nid-pat", user, SESSION_1, "/api/v1/awards/9000201/summary")).isEqualTo(404);   // unrelated
        assertThat(jdbc.sql("SELECT institutional_identifier FROM authz.identity_link WHERE cognito_subject = 'nid-pat' "
                + "AND status = 'ACTIVE'").query(String.class).single()).isEqualTo("AbC-Pat-NameID");
        // A new sign-in session re-reads the profile (session verification) and keeps the link.
        assertThat(status("nid-pat", user, SESSION_2, "/api/v1/awards/9000102/summary")).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM authz.identity_link WHERE cognito_subject = 'nid-pat' "
                + "AND status = 'ACTIVE'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theNameIdIsMatchedToTheCrosswalkExactlyNotIgnoringCase() throws Exception {
        // The crosswalk holds "abc-kim-nameid"; the IdP sends "AbC-Kim-NameID": not the same person.
        String user = federated("nid-kim", "AbC-Kim-NameID");
        assertThat(status("nid-kim", user, SESSION_1, "/api/v1/awards/search")).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM authz.identity_link WHERE cognito_subject = 'nid-kim'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT detail->>'outcome' FROM authz.access_audit WHERE detail->>'cognito_subject' = 'nid-kim' "
                + "ORDER BY audit_id DESC LIMIT 1").query(String.class).single()).isEqualTo("REFUSED_UNKNOWN_PERSON");
    }
}
