package edu.bu.archive.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;

class RecordAuthorizationGateTest {

    private final SyntheticArchive archive = new SyntheticArchive();

    private RecordAuthorizationGate gate(AuthorizationProperties properties) {
        return new RecordAuthorizationGate(properties, archive.units);
    }

    private static AuthorizationProperties configured(boolean enforce) {
        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(enforce);
        properties.setVersionScope(AuthorizationPolicy.VersionScope.PER_VERSION);
        properties.setDepartmentMatch(AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
        properties.setResearchStaffRoles(new java.util.LinkedHashSet<>(Set.of("pi", " mpi ", "COI")));
        return properties;
    }

    @Test
    void enforcementIsOffByDefaultAndTodaysBehaviourIsUnchangedAndLabelled() {
        var defaults = new AuthorizationProperties();
        var gate = gate(defaults);
        assertThat(defaults.isEnforcementEnabled()).isFalse();
        assertThat(gate.status()).isEqualTo(RecordAuthorizationGate.Status.NOT_ENFORCED);
        assertThat(RecordAuthorizationGate.NOT_ENFORCED_LABEL).isEqualTo("record authorization not enforced");
        // Even an unmapped caller is allowed while enforcement is off - exactly as today.
        assertThat(gate.decide(() -> archive.outcomeFor(archive.unmapped),
                SyntheticArchive.AWARD_UNRELATED, List.of()).allowed()).isTrue();
    }

    @Test
    void thePolicyHasNoDefaultsAndAnEnforcedGateWithoutOneDeniesEverything() {
        var properties = new AuthorizationProperties();
        properties.setEnforcementEnabled(true);
        assertThat(properties.policy()).isEmpty();

        var gate = gate(properties);
        assertThat(gate.status()).isEqualTo(RecordAuthorizationGate.Status.ENFORCED_POLICY_NOT_CONFIGURED);
        var decision = gate.decide(() -> archive.outcomeFor(archive.central), SyntheticArchive.AWARD_A_V1, List.of());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialCode()).isEqualTo("POLICY_NOT_CONFIGURED");
    }

    @Test
    void anEnforcedGateDeniesAMissingIdentityAndAnyEvaluationFailure() {
        var gate = gate(configured(true));
        assertThat(gate.decide(null, SyntheticArchive.AWARD_A_V1, List.of()).allowed()).isFalse();
        assertThat(gate.decide(() -> null, SyntheticArchive.AWARD_A_V1, List.of()).allowed()).isFalse();
        var failure = gate.decide(() -> { throw new IllegalStateException("store unavailable"); },
                SyntheticArchive.AWARD_A_V1, List.of());
        assertThat(failure.allowed()).isFalse();
        assertThat(failure.denialCode()).isEqualTo("EVALUATION_FAILED");
    }

    @Test
    void anEnforcedConfiguredGateAppliesTheEvaluator() {
        var gate = gate(configured(true));
        assertThat(gate.status()).isEqualTo(RecordAuthorizationGate.Status.ENFORCED);
        assertThat(gate.decide(() -> archive.outcomeFor(archive.department), SyntheticArchive.AWARD_A_V1,
                List.of()).allowed()).isTrue();
        assertThat(gate.decide(() -> archive.outcomeFor(archive.department), SyntheticArchive.AWARD_UNRELATED,
                List.of()).allowed()).isFalse();
    }

    @Test
    void configuredRoleCodesAreNormalised() {
        assertThat(configured(true).policy()).get()
                .extracting(AuthorizationPolicy::researchStaffRoles)
                .isEqualTo(Set.of("PI", "MPI", "COI"));
    }
}
