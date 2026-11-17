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
    private final boolean enabled;

    /**
     * @param actor         the CURRENT request's actor - the resolved
     *                      institutional identifier, or a
     *                      {@code cognito-sub:} fallback for an authenticated
     *                      caller with no resolvable link. Empty ONLY when
     *                      the request is genuinely unauthenticated.
     * @param correlationId the current request's correlation id
     */
    public ArchiveActivityRecorder(ActivityAuditor auditor, Clock clock,
                                   Supplier<Optional<String>> actor,
                                   Supplier<String> correlationId) {
        this(auditor, clock, actor, correlationId, true);
    }

    private ArchiveActivityRecorder(ActivityAuditor auditor, Clock clock,
                                    Supplier<Optional<String>> actor,
                                    Supplier<String> correlationId,
                                    boolean enabled) {
        this.auditor = Objects.requireNonNull(auditor, "auditor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.enabled = enabled;
    }

    /** Collection off: every method becomes a no-op that cannot throw. */
    public static ArchiveActivityRecorder disabled(Clock clock) {
        return new ArchiveActivityRecorder(ActivityAuditor.disabled(), clock,
                Optional::empty, () -> null, false);
    }

    /**
     * Whether collection is on.
     *
     * <p>Call sites consult this before doing any extra work FOR the audit
     * (resolving a business key, say), so that with collection off - every
     * deployed environment - no additional query runs and behaviour is
     * byte-for-byte unchanged.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * A record was read and shown.
     *
     * @throws AuditUnavailableException only under a FAIL_CLOSED routine-read
     *         policy, and only when the audit write failed
     */
    public void recordViewed(String resourceType, String resourceKey, String reason) {
        recordViewed(resourceType, resourceKey, reason, null);
    }

    /**
     * As {@link #recordViewed(String, String, String)}, also naming the
     * archive row id the request addressed (an award_id, proposal_id,
     * subaward_id or negotiation_id) when it differs from the business key.
     */
    public void recordViewed(String resourceType, String resourceKey, String reason, Long recordId) {
        auditor.recordPermitted(withRecordId(event(ActivityAction.RECORD_VIEWED, resourceType,
                resourceKey, ActivityOutcome.PERMITTED, reason), recordId));
    }

    /**
     * A read was refused. Never throws, whatever the policy.
     *
     * @param denialCode the engine's own code, never prose and never a
     *                   message that would confirm the record exists
     */
    public void recordAccessDenied(String resourceType, String resourceKey, String denialCode) {
        recordAccessDenied(resourceType, resourceKey, denialCode, null);
    }

    /** As {@link #recordAccessDenied(String, String, String)}, with the addressed row id. */
    public void recordAccessDenied(String resourceType, String resourceKey, String denialCode,
                                   Long recordId) {
        ActivityAuditEvent event;
        try {
            event = withRecordId(event(ActivityAction.RECORD_ACCESS_DENIED, resourceType,
                    resourceKey, ActivityOutcome.DENIED, denialCode), recordId);
        } catch (RuntimeException failure) {
            // Building the event (actor resolution) must not turn a denial
            // into a different error either; record without an actor.
            event = withRecordId(ActivityAuditEvent.of(clock.instant(), null,
                    ActivityAction.RECORD_ACCESS_DENIED, resourceType, resourceKey,
                    ActivityOutcome.DENIED, denialCode, null), recordId);
        }
        auditor.recordDenied(event);
    }

    /**
     * Marks what a release event actually means.
     *
     * <p><strong>A known and deliberate limitation.</strong> These rows record
     * that the system AUTHORISED a file for release and committed the audit
     * before any byte left - not that the caller successfully received it. A
     * client that disconnects mid-stream, or an S3 read that fails after the
     * headers are sent, still leaves this row behind.
     *
     * <p>It is deliberate because the alternative is worse. FAIL_CLOSED means
     * "do not release the file unless the audit is written", which forces the
     * write to happen BEFORE the stream starts. An event recorded after the
     * stream completed could not refuse anything - by then the bytes are
     * already with the caller - so it would be a weaker guarantee wearing a
     * stronger name.
     *
     * <p>The value is written into every release row so the limitation is
     * visible in the DATA, not only in a document someone has to find.
     */
    static final String RELEASE_PHASE = "release_authorized";

    /**
     * An attachment was authorised for release to the caller.
     *
     * <p>FAIL_CLOSED by default: a file leaving the system with no record of
     * it is the case the audit exists for. Called BEFORE the attachment is
     * resolved or streamed, so a failed audit write refuses the release.
     *
     * <p>See {@link #RELEASE_PHASE} for what this row does and does not prove.
     */
    public void attachmentDownloaded(String resourceType, String resourceKey, String reason) {
        attachmentDownloaded(resourceType, resourceKey, reason, null);
    }

    /**
     * As {@link #attachmentDownloaded(String, String, String)}, naming WHICH
     * attachment was released. Only its id - never its file name, its
     * description or a byte of its content.
     */
    public void attachmentDownloaded(String resourceType, String resourceKey, String reason,
                                     Long attachmentId) {
        attachmentDownloaded(resourceType, resourceKey, reason, null, attachmentId);
    }

    /**
     * As above, also naming the archive row id of the owning record, so a
     * download row keyed by business key can be joined to the exact version.
     */
    public void attachmentDownloaded(String resourceType, String resourceKey, String reason,
                                     Long recordId, Long attachmentId) {
        ActivityAuditEvent event = withRecordId(event(ActivityAction.ATTACHMENT_DOWNLOADED,
                resourceType, resourceKey, ActivityOutcome.PERMITTED, reason)
                .withDetail("phase", RELEASE_PHASE), recordId);
        if (attachmentId != null) {
            event = event.withDetail("attachmentId", attachmentId);
        }
        auditor.recordPermitted(event);
    }

    /**
     * An export (a report, a consolidated PDF) was authorised for release.
     * FAIL_CLOSED by default; same phase caveat as
     * {@link #attachmentDownloaded}.
     */
    public void exportGenerated(String resourceType, String resourceKey, String reason) {
        auditor.recordPermitted(event(ActivityAction.EXPORT_GENERATED, resourceType,
                resourceKey, ActivityOutcome.PERMITTED, reason)
                .withDetail("phase", RELEASE_PHASE));
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

    private static ActivityAuditEvent withRecordId(ActivityAuditEvent event, Long recordId) {
        return recordId == null ? event : event.withDetail("recordId", recordId);
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
