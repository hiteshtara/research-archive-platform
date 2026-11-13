package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;

/**
 * The profile gate on the Cognito directory, asserted structurally.
 *
 * <p>Starting a context with the {@code aws} profile active to test this
 * would be the wrong test: it would activate every other AWS-profile bean and
 * could itself reach AWS. What matters is the annotation, because that is what
 * keeps the real pool out of every test context - and it is a single line that
 * a later change could remove without any test noticing.
 */
@DisplayName("The Cognito directory is only wired under the aws profile")
class CognitoDirectoryConfigurationTest {

    @Test
    void theConfigurationIsGatedOnTheAwsProfile() {
        Profile profile = CognitoDirectoryConfiguration.class.getAnnotation(Profile.class);
        assertThat(profile)
                .as("without @Profile, a test context would build a real Cognito client")
                .isNotNull();
        assertThat(profile.value())
                .as("the grant-admin task runs with SPRING_PROFILES_ACTIVE=aws,grant-admin;"
                        + " no test context activates aws")
                .containsExactly("aws");
    }

    @Test
    void theConfigurationExposesOnlyTheReadPortAndNeverTheSdkClient() {
        /*
         * If a CognitoIdentityProviderClient bean were exposed, any other bean
         * could inject it and reach a Cognito write operation, which would
         * make the narrow IAM policy the only thing standing between this
         * application and modifying real users.
         */
        for (Method method : CognitoDirectoryConfiguration.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertThat(method.getReturnType())
                    .as("bean method " + method.getName() + " must return only the read port")
                    .isEqualTo(CognitoDirectory.class);
        }
        assertThat(Arrays.stream(CognitoDirectoryConfiguration.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .count())
                .isEqualTo(1);
    }
}
