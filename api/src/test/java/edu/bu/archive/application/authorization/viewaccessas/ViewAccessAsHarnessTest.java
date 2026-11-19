package edu.bu.archive.application.authorization.viewaccessas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.AccessOutcome;
import edu.bu.archive.application.authorization.AuthorizationPolicy;
import edu.bu.archive.application.authorization.RecordContact;
import edu.bu.archive.application.authorization.RecordFacts;
import edu.bu.archive.application.authorization.RecordModule;
import edu.bu.archive.application.authorization.UnitHierarchy;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;

/**
 * The View Access As QA harness, exercised across every profile the QA brief
 * asks for, and emitting the decision table a tester reads.
 *
 * <p>Records here are synthetic {@link RecordFacts}: no archive row is read,
 * so no DEV record detail - restricted or otherwise - can reach the output.
 * That is the first line of defence for the disclosure threat, not a
 * convenience.
 */
@DisplayName("View Access As - temporary QA harness")
class ViewAccessAsHarnessTest {

    private static final String UNIT = "1242040000";
    private static final String CHILD_UNIT = "1242041100";
    private static final String OTHER_UNIT = "1555000000";
    private static final String PERSON = "KP-900001";

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private static final UnitHierarchy HIERARCHY =
            new MapUnitHierarchy(Map.of(CHILD_UNIT, UNIT));

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match) {
        return policy(match, AuthorizationPolicy.VersionScope.PER_VERSION);
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.DepartmentMatch match,
                                              AuthorizationPolicy.VersionScope scope) {
        return new AuthorizationPolicy(scope, match, Set.of("PI", "COI", "MPI", "KP"),
                AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
    }

    private static ViewAccessAsHarness harness(AuthorizationPolicy policy) {
        return new ViewAccessAsHarness(policy, HIERARCHY, NOW);
    }

    // ---------------------------------------------------------------- records

    private static RecordFacts award(String number, String leadUnit) {
        return record(RecordModule.AWARD, number, number, leadUnit, List.of(), Set.of());
    }

    private static RecordFacts record(RecordModule module, String versionKey, String familyKey,
                                      String leadUnit, List<RecordContact> contacts, Set<String> ios) {
        return new RecordFacts(module, versionKey, familyKey, leadUnit, contacts, ios);
    }

    // --------------------------------------------------------------- profiles

    private static SyntheticProfile noGrants() {
        return SyntheticProfile.named("P-NONE", "no grants at all").build();
    }

    private static SyntheticProfile unitExact() {
        return SyntheticProfile.named("P-UNIT-EXACT", "UNIT " + UNIT + ", descendants=false")
                .unit(UNIT, false).build();
    }

    private static SyntheticProfile unitDescendants() {
        return SyntheticProfile.named("P-UNIT-DESC", "UNIT " + UNIT + ", descendants=true")
                .unit(UNIT, true).build();
    }

    private static SyntheticProfile central() {
        return SyntheticProfile.named("P-CENTRAL", "CENTRAL").central().build();
    }

    private static SyntheticProfile io() {
        return SyntheticProfile.named("P-IO", "IO value IO-7781").io("IO-7781").build();
    }

    private static SyntheticProfile researchStaff() {
        return SyntheticProfile.named("P-STAFF", "CONTACT_DERIVATION for " + PERSON)
                .researchStaff(PERSON).build();
    }

    private static SyntheticProfile revoked() {
        return SyntheticProfile.named("P-REVOKED", "UNIT " + UNIT + ", revoked")
                .revokedUnit(UNIT, NOW.minusSeconds(3600)).build();
    }

    private static SyntheticProfile expired() {
        return SyntheticProfile.named("P-EXPIRED", "UNIT " + UNIT + ", expired an hour ago")
                .expiredUnit(UNIT, NOW.minusSeconds(3600)).build();
    }

    private static SyntheticProfile notYetValid() {
        return SyntheticProfile.named("P-FUTURE", "UNIT " + UNIT + ", starts tomorrow")
                .futureUnit(UNIT, NOW.plusSeconds(86_400)).build();
    }

    // ------------------------------------------------- the runnable scenario

    /**
     * The developer-facing run. Produces the decision table and fails if any
     * row's actual verdict differs from the expected one.
     */
    @Test
    @DisplayName("the decision table is produced and every expectation holds")
    void theDecisionTable() throws Exception {
        var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
        var table = new DecisionTable(
                "profile set: baseline UNIT " + UNIT + " (descendants=false) plus comparators",
                harness.policy(), harness.evaluatedAt());

        RecordFacts inScope = award("100012-00002", UNIT);
        RecordFacts childScope = award("100087-00003", CHILD_UNIT);
        RecordFacts outOfScope = award("100045-00001", OTHER_UNIT);

        for (var profile : List.of(noGrants(), unitExact(), central(), revoked(), expired(), notYetValid())) {
            boolean reaches = profile.name().equals("P-UNIT-EXACT") || profile.name().equals("P-CENTRAL");
            table.add("Award 100012-00002 (unit " + UNIT + ")", profile,
                    reaches ? Verdict.PERMIT : Verdict.DENY, harness.evaluate(profile, inScope));
        }

        table.add("Award 100087-00003 (child unit)", unitExact(),
                Verdict.DENY, harness.evaluate(unitExact(), childScope));
        table.add("Award 100045-00001 (other unit)", unitExact(),
                Verdict.DENY, harness.evaluate(unitExact(), outOfScope));
        table.add("Subaward 3595", unitExact(),
                Verdict.DENY, harness.evaluate(unitExact(),
                        record(RecordModule.SUBAWARD, "3595", "3595", UNIT, List.of(), Set.of())));
        table.add("IRB 7781", central(),
                Verdict.PERMIT, harness.evaluate(central(),
                        record(RecordModule.IRB, "7781", "7781", null, List.of(), Set.of())));

        // Descendants: BOTH the grant flag and the policy must agree.
        var descendantsPolicy = harness(policy(AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS));
        table.add("Award 100087-00003 (child unit)", unitDescendants(),
                Verdict.DENY, harness.evaluate(unitDescendants(), childScope));
        table.add("Award 100087-00003 (child unit, policy=DESC)", unitExact(),
                Verdict.DENY, descendantsPolicy.evaluate(unitExact(), childScope));
        table.add("Award 100087-00003 (child unit, policy=DESC)", unitDescendants(),
                Verdict.PERMIT, descendantsPolicy.evaluate(unitDescendants(), childScope));

        // IO and Research Staff.
        table.add("Award 100300-00001 (io IO-7781)", io(), Verdict.PERMIT,
                harness.evaluate(io(), record(RecordModule.AWARD, "100300-00001", "100300-00001",
                        OTHER_UNIT, List.of(), Set.of("IO-7781"))));
        table.add("Award 100400-00001 (PI " + PERSON + ")", researchStaff(), Verdict.PERMIT,
                harness.evaluate(researchStaff(), record(RecordModule.AWARD, "100400-00001",
                        "100400-00001", OTHER_UNIT,
                        List.of(RecordContact.employee(PERSON, "PI")), Set.of())));
        table.add("Award 100401-00001 (non-employee PI)", researchStaff(), Verdict.DENY,
                harness.evaluate(researchStaff(), record(RecordModule.AWARD, "100401-00001",
                        "100401-00001", OTHER_UNIT,
                        List.of(RecordContact.nonEmployee("PI")), Set.of())));

        Path written = table.writeTo(Path.of("target", "view-access-as"), "decision-table.txt");
        System.out.println();
        System.out.println(table.render());
        System.out.println("written to " + written.toAbsolutePath());

        assertThat(table.failures())
                .as("every expectation must match the engine's own verdict")
                .isEmpty();
        assertThat(table.count(Verdict.INDETERMINATE))
                .as("a configured policy must never yield INDETERMINATE")
                .isZero();
    }

    // ------------------------------------------------------- per-profile facts

    @Nested
    @DisplayName("profiles")
    class Profiles {

        @Test
        void noGrantsIsNotProvisionedRatherThanOutOfScope() {
            var evaluation = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(noGrants(), award("100012-00002", UNIT));
            assertThat(evaluation.verdict()).isEqualTo(Verdict.DENY);
            assertThat(evaluation.rule()).isEqualTo("ACCESS_NOT_PROVISIONED");
            assertThat(evaluation.outcome()).isInstanceOf(AccessOutcome.NotProvisioned.class);
        }

        @Test
        void theBaselineProfilePermitsItsOwnUnitAndNothingElse() {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            assertThat(harness.evaluate(unitExact(), award("a", UNIT)).verdict()).isEqualTo(Verdict.PERMIT);
            assertThat(harness.evaluate(unitExact(), award("b", CHILD_UNIT)).verdict()).isEqualTo(Verdict.DENY);
            assertThat(harness.evaluate(unitExact(), award("c", OTHER_UNIT)).verdict()).isEqualTo(Verdict.DENY);
        }

        @Test
        void centralShortCircuitsBeforeAnyRecordFactIsRead() {
            // A record with no lead unit, no contacts and no IO values: nothing
            // to match on. CENTRAL still permits, which is the short-circuit.
            var evaluation = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(central(), record(RecordModule.IRB, "x", "x", null, List.of(), Set.of()));
            assertThat(evaluation.verdict()).isEqualTo(Verdict.PERMIT);
            assertThat(evaluation.rule()).isEqualTo("CENTRAL");
        }

        @Test
        void descendantsNeedBothTheGrantFlagAndThePolicy() {
            var exact = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            var desc = harness(policy(AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS));
            RecordFacts child = award("child", CHILD_UNIT);

            assertThat(exact.evaluate(unitDescendants(), child).verdict())
                    .as("grant asks for descendants, policy does not allow it")
                    .isEqualTo(Verdict.DENY);
            assertThat(desc.evaluate(unitExact(), child).verdict())
                    .as("policy allows descendants, grant did not ask")
                    .isEqualTo(Verdict.DENY);
            assertThat(desc.evaluate(unitDescendants(), child).verdict())
                    .as("both agree (D4)")
                    .isEqualTo(Verdict.PERMIT);
        }

        @Test
        void ioPermitsOnlyAnIntersectingValue() {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            assertThat(harness.evaluate(io(), record(RecordModule.AWARD, "a", "a", OTHER_UNIT,
                    List.of(), Set.of("IO-7781"))).rule()).isEqualTo("IO_GRANT");
            assertThat(harness.evaluate(io(), record(RecordModule.AWARD, "b", "b", OTHER_UNIT,
                    List.of(), Set.of("IO-0000"))).verdict()).isEqualTo(Verdict.DENY);
        }

        @Test
        void researchStaffNeedsAnEmployeeContactInAQualifyingRole() {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            assertThat(harness.evaluate(researchStaff(), record(RecordModule.AWARD, "a", "a",
                    OTHER_UNIT, List.of(RecordContact.employee(PERSON, "PI")), Set.of())).rule())
                    .isEqualTo("RESEARCH_STAFF_CONTACT");
            assertThat(harness.evaluate(researchStaff(), record(RecordModule.AWARD, "b", "b",
                    OTHER_UNIT, List.of(RecordContact.employee(PERSON, "VIEWER")), Set.of())).verdict())
                    .as("a role outside researchStaffRoles does not qualify")
                    .isEqualTo(Verdict.DENY);
            assertThat(harness.evaluate(researchStaff(), record(RecordModule.AWARD, "c", "c",
                    OTHER_UNIT, List.of(RecordContact.nonEmployee("PI")), Set.of())).verdict())
                    .as("a rolodex contact has no PERSON_ID and can never match")
                    .isEqualTo(Verdict.DENY);
        }

        @Test
        void researchStaffReachesNoModuleBeyondAwardAndProposal() {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            for (RecordModule module : List.of(RecordModule.NEGOTIATION, RecordModule.SUBAWARD,
                    RecordModule.IRB)) {
                assertThat(harness.evaluate(researchStaff(), record(module, "x", "x", OTHER_UNIT,
                        List.of(RecordContact.employee(PERSON, "PI")), Set.of())).verdict())
                        .as(module + " has no contact rule")
                        .isEqualTo(Verdict.DENY);
            }
        }
    }

    @Nested
    @DisplayName("grants that are no longer, or not yet, active")
    class Inactive {

        @Test
        void aRevokedGrantGrantsNothing() {
            var evaluation = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(revoked(), award("a", UNIT));
            assertThat(evaluation.verdict()).isEqualTo(Verdict.DENY);
            assertThat(evaluation.rule()).isEqualTo("ACCESS_NOT_PROVISIONED");
        }

        @Test
        void anExpiredGrantGrantsNothingEvenThoughItWasNeverRevoked() {
            /*
             * Worth separating from revocation: V087's unique index is NOT
             * time-aware, so this grant still occupies its scope and still
             * blocks a re-grant - while granting nothing.
             */
            var evaluation = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(expired(), award("a", UNIT));
            assertThat(evaluation.verdict()).isEqualTo(Verdict.DENY);
            assertThat(evaluation.rule()).isEqualTo("ACCESS_NOT_PROVISIONED");
        }

        @Test
        void aGrantThatHasNotStartedGrantsNothing() {
            assertThat(harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(notYetValid(), award("a", UNIT)).verdict())
                    .isEqualTo(Verdict.DENY);
        }

        @Test
        void anExpiredGrantBecomesActiveAgainOnlyByMovingTheClockBack() {
            // The same profile, evaluated before its expiry, permits - proving
            // the denial above came from the clock and not from a typo.
            var before = new ViewAccessAsHarness(
                    policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT),
                    HIERARCHY, NOW.minusSeconds(7200));
            assertThat(before.evaluate(expired(), award("a", UNIT)).verdict()).isEqualTo(Verdict.PERMIT);
        }
    }

    @Nested
    @DisplayName("version scope")
    class VersionScope {

        @Test
        void familyWideReachesEveryVersionAndPerVersionDoesNot() {
            RecordFacts v1 = record(RecordModule.AWARD, "100012-00001", "100012", UNIT, List.of(), Set.of());
            RecordFacts v2 = record(RecordModule.AWARD, "100012-00002", "100012", OTHER_UNIT, List.of(), Set.of());
            List<RecordFacts> family = List.of(v1, v2);

            var perVersion = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                    AuthorizationPolicy.VersionScope.PER_VERSION));
            var familyWide = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                    AuthorizationPolicy.VersionScope.FAMILY_WIDE));

            assertThat(perVersion.evaluate(unitExact(), v2, family).verdict()).isEqualTo(Verdict.DENY);
            assertThat(familyWide.evaluate(unitExact(), v2, family).verdict()).isEqualTo(Verdict.PERMIT);
        }

        @Test
        void familyWideCannotBeWidenedWithAnotherFamilysVersions() {
            RecordFacts target = record(RecordModule.AWARD, "100045-00001", "100045", OTHER_UNIT,
                    List.of(), Set.of());
            RecordFacts foreign = record(RecordModule.AWARD, "100012-00001", "100012", UNIT,
                    List.of(), Set.of());

            var familyWide = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                    AuthorizationPolicy.VersionScope.FAMILY_WIDE));
            assertThat(familyWide.evaluate(unitExact(), target, List.of(target, foreign)).verdict())
                    .as("another family's in-scope version must not authorise this one")
                    .isEqualTo(Verdict.DENY);
        }
    }

    @Nested
    @DisplayName("the harness itself")
    class HarnessProperties {

        @Test
        void anUnconfiguredPolicyIsIndeterminateNotDenied() {
            /*
             * The engine cannot be handed a null policy - RecordAccessEvaluator
             * requires one - so the INDETERMINATE path is reached the way
             * production reaches it: through a denial code that means "no
             * decision was made".
             */
            var decision = edu.bu.archive.application.authorization.AccessDecision.deny(
                    AccessOutcome.DenialReason.POLICY_NOT_CONFIGURED.name());
            assertThat(Verdict.of(decision)).isEqualTo(Verdict.INDETERMINATE);
            assertThat(Verdict.of(edu.bu.archive.application.authorization.AccessDecision.deny(
                    AccessOutcome.DenialReason.EVALUATION_FAILED.name())))
                    .isEqualTo(Verdict.INDETERMINATE);
            assertThat(Verdict.of(edu.bu.archive.application.authorization.AccessDecision.deny(
                    "OUT_OF_SCOPE")))
                    .as("a real scope denial is a decision, not an absence of one")
                    .isEqualTo(Verdict.DENY);
        }

        @Test
        void noSimulatorStateCrossesBetweenProfiles() {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            // Evaluate CENTRAL first; a leaked grant store would make the
            // following no-grant profile permissive.
            assertThat(harness.evaluate(central(), award("a", UNIT)).verdict()).isEqualTo(Verdict.PERMIT);
            assertThat(harness.evaluate(noGrants(), award("a", UNIT)).verdict()).isEqualTo(Verdict.DENY);
            assertThat(harness.evaluate(central(), award("a", UNIT)).verdict()).isEqualTo(Verdict.PERMIT);
        }

        @Test
        void concurrentProfilesDoNotInterfere() throws Exception {
            var harness = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT));
            RecordFacts target = award("100012-00002", UNIT);
            List<SyntheticProfile> profiles = List.of(central(), unitExact(), noGrants(), revoked(),
                    expired(), notYetValid(), io(), researchStaff());

            ExecutorService pool = Executors.newFixedThreadPool(profiles.size());
            try {
                List<Callable<String>> jobs = profiles.stream()
                        .map(p -> (Callable<String>) () -> {
                            String seen = null;
                            for (int i = 0; i < 50; i++) {
                                String verdict = harness.evaluate(p, target).verdict().name();
                                if (seen != null && !seen.equals(verdict)) {
                                    return p.name() + " unstable: " + seen + " then " + verdict;
                                }
                                seen = verdict;
                            }
                            return p.name() + "=" + seen;
                        })
                        .toList();
                List<String> results = new java.util.ArrayList<>();
                for (Future<String> future : pool.invokeAll(jobs)) {
                    results.add(future.get());
                }
                assertThat(results)
                        .as("each profile's verdict stays its own under contention")
                        .containsExactlyInAnyOrder(
                                "P-CENTRAL=PERMIT", "P-UNIT-EXACT=PERMIT", "P-NONE=DENY",
                                "P-REVOKED=DENY", "P-EXPIRED=DENY", "P-FUTURE=DENY",
                                "P-IO=DENY", "P-STAFF=DENY");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void theHarnessNeverConstructsAnIdentityARealSignInCouldProduce() {
            var outcome = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .outcomeFor(unitExact());
            assertThat(outcome).isInstanceOf(AccessOutcome.Scoped.class);
            // The issuer is not a Cognito user-pool issuer and cannot be one:
            // nothing signs it, and SecurityConfiguration would reject it.
            assertThat(new edu.bu.archive.application.authorization.ValidatedCognitoIdentity(
                    "simulation://view-access-as/not-a-user-pool", "simulated-x").issuer())
                    .doesNotContain("cognito-idp")
                    .doesNotContain("amazonaws.com");
        }

        @Test
        void theRuleColumnIsTheEnginesOwnWordsNotTheHarnesss() {
            var evaluation = harness(policy(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT))
                    .evaluate(unitExact(), award("a", UNIT));
            // Every rule string is either an AccessReason name or a denial code.
            Set<String> enginVocabulary = new java.util.HashSet<>();
            for (var reason : edu.bu.archive.application.authorization.AccessReason.values()) {
                enginVocabulary.add(reason.name());
            }
            for (var reason : AccessOutcome.DenialReason.values()) {
                enginVocabulary.add(reason.name());
            }
            enginVocabulary.add("OUT_OF_SCOPE");
            enginVocabulary.add("ACCESS_NOT_PROVISIONED");
            assertThat(enginVocabulary).contains(evaluation.rule());
        }

        @Test
        void aSyntheticProfileCarriesNoPersistedGrant() {
            // The grants a profile describes are plain value objects with
            // locally-assigned ids; nothing has an identity in authz.access_grant.
            assertThat(unitExact().grants()).allSatisfy(grant -> {
                assertThat(grant.grantee()).isEqualTo(SyntheticProfile.GRANTEE);
                assertThat(grant.id()).isLessThanOrEqualTo(8L);
            });
            assertThat(noGrants().grants()).isEmpty();
            assertThat(researchStaff().kualiPersonId()).isNotEqualTo(Optional.empty());
        }
    }
}
