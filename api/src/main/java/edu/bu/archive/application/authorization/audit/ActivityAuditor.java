package edu.bu.archive.application.authorization.audit;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityOutcome;

/**
 * Records archive activity, and decides what happens when recording fails.
 *
 * <h2>The invariant that is not configurable (SEC-008 requirement 1)</h2>
 *
 * <p><strong>An audit failure can never grant access.</strong> That is
 * enforced by the shape of this class, not by care at the call sites:
 *
 * <ul>
 *   <li>auditing happens <em>after</em> the authorization decision, and this
 *       class is given the outcome rather than asked for one — there is no
 *       code path here that can turn a DENIED into a PERMITTED;
 *   <li>{@link #recordDenied} never throws, whatever the policy says. A
 *       failure to record a refusal must not become a second, different
 *       failure that some caller might catch and handle as "proceed";
 *   <li>only {@link #recordPermitted} consults the failure policy, and the
 *       strictest thing it can do is refuse an access that was already
 *       permitted. Refusing more is always safe; the reverse is the thing
 *       that must be impossible.
 * </ul>
 *
 * <h2>The part that IS a decision (SEC-008 requirement 2)</h2>
 *
 * <p>Whether an audit-delivery failure should block an otherwise authorized
 * read is a business tradeoff, not an engineering one, and it is deliberately
 * <em>not</em> given a default:
 *
 * <table border="1">
 *   <caption>Failure policy tradeoff</caption>
 *   <tr><th></th><th>FAIL_OPEN</th><th>FAIL_CLOSED</th></tr>
 *   <tr><td>Preferred</td><td>availability</td><td>audit completeness</td></tr>
 *   <tr><td>On a database hiccup</td><td>the archive keeps serving</td>
 *       <td>reads start failing</td></tr>
 *   <tr><td>Risk</td><td>a read happens with no record that it did</td>
 *       <td>an audit outage becomes an archive outage</td></tr>
 * </table>
 *
 * <p>Enabling collection without choosing one is a startup failure, not a
 * silent default — see {@code ActivityAuditProperties.validate()}. With
 * collection OFF the policy is moot and nothing is required.
 */
public class ActivityAuditor {

    private static final Logger log = LoggerFactory.getLogger(ActivityAuditor.class);

    /** What to do when the audit write fails. */
    public enum FailurePolicy {
        /** Serve the read; record the loss in the application log. */
        FAIL_OPEN,
        /** Refuse the read rather than serve it unrecorded. */
        FAIL_CLOSED
    }

    private final ActivityAuditPort port;
    private final FailurePolicy failurePolicy;

    public ActivityAuditor(ActivityAuditPort port, FailurePolicy failurePolicy) {
        this.port = Objects.requireNonNull(port, "port");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
    }

    /** Collection off, and therefore no policy to apply. */
    public static ActivityAuditor disabled() {
        return new ActivityAuditor(ActivityAuditPort.DISABLED, FailurePolicy.FAIL_OPEN);
    }

    /**
     * Records a permitted access.
     *
     * @throws AuditUnavailableException under {@link FailurePolicy#FAIL_CLOSED}
     *         when the row could not be written. The caller must let this
     *         propagate: swallowing it reinstates FAIL_OPEN silently.
     */
    public void recordPermitted(ActivityAuditEvent event) {
        if (event.outcome() == ActivityOutcome.DENIED) {
            throw new IllegalArgumentException(
                    "recordPermitted received a DENIED event; use recordDenied - "
                            + "the two paths differ in whether they may throw");
        }
        try {
            port.append(event);
        } catch (RuntimeException failure) {
            if (failurePolicy == FailurePolicy.FAIL_CLOSED) {
                log.error("activity audit write failed; refusing the read (FAIL_CLOSED)", failure);
                throw new AuditUnavailableException(failure);
            }
            /*
             * FAIL_OPEN. The read proceeds and the loss is recorded where it
             * can still be noticed. Logged at ERROR deliberately: an audit
             * gap is an incident even when the request succeeded, and a
             * WARN here would make a silent compliance gap look routine.
             */
            log.error("activity audit write failed; serving the read anyway (FAIL_OPEN). "
                    + "action={} resourceType={} correlationId={}",
                    event.action(), event.resourceType(), event.correlationId(), failure);
        }
    }

    /**
     * Records a refusal. <strong>Never throws.</strong>
     *
     * <p>Not a convenience: if this could throw, a caller that catches
     * broadly around the denial path could convert a refusal into some other
     * error - and the one thing SEC-008 requires is that no audit failure can
     * ever produce access that would otherwise be denied. The denial has
     * already happened by the time this is called; the only question is
     * whether it gets written down.
     */
    public void recordDenied(ActivityAuditEvent event) {
        try {
            port.append(event);
        } catch (RuntimeException failure) {
            log.error("activity audit write failed for a DENIED access; the denial stands. "
                    + "action={} resourceType={} correlationId={}",
                    event.action(), event.resourceType(), event.correlationId(), failure);
        }
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }
}
