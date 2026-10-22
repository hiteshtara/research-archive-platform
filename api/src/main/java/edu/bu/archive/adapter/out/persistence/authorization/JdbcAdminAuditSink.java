package edu.bu.archive.adapter.out.persistence.authorization;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminAuditAction;
import edu.bu.archive.application.authorization.admin.AdminAuditSink;

/**
 * Append-only writer for authz.access_audit (V082).
 *
 * <p>{@code MANDATORY} propagation on purpose: the audit row must commit with
 * the change it describes. If this created its own transaction, an
 * administrative change could commit while its audit write rolled back,
 * leaving a permission change with no record of who made it - the one thing
 * the retained audit scope exists to prevent.
 */
@Repository
public class JdbcAdminAuditSink implements AdminAuditSink {

    private final JdbcClient jdbc;

    public JdbcAdminAuditSink(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, AdminAuditAction action,
                       InstitutionalIdentifier subject, String detail) {

        jdbc.sql("""
                        INSERT INTO authz.access_audit
                            (actor, action, institutional_identifier, detail)
                        VALUES (:actor, :action, :subject, CAST(:detail AS JSONB))
                        """)
                .param("actor", actor)
                .param("action", action.name())
                .param("subject", subject == null ? null : subject.value())
                .param("detail", detail == null ? null : toJsonText(detail))
                .update();
    }

    /**
     * Wrap free text as a JSON object so the column stays valid JSONB. Detail is
     * operator-supplied, so it is escaped rather than interpolated, and never
     * carries credentials or record content.
     */
    private static String toJsonText(String detail) {
        String escaped = detail
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"note\":\"" + escaped + "\"}";
    }
}
