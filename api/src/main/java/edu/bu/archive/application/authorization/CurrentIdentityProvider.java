package edu.bu.archive.application.authorization;

import java.util.Optional;

/**
 * Who the current request is, as a validated Cognito identity. The
 * production implementation reads the already-validated JWT; nothing else
 * (headers, parameters, cookies) may be used. A demo build may replace this
 * bean with a synthetic identity source that exists only in that build.
 */
public interface CurrentIdentityProvider {

    Optional<ValidatedCognitoIdentity> current();
}
