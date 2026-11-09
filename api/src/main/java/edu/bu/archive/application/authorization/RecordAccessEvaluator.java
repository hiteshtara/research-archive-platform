package edu.bu.archive.application.authorization;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides one record version against an {@link AccessOutcome} under an
 * explicit {@link AuthorizationPolicy}. A relationship to another record is
 * never a reason: only this record's (or, under FAMILY_WIDE, its own
 * family's) facts count.
 *
 * <p><strong>One exception, and it is SEC-003.</strong> Contact-derived
 * Research Staff access is decided against the version being read even under
 * FAMILY_WIDE. {@code versionScope} governs the explicit CENTRAL, UNIT and IO
 * grants only.
 *
 * <p>Module rules (approved requirements vs proposals):
 * <ul>
 *   <li>Central reaches every module (ID 1).</li>
 *   <li>Department: Award, Proposal and Negotiation by their resolved lead
 *       unit (ID 2; "lead unit" itself is proposal P6/D-E). Subaward and IRB
 *       have no approved department rule and are central-only (proposal P8).</li>
 *   <li>Research Staff: Award and Proposal contacts only (ID 3; qualifying
 *       roles are proposal P4 and come from the policy). Always scoped to
 *       the version being read (SEC-003).</li>
 *   <li>IO: any record whose resolver supplied IO values (ID 4; field pending D-A).</li>
 * </ul>
 */
public class RecordAccessEvaluator {

    /**
     * Why this record is being decided.
     *
     * <p>SEC-005, business decision 2026-11-09: a PARENT_AWARD grant reaches
     * Award records and their history, and must NOT automatically authorise a
     * Subaward or a Negotiation that happens to reference such an Award.
     *
     * <p>Subaward and Negotiation access is resolved by asking whether an
     * Award is visible, so without this distinction the restriction would be
     * unimplementable in Java: the inherited question is literally the same
     * question. The context says which one is being asked, and the SQL side
     * mirrors it by omitting the parent-award clause from the inherited
     * Award predicate.
     */
    public enum DecisionContext {
        /** The caller is reading this record itself. */
        DIRECT,
        /** This Award is being consulted to decide a Subaward or Negotiation. */
        INHERITED
    }

    private static final Set<RecordModule> DEPARTMENT_MODULES =
            EnumSet.of(RecordModule.AWARD, RecordModule.PROPOSAL, RecordModule.NEGOTIATION);
    private static final Set<RecordModule> CONTACT_MODULES =
            EnumSet.of(RecordModule.AWARD, RecordModule.PROPOSAL);

    private final AuthorizationPolicy policy;
    private final UnitHierarchy unitHierarchy;

    public RecordAccessEvaluator(AuthorizationPolicy policy, UnitHierarchy unitHierarchy) {
        this.policy = Objects.requireNonNull(policy);
        this.unitHierarchy = Objects.requireNonNull(unitHierarchy);
    }

    /**
     * @param record        the version being read; the ONLY version contact
     *                      derivation is ever decided against (SEC-003)
     * @param familyVersions every version of the same family (including
     *                       {@code record}); used only under FAMILY_WIDE, and
     *                       then only for department and IO grants
     */
    public AccessDecision decide(AccessOutcome outcome, RecordFacts record, List<RecordFacts> familyVersions) {
        return decide(outcome, record, familyVersions, DecisionContext.DIRECT);
    }

    /**
     * @param context {@link DecisionContext#INHERITED} when this Award is
     *                being consulted on behalf of a Subaward or Negotiation.
     *                PARENT_AWARD grants are ignored in that case (SEC-005).
     */
    public AccessDecision decide(AccessOutcome outcome, RecordFacts record,
                                 List<RecordFacts> familyVersions, DecisionContext context) {
        if (!(outcome instanceof AccessOutcome.Scoped scoped)) {
            return AccessDecision.deny(denialCode(outcome));
        }
        AccessScope scope = scoped.scope();
        if (scope.central()) {
            return AccessDecision.allow(EnumSet.of(AccessReason.CENTRAL));
        }

        List<RecordFacts> considered = policy.versionScope() == AuthorizationPolicy.VersionScope.FAMILY_WIDE
                ? familyOf(record, familyVersions)
                : List.of(record);

        EnumSet<AccessReason> reasons = EnumSet.noneOf(AccessReason.class);
        for (RecordFacts version : considered) {
            if (departmentMatches(scope, version)) {
                reasons.add(AccessReason.DEPARTMENT);
            }
            if (ioMatches(scope, version)) {
                reasons.add(AccessReason.IO_GRANT);
            }
            if (context == DecisionContext.DIRECT && parentAwardMatches(scope, version)) {
                reasons.add(AccessReason.PARENT_AWARD);
            }
        }
        /*
         * SEC-003, decided by the business 2026-11-05: contact-derived access
         * is ALWAYS version-specific, whatever versionScope says - being
         * listed on one version of a family must not reach the rest of it.
         * So this is evaluated against `record`, never against `considered`.
         *
         * Hard-coded rather than made configurable on purpose: the business
         * decided this rule instead of delegating it, and a setting could be
         * configured back into non-compliance. The explicit CENTRAL, UNIT and
         * IO grants above were deliberately left under versionScope.
         */
        if (contactMatches(scope, record)) {
            reasons.add(AccessReason.RESEARCH_STAFF_CONTACT);
        }
        return reasons.isEmpty() ? AccessDecision.deny("OUT_OF_SCOPE") : AccessDecision.allow(reasons);
    }

    private static List<RecordFacts> familyOf(RecordFacts record, List<RecordFacts> familyVersions) {
        // Never let a caller widen access with versions of another family.
        List<RecordFacts> family = familyVersions.stream()
                .filter(v -> v.module() == record.module() && v.familyKey().equals(record.familyKey()))
                .toList();
        return family.isEmpty() ? List.of(record) : family;
    }

    private boolean departmentMatches(AccessScope scope, RecordFacts version) {
        if (!DEPARTMENT_MODULES.contains(version.module()) || version.leadUnitNumber() == null) {
            return false;
        }
        for (UnitGrant unit : scope.units()) {
            if (unit.unitNumber().equals(version.leadUnitNumber())) {
                return true;
            }
            if (policy.departmentMatch() == AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS
                    && unit.includeDescendants()
                    && unitHierarchy.isSameOrDescendant(version.leadUnitNumber(), unit.unitNumber())) {
                return true;
            }
        }
        return false;
    }

    /**
     * SEC-005. The scope's Award numbers are ALREADY expanded through the
     * hierarchy by {@link AccessScopeResolver}, bounded and family-checked
     * there, so this is a set membership test and nothing more. Deliberately
     * so: a second traversal here could disagree with the one the list SQL
     * used, and a list and a workspace answering differently about the same
     * Award is exactly the defect this design avoids.
     *
     * <p>Award only, and only when the Award is being read DIRECTLY. A
     * PARENT_AWARD grant never matches a Proposal or IRB record, and - by
     * business decision 2026-11-09 - never authorises a Subaward or
     * Negotiation through inheritance either. See {@link DecisionContext}.
     *
     * <p>It DOES reach every version of the Awards it names, because
     * award_number is the family key: "the Award and its authorized history"
     * is one scope, not a per-version one.
     */
    private boolean parentAwardMatches(AccessScope scope, RecordFacts version) {
        return version.module() == RecordModule.AWARD
                && !scope.parentAwardNumbers().isEmpty()
                && version.familyKey() != null
                && scope.parentAwardNumbers().contains(version.familyKey().trim());
    }

    private boolean contactMatches(AccessScope scope, RecordFacts version) {
        if (scope.contactPersonId().isEmpty() || !CONTACT_MODULES.contains(version.module())) {
            return false;
        }
        KualiPersonId person = scope.contactPersonId().get();
        return version.contacts().stream().anyMatch(contact ->
                contact.employee()
                        && contact.personId().filter(person::equals).isPresent()
                        && contact.roleCode() != null
                        && policy.researchStaffRoles().contains(contact.roleCode().trim().toUpperCase()));
    }

    private static boolean ioMatches(AccessScope scope, RecordFacts version) {
        return version.ioValues().stream().anyMatch(scope.ioValues()::contains);
    }

    private static String denialCode(AccessOutcome outcome) {
        return switch (outcome) {
            case AccessOutcome.NotProvisioned notProvisioned -> "ACCESS_NOT_PROVISIONED";
            case AccessOutcome.Denied denied -> denied.reason().name();
            case AccessOutcome.Scoped scoped -> "OUT_OF_SCOPE";
        };
    }
}
