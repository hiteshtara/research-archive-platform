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
 * {@code database/roles/archive_api_privilege_approval_grant.sql} is
 * least-privilege: run on top of the real role script and the held-back
 * V090, then act AS {@code archive_api} and check both halves - what the
 * workflow needs (SELECT, INSERT, UPDATE) and what it must never have
 * (DELETE, TRUNCATE, or a way round the immutability trigger).
 *
 * <p>Not applied to any shared database. This is the rehearsal.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeApprovalGrantIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static Connection owner;
    private static Connection api;

    @BeforeAll
    static void provisionAndGrant() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ArchiveApiRoleHarness.provision(owner, postgres);

        // Before V090 exists the grant script must refuse rather than half-grant.
        String grant = Files.readString(locateGrantScript());
        try (Statement s = owner.createStatement()) {
            assertThatThrownBy(() -> s.execute(grant))
                    .hasMessageContaining("Apply V090 before granting");
            s.execute("ROLLBACK");
        }

        ArchiveApiRoleHarness.applyPendingApproval(owner);
        try (Statement s = owner.createStatement()) {
            s.execute(grant);
            s.execute("""
                    INSERT INTO authz.admin_role_member (institutional_identifier, status, granted_by, reason)
                    VALUES ('SYN-B', 'ACTIVE', 'test-fixture', 'synthetic approver for the grant test')
                    """);
        }
        api = DriverManager.getConnection(postgres.getJdbcUrl(),
                ArchiveApiRoleHarness.API_USER, ArchiveApiRoleHarness.API_PASSWORD);
    }

    @AfterAll
    static void close() throws SQLException {
        if (api != null) api.close();
        if (owner != null) owner.close();
    }

    @Test
    @DisplayName("exactly SELECT, INSERT and UPDATE; no DELETE, no TRUNCATE")
    void privilegesAreExactlyWhatTheWorkflowNeeds() throws Exception {
        assertThat(privilege("SELECT")).isTrue();
        assertThat(privilege("INSERT")).isTrue();
        assertThat(privilege("UPDATE")).isTrue();
        assertThat(privilege("DELETE")).isFalse();
        assertThat(privilege("TRUNCATE")).isFalse();
        assertThat(privilege("REFERENCES")).isFalse();
        assertThat(privilege("TRIGGER")).isFalse();
    }

    @Test
    @DisplayName("the API role can request, approve and consume through the trigger")
    void theWorkflowRunsAsTheApiRole() throws Exception {
        long id;
        try (Statement s = api.createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO authz.privilege_approval
                         (action, subject_identifier, requested_by, reason, valid_until)
                     VALUES ('ADMIN_ASSIGN', 'SYN-S1', 'SYN-A', 'requested as the API role', now())
                     RETURNING approval_id
                     """)) {
            rs.next();
            id = rs.getLong(1);
        }
        assertThatCode(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-B', approved_at = now(), approval_reason = 'approved as the API role' "
                + "WHERE approval_id = " + id)).doesNotThrowAnyException();
        assertThatCode(() -> asApi("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                + "consumed_at = now(), consumed_by = 'SYN-A' WHERE approval_id = " + id))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UPDATE does not let the API role rewrite a bound column")
    void updateCannotBypassImmutability() {
        assertThatThrownBy(() -> {
            asApi("""
                    INSERT INTO authz.privilege_approval
                        (action, subject_identifier, requested_by, reason, valid_until)
                    VALUES ('ADMIN_ASSIGN', 'SYN-S2', 'SYN-A', 'requested as the API role', now())
                    """);
            asApi("UPDATE authz.privilege_approval SET subject_identifier = 'SYN-SOMEONE-ELSE' "
                    + "WHERE subject_identifier = 'SYN-S2'");
        }).hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("DELETE and TRUNCATE are refused by the grant, before the trigger")
    void appendOnlyForTheApiRole() {
        assertThatThrownBy(() -> asApi("DELETE FROM authz.privilege_approval"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApi("TRUNCATE authz.privilege_approval"))
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("re-running the role script after V090 grants no DELETE on any authz table")
    void roleScriptRerunStillGrantsNoDelete() throws Exception {
        try (Statement s = owner.createStatement()) {
            s.execute(ArchiveApiRoleHarness.roleScriptFor(postgres));
            s.execute(Files.readString(locateGrantScript()));
        }
        List<String> deletable = new ArrayList<>();
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'authz' AND c.relkind IN ('r', 'p')
                        AND (has_table_privilege('archive_api', c.oid, 'DELETE')
                             OR has_table_privilege('archive_api', c.oid, 'TRUNCATE'))
                     """)) {
            while (rs.next()) {
                deletable.add(rs.getString(1));
            }
        }
        assertThat(deletable).isEmpty();
        assertThat(privilege("UPDATE")).as("and the grant is still in place").isTrue();
    }

    private static boolean privilege(String kind) throws SQLException {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery("SELECT has_table_privilege('archive_api', "
                     + "'authz.privilege_approval', '" + kind + "')")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static void asApi(String sql) throws SQLException {
        try (Statement s = api.createStatement()) {
            s.execute(sql);
        }
    }

    private static Path locateGrantScript() {
        Path c = Path.of("database", "roles", "archive_api_privilege_approval_grant.sql");
        return Files.exists(c) ? c
                : Path.of("..", "database", "roles", "archive_api_privilege_approval_grant.sql");
    }
}
