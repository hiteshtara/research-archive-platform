package edu.bu.archive.adapter.in.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcAccessGrantRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminAuditSink;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminIdentityRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcAdminRoleRepository;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcIdentityLinkRepository;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.ArchiveAdminService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read-only administration, against the real schema.
 *
 * <p>The in-memory suite proves the gate returns the right status codes. This
 * one proves the thing that actually matters: that a refused write leaves the
 * DATABASE untouched. A 409 would be worthless if the row had already been
 * inserted and the audit trigger had already fired.
 *
 * <p>Every case runs with record-level enforcement OFF, because that is the
 * configuration this exists to support.
 */
@Testcontainers
class AdministrationReadOnlyIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";
    private static final String ADMIN_SUBJECT = "sub-admin";
    private static final String ADMIN_INST = "SYN-INST-ADMIN";
    private static final String ORDINARY_SUBJECT = "sub-ordinary";
    private static final String ORDINARY_INST = "SYN-INST-ORDINARY";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;

    private AuthorizationProperties properties;
    private ValidatedCognitoIdentity caller;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            AdministrationReadOnlyIntegrationTest::migrationVersion))
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
            statement.execute("""
                    INSERT INTO archive.unit (unit_number, unit_name, parent_unit_number)
                    VALUES ('SYN-U-001', 'Synthetic root', NULL)
                    """);
            statement.execute("""
                    INSERT INTO authz.identity_link (cognito_issuer, cognito_subject,
                        institutional_identifier, kuali_person_id, login_name, method, status,
                        verified_by, verified_at)
                    VALUES
                      ('%1$s', '%2$s', '%3$s', 'SYNP-ADMIN', 'syn-admin', 'ADMIN_VERIFIED',
                       'ACTIVE', 'operator', now()),
                      ('%1$s', '%4$s', '%5$s', 'SYNP-ORD', 'syn-ord', 'ADMIN_VERIFIED',
                       'ACTIVE', 'operator', now())
                    """.formatted(ISSUER, ADMIN_SUBJECT, ADMIN_INST, ORDINARY_SUBJECT, ORDINARY_INST));
            statement.execute("""
                    INSERT INTO authz.admin_role_member (institutional_identifier, status,
                        granted_by, granted_at, reason)
                    VALUES ('%s', 'ACTIVE', 'BOOTSTRAP', now(), 'test fixture')
                    """.formatted(ADMIN_INST));
        }

        jdbc = JdbcClient.create(dataSource);
    }

    @BeforeEach
    void readOnlyConfiguration() {
        properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(false);          // record enforcement OFF
        properties.setAdministrationEnabled(true);        // surface open
        properties.setAdministrationWritesEnabled(false); // read-only
        caller = new ValidatedCognitoIdentity(ISSUER, ADMIN_SUBJECT);
    }

    // ---- the fixture is real -------------------------------------------

    @Test
    void theFixtureIsRealSoTheseAssertionsAreNotVacuous() {
        assertThat(count("authz.identity_link")).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM authz.admin_role_member WHERE status = 'ACTIVE'")
                .query(Integer.class).single())
                .as("an ACTIVE ARCHIVE_ADMIN must exist, or every read would 403 for the wrong reason")
                .isEqualTo(1);
    }

    // ---- the four reads, against the real schema -----------------------

    @Test
    void allFourReadEndpointsAnswerForAnAdministratorWithEnforcementOff() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/admin/identities?q=syn")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/identities/" + ADMIN_INST)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/administrators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].institutionalIdentifier").value(ADMIN_INST));
    }

    @Test
    void aNonAdministratorIsRefusedOnTheReads() throws Exception {
        caller = new ValidatedCognitoIdentity(ISSUER, ORDINARY_SUBJECT);
        MockMvc mvc = mvc();
        // 403, not 404: with administration enabled the surface exists.
        mvc.perform(get("/api/v1/admin/identities?q=syn")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/administrators")).andExpect(status().isForbidden());
    }

    // ---- the eight writes change NOTHING in the database ---------------

    @Test
    void everyWriteIsRefusedAndTheDatabaseIsUntouched() throws Exception {
        int grantsBefore = count("authz.access_grant");
        int auditBefore = count("authz.access_audit");
        int linksBefore = count("authz.identity_link");
        int adminsBefore = count("authz.admin_role_member");
        int suspendedBefore = count("authz.person_status");

        expectAllWritesRefused();

        assertThat(count("authz.access_grant"))
                .as("a refused grant must not reach authz.access_grant")
                .isEqualTo(grantsBefore);
        assertThat(count("authz.access_audit"))
                .as("a refused write must write no audit row")
                .isEqualTo(auditBefore);
        assertThat(count("authz.identity_link")).isEqualTo(linksBefore);
        assertThat(count("authz.admin_role_member")).isEqualTo(adminsBefore);
        assertThat(count("authz.person_status")).isEqualTo(suspendedBefore);
    }

    @Test
    void theRefusalCarriesTheDisabledCodeAndNotAnAuthorizationError() throws Exception {
        mvc().perform(post("/api/v1/admin/identities/" + ORDINARY_INST + "/grants")
                        .contentType("application/json")
                        .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMINISTRATION_WRITES_DISABLED"));
    }

    /**
     * Non-vacuity for the test above: the same request DOES write once writes
     * are enabled. Without this, "no rows changed" could pass because the
     * request could never have succeeded for an unrelated reason.
     */
    @Test
    void theSameWriteSucceedsAndAuditsOnceWritesAreEnabled() throws Exception {
        properties.setAdministrationWritesEnabled(true);
        int grantsBefore = count("authz.access_grant");
        int auditBefore = count("authz.access_audit");

        mvc().perform(post("/api/v1/admin/identities/" + ORDINARY_INST + "/grants")
                        .contentType("application/json")
                        .content("{\"type\":\"IO\",\"ioValue\":\"9500099999\"}"))
                .andExpect(status().isCreated());

        assertThat(count("authz.access_grant")).isEqualTo(grantsBefore + 1);
        assertThat(count("authz.access_audit"))
                .as("a successful grant writes exactly one audit row")
                .isEqualTo(auditBefore + 1);

        // Record enforcement is still off, so this grant changes nothing a
        // research user can see.
        assertThat(properties.isEnforcementEnabled()).isFalse();
    }

    /** Auditing does not depend on record enforcement, and is append-only. */
    @Test
    void theAuditTrailIsAppendOnlyRegardlessOfRecordEnforcement() {
        assertThat(properties.isEnforcementEnabled()).isFalse();
        jdbc.sql("""
                INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail)
                VALUES ('test', 'ACCESS_GRANTED', ?, '{}'::jsonb)
                """).param(ORDINARY_INST).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("UPDATE authz.access_audit SET actor = 'tampered'").update())
                .as("the database, not a convention, refuses to change an audit row")
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("DELETE FROM authz.access_audit").update())
                .hasMessageContaining("append-only");
    }

    // ---- helpers -------------------------------------------------------

    private void expectAllWritesRefused() throws Exception {
        MockMvc mvc = mvc();
        var refused = status().isConflict();
        mvc.perform(post("/api/v1/admin/identities/" + ORDINARY_INST + "/links")
                .contentType("application/json")
                .content("{\"cognitoIssuer\":\"" + ISSUER + "\",\"cognitoSubject\":\"sub-new\","
                        + "\"method\":\"ADMIN_VERIFIED\",\"reason\":\"x\"}")).andExpect(refused);
        mvc.perform(delete("/api/v1/admin/identities/" + ORDINARY_INST + "/links/1?reason=x"))
                .andExpect(refused);
        mvc.perform(put("/api/v1/admin/identities/" + ORDINARY_INST + "/suspension")
                .contentType("application/json")
                .content("{\"suspended\":true,\"reason\":\"x\"}")).andExpect(refused);
        mvc.perform(post("/api/v1/admin/identities/" + ORDINARY_INST + "/grants")
                .contentType("application/json")
                .content("{\"type\":\"IO\",\"ioValue\":\"9500012345\"}")).andExpect(refused);
        mvc.perform(patch("/api/v1/admin/identities/" + ORDINARY_INST + "/grants/1")
                .contentType("application/json")
                .content("{\"includeDescendants\":true}")).andExpect(refused);
        mvc.perform(delete("/api/v1/admin/identities/" + ORDINARY_INST + "/grants/1?reason=x"))
                .andExpect(refused);
        mvc.perform(post("/api/v1/admin/administrators")
                .contentType("application/json")
                .content("{\"institutionalIdentifier\":\"" + ORDINARY_INST + "\",\"reason\":\"x\"}"))
                .andExpect(refused);
        mvc.perform(delete("/api/v1/admin/administrators/" + ORDINARY_INST + "?reason=x"))
                .andExpect(refused);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private MockMvc mvc() {
        ArchiveAdminService service = new ArchiveAdminService(
                new JdbcAdminRoleRepository(jdbc),
                new JdbcAdminAuditSink(jdbc),
                new JdbcAccessGrantRepository(jdbc),
                new JdbcAdminIdentityRepository(jdbc),
                new JdbcAdminAuditRepository(jdbc));
        CurrentIdentityProvider identities = () -> Optional.ofNullable(caller);
        AdminAuthorizationController controller = new AdminAuthorizationController(
                service, identities,
                new IdentityResolver(new JdbcIdentityLinkRepository(jdbc)), properties,
                Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static int migrationVersion(Path path) {
        var matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate database/migrations/");
    }
}
