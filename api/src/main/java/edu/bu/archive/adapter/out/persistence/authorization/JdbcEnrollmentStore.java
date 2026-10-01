package edu.bu.archive.adapter.out.persistence.authorization;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.EnrollmentStore;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/**
 * authz.identity_link, authz.principal_crosswalk, authz.kim_principal and
 * authz.access_audit (V082, V083) for enrollment. Every write that changes a
 * link carries its audit row in the same transaction.
 */
public class JdbcEnrollmentStore implements EnrollmentStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcEnrollmentStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<StoredLink> links(ValidatedCognitoIdentity identity) {
        return jdbc.sql("""
                        SELECT identity_link_id, status, method, institutional_identifier, kuali_person_id
                        FROM authz.identity_link
                        WHERE cognito_issuer = :issuer AND cognito_subject = :subject
                        ORDER BY identity_link_id
                        """)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query((rs, n) -> new StoredLink(
                        rs.getLong("identity_link_id"),
                        "ACTIVE".equals(rs.getString("status")),
                        "AUTO_VERIFIED".equals(rs.getString("method")),
                        rs.getString("institutional_identifier"),
                        rs.getString("kuali_person_id")))
                .list();
    }

    @Override
    public boolean mappingStillValid(String attributeName, String identifier, String principalId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM authz.principal_crosswalk x
                            JOIN authz.kim_principal p ON p.prncpl_id = x.prncpl_id
                            WHERE x.attribute_name = :attribute
                              AND x.attribute_value = :identifier
                              AND x.prncpl_id = :principal
                              AND x.status = 'ACTIVE'
                              AND p.actv_ind = 'Y'
                        )
                        """)
                .param("attribute", attributeName)
                .param("identifier", identifier)
                .param("principal", principalId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<String> activeCrosswalkPrincipals(String attributeName, String value) {
        return jdbc.sql("""
                        SELECT DISTINCT prncpl_id
                        FROM authz.principal_crosswalk
                        WHERE attribute_name = :attribute AND attribute_value = :value AND status = 'ACTIVE'
                        ORDER BY prncpl_id
                        """)
                .param("attribute", attributeName)
                .param("value", value)
                .query(String.class)
                .list();
    }

    @Override
    public Optional<Boolean> principalActive(String principalId) {
        return jdbc.sql("SELECT actv_ind FROM authz.kim_principal WHERE prncpl_id = :principal")
                .param("principal", principalId)
                .query(String.class)
                .optional()
                .map("Y"::equals);
    }

    @Override
    public LinkResult link(ValidatedCognitoIdentity identity, String institutionalIdentifier, String principalId,
                           String verifiedBy, Audit linkedAudit) {
        return transactions.execute(status -> {
            // Serialises enrollments of the same identifier under one issuer, so the
            // "another profile already holds it" check cannot race.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "authz.identity_link|" + identity.issuer() + "|" + institutionalIdentifier)
                    .query((rs, n) -> 1)
                    .list();
            boolean otherProfile = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM authz.identity_link
                                WHERE cognito_issuer = :issuer AND institutional_identifier = :identifier
                                  AND status = 'ACTIVE' AND cognito_subject <> :subject
                            )
                            """)
                    .param("issuer", identity.issuer())
                    .param("identifier", institutionalIdentifier)
                    .param("subject", identity.subject())
                    .query(Boolean.class)
                    .single();
            if (otherProfile) {
                return LinkResult.IDENTIFIER_LINKED_TO_ANOTHER_PROFILE;
            }
            int inserted = jdbc.sql("""
                            INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier,
                                kuali_person_id, login_name, method, status, verified_by, verified_at)
                            VALUES (:issuer, :subject, :identifier, :principal, NULL, 'AUTO_VERIFIED', 'ACTIVE',
                                :verifiedBy, CURRENT_TIMESTAMP)
                            ON CONFLICT (cognito_issuer, cognito_subject) WHERE status = 'ACTIVE' DO NOTHING
                            """)
                    .param("issuer", identity.issuer())
                    .param("subject", identity.subject())
                    .param("identifier", institutionalIdentifier)
                    .param("principal", principalId)
                    .param("verifiedBy", verifiedBy)
                    .update();
            if (inserted == 0) {
                return LinkResult.ALREADY_LINKED;
            }
            insertAudit(linkedAudit);
            return LinkResult.LINKED;
        });
    }

    @Override
    public boolean revoke(long linkId, String revokedBy, Audit audit) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed = jdbc.sql("""
                            UPDATE authz.identity_link
                            SET status = 'REVOKED', revoked_by = :revokedBy, revoked_at = CURRENT_TIMESTAMP
                            WHERE identity_link_id = :id AND status = 'ACTIVE'
                            """)
                    .param("revokedBy", revokedBy)
                    .param("id", linkId)
                    .update();
            if (changed == 0) {
                return false;
            }
            insertAudit(audit);
            return true;
        }));
    }

    @Override
    public void audit(Audit audit) {
        insertAudit(audit);
    }

    private void insertAudit(Audit audit) {
        String detail;
        try {
            detail = json.writeValueAsString(audit.detail());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit detail is not serialisable", e);
        }
        jdbc.sql("""
                        INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail)
                        VALUES (:actor, :action, :identifier, CAST(:detail AS jsonb))
                        """)
                .param("actor", audit.actor())
                .param("action", audit.action())
                .param("identifier", audit.institutionalIdentifier())
                .param("detail", detail)
                .update();
    }
}
