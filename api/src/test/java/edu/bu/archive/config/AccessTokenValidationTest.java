package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Exercises the PRODUCTION access-token validator chain
 * (SecurityConfiguration.accessTokenValidator) against locally signed tokens.
 * The RSA key pair is generated in memory per test run; no signing material
 * is committed or written anywhere. Fixture-based; real Cognito tokens and
 * BU federation are NOT VERIFIED by these tests.
 */
class AccessTokenValidationTest {

    private static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";
    private static final String CLIENT_ID = "synthetic-client-id";

    private static KeyPair signingKeys;
    private static KeyPair otherKeys;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void createLocalKeysAndDecoder() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKeys.getPublic()).build();
        decoder.setJwtValidator(SecurityConfiguration.accessTokenValidator(ISSUER, CLIENT_ID));
    }

    private static String token(KeyPair keys, String issuer, String clientId, String tokenUse, Instant expires)
            throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("00000000-0000-4000-8000-000000000001")
                .claim("client_id", clientId)
                .claim("token_use", tokenUse)
                .claim("username", "syntheticshib_syn-nameid-1003")
                .issueTime(Date.from(expires.minusSeconds(3600)))
                .expirationTime(Date.from(expires))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static Instant inOneHour() {
        return Instant.now().plusSeconds(3600);
    }

    @Test
    void aValidAccessTokenYieldsOnlyIssuerAndSubject() throws Exception {
        var jwt = decoder.decode(token(signingKeys, ISSUER, CLIENT_ID, "access", inOneHour()));
        var identity = ValidatedCognitoIdentity.fromValidatedAccessToken(jwt);
        assertThat(identity.issuer()).isEqualTo(ISSUER);
        assertThat(identity.subject()).isEqualTo("00000000-0000-4000-8000-000000000001");
    }

    @Test
    void wrongIssuerIsRejected() {
        assertThatThrownBy(() -> decoder.decode(
                token(signingKeys, "https://cognito-idp.test.invalid/us-east-1_OTHER", CLIENT_ID, "access", inOneHour())))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void wrongClientIsRejected() {
        assertThatThrownBy(() -> decoder.decode(token(signingKeys, ISSUER, "other-client", "access", inOneHour())))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void anInvalidSignatureIsRejected() {
        assertThatThrownBy(() -> decoder.decode(token(otherKeys, ISSUER, CLIENT_ID, "access", inOneHour())))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        assertThatThrownBy(() -> decoder.decode(
                token(signingKeys, ISSUER, CLIENT_ID, "access", Instant.now().minusSeconds(600))))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void anIdTokenIsRejected() {
        assertThatThrownBy(() -> decoder.decode(token(signingKeys, ISSUER, CLIENT_ID, "id", inOneHour())))
                .isInstanceOf(JwtValidationException.class);
    }
}
