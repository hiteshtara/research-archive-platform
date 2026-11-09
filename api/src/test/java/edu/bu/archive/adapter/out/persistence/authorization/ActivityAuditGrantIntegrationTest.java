package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-008: the activity-audit grant is least-privilege, and granting it
 * widens nothing else.
 *
 * <p>The risk a new grant carries is not what it obviously allows but what it
 * quietly also allows. So this runs the real
 * {@code database/roles/archive_api_activity_audit_grant.sql} on top of the
 * real role script, then acts AS {@code archive_api} and checks both halves:
 * the one new thing it can do, and everything it still cannot.
 *
 * <p>Not applied to any shared database. This is the rehearsal.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ActivityAuditGrantIntegrationTest {

    private static final String API_PASSWORD = ArchiveApiRoleHarness.API_PASSWORD;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static Connection owner;
    private static Connection api;

    @BeforeAll
    static void provisionAndGrant() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ArchiveApiRoleHarness.provision(owner, postgres);

        try (Statement s = owner.createStatement()) {
            s.execute(Files.readString(locateGrantScript()));
        }
        api = DriverManager.getConnection(
                postgres.getJdbcUrl(), ArchiveApiRoleHarness.API_USER, API_PASSWORD);
    }

    @AfterAll
    static void close() throws SQLException {
        if (api != null) api.close();
        if (owner != null) owner.close();
    }

    // ── the one thing it grants ─────────────────────────────────────────

    @Test
    @DisplayName("the API role can insert an activity row")
    void theApiRoleCanInsert() {
        assertThatCode(() -> asApi("""
                INSERT INTO authz.activity_audit
                    (occurred_at, actor, action, resource_type, resource_key, outcome, correlation_id)
                VALUES (CURRENT_TIMESTAMP, 'ARP-SYN-0001', 'RECORD_VIEWED',
                        'AWARD', 'SYN-AWD-A', 'PERMITTED', 'corr-1')
                """)).doesNotThrowAnyException();
    }

    // ── everything it must NOT grant ────────────────────────────────────

    @Test
    @DisplayName("it grants no SELECT: writing the audit is not reading it")
    void theApiRoleCannotReadTheAudit() {
        /*
         * Who may read an audit of who read what is a separate
         * access-control question. Granting SELECT here would answer it by
         * accident.
         */
        assertThatThrownBy(() -> asApi("SELECT count(*) FROM authz.activity_audit"))
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("it grants no UPDATE or DELETE, and the grant refuses before the trigger")
    void theAuditStaysAppendOnlyForTheApiRole() {
        assertThatThrownBy(() -> asApi("UPDATE authz.activity_audit SET actor = 'x'"))
                .as("the grant must refuse first; the append-only trigger is the second line")
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApi("DELETE FROM authz.activity_audit"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApi("TRUNCATE authz.activity_audit"))
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("catalog privileges are exactly INSERT")
    void catalogPrivilegesAreExactlyInsert() throws Exception {
        assertThat(privilege("INSERT")).isTrue();
        assertThat(privilege("SELECT")).isFalse();
        assertThat(privilege("UPDATE")).isFalse();
        assertThat(privilege("DELETE")).isFalse();
        assertThat(privilege("TRUNCATE")).isFalse();
    }

    @Test
    @DisplayName("granting it changes nothing about archive data")
    void theArchiveIsStillReadOnly() throws Exception {
        /*
         * The question this test exists for: did adding an authz privilege
         * widen anything in the archive schema? The same catalog sweep
         * SEC-009 uses, re-run after the grant.
         */
        List<String> offenders = new ArrayList<>();
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT n.nspname || '.' || c.relname || ' -> ' || p.priv
                       FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      CROSS JOIN (VALUES ('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE')) AS p(priv)
                      WHERE n.nspname = 'archive'
                        AND c.relkind IN ('r','p')
                        AND has_table_privilege('archive_api', c.oid, p.priv)
                      ORDER BY 1
                     """)) {
            while (rs.next()) {
                offenders.add(rs.getString(1));
            }
        }
        assertThat(offenders)
                .as("the activity-audit grant must not widen archive access")
                .isEmpty();

        assertThatThrownBy(() -> asApi("INSERT INTO archive.person (person_id) VALUES ('X')"))
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("granting it changes nothing about the administrative audit")
    void theAdministrativeAuditIsUnchanged() {
        // access_audit stays INSERT-only too; the new grant is for a
        // different table and must not have touched this one.
        assertThatThrownBy(() -> asApi("UPDATE authz.access_audit SET actor = 'x'"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApi("DELETE FROM authz.access_audit"))
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("the script refuses to run before V089 exists")
    void theScriptRefusesWithoutTheTable() throws Exception {
        /*
         * A grant script that silently does nothing when the table is absent
         * would look like it had worked, and the first audit write would fail
         * in production instead.
         */
        asOwner("ALTER TABLE authz.activity_audit RENAME TO activity_audit_hidden");
        try {
            assertThatThrownBy(() -> asOwner(Files.readString(locateGrantScript())))
                    .hasMessageContaining("Apply V089 before granting");
        } finally {
            asOwner("ROLLBACK");
            asOwner("ALTER TABLE IF EXISTS authz.activity_audit_hidden RENAME TO activity_audit");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static boolean privilege(String name) throws Exception {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT has_table_privilege('archive_api','authz.activity_audit','"
                             + name + "')")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static void asApi(String sql) throws SQLException {
        try (Statement s = api.createStatement()) {
            s.execute(sql);
        }
    }

    private static void asOwner(String sql) throws SQLException {
        try (Statement s = owner.createStatement()) {
            s.execute(sql);
        }
    }

    private static Path locateGrantScript() {
        Path candidate = Path.of("database", "roles", "archive_api_activity_audit_grant.sql");
        return Files.exists(candidate)
                ? candidate
                : Path.of("..", "database", "roles", "archive_api_activity_audit_grant.sql");
    }
}
