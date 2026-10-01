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
    void familyWideWrapsThePredicateInAnExistsOverTheSameFamily() {
        var fragment = new RecordScopeSqlBuilder(FAMILY_WIDE, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), "P1", Set.of()), RecordModule.AWARD, "av", Set.of());
        assertThat(fragment.sql()).contains("az_fv.award_number = av.award_number")
                .contains("az_ap.award_id = az_fv.award_id");
    }

    @Test
    void modulesWithoutAnApprovedNonCentralRuleDenyForNonCentralUsers() {
        var builder = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE);
        var scope = scoped(false, Set.of(new UnitGrant("U1", false)), "P1", Set.of());
        for (RecordModule module : Set.of(RecordModule.NEGOTIATION, RecordModule.SUBAWARD, RecordModule.IRB)) {
            assertThat(builder.build(scope, module, "x", Set.of("U1"))).isEqualTo(SqlFragment.DENY_ALL);
        }
    }

    @Test
    void proposalPredicateUsesProposalPeopleByProposalId() {
        var fragment = new RecordScopeSqlBuilder(PER_VERSION, IoSqlStrategy.NONE).build(
                scoped(false, Set.of(), "P1", Set.of()), RecordModule.PROPOSAL, "ranked", Set.of());
        assertThat(fragment.sql()).contains("az_pp.proposal_id = ranked.proposal_id");
    }
}
