package edu.bu.archive.application.me;

import edu.bu.archive.adapter.in.web.dto.me.CapabilitiesResponse;
import edu.bu.archive.application.authorization.AuthorizationProperties;
import edu.bu.archive.application.authorization.CurrentIdentityProvider;
import edu.bu.archive.application.authorization.IdentityResolution;
import edu.bu.archive.application.authorization.IdentityResolver;
import edu.bu.archive.application.authorization.InstitutionalIdentifier;
import edu.bu.archive.application.authorization.ValidatedCognitoIdentity;
import edu.bu.archive.application.authorization.admin.AdminRoleMember;
import edu.bu.archive.application.authorization.admin.AdminRoleRepository;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * The contract's own test table, in
 * docs/security/CAPABILITIES_ENDPOINT_CONTRACT.md section 7. The web-layer
 * cases (401, response shape, reachable with enforcement off) are in
 * MeCapabilitiesControllerTest; the resolution cases are here.
 *
 * Every case asserts the WHOLE response, not just the field it is about,
 * because "exactly three booleans, and no accidental true among them" is
 * the property that matters.
 */
class CapabilitiesServiceTest {

    private final CurrentIdentityProvider identities = mock(CurrentIdentityProvider.class);
    private final IdentityResolver resolver = mock(IdentityResolver.class);
    private final AdminRoleRepository adminRoles = mock(AdminRoleRepository.class);
    private final AuthorizationProperties authorization = new AuthorizationProperties();

    private final CapabilitiesService service =
            new CapabilitiesService(identities, resolver, adminRoles, authorization);

    private static final InstitutionalIdentifier MEMBER =
            new InstitutionalIdentifier("admin@bu.edu");

    private ValidatedCognitoIdentity signedIn() {
        ValidatedCognitoIdentity identity = mock(ValidatedCognitoIdentity.class);
        when(identities.current()).thenReturn(Optional.of(identity));
        return identity;
    }

    private void resolvesTo(IdentityResolution resolution) {
        when(resolver.resolve(any())).thenReturn(resolution);
    }

    private IdentityResolution.Mapped mapped(ValidatedCognitoIdentity identity) {
        return new IdentityResolution.Mapped(identity, MEMBER, Optional.empty());
    }

    private void assertAllFalse(CapabilitiesResponse actual) {
        assertThat(actual.administrator()).isFalse();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isFalse();
    }

    // --- Case: authenticated, no institutional identity ---

    @Test
    void authenticatedWithNoInstitutionalIdentityIsAllFalse() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(new IdentityResolution.NotProvisioned(identity));

        assertAllFalse(service.current());
        // Nothing to look up, so the membership store is never consulted.
        verify(adminRoles, never()).findActive(any());
    }

    @Test
    void anAmbiguousIdentityIsAllFalse() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(new IdentityResolution.Ambiguous(identity));

        assertAllFalse(service.current());
        verify(adminRoles, never()).findActive(any());
    }

    @Test
    void aRevokedLinkIsAllFalse() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(new IdentityResolution.Revoked(identity));

        assertAllFalse(service.current());
        verify(adminRoles, never()).findActive(any());
    }

    @Test
    void noCurrentIdentityAtAllIsAllFalse() {
        when(identities.current()).thenReturn(Optional.empty());

        assertAllFalse(service.current());
        verify(adminRoles, never()).findActive(any());
    }

    // --- Case: authenticated, identity but no membership ---

    @Test
    void anIdentityWithNoMembershipIsAllFalse() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.empty());
        authorization.setEnforcementEnabled(true);

        // All false even with enforcement ON: a non-administrator is told
        // nothing about the enforcement state.
        assertAllFalse(service.current());
    }

    // --- Case: SUSPENDED identity holding a membership row ---

    @Test
    void aSuspendedIdentityIsNotAnAdministratorEvenHoldingAMembershipRow() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(new IdentityResolution.Suspended(identity));
        // The row exists and is ACTIVE. It must not matter.
        when(adminRoles.findActive(any())).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(true);

        assertAllFalse(service.current());
        // The decisive assertion: suspension short-circuits BEFORE the
        // membership read, so an ACTIVE row cannot be reached at all.
        verify(adminRoles, never()).findActive(any());
    }

    // --- Case: active administrator, enforcement OFF ---

    @Test
    void anActiveAdministratorWithEnforcementOffCanSeeTheToggleButNotUseIt() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(false);

        CapabilitiesResponse actual = service.current();
        assertThat(actual.administrator()).isTrue();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isFalse();
    }

    // --- Case: active administrator, enforcement ON ---

    @Test
    void enforcementOnAloneDoesNotMakeAdministrationAvailable() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(true);

        CapabilitiesResponse actual = service.current();
        assertThat(actual.administrator()).isTrue();
        assertThat(actual.enforcementEnabled()).isTrue();
        /*
         * This assertion was `isTrue()` and passed only because availability
         * was derived from the enforcement flag. The two are now independent,
         * so enforcement alone leaves the administrative surface closed.
         */
        assertThat(actual.administrationAvailable()).isFalse();
        assertThat(actual.administrationWritesAvailable()).isFalse();
    }

    // --- Case: the decoupling itself ---

    @Test
    void administrationIsAvailableWithRecordEnforcementOff() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(false);
        authorization.setAdministrationEnabled(true);

        CapabilitiesResponse actual = service.current();
        assertThat(actual.administrator()).isTrue();
        assertThat(actual.enforcementEnabled()).isFalse();
        assertThat(actual.administrationAvailable()).isTrue();
        assertThat(actual.administrationWritesAvailable())
                .as("read-only until writes are separately enabled")
                .isFalse();
    }

    @Test
    void writesAreReportedOnlyWhenBothFlagsAreOn() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(false);
        authorization.setAdministrationEnabled(false);
        authorization.setAdministrationWritesEnabled(true);

        // Writes without the surface must not be reported as available.
        assertThat(service.current().administrationWritesAvailable()).isFalse();

        authorization.setAdministrationEnabled(true);
        assertThat(service.current().administrationWritesAvailable()).isTrue();
    }

    // --- Case: membership revoked between calls, no caching ---

    @Test
    void aMembershipRevokedBetweenCallsIsReflectedOnTheSecondCall() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        authorization.setEnforcementEnabled(true);
        when(adminRoles.findActive(MEMBER))
                .thenReturn(Optional.of(mock(AdminRoleMember.class)))
                .thenReturn(Optional.empty());

        assertThat(service.current().administrator()).isTrue();
        // Nothing is memoised, so the revoke takes effect immediately.
        assertAllFalse(service.current());
    }

    // --- Case: identity resolution throws -> fails closed ---

    @Test
    void identityResolutionThrowingFailsClosedRatherThanPropagating() {
        signedIn();
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("resolver down"));
        authorization.setEnforcementEnabled(true);

        // Does not throw: a 500 would make the UI interpret an error, and a
        // uniform denial cannot be misinterpreted.
        assertAllFalse(service.current());
    }

    @Test
    void readingTheCurrentIdentityThrowingFailsClosed() {
        when(identities.current()).thenThrow(new IllegalStateException("no request context"));
        authorization.setEnforcementEnabled(true);

        assertAllFalse(service.current());
    }

    @Test
    void theMembershipReadThrowingFailsClosed() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenThrow(new RuntimeException("database down"));
        authorization.setEnforcementEnabled(true);

        assertAllFalse(service.current());
    }

    // --- The endpoint must never consult the administrator API ---

    @Test
    void membershipIsReadDirectlyFromTheRepositoryNotTheAdministratorApi() {
        ValidatedCognitoIdentity identity = signedIn();
        resolvesTo(mapped(identity));
        when(adminRoles.findActive(MEMBER)).thenReturn(Optional.of(mock(AdminRoleMember.class)));
        authorization.setEnforcementEnabled(false);

        service.current();

        // findActive is a plain repository read with no enforcement gate,
        // which is why this works with enforcement OFF - the state in which
        // the whole administrator API tree returns 404.
        verify(adminRoles).findActive(MEMBER);
    }
}
