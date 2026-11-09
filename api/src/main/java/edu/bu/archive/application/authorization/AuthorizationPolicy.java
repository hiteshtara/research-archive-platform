package edu.bu.archive.application.authorization;

import java.util.Objects;
import java.util.Set;

/**
 * The unapproved policy choices (proposals P3, P4, P6 in design rev 3.6a),
 * as explicit strategies. There is deliberately NO default: an enforced
 * deployment must configure every one, or every request is denied
 * (POLICY_NOT_CONFIGURED). Configuring them is the stakeholders' decision,
 * not this code's.
 */
public record AuthorizationPolicy(
        VersionScope versionScope,
        DepartmentMatch departmentMatch,
        Set<String> researchStaffRoles,
        /**
         * SEC-005: how many parent to child hops a PARENT_AWARD grant may
         * follow. 0 is the granted Award alone, 1 adds its direct children.
         * Conservative by default: access that is too narrow is a support
         * request, access that is too wide is a disclosure.
         */
        int parentAwardDepth,
        ContactDerivation contactDerivation
) {

    /** Earlier three-setting form: contact access only through an explicit grant. */
    /** Default parent-award depth: one level. */
    public static final int DEFAULT_PARENT_AWARD_DEPTH = 1;

    public AuthorizationPolicy(VersionScope versionScope, DepartmentMatch departmentMatch, Set<String> researchStaffRoles) {
        this(versionScope, departmentMatch, researchStaffRoles,
             DEFAULT_PARENT_AWARD_DEPTH, ContactDerivation.EXPLICIT_GRANT);
    }

    /** The shape before SEC-005 added a depth. */
    public AuthorizationPolicy(VersionScope versionScope, DepartmentMatch departmentMatch,
                               Set<String> researchStaffRoles, ContactDerivation contactDerivation) {
        this(versionScope, departmentMatch, researchStaffRoles,
             DEFAULT_PARENT_AWARD_DEPTH, contactDerivation);
    }

    /**
     * Where Research Staff (ID 3) access comes from (design section 13, decided 2026-10-01).
     */
    public enum ContactDerivation {
        /** Only identities holding an explicit CONTACT_DERIVATION grant. */
        EXPLICIT_GRANT,
        /**
         * Any identity whose ACTIVE link carries a verified KIM principal (PERSON_ID), and only
         * while that principal is a qualifying contact on at least one record. No grant row is
         * needed; a KIM principal who is nobody's contact still has no access.
         */
        VERIFIED_PRINCIPAL
    }

    /** P3 / D-D. */
    public enum VersionScope {
        /** Access follows the facts of the version being read. */
        PER_VERSION,
        /** Access to any version of a family reaches every version of it. */
        FAMILY_WIDE
    }

    /** P6 / D-E. */
    public enum DepartmentMatch {
        EXACT_LEAD_UNIT,
        /** Honour a UNIT grant's includeDescendants flag via the unit hierarchy. */
        LEAD_UNIT_WITH_DESCENDANTS
    }

    public AuthorizationPolicy {
        Objects.requireNonNull(versionScope, "versionScope");
        Objects.requireNonNull(departmentMatch, "departmentMatch");
        Objects.requireNonNull(contactDerivation, "contactDerivation");
        researchStaffRoles = Set.copyOf(researchStaffRoles);
        if (parentAwardDepth < 0) {
            throw new IllegalArgumentException("parentAwardDepth must not be negative");
        }
        if (researchStaffRoles.isEmpty()) {
            throw new IllegalArgumentException("researchStaffRoles must be configured (P4)");
        }
    }
}
