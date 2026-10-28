package edu.bu.archive.application.authorization.fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.IdentityLink;
import edu.bu.archive.application.authorization.IdentityLinkStatus;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.KualiPersonId;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminIdentityRepository;
import edu.bu.archive.application.authorization.admin.IdentitySearchResult;

/** TEST-ONLY in-memory identity/suspension store. */
public final class InMemoryAdminIdentityRepository implements AdminIdentityRepository {

    private final List<IdentityLink> links = new ArrayList<>();
    private final Map<String, Boolean> suspensions = new LinkedHashMap<>();
    private final List<IdentitySearchResult> searchable = new ArrayList<>();
    private final Map<String, Integer> grantCounts = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public void setActiveGrantCount(String identity, int count) {
        grantCounts.put(identity, count);
    }

    public void addSearchable(IdentitySearchResult result) {
        searchable.add(result);
    }

    @Override
    public List<IdentityLink> findLinksFor(InstitutionalIdentifier identity) {
        return links.stream()
                .filter(link -> link.institutionalIdentifier().value().equals(identity.value()))
                .toList();
    }

    @Override
    public IdentityLink link(String cognitoIssuer, String cognitoSubject,
                             InstitutionalIdentifier identity, KualiPersonId principal,
                             String loginName, String verifiedBy) {

        boolean alreadyLinked = links.stream().anyMatch(link -> link.isActive()
                && link.cognitoIdentity().issuer().equals(cognitoIssuer)
                && link.cognitoIdentity().subject().equals(cognitoSubject));
        if (alreadyLinked) {
            throw new IllegalStateException("an active identity link already exists");
        }
        if (hasEverBeenLinked(identity)) {
            throw new IllegalStateException("this archive identity has been linked before");
        }
        var created = new IdentityLink(ids.incrementAndGet(),
                new ValidatedCognitoIdentity(cognitoIssuer, cognitoSubject),
                identity, Optional.ofNullable(principal), loginName,
                IdentityLinkStatus.ACTIVE, Instant.EPOCH);
        links.add(created);
        return created;
    }

    @Override
    public IdentityLink relink(String cognitoIssuer, String cognitoSubject,
                               InstitutionalIdentifier identity, KualiPersonId principal,
                               String loginName, String verifiedBy) {
        if (!hasEverBeenLinked(identity)) {
            throw new IllegalStateException("never linked; use link()");
        }
        var created = new IdentityLink(ids.incrementAndGet(),
                new ValidatedCognitoIdentity(cognitoIssuer, cognitoSubject),
                identity, Optional.ofNullable(principal), loginName,
                IdentityLinkStatus.ACTIVE, Instant.EPOCH);
        links.add(created);
        return created;
    }

    @Override
    public int activeGrantCountFor(InstitutionalIdentifier identity) {
        return grantCounts.getOrDefault(identity.value(), 0);
    }

    @Override
    public boolean hasEverBeenLinked(InstitutionalIdentifier identity) {
        return links.stream()
                .anyMatch(link -> link.institutionalIdentifier().value().equals(identity.value()));
    }

    @Override
    public InstitutionalIdentifier revokeLink(long linkId, String revokedBy) {
        var existing = links.stream()
                .filter(link -> link.id() == linkId && link.isActive())
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("no active identity link " + linkId));
        links.replaceAll(link -> link.id() == linkId
                ? new IdentityLink(link.id(), link.cognitoIdentity(), link.institutionalIdentifier(),
                        link.kualiPersonId(), link.loginName(), IdentityLinkStatus.REVOKED,
                        link.verifiedAt())
                : link);
        return existing.institutionalIdentifier();
    }

    @Override
    public boolean isSuspended(InstitutionalIdentifier identity) {
        return suspensions.getOrDefault(identity.value(), false);
    }

    @Override
    public void setSuspended(InstitutionalIdentifier identity, boolean suspended,
                             String changedBy, String reason) {
        suspensions.put(identity.value(), suspended);
    }

    @Override
    public List<IdentitySearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toLowerCase();
        return searchable.stream()
                .filter(result -> contains(result.institutionalIdentifier(), needle)
                        || contains(result.kualiPersonId(), needle)
                        || contains(result.displayName(), needle))
                .limit(Math.max(1, limit))
                .toList();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }
}
