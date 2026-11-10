package edu.bu.archive.application.authorization.admin.provisioning;

/**
 * One operator-typed request to link a Cognito identity to an archive
 * identity. Every field is a flag; nothing is read from a token, looked up in
 * Cognito, or inferred from an email address.
 *
 * @param kualiPersonId the verified Kuali employee PERSON_ID, or null. Null is
 *        correct and sufficient for CENTRAL, UNIT, IO and PARENT_AWARD access.
 *        It is NOT sufficient for CONTACT_DERIVATION: a mapping without a
 *        PERSON_ID never matches a contact row, so the grant would be issued
 *        and grant nothing. {@link IdentityLinkCommand} says so rather than
 *        leaving it to be discovered as "SEC-003 does not work".
 */
public record IdentityLinkRequest(
        String awsAccountId,
        String environment,
        String cognitoUserPoolId,
        String cognitoRegion,
        String cognitoIssuer,
        String cognitoSubject,
        String subjectIdentifier,
        String actorIdentifier,
        String kualiPersonId,
        String loginName,
        String operator,
        String reason,
        String approvalReference,
        boolean dryRun,
        boolean confirmed,
        String unlinkLinkId) {

    /** A request that withdraws a link rather than creating one. */
    public boolean isUnlink() {
        return unlinkLinkId != null && !unlinkLinkId.isBlank();
    }

    /** Back-compatible creation form, before unlink existed. */
    public IdentityLinkRequest(
            String awsAccountId, String environment, String cognitoUserPoolId,
            String cognitoRegion, String cognitoIssuer, String cognitoSubject,
            String subjectIdentifier, String actorIdentifier, String kualiPersonId,
            String loginName, String operator, String reason, String approvalReference,
            boolean dryRun, boolean confirmed) {

        this(awsAccountId, environment, cognitoUserPoolId, cognitoRegion, cognitoIssuer,
                cognitoSubject, subjectIdentifier, actorIdentifier, kualiPersonId, loginName,
                operator, reason, approvalReference, dryRun, confirmed, null);
    }
}
