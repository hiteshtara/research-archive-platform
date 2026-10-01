package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;

/** Fixture-based. Real BU federation and identity mapping: NOT VERIFIED. */
class IdentityResolverTest {

    private final SyntheticArchive archive = new SyntheticArchive();

    @Test
    void anActiveLinkMapsToTheInstitutionalIdentityAndPerson() {
        var resolution = archive.identityResolver.resolve(archive.researchStaff);
        assertThat(resolution).isInstanceOf(IdentityResolution.Mapped.class);
        var mapped = (IdentityResolution.Mapped) resolution;
        assertThat(mapped.institutionalIdentifier().value()).isEqualTo("SYN-INST-1003");
        assertThat(mapped.kualiPersonId()).map(KualiPersonId::value).contains("SYNP-1003");
    }

    @Test
    void anAuthenticatedIdentityWithNoLinkIsNotProvisioned() {
        assertThat(archive.identityResolver.resolve(archive.unmapped))
                .isInstanceOf(IdentityResolution.NotProvisioned.class);
    }

    @Test
    void onlyRevokedLinksMeanRevoked() {
        assertThat(archive.identityResolver.resolve(archive.revoked))
                .isInstanceOf(IdentityResolution.Revoked.class);
    }

    @Test
    void anInstitutionalIdentityLinkedToTwoPeopleIsAmbiguous() {
        assertThat(archive.identityResolver.resolve(archive.ambiguousA))
                .isInstanceOf(IdentityResolution.Ambiguous.class);
    }

    @Test
    void suspensionOverridesAnActiveLink() {
        assertThat(archive.identityResolver.resolve(archive.suspended))
                .isInstanceOf(IdentityResolution.Suspended.class);
    }

    @Test
    void aReassignedLoginNameNeverInheritsThePreviousHoldersIdentity() {
        // Both people carried login name "syn-shared"; the archive never
        // looks anyone up by login name, so the new holder is a different
        // institutional identity and person, and the old one stays revoked.
        var newHolder = archive.identityResolver.resolve(archive.reassignedNewHolder);
        assertThat(newHolder).isInstanceOf(IdentityResolution.Mapped.class);
        assertThat(((IdentityResolution.Mapped) newHolder).institutionalIdentifier().value())
                .isEqualTo("SYN-INST-1011");
        assertThat(archive.identityResolver.resolve(archive.reassignedOldHolder))
                .isInstanceOf(IdentityResolution.Revoked.class);
    }

    @Test
    void identifiersAreMaskedWhenPrinted() {
        assertThat(new InstitutionalIdentifier("SYN-INST-1003").toString()).doesNotContain("1003");
        assertThat(new KualiPersonId("SYNP-1003").toString()).doesNotContain("1003");
    }

    @Test
    void theRealBuAttributeSourceResolvesNobodyUntilIamConfirmsTheContract() {
        assertThat(new AwaitingIamConfirmationAttributeSource().institutionalIdentifierFor(archive.central))
                .isEmpty();
    }
}
