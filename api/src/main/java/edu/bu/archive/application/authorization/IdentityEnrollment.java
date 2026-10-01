package edu.bu.archive.application.authorization;

/**
 * Runs once per enforced request, before the identity is resolved: links an
 * unlinked identity when every enrollment check passes, and revokes an
 * automatically verified link whose mapping no longer holds. Never grants
 * anything; never widens access on failure.
 */
public interface IdentityEnrollment {

    IdentityEnrollment DISABLED = identity -> EnrollmentOutcome.DISABLED;

    EnrollmentOutcome prepare(ValidatedCognitoIdentity identity);
}
