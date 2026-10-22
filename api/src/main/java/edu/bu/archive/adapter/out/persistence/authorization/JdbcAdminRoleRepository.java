package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleMemberStatus;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;
import edu.bu.archive.application.authorization.admin.LastArchiveAdministratorException;

/** authz.admin_role_member (V084). */
@Repository
public class JdbcAdminRoleRepository implements AdminRoleRepository {

    private static final String COLUMNS = """
            admin_member_id, institutional_identifier, status,
            granted_by, granted_at, revoked_by, revoked_at, reason
            """;

    private final JdbcClient jdbc;

    public JdbcAdminRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AdminRoleMember> findActive(InstitutionalIdentifier member) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM authz.admin_role_member
                        WHERE institutional_identifier = :member AND status = 'ACTIVE'
                        """)
                .param("member", member.value())
                .query(JdbcAdminRoleRepository::map)
                .optional();
    }

    @Override
    public List<AdminRoleMember> findHistory(InstitutionalIdentifier member) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM authz.admin_role_member
                        WHERE institutional_identifier = :member
                        ORDER BY admin_member_id DESC
                        """)
                .param("member", member.value())
                .query(JdbcAdminRoleRepository::map)
                .list();
    }

    @Override
    public List<AdminRoleMember> findAllActive() {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM authz.admin_role_member
                        WHERE status = 'ACTIVE'
                        ORDER BY admin_member_id
                        """)
                .query(JdbcAdminRoleRepository::map)
                .list();
    }

    @Override
    public int countActive() {
        Integer n = jdbc.sql(
                        "SELECT count(*) FROM authz.admin_role_member WHERE status = 'ACTIVE'")
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AdminRoleMember assign(
            InstitutionalIdentifier member, String grantedBy, String reason) {

        if (findActive(member).isPresent()) {
            throw new IllegalStateException(
                    "identity already holds an active ARCHIVE_ADMIN membership");
        }
        return insert(member, grantedBy, reason);
    }

    /**
     * Revoke under the locking contract.
     *
     * <p>{@code FOR UPDATE} on the ACTIVE rows is what makes the guard correct.
     * The count is taken from the locked set, so a concurrent revoke of the
     * other final administrator blocks until this transaction commits and then
     * re-reads a count of one - and is refused. Without the lock both
     * transactions would read two and both would succeed, leaving zero
     * administrators and no way back in through the application.
     *
     * <p>{@code MANDATORY} propagation: this must never create its own
     * transaction, or the lock would be released before the caller's audit
     * write commits, letting an administrative change persist without its
     * audit row.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeWithLastAdministratorGuard(
            InstitutionalIdentifier member, String revokedBy, String reason) {

        List<Long> activeIds = jdbc.sql("""
                        SELECT admin_member_id
                        FROM authz.admin_role_member
                        WHERE status = 'ACTIVE'
                        ORDER BY admin_member_id
                        FOR UPDATE
                        """)
                .query(Long.class)
                .list();

        Optional<AdminRoleMember> target = findActive(member);
        if (target.isEmpty()) {
            throw new IllegalStateException(
                    "identity holds no active ARCHIVE_ADMIN membership to revoke");
        }
        if (activeIds.size() <= 1) {
            throw new LastArchiveAdministratorException(
                    "refusing to revoke the last active ARCHIVE_ADMIN; assign another "
                            + "administrator first");
        }

        int updated = jdbc.sql("""
                        UPDATE authz.admin_role_member
                        SET status = 'REVOKED',
                            revoked_by = :revokedBy,
                            revoked_at = CURRENT_TIMESTAMP,
                            reason = COALESCE(:reason, reason)
                        WHERE admin_member_id = :id AND status = 'ACTIVE'
                        """)
                .param("revokedBy", revokedBy)
                .param("reason", reason)
                .param("id", target.get().adminMemberId())
                .update();

        if (updated != 1) {
            throw new IllegalStateException(
                    "concurrent modification revoking ARCHIVE_ADMIN membership");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AdminRoleMember bootstrap(
            InstitutionalIdentifier member, String operator, String reason) {

        // Lock the whole active set, so two concurrent bootstraps cannot both
        // observe an empty table and both insert.
        List<Long> active = jdbc.sql("""
                        SELECT admin_member_id FROM authz.admin_role_member
                        WHERE status = 'ACTIVE' FOR UPDATE
                        """)
                .query(Long.class)
                .list();

        if (!active.isEmpty()) {
            throw new IllegalStateException(
                    "refusing to bootstrap: an active ARCHIVE_ADMIN already exists. "
                            + "Assign further administrators through an existing one.");
        }

        return insert(member, AdminRoleMember.BOOTSTRAP_ACTOR,
                "operator=" + operator + (reason == null ? "" : "; " + reason));
    }

    private AdminRoleMember insert(
            InstitutionalIdentifier member, String grantedBy, String reason) {

        return jdbc.sql("""
                        INSERT INTO authz.admin_role_member
                            (institutional_identifier, status, granted_by, reason)
                        VALUES (:member, 'ACTIVE', :grantedBy, :reason)
                        RETURNING admin_member_id, institutional_identifier, status,
                                  granted_by, granted_at, revoked_by, revoked_at, reason
                        """)
                .param("member", member.value())
                .param("grantedBy", grantedBy)
                .param("reason", reason)
                .query(JdbcAdminRoleRepository::map)
                .single();
    }

    private static AdminRoleMember map(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRoleMember(
                rs.getLong("admin_member_id"),
                new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                AdminRoleMemberStatus.valueOf(rs.getString("status")),
                rs.getString("granted_by"),
                toInstant(rs.getTimestamp("granted_at")),
                rs.getString("revoked_by"),
                toInstant(rs.getTimestamp("revoked_at")),
                rs.getString("reason"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
