package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-009: the archive is read-only to the API, for EVERY {@code archive.*}
 * object, and stays that way.
 *
 * <p>{@link ArchiveApiRoleIntegrationTest} already proves the role cannot
 * write {@code archive.person}. One sampled table is enough to catch a role
 * script that forgot the archive schema altogether; it is not enough to catch
 * the realistic regression, which is a single widening grant on a single
 * table — {@code GRANT INSERT ON archive.award_version} added to fix a load
 * problem, or an {@code ALTER DEFAULT PRIVILEGES} broadened to {@code ALL}.
 * A sampled test passes right through that. So every assertion here is driven
 * from {@code pg_catalog} over the whole schema rather than from a list
 * written by hand.
 *
 * <p>Four holes a table-level check alone would miss are closed explicitly:
 * column-level grants (invisible to {@code has_table_privilege}), writable
 * views over archive tables, sequence {@code USAGE} (which is what an
 * {@code INSERT} into a serial column actually needs), and the default
 * privileges that decide what a table created TOMORROW inherits.
 *
 * <p>Every count this suite inspects is asserted against a floor. Each of
 * these queries returns "no offending rows" just as happily when it has
 * inspected nothing at all, so a schema that failed to materialise would
 * otherwise read as a clean pass.
 *
 * <p>Fixtures are synthetic; no BU data appears here. Nothing in this suite
 * touches a deployed environment — it runs entirely against a throwaway
 * container.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ArchiveReadOnlyPrivilegeIntegrationTest {

    /**
     * Floors, not exact counts. 116 {@code CREATE TABLE archive.*} statements
     * are committed as of V087 and some were later dropped (the Protocol
     * Archive, V032). These are set well below what the migrations build so
     * ordinary schema work does not trip them, while a schema that came up
     * empty or half-applied still fails. Raise them deliberately; never lower
     * one to make a run go green.
     */
    private static final int MINIMUM_ARCHIVE_TABLES = 80;
    private static final int MINIMUM_ARCHIVE_COLUMNS = 400;

    /** The write privileges no archive object may confer on the API role. */
    private static final List<String> WRITE_PRIVILEGES =
            List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE");

    /**
     * The historical research tables named in the requirement, asserted by
     * name as well as by catalog sweep. If a rename ever makes one of these
     * absent, this suite should fail loudly rather than quietly inspect one
     * table fewer.
     */
    private static final List<String> HISTORICAL_RESEARCH_TABLES = List.of(
            "archive.award_version",
            "archive.proposal_version",
            "archive.subaward",
            "archive.negotiation",
            "archive.award_attachment",
            "archive.proposal_attachment",
            "archive.subaward_attachment",
            "archive.archived_attachment",
            "archive.irb_protocol",
            "archive.person");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static Connection owner;
    private static Connection api;
    private static int migrationsApplied;

    @BeforeAll
    static void provision() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        migrationsApplied = ArchiveApiRoleHarness.provision(owner, postgres);
        api = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                ArchiveApiRoleHarness.API_USER,
                ArchiveApiRoleHarness.API_PASSWORD);
    }

    @AfterAll
    static void closeConnections() throws SQLException {
        if (api != null) api.close();
        if (owner != null) owner.close();
    }

    @Test
    @DisplayName("the harness applied the committed migrations")
    void theHarnessAppliedTheCommittedMigrations() {
        assertThat(migrationsApplied)
                .as("migration files applied - every later assertion is void without them")
                .isGreaterThanOrEqualTo(80);
    }

    // ── the SEC-009 requirement itself ──────────────────────────────────

    @Nested
    @DisplayName("No archive object grants the API role any write")
    class NoArchiveWrites {

        @Test
        void noArchiveTableGrantsInsertUpdateDeleteOrTruncate() throws Exception {
            List<String> offenders = query("""
                    SELECT n.nspname || '.' || c.relname || ' -> ' || p.priv
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     CROSS JOIN (VALUES ('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE')) AS p(priv)
                     WHERE n.nspname = 'archive'
                       AND c.relkind IN ('r','p')
                       AND has_table_privilege('archive_api', c.oid, p.priv)
                     ORDER BY 1
                    """);

            assertThat(tablesInspected())
                    .as("archive tables inspected - a vacuous sweep must not pass")
                    .isGreaterThanOrEqualTo(MINIMUM_ARCHIVE_TABLES);
            assertThat(offenders)
                    .as("archive_api must hold no INSERT/UPDATE/DELETE/TRUNCATE "
                            + "on any archive table; SEC-009")
                    .isEmpty();
        }

        @Test
        void noArchiveColumnCarriesAColumnLevelWriteGrant() throws Exception {
            /*
             * has_table_privilege is blind to a column-level grant:
             * GRANT UPDATE (status) ON archive.award_version leaves the
             * table-level answer false while the write is perfectly possible.
             * has_column_privilege answers true for either level, and the
             * table level is already proved false above, so anything true
             * here is a column grant.
             */
            List<String> offenders = query("""
                    SELECT n.nspname || '.' || c.relname || '.' || a.attname || ' -> ' || p.priv
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                      JOIN pg_attribute a ON a.attrelid = c.oid
                                         AND a.attnum > 0
                                         AND NOT a.attisdropped
                     CROSS JOIN (VALUES ('INSERT'),('UPDATE')) AS p(priv)
                     WHERE n.nspname = 'archive'
                       AND c.relkind IN ('r','p')
                       AND has_column_privilege('archive_api', c.oid, a.attname, p.priv)
                     ORDER BY 1
                    """);

            assertThat(columnsInspected())
                    .as("archive columns inspected")
                    .isGreaterThanOrEqualTo(MINIMUM_ARCHIVE_COLUMNS);
            assertThat(offenders)
                    .as("no archive column may carry a column-level INSERT or UPDATE grant")
                    .isEmpty();
        }

        @Test
        void noArchiveViewIsWritable() throws Exception {
            /*
             * A view over archive tables is writable if it is auto-updatable
             * or carries INSTEAD OF triggers, and the blanket
             * GRANT SELECT ON ALL TABLES deliberately includes views. So the
             * grant has to be checked on views too, not just on tables.
             */
            List<String> offenders = query("""
                    SELECT n.nspname || '.' || c.relname || ' -> ' || p.priv
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     CROSS JOIN (VALUES ('INSERT'),('UPDATE'),('DELETE')) AS p(priv)
                     WHERE n.nspname = 'archive'
                       AND c.relkind IN ('v','m')
                       AND has_table_privilege('archive_api', c.oid, p.priv)
                     ORDER BY 1
                    """);

            assertThat(offenders).as("no archive view may be writable by archive_api").isEmpty();
            assertThat(scalar("""
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'archive' AND c.relkind IN ('v','m')
                    """))
                    .as("archive views inspected - v_global_search alone should make this >= 1")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        void noArchiveSequenceGrantsUsageOrUpdate() throws Exception {
            /*
             * The privilege an INSERT into a serial column actually needs is
             * USAGE on the sequence. The role script grants it for exactly
             * five authz sequences and no archive one; prove the archive side.
             */
            List<String> offenders = query("""
                    SELECT n.nspname || '.' || c.relname || ' -> ' || p.priv
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     CROSS JOIN (VALUES ('USAGE'),('UPDATE')) AS p(priv)
                     WHERE n.nspname = 'archive'
                       AND c.relkind = 'S'
                       AND has_sequence_privilege('archive_api', c.oid, p.priv)
                     ORDER BY 1
                    """);
            assertThat(offenders)
                    .as("no archive sequence may grant USAGE or UPDATE to archive_api")
                    .isEmpty();
        }
    }

    // ── the same claim, exercised rather than read ──────────────────────

    @Nested
    @DisplayName("Real write attempts are refused, real reads succeed")
    class ExercisedAsTheRole {

        @Test
        void everyHistoricalResearchTableIsReadableAndNotWritable() throws Exception {
            for (String table : HISTORICAL_RESEARCH_TABLES) {
                assertThat(relationExists(table))
                        .as(table + " must exist for this assertion to mean anything")
                        .isTrue();

                assertThatCode(() -> asApi("SELECT count(*) FROM " + table))
                        .as(table + " must stay readable - authorized viewing is the point")
                        .doesNotThrowAnyException();

                assertThatThrownBy(() -> asApi("INSERT INTO " + table + " DEFAULT VALUES"))
                        .as("INSERT " + table)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> asApi("DELETE FROM " + table + " WHERE false"))
                        .as("DELETE " + table)
                        .hasMessageContaining("permission denied");

                /*
                 * UPDATE needs a column name, so take the first real column
                 * from the catalog and assign it to itself. A no-op if it ever
                 * ran; it must not run.
                 */
                String column = firstColumnOf(table).orElseThrow();
                assertThatThrownBy(() -> asApi(
                        "UPDATE " + table + " SET " + column + " = " + column + " WHERE false"))
                        .as("UPDATE " + table)
                        .hasMessageContaining("permission denied");

                assertThatThrownBy(() -> asApi("TRUNCATE " + table))
                        .as("TRUNCATE " + table)
                        .hasMessageContaining("permission denied");
            }
        }

        @Test
        void archiveSchemaDdlIsRefused() {
            assertThatThrownBy(() -> asApi(
                    "ALTER TABLE archive.award_version ADD COLUMN sneaky int"))
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> asApi("DROP TABLE archive.award_version"))
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> asApi("CREATE TABLE archive.sneaky (x int)"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void copyIntoAnArchiveTableIsRefused() throws Exception {
            /*
             * COPY ... FROM is a separate statement from INSERT and a separate
             * habit when someone is trying to load data quickly. It needs the
             * same INSERT privilege, which is exactly what this asserts.
             */
            assertThatThrownBy(() -> asApi("COPY archive.person FROM STDIN"))
                    .hasMessageContaining("permission denied");
        }
    }

    // ── what a future object inherits ───────────────────────────────────

    @Nested
    @DisplayName("Future archive objects inherit SELECT and nothing else")
    class FutureObjects {

        @Test
        void defaultPrivilegesInTheArchiveSchemaGrantSelectOnly() throws Exception {
            List<String> granted = query("""
                    SELECT a.privilege_type
                      FROM pg_default_acl d
                      JOIN pg_namespace n ON n.oid = d.defaclnamespace
                     CROSS JOIN LATERAL aclexplode(d.defaclacl) a
                     WHERE n.nspname = 'archive'
                       AND a.grantee = 'archive_api'::regrole
                     ORDER BY 1
                    """);

            /*
             * Both halves matter. SELECT must be there, or a table a future
             * migration adds is invisible to the API until someone notices.
             * Nothing else may be there, or that same table arrives writable.
             */
            assertThat(granted)
                    .as("ALTER DEFAULT PRIVILEGES ... IN SCHEMA archive, as the role script sets it")
                    .containsExactly("SELECT");
        }

        @Test
        void aTableCreatedAfterTheRoleScriptIsReadableAndNotWritable() throws Exception {
            /*
             * The default-privilege ACL above is a statement of intent. This
             * is the behaviour: create a table as the owner AFTER the role
             * script ran, exactly as a new migration would, and check what
             * the API role actually gets.
             */
            asOwner("CREATE TABLE archive.sec009_future_probe (id int PRIMARY KEY, note text)");
            try {
                assertThatCode(() ->
                        asApi("SELECT count(*) FROM archive.sec009_future_probe"))
                        .as("a new archive table must be readable")
                        .doesNotThrowAnyException();
                assertThatThrownBy(() ->
                        asApi("INSERT INTO archive.sec009_future_probe (id) VALUES (1)"))
                        .as("a new archive table must not be writable")
                        .hasMessageContaining("permission denied");
            } finally {
                asOwner("DROP TABLE archive.sec009_future_probe");
            }
        }
    }

    // ── nothing routes around the grants ────────────────────────────────

    @Test
    @DisplayName("the API role holds no role membership that could confer writes")
    void theApiRoleHoldsNoRoleMembership() throws Exception {
        /*
         * A grant to a GROUP role that archive_api belongs to would not appear
         * in any of the sweeps above. The role script never adds a membership;
         * assert the role is in nothing at all, which is the only version of
         * this claim that cannot rot.
         */
        assertThat(query("""
                SELECT r.rolname
                  FROM pg_auth_members m
                  JOIN pg_roles r ON r.oid = m.roleid
                 WHERE m.member = 'archive_api'::regrole
                 ORDER BY 1
                """))
                .as("archive_api must belong to no role")
                .isEmpty();
    }

    @Test
    @DisplayName("the API role cannot read or write the migration ledger")
    void theApiRoleCannotTouchTheMigrationLedger() {
        /*
         * Applying a migration is how archive data legitimately changes. The
         * API having no access to the ledger is part of being read-only:
         * it cannot apply one and cannot report that it did.
         */
        assertThatThrownBy(() -> asApi("SELECT * FROM public.schema_migration"))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApi(
                "INSERT INTO public.schema_migration (version, description, file_name)"
                        + " VALUES (9999,'sec009','sec009.sql')"))
                .hasMessageContaining("permission denied");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static int tablesInspected() throws Exception {
        return scalar("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'archive' AND c.relkind IN ('r','p')
                """);
    }

    private static int columnsInspected() throws Exception {
        return scalar("""
                SELECT count(*)
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid
                                     AND a.attnum > 0
                                     AND NOT a.attisdropped
                 WHERE n.nspname = 'archive' AND c.relkind IN ('r','p')
                """);
    }

    private static boolean relationExists(String qualifiedName) throws Exception {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT to_regclass('" + qualifiedName + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static Optional<String> firstColumnOf(String qualifiedName) throws Exception {
        try (Statement s = owner.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT a.attname
                       FROM pg_attribute a
                      WHERE a.attrelid = to_regclass('%s')
                        AND a.attnum > 0
                        AND NOT a.attisdropped
                      ORDER BY a.attnum
                      LIMIT 1
                     """.formatted(qualifiedName))) {
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
    }

    private static int scalar(String sql) throws Exception {
        try (Statement s = owner.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> query(String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement s = owner.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
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
}
