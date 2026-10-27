package edu.bu.archive.application.authorization.admin;

import java.util.List;

/**
 * Read side of authz.access_audit. The write side is {@link AdminAuditSink},
 * which is deliberately a different interface: the audit table is append-only
 * (enforced by a database trigger), and splitting read from write keeps that
 * property obvious at every call site.
 */
public interface AdminAuditRepository {

    /** Matching audit rows, newest first, capped by {@link AuditQuery#limit()}. */
    List<AuditEntry> find(AuditQuery query);
}
