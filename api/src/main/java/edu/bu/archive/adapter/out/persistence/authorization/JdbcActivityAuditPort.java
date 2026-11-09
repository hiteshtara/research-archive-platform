package edu.bu.archive.adapter.out.persistence.authorization;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent;
import edu.bu.archive.application.authorization.audit.ActivityAuditPort;

/**
 * Writes SEC-008 activity rows to {@code authz.activity_audit}.
 *
 * <p>INSERT only. The table is append-only by trigger and the API role is
 * granted INSERT and nothing else, so there is no update or delete path to
 * write even by mistake.
 *
 * <p>Deliberately does NOT catch its own failures. Whether a failed write
 * blocks the read is {@code ActivityAuditor}'s decision, taken from the
 * configured policy; an adapter that swallowed exceptions would impose
 * FAIL_OPEN on everyone and make the policy unreachable.
 */
public class JdbcActivityAuditPort implements ActivityAuditPort {

    private static final String INSERT = """
            INSERT INTO authz.activity_audit
                (occurred_at, actor, action, resource_type, resource_key,
                 outcome, reason, correlation_id, detail)
            VALUES (:occurredAt, :actor, :action, :resourceType, :resourceKey,
                    :outcome, :reason, :correlationId, CAST(:detail AS JSONB))
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcActivityAuditPort(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(ActivityAuditEvent event) {
        jdbc.sql(INSERT)
                .param("occurredAt", java.sql.Timestamp.from(event.occurredAt()))
                .param("actor", event.actor())
                .param("action", event.action().name())
                .param("resourceType", event.resourceType())
                .param("resourceKey", event.resourceKey())
                .param("outcome", event.outcome().name())
                .param("reason", event.reason())
                .param("correlationId", event.correlationId())
                .param("detail", serialise(event))
                .update();
    }

    private String serialise(ActivityAuditEvent event) {
        if (event.detail().isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(event.detail());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            /*
             * The detail map holds scalars only - ActivityAuditEvent.withDetail
             * refuses anything else - so this is unreachable in practice. If it
             * ever happens, lose the detail rather than the row: the actor,
             * action and outcome are the audit; the detail is colour.
             */
            return null;
        }
    }
}
