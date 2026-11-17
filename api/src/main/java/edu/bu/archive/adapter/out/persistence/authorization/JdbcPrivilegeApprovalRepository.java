package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.approval.ApprovalBinding;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeAction;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApproval;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalException;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalRepository;
import edu.bu.archive.application.authorization.admin.approval.PrivilegeApprovalService;

/**
 * {@code authz.privilege_approval} (V090, held back in
 * {@code database/migrations-pending-approval/}).
 *
 * <p>Parameters that may be NULL are always CAST: PostgreSQL cannot infer the
 * type of a bare NULL in {@code IS NOT DISTINCT FROM}, and the NULL-safe
 * comparison is the point - "no expiry" must match "no expiry" and nothing else.
 */
@Repository
public class JdbcPrivilegeApprovalRepository implements PrivilegeApprovalRepository {

    private static final String LIVE_INDEX = "ux_privilege_approval_live";

    private static final String COLUMNS = """
            SELECT approval_id, action, subject_identifier, grant_type, unit_number, io_value,
                   award_number, include_descendants, expires_at, target_grant_id,
                   cognito_issuer, cognito_subject, proposed_kuali_person_id,
                   requested_by, requested_at, reason,
                   approved_by, approved_at, approval_reason, valid_until,
                   consumed_at, consumed_by, revoked_at, revoked_by, revocation_reason, status
              FROM authz.privilege_approval
            """;

    /** The live-index key, NULL-safe. Shared by findLive and expireStale. */
    private static final String TARGET_KEY = """
               action = :action
               AND subject_identifier = :subject
               AND grant_type      IS NOT DISTINCT FROM CAST(:grantType AS VARCHAR)
               AND unit_number     IS NOT DISTINCT FROM CAST(:unitNumber AS VARCHAR)
               AND io_value        IS NOT DISTINCT FROM CAST(:ioValue AS TEXT)
               AND award_number    IS NOT DISTINCT FROM CAST(:awardNumber AS VARCHAR)
               AND target_grant_id IS NOT DISTINCT FROM CAST(:targetGrantId AS BIGINT)
               AND cognito_issuer  IS NOT DISTINCT FROM CAST(:cognitoIssuer AS TEXT)
               AND cognito_subject IS NOT DISTINCT FROM CAST(:cognitoSubject AS TEXT)
               AND proposed_kuali_person_id
                                   IS NOT DISTINCT FROM CAST(:proposedKualiPersonId AS TEXT)
            """;

    /** Approver is an active ARCHIVE_ADMIN and not suspended (section 3.3). */
    private static final String APPROVER_ELIGIBLE = """
               EXISTS (SELECT 1 FROM authz.admin_role_member m
                        WHERE m.institutional_identifier = %s AND m.status = 'ACTIVE')
               AND NOT EXISTS (SELECT 1 FROM authz.person_status s
                                WHERE s.institutional_identifier = %s AND s.suspended)
            """;

    private final JdbcClient jdbc;

    public JdbcPrivilegeApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PrivilegeApproval request(PrivilegeAction action, InstitutionalIdentifier subject,
                                     ApprovalBinding binding, String requestedBy, String reason) {
        Long id;
        try {
            id = bind(jdbc.sql("""
                            INSERT INTO authz.privilege_approval
                                (action, subject_identifier, grant_type, unit_number, io_value,
                                 award_number, include_descendants, expires_at, target_grant_id,
                                 cognito_issuer, cognito_subject, proposed_kuali_person_id,
                                 requested_by, reason, valid_until)
                            VALUES (:action, :subject, :grantType, :unitNumber, :ioValue,
                                    :awardNumber, :includeDescendants, :expiresAt, :targetGrantId,
                                    :cognitoIssuer, :cognitoSubject, :proposedKualiPersonId,
                                    :requestedBy, :reason,
                                    -- overwritten by the trigger; supplied only to satisfy NOT NULL
                                    CURRENT_TIMESTAMP)
                            RETURNING approval_id
                            """), action, subject, binding)
                    .param("includeDescendants", binding.includeDescendants())
                    .param("expiresAt", toTimestamp(binding.expiresAt()))
                    .param("requestedBy", requestedBy)
                    .param("reason", reason)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(LIVE_INDEX)) {
                throw new PrivilegeApprovalException(
                        PrivilegeApprovalException.Refusal.DUPLICATE_LIVE_REQUEST,
                        "a live " + action + " request for this subject and target already "
                                + "exists. Approve, use or revoke it first. Nothing was written.");
            }
            throw e;
        }
        return findById(id).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean approve(long approvalId, String approvedBy, String approvalReason) {
        /*
         * Every rule is in the WHERE clause, so a concurrent change between
         * the service's checks and this statement still refuses. The two
         * identity rules are ALSO CHECK constraints: this clause makes the
         * refusal a clean "0 rows" rather than a constraint violation.
         */
        return jdbc.sql("""
                        UPDATE authz.privilege_approval
                           SET status = 'APPROVED',
                               approved_by = :approvedBy,
                               approved_at = CURRENT_TIMESTAMP,
                               approval_reason = :approvalReason
                         WHERE approval_id = :id
                           AND status = 'PENDING'
                           AND requested_by <> :approvedBy
                           AND subject_identifier <> :approvedBy
                           AND valid_until > CURRENT_TIMESTAMP
                           AND """ + APPROVER_ELIGIBLE.formatted(":approvedBy", ":approvedBy"))
                .param("approvedBy", approvedBy)
                .param("approvalReason", approvalReason)
                .param("id", approvalId)
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revoke(long approvalId, String revokedBy, String revocationReason) {
        return jdbc.sql("""
                        UPDATE authz.privilege_approval
                           SET status = 'REVOKED',
                               revoked_by = :revokedBy,
                               revoked_at = CURRENT_TIMESTAMP,
                               revocation_reason = :reason
                         WHERE approval_id = :id AND status IN ('PENDING', 'APPROVED')
                        """)
                .param("revokedBy", revokedBy)
                .param("reason", revocationReason)
                .param("id", approvalId)
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(long approvalId, PrivilegeAction action,
                           InstitutionalIdentifier subject, ApprovalBinding binding,
                           String consumedBy) {
        /*
         * One conditional UPDATE. Concurrent executors serialise on the row:
         * exactly one sees status = 'APPROVED' and changes it; every other
         * re-evaluates after that commit, sees CONSUMED, and updates nothing.
         *
         * include_descendants and expires_at are compared here even though
         * they are not in the live-index key: the approval covers the values
         * the approver saw, not merely the same grant.
         */
        return bind(jdbc.sql("""
                        UPDATE authz.privilege_approval
                           SET status = 'CONSUMED',
                               consumed_at = CURRENT_TIMESTAMP,
                               consumed_by = :consumedBy
                         WHERE approval_id = :id
                           AND status = 'APPROVED'
                           AND valid_until > CURRENT_TIMESTAMP
                           AND """ + TARGET_KEY + """
                           AND include_descendants IS NOT DISTINCT FROM CAST(:includeDescendants AS BOOLEAN)
                           AND expires_at IS NOT DISTINCT FROM CAST(:expiresAt AS TIMESTAMPTZ)
                           AND """ + APPROVER_ELIGIBLE.formatted("approved_by", "approved_by")),
                action, subject, binding)
                .param("includeDescendants", binding.includeDescendants())
                .param("expiresAt", toTimestamp(binding.expiresAt()))
                .param("consumedBy", consumedBy)
                .param("id", approvalId)
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PrivilegeApproval> expireStale(PrivilegeAction action,
                                               InstitutionalIdentifier subject,
                                               ApprovalBinding binding) {
        List<Long> ids = bind(jdbc.sql("""
                        UPDATE authz.privilege_approval
                           SET status = 'REVOKED',
                               revoked_by = :expiredActor,
                               revoked_at = CURRENT_TIMESTAMP,
                               revocation_reason = 'expired: 72 hours passed since the request'
                         WHERE status IN ('PENDING', 'APPROVED')
                           AND valid_until <= CURRENT_TIMESTAMP
                           AND """ + TARGET_KEY + """
                        RETURNING approval_id
                        """), action, subject, binding)
                .param("expiredActor", PrivilegeApprovalService.EXPIRED_ACTOR)
                .query(Long.class)
                .list();
        return ids.stream().map(id -> findById(id).orElseThrow()).toList();
    }

    @Override
    public Optional<PrivilegeApproval> findById(long approvalId) {
        return jdbc.sql(COLUMNS + " WHERE approval_id = :id")
                .param("id", approvalId)
                .query(JdbcPrivilegeApprovalRepository::map)
                .optional();
    }

    @Override
    public Optional<PrivilegeApproval> findLive(PrivilegeAction action,
                                                InstitutionalIdentifier subject,
                                                ApprovalBinding binding) {
        return bind(jdbc.sql(COLUMNS + " WHERE status IN ('PENDING', 'APPROVED') AND "
                        + TARGET_KEY), action, subject, binding)
                .query(JdbcPrivilegeApprovalRepository::map)
                .optional();
    }

    @Override
    public List<PrivilegeApproval> findAllLive() {
        return jdbc.sql(COLUMNS + " WHERE status IN ('PENDING', 'APPROVED') ORDER BY approval_id")
                .query(JdbcPrivilegeApprovalRepository::map)
                .list();
    }

    // ---------------------------------------------------------------------

    /** The live-index key parameters. */
    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec,
                                                 PrivilegeAction action,
                                                 InstitutionalIdentifier subject,
                                                 ApprovalBinding binding) {
        return spec
                .param("action", action.name())
                .param("subject", subject.value())
                .param("grantType", binding.grantType() == null ? null : binding.grantType().name())
                .param("unitNumber", binding.unitNumber())
                .param("ioValue", binding.ioValue())
                .param("awardNumber", binding.awardNumber())
                .param("targetGrantId", binding.targetGrantId())
                .param("cognitoIssuer", binding.cognitoIssuer())
                .param("cognitoSubject", binding.cognitoSubject())
                .param("proposedKualiPersonId", binding.proposedKualiPersonId());
    }

    private static PrivilegeApproval map(ResultSet rs, int rowNum) throws SQLException {
        String grantType = rs.getString("grant_type");
        Object descendants = rs.getObject("include_descendants");
        Object target = rs.getObject("target_grant_id");
        ApprovalBinding binding = new ApprovalBinding(
                grantType == null ? null : GrantType.valueOf(grantType),
                rs.getString("unit_number"),
                rs.getString("io_value"),
                rs.getString("award_number"),
                descendants == null ? null : (Boolean) descendants,
                toInstant(rs.getTimestamp("expires_at")),
                target == null ? null : ((Number) target).longValue(),
                rs.getString("cognito_issuer"),
                rs.getString("cognito_subject"),
                rs.getString("proposed_kuali_person_id"));
        return new PrivilegeApproval(
                rs.getLong("approval_id"),
                PrivilegeAction.valueOf(rs.getString("action")),
                new InstitutionalIdentifier(rs.getString("subject_identifier")),
                binding,
                rs.getString("requested_by"),
                toInstant(rs.getTimestamp("requested_at")),
                rs.getString("reason"),
                rs.getString("approved_by"),
                toInstant(rs.getTimestamp("approved_at")),
                rs.getString("approval_reason"),
                toInstant(rs.getTimestamp("valid_until")),
                toInstant(rs.getTimestamp("consumed_at")),
                rs.getString("consumed_by"),
                toInstant(rs.getTimestamp("revoked_at")),
                rs.getString("revoked_by"),
                rs.getString("revocation_reason"),
                PrivilegeApproval.Status.valueOf(rs.getString("status")));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
