package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.Optional;

/**
 * Where the utility is ACTUALLY running, as opposed to where the operator
 * said it is running.
 *
 * <p>This exists because the first version of the check was worthless: the
 * CLI passed the same {@code --aws-account-id} flag as both the expected
 * and the actual value, so the comparison was {@code x == x} and always
 * passed. An operator who typed {@code --environment=dev} while pointed at
 * another account would have sailed through every guard, including the
 * refusal to run against production.
 *
 * <p>A flag states an intention. This states a fact.
 */
public interface RuntimeIdentity {

    /** The AWS account this process is really running in, if it can be determined. */
    Optional<String> awsAccountId();

    /** The ECS cluster this process is really running in, if any. */
    Optional<String> clusterName();

    /** Nothing can be determined - running outside ECS, for example. */
    RuntimeIdentity UNKNOWN = new RuntimeIdentity() {
        @Override
        public Optional<String> awsAccountId() {
            return Optional.empty();
        }

        @Override
        public Optional<String> clusterName() {
            return Optional.empty();
        }
    };
}
