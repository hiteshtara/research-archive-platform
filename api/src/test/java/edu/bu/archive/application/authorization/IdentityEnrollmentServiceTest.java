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
                "app.authorization.enrollment.crosswalk-attribute-name",
                "app.authorization.enrollment.username-case-sensitive (true|false: the user pool's "
                        + "UsernameConfiguration.CaseSensitive)");
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
        properties.setMaxSignInAge(Duration.ofHours(12));
        properties.getEnrollment().setEnabled(enroll);
        return recordService(properties, enrollment, token("sub-1"));
    }

    private RecordAuthorizationService recordService(AuthorizationProperties properties, IdentityEnrollment enrollment,
                                                     ValidatedCognitoIdentity identity) {
        var links = new InMemoryIdentityLinkRepository();
        return new RecordAuthorizationService(properties, () -> Optional.of(identity),
                new IdentityResolver(links), new AccessScopeResolver(grantee -> List.of(), clock),
                Mockito.mock(RecordFactsRepository.class), new MapUnitHierarchy(Map.of()), unit -> Set.of(unit),
                Mockito.mock(IoSqlStrategy.class), enrollment, clock);
    }

    private AccessOutcome freshOutcome(RecordAuthorizationService service) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        return service.outcome();
    }

    // --- maximum sign-in age: refreshed sessions, separately from offboarding -----------------

    @Test
    void aRefreshedTokenKeepsItsSignInTimeAndIsRefusedOnceTheMaximumAgePasses() {
        AtomicInteger calls = new AtomicInteger();
        IdentityEnrollment counting = identity -> {
            calls.incrementAndGet();
            return EnrollmentOutcome.LINK_STILL_VALID;
        };
        var service = recordService(true, true, counting);
        // Signed in at 11:00; the clock is 12:00. A refresh at any later time carries the same auth_time.
        assertThat(freshOutcome(service)).isNotInstanceOf(AccessOutcome.Denied.class);
        clock.advance(Duration.ofHours(11));            // 23:00: 12h after sign-in, still allowed
        assertThat(freshOutcome(service)).isNotInstanceOf(AccessOutcome.Denied.class);
        clock.advance(Duration.ofSeconds(1));
        int before = calls.get();
        assertThat(freshOutcome(service))
                .isEqualTo(new AccessOutcome.Denied(AccessOutcome.DenialReason.REAUTHENTICATION_REQUIRED));
        // Refused before enrollment or any grant is read.
        assertThat(calls.get()).isEqualTo(before);
        assertThatThrownBy(service::requireProvisioned).isInstanceOf(ReauthenticationRequiredException.class);
        assertThat(service.status().problem()).isEqualTo("REAUTHENTICATION_REQUIRED");
    }

    @Test
    void aMissingOrFutureSignInTimeIsRefused() {
        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(new LinkedHashSet<>(Set.of("PI")));
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL);
        properties.setMaxSignInAge(Duration.ofHours(12));
        var reauth = new AccessOutcome.Denied(AccessOutcome.DenialReason.REAUTHENTICATION_REQUIRED);
        assertThat(freshOutcome(recordService(properties, IdentityEnrollment.DISABLED,
                new ValidatedCognitoIdentity(ISSUER, "sub-1", PROVIDER + "_sub-1")))).isEqualTo(reauth);
        assertThat(freshOutcome(recordService(properties, IdentityEnrollment.DISABLED,
                token("sub-1", clock.instant().plus(Duration.ofMinutes(6)))))).isEqualTo(reauth);
        assertThat(freshOutcome(recordService(properties, IdentityEnrollment.DISABLED,
                token("sub-1", clock.instant().plus(Duration.ofMinutes(4)))))).isNotEqualTo(reauth);
    }

    @Test
    void anEnforcedDeploymentWithoutAMaximumSignInAgeDeniesEveryRequest() {
        for (Duration missing : new Duration[] {null, Duration.ZERO, Duration.ofHours(-1)}) {
            var properties = new AuthorizationProperties();
            properties.setEnforcementEnabled(true);
            properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
            properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
            properties.setResearchStaffRoles(new LinkedHashSet<>(Set.of("PI")));
            properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.VERIFIED_PRINCIPAL);
            properties.setMaxSignInAge(missing);
            assertThat(freshOutcome(recordService(properties, IdentityEnrollment.DISABLED, token("sub-1"))))
                    .isEqualTo(new AccessOutcome.Denied(AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED));
        }
    }

    @Test
    void withEnforcementOffTheSignInAgeIsNotChecked() {
        var properties = new AuthorizationProperties();
        var service = recordService(properties, IdentityEnrollment.DISABLED,
                new ValidatedCognitoIdentity(ISSUER, "sub-1", PROVIDER + "_sub-1"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        service.requireProvisioned();
        assertThat(service.status().mode()).isEqualTo("NOT_ENFORCED");
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

    private CognitoProfile nameIdProfile(String sub, String nameId, String username) {
        var p = new CognitoProfile(sub, username, true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, nameId)), Map.of(ATTRIBUTE, "SYN-V-2"));
        pool.put(PROVIDER + "_" + sub, p);
        return p;
    }

    private IdentityEnrollmentService byNameId() {
        return new IdentityEnrollmentService(new IdentityEnrollmentService.Settings(
                PROVIDER, IdentityEnrollmentService.FEDERATED_USER_ID, CROSSWALK, Duration.ZERO), reader, store, clock);
    }

    @Test
    void theFederatedNameIdCanBeTheIdentifierSource() {
        // Cognito keys a SAML profile as <provider>_<NameID>; the NameID is fresh by construction.
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "s-nid", "SYNP-1", true));
        nameIdProfile("s-nid", "s-nid", PROVIDER + "_s-nid");
        var service = byNameId();
        assertThat(service.prepare(token("s-nid"))).isEqualTo(EnrollmentOutcome.LINKED);
        assertThat(onlyLink().identifier()).isEqualTo("s-nid");
        assertThat(service.prepare(token("s-nid", SIGN_IN.plusSeconds(60)))).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
    }

    @Test
    void inNameIdModeAProfileNotKeyedByThatNameIdIsRefused() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "nid-x", "SYNP-1", true));
        nameIdProfile("s-odd", "nid-x", PROVIDER + "_s-odd");      // identities say nid-x, username says otherwise
        assertThat(byNameId().prepare(token("s-odd"))).isEqualTo(EnrollmentOutcome.REFUSED_NAMEID_NOT_PROFILE_KEY);
        assertThat(store.links).isEmpty();
    }

    // --- Cognito username case: the pool's CaseSensitive setting -----------------------------

    private IdentityEnrollmentService byNameId(boolean usernameCaseSensitive) {
        return new IdentityEnrollmentService(new IdentityEnrollmentService.Settings(
                PROVIDER, IdentityEnrollmentService.FEDERATED_USER_ID, CROSSWALK, Duration.ZERO, null,
                usernameCaseSensitive), reader, store, clock);
    }

    /** A federated profile as a pool stores it: username generated from the NameID, in the pool's case. */
    private ValidatedCognitoIdentity federatedProfile(String sub, String nameId, boolean poolCaseSensitive) {
        String generated = PROVIDER + "_" + nameId;
        String username = poolCaseSensitive ? generated : generated.toLowerCase(java.util.Locale.ROOT);
        pool.put(username, new CognitoProfile(sub, username, true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, nameId)), Map.of()));
        return new ValidatedCognitoIdentity(ISSUER, sub, username, SIGN_IN);
    }

    @Test
    void aCaseInsensitivePoolLowercasesTheGeneratedUsernameAndIsStillEnrolledAndReverified() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "AbC-Nid-7", "SYNP-1", true));
        var token = federatedProfile("s-ci", "AbC-Nid-7", false);       // username syntheticsaml_abc-nid-7
        // The defect: comparing exactly refuses every mixed-case NameID in a case-insensitive pool.
        assertThat(byNameId(true).prepare(token)).isEqualTo(EnrollmentOutcome.REFUSED_NAMEID_NOT_PROFILE_KEY);
        assertThat(store.links).isEmpty();
        // Configured as the pool really is: linked, with the NameID recorded exactly as BU sent it.
        var service = byNameId(false);
        assertThat(service.prepare(token)).isEqualTo(EnrollmentOutcome.LINKED);
        assertThat(onlyLink().identifier()).isEqualTo("AbC-Nid-7");
        // Session verification (a new sign-in) takes the same path and keeps the link.
        var nextSession = new ValidatedCognitoIdentity(ISSUER, "s-ci", token.username(), SIGN_IN.plusSeconds(3600));
        assertThat(service.prepare(nextSession)).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        assertThat(onlyLink().active()).isTrue();
    }

    @Test
    void aCaseSensitivePoolKeepsTheGeneratedUsernameAndComparesItExactly() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "AbC-Nid-8", "SYNP-1", true));
        var token = federatedProfile("s-cs", "AbC-Nid-8", true);        // username SyntheticSaml_AbC-Nid-8
        var service = byNameId(true);
        assertThat(service.prepare(token)).isEqualTo(EnrollmentOutcome.LINKED);
        var nextSession = new ValidatedCognitoIdentity(ISSUER, "s-cs", token.username(), SIGN_IN.plusSeconds(3600));
        assertThat(service.prepare(nextSession)).isEqualTo(EnrollmentOutcome.LINK_STILL_VALID);
        // In a case-sensitive pool a lowercased username is a different profile key: refused.
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "XyZ-Nid-9", "SYNP-2", true));
        pool.put("syntheticsaml_xyz-nid-9", new CognitoProfile("s-cs2", "syntheticsaml_xyz-nid-9", true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "XyZ-Nid-9")), Map.of()));
        assertThat(service.prepare(new ValidatedCognitoIdentity(ISSUER, "s-cs2", "syntheticsaml_xyz-nid-9", SIGN_IN)))
                .isEqualTo(EnrollmentOutcome.REFUSED_NAMEID_NOT_PROFILE_KEY);
    }

    @Test
    void ignoringCaseNeverAcceptsADifferentNameIdForTheProfile() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "nid-real", "SYNP-1", true));
        pool.put("syntheticsaml_nid-other", new CognitoProfile("s-x", "syntheticsaml_nid-other", true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "nid-real")), Map.of()));
        assertThat(byNameId(false).prepare(new ValidatedCognitoIdentity(ISSUER, "s-x", "syntheticsaml_nid-other", SIGN_IN)))
                .isEqualTo(EnrollmentOutcome.REFUSED_NAMEID_NOT_PROFILE_KEY);
    }

    @Test
    void theInstitutionalIdentifierStaysAnExactMatchInBothPoolModes() {
        // The crosswalk holds "abc-nid-10"; BU sends "AbC-Nid-10". Only the username is case-folded.
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "abc-nid-10", "SYNP-1", true));
        assertThat(byNameId(false).prepare(federatedProfile("s-e1", "AbC-Nid-10", false)))
                .isEqualTo(EnrollmentOutcome.REFUSED_UNKNOWN_PERSON);
        assertThat(byNameId(true).prepare(federatedProfile("s-e2", "AbC-Nid-10", true)))
                .isEqualTo(EnrollmentOutcome.REFUSED_UNKNOWN_PERSON);
        assertThat(store.links).isEmpty();
    }

    @Test
    void aNameIdThatChangesOnlyInCaseRevokesTheLinkEvenInACaseInsensitivePool() {
        store.crosswalk.add(new InMemoryEnrollmentStore.Crosswalk(CROSSWALK, "AbC-Nid-11", "SYNP-1", true));
        var token = federatedProfile("s-cc", "AbC-Nid-11", false);
        var service = byNameId(false);
        assertThat(service.prepare(token)).isEqualTo(EnrollmentOutcome.LINKED);
        // Same lowercased username, but the identities now carry "abc-nid-11": a different identifier.
        pool.put(token.username(), new CognitoProfile("s-cc", token.username(), true,
                List.of(new CognitoProfile.FederatedIdentity(PROVIDER, "abc-nid-11")), Map.of()));
        var nextSession = new ValidatedCognitoIdentity(ISSUER, "s-cc", token.username(), SIGN_IN.plusSeconds(3600));
        assertThat(service.prepare(nextSession)).isEqualTo(EnrollmentOutcome.REVOKED_IDENTITY_EVIDENCE_CHANGED);
        assertThat(onlyLink().active()).isFalse();
    }

    @Test
    void thePoolsUsernameCaseSettingIsRequired() {
        var e = new AuthorizationProperties.Enrollment();
        e.setUserPoolId("p"); e.setRegion("r"); e.setSamlProviderName(PROVIDER); e.setCrosswalkAttributeName(CROSSWALK);
        e.setIdentifierAttribute(IdentityEnrollmentService.FEDERATED_USER_ID);
        assertThat(e.missingSettings()).singleElement().asString().contains("username-case-sensitive");
        e.setUsernameCaseSensitive(false);
        assertThat(e.missingSettings()).isEmpty();
    }

    @Test
    void aMappedAttributeIdentifierNeedsExplicitAcceptanceOfTheFreshnessRisk() {
        var e = new AuthorizationProperties.Enrollment();
        e.setUserPoolId("p"); e.setRegion("r"); e.setSamlProviderName(PROVIDER); e.setCrosswalkAttributeName(CROSSWALK);
        e.setIdentifierAttribute("custom:bu_identifier");
        e.setUsernameCaseSensitive(true);
        assertThat(e.missingSettings()).anyMatch(m -> m.contains("accept-mapped-attribute-identifier"));
        e.setAcceptMappedAttributeIdentifier(true);
        assertThat(e.missingSettings()).isEmpty();
        e.setAcceptMappedAttributeIdentifier(false);
        e.setIdentifierAttribute(IdentityEnrollmentService.FEDERATED_USER_ID);
        assertThat(e.missingSettings()).isEmpty();
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
                PROVIDER, ATTRIBUTE, CROSSWALK, Duration.ZERO, "synthetic-pool", true), reader, store, clock);
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
