package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.admin.GrantSpecification;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;

/**
 * SEC-005 Parent Award: resolution, decision and list SQL.
 *
 * <p>The hierarchy traversal itself is proved against a real PostgreSQL in
 * {@code AwardHierarchyExpansionIntegrationTest}. These tests use a stub
 * hierarchy and prove the parts around it: that the resolver expands at the
 * policy's depth, that the evaluator and the SQL builder agree about which
 * Awards are reached, and that the grant shape is validated the same way the
 * V088 CHECK constraints validate it.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
class ParentAwardAccessTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final String GRANTEE = "SYN-INST-5000";

    /** Family A: A-000 -> A-100 -> A-110. Family B is unrelated. */
    private static final Map<String, Set<String>> DEPTH_1 = Map.of(
            "A-000", Set.of("A-000", "A-100"),
            "B-000", Set.of("B-000"));
    private static final Map<String, Set<String>> DEPTH_2 = Map.of(
            "A-000", Set.of("A-000", "A-100", "A-110"),
            "B-000", Set.of("B-000"));

    /** Records a depth it was asked for, so the policy wiring can be asserted. */
    private static final class RecordingHierarchy implements AwardHierarchy {
        private int lastDepth = -1;

        @Override
        public Set<String> descendantsWithin(String awardNumber, int maxDepth) {
            lastDepth = maxDepth;
            Map<String, Set<String>> table = maxDepth >= 2 ? DEPTH_2 : DEPTH_1;
            return table.getOrDefault(awardNumber, AwardHierarchy.single(awardNumber));
        }
    }

    private static AuthorizationPolicy policy(int depth) {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.PER_VERSION,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("PI", "MPI", "COI"),
                depth,
                AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
    }

    private static RecordFacts award(String awardNumber, String versionKey) {
        return new RecordFacts(RecordModule.AWARD, versionKey, awardNumber, "SYN-U-700",
                List.of(), Set.of());
    }

    private static AccessOutcome resolve(AuthorizationPolicy policy, AwardHierarchy hierarchy,
                                         String... grantedAwards) {
        var grants = new InMemoryAccessGrantRepository();
        long id = 0;
        for (String awardNumber : grantedAwards) {
            grants.add(new AccessGrant(++id, new InstitutionalIdentifier(GRANTEE),
                    GrantType.PARENT_AWARD, null, false, null, null, null, null, awardNumber));
        }
        var resolution = new IdentityResolution.Mapped(
                new ValidatedCognitoIdentity("https://issuer.test.invalid", "sub-5000"),
                new InstitutionalIdentifier(GRANTEE),
                Optional.empty());
        return new AccessScopeResolver(grants, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> Optional.of(policy), ContactRelationships.NONE, hierarchy)
                .resolve(resolution);
    }

    // ── resolution ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Resolution expands the grant at the policy's depth")
    class Resolution {

        @Test
        void theScopeCarriesTheExpandedAwards() {
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000");
            assertThat(outcome).isInstanceOf(AccessOutcome.Scoped.class);
            assertThat(((AccessOutcome.Scoped) outcome).scope().parentAwardNumbers())
                    .containsExactlyInAnyOrder("A-000", "A-100");
        }

        @Test
        void theConfiguredDepthIsTheDepthUsed() {
            var hierarchy = new RecordingHierarchy();
            resolve(policy(2), hierarchy, "A-000");
            assertThat(hierarchy.lastDepth)
                    .as("the policy's parentAwardDepth must reach the hierarchy, "
                            + "or the setting is decorative")
                    .isEqualTo(2);
        }

        @Test
        void twoGrantsUnionTheirExpansions() {
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000", "B-000");
            assertThat(((AccessOutcome.Scoped) outcome).scope().parentAwardNumbers())
                    .containsExactlyInAnyOrder("A-000", "A-100", "B-000");
        }

        @Test
        void aParentAwardGrantAloneIsNotAnEmptyScope() {
            // A scope holding only parent-award numbers must not read as
            // "no active grants" - that would answer NOT_PROVISIONED and the
            // grant would look like it had never been made.
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000");
            assertThat(outcome).isInstanceOf(AccessOutcome.Scoped.class);
            assertThat(((AccessOutcome.Scoped) outcome).scope().isEmpty()).isFalse();
        }

        @Test
        void withNoHierarchyWiredTheGrantStillReachesItsOwnAward() {
            // AwardHierarchy.SELF_ONLY is the default. Under-reaching is safe;
            // reaching nothing would look like a broken grant.
            AccessOutcome outcome = resolve(policy(1), AwardHierarchy.SELF_ONLY, "A-000");
            assertThat(((AccessOutcome.Scoped) outcome).scope().parentAwardNumbers())
                    .containsExactly("A-000");
        }
    }

    // ── decision ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The evaluator decides Awards, and only Awards")
    class Decision {

        private final RecordAccessEvaluator evaluator =
                new RecordAccessEvaluator(policy(1), new MapUnitHierarchy(Map.of()));

        private AccessDecision decide(RecordFacts record) {
            return evaluator.decide(resolve(policy(1), new RecordingHierarchy(), "A-000"),
                    record, List.of(record));
        }

        @Test
        void theGrantedAwardIsPermitted() {
            AccessDecision decision = decide(award("A-000", "1"));
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reasons()).containsExactly(AccessReason.PARENT_AWARD);
        }

        @Test
        void aChildAwardWithinDepthIsPermitted() {
            assertThat(decide(award("A-100", "2")).allowed()).isTrue();
        }

        @Test
        void aGrandchildBeyondDepthIsDenied() {
            AccessDecision decision = decide(award("A-110", "3"));
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denialCode()).isEqualTo("OUT_OF_SCOPE");
        }

        @Test
        void anUnrelatedFamilyIsDenied() {
            assertThat(decide(award("B-000", "4")).allowed()).isFalse();
        }

        @Test
        void everyVersionOfAGrantedAwardIsPermitted() {
            /*
             * award_number is the family key, identical across versions, so a
             * PARENT_AWARD grant reaches every version of the Awards it names.
             * Asserted rather than left implicit: it is the one place this
             * grant type is family-grained while SEC-003 contacts are not.
             */
            assertThat(decide(award("A-000", "1")).allowed()).isTrue();
            assertThat(decide(award("A-000", "2")).allowed()).isTrue();
            assertThat(decide(award("A-000", "3")).allowed()).isTrue();
        }

        @Test
        void itMatchesNoOtherModuleDirectly() {
            /*
             * Direct evaluation only. Subaward and Negotiation access is
             * INHERITED - resolved by asking whether a funding or associated
             * Award is visible - so it is decided through an AWARD record,
             * never by matching a SUBAWARD record against this clause.
             */
            for (RecordModule module : List.of(RecordModule.PROPOSAL, RecordModule.SUBAWARD,
                    RecordModule.NEGOTIATION, RecordModule.IRB)) {
                var record = new RecordFacts(module, "1", "A-000", "SYN-U-700", List.of(), Set.of());
                assertThat(decide(record).allowed())
                        .as(module + " must not match the parent-award clause directly")
                        .isFalse();
            }
        }
    }

    // ── list SQL ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The list SQL scopes to the same set the evaluator decided")
    class ListSql {

        @Test
        void theAwardPredicateNamesTheExpandedSet() {
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000");
            SqlFragment fragment = new RecordScopeSqlBuilder(policy(1), IoSqlStrategy.NONE)
                    .build(outcome, RecordModule.AWARD, "av", Set.of());

            assertThat(fragment.sql()).contains("TRIM(av.award_number) IN (:az_parent_awards)");
            assertThat(fragment.params()).containsKey("az_parent_awards");
            List<?> awards = (List<?>) fragment.params().get("az_parent_awards");
            assertThat(awards.stream().map(String::valueOf).toList())
                    .containsExactlyInAnyOrder("A-000", "A-100");
        }

        @Test
        void proposalAndIrbAreNeverWidenedByAParentAwardGrant() {
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000");
            var builder = new RecordScopeSqlBuilder(policy(1), IoSqlStrategy.NONE);
            for (RecordModule module : List.of(RecordModule.PROPOSAL, RecordModule.IRB)) {
                SqlFragment fragment = builder.build(outcome, module, "x", Set.of());
                assertThat(fragment.sql())
                        .as(module + " has no Award to inherit from and must deny")
                        .doesNotContain("az_parent_awards");
            }
        }

        @Test
        void subawardAndNegotiationInheritTheAwardClause() {
            /*
             * Inheritance is the approved 2026-10-26 rule applied to a new
             * grant type, not a new rule: subawardRow and negotiationRow
             * reuse the Award predicate verbatim, which is exactly why the
             * list cannot disagree with RecordAuthorizationService - that
             * resolves the same question through canSeeAward.
             *
             * Asserted rather than assumed because the alternative - a grant
             * type that silently does NOT travel, as design 4.6 specifies for
             * IO - would be invisible until someone compared the two layers.
             * Pending sign-off: docs/security/SEC_005_PARENT_AWARD.md.
             */
            AccessOutcome outcome = resolve(policy(1), new RecordingHierarchy(), "A-000");
            var builder = new RecordScopeSqlBuilder(policy(1), IoSqlStrategy.NONE);
            for (RecordModule module : List.of(RecordModule.SUBAWARD, RecordModule.NEGOTIATION)) {
                SqlFragment fragment = builder.build(outcome, module, "x", Set.of());
                assertThat(fragment.sql())
                        .as(module + " inherits visibility from its funding/associated Award")
                        .contains("az_parent_awards");
            }
        }
    }

    // ── grant shape, mirroring the V088 CHECK constraints ───────────────

    @Nested
    @DisplayName("A PARENT_AWARD grant has exactly one qualifier")
    class Shape {

        @Test
        void theAwardNumberIsRequired() {
            assertThatThrownBy(() -> new GrantSpecification(
                    GrantType.PARENT_AWARD, null, false, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires an award number");
        }

        @Test
        void itCannotAlsoCarryAUnitOrIoValue() {
            assertThatThrownBy(() -> new GrantSpecification(
                    GrantType.PARENT_AWARD, "SYN-U-700", false, null, "A-000", null, null))
                    .hasMessageContaining("must not carry a unit number or IO value");
        }

        @Test
        void descendantsAreAPolicySettingNotAGrantFlag() {
            assertThatThrownBy(() -> new GrantSpecification(
                    GrantType.PARENT_AWARD, null, true, null, "A-000", null, null))
                    .hasMessageContaining("depth is a policy setting");
        }

        @Test
        void otherTypesCannotSmuggleAnAwardNumber() {
            assertThatThrownBy(() -> new GrantSpecification(
                    GrantType.UNIT, "SYN-U-700", false, null, "A-000", null, null))
                    .hasMessageContaining("must not carry an award number");
            assertThatThrownBy(() -> new AccessGrant(
                    1L, new InstitutionalIdentifier(GRANTEE), GrantType.CENTRAL,
                    null, false, null, null, null, null, "A-000"))
                    .hasMessageContaining("must not carry an award number");
        }

        @Test
        void aNegativeDepthIsRejected() {
            assertThatThrownBy(() -> policy(-1))
                    .hasMessageContaining("parentAwardDepth must not be negative");
        }
    }
}
