package edu.bu.archive.application.authorization.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.EnrollmentStore;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;

/** TEST-ONLY in-memory enrollment store with the same rules as JdbcEnrollmentStore. */
public class InMemoryEnrollmentStore implements EnrollmentStore {

    public record Link(long id, String issuer, String subject, String identifier, String principal,
                       boolean auto, boolean active) {
    }

    public record Crosswalk(String attribute, String value, String principal, boolean active) {
    }

    public final List<Link> links = new ArrayList<>();
    public final List<Crosswalk> crosswalk = new ArrayList<>();
    public final Map<String, Boolean> principals = new HashMap<>();
    public final List<Audit> audits = new ArrayList<>();
    /** Simulates a concurrent insert of the same issuer + subject between check and write. */
    public boolean raceOnInsert;
    private final AtomicLong ids = new AtomicLong(100);

    public Link addLink(ValidatedCognitoIdentity identity, String identifier, String principal, boolean auto,
                        boolean active) {
        Link link = new Link(ids.incrementAndGet(), identity.issuer(), identity.subject(), identifier, principal,
                auto, active);
        links.add(link);
        return link;
    }

    @Override
    public List<StoredLink> links(ValidatedCognitoIdentity identity) {
        return links.stream()
                .filter(l -> l.issuer().equals(identity.issuer()) && l.subject().equals(identity.subject()))
                .map(l -> new StoredLink(l.id(), l.active(), l.auto(), l.identifier(), l.principal()))
                .toList();
    }

    @Override
    public boolean mappingStillValid(String attributeName, String identifier, String principalId) {
        return crosswalk.stream().anyMatch(x -> x.active() && x.attribute().equals(attributeName)
                && x.value().equals(identifier) && x.principal().equals(principalId))
                && Boolean.TRUE.equals(principals.get(principalId));
    }

    @Override
    public List<String> activeCrosswalkPrincipals(String attributeName, String value) {
        return crosswalk.stream()
                .filter(x -> x.active() && x.attribute().equals(attributeName) && x.value().equals(value))
                .map(Crosswalk::principal).distinct().sorted().toList();
    }

    @Override
    public Optional<Boolean> principalActive(String principalId) {
        return Optional.ofNullable(principals.get(principalId));
    }

    @Override
    public LinkResult link(ValidatedCognitoIdentity identity, String identifier, String principalId,
                           String verifiedBy, Audit linkedAudit) {
        boolean other = links.stream().anyMatch(l -> l.active() && l.issuer().equals(identity.issuer())
                && l.identifier().equals(identifier) && !l.subject().equals(identity.subject()));
        if (other) {
            return LinkResult.IDENTIFIER_LINKED_TO_ANOTHER_PROFILE;
        }
        if (raceOnInsert) {
            addLink(identity, identifier, principalId, true, true);
        }
        boolean exists = links.stream().anyMatch(l -> l.active() && l.issuer().equals(identity.issuer())
                && l.subject().equals(identity.subject()));
        if (exists) {
            return LinkResult.ALREADY_LINKED;
        }
        addLink(identity, identifier, principalId, true, true);
        audits.add(linkedAudit);
        return LinkResult.LINKED;
    }

    @Override
    public boolean revoke(long linkId, String revokedBy, Audit audit) {
        for (int i = 0; i < links.size(); i++) {
            Link l = links.get(i);
            if (l.id() == linkId && l.active()) {
                links.set(i, new Link(l.id(), l.issuer(), l.subject(), l.identifier(), l.principal(), l.auto(),
                        false));
                audits.add(audit);
                return true;
            }
        }
        return false;
    }

    @Override
    public void audit(Audit audit) {
        audits.add(audit);
    }

    public List<String> outcomes() {
        return audits.stream().map(a -> String.valueOf(a.detail().get("outcome"))).toList();
    }
}
