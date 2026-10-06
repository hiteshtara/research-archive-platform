package edu.bu.archive.application.authorization.fixtures;

import java.util.Map;

/**
 * TEST-ONLY. A synthetic SAML assertion as a federated IdP would send it to
 * Cognito: an issuer, a NameID and attributes. It is NOT a Cognito token and
 * never reaches the API. Attribute names here are invented for tests
 * ("synthetic:*"); they are NOT BU's attribute names, which await BU IAM.
 */
public record SyntheticSamlAssertion(
        String idpEntityId,
        String nameIdFormat,
        String nameId,
        Map<String, String> attributes
) {

    public static final String PERSISTENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
    public static final String TRANSIENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient";
    public static final String INSTITUTIONAL_ID_ATTRIBUTE = "synthetic:institutionalId";
    public static final String LOGIN_NAME_ATTRIBUTE = "synthetic:loginName";
}
