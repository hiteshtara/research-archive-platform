package edu.bu.archive.application.authorization.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * A second administrator's approval to re-link one archive identity to one
 * named Cognito identity.
 *
 * <p>Bound to all three of identity, proposed issuer and proposed subject.
 * Consuming it compares all three, so an approval for one identity cannot
 * authorise another, and an approval for one target cannot be pointed at a
 * different Cognito account.
 */
public record RelinkApproval(
        long approvalId,
        InstitutionalIdentifier identity,
        String proposedCognitoIssuer,
        String proposedCognitoSubject,
        String requestedBy,
        Instant requestedAt,
        int grantsAtRequest,
        String reason,
        String approvedBy,
        Instant approvedAt,
        Status status) {

    public enum Status { PENDING, APPROVED, CONSUMED, REVOKED }

    public boolean isUsable() {
        return status == Status.APPROVED;
    }

    /** True when this approval names exactly this identity and this target. */
    public boolean covers(InstitutionalIdentifier target, String issuer, String subject) {
        return identity.value().equals(target.value())
                && proposedCognitoIssuer.equals(issuer)
                && proposedCognitoSubject.equals(subject);
    }
}
