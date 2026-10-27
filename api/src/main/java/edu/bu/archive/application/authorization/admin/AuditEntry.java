package edu.bu.archive.application.authorization.admin;

import java.time.Instant;

/**
 * One row of authz.access_audit, as read back for administrative review.
 *
 * <p>{@code subject} is the raw institutional identifier string rather than an
 * {@link edu.bu.archive.application.authorization.InstitutionalIdentifier}
 * because audit rows are historical: an identifier that has since been
 * withdrawn must still render, and the value type rejects blanks.
 */
public record AuditEntry(
        long auditId,
        Instant occurredAt,
        String actor,
        String action,
        String subject,
        String detail) {
}
