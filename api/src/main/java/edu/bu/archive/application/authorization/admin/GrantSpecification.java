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
        /** PARENT_AWARD only (SEC-005): the Award the grant is rooted at. */
        String awardNumber,
        Instant expiresAt,
        String reason) {

    /** The shape before SEC-005 added PARENT_AWARD. */
    public GrantSpecification(GrantType type, String unitNumber, boolean includeDescendants,
                              String ioValue, Instant expiresAt, String reason) {
        this(type, unitNumber, includeDescendants, ioValue, null, expiresAt, reason);
    }

    public GrantSpecification {
        if (type == null) {
            throw new IllegalArgumentException("grant type is required");
        }
        unitNumber = trimToNull(unitNumber);
        ioValue = trimToNull(ioValue);
        awardNumber = trimToNull(awardNumber);
        reason = trimToNull(reason);

        switch (type) {
            case UNIT -> {
                if (unitNumber == null) {
                    throw new IllegalArgumentException("UNIT grant requires a unit number");
                }
                if (ioValue != null) {
                    throw new IllegalArgumentException("UNIT grant must not carry an IO value");
                }
                if (awardNumber != null) {
                    throw new IllegalArgumentException("UNIT grant must not carry an award number");
                }
            }
            case IO -> {
                if (ioValue == null) {
                    throw new IllegalArgumentException("IO grant requires an IO (SAP account) value");
                }
                if (unitNumber != null) {
                    throw new IllegalArgumentException("IO grant must not carry a unit number");
                }
                if (awardNumber != null) {
                    throw new IllegalArgumentException("IO grant must not carry an award number");
                }
                if (includeDescendants) {
                    // ck_access_grant_shape forbids it: descendants are a unit-hierarchy
                    // notion and an IO value has no hierarchy to descend.
                    throw new IllegalArgumentException("IO grant cannot include descendants");
                }
            }
            case PARENT_AWARD -> {
                if (awardNumber == null) {
                    throw new IllegalArgumentException(
                            "PARENT_AWARD grant requires an award number");
                }
                if (unitNumber != null || ioValue != null) {
                    throw new IllegalArgumentException(
                            "PARENT_AWARD grant must not carry a unit number or IO value");
                }
                if (includeDescendants) {
                    /*
                     * ck_access_grant_shape forbids it, and it would be
                     * ambiguous anyway: Award descendants are governed by
                     * app.authorization.parent-award-depth, not by a
                     * per-grant flag that means "unit descendants" everywhere
                     * else in this type.
                     */
                    throw new IllegalArgumentException(
                            "PARENT_AWARD grant cannot include descendants; depth is a policy setting");
                }
            }
            case CENTRAL, CONTACT_DERIVATION -> {
                if (unitNumber != null || ioValue != null || awardNumber != null) {
                    throw new IllegalArgumentException(
                            type + " grant must not carry a unit number, IO value or award number");
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
