package edu.bu.archive.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What this release may apply to a shared database.
 *
 * <h2>Why a test and not a note</h2>
 *
 * The ETL migration runner has no target-version parameter. It globs
 * {@code database/migrations/} and applies <strong>every</strong> version not
 * already in {@code public.schema_migration}:
 *
 * <ul>
 *   <li>{@code --migrate-only} applies all of them, not a chosen one;
 *   <li>{@code load_subawards_from_csv.py} — the <b>nightly Subaward
 *       sync</b> — calls {@code apply_migrations} unconditionally on every
 *       run, as does every other loader.
 * </ul>
 *
 * So a file in {@code database/migrations/} is not a proposal. It is an
 * instruction to apply it the next time any loader runs from an image that
 * contains it, including a scheduled one at 02:00. There is no "apply V088 but
 * not V089" available in that design.
 *
 * <p>V089 (SEC-008 activity audit) is therefore held in
 * {@code database/migrations-pending-approval/} until its retention period,
 * failure policies, privacy defaults and database privilege are approved. This
 * test is what stops it drifting back: raising {@link #RELEASE_CEILING} is a
 * visible, deliberate edit that a reviewer sees.
 */
@DisplayName("Migration release scope")
class MigrationReleaseScopeTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * The highest migration version this release is permitted to carry.
     *
     * <p>Raise it only when the migration above it is approved for the same
     * release as the code. V088 (SEC-005 Parent Award) is approved; V089
     * (SEC-008) is not.
     */
    private static final int RELEASE_CEILING = 88;

    @Test
    @DisplayName("no migration above the release ceiling is in the applied directory")
    void nothingAboveTheCeilingCanBeApplied() throws IOException {
        List<Integer> above = versionsIn(migrationsDirectory()).stream()
                .filter(version -> version > RELEASE_CEILING)
                .toList();

        assertThat(above)
                .as("database/migrations/ is applied in full by every loader run, including "
                        + "the nightly Subaward sync. A version above V%d here would be applied "
                        + "automatically, without the approval it is waiting for.",
                        RELEASE_CEILING)
                .isEmpty();
    }

    @Test
    @DisplayName("every held-back migration is absent from the applied directory")
    void heldBackMigrationsAreNotAlsoReleased() throws IOException {
        Path pending = pendingDirectory();
        if (!Files.isDirectory(pending)) {
            return;
        }
        List<Integer> held = versionsIn(pending);
        assertThat(held)
                .as("the held-back directory should not be empty while V089 is deferred")
                .isNotEmpty();

        List<Integer> released = versionsIn(migrationsDirectory());
        assertThat(released)
                .as("a version must be in exactly one directory - two copies of the same "
                        + "version number is worse than either placement")
                .doesNotContainAnyElementsOf(held);
    }

    @Test
    @DisplayName("V089 specifically is held back")
    void v089IsHeldBack() throws IOException {
        assertThat(versionsIn(migrationsDirectory()))
                .as("SEC-008's migration must not reach a shared database before its "
                        + "retention, privacy, failure-policy and privilege approvals")
                .doesNotContain(89);
        assertThat(versionsIn(pendingDirectory()))
                .as("and it must still exist, so the approved implementation can be released "
                        + "later without being rewritten")
                .contains(89);
    }

    @Test
    @DisplayName("the applied directory still has no gap below the ceiling")
    void theReleasedSequenceHasNoUnexplainedGap() throws IOException {
        /*
         * Holding a version back must not look like a lost migration to the
         * runner's own gap check. V089 is the HIGHEST version, so removing it
         * leaves no gap - this asserts that rather than assuming it, because
         * holding back a middle version later would be a different and much
         * more disruptive act.
         */
        List<Integer> released = versionsIn(migrationsDirectory());
        int highest = released.stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(highest)
                .as("the held-back version must be the highest, or the sequence gains a gap")
                .isEqualTo(RELEASE_CEILING);
    }

    private static List<Integer> versionsIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .map(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        }
    }

    private static Path migrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", "migrations");
    }

    private static Path pendingDirectory() {
        Path candidate = Path.of("database", "migrations-pending-approval");
        return Files.isDirectory(candidate)
                ? candidate
                : Path.of("..", "database", "migrations-pending-approval");
    }
}
