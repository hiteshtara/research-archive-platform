package edu.bu.archive.application.authorization.admin.bootstrap;

/**
 * Everything the operator must state to seat the second administrator.
 * Nothing is derived, read from a token or looked up.
 *
 * @param institutionalIdentifier the new administrator's archive identifier
 *        (ARP-XXXXXXXX-XXXXXXXX), or null with {@code mintArchiveIdentifier}
 * @param mintArchiveIdentifier mint a fresh archive identifier on execution
 *        (D-ID-5); a dry run reserves nothing
 * @param changeReference the change or ticket that approved this seating
 * @param execute the operator's go-ahead. Without it the run is a dry run that
 *        prints the plan and writes nothing - the safe outcome is the one you
 *        get by forgetting a flag.
 */
public record SecondAdministratorBootstrapRequest(
        String awsAccountId,
        String environment,
        String cognitoUserPoolId,
        String cognitoIssuer,
        String cognitoSubject,
        String institutionalIdentifier,
        boolean mintArchiveIdentifier,
        String loginName,
        String operator,
        String reason,
        String changeReference,
        boolean execute) {
}
