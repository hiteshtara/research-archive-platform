package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port onto the archive's own identity store for enrollment: identity links,
 * the KIM principal crosswalk (V083) and the append-only audit. The only
 * writer of identity links in the API.
 */
public interface EnrollmentStore {

    /** Every link (active or revoked) for exactly this issuer + subject. */
    List<StoredLink> links(ValidatedCognitoIdentity identity);

    /**
     * True when an ACTIVE crosswalk row maps (attributeName, identifier) to
     * exactly this principal AND that principal is active (actv_ind = 'Y').
     */
    boolean mappingStillValid(String attributeName, String identifier, String principalId);

    /** Distinct principals of the ACTIVE crosswalk rows for (attributeName, value). */
    List<String> activeCrosswalkPrincipals(String attributeName, String value);

    /** Empty when no such KIM principal exists; otherwise whether it is active. */
    Optional<Boolean> principalActive(String principalId);

    /**
     * Atomically: refuse when another ACTIVE link exists for the same issuer
     * and institutional identifier; otherwise insert an ACTIVE AUTO_VERIFIED
     * link (login_name NULL) and the given audit row in one transaction. A
     * concurrent insert for the same issuer + subject is ALREADY_LINKED.
     */
    LinkResult link(ValidatedCognitoIdentity identity, String institutionalIdentifier, String principalId,
                    String verifiedBy, Audit linkedAudit);

    /** Revokes the link if still ACTIVE, with its audit row, atomically. False when nothing changed. */
    boolean revoke(long linkId, String revokedBy, Audit audit);

    void audit(Audit audit);

    enum LinkResult { LINKED, ALREADY_LINKED, IDENTIFIER_LINKED_TO_ANOTHER_PROFILE }

    record StoredLink(long id, boolean active, boolean autoVerified, String institutionalIdentifier,
                      String principalId) {
        @Override
        public String toString() {
            return "StoredLink[id=" + id + ", active=" + active + ", autoVerified=" + autoVerified + "]";
        }
    }

    /** One authz.access_audit row; detail is stored as JSON. */
    record Audit(String actor, String action, String institutionalIdentifier, Map<String, Object> detail) {
        public Audit {
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }
}
