package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;

/** Fixture-based. */
class AccessScopeResolverTest {

    private final SyntheticArchive archive = new SyntheticArchive();

    private AccessScope scopeOf(ValidatedCognitoIdentity identity) {
        var outcome = archive.outcomeFor(identity);
        assertThat(outcome).isInstanceOf(AccessOutcome.Scoped.class);
        return ((AccessOutcome.Scoped) outcome).scope();
    }

    @Test
    void grantsUnionIntoOneScope() {
        AccessScope scope = scopeOf(archive.multi);
        assertThat(scope.central()).isFalse();
        assertThat(scope.units()).extracting(UnitGrant::unitNumber).containsExactly("SYN-U-200");
        assertThat(scope.ioValues()).containsExactly("SYN-IO-0005");
        assertThat(scope.contactPersonId()).map(KualiPersonId::value).contains("SYNP-1005");
    }

    @Test
    void aMappedPersonWithNoActiveGrantsIsNotProvisioned() {
        assertThat(archive.outcomeFor(archive.noGrants)).isEqualTo(
                new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS));
        assertThat(archive.outcomeFor(archive.reassignedNewHolder)).isEqualTo(
                new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_ACTIVE_GRANTS));
    }

    @Test
    void unmappedAmbiguousRevokedAndSuspendedNeverGetAScope() {
        assertThat(archive.outcomeFor(archive.unmapped)).isEqualTo(
                new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_IDENTITY_LINK));
        assertThat(archive.outcomeFor(archive.ambiguousA)).isEqualTo(
                new AccessOutcome.Denied(AccessOutcome.DenialReason.AMBIGUOUS_IDENTITY));
        assertThat(archive.outcomeFor(archive.revoked)).isEqualTo(
                new AccessOutcome.Denied(AccessOutcome.DenialReason.REVOKED_IDENTITY));
        assertThat(archive.outcomeFor(archive.suspended)).isEqualTo(
                new AccessOutcome.Denied(AccessOutcome.DenialReason.SUSPENDED_IDENTITY));
    }

    @Test
    void revokingOneGrantRemovesOnlyWhatItSupplied() {
        var ioGrant = archive.grants.findForGrantee(new InstitutionalIdentifier("SYN-INST-1005")).stream()
                .filter(g -> g.type() == GrantType.IO).findFirst().orElseThrow();
        archive.grants.revoke(ioGrant.id(), SyntheticArchive.NOW.minusSeconds(1));

        AccessScope scope = scopeOf(archive.multi);
        assertThat(scope.ioValues()).isEmpty();
        assertThat(scope.units()).extracting(UnitGrant::unitNumber).containsExactly("SYN-U-200");
    }

    @Test
    void expiredAndNotYetValidGrantsAreIgnored() {
        var expired = new AccessGrant(900, new InstitutionalIdentifier("SYN-INST-1006"), GrantType.CENTRAL,
                null, false, null, null, SyntheticArchive.NOW.minusSeconds(1), null);
        var future = new AccessGrant(901, new InstitutionalIdentifier("SYN-INST-1006"), GrantType.CENTRAL,
                null, false, null, SyntheticArchive.NOW.plusSeconds(60), null, null);
        archive.grants.add(expired);
        archive.grants.add(future);

        assertThat(archive.outcomeFor(archive.noGrants)).isInstanceOf(AccessOutcome.NotProvisioned.class);
    }

    @Test
    void contactAccessNeedsBothTheGrantAndAKualiEmployeeId() {
        // Department persona has a person id but no CONTACT_DERIVATION grant.
        assertThat(scopeOf(archive.department).contactPersonId()).isEmpty();
        assertThat(scopeOf(archive.researchStaff).contactPersonId()).isPresent();
    }

    @Test
    void grantsAreKeyedOnTheInstitutionalIdentityNotTheLoginName() {
        // The old holder of login "syn-shared" had CENTRAL; it must not
        // reach the new holder even though the login name is identical.
        assertThat(archive.outcomeFor(archive.reassignedNewHolder))
                .isNotInstanceOf(AccessOutcome.Scoped.class);
        assertThat(Instant.EPOCH).isNotNull();
    }
}
