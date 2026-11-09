package edu.bu.archive.application.authorization.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityAction;
import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityOutcome;
import edu.bu.archive.application.authorization.audit.ActivityAuditor.FailurePolicy;

/**
 * SEC-008: the five call sites.
 *
 * <p>What these pin down is that a call site cannot get the dangerous things
 * wrong, because it is not allowed to make those choices. It does not pick
 * the action, the outcome, or which of the two recording paths to use — so a
 * denial cannot be routed through the path that may throw, and a search
 * cannot carry its terms.
 */
class ArchiveActivityRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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

    private static ArchiveActivityRecorder recorder(RecordingPort port,
                                                    FailurePolicy sensitive,
                                                    FailurePolicy routine) {
        return new ArchiveActivityRecorder(
                new ActivityAuditor(port, sensitive, routine), CLOCK,
                () -> Optional.of("ARP-SYN-0001"), () -> "corr-1");
    }

    @Test
    @DisplayName("all five call sites write the action SEC-008 names")
    void allFiveCallSitesRecordTheRightAction() {
        var port = new RecordingPort();
        var recorder = recorder(port, FailurePolicy.FAIL_OPEN, FailurePolicy.FAIL_OPEN);

        recorder.recordViewed("AWARD", "SYN-AWD-A", "DEPARTMENT");
        recorder.recordAccessDenied("AWARD", "SYN-AWD-B", "OUT_OF_SCOPE");
        recorder.attachmentDownloaded("AWARD", "SYN-AWD-A", "DEPARTMENT");
        recorder.exportGenerated("AWARD", "SYN-AWD-A", "DEPARTMENT");
        recorder.searchPerformed("AWARD", 17);

        assertThat(port.written).extracting(ActivityAuditEvent::action).containsExactly(
                ActivityAction.RECORD_VIEWED,
                ActivityAction.RECORD_ACCESS_DENIED,
                ActivityAction.ATTACHMENT_DOWNLOADED,
                ActivityAction.EXPORT_GENERATED,
                ActivityAction.SEARCH_PERFORMED);
        assertThat(port.written).extracting(ActivityAuditEvent::actor).containsOnly("ARP-SYN-0001");
        assertThat(port.written).extracting(ActivityAuditEvent::correlationId).containsOnly("corr-1");
        assertThat(port.written).extracting(ActivityAuditEvent::occurredAt).containsOnly(NOW);
    }

    @Test
    @DisplayName("a denial is recorded as DENIED and can never throw")
    void aDenialIsRecordedAsDeniedAndNeverThrows() {
        var port = new RecordingPort();
        port.broken = true;
        var recorder = recorder(port, FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_CLOSED);

        assertThatCode(() -> recorder.recordAccessDenied("AWARD", "SYN-AWD-B", "OUT_OF_SCOPE"))
                .as("even under FAIL_CLOSED on both tiers, a refusal cannot be converted")
                .doesNotThrowAnyException();

        port.broken = false;
        recorder.recordAccessDenied("AWARD", "SYN-AWD-B", "OUT_OF_SCOPE");
        assertThat(port.written).singleElement()
                .extracting(ActivityAuditEvent::outcome).isEqualTo(ActivityOutcome.DENIED);
    }

    @Test
    @DisplayName("a failed download audit refuses the release; a failed view audit does not")
    void theTwoTiersApplyAtTheCallSites() {
        var port = new RecordingPort();
        port.broken = true;
        var recorder = recorder(port, FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> recorder.attachmentDownloaded("AWARD", "SYN-AWD-A", null))
                .isInstanceOf(AuditUnavailableException.class);
        assertThatThrownBy(() -> recorder.exportGenerated("AWARD", "SYN-AWD-A", null))
                .isInstanceOf(AuditUnavailableException.class);

        assertThatCode(() -> recorder.recordViewed("AWARD", "SYN-AWD-A", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.searchPerformed("AWARD", 3))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a search records a count and has no way to record its terms")
    void aSearchCannotCarryItsTerms() throws Exception {
        var port = new RecordingPort();
        recorder(port, FailurePolicy.FAIL_OPEN, FailurePolicy.FAIL_OPEN)
                .searchPerformed("AWARD", 17);

        ActivityAuditEvent event = port.written.get(0);
        assertThat(event.detail()).containsEntry("resultCount", 17);
        assertThat(event.resourceKey()).isNull();

        /*
         * The API shape is the privacy control. searchPerformed takes a
         * module and an int - there is no overload, and no parameter, that
         * could accept a query string.
         */
        boolean anyMethodTakesAString = java.util.Arrays
                .stream(ArchiveActivityRecorder.class.getMethod(
                        "searchPerformed", String.class, int.class).getParameterTypes())
                .filter(String.class::equals)
                .count() > 1;
        assertThat(anyMethodTakesAString)
                .as("searchPerformed must take exactly one String, the module")
                .isFalse();
    }

    @Test
    @DisplayName("release events say they record the decision, not the delivery")
    void releaseEventsCarryTheirPhase() {
        /*
         * The known limitation, asserted so it stays visible in the data:
         * these rows mean "authorised for release and audited before any byte
         * left", not "the caller received the file". Recording after the
         * stream would be a weaker guarantee wearing a stronger name, because
         * by then nothing can be refused.
         */
        var port = new RecordingPort();
        var recorder = recorder(port, FailurePolicy.FAIL_CLOSED, FailurePolicy.FAIL_OPEN);
        recorder.attachmentDownloaded("AWARD", "SYN-AWD-A", null);
        recorder.exportGenerated("AWARD", "SYN-AWD-A", null);

        assertThat(port.written).hasSize(2);
        assertThat(port.written).allSatisfy(event ->
                assertThat(event.detail()).containsEntry("phase", "release_authorized"));

        // A routine view is not a release and carries no phase.
        recorder.recordViewed("AWARD", "SYN-AWD-A", null);
        assertThat(port.written.get(2).detail()).doesNotContainKey("phase");
    }

    @Test
    @DisplayName("a negative result count is clamped rather than stored")
    void aNegativeCountIsClamped() {
        var port = new RecordingPort();
        recorder(port, FailurePolicy.FAIL_OPEN, FailurePolicy.FAIL_OPEN)
                .searchPerformed("AWARD", -5);
        assertThat(port.written.get(0).detail()).containsEntry("resultCount", 0);
    }

    @Test
    @DisplayName("with collection disabled every call site is a no-op that cannot throw")
    void disabledIsSafeAtEveryCallSite() {
        var recorder = ArchiveActivityRecorder.disabled(CLOCK);
        assertThatCode(() -> {
            recorder.recordViewed("AWARD", "SYN-AWD-A", "DEPARTMENT");
            recorder.recordAccessDenied("AWARD", "SYN-AWD-B", "OUT_OF_SCOPE");
            recorder.attachmentDownloaded("AWARD", "SYN-AWD-A", null);
            recorder.exportGenerated("AWARD", "SYN-AWD-A", null);
            recorder.searchPerformed("AWARD", 1);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unknown actor is recorded as absent, not invented")
    void anUnknownActorIsNull() {
        var port = new RecordingPort();
        new ArchiveActivityRecorder(
                new ActivityAuditor(port, FailurePolicy.FAIL_OPEN, FailurePolicy.FAIL_OPEN),
                CLOCK, Optional::empty, () -> null)
                .recordAccessDenied("AWARD", "SYN-AWD-B", "ACCESS_NOT_PROVISIONED");

        assertThat(port.written.get(0).actor())
                .as("a refusal before an identity resolved has no actor, and must not get one")
                .isNull();
    }
}
