package edu.bu.archive.application.authorization.audit;

/**
 * SEC-008 configuration. Collection is OFF until approved.
 *
 * <p>{@code failurePolicy} has <strong>no default on purpose</strong>.
 * Enabling collection without choosing one is a startup failure rather than a
 * silent availability-versus-audit-completeness decision taken by whoever
 * wrote this class. With collection off, nothing is required.
 */
public class ActivityAuditProperties {

    /**
     * Proposed for review, NOT an approved BU retention policy. Retention is
     * implemented by dropping a monthly partition, so changing it is an
     * operational change, not a schema one.
     */
    public static final int PROPOSED_RETENTION_DAYS = 90;

    private boolean enabled = false;

    /**
     * Attachment downloads and exports. Defaulted to FAIL_CLOSED by business
     * direction 2026-11-09 for planning and testing: a file leaving the
     * system with no record of it is the case the audit exists for. Still
     * overridable, and still not an approved production policy.
     */
    private ActivityAuditor.FailurePolicy sensitiveReleaseFailurePolicy =
            ActivityAuditor.FailurePolicy.FAIL_CLOSED;

    /**
     * Record views and searches. <strong>No default.</strong>
     *
     * <p>{@code FAIL_OPEN} is the PROPOSED policy (engineering direction
     * 2026-11-09): a routine read keeps working when the audit store hiccups,
     * and the gap is reported through structured logging and the
     * {@code archive.audit.gap} counter. It is pending BU approval and is NOT
     * a default: owner direction 2026-11-17 removed the earlier FAIL_OPEN
     * default, so enabling collection without setting
     * {@code app.authorization.activity-audit.routine-read-failure-policy}
     * refuses to start. Requiring it (rather than defaulting to FAIL_CLOSED)
     * keeps a compliance-versus-availability decision from being taken by a
     * default in either direction, and costs one property at the single
     * moment collection is switched on. With collection off nothing is
     * required.
     */
    private ActivityAuditor.FailurePolicy routineReadFailurePolicy;

    private int retentionDays = PROPOSED_RETENTION_DAYS;

    /**
     * @throws IllegalStateException when collection is enabled without an
     *         explicit failure policy
     */
    public void validate() {
        if (enabled && routineReadFailurePolicy == null) {
            throw new IllegalStateException(
                    "app.authorization.activity-audit.enabled is true but "
                            + "routine-read-failure-policy is not set. Choose FAIL_OPEN "
                            + "(prefer availability: serve the read, log the audit gap) or "
                            + "FAIL_CLOSED (prefer audit completeness: refuse the read). "
                            + "There is no default for routine reads - see "
                            + "docs/security/SEC_008_ACTIVITY_AUDIT.md. Attachment downloads "
                            + "and exports are FAIL_CLOSED unless explicitly overridden.");
        }
        if (enabled && sensitiveReleaseFailurePolicy == null) {
            throw new IllegalStateException(
                    "sensitive-release-failure-policy must not be null when activity "
                            + "auditing is enabled");
        }
        if (retentionDays <= 0) {
            throw new IllegalStateException("activity-audit retention-days must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ActivityAuditor.FailurePolicy getSensitiveReleaseFailurePolicy() {
        return sensitiveReleaseFailurePolicy;
    }

    public void setSensitiveReleaseFailurePolicy(ActivityAuditor.FailurePolicy policy) {
        this.sensitiveReleaseFailurePolicy = policy;
    }

    public ActivityAuditor.FailurePolicy getRoutineReadFailurePolicy() {
        return routineReadFailurePolicy;
    }

    public void setRoutineReadFailurePolicy(ActivityAuditor.FailurePolicy policy) {
        this.routineReadFailurePolicy = policy;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
