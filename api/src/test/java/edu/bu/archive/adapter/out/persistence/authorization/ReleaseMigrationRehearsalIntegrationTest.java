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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The DEV release, rehearsed end to end on a throwaway database.
 *
 * <p>Every other suite starts from "all migrations applied". That is not what
 * a release does. A release starts from the schema that is <em>already in
 * DEV</em> — V082–V087, with grant rows in it — and applies V088 and V089 on
 * top. This rehearses exactly that, and then rehearses undoing it.
 *
 * <p>What it is looking for is the class of defect that only exists in the
 * transition: a migration that works on an empty table but not on one with
 * rows, an index rebuild that drops an invariant on the way past, a rollback
 * that quietly destroys audit evidence, and any widening of archive access as
 * a side effect of either.
 *
 * <p>Nothing here touches a shared database. Fixtures are synthetic.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ReleaseMigrationRehearsalIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** The highest migration already applied in DEV, verified 2026-11-09. */
    private static final int DEPLOYED_IN_DEV = 87;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private Connection owner;

    @BeforeEach
    void freshDatabase() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        // Each test starts from nothing; a release rehearsal that inherited
        // the previous test's schema would prove nothing about ordering.
        exec("DROP SCHEMA IF EXISTS archive CASCADE");
        exec("DROP SCHEMA IF EXISTS authz CASCADE");
        exec("DROP TABLE IF EXISTS public.schema_migration");
        exec("""
                CREATE TABLE public.schema_migration (
                    version       INTEGER PRIMARY KEY,
                    description   VARCHAR(500) NOT NULL,
                    file_name     VARCHAR(500) NOT NULL,
                    installed_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    // ── the state DEV is actually in ────────────────────────────────────

    private void applyThrough(int maxVersion) throws Exception {
        for (Path migration : migrations()) {
            int version = version(migration);
            if (version > maxVersion) {
                continue;
            }
            exec(Files.readString(migration));
            exec("INSERT INTO public.schema_migration (version, description, file_name) VALUES ("
                    + version + ", 'rehearsal', '" + migration.getFileName() + "')");
        }
    }

    /**
     * Applies one migration by version, from the release directory or - for a
     * version held back from the release - from
     * {@code database/migrations-pending-approval/}.
     *
     * <p>The rehearsal still exercises V089 so its schema and rollback stay
     * proved, while the release artifact itself cannot apply it.
     */
    private void apply(int version) throws Exception {
        Path migration = Stream.concat(migrations().stream(), pendingApproval().stream())
                .filter(m -> version(m) == version)
                .findFirst()
                .orElseThrow();
        exec(Files.readString(migration));
        exec("INSERT INTO public.schema_migration (version, description, file_name) VALUES ("
                + version + ", 'rehearsal', '" + migration.getFileName() + "')");
    }

    /** Grants of the kinds DEV could plausibly hold when the release lands. */
    private void seedExistingGrants() throws Exception {
        /*
         * One statement per shape. ck_access_grant_shape rejects an IO grant
         * with a null io_value, so inserting then updating fails at the
         * insert - the constraint is doing its job and the fixture has to
         * respect it.
         */
        exec("""
                INSERT INTO authz.access_grant
                    (institutional_identifier, grant_type, unit_number, io_value,
                     include_descendants, granted_by)
                VALUES ('ARP-SYN-0001', 'CENTRAL', NULL, NULL, FALSE, 'rehearsal'),
                       ('ARP-SYN-0002', 'UNIT', 'SYN-U-100', NULL, TRUE, 'rehearsal'),
                       ('ARP-SYN-0003', 'IO', NULL, 'SYN-IO-0004', FALSE, 'rehearsal')
                """);
    }

    // ── 1. ordering and compatibility ───────────────────────────────────

    @Test
    @DisplayName("V088 applies cleanly on top of the schema DEV already has, with grants in it")
    void v088AppliesOnTopOfTheDeployedSchemaWithExistingGrants() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        assertThat(count("SELECT count(*) FROM authz.access_grant")).isEqualTo(3);

        assertThatCode(() -> apply(88))
                .as("V088 must not require an empty grant table")
                .doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM authz.access_grant"))
                .as("no existing grant may be lost or rewritten")
                .isEqualTo(3);
        assertThat(count("""
                SELECT count(*) FROM authz.access_grant WHERE award_number IS NOT NULL
                """))
                .as("existing grants must not acquire an award number")
                .isZero();
    }

    @Test
    @DisplayName("the V087 invariant survives V088's index rebuild")
    void theUniquenessInvariantSurvivesTheRebuild() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        apply(88);

        // The same scope twice is still refused - the rebuild must not have
        // dropped the invariant on its way past.
        assertThatThrownBy(() -> exec("""
                INSERT INTO authz.access_grant
                    (institutional_identifier, grant_type, unit_number, include_descendants, granted_by)
                VALUES ('ARP-SYN-0002', 'UNIT', 'SYN-U-100', TRUE, 'rehearsal')
                """))
                .hasMessageContaining("ux_access_grant_active_scope");

        // And the thing V088 exists for: two parent-award grants for
        // DIFFERENT Awards must both be storable.
        assertThatCode(() -> exec("""
                INSERT INTO authz.access_grant
                    (institutional_identifier, grant_type, award_number, granted_by)
                VALUES ('ARP-SYN-0004', 'PARENT_AWARD', 'SYN-AWD-A', 'rehearsal'),
                       ('ARP-SYN-0004', 'PARENT_AWARD', 'SYN-AWD-B', 'rehearsal')
                """)).doesNotThrowAnyException();

        assertThatThrownBy(() -> exec("""
                INSERT INTO authz.access_grant
                    (institutional_identifier, grant_type, award_number, granted_by)
                VALUES ('ARP-SYN-0004', 'PARENT_AWARD', 'SYN-AWD-A', 'rehearsal')
                """))
                .as("the same Award twice is still a duplicate")
                .hasMessageContaining("ux_access_grant_active_scope");
    }

    @Test
    @DisplayName("V089 applies after V088 and is independent of it")
    void v089AppliesIndependently() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        apply(88);
        assertThatCode(() -> apply(89)).doesNotThrowAnyException();
        assertThat(count("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'authz' AND c.relname = 'activity_audit'
                """)).isEqualTo(1);
    }

    @Test
    @DisplayName("V089 alone also applies, so the two are not order-coupled")
    void v089DoesNotDependOnV088() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        assertThatCode(() -> apply(89))
                .as("a release that applies only the audit migration must work")
                .doesNotThrowAnyException();
    }

    // ── 2. rollback ─────────────────────────────────────────────────────

    @Test
    @DisplayName("V088 rolls back to exactly V087's schema")
    void v088RollsBackCleanly() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        String indexBefore = indexDefinition();

        apply(88);
        assertThat(indexDefinition()).isNotEqualTo(indexBefore);

        exec(Files.readString(rollback("V088__parent_award_grants_rollback.sql")));

        assertThat(indexDefinition())
                .as("the V087 index must be restored exactly, not merely dropped")
                .isEqualTo(indexBefore);
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'authz' AND table_name = 'access_grant'
                   AND column_name = 'award_number'
                """)).isZero();
        assertThat(count("SELECT count(*) FROM authz.access_grant"))
                .as("rolling back a schema change must not touch grant rows")
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM public.schema_migration WHERE version = 88"))
                .as("the ledger row goes too, or re-applying would be skipped")
                .isZero();
    }

    @Test
    @DisplayName("V088 rollback refuses rather than deleting a parent-award grant")
    void v088RollbackRefusesWhenGrantsExist() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        apply(88);
        exec("""
                INSERT INTO authz.access_grant
                    (institutional_identifier, grant_type, award_number, granted_by)
                VALUES ('ARP-SYN-0005', 'PARENT_AWARD', 'SYN-AWD-C', 'rehearsal')
                """);

        assertThatThrownBy(() ->
                exec(Files.readString(rollback("V088__parent_award_grants_rollback.sql"))))
                .as("a grant row is audit evidence; a schema rollback must not delete it")
                .hasMessageContaining("Refusing to roll back V088");
        exec("ROLLBACK");
    }

    @Test
    @DisplayName("V089 rolls back when empty, and refuses when it holds rows")
    void v089RollbackProtectsAuditRows() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        apply(89);

        // Empty: clean removal.
        assertThatCode(() ->
                exec(Files.readString(rollback("V089__archive_activity_audit_rollback.sql"))))
                .doesNotThrowAnyException();
        assertThat(count("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'authz' AND c.relname = 'activity_audit'
                """)).isZero();

        // With rows: refusal, unless the loss is an explicit decision.
        apply(89);
        exec("""
                INSERT INTO authz.activity_audit (actor, action, outcome)
                VALUES ('ARP-SYN-0001', 'RECORD_VIEWED', 'PERMITTED')
                """);
        assertThatThrownBy(() ->
                exec(Files.readString(rollback("V089__archive_activity_audit_rollback.sql"))))
                .hasMessageContaining("Refusing to roll back V089");
        // The script opens its own BEGIN, so its refusal leaves this
        // connection in an aborted transaction. Clear it, or every statement
        // below fails with "current transaction is aborted" for the wrong
        // reason.
        exec("ROLLBACK");

        exec("SET rollback.allow_audit_loss = 'yes'");
        assertThatCode(() ->
                exec(Files.readString(rollback("V089__archive_activity_audit_rollback.sql"))))
                .as("an authorised loss is still possible, but only deliberately")
                .doesNotThrowAnyException();
    }

    // ── 3. the archive never becomes writable ───────────────────────────

    @Test
    @DisplayName("no archive table becomes writable at any point in the release")
    void theArchiveStaysReadOnlyThroughout() throws Exception {
        applyThrough(DEPLOYED_IN_DEV);
        seedExistingGrants();
        provisionRole();
        assertThat(archiveWritePrivileges()).as("before the release").isEmpty();

        apply(88);
        provisionRole();
        assertThat(archiveWritePrivileges()).as("after V088").isEmpty();

        apply(89);
        provisionRole();
        exec(Files.readString(Path.of(rolesDirectory().toString(),
                "archive_api_activity_audit_grant.sql")));
        assertThat(archiveWritePrivileges()).as("after V089 and the audit grant").isEmpty();

        exec(Files.readString(rollback("V089__archive_activity_audit_rollback.sql")));
        assertThat(archiveWritePrivileges()).as("after rolling V089 back").isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void provisionRole() throws Exception {
        exec(ArchiveApiRoleHarness.roleScriptFor(postgres));
    }

    private List<String> archiveWritePrivileges() throws Exception {
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
        return offenders;
    }

    private String indexDefinition() throws Exception {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT COALESCE(max(indexdef), '(absent)') FROM pg_indexes
                      WHERE schemaname = 'authz' AND indexname = 'ux_access_grant_active_scope'
                     """)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long count(String sql) throws Exception {
        try (Statement s = owner.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = owner.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<Path> migrations() throws Exception {
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations")
                : Path.of("..", "database", "migrations");
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ReleaseMigrationRehearsalIntegrationTest::version))
                    .toList();
        }
    }

    private static List<Path> pendingApproval() throws Exception {
        Path dir = Files.isDirectory(Path.of("database", "migrations-pending-approval"))
                ? Path.of("database", "migrations-pending-approval")
                : Path.of("..", "database", "migrations-pending-approval");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .toList();
        }
    }

    private static Path rolesDirectory() {
        Path candidate = Path.of("database", "roles");
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", "roles");
    }

    private static Path rollback(String name) {
        Path candidate = Path.of("database", "rollback", name);
        return Files.exists(candidate) ? candidate : Path.of("..", "database", "rollback", name);
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
