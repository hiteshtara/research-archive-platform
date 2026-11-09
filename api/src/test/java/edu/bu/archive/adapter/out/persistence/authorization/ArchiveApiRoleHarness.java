package edu.bu.archive.adapter.out.persistence.authorization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Builds a database that carries every committed migration and the real
 * {@code archive_api} role, so a test can act AS that role.
 *
 * <p>The setup is the one proved by {@link ArchiveApiRoleIntegrationTest}:
 * apply the migrations in version order, create the ETL-owned migration
 * ledger, then run {@code database/roles/archive_api_role.sql} verbatim with
 * only the two substitutions psql would otherwise supply. Running the shipped
 * script rather than a copy of its grants is the whole point — a test written
 * against a hand-maintained grant list would keep passing while the script
 * that actually provisions DEV and production drifted away from it.
 *
 * <p>Extracted here so {@link ArchiveReadOnlyPrivilegeIntegrationTest} shares
 * one definition of "the real role" with the suite that came first.
 * {@code ArchiveApiRoleIntegrationTest} still carries its own copy; moving it
 * onto this harness is a follow-up, deliberately not done in a test-only
 * change that should not risk a passing privilege suite.
 */
final class ArchiveApiRoleHarness {

    static final String API_USER = "archive_api";
    static final String API_PASSWORD = "test-only-archive-api-password";

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private ArchiveApiRoleHarness() {
    }

    /**
     * Applies every committed migration, creates the migration ledger and runs
     * the role script, on the given owner connection.
     *
     * @return how many migration files were applied, so a caller can refuse to
     *         trust a run that silently applied none
     */
    static int provision(Connection owner, PostgreSQLContainer<?> postgres) throws Exception {
        List<Path> migrations = committedMigrations();
        if (migrations.isEmpty()) {
            throw new IllegalStateException(
                    "no migrations found under " + locateMigrationsDirectory().toAbsolutePath()
                            + " - the privilege assertions would pass against an empty schema");
        }
        try (Statement s = owner.createStatement()) {
            for (Path migration : migrations) {
                s.execute(Files.readString(migration));
            }
        }

        /*
         * The ETL's migration runner creates the ledger; no migration file
         * does. Create it the same way so the role script's REVOKE branch is
         * exercised rather than skipped.
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

        try (Statement s = owner.createStatement()) {
            s.execute(roleScriptFor(postgres));
        }
        return migrations.size();
    }

    /**
     * The shipped role script, with only what psql would supply substituted:
     * the {@code :'archive_api_password'} variable, the owner role in
     * {@code ALTER DEFAULT PRIVILEGES} (the container superuser stands in for
     * {@code archive_admin}) and the database name in {@code GRANT CONNECT}.
     */
    static String roleScriptFor(PostgreSQLContainer<?> postgres) throws Exception {
        return stripPsqlMetaCommands(Files.readString(locateRoleScript()))
                .replace(":'archive_api_password'", "'" + API_PASSWORD + "'")
                .replace("FOR ROLE archive_admin", "FOR ROLE " + postgres.getUsername())
                .replace("GRANT CONNECT ON DATABASE research_archive",
                         "GRANT CONNECT ON DATABASE " + postgres.getDatabaseName());
    }

    private static List<Path> committedMigrations() throws Exception {
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ArchiveApiRoleHarness::migrationVersion))
                    .toList();
        }
    }

    /**
     * Drops psql meta-command lines (those beginning with a backslash). They
     * are not SQL and JDBC cannot execute them. {@code \o /dev/null} only
     * suppresses terminal output, so removing it changes nothing about what
     * the script does to the database.
     */
    private static String stripPsqlMetaCommands(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("\\"))
                .collect(Collectors.joining("\n"));
    }

    private static int migrationVersion(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * Applies the migrations held back from the release in
     * {@code database/migrations-pending-approval/}.
     *
     * <p>Those files are deliberately outside the directory the ETL runner
     * globs, because that runner applies EVERY pending version it finds and
     * has no target-version parameter - so a file in
     * {@code database/migrations/} is an instruction to apply it on the next
     * loader run, including the nightly sync. Tests that need the schema ask
     * for it explicitly here; nothing else can reach it.
     *
     * @return how many held-back migrations were applied
     */
    static int applyPendingApproval(Connection owner) throws Exception {
        Path directory = locatePendingApprovalDirectory();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> pending;
        try (Stream<Path> files = Files.list(directory)) {
            pending = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ArchiveApiRoleHarness::migrationVersion))
                    .toList();
        }
        try (Statement s = owner.createStatement()) {
            for (Path migration : pending) {
                s.execute(Files.readString(migration));
            }
        }
        return pending.size();
    }

    static Path locatePendingApprovalDirectory() {
        Path c = Path.of("database", "migrations-pending-approval");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations-pending-approval");
    }

    /** Resolves whether the suite runs from the repository root or from {@code api/}. */
    static Path locateMigrationsDirectory() {
        Path c = Path.of("database", "migrations");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations");
    }

    static Path locateRoleScript() {
        Path c = Path.of("database", "roles", "archive_api_role.sql");
        return Files.exists(c) ? c : Path.of("..", "database", "roles", "archive_api_role.sql");
    }

    /** The API source tree, from either working directory. */
    static Path locateApiMainSources() {
        Path c = Path.of("src", "main", "java");
        return Files.isDirectory(c) ? c : Path.of("api", "src", "main", "java");
    }
}
