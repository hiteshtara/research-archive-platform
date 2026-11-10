package edu.bu.archive.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the committed one-off operator task definitions.
 *
 * <p><strong>Why this test exists.</strong> The first real DEV dry run failed
 * because the hand-written task definition omitted
 * {@code ARCHIVE_DOCUMENTS_BUCKET}: {@code S3AwardAttachmentStorage} refuses to
 * construct without it, so the Spring context never started and the CLI never
 * ran. The same attempt also set {@code SPRING_PROFILES_ACTIVE=grant-admin},
 * REPLACING {@code aws} rather than adding to it, which silently drops the
 * deployment profile. Both are the kind of omission a reviewer reads straight
 * past, so they are asserted here instead.
 *
 * <p>Every structural rule is applied to EVERY operator task definition, by
 * parameterising over the directory rather than naming one file. A second
 * utility arrived (identity linking) and the alternative was a copy of this
 * class per family, which is how one of them ends up unguarded.
 *
 * <p>This checks the shape of the committed files. It cannot see a NEW
 * variable added to the deployed API later -
 * {@code scripts/verify-grant-admin-taskdef.sh} diffs against the live API
 * task definition for that.
 */
@DisplayName("The committed operator task definitions")
class OperatorTaskDefinitionTest {

    /** One committed task definition, with the profile it is expected to add. */
    record Operator(String file, String family, String profile, String container) {
        @Override
        public String toString() {
            return profile;
        }
    }

    static Stream<Operator> operators() {
        return Stream.of(
                new Operator("ops/grant-admin/dev-grant-admin.taskdef.json",
                        "research-archive-platform-dev-grant-admin", "grant-admin", "grant-admin"),
                new Operator("ops/grant-admin/dev-link-identity.taskdef.json",
                        "research-archive-platform-dev-link-identity", "link-identity",
                        "link-identity"));
    }

    // ── the whole point of a separate family ─────────────────────────

    @ParameterizedTest
    @MethodSource("operators")
    void itRunsInItsOwnFamilySoTheApiServiceIsNeverTouched(Operator operator) {
        assertThat(taskDefinition(operator).get("family").asText()).isEqualTo(operator.family());
    }

    @Test
    void eachUtilityHasItsOwnFamilyAndContainerName() {
        /*
         * Two definitions sharing a family would make each registration a new
         * revision of the other, so "run the grant utility" could start the
         * linking one. The container name is what the log stream is keyed on.
         */
        List<Operator> all = operators().toList();
        assertThat(all).hasSizeGreaterThan(1);
        assertThat(all.stream().map(Operator::family).distinct().count()).isEqualTo(all.size());
        assertThat(all.stream().map(Operator::container).distinct().count()).isEqualTo(all.size());
        for (Operator operator : all) {
            assertThat(container(operator).get("name").asText()).isEqualTo(operator.container());
        }
    }

    // ── the image ────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("operators")
    void theImageIsPinnedByDigestNotByTag(Operator operator) {
        assertThat(container(operator).get("image").asText())
                .as("both ECR repositories are MUTABLE, so a tag can be repointed")
                .contains("@sha256:")
                .doesNotContain(":latest");
    }

    /**
     * The digest must be the image that was actually verified as deployed.
     *
     * <p>This is the check that was missing. The digest was pinned correctly -
     * by digest, never by tag - and then maintained by hand, bumped once per
     * release with a commit saying "point at the post-#103 image". Five
     * releases later it still said post-#103 while the API ran revision 87, so
     * an operator running the utility would have run months-old CLI code
     * against today's database, and nothing would have said so.
     *
     * <p>A hand-maintained field that has to be remembered is a field that
     * will be forgotten, so it is derived instead: the one place the deployed
     * digest is recorded and verified each release is the readiness snapshot,
     * and these definitions must agree with it. Deploying a new image now
     * fails this test until the definitions are bumped too, which is the
     * intended coupling - the utilities must run the same code as the API.
     */
    @ParameterizedTest
    @MethodSource("operators")
    void theImageIsTheVerifiedDeployedImage(Operator operator) throws Exception {
        JsonNode readiness = new ObjectMapper().readTree(
                Files.readString(locate("ui/src/features/qa/securityReadiness.json")));
        String deployed = readiness.get("deployment").get("imageDigest").asText();

        assertThat(deployed).startsWith("sha256:");
        assertThat(container(operator).get("image").asText())
                .as("the utility must run the same image the API was verified on (%s)",
                        readiness.get("deployment").get("apiTaskDefinition").asText())
                .endsWith("@" + deployed);
    }

    @ParameterizedTest
    @MethodSource("operators")
    void theImageIsTheApiRepositoryNotSomeOtherOne(Operator operator) {
        assertThat(container(operator).get("image").asText())
                .startsWith("770203350335.dkr.ecr.us-east-1.amazonaws.com/"
                        + "research-archive-platform-dev-api@");
    }

    @ParameterizedTest
    @MethodSource("operators")
    void theArchitectureIsDeclaredExplicitly(Operator operator) {
        JsonNode platform = taskDefinition(operator).get("runtimePlatform");
        assertThat(platform).isNotNull();
        assertThat(platform.get("cpuArchitecture").asText()).isEqualTo("X86_64");
        assertThat(platform.get("operatingSystemFamily").asText()).isEqualTo("LINUX");
    }

    // ── startup ──────────────────────────────────────────────────────

    /** The omission that made the first dry run fail before the CLI ran. */
    @ParameterizedTest
    @MethodSource("operators")
    void everyEnvironmentVariableTheApiNeedsToStartIsPresent(Operator operator) {
        List<String> missing = new ArrayList<>();
        for (String required : List.of(
                "ARCHIVE_DOCUMENTS_BUCKET",
                "COGNITO_ISSUER_URI",
                "COGNITO_CLIENT_ID",
                "APP_CORS_ALLOWED_ORIGINS",
                "APP_EXPLORER_ENABLED",
                "APP_SEARCH_SEMANTIC_ENABLED",
                "APP_SEARCH_TIMING_ENABLED",
                "SPRING_PROFILES_ACTIVE",
                "SPRING_MAIN_WEB_APPLICATION_TYPE")) {
            if (environment(operator, required) == null) {
                missing.add(required);
            }
        }
        assertThat(missing)
                .as("a missing variable fails at Spring startup, before the CLI runs")
                .isEmpty();
    }

    /** The second mistake: replacing the deployment profile instead of adding to it. */
    @ParameterizedTest
    @MethodSource("operators")
    void theUtilityProfileIsAddedToAwsRatherThanReplacingIt(Operator operator) {
        List<String> active = List.of(environment(operator, "SPRING_PROFILES_ACTIVE").split(","));

        assertThat(active).contains("aws", operator.profile());
        assertThat(active.indexOf("aws"))
                .as("aws must come first, as it does on the deployed API")
                .isZero();
    }

    @ParameterizedTest
    @MethodSource("operators")
    void noOtherUtilityProfileIsActive(Operator operator) {
        /*
         * Each definition runs exactly one utility. Both runners are
         * ApplicationRunners, so two active profiles would run both in one
         * task - and the second would act on whatever the first had just
         * written.
         */
        List<String> active = List.of(environment(operator, "SPRING_PROFILES_ACTIVE").split(","));
        List<String> others = operators()
                .map(Operator::profile)
                .filter(p -> !p.equals(operator.profile()))
                .toList();

        assertThat(active).doesNotContainAnyElementsOf(others);
        assertThat(active)
                .as("the admin bootstrap is a separate, one-time procedure")
                .doesNotContain("bootstrap-admin");
    }

    @ParameterizedTest
    @MethodSource("operators")
    void thereIsNoHttpListener(Operator operator) {
        assertThat(environment(operator, "SPRING_MAIN_WEB_APPLICATION_TYPE")).isEqualTo("none");
        assertThat(container(operator).has("portMappings"))
                .as("a port mapping would make this registrable behind the ALB")
                .isFalse();
    }

    // ── enforcement is not switched on by a utility ──────────────────

    @ParameterizedTest
    @MethodSource("operators")
    void noAuthorizationSettingIsOverridden(Operator operator) {
        /*
         * These utilities provision the inputs to enforcement; they must not
         * be the thing that turns it on, or alters the policy a later
         * acceptance test is judging. Enabling enforcement is a separate,
         * approved change to the API service.
         */
        for (JsonNode variable : container(operator).get("environment")) {
            assertThat(variable.get("name").asText())
                    .as("a utility task definition must not carry authorization policy")
                    .doesNotStartWith("APP_AUTHORIZATION_");
        }
    }

    // ── credentials ──────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("operators")
    void theExistingArchiveApiSecretsAreUsedUnchanged(Operator operator) {
        List<String> names = new ArrayList<>();
        for (JsonNode secret : container(operator).get("secrets")) {
            names.add(secret.get("name").asText());
            assertThat(secret.get("valueFrom").asText())
                    .as("the API's own secret, referenced by ARN and JSON key")
                    .contains("research-archive-platform/dev/postgres-api-");
        }
        assertThat(names).containsExactlyInAnyOrder(
                "POSTGRES_DB", "POSTGRES_HOST", "POSTGRES_PASSWORD",
                "POSTGRES_PORT", "POSTGRES_USER");
    }

    @ParameterizedTest
    @MethodSource("operators")
    void noCredentialIsInlined(Operator operator) {
        assertThat(taskDefinition(operator).toString())
                .as("credentials come from Secrets Manager, never from the definition")
                .doesNotContain("POSTGRES_PASSWORD\":\"")
                .doesNotContainPattern("(AKIA|ASIA)[0-9A-Z]{16}");
        for (JsonNode variable : container(operator).get("environment")) {
            assertThat(variable.get("name").asText())
                    .as("no credential-shaped plain environment variable")
                    .doesNotContain("PASSWORD")
                    .doesNotContain("SECRET_KEY");
        }
    }

    @ParameterizedTest
    @MethodSource("operators")
    void theExistingApiIamRolesAreReused(Operator operator) {
        assertThat(taskDefinition(operator).get("taskRoleArn").asText())
                .isEqualTo("arn:aws:iam::770203350335:role/"
                        + "research-archive-platform-dev-api-task-role");
        assertThat(taskDefinition(operator).get("executionRoleArn").asText())
                .isEqualTo("arn:aws:iam::770203350335:role/"
                        + "research-archive-platform-dev-api-execution-role");
    }

    // ── nothing in the directory escapes these rules ─────────────────

    @Test
    void everyCommittedOperatorTaskDefinitionIsCoveredHere() throws Exception {
        /*
         * The failure mode this class is parameterised to avoid: a third
         * utility is added, its task definition is committed, and none of the
         * rules above are applied to it because nobody added it to the list.
         */
        List<String> onDisk;
        try (Stream<Path> files = Files.list(locate("ops/grant-admin"))) {
            onDisk = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".taskdef.json"))
                    .sorted()
                    .toList();
        }
        List<String> covered = operators()
                .map(o -> Path.of(o.file()).getFileName().toString())
                .sorted()
                .toList();

        assertThat(onDisk)
                .as("a committed task definition that no test covers")
                .isEqualTo(covered);
    }

    // ---------------- helpers ----------------

    private static JsonNode taskDefinition(Operator operator) {
        try {
            return new ObjectMapper().readTree(Files.readString(locate(operator.file())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + operator.file(), e);
        }
    }

    private static JsonNode container(Operator operator) {
        return taskDefinition(operator).get("containerDefinitions").get(0);
    }

    private static String environment(Operator operator, String name) {
        for (JsonNode variable : container(operator).get("environment")) {
            if (name.equals(variable.get("name").asText())) {
                return variable.get("value").asText();
            }
        }
        return null;
    }

    private static Path locate(String relative) {
        Path candidate = Path.of(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path fromModule = Path.of("..", relative);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate " + relative);
    }
}
