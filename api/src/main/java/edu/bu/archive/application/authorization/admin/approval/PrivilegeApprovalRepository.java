package edu.bu.archive.application.authorization.admin.approval;

import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * Store of privilege approvals ({@code authz.privilege_approval}, V090).
 *
 * <p>Every write is a single conditional statement, so the rule it enforces
 * holds under concurrency without a separate read-then-write. Every write
 * requires the caller's transaction ({@code MANDATORY}), so it commits or
 * rolls back with the change and the audit row it belongs to.
 *
 * <p>There is deliberately no method that changes a bound column: the V090
 * trigger would refuse it anyway, and a method that tried would be a standing
 * invitation to edit a request after it was approved.
 */
public interface PrivilegeApprovalRepository {

    /**
     * Insert a PENDING request. {@code valid_until} is set by the database
     * (72 hours from the request).
     *
     * @throws PrivilegeApprovalException {@code DUPLICATE_LIVE_REQUEST} when
     *         a live request for the same target already exists
     */
    PrivilegeApproval request(PrivilegeAction action, InstitutionalIdentifier subject,
                              ApprovalBinding binding, String requestedBy, String reason);

    /**
     * PENDING to APPROVED, conditionally: still PENDING, approver is neither
     * the requester nor the subject, approver is an active unsuspended
     * ARCHIVE_ADMIN, and the request has not expired.
     *
     * @return false when any condition failed; nothing was changed
     */
    boolean approve(long approvalId, String approvedBy, String approvalReason);

    /**
     * PENDING or APPROVED to REVOKED.
     *
     * @return false when the approval was not live; nothing was changed
     */
    boolean revoke(long approvalId, String revokedBy, String revocationReason);

    /**
     * APPROVED to CONSUMED, conditionally on: still APPROVED, not expired, the
     * action, subject and every binding field equal (NULL-safe), and the
     * approver still an active unsuspended ARCHIVE_ADMIN.
     *
     * <p>MUST be called inside the transaction that performs the protected
     * write, so a failure after this rolls the consumption back too.
     *
     * @return true for exactly one of any number of concurrent callers
     */
    boolean consume(long approvalId, PrivilegeAction action, InstitutionalIdentifier subject,
                    ApprovalBinding binding, String consumedBy);

    /**
     * Mark expired live rows for this target REVOKED by {@code 'EXPIRED'}, so
     * a fresh request is not blocked by the live unique index (section 3.2b).
     *
     * @return the rows that were expired, for auditing
     */
    List<PrivilegeApproval> expireStale(PrivilegeAction action, InstitutionalIdentifier subject,
                                        ApprovalBinding binding);

    Optional<PrivilegeApproval> findById(long approvalId);

    /**
     * The live approval for this target, matched on the live-index key
     * (action, subject, grant type, unit, IO, award, target grant, Cognito
     * issuer and subject). At most one exists.
     */
    Optional<PrivilegeApproval> findLive(PrivilegeAction action, InstitutionalIdentifier subject,
                                         ApprovalBinding binding);

    /** Every PENDING or APPROVED approval, oldest first. */
    List<PrivilegeApproval> findAllLive();
}
