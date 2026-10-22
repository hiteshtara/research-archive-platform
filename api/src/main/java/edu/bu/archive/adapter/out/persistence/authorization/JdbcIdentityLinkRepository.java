package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.IdentityLinkRepository;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/** Read-only access to authz.identity_link and authz.person_status (V082). */
@Repository
public class JdbcIdentityLinkRepository implements IdentityLinkRepository {

    private final JdbcClient jdbc;

    public JdbcIdentityLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IdentityLink> findByCognitoIdentity(ValidatedCognitoIdentity identity) {
        return jdbc.sql("""
                        SELECT identity_link_id, cognito_issuer, cognito_subject,
                               institutional_identifier, kuali_person_id, login_name,
                               status, verified_at
                        FROM authz.identity_link
                        WHERE cognito_issuer = :issuer AND cognito_subject = :subject
                        ORDER BY identity_link_id
                        """)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query((rs, rowNum) -> new IdentityLink(
                        rs.getLong("identity_link_id"),
                        new ValidatedCognitoIdentity(rs.getString("cognito_issuer"), rs.getString("cognito_subject")),
                        new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                        Optional.ofNullable(rs.getString("kuali_person_id")).map(KualiPersonId::new),
                        rs.getString("login_name"),
                        IdentityLinkStatus.valueOf(rs.getString("status")),
                        toInstant(rs.getTimestamp("verified_at"))
                ))
                .list();
    }

    @Override
    public List<KualiPersonId> activePersonIdsFor(InstitutionalIdentifier identifier) {
        return jdbc.sql("""
                        SELECT DISTINCT kuali_person_id
                        FROM authz.identity_link
                        WHERE institutional_identifier = :identifier
                          AND status = 'ACTIVE'
                          AND kuali_person_id IS NOT NULL
                        """)
                .param("identifier", identifier.value())
                .query((rs, rowNum) -> new KualiPersonId(rs.getString("kuali_person_id")))
                .list();
    }

    @Override
    public boolean isSuspended(InstitutionalIdentifier identifier) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM authz.person_status
                            WHERE institutional_identifier = :identifier AND suspended
                        )
                        """)
                .param("identifier", identifier.value())
                .query(Boolean.class)
                .single();
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
