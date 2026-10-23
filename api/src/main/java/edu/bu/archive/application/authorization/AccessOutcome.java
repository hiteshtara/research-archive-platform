package edu.bu.archive.application.authorization;

/** What a request may see, before any record is considered. */
public sealed interface AccessOutcome {

    /** Mapped person with at least one active grant. */
    record Scoped(AccessScope scope) implements AccessOutcome {
    }

    /** Authenticated but not provisioned: no identity link, or no active grant. */
    record NotProvisioned(NotProvisionedReason reason) implements AccessOutcome {
    }

    /** Ambiguous, revoked or suspended identity, or a failure: no access. */
    record Denied(DenialReason reason) implements AccessOutcome {
    }

    enum NotProvisionedReason { NO_IDENTITY_LINK, NO_ACTIVE_GRANTS }

    enum DenialReason {
        AMBIGUOUS_IDENTITY,
        REVOKED_IDENTITY,
        SUSPENDED_IDENTITY,
        POLICY_NOT_CONFIGURED,
        EVALUATION_FAILED,
        /** The BU sign-in is missing or older than the configured maximum: sign in again. */
        REAUTHENTICATION_REQUIRED
    }
}
