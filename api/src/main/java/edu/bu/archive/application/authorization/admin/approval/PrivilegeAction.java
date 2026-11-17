package edu.bu.archive.application.authorization.admin.approval;

/**
 * The escalations that need a second, independent administrator
 * (A-category design, section 3.2). Mirrors {@code ck_pa_action} in V090.
 */
public enum PrivilegeAction {
    /** Every appointment of an ARCHIVE_ADMIN. */
    ADMIN_ASSIGN,
    /** Un-suspending an identity whose ARCHIVE_ADMIN membership is active (D3). */
    ADMIN_RESTORE,
    /** An administrator granting archive access to themselves (control 1). */
    SELF_GRANT,
    /** An administrator widening their own grant (control 2). */
    SELF_WIDEN,
    /** Linking a Cognito identity to an archive identity that holds grants or ARCHIVE_ADMIN (N2). */
    LINK_PRIVILEGED,
    /** Any CENTRAL grant, to anyone (D1). */
    CENTRAL_GRANT
}
