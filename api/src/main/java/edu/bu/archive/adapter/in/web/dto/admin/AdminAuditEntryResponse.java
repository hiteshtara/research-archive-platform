package edu.bu.archive.adapter.in.web.dto.admin;

import java.time.Instant;

import edu.bu.archive.application.authorization.admin.AuditEntry;

/** One administrative audit row. */
public record AdminAuditEntryResponse(
        long auditId,
        Instant occurredAt,
        String actor,
        String action,
        String subject,
        String detail) {

    public static AdminAuditEntryResponse from(AuditEntry entry) {
        return new AdminAuditEntryResponse(entry.auditId(), entry.occurredAt(), entry.actor(),
                entry.action(), entry.subject(), entry.detail());
    }
}
