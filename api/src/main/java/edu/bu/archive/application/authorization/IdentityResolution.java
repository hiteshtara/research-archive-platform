package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * Outcome of mapping a validated Cognito identity to an archive person.
 * Only {@link Mapped} can ever lead to record access; every other outcome
 * means no record access at all.
 */
public sealed interface IdentityResolution {

    /** A single active, verified link. The person may still hold no grants. */
    record Mapped(
            ValidatedCognitoIdentity cognitoIdentity,
            InstitutionalIdentifier institutionalIdentifier,
            Optional<KualiPersonId> kualiPersonId
    ) implements IdentityResolution {
    }

    /** Authenticated, but no active link exists: access is not provisioned. */
    record NotProvisioned(ValidatedCognitoIdentity cognitoIdentity)
            implements IdentityResolution {
    }

    /** More than one active link, or one institutional identity mapped to
     *  several people: never auto-resolved. */
    record Ambiguous(ValidatedCognitoIdentity cognitoIdentity)
            implements IdentityResolution {
    }

    /** The only link(s) for this Cognito identity are revoked. */
    record Revoked(ValidatedCognitoIdentity cognitoIdentity)
            implements IdentityResolution {
    }

    /** The institutional identity is suspended: overrides every grant. */
    record Suspended(ValidatedCognitoIdentity cognitoIdentity)
            implements IdentityResolution {
    }
}
