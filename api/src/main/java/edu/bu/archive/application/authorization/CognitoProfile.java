package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A user-pool profile as read server-side (AdminGetUser) for enrollment.
 * {@code identities} is Cognito's own federation record for the profile
 * (set by Cognito, not user-editable); {@code attributes} are the profile's
 * user attributes, including any mapped from the SAML assertion.
 */
public record CognitoProfile(
        String sub,
        String username,
        boolean enabled,
        List<FederatedIdentity> identities,
        Map<String, String> attributes
) {

    public CognitoProfile {
        identities = identities == null ? List.of() : List.copyOf(identities);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name)).map(String::trim).filter(v -> !v.isEmpty());
    }

    /** One entry of the profile's {@code identities} attribute. */
    public record FederatedIdentity(String providerName, String userId) {
    }

    @Override
    public String toString() {
        return "CognitoProfile[****]";
    }
}
