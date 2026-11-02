package edu.bu.archive.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the committed one-off grant-admin task definition.
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
 * <p>This checks the shape of the committed file. It cannot see a NEW variable
 * added to the deployed API later - {@code scripts/verify-grant-admin-taskdef.sh}
 * diffs against the live API task definition for that.
 */
@DisplayName("The committed grant-admin task definition")
class GrantAdminTaskDefinitionTest {

    private static JsonNode taskDefinition;
    private static JsonNode container;

    @BeforeAll
    static void load() throws Exception {
        Path file = locate("ops/grant-admin/dev-grant-admin.taskdef.json");
        taskDefinition = new ObjectMapper().readTree(Files.readString(file));
        container = taskDefinition.get("containerDefinitions").get(0);
    }

    @Test
    void itRunsInItsOwnFamilySoTheApiServiceIsNeverTouched() {
        assertThat(taskDefinition.get("family").asText())
                .isEqualTo("research-archive-platform-dev-grant-admin");
    }

    @Test
    void theImageIsPinnedByDigestNotByTag() {
        String image = container.get("image").asText();
        assertThat(image)
                .as("both ECR repositories are MUTABLE, so a tag can be repointed")
                .contains("@sha256:")
                .doesNotContain(":latest");
    }

    @Test
    void theArchitectureIsDeclaredExplicitly() {
        JsonNode platform = taskDefinition.get("runtimePlatform");
        assertThat(platform).isNotNull();
        assertThat(platform.get("cpuArchitecture").asText()).isEqualTo("X86_64");
        assertThat(platform.get("operatingSystemFamily").asText()).isEqualTo("LINUX");
    }

    /** The omission that made the first dry run fail before the CLI ran. */
    @Test
    void everyEnvironmentVariableTheApiNeedsToStartIsPresent() {
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
            if (environment(required) == null) {
                missing.add(required);
            }
        }
        assertThat(missing)
                .as("a missing variable fails at Spring startup, before the CLI runs")
                .isEmpty();
    }

    /** The second mistake: replacing the deployment profile instead of adding to it. */
    @Test
    void grantAdminIsAddedToTheAwsProfileRatherThanReplacingIt() {
        String profiles = environment("SPRING_PROFILES_ACTIVE");
        List<String> active = List.of(profiles.split(","));
        assertThat(active).contains("aws", "grant-admin");
        assertThat(active.indexOf("aws"))
                .as("aws must come first, as it does on the deployed API")
                .isZero();
    }

    @Test
    void thereIsNoHttpListener() {
        assertThat(environment("SPRING_MAIN_WEB_APPLICATION_TYPE")).isEqualTo("none");
        assertThat(container.has("portMappings"))
                .as("a port mapping would make this registrable behind the ALB")
                .isFalse();
    }

    @Test
    void theExistingArchiveApiSecretsAreUsedUnchanged() {
        List<String> names = new ArrayList<>();
        for (JsonNode secret : container.get("secrets")) {
            names.add(secret.get("name").asText());
            assertThat(secret.get("valueFrom").asText())
                    .as("the API's own secret, referenced by ARN and JSON key")
                    .contains("research-archive-platform/dev/postgres-api-");
        }
        assertThat(names).containsExactlyInAnyOrder(
                "POSTGRES_DB", "POSTGRES_HOST", "POSTGRES_PASSWORD",
                "POSTGRES_PORT", "POSTGRES_USER");
    }

    @Test
    void noCredentialIsInlined() {
        String json = taskDefinition.toString();
        assertThat(json)
                .as("credentials come from Secrets Manager, never from the definition")
                .doesNotContain("POSTGRES_PASSWORD\":\"")
                .doesNotContainPattern("(AKIA|ASIA)[0-9A-Z]{16}");
        for (JsonNode variable : container.get("environment")) {
            assertThat(variable.get("name").asText())
                    .as("no credential-shaped plain environment variable")
                    .doesNotContain("PASSWORD")
                    .doesNotContain("SECRET_KEY");
        }
    }

    @Test
    void theExistingApiIamRolesAreReused() {
        assertThat(taskDefinition.get("taskRoleArn").asText())
                .isEqualTo("arn:aws:iam::770203350335:role/research-archive-platform-dev-api-task-role");
        assertThat(taskDefinition.get("executionRoleArn").asText())
                .isEqualTo("arn:aws:iam::770203350335:role/research-archive-platform-dev-api-execution-role");
    }

    private static String environment(String name) {
        for (JsonNode variable : container.get("environment")) {
            if (name.equals(variable.get("name").asText())) {
                return variable.get("value").asText();
            }
        }
        return null;
    }

    private static Path locate(String relative) {
        Path candidate = Path.of(relative);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path fromModule = Path.of("..", relative);
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate " + relative);
    }
}
