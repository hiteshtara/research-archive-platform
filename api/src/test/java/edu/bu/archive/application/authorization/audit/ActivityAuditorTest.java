package edu.bu.archive.application.authorization.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityAction;
import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityOutcome;
import edu.bu.archive.application.authorization.audit.ActivityAuditor.FailurePolicy;

/**
 * SEC-008: the audit failure policy, as behaviour rather than as a claim.
 *
 * <p>The requirement splits in two, and so do these tests. Requirement 1 —
 * an audit failure must never grant access — is an invariant and is tested as
 * one: it holds under BOTH policies, because it is not a policy. Requirement 2
 * — whether a failed write blocks an authorized read — is a business decision,
 * so both answers are tested and neither is a default.
 */
class ActivityAuditorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    /** Records what reached it, and can be told to fail. */
    private static final class RecordingPort implements ActivityAuditPort {
        private final List<ActivityAuditEvent> written = new ArrayList<>();
        private boolean broken;

        @Override
        public void append(ActivityAuditEvent event) {
            if (broken) {
                throw new IllegalStateException("audit store unavailable");
            }
            written.add(event);
        }
    }

    private static ActivityAuditEvent permitted() {
        return ActivityAuditEvent.of(NOW, "ARP-SYN-0001", ActivityAction.RECORD_VIEWED,
                "AWARD", "SYN-AWD-A", ActivityOutcome.PERMITTED, "DEPARTMENT", "corr-1");
    }

    private static ActivityAuditEvent denied() {
        return ActivityAuditEvent.of(NOW, "ARP-SYN-0001", ActivityAction.RECORD_ACCESS_DENIED,
                "AWARD", "SYN-AWD-B", ActivityOutcome.DENIED, "OUT_OF_SCOPE", "corr-2");
    }

    // ── requirement 1: an invariant, not a policy ───────────────────────

    @Nested
    @DisplayName("An audit failure never grants access")
    class NeverGrantsAccess {

        @Test
        void aFailedDenialRecordNeverThrows_underEitherPolicy() {
            /*
             * The denial has already happened. If this could throw, a caller
             * catching broadly around the denial path could turn a refusal
             * into some other control flow - which is the one outcome SEC-008
             * forbids. So it is unconditional, not policy-dependent.
             */
            for (FailurePolicy policy : FailurePolicy.values()) {
                var port = new RecordingPort();
                port.broken = true;
                var auditor = new ActivityAuditor(port, policy, policy);
                assertThatCode(() -> auditor.recordDenied(denied()))
                        .as("recordDenied must never throw under " + policy)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        void thePermittedPathRefusesToAcceptADeniedEvent() {
            // Structural: a DENIED outcome cannot be routed through the only
            // method that can throw, so it cannot be converted into one.
            var auditor = new ActivityAuditor(new RecordingPort(), FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_CLOSED);
            assertThatThrownBy(() -> auditor.recordPermitted(denied()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("use recordDenied");
        }

        @Test
        void theStrictestOutcomeOfAnAuditFailureIsRefusal() {
            // FAIL_CLOSED can only ever withhold an access that was already
            // permitted. There is no path from an audit failure to a permit.
            var port = new RecordingPort();
            port.broken = true;
            var auditor = new ActivityAuditor(port, FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_CLOSED);
            assertThatThrownBy(() -> auditor.recordPermitted(permitted()))
                    .isInstanceOf(AuditUnavailableException.class);
            assertThat(AuditUnavailableException.CODE).isEqualTo("AUDIT_UNAVAILABLE");
        }
    }

    // ── requirement 2: a decision, so both answers are real ─────────────

    @Nested
    @DisplayName("The failure policy decides whether a failed write blocks a read")
    class FailurePolicyBehaviour {

        @Test
        void failOpenServesTheReadAndLosesTheRow() {
            var port = new RecordingPort();
            port.broken = true;
            var auditor = new ActivityAuditor(port, FailurePolicy.FAIL_OPEN, FailurePolicy.FAIL_OPEN);

            assertThatCode(() -> auditor.recordPermitted(permitted())).doesNotThrowAnyException();
            assertThat(port.written).as("the row is genuinely lost, not queued").isEmpty();
        }

        @Test
        void failClosedRefusesTheRead() {
            var port = new RecordingPort();
            port.broken = true;
            var auditor = new ActivityAuditor(port, FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_CLOSED);

            assertThatThrownBy(() -> auditor.recordPermitted(permitted()))
                    .isInstanceOf(AuditUnavailableException.class)
                    .hasMessageContaining("FAIL_CLOSED");
        }

        @Test
        void aHealthyStoreBehavesIdenticallyUnderBothPolicies() {
            for (FailurePolicy policy : FailurePolicy.values()) {
                var port = new RecordingPort();
                new ActivityAuditor(port, policy, policy).recordPermitted(permitted());
                assertThat(port.written).as(policy.toString()).hasSize(1);
            }
        }
    }

    // ── collection off ──────────────────────────────────────────────────

    @Test
    @DisplayName("with collection disabled nothing is written and nothing throws")
    void disabledWritesNothing() {
        var auditor = ActivityAuditor.disabled();
        assertThatCode(() -> {
            auditor.recordPermitted(permitted());
            auditor.recordDenied(denied());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabling collection without choosing a policy is a startup failure")
    void enablingWithoutAPolicyFailsFast() {
        /*
         * The point of SEC-008 requirement 2: the availability-versus-audit
         * tradeoff must be chosen by someone, not defaulted by this code.
         */
        var properties = new ActivityAuditProperties();
        properties.setEnabled(true);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routine-read-failure-policy is not set")
                .hasMessageContaining("FAIL_OPEN")
                .hasMessageContaining("FAIL_CLOSED");

        properties.setRoutineReadFailurePolicy(FailurePolicy.FAIL_OPEN);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("collection off needs no policy")
    void disabledNeedsNoPolicy() {
        var properties = new ActivityAuditProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRoutineReadFailurePolicy())
                .as("routine reads have no default - the business must choose")
                .isNull();
        assertThat(properties.getSensitiveReleaseFailurePolicy())
                .as("attachment downloads and exports default to FAIL_CLOSED")
                .isEqualTo(FailurePolicy.FAIL_CLOSED);
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getRetentionDays())
                .as("proposed for review, not an approved BU retention policy")
                .isEqualTo(90);
    }

    // ── two policies, because the tradeoff differs ──────────────────────

    @Nested
    @DisplayName("Sensitive releases and routine reads are governed separately")
    class TwoTierPolicy {

        @Test
        void downloadsAndExportsAreClassedAsSensitiveReleases() {
            assertThat(ActivityAuditor.isSensitiveRelease(ActivityAction.ATTACHMENT_DOWNLOADED)).isTrue();
            assertThat(ActivityAuditor.isSensitiveRelease(ActivityAction.EXPORT_GENERATED)).isTrue();
            assertThat(ActivityAuditor.isSensitiveRelease(ActivityAction.RECORD_VIEWED)).isFalse();
            assertThat(ActivityAuditor.isSensitiveRelease(ActivityAction.SEARCH_PERFORMED)).isFalse();
        }

        @Test
        void aDownloadIsRefusedWhileARoutineReadIsServed() {
            /*
             * The whole point of two policies: with the business direction
             * applied - FAIL_CLOSED for releases, FAIL_OPEN for routine reads
             * - the same audit outage stops a file leaving the system while
             * letting someone keep reading a record on screen.
             */
            var port = new RecordingPort();
            port.broken = true;
            var auditor = new ActivityAuditor(port,
                    FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_OPEN);

            assertThatThrownBy(() -> auditor.recordPermitted(
                    ActivityAuditEvent.of(NOW, "ARP-SYN-0001", ActivityAction.ATTACHMENT_DOWNLOADED,
                            "AWARD", "SYN-AWD-A", ActivityOutcome.PERMITTED, null, "c")))
                    .isInstanceOf(AuditUnavailableException.class);

            assertThatCode(() -> auditor.recordPermitted(permitted()))
                    .as("a routine record view still succeeds")
                    .doesNotThrowAnyException();
        }

        @Test
        void policyForNamesWhichSettingGovernsAnAction() {
            var auditor = new ActivityAuditor(new RecordingPort(),
                    FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_OPEN);
            assertThat(auditor.policyFor(ActivityAction.EXPORT_GENERATED))
                    .isEqualTo(FailurePolicy.FAIL_CLOSED);
            assertThat(auditor.policyFor(ActivityAction.RECORD_VIEWED))
                    .isEqualTo(FailurePolicy.FAIL_OPEN);
        }

        @Test
        void aDeniedRecordStillNeverThrowsEvenForASensitiveAction() {
            // The invariant outranks both policies.
            var port = new RecordingPort();
            port.broken = true;
            var auditor = new ActivityAuditor(port,
                    FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_CLOSED);
            assertThatCode(() -> auditor.recordDenied(
                    ActivityAuditEvent.of(NOW, "ARP-SYN-0001", ActivityAction.RECORD_ACCESS_DENIED,
                            "AWARD", "SYN-AWD-B", ActivityOutcome.DENIED, "OUT_OF_SCOPE", "c")))
                    .doesNotThrowAnyException();
        }
    }

    // ── privacy defaults, enforced by the type ──────────────────────────

    @Nested
    @DisplayName("The event type cannot carry what SEC-008 forbids")
    class PrivacyByConstruction {

        @Test
        void detailTakesScalarsOnly() {
            var event = permitted();
            assertThatCode(() -> event.withDetail("resultCount", 42)).doesNotThrowAnyException();
            assertThatCode(() -> event.withDetail("module", "AWARD")).doesNotThrowAnyException();

            assertThatThrownBy(() -> event.withDetail("terms", List.of("cancer", "grant")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scalars only");
        }

        @Test
        void aLongStringIsRefusedBecauseThatIsWhatContentLooksLike() {
            assertThatThrownBy(() -> permitted().withDetail("body", "x".repeat(201)))
                    .hasMessageContaining("keys and counts, not content");
        }

        @Test
        void thereIsNoFieldForASearchTerm() {
            /*
             * Asserted on the type itself: a future field called something
             * like searchTerms or query would make the privacy default a
             * matter of discipline rather than of structure.
             */
            List<String> components = java.util.Arrays.stream(
                            ActivityAuditEvent.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(components).doesNotContain("searchTerms", "query", "terms",
                    "content", "body", "password", "token");
            assertThat(components).contains("occurredAt", "actor", "action",
                    "resourceType", "outcome", "correlationId");
        }

        @Test
        void aSearchIsRecordedWithoutItsTerms() {
            var search = ActivityAuditEvent
                    .of(NOW, "ARP-SYN-0001", ActivityAction.SEARCH_PERFORMED, "AWARD", null,
                            ActivityOutcome.PERMITTED, null, "corr-3")
                    .withDetail("resultCount", 17);
            assertThat(search.detail()).containsEntry("resultCount", 17);
            assertThat(search.resourceKey()).isNull();
        }
    }
}
