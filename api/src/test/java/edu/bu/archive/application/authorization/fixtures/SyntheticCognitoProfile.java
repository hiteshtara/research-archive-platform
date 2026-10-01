package edu.bu.archive.application.authorization.fixtures;

import java.util.Map;

import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * TEST-ONLY. The Cognito user-pool profile a federated sign-in produces:
 * Cognito's own generated username and sub, the federation record
 * ("identities": provider + provider user id) and mapped attributes.
 */
public record SyntheticCognitoProfile(
        String issuer,
        String sub,
        String generatedUsername,
        String providerName,
        String providerUserId,
        Map<String, String> mappedAttributes
) {

    /** What the API learns from a validated access token: issuer + sub only. */
    public ValidatedCognitoIdentity validatedIdentity() {
        return new ValidatedCognitoIdentity(issuer, sub);
    }
}
