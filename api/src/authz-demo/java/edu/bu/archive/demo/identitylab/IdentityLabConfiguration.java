package edu.bu.archive.demo.identitylab;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;


/**
 * LOCAL SAML INTEGRATION - COGNITO SIMULATED (archive side of identity-lab/).
 *
 * <p>Exists only in builds made with -Pauthz-demo; activates only under the
 * "identity-lab" profile. Unlike the persona demo it changes NOTHING about
 * authentication: the production SecurityConfiguration validates real signed
 * access tokens from the lab's simulated Cognito, and the production
 * JwtCurrentIdentityProvider maps them to identities. This class only checks
 * that the run really is the local lab.
 */
@Configuration
@Profile("identity-lab")
public class IdentityLabConfiguration {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");
    static final String LAB_ISSUER_PREFIX = "https://localhost:9443/";

    public IdentityLabConfiguration(
            Environment environment,
            @Value("${app.security.enabled:true}") boolean securityEnabled,
            @Value("${app.authorization.enforcement-enabled:false}") boolean enforcementEnabled,
            @Value("${app.identity-lab.disposable-database:false}") boolean disposableDatabase,
            @Value("${app.security.cognito.issuer-uri:}") String issuerUri,
            @Value("${spring.datasource.url:}") String datasourceUrl
    ) {
        if (environment.acceptsProfiles(Profiles.of("authz-demo"))) {
            throw new IllegalStateException("identity-lab must not run with the authz-demo persona profile");
        }
        if (!securityEnabled) {
            throw new IllegalStateException("identity-lab requires app.security.enabled=true (real token validation)");
        }
        if (!enforcementEnabled) {
            throw new IllegalStateException("identity-lab requires app.authorization.enforcement-enabled=true");
        }
        if (!disposableDatabase) {
            throw new IllegalStateException("identity-lab requires app.identity-lab.disposable-database=true");
        }
        if (!issuerUri.startsWith(LAB_ISSUER_PREFIX)) {
            throw new IllegalStateException("identity-lab accepts only the local simulated issuer, not: " + issuerUri);
        }
        String host = hostOf(datasourceUrl);
        if (!LOOPBACK.contains(host)) {
            throw new IllegalStateException("identity-lab refuses a non-loopback database host: " + host);
        }
    }

    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "";
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        return uri.getHost() == null ? "" : uri.getHost();
    }

    // IO grants use the production rule (Award account number, approved decision D-A).
}
