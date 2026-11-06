package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RecordScopeSqlBuilderTest {

    private static final AuthorizationPolicy PER_VERSION = new AuthorizationPolicy(
            AuthorizationPolicy.VersionScope.PER_VERSION, AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
            Set.of("PI", "MPI", "COI"));
    private static final AuthorizationPolicy FAMILY_WIDE = new AuthorizationPolicy(
            AuthorizationPolicy.VersionScope.FAMILY_WIDE, AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT,
            Set.of("PI"));

    private static AccessOutcome scoped(boolean central, Set<UnitGrant> units, String person, Set<String> ios) {
        return new AccessOutcome.Scoped(new AccessScope(central, units,
                Optional.ofNullable(person).map(KualiPersonId::new), ios));
    }

    @Test
    void centralAddsNoRestrictionAndEverythingElseDeniesUnlessScoped() {
        var builder = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE);
        assertThat(builder.build(scoped(true, Set.of(), null, Set.of()), RecordModule.AWARD, "av", Set.of()))
                .isEqualTo(SqlFragment.NONE);
        assertThat(builder.build(new AccessOutcome.NotProvisioned(AccessOutcome.NotProvisionedReason.NO_IDENTITY_LINK),
                RecordModule.AWARD, "av", Set.of())).isEqualTo(SqlFragment.DENY_ALL);
        assertThat(builder.build(new AccessOutcome.Denied(AccessOutcome.DenialReason.SUSPENDED_IDENTITY),
                RecordModule.AWARD, "av", Set.of())).isEqualTo(SqlFragment.DENY_ALL);
    }

    @Test
    void awardPredicateUnionsUnitsContactsAndIoOnTheRowAlias() {
        var io = (IoSqlStrategy) (module, alias) -> Optional.of("EXISTS(io " + alias + ".award_id IN (:az_ios))");
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, io).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of("IO1")),
                RecordModule.AWARD, "av", Set.of("U1"));
        assertThat(fragment.sql())
                .contains("av.lead_unit_number IN (:az_units)")
                .contains("az_ap.award_id = av.award_id AND az_ap.person_id = :az_person")
                .contains("IN (:az_roles)")
                .contains("EXISTS(io av.award_id IN (:az_ios))")
                .contains(" OR ")
                .doesNotContain("{row}");
        assertThat(fragment.params()).containsKeys("az_units", "az_person", "az_roles", "az_ios");
    }

    @Test
    void ioGrantsMatchNothingWhileTheRealIoFieldIsUnresolved() {
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), null, Set.of("IO1")), RecordModule.AWARD, "av", Set.of());
        assertThat(fragment.sql()).contains("FALSE");
    }

    @Test
    void familyWideWrapsTheDepartmentClauseInAnExistsOverTheSameFamily() {
        /*
         * Rewritten for SEC-003 (2026-11-06). This test used to drive a
         * contact-only scope and assert the contact clause sat INSIDE the
         * wrap (az_ap.award_id = az_fv.award_id). That is now exactly the
         * behaviour the business rejected, so the wrap is demonstrated with
         * the department clause, which is still family-wide.
         */
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), null, Set.of()),
                RecordModule.AWARD, "av", Set.of("U1"));
        assertThat(fragment.sql()).contains("az_fv.award_number = av.award_number")
                .contains("az_fv.lead_unit_number IN (:az_units)");
    }

    @Test
    void modulesWithoutAnApprovedNonCentralRuleDenyForNonCentralUsers() {
        // SUBAWARD left this list on 2026-10-26 and NEGOTIATION on
        // 2026-10-27, as each rule was approved. Only IRB still has no
        // approved non-Central rule, and must keep denying.
        var builder = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE);
        var scope = scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of());
        for (RecordModule module : Set.of(RecordModule.IRB)) {
            assertThat(builder.build(scope, module, "x", Set.of("U1"))).isEqualTo(SqlFragment.DENY_ALL);
        }
    }

    @Test
    void subawardInheritsTheAwardPredicateThroughItsFAMILYsFundingRows() {
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of()),
                RecordModule.SUBAWARD, "s", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("archive.subaward_funding az_sf")
                // FAMILY-level: correlates on subaward_code, not subaward_id.
                // Per version, only 4.06% of real dev version rows were
                // reachable, because funding sits on the newest version only.
                .contains("az_fam.subaward_code = s.subaward_code")
                // The family is reached through subaward_id. Reading
                // subaward_funding's own denormalised subaward_code would
                // attribute 8 real dev rows to the WRONG family.
                .contains("az_sf.subaward_id = az_fam.subaward_id")
                .doesNotContain("az_sf.subaward_code")
                // INNER join: a funding row whose award_id resolves to
                // nothing can never authorise.
                .contains("JOIN archive.award_version az_sa ON az_sa.award_id = az_sf.award_id")
                // The Award rule itself, applied to the FUNDED Award row.
                .contains("az_sa.lead_unit_number IN (:az_units)");
    }

    @Test
    void subawardWithNoUsableAwardClauseDeniesRatherThanMatchingEveryFundingRow() {
        // The dangerous shape: an EXISTS whose inner predicate collapsed to
        // nothing would authorise any Subaward that has ANY funding row.
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), null, Set.of()), RecordModule.SUBAWARD, "s", Set.of());

        assertThat(fragment).isEqualTo(SqlFragment.DENY_ALL);
    }

    @Test
    void familyWideSubawardAnchorsTheFamilyWrapOnTheFundedAwardNotTheSubaward() {
        /*
         * A Subaward row has no award_number, so wrapping on the outer alias
         * would be invalid SQL. Anchoring on az_sa is also what keeps the
         * predicate agreeing with canSeeAward, which is family-wide here.
         */
        // Driven by a UNIT grant, not a contact: after SEC-003 the contact
        // clause is no longer family-wrapped, so a contact-only scope would
        // render no wrap at all and prove nothing about where it anchors.
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), null, Set.of()),
                RecordModule.SUBAWARD, "s", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("az_fv.award_number = az_sa.award_number")
                .doesNotContain("s.award_number");
    }

    @Test
    void proposalPredicateUsesProposalPeopleByProposalId() {
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), "P1", Set.of()), RecordModule.PROPOSAL, "ranked", Set.of());
        assertThat(fragment.sql()).contains("az_pp.proposal_id = ranked.proposal_id");
    }

    // --- SEC-003: contact clauses are never family-wrapped ----------------

    /*
     * The business rejected family-wide contact access on 2026-11-05. The SQL
     * half of that rule is structural: the department and IO clauses stay
     * inside the EXISTS over the family, and the contact clause moves out to
     * the row being tested. These tests assert the STRUCTURE, because the
     * integration suites assert the resulting rows - and a predicate that
     * returns the right rows for the wrong reason would pass those.
     */

    @Test
    void familyWideKeepsTheAwardContactClauseOnTheRowAndNotInTheFamilyWrap() {
        var io = (IoSqlStrategy) (module, alias) -> Optional.of("TRIM(" + alias + ".account_number) IN (:az_ios)");
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, io).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of("IO1")),
                RecordModule.AWARD, "av", Set.of("U1"));

        assertThat(fragment.sql())
                // Department and IO: still family-wide.
                .contains("az_fv.award_number = av.award_number")
                .contains("az_fv.lead_unit_number IN (:az_units)")
                .contains("TRIM(az_fv.account_number) IN (:az_ios)")
                // Contact: pinned to the row being read.
                .contains("az_ap.award_id = av.award_id")
                // ...and NOT to any version of the family.
                .doesNotContain("az_ap.award_id = az_fv.award_id")
                .doesNotContain("{row}");
    }

    @Test
    void familyWideWithOnlyAContactRendersNoFamilyWrapAtAll() {
        // Nothing left to wrap: a contact-only scope is purely per-version.
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), "P1", Set.of()), RecordModule.AWARD, "av", Set.of());

        assertThat(fragment.sql())
                .contains("az_ap.award_id = av.award_id")
                .doesNotContain("az_fv")
                .doesNotContain("FALSE");
    }

    @Test
    void perVersionStillRendersAllThreeClausesAgainstTheRowAndNeverWraps() {
        var io = (IoSqlStrategy) (module, alias) -> Optional.of("TRIM(" + alias + ".account_number) IN (:az_ios)");
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, io).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of("IO1")),
                RecordModule.AWARD, "av", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("av.lead_unit_number IN (:az_units)")
                .contains("az_ap.award_id = av.award_id")
                .contains("TRIM(av.account_number) IN (:az_ios)")
                .doesNotContain("az_fv");
        assertThat(fragment.params()).containsKeys("az_units", "az_person", "az_roles", "az_ios");
    }

    @Test
    void familyWideKeepsTheProposalContactClauseOnTheRowAndNotInTheFamilyWrap() {
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of()),
                RecordModule.PROPOSAL, "ranked", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("az_fv.proposal_number = ranked.proposal_number")
                .contains("az_fv.lead_unit_number IN (:az_units)")
                .contains("az_pp.proposal_id = ranked.proposal_id")
                .doesNotContain("az_pp.proposal_id = az_fv.proposal_id");
    }

    @Test
    void familyWideSubawardKeepsTheContactClauseOnTheFundedAwardVersion() {
        /*
         * The outer Subaward row has NO award_id column, so the contact
         * clause cannot be hoisted to it - it belongs on az_sa, the funded
         * Award version the EXISTS has already joined. That also matches
         * RecordAuthorizationService.canSeeSubaward, which decides each
         * funding award_id as canSeeAward would.
         */
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of()),
                RecordModule.SUBAWARD, "s", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("az_ap.award_id = az_sa.award_id")
                .doesNotContain("az_ap.award_id = az_fv.award_id")
                .doesNotContain("= s.award_id")
                // The department clause is still family-wrapped on az_sa.
                .contains("az_fv.award_number = az_sa.award_number");
    }

    @Test
    void familyWideNegotiationKeepsBothInheritedContactClausesOnTheirJoinedVersion() {
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of()),
                RecordModule.NEGOTIATION, "n", Set.of("U1"));

        assertThat(fragment.sql())
                .contains("az_ap.award_id = az_na.award_id")
                .contains("az_pp.proposal_id = az_np.proposal_id")
                .doesNotContain("= n.award_id")
                .doesNotContain("= n.proposal_id")
                .doesNotContain("az_ap.award_id = az_fv.award_id")
                .doesNotContain("az_pp.proposal_id = az_fv.proposal_id");
    }

    @Test
    void theNegotiationInheritedJoinsArePinnedToTheVersionTheJavaPathDecides() {
        /*
         * An association names a FAMILY (associated_document_id holds an
         * award_number or a proposal_number), so the inherited join has to
         * choose a version. canSeeAwardNumber chooses is_primary_current,
         * canSeeProposalNumber the latest proposal_version - and with the
         * contact clause now per-version, an unpinned join would authorise
         * a Negotiation from a SUPERSEDED version that the record endpoint
         * then refuses. Proven behaviourally in
         * NegotiationRecordAuthorizationIntegrationTest; asserted here so
         * the pin cannot be dropped as "redundant".
         */
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), "P1", Set.of()),
                RecordModule.NEGOTIATION, "n", Set.of());

        assertThat(fragment.sql())
                .contains("az_na.is_primary_current = TRUE")
                .contains("az_np.proposal_id = (SELECT az_npl.proposal_id")
                .contains("ORDER BY az_npl.version_number DESC");
    }

    @Test
    void aContactOnlyScopeStillDeniesEveryModuleWithNoApprovedContactRule() {
        // Moving the contact clause must not accidentally give it to a module
        // that has no contact rule (IRB), nor let it stand alone where the
        // inherited Award clause is the only path (checked above).
        var builder = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE);
        assertThat(builder.build(scoped(false, Set.of(), "P1", Set.of()), RecordModule.IRB, "i", Set.of()))
                .isEqualTo(SqlFragment.DENY_ALL);
    }
}
