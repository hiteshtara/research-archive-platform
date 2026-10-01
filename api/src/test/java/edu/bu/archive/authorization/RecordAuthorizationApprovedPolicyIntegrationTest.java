package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Record authorization ENFORCED with the policy APPROVED by Hitesh on 2026-10-01 (design section 14):
 * P3 FAMILY_WIDE within one Award number, P6 sub-units only when the grant's include-sub-units flag
 * is set (LEAD_UNIT_WITH_DESCENDANTS), P4 PI/MPI/COI (Key Person excluded), Research Staff via the
 * verified principal. Proves the approved boundaries: family-wide never reaches child Awards or
 * related records, and revoking one basis removes only the access that rested on it.
 * SYNTHETIC seed (api/src/test/resources/authz/synthetic-seed.sql); sign-in replaced (X-Test-Persona).
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
        "app.attachments.storage=local",
        "app.attachments.local-directory=target/authz-test-attachments"
})
@AutoConfigureMockMvc
class RecordAuthorizationApprovedPolicyIntegrationTest {

    static final String ISSUER = "https://demo.invalid/synthetic-cognito";
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

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
    static void loadSchemaAndSyntheticSeed() throws Exception {
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
        }
    }

    /** TEST-ONLY sign-in replacement. */
    @TestConfiguration
    static class SyntheticIdentity {
        @Bean
        @Primary
        CurrentIdentityProvider testIdentity() {
            return () -> Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(a -> ((ServletRequestAttributes) a).getRequest())
                    .filter(r -> r.getHeader("X-Test-Persona") != null)
                    .map(r -> new ValidatedCognitoIdentity(ISSUER, "demo-" + r.getHeader("X-Test-Persona"), null,
                            signInTime(r)));
        }

        /** auth_time: now, or X-Test-Sign-In-Age-Minutes ago; "none" means the token has no auth_time. */
        static java.time.Instant signInTime(jakarta.servlet.http.HttpServletRequest r) {
            String age = r.getHeader("X-Test-Sign-In-Age-Minutes");
            if ("none".equals(age)) {
                return null;
            }
            return java.time.Instant.now().minus(java.time.Duration.ofMinutes(age == null ? 1 : Long.parseLong(age)));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private int status(String persona, String path) throws Exception {
        return mvc.perform(get(path).header("X-Test-Persona", persona)).andReturn().getResponse().getStatus();
    }

    private JsonNode body(String persona, String path) throws Exception {
        var r = mvc.perform(get(path).header("X-Test-Persona", persona)).andReturn().getResponse();
        assertThat(r.getStatus()).as(persona + " " + path).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    private long grant(String institutionalId, String type, String unit, boolean descendants, String io) {
        return jdbc.sql("""
                INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, include_descendants,
                    io_value, granted_by, reason) VALUES (:i, :t, :u, :d, :io, 'it', 'SYNTHETIC') RETURNING grant_id
                """).param("i", institutionalId).param("t", type).param("u", unit).param("d", descendants)
                .param("io", io).query(Long.class).single();
    }

    private void revoke(long grantId) {
        jdbc.sql("UPDATE authz.access_grant SET revoked_by = 'it', revoked_at = now() WHERE grant_id = :g")
                .param("g", grantId).update();
    }

    @Test
    void familyWideOpensEveryVersionOfThePermittedAwardNumber() throws Exception {
        // Pat is PI only on A's versions in SYN-U-100; A' (9000103, SYN-U-200, another PI) opens family-wide.
        assertThat(status("pi", "/api/v1/awards/9000103/summary")).isEqualTo(200);
        // Pat is MPI on C; C' (9000302, another unit, no Pat) opens family-wide.
        assertThat(status("pi", "/api/v1/awards/9000302/summary")).isEqualTo(200);
        assertThat(body("pi", "/api/v1/awards/9000102/versions").get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void familyWideNeverReachesChildAwardsRelatedRecordsOrKeyPersonAwards() throws Exception {
        assertThat(status("pi", "/api/v1/awards/9000111/summary")).isEqualTo(404);           // A's child
        assertThat(status("pi", "/api/v1/awards/by-number/990001-00002")).isEqualTo(404);
        assertThat(body("pi", "/api/v1/awards/990001-00001/hierarchy").get("root").get("children")).isEmpty();
        assertThat(status("pi", "/api/proposals/SYN-PRP-0001")).isEqualTo(404);             // A's related Proposal
        assertThat(body("pi", "/api/v1/awards/9000102/funding-proposals")).isEmpty();
        assertThat(body("pi", "/api/v1/awards/9000102/negotiations")).isEmpty();
        assertThat(body("pi", "/api/v1/awards/9000102/funding-subawards")).isEmpty();
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(404);           // E: Key Person only
        assertThat(status("pi", "/api/v1/awards/9000201/summary")).isEqualTo(404);           // B: D's parent
        var dHierarchy = mvc.perform(get("/api/v1/awards/990004-00001/hierarchy").header("X-Test-Persona", "pi"))
                .andReturn().getResponse();
        assertThat(dHierarchy.getContentAsString()).doesNotContain("SYNTHETIC Award B");
    }

    @Test
    void subUnitsOnlyWhenTheGrantSaysSo() throws Exception {
        // Department's SYN-U-100 grant has include_descendants = TRUE: G (SYN-U-110) opens.
        assertThat(status("department", "/api/v1/awards/9000701/summary")).isEqualTo(200);
        jdbc.sql("UPDATE authz.access_grant SET include_descendants = FALSE "
                + "WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'").update();
        try {
            assertThat(status("department", "/api/v1/awards/9000701/summary")).isEqualTo(404);
        } finally {
            jdbc.sql("UPDATE authz.access_grant SET include_descendants = TRUE "
                    + "WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'").update();
        }
    }

    @Test
    void revokingOneBasisRemovesOnlyTheAccessThatRestedOnIt() throws Exception {
        // Pat gains a UNIT grant on SYN-U-300: E (unit) opens; C and D are also contact-derived.
        long unit = grant("SYN-INST-0003", "UNIT", "SYN-U-300", false, null);
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(200);
        revoke(unit);
        assertThat(status("pi", "/api/v1/awards/9000501/summary")).isEqualTo(404);           // no other basis
        assertThat(status("pi", "/api/v1/awards/9000301/summary")).isEqualTo(200);           // contact basis remains
        assertThat(status("pi", "/api/v1/awards/9000401/summary")).isEqualTo(200);

        // Department gains IO SYN-IO-7001 (Award F's account), then loses its UNIT grant.
        long io = grant("SYN-INST-0002", "IO", null, false, "SYN-IO-7001");
        long deptUnit = jdbc.sql("SELECT grant_id FROM authz.access_grant WHERE institutional_identifier = 'SYN-INST-0002' "
                + "AND grant_type = 'UNIT' AND revoked_at IS NULL").query(Long.class).single();
        revoke(deptUnit);
        try {
            assertThat(status("department", "/api/v1/awards/9000102/summary")).isEqualTo(404);   // unit basis gone
            assertThat(status("department", "/api/v1/awards/9000601/summary")).isEqualTo(200);   // IO basis remains
        } finally {
            jdbc.sql("UPDATE authz.access_grant SET revoked_by = NULL, revoked_at = NULL WHERE grant_id = :g")
                    .param("g", deptUnit).update();
            revoke(io);
        }
    }

    @Test
    void aUnitThatLedAnyVersionSeesTheWholeAwardButNotItsChildren() throws Exception {
        // Approved P3 consequence: A' (an old version of A) is in SYN-U-200, so a SYN-U-200 grant opens all of A.
        long g = grant("SYN-INST-0007", "UNIT", "SYN-U-200", false, null);
        try {
            assertThat(status("nogrants", "/api/v1/awards/9000102/summary")).isEqualTo(200);
            assertThat(status("nogrants", "/api/v1/awards/9000111/summary")).isEqualTo(404);   // A's child: SYN-U-100
        } finally {
            revoke(g);
        }
        assertThat(status("nogrants", "/api/v1/awards/9000102/summary")).isEqualTo(403);       // nothing left
    }

    // --- sign-in freshness (a refreshed token keeps its original auth_time) ----------------------

    private org.springframework.mock.web.MockHttpServletResponse signedInAgo(String persona, String age, String path)
            throws Exception {
        return mvc.perform(get(path).header("X-Test-Persona", persona).header("X-Test-Sign-In-Age-Minutes", age))
                .andReturn().getResponse();
    }

    @Test
    void aSignInOlderThanTheMaximumIsRefusedWith401EvenForCentral() throws Exception {
        assertThat(signedInAgo("pi", "719", "/api/v1/awards/9000101/summary").getStatus()).isEqualTo(200);
        for (String persona : List.of("pi", "department", "central")) {
            var refused = signedInAgo(persona, "721", "/api/v1/awards/9000101/summary");
            assertThat(refused.getStatus()).as(persona).isEqualTo(401);
            assertThat(json.readTree(refused.getContentAsString()).path("code").asText())
                    .isEqualTo("REAUTHENTICATION_REQUIRED");
            assertThat(refused.getHeader("WWW-Authenticate")).contains("invalid_token");
        }
        // Lists, search and files are refused the same way, not silently emptied.
        assertThat(signedInAgo("pi", "721", "/api/v1/awards/search").getStatus()).isEqualTo(401);
        // The access status names the problem so the UI can send the person back through BU login.
        var status = signedInAgo("pi", "721", "/api/v1/me/access");
        assertThat(status.getStatus()).isEqualTo(200);
        assertThat(json.readTree(status.getContentAsString()).path("problem").asText())
                .isEqualTo("REAUTHENTICATION_REQUIRED");
    }

    @Test
    void aTokenWithNoSignInTimeOrOneFromTheFutureIsRefused() throws Exception {
        assertThat(signedInAgo("pi", "none", "/api/v1/awards/9000101/summary").getStatus()).isEqualTo(401);
        assertThat(signedInAgo("central", "none", "/api/v1/awards/search").getStatus()).isEqualTo(401);
        // More than the allowed clock skew in the future: not trusted.
        assertThat(signedInAgo("pi", "-10", "/api/v1/awards/9000101/summary").getStatus()).isEqualTo(401);
        assertThat(signedInAgo("pi", "-2", "/api/v1/awards/9000101/summary").getStatus()).isEqualTo(200);
    }

    @Test
    void aFreshSignInDoesNotRestoreRevokedAccess() throws Exception {
        // Offboarding is a separate control: revoking the archive link denies even a brand-new sign-in.
        jdbc.sql("UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = 'offboarding-test', revoked_at = now() "
                + "WHERE cognito_subject = 'demo-oav'").update();
        try {
            assertThat(signedInAgo("oav", "0", "/api/v1/awards/9000601/summary").getStatus()).isEqualTo(403);
        } finally {
            jdbc.sql("UPDATE authz.identity_link SET status = 'ACTIVE', revoked_by = NULL, revoked_at = NULL "
                    + "WHERE cognito_subject = 'demo-oav'").update();
        }
        assertThat(signedInAgo("oav", "0", "/api/v1/awards/9000601/summary").getStatus()).isEqualTo(200);
    }
}
