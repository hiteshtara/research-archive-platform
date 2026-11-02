package edu.bu.archive.application.authorization.admin.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared runtime check, tested directly rather than through either CLI.
 *
 * <p>It is pure logic over a {@link RuntimeIdentity}, so it needs no database
 * and belongs in the unit suite - which also means these cases run on every
 * push, not only in the Testcontainers job.
 */
@DisplayName("RuntimeVerification")
class RuntimeVerificationTest {

    private static final String ACCOUNT = "770203350335";
    private static final String DEV_CLUSTER = "research-archive-platform-dev-api";

    /** Outcome of one verification: what it approved and what it refused. */
    private record Result(List<String> checks, List<String> problems) {
        boolean refused() {
            return !problems.isEmpty();
        }

        String allProblems() {
            return String.join(" | ", problems);
        }

        String allChecks() {
            return String.join(" | ", checks);
        }
    }

    private static Result verify(String statedAccount, String statedEnvironment,
                                 String actualAccount, String actualCluster,
                                 boolean willWrite) {
        List<String> checks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        RuntimeVerification.verify(
                runtime(actualAccount, actualCluster),
                statedAccount, statedEnvironment, willWrite, checks, problems);
        return new Result(checks, problems);
    }

    private static RuntimeIdentity runtime(String account, String cluster) {
        return new RuntimeIdentity() {
            @Override
            public Optional<String> awsAccountId() {
                return Optional.ofNullable(account);
            }

            @Override
            public Optional<String> clusterName() {
                return Optional.ofNullable(cluster);
            }
        };
    }

    /**
     * The real value ECS task metadata reports, captured from a live DEV task
     * on 2026-11-02. Every pre-existing fixture used the bare name, which is
     * exactly why the ARN defect reached production code.
     */
    private static final String DEV_CLUSTER_ARN =
            "arn:aws:ecs:us-east-1:770203350335:cluster/research-archive-platform-dev-api";

    @Nested
    @DisplayName("The real ECS cluster ARN form")
    class ClusterArn {

        @Test
        void theActualDevArnIsAcceptedForAConfirmedWrite() {
            Result r = verify(ACCOUNT, "dev", ACCOUNT, DEV_CLUSTER_ARN, true);
            assertThat(r.refused())
                    .as("a confirmed grant from a real DEV ECS task must be possible")
                    .isFalse();
            assertThat(r.allChecks())
                    .contains("cluster ARN is well formed, ECS, account 770203350335 "
                            + "and region us-east-1")
                    .contains("matched EXACTLY");
        }

        @Test
        void everyRealDevClusterArnIsAccepted() {
            for (String name : List.of("research-archive-platform-dev-api",
                                       "research-archive-platform-dev-etl",
                                       "research-archive-platform-dev-dbtunnel")) {
                String arn = "arn:aws:ecs:us-east-1:770203350335:cluster/" + name;
                assertThat(verify(ACCOUNT, "dev", ACCOUNT, arn, true).refused())
                        .as("ARN for %s", name).isFalse();
            }
        }

        @Test
        void anArnFromAnotherAccountIsRefusedEvenIfTheNameLooksRight() {
            // The ARN account is cross-checked against the TaskARN account,
            // never against the flag.
            String arn = "arn:aws:ecs:us-east-1:999999999999:cluster/"
                    + "research-archive-platform-dev-api";
            Result r = verify(ACCOUNT, "dev", ACCOUNT, arn, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems())
                    .contains("belongs to account 999999999999")
                    .contains("the runtime contradicts itself");
        }

        @Test
        void anArnFromAnotherRegionIsRefused() {
            String arn = "arn:aws:ecs:us-west-2:770203350335:cluster/"
                    + "research-archive-platform-dev-api";
            Result r = verify(ACCOUNT, "dev", ACCOUNT, arn, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("is in region 'us-west-2', not us-east-1");
        }

        @Test
        void aNonEcsArnIsRefused() {
            String arn = "arn:aws:eks:us-east-1:770203350335:cluster/"
                    + "research-archive-platform-dev-api";
            Result r = verify(ACCOUNT, "dev", ACCOUNT, arn, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("is not an ECS ARN");
        }

        @Test
        void anEcsArnForTheWrongResourceTypeIsRefused() {
            // A task ARN, not a cluster ARN - the adjacent metadata field.
            String arn = "arn:aws:ecs:us-east-1:770203350335:task/"
                    + "research-archive-platform-dev-api";
            Result r = verify(ACCOUNT, "dev", ACCOUNT, arn, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("not a cluster ARN");
        }

        @Test
        void aMalformedArnIsRefused() {
            for (String bad : List.of("arn:aws:ecs:us-east-1:770203350335:cluster",
                                      "arn:aws:ecs:us-east-1:cluster/x-dev-api",
                                      "arn:",
                                      "arn:aws:ecs:us-east-1:770203350335:cluster/")) {
                Result r = verify(ACCOUNT, "dev", ACCOUNT, bad, true);
                assertThat(r.refused()).as("malformed: %s", bad).isTrue();
            }
        }

        @Test
        void aMalformedArnIsRefusedForADryRunToo() {
            // Present-but-invalid is a stronger signal than absent: something
            // is actively wrong about where this is running.
            Result r = verify(ACCOUNT, "dev", ACCOUNT, "arn:aws:ecs:bogus", false);
            assertThat(r.refused()).isTrue();
        }

        @Test
        void aProductionArnIsRefusedEvenForADryRun() {
            for (String env : List.of("prod", "production", "prd")) {
                String arn = "arn:aws:ecs:us-east-1:770203350335:cluster/"
                        + "research-archive-platform-" + env + "-api";
                Result r = verify(ACCOUNT, "dev", ACCOUNT, arn, false);
                assertThat(r.refused()).as("environment %s", env).isTrue();
                assertThat(r.allProblems()).contains("refuses to run against production");
            }
        }

        @Test
        void anArnFromAnotherProjectIsRefused() {
            for (String name : List.of("wp-b-wp-cluster-beta-test",
                                       "phpbin-test-ecscluster",
                                       "kuali-ecs-stg-cluster")) {
                String arn = "arn:aws:ecs:us-east-1:770203350335:cluster/" + name;
                assertThat(verify(ACCOUNT, "dev", ACCOUNT, arn, true).refused())
                        .as("other project: %s", name).isTrue();
            }
        }

        @Test
        void aShortEnvironmentStillCannotPassAgainstAnArn() {
            String arn = "arn:aws:ecs:us-east-1:770203350335:cluster/"
                    + "research-archive-platform-test-api";
            Result r = verify(ACCOUNT, "e", ACCOUNT, arn, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("environment mismatch");
        }

        @Test
        void bareNamesStillWork() {
            // Compatibility: a RuntimeIdentity need not come from ECS metadata.
            assertThat(verify(ACCOUNT, "dev", ACCOUNT, DEV_CLUSTER, true).refused()).isFalse();
        }

        @Test
        void anArnWithNoRuntimeAccountIsStillRefusedForAWrite() {
            // The account half is unknown, so the write fails closed even
            // though the cluster ARN itself parses.
            Result r = verify(ACCOUNT, "dev", null, DEV_CLUSTER_ARN, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("cannot determine the AWS account");
        }
    }

    /** A correct DEV write: the baseline everything else deviates from. */
    @Test
    void aMatchingDevRuntimeIsAccepted() {
        Result r = verify(ACCOUNT, "dev", ACCOUNT, DEV_CLUSTER, true);
        assertThat(r.refused()).isFalse();
        assertThat(r.allChecks())
                .contains("confirmed against the ECS task metadata")
                .contains("matched EXACTLY");
    }

    @Test
    void everyRealDevClusterIsAccepted() {
        for (String cluster : List.of("research-archive-platform-dev-api",
                                      "research-archive-platform-dev-etl",
                                      "research-archive-platform-dev-dbtunnel")) {
            Result r = verify(ACCOUNT, "dev", ACCOUNT, cluster, true);
            assertThat(r.refused()).as("cluster %s must be accepted", cluster).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // The defect this class was changed for.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Short environment strings cannot pass by substring")
    class ShortEnvironmentStrings {

        /**
         * The exact regression. Before the fix this returned NO problems and
         * printed "OK environment e is consistent with cluster
         * research-archive-platform-test-api", because the check was
         * cluster.contains("e").
         */
        @Test
        void aOneCharacterEnvironmentAgainstAnotherEnvironmentIsRefused() {
            Result r = verify(ACCOUNT, "e", ACCOUNT, "research-archive-platform-test-api", true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems())
                    .contains("environment mismatch")
                    .contains("'e'")
                    .contains("'test'");
            assertThat(r.allChecks()).doesNotContain("matched EXACTLY");
        }

        @Test
        void aOneCharacterEnvironmentAgainstTheDevClusterIsAlsoRefused() {
            // 'd' is a substring of "dev" but is not the environment.
            Result r = verify(ACCOUNT, "d", ACCOUNT, DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("environment mismatch");
        }

        @Test
        void aPrefixOfTheRealEnvironmentIsRefused() {
            Result r = verify(ACCOUNT, "de", ACCOUNT, DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("environment mismatch");
        }

        @Test
        void aSupersetOfTheRealEnvironmentIsRefused() {
            Result r = verify(ACCOUNT, "development", ACCOUNT, DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("environment mismatch");
        }

        @Test
        void aOneCharacterEnvironmentCannotEvenDryRunAgainstProduction() {
            // Before the fix this printed an OK line; the production refusal
            // fired independently, which is why this was never a bypass.
            Result r = verify(ACCOUNT, "o", ACCOUNT, "research-archive-platform-prod-api", false);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("refuses to run against production");
            assertThat(r.allChecks()).doesNotContain("matched EXACTLY");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Misleading cluster names")
    class MisleadingClusterNames {

        @Test
        void aClusterFromAnotherProjectIsNotApproved() {
            // These really exist in this AWS account.
            for (String cluster : List.of("wp-b-wp-cluster-beta-test",
                                          "phpbin-test-ecscluster",
                                          "kuali-ecs-stg-cluster")) {
                Result r = verify(ACCOUNT, "dev", ACCOUNT, cluster, true);
                assertThat(r.refused()).as("cluster %s must not be approved", cluster).isTrue();
            }
        }

        @Test
        void aClusterNameThatMerelyContainsTheEnvironmentIsRefused() {
            // "dev" appears, but not as the environment segment.
            Result r = verify(ACCOUNT, "dev", ACCOUNT,
                    "research-archive-platform-prod-devtools", true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("refuses to run against production");
        }

        @Test
        void anEnvironmentLikeSegmentInTheWrongPositionIsRefused() {
            Result r = verify(ACCOUNT, "dev", ACCOUNT,
                    "research-archive-platform-dev-api-shadow", true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("does not match the approved");
        }

        @Test
        void reproductionIsNotProduction() {
            // The replaced substring test would have refused this wrongly.
            // It is still refused - as unrecognised, not as production.
            Result r = verify(ACCOUNT, "reproduction", ACCOUNT,
                    "research-archive-platform-reproduction-api", true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems())
                    .contains("unrecognised environment")
                    .doesNotContain("refuses to run against production");
        }

        @Test
        void anUnrecognisedEnvironmentFailsClosedForAWriteAndOnlyWarnsForADryRun() {
            String cluster = "research-archive-platform-banana-api";
            assertThat(verify(ACCOUNT, "banana", ACCOUNT, cluster, true).refused()).isTrue();

            Result dryRun = verify(ACCOUNT, "banana", ACCOUNT, cluster, false);
            assertThat(dryRun.refused()).isFalse();
            assertThat(dryRun.allChecks()).contains("unrecognised environment");
        }

        @Test
        void aProjectRenameFailsClosedRatherThanPassing() {
            Result r = verify(ACCOUNT, "dev", ACCOUNT, "research-archive-dev-api", true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("does not match the approved");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Production is refused, however it is labelled")
    class Production {

        @Test
        void everyProductionSegmentIsRefusedEvenWithDevFlags() {
            for (String env : List.of("prod", "production", "prd")) {
                Result r = verify(ACCOUNT, "dev", ACCOUNT,
                        "research-archive-platform-" + env + "-api", true);
                assertThat(r.refused()).as("environment %s", env).isTrue();
                assertThat(r.allProblems()).contains("refuses to run against production");
            }
        }

        @Test
        void aProductionClusterIsRefusedForADryRunToo() {
            Result r = verify(ACCOUNT, "prod", ACCOUNT,
                    "research-archive-platform-prod-etl", false);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("refuses to run against production");
        }

        @Test
        void anUnapprovedClusterNamingProductionIsRefusedOutright() {
            // Unparseable, so no environment can be extracted - but a segment
            // says production, so it must not even be dry-run against.
            Result r = verify(ACCOUNT, "dev", ACCOUNT, "some-prod-thing-over-here", false);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("refuses to run against production");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Missing metadata fails closed for a write")
    class MissingMetadata {

        @Test
        void nothingKnownAtAllRefusesAConfirmedWrite() {
            List<String> checks = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            RuntimeVerification.verify(RuntimeIdentity.UNKNOWN, ACCOUNT, "dev", true,
                    checks, problems);
            assertThat(problems).hasSize(2);
            assertThat(String.join(" | ", problems))
                    .contains("cannot determine the AWS account from the runtime")
                    .contains("cannot determine the ECS cluster from the runtime");
        }

        @Test
        void nothingKnownAtAllAllowsADryRunWithWarnings() {
            List<String> checks = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            RuntimeVerification.verify(RuntimeIdentity.UNKNOWN, ACCOUNT, "dev", false,
                    checks, problems);
            assertThat(problems).isEmpty();
            assertThat(String.join(" | ", checks))
                    .contains("runtime account unknown")
                    .contains("runtime cluster unknown");
        }

        @Test
        void aVerifiedAccountWithNoClusterStillRefusesAWrite() {
            Result r = verify(ACCOUNT, "dev", ACCOUNT, null, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("cannot determine the ECS cluster");
        }

        @Test
        void aVerifiedClusterWithNoAccountStillRefusesAWrite() {
            Result r = verify(ACCOUNT, "dev", null, DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("cannot determine the AWS account");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("The account check is an equality test and cannot be spoofed")
    class Account {

        @Test
        void aDifferentActualAccountIsRefused() {
            Result r = verify(ACCOUNT, "dev", "999999999999", DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("account mismatch");
        }

        @Test
        void anAccountPrefixIsNotAMatch() {
            Result r = verify("7702", "dev", ACCOUNT, DEV_CLUSTER, true);
            assertThat(r.refused()).isTrue();
            assertThat(r.allProblems()).contains("account mismatch");
        }

        @Test
        void aMissingAccountFlagReportsTheRuntimeWithoutClaimingAMatch() {
            Result r = verify(null, "dev", ACCOUNT, DEV_CLUSTER, true);
            assertThat(r.refused()).isFalse();
            assertThat(r.allChecks())
                    .contains("runtime reports AWS account " + ACCOUNT)
                    .doesNotContain("confirmed against the ECS task metadata");
        }
    }
}
