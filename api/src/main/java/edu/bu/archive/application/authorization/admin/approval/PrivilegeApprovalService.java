package edu.bu.archive.application.authorization.admin.approval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException.Refusal;

/**
 * Independent approval for privilege escalation (A-category design, section 3).
 *
 * <p><strong>Ordering</strong> (section 3.3), enforced at every step:
 * <ol>
 *   <li><b>Request</b> - any active administrator, with a reason, bound to
 *       the exact action, subject and specification.</li>
 *   <li><b>Approve</b> - a different active, unsuspended administrator who
 *       is neither the requester nor the subject, with their own reason,
 *       within 72 hours of the request.</li>
 *   <li><b>Execute</b> - the protected write calls
 *       {@link #requireConsumedApproval} inside its own transaction. The
 *       approver is re-checked, the window and the binding are re-checked,
 *       and the approval is consumed by one conditional UPDATE. If the write
 *       then fails, the consumption rolls back with it.</li>
 *   <li><b>Revoke</b> - the requester, the approver, or any active
 *       administrator, while the approval is live.</li>
 * </ol>
 *
 * <p>The requester and beneficiary rules are also database CHECKs
 * ({@code ck_pa_two_people}, {@code ck_pa_not_subject}); the checks here exist
 * so a refusal is a sentence, not a constraint name.
 *
 * <p><strong>Every transition writes exactly one admin audit row</strong> in
 * the same transaction; a refusal writes none.
 *
 * <p><strong>Phase 1: no caller.</strong> Nothing in {@code ArchiveAdminService}
 * or any controller calls this yet. Phase 2 replaces A1's outright refusals
 * with {@link #requireConsumedApproval}.
 */
@Service
public class PrivilegeApprovalService {

    /** {@code revoked_by} for a live row cleared because it expired (section 3.2b). */
    public static final String EXPIRED_ACTOR = "EXPIRED";

    private final PrivilegeApprovalRepository approvals;
    private final AdminRoleRepository adminRoles;
    private final AdminIdentityRepository identities;
    private final AdminAuditSink audit;

    public PrivilegeApprovalService(PrivilegeApprovalRepository approvals,
                                    AdminRoleRepository adminRoles,
                                    AdminIdentityRepository identities,
                                    AdminAuditSink audit) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.adminRoles = Objects.requireNonNull(adminRoles, "adminRoles");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Ask for an escalation to be approved.
     *
     * <p>An expired live request for the same target would block this one
     * under the live unique index, so it is first marked REVOKED by
     * {@link #EXPIRED_ACTOR} - in this transaction, and audited.
     */
    @Transactional
    public PrivilegeApproval requestApproval(InstitutionalIdentifier requester,
                                             PrivilegeAction action,
                                             InstitutionalIdentifier subject,
                                             ApprovalBinding binding,
                                             String reason) {
        requireActiveAdministrator(requester, "request an approval");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(binding, "binding");
        binding.requireShapeFor(action);
        String why = ApprovalReasons.require(reason);

        for (PrivilegeApproval stale : approvals.expireStale(action, subject, binding)) {
            audit.record(requester.value(), AdminAuditAction.PRIVILEGE_APPROVAL_EXPIRED,
                    subject, "approval=" + stale.approvalId() + "; action=" + action
                            + "; was=" + stale.status() + "; validUntil=" + stale.validUntil()
                            + "; cleared so the request can be resubmitted");
        }

        PrivilegeApproval created = approvals.request(action, subject, binding,
                requester.value(), why);
        audit.record(requester.value(), AdminAuditAction.PRIVILEGE_APPROVAL_REQUESTED, subject,
                "approval=" + created.approvalId() + "; action=" + action
                        + "; binding=" + binding.describe()
                        + "; validUntil=" + created.validUntil() + "; " + why);
        return created;
    }

    /** The second administrator agrees. */
    @Transactional
    public PrivilegeApproval approve(InstitutionalIdentifier approver, long approvalId,
                                     String approvalReason) {
        Objects.requireNonNull(approver, "approver");
        String why = ApprovalReasons.require(approvalReason);
        PrivilegeApproval existing = load(approvalId);

        if (existing.requestedBy().equals(approver.value())) {
            throw new PrivilegeApprovalException(Refusal.REQUESTER_CANNOT_APPROVE,
                    "the administrator who requested this cannot also approve it; a second, "
                            + "distinct ARCHIVE_ADMIN must. Nothing was written.");
        }
        if (existing.subject().equals(approver)) {
            throw new PrivilegeApprovalException(Refusal.BENEFICIARY_CANNOT_APPROVE,
                    "the administrator who would gain this privilege cannot approve it. "
                            + "Nothing was written.");
        }
        if (existing.action() == PrivilegeAction.LINK_PRIVILEGED
                && approverSignsInAs(approver, existing.binding())) {
            // Ported from ArchiveAdminService.approveRelink (V086), QA M2.
            throw new PrivilegeApprovalException(Refusal.APPROVER_IS_LINK_TARGET,
                    "the approver cannot approve linking their own Cognito identity; that would "
                            + "put one person on both sides of the decision. Nothing was written.");
        }
        if (!isEligibleAdministrator(approver)) {
            throw new PrivilegeApprovalException(Refusal.APPROVER_INELIGIBLE,
                    "only an active, unsuspended ARCHIVE_ADMIN can approve. Nothing was written.");
        }
        if (existing.status() != PrivilegeApproval.Status.PENDING) {
            throw new PrivilegeApprovalException(Refusal.NOT_PENDING,
                    "approval " + approvalId + " is " + existing.status()
                            + ", not PENDING. Nothing was written.");
        }
        if (!approvals.approve(approvalId, approver.value(), why)) {
            throw refusalAfterLostRace(approvalId, "approved");
        }
        audit.record(approver.value(), AdminAuditAction.PRIVILEGE_APPROVAL_GRANTED,
                existing.subject(), "approval=" + approvalId + "; action=" + existing.action()
                        + "; requestedBy=" + existing.requestedBy() + "; " + why);
        return load(approvalId);
    }

    /**
     * Withdraw a live approval. Either party, or any active administrator.
     *
     * <p>Accepted risk (QA LOW, design 3.3 step 4): any active administrator
     * can revoke any live approval. Revocation only ever REMOVES authority -
     * it cannot grant anything - and every revocation is audited with the
     * actor and a reason, so the worst case is a visible denial of service
     * that a second request repairs.
     */
    @Transactional
    public void revoke(InstitutionalIdentifier actor, long approvalId, String reason) {
        Objects.requireNonNull(actor, "actor");
        String why = ApprovalReasons.require(reason);
        PrivilegeApproval existing = load(approvalId);

        boolean party = actor.value().equals(existing.requestedBy())
                || actor.value().equals(existing.approvedBy());
        if (!party && !isEligibleAdministrator(actor)) {
            throw new PrivilegeApprovalException(Refusal.NOT_PERMITTED,
                    "only the requester, the approver or an active ARCHIVE_ADMIN can revoke an "
                            + "approval. Nothing was written.");
        }
        if (!existing.isLive() || !approvals.revoke(approvalId, actor.value(), why)) {
            throw new PrivilegeApprovalException(Refusal.NOT_LIVE,
                    "approval " + approvalId + " is not PENDING or APPROVED, so there is "
                            + "nothing to revoke. Nothing was written.");
        }
        audit.record(actor.value(), AdminAuditAction.PRIVILEGE_APPROVAL_REVOKED,
                existing.subject(), "approval=" + approvalId + "; action=" + existing.action()
                        + "; was=" + existing.status() + "; " + why);
    }

    /**
     * For the protected write (phase 2): find the approval for exactly this
     * act, re-check it, and consume it.
     *
     * <p>{@code MANDATORY}: it must run inside the transaction that performs
     * the write. A consumption that committed separately would burn the
     * approval even when the write then failed, and a write that committed
     * separately could happen without one.
     *
     * <p>{@code noRollbackFor}: a refusal writes nothing (it is raised before
     * the consume, or because the conditional UPDATE matched no row), so it
     * must not mark the CALLER's transaction rollback-only. The bulk grant
     * path catches a per-grantee refusal and carries on; without this, one
     * refused CENTRAL grantee would roll back every other grantee's grant.
     * A caller that lets the exception propagate still rolls back as usual.
     *
     * @return the consumed approval, whose id the caller puts in its own audit row
     * @throws PrivilegeApprovalException when anything does not match; nothing
     *         is consumed
     */
    @Transactional(propagation = Propagation.MANDATORY,
            noRollbackFor = PrivilegeApprovalException.class)
    public PrivilegeApproval requireConsumedApproval(PrivilegeAction action,
                                                     InstitutionalIdentifier subject,
                                                     ApprovalBinding binding,
                                                     InstitutionalIdentifier executor) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(binding, "binding");
        requireActiveAdministrator(executor, "execute an approved change");

        PrivilegeApproval live = approvals.findLive(action, subject, binding)
                .orElseThrow(() -> new PrivilegeApprovalException(Refusal.NO_APPROVAL,
                        "this change needs an approved " + action + " request for exactly this "
                                + "subject and specification, and there is none. "
                                + "Nothing was written."));

        if (live.status() == PrivilegeApproval.Status.PENDING) {
            throw new PrivilegeApprovalException(Refusal.NOT_APPROVED,
                    "approval " + live.approvalId() + " is still PENDING; a second "
                            + "administrator has not approved it. Nothing was written.");
        }
        if (!live.validUntil().isAfter(Instant.now())) {
            throw expired(live);
        }
        if (!live.binding().equals(binding)) {
            throw new PrivilegeApprovalException(Refusal.BINDING_MISMATCH,
                    "approval " + live.approvalId() + " was granted for " + live.binding().describe()
                            + ", which differs from this change " + binding.describe()
                            + ". Revoke it and request the change actually wanted. "
                            + "Nothing was written.");
        }
        if (!isEligibleAdministrator(new InstitutionalIdentifier(live.approvedBy()))) {
            throw new PrivilegeApprovalException(Refusal.APPROVER_INELIGIBLE,
                    "the administrator who approved " + live.approvalId() + " is no longer an "
                            + "active, unsuspended ARCHIVE_ADMIN, so the approval is void. "
                            + "Nothing was written.");
        }

        /*
         * The authoritative step. Everything above is re-asserted inside this
         * one UPDATE (status, window, every bound field, approver
         * eligibility), so a change that lands between the checks above and
         * here still refuses. Concurrent executors serialise on the row and
         * exactly one sees status = 'APPROVED'.
         */
        if (!approvals.consume(live.approvalId(), action, subject, binding, executor.value())) {
            throw refusalAfterLostRace(live.approvalId(), "used");
        }
        audit.record(executor.value(), AdminAuditAction.PRIVILEGE_APPROVAL_CONSUMED, subject,
                "approval=" + live.approvalId() + "; action=" + action
                        + "; approvedBy=" + live.approvedBy()
                        + "; binding=" + binding.describe());
        return load(live.approvalId());
    }

    /** Every live approval, for an approvals inbox. Read-only. */
    @Transactional(readOnly = true)
    public List<PrivilegeApproval> liveApprovals(InstitutionalIdentifier actor) {
        requireActiveAdministrator(actor, "list approvals");
        return approvals.findAllLive();
    }

    // ---------------------------------------------------------------------

    private PrivilegeApproval load(long approvalId) {
        return approvals.findById(approvalId).orElseThrow(() ->
                new PrivilegeApprovalException(Refusal.NO_APPROVAL,
                        "no privilege approval " + approvalId));
    }

    /** A conditional UPDATE matched nothing: explain from the row as it is now. */
    private PrivilegeApprovalException refusalAfterLostRace(long approvalId, String verb) {
        PrivilegeApproval now = load(approvalId);
        if (now.status() == PrivilegeApproval.Status.CONSUMED) {
            return new PrivilegeApprovalException(Refusal.ALREADY_CONSUMED,
                    "approval " + approvalId + " has already been used. Nothing was written.");
        }
        if (now.status() == PrivilegeApproval.Status.REVOKED) {
            return new PrivilegeApprovalException(Refusal.NOT_LIVE,
                    "approval " + approvalId + " was revoked. Nothing was written.");
        }
        if (!now.validUntil().isAfter(Instant.now())) {
            return expired(now);
        }
        if (now.approvedBy() != null
                && !isEligibleAdministrator(new InstitutionalIdentifier(now.approvedBy()))) {
            return new PrivilegeApprovalException(Refusal.APPROVER_INELIGIBLE,
                    "the approver of " + approvalId + " is no longer an active, unsuspended "
                            + "ARCHIVE_ADMIN. Nothing was written.");
        }
        return new PrivilegeApprovalException(
                now.status() == PrivilegeApproval.Status.PENDING
                        ? Refusal.NOT_APPROVED : Refusal.NOT_PENDING,
                "approval " + approvalId + " could not be " + verb + " (it is " + now.status()
                        + "). Nothing was written.");
    }

    private static PrivilegeApprovalException expired(PrivilegeApproval approval) {
        return new PrivilegeApprovalException(Refusal.EXPIRED,
                "approval " + approval.approvalId() + " expired at " + approval.validUntil()
                        + " (72 hours after the request). Submit a new request. "
                        + "Nothing was written.");
    }

    private void requireActiveAdministrator(InstitutionalIdentifier actor, String what) {
        if (actor == null || !isEligibleAdministrator(actor)) {
            throw new PrivilegeApprovalException(Refusal.NOT_ADMINISTRATOR,
                    "only an active, unsuspended ARCHIVE_ADMIN can " + what + ".");
        }
    }

    /** True when the approver holds an ACTIVE link to the very sign-in being linked. */
    private boolean approverSignsInAs(InstitutionalIdentifier approver, ApprovalBinding binding) {
        return identities.findLinksFor(approver).stream()
                .filter(IdentityLink::isActive)
                .anyMatch(link -> link.cognitoIdentity().issuer().equals(binding.cognitoIssuer())
                        && link.cognitoIdentity().subject().equals(binding.cognitoSubject()));
    }

    /** Active ARCHIVE_ADMIN membership and not suspended (section 3.3). */
    private boolean isEligibleAdministrator(InstitutionalIdentifier identity) {
        return adminRoles.findActive(identity).isPresent() && !identities.isSuspended(identity);
    }
}
