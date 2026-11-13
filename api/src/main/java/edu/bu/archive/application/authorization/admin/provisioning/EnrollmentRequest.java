package edu.bu.archive.application.authorization.admin.provisioning;

/**
 * Everything the operator typed to enrol an ordinary BU research user.
 *
 * <p>Nothing here is derived. The archive identifier is MINTED, not supplied,
 * so there is no field for it: an identifier the utility accepted from a flag
 * would be an identifier nobody approved, and an identifier it derived from an
 * email address would be worse.
 */
public record EnrollmentRequest(
        String awsAccountId,
        String environment,
        /** The acting ARCHIVE_ADMIN's ARP. */
        String actorIdentifier,
        String cognitoIssuer,
        /** The immutable Cognito {@code sub} UUID. Never a username or email. */
        String cognitoSubject,
        /** The pool to corroborate the subject against. */
        String cognitoUserPoolId,
        /** Display/lookup only; never used to select the identity. */
        String loginName,
        String operator,
        String reason,
        String approvalReference,
        boolean dryRun,
        boolean confirmed) { }
