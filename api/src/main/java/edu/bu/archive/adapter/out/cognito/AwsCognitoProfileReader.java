package edu.bu.archive.adapter.out.cognito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.CognitoProfile;
import edu.bu.archive.application.authorization.CognitoProfileReader;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Reads one profile with {@code AdminGetUser} on the configured pool (IAM:
 * {@code cognito-idp:AdminGetUser} on that pool only). Credentials come from
 * the AWS default provider chain, never from configuration. A missing user
 * is empty; every other error propagates and enrollment fails closed.
 */
public class AwsCognitoProfileReader implements CognitoProfileReader, AutoCloseable {

    static final String IDENTITIES_ATTRIBUTE = "identities";

    private final CognitoIdentityProviderClient client;
    private final String userPoolId;
    private final ObjectMapper json = new ObjectMapper();

    public AwsCognitoProfileReader(CognitoIdentityProviderClient client, String userPoolId) {
        this.client = client;
        this.userPoolId = userPoolId;
    }

    @Override
    public Optional<CognitoProfile> read(String username) {
        AdminGetUserResponse response;
        try {
            response = client.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
        } catch (UserNotFoundException notFound) {
            return Optional.empty();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (AttributeType attribute : response.userAttributes()) {
            if (attribute.name() != null && attribute.value() != null) {
                attributes.put(attribute.name(), attribute.value());
            }
        }
        return Optional.of(new CognitoProfile(
                attributes.get("sub"),
                response.username(),
                !Boolean.FALSE.equals(response.enabled()),
                identities(attributes.get(IDENTITIES_ATTRIBUTE)),
                attributes,
                response.userStatusAsString()));
    }

    /**
     * Cognito's {@code identities} attribute: a JSON array of
     * {@code {"providerName": ..., "userId": ..., ...}}. Unparseable means
     * no federation record (enrollment then refuses the profile).
     */
    List<CognitoProfile.FederatedIdentity> identities(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = json.readTree(raw);
            List<CognitoProfile.FederatedIdentity> result = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    JsonNode provider = node.get("providerName");
                    JsonNode userId = node.get("userId");
                    if (provider != null && provider.isTextual()) {
                        result.add(new CognitoProfile.FederatedIdentity(provider.asText(),
                                userId == null || userId.isNull() ? null : userId.asText()));
                    }
                }
            }
            return result;
        } catch (Exception unparseable) {
            return List.of();
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
