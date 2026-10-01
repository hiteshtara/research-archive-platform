package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;

/** Read-only access to authz.access_grant (V082). */
@Repository
public class JdbcAccessGrantRepository implements AccessGrantRepository {

    private final JdbcClient jdbc;

    public JdbcAccessGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AccessGrant> findForGrantee(InstitutionalIdentifier grantee) {
        return jdbc.sql("""
                        SELECT grant_id, institutional_identifier, grant_type, unit_number,
                               include_descendants, io_value, valid_from, expires_at, revoked_at
                        FROM authz.access_grant
                        WHERE institutional_identifier = :grantee
                        ORDER BY grant_id
                        """)
                .param("grantee", grantee.value())
                .query((rs, rowNum) -> new AccessGrant(
                        rs.getLong("grant_id"),
                        new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                        GrantType.valueOf(rs.getString("grant_type")),
                        rs.getString("unit_number"),
                        rs.getBoolean("include_descendants"),
                        rs.getString("io_value"),
                        toInstant(rs.getTimestamp("valid_from")),
                        toInstant(rs.getTimestamp("expires_at")),
                        toInstant(rs.getTimestamp("revoked_at"))
                ))
                .list();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
