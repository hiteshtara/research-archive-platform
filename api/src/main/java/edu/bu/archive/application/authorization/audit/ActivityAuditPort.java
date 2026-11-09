package edu.bu.archive.application.authorization.audit;

/**
 * Where activity rows go. One method, so the no-op and the JDBC
 * implementation cannot drift apart.
 */
@FunctionalInterface
public interface ActivityAuditPort {

    void append(ActivityAuditEvent event);

    /**
     * Collection off: the default everywhere until SEC-008 is approved.
     *
     * <p>Writes nothing and throws nothing, so a deployment with the flag off
     * behaves exactly as it does today whatever the failure policy says.
     */
    ActivityAuditPort DISABLED = event -> { };
}
