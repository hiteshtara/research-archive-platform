package edu.bu.archive.application.authorization.admin;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;

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
     * <p>Only an existing active administrator may do this, so the role cannot
     * be self-granted. The first administrator arrives through
     * {@link #bootstrapFirstAdministrator} instead.
     */
    @Transactional
    public AdminRoleMember assignAdministrator(
            InstitutionalIdentifier actor, InstitutionalIdentifier subject, String reason) {

        requireAdministrator(actor);
        requireSubject(subject);

        AdminRoleMember assigned = adminRoles.assign(subject, actor.value(), reason);
        audit.record(actor.value(), AdminAuditAction.ADMIN_ROLE_ASSIGNED, subject, reason);
        return assigned;
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

        adminRoles.revokeWithLastAdministratorGuard(subject, actor.value(), reason);
        audit.record(actor.value(), AdminAuditAction.ADMIN_ROLE_REVOKED, subject, reason);
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
     * Grant archive access.
     *
     * <p>Approved decision D-ADM-2: no second approver is required, so this
     * commits on one administrator's action. D-ADM-3: the grant does not
     * expire unless {@link GrantSpecification#expiresAt()} says so.
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

        AccessGrant granted = grants.create(subject, specification, actor.value());
        audit.record(actor.value(), AdminAuditAction.ACCESS_GRANTED, subject,
                describe(granted, specification.reason()));
        return granted;
    }

    /** Change a grant's expiry, or whether a UNIT grant includes sub-units. */
    @Transactional
    public AccessGrant modifyAccess(
            InstitutionalIdentifier actor, long grantId, Instant expiresAt,
            boolean includeDescendants, String reason) {

        requireAdministrator(actor);
        AccessGrant modified = grants.modify(grantId, expiresAt, includeDescendants);
        audit.record(actor.value(), AdminAuditAction.ACCESS_MODIFIED, modified.grantee(),
                describe(modified, reason));
        return modified;
    }

    /**
     * Revoke a grant.
     *
     * <p>Effective on the caller's next request: authorization is resolved per
     * request from this store, never from a Cognito group claim or a cache, so
     * the revoked user loses access immediately even though their JWT stays
     * valid for up to an hour.
     */
    @Transactional
    public void revokeAccess(InstitutionalIdentifier actor, long grantId, String reason) {
        requireAdministrator(actor);
        AccessGrant existing = grants.findById(grantId)
                .orElseThrow(() -> new NoSuchElementException("no grant " + grantId));

        grants.revoke(grantId, actor.value(), reason);
        audit.record(actor.value(), AdminAuditAction.ACCESS_REVOKED, existing.grantee(),
                describe(existing, reason));
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
     */
    @Transactional
    public IdentityLink linkIdentity(
            InstitutionalIdentifier actor, String cognitoIssuer, String cognitoSubject,
            InstitutionalIdentifier subject, KualiPersonId principal, String loginName,
            String reason) {

        return linkInTransaction(
                actor, cognitoIssuer, cognitoSubject, subject, principal, loginName, reason);
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
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "re-linking transfers whatever grants this identity holds; a reason is "
                            + "required so the audit trail says why");
        }

        /*
         * RE-CHECKED here, not taken from the request. Grants can be issued
         * or revoked between asking and executing, and the two-person rule
         * must reflect what is actually about to move - including the case
         * where an identity had none when the request was raised and has
         * some now.
         */
        int inherited = identities.activeGrantCountFor(subject);
        Long consumedApproval = null;

        if (inherited > 0) {
            if (relinkApprovals == null) {
                throw new IllegalStateException(
                        "this archive identity holds " + inherited + " active grant(s), so a "
                                + "second ARCHIVE_ADMIN must approve the relink, and no "
                                + "approval store is configured");
            }
            RelinkApproval approval = relinkApprovals
                    .findLive(subject, cognitoIssuer.trim(), cognitoSubject.trim())
                    .orElseThrow(() -> new RelinkApprovalRequiredException(
                            "this archive identity holds active grants, which the new Cognito "
                                    + "identity would inherit. A second, distinct ARCHIVE_ADMIN "
                                    + "must approve this exact identity and target first."));

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
                        + (consumedApproval == null
                                ? "; no second approval required (no active grants)"
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
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
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
    public RelinkApproval approveRelink(InstitutionalIdentifier actor, long approvalId) {
        requireAdministrator(actor);
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
                "approval=" + approvalId + "; requested by " + approval.requestedBy());
        return approved;
    }

    /** Withdraw a relink request or approval. A revoked one authorises nothing. */
    @Transactional
    public void revokeRelinkApproval(InstitutionalIdentifier actor, long approvalId) {
        requireAdministrator(actor);
        requireApprovalStore();

        RelinkApproval approval = relinkApprovals.findById(approvalId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "no relink approval " + approvalId));
        relinkApprovals.revoke(approvalId, actor.value());
        audit.record(actor.value(), AdminAuditAction.RELINK_APPROVAL_REVOKED,
                approval.identity(), "approval=" + approvalId);
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

    /** Revoke an identity link. The person can no longer resolve to anyone. */
    @Transactional
    public void unlinkIdentity(InstitutionalIdentifier actor, long linkId, String reason) {
        requireAdministrator(actor);
        InstitutionalIdentifier subject = identities.revokeLink(linkId, actor.value());
        audit.record(actor.value(), AdminAuditAction.IDENTITY_UNLINKED, subject,
                "link=" + linkId + (reason == null ? "" : "; " + reason));
    }

    /**
     * Suspend or restore archive access.
     *
     * <p>Suspension overrides every grant without touching any of them, so
     * restoring is one action rather than re-granting. Use it for "stop this
     * person now, decide about their grants later".
     */
    @Transactional
    public void setSuspended(InstitutionalIdentifier actor, InstitutionalIdentifier subject,
                             boolean suspended, String reason) {
        requireAdministrator(actor);
        requireSubject(subject);

        identities.setSuspended(subject, suspended, actor.value(), reason);
        audit.record(actor.value(),
                suspended ? AdminAuditAction.IDENTITY_SUSPENDED : AdminAuditAction.IDENTITY_RESTORED,
                subject, reason);
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
