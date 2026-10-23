package edu.bu.archive.application.authorization;

import java.time.Instant;
import java.util.Optional;

/**
 * One archive-owned link from a Cognito identity (issuer + subject) to a
 * verified institutional identity and, when established, a Kuali PERSON_ID.
 * Created only by a trusted enrollment or provisioning process - never from
 * request input. {@code loginName} is for display and lookup only and is
 * never used to find or authorize anyone.
 */
public record IdentityLink(
        long id,
        ValidatedCognitoIdentity cognitoIdentity,
        InstitutionalIdentifier institutionalIdentifier,
        Optional<KualiPersonId> kualiPersonId,
        String loginName,
        IdentityLinkStatus status,
        Instant verifiedAt
) {

    public boolean isActive() {
        return status == IdentityLinkStatus.ACTIVE;
    }
}
