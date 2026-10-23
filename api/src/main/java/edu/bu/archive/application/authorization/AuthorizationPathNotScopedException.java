package edu.bu.archive.application.authorization;

/**
 * This endpoint has not been brought under record-level authorization yet.
 * With enforcement on, it is closed to everyone except Central users rather
 * than risk returning out-of-scope records (403).
 */
public class AuthorizationPathNotScopedException extends RuntimeException {
    public static final String CODE = "NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION";

    public AuthorizationPathNotScopedException() {
        super(CODE);
    }
}
