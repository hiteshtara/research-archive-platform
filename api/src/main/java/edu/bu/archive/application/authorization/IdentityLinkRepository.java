package edu.bu.archive.application.authorization;

import java.util.List;

/** Read-only port onto the archive-owned identity store. */
public interface IdentityLinkRepository {

    /** Every link (active or revoked) for exactly this issuer + subject. */
    List<IdentityLink> findByCognitoIdentity(ValidatedCognitoIdentity identity);

    /** Distinct Kuali PERSON_IDs actively linked to this institutional identity. */
    List<KualiPersonId> activePersonIdsFor(InstitutionalIdentifier identifier);

    boolean isSuspended(InstitutionalIdentifier identifier);
}
