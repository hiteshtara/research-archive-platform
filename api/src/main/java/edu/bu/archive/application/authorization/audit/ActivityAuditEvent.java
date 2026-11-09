package edu.bu.archive.application.authorization.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One archive activity, as SEC-008 defines it: timestamp, actor, action,
 * resource type, outcome and correlation id.
 *
 * <p><strong>What this type structurally cannot carry.</strong> There is no
 * field for a search term, a document body, an attachment, a token or a
 * password. {@code detail} is a bounded map for counts and keys, and
 * {@link #withDetail} refuses anything that is not a scalar. A JSONB column
 * with a free-text setter is exactly how search terms end up in an audit
 * table, so the type refuses rather than relying on every caller to remember.
 *
 * @param resourceKey a BUSINESS key (award number, proposal number, subaward
 *                    id) - never a row id that means nothing outside one
 *                    database, and never a value that identifies a person
 */
public record ActivityAuditEvent(
        Instant occurredAt,
        String actor,
        ActivityAction action,
        String resourceType,
        String resourceKey,
        ActivityOutcome outcome,
        String reason,
        String correlationId,
        Map<String, Object> detail
) {

    /** The actions SEC-008 asks for. Matches ck_activity_audit_action in V089. */
    public enum ActivityAction {
        RECORD_VIEWED,
        RECORD_ACCESS_DENIED,
        ATTACHMENT_DOWNLOADED,
        EXPORT_GENERATED,
        /** That a search happened and how many rows it returned. Never the terms. */
        SEARCH_PERFORMED
    }

    public enum ActivityOutcome {
        PERMITTED,
        DENIED,
        ERROR
    }

    public ActivityAuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public static ActivityAuditEvent of(Instant at, String actor, ActivityAction action,
                                        String resourceType, String resourceKey,
                                        ActivityOutcome outcome, String reason,
                                        String correlationId) {
        return new ActivityAuditEvent(at, actor, action, resourceType, resourceKey,
                outcome, reason, correlationId, Map.of());
    }

    /**
     * Adds one bounded detail. Scalars only: a count, a module name, a
     * version key. Anything else - a collection, a map, an object - is
     * refused, because that is the shape a captured search term or a
     * serialised document arrives in.
     */
    public ActivityAuditEvent withDetail(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("detail key is required");
        }
        if (!(value instanceof Number || value instanceof Boolean || value instanceof String)) {
            throw new IllegalArgumentException(
                    "activity detail takes scalars only; got "
                            + (value == null ? "null" : value.getClass().getName())
                            + ". Structured payloads are how search terms and document "
                            + "content reach an audit table.");
        }
        if (value instanceof String text && text.length() > 200) {
            throw new IllegalArgumentException(
                    "activity detail string is too long (" + text.length() + "); "
                            + "audit details are keys and counts, not content");
        }
        Map<String, Object> merged = new LinkedHashMap<>(detail);
        merged.put(key, value);
        return new ActivityAuditEvent(occurredAt, actor, action, resourceType, resourceKey,
                outcome, reason, correlationId, merged);
    }
}
