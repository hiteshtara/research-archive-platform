package edu.bu.archive.application.authorization.admin;

/**
 * Administrative actions recorded in authz.access_audit.
 *
 * <p>Scope note: these are the ONLY actions audited. Routine searches and
 * record views are deliberately not logged - a recorded scope decision that
 * narrows SEC-008's literal "all archive activity" and is still awaiting
 * confirmation. See ANTHONY_REQUIREMENTS_TRACEABILITY.md, decision D4.
 *
 * <p>Adding a value needs no migration: authz.access_audit.action is
 * VARCHAR(40) with no CHECK constraint. Keep names under 40 characters.
 */
public enum AdminAuditAction {
    ADMIN_ROLE_ASSIGNED,
    ADMIN_ROLE_REVOKED,
    ADMIN_ROLE_BOOTSTRAPPED,
    ACCESS_GRANTED,
    ACCESS_MODIFIED,
    ACCESS_REVOKED,
    IDENTITY_LINKED,
    IDENTITY_RELINKED,
    RELINK_APPROVAL_REQUESTED,
    RELINK_APPROVAL_GRANTED,
    RELINK_APPROVAL_REVOKED,
    IDENTITY_UNLINKED,
    IDENTITY_SUSPENDED,
    IDENTITY_RESTORED,
    // A-category control 3 (V090, held back): one row per approval transition.
    PRIVILEGE_APPROVAL_REQUESTED,
    PRIVILEGE_APPROVAL_GRANTED,
    PRIVILEGE_APPROVAL_REVOKED,
    PRIVILEGE_APPROVAL_CONSUMED,
    PRIVILEGE_APPROVAL_EXPIRED,
    /** The one-time operator seating of the second administrator (design 11.1). */
    ADMIN_ROLE_SECOND_BOOTSTRAPPED
}
