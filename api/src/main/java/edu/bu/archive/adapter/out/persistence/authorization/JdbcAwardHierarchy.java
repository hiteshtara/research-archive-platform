package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.AwardHierarchy;

/**
 * Award hierarchy expansion over {@code archive.award_hierarchy} (SEC-005).
 *
 * <p>Read-only: one {@code SELECT}, no writes, consistent with every other
 * archive repository in this codebase.
 *
 * <h2>The three guarantees, and how each is actually enforced</h2>
 *
 * <p><b>Bounded</b> — {@code depth < :maxDepth} in the recursive arm, and the
 * depth is clamped before the query runs. A grant can therefore never be made
 * to walk further by anything in the data.
 *
 * <p><b>Cycle-safe</b> — the walk carries its own {@code path} array and
 * refuses to re-enter an Award already on it ({@code NOT child.award_number =
 * ANY(walk.path)}). PostgreSQL's own {@code CYCLE} clause would do this too,
 * but the explicit array works on every supported version and makes the
 * guarantee visible in the SQL rather than implied by a keyword. The path is
 * built as {@code text}, not as the column's {@code varchar(50)}: a recursive
 * CTE requires both terms to agree on the array type exactly, and
 * {@code varchar(50)[]} in the seed against {@code varchar[]} overall is a
 * grammar error rather than a widening. Without the cycle guard a
 * malformed archive row pair (A parent of B, B parent of A) spins until the
 * statement timeout — and this reads an archive of Kuali data, which can be
 * malformed in ways Kuali itself could not produce.
 *
 * <p><b>Family-bounded</b> — every hop requires
 * {@code child.root_award_number = walk.root_award_number}. Walking parent
 * links alone does NOT imply staying in one family: a malformed or re-parented
 * row could point at an Award whose root differs, and following it would hand
 * the grantee an unrelated Award family. The root is compared explicitly on
 * every hop.
 *
 * <p>Soft-deleted rows are excluded. {@code active} is Kuali's {@code Y}/{@code N}
 * text flag (the project's {@code OjbCharBooleanConversion} convention), and
 * only an explicit {@code N} excludes a row — NULL and unexpected values are
 * treated as live, because a hierarchy edge that merely looks odd should not
 * silently widen or narrow access without someone noticing it.
 */
public class JdbcAwardHierarchy implements AwardHierarchy {

    /**
     * A hard ceiling, independent of configuration. The configured depth is a
     * policy knob; this is a guard against one being set to something absurd.
     * BU's deepest observed Award hierarchy is far below this.
     */
    static final int MAX_SUPPORTED_DEPTH = 10;

    private static final String EXPAND = """
            WITH RECURSIVE walk AS (
                SELECT h.award_number,
                       h.root_award_number,
                       0 AS depth,
                       ARRAY[h.award_number::text] AS path
                  FROM archive.award_hierarchy h
                 WHERE h.award_number = :awardNumber
                   AND COALESCE(UPPER(TRIM(h.active)), 'Y') <> 'N'
                UNION ALL
                SELECT child.award_number,
                       child.root_award_number,
                       walk.depth + 1,
                       walk.path || child.award_number::text
                  FROM archive.award_hierarchy child
                  JOIN walk
                    ON child.parent_award_number = walk.award_number
                   AND child.root_award_number = walk.root_award_number
                 WHERE walk.depth < :maxDepth
                   AND COALESCE(UPPER(TRIM(child.active)), 'Y') <> 'N'
                   AND NOT child.award_number::text = ANY(walk.path)
            )
            SELECT DISTINCT award_number FROM walk
            """;

    private final JdbcClient jdbc;

    public JdbcAwardHierarchy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> descendantsWithin(String awardNumber, int maxDepth) {
        Set<String> reachable = AwardHierarchy.single(awardNumber);
        if (reachable.isEmpty()) {
            return reachable;
        }
        String root = reachable.iterator().next();

        int depth = Math.max(0, Math.min(maxDepth, MAX_SUPPORTED_DEPTH));
        if (depth == 0) {
            // Depth 0 is the granted Award alone. Asking the database would
            // return the same thing, but only when a hierarchy row exists -
            // and an Award with no hierarchy row must still reach itself.
            return reachable;
        }

        List<String> found = jdbc.sql(EXPAND)
                .param("awardNumber", root)
                .param("maxDepth", depth)
                .query(String.class)
                .list();

        Set<String> expanded = new LinkedHashSet<>(reachable);
        found.stream().filter(n -> n != null && !n.isBlank()).map(String::trim).forEach(expanded::add);
        return expanded;
    }
}
