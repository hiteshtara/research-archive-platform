package edu.bu.archive.application.authorization.admin;

import java.util.List;
import java.util.Optional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * Approvals for re-linking an archive identity that holds active grants.
 *
 * <p>Not a role store. Approvers are ARCHIVE_ADMINs from
 * {@code authz.admin_role_member}; this holds the individual decisions they
 * make, which is why it is a separate table and not another membership type.
 */
public interface RelinkApprovalRepository {

    /** Record a request. Refused if a live one already exists for this target. */
    RelinkApproval request(InstitutionalIdentifier identity, String proposedCognitoIssuer,
                           String proposedCognitoSubject, String requestedBy,
                           int grantsAtRequest, String reason);

    /**
     * Approve a pending request.
     *
     * @throws IllegalStateException if it is not PENDING, or if the approver
     *         is the requester - the two-person rule, also a CHECK constraint
     */
    RelinkApproval approve(long approvalId, String approvedBy);

    /** Withdraw an approval or request. A revoked one can never authorise anything. */
    void revoke(long approvalId, String revokedBy);

    Optional<RelinkApproval> findById(long approvalId);

    /** The live (PENDING or APPROVED) approval for this exact target, if any. */
    Optional<RelinkApproval> findLive(InstitutionalIdentifier identity,
                                      String proposedCognitoIssuer,
                                      String proposedCognitoSubject);

    List<RelinkApproval> findForIdentity(InstitutionalIdentifier identity);

    /**
     * Atomically mark an APPROVED approval consumed.
     *
     * <p>Must be a single conditional update returning whether it changed a
     * row. Two concurrent relinks then serialise on it: exactly one consumes
     * the approval and the other finds nothing to consume, so an approval can
     * authorise one relink and no more.
     *
     * @return true if this caller consumed it
     */
    boolean consume(long approvalId);
}
