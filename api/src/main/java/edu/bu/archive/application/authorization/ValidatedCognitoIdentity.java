package edu.bu.archive.application.authorization;

import java.util.Objects;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who signed in, as far as Cognito can say: the issuer and subject of an
 * access token that SecurityConfiguration has ALREADY validated (signature,
 * issuer, client_id, token_use=access, expiry).
 *
 * <p>Identity is issuer + subject, and nothing else: {@link #equals} and
 * {@link #hashCode} use only those two.
 *
 * <p>The token's {@code username} claim is carried for ONE purpose: the
 * server-side enrollment step looks that exact profile up in the user pool
 * (AdminGetUser) and then requires the profile's {@code sub} to equal this
 * token's subject. It is never split, parsed or compared to anything to
 * recover an institutional identifier (for a federated user it is generated
 * from the IdP name and the SAML NameID). Email, display names and anything
 * the browser sends are not identity.
 */
public record ValidatedCognitoIdentity(String issuer, String subject, String username, java.time.Instant authTime,
                                       java.util.Set<String> scopes) {

    public ValidatedCognitoIdentity {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (username != null && username.isBlank()) {
            username = null;
        }
        scopes = scopes == null ? java.util.Set.of() : java.util.Set.copyOf(scopes);
    }

    /** An identity whose token username is unknown (enrollment will refuse it). */
    public ValidatedCognitoIdentity(String issuer, String subject) {
        this(issuer, subject, null, null, null);
    }

    /** An identity without a sign-in time (enrollment's per-session re-check will deny it). */
    public ValidatedCognitoIdentity(String issuer, String subject, String username) {
        this(issuer, subject, username, null, null);
    }

    public ValidatedCognitoIdentity(String issuer, String subject, String username, java.time.Instant authTime) {
        this(issuer, subject, username, authTime, null);
    }

    /**
     * Cognito's self-service scope. A token carrying it lets its holder change their own writable
     * user-pool attributes (UpdateUserAttributes) - including a mapped identifier attribute - so
     * enrollment never trusts such a token's profile.
     */
    public static final String SELF_SERVICE_SCOPE = "aws.cognito.signin.user.admin";

    public boolean canEditOwnAttributes() {
        return scopes.contains(SELF_SERVICE_SCOPE);
    }

    /** The token's auth_time: when the user last signed in at the IdP (one value per sign-in session). */
    public Optional<java.time.Instant> signInTime() {
        return Optional.ofNullable(authTime);
    }

    /** The token's username claim, for the enrollment profile lookup only. */
    public Optional<String> tokenUsername() {
        return Optional.ofNullable(username);
    }

    /** From a token the resource server has already validated. */
    public static ValidatedCognitoIdentity fromValidatedAccessToken(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        if (!"access".equals(jwt.getClaimAsString("token_use"))) {
            throw new IllegalArgumentException("not an access token");
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        java.time.Instant authTime = null;
        Object claim = jwt.getClaims().get("auth_time");
        if (claim instanceof java.time.Instant instant) {
            authTime = instant;
        } else if (claim instanceof Number seconds) {
            authTime = java.time.Instant.ofEpochSecond(seconds.longValue());
        }
        String scope = jwt.getClaimAsString("scope");
        java.util.Set<String> scopes = scope == null ? java.util.Set.of()
                : java.util.Set.of(scope.trim().split("\\s+"));
        return new ValidatedCognitoIdentity(issuer, jwt.getSubject(), jwt.getClaimAsString("username"), authTime, scopes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValidatedCognitoIdentity that
                && issuer.equals(that.issuer) && subject.equals(that.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, subject);
    }

    @Override
    public String toString() {
        return "ValidatedCognitoIdentity[issuer=" + issuer + ", subject=" + subject + "]";
    }
}
