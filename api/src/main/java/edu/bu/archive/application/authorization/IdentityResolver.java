package edu.bu.archive.application.authorization;

import java.util.List;
import java.util.Objects;

/**
 * Maps a validated Cognito identity to an archive person through the
 * archive's own identity links. Fails closed: anything other than exactly
 * one active link to an unsuspended, unambiguous person is not a mapping.
 */
public class IdentityResolver {

    private final IdentityLinkRepository links;

    public IdentityResolver(IdentityLinkRepository links) {
        this.links = Objects.requireNonNull(links);
    }

    public IdentityResolution resolve(ValidatedCognitoIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        List<IdentityLink> all = links.findByCognitoIdentity(identity);
        List<IdentityLink> active = all.stream().filter(IdentityLink::isActive).toList();

        if (active.isEmpty()) {
            return all.isEmpty()
                    ? new IdentityResolution.NotProvisioned(identity)
                    : new IdentityResolution.Revoked(identity);
        }
        if (active.size() > 1) {
            return new IdentityResolution.Ambiguous(identity);
        }

        IdentityLink link = active.get(0);
        if (links.isSuspended(link.institutionalIdentifier())) {
            return new IdentityResolution.Suspended(identity);
        }
        // One institutional identity must belong to at most one person.
        if (links.activePersonIdsFor(link.institutionalIdentifier()).size() > 1) {
            return new IdentityResolution.Ambiguous(identity);
        }
        return new IdentityResolution.Mapped(
                identity,
                link.institutionalIdentifier(),
                link.kualiPersonId()
        );
    }
}
