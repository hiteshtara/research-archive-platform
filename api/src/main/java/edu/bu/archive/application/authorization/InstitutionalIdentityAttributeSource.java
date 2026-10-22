package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * Where a trusted enrollment process would read a federated user's
 * institutional identifier from (for example a Cognito profile attribute
 * populated by the SAML attribute mapping). Used ONLY by enrollment, never
 * on the request path, and never with request input.
 */
public interface InstitutionalIdentityAttributeSource {

    Optional<InstitutionalIdentifier> institutionalIdentifierFor(
            ValidatedCognitoIdentity cognitoIdentity
    );
}
