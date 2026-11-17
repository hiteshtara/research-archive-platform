package edu.bu.archive.application.authorization.admin;

/**
 * Refused: the administrative change carried no reason, or one that does not
 * meet {@link Reasons#require(String)}.
 *
 * <p>Extends {@link IllegalArgumentException} because it is a malformed
 * request, so existing callers that treat bad input as a refusal (the bulk
 * path, the operator CLIs) keep doing so. The HTTP layer maps it to
 * {@code 400 REASON_REQUIRED}.
 */
public class ReasonRequiredException extends IllegalArgumentException {

    public static final String CODE = "REASON_REQUIRED";

    public ReasonRequiredException(String message) {
        super(message);
    }
}
