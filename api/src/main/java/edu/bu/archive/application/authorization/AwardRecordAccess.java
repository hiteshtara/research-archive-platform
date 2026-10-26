package edu.bu.archive.application.authorization;

/**
 * The Award slice of record authorization, as the Award application service
 * needs it.
 *
 * <p>This is a port, not a second authorization system. Every method is
 * already implemented by {@link RecordAuthorizationService} against the
 * Phase 1 policy, grants, identity links and KRIM-derived relationships; this
 * interface exists only so {@link
 * edu.bu.archive.application.award.AwardArchiveService} can depend on the
 * three operations it actually uses, and so {@link #PERMIT_ALL} can stand in
 * where there is no authorization at all - slice tests, and any context where
 * the Phase 1 beans are not present.
 *
 * <p>{@link #PERMIT_ALL} is the default and reproduces today's behaviour
 * exactly: no predicate is added to any statement and no record check runs.
 * It is deliberately the <em>only</em> permissive implementation; the real one
 * fails closed on a missing identity, a missing policy, or any failure while
 * resolving a scope.
 */
public interface AwardRecordAccess {

    /**
     * The scope predicate for Award rows under {@code alias}, to be appended
     * to the SAME {@code WHERE} as the search filters so that counts,
     * ordering and {@code LIMIT}/{@code OFFSET} all see only in-scope rows.
     *
     * <p>Returns {@link SqlFragment#NONE} when enforcement is off or the
     * caller is Central, and {@link SqlFragment#DENY_ALL} when the caller has
     * no usable scope. It is never empty merely because something failed -
     * a failure denies.
     */
    SqlFragment awardScopeSql(String alias);

    /**
     * Throws when the caller has no archive access at all.
     *
     * <p>DECISION 1 (2026-10-26): an authenticated user with no assigned
     * permissions receives Access Not Provisioned and no research data.
     * A scope predicate alone would render an empty list, which is "no
     * research data" but tells the user nothing and looks like an empty
     * archive. List endpoints call this first so the caller gets the
     * explicit problem instead.
     *
     * @throws AccessNotProvisionedException     no identity link, or no active grant
     * @throws IdentityAccessDeniedException     ambiguous, revoked or suspended
     * @throws ReauthenticationRequiredException the BU sign-in is too old
     */
    void requireProvisioned();

    /** True when enforcement is off, or the caller is Central (unrestricted). */
    boolean unrestricted();

    /**
     * Refuses a cross-module query that returns Award rows for which no
     * per-module scoping exists yet.
     *
     * <p>The document and attachment search indexes UNION Award,
     * Proposal, Negotiation, Subaward and IRB rows in one statement.
     * Scoping only the Award branch would still disclose the other four,
     * and no approved record rule exists for them. Rather than leave the
     * endpoint silently permitting Award rows outside the caller's scope,
     * it is refused for anyone who is not unrestricted.
     *
     * <p>Fails CLOSED and loudly, not quietly: an empty page would look
     * like an empty archive.
     */
    default void requireUnrestrictedForCrossModuleSearch(String what) {
        requireProvisioned();
        if (!unrestricted()) {
            throw new IdentityAccessDeniedException(
                    what + " is not available with scoped access yet, because it returns "
                            + "records from modules that have no approved authorization rule. "
                            + "Use the module-specific search instead.");
        }
    }

    /**
     * Throws unless EVERY version of the Award family is visible.
     *
     * <p>For responses that span a family with no version key - the AI
     * summary, question answering and evidence search. Seeing some versions
     * and being handed an answer synthesised from them, presented as the
     * whole Award, is a wrong answer dressed as a complete one. 403
     * AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS is the honest response.
     */
    void requireEveryAwardVersion(String awardNumber);

    /**
     * Throws when the caller may not read this Award version.
     *
     * @throws RecordNotAccessibleException which is a {@link
     *         java.util.NoSuchElementException}, so an unauthorized record is
     *         indistinguishable from a missing one and the response is 404.
     *         That is deliberate: a 403 would confirm the record exists.
     */
    void requireAward(long awardId);

    /** Throws when the caller may not read the current version of this Award family. */
    void requireAwardNumber(String awardNumber);

    /**
     * Whether an Award may be shown, without throwing. For additive callouts
     * that must simply disappear rather than fail the whole response - the
     * exact workflow-document-number match being the one that exists today.
     */
    boolean canSeeAwardNumber(String awardNumber);

    /**
     * Refuses to start when record authorization is ENABLED but the only
     * available implementation is {@link #PERMIT_ALL}.
     *
     * <p>Lives on the port so every service that resolves one is held to the
     * same rule - AwardArchiveService, AwardContactService, ExplorerService
     * and DashboardService all call it. Without it, a context where the
     * Phase 1 beans are missing, excluded or failed to initialise would
     * serve every Award to every authenticated caller while configuration
     * claimed enforcement was on. An operator would have positive
     * confirmation of a control that was not running.
     *
     * <p>Enforcement OFF with PERMIT_ALL is the normal, supported
     * configuration and is left alone.
     */
    static void requireEnforceableWiring(
            AwardRecordAccess access,
            org.springframework.beans.factory.ObjectProvider<AuthorizationProperties> properties
    ) {
        if (access != PERMIT_ALL) {
            return;
        }
        AuthorizationProperties settings = properties == null ? null : properties.getIfAvailable();
        if (settings != null && settings.isEnforcementEnabled()) {
            throw new IllegalStateException(
                    "app.authorization.enforcement-enabled is true but no AwardRecordAccess "
                            + "implementation is available, so Award authorization would silently "
                            + "permit every record. Refusing to start. Ensure the record "
                            + "authorization beans (AuthorizationConfiguration) are present.");
        }
    }

    /** No authorization: today's behaviour, and the default. */
    AwardRecordAccess PERMIT_ALL = new AwardRecordAccess() {

        @Override
        public SqlFragment awardScopeSql(String alias) {
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
        public void requireEveryAwardVersion(String awardNumber) {
            // Intentionally nothing.
        }

        @Override
        public void requireAward(long awardId) {
            // Intentionally nothing.
        }

        @Override
        public void requireAwardNumber(String awardNumber) {
            // Intentionally nothing.
        }

        @Override
        public boolean canSeeAwardNumber(String awardNumber) {
            return true;
        }
    };
}
