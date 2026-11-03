package edu.bu.archive.adapter.out.cognito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;

/**
 * The read-only Cognito adapter.
 *
 * <p>The shapes asserted here were taken from the live DEV pool on 2026-11-03,
 * not invented: 6 federated users whose username is
 * {@code BUShibboleth_<name>@bu.edu} and whose {@code sub} is a UUID, and 7
 * native users whose username equals their {@code sub}. {@code AdminGetUser}
 * resolves a {@code sub} supplied in the {@code Username} field for both
 * kinds, which is why {@code ListUsers} is not requested.
 */
@DisplayName("Read-only Cognito user-pool directory")
class CognitoIdentityProviderDirectoryTest {

    private static final String POOL = "us-east-1_VJ4ekQ27c";
    private static final String SUBJECT = "0fa15e00-0000-4000-8000-000000000fab";

    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoIdentityProviderDirectory directory =
            new CognitoIdentityProviderDirectory(client);

    private static AdminGetUserResponse user(String subject, String username, boolean enabled, String status) {
        return AdminGetUserResponse.builder()
                .username(username)
                .enabled(enabled)
                .userStatus(status)
                .userAttributes(List.of(
                        AttributeType.builder().name("sub").value(subject).build(),
                        AttributeType.builder().name("email").value("someone@bu.edu").build()))
                .build();
    }

    @Test
    void aFederatedUserIsFoundBySubjectEvenThoughItsUsernameIsDifferent() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(user(SUBJECT, "BUShibboleth_someone@bu.edu", true, "EXTERNAL_PROVIDER"));

        CognitoDirectory.PoolUser found = directory.findBySubject(POOL, SUBJECT).orElseThrow();

        assertThat(found.subject()).isEqualTo(SUBJECT);
        assertThat(found.username()).isEqualTo("BUShibboleth_someone@bu.edu");
        assertThat(found.isFederated()).isTrue();
        assertThat(found.enabled()).isTrue();
        assertThat(found.status()).isEqualTo("EXTERNAL_PROVIDER");
    }

    @Test
    void aNativeUserIsFoundAndReportedAsNotFederated() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(user(SUBJECT, SUBJECT, true, "CONFIRMED"));

        CognitoDirectory.PoolUser found = directory.findBySubject(POOL, SUBJECT).orElseThrow();

        assertThat(found.isFederated())
                .as("a native user's username is its own sub, which is not a Shibboleth username")
                .isFalse();
        assertThat(found.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void theSubjectIsLookedUpByTheSubItselfAndListUsersIsNeverCalled() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(user(SUBJECT, "BUShibboleth_someone@bu.edu", true, "EXTERNAL_PROVIDER"));

        directory.findBySubject(POOL, SUBJECT);

        var request = org.mockito.ArgumentCaptor.forClass(AdminGetUserRequest.class);
        verify(client).adminGetUser(request.capture());
        assertThat(request.getValue().userPoolId()).isEqualTo(POOL);
        assertThat(request.getValue().username())
                .as("the operator types a sub; Cognito resolves it in the Username field")
                .isEqualTo(SUBJECT);
        verify(client, never()).listUsers(any(software.amazon.awssdk.services.cognitoidentityprovider.model
                .ListUsersRequest.class));
    }

    @Test
    void aDisabledUserIsReturnedRatherThanHiddenSoTheCallerCanRefuseItExplicitly() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(user(SUBJECT, "BUShibboleth_someone@bu.edu", false, "EXTERNAL_PROVIDER"));

        CognitoDirectory.PoolUser found = directory.findBySubject(POOL, SUBJECT).orElseThrow();

        assertThat(found.enabled())
                .as("'disabled' and 'absent' are different refusals and must stay distinguishable")
                .isFalse();
    }

    @Test
    void anAbsentSubjectIsEmptyRatherThanAnError() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().message("User does not exist.").build());

        assertThat(directory.findBySubject(POOL, SUBJECT)).isEmpty();
    }

    @Test
    void aResponseWhoseSubDoesNotMatchTheRequestIsNotAccepted() {
        /*
         * Should be unreachable - username and sub are both unique within a
         * pool - which is precisely why it is asserted rather than assumed.
         * Resolving a subject and trusting whatever came back would make the
         * whole lookup a formality.
         */
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(user("11111111-2222-3333-4444-555555555555",
                        "BUShibboleth_someone@bu.edu", true, "EXTERNAL_PROVIDER"));

        assertThat(directory.findBySubject(POOL, SUBJECT))
                .as("a user that is not the requested subject is not the requested subject")
                .isEmpty();
    }

    @Test
    void aResponseWithNoSubAttributeIsNotAccepted() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder()
                        .username("BUShibboleth_someone@bu.edu")
                        .enabled(true)
                        .userStatus("EXTERNAL_PROVIDER")
                        .userAttributes(List.of())
                        .build());

        assertThat(directory.findBySubject(POOL, SUBJECT)).isEmpty();
    }

    @Test
    void aMissingPermissionIsRethrownAndNeverReadAsAnAbsentSubject() {
        /*
         * The important one. If this returned empty, a revoked or
         * never-granted cognito-idp:AdminGetUser permission would be reported
         * to the operator as "that subject is not in the pool" - a clean
         * refusal for the wrong reason, and one they might try to fix by
         * picking a different subject.
         */
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(NotAuthorizedException.builder()
                        .message("not authorized")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("AccessDeniedException").build())
                        .build());

        assertThatThrownBy(() -> directory.findBySubject(POOL, SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be read")
                .hasMessageContaining("AccessDeniedException");
    }

    @Test
    void throttlingIsRethrownToo() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(TooManyRequestsException.builder()
                        .message("slow down")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("TooManyRequestsException").build())
                        .build());

        assertThatThrownBy(() -> directory.findBySubject(POOL, SUBJECT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBlankPoolOrSubjectIsRefusedBeforeAnyApiCall() {
        for (String[] bad : new String[][] {{null, SUBJECT}, {"", SUBJECT}, {POOL, null}, {POOL, "  "}}) {
            assertThatThrownBy(() -> directory.findBySubject(bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(client, never()).adminGetUser(any(AdminGetUserRequest.class));
    }

    @Test
    void theAdapterExposesNoWriteOperation() {
        /*
         * A structural assertion: the class must offer only the port's single
         * read method, so no future caller can reach a create, update, delete
         * or group operation through it. The SDK client is private and never
         * returned.
         */
        List<String> declared = java.util.Arrays.stream(
                        CognitoIdentityProviderDirectory.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();
        assertThat(declared)
                .as("only the read port method may be public")
                .containsExactly("findBySubject");
    }
}
