package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

/**
 * authz.access_grant (V082).
 *
 * <p>Writes use {@code MANDATORY} propagation for the same reason
 * {@link JdbcAdminAuditSink} does: a grant change and its audit row must
 * commit together or not at all. A write called outside a transaction fails
 * loudly here rather than quietly committing an unaudited permission change.
 */
@Repository
public class JdbcAccessGrantRepository implements AccessGrantRepository {

    private static final String SELECT_COLUMNS = """
            SELECT grant_id, institutional_identifier, grant_type, unit_number,
                   include_descendants, io_value, valid_from, expires_at, revoked_at
            FROM authz.access_grant
            """;

    private final JdbcClient jdbc;

    public JdbcAccessGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AccessGrant> findForGrantee(InstitutionalIdentifier grantee) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE institutional_identifier = :grantee ORDER BY grant_id")
                .param("grantee", grantee.value())
                .query(JdbcAccessGrantRepository::mapGrant)
                .list();
    }

    @Override
    public Optional<AccessGrant> findById(long grantId) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE grant_id = :id")
                .param("id", grantId)
                .query(JdbcAccessGrantRepository::mapGrant)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccessGrant create(InstitutionalIdentifier grantee, GrantSpecification specification,
                              String grantedBy) {

        Long grantId = jdbc.sql("""
                        INSERT INTO authz.access_grant
                            (institutional_identifier, grant_type, unit_number,
                             include_descendants, io_value, granted_by, reason, expires_at)
                        VALUES (:grantee, :type, :unit, :descendants, :io, :grantedBy, :reason, :expires)
                        RETURNING grant_id
                        """)
                .param("grantee", grantee.value())
                .param("type", specification.type().name())
                .param("unit", specification.unitNumber())
                .param("descendants", specification.includeDescendants())
                .param("io", specification.ioValue())
                .param("grantedBy", grantedBy)
                .param("reason", specification.reason())
                .param("expires", toTimestamp(specification.expiresAt()))
                .query(Long.class)
                .single();

        return findById(grantId).orElseThrow(
                () -> new IllegalStateException("grant " + grantId + " vanished after insert"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccessGrant modify(long grantId, Instant expiresAt, boolean includeDescendants) {
        // Only UNIT grants may carry descendants (ck_access_grant_shape). Rather
        // than let the constraint decide, refuse here so the caller gets a
        // meaningful message instead of a database error.
        AccessGrant existing = findById(grantId)
                .filter(grant -> grant.revokedAt() == null)
                .orElseThrow(() -> new NoSuchElementException("no active grant " + grantId));
        if (includeDescendants && existing.type() != GrantType.UNIT) {
            throw new IllegalArgumentException(
                    existing.type() + " grant cannot include descendants");
        }

        jdbc.sql("""
                        UPDATE authz.access_grant
                           SET expires_at = :expires, include_descendants = :descendants
                         WHERE grant_id = :id AND revoked_at IS NULL
                        """)
                .param("expires", toTimestamp(expiresAt))
                .param("descendants", includeDescendants)
                .param("id", grantId)
                .update();

        return findById(grantId).orElseThrow(
                () -> new IllegalStateException("grant " + grantId + " vanished after update"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(long grantId, String revokedBy, String reason) {
        int updated = jdbc.sql("""
                        UPDATE authz.access_grant
                           SET revoked_at = CURRENT_TIMESTAMP,
                               revoked_by = :revokedBy,
                               reason = COALESCE(:reason, reason)
                         WHERE grant_id = :id AND revoked_at IS NULL
                        """)
                .param("revokedBy", revokedBy)
                .param("reason", reason)
                .param("id", grantId)
                .update();

        if (updated == 0) {
            // Either the id is unknown or it was already revoked. Both are "there
            // is nothing active to revoke", and neither should look like success.
            throw new NoSuchElementException("no active grant " + grantId);
        }
    }

    private static AccessGrant mapGrant(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccessGrant(
                rs.getLong("grant_id"),
                new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                GrantType.valueOf(rs.getString("grant_type")),
                rs.getString("unit_number"),
                rs.getBoolean("include_descendants"),
                rs.getString("io_value"),
                toInstant(rs.getTimestamp("valid_from")),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("revoked_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
