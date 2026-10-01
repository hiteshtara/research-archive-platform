package edu.bu.archive.config;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * Production identity source: only a JWT the resource server has already
 * validated (SecurityConfiguration). Any other authentication - or none, as
 * under the local permit-all profile - yields no identity, which an enforced
 * deployment treats as not provisioned.
 */
public class JwtCurrentIdentityProvider implements CurrentIdentityProvider {

    @Override
    public Optional<ValidatedCognitoIdentity> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                return Optional.of(ValidatedCognitoIdentity.fromValidatedAccessToken(jwt.getToken()));
            } catch (IllegalArgumentException notAnAccessToken) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
