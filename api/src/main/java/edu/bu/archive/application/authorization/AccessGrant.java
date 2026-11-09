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
        Instant revokedAt,
        /** PARENT_AWARD only (SEC-005): the Award this grant is rooted at. */
        String awardNumber
) {

    /**
     * The shape every grant type but PARENT_AWARD has. Kept so the existing
     * call sites - and the stored procedure-free JDBC mapper - do not all
     * have to pass a null they do not care about.
     */
    public AccessGrant(long id, InstitutionalIdentifier grantee, GrantType type, String unitNumber,
                       boolean includeDescendants, String ioValue, Instant validFrom,
                       Instant expiresAt, Instant revokedAt) {
        this(id, grantee, type, unitNumber, includeDescendants, ioValue,
             validFrom, expiresAt, revokedAt, null);
    }

    public AccessGrant {
        Objects.requireNonNull(grantee, "grantee");
        Objects.requireNonNull(type, "type");
        if (type == GrantType.UNIT && (unitNumber == null || unitNumber.isBlank())) {
            throw new IllegalArgumentException("UNIT grant needs a unit number");
        }
        if (type == GrantType.IO && (ioValue == null || ioValue.isBlank())) {
            throw new IllegalArgumentException("IO grant needs an IO value");
        }
        if (type == GrantType.PARENT_AWARD && (awardNumber == null || awardNumber.isBlank())) {
            throw new IllegalArgumentException("PARENT_AWARD grant needs an award number");
        }
        if (type != GrantType.PARENT_AWARD && awardNumber != null) {
            /*
             * Mirrors ck_access_grant_shape in V088. An Award number on a UNIT
             * or IO grant would be silently ignored by the resolver, which is
             * the kind of thing that reads as "the grant does not work".
             */
            throw new IllegalArgumentException(type + " grant must not carry an award number");
        }
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null
                && (validFrom == null || !now.isBefore(validFrom))
                && (expiresAt == null || now.isBefore(expiresAt));
    }
}
