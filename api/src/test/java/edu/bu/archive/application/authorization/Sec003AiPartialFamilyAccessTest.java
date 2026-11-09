package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.InMemoryIdentityLinkRepository;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;

/**
 * SEC-003's consequence for the AI and family-spanning endpoints, driven
 * through a real {@link RecordAuthorizationService} with enforcement ON.
 *
 * <p>Why this test exists. Version-specific contact access means a research
 * staff member can now see SOME versions of an Award family and not others.
 * Award Summary, Award Questions and the history endpoints build their
 * response from the whole family, so a partial family must not be summarised
 * and presented as the whole Award - it has to be refused with
 * {@code AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS}.
 *
 * <p>That gate already existed and is deliberately unchanged. What changed is
 * how often it fires: before SEC-003 a contact-derived caller always saw the
 * complete family, so it never fired for them. These tests pin both sides -
 * it fires on a partial family, and it stays silent on a complete one - so
 * the restriction cannot be weakened later to make a partial family work.
 *
 * <p>Enforcement is ON here, which no other test in this package does. It is
 * the only way to reach the gate: with the flag off every method short
 * circuits to today's permissive behaviour.
 */
class Sec003AiPartialFamilyAccessTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final String ISSUER = "https://idp.test.invalid/synthetic";
    private static final Set<String> ROLES = Set.of("PI", "MPI", "COI");

    /*
     * Version keys are the award_id as text, because that is what the
     * production facts query renders (award_id::text) and what
     * requireAward compares against String.valueOf(awardId). A readable
     * key like "SYN-AWD-P-1" would make every lookup miss and every
     * assertion below pass or fail for the wrong reason.
     */

    /** Family P: the contact is listed on v2 (id 2) and NOT on v1 (id 1). */
    private static final RecordFacts PARTIAL_V1 = award("1", "SYN-AWD-P",
            List.of(RecordContact.nonEmployee("COI")));
    private static final RecordFacts PARTIAL_V2 = award("2", "SYN-AWD-P",
            List.of(RecordContact.employee("SYNP-3001", "PI")));

    /** Family C: the contact is listed on every version (ids 3 and 4). */
    private static final RecordFacts COMPLETE_V1 = award("3", "SYN-AWD-C",
            List.of(RecordContact.employee("SYNP-3001", "PI")));
    private static final RecordFacts COMPLETE_V2 = award("4", "SYN-AWD-C",
            List.of(RecordContact.employee("SYNP-3001", "PI")));

    private static RecordFacts award(String versionKey, String family, List<RecordContact> contacts) {
        // Unit SYN-U-900 is nobody's granted department, so contact
        // derivation is the only rule that can reach these records.
        return new RecordFacts(RecordModule.AWARD, versionKey, family, "SYN-U-900", contacts, Set.of());
    }

    private final InMemoryIdentityLinkRepository links = new InMemoryIdentityLinkRepository();
    private final InMemoryAccessGrantRepository grants = new InMemoryAccessGrantRepository();
    private RecordAuthorizationService service;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        var identity = new ValidatedCognitoIdentity(ISSUER, "syn-sub-3001", "syn-staff", NOW);
        links.link(identity, "SYN-INST-3001", "SYNP-3001", "syn-staff", IdentityLinkStatus.ACTIVE);
        grants.grant("SYN-INST-3001", GrantType.CONTACT_DERIVATION, null, false, null);

        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.FAMILY_WIDE);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(ROLES);
        properties.setContactDerivation(AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
        properties.setMaxSignInAge(Duration.ofHours(8));

        var unitHierarchy = new MapUnitHierarchy(Map.of("SYN-U-900", "SYN-U-001"));

        service = new RecordAuthorizationService(
                properties,
                () -> Optional.of(identity),
                new IdentityResolver(links),
                new AccessScopeResolver(grants, Clock.fixed(NOW, ZoneOffset.UTC)),
                new FixedFacts(),
                unitHierarchy,
                unit -> Set.of(unit),
                IoSqlStrategy.NONE,
                IdentityEnrollment.DISABLED,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void enforcementIsActuallyOnOrNothingBelowProvesAnything() {
        // The trap this guards: with the flag off every assertion below
        // passes vacuously, because each method returns early.
        assertThat(service.enforced()).isTrue();
        assertThat(service.outcome()).isInstanceOf(AccessOutcome.Scoped.class);
    }

    @Test
    void theContactSeesTheVersionTheyAreListedOnAndNotTheOther() {
        assertThat(service.canSeeAward(2L)).isTrue();    // listed on this one
        assertThat(service.canSeeAward(1L)).isFalse();   // not on this one
    }

    @Test
    void aPartiallyVisibleFamilyIsRefusedForTheFamilySpanningEndpoints() {
        assertThat(service.canSeeEveryAwardVersion("SYN-AWD-P")).isFalse();
        assertThatThrownBy(() -> service.requireEveryAwardVersion("SYN-AWD-P"))
                .isInstanceOf(PartialFamilyAccessException.class)
                .hasMessage(PartialFamilyAccessException.CODE);
    }

    @Test
    void aCompletelyVisibleFamilyIsStillAllowed() {
        // The other half of the rule: SEC-003 must not refuse a research
        // staff member who really is listed on every version.
        assertThat(service.canSeeEveryAwardVersion("SYN-AWD-C")).isTrue();
        assertThatCode(() -> service.requireEveryAwardVersion("SYN-AWD-C"))
                .doesNotThrowAnyException();
    }

    @Test
    void theRefusalIsNotJustADeniedRecord() {
        /*
         * The two failures are different and must stay different: the
         * current version IS readable, so this is a 403 about completeness,
         * not a 404 about the record. requireEveryAwardVersion checks the
         * record first, so a plain denial would mask the distinction.
         */
        assertThatCode(() -> service.requireAwardNumber("SYN-AWD-P")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireEveryAwardVersion("SYN-AWD-P"))
                .isInstanceOf(PartialFamilyAccessException.class);
    }

    /**
     * Facts for the two families above. Award ids are 1/2 for family P and
     * 3/4 for family C; the current version is the LAST of each, which is
     * what {@code currentAwardVersionKey} must return - so for family P the
     * current version is the one the contact IS listed on, which is why the
     * record read succeeds while the family-spanning read does not.
     */
    private static final class FixedFacts implements RecordFactsRepository {

        private static final Map<String, List<RecordFacts>> FAMILIES = Map.of(
                "SYN-AWD-P", List.of(PARTIAL_V1, PARTIAL_V2),
                "SYN-AWD-C", List.of(COMPLETE_V1, COMPLETE_V2));

        private static final Map<Long, String> FAMILY_OF_ID = Map.of(
                1L, "SYN-AWD-P", 2L, "SYN-AWD-P", 3L, "SYN-AWD-C", 4L, "SYN-AWD-C");

        @Override
        public Optional<String> awardNumberForId(long awardId) {
            return Optional.ofNullable(FAMILY_OF_ID.get(awardId));
        }

        @Override
        public List<RecordFacts> awardFamily(String awardNumber) {
            return FAMILIES.getOrDefault(awardNumber, List.of());
        }

        @Override
        public Optional<String> currentAwardVersionKey(String awardNumber) {
            List<RecordFacts> family = awardFamily(awardNumber);
            return family.isEmpty() ? Optional.empty()
                    : Optional.of(family.get(family.size() - 1).versionKey());
        }

        @Override
        public Optional<String> proposalNumberForId(long proposalId) {
            return Optional.empty();
        }

        @Override
        public List<RecordFacts> proposalFamily(String proposalNumber) {
            return List.of();
        }

        @Override
        public Optional<String> latestProposalVersionKey(String proposalNumber) {
            return Optional.empty();
        }

        @Override
        public List<Long> subawardFamilyFundingAwardIds(long subawardId) {
            return List.of();
        }

        @Override
        public Optional<NegotiationFacts> negotiationFacts(long negotiationId) {
            return Optional.empty();
        }
    }
}
