package edu.bu.archive.application.authorization.admin.approval;

/**
 * A privilege-approval step was refused. Nothing was written: every caller is
 * transactional, so the refusal rolls back anything earlier in the same
 * transaction, including a consumption.
 *
 * <p>{@link #refusal()} says which rule refused, so tests and (in phase 2) the
 * HTTP mapping can tell them apart without parsing the message.
 */
public class PrivilegeApprovalException extends RuntimeException {

    public enum Refusal {
        /** The actor is not an active, unsuspended ARCHIVE_ADMIN. */
        NOT_ADMINISTRATOR,
        /** No live approval exists for this action, subject and binding. */
        NO_APPROVAL,
        /** The approval exists but is still PENDING. */
        NOT_APPROVED,
        /** The approval is not PENDING, so it cannot be approved. */
        NOT_PENDING,
        /** The approval is CONSUMED or REVOKED, so it cannot be revoked or used. */
        NOT_LIVE,
        /** The approval was already used. */
        ALREADY_CONSUMED,
        /** Past {@code valid_until}: 72 hours from the request (D2). */
        EXPIRED,
        /** The execution differs from what was approved in at least one bound field. */
        BINDING_MISMATCH,
        /** The approver is not, or is no longer, an active unsuspended ARCHIVE_ADMIN. */
        APPROVER_INELIGIBLE,
        /** The two-person rule: the requester cannot approve. */
        REQUESTER_CANNOT_APPROVE,
        /** The beneficiary cannot approve their own escalation. */
        BENEFICIARY_CANNOT_APPROVE,
        /** A live request for the same target already exists. */
        DUPLICATE_LIVE_REQUEST,
        /** The actor may not revoke this approval. */
        NOT_PERMITTED,
        /** The reason does not meet the rule (decision D4). */
        REASON_REQUIRED
    }

    private final Refusal refusal;

    public PrivilegeApprovalException(Refusal refusal, String message) {
        super(message);
        this.refusal = refusal;
    }

    public Refusal refusal() {
        return refusal;
    }
}
