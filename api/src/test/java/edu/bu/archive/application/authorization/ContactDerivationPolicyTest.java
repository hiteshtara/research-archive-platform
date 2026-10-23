package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;

/**
 * Design section 13 (decided 2026-10-01): under VERIFIED_PRINCIPAL, a verified mapping to a KIM
 * principal gives Research Staff access from contact rows alone - but only while that principal
 * is a qualifying contact somewhere. A KIM account alone grants nothing.
 */
class ContactDerivationPolicyTest {

    private final SyntheticArchive archive = new SyntheticArchive();

    private AccessScopeResolver resolver(AuthorizationPolicy.ContactDerivation mode, Set<String> contactPeople) {
        var policy = new AuthorizationPolicy(AuthorizationPolicy.VersionScope.PER_VERSION,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, Set.of("PI", "MPI", "COI"), mode);
        ContactRelationships contacts = (person, roles) -> contactPeople.contains(person.value())
                && roles.equals(Set.of("PI", "MPI", "COI"));
        return new AccessScopeResolver(archive.grants, SyntheticArchive.CLOCK, () -> Optional.of(policy), contacts);
    }

    private AccessOutcome outcome(AccessScopeResolver resolver, ValidatedCognitoIdentity identity) {
        return resolver.resolve(archive.identityResolver.resolve(identity));
    }

    @Test
    void aVerifiedPrincipalWhoIsAContactGetsResearchStaffAccessWithoutAnyGrant() {
        // noGrants (SYNP-1006) has no grant rows at all.
        var outcome = outcome(resolver(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL, Set.of("SYNP-1006")),
                archive.noGrants);
        assertThat(outcome).isInstanceOf(AccessOutcome.Scoped.class);
        var scope = ((AccessOutcome.Scoped) outcome).scope();
        assertThat(scope.contactPersonId()).contains(new KualiPersonId("SYNP-1006"));
        assertThat(scope.central()).isFalse();
        assertThat(scope.units()).isEmpty();
        assertThat(scope.ioValues()).isEmpty();
    }

    @Test
    void aKimAccountAloneGrantsNothing() {
        var outcome = outcome(resolver(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL, Set.of()),
                archive.noGrants);
        assertThat(outcome).isInstanceOf(AccessOutcome.NotProvisioned.class);
    }

    @Test
    void underExplicitGrantTheSameContactNeedsTheGrant() {
        var outcome = outcome(resolver(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT, Set.of("SYNP-1006")),
                archive.noGrants);
        assertThat(outcome).isInstanceOf(AccessOutcome.NotProvisioned.class);
    }

    @Test
    void missingAmbiguousRevokedOrSuspendedMappingsStillDeny() {
        var resolver = resolver(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL,
                Set.of("SYNP-1003", "SYNP-1006", "SYNP-1007", "SYNP-1008", "SYNP-1009", "SYNP-1010"));
        assertThat(outcome(resolver, archive.unmapped)).isInstanceOf(AccessOutcome.NotProvisioned.class);
        assertThat(outcome(resolver, archive.ambiguousA)).isInstanceOf(AccessOutcome.Denied.class);
        assertThat(outcome(resolver, archive.revoked)).isInstanceOf(AccessOutcome.Denied.class);
        assertThat(outcome(resolver, archive.suspended)).isInstanceOf(AccessOutcome.Denied.class);
    }

    @Test
    void explicitGrantsAreUnchangedByTheContactRule() {
        var outcome = outcome(resolver(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL, Set.of()),
                archive.central);
        assertThat(((AccessOutcome.Scoped) outcome).scope().central()).isTrue();
    }
}
