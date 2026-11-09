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
    private ActivityAuditor.FailurePolicy failurePolicy;
    private int retentionDays = PROPOSED_RETENTION_DAYS;

    /**
     * @throws IllegalStateException when collection is enabled without an
     *         explicit failure policy
     */
    public void validate() {
        if (enabled && failurePolicy == null) {
            throw new IllegalStateException(
                    "app.authorization.activity-audit.enabled is true but "
                            + "failure-policy is not set. Choose FAIL_OPEN (prefer "
                            + "availability: serve the read, log the audit gap) or "
                            + "FAIL_CLOSED (prefer audit completeness: refuse the read). "
                            + "There is no default - see docs/security/SEC_008_ACTIVITY_AUDIT.md.");
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

    public ActivityAuditor.FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(ActivityAuditor.FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
