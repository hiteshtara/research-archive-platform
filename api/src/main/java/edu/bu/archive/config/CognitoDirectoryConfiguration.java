package edu.bu.archive.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import edu.bu.archive.adapter.out.cognito.CognitoIdentityProviderDirectory;
import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;

/**
 * The read-only Cognito user-pool directory, for operator identity enrollment.
 *
 * <p>Behind {@code @Profile("aws")} deliberately. The grant-admin task runs
 * with {@code SPRING_PROFILES_ACTIVE=aws,grant-admin}, so a real deployment
 * gets the real pool; every test context runs without {@code aws}, so no test
 * can reach Cognito even accidentally. {@code IdentityEnrollmentCli} falls back
 * to {@link CognitoDirectory#UNAVAILABLE} when this bean is absent, which
 * refuses a confirmed enrollment and only warns for a dry run - so the absence
 * of this configuration degrades to "cannot verify", never to "verified".
 *
 * <p>Region comes from {@code AWS_REGION}, which the task definition sets, and
 * not from a command-line flag: a flag would let an operator point the check at
 * a pool in another region while the rest of the verification believed it was
 * in us-east-1.
 *
 * <p>No bean here exposes the SDK client itself. Only the narrow read port
 * leaves this class, so nothing downstream can reach a Cognito write
 * operation.
 */
@Configuration
@Profile("aws")
public class CognitoDirectoryConfiguration {

    @Bean
    CognitoDirectory cognitoDirectory(@Value("${AWS_REGION:us-east-1}") String awsRegion) {
        return new CognitoIdentityProviderDirectory(
                CognitoIdentityProviderClient.builder()
                        .region(Region.of(awsRegion))
                        .build());
    }
}
