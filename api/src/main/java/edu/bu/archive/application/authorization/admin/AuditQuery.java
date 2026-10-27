package edu.bu.archive.application.authorization.admin;

import java.time.Instant;

/**
 * Filter over the administrative audit history. Every field is optional; an
 * omitted field imposes no condition. Results are newest first.
 *
 * <p>{@code limit} is clamped rather than validated so a review screen cannot
 * accidentally ask for the whole table.
 */
public record AuditQuery(
        String subject,
        AdminAuditAction action,
        Instant from,
        Instant to,
        int limit) {

    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 100;

    public AuditQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    public static AuditQuery forSubject(String subject) {
        return new AuditQuery(subject, null, null, null, DEFAULT_LIMIT);
    }
}
