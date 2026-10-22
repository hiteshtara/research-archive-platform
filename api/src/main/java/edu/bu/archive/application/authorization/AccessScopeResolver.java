package edu.bu.archive.application.authorization;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Turns an identity resolution plus active grants into an {@link AccessOutcome}. */
public class AccessScopeResolver {

    private final AccessGrantRepository grants;
    private final Clock clock;

    public AccessScopeResolver(AccessGrantRepository grants, Clock clock) {
        this.grants = Objects.requireNonNull(grants);
        this.clock = Objects.requireNonNull(clock);
    }

    public AccessOutcome resolve(IdentityResolution resolution) {
        return switch (resolution) {
            case IdentityResolution.NotProvisioned ignored ->
                    new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_IDENTITY_LINK);
            case IdentityResolution.Ambiguous ignored ->
                    new AccessOutcome.Denied(AccessOutcome.DenialReason.AMBIGUOUS_IDENTITY);
            case IdentityResolution.Revoked ignored ->
                    new AccessOutcome.Denied(AccessOutcome.DenialReason.REVOKED_IDENTITY);
            case IdentityResolution.Suspended ignored ->
                    new AccessOutcome.Denied(AccessOutcome.DenialReason.SUSPENDED_IDENTITY);
            case IdentityResolution.Mapped mapped -> scopeFor(mapped);
        };
    }

    private AccessOutcome scopeFor(IdentityResolution.Mapped mapped) {
        var now = clock.instant();
        List<AccessGrant> active = grants.findForGrantee(mapped.institutionalIdentifier())
                .stream()
                .filter(grant -> grant.isActiveAt(now))
                .toList();

        boolean central = false;
        Set<UnitGrant> units = new HashSet<>();
        Set<String> ios = new HashSet<>();
        boolean contactDerivation = false;
        for (AccessGrant grant : active) {
            switch (grant.type()) {
                case CENTRAL -> central = true;
                case UNIT -> units.add(new UnitGrant(grant.unitNumber(), grant.includeDescendants()));
                case IO -> ios.add(grant.ioValue());
                case CONTACT_DERIVATION -> contactDerivation = true;
            }
        }
        // Contact-derived access needs both the grant and a Kuali employee
        // PERSON_ID; a mapping without one can never match a contact row.
        Optional<KualiPersonId> contactPerson =
                contactDerivation ? mapped.kualiPersonId() : Optional.empty();

        var scope = new AccessScope(central, units, contactPerson, ios);
        return scope.isEmpty()
                ? new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS)
                : new AccessOutcome.Scoped(scope);
    }
}
