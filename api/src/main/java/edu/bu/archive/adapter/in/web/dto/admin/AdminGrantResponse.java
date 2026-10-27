package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.AccessGrant;

/**
 * One archive grant, as the administrative UI shows it.
 *
 * <p>{@code label} is the administrator-facing name for the grant type. IO is
 * labelled "SAP Account" because that is what administrators call it, while
 * the stored {@code grant_type} stays IO - renaming the enum would mean a
 * migration and would break the approved D-A decision's vocabulary.
 */
public record AdminGrantResponse(
        long grantId,
        String type,
        String label,
        String unitNumber,
        boolean includeDescendants,
        String ioValue,
        Instant validFrom,
        Instant expiresAt,
        Instant revokedAt,
        boolean active,
        String scopeNote) {

    public static AdminGrantResponse from(AccessGrant grant, Instant now) {
        return new AdminGrantResponse(
                grant.id(),
                grant.type().name(),
                label(grant),
                grant.unitNumber(),
                grant.includeDescendants(),
                grant.ioValue(),
                grant.validFrom(),
                grant.expiresAt(),
                grant.revokedAt(),
                grant.isActiveAt(now),
                scopeNote(grant));
    }

    private static String label(AccessGrant grant) {
        return switch (grant.type()) {
            case CENTRAL -> "Central (all records)";
            case UNIT -> "Unit " + grant.unitNumber()
                    + (grant.includeDescendants() ? " and sub-units" : " only");
            case IO -> "SAP Account " + grant.ioValue();
            case CONTACT_DERIVATION -> "Records where listed in a qualifying role";
        };
    }

    /**
     * Stated on the response rather than left to the UI: an IO grant reaches
     * Awards only and never travels to Proposals, Negotiations or Subawards
     * through relationships (design 4.6). Without saying so, the narrower
     * scope reads as a bug.
     */
    private static String scopeNote(AccessGrant grant) {
        return grant.type() == edu.bu.archive.application.authorization.GrantType.IO
                ? "Applies to Awards only; does not reach related Proposals, "
                        + "Negotiations or Subawards."
                : null;
    }
}
