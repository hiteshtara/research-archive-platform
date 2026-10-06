package edu.bu.archive.application.authorization.fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import edu.bu.archive.application.authorization.AccessGrant;
import edu.bu.archive.application.authorization.AccessGrantRepository;
import edu.bu.archive.application.authorization.GrantType;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;

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
}
