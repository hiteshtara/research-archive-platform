package edu.bu.archive.application.authorization.audit;

/**
 * The activity audit could not be written, and the configured policy is
 * {@code FAIL_CLOSED}: audit completeness is preferred to availability, so
 * the read is refused rather than served unrecorded.
 *
 * <p>This is never thrown on a denial path. A failure to record a DENIAL can
 * never turn it into access - see {@link ActivityAuditor}.
 */
public class AuditUnavailableException extends RuntimeException {

    public static final String CODE = "AUDIT_UNAVAILABLE";

    public AuditUnavailableException(Throwable cause) {
        super("Activity audit unavailable; refusing the read under FAIL_CLOSED", cause);
    }
}
