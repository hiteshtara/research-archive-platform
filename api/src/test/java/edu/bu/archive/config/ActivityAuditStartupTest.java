package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import edu.bu.archive.application.authorization.audit.ActivityAuditor.FailurePolicy;

/**
 * Startup behaviour of the SEC-008 settings, through the same bean method the
 * application uses. FAIL_OPEN for routine views is a PROPOSED policy pending
 * BU approval and is NOT a default (owner direction 2026-11-17).
 */
class ActivityAuditStartupTest {

    private final AuthorizationConfiguration configuration = new AuthorizationConfiguration();

    @Test
    @DisplayName("flag off: starts with no audit settings at all")
    void flagOffNeedsNothing() {
        var properties = configuration.activityAuditProperties(new MockEnvironment());
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRoutineReadFailurePolicy()).isNull();
    }

    @Test
    @DisplayName("flag on without routine-read-failure-policy: refuses to start, naming the property")
    void flagOnWithoutRoutinePolicyRefusesToStart() {
        var environment = new MockEnvironment()
                .withProperty("app.authorization.activity-audit.enabled", "true");

        assertThatThrownBy(() -> configuration.activityAuditProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routine-read-failure-policy is not set")
                .hasMessageContaining("FAIL_OPEN")
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    @DisplayName("flag on with an explicit routine policy starts; sensitive releases default FAIL_CLOSED")
    void flagOnWithAnExplicitPolicyStarts() {
        var environment = new MockEnvironment()
                .withProperty("app.authorization.activity-audit.enabled", "true")
                .withProperty("app.authorization.activity-audit.routine-read-failure-policy",
                        "fail_closed");

        var properties = configuration.activityAuditProperties(environment);
        assertThat(properties.getRoutineReadFailurePolicy()).isEqualTo(FailurePolicy.FAIL_CLOSED);
        assertThat(properties.getSensitiveReleaseFailurePolicy())
                .isEqualTo(FailurePolicy.FAIL_CLOSED);
    }
}
