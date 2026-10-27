package edu.bu.archive.adapter.out.persistence.authorization;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.IdentitySearchResult;

/** Administrative writes over authz.identity_link and authz.person_status (V082). */
@Repository
public class JdbcAdminIdentityRepository implements AdminIdentityRepository {

    /** Recorded in identity_link.method so an administrator-created link is identifiable. */
    static final String METHOD_ADMIN_VERIFIED = "ADMIN_VERIFIED";

    private final JdbcClient jdbc;

    public JdbcAdminIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IdentityLink> findLinksFor(InstitutionalIdentifier identity) {
        return jdbc.sql("""
                        SELECT identity_link_id, cognito_issuer, cognito_subject,
                               institutional_identifier, kuali_person_id, login_name,
                               status, verified_at
                        FROM authz.identity_link
                        WHERE institutional_identifier = :identity
                        ORDER BY identity_link_id
                        """)
                .param("identity", identity.value())
                .query((rs, rowNum) -> new IdentityLink(
                        rs.getLong("identity_link_id"),
                        new ValidatedCognitoIdentity(rs.getString("cognito_issuer"),
                                rs.getString("cognito_subject")),
                        new InstitutionalIdentifier(rs.getString("institutional_identifier")),
                        Optional.ofNullable(rs.getString("kuali_person_id")).map(KualiPersonId::new),
                        rs.getString("login_name"),
                        IdentityLinkStatus.valueOf(rs.getString("status")),
                        toInstant(rs.getTimestamp("verified_at"))))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityLink link(String cognitoIssuer, String cognitoSubject,
                             InstitutionalIdentifier identity, KualiPersonId principal,
                             String loginName, String verifiedBy) {

        boolean alreadyLinked = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM authz.identity_link
                            WHERE cognito_issuer = :issuer AND cognito_subject = :subject
                              AND status = 'ACTIVE')
                        """)
                .param("issuer", cognitoIssuer)
                .param("subject", cognitoSubject)
                .query(Boolean.class)
                .single();
        if (alreadyLinked) {
            // A second active link would make IdentityResolver return Ambiguous,
            // which denies everything - so refuse here, where it can be explained.
            throw new IllegalStateException(
                    "an active identity link already exists for this Cognito identity");
        }

        Long linkId = jdbc.sql("""
                        INSERT INTO authz.identity_link
                            (cognito_issuer, cognito_subject, institutional_identifier,
                             kuali_person_id, login_name, method, status, verified_by, verified_at)
                        VALUES (:issuer, :subject, :identity, :principal, :loginName,
                                :method, 'ACTIVE', :verifiedBy, CURRENT_TIMESTAMP)
                        RETURNING identity_link_id
                        """)
                .param("issuer", cognitoIssuer)
                .param("subject", cognitoSubject)
                .param("identity", identity.value())
                .param("principal", principal == null ? null : principal.value())
                .param("loginName", loginName)
                .param("method", METHOD_ADMIN_VERIFIED)
                .param("verifiedBy", verifiedBy)
                .query(Long.class)
                .single();

        return findLinksFor(identity).stream()
                .filter(link -> link.id() == linkId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("link " + linkId + " vanished after insert"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InstitutionalIdentifier revokeLink(long linkId, String revokedBy) {
        String identity = jdbc.sql("""
                        UPDATE authz.identity_link
                           SET status = 'REVOKED',
                               revoked_by = :revokedBy,
                               revoked_at = CURRENT_TIMESTAMP
                         WHERE identity_link_id = :id AND status = 'ACTIVE'
                        RETURNING institutional_identifier
                        """)
                .param("revokedBy", revokedBy)
                .param("id", linkId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException("no active identity link " + linkId));
        return new InstitutionalIdentifier(identity);
    }

    @Override
    public boolean isSuspended(InstitutionalIdentifier identity) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM authz.person_status
                            WHERE institutional_identifier = :identity AND suspended)
                        """)
                .param("identity", identity.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void setSuspended(InstitutionalIdentifier identity, boolean suspended,
                             String changedBy, String reason) {
        jdbc.sql("""
                        INSERT INTO authz.person_status
                            (institutional_identifier, suspended, changed_by, changed_at, reason)
                        VALUES (:identity, :suspended, :changedBy, CURRENT_TIMESTAMP, :reason)
                        ON CONFLICT (institutional_identifier) DO UPDATE
                           SET suspended = EXCLUDED.suspended,
                               changed_by = EXCLUDED.changed_by,
                               changed_at = EXCLUDED.changed_at,
                               reason = EXCLUDED.reason
                        """)
                .param("identity", identity.value())
                .param("suspended", suspended)
                .param("changedBy", changedBy)
                .param("reason", reason)
                .update();
    }

    /*
     * Three sources, merged with identity_link winning over sign-in history
     * winning over archive.person, because that is the order of certainty
     * about who the person is. A LinkedHashMap keyed on the identifier keeps
     * the first (most certain) hit and preserves insertion order.
     *
     * archive.person entries have no institutional identifier at all - they
     * are historical KRIM rows, and which BU attribute becomes the
     * institutional identifier is still undecided (D-ID-3). They are returned
     * so an administrator can find the right person and read off the
     * principal id; the identifier must then be supplied explicitly.
     */
    @Override
    public List<IdentitySearchResult> search(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String pattern = "%" + trimmed.toLowerCase() + "%";
        int capped = Math.max(1, Math.min(limit, 100));

        Map<String, IdentitySearchResult> found = new LinkedHashMap<>();

        jdbc.sql("""
                        SELECT l.institutional_identifier,
                               MAX(l.kuali_person_id)                        AS kuali_person_id,
                               MAX(COALESCE(p.full_name, l.login_name))      AS display_name,
                               BOOL_OR(l.status = 'ACTIVE')                  AS has_active_link
                        FROM authz.identity_link l
                        LEFT JOIN archive.person p ON p.person_id = l.kuali_person_id
                        WHERE LOWER(l.institutional_identifier) LIKE :pattern
                           OR LOWER(COALESCE(l.login_name, '')) LIKE :pattern
                           OR LOWER(COALESCE(l.kuali_person_id, '')) LIKE :pattern
                           OR LOWER(COALESCE(p.full_name, '')) LIKE :pattern
                        GROUP BY l.institutional_identifier
                        ORDER BY l.institutional_identifier
                        LIMIT :limit
                        """)
                .param("pattern", pattern)
                .param("limit", capped)
                .query((rs, rowNum) -> new IdentitySearchResult(
                        IdentitySearchResult.Source.IDENTITY_LINK,
                        rs.getString("institutional_identifier"),
                        rs.getString("kuali_person_id"),
                        rs.getString("display_name"),
                        rs.getBoolean("has_active_link"),
                        false, false, 0))
                .list()
                .forEach(result -> found.put(result.institutionalIdentifier(), result));

        jdbc.sql("""
                        SELECT DISTINCT a.institutional_identifier
                        FROM authz.access_audit a
                        WHERE a.institutional_identifier IS NOT NULL
                          AND LOWER(a.institutional_identifier) LIKE :pattern
                        ORDER BY a.institutional_identifier
                        LIMIT :limit
                        """)
                .param("pattern", pattern)
                .param("limit", capped)
                .query((rs, rowNum) -> rs.getString("institutional_identifier"))
                .list()
                .forEach(identifier -> found.computeIfAbsent(identifier, id ->
                        new IdentitySearchResult(IdentitySearchResult.Source.SIGN_IN_HISTORY,
                                id, null, null, false, false, false, 0)));

        List<IdentitySearchResult> historical = jdbc.sql("""
                        SELECT person_id, full_name
                        FROM archive.person
                        WHERE LOWER(person_id) LIKE :pattern
                           OR LOWER(COALESCE(full_name, '')) LIKE :pattern
                        ORDER BY full_name NULLS LAST, person_id
                        LIMIT :limit
                        """)
                .param("pattern", pattern)
                .param("limit", capped)
                .query((rs, rowNum) -> new IdentitySearchResult(
                        IdentitySearchResult.Source.HISTORICAL_PERSON,
                        null,
                        rs.getString("person_id"),
                        rs.getString("full_name"),
                        false, false, false, 0))
                .list();

        List<IdentitySearchResult> results = new ArrayList<>(found.values());
        historical.stream()
                .filter(person -> found.values().stream()
                        .noneMatch(known -> person.kualiPersonId() != null
                                && person.kualiPersonId().equals(known.kualiPersonId())))
                .forEach(results::add);

        return results.size() > capped ? results.subList(0, capped) : results;
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
