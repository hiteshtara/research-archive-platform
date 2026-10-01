package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import edu.bu.archive.application.authorization.fixtures.InMemoryEnrollmentStore;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;

/**
 * Enrollment (design 12.2 Option A, 13) against a fake user pool and an
 * in-memory store. Every outcome; fail closed; audited. SYNTHETIC values only.
 */
class IdentityEnrollmentServiceTest {

    static final String ISSUER = "https://idp.invalid/synthetic-pool";
    static final String PROVIDER = "SyntheticSaml";
    static final String ATTRIBUTE = "custom:synthetic_principal_attr";
    static final String CROSSWALK = "syntheticAttr";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
    private final InMemoryEnrollmentStore store = new InMemoryEnrollmentStore();
    private final Map<String, CognitoProfile> pool = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private RuntimeException poolFailure;

    private final CognitoProfileReader reader = username -> {
        reads.incrementAndGet();
        if (poolFailure != null) {
            throw poolFailure;
        }
        return Optional.ofNullable(pool.get(username));
    };

    private IdentityEnrollmentService service(Duration retry) {
        return new IdentityEnrollmentService(
                new IdentityEnrollmentService.Settings(PROVIDER, ATTRIBUTE, CROSSWALK, retry), reader, store, clock);
    }

    private final IdentityEnrollmentService service = service(Duration.ZERO);

    IdentityEnrollmentServiceTest() {
        store.principals.put("SYNP-1", true);
        store.principals.put("SYNP-2", true);
        store.principals.put("SYNP-OFF", false);
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-1", "SYNP-1", true));
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-2", "SYNP-2", true));
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-OFF", "SYNP-OFF", true));
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-OLD", "SYNP-2", false));
    }

    static final Instant SIGN_IN = Instant.parse("2026-10-01T11:00:00Z");

    private static ValidatedCognitoIdentity token(String sub) {
        return token(sub, SIGN_IN);
    }

    private static ValidatedCognitoIdentity token(String sub, Instant signIn) {
        return new ValidatedCognitoIdentity(ISSUER, sub, PROVIDER + "_" + sub, signIn);
    }

    private CognitoProfile profile(String sub, String provider, String value) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", sub + "@example.invalid");   // present, and must never be used
        if (value != null) {
            attributes.put(ATTRIBUTE, value);
        }
        var p = new CognitoProfile(sub, PROVIDER + "_" + sub, true,
                provider == null ? List.of() : List.of(new CognitoProfile.FederatedIdentity(provider, "nameid-" + sub)),
                attributes);
        pool.put(PROVIDER + "_" + sub, p);
        return p;
    }

    private InMemoryEnrollmentStore.Link onlyLink() {
        assertThat(store.links).hasSize(1);
        return store.links.get(0);
    }

    @Test
    void aFederatedProfileWithOneActiveCrosswalkRowToAnActivePrincipalIsLinked() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.LINKED);
        var link = onlyLink();
        assertThat(link.identifier()).isEqualTo("SYN-V-1");
        assertThat(link.principal()).isEqualTo("SYNP-1");
        assertThat(link.auto()).isTrue();
        assertThat(store.audits).singleElement().satisfies(a -> {
            assertThat(a.actor()).isEqualTo("api-enrollment");
            assertThat(a.action()).isEqualTo("ENROLLMENT_LINKED");
            assertThat(a.institutionalIdentifier()).isEqualTo("SYN-V-1");
            assertThat(a.detail()).containsEntry("kuali_person_id", "SYNP-1")
                    .containsEntry("cognito_subject", "sub-1").doesNotContainKey("email");
        });
        // The next request re-checks the link (cheap) and does not read the pool again.
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void aConcurrentInsertForTheSameProfileIsAlreadyLinked() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        store.raceOnInsert = true;
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.ALREADY_LINKED);
        assertThat(store.outcomes()).containsExactly("ALREADY_LINKED");
        assertThat(store.audits.get(0).action()).isEqualTo("ENROLLMENT_ALREADY_LINKED");
    }

    @Test
    void everyRefusalLinksNothingAndIsAudited() {
        Map<String, EnrollmentOutcome> cases = new java.util.LinkedHashMap<>();
        profile("unknown", PROVIDER, "SYN-V-NOBODY");
        cases.put("unknown", EnrollmentOutcome.REFUSED_UNKNOWN_PERSON);
        profile("inactive", PROVIDER, "SYN-V-OFF");
        cases.put("inactive", EnrollmentOutcome.REFUSED_INACTIVE_PRINCIPAL);
        profile("revoked-row", PROVIDER, "SYN-V-OLD");
        cases.put("revoked-row", EnrollmentOutcome.REFUSED_UNKNOWN_PERSON);
        profile("native", null, "SYN-V-1");
        cases.put("native", EnrollmentOutcome.REFUSED_NOT_FEDERATED);
        profile("other-idp", "SomeOtherIdp", "SYN-V-1");
        cases.put("other-idp", EnrollmentOutcome.REFUSED_NOT_FEDERATED);
        profile("no-attr", PROVIDER, null);
        cases.put("no-attr", EnrollmentOutcome.REFUSED_MISSING_IDENTIFIER);
        profile("blank-attr", PROVIDER, "   ");
        cases.put("blank-attr", EnrollmentOutcome.REFUSED_MISSING_IDENTIFIER);
        cases.put("not-in-pool", EnrollmentOutcome.REFUSED_PROFILE_NOT_FOUND);

        for (var c : cases.entrySet()) {
            assertThat(service.prepare(token(c.getKey()))).as(c.getKey()).isEqualTo(c.getValue());
        }
        assertThat(store.links).isEmpty();
        assertThat(store.audits).hasSize(cases.size())
                .allSatisfy(a -> assertThat(a.action()).isEqualTo("ENROLLMENT_REFUSED"));
        assertThat(store.outcomes()).containsExactlyElementsOf(cases.values().stream().map(Enum::name).toList());
        // An unknown value is in no authz table, so it is never recorded.
        assertThat(store.audits.get(0).institutionalIdentifier()).isNull();
        assertThat(store.audits.toString()).doesNotContain("SYN-V-NOBODY").doesNotContain("example.invalid");
    }

    @Test
    void theProfileSubMustEqualTheTokenSub() {
        // The token names a username whose profile belongs to a different sub.
        profile("someone-else", PROVIDER, "SYN-V-1");
        var forged = new ValidatedCognitoIdentity(ISSUER, "sub-attacker", PROVIDER + "_someone-else");
        assertThat(service.prepare(forged)).isEqualTo(EnrollmentOutcome.REFUSED_SUBJECT_MISMATCH);
        assertThat(store.links).isEmpty();
    }

    @Test
    void aTokenWithoutAUsernameIsRefusedWithoutReadingThePool() {
        assertThat(service.prepare(new ValidatedCognitoIdentity(ISSUER, "sub-1")))
                .isEqualTo(EnrollmentOutcome.REFUSED_NO_USERNAME);
        assertThat(reads.get()).isZero();
    }

    @Test
    void aDisabledProfileIsRefused() {
        var enabled = profile("sub-1", PROVIDER, "SYN-V-1");
        pool.put(enabled.username(), new CognitoProfile(enabled.sub(), enabled.username(), false,
                enabled.identities(), enabled.attributes()));
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_PROFILE_DISABLED);
    }

    @Test
    void anAmbiguousCrosswalkIsNeverAutoResolved() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-1", "SYNP-2", true));
        profile("sub-1", PROVIDER, "SYN-V-1");
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_AMBIGUOUS_MAPPING);
        assertThat(store.audits.get(0).detail()).containsEntry("candidates", 2);
        assertThat(store.links).isEmpty();
    }

    @Test
    void aCrosswalkRowToSomethingThatIsNotAKimPrincipalIsRefused() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-ROLODEX", "990001", true));
        profile("sub-1", PROVIDER, "SYN-V-ROLODEX");
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_NOT_A_KIM_PRINCIPAL);
    }

    @Test
    void aPreviouslyRevokedProfileIsNeverRelinkedAutomatically() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        store.addLink(token("sub-1"), "SYN-V-1", "SYNP-1", true, false);
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_PREVIOUSLY_REVOKED);
        assertThat(reads.get()).isZero();
        assertThat(store.links).hasSize(1).allMatch(l -> !l.active());
    }

    @Test
    void anIdentifierAlreadyLinkedToAnotherProfileIsRefused() {
        profile("sub-a", PROVIDER, "SYN-V-1");
        profile("sub-b", PROVIDER, "SYN-V-1");
        assertThat(service.prepare(token("sub-a"))).isEqualTo(EnrollmentOutcome.LINKED);
        assertThat(service.prepare(token("sub-b")))
                .isEqualTo(EnrollmentOutcome.REFUSED_IDENTIFIER_LINKED_TO_ANOTHER_PROFILE);
        assertThat(store.links).hasSize(1);
    }

    @Test
    void aPoolFailureFailsClosedAndIsAudited() {
        poolFailure = new IllegalStateException("pool unreachable");
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.FAILED_PROFILE_READ);
        assertThat(store.audits).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo("ENROLLMENT_FAILED");
            assertThat(a.detail()).containsEntry("error", "IllegalStateException");
        });
        assertThat(store.links).isEmpty();
    }

    @Test
    void anUnexpectedStoreFailureFailsClosed() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        var failing = new IdentityEnrollmentService(
                new IdentityEnrollmentService.Settings(PROVIDER, ATTRIBUTE, CROSSWALK, Duration.ZERO), reader,
                new InMemoryEnrollmentStore() {
                    @Override
                    public List<String> activeCrosswalkPrincipals(String attributeName, String value) {
                        throw new IllegalStateException("database unavailable");
                    }
                }, clock);
        assertThat(failing.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.FAILED);
    }

    @Test
    void aRevokedCrosswalkRowRevokesTheLinkOnTheNextRequest() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.LINKED);
        store.crosswalk.replaceAll(x -> x.value().equals("SYN-V-1")
                ? new InMemoryEnrollmentStore.Crosswalk(x.attribute(), x.value(), x.principal(), false) : x);

        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REVOKED_MAPPING_NO_LONGER_VALID);
        assertThat(onlyLink().active()).isFalse();
        assertThat(store.audits.get(store.audits.size() - 1)).satisfies(a -> {
            assertThat(a.action()).isEqualTo("ENROLLMENT_LINK_REVOKED");
            assertThat(a.detail()).containsEntry("outcome", "REVOKED_MAPPING_NO_LONGER_VALID");
        });
        // And it stays revoked: a later request is refused, never re-linked.
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_PREVIOUSLY_REVOKED);
    }

    @Test
    void anInactivatedPrincipalRevokesTheLinkOnTheNextRequest() {
        profile("sub-1", PROVIDER, "SYN-V-1");
        service.prepare(token("sub-1"));
        store.principals.put("SYNP-1", false);
        assertThat(service.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REVOKED_MAPPING_NO_LONGER_VALID);
        assertThat(onlyLink().active()).isFalse();
    }

    @Test
    void adminVerifiedLinksAreNotRecheckedAgainstTheCrosswalkButMustMatchTheSignInEvidence() {
        store.addLink(token("admin"), "SYN-V-ADMIN", "SYNP-NOT-IN-CROSSWALK", false, true);
        profile("admin", PROVIDER, "SYN-V-ADMIN");
        assertThat(service.prepare(token("admin"))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(store.links.get(0).active()).isTrue();
        assertThat(store.audits).isEmpty();
        // a later sign-in whose profile carries another identifier revokes even an admin-verified link
        profile("admin", PROVIDER, "SYN-V-1");
        assertThat(service.prepare(token("admin", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
        assertThat(store.links.get(0).active()).isFalse();
    }

    @Test
    void aRefusalIsRememberedForTheRetryWindowThenReRead() {
        var cached = service(Duration.ofSeconds(60));
        profile("sub-1", PROVIDER, "SYN-V-NOBODY");
        assertThat(cached.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.REFUSED_UNKNOWN_PERSON);
        assertThat(cached.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.RETRY_DEFERRED);
        assertThat(reads.get()).isEqualTo(1);
        assertThat(store.audits).hasSize(1);

        // The administrator imports the crosswalk row; after the window the user is linked.
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "SYN-V-NOBODY", "SYNP-NEW", true));
        store.principals.put("SYNP-NEW", true);
        clock.advance(Duration.ofSeconds(61));
        assertThat(cached.prepare(token("sub-1"))).isEqualTo(EnrollmentOutcome.LINKED);
    }

    @Test
    void settingsHaveNoDefaults() {
        assertThatThrownBy(() -> new IdentityEnrollmentService.Settings(null, ATTRIBUTE, CROSSWALK, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityEnrollmentService.Settings(PROVIDER, " ", CROSSWALK, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityEnrollmentService.Settings(PROVIDER, ATTRIBUTE, "", null))
                .isInstanceOf(IllegalArgumentException.class);
        var properties = new AuthorizationProperties();
        assertThat(properties.getEnrollment().isEnabled()).isFalse();
        assertThat(properties.getEnrollment().missingSettings()).containsExactly(
                "app.authorization.enrollment.user-pool-id", "app.authorization.enrollment.region",
                "app.authorization.enrollment.saml-provider-name",
                "app.authorization.enrollment.identifier-attribute",
                "app.authorization.enrollment.crosswalk-attribute-name");
    }

    @Test
    void identityIsIssuerAndSubjectOnlyAndTheUsernameIsNeverPrinted() {
        var a = new ValidatedCognitoIdentity(ISSUER, "sub-1", "SyntheticSaml_nameid");
        var b = new ValidatedCognitoIdentity(ISSUER, "sub-1");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).doesNotContain("nameid");
        assertThat(b.tokenUsername()).isEmpty();
    }

    // --- wiring into RecordAuthorizationService -------------------------------------------

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RecordAuthorizationService recordService(boolean enforce, boolean enroll, IdentityEnrollment enrollment) {
        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(enforce);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.PER_VERSION);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(new LinkedHashSet<>(Set.of("PI")));
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL);
        properties.getEnrollment().setEnabled(enroll);
        var links = new InMemoryIdentityLinkRepository();
        return new RecordAuthorizationService(properties, () -> Optional.of(token("sub-1")),
                new IdentityResolver(links), new AccessScopeResolver(grantee -> List.of(), clock),
                Mockito.mock(RecordFactsRepository.class), new MapUnitHierarchy(Map.of()), unit -> Set.of(unit),
                Mockito.mock(IoSqlStrategy.class), enrollment);
    }

    @Test
    void enrollmentRunsOncePerEnforcedRequestAndNeverWhenEnforcementOrEnrollmentIsOff() {
        AtomicInteger calls = new AtomicInteger();
        IdentityEnrollment counting = identity -> {
            calls.incrementAndGet();
            return EnrollmentOutcome.REFUSED_UNKNOWN_PERSON;
        };

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var enforced = recordService(true, true, counting);
        enforced.outcome();
        enforced.outcome();
        enforced.status();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(enforced.outcome()).isInstanceOf(AccessOutcome.NotProvisioned.class);

        for (boolean[] flags : new boolean[][] {{false, true}, {true, false}, {false, false}}) {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            var service = recordService(flags[0], flags[1], counting);
            service.outcome();
            service.status();
            service.unrestricted();
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void anEnrollmentExceptionDeniesTheRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var service = recordService(true, true, identity -> {
            throw new IllegalStateException("store down");
        });
        assertThat(service.outcome()).isEqualTo(new AccessOutcome.Denied(AccessOutcome.DenialReason.EVALUATION_FAILED));
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // --- per-session re-verification of an existing link (reassignment, stale attributes) -----

    private void linked(String sub, String value) {
        profile(sub, PROVIDER, value);
        assertThat(service.prepare(token(sub))).isEqualTo(EnrollmentOutcome.LINKED);
    }

    @Test
    void theSameSignInSessionIsVerifiedOnceThenCached() {
        linked("s-cache", "SYN-V-1");
        reads.set(0);
        assertThat(service.prepare(token("s-cache"))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(service.prepare(token("s-cache"))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(reads.get()).isZero();   // verified when it was linked in this session
        // a NEW sign-in session (new auth_time) is re-verified once
        assertThat(service.prepare(token("s-cache", SIGN_IN.plusSeconds(3600))))
                .isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(service.prepare(token("s-cache", SIGN_IN.plusSeconds(3600))))
                .isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void aReassignedNameIdNowCarryingAnotherIdentifierRevokesTheLink() {
        // Same Cognito username and sub (same NameID), but the IdP now sends person 2's identifier.
        linked("s-reassigned", "SYN-V-1");
        profile("s-reassigned", PROVIDER, "SYN-V-2");
        assertThat(service.prepare(token("s-reassigned", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
        assertThat(onlyLink().active()).isFalse();
        // and the revoked profile is never re-linked automatically
        assertThat(service.prepare(token("s-reassigned", SIGN_IN.plusSeconds(120))))
                .isEqualTo(EnrollmentOutcome.REFUSED_PREVIOUSLY_REVOKED);
    }

    @Test
    void aStaleSessionKeepsItsVerificationButTheNextSignInSeesTheChangedAttribute() {
        linked("s-stale", "SYN-V-1");
        assertThat(service.prepare(token("s-stale"))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        profile("s-stale", PROVIDER, null);   // the IdP stopped sending the identifier
        assertThat(service.prepare(token("s-stale", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
    }

    @Test
    void aProfileThatIsNoLongerFederatedOrIsDisabledOrHasAnotherSubRevokesTheLink() {
        linked("s-native", "SYN-V-1");
        profile("s-native", null, "SYN-V-1");   // native account: no federated identity
        assertThat(service.prepare(token("s-native", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);

        store.links.clear();
        linked("s-sub", "SYN-V-1");
        pool.put(PROVIDER + "_s-sub", new CognitoProfile("another-sub", PROVIDER + "_s-sub", true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "n")), Map.of(ATTRIBUTE, "SYN-V-1")));
        assertThat(service.prepare(token("s-sub", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);

        store.links.clear();
        linked("s-disabled", "SYN-V-1");
        pool.put(PROVIDER + "_s-disabled", new CognitoProfile("s-disabled", PROVIDER + "_s-disabled", false,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "n")), Map.of(ATTRIBUTE, "SYN-V-1")));
        assertThat(service.prepare(token("s-disabled", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
    }

    @Test
    void aFailedProfileReadOrMissingSignInTimeDeniesTheRequestButKeepsTheLink() {
        linked("s-fail", "SYN-V-1");
        poolFailure = new IllegalStateException("pool unavailable");
        var outcome = service.prepare(token("s-fail", SIGN_IN.plusSeconds(60)));
        assertThat(outcome).isEqualTo(EnrollmentOutcome.FAILED_SESSION_VERIFICATION);
        assertThat(outcome.deniesRequest()).isTrue();
        assertThat(onlyLink().active()).isTrue();
        poolFailure = null;
        var noAuthTime = new ValidatedCognitoIdentity(ISSUER, "s-fail", PROVIDER + "_s-fail");
        assertThat(service.prepare(noAuthTime).deniesRequest()).isTrue();
    }

    @Test
    void aValidTokenAloneNeverEstablishesALink() {
        // Valid token, but the pool has no matching federated profile with a crosswalked identifier.
        assertThat(service.prepare(token("s-nothing"))).isEqualTo(EnrollmentOutcome.REFUSED_PROFILE_NOT_FOUND);
        profile("s-nofed", null, "SYN-V-1");
        assertThat(service.prepare(token("s-nofed"))).isEqualTo(EnrollmentOutcome.REFUSED_NOT_FEDERATED);
        assertThat(store.links).isEmpty();
    }

    // --- trust boundary: self-editable tokens, and the NameID as identifier source -------------

    @Test
    void aTokenThatCouldEditItsOwnAttributesIsNeverTrustedForEnrollment() {
        profile("s-admin-scope", PROVIDER, "SYN-V-1");
        var selfEditable = new ValidatedCognitoIdentity(ISSUER, "s-admin-scope", PROVIDER + "_s-admin-scope", SIGN_IN,
                java.util.Set.of("openid", ValidatedCognitoIdentity.SELF_SERVICE_SCOPE));
        assertThat(service.prepare(selfEditable)).isEqualTo(EnrollmentOutcome.REFUSED_SELF_EDITABLE_TOKEN);
        assertThat(store.links).isEmpty();
        // and an existing link is not honoured for such a token either
        linked("s-linked", "SYN-V-2");
        var later = new ValidatedCognitoIdentity(ISSUER, "s-linked", PROVIDER + "_s-linked", SIGN_IN,
                java.util.Set.of(ValidatedCognitoIdentity.SELF_SERVICE_SCOPE));
        assertThat(service.prepare(later).deniesRequest()).isTrue();
    }

    @Test
    void theFederatedNameIdCanBeTheIdentifierSource() {
        var byNameId = new IdentityEnrollmentService(new IdentityEnrollmentService.Settings(
                PROVIDER, IdentityEnrollmentService.FEDERATED_USER_ID, CROSSWALK, Duration.ZERO), reader, store, clock);
        // profile(...) records the NameID as "nameid-<sub>"; the attribute is deliberately different.
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "nameid-s-nid", "SYNP-1", true));
        profile("s-nid", PROVIDER, "SYN-V-2");
        assertThat(byNameId.prepare(token("s-nid"))).isEqualTo(EnrollmentOutcome.LINKED);
        assertThat(onlyLink().identifier()).isEqualTo("nameid-s-nid");
    }

    // --- native / linked accounts, foreign issuers, transient failures -------------------------

    @Test
    void aLinkedNativeAccountIsNeverTrustedEvenWithTheProviderInItsIdentities() {
        // AdminLinkProviderForUser makes a native (CONFIRMED) profile list the SAML provider.
        pool.put(PROVIDER + "_s-linkednative", new CognitoProfile("s-linkednative", PROVIDER + "_s-linkednative", true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "n")), Map.of(ATTRIBUTE, "SYN-V-1"), "CONFIRMED"));
        assertThat(service.prepare(token("s-linkednative"))).isEqualTo(EnrollmentOutcome.REFUSED_NOT_FEDERATED_ONLY);
        assertThat(store.links).isEmpty();
    }

    @Test
    void anExistingLinkIsRevokedIfTheProfileStopsBeingFederatedOnly() {
        linked("s-turned-native", "SYN-V-1");
        pool.put(PROVIDER + "_s-turned-native", new CognitoProfile("s-turned-native", PROVIDER + "_s-turned-native", true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "n")), Map.of(ATTRIBUTE, "SYN-V-1"), "CONFIRMED"));
        assertThat(service.prepare(token("s-turned-native", SIGN_IN.plusSeconds(60))))
                .isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
    }

    @Test
    void aTokenFromAnotherPoolIsRefused() {
        var poolBound = new IdentityEnrollmentService(new IdentityEnrollmentService.Settings(
                PROVIDER, ATTRIBUTE, CROSSWALK, Duration.ZERO, "synthetic-pool"), reader, store, clock);
        profile("s-pool", PROVIDER, "SYN-V-1");
        var foreign = new ValidatedCognitoIdentity("https://idp.invalid/other-pool", "s-pool", PROVIDER + "_s-pool", SIGN_IN);
        assertThat(poolBound.prepare(foreign)).isEqualTo(EnrollmentOutcome.REFUSED_FOREIGN_ISSUER);
        assertThat(poolBound.prepare(token("s-pool"))).isEqualTo(EnrollmentOutcome.LINKED);
    }

    @Test
    void transientFailuresAreNotHeldBackByTheRefusalWindow() {
        var cached = service(Duration.ofSeconds(60));
        profile("s-transient", PROVIDER, "SYN-V-1");
        poolFailure = new IllegalStateException("throttled");
        assertThat(cached.prepare(token("s-transient")).failed()).isTrue();
        poolFailure = null;
        assertThat(cached.prepare(token("s-transient"))).isEqualTo(EnrollmentOutcome.LINKED);
    }
}
