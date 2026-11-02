package edu.bu.archive.application.authorization.admin.provisioning;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><strong>The environment is matched EXACTLY, never as a substring.</strong>
 * The first version compared the stated environment against the cluster name
 * with {@code cluster.contains(stated)}. That let a deliberately short flag
 * pass vacuously: {@code --environment=e} against
 * {@code research-archive-platform-test-api} printed
 * "OK environment e is consistent with cluster ..." and raised no problem at
 * all. The account check was never fooled (it is an equality test) and the
 * production refusal was never fooled (it was independent of the flag), so
 * this was a weak-assurance bug rather than a privilege bypass - but a check
 * that prints OK without constraining anything is exactly the class of defect
 * this class exists to remove. The cluster name is now parsed into an
 * environment segment and compared with {@code equals}.
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

    /**
     * The only cluster shape this project accepts:
     * {@code research-archive-platform-<environment>-<component>}, where
     * neither trailing segment may itself contain a hyphen. The real DEV
     * clusters are {@code …-dev-api}, {@code …-dev-etl} and
     * {@code …-dev-dbtunnel}.
     *
     * <p>The project prefix is deliberately hard-coded rather than passed in.
     * A caller-supplied project name would be one more operator-influenced
     * input in a check whose whole purpose is to not trust operator input,
     * and this repository builds exactly one project. If the project is ever
     * renamed, this stops matching and the check fails CLOSED - a refusal to
     * write, which is the correct direction to fail in.
     */
    private static final Pattern APPROVED_CLUSTER = Pattern.compile(
            "^research-archive-platform-(?<environment>[a-z0-9]+)-(?<component>[a-z0-9]+)$");

    /** Environment segments that mean production, compared as whole segments. */
    private static final Set<String> PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "prd");

    /**
     * Environment segments this project knows about. Anything else is
     * "unrecognised", which fails closed rather than being creatively parsed -
     * a cluster called {@code wp-b-wp-cluster-beta-test} really does exist in
     * this AWS account, and guessing an environment out of it would be worse
     * than refusing.
     */
    private static final Set<String> RECOGNISED_ENVIRONMENTS =
            Set.of("dev", "test", "qa", "stage", "staging", "stg", "sandbox",
                   "prod", "production", "prd");

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

        String raw = cluster.get();
        String lower = raw.toLowerCase(Locale.ROOT);
        Matcher matched = APPROVED_CLUSTER.matcher(lower);

        if (!matched.matches()) {
            /*
             * An unrecognised cluster name yields no environment at all, so
             * there is nothing to compare and nothing ruling out production.
             *
             * The production sweep still runs, on whole hyphen-separated
             * segments: an unparseable name whose segments include a
             * production marker is refused outright rather than merely
             * left unverified, so it cannot even be dry-run against.
             */
            if (hasProductionSegment(lower)) {
                problems.add("This utility refuses to run against production. Cluster "
                        + raw + " does not match the approved "
                        + "research-archive-platform-<environment>-<component> shape, and one "
                        + "of its segments names production.");
            } else if (willWrite) {
                problems.add("cluster " + raw + " does not match the approved "
                        + "research-archive-platform-<environment>-<component> shape, so the "
                        + "environment cannot be identified and production cannot be ruled "
                        + "out. Refusing to write.");
            } else {
                checks.add("WARN   cluster " + raw + " is not an approved "
                        + "research-archive-platform cluster name - a dry run cannot confirm "
                        + "the environment from it");
            }
            return;
        }

        String actualEnvironment = matched.group("environment");

        /*
         * Checked before the comparison and regardless of willWrite: a
         * production cluster is refused outright, so not even a dry run
         * executes there. Matched as a WHOLE segment, which is both stricter
         * and more precise than the substring test this replaced - that one
         * would have fired on a cluster named "...-reproduction-..." and, far
         * worse, could be satisfied by a one-character --environment.
         */
        if (PRODUCTION_ENVIRONMENTS.contains(actualEnvironment)) {
            problems.add("This utility refuses to run against production. The cluster's "
                    + "environment segment is '" + actualEnvironment + "' (cluster " + raw
                    + ") regardless of what --environment claims.");
            return;
        }

        if (!RECOGNISED_ENVIRONMENTS.contains(actualEnvironment)) {
            if (willWrite) {
                problems.add("cluster " + raw + " names an unrecognised environment '"
                        + actualEnvironment + "', so it cannot be confirmed as a "
                        + "non-production environment. Refusing to write.");
            } else {
                checks.add("WARN   cluster " + raw + " names an unrecognised environment '"
                        + actualEnvironment + "' - a dry run cannot confirm it");
            }
            return;
        }

        if (statedEnvironment == null) {
            // The missing flag is the caller's complaint to make.
            checks.add("OK     runtime reports cluster " + raw + ", environment '"
                    + actualEnvironment + "'");
        } else if (!actualEnvironment.equals(statedEnvironment)) {
            problems.add("environment mismatch: --environment says '" + statedEnvironment
                    + "' but this task runs in cluster " + raw + ", whose environment is '"
                    + actualEnvironment + "'. Refusing.");
        } else {
            checks.add("OK     environment '" + actualEnvironment + "' matched EXACTLY against "
                    + "the ECS cluster name " + raw + " - not a substring of it");
        }
    }

    /**
     * True when any whole hyphen-separated segment names production.
     *
     * <p>Segment-wise on purpose. {@code contains("prod")} would also fire on
     * "reproduction", and - the reason this class was changed - a substring
     * test in the opposite direction let a one-character {@code --environment}
     * match any cluster name at all.
     */
    private static boolean hasProductionSegment(String lowerCluster) {
        for (String segment : lowerCluster.split("-")) {
            if (PRODUCTION_ENVIRONMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
