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

    /**
     * SEC-008 activity auditing.
     *
     * <p>Three independent gates keep this inert: the V089 table must exist,
     * {@code archive_api} must hold INSERT on it, and
     * {@code app.authorization.activity-audit.enabled} must be true. None of
     * the three is satisfied in any deployed environment, so this bean
     * resolves to a disabled recorder and every call site is a no-op.
     *
     * <p>Wired as a bean even while disabled so the call sites compile,
     * run and are tested in exactly the shape they will have when it is
     * switched on - rather than being added hurriedly on the day it is.
     */
    @Bean
    edu.bu.archive.application.authorization.audit.ActivityAuditProperties activityAuditProperties(
            org.springframework.core.env.Environment environment) {
        var properties =
                new edu.bu.archive.application.authorization.audit.ActivityAuditProperties();
        properties.setEnabled(environment.getProperty(
                "app.authorization.activity-audit.enabled", Boolean.class, false));
        String routine = environment.getProperty(
                "app.authorization.activity-audit.routine-read-failure-policy");
        if (routine != null && !routine.isBlank()) {
            properties.setRoutineReadFailurePolicy(
                    edu.bu.archive.application.authorization.audit.ActivityAuditor.FailurePolicy
                            .valueOf(routine.trim().toUpperCase()));
        }
        String sensitive = environment.getProperty(
                "app.authorization.activity-audit.sensitive-release-failure-policy");
        if (sensitive != null && !sensitive.isBlank()) {
            properties.setSensitiveReleaseFailurePolicy(
                    edu.bu.archive.application.authorization.audit.ActivityAuditor.FailurePolicy
                            .valueOf(sensitive.trim().toUpperCase()));
        }
        properties.setRetentionDays(environment.getProperty(
                "app.authorization.activity-audit.retention-days", Integer.class,
                edu.bu.archive.application.authorization.audit.ActivityAuditProperties
                        .PROPOSED_RETENTION_DAYS));
        properties.validate();
        return properties;
    }

    @Bean
    edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder archiveActivityRecorder(
            edu.bu.archive.application.authorization.audit.ActivityAuditProperties properties,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.jdbc.core.simple.JdbcClient> jdbc,
            org.springframework.beans.factory.ObjectProvider<
                    com.fasterxml.jackson.databind.ObjectMapper> json,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
                    meterRegistry,
            AuthorizationProperties authorization) {

        /*
         * Resolved BEFORE the datasource is touched. With collection off -
         * which is every deployed environment - this bean must not require a
         * JdbcClient at all, or it would drag a datasource into contexts that
         * deliberately have none.
         */
        if (!properties.isEnabled()) {
            return edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder
                    .disabled(Clock.systemUTC());
        }

        var registry = meterRegistry.getIfAvailable();
        edu.bu.archive.application.authorization.audit.ActivityAuditor.AuditGapMetrics metrics =
                registry == null
                        ? edu.bu.archive.application.authorization.audit.ActivityAuditor
                                .AuditGapMetrics.NONE
                        : (action, disposition) -> registry.counter("archive.audit.gap",
                                "action", action.name(), "disposition", disposition).increment();

        var auditor = new edu.bu.archive.application.authorization.audit.ActivityAuditor(
                new edu.bu.archive.adapter.out.persistence.authorization.JdbcActivityAuditPort(
                        jdbc.getObject(), json.getObject()),
                properties.getSensitiveReleaseFailurePolicy(),
                properties.getRoutineReadFailurePolicy(),
                metrics);

        return new edu.bu.archive.application.authorization.audit.ArchiveActivityRecorder(
                auditor, Clock.systemUTC(),
                () -> java.util.Optional.empty(),
                () -> null);
    }

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
        /*
         * SEC-005: the real hierarchy, not AwardHierarchy.SELF_ONLY. With the
         * SELF_ONLY default a PARENT_AWARD grant would quietly reach only the
         * Award it names and look like a half-working feature, so the wiring
         * is explicit here rather than left to a default.
         */
        return new AccessScopeResolver(grants, Clock.systemUTC(), properties::policy,
                new edu.bu.archive.adapter.out.persistence.authorization.JdbcContactRelationships(jdbc),
                new edu.bu.archive.adapter.out.persistence.authorization.JdbcAwardHierarchy(jdbc));
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

    /**
     * Refuse to start when enforcement is on and its configuration is not.
     *
     * <h2>Why startup and not request time</h2>
     *
     * <p>Without this, {@code app.authorization.enforcement-enabled=true} on
     * its own starts cleanly and then denies EVERY record to EVERY user -
     * including a central administrator - because
     * {@link RecordAuthorizationGate} checks the policy before it consults any
     * grant, and none of the policy strategies has a default. The gate even
     * computes {@code Status.ENFORCED_POLICY_NOT_CONFIGURED} for exactly this
     * case, but nothing consumes it: no endpoint, no health indicator, no log
     * line. An operator who flipped the flag would see a dark archive and no
     * explanation, and the natural reading of a dark archive is "the deploy
     * broke the database".
     *
     * <p>Failing at startup turns that into a message naming the properties,
     * before the service ever reports healthy. The deployment stops; the
     * previous task definition keeps serving.
     *
     * <p>Enforcement off is untouched - the method returns immediately, so
     * every environment that exists today starts exactly as before.
     */
    @Bean
    AuthorizationEnforcementSettings authorizationEnforcementSettings(AuthorizationProperties properties) {
        requireEnforcementSettings(properties);
        return new AuthorizationEnforcementSettings(properties.isEnforcementEnabled());
    }

    /** Marker that the enforcement configuration was validated at startup. */
    public record AuthorizationEnforcementSettings(boolean enforcementEnabled) {
    }

    public static void requireEnforcementSettings(AuthorizationProperties properties) {
        List<String> problems = properties.enforcementConfigurationProblems();
        if (problems.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "Record-level authorization is enabled (app.authorization.enforcement-enabled=true) "
                        + "but its configuration is incomplete, and starting this way would deny every "
                        + "record to every user, administrators included. "
                        + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + ":"
                        + problems.stream().map(problem -> System.lineSeparator() + "  - " + problem)
                                .collect(java.util.stream.Collectors.joining())
                        + System.lineSeparator()
                        + "Set them, or set app.authorization.enforcement-enabled=false.");
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
