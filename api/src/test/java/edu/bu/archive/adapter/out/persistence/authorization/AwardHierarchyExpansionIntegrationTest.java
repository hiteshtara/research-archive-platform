package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-005: Award hierarchy expansion, against a real PostgreSQL.
 *
 * <p>What this proves that a unit test cannot: that the recursive CTE in
 * {@link JdbcAwardHierarchy} is valid SQL against the real
 * {@code archive.award_hierarchy} schema, and that its three safety
 * properties hold as <em>behaviour</em> rather than as intentions —
 *
 * <ul>
 *   <li><b>bounded</b> — depth 1 must not reach a grandchild;
 *   <li><b>cycle-safe</b> — a malformed parent loop must terminate, not hang.
 *       Kuali cannot produce a cycle (every child gets a fresh Award number),
 *       but this reads an <em>archive</em> of that data, and an archive can be
 *       malformed in ways its source could not be. The fixture writes the
 *       cycle directly, which is the only way to find out;
 *   <li><b>family-bounded</b> — a row whose {@code root_award_number} differs
 *       must never be followed, even when its {@code parent_award_number}
 *       points at an Award in scope. This is the one that would quietly hand
 *       a grantee an unrelated Award family.
 * </ul>
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class AwardHierarchyExpansionIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static Connection owner;
    private static JdbcAwardHierarchy hierarchy;

    @BeforeAll
    static void applyMigrationsAndSeed() throws Exception {
        owner = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        List<Path> migrations;
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations")
                : Path.of("..", "database", "migrations");
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(AwardHierarchyExpansionIntegrationTest::version))
                    .toList();
        }
        try (Statement s = owner.createStatement()) {
            for (Path migration : migrations) {
                s.execute(Files.readString(migration));
            }
        }

        /*
         * Family R: a three-level chain, plus a soft-deleted child and a
         * sibling. Family Q: a separate family whose row claims a parent in
         * family R - the cross-family trap. Family C: a two-row cycle.
         */
        try (Statement s = owner.createStatement()) {
            s.execute("""
                    INSERT INTO archive.award_hierarchy
                        (award_hierarchy_id, root_award_number, award_number,
                         parent_award_number, originating_award_number, active)
                    VALUES
                        (1, 'R-000', 'R-000', 'R-000', 'R-000', 'Y'),
                        (2, 'R-000', 'R-100', 'R-000', 'R-000', 'Y'),
                        (3, 'R-000', 'R-200', 'R-000', 'R-000', 'Y'),
                        (4, 'R-000', 'R-110', 'R-100', 'R-000', 'Y'),
                        (5, 'R-000', 'R-120', 'R-100', 'R-000', 'N'),
                        (6, 'R-000', 'R-111', 'R-110', 'R-000', 'Y'),
                        (10, 'Q-000', 'Q-000', 'Q-000', 'Q-000', 'Y'),
                        (11, 'Q-000', 'Q-900', 'R-000', 'Q-000', 'Y'),
                        (20, 'C-000', 'C-A',   'C-B',   'C-000', 'Y'),
                        (21, 'C-000', 'C-B',   'C-A',   'C-000', 'Y')
                    """);
        }

        var dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        hierarchy = new JdbcAwardHierarchy(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void close() throws SQLException {
        if (owner != null) {
            owner.close();
        }
    }

    // ── bounded ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("depth 0 is the granted Award alone")
    void depthZeroIsTheAwardAlone() {
        assertThat(hierarchy.descendantsWithin("R-000", 0)).containsExactly("R-000");
    }

    @Test
    @DisplayName("depth 1 reaches direct children and stops")
    void depthOneReachesDirectChildrenOnly() {
        assertThat(hierarchy.descendantsWithin("R-000", 1))
                .containsExactlyInAnyOrder("R-000", "R-100", "R-200")
                .as("R-110 is a grandchild and must not be reached at depth 1")
                .doesNotContain("R-110", "R-111");
    }

    @Test
    @DisplayName("depth 2 reaches grandchildren, depth 3 reaches the whole chain")
    void deeperDepthsReachFurther() {
        assertThat(hierarchy.descendantsWithin("R-000", 2))
                .containsExactlyInAnyOrder("R-000", "R-100", "R-200", "R-110");
        assertThat(hierarchy.descendantsWithin("R-000", 3))
                .containsExactlyInAnyOrder("R-000", "R-100", "R-200", "R-110", "R-111");
    }

    @Test
    @DisplayName("a depth beyond the supported ceiling is clamped, not honoured")
    void depthIsClampedToTheCeiling() {
        // Still the whole of family R, and no exception: the clamp is a guard
        // against an absurd configuration, not a reason to fail a request.
        assertThat(hierarchy.descendantsWithin("R-000", Integer.MAX_VALUE))
                .containsExactlyInAnyOrder("R-000", "R-100", "R-200", "R-110", "R-111");
    }

    // ── family-bounded ──────────────────────────────────────────────────

    @Test
    @DisplayName("a child in another family is never followed, however it points")
    void expansionNeverLeavesTheFamily() {
        /*
         * Q-900 names R-000 as its parent but belongs to root Q-000. Walking
         * parent links alone would pull an unrelated family into scope; the
         * root check on every hop is what stops it.
         */
        assertThat(hierarchy.descendantsWithin("R-000", 5))
                .as("Q-900 is in family Q-000 and must never be reachable from R-000")
                .doesNotContain("Q-900", "Q-000");
    }

    @Test
    @DisplayName("expanding one family does not reach a sibling family")
    void familiesStaySeparate() {
        assertThat(hierarchy.descendantsWithin("Q-000", 5)).containsExactly("Q-000");
    }

    // ── cycle-safe ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a parent cycle terminates instead of hanging")
    void aCycleTerminates() {
        /*
         * C-A is the parent of C-B and C-B is the parent of C-A. Without the
         * path guard this walks until the statement timeout. The assertion
         * that matters is that it returns at all; what it returns is the two
         * Awards of that family, each once.
         */
        Set<String> reachable = hierarchy.descendantsWithin("C-A", 5);
        assertThat(reachable).containsExactlyInAnyOrder("C-A", "C-B");
    }

    // ── soft deletes and missing rows ───────────────────────────────────

    @Test
    @DisplayName("a soft-deleted hierarchy row is not followed")
    void inactiveRowsAreExcluded() {
        assertThat(hierarchy.descendantsWithin("R-100", 1))
                .as("R-120 is active = 'N'")
                .containsExactlyInAnyOrder("R-100", "R-110");
    }

    @Test
    @DisplayName("an Award with no hierarchy row still reaches itself")
    void anAwardWithNoHierarchyRowReachesItself() {
        /*
         * The grant must not silently authorise nothing. An Award that was
         * never part of a hierarchy has no row at all, and a PARENT_AWARD
         * grant naming it has to keep reaching it.
         */
        assertThat(hierarchy.descendantsWithin("NOT-IN-HIERARCHY", 1))
                .containsExactly("NOT-IN-HIERARCHY");
    }

    @Test
    @DisplayName("a blank or null Award number reaches nothing")
    void blankAwardNumbersReachNothing() {
        assertThat(hierarchy.descendantsWithin(null, 1)).isEmpty();
        assertThat(hierarchy.descendantsWithin("   ", 1)).isEmpty();
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
