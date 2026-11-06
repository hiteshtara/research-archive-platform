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
