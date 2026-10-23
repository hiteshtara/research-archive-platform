package edu.bu.archive.application.authorization.admin;

/**
 * Administrative actions recorded in authz.access_audit.
 *
 * <p>Scope note: these are the ONLY actions audited. Routine searches and
 * record views are deliberately not logged - a recorded scope decision that
 * narrows SEC-008's literal "all archive activity" and is still awaiting
 * confirmation. See ANTHONY_REQUIREMENTS_TRACEABILITY.md, decision D4.
 */
public enum AdminAuditAction {
    ADMIN_ROLE_ASSIGNED,
    ADMIN_ROLE_REVOKED,
    ADMIN_ROLE_BOOTSTRAPPED,
    ACCESS_GRANTED,
    ACCESS_MODIFIED,
    ACCESS_REVOKED
}
