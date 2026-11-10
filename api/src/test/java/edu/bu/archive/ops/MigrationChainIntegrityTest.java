package edu.bu.archive.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The migration chain, checked where CI can see it.
 *
 * <h2>Why this exists in Java</h2>
 *
 * <p>The chain check itself lives in the ETL
 * ({@code find_missing_migration_versions}) and is covered by Python tests -
 * but <strong>CI does not run the ETL tests</strong>. The consequence was not
 * theoretical: the committed chain reported V081 missing and undeclared, that
 * assertion failed from the moment V082 landed, and nobody saw it for five
 * releases. A check whose whole purpose is to catch a renumbered or lost
 * migration before it ships has to run on every pull request.
 *
 * <p>So this applies the same arithmetic in the API's unit-test suite, which
 * CI does run.
 *
 * <h2>Why it reads the Python rather than restating it</h2>
 *
 * <p>The declarations are parsed out of {@code migrations.py}. A second,
 * hand-maintained copy of "which gaps are explained" is exactly the kind of
 * duplicated fact that drifts - and the drifting copy is the one nobody is
 * reading when it matters. One source of truth, read at test time.
 *
 * <p>The parse is itself asserted to have found something, because a renamed
 * constant would otherwise yield an empty set and a test that passes by
 * knowing nothing.
 */
@DisplayName("Migration chain integrity")
class MigrationChainIntegrityTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** {@code NAME = {73, 81}} - the set literal, however it is spaced. */
    private static final Pattern DECLARATION =
            Pattern.compile("^(INTENTIONALLY_SUPERSEDED|RESERVED)_MIGRATION_VERSIONS\\s*=\\s*\\{([^}]*)\\}",
                    Pattern.MULTILINE);

    @Test
    @DisplayName("every gap in the committed chain is declared, with a reason")
    void everyGapIsDeclared() throws IOException {
        Set<Integer> versions = committedVersions();
        Set<Integer> declared = declaredGaps();

        TreeSet<Integer> undeclared = new TreeSet<>();
        for (int version = versions.iterator().next();
                version <= versions.stream().mapToInt(Integer::intValue).max().orElseThrow();
                version++) {
            if (!versions.contains(version) && !declared.contains(version)) {
                undeclared.add(version);
            }
        }

        assertThat(undeclared)
                .as("an undeclared gap is a migration that was renumbered or lost. "
                        + "If it is deliberate, declare it in migrations.py with the reason - "
                        + "INTENTIONALLY_SUPERSEDED for one that will never arrive, "
                        + "RESERVED for one that is expected to.")
                .isEmpty();
    }

    @Test
    @DisplayName("no declaration is stale - a declared gap whose migration has arrived")
    void noDeclarationIsStale() throws IOException {
        Set<Integer> present = committedVersions();
        Set<Integer> stale = declaredGaps().stream()
                .filter(present::contains)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(stale)
                .as("this version is declared as a gap AND present on disk. The "
                        + "declaration is now false, and a false declaration would "
                        + "silence a genuine future gap at that number. Remove it from "
                        + "migrations.py. Expected when feat/award-amount-dates-v081 "
                        + "merges and brings V081 with it.")
                .isEmpty();
    }

    @Test
    @DisplayName("the declarations were actually found, so this suite cannot pass by knowing nothing")
    void theDeclarationsParse() throws IOException {
        Set<Integer> declared = declaredGaps();

        assertThat(declared)
                .as("parsed from migrations.py; an empty set means the constants were "
                        + "renamed and these tests silently stopped checking anything")
                .isNotEmpty()
                .contains(73, 81);
    }

    @Test
    @DisplayName("73 and 81 are declared separately, because their futures differ")
    void supersededAndReservedAreNotConflated() throws IOException {
        String source = Files.readString(migrationsPy());

        assertThat(versionsIn(source, "INTENTIONALLY_SUPERSEDED"))
                .as("V073 will never arrive - V077 implements its change, and "
                        + "restoring it is unsafe (its DROP CONSTRAINT has no IF EXISTS)")
                .contains(73)
                .doesNotContain(81);
        assertThat(versionsIn(source, "RESERVED"))
                .as("V081 is held by an unmerged branch and IS expected to arrive, so "
                        + "its declaration has an expiry that V073's does not")
                .contains(81)
                .doesNotContain(73);
    }

    // ---------------- helpers ----------------

    private static Set<Integer> declaredGaps() throws IOException {
        String source = Files.readString(migrationsPy());
        Set<Integer> declared = new TreeSet<>();
        Matcher matcher = DECLARATION.matcher(source);
        while (matcher.find()) {
            declared.addAll(parseVersions(matcher.group(2)));
        }
        return declared;
    }

    private static Set<Integer> versionsIn(String source, String prefix) {
        Matcher matcher = Pattern.compile(
                "^" + prefix + "_MIGRATION_VERSIONS\\s*=\\s*\\{([^}]*)\\}",
                Pattern.MULTILINE).matcher(source);
        return matcher.find() ? parseVersions(matcher.group(1)) : Set.of();
    }

    private static Set<Integer> parseVersions(String literal) {
        Set<Integer> versions = new TreeSet<>();
        for (String part : literal.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                versions.add(Integer.valueOf(trimmed));
            }
        }
        return versions;
    }

    private static Set<Integer> committedVersions() throws IOException {
        Path directory = locate(Path.of("database", "migrations"));
        try (Stream<Path> files = Files.list(directory)) {
            List<Integer> versions = files
                    .map(path -> MIGRATION_VERSION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
            assertThat(versions).as("no migrations found - wrong directory?").isNotEmpty();
            return new TreeSet<>(versions);
        }
    }

    private static Path migrationsPy() {
        return locate(Path.of("etl", "archive_etl", "upload", "migrations.py"));
    }

    private static Path locate(Path relative) {
        if (Files.exists(relative)) {
            return relative;
        }
        Path fromModule = Path.of("..").resolve(relative);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate " + relative);
    }
}
