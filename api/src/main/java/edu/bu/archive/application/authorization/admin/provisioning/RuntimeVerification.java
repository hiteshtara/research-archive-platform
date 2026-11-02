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

    /**
     * The shape ECS task metadata actually reports, confirmed against a live
     * DEV task on 2026-11-02:
     *
     * <pre>
     *   "Cluster": "arn:aws:ecs:us-east-1:770203350335:cluster/research-archive-platform-dev-etl"
     * </pre>
     *
     * <p>It is the full ARN, not the bare name. The first version of this
     * class matched the bare form only, so a real confirmed grant from an ECS
     * task was REFUSED - the dry run passed only because an unparseable
     * cluster merely warns when nothing is being written. Found by the first
     * live dry run, not by CI, because every fixture passed a bare name.
     *
     * <p>Parsed here rather than normalised away in
     * {@code EcsTaskMetadataRuntimeIdentity} on purpose: the ARN carries the
     * region and the account as well as the name, and those are two more
     * pieces of independent evidence. Stripping them in the adapter would
     * discard them before reaching the one class that makes trust decisions.
     */
    private static final Pattern CLUSTER_ARN = Pattern.compile(
            "^arn:(?<partition>aws[a-z0-9-]*):(?<service>[a-z0-9-]+):(?<region>[a-z0-9-]*)"
                    + ":(?<account>[0-9]*):(?<resourceType>[a-zA-Z0-9-]+)/(?<name>[^/]+)$");

    /**
     * Every resource in this project is in us-east-1 - the RDS instance, both
     * ECR repositories, the clusters and the Cognito pool. A cluster ARN in
     * another region means the task is not where it is supposed to be, which
     * is worth refusing rather than explaining.
     */
    private static final String EXPECTED_REGION = "us-east-1";

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

        Optional<String> runtimeAccount = runtime.awsAccountId();
        verifyAccount(runtimeAccount, statedAccount, willWrite, checks, problems);
        verifyCluster(runtime.clusterName(), runtimeAccount, statedEnvironment, willWrite,
                checks, problems);
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
            Optional<String> runtimeAccount,
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

        /*
         * The production sweep runs on the WHOLE raw value first, before any
         * parsing can fail. An ARN naming a production cluster must be refused
         * even if some other part of it is malformed, and refused outright
         * rather than merely left unverified - nothing executes against
         * production, not even a dry run.
         */
        if (hasProductionSegment(lower)) {
            problems.add("This utility refuses to run against production. Cluster " + raw
                    + " names production in one of its segments, whatever --environment claims.");
            return;
        }

        String clusterName;
        if (lower.startsWith("arn:")) {
            clusterName = clusterNameFromArn(raw, lower, runtimeAccount, checks, problems);
            if (clusterName == null) {
                return;      // the specific reason has already been recorded
            }
        } else {
            /*
             * A bare name. Supported because a RuntimeIdentity need not come
             * from ECS task metadata, and because it is the readable form in
             * tests. It carries NO account or region evidence, so it is held
             * to the name rules alone - which is why the ARN form is preferred
             * wherever it is available.
             */
            clusterName = lower;
        }

        verifyClusterName(raw, clusterName, statedEnvironment, willWrite, checks, problems);
    }

    /**
     * Validate a cluster ARN and return its cluster name, or null having
     * recorded why it was rejected.
     *
     * <p>A present-but-invalid ARN is refused OUTRIGHT, for a dry run as well
     * as a write. An absent cluster means "we do not know"; an ARN that is
     * malformed, points at another service, another resource type, another
     * account or another region means something is actively wrong about where
     * this process is running, which is a stronger signal than ignorance.
     */
    private static String clusterNameFromArn(
            String raw, String lower, Optional<String> runtimeAccount,
            List<String> checks, List<String> problems) {

        Matcher arn = CLUSTER_ARN.matcher(lower);
        if (!arn.matches()) {
            problems.add("cluster " + raw + " is not a well-formed ARN. Refusing: a runtime "
                    + "identity that cannot be parsed cannot be trusted.");
            return null;
        }

        if (!"ecs".equals(arn.group("service"))) {
            problems.add("cluster " + raw + " is not an ECS ARN (service '"
                    + arn.group("service") + "'). Refusing.");
            return null;
        }

        if (!"cluster".equals(arn.group("resourceType"))) {
            problems.add("cluster " + raw + " is an ECS '" + arn.group("resourceType")
                    + "' ARN, not a cluster ARN. Refusing.");
            return null;
        }

        /*
         * Cross-check against the account established from the TaskARN, NOT
         * against --aws-account-id. Two independent fields of the metadata
         * document have to agree; a flag is not consulted here at all.
         */
        String arnAccount = arn.group("account");
        if (runtimeAccount.isPresent() && !runtimeAccount.get().equals(arnAccount)) {
            problems.add("cluster " + raw + " belongs to account " + arnAccount
                    + " but this task runs in " + runtimeAccount.get()
                    + ". Refusing: the runtime contradicts itself.");
            return null;
        }

        String region = arn.group("region");
        if (!EXPECTED_REGION.equals(region)) {
            problems.add("cluster " + raw + " is in region '" + region + "', not "
                    + EXPECTED_REGION + ". Refusing.");
            return null;
        }

        checks.add("OK     cluster ARN is well formed, ECS, account " + arnAccount
                + " and region " + region + " - all agreeing with the task metadata");
        return arn.group("name");
    }

    /** The name rules, applied to a bare name or to one extracted from an ARN. */
    private static void verifyClusterName(
            String raw, String clusterName, String statedEnvironment,
            boolean willWrite, List<String> checks, List<String> problems) {

        Matcher matched = APPROVED_CLUSTER.matcher(clusterName);
        if (!matched.matches()) {
            if (willWrite) {
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
         * Belt and braces. hasProductionSegment already swept the raw value,
         * so this cannot normally fire - but the environment segment is the
         * authoritative place to decide, and a future change to the sweep
         * must not be able to let production through here.
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
                    + "the ECS cluster " + raw + " - not a substring of it");
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
