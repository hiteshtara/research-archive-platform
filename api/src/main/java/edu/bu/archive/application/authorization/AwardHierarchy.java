package edu.bu.archive.application.authorization;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expands a PARENT_AWARD grant into the Award numbers it reaches (SEC-005).
 *
 * <p>Expansion happens once, when the scope is resolved — not per record —
 * because the result is needed in two places that must agree: the
 * {@link RecordAccessEvaluator}, which answers one record, and
 * {@link RecordScopeSqlBuilder}, which scopes a list. A predicate that
 * recursed per row could not be rendered into a {@code WHERE} clause at all
 * ({@code WITH RECURSIVE} is statement-level in PostgreSQL), and two
 * independent traversals could disagree, which is how a list and a workspace
 * end up answering differently about the same Award.
 *
 * <p><strong>Three guarantees every implementation owes:</strong>
 *
 * <ol>
 *   <li><b>Bounded.</b> Never more than {@code maxDepth} parent→child hops.
 *       Depth 0 is the granted Award alone; depth 1 adds its direct children.
 *       The default is 1, deliberately conservative.
 *   <li><b>Cycle-safe.</b> A row whose parent chain loops must terminate the
 *       walk, not hang it. Kuali's own data cannot contain a cycle — every
 *       child is created with a freshly generated Award number that cannot
 *       already be an ancestor — but this reads an archive of that data, and
 *       an archive can be malformed in ways its source could not be.
 *   <li><b>Family-bounded.</b> Expansion must never leave the Award family it
 *       started in. {@code archive.award_hierarchy.root_award_number} is the
 *       family key, and it is checked explicitly rather than assumed to follow
 *       from walking parent links.
 * </ol>
 *
 * <p>The returned set always contains the granted Award itself, so a grant on
 * an Award with no hierarchy rows at all still reaches that Award.
 */
@FunctionalInterface
public interface AwardHierarchy {

    /**
     * Every Award number reachable from {@code awardNumber} within
     * {@code maxDepth} parent→child hops, inside its own family.
     *
     * @return a set containing at least {@code awardNumber}; never null
     */
    Set<String> descendantsWithin(String awardNumber, int maxDepth);

    /**
     * No hierarchy: a PARENT_AWARD grant reaches exactly the Award it names.
     *
     * <p>This is the default wiring, and it is a safe one — it under-reaches
     * rather than over-reaches. An implementation that returned an empty set
     * would be worse than useless: the grant would authorise nothing and look
     * like a configuration problem.
     */
    AwardHierarchy SELF_ONLY = (awardNumber, maxDepth) -> single(awardNumber);

    static Set<String> single(String awardNumber) {
        Set<String> self = new LinkedHashSet<>();
        if (awardNumber != null && !awardNumber.isBlank()) {
            self.add(awardNumber.trim());
        }
        return self;
    }
}
