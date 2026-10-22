package edu.bu.archive.application.authorization.admin;

/**
 * The caller is not an active ARCHIVE_ADMIN.
 *
 * <p>Thrown by the service layer, not by a controller annotation, so that the
 * check cannot be bypassed by a new endpoint that forgets to declare it.
 */
public class NotAnArchiveAdministratorException extends RuntimeException {

    public NotAnArchiveAdministratorException(String message) {
        super(message);
    }
}
