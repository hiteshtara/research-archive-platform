package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import edu.bu.archive.application.authorization.admin.AdminAuditRepository;
import edu.bu.archive.application.authorization.admin.AuditEntry;
import edu.bu.archive.application.authorization.admin.AuditQuery;

/**
 * Read side of authz.access_audit (V082). Read-only by construction: the table
 * has a BEFORE UPDATE OR DELETE trigger that raises, so there is nothing to
 * write here even if a future change tried.
 */
@Repository
public class JdbcAdminAuditRepository implements AdminAuditRepository {

    private final JdbcClient jdbc;

    public JdbcAdminAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditEntry> find(AuditQuery query) {
        // Every filter is applied as "parameter IS NULL OR <condition>" so one
        // statement serves every combination, and an omitted filter cannot
        // accidentally become a condition that hides rows.
        return jdbc.sql("""
                        SELECT audit_id, occurred_at, actor, action,
                               institutional_identifier, detail::text AS detail
                        FROM authz.access_audit
                        WHERE (:subject IS NULL OR institutional_identifier = :subject)
                          AND (:action  IS NULL OR action = :action)
                          AND (CAST(:fromTime AS TIMESTAMPTZ) IS NULL OR occurred_at >= CAST(:fromTime AS TIMESTAMPTZ))
                          AND (CAST(:toTime   AS TIMESTAMPTZ) IS NULL OR occurred_at <  CAST(:toTime   AS TIMESTAMPTZ))
                        ORDER BY occurred_at DESC, audit_id DESC
                        LIMIT :limit
                        """)
                .param("subject", query.subject())
                .param("action", query.action() == null ? null : query.action().name())
                .param("fromTime", query.from() == null ? null : Timestamp.from(query.from()))
                .param("toTime", query.to() == null ? null : Timestamp.from(query.to()))
                .param("limit", query.limit())
                .query((rs, rowNum) -> new AuditEntry(
                        rs.getLong("audit_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("institutional_identifier"),
                        rs.getString("detail")))
                .list();
    }
}
