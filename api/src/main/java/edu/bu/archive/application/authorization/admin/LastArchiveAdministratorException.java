package edu.bu.archive.application.authorization.admin;

/**
 * Refused: revoking this membership would leave no active ARCHIVE_ADMIN.
 *
 * <p>Raised after the active rows have been locked, so the decision is made on
 * a count that cannot change underneath it. Two concurrent revokes of the final
 * two administrators therefore serialise, and exactly one is refused.
 */
public class LastArchiveAdministratorException extends RuntimeException {

    public LastArchiveAdministratorException(String message) {
        super(message);
    }
}
