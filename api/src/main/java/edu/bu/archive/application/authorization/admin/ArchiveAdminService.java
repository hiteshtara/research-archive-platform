package edu.bu.archive.application.authorization.admin;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import org.springframework.dao.DuplicateKeyException;
import java.sql.SQLException;

/**
 * The one place administrative changes happen.
 *
 * <p>Every method that changes anything (a) requires the caller to be an active
 * ARCHIVE_ADMIN and (b) writes an audit row in the same transaction. Both
 * checks live here rather than on controllers deliberately: the existing
 * attachment gate is enforced per endpoint across 26 controllers, which is 26
 * chances to forget it. Routing every administrative change through this
 * service makes omission impossible rather than merely discouraged.
 *
 * <p><strong>Administration is not data access.</strong> Nothing in this class
 * grants the caller or the subject any archive record access, and the
 * record-level evaluator never consults
 * {@code authz.admin_role_member}. An ARCHIVE_ADMIN with no
 * {@code authz.access_grant} row sees no records.
 *
 * <p><strong>Revocation is immediate.</strong> Authorization is resolved per
 * request from the authorization store, never from a Cognito group claim, so a
 * revoke takes effect on the next request even though the caller's JWT remains
 * valid for up to an hour.
 *
 * <p>Phase 1 scope: no administrator is bootstrapped and no live permission is
 * granted. The class is wired and tested, not used.
 */
@Service
public class ArchiveAdminService {

    private final AdminRoleRepository adminRoles;
    private final AdminAuditSink audit;
    private final AccessGrantRepository grants;
    private final AdminIdentityRepository identities;
    private final AdminAuditRepository auditHistory;
    private final RelinkApprovalRepository relinkApprovals;

    public ArchiveAdminService(AdminRoleRepository adminRoles, AdminAuditSink audit,
                               AccessGrantRepository grants, AdminIdentityRepository identities,
                               AdminAuditRepository auditHistory) {
        this(adminRoles, audit, grants, identities, auditHistory, null);
    }

    /** The Spring constructor. The 5-arg one is a test convenience. */
    @org.springframework.beans.factory.annotation.Autowired
    public ArchiveAdminService(AdminRoleRepository adminRoles, AdminAuditSink audit,
                               AccessGrantRepository grants, AdminIdentityRepository identities,
                               AdminAuditRepository auditHistory,
                               RelinkApprovalRepository relinkApprovals) {
        this.relinkApprovals = relinkApprovals;
        this.adminRoles = adminRoles;
        this.audit = audit;
        this.grants = grants;
        this.identities = identities;
        this.auditHistory = auditHistory;
    }

    /** True when this identity currently holds ARCHIVE_ADMIN. */
    public boolean isAdministrator(InstitutionalIdentifier identity) {
        return identity != null && adminRoles.findActive(identity).isPresent();
    }

    /** Active administrators. Readable by an administrator only. */
    public List<AdminRoleMember> listAdministrators(InstitutionalIdentifier actor) {
        requireAdministrator(actor);
        return adminRoles.findAllActive();
    }

    /**
     * Assign ARCHIVE_ADMIN to another identity.
     *
     * <p><strong>Refused until the approval workflow exists</strong> (decision
     * D1, A-category design section 3.2). Appointing an administrator needs a
     * second, distinct administrator's approval, and one administrator acting
     * alone is exactly what that rule exists to stop. The refusal is here, in
     * the service, so the HTTP API and every operator utility are gated alike.
     * The first administrator still arrives through the operator bootstrap
     * ({@link #bootstrapFirstAdministratorWithIdentity}), which is separate.
     *
     * @throws ApprovalRequiredException always, once the caller and reason are valid
     */
    @Transactional
    public AdminRoleMember assignAdministrator(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        Reasons.require(reason);

        throw new ApprovalRequiredException(
                "appointing an ARCHIVE_ADMIN requires independent approval by a second, "
                        + "distinct administrator, and the approval workflow is not available "
                        + "yet. Nothing was written.");
    }

    /**
     * Revoke ARCHIVE_ADMIN.
     *
     * <p>Refused when it would leave no active administrator. The guard is
     * applied by the repository under a row lock, so two concurrent revokes of
     * the final two administrators serialise and exactly one is refused - see
     * {@link AdminRoleRepository}'s locking contract.
     *
     * <p>Self-revocation is permitted, subject to the same guard; an
     * administrator stepping down is normal, emptying the role is not.
     */
    @Transactional
    public void revokeAdministrator(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);

        adminRoles.revokeWithLastAdministratorGuard(subject, actor.value(), why);
        audit.record(actor.value(), AdminAuditAction.ADMIN_ROLE_REVOKED, subject, why);
    }

    /**
     * Create the single controlled first administrator.
     *
     * <p>Not reachable through the application's own API surface: it is invoked
     * by an operator-run path that records who ran it, because the first
     * administrator cannot be granted by an existing administrator. Refuses
     * when any active administrator already exists, so it cannot be replayed to
     * inject one later.
     *
     * <p><strong>Phase 1: not invoked.</strong> No real administrator is
     * bootstrapped in this phase.
     */
    /**
     * @deprecated Creates the administrator WITHOUT linking a Cognito
     *     identity, which on its own produces an administrator nobody can
     *     act as. Pairing it with a separate {@link #linkIdentity} call is
     *     what caused the original defect: two transactions, so a failure
     *     in the second committed the first and stranded the administrator
     *     with no route back - the utility refuses to re-run, the admin API
     *     404s while enforcement is off, and enabling enforcement does not
     *     help because no token resolves to an unlinked identity.
     *
     *     <p>Use {@link #bootstrapFirstAdministratorWithIdentity} instead;
     *     it does both in one transaction.
     *
     *     <p>Retained only so the regression test can drive the old
     *     sequence and prove it still strands an administrator. Nothing in
     *     {@code src/main} calls it, and nothing new should.
     */
    @Deprecated(since = "2026-10-29", forRemoval = false)
    @Transactional
    public AdminRoleMember bootstrapFirstAdministrator(
            InstitutionalIdentifier subject, String operator, String reason) {

        return bootstrapInTransaction(subject, operator, reason);
    }

    /**
     * Bootstrap the first administrator AND link the Cognito identity that
     * will act as them, in ONE transaction.
     *
     * <p>This exists because doing it in two was a recovery trap. The
     * bootstrap utility used to call {@link #bootstrapFirstAdministrator}
     * and then {@link #linkIdentity} - two {@code @Transactional} methods,
     * so two transactions. If the second failed, DEV was left with an
     * administrator that nobody could ever act as:
     *
     * <ul>
     *   <li>re-running the utility is refused, because an ACTIVE
     *       administrator already exists;</li>
     *   <li>the administrative API 404s while enforcement is off, so there
     *       is no HTTP route to add the missing link;</li>
     *   <li>turning enforcement on does not help either - with no link, no
     *       token resolves to that archive identity, so there is still
     *       nobody who can use the API to repair it.</li>
     * </ul>
     *
     * <p>The only way out was a hand-written UPDATE against the database as
     * its owner. The fix is to make the half-state impossible rather than to
     * document how to escape it.
     *
     * <p>Both writes and both audit rows commit together or not at all, so a
     * failure leaves nothing behind and the utility can simply be run again.
     *
     * <p>Note the two helpers are called directly rather than through
     * {@code this.linkIdentity(...)}: a self-invocation would bypass the
     * proxy and silently ignore the inner {@code @Transactional}, which
     * happens to give the right behaviour here for the wrong reason. Calling
     * the un-annotated internals makes the single transaction explicit.
     */
    @Transactional
    public BootstrapResult bootstrapFirstAdministratorWithIdentity(
            InstitutionalIdentifier subject, String operator, String reason,
            String cognitoIssuer, String cognitoSubject,
            KualiPersonId principal, String loginName) {

        AdminRoleMember first = bootstrapInTransaction(subject, operator, reason);
        /*
         * The actor is the administrator created a moment ago, in this same
         * transaction - requireAdministrator therefore sees the row and
         * passes. Outside the transaction it would not exist yet.
         */
        IdentityLink link = linkInTransaction(
                subject, cognitoIssuer, cognitoSubject, subject, principal, loginName,
                "bootstrap by operator=" + operator);
        return new BootstrapResult(first, link);
    }

    /** Membership plus its audit row. Caller supplies the transaction. */
    private AdminRoleMember bootstrapInTransaction(
            InstitutionalIdentifier subject, String operator, String reason) {

        requireSubject(subject);
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException(
                    "bootstrap requires the operator identity, so the chain of custody "
                            + "starts at a recorded event rather than an unexplained row");
        }

        AdminRoleMember first = adminRoles.bootstrap(subject, operator, reason);
        audit.record(AdminRoleMember.BOOTSTRAP_ACTOR,
                AdminAuditAction.ADMIN_ROLE_BOOTSTRAPPED, subject,
                "operator=" + operator + (reason == null ? "" : "; " + reason));
        return first;
    }

    /** Identity link plus its audit row. Caller supplies the transaction. */
    private IdentityLink linkInTransaction(
            InstitutionalIdentifier actor, String cognitoIssuer, String cognitoSubject,
            InstitutionalIdentifier subject, KualiPersonId principal, String loginName,
            String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        if (cognitoIssuer == null || cognitoIssuer.isBlank()
                || cognitoSubject == null || cognitoSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "the Cognito issuer and subject identify who signed in; both are required");
        }

        IdentityLink link = identities.link(
                cognitoIssuer, cognitoSubject, subject, principal, loginName, actor.value());
        audit.record(actor.value(), AdminAuditAction.IDENTITY_LINKED, subject,
                "link=" + link.id() + (reason == null ? "" : "; " + reason));
        return link;
    }

    /** What one atomic bootstrap produced. */
    public record BootstrapResult(AdminRoleMember member, IdentityLink link) { }

    /**
     * Record that an administrator changed a data-access grant.
     *
     * <p>The grant write itself belongs to the grant repository; this records
     * the administrative act. Callers must invoke it inside the same
     * transaction as the grant change so the two commit together.
     */
    @Transactional
    public void recordGrantChange(
            InstitutionalIdentifier actor, AdminAuditAction action,
            InstitutionalIdentifier subject, String detail) {

        requireAdministrator(actor);
        if (action != AdminAuditAction.ACCESS_GRANTED
                && action != AdminAuditAction.ACCESS_MODIFIED
                && action != AdminAuditAction.ACCESS_REVOKED) {
            throw new IllegalArgumentException(
                    "recordGrantChange is for ACCESS_* actions; got " + action);
        }
        audit.record(actor.value(), action, subject, detail);
    }

    // ---------------------------------------------------------------------
    // Data-access grants. Administration is NOT data access: nothing below
    // grants the ACTOR anything. An administrator who wants archive access
    // must be granted it explicitly, like anyone else, and that grant is
    // audited like any other.
    // ---------------------------------------------------------------------

    /**
     * The index V087 added. Named explicitly so an unrelated unique violation
     * is never reported as a duplicate grant.
     */
    private static final String ACTIVE_GRANT_SCOPE_INDEX = "ux_access_grant_active_scope";

    /**
     * True only for a unique violation naming {@code ux_access_grant_active_scope}.
     *
     * <p>Both conditions are required. SQLSTATE 23505 alone would also match
     * {@code ux_admin_role_member_active}, {@code ux_identity_link_active_cognito},
     * {@code ux_identity_link_active_identity} and {@code ux_relink_approval_live},
     * none of which means "this grant already exists".
     *
     * <p>The constraint name is read from the message rather than from
     * {@code PSQLException.getServerErrorMessage().getConstraint()} because the
     * PostgreSQL driver is a runtime-scope dependency and is deliberately not
     * on the compile classpath. If PostgreSQL ever changed that message, this
     * returns false and the original exception propagates - a 500 rather than
     * a wrong 409, which is the safe direction to fail in.
     */
    private static boolean violatesActiveGrantScope(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(ACTIVE_GRANT_SCOPE_INDEX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Grant archive access.
     *
     * <p>Approved decision D-ADM-2: no second approver is required, so this
     * commits on one administrator's action - except where the A-category
     * controls say otherwise. Refused, before anything is written:
     * <ul>
     *   <li>a grant to the actor themselves ({@link SelfAdministrationException});</li>
     *   <li>any CENTRAL grant (decision D1, {@link ApprovalRequiredException}),
     *       until the approval workflow exists;</li>
     *   <li>a missing or placeholder reason ({@link ReasonRequiredException}).</li>
     * </ul>
     * D-ADM-3: the grant does not expire unless
     * {@link GrantSpecification#expiresAt()} says so.
     */
    @Transactional
    public AccessGrant grantAccess(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject,
            GrantSpecification specification) {

        requireAdministrator(actor);
        requireSubject(subject);
        if (specification == null) {
            throw new IllegalArgumentException("grant specification is required");
        }
        String why = Reasons.require(specification.reason());
        refuseSelfGrant(actor, subject);
        refuseCentralWithoutApproval(specification);
        specification = withReason(specification, why);

        /*
         * V087's ux_access_grant_active_scope is what actually holds "at most
         * one unrevoked grant per grantee and scope". It is translated here,
         * once, so the HTTP API and the provisioning CLI agree on what a
         * duplicate is rather than each deciding separately.
         *
         * The audit row is written AFTER the insert on purpose: if the index
         * refuses, audit.record is never reached, so a refusal cannot leave an
         * ACCESS_GRANTED row behind. The surrounding @Transactional would roll
         * one back anyway - this just means there is nothing to roll back.
         */
        AccessGrant granted;
        try {
            granted = grants.create(subject, specification, actor.value());
        } catch (DuplicateKeyException e) {
            if (!violatesActiveGrantScope(e)) {
                /*
                 * Some other unique index. Not ours to reinterpret: turning
                 * an unrelated integrity failure into 409 would tell the
                 * caller to retry something that will never succeed.
                 */
                throw e;
            }
            throw new DuplicateActiveGrantException(
                    "an unrevoked grant for this grantee and scope already exists. "
                            + "Revoke it before granting the same scope again; two identical "
                            + "active grants cannot be told apart when one is later revoked.",
                    e);
        }

        audit.record(actor.value(), AdminAuditAction.ACCESS_GRANTED, subject,
                describe(granted, specification.reason()));
        return granted;
    }

    /**
     * SEC-002: grant the SAME scope to many grantees in one reviewed action.
     *
     * <p>Built on {@link #grantAccess}, not beside it. Every grantee goes
     * through the identical path - the same administrator check, the same
     * specification validation, the same V087 duplicate translation, the same
     * audit row - so a bulk assignment cannot acquire looser rules than the
     * single one, which is the usual way a bulk endpoint becomes the weak
     * door.
     *
     * <h2>Per-grantee outcomes, not all-or-nothing</h2>
     *
     * <p>A realistic department rollout names dozens of people and some of
     * them already hold the grant. Failing the whole batch for that would
     * make the operation unusable and push operators towards doing it one at
     * a time with no batch record at all. So each grantee gets its own
     * outcome and the batch reports them.
     *
     * <p>This means the method is NOT atomic across grantees, and that is
     * deliberate - but it is atomic FOR each grantee: a grant and its audit
     * row commit together or not at all, because {@code grantAccess} is
     * {@code @Transactional} and this method is not. Marking this
     * {@code @Transactional} would silently convert every per-grantee failure
     * into a rollback of the whole batch and make the reported outcomes a
     * lie.
     *
     * <h2>The batch reference</h2>
     *
     * <p>Every audit row in the batch carries the same {@code batchRef}, so
     * "what did that rollout actually do" is answerable afterwards, and a
     * summary row records the totals. No migration is needed for either:
     * {@code access_audit.action} is {@code VARCHAR(40)} with no CHECK and
     * the detail column is JSONB.
     *
     * @param subjects grantees, de-duplicated; order is preserved for the report
     * @return one outcome per distinct grantee, in the order supplied
     */
    public BulkGrantReport grantAccessInBulk(
            InstitutionalIdentifier actor, List<InstitutionalIdentifier> subjects,
            GrantSpecification specification) {

        requireAdministrator(actor);
        if (subjects == null || subjects.isEmpty()) {
            throw new IllegalArgumentException("at least one grantee is required");
        }
        if (specification == null) {
            throw new IllegalArgumentException("grant specification is required");
        }
        /*
         * Checked once, up front, so a batch that every grantee would fail for
         * the same reason writes nothing at all - not even the summary row.
         * grantAccess repeats both checks per grantee; that is the control,
         * this is only the early answer. A self-grant is the one refusal that
         * depends on the grantee, so it stays per-grantee and becomes a
         * FAILED outcome.
         */
        Reasons.require(specification.reason());
        refuseCentralWithoutApproval(specification);

        /*
         * De-duplicated before anything is written. The same identifier twice
         * in one request is an operator slip (a pasted list), and letting it
         * through would make the second one a self-inflicted V087 duplicate
         * reported as a failure.
         */
        List<InstitutionalIdentifier> distinct = new java.util.ArrayList<>(
                new java.util.LinkedHashSet<>(subjects));

        String batchRef = java.util.UUID.randomUUID().toString();
        List<BulkGrantOutcome> outcomes = new java.util.ArrayList<>();
        for (InstitutionalIdentifier subject : distinct) {
            try {
                AccessGrant granted = grantAccess(actor, subject, specification);
                outcomes.add(BulkGrantOutcome.granted(subject, granted.id(), batchRef));
            } catch (DuplicateActiveGrantException duplicate) {
                // Already holds this scope. Not a failure of the rollout.
                outcomes.add(BulkGrantOutcome.alreadyHeld(subject, batchRef));
            } catch (RuntimeException failure) {
                outcomes.add(BulkGrantOutcome.failed(subject, batchRef, failure.getMessage()));
            }
        }

        long granted = outcomes.stream().filter(o -> o.status() == BulkGrantStatus.GRANTED).count();
        long alreadyHeld = outcomes.stream().filter(o -> o.status() == BulkGrantStatus.ALREADY_HELD).count();
        long failed = outcomes.stream().filter(o -> o.status() == BulkGrantStatus.FAILED).count();

        if (failed == outcomes.size()) {
            /*
             * Every grantee was refused (for example a batch whose only entry
             * is the actor). Nothing changed, so nothing is audited: a
             * refusal writes no audit row, for a batch as for a single grant.
             * The report still tells the operator why each entry failed.
             */
            return new BulkGrantReport(batchRef, outcomes);
        }
        audit.record(actor.value(), AdminAuditAction.ACCESS_GRANTED, null,
                String.format("{\"batchRef\":\"%s\",\"requested\":%d,\"granted\":%d,"
                                + "\"alreadyHeld\":%d,\"failed\":%d}",
                        batchRef, distinct.size(), granted, alreadyHeld, failed));

        return new BulkGrantReport(batchRef, outcomes);
    }

    public enum BulkGrantStatus { GRANTED, ALREADY_HELD, FAILED }

    /** What happened for one grantee in a bulk assignment. */
    public record BulkGrantOutcome(
            InstitutionalIdentifier subject,
            BulkGrantStatus status,
            Long grantId,
            String batchRef,
            String message) {

        static BulkGrantOutcome granted(InstitutionalIdentifier subject, long grantId, String batchRef) {
            return new BulkGrantOutcome(subject, BulkGrantStatus.GRANTED, grantId, batchRef, null);
        }

        static BulkGrantOutcome alreadyHeld(InstitutionalIdentifier subject, String batchRef) {
            return new BulkGrantOutcome(subject, BulkGrantStatus.ALREADY_HELD, null, batchRef,
                    "an unrevoked grant for this scope already exists");
        }

        static BulkGrantOutcome failed(InstitutionalIdentifier subject, String batchRef, String message) {
            return new BulkGrantOutcome(subject, BulkGrantStatus.FAILED, null, batchRef, message);
        }
    }

    /** The batch as a whole, for the operator and for the audit trail. */
    public record BulkGrantReport(String batchRef, List<BulkGrantOutcome> outcomes) {

        public BulkGrantReport {
            outcomes = List.copyOf(outcomes);
        }

        public long granted() {
            return outcomes.stream().filter(o -> o.status() == BulkGrantStatus.GRANTED).count();
        }

        public long alreadyHeld() {
            return outcomes.stream().filter(o -> o.status() == BulkGrantStatus.ALREADY_HELD).count();
        }

        public long failed() {
            return outcomes.stream().filter(o -> o.status() == BulkGrantStatus.FAILED).count();
        }
    }

    /**
     * Change a grant's expiry, or whether a UNIT grant includes sub-units.
     *
     * <ul>
     *   <li><strong>Bound to the subject</strong> (finding N1): the grant must
     *       belong to {@code subject}, the identity named in the request
     *       path. A grant that exists but belongs to someone else is reported
     *       exactly as a missing one, so grant ids cannot be probed across
     *       identities.</li>
     *   <li><strong>Locked</strong> (finding N5): the grant row is read with
     *       {@code SELECT ... FOR UPDATE} in this transaction, so whether the
     *       change widens the grant is judged against the committed value,
     *       never one another transaction is changing.</li>
     *   <li><strong>Widening your own grant is refused</strong>: sub-units
     *       {@code false -> true}, a later expiry, or removing the expiry.
     *       Narrowing your own grant is allowed. Widening someone else's is
     *       allowed (decision D-ADM-2), with a reason, and the audit row
     *       records the before and after values.</li>
     *   <li><strong>An expiry in the past is refused</strong>: ending access
     *       now is what revocation is for.</li>
     * </ul>
     */
    @Transactional
    public AccessGrant modifyAccess(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, long grantId,
            Instant expiresAt, boolean includeDescendants, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException(
                    "the new expiry is not in the future; to end access now, revoke the grant");
        }

        AccessGrant before = grants.lockActive(grantId)
                .filter(grant -> grant.grantee().equals(subject))
                .orElseThrow(() -> new NoSuchElementException("no active grant " + grantId));

        if (actor.equals(subject) && widens(before, expiresAt, includeDescendants)) {
            throw new SelfAdministrationException(
                    "an administrator cannot widen their own grant (sub-units added, expiry "
                            + "extended or removed); a second administrator must make this "
                            + "change. Narrowing your own grant is allowed. Nothing was written.");
        }

        AccessGrant modified = grants.modify(grantId, expiresAt, includeDescendants);
        audit.record(actor.value(), AdminAuditAction.ACCESS_MODIFIED, modified.grantee(),
                describe(modified, null)
                        + "; before=" + state(before.includeDescendants(), before.expiresAt())
                        + "; after=" + state(modified.includeDescendants(), modified.expiresAt())
                        + "; " + why);
        return modified;
    }

    /**
     * True when the change gives the grantee more than they hold now:
     * sub-units {@code false -> true}, a later expiry, or any expiry removed.
     */
    public static boolean widens(AccessGrant current, Instant newExpiresAt,
                                 boolean newIncludeDescendants) {
        if (newIncludeDescendants && !current.includeDescendants()) {
            return true;
        }
        if (newExpiresAt == null) {
            return current.expiresAt() != null;
        }
        return current.expiresAt() != null && newExpiresAt.isAfter(current.expiresAt());
    }

    private static String state(boolean includeDescendants, Instant expiresAt) {
        return "{includeDescendants=" + includeDescendants
                + ", expires=" + (expiresAt == null ? "never" : expiresAt.toString()) + "}";
    }

    /**
     * Revoke a grant.
     *
     * <p>Effective on the caller's next request: authorization is resolved per
     * request from this store, never from a Cognito group claim or a cache, so
     * the revoked user loses access immediately even though their JWT stays
     * valid for up to an hour.
     *
     * <p>Self-revocation is allowed: reducing your own access is not escalation.
     */
    @Transactional
    public void revokeAccess(InstitutionalIdentifier actor, InstitutionalIdentifier subject,
                             long grantId, String reason) {
        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);
        /*
         * Bound to the subject (finding N1). Another identity's grant answers
         * exactly as a missing one, so ids cannot be probed.
         */
        AccessGrant existing = grants.findById(grantId)
                .filter(grant -> grant.grantee().equals(subject))
                .orElseThrow(() -> new NoSuchElementException("no grant " + grantId));

        // The grant's own reason is left alone (N3); the revocation reason is audited.
        grants.revoke(grantId, actor.value());
        audit.record(actor.value(), AdminAuditAction.ACCESS_REVOKED, existing.grantee(),
                describe(existing, why));
    }

    // ---------------------------------------------------------------------
    // Identity links and suspension
    // ---------------------------------------------------------------------

    /**
     * Link a BU-authenticated Cognito identity to an institutional identity
     * and, when known, an archive principal.
     *
     * <p>Approved decision D-ID-1: the principal's historical KRIM
     * {@code ACTV_IND} is not consulted. Most people on archived records are
     * inactive in KRIM, and that must not stop an administrator verifying a
     * legitimate user.
     *
     * <p>Refused with {@link ApprovalRequiredException} for an identity that
     * already holds active grants or ARCHIVE_ADMIN and has no active link
     * (finding N2).
     */
    @Transactional
    public IdentityLink linkIdentity(
            InstitutionalIdentifier actor, String cognitoIssuer, String cognitoSubject,
            InstitutionalIdentifier subject, KualiPersonId principal, String loginName,
            String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);
        refusePrivilegedLinkWithoutApproval(subject);
        return linkInTransaction(
                actor, cognitoIssuer, cognitoSubject, subject, principal, loginName, why);
    }

    /**
     * Finding N2: unlink-then-link must not bypass the V086 two-person rule.
     *
     * <p>{@link #relinkIdentity} needs a second administrator when the
     * identity holds active grants. Without this check one administrator
     * could unlink, then link a new Cognito subject here, and the grants (or
     * ARCHIVE_ADMIN) would follow the identity to whoever signs in as it.
     * So an identity with no active link that holds active grants or an
     * active ARCHIVE_ADMIN membership cannot be linked by one administrator.
     * Until the approval workflow exists that is a refusal; relink keeps its
     * V086 path unchanged.
     *
     * <p>It also covers an administrator linking a second Cognito identity
     * to themselves, because the actor always holds ARCHIVE_ADMIN.
     */
    private void refusePrivilegedLinkWithoutApproval(InstitutionalIdentifier subject) {
        boolean hasActiveLink = identities.findLinksFor(subject).stream()
                .anyMatch(IdentityLink::isActive);
        if (hasActiveLink) {
            // The repository refuses a second active link with its own message.
            return;
        }
        if (identities.activeGrantCountFor(subject) > 0
                || adminRoles.findActive(subject).isPresent()) {
            throw new ApprovalRequiredException(
                    "this archive identity holds active grants or ARCHIVE_ADMIN, which a new "
                            + "Cognito identity would inherit. Linking it needs a second "
                            + "administrator's approval: use the re-link path. Nothing was "
                            + "written.");
        }
    }

    /**
     * Re-link an archive identity to a NEW Cognito identity for the SAME
     * person - a replaced user pool, or re-created federation.
     *
     * <p>Deliberately not reachable through {@link #linkIdentity}. The grants
     * hang off the archive identity, so re-linking hands them to whoever the
     * new Cognito subject belongs to; if that is a different person, it is a
     * silent transfer of someone else's access. No constraint can tell the
     * two apart, so this is a separate act an administrator must choose, and
     * the audit row records how many grants moved with it.
     */
    @Transactional
    public IdentityLink relinkIdentity(
            InstitutionalIdentifier actor, String cognitoIssuer, String cognitoSubject,
            InstitutionalIdentifier subject, KualiPersonId principal, String loginName,
            String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        if (cognitoIssuer == null || cognitoIssuer.isBlank()
                || cognitoSubject == null || cognitoSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "the Cognito issuer and subject identify who signed in; both are required");
        }
        // Re-linking transfers whatever grants this identity holds; the audit
        // trail must say why.
        reason = Reasons.require(reason);
        refusePrincipalChangeOnRelink(subject, principal);

        /*
         * RE-CHECKED here, not taken from the request. Grants can be issued
         * or revoked between asking and executing, and the two-person rule
         * must reflect what is actually about to move - including the case
         * where an identity had none when the request was raised and has
         * some now.
         */
        int inherited = identities.activeGrantCountFor(subject);
        /*
         * QA finding N-161-1: ARCHIVE_ADMIN follows the archive identity just
         * as grants do. Without this, one administrator could unlink another
         * administrator and relink that identity to any Cognito user, making
         * them an administrator with no second approval.
         */
        boolean administrator = adminRoles.findActive(subject).isPresent();
        Long consumedApproval = null;

        if (inherited > 0 || administrator) {
            if (relinkApprovals == null) {
                throw new IllegalStateException(
                        "this archive identity holds active grants or ARCHIVE_ADMIN, so a "
                                + "second ARCHIVE_ADMIN must approve the relink, and no "
                                + "approval store is configured");
            }
            RelinkApproval approval = relinkApprovals
                    .findLive(subject, cognitoIssuer.trim(), cognitoSubject.trim())
                    .orElseThrow(() -> new RelinkApprovalRequiredException(
                            "this archive identity holds active grants or ARCHIVE_ADMIN, which "
                                    + "the new Cognito identity would inherit. A second, distinct "
                                    + "ARCHIVE_ADMIN must approve this exact identity and target "
                                    + "first."));

            if (!approval.isUsable()) {
                throw new RelinkApprovalRequiredException(
                        "the relink approval for this target is " + approval.status()
                                + ", not APPROVED; a revoked or unapproved request authorises "
                                + "nothing");
            }
            // Belt and braces: findLive already matched all three, and the
            // approval itself re-asserts the binding.
            if (!approval.covers(subject, cognitoIssuer.trim(), cognitoSubject.trim())) {
                throw new RelinkApprovalRequiredException(
                        "the approval does not name this identity and target");
            }
            /*
             * No check that the executor differs from the approver. The
             * two-person rule is about the DECISION, and it is already held
             * where it cannot be bypassed: ck_relink_approval_two_people
             * makes approved_by <> requested_by a database constraint. Who
             * runs the resulting relink adds nothing - they can only do what
             * two administrators already agreed to, against this exact
             * identity and target.
             */
            if (!relinkApprovals.consume(approval.approvalId())) {
                // Another relink took it first, or it was revoked between the
                // read and the consume.
                throw new RelinkApprovalRequiredException(
                        "the relink approval was already used or withdrawn; request a new one");
            }
            consumedApproval = approval.approvalId();
        }

        IdentityLink link = identities.relink(
                cognitoIssuer.trim(), cognitoSubject.trim(), subject, principal,
                loginName, actor.value());
        audit.record(actor.value(), AdminAuditAction.IDENTITY_RELINKED, subject,
                "link=" + link.id() + "; grants inherited by the new Cognito identity="
                        + inherited
                        + (administrator ? "; ARCHIVE_ADMIN inherited" : "")
                        + (consumedApproval == null
                                ? "; no second approval required (no active grants or ARCHIVE_ADMIN)"
                                : "; approval=" + consumedApproval)
                        + "; " + reason.trim());
        return link;
    }

    /**
     * Ask a second administrator to approve re-linking an identity that holds
     * active grants. Bound to this exact identity and this exact target.
     */
    @Transactional
    public RelinkApproval requestRelinkApproval(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject,
            String cognitoIssuer, String cognitoSubject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);
        reason = Reasons.require(reason);
        requireApprovalStore();

        int grants = identities.activeGrantCountFor(subject);
        RelinkApproval requested = relinkApprovals.request(
                subject, cognitoIssuer.trim(), cognitoSubject.trim(), actor.value(),
                grants, reason.trim());
        audit.record(actor.value(), AdminAuditAction.RELINK_APPROVAL_REQUESTED, subject,
                "approval=" + requested.approvalId() + "; grants at request=" + grants
                        + "; " + reason.trim());
        return requested;
    }

    /**
     * Approve a pending relink request.
     *
     * <p>The approver must be a different ARCHIVE_ADMIN from the requester,
     * and must not be the person whose Cognito identity is being linked -
     * otherwise the two-person rule would be satisfied by one person acting
     * on their own behalf.
     */
    @Transactional
    public RelinkApproval approveRelink(InstitutionalIdentifier actor, long approvalId,
                                        String reason) {
        requireAdministrator(actor);
        String why = Reasons.require(reason);
        requireApprovalStore();

        RelinkApproval approval = relinkApprovals.findById(approvalId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "no relink approval " + approvalId));

        if (approval.requestedBy().equals(actor.value())) {
            throw new IllegalStateException(
                    "the administrator who requested this relink cannot also approve it; "
                            + "a second, distinct ARCHIVE_ADMIN must");
        }
        /*
         * And the approver must not be the target. If the proposed Cognito
         * identity is the approver's own, approving it would hand themselves
         * the identity's grants - one person on both sides of the decision.
         */
        identities.findLinksFor(actor).stream()
                .filter(IdentityLink::isActive)
                .filter(link -> link.cognitoIdentity().issuer().equals(approval.proposedCognitoIssuer())
                        && link.cognitoIdentity().subject().equals(approval.proposedCognitoSubject()))
                .findAny()
                .ifPresent(self -> {
                    throw new IllegalStateException(
                            "the approver cannot be the Cognito identity being linked; that "
                                    + "would put one person on both sides of the decision");
                });

        RelinkApproval approved = relinkApprovals.approve(approvalId, actor.value());
        audit.record(actor.value(), AdminAuditAction.RELINK_APPROVAL_GRANTED, approval.identity(),
                "approval=" + approvalId + "; requested by " + approval.requestedBy()
                        + "; " + why);
        return approved;
    }

    /** Withdraw a relink request or approval. A revoked one authorises nothing. */
    @Transactional
    public void revokeRelinkApproval(InstitutionalIdentifier actor, long approvalId,
                                     String reason) {
        requireAdministrator(actor);
        String why = Reasons.require(reason);
        requireApprovalStore();

        RelinkApproval approval = relinkApprovals.findById(approvalId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "no relink approval " + approvalId));
        relinkApprovals.revoke(approvalId, actor.value());
        audit.record(actor.value(), AdminAuditAction.RELINK_APPROVAL_REVOKED,
                approval.identity(), "approval=" + approvalId + "; " + why);
    }

    private void requireApprovalStore() {
        if (relinkApprovals == null) {
            throw new IllegalStateException("no relink approval store is configured");
        }
    }

    /** Active grants currently attached to an archive identity. */
    public int activeGrantCountFor(InstitutionalIdentifier actor, InstitutionalIdentifier subject) {
        requireAdministrator(actor);
        return identities.activeGrantCountFor(subject);
    }

    /**
     * Revoke an identity link. The person can no longer resolve to anyone.
     *
     * <p>Bound to the subject (finding N1): a link that belongs to a
     * different identity is reported exactly as a missing one.
     */
    @Transactional
    public void unlinkIdentity(InstitutionalIdentifier actor, InstitutionalIdentifier subject,
                               long linkId, String reason) {
        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);
        if (actor.equals(subject)) {
            // Like self-suspension: an administrator who unlinks themselves
            // can no longer sign in as anyone, and cannot undo it.
            throw new SelfAdministrationException(
                    "an administrator cannot unlink their own identity; they would be locked "
                            + "out. A second administrator must do this. Nothing was written.");
        }
        identities.revokeLink(subject, linkId, actor.value());
        audit.record(actor.value(), AdminAuditAction.IDENTITY_UNLINKED, subject,
                "link=" + linkId + "; " + why);
    }

    /**
     * Suspend or restore archive access.
     *
     * <p>Suspension overrides every grant without touching any of them, so
     * restoring is one action rather than re-granting. Use it for "stop this
     * person now, decide about their grants later".
     *
     * <p>Refused, before anything is written:
     * <ul>
     *   <li>suspending or restoring yourself ({@link SelfAdministrationException}).
     *       A suspended administrator no longer resolves, so self-suspension
     *       is a lockout - and for the last administrator one the
     *       application cannot undo;</li>
     *   <li>suspending or restoring an identity that holds an active
     *       ARCHIVE_ADMIN membership ({@link ApprovalRequiredException}).
     *       Restoring would make its administrator privileges usable again
     *       (decision D3); suspending could then not be undone. Suspending
     *       or restoring anyone else stays a single-administrator action.</li>
     * </ul>
     */
    @Transactional
    public void setSuspended(InstitutionalIdentifier actor, InstitutionalIdentifier subject,
                             boolean suspended, String reason) {
        requireAdministrator(actor);
        requireSubject(subject);
        String why = Reasons.require(reason);
        if (actor.equals(subject)) {
            throw new SelfAdministrationException(
                    "an administrator cannot change their own suspension; a suspended "
                            + "administrator cannot sign in to undo it. Nothing was written.");
        }
        if (adminRoles.findActive(subject).isPresent()) {
            /*
             * Restore: decision D3. Suspend: refused too, because restoring
             * an administrator needs approval, so one administrator
             * suspending another would be irreversible in the application
             * until the approval workflow exists.
             */
            throw new ApprovalRequiredException(
                    (suspended
                            ? "suspending an ARCHIVE_ADMIN could not be undone without a second "
                                    + "administrator's approval"
                            : "restoring this identity would make its ARCHIVE_ADMIN privileges "
                                    + "usable again, which requires a second administrator's "
                                    + "approval")
                            + ". The approval workflow is not available yet. Nothing was "
                            + "written.");
        }

        identities.setSuspended(subject, suspended, actor.value(), why);
        audit.record(actor.value(),
                suspended ? AdminAuditAction.IDENTITY_SUSPENDED : AdminAuditAction.IDENTITY_RESTORED,
                subject, why);
    }

    // ---------------------------------------------------------------------
    // Review
    // ---------------------------------------------------------------------

    /** Everything about one identity, for the administrative workspace. */
    public IdentityOverview describeIdentity(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject) {

        requireAdministrator(actor);
        requireSubject(subject);

        return new IdentityOverview(
                subject,
                identities.findLinksFor(subject),
                grants.findForGrantee(subject),
                identities.isSuspended(subject),
                isAdministrator(subject),
                auditHistory.find(AuditQuery.forSubject(subject.value())));
    }

    /** Candidate identities matching free text. See {@link IdentitySearchResult}. */
    public List<IdentitySearchResult> searchIdentities(
            InstitutionalIdentifier actor, String query, int limit) {

        requireAdministrator(actor);
        return identities.search(query, limit);
    }

    /** Administrative audit history. Readable by an administrator only. */
    public List<AuditEntry> auditHistory(InstitutionalIdentifier actor, AuditQuery query) {
        requireAdministrator(actor);
        return auditHistory.find(query == null ? new AuditQuery(null, null, null, null, 0) : query);
    }

    /**
     * Describe a grant for the audit detail. Deliberately records what was
     * authorized rather than only the grant id, so the audit trail stays
     * readable after the grant row is revoked or modified.
     */
    private static String describe(AccessGrant grant, String reason) {
        StringBuilder detail = new StringBuilder("grant=").append(grant.id())
                .append("; type=").append(grant.type());
        if (grant.unitNumber() != null) {
            detail.append("; unit=").append(grant.unitNumber())
                    .append(grant.includeDescendants() ? " (+sub-units)" : "");
        }
        if (grant.ioValue() != null) {
            detail.append("; io=").append(grant.ioValue());
        }
        if (grant.expiresAt() != null) {
            detail.append("; expires=").append(grant.expiresAt());
        }
        if (reason != null && !reason.isBlank()) {
            detail.append("; ").append(reason.trim());
        }
        return detail.toString();
    }

    /**
     * QA finding N-161-2: a relink must not change the Kuali person id.
     *
     * <p>A V086 approval is bound to the identity and the proposed Cognito
     * issuer and subject, but {@code authz.relink_approval} has no column for
     * the principal, so it cannot bind one - and the principal is what
     * CONTACT_DERIVATION resolves through. Letting the executor choose it
     * would let them point an approved relink at someone else's records.
     *
     * <p>Adding the column is a migration, which this PR deliberately does
     * not contain. Until an approval can bind it, a relink keeps exactly the
     * principal of the identity's most recent link (null stays null).
     * Changing a principal is a separate act that needs its own approved
     * procedure. Relink exists for "same person, new Cognito subject", and
     * the same person has the same principal.
     */
    private void refusePrincipalChangeOnRelink(InstitutionalIdentifier subject,
                                               KualiPersonId principal) {
        String previous = identities.findLinksFor(subject).stream()
                .max(java.util.Comparator.comparingLong(IdentityLink::id))
                .flatMap(IdentityLink::kualiPersonId)
                .map(KualiPersonId::value)
                .orElse(null);
        String proposed = principal == null ? null : principal.value();
        if (!java.util.Objects.equals(previous, proposed)) {
            throw new ApprovalRequiredException(
                    "a relink cannot change the Kuali person id: the relink approval does not "
                            + "bind it, and contact-derived access resolves through it. Relink "
                            + "with the identity's existing principal; changing the principal "
                            + "needs a separate approved procedure. Nothing was written.");
        }
    }

    /** Control 1: no self-granting. Bulk grants inherit it per grantee. */
    private static void refuseSelfGrant(InstitutionalIdentifier actor,
                                        InstitutionalIdentifier subject) {
        if (actor.equals(subject)) {
            throw new SelfAdministrationException(
                    "an administrator cannot grant archive access to themselves; a second "
                            + "administrator must. Nothing was written.");
        }
    }

    /** Decision D1: every CENTRAL grant needs independent approval. */
    private static void refuseCentralWithoutApproval(GrantSpecification specification) {
        if (specification.type() == GrantType.CENTRAL) {
            throw new ApprovalRequiredException(
                    "a CENTRAL grant (every record, every unit) requires independent approval "
                            + "by a second administrator, and the approval workflow is not "
                            + "available yet. Nothing was written.");
        }
    }

    private static GrantSpecification withReason(GrantSpecification specification, String reason) {
        return new GrantSpecification(specification.type(), specification.unitNumber(),
                specification.includeDescendants(), specification.ioValue(),
                specification.awardNumber(), specification.expiresAt(), reason);
    }

    private void requireAdministrator(InstitutionalIdentifier actor) {
        if (!isAdministrator(actor)) {
            throw new NotAnArchiveAdministratorException(
                    "caller does not hold an active ARCHIVE_ADMIN membership");
        }
    }

    private static void requireSubject(InstitutionalIdentifier subject) {
        if (subject == null || subject.value() == null || subject.value().isBlank()) {
            throw new IllegalArgumentException("subject identity is required");
        }
    }
}
