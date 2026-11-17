package edu.bu.archive.application.authorization.admin;

/**
 * Refused: this change needs independent approval by a second, distinct
 * administrator, and the approval workflow does not exist yet.
 *
 * <p>Raised for the actions decisions D1 and D3 and the A-category design
 * place behind approval: appointing an administrator, any CENTRAL grant,
 * restoring a suspended identity that holds ARCHIVE_ADMIN, and linking a new
 * Cognito identity to an archive identity that already holds grants or
 * ARCHIVE_ADMIN. Until the approval workflow lands these are refused
 * outright, for the HTTP API and the operator CLIs alike.
 *
 * <p>Mapped to {@code 403 APPROVAL_REQUIRED}.
 */
public class ApprovalRequiredException extends RuntimeException {

    public static final String CODE = "APPROVAL_REQUIRED";

    public ApprovalRequiredException(String message) {
        super(message);
    }
}
