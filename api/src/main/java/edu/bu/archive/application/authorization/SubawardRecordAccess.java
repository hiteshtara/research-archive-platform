package edu.bu.archive.application.authorization;

/**
 * The Subaward slice of record authorization.
 *
 * <p>A port over the existing engine, sharing {@link RecordAccess} with the
 * Award and Proposal ports. There is no Subaward authorization rule of its
 * own: access is INHERITED from the Awards that fund the Subaward, so every
 * decision here is an Award decision taken somewhere else.
 *
 * <p>Approved 2026-10-26: a user may access a Subaward when they are
 * authorized to view <em>at least one</em> of its funding Awards. ANY-match,
 * not ALL-match.
 *
 * <p>Access to a Subaward grants nothing on the Awards themselves - the
 * Award endpoints keep their own gates, so a user who reaches a Subaward
 * through Award A still cannot open Award B that also funds it.
 */
public interface SubawardRecordAccess extends RecordAccess {

    /**
     * Scope predicate for Subaward rows under {@code alias}, to sit in the
     * SAME {@code WHERE} as the search filters so the page and the count
     * describe one row set. The alias must expose {@code subaward_id}.
     */
    SqlFragment subawardScopeSql(String alias);

    /**
     * Throws when no funding Award of this Subaward is accessible.
     *
     * @throws RecordNotAccessibleException a {@link
     *         java.util.NoSuchElementException}, so an unauthorized Subaward
     *         is answered 404 exactly like a missing one - a 403 would
     *         confirm it exists.
     */
    void requireSubaward(long subawardId);

    /** Whether a Subaward may be listed, without throwing. */
    boolean canSeeSubaward(long subawardId);

    /**
     * Whether one FUNDING Award is accessible in its own right.
     *
     * <p>The same decision {@code RecordVisibility.canSeeAward} makes, and
     * the same method on the implementation - declared here so the Subaward
     * services can filter funding rows without depending on the whole
     * visibility interface.
     *
     * <p>Needed because reaching a Subaward authorizes the Subaward, not its
     * Awards. A Subaward funded by Awards A and B, where the caller may see
     * only A, is readable - but its funding list must not hand back B's
     * number, title, sponsor, status, amount or link.
     */
    boolean canSeeAward(long awardId);

    /** No authorization: today's behaviour, and the default. */
    SubawardRecordAccess PERMIT_ALL = new SubawardRecordAccess() {

        @Override
        public SqlFragment subawardScopeSql(String alias) {
            return SqlFragment.NONE;
        }

        @Override
        public void requireProvisioned() {
            // Intentionally nothing.
        }

        @Override
        public boolean unrestricted() {
            return true;
        }

        @Override
        public void requireSubaward(long subawardId) {
            // Intentionally nothing.
        }

        @Override
        public boolean canSeeSubaward(long subawardId) {
            return true;
        }

        @Override
        public boolean canSeeAward(long awardId) {
            return true;
        }
    };

    /**
     * Startup refusal for this port. Delegates to
     * {@link RecordAccess#requireEnforceableWiring}; kept as a named entry
     * point so call sites read as the module they protect.
     */
    static void requireEnforceableWiring(
            SubawardRecordAccess access,
            org.springframework.beans.factory.ObjectProvider<AuthorizationProperties> properties
    ) {
        RecordAccess.requireEnforceableWiring(access, PERMIT_ALL, properties, "SubawardRecordAccess");
    }
}
