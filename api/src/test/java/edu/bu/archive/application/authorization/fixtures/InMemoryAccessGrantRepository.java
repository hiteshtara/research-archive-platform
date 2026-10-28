package edu.bu.archive.application.authorization.fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.admin.GrantSpecification;

import java.util.NoSuchElementException;
import java.util.Optional;

/** TEST-ONLY in-memory grant store. */
public final class InMemoryAccessGrantRepository implements AccessGrantRepository {

    private final List<AccessGrant> grants = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    public AccessGrant grant(String grantee, GrantType type, String unit, boolean descendants, String io) {
        var grant = new AccessGrant(ids.incrementAndGet(), new InstitutionalIdentifier(grantee), type,
                unit, descendants, io, null, null, null);
        grants.add(grant);
        return grant;
    }

    public void add(AccessGrant grant) {
        grants.add(grant);
    }

    public void revoke(long grantId, Instant at) {
        grants.replaceAll(g -> g.id() == grantId
                ? new AccessGrant(g.id(), g.grantee(), g.type(), g.unitNumber(), g.includeDescendants(),
                        g.ioValue(), g.validFrom(), g.expiresAt(), at)
                : g);
    }

    @Override
    public List<AccessGrant> findForGrantee(InstitutionalIdentifier grantee) {
        return grants.stream().filter(g -> g.grantee().value().equals(grantee.value())).toList();
    }

    @Override
    public Optional<AccessGrant> findById(long grantId) {
        return grants.stream().filter(g -> g.id() == grantId).findFirst();
    }

    @Override
    public AccessGrant create(InstitutionalIdentifier grantee, GrantSpecification specification,
                              String grantedBy) {
        var created = new AccessGrant(ids.incrementAndGet(), grantee, specification.type(),
                specification.unitNumber(), specification.includeDescendants(),
                specification.ioValue(), Instant.EPOCH, specification.expiresAt(), null);
        grants.add(created);
        return created;
    }

    @Override
    public AccessGrant modify(long grantId, Instant expiresAt, boolean includeDescendants) {
        var existing = findById(grantId)
                .filter(g -> g.revokedAt() == null)
                .orElseThrow(() -> new NoSuchElementException("no active grant " + grantId));
        var modified = new AccessGrant(existing.id(), existing.grantee(), existing.type(),
                existing.unitNumber(), includeDescendants, existing.ioValue(),
                existing.validFrom(), expiresAt, null);
        grants.replaceAll(g -> g.id() == grantId ? modified : g);
        return modified;
    }

    @Override
    public void revoke(long grantId, String revokedBy, String reason) {
        findById(grantId)
                .filter(g -> g.revokedAt() == null)
                .orElseThrow(() -> new NoSuchElementException("no active grant " + grantId));
        revoke(grantId, Instant.EPOCH.plusSeconds(1));
    }
}
