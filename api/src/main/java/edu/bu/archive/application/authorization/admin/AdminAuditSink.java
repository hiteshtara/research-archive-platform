package edu.bu.archive.application.authorization.admin;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/**
 * Append-only sink for administrative audit events (authz.access_audit).
 *
 * <p>Writes MUST happen in the same transaction as the change they record, so
 * an administrative change can never be committed without its audit row.
 */
public interface AdminAuditSink {

    /**
     * @param actor   acting administrator's institutional identifier, or
     *                {@link AdminRoleMember#BOOTSTRAP_ACTOR}
     * @param action  what happened
     * @param subject identity the action was performed on, may be null for
     *                actions with no single subject
     * @param detail  JSON detail; never credentials or record content
     */
    void record(String actor, AdminAuditAction action,
                InstitutionalIdentifier subject, String detail);
}
