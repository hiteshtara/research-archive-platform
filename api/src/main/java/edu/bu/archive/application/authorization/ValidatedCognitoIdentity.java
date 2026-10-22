package edu.bu.archive.application.authorization;

import java.util.Objects;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who signed in, as far as Cognito can say: the issuer and subject of an
 * access token that SecurityConfiguration has ALREADY validated (signature,
 * issuer, client_id, token_use=access, expiry).
 *
 * <p>Deliberately nothing else. The Cognito username is not read: for a
 * federated user it is generated from the IdP name and the SAML NameID and
 * must never be split or parsed to recover an institutional identifier.
 * Email, display names and anything the browser sends are not identity.
 */
public record ValidatedCognitoIdentity(String issuer, String subject) {

    public ValidatedCognitoIdentity {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
    }

    /** From a token the resource server has already validated. */
    public static ValidatedCognitoIdentity fromValidatedAccessToken(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        if (!"access".equals(jwt.getClaimAsString("token_use"))) {
            throw new IllegalArgumentException("not an access token");
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        return new ValidatedCognitoIdentity(issuer, jwt.getSubject());
    }
}
