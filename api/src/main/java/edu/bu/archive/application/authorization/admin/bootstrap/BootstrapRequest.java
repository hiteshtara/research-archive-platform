package edu.bu.archive.application.authorization.admin.bootstrap;

/**
 * Everything the operator must state explicitly. Nothing here is derived, read
 * from a token, or looked up: the utility refuses rather than guesses.
 *
 * @param institutionalIdentifier the approved institutional identity the
 *        ARCHIVE_ADMIN membership is keyed on. It must be supplied, never
 *        derived from an email address, a Cognito username or a SAML NameID -
 *        which attribute it should be is decision D-ID-3 and is still open.
 * @param mintArchiveIdentifier mint a fresh ARP-… identifier instead of
 *        supplying one. The approved model (D-ID-5): the archive issues its
 *        own identifier, so no BU attribute has to be settled first. Minting
 *        happens ONLY on a confirmed run - a dry run reserves nothing, so the
 *        value shown by a dry run could never be mistaken for the real one.
 * @param acceptProvisionalIdentifier explicit acknowledgement, required only
 *        when the supplied identifier looks like an email address or matches
 *        the Cognito username. Mirrors
 *        {@code app.authorization.enrollment.accept-mapped-attribute-identifier}:
 *        a risk that cannot be removed can at least be refused by default.
 * @param confirmed the operator's final go-ahead. Absent, the utility performs
 *        a dry run and says so.
 */
public record BootstrapRequest(
        String awsAccountId,
        String environment,
        String cognitoUserPoolId,
        String cognitoIssuer,
        String cognitoSubject,
        String institutionalIdentifier,
        String loginName,
        String operator,
        String reason,
        boolean mintArchiveIdentifier,
        boolean acceptProvisionalIdentifier,
        boolean dryRun,
        boolean confirmed) {
}
