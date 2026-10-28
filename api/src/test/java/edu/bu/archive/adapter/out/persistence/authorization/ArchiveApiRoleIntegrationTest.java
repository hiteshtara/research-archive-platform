package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The {@code archive_api} least-privilege role, exercised as that role.
 *
 * <p>Applies every committed migration, runs
 * {@code database/roles/archive_api_role.sql} verbatim, then opens a SECOND
 * connection authenticated as {@code archive_api} and asserts what it can and
 * cannot do. Running the real script rather than a copy of the grants is the
 * point: a test against a hand-written grant list would pass while the
 * shipped script was wrong.
 *
 * <p>The owner connection is the container superuser, standing in for
 * {@code archive_admin}.
 */
@Testcontainers
class ArchiveApiRoleIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String API_PASSWORD = "test-only-archive-api-password";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static Connection owner;
    private static Connection api;

    @BeforeAll
    static void applyMigrationsAndCreateRole() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ArchiveApiRoleIntegrationTest::migrationVersion))
                    .toList();
        }
        try (Statement s = owner.createStatement()) {
            for (Path migration : migrations) {
                s.execute(Files.readString(migration));
            }
        }

        /*
         * The ETL's migration runner creates the ledger; no migration file
         * does. Create it here the same way so the role script's REVOKE
         * branch is exercised rather than skipped.
         */
        try (Statement s = owner.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS public.schema_migration (
                        version       INTEGER PRIMARY KEY,
                        description   VARCHAR(500) NOT NULL,
                        file_name     VARCHAR(500) NOT NULL,
                        installed_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        /*
         * The shipped script, with only the two things psql would supply
         * substituted: the :'archive_api_password' variable, and the owner
         * role name in ALTER DEFAULT PRIVILEGES (the container's superuser
         * stands in for archive_admin). Everything else runs verbatim, so a
         * mistake in the real file fails here.
         */
        String script = Files.readString(locateRoleScript())
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'archive_api_password'", "'" + API_PASSWORD + "'")
                .replace("FOR ROLE archive_admin", "FOR ROLE " + postgres.getUsername())
                .replace("GRANT CONNECT ON DATABASE research_archive",
                         "GRANT CONNECT ON DATABASE " + postgres.getDatabaseName());
        try (Statement s = owner.createStatement()) {
            s.execute(script);
        }

        api = DriverManager.getConnection(postgres.getJdbcUrl(), "archive_api", API_PASSWORD);
    }

    @AfterAll
    static void closeConnections() throws SQLException {
        if (api != null) api.close();
        if (owner != null) owner.close();
    }

    // ── 1. archive reads ────────────────────────────────────────────────

    @Nested
    @DisplayName("Archive reads succeed")
    class ArchiveReads {

        @Test
        void allFourModulesAreReadable() {
            for (String table : List.of("archive.award_version", "archive.proposal_version",
                    "archive.subaward", "archive.negotiation")) {
                assertThatCode(() -> asApi("SELECT count(*) FROM " + table))
                        .as(table).doesNotThrowAnyException();
            }
        }

        @Test
        void searchAndDashboardSourcesAreReadable() {
            for (String rel : List.of("archive.v_global_search", "archive.irb_protocol",
                    "archive.negotiation_search_attribute", "archive.person")) {
                assertThatCode(() -> asApi("SELECT count(*) FROM " + rel))
                        .as(rel).doesNotThrowAnyException();
            }
        }
    }

    // ── 2-5. authorized writes ──────────────────────────────────────────

    @Nested
    @DisplayName("Required administrative writes succeed")
    class AuthorizedWrites {

        @Test
        void identityLinkInsertAndUpsertSucceed() {
            assertThatCode(() -> asApi("""
                    INSERT INTO authz.identity_link
                      (cognito_issuer, cognito_subject, institutional_identifier,
                       method, status, verified_by, verified_at)
                    VALUES ('iss','sub-1','ARP-TEST0001-00000000','ADMIN_VERIFIED',
                            'ACTIVE','test',CURRENT_TIMESTAMP)
                    """)).doesNotThrowAnyException();

            // the ON CONFLICT ... DO UPDATE shape - needs UPDATE as well as INSERT
            assertThatCode(() -> asApi("""
                    INSERT INTO authz.identity_link
                      (cognito_issuer, cognito_subject, institutional_identifier,
                       method, status, verified_by, verified_at)
                    VALUES ('iss','sub-1','ARP-TEST0001-00000000','ADMIN_VERIFIED',
                            'ACTIVE','test',CURRENT_TIMESTAMP)
                    ON CONFLICT (cognito_issuer, cognito_subject) WHERE status='ACTIVE'
                    DO UPDATE SET verified_by = 'test2'
                    """)).doesNotThrowAnyException();

            assertThatCode(() -> asApi(
                    "UPDATE authz.identity_link SET login_name='x' WHERE cognito_subject='sub-1'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void grantAdminMembershipAndRelinkApprovalWritesSucceed() {
            assertThatCode(() -> asApi("""
                    INSERT INTO authz.access_grant
                      (institutional_identifier, grant_type, granted_by)
                    VALUES ('ARP-TEST0002-00000000','CENTRAL','test')
                    """)).doesNotThrowAnyException();
            assertThatCode(() -> asApi("""
                    UPDATE authz.access_grant SET reason='why' WHERE granted_by='test'
                    """)).doesNotThrowAnyException();

            assertThatCode(() -> asApi("""
                    INSERT INTO authz.admin_role_member
                      (institutional_identifier, status, granted_by)
                    VALUES ('ARP-TEST0003-00000000','ACTIVE','BOOTSTRAP')
                    """)).doesNotThrowAnyException();
            assertThatCode(() -> asApi("""
                    UPDATE authz.admin_role_member SET reason='why' WHERE granted_by='BOOTSTRAP'
                    """)).doesNotThrowAnyException();

            assertThatCode(() -> asApi("""
                    INSERT INTO authz.relink_approval
                      (institutional_identifier, proposed_cognito_issuer,
                       proposed_cognito_subject, requested_by, grants_at_request, reason)
                    VALUES ('ARP-TEST0004-00000000','iss','sub-9','admin-a',1,'test')
                    """)).doesNotThrowAnyException();
            assertThatCode(() -> asApi("""
                    UPDATE authz.relink_approval SET status='REVOKED',
                      revoked_by='admin-b', revoked_at=CURRENT_TIMESTAMP
                     WHERE requested_by='admin-a'
                    """)).doesNotThrowAnyException();
        }

        @Test
        void suspensionUpsertSucceeds() {
            /*
             * person_status is written with INSERT ... ON CONFLICT DO UPDATE.
             * A grant set derived from grepping for "UPDATE authz.person_status"
             * would omit UPDATE and this would fail - which is why it has its
             * own test.
             */
            String upsert = """
                    INSERT INTO authz.person_status
                      (institutional_identifier, suspended, changed_by, changed_at)
                    VALUES ('ARP-TEST0005-00000000', true, 'test', CURRENT_TIMESTAMP)
                    ON CONFLICT (institutional_identifier) DO UPDATE
                      SET suspended = EXCLUDED.suspended
                    """;
            assertThatCode(() -> asApi(upsert)).doesNotThrowAnyException();
            assertThatCode(() -> asApi(upsert))
                    .as("second call takes the DO UPDATE branch")
                    .doesNotThrowAnyException();
        }

        @Test
        void auditInsertSucceeds() {
            assertThatCode(() -> asApi("""
                    INSERT INTO authz.access_audit (actor, action, institutional_identifier)
                    VALUES ('admin-a','ACCESS_GRANTED','ARP-TEST0006-00000000')
                    """)).doesNotThrowAnyException();
        }
    }

    // ── 6-10. everything that must fail ─────────────────────────────────

    @Nested
    @DisplayName("Forbidden operations fail")
    class Forbidden {

        @Test
        void auditUpdateAndDeleteFailOnTheGrantBeforeTheTrigger() {
            /*
             * Two independent defences, and this asserts the ORDER: the grant
             * refuses first, so the statement never reaches
             * tr_access_audit_append_only. The message proves which one acted.
             */
            assertThatThrownBy(() -> asApi("UPDATE authz.access_audit SET actor='x'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi("DELETE FROM authz.access_audit"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void theAppendOnlyTriggerStillExistsAndStillFires() throws Exception {
            /*
             * The trigger is the second line of defence. Prove it is intact by
             * hitting it as the OWNER, who is not blocked by any grant. It is
             * FOR EACH ROW, so it needs a row to defend - an UPDATE matching
             * nothing fires nothing (and changes nothing).
             */
            asOwner("""
                    INSERT INTO authz.access_audit (actor, action, institutional_identifier)
                    VALUES ('trigger-probe','ACCESS_GRANTED','ARP-TRIGGER1-00000000')
                    """);
            assertThatThrownBy(() -> asOwner(
                    "UPDATE authz.access_audit SET actor='x' WHERE actor='trigger-probe'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> asOwner(
                    "DELETE FROM authz.access_audit WHERE actor='trigger-probe'"))
                    .hasMessageContaining("append-only");
        }

        @Test
        void droppingTheAuditTriggerFails() {
            assertThatThrownBy(() -> asApi(
                    "DROP TRIGGER tr_access_audit_append_only ON authz.access_audit"))
                    .hasMessageContaining("must be owner");
        }

        @Test
        void archiveWritesFail() {
            assertThatThrownBy(() -> asApi(
                    "INSERT INTO archive.person (person_id) VALUES ('X')"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi("UPDATE archive.person SET first_name='x'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi("DELETE FROM archive.person"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void deleteFailsOnEveryAuthzTable() {
            for (String t : List.of("identity_link", "access_grant", "admin_role_member",
                    "relink_approval", "person_status", "access_audit",
                    "kim_principal", "principal_crosswalk")) {
                assertThatThrownBy(() -> asApi("DELETE FROM authz." + t))
                        .as(t).hasMessageContaining("permission denied");
            }
        }

        @Test
        void ddlFails() {
            assertThatThrownBy(() -> asApi("CREATE TABLE authz.sneaky (x int)"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi("CREATE SCHEMA authz2"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi("ALTER TABLE authz.access_grant ADD COLUMN x int"))
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> asApi("DROP INDEX authz.ux_identity_link_active_identity"))
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> asApi("TRUNCATE authz.access_audit"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void migrationExecutionFails() {
            assertThatThrownBy(() -> asApi("SELECT * FROM public.schema_migration"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi(
                    "INSERT INTO public.schema_migration (version, description, file_name)"
                            + " VALUES (999,'x','x.sql')"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void objectCreationInThePublicSchemaFails() {
            assertThatThrownBy(() -> asApi("CREATE TABLE public.sneaky (x int)"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void unauthorizedSequenceUsageFails() {
            // principal_crosswalk is ETL-owned: SELECT yes, INSERT no, and its
            // sequence is deliberately not granted.
            assertThatThrownBy(() -> asApi("""
                    INSERT INTO authz.principal_crosswalk
                      (attribute_name, attribute_value, prncpl_id, evidence_ref, verified_by)
                    VALUES ('eppn','x','P1','ref','test')
                    """)).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> asApi(
                    "SELECT nextval('authz.principal_crosswalk_crosswalk_id_seq')"))
                    .hasMessageContaining("permission denied");
        }
    }

    // ── role attributes ─────────────────────────────────────────────────

    @Test
    void theRoleHasNoDangerousAttributesAndOwnsNothing() throws Exception {
        try (Statement s = owner.createStatement();
             var rs = s.executeQuery("""
                     SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication,
                            rolbypassrls, rolinherit, rolcanlogin
                       FROM pg_roles WHERE rolname='archive_api'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("rolsuper")).as("SUPERUSER").isFalse();
            assertThat(rs.getBoolean("rolcreatedb")).as("CREATEDB").isFalse();
            assertThat(rs.getBoolean("rolcreaterole")).as("CREATEROLE").isFalse();
            assertThat(rs.getBoolean("rolreplication")).as("REPLICATION").isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).as("BYPASSRLS").isFalse();
            assertThat(rs.getBoolean("rolinherit")).as("INHERIT").isFalse();
            assertThat(rs.getBoolean("rolcanlogin")).as("LOGIN").isTrue();
        }

        try (Statement s = owner.createStatement();
             var rs = s.executeQuery("""
                     SELECT count(*) FROM pg_class c
                       JOIN pg_roles r ON r.oid = c.relowner
                      WHERE r.rolname = 'archive_api'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("objects owned by archive_api").isZero();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

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

    private static int migrationVersion(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrationsDirectory() {
        Path c = Path.of("database", "migrations");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations");
    }

    private static Path locateRoleScript() {
        Path c = Path.of("database", "roles", "archive_api_role.sql");
        return Files.exists(c) ? c : Path.of("..", "database", "roles", "archive_api_role.sql");
    }
}
