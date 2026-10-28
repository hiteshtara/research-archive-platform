package edu.bu.archive.application.authorization.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.GrantType;

/**
 * What an administrator asked for when granting archive access.
 *
 * <p>Validated here rather than at the controller so the same rules apply to
 * every caller, and so they match the CHECK constraints in V082 instead of
 * letting the database be the first thing that notices.
 *
 * <p>{@code expiresAt} is null by default: approved decision D-ADM-3 - grants
 * do not expire automatically unless an administrator explicitly sets an
 * expiry. A null expiry is therefore a deliberate choice, not a missing value.
 */
public record GrantSpecification(
        GrantType type,
        String unitNumber,
        boolean includeDescendants,
        String ioValue,
        Instant expiresAt,
        String reason) {

    public GrantSpecification {
        if (type == null) {
            throw new IllegalArgumentException("grant type is required");
        }
        unitNumber = trimToNull(unitNumber);
        ioValue = trimToNull(ioValue);
        reason = trimToNull(reason);

        switch (type) {
            case UNIT -> {
                if (unitNumber == null) {
                    throw new IllegalArgumentException("UNIT grant requires a unit number");
                }
                if (ioValue != null) {
                    throw new IllegalArgumentException("UNIT grant must not carry an IO value");
                }
            }
            case IO -> {
                if (ioValue == null) {
                    throw new IllegalArgumentException("IO grant requires an IO (SAP account) value");
                }
                if (unitNumber != null) {
                    throw new IllegalArgumentException("IO grant must not carry a unit number");
                }
                if (includeDescendants) {
                    // ck_access_grant_shape forbids it: descendants are a unit-hierarchy
                    // notion and an IO value has no hierarchy to descend.
                    throw new IllegalArgumentException("IO grant cannot include descendants");
                }
            }
            case CENTRAL, CONTACT_DERIVATION -> {
                if (unitNumber != null || ioValue != null) {
                    throw new IllegalArgumentException(
                            type + " grant must not carry a unit number or IO value");
                }
                if (includeDescendants) {
                    // ck_access_grant_shape forbids it: there is no unit to descend from.
                    throw new IllegalArgumentException(
                            type + " grant cannot include descendants");
                }
            }
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
