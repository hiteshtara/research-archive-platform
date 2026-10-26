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
 * <p>Deliberately a sibling of the Award port rather than a shared one. The
 * Award port is on an unmerged branch, so sharing it now would couple this
 * work to that review; the two should be consolidated once both are on main.
 * The duplication is the interface only - there is exactly one implementation
 * of the rules, in {@link RecordAuthorizationService}.
 */
public interface ProposalRecordAccess {

    /**
     * Scope predicate for Proposal rows under {@code alias}, to sit in the
     * SAME {@code WHERE} as the search filters so the count, the page and the
     * ordering all describe one row set.
     */
    SqlFragment proposalScopeSql(String alias);

    /**
     * Throws when the caller has no archive access at all, so an
     * unprovisioned user gets an explicit problem rather than an empty list
     * that looks like an empty archive.
     */
    void requireProvisioned();

    /** True when enforcement is off, or the caller is Central. */
    boolean unrestricted();

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
     * Refuses to start when record authorization is ENABLED but the only
     * available implementation is {@link #PERMIT_ALL}.
     *
     * <p>Without it, a context where the Phase 1 beans are missing, excluded
     * or failed to initialise would serve every Proposal to every
     * authenticated caller while configuration claimed enforcement was on -
     * an operator reading {@code enforcement-enabled=true} would have
     * positive confirmation of a control that was not running. Failing
     * startup is loud and cannot be mistaken for working.
     *
     * <p>Enforcement OFF with {@code PERMIT_ALL} is the supported
     * configuration today and is left alone.
     */
    static void requireEnforceableWiring(
            ProposalRecordAccess access,
            org.springframework.beans.factory.ObjectProvider<AuthorizationProperties> properties
    ) {
        if (access != PERMIT_ALL) {
            return;
        }
        AuthorizationProperties settings = properties == null ? null : properties.getIfAvailable();
        if (settings != null && settings.isEnforcementEnabled()) {
            throw new IllegalStateException(
                    "app.authorization.enforcement-enabled is true but no ProposalRecordAccess "
                            + "implementation is available, so Proposal authorization would "
                            + "silently permit every record. Refusing to start. Ensure the record "
                            + "authorization beans (AuthorizationConfiguration) are present.");
        }
    }
}
