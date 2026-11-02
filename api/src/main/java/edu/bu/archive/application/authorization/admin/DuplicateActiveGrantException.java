package edu.bu.archive.application.authorization.admin;

/**
 * Refused: an unrevoked grant for this grantee and scope already exists.
 *
 * <p>Raised when V087's {@code ux_access_grant_active_scope} rejects the
 * insert. The database holds that invariant, not the application: the
 * duplicate pre-check in the provisioning CLI is a read followed by a write
 * with no lock, so two concurrent identical requests can both pass it and
 * only the index stops the second one. Four concurrent identical requests
 * once produced four active grants, which matters because revocation takes a
 * single {@code grant_id} - two identical rows cannot be told apart, and
 * revoking one leaves the other in force while the audit trail reads as
 * though access was removed.
 *
 * <p>This is a conflict with current state, not a malformed request. The same
 * call succeeds once the existing grant is revoked, so the HTTP layer answers
 * 409 rather than 400 or 500.
 *
 * <p>Carries no grantee identifier in its message. Identifiers are not
 * secrets, but they are not error-body material either, and the caller
 * already knows which grantee it asked about.
 */
public class DuplicateActiveGrantException extends RuntimeException {

    public DuplicateActiveGrantException(String message, Throwable cause) {
        super(message, cause);
    }
}
