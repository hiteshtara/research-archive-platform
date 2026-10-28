package edu.bu.archive.application.authorization.admin;

/**
 * Refused: re-linking this archive identity would transfer active grants, and
 * a second, distinct ARCHIVE_ADMIN has not approved this exact identity and
 * target.
 *
 * <p>Distinct from a plain refusal so the administrative API can answer 409 -
 * the request is well formed and will succeed once the approval exists.
 */
public class RelinkApprovalRequiredException extends RuntimeException {

    public RelinkApprovalRequiredException(String message) {
        super(message);
    }
}
