package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;
import edu.bu.archive.application.authorization.fixtures.SyntheticCognitoFederation;
import edu.bu.archive.application.authorization.fixtures.SyntheticSamlAssertion;

/**
 * The three identity layers stay separate: SAML input, the Cognito profile
 * it produces, and the archive's own link. Fixture-based; real BU federation
 * NOT VERIFIED.
 */
class SyntheticFederationTest {

    private static SyntheticSamlAssertion assertion(String nameId, String format) {
        return new SyntheticSamlAssertion("https://idp.test.invalid/synthetic", format, nameId,
                Map.of(SyntheticSamlAssertion.INSTITUTIONAL_ID_ATTRIBUTE, "SYN-INST-2001"));
    }

    @Test
    void theSameNameIdIsRecognisedAsTheSameCognitoProfile() {
        var federation = new SyntheticCognitoFederation();
        var first = federation.signIn(assertion("syn-stable", SyntheticSamlAssertion.PERSISTENT));
        var again = federation.signIn(assertion("syn-stable", SyntheticSamlAssertion.PERSISTENT));
        assertThat(again.sub()).isEqualTo(first.sub());
    }

    @Test
    void aChangedNameIdIsANewProfileThatTheArchiveDoesNotRecognise() {
        // Why a stable NameID is a precondition: a stable institutional
        // attribute cannot make Cognito recognise a different NameID.
        var archive = new SyntheticArchive();
        var federation = archive.federation;
        var changed = federation.signIn(new SyntheticSamlAssertion("https://idp.test.invalid/synthetic",
                SyntheticSamlAssertion.TRANSIENT, "syn-nameid-1003-changed",
                Map.of(SyntheticSamlAssertion.INSTITUTIONAL_ID_ATTRIBUTE, "SYN-INST-1003")));
        assertThat(changed.sub()).isNotEqualTo(archive.researchStaff.subject());
        assertThat(archive.identityResolver.resolve(changed.validatedIdentity()))
                .isInstanceOf(IdentityResolution.NotProvisioned.class);
    }

    @Test
    void theApiSeesOnlyIssuerAndSubNeverTheGeneratedUsername() {
        var profile = new SyntheticCognitoFederation()
                .signIn(assertion("SYN-Mixed-Case", SyntheticSamlAssertion.PERSISTENT));
        assertThat(profile.generatedUsername()).isEqualTo("syntheticshib_syn-mixed-case");
        var identity = profile.validatedIdentity();
        assertThat(identity.issuer()).isEqualTo(SyntheticCognitoFederation.ISSUER);
        assertThat(identity.subject()).isEqualTo(profile.sub());
        assertThat(identity.toString()).doesNotContain("syn-mixed-case");
    }
}
