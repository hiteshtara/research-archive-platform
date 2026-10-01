package edu.bu.archive.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import edu.bu.archive.adapter.out.cognito.AwsCognitoProfileReader;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcEnrollmentStore;
import edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CognitoProfileReader;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityEnrollment;
import edu.bu.archive.application.authorization.IdentityEnrollmentService;
import edu.bu.archive.application.authorization.IdentityLinkRepository;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.IoResolver;
import edu.bu.archive.application.authorization.IoSqlStrategy;
import edu.bu.archive.application.authorization.RecordAuthorizationService;
import edu.bu.archive.application.authorization.RecordFactsRepository;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Record authorization wiring. Enforcement is OFF unless
 * app.authorization.enforcement-enabled=true; the policy strategies have no
 * defaults. The real IO mapping and identity source are the inert/production
 * ones; only a separate demo build can supply alternatives.
 *
 * <p>Enrollment (app.authorization.enrollment.*) is OFF unless
 * app.authorization.enrollment.enabled=true. Enabled with any required
 * setting missing, the application refuses to start. The Cognito client is
 * created only when enrollment is enabled.
 */
@Configuration
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CurrentIdentityProvider currentIdentityProvider() {
        return new JwtCurrentIdentityProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    IoResolver ioResolver(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        // IO grants match the Award account number (approved decision D-A).
        return new edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoResolver(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    IoSqlStrategy ioSqlStrategy() {
        return new edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoSql();
    }

    @Bean
    IdentityResolver identityResolver(IdentityLinkRepository links) {
        return new IdentityResolver(links);
    }

    @Bean
    AccessScopeResolver accessScopeResolver(AccessGrantRepository grants, AuthorizationProperties properties,
                                            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new AccessScopeResolver(grants, Clock.systemUTC(), properties::policy,
                new edu.bu.archive.adapter.out.persistence.authorization.JdbcContactRelationships(jdbc));
    }

    @Bean
    RecordAuthorizationService recordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            JdbcUnitHierarchy units,
            IoSqlStrategy ioSql,
            IdentityEnrollment enrollment
    ) {
        return new RecordAuthorizationService(properties, identities, identityResolver, scopeResolver,
                facts, units, units, ioSql, enrollment);
    }

    @Bean
    IdentityEnrollment identityEnrollment(AuthorizationProperties properties,
                                          ObjectProvider<CognitoProfileReader> profileReaders,
                                          org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                          PlatformTransactionManager transactionManager) {
        AuthorizationProperties.Enrollment settings = properties.getEnrollment();
        if (!settings.isEnabled()) {
            return IdentityEnrollment.DISABLED;
        }
        requireEnrollmentSettings(settings);
        CognitoProfileReader reader = profileReaders.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("app.authorization.enrollment.enabled=true but no CognitoProfileReader "
                    + "is available");
        }
        return new IdentityEnrollmentService(
                new IdentityEnrollmentService.Settings(settings.getSamlProviderName(),
                        settings.getIdentifierAttribute(), settings.getCrosswalkAttributeName(),
                        Duration.ofSeconds(Math.max(0, settings.getRefusalRetrySeconds())),
                        settings.getUserPoolId().trim(), settings.getUsernameCaseSensitive()),
                reader,
                new JdbcEnrollmentStore(jdbc, new TransactionTemplate(transactionManager)),
                Clock.systemUTC());
    }

    /** AdminGetUser on the configured pool; default credentials provider chain; no credentials in config. */
    @Bean
    @ConditionalOnProperty(name = "app.authorization.enrollment.enabled", havingValue = "true")
    CognitoProfileReader cognitoProfileReader(AuthorizationProperties properties) {
        AuthorizationProperties.Enrollment settings = properties.getEnrollment();
        requireEnrollmentSettings(settings);
        var builder = CognitoIdentityProviderClient.builder()
                .region(Region.of(settings.getRegion().trim()))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)));
        if (settings.getEndpointOverride() != null && !settings.getEndpointOverride().isBlank()) {
            // Only a local test endpoint (the identity lab's simulated Cognito) may replace AWS.
            String host = URI.create(settings.getEndpointOverride().trim()).getHost();
            if (host == null || !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(host)) {
                throw new IllegalStateException(
                        "app.authorization.enrollment.endpoint-override is allowed only for a loopback host");
            }
            // Only for a local lab's simulated user pool.
            builder.endpointOverride(URI.create(settings.getEndpointOverride().trim()));
        }
        return new AwsCognitoProfileReader(builder.build(), settings.getUserPoolId().trim());
    }

    public static void requireEnrollmentSettings(AuthorizationProperties.Enrollment settings) {
        List<String> missing = settings.missingSettings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Record-authorization enrollment is enabled "
                    + "(app.authorization.enrollment.enabled=true) but these required settings are missing: "
                    + String.join(", ", missing)
                    + ". Set them, or set app.authorization.enrollment.enabled=false.");
        }
    }
}
