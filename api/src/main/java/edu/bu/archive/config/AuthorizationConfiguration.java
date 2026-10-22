package edu.bu.archive.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * Record authorization wiring. Enforcement is OFF unless
 * app.authorization.enforcement-enabled=true; the policy strategies have no
 * defaults. The real IO mapping and identity source are the inert/production
 * ones; only a separate demo build can supply alternatives.
 *
 * <p>PHASE 1 SCOPE: no CognitoProfileReader implementation is shipped. The
 * enrollment scaffolding is present, but with no reader bean the
 * identityEnrollment factory below cannot build a live enrolment service, so
 * enabling app.authorization.enrollment.enabled=true fails fast at startup
 * instead of auto-linking identities. Automatic linking is deliberately
 * impossible until BU confirms which attribute is the institutional
 * identifier; until then identities are linked by an administrator
 * (IdentityLinkStatus/method ADMIN_VERIFIED). The AWS Cognito reader and its
 * SDK dependency arrive with Phase 2.
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
