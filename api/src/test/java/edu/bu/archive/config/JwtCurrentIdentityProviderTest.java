package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtCurrentIdentityProviderTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwt(String tokenUse) throws Exception {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(new URL("https://cognito-idp.test.invalid/us-east-1_SYNTHETIC").toString())
                .subject("sub-1").claim("token_use", tokenUse).claim("username", "syntheticshib_x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test
    void onlyAValidatedAccessTokenYieldsAnIdentityAndOnlyIssuerAndSub() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("access")));
        var identity = new JwtCurrentIdentityProvider().current().orElseThrow();
        assertThat(identity.issuer()).isEqualTo("https://cognito-idp.test.invalid/us-east-1_SYNTHETIC");
        assertThat(identity.subject()).isEqualTo("sub-1");
    }

    @Test
    void anythingElseYieldsNoIdentity() throws Exception {
        assertThat(new JwtCurrentIdentityProvider().current()).isEmpty();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null));
        assertThat(new JwtCurrentIdentityProvider().current()).isEmpty();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("id")));
        assertThat(new JwtCurrentIdentityProvider().current()).isEmpty();
    }
}
