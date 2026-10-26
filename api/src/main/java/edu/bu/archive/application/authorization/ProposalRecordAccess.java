package edu.bu.archive.application.authorization;

/**
 * The Proposal slice of record authorization, as the Proposal application
 * services need it.
 *
 * <p>A port, not a second authorization system. Every method is already
 * implemented by {@link RecordAuthorizationService} against the Phase 1
 * policy, grants, identity links, department hierarchy and KRIM-derived
 * relationships; this interface exists so the Proposal services depend on the
 * operations they use, and so {@link #PERMIT_ALL} can stand in where the
 * Phase 1 beans are absent.
 *
 * <p>A sibling of {@link AwardRecordAccess}: the record operations differ per
 * module, so each has its own port, while everything they share -
 * {@code requireProvisioned}, {@code unrestricted} and the startup refusal -
 * lives once in {@link RecordAccess}. There is exactly one implementation of
 * the rules themselves, in {@link RecordAuthorizationService}.
 */
public interface ProposalRecordAccess extends RecordAccess {

    /**
     * Scope predicate for Proposal rows under {@code alias}, to sit in the
     * SAME {@code WHERE} as the search filters so the count, the page and the
     * ordering all describe one row set.
     */
    SqlFragment proposalScopeSql(String alias);

    /**
     * Throws when the caller may not read this Proposal version.
     *
     * @throws RecordNotAccessibleException a {@link
     *         java.util.NoSuchElementException}, so an unauthorized Proposal
     *         is answered 404 exactly like a missing one - a 403 would
     *         confirm the record exists.
     */
    void requireProposal(long proposalId);

    /** Throws when the caller may not read this Proposal family. */
    void requireProposalNumber(String proposalNumber);

    /**
     * Throws unless EVERY version of the family is visible. For responses
     * that span a family with no version key, where showing a subset as
     * though it were the whole thing is a wrong answer, not a partial one.
     */
    void requireEveryProposalVersion(String proposalNumber);

    /** Whether a Proposal may be listed, without throwing. */
    boolean canSeeProposalNumber(String proposalNumber);

    /** No authorization: today's behaviour, and the default. */
    ProposalRecordAccess PERMIT_ALL = new ProposalRecordAccess() {

        @Override
        public SqlFragment proposalScopeSql(String alias) {
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
        public void requireProposal(long proposalId) {
            // Intentionally nothing.
        }

        @Override
        public void requireProposalNumber(String proposalNumber) {
            // Intentionally nothing.
        }

        @Override
        public void requireEveryProposalVersion(String proposalNumber) {
            // Intentionally nothing.
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
            ProposalRecordAccess access,
            org.springframework.beans.factory.ObjectProvider<AuthorizationProperties> properties
    ) {
        RecordAccess.requireEnforceableWiring(access, PERMIT_ALL, properties, "ProposalRecordAccess");
    }
}
