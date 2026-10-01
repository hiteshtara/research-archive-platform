package edu.bu.archive.application.authorization.fixtures;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TEST-ONLY simulation of Cognito federating a SAML IdP, following the
 * documented behaviour that matters to the identity design:
 * <ul>
 *   <li>a federated user is recognised by NameID; a different NameID is a
 *       different profile with a different sub;</li>
 *   <li>the generated username is IdP name + "_" + NameID (lowercased in a
 *       case-insensitive pool) - the archive never parses it;</li>
 *   <li>mapped attributes are copied into the profile.</li>
 * </ul>
 * Lives under src/test only; there is no equivalent in any deployable path.
 */
public final class SyntheticCognitoFederation {

    public static final String ISSUER = "https://cognito-idp.test.invalid/us-east-1_SYNTHETIC";
    public static final String PROVIDER_NAME = "SyntheticShib";

    private final Map<String, SyntheticCognitoProfile> profilesByNameId = new HashMap<>();

    public SyntheticCognitoProfile signIn(SyntheticSamlAssertion assertion) {
        return profilesByNameId.compute(assertion.nameId(), (nameId, existing) -> {
            String sub = existing != null
                    ? existing.sub()
                    : UUID.nameUUIDFromBytes((PROVIDER_NAME + "|" + nameId).getBytes(StandardCharsets.UTF_8)).toString();
            return new SyntheticCognitoProfile(
                    ISSUER,
                    sub,
                    (PROVIDER_NAME + "_" + nameId).toLowerCase(Locale.ROOT),
                    PROVIDER_NAME,
                    nameId,
                    Map.copyOf(assertion.attributes())
            );
        });
    }
}
