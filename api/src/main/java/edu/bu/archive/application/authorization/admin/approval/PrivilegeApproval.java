package edu.bu.archive.application.authorization.admin.approval;

import java.time.Instant;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/** One row of {@code authz.privilege_approval} (V090). */
public record PrivilegeApproval(
        long approvalId,
        PrivilegeAction action,
        InstitutionalIdentifier subject,
        ApprovalBinding binding,
        String requestedBy,
        Instant requestedAt,
        String reason,
        String approvedBy,
        Instant approvedAt,
        String approvalReason,
        Instant validUntil,
        Instant consumedAt,
        String consumedBy,
        Instant revokedAt,
        String revokedBy,
        String revocationReason,
        Status status) {

    /** Mirrors {@code ck_pa_status}. */
    public enum Status {
        PENDING, APPROVED, CONSUMED, REVOKED
    }

    /** PENDING or APPROVED: the states the live unique index covers. */
    public boolean isLive() {
        return status == Status.PENDING || status == Status.APPROVED;
    }
}
