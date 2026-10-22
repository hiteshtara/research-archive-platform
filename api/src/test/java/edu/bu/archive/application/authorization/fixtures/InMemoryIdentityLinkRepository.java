package edu.bu.archive.application.authorization.fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.IdentityLinkRepository;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/** TEST-ONLY in-memory identity store. */
public final class InMemoryIdentityLinkRepository implements IdentityLinkRepository {

    private final List<IdentityLink> links = new ArrayList<>();
    private final Set<String> suspended = new HashSet<>();
    private final AtomicLong ids = new AtomicLong();

    public IdentityLink link(
            ValidatedCognitoIdentity identity,
            String institutionalId,
            String kualiPersonId,
            String loginName,
            IdentityLinkStatus status
    ) {
        var link = new IdentityLink(
                ids.incrementAndGet(),
                identity,
                new InstitutionalIdentifier(institutionalId),
                Optional.ofNullable(kualiPersonId).map(KualiPersonId::new),
                loginName,
                status,
                Instant.parse("2026-10-01T00:00:00Z")
        );
        links.add(link);
        return link;
    }

    public void suspend(String institutionalId) {
        suspended.add(institutionalId);
    }

    public void revokeAll(ValidatedCognitoIdentity identity) {
        links.replaceAll(link -> link.cognitoIdentity().equals(identity)
                ? new IdentityLink(link.id(), link.cognitoIdentity(), link.institutionalIdentifier(),
                        link.kualiPersonId(), link.loginName(), IdentityLinkStatus.REVOKED, link.verifiedAt())
                : link);
    }

    @Override
    public List<IdentityLink> findByCognitoIdentity(ValidatedCognitoIdentity identity) {
        return links.stream().filter(link -> link.cognitoIdentity().equals(identity)).toList();
    }

    @Override
    public List<KualiPersonId> activePersonIdsFor(InstitutionalIdentifier identifier) {
        return links.stream()
                .filter(IdentityLink::isActive)
                .filter(link -> link.institutionalIdentifier().value().equals(identifier.value()))
                .flatMap(link -> link.kualiPersonId().stream())
                .distinct()
                .toList();
    }

    @Override
    public boolean isSuspended(InstitutionalIdentifier identifier) {
        return suspended.contains(identifier.value());
    }
}
