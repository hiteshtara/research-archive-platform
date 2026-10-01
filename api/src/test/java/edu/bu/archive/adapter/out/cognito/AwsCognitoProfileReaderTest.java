package edu.bu.archive.adapter.out.cognito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CognitoProfile;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/** AdminGetUser mapping, with a mocked client (no AWS call). SYNTHETIC values only. */
class AwsCognitoProfileReaderTest {

    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final AwsCognitoProfileReader reader = new AwsCognitoProfileReader(client, "us-east-1_SYNTHETIC");

    @Test
    void mapsSubIdentitiesAndAttributesFromAdminGetUser() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(AdminGetUserResponse.builder()
                .username("SyntheticSaml_nameid-1")
                .enabled(true)
                .userAttributes(
                        AttributeType.builder().name("sub").value("sub-1").build(),
                        AttributeType.builder().name("custom:synthetic_attr").value("SYN-V-1").build(),
                        AttributeType.builder().name("identities").value(
                                "[{\"userId\":\"nameid-1\",\"providerName\":\"SyntheticSaml\",\"providerType\":\"SAML\","
                                        + "\"issuer\":null,\"primary\":true,\"dateCreated\":1}]").build())
                .build());

        CognitoProfile profile = reader.read("SyntheticSaml_nameid-1").orElseThrow();
        assertThat(profile.sub()).isEqualTo("sub-1");
        assertThat(profile.enabled()).isTrue();
        assertThat(profile.identities()).containsExactly(
                new CognitoProfile.FederatedIdentity("SyntheticSaml", "nameid-1"));
        assertThat(profile.attribute("custom:synthetic_attr")).contains("SYN-V-1");

        ArgumentCaptor<AdminGetUserRequest> request = ArgumentCaptor.forClass(AdminGetUserRequest.class);
        Mockito.verify(client).adminGetUser(request.capture());
        assertThat(request.getValue().userPoolId()).isEqualTo("us-east-1_SYNTHETIC");
        assertThat(request.getValue().username()).isEqualTo("SyntheticSaml_nameid-1");
    }

    @Test
    void aMissingUserIsEmptyAndOtherErrorsPropagate() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().message("no").build());
        assertThat(reader.read("nobody")).isEmpty();

        Mockito.reset(client);
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("denied").build());
        assertThatThrownBy(() -> reader.read("someone")).isInstanceOf(CognitoIdentityProviderException.class);
    }

    @Test
    void aNativeOrMalformedIdentitiesAttributeMeansNoFederation() {
        assertThat(reader.identities(null)).isEmpty();
        assertThat(reader.identities("not json")).isEmpty();
        assertThat(reader.identities("{\"providerName\":\"x\"}")).isEmpty();
        assertThat(reader.identities("[{\"userId\":\"u\"}]")).isEmpty();
    }

    // --- configuration ---------------------------------------------------------------------

    @Configuration
    @EnableConfigurationProperties(AuthorizationProperties.class)
    static class PropertiesOnly {
        @Bean
        Object enrollmentGuard(AuthorizationProperties properties) {
            if (properties.getEnrollment().isEnabled()) {
                edu.bu.archive.config.AuthorizationConfiguration.requireEnrollmentSettings(properties.getEnrollment());
            }
            return new Object();
        }
    }

    @Test
    void enrollmentEnabledWithMissingSettingsRefusesToStartWithAClearMessage() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("app.authorization.enrollment.enabled=true",
                        "app.authorization.enrollment.region=us-east-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("app.authorization.enrollment.user-pool-id")
                            .hasMessageContaining("app.authorization.enrollment.identifier-attribute")
                            .hasMessageNotContaining("app.authorization.enrollment.region");
                });
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
