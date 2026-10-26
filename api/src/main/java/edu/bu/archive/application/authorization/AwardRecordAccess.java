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

    /** No authorization: today's behaviour, and the default. */
    AwardRecordAccess PERMIT_ALL = new AwardRecordAccess() {

        @Override
        public SqlFragment awardScopeSql(String alias) {
            return SqlFragment.NONE;
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
