package edu.bu.archive.adapter.in.web.dto.me;

/**
 * What the signed-in caller may do, as the server sees it.
 *
 * <p>Exactly three booleans, and deliberately nothing else. No grants, no
 * institutional identifier, no Cognito subject, no membership dates, no
 * administrator roster, no auditing flag. A non-administrator learns only
 * {@code false}, which they already knew.
 *
 * <p>This record grants nothing. Every administrator endpoint
 * independently resolves its own actor and calls
 * {@code requireAdministrator}, so a response saying
 * {@code administrator: true} changes no authorization outcome anywhere.
 * It exists so the UI can decide whether to render a toggle, which is a
 * presentation question.
 *
 * @param administrator the caller resolves to an ACTIVE ARCHIVE_ADMIN
 *        membership
 * @param enforcementEnabled whether record-level enforcement is on.
 *        Reported only to an administrator - see {@link #denied()}
 * @param administrationAvailable {@code administrator && enforcementEnabled}:
 *        whether the administration screens can actually function, as
 *        opposed to merely being offered
 */
public record CapabilitiesResponse(
        boolean administrator,
        boolean enforcementEnabled,
        boolean administrationAvailable
) {

    /**
     * Every capability denied, disclosing nothing at all.
     *
     * <p>Used for every caller who is not a verified administrator: no
     * institutional identity, no membership, a suspended identity, an
     * ambiguous or revoked link, and any fault while resolving either.
     *
     * <p>{@code enforcementEnabled} is deliberately {@code false} here
     * rather than the real setting. The approved contract's status table
     * and its test table both specify "all three false" for an
     * authenticated non-administrator, and the standing requirement is
     * that no sensitive information appears in the response. Reporting the
     * real enforcement state to any authenticated caller would be a wider
     * disclosure than either allows.
     *
     * <p>The consequence, stated so it is not mistaken for a bug: for a
     * non-administrator {@code enforcementEnabled} is not a reading of the
     * system, it is part of a uniform denial. Nothing in the UI consumes
     * it - toggle visibility keys on {@code administrator} alone - and a
     * non-administrator has no screen whose behaviour depends on it.
     */
    public static CapabilitiesResponse denied() {
        return new CapabilitiesResponse(false, false, false);
    }

    /** An administrator's capabilities, given the live enforcement state. */
    public static CapabilitiesResponse administrator(boolean enforcementEnabled) {
        return new CapabilitiesResponse(true, enforcementEnabled, enforcementEnabled);
    }
}
