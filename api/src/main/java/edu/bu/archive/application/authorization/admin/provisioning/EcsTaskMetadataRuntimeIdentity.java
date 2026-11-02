package edu.bu.archive.application.authorization.admin.provisioning;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ECS task metadata endpoint, which Fargate injects as
 * {@code ECS_CONTAINER_METADATA_URI_V4}.
 *
 * <p>Chosen over {@code sts:GetCallerIdentity} on purpose: it needs no IAM
 * permission, no SDK and no internet route, and it cannot be influenced by
 * anything the operator types. The task ARN it returns carries the account
 * id in its fifth field.
 *
 * <p>If the variable is absent the process is not running as an ECS task,
 * and this reports nothing rather than guessing. The caller decides what
 * an unknown runtime means; for writing grants it means refusing.
 */
public final class EcsTaskMetadataRuntimeIdentity implements RuntimeIdentity {

    private static final Pattern TASK_ARN =
            Pattern.compile("\"TaskARN\"\\s*:\\s*\"(arn:aws[^\"]*)\"");
    private static final Pattern CLUSTER =
            Pattern.compile("\"Cluster\"\\s*:\\s*\"([^\"]+)\"");

    private final String accountId;
    private final String cluster;

    public EcsTaskMetadataRuntimeIdentity() {
        this(System.getenv("ECS_CONTAINER_METADATA_URI_V4"));
    }

    /**
     * Explicit endpoint, for tests and for any caller that already knows
     * the URI. Public because an unreachable or nonsense endpoint must be
     * exercisable: it has to read as "unknown", never as agreement.
     */
    public EcsTaskMetadataRuntimeIdentity(String metadataUri) {
        String body = metadataUri == null ? null : fetch(metadataUri + "/task");
        this.accountId = body == null ? null : accountFromTaskArn(body);
        this.cluster = body == null ? null : group(CLUSTER, body);
    }

    private static String fetch(String url) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (InterruptedException e) {
            // Restore the flag only for the one exception that clears it.
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            /*
             * Unreachable metadata is "unknown", never "assume it is fine".
             *
             * This used to call Thread.currentThread().interrupt() for every
             * exception, so an ordinary connection refusal left the calling
             * thread interrupted - and the caller then went on to do JDBC work
             * on it. Only InterruptedException clears the flag, so only
             * InterruptedException restores it.
             */
            return null;
        }
    }

    private static String accountFromTaskArn(String body) {
        String arn = group(TASK_ARN, body);
        if (arn == null) {
            return null;
        }
        // arn:aws:ecs:<region>:<account>:task/<cluster>/<id>
        String[] parts = arn.split(":");
        return parts.length > 4 && !parts[4].isBlank() ? parts[4] : null;
    }

    private static String group(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public Optional<String> awsAccountId() {
        return Optional.ofNullable(accountId);
    }

    /**
     * The {@code Cluster} field exactly as ECS reports it, which on Fargate is
     * the full ARN:
     *
     * <pre>
     *   arn:aws:ecs:us-east-1:770203350335:cluster/research-archive-platform-dev-etl
     * </pre>
     *
     * <p><strong>Deliberately not normalised to the bare name here.</strong>
     * The ARN also carries the region and the account, and those are two more
     * pieces of independent evidence about where this process is running.
     * {@code RuntimeVerification} cross-checks the ARN's account against the
     * one derived from {@code TaskARN}; stripping the ARN in this adapter
     * would throw that away before it reached the one class that makes trust
     * decisions. This class stays a faithful reporter of what AWS said.
     */
    @Override
    public Optional<String> clusterName() {
        return Optional.ofNullable(cluster);
    }
}
