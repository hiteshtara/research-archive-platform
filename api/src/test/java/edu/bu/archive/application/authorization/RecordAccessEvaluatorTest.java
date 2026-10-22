package edu.bu.archive.application.authorization;

import static edu.bu.archive.application.authorization.fixtures.SyntheticArchive.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import edu.bu.archive.application.authorization.fixtures.SyntheticArchive;

/**
 * Fixture-based authorization verified across every module. The policy
 * strategies are exercised independently; none is an approved default.
 */
class RecordAccessEvaluatorTest {

    private static final Set<String> PI_MPI_COI = Set.of("PI", "MPI", "COI");

    private final SyntheticArchive archive = new SyntheticArchive();

    private AccessDecision decide(AuthorizationPolicy policy, ValidatedCognitoIdentity who, RecordFacts record,
                                  List<RecordFacts> family) {
        return new RecordAccessEvaluator(policy, archive.units).decide(archive.outcomeFor(who), record, family);
    }

    private static AuthorizationPolicy policy(AuthorizationPolicy.VersionScope versions,
                                              AuthorizationPolicy.DepartmentMatch match) {
        return new AuthorizationPolicy(versions, match, PI_MPI_COI);
    }

    private static final AuthorizationPolicy PER_VERSION_EXACT =
            policy(AuthorizationPolicy.VersionScope.PER_VERSION, AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);

    private boolean allowed(ValidatedCognitoIdentity who, RecordFacts record) {
        return decide(PER_VERSION_EXACT, who, record, List.of(record)).allowed();
    }

    @Test
    void centralReachesEveryModule() {
        for (RecordFacts record : List.of(AWARD_UNRELATED, PROPOSAL_RELATED_OTHER_UNIT, NEGOTIATION_NO_UNIT, SUBAWARD, IRB)) {
            assertThat(allowed(archive.central, record)).as(record.versionKey()).isTrue();
        }
    }

    @Test
    void departmentSeesItsUnitsAwardsProposalsAndNegotiationsOnly() {
        assertThat(allowed(archive.department, AWARD_A_V1)).isTrue();
        assertThat(allowed(archive.department, PROPOSAL_DEPT)).isTrue();
        assertThat(allowed(archive.department, NEGOTIATION_DEPT)).isTrue();

        assertThat(allowed(archive.department, AWARD_UNRELATED)).isFalse();
        assertThat(allowed(archive.department, PROPOSAL_RELATED_OTHER_UNIT)).isFalse();
        assertThat(allowed(archive.department, NEGOTIATION_NO_UNIT)).isFalse();
        // P8: Subaward and IRB have no approved department rule.
        assertThat(allowed(archive.department, SUBAWARD)).isFalse();
        assertThat(allowed(archive.department, IRB)).isFalse();
    }

    @Test
    void researchStaffSeesOnlyRecordsWhereListedInAQualifyingRole() {
        assertThat(allowed(archive.researchStaff, AWARD_B_V2)).isTrue();      // PI
        assertThat(allowed(archive.researchStaff, PROPOSAL_MPI)).isTrue();    // MPI

        assertThat(allowed(archive.researchStaff, AWARD_KP_ONLY)).isFalse();  // KP not in the configured roles
        assertThat(allowed(archive.researchStaff, AWARD_UNRELATED)).isFalse();
        // Listed on these, but no approved contact rule for these modules (P8).
        assertThat(allowed(archive.researchStaff, NEGOTIATION_DEPT)).isFalse();
        assertThat(allowed(archive.researchStaff, SUBAWARD)).isFalse();
        assertThat(allowed(archive.researchStaff, IRB)).isFalse();
    }

    @Test
    void nonEmployeeContactsNeverMatchAnyone() {
        assertThat(AWARD_B_V1.contacts()).allMatch(contact -> !contact.employee());
        assertThat(allowed(archive.researchStaff, AWARD_B_V1)).isFalse();
    }

    @Test
    void qualifyingRolesComeFromThePolicyNotTheCode() {
        var withKeyPersons = new AuthorizationPolicy(AuthorizationPolicy.VersionScope.PER_VERSION,
                AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT, Set.of("PI", "MPI", "COI", "KP"));
        assertThat(decide(withKeyPersons, archive.researchStaff, AWARD_KP_ONLY, List.of(AWARD_KP_ONLY)).allowed())
                .isTrue();
    }

    @Test
    void authorizedViewerSeesOnlyTheIoBearingVersion() {
        assertThat(allowed(archive.authorizedViewer, AWARD_IO_V1)).isTrue();
        assertThat(allowed(archive.authorizedViewer, AWARD_IO_V2)).isFalse();
        assertThat(allowed(archive.authorizedViewer, AWARD_UNRELATED)).isFalse();
    }

    @Test
    void theRealIoResolverIsDisabledSoIoGrantsReachNoRealRecord() {
        var resolver = new DisabledIoResolver();
        assertThat(resolver.ioValuesFor(RecordModule.AWARD, "SYN-AWD-D-1")).isEmpty();
        var asLoadedForReal = award("SYN-AWD-D-1", "SYN-AWD-D", "SYN-U-300", List.of(),
                resolver.ioValuesFor(RecordModule.AWARD, "SYN-AWD-D-1"));
        assertThat(allowed(archive.authorizedViewer, asLoadedForReal)).isFalse();
    }

    @Test
    void multipleGrantsUnionAndEachReasonIsReported() {
        assertThat(allowed(archive.multi, AWARD_U200)).isTrue();
        assertThat(allowed(archive.multi, AWARD_IO_5)).isTrue();
        assertThat(allowed(archive.multi, AWARD_A_V1)).isFalse();
        assertThat(decide(PER_VERSION_EXACT, archive.multi, AWARD_U200, List.of(AWARD_U200)).reasons())
                .containsExactly(AccessReason.DEPARTMENT);
    }

    @Test
    void aRelationshipToAnAccessibleRecordNeverAuthorizesTheRelatedRecord() {
        // The department can see AWARD_A, but the related proposal is in another unit.
        assertThat(allowed(archive.department, AWARD_A_V1)).isTrue();
        assertThat(allowed(archive.department, PROPOSAL_RELATED_OTHER_UNIT)).isFalse();
    }

    @Test
    void noRecordAccessForUnprovisionedOrDeniedIdentities() {
        for (ValidatedCognitoIdentity who : List.of(archive.noGrants, archive.unmapped, archive.ambiguousA,
                archive.revoked, archive.suspended, archive.reassignedNewHolder)) {
            assertThat(allowed(who, AWARD_A_V1)).isFalse();
            assertThat(allowed(who, AWARD_UNRELATED)).isFalse();
        }
        assertThat(decide(PER_VERSION_EXACT, archive.unmapped, AWARD_A_V1, List.of()).denialCode())
                .isEqualTo("ACCESS_NOT_PROVISIONED");
        assertThat(decide(PER_VERSION_EXACT, archive.suspended, AWARD_A_V1, List.of()).denialCode())
                .isEqualTo("SUSPENDED_IDENTITY");
    }

    @Nested
    class VersionScopeStrategies {

        private final List<RecordFacts> familyB = List.of(AWARD_B_V1, AWARD_B_V2);

        @Test
        void perVersionUsesOnlyTheVersionBeingRead() {
            // Research staff is PI on v2 only; department unit is v1's.
            assertThat(decide(PER_VERSION_EXACT, archive.researchStaff, AWARD_B_V1, familyB).allowed()).isFalse();
            assertThat(decide(PER_VERSION_EXACT, archive.department, AWARD_B_V2, familyB).allowed()).isFalse();
        }

        @Test
        void familyWideReachesEveryVersionOfTheSameFamilyOnly() {
            var familyWide = policy(AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                    AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
            assertThat(decide(familyWide, archive.researchStaff, AWARD_B_V1, familyB).allowed()).isTrue();
            assertThat(decide(familyWide, archive.department, AWARD_B_V2, familyB).allowed()).isTrue();
        }

        @Test
        void familyWideIgnoresVersionsSmuggledInFromAnotherFamily() {
            var familyWide = policy(AuthorizationPolicy.VersionScope.FAMILY_WIDE,
                    AuthorizationPolicy.DepartmentMatch.EXACT_LEAD_UNIT);
            assertThat(decide(familyWide, archive.department, AWARD_UNRELATED,
                    List.of(AWARD_UNRELATED, AWARD_A_V1)).allowed()).isFalse();
        }
    }

    @Nested
    class DepartmentMatchStrategies {

        @Test
        void exactLeadUnitExcludesChildUnits() {
            assertThat(allowed(archive.department, AWARD_CHILD_UNIT)).isFalse();
        }

        @Test
        void descendantsAreIncludedOnlyWhenBothStrategyAndGrantAskForThem() {
            var withDescendants = policy(AuthorizationPolicy.VersionScope.PER_VERSION,
                    AuthorizationPolicy.DepartmentMatch.LEAD_UNIT_WITH_DESCENDANTS);
            // Department's grant has includeDescendants=true.
            assertThat(decide(withDescendants, archive.department, AWARD_CHILD_UNIT, List.of()).allowed()).isTrue();
            // Multi's SYN-U-200 grant does not ask for descendants.
            var childOf200 = award("SYN-AWD-G-1", "SYN-AWD-G", "SYN-U-210", List.of(), Set.of());
            assertThat(decide(withDescendants, archive.multi, childOf200, List.of()).allowed()).isFalse();
        }
    }
}
