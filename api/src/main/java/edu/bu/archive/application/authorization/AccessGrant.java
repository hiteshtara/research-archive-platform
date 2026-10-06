package edu.bu.archive.application.authorization;

import java.time.Instant;
import java.util.Objects;

/**
 * One archive grant to one institutional identity. Grants are keyed on the
 * institutional identity, never on a login name, so a reassigned login name
 * cannot inherit someone else's grants.
 */
public record AccessGrant(
        long id,
        InstitutionalIdentifier grantee,
        GrantType type,
        String unitNumber,
        boolean includeDescendants,
        String ioValue,
        Instant validFrom,
        Instant expiresAt,
        Instant revokedAt
) {

    public AccessGrant {
        Objects.requireNonNull(grantee, "grantee");
        Objects.requireNonNull(type, "type");
        if (type == GrantType.UNIT && (unitNumber == null || unitNumber.isBlank())) {
            throw new IllegalArgumentException("UNIT grant needs a unit number");
        }
        if (type == GrantType.IO && (ioValue == null || ioValue.isBlank())) {
            throw new IllegalArgumentException("IO grant needs an IO value");
        }
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null
                && (validFrom == null || !now.isBefore(validFrom))
                && (expiresAt == null || now.isBefore(expiresAt));
    }
}
