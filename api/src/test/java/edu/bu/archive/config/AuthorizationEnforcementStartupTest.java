package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.AuthorizationProperties;

/**
 * Enforcement must not be switchable on halfway.
 *
 * <p>Before this, {@code app.authorization.enforcement-enabled=true} alone
 * started cleanly and denied every record to every user - administrators
 * included - because the gate checks the policy before it consults any grant
 * and no strategy has a default. The symptom is an archive that looks empty,
 * which reads as a broken deployment rather than a configuration mistake.
 *
 * <p>These tests pin both directions: the refusal when something is missing,
 * and - just as important - that nothing changes for the environments that
 * exist today, where enforcement is off.
 */
@DisplayName("Enforcement configuration is validated at startup")
class AuthorizationEnforcementStartupTest {

    @Nested
    @DisplayName("Enforcement off - today's environments")
    class EnforcementOff {

        @Test
        @DisplayName("an unconfigured, unenforced application starts, exactly as DEV does now")
        void unconfiguredAndUnenforcedIsFine() {
            AuthorizationProperties properties = new AuthorizationProperties();

            assertThat(properties.isEnforcementEnabled()).isFalse();
            assertThat(properties.enforcementConfigurationProblems()).isEmpty();
            assertThatCode(() -> AuthorizationConfiguration.requireEnforcementSettings(properties))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("nothing is required while it is off, however incomplete")
        void nothingIsRequiredWhileOff() {
            /*
             * DEV's task definition 85 carries no APP_AUTHORIZATION_* variable
             * at all. That must stay a valid, startable configuration - this
             * safeguard is for the moment someone turns it on, not a new
             * requirement on everyone who has not.
             */
            AuthorizationProperties properties = new AuthorizationProperties();
            properties.setParentAwardDepth(-5);

            assertThat(properties.enforcementConfigurationProblems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Enforcement on")
    class EnforcementOn {

        @Test
        @DisplayName("the flag alone refuses to start, and names every missing setting at once")
        void theFlagAloneIsRefused() {
            AuthorizationProperties properties = new AuthorizationProperties();
            properties.setEnforcementEnabled(true);

            List<String> problems = properties.enforcementConfigurationProblems();

            assertThat(problems)
                    .as("all of them, not the first - otherwise it is five restarts "
                            + "to discover five problems")
                    .hasSize(5);
            assertThat(String.join("\n", problems))
                    .contains("app.authorization.version-scope")
                    .contains("app.authorization.department-match")
                    .contains("app.authorization.contact-derivation")
                    .contains("app.authorization.research-staff-roles")
                    .contains("app.authorization.max-sign-in-age");

            assertThatThrownBy(() -> AuthorizationConfiguration.requireEnforcementSettings(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("would deny every record to every user")
                    .hasMessageContaining("app.authorization.enforcement-enabled=false");
        }

        @Test
        @DisplayName("the diagnostic lists the allowed values, so the fix does not need the source")
        void theDiagnosticNamesTheAllowedValues() {
            AuthorizationProperties properties = new AuthorizationProperties();
            properties.setEnforcementEnabled(true);

            String message = String.join("\n", properties.enforcementConfigurationProblems());

            for (AuthorizationPolicy.VersionScope value : AuthorizationPolicy.VersionScope.values()) {
                assertThat(message).contains(value.name());
            }
            for (AuthorizationPolicy.DepartmentMatch value : AuthorizationPolicy.DepartmentMatch.values()) {
                assertThat(message).contains(value.name());
            }
        }

        @Test
        @DisplayName("a fully configured enforcement starts")
        void aFullyConfiguredEnforcementStarts() {
            AuthorizationProperties properties = configured();

            assertThat(properties.enforcementConfigurationProblems()).isEmpty();
            assertThat(properties.policy()).isPresent();
            assertThatCode(() -> AuthorizationConfiguration.requireEnforcementSettings(properties))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("max-sign-in-age is required even though it is not part of the policy")
        void maxSignInAgeIsRequiredSeparately() {
            /*
             * policy() does not include it, so a configuration missing only
             * this one would have produced a VALID policy and then denied
             * every enforced request inside RecordAuthorizationService - a
             * second late failure, in a different place, with a different
             * symptom.
             */
            AuthorizationProperties properties = configured();
            properties.setMaxSignInAge(null);

            assertThat(properties.policy())
                    .as("the policy is complete, which is exactly why this needs its own check")
                    .isPresent();
            assertThat(properties.enforcementConfigurationProblems())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("app.authorization.max-sign-in-age");
        }

        @Test
        @DisplayName("a non-positive max-sign-in-age is refused rather than treated as 'never expires'")
        void aNonPositiveMaxSignInAgeIsRefused() {
            AuthorizationProperties properties = configured();
            properties.setMaxSignInAge(Duration.ZERO);

            assertThat(properties.enforcementConfigurationProblems())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("must be positive");
        }

        @Test
        @DisplayName("an empty research-staff-roles set is refused - it silently disables SEC-003")
        void anEmptyRoleSetIsRefused() {
            AuthorizationProperties properties = configured();
            properties.setResearchStaffRoles(Set.of());

            assertThat(properties.enforcementConfigurationProblems())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("research-staff-roles");
        }

        @Test
        @DisplayName("a parent-award depth above the supported maximum is refused, not silently clamped")
        void anUnsupportedDepthIsRefused() {
            /*
             * The traversal clamps to MAX_PARENT_AWARD_DEPTH. Clamping is the
             * right runtime behaviour and the wrong configuration behaviour:
             * a depth of 25 would be accepted, reported in no log, and mean
             * 10. Refusing makes the configured number honest.
             */
            AuthorizationProperties properties = configured();
            properties.setParentAwardDepth(AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH + 15);

            assertThat(properties.enforcementConfigurationProblems())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("would clamp to the maximum");
        }

        @Test
        @DisplayName("the supported maximum itself is accepted")
        void theMaximumItselfIsAccepted() {
            AuthorizationProperties properties = configured();
            properties.setParentAwardDepth(AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH);

            assertThat(properties.enforcementConfigurationProblems()).isEmpty();
        }

        @Test
        @DisplayName("a negative depth is refused")
        void aNegativeDepthIsRefused() {
            AuthorizationProperties properties = configured();
            properties.setParentAwardDepth(-1);

            assertThat(properties.enforcementConfigurationProblems())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("must not be negative");
        }
    }

    @Nested
    @DisplayName("It runs at startup, not merely on request")
    class WiredIntoStartup {

        @Test
        @DisplayName("the validating bean factory itself throws, which is what fails the context")
        void theBeanFactoryThrows() {
            AuthorizationProperties properties = new AuthorizationProperties();
            properties.setEnforcementEnabled(true);

            assertThatThrownBy(() -> new AuthorizationConfiguration()
                    .authorizationEnforcementSettings(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.authorization.version-scope");
        }

        @Test
        @DisplayName("and it is an eager singleton @Bean, so Spring calls it before the app is healthy")
        void theBeanIsEagerAndNotLazy() throws Exception {
            /*
             * A @Bean that nothing injects is still instantiated at refresh,
             * provided it is a singleton and not @Lazy. That is the whole
             * mechanism here: no other bean depends on this one, so if it
             * were lazy the validation would never run and the safeguard
             * would be decorative.
             */
            var method = AuthorizationConfiguration.class
                    .getDeclaredMethod("authorizationEnforcementSettings", AuthorizationProperties.class);

            assertThat(method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                    .as("must be a @Bean, or it is never called")
                    .isTrue();
            assertThat(method.isAnnotationPresent(org.springframework.context.annotation.Lazy.class))
                    .as("@Lazy would defer it past startup, which defeats the point")
                    .isFalse();
            assertThat(method.isAnnotationPresent(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean.class))
                    .as("a conditional would let a stray bean silently replace the safeguard")
                    .isFalse();
        }

        @Test
        @DisplayName("with enforcement off the same factory returns normally")
        void theBeanFactoryIsQuietWhenOff() {
            assertThatCode(() -> new AuthorizationConfiguration()
                    .authorizationEnforcementSettings(new AuthorizationProperties()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the traversal ceiling and the validated ceiling are the same number")
    void theCeilingIsNotDuplicated() {
        /*
         * JdbcAwardHierarchy now reads AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH
         * rather than carrying its own 10. Two constants that agree by
         * coincidence are one edit away from disagreeing, and the symptom
         * would be a configuration the validator accepts and the query ignores.
         */
        assertThat(AuthorizationPolicy.MAX_PARENT_AWARD_DEPTH).isEqualTo(10);
    }

    private static AuthorizationProperties configured() {
        AuthorizationProperties properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
        properties.setResearchStaffRoles(Set.of("PI"));
        properties.setMaxSignInAge(Duration.ofHours(12));
        return properties;
    }
}
