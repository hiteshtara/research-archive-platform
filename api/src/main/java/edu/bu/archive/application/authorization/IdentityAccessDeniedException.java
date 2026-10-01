package edu.bu.archive.application.authorization;

/** Ambiguous, revoked or suspended identity, or a failure: 403, no detail. */
public class IdentityAccessDeniedException extends RuntimeException {
    public IdentityAccessDeniedException() {
        super("ACCESS_DENIED");
    }
}
