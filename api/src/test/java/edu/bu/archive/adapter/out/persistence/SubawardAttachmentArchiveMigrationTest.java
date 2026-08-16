package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * Schema-only proof for V077 (widens
 * archive.subaward_attachment_archive.archive_status's CHECK constraint
 * from ARCHIVED/MISSING/FAILED to also allow PENDING/UPLOADING, adds
 * DEFAULT 'PENDING', and drops ux_subaward_attachment_archive_object -
 * see database/migrations/V077__widen_subaward_attachment_archive_status_and_allow_shared_objects.sql
 * for the full rationale).
 *
 * "Clean committed baseline" requirement: this class deliberately reads
 * the migration set via `git ls-files database/migrations/`, never a
 * bare directory listing - a bare listing would also pick up the
 * unrelated, untracked, uncommitted V071/V073 files that happen to sit
 * in this working tree (see docs/project-memory/CURRENT_STATE.md's
 * "Open items"), silently making this test's "full chain" pass depend
 * on local, uncommitted state that a fresh clone would never have. Only
 * git-tracked files are applied - this test must be run with V077 (and
 * this file) staged (`git add`) if run before they're committed, since
 * `git ls-files` reflects the index, not just HEAD.
 *
 * No Java repository exists for this table (Subaward attachment
 * archival is ETL-only, per etl/attachment_orchestrator.py), so this
 * reads migration files directly via JDBC against a real ephemeral
 * Postgres - same technique as
 * NegotiationArchiveRepositorySchemaIntegrationTest. pgvector/pgvector:pg17
 * (not bare postgres:17) for the same reason as that test - V069/V070's
 * CREATE EXTENSION vector would otherwise fail the CHAIN container's
 * full-chain apply.
 *
 * Two separate containers because the two things this class proves need
 * genuinely different starting schemas:
 *   - CHAIN: the full, real, git-tracked migration set must apply
 *     cleanly end to end, including V077 in its real position, and the
 *     resulting schema must accept the widened statuses, reject invalid
 *     ones, default correctly, allow two rows to share one
 *     (s3_bucket, s3_key), still enforce the attachment_id PK and the
 *     subaward_id/attachment_id FKs, and be idempotent under a second
 *     V077 execution.
 *   - PRESERVATION: proving V077 mutates no existing row, and proving
 *     the "before" contrast (two rows may NOT share a key pre-V077),
 *     requires inserting fixture rows BEFORE V077 runs (under V019's
 *     original, narrower constraints) and re-reading/re-testing AFTER -
 *     a full-chain container that already has V077 applied by the time
 *     a test method runs can't represent that "before" state at all.
 */
@Testcontainers
class SubawardAttachmentArchiveMigrationTest {

    private static final Pattern MIGRATION_VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final int TARGET_VERSION = 77;

    @Container
    static PostgreSQLContainer<?> chainContainer = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Container
    static PostgreSQLContainer<?> preservationContainer = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    private static Path targetMigrationPath;

    // Captured in @BeforeAll, asserted in @Test methods below - see
    // v077PreservesExistingArchivedRowDataUnchanged().
    private static FixtureRow beforeMigration;
    private static FixtureRow afterMigration;

    private record FixtureRow(
            long attachmentId, long subawardId, String subawardCode,
            int sequenceNumber, String fileDataId, String originalFileName,
            String mimeType, Long documentId, String s3Bucket, String s3Key,
            Long byteSize, String sha256, String archiveStatus,
            Object archivedTimestamp, String errorMessage
    ) {
    }

    @BeforeAll
    static void applyMigrationsAndSeedFixtures() throws Exception {
        Path repoRoot = locateRepoRoot();
        Path migrationsDir = repoRoot.resolve("database/migrations");
        List<Path> allMigrations = gitTrackedMigrations(repoRoot, migrationsDir);
        assertThat(allMigrations)
                .as("expected git-tracked database/migrations/*.sql files "
                        + "under " + migrationsDir + " - if run before "
                        + "committing, stage V077 and this test file "
                        + "first (`git add`), since git ls-files reflects "
                        + "the index")
                .isNotEmpty();

        targetMigrationPath = allMigrations.stream()
                .filter(path -> migrationVersion(path) == TARGET_VERSION)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "V" + TARGET_VERSION + " migration file not found "
                                + "among git-tracked files in " + migrationsDir
                                + " - is it staged?"
                ));

        // ---- CHAIN container: apply every git-tracked migration, in order ----
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            for (Path migration : allMigrations) {
                statement.execute(Files.readString(migration));
            }
        }

        // ---- PRESERVATION container: apply only what predates the target ----
        List<Path> beforeTargetMigrations = allMigrations.stream()
                .filter(path -> migrationVersion(path) < TARGET_VERSION)
                .toList();
        try (Connection connection = connect(preservationContainer);
                Statement statement = connection.createStatement()) {
            for (Path migration : beforeTargetMigrations) {
                statement.execute(Files.readString(migration));
            }

            // archive.subaward is the FK parent for both
            // subaward_attachment (V018) and subaward_attachment_archive
            // (V019) - must exist first, minimal required columns only.
            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, sequence_number, subaward_code
                    ) VALUES (
                        900001, 1, 'TEST-CODE-1'
                    )
                    """);

            // Synthetic fixture, not a copy of any specific live row -
            // shaped like the real archived population (see the
            // 2026-08-15 read-only ECS diagnostic: 1,764 real ARCHIVED
            // rows, single bucket, distinct per-row keys under the old
            // subawards/{subaward_id}/{attachment_id}/{filename}
            // scheme) but with placeholder identifiers so this test
            // never depends on live dev data staying stable.
            statement.execute("""
                    INSERT INTO archive.subaward_attachment (
                        attachment_id, subaward_id, subaward_code, sequence_number,
                        attachment_type_code, attachment_type_description, document_id,
                        file_data_id, file_name, mime_type, document_status_code,
                        description, last_update_timestamp, last_update_user,
                        source_update_timestamp, source_update_user,
                        source_version_number, source_object_id
                    ) VALUES (
                        900001, 900001, 'TEST-CODE-1', 1,
                        NULL, NULL, NULL,
                        '11111111-1111-1111-1111-111111111111', 'fixture.pdf',
                        'application/pdf', NULL,
                        NULL, NULL, NULL,
                        NULL, NULL,
                        NULL, NULL
                    )
                    """);

            statement.execute("""
                    INSERT INTO archive.subaward_attachment_archive (
                        attachment_id, subaward_id, subaward_code, sequence_number,
                        file_data_id, original_file_name, mime_type, document_id,
                        s3_bucket, s3_key, byte_size, sha256,
                        archive_status, archived_timestamp, error_message
                    ) VALUES (
                        900001, 900001, 'TEST-CODE-1', 1,
                        '11111111-1111-1111-1111-111111111111', 'fixture.pdf',
                        'application/pdf', NULL,
                        'test-bucket', 'subawards/900001/900001/fixture.pdf',
                        1234, 'abababababababababababababababababababababababababababababababab',
                        'ARCHIVED', '2026-08-01T00:00:00Z', NULL
                    )
                    """);

            beforeMigration = readFixtureRow(connection);

            // "Before" contrast: two reference rows may NOT yet share a
            // single (s3_bucket, s3_key) - proves the old
            // ux_subaward_attachment_archive_object constraint is
            // genuinely still in force pre-migration, not already gone
            // for some unrelated reason.
            seedSecondReferenceRow(statement, 900002L, 900001L);
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number, s3_bucket, s3_key, archive_status) "
                    + "VALUES (900002, 900001, 'TEST-CODE-1', 2, "
                    + "'test-bucket', "
                    + "'subawards/900001/900001/fixture.pdf', 'ARCHIVED')"
            ))
                    .as("pre-migration, a second row must NOT be able to "
                            + "share the first row's (s3_bucket, s3_key)")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "ux_subaward_attachment_archive_object"
                    );
        }

        // Apply the target migration itself to the preservation container.
        try (Connection connection = connect(preservationContainer);
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(targetMigrationPath));
            afterMigration = readFixtureRow(connection);
        }
    }

    private static void seedSecondReferenceRow(
            Statement statement, long attachmentId, long subawardId
    ) throws SQLException {
        statement.execute(String.format(
                "INSERT INTO archive.subaward_attachment "
                + "(attachment_id, subaward_id, subaward_code, sequence_number) "
                + "VALUES (%d, %d, 'TEST-CODE-1', 2)",
                attachmentId, subawardId
        ));
    }

    private static FixtureRow readFixtureRow(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT attachment_id, subaward_id, subaward_code, "
                        + "sequence_number, file_data_id, original_file_name, "
                        + "mime_type, document_id, s3_bucket, s3_key, "
                        + "byte_size, sha256, archive_status, "
                        + "archived_timestamp, error_message "
                        + "FROM archive.subaward_attachment_archive "
                        + "WHERE attachment_id = 900001"
                )) {
            assertThat(resultSet.next()).isTrue();
            return new FixtureRow(
                    resultSet.getLong("attachment_id"),
                    resultSet.getLong("subaward_id"),
                    resultSet.getString("subaward_code"),
                    resultSet.getInt("sequence_number"),
                    resultSet.getString("file_data_id"),
                    resultSet.getString("original_file_name"),
                    resultSet.getString("mime_type"),
                    (Long) resultSet.getObject("document_id"),
                    resultSet.getString("s3_bucket"),
                    resultSet.getString("s3_key"),
                    (Long) resultSet.getObject("byte_size"),
                    resultSet.getString("sha256"),
                    resultSet.getString("archive_status"),
                    resultSet.getTimestamp("archived_timestamp"),
                    resultSet.getString("error_message")
            );
        }
    }

    private static Connection connect(PostgreSQLContainer<?> container)
            throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
    }

    /*
     * Returns every database/migrations/*.sql path git considers
     * tracked (staged or committed - `git ls-files` reads the index),
     * sorted by migration version. Deliberately excludes any untracked
     * file physically present in the working tree, so this test proves
     * what a clean checkout of this commit would actually apply, not
     * what happens to be sitting locally right now.
     */
    private static List<Path> gitTrackedMigrations(
            Path repoRoot, Path migrationsDir
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "git", "ls-files", "--", "database/migrations"
        );
        processBuilder.directory(repoRoot.toFile());
        Process process = processBuilder.start();

        List<Path> tracked = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Path candidate = repoRoot.resolve(line);
                String fileName = candidate.getFileName().toString();
                if (MIGRATION_VERSION.matcher(fileName).matches()
                        && Files.exists(candidate)) {
                    tracked.add(candidate);
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "git ls-files exited " + exitCode + " in " + repoRoot
            );
        }

        return tracked.stream()
                .sorted(Comparator.comparingInt(
                        SubawardAttachmentArchiveMigrationTest::migrationVersion
                ))
                .toList();
    }

    private static int migrationVersion(Path path) {
        Matcher matcher =
                MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a migration file: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /*
     * archive.subaward_attachment_archive has two FK parents -
     * archive.subaward (V018, via subaward_id) and
     * archive.subaward_attachment (V018, via attachment_id) - both
     * must exist before any archive-row insert below can succeed,
     * independent of whatever archive_status value is under test.
     */
    private static void seedParentChain(Statement statement, long id)
            throws SQLException {
        statement.execute(String.format(
                "INSERT INTO archive.subaward "
                + "(subaward_id, sequence_number, subaward_code) "
                + "VALUES (%d, 1, 'CONSTRAINT-TEST')",
                id
        ));
        statement.execute(String.format(
                "INSERT INTO archive.subaward_attachment "
                + "(attachment_id, subaward_id, subaward_code, sequence_number) "
                + "VALUES (%d, %d, 'CONSTRAINT-TEST', 1)",
                id, id
        ));
    }

    private static Path locateRepoRoot() throws IOException {
        // .git is a directory in a normal clone but a plain file (a
        // "gitdir: ..." pointer back to the main repo) inside a
        // `git worktree` checkout - accept either so this test also
        // runs from a worktree, not just the primary clone.
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("database/migrations"))
                    && Files.exists(candidate.resolve(".git"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException(
                "Could not locate the repository root (a directory "
                        + "containing both database/migrations/ and .git/) "
                        + "above " + Path.of("").toAbsolutePath()
        );
    }

    @Test
    void fullMigrationChainAppliesCleanlyIncludingV077() throws Exception {
        // If @BeforeAll's chainContainer setup threw, this class would
        // already have failed to initialize - this assertion exists so
        // a passing run has an explicit, named proof point of its own,
        // and so the constraint/default checks below have somewhere to
        // hang an independent "the chain really did finish" assertion.
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT to_regclass("
                        + "'archive.subaward_attachment_archive') IS NOT NULL"
                )) {
            resultSet.next();
            assertThat(resultSet.getBoolean(1)).isTrue();
        }
    }

    @Test
    void v077WidenedConstraintAcceptsAllFiveDurableStates() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            for (String status : List.of(
                    "PENDING", "UPLOADING", "ARCHIVED", "MISSING", "FAILED"
            )) {
                long id = 800000L + Math.floorMod(status.hashCode(), 1000);
                seedParentChain(statement, id);
                assertThatCode(() -> statement.execute(String.format(
                        "INSERT INTO archive.subaward_attachment_archive "
                        + "(attachment_id, subaward_id, subaward_code, "
                        + "sequence_number, archive_status) VALUES "
                        + "(%d, %d, 'CONSTRAINT-TEST', 1, '%s')",
                        id, id, status
                )))
                        .as("status %s must be accepted by the widened "
                                + "CHECK constraint", status)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void v077WidenedConstraintStillRejectsAnInvalidStatus() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            seedParentChain(statement, 777777L);
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number, archive_status) VALUES "
                    + "(777777, 777777, 'CONSTRAINT-TEST', 1, 'BOGUS')"
            ))
                    .as("the widening must still be a CHECK constraint, "
                            + "not its removal")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "ck_subaward_attachment_archive_status"
                    );
        }
    }

    @Test
    void v077DefaultIsPendingForAMetadataOnlyInsert() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            seedParentChain(statement, 766000L);
            statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number) VALUES "
                    + "(766000, 766000, 'CONSTRAINT-TEST', 1)"
            );
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT archive_status FROM "
                    + "archive.subaward_attachment_archive "
                    + "WHERE attachment_id = 766000"
            )) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("PENDING");
            }
        }
    }

    /*
     * Two (or more) reference rows sharing the exact same
     * (s3_bucket, s3_key) - the whole point of dropping
     * ux_subaward_attachment_archive_object - must now succeed, mirroring
     * exactly what etl/attachment_orchestrator.py's mark_subaward_file_uploaded
     * does: a single bulk UPDATE ... WHERE file_data_id = :file_data_id
     * setting every sharing row to the identical bucket/key.
     */
    @Test
    void v077AllowsTwoOrMoreRowsToShareOneBucketAndKey() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            seedParentChain(statement, 744000L);
            seedParentChain(statement, 744001L);
            statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number, file_data_id, s3_bucket, s3_key, "
                    + "archive_status) VALUES "
                    + "(744000, 744000, 'CONSTRAINT-TEST', 1, "
                    + "'22222222-2222-2222-2222-222222222222', "
                    + "'shared-bucket', 'subawards/shared-file.pdf', "
                    + "'PENDING')"
            );
            assertThatCode(() -> statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number, file_data_id, s3_bucket, s3_key, "
                    + "archive_status) VALUES "
                    + "(744001, 744001, 'CONSTRAINT-TEST', 1, "
                    + "'22222222-2222-2222-2222-222222222222', "
                    + "'shared-bucket', 'subawards/shared-file.pdf', "
                    + "'PENDING')"
            ))
                    .as("dropping ux_subaward_attachment_archive_object "
                            + "must allow two distinct attachment_id rows "
                            + "to carry the same (s3_bucket, s3_key)")
                    .doesNotThrowAnyException();

            // Simulate the orchestrator's own bulk UPDATE ... WHERE
            // file_data_id = :file_data_id - both rows must accept it
            // together in one statement, exactly as
            // mark_subaward_file_uploaded does.
            assertThatCode(() -> statement.execute(
                    "UPDATE archive.subaward_attachment_archive "
                    + "SET archive_status = 'ARCHIVED', "
                    + "s3_bucket = 'shared-bucket', "
                    + "s3_key = 'subawards/shared-file.pdf' "
                    + "WHERE file_data_id = "
                    + "'22222222-2222-2222-2222-222222222222'"
            )).doesNotThrowAnyException();

            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT COUNT(*) FROM archive.subaward_attachment_archive "
                    + "WHERE file_data_id = "
                    + "'22222222-2222-2222-2222-222222222222' "
                    + "AND archive_status = 'ARCHIVED' "
                    + "AND s3_bucket = 'shared-bucket' "
                    + "AND s3_key = 'subawards/shared-file.pdf'"
            )) {
                resultSet.next();
                assertThat(resultSet.getInt(1))
                        .as("both reference rows must now point at the "
                                + "one shared object")
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void v077StillEnforcesTheAttachmentIdPrimaryKey() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            seedParentChain(statement, 733000L);
            statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number) VALUES "
                    + "(733000, 733000, 'CONSTRAINT-TEST', 1)"
            );
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number) VALUES "
                    + "(733000, 733000, 'CONSTRAINT-TEST', 1)"
            ))
                    .as("dropping the UNIQUE(bucket,key) constraint must "
                            + "not touch the attachment_id PRIMARY KEY")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("subaward_attachment_archive_pkey");
        }
    }

    @Test
    void v077StillEnforcesForeignKeysToSubawardAndSubawardAttachment()
            throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            // No archive.subaward / archive.subaward_attachment row
            // seeded for 722000 at all - both FKs must reject this.
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number) VALUES "
                    + "(722000, 722000, 'CONSTRAINT-TEST', 1)"
            ))
                    .as("attachment_id must still FK to "
                            + "archive.subaward_attachment")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign key");
        }
    }

    /*
     * Re-executes the target migration's own SQL a second time against a
     * schema where it has already been applied once (chainContainer, via
     * @BeforeAll). Proves the migration is safe even if manually
     * re-run - both DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT (same
     * name, same definition both times) and SET DEFAULT are each
     * individually idempotent operations, so the whole file re-executing
     * cleanly a second time (no error, identical resulting
     * constraint/default, constraint still dropped) is expected, not
     * incidental.
     */
    @Test
    void v077IsIdempotentWhenReExecuted() throws Exception {
        try (Connection connection = connect(chainContainer);
                Statement statement = connection.createStatement()) {
            assertThatCode(() -> statement.execute(
                    Files.readString(targetMigrationPath)
            )).doesNotThrowAnyException();

            // Re-verify the CHECK constraint still has exactly the
            // widened shape post-re-run, not a duplicated or corrupted
            // one, and the UNIQUE constraint is still gone (not
            // resurrected by the re-run).
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT conname FROM pg_constraint "
                    + "WHERE conrelid = 'archive.subaward_attachment_archive'::regclass "
                    + "AND conname IN ("
                    + "'ck_subaward_attachment_archive_status', "
                    + "'ux_subaward_attachment_archive_object')"
            )) {
                List<String> remaining = new ArrayList<>();
                while (resultSet.next()) {
                    remaining.add(resultSet.getString(1));
                }
                assertThat(remaining)
                        .as("re-running the migration must leave exactly "
                                + "the CHECK constraint, no duplicate, and "
                                + "the UNIQUE constraint still absent")
                        .containsExactly("ck_subaward_attachment_archive_status");
            }

            seedParentChain(statement, 755000L);
            statement.execute(
                    "INSERT INTO archive.subaward_attachment_archive "
                    + "(attachment_id, subaward_id, subaward_code, "
                    + "sequence_number) VALUES "
                    + "(755000, 755000, 'CONSTRAINT-TEST', 1)"
            );
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT archive_status FROM "
                    + "archive.subaward_attachment_archive "
                    + "WHERE attachment_id = 755000"
            )) {
                resultSet.next();
                assertThat(resultSet.getString(1)).isEqualTo("PENDING");
            }
        }
    }

    /*
     * The core "no destructive data mutation" proof: a row inserted
     * BEFORE the target migration (under V019's original, narrower
     * constraints) is byte-for-byte identical after it runs - the
     * migration touches only the CHECK/UNIQUE constraints and the
     * column's DEFAULT, never UPDATEs a row.
     */
    @Test
    void v077PreservesExistingArchivedRowDataUnchanged() {
        assertThat(afterMigration).isEqualTo(beforeMigration);
        assertThat(afterMigration.archiveStatus()).isEqualTo("ARCHIVED");
    }
}
