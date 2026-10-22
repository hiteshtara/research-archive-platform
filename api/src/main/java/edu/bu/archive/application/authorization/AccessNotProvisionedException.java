package edu.bu.archive.application.authorization;

/** Authenticated, but archive access is not provisioned (403, no record data). */
public class AccessNotProvisionedException extends RuntimeException {
    public AccessNotProvisionedException() {
        super(AccessNotProvisionedProblem.CODE);
    }
}
