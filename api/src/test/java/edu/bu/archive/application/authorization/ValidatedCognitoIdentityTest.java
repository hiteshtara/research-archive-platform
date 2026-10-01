package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The sign-in time (auth_time) drives enrollment's once-per-session re-verification. */
class ValidatedCognitoIdentityTest {

    private static Jwt.Builder access() {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuer("https://idp.invalid/pool")
                .subject("sub-1").claim("token_use", "access").claim("username", "Provider_nameid");
    }

    @Test
    void authTimeIsReadFromTheValidatedAccessToken() {
        var withInstant = ValidatedCognitoIdentity.fromValidatedAccessToken(
                access().claim("auth_time", Instant.ofEpochSecond(1_790_000_000L)).build());
        assertThat(withInstant.signInTime()).contains(Instant.ofEpochSecond(1_790_000_000L));
        var withNumber = ValidatedCognitoIdentity.fromValidatedAccessToken(
                access().claim("auth_time", 1_790_000_000L).build());
        assertThat(withNumber.signInTime()).contains(Instant.ofEpochSecond(1_790_000_000L));
        assertThat(ValidatedCognitoIdentity.fromValidatedAccessToken(access().build()).signInTime()).isEmpty();
    }

    @Test
    void signInTimeAndUsernameDoNotChangeIdentityEquality() {
        var a = new ValidatedCognitoIdentity("i", "s", "u1", Instant.EPOCH);
        var b = new ValidatedCognitoIdentity("i", "s", "u2", Instant.now());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void theSelfServiceScopeIsRecognisedFromTheScopeClaim() {
        assertThat(ValidatedCognitoIdentity.fromValidatedAccessToken(
                access().claim("scope", "openid email profile").build()).canEditOwnAttributes()).isFalse();
        assertThat(ValidatedCognitoIdentity.fromValidatedAccessToken(
                access().claim("scope", "openid aws.cognito.signin.user.admin").build()).canEditOwnAttributes()).isTrue();
    }
}
