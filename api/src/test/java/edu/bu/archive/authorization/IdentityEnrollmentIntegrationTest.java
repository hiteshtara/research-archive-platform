package edu.bu.archive.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.CognitoProfile;
import edu.bu.archive.application.authorization.CognitoProfileReader;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Server-side enrollment (design 12.2 Option A, 13), ENFORCED, end to end:
 * the real application, the real JDBC enrollment store, V082 + V083 and the
 * SYNTHETIC seed on a disposable Postgres. Only two things are replaced, both
 * TEST-ONLY: sign-in (the X-Test-Sub / X-Test-Username headers stand in for a
 * validated access token) and the user pool (an in-memory AdminGetUser).
 *
 * <p>Policy here is VERIFIED_PRINCIPAL (approved decision, design 13) with the
 * demo role set PI, MPI, COI. Real federation and the real attribute: NOT VERIFIED.
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.security.enabled=false",
        "app.authorization.enforcement-enabled=true",
        "app.authorization.version-scope=PER_VERSION",
        "app.authorization.department-match=EXACT_LEAD_UNIT",
        "app.authorization.research-staff-roles=PI,MPI,COI",
        "app.authorization.contact-derivation=VERIFIED_PRINCIPAL",
        "app.authorization.enrollment.enabled=true",
        "app.authorization.enrollment.user-pool-id=us-east-1_SYNTHETIC",
        "app.authorization.enrollment.region=us-east-1",
        // Never reached: the test replaces the reader. Points nowhere real.
        "app.authorization.enrollment.endpoint-override=http://127.0.0.1:9",
        "app.authorization.enrollment.saml-provider-name=SyntheticSaml",
        "app.authorization.enrollment.identifier-attribute=custom:synthetic_principal_attr",
        "app.authorization.enrollment.crosswalk-attribute-name=syntheticPrincipalAttr",
        "app.authorization.enrollment.refusal-retry-seconds=0",
        "app.attachments.storage=local",
        "app.attachments.local-directory=target/authz-enrollment-test-attachments",
        "app.search.semantic.enabled=false"
})
@AutoConfigureMockMvc
class IdentityEnrollmentIntegrationTest {

    static final String ISSUER = "https://enroll.invalid/synthetic-pool";
    static final String SEED_ISSUER = "https://demo.invalid/synthetic-cognito";
    static final String PROVIDER = "SyntheticSaml";
    static final String ATTRIBUTE = "custom:synthetic_principal_attr";
    static final String CROSSWALK = "syntheticPrincipalAttr";
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** The fake user pool: username -> profile. */
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
            // SYNTHETIC KIM principals and crosswalk. SYNP-PI-01 (Pat) is PI/MPI/COI on seed Awards;
            // SYNP-OTHER-04/-05/-06 are PIs on C+D+E, F and G. SYNP-INACTIVE is an inactive principal.
            s.execute("""
                    INSERT INTO authz.kim_principal (prncpl_id, entity_id, actv_ind, load_ref) VALUES
                      ('SYNP-PI-01', 'SYNE-01', 'Y', 'SYNTHETIC-LOAD'),
                      ('SYNP-OTHER-04', 'SYNE-04', 'Y', 'SYNTHETIC-LOAD'),
                      ('SYNP-OTHER-05', 'SYNE-05', 'Y', 'SYNTHETIC-LOAD'),
                      ('SYNP-OTHER-06', 'SYNE-06', 'Y', 'SYNTHETIC-LOAD'),
                      ('SYNP-INACTIVE', 'SYNE-99', 'N', 'SYNTHETIC-LOAD');
                    INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by) VALUES
                      ('syntheticPrincipalAttr', 'SYN-ATTR-PAT', 'SYNP-PI-01', 'FICTIONAL-1', 'synthetic'),
                      ('syntheticPrincipalAttr', 'SYN-ATTR-KIM', 'SYNP-OTHER-04', 'FICTIONAL-2', 'synthetic'),
                      ('syntheticPrincipalAttr', 'SYN-ATTR-JO', 'SYNP-OTHER-05', 'FICTIONAL-3', 'synthetic'),
                      ('syntheticPrincipalAttr', 'SYN-ATTR-AL', 'SYNP-OTHER-06', 'FICTIONAL-4', 'synthetic'),
                      ('syntheticPrincipalAttr', 'SYN-ATTR-GONE', 'SYNP-INACTIVE', 'FICTIONAL-5', 'synthetic');
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
                    .map(r -> new ValidatedCognitoIdentity(
                            Optional.ofNullable(r.getHeader("X-Test-Issuer")).orElse(ISSUER),
                            r.getHeader("X-Test-Sub"), r.getHeader("X-Test-Username"),
                            // the token's auth_time: one value per sign-in session
                            java.time.Instant.ofEpochSecond(Long.parseLong(
                                    Optional.ofNullable(r.getHeader("X-Test-Auth-Time")).orElse("1790000000")))));
        }

        @Bean
        @Primary
        CognitoProfileReader testPool() {
            return username -> Optional.ofNullable(POOL.get(username));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    /** A federated profile in the fake pool; returns the token username. */
    private static String federated(String sub, String provider, String value) {
        String username = provider + "_nameid-" + sub;
        Map<String, String> attributes = new java.util.HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", "SYNTHETIC-" + sub + "@example.invalid");
        if (value != null) {
            attributes.put(ATTRIBUTE, value);
        }
        POOL.put(username, new CognitoProfile(sub, username, true,
                List.of(new CognitoProfile.FederatedIdentity(provider, "nameid-" + sub)), attributes));
        return username;
    }

    private MvcResult call(String sub, String username, String path) throws Exception {
        var request = get(path).header("X-Test-Sub", sub);
        if (username != null) {
            request = request.header("X-Test-Username", username);
        }
        return mvc.perform(request).andReturn();
    }

    private int status(String sub, String username, String path) throws Exception {
        return call(sub, username, path).getResponse().getStatus();
    }

    private String code(String sub, String username, String path) throws Exception {
        MvcResult result = call(sub, username, path);
        return json.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private List<Map<String, Object>> links(String sub) {
        return jdbc.sql("SELECT * FROM authz.identity_link WHERE cognito_issuer = :i AND cognito_subject = :s")
                .param("i", ISSUER).param("s", sub).query().listOfRows();
    }

    private List<String> auditOutcomes(String sub) {
        return jdbc.sql("""
                        SELECT detail->>'outcome' FROM authz.access_audit
                        WHERE actor = 'api-enrollment' AND detail->>'cognito_subject' = :s ORDER BY audit_id
                        """)
                .param("s", sub).query(String.class).list();
    }

    private Set<String> awardFamilies(String sub, String username) throws Exception {
        MvcResult result = call(sub, username, "/api/v1/awards/search?size=100");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = json.readTree(result.getResponse().getContentAsString()).get("results");
        Set<String> numbers = new TreeSet<>();
        page.get("content").forEach(n -> numbers.add(n.get("awardNumber").asText()));
        return numbers;
    }

    @Test
    void aMatchingPrincipalIsEnrolledAndSeesTheirContactAwardsWithNoGrantRow() throws Exception {
        String user = federated("enr-pat", PROVIDER, "SYN-ATTR-PAT");

        assertThat(status("enr-pat", user, "/api/v1/awards/9000102/summary")).isEqualTo(200);   // PI
        assertThat(status("enr-pat", user, "/api/v1/awards/9000201/summary")).isEqualTo(404);   // unrelated
        assertThat(status("enr-pat", user, "/api/v1/awards/9000501/summary")).isEqualTo(404);   // KP only
        assertThat(awardFamilies("enr-pat", user))
                .containsExactly("990001-00001", "990003-00001", "990004-00001");

        var link = links("enr-pat");
        assertThat(link).hasSize(1);
        assertThat(link.get(0)).containsEntry("status", "ACTIVE").containsEntry("method", "AUTO_VERIFIED")
                .containsEntry("institutional_identifier", "SYN-ATTR-PAT").containsEntry("kuali_person_id", "SYNP-PI-01")
                .containsEntry("verified_by", "api-enrollment");
        assertThat(link.get(0).get("login_name")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = 'SYN-ATTR-PAT'")
                .query(Long.class).single()).isZero();
        assertThat(auditOutcomes("enr-pat")).containsExactly("LINKED");
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_audit WHERE detail::text LIKE '%example.invalid%'")
                .query(Long.class).single()).isZero();

        JsonNode access = json.readTree(call("enr-pat", user, "/api/v1/me/access").getResponse().getContentAsString());
        assertThat(access.get("grantKinds")).hasSize(1);
        assertThat(access.get("grantKinds").get(0).asText()).isEqualTo("RESEARCH_STAFF");
    }

    @Test
    void everyUnverifiableSignInFailsClosedAndIsAudited() throws Exception {
        record Case(String sub, String username, String outcome) {
        }
        String revokedUser = federated("enr-prev-revoked", PROVIDER, "SYN-ATTR-AL");
        jdbc.sql("""
                INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier, kuali_person_id,
                    method, status, verified_by, verified_at, revoked_by, revoked_at)
                VALUES (:i, 'enr-prev-revoked', 'SYN-ATTR-AL', 'SYNP-OTHER-06', 'AUTO_VERIFIED', 'REVOKED', 'test', now(), 'test', now())
                """).param("i", ISSUER).update();
        String patProfile = federated("enr-real-owner", PROVIDER, "SYN-ATTR-PAT");

        List<Case> cases = List.of(
                new Case("enr-unknown", federated("enr-unknown", PROVIDER, "SYN-ATTR-NOBODY"), "REFUSED_UNKNOWN_PERSON"),
                new Case("enr-inactive", federated("enr-inactive", PROVIDER, "SYN-ATTR-GONE"), "REFUSED_INACTIVE_PRINCIPAL"),
                new Case("enr-wrong-idp", federated("enr-wrong-idp", "SomeOtherIdp", "SYN-ATTR-PAT"), "REFUSED_NOT_FEDERATED"),
                new Case("enr-no-attr", federated("enr-no-attr", PROVIDER, null), "REFUSED_MISSING_IDENTIFIER"),
                new Case("enr-prev-revoked", revokedUser, "REFUSED_PREVIOUSLY_REVOKED"),
                // A token whose username names someone else's profile: sub mismatch.
                new Case("enr-forged", patProfile, "REFUSED_SUBJECT_MISMATCH"),
                new Case("enr-no-username", null, "REFUSED_NO_USERNAME"),
                new Case("enr-not-in-pool", "SyntheticSaml_nameid-nobody", "REFUSED_PROFILE_NOT_FOUND"));

        for (Case c : cases) {
            MvcResult result = call(c.sub(), c.username(), "/api/v1/awards/9000102/summary");
            assertThat(result.getResponse().getStatus()).as(c.sub()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).as(c.sub()).doesNotContain("SYNTHETIC Award");
            assertThat(auditOutcomes(c.sub())).as(c.sub()).containsExactly(c.outcome());
            assertThat(links(c.sub()).stream().filter(l -> "ACTIVE".equals(l.get("status")))).as(c.sub()).isEmpty();
        }
        assertThat(code("enr-unknown", cases.get(0).username(), "/api/v1/awards/9000102/summary")).isEqualTo("ACCESS_NOT_PROVISIONED");
        assertThat(code("enr-prev-revoked", revokedUser, "/api/v1/awards/9000102/summary")).isEqualTo("ACCESS_DENIED");
        // Refusal audit rows are ENROLLMENT_REFUSED; an unknown value is never recorded.
        assertThat(jdbc.sql("SELECT DISTINCT action FROM authz.access_audit WHERE detail->>'cognito_subject' = 'enr-unknown'")
                .query(String.class).list()).containsExactly("ENROLLMENT_REFUSED");
        assertThat(jdbc.sql("SELECT count(*) FROM authz.access_audit WHERE detail::text LIKE '%SYN-ATTR-NOBODY%' "
                + "OR institutional_identifier = 'SYN-ATTR-NOBODY'").query(Long.class).single()).isZero();
    }

    @Test
    void aSecondProfileForAnAlreadyLinkedIdentifierIsRefused() throws Exception {
        String first = federated("enr-kim-a", PROVIDER, "SYN-ATTR-KIM");
        String second = federated("enr-kim-b", PROVIDER, "SYN-ATTR-KIM");
        assertThat(status("enr-kim-a", first, "/api/v1/awards/9000301/summary")).isEqualTo(200);
        assertThat(status("enr-kim-b", second, "/api/v1/awards/9000301/summary")).isEqualTo(403);
        assertThat(auditOutcomes("enr-kim-b")).containsExactly("REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE");
        assertThat(links("enr-kim-b")).isEmpty();
    }

    @Test
    void revokingTheCrosswalkRowDeniesTheNextRequestAndRevokesTheLink() throws Exception {
        String user = federated("enr-jo", PROVIDER, "SYN-ATTR-JO");
        assertThat(status("enr-jo", user, "/api/v1/awards/9000601/summary")).isEqualTo(200);

        jdbc.sql("""
                UPDATE authz.principal_crosswalk SET status = 'REVOKED', revoked_by = 'test', revoked_at = now()
                WHERE attribute_value = 'SYN-ATTR-JO'
                """).update();

        assertThat(status("enr-jo", user, "/api/v1/awards/9000601/summary")).isEqualTo(403);
        var link = links("enr-jo");
        assertThat(link).hasSize(1);
        assertThat(link.get(0)).containsEntry("status", "REVOKED").containsEntry("revoked_by", "api-enrollment");
        assertThat(jdbc.sql("""
                        SELECT action FROM authz.access_audit
                        WHERE detail->>'cognito_subject' = 'enr-jo' AND detail->>'outcome' = 'REVOKED_MAPPING_NO_LONGER_VALID'
                        """).query(String.class).list()).containsExactly("ENROLLMENT_LINK_REVOKED");
        // Never silently re-linked, even if the row came back.
        assertThat(status("enr-jo", user, "/api/v1/awards/9000601/summary")).isEqualTo(403);
        assertThat(auditOutcomes("enr-jo"))
                .containsExactly("LINKED", "REVOKED_MAPPING_NO_LONGER_VALID", "REFUSED_PREVIOUSLY_REVOKED");
    }

    @Test
    void anAdminVerifiedSeedLinkWithoutVerifiableSignInEvidenceIsDeniedButNotRevoked() throws Exception {
        // No token username / no federated profile: the session cannot be re-verified, so the
        // request fails closed; the administrator's link itself is left for an administrator.
        MvcResult result = mvc.perform(get("/api/v1/awards/9000201/summary")
                .header("X-Test-Issuer", SEED_ISSUER).header("X-Test-Sub", "demo-central")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM authz.identity_link WHERE cognito_subject = 'demo-central'")
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void aReassignedNameIdThatNowCarriesAnotherIdentifierLosesTheLinkAtTheNextSignIn() throws Exception {
        jdbc.sql("INSERT INTO authz.kim_principal (prncpl_id, entity_id, actv_ind, load_ref) "
                + "VALUES ('SYNP-OTHER-07', 'SYNE-R', 'Y', 'test') ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, "
                + "verified_by) VALUES ('syntheticPrincipalAttr', 'SYN-ATTR-REASSIGN', 'SYNP-OTHER-07', 'x', 'x')").update();
        // Linked and working in sign-in session 1 (SYNP-OTHER-07 is PI on Award H).
        String username = federated("enr-reassign", PROVIDER, "SYN-ATTR-REASSIGN");
        assertThat(callAt("enr-reassign", username, 1_790_000_000L, "/api/v1/awards/search").getResponse().getStatus())
                .isEqualTo(200);
        // BU reassigns the NameID: same Cognito username and sub, but the assertion (and so the
        // profile) now carries a different person's identifier. Session 2 must not inherit access.
        federated("enr-reassign", PROVIDER, "SYN-ATTR-NOBODY");
        MvcResult next = callAt("enr-reassign", username, 1_790_003_600L, "/api/v1/awards/search");
        assertThat(next.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM authz.identity_link WHERE cognito_subject = 'enr-reassign'")
                .query(String.class).single()).isEqualTo("REVOKED");
        assertThat(auditOutcomes("enr-reassign")).contains("REVOKED_IDENTITY_EVIDENCE_CHANGED");
    }

    private MvcResult callAt(String sub, String username, long authTime, String path) throws Exception {
        return mvc.perform(get(path).header("X-Test-Sub", sub).header("X-Test-Username", username)
                .header("X-Test-Auth-Time", String.valueOf(authTime))).andReturn();
    }

    @Test
    void theSchemaItselfRefusesAmbiguousAndNonPrincipalCrosswalkRows() {
        // One value -> two principals, and one principal <- two values: unique among ACTIVE rows.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by)
                VALUES ('syntheticPrincipalAttr', 'SYN-ATTR-PAT', 'SYNP-OTHER-04', 'x', 'x')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by)
                VALUES ('syntheticPrincipalAttr', 'SYN-ATTR-PAT-2', 'SYNP-PI-01', 'x', 'x')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        // A rolodex (non-employee) id is not a KIM principal: no crosswalk row can name it.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by)
                VALUES ('syntheticPrincipalAttr', 'SYN-ATTR-ROLODEX', '990001', 'x', 'x')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        // The audit stays append-only.
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM authz.access_audit").update())
                .hasMessageContaining("append-only");
    }
}
