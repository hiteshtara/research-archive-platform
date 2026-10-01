package edu.bu.archive.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.bu.archive.adapter.out.persistence.authorization.JdbcUnitHierarchy;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.AccessScopeResolver;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.DisabledIoResolver;
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
    IoResolver ioResolver() {
        return new DisabledIoResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    IoSqlStrategy ioSqlStrategy() {
        return IoSqlStrategy.NONE;
    }

    @Bean
    IdentityResolver identityResolver(IdentityLinkRepository links) {
        return new IdentityResolver(links);
    }

    @Bean
    AccessScopeResolver accessScopeResolver(AccessGrantRepository grants) {
        return new AccessScopeResolver(grants, Clock.systemUTC());
    }

    @Bean
    RecordAuthorizationService recordAuthorizationService(
            AuthorizationProperties properties,
            CurrentIdentityProvider identities,
            IdentityResolver identityResolver,
            AccessScopeResolver scopeResolver,
            RecordFactsRepository facts,
            JdbcUnitHierarchy units,
            IoSqlStrategy ioSql
    ) {
        return new RecordAuthorizationService(properties, identities, identityResolver, scopeResolver,
                facts, units, units, ioSql);
    }
}
