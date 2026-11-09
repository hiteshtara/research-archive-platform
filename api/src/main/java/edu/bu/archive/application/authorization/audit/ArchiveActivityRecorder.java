package edu.bu.archive.application.authorization.audit;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityAction;
import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityOutcome;

/**
 * The five SEC-008 call sites, in one place.
 *
 * <p>Call sites do not build {@link ActivityAuditEvent} values themselves.
 * They call one of the five methods here, and this class decides the action,
 * the outcome and which of {@code recordPermitted} / {@code recordDenied} to
 * use. That matters for the one invariant SEC-008 insists on: a call site
 * cannot accidentally route a denial through the path that is allowed to
 * throw, because it never chooses the path.
 *
 * <h2>Collection off is the default everywhere</h2>
 *
 * <p>With {@code app.authorization.activity-audit.enabled} false the auditor
 * is {@link ActivityAuditor#disabled()}: every method here becomes a no-op
 * that cannot throw. Wiring a call site is therefore safe to do before the
 * feature is approved, which is why the call sites exist now and the
 * privilege and the flag do not.
 *
 * <h2>What never reaches a row</h2>
 *
 * <p>{@link #searchPerformed} takes a result COUNT, not a query. There is no
 * parameter on any method here that could carry a search term, a document
 * body or a credential - the shape of the API is the privacy control, not a
 * convention the call sites have to remember.
 */
public class ArchiveActivityRecorder {

    private final ActivityAuditor auditor;
    private final Clock clock;
    private final Supplier<Optional<String>> actor;
    private final Supplier<String> correlationId;

    public ArchiveActivityRecorder(ActivityAuditor auditor, Clock clock,
                                   Supplier<Optional<String>> actor,
                                   Supplier<String> correlationId) {
        this.auditor = Objects.requireNonNull(auditor, "auditor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    /** Collection off: every method becomes a no-op that cannot throw. */
    public static ArchiveActivityRecorder disabled(Clock clock) {
        return new ArchiveActivityRecorder(ActivityAuditor.disabled(), clock,
                Optional::empty, () -> null);
    }

    /**
     * A record was read and shown.
     *
     * @throws AuditUnavailableException only under a FAIL_CLOSED routine-read
     *         policy, and only when the audit write failed
     */
    public void recordViewed(String resourceType, String resourceKey, String reason) {
        auditor.recordPermitted(event(ActivityAction.RECORD_VIEWED, resourceType, resourceKey,
                ActivityOutcome.PERMITTED, reason));
    }

    /**
     * A read was refused. Never throws, whatever the policy.
     *
     * @param denialCode the engine's own code, never prose and never a
     *                   message that would confirm the record exists
     */
    public void recordAccessDenied(String resourceType, String resourceKey, String denialCode) {
        auditor.recordDenied(event(ActivityAction.RECORD_ACCESS_DENIED, resourceType, resourceKey,
                ActivityOutcome.DENIED, denialCode));
    }

    /**
     * An attachment was released to the caller.
     *
     * <p>FAIL_CLOSED by default: a file left the system, and a release with no
     * record of it is the case the audit exists for.
     */
    public void attachmentDownloaded(String resourceType, String resourceKey, String reason) {
        auditor.recordPermitted(event(ActivityAction.ATTACHMENT_DOWNLOADED, resourceType,
                resourceKey, ActivityOutcome.PERMITTED, reason));
    }

    /** An export (a report, a consolidated PDF) was generated. FAIL_CLOSED by default. */
    public void exportGenerated(String resourceType, String resourceKey, String reason) {
        auditor.recordPermitted(event(ActivityAction.EXPORT_GENERATED, resourceType,
                resourceKey, ActivityOutcome.PERMITTED, reason));
    }

    /**
     * A search ran.
     *
     * <p>Takes the module and how many rows came back. <strong>There is
     * deliberately no parameter for the query.</strong> A call site cannot
     * record a search term through this API even by mistake, which is a
     * stronger guarantee than asking every caller to remember not to.
     */
    public void searchPerformed(String module, int resultCount) {
        auditor.recordPermitted(
                event(ActivityAction.SEARCH_PERFORMED, module, null,
                        ActivityOutcome.PERMITTED, null)
                        .withDetail("resultCount", Math.max(0, resultCount)));
    }

    private ActivityAuditEvent event(ActivityAction action, String resourceType,
                                     String resourceKey, ActivityOutcome outcome, String reason) {
        return ActivityAuditEvent.of(
                clock.instant(),
                actor.get().orElse(null),
                action,
                resourceType,
                resourceKey,
                outcome,
                reason,
                correlationId.get());
    }
}
