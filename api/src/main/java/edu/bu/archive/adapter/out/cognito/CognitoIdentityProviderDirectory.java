package edu.bu.archive.adapter.out.cognito;

import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import edu.bu.archive.application.authorization.admin.provisioning.CognitoDirectory;

/**
 * Reads one user from the real Cognito user pool. Nothing else.
 *
 * <p>Only {@link AdminGetUserRequest} is issued, so the task role needs only
 * {@code cognito-idp:AdminGetUser} on one pool. Notably it does <em>not</em>
 * need {@code ListUsers}, which can enumerate the entire pool - see "Why
 * AdminGetUser is enough" below. No create, update, delete or group operation
 * exists on this class, and the client is never handed out.
 *
 * <h2>Why AdminGetUser is enough</h2>
 *
 * An operator types an immutable {@code sub}, not a username, and
 * {@code AdminGetUser} takes a {@code Username}. Those are usually different
 * values here: of the 13 users in the DEV pool, the 6 federated ones have a
 * username of the form {@code BUShibboleth_<name>@bu.edu} while their
 * {@code sub} is a UUID, and only the 7 native ones have username == sub.
 *
 * <p>Verified against the live DEV pool on 2026-11-03: Cognito accepts a
 * {@code sub} in the {@code Username} field and resolves it for federated
 * users too, returning the real username. So the broader {@code ListUsers}
 * with a {@code sub="..."} filter - which also works - is not needed, and is
 * deliberately not requested.
 *
 * <h2>The returned sub is verified, not assumed</h2>
 *
 * The response's own {@code sub} attribute is compared to the requested
 * subject, and a mismatch yields {@link Optional#empty()} rather than a user.
 * Resolving a subject and then trusting whatever came back would make the
 * lookup a formality; this is the same rule
 * {@link edu.bu.archive.application.authorization.ValidatedCognitoIdentity}
 * already prescribes for the token path.
 *
 * <h2>Failure is not absence</h2>
 *
 * {@link UserNotFoundException} means the subject genuinely is not in the pool
 * and maps to {@link Optional#empty()}. Every other failure - no permission,
 * throttling, the wrong pool, a network fault - is rethrown, so the caller
 * refuses a confirmed enrollment instead of recording "subject absent". A
 * tool error is not a negative result.
 */
public final class CognitoIdentityProviderDirectory implements CognitoDirectory {

    private final CognitoIdentityProviderClient client;

    public CognitoIdentityProviderDirectory(CognitoIdentityProviderClient client) {
        this.client = client;
    }

    @Override
    public Optional<PoolUser> findBySubject(String userPoolId, String subject) {
        if (userPoolId == null || userPoolId.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("a user pool id and a subject are both required");
        }

        AdminGetUserResponse response;
        try {
            response = client.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(subject)
                    .build());
        } catch (UserNotFoundException absent) {
            return Optional.empty();
        } catch (CognitoIdentityProviderException failure) {
            /*
             * Rethrown deliberately. Returning empty here would let a missing
             * permission or a throttle masquerade as "this subject does not
             * exist", and the caller treats absence as a clean refusal while
             * treating a failure as unverifiable.
             */
            throw new IllegalStateException(
                    "the Cognito user pool could not be read: "
                            + failure.awsErrorDetails().errorCode(), failure);
        }

        String resolvedSubject = attribute(response.userAttributes(), "sub");
        if (resolvedSubject == null || !resolvedSubject.equals(subject)) {
            /*
             * Cognito resolved the Username to SOME user, but not the one
             * whose sub was asked for. Both values are unique within a pool
             * so this should be unreachable - which is exactly why it must
             * not be assumed away.
             */
            return Optional.empty();
        }

        return Optional.of(new PoolUser(
                resolvedSubject,
                response.username(),
                Boolean.TRUE.equals(response.enabled()),
                response.userStatusAsString()));
    }

    private static String attribute(List<AttributeType> attributes, String name) {
        if (attributes == null) {
            return null;
        }
        for (AttributeType attribute : attributes) {
            if (name.equals(attribute.name())) {
                return attribute.value();
            }
        }
        return null;
    }
}
