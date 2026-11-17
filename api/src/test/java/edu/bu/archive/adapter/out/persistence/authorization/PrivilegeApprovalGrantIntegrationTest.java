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

    // ── QA M1: the database, not the caller, owns every timestamp ───────
    //
    // Exploit proven by QA against 4e95339c: as archive_api, INSERT with a
    // backdated requested_at, then approve and consume with backdated
    // timestamps; every CHECK compared against a window derived from the
    // caller's own clock, so an expired approval reached CONSUMED.

    @Test
    @DisplayName("QA M1: a backdated requested_at is refused for the API role")
    void backdatedRequestIsRefused() throws Exception {
        assertThatThrownBy(() -> asApi("""
                INSERT INTO authz.privilege_approval
                    (action, subject_identifier, requested_by, reason, requested_at, valid_until)
                VALUES ('ADMIN_ASSIGN', 'SYN-M1-REQ', 'SYN-A', 'backdated by the caller',
                        now() - interval '100 hours', now())
                """)).hasMessageContaining("requested_at");
        assertThat(count("subject_identifier = 'SYN-M1-REQ'")).isZero();
    }

    @Test
    @DisplayName("QA M1: a backdated approved_at is refused for the API role")
    void backdatedApprovalIsRefused() throws Exception {
        long id = apiRequest("SYN-M1-APR");
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-B', approved_at = now() - interval '1 hour', "
                + "approval_reason = 'approved with a backdated time' WHERE approval_id = " + id))
                .hasMessageContaining("approved_at");
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("QA M1: a backdated consumed_at is refused for the API role")
    void backdatedConsumeIsRefused() throws Exception {
        long id = apiApproved("SYN-M1-CON");
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                + "consumed_at = now() - interval '1 minute', consumed_by = 'SYN-A' "
                + "WHERE approval_id = " + id))
                .hasMessageContaining("consumed_at");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("QA M1: an expired row cannot be approved or consumed by SQL, whatever timestamps are supplied")
    void expiredApprovalCannotBeApprovedOrConsumedBySql() throws Exception {
        long pending = apiRequest("SYN-M1-EXP-P");
        expireAsOwner(pending);
        for (String approvedAt : new String[] {"requested_at + interval '1 hour'", "now()"}) {
            assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                    + "approved_by = 'SYN-B', approved_at = " + approvedAt + ", "
                    + "approval_reason = 'approving an expired request' WHERE approval_id = " + pending))
                    .as("approved_at = %s", approvedAt)
                    .isInstanceOf(SQLException.class);
        }
        assertThat(statusOf(pending)).isEqualTo("PENDING");

        long approved = apiApproved("SYN-M1-EXP-A");
        expireAsOwner(approved);
        for (String consumedAt : new String[] {"approved_at + interval '1 minute'", "now()"}) {
            assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                    + "consumed_at = " + consumedAt + ", consumed_by = 'SYN-A' "
                    + "WHERE approval_id = " + approved))
                    .as("consumed_at = %s", consumedAt)
                    .isInstanceOf(SQLException.class);
        }
        assertThat(statusOf(approved)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("QA M1: the approver must be an ACTIVE, unsuspended administrator - in the database")
    void approverMustBeAnActiveAdministratorInTheDatabase() throws Exception {
        long notAdmin = apiRequest("SYN-M1-ELIG-1");
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-NOT-AN-ADMIN', approved_at = now(), "
                + "approval_reason = 'approved by a non-administrator' WHERE approval_id = " + notAdmin))
                .hasMessageContaining("active, unsuspended ARCHIVE_ADMIN");

        asOwner("""
                INSERT INTO authz.admin_role_member (institutional_identifier, status, granted_by, reason)
                VALUES ('SYN-SUSPENDED-ADMIN', 'ACTIVE', 'test-fixture', 'synthetic suspended admin');
                INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at, reason)
                VALUES ('SYN-SUSPENDED-ADMIN', TRUE, 'test-fixture', now(), 'synthetic suspension')
                """);
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-SUSPENDED-ADMIN', approved_at = now(), "
                + "approval_reason = 'approved by a suspended administrator' WHERE approval_id = " + notAdmin))
                .hasMessageContaining("active, unsuspended ARCHIVE_ADMIN");
        assertThat(statusOf(notAdmin)).isEqualTo("PENDING");

        // Eligible at approval, revoked before consumption: the consume is refused too.
        asOwner("""
                INSERT INTO authz.admin_role_member (institutional_identifier, status, granted_by, reason)
                VALUES ('SYN-LEAVING-ADMIN', 'ACTIVE', 'test-fixture', 'synthetic admin who leaves')
                """);
        long leaving = apiRequest("SYN-M1-ELIG-2");
        asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', approved_by = 'SYN-LEAVING-ADMIN', "
                + "approved_at = now(), approval_reason = 'approved before leaving' WHERE approval_id = " + leaving);
        asOwner("UPDATE authz.admin_role_member SET status = 'REVOKED', revoked_by = 'test-fixture', "
                + "revoked_at = now() WHERE institutional_identifier = 'SYN-LEAVING-ADMIN'");
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'CONSUMED', "
                + "consumed_at = now(), consumed_by = 'SYN-A' WHERE approval_id = " + leaving))
                .hasMessageContaining("active, unsuspended ARCHIVE_ADMIN");
        assertThat(statusOf(leaving)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("QA M2: the database refuses an approver approving the link of their own Cognito identity")
    void anApproverCannotApproveLinkingTheirOwnCognitoIdentityInTheDatabase() throws Exception {
        asOwner("""
                INSERT INTO authz.identity_link
                    (cognito_issuer, cognito_subject, institutional_identifier, method, status,
                     verified_by, verified_at)
                VALUES ('https://synthetic-issuer', 'b0b0b0b0-0000-0000-0000-00000000000b', 'SYN-B',
                        'ADMIN_VERIFIED', 'ACTIVE', 'test-fixture', now())
                """);
        long id;
        try (Statement s = api.createStatement();
             ResultSet rs = s.executeQuery("""
                     INSERT INTO authz.privilege_approval
                         (action, subject_identifier, cognito_issuer, cognito_subject,
                          requested_by, reason, valid_until)
                     VALUES ('LINK_PRIVILEGED', 'SYN-M2-TARGET', 'https://synthetic-issuer',
                             'b0b0b0b0-0000-0000-0000-00000000000b', 'SYN-A',
                             'link the approver''s own sign-in', now())
                     RETURNING approval_id
                     """)) {
            rs.next();
            id = rs.getLong(1);
        }
        assertThatThrownBy(() -> asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', "
                + "approved_by = 'SYN-B', approved_at = now(), "
                + "approval_reason = 'approving my own sign-in' WHERE approval_id = " + id))
                .hasMessageContaining("own Cognito identity");
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    /** A PENDING ADMIN_ASSIGN row inserted AS archive_api, with database-chosen timestamps. */
    private static long apiRequest(String subject) throws SQLException {
        try (Statement s = api.createStatement();
             ResultSet rs = s.executeQuery("INSERT INTO authz.privilege_approval "
                     + "(action, subject_identifier, requested_by, reason, valid_until) "
                     + "VALUES ('ADMIN_ASSIGN', '" + subject + "', 'SYN-A', "
                     + "'requested as the API role', now()) RETURNING approval_id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long apiApproved(String subject) throws SQLException {
        long id = apiRequest(subject);
        asApi("UPDATE authz.privilege_approval SET status = 'APPROVED', approved_by = 'SYN-B', "
                + "approved_at = now(), approval_reason = 'approved as the API role' "
                + "WHERE approval_id = " + id);
        return id;
    }

    /**
     * Simulate 100 hours passing: shift the whole timeline back as the OWNER
     * with triggers off for one transaction. archive_api cannot do this - it
     * is the test harness standing in for the clock.
     */
    private static void expireAsOwner(long id) throws SQLException {
        boolean auto = owner.getAutoCommit();
        owner.setAutoCommit(false);
        try (Statement s = owner.createStatement()) {
            s.execute("SET LOCAL session_replication_role = replica");
            s.execute("UPDATE authz.privilege_approval SET "
                    + "requested_at = requested_at - interval '100 hours', "
                    + "valid_until = valid_until - interval '100 hours', "
                    + "approved_at = approved_at - interval '100 hours' WHERE approval_id = " + id);
            owner.commit();
        } finally {
            owner.setAutoCommit(auto);
        }
    }

    private static String statusOf(long id) throws SQLException {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT status FROM authz.privilege_approval WHERE approval_id = " + id)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int count(String where) throws SQLException {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM authz.privilege_approval WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void asOwner(String sql) throws SQLException {
        try (Statement s = owner.createStatement()) {
            s.execute(sql);
        }
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
