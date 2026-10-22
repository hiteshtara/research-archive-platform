package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * The real BU adapter, deliberately inert. Which SAML attribute carries the
 * stable institutional identifier, whether BU can issue a stable NameID to a
 * Cognito SP, and how that identity binds to KRIM_PRNCPL_T.PRNCPL_ID are all
 * awaiting BU IAM (authorization design rev 3.6a, section 12.9). Until that
 * contract is confirmed this resolves nobody, so nobody can be enrolled from
 * real BU sign-ins and every real user stays not provisioned (no access).
 *
 * <p>Do not replace this with a guessed attribute name, a parsed Cognito
 * username or an email match.
 */
public final class AwaitingIamConfirmationAttributeSource
        implements InstitutionalIdentityAttributeSource {

    @Override
    public Optional<InstitutionalIdentifier> institutionalIdentifierFor(
            ValidatedCognitoIdentity cognitoIdentity
    ) {
        return Optional.empty();
    }
}
