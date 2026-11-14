package edu.bu.archive.application.authorization.admin;

/**
 * The administrative API is available read-only and a write was attempted.
 *
 * <p>Not an authorization failure: the caller may well be a verified active
 * ARCHIVE_ADMIN, and the same resources are readable. The environment is
 * configured to refuse writes, and the request would succeed once
 * {@code app.authorization.administration-writes-enabled} is true.
 *
 * <p>Maps to 409, the same status the last-administrator and duplicate-grant
 * guards use, for the same reason: a well-formed, authorized request refused
 * by the current state of the server.
 */
public class AdministrationWritesDisabledException extends RuntimeException {

    public AdministrationWritesDisabledException(String message) {
        super(message);
    }
}
