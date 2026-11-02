package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one implementation of "is this process really where the operator says
 * it is", shared by both operator utilities.
 *
 * <p>It is shared on purpose. {@code ArchiveAdminBootstrapCommand} and
 * {@code GrantProvisioningCommand} each carried their own copy of these
 * comparisons, which is how they came to disagree: the same gap had to be
 * found and closed twice, and a third utility would have started from
 * whichever copy its author happened to read. Both now call this, so
 * "consistent across both utilities" is a property of the code rather than a
 * claim about two files that happen to look alike today.
 *
 * <p><strong>Fail closed.</strong> A write is refused whenever the runtime
 * cannot be established. That is the whole point: the check this replaced
 * compared a flag against itself and therefore always passed.
 *
 * <p><strong>Both halves are required for a write, not just the account.</strong>
 * The first version verified the account and then wrapped every cluster check
 * in {@code if (cluster.isPresent())}, so a runtime that reported an account
 * but no cluster skipped the environment comparison AND the refusal to run
 * against production - silently, with an "OK AWS account confirmed" line
 * printed above it that made the run look verified. A real Fargate task
 * always reports {@code Cluster} alongside {@code TaskARN}, so requiring it
 * costs a correct invocation nothing and closes that hole.
 */
public final class RuntimeVerification {

    private RuntimeVerification() {
    }

    /**
     * Compare the operator's stated account and environment against what the
     * runtime actually reports, appending one line per check to {@code checks}
     * and one line per failure to {@code problems}.
     *
     * @param runtime           where the process really is; {@link RuntimeIdentity#UNKNOWN}
     *                          when nothing could be determined
     * @param statedAccount     the {@code --aws-account-id} flag, already trimmed, or null
     * @param statedEnvironment the {@code --environment} flag, already trimmed and
     *                          lower-cased, or null
     * @param willWrite         true when this invocation would write - confirmed and not a
     *                          dry run. An unverifiable runtime is a refusal for a write and
     *                          a warning for a dry run.
     */
    public static void verify(
            RuntimeIdentity runtime,
            String statedAccount,
            String statedEnvironment,
            boolean willWrite,
            List<String> checks,
            List<String> problems) {

        verifyAccount(runtime.awsAccountId(), statedAccount, willWrite, checks, problems);
        verifyCluster(runtime.clusterName(), statedEnvironment, willWrite, checks, problems);
    }

    private static void verifyAccount(
            Optional<String> actualAccount,
            String statedAccount,
            boolean willWrite,
            List<String> checks,
            List<String> problems) {

        if (actualAccount.isEmpty()) {
            if (willWrite) {
                problems.add("cannot determine the AWS account from the runtime "
                        + "(ECS_CONTAINER_METADATA_URI_V4 is absent or unreachable), so the "
                        + "stated account cannot be verified. Refusing to write. Run this as "
                        + "an ECS task; a dry run is still available.");
            } else {
                checks.add("WARN   runtime account unknown - a dry run outside ECS cannot "
                        + "verify where it is pointed");
            }
            return;
        }

        if (statedAccount == null) {
            // The missing flag is the caller's complaint to make; all this can
            // report is where the process actually is.
            checks.add("OK     runtime reports AWS account " + actualAccount.get());
        } else if (!actualAccount.get().equals(statedAccount)) {
            problems.add("account mismatch: --aws-account-id says " + statedAccount
                    + " but this task is running in " + actualAccount.get() + ". Refusing.");
        } else {
            checks.add("OK     AWS account " + actualAccount.get()
                    + " confirmed against the ECS task metadata, not just the flag");
        }
    }

    private static void verifyCluster(
            Optional<String> cluster,
            String statedEnvironment,
            boolean willWrite,
            List<String> checks,
            List<String> problems) {

        if (cluster.isEmpty()) {
            /*
             * Not merely "the environment is unconfirmed": with no cluster
             * name there is nothing that rules out a production cluster
             * either, and --environment cannot do it because a flag is what
             * is being checked. So a write refuses here even when the account
             * verified cleanly.
             */
            if (willWrite) {
                problems.add("cannot determine the ECS cluster from the runtime, so the "
                        + "stated environment cannot be verified and a production cluster "
                        + "cannot be ruled out. Refusing to write. Run this as an ECS task; "
                        + "a dry run is still available.");
            } else {
                checks.add("WARN   runtime cluster unknown - a dry run outside ECS can "
                        + "neither confirm the environment nor rule out production");
            }
            return;
        }

        String lowerCluster = cluster.get().toLowerCase(Locale.ROOT);

        /*
         * Checked before the environment comparison and regardless of
         * willWrite: a production cluster is refused outright, so not even a
         * dry run executes there.
         */
        if (lowerCluster.contains("prod")) {
            problems.add("This utility refuses to run against production. "
                    + "The cluster name says prod regardless of what --environment claims.");
        }

        if (statedEnvironment == null) {
            checks.add("OK     runtime reports cluster " + cluster.get());
        } else if (!lowerCluster.contains(statedEnvironment)) {
            problems.add("environment mismatch: --environment says " + statedEnvironment
                    + " but this task runs in cluster " + cluster.get() + ". Refusing.");
        } else {
            checks.add("OK     environment " + statedEnvironment
                    + " is consistent with cluster " + cluster.get());
        }
    }
}
