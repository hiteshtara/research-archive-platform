package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

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

import edu.bu.archive.adapter.out.persistence.authorization.AwardAccountNumberIoSql;
import edu.bu.archive.application.authorization.fixtures.InMemoryAccessGrantRepository;
import edu.bu.archive.application.authorization.fixtures.MapUnitHierarchy;

/**
 * F1 (A8 isolated window, 2026-11-18): an IO grant must authorise Awards only.
 *
 * <p>Documented boundary (GrantProvisioningCommand, AwardAccountNumberIoResolver,
 * DEV runbooks): "an IO grant never reaches Proposals, Subawards or
 * Negotiations". The owner confirmed on 2026-11-18 that this documented policy
 * stands and that the observed behaviour is a security defect. Live, an IO
 * caller opened all 12 endpoints of a Subaward funded by its Award, because
 * Subaward and Negotiation access is decided by asking "is a linked Award
 * visible?" and IO-derived visibility answered yes.
 *
 * <p>The fix mirrors SEC-005 (PARENT_AWARD): the IO clause is honoured for a
 * DIRECT Award decision and ignored for an INHERITED one, in both layers. The
 * evaluator does it through {@link RecordAccessEvaluator.DecisionContext}, and
 * the list SQL omits the clause when it builds the inherited Award predicate.
 *
 * <p>Fixtures are synthetic. No BU data appears here.
 */
class IoGrantInheritanceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final String GRANTEE = "SYN-INST-6000";
    private static final String IO_VALUE = "SYN-IO-9000";
    private static final String UNIT = "SYN-U-800";

    /** The real strategy, so the SQL assertions see the predicate DEV renders. */
    private static final IoSqlStrategy IO_SQL = new AwardAccountNumberIoSql();

    private static AuthorizationPolicy policy() {
        return new AuthorizationPolicy(
                AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
                Set.of("PI", "MPI", "COI"),
                1,
                AuthorizationPolicy.ContactDerivation.EXPLICIT_GRANT);
    }

    /** An Award version carrying the granted IO, in a unit the caller does NOT hold. */
    private static RecordFacts ioAward() {
        return new RecordFacts(RecordModule.AWARD, "1", "C-000", "SYN-U-OTHER",
                List.of(), Set.of(IO_VALUE));
    }

    private static AccessOutcome resolve(AccessGrant... grants) {
        var repository = new InMemoryAccessGrantRepository();
        for (AccessGrant grant : grants) {
            repository.add(grant);
        }
        var resolution = new IdentityResolution.Mapped(
                new ValidatedCognitoIdentity("https://issuer.test.invalid", "sub-6000"),
                new InstitutionalIdentifier(GRANTEE),
                Optional.empty());
        return new AccessScopeResolver(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> Optional.of(policy()), ContactRelationships.NONE, AwardHierarchy.SELF_ONLY)
                .resolve(resolution);
    }

    private static AccessGrant ioGrant(long id) {
        return new AccessGrant(id, new InstitutionalIdentifier(GRANTEE), GrantType.IO,
                null, false, IO_VALUE, null, null, null);
    }

    private static AccessGrant unitGrant(long id) {
        return new AccessGrant(id, new InstitutionalIdentifier(GRANTEE), GrantType.UNIT,
                UNIT, false, null, null, null, null);
    }

    private static RecordAccessEvaluator evaluator() {
        return new RecordAccessEvaluator(policy(), new MapUnitHierarchy(Map.of()));
    }

    // ── decision ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The evaluator honours IO for Awards and ignores it for inherited decisions")
    class Decision {

        @Test
        void anIoGrantStillAuthorisesTheAwardDirectly() {
            // The fix is about inheritance, not about the grant: an IO grant
            // that stopped reaching its own Award would be a broken grant.
            AccessDecision decision = evaluator().decide(resolve(ioGrant(1)), ioAward(),
                    List.of(ioAward()), RecordAccessEvaluator.DecisionContext.DIRECT);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reasons()).containsExactly(AccessReason.IO_GRANT);
        }

        @Test
        void anIoGrantDoesNotAuthoriseAnInheritedDecision() {
            /*
             * The inherited question is "may this caller see a Subaward (or
             * Negotiation) because this Award is visible?". For an IO-only
             * caller the answer must be no: an IO grant reaches Awards only.
             * RED on main c339e94: the evaluator added IO_GRANT in both contexts.
             */
            AccessDecision decision = evaluator().decide(resolve(ioGrant(1)), ioAward(),
                    List.of(ioAward()), RecordAccessEvaluator.DecisionContext.INHERITED);
            assertThat(decision.allowed())
                    .as("an IO grant must not authorise a Subaward or Negotiation through its Award")
                    .isFalse();
            assertThat(decision.denialCode()).isEqualTo("OUT_OF_SCOPE");
        }

        @Test
        void aUnitGrantStillTravelsThroughInheritance() {
            // The restriction is IO-specific. A department grant on the funding
            // Award must still authorise the Subaward exactly as before.
            RecordFacts unitAward = new RecordFacts(RecordModule.AWARD, "1", "D-000", UNIT,
                    List.of(), Set.of());
            AccessDecision decision = evaluator().decide(resolve(unitGrant(1)), unitAward,
                    List.of(unitAward), RecordAccessEvaluator.DecisionContext.INHERITED);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reasons()).containsExactly(AccessReason.DEPARTMENT);
        }

        @Test
        void aCallerWithBothGrantsInheritsOnlyThroughTheUnit() {
            // An Award matched by BOTH grants: the inherited decision is allowed,
            // and its reason is the department, never the IO.
            RecordFacts both = new RecordFacts(RecordModule.AWARD, "1", "E-000", UNIT,
                    List.of(), Set.of(IO_VALUE));
            AccessDecision inherited = evaluator().decide(resolve(unitGrant(1), ioGrant(2)), both,
                    List.of(both), RecordAccessEvaluator.DecisionContext.INHERITED);
            assertThat(inherited.allowed()).isTrue();
            assertThat(inherited.reasons()).containsExactly(AccessReason.DEPARTMENT);

            // ...and an IO-only Award gives that same caller nothing to inherit.
            AccessDecision ioOnly = evaluator().decide(resolve(unitGrant(1), ioGrant(2)), ioAward(),
                    List.of(ioAward()), RecordAccessEvaluator.DecisionContext.INHERITED);
            assertThat(ioOnly.allowed()).isFalse();
        }
    }

    // ── list SQL ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The list SQL omits the IO clause from the inherited Award predicate")
    class ListSql {

        private final RecordScopeSqlBuilder builder = new RecordScopeSqlBuilder(policy(), IO_SQL);

        @Test
        void theAwardListStillCarriesTheIoClause() {
            SqlFragment fragment = builder.build(resolve(ioGrant(1)), RecordModule.AWARD, "av", Set.of());
            assertThat(fragment.sql()).contains("IN (:az_ios)");
            assertThat(fragment.params()).containsKey("az_ios");
        }

        @Test
        void subawardAndNegotiationListsDoNotInheritAnIoGrant() {
            /*
             * subawardRow and negotiationRow reuse the Award predicate, so the
             * clause is omitted at that call site - the same shape as SEC-005.
             * RED on main c339e94: both fragments contained "IN (:az_ios)".
             */
            for (RecordModule module : List.of(RecordModule.SUBAWARD, RecordModule.NEGOTIATION)) {
                SqlFragment fragment = builder.build(resolve(ioGrant(1)), module, "x", Set.of());
                assertThat(fragment.sql())
                        .as(module + " must not be reachable through an IO grant")
                        .doesNotContain(":az_ios");
                assertThat(fragment.params())
                        .as(module + " must not even bind the IO parameter")
                        .doesNotContainKey("az_ios");
            }
        }

        @Test
        void anIoOnlyCallerGetsADenyAllSubawardPredicate() {
            // With the IO clause gone an IO-only scope has no inherited clause
            // at all, so the inherited Award predicate must render FALSE rather
            // than an empty OR that could match everything.
            SqlFragment fragment = builder.build(resolve(ioGrant(1)), RecordModule.SUBAWARD, "s", Set.of());
            assertThat(fragment.sql()).contains("FALSE");
        }

        @Test
        void aUnitGrantStillScopesTheSubawardList() {
            // build() takes the department units already expanded by the caller
            // (RecordAuthorizationService.expandedUnits), so pass them as it does.
            SqlFragment fragment = builder.build(resolve(unitGrant(1), ioGrant(2)),
                    RecordModule.SUBAWARD, "s", Set.of(UNIT));
            assertThat(fragment.sql()).contains(":az_units");
            assertThat(fragment.sql()).doesNotContain(":az_ios");
        }
    }

    // ── the two layers must agree ───────────────────────────────────────

    @Test
    @DisplayName("Evaluator and SQL give the same answer for direct and inherited IO decisions")
    void evaluatorAndSqlAgreeOnIoInheritance() {
        AccessOutcome outcome = resolve(ioGrant(1));
        var builder = new RecordScopeSqlBuilder(policy(), IO_SQL);

        boolean evaluatorDirect = evaluator().decide(outcome, ioAward(), List.of(ioAward()),
                RecordAccessEvaluator.DecisionContext.DIRECT).allowed();
        boolean sqlDirect = builder.build(outcome, RecordModule.AWARD, "av", Set.of())
                .sql().contains(":az_ios");
        assertThat(evaluatorDirect).as("direct: evaluator").isTrue();
        assertThat(sqlDirect).as("direct: SQL").isTrue();

        boolean evaluatorInherited = evaluator().decide(outcome, ioAward(), List.of(ioAward()),
                RecordAccessEvaluator.DecisionContext.INHERITED).allowed();
        boolean sqlInherited = builder.build(outcome, RecordModule.SUBAWARD, "s", Set.of())
                .sql().contains(":az_ios");
        assertThat(evaluatorInherited).as("inherited: evaluator must refuse").isFalse();
        assertThat(sqlInherited).as("inherited: SQL must refuse").isFalse();
    }
}
