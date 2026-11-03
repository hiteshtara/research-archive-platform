package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.Optional;

/**
 * Reads the DEV Cognito user pool to confirm that a subject an operator typed
 * really exists.
 *
 * <p>Why this is a port with no default: the archive deliberately ships no
 * {@code CognitoProfileReader} either (see {@code AuthorizationConfiguration}),
 * because automatic enrollment must stay impossible. This is the narrow,
 * read-only counterpart - it looks one subject up and reports what the pool
 * says. It never creates, modifies, enables or disables a Cognito user, and it
 * never reads a password or a token.
 *
 * <p><strong>Absence fails closed.</strong> A confirmed enrollment with no
 * directory available is refused, not waved through: the whole point of the
 * check is that the operator's typed subject is corroborated by the pool.
 */
public interface CognitoDirectory {

    /** What the pool knows about one subject. */
    record PoolUser(String subject, String username, boolean enabled, String status) {

        /** Federated users arrive as {@code BUShibboleth_<email>}. */
        public boolean isFederated() {
            return username != null && username.startsWith("BUShibboleth_");
        }
    }

    /**
     * Look one subject up by its immutable {@code sub}.
     *
     * @return the user, or empty when the pool holds no such subject
     * @throws RuntimeException if the pool cannot be read at all - which must
     *         be treated as "unverifiable", never as "absent"
     */
    Optional<PoolUser> findBySubject(String userPoolId, String subject);

    /** Nothing can be read - running without a configured directory. */
    CognitoDirectory UNAVAILABLE = (pool, subject) -> {
        throw new UnsupportedOperationException(
                "no Cognito directory is configured, so the subject cannot be verified "
                        + "against the user pool");
    };
}
