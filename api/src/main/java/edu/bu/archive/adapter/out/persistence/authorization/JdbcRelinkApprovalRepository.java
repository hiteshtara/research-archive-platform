package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.RelinkApproval;
import edu.bu.archive.application.authorization.admin.RelinkApprovalRepository;

/** authz.relink_approval (V086). */
@Repository
public class JdbcRelinkApprovalRepository implements RelinkApprovalRepository {

    private static final String COLUMNS = """
            SELECT approval_id, institutional_identifier, proposed_cognito_issuer,
                   proposed_cognito_subject, requested_by, requested_at,
                   grants_at_request, reason, approved_by, approved_at, status
            FROM authz.relink_approval
            """;

    private final JdbcClient jdbc;

    public JdbcRelinkApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RelinkApproval request(InstitutionalIdentifier identity, String proposedCognitoIssuer,
                                  String proposedCognitoSubject, String requestedBy,
                                  int grantsAtRequest, String reason) {
        Long id = jdbc.sql("""
                        INSERT INTO authz.relink_approval
                            (institutional_identifier, proposed_cognito_issuer,
                             proposed_cognito_subject, requested_by, grants_at_request, reason)
                        VALUES (:identity, :issuer, :subject, :requestedBy, :grants, :reason)
                        RETURNING approval_id
                        """)
                .param("identity", identity.value())
                .param("issuer", proposedCognitoIssuer)
                .param("subject", proposedCognitoSubject)
                .param("requestedBy", requestedBy)
                .param("grants", grantsAtRequest)
                .param("reason", reason)
                .query(Long.class)
                .single();
        return findById(id).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RelinkApproval approve(long approvalId, String approvedBy) {
        /*
         * The two-person rule is in the WHERE clause as well as in a CHECK
         * constraint: requested_by <> :approvedBy. Expressed here too so the
         * refusal is a clear message rather than a constraint violation.
         */
        int updated = jdbc.sql("""
                        UPDATE authz.relink_approval
                           SET status = 'APPROVED',
                               approved_by = :approvedBy,
                               approved_at = CURRENT_TIMESTAMP
                         WHERE approval_id = :id
                           AND status = 'PENDING'
                           AND requested_by <> :approvedBy
                        """)
                .param("approvedBy", approvedBy)
                .param("id", approvalId)
                .update();

        if (updated == 0) {
            RelinkApproval existing = findById(approvalId).orElseThrow(
                    () -> new NoSuchElementException("no relink approval " + approvalId));
            if (existing.requestedBy().equals(approvedBy)) {
                throw new IllegalStateException(
                        "the administrator who requested this relink cannot also approve it; "
                                + "a second, distinct ARCHIVE_ADMIN must");
            }
            throw new IllegalStateException(
                    "relink approval " + approvalId + " is " + existing.status()
                            + ", not PENDING");
        }
        return findById(approvalId).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(long approvalId, String revokedBy) {
        int updated = jdbc.sql("""
                        UPDATE authz.relink_approval
                           SET status = 'REVOKED',
                               revoked_by = :revokedBy,
                               revoked_at = CURRENT_TIMESTAMP
                         WHERE approval_id = :id AND status IN ('PENDING', 'APPROVED')
                        """)
                .param("revokedBy", revokedBy)
                .param("id", approvalId)
                .update();
        if (updated == 0) {
            throw new NoSuchElementException(
                    "no live relink approval " + approvalId + " to revoke");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(long approvalId) {
        /*
         * One conditional UPDATE, so two concurrent relinks serialise on the
         * row: exactly one sees status='APPROVED' and changes it, the other
         * updates nothing and is refused. An approval therefore authorises
         * one relink and no more.
         */
        return jdbc.sql("""
                        UPDATE authz.relink_approval
                           SET status = 'CONSUMED', consumed_at = CURRENT_TIMESTAMP
                         WHERE approval_id = :id AND status = 'APPROVED'
                        """)
                .param("id", approvalId)
                .update() == 1;
    }

    @Override
    public Optional<RelinkApproval> findById(long approvalId) {
        return jdbc.sql(COLUMNS + " WHERE approval_id = :id")
                .param("id", approvalId)
                .query(JdbcRelinkApprovalRepository::map)
                .optional();
    }

    @Override
    public Optional<RelinkApproval> findLive(InstitutionalIdentifier identity,
                                             String proposedCognitoIssuer,
                                             String proposedCognitoSubject) {
        return jdbc.sql(COLUMNS + """
                         WHERE institutional_identifier = :identity
                           AND proposed_cognito_issuer = :issuer
                           AND proposed_cognito_subject = :subject
                           AND status IN ('PENDING', 'APPROVED')
                        """)
                .param("identity", identity.value())
                .param("issuer", proposedCognitoIssuer)
                .param("subject", proposedCognitoSubject)
                .query(JdbcRelinkApprovalRepository::map)
                .optional();
    }

    @Override
    public List<RelinkApproval> findForIdentity(InstitutionalIdentifier identity) {
        return jdbc.sql(COLUMNS + " WHERE institutional_identifier = :identity ORDER BY approval_id")
                .param("identity", identity.value())
                .query(JdbcRelinkApprovalRepository::map)
                .list();
    }

    private static RelinkApproval map(ResultSet rs, int rowNum) throws SQLException {
        return new RelinkApproval(
                rs.getLong("approval_id"),
                new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                rs.getString("proposed_cognito_issuer"),
                rs.getString("proposed_cognito_subject"),
                rs.getString("requested_by"),
                toInstant(rs.getTimestamp("requested_at")),
                rs.getInt("grants_at_request"),
                rs.getString("reason"),
                rs.getString("approved_by"),
                toInstant(rs.getTimestamp("approved_at")),
                RelinkApproval.Status.valueOf(rs.getString("status")));
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
