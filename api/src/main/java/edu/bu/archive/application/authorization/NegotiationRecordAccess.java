package edu.bu.archive.application.authorization;

/**
 * The Negotiation slice of record authorization.
 *
 * <p>A port over the existing engine, sharing {@link RecordAccess} with the
 * Award, Proposal and Subaward ports. Approved 2026-10-27 as a HYBRID rule,
 * after profiling showed that inheritance alone would reach only 20.7% of
 * Negotiations:
 *
 * <ul>
 *   <li>the recorded <strong>lead unit</strong> (99.47% coverage), or</li>
 *   <li>the associated <strong>Award</strong>, only when the association
 *       type is explicitly {@code 'Award'}, or</li>
 *   <li>the associated <strong>Proposal</strong>, only when the association
 *       type is explicitly {@code 'Institutional Proposal'}.</li>
 * </ul>
 *
 * <p>These are ALTERNATIVE paths: a valid grant through any one permits
 * access. Negotiation is flat - one row per Negotiation, no versions - so
 * unlike the other modules there is no family question here at all.
 *
 * <p>Reaching a Negotiation grants NOTHING on its associated Award,
 * Proposal or Subaward: those keep their own gates, and restricted
 * associated records are omitted from Negotiation responses entirely (D7).
 */
public interface NegotiationRecordAccess extends RecordAccess {

    /**
     * Scope predicate for Negotiation rows under {@code alias}, to sit in
     * the SAME {@code WHERE} as the search filters so the page and the count
     * describe one row set. The alias must expose {@code negotiation_id},
     * {@code negotiation_association_type_description} and
     * {@code associated_document_id}.
     */
    SqlFragment negotiationScopeSql(String alias);

    /**
     * Throws when the caller may not read this Negotiation.
     *
     * @throws RecordNotAccessibleException a {@link
     *         java.util.NoSuchElementException}, so an unauthorized
     *         Negotiation is answered 404 exactly like a missing one - a 403
     *         would confirm it exists.
     */
    void requireNegotiation(long negotiationId);

    /** Whether a Negotiation may be listed, without throwing. */
    boolean canSeeNegotiation(long negotiationId);

    /**
     * Whether one Award is accessible in its own right, for filtering the
     * associated-record response.
     *
     * <p>Needed because reaching a Negotiation through its lead unit does
     * not authorize its associated Award. Without this, a Negotiation
     * reachable by department would hand back the Award's number, title,
     * sponsor and a link.
     */
    boolean canSeeAwardNumber(String awardNumber);

    /** The same, for an associated Proposal. */
    boolean canSeeProposalNumber(String proposalNumber);

    /** No authorization: today's behaviour, and the default. */
    NegotiationRecordAccess PERMIT_ALL = new NegotiationRecordAccess() {

        @Override
        public SqlFragment negotiationScopeSql(String alias) {
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
        public void requireNegotiation(long negotiationId) {
            // Intentionally nothing.
        }

        @Override
        public boolean canSeeNegotiation(long negotiationId) {
            return true;
        }

        @Override
        public boolean canSeeAwardNumber(String awardNumber) {
            return true;
        }

        @Override
        public boolean canSeeProposalNumber(String proposalNumber) {
            return true;
        }
    };

    /**
     * Startup refusal for this port. Delegates to
     * {@link RecordAccess#requireEnforceableWiring}; kept as a named entry
     * point so call sites read as the module they protect.
     */
    static void requireEnforceableWiring(
            NegotiationRecordAccess access,
            org.springframework.beans.factory.ObjectProvider<AuthorizationProperties> properties
    ) {
        RecordAccess.requireEnforceableWiring(access, PERMIT_ALL, properties, "NegotiationRecordAccess");
    }
}
