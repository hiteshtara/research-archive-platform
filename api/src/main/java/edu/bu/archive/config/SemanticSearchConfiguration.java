package edu.bu.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.adapter.out.search.BedrockEmbeddingProvider;
import edu.bu.archive.application.port.out.EmbeddingProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.time.Duration;

/*
 * Semantic search's Bedrock client/provider beans only exist at all when
 * app.search.semantic.enabled=true, mirroring AiConfiguration's
 * bean-level @ConditionalOnProperty pattern - no BedrockRuntimeClient is
 * ever constructed (no network setup, no credential resolution) when the
 * flag is off. GlobalSearchService itself stays a single always-present
 * @Service (unlike AiConfiguration's class-level gate on the whole
 * config) since it has five other domains to serve regardless of this
 * flag - see GlobalSearchService's own semantic-branch guard.
 */
@Configuration
@EnableConfigurationProperties(SemanticSearchProperties.class)
public class SemanticSearchConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "app.search.semantic.enabled",
            havingValue = "true"
    )
    BedrockRuntimeClient bedrockRuntimeClient(
            @Value("${AWS_REGION:us-east-1}")
            String awsRegion,
            SemanticSearchProperties properties
    ) {
        // apiCallTimeout bounds the whole call (including internal SDK
        // retries) - the fix for a real, pre-existing gap:
        // bedrockTimeoutMs was declared in SemanticSearchProperties but
        // never actually applied anywhere (see
        // docs/architecture/AWARD_EVIDENCE_RETRIEVAL_PHASE3_DESIGN.md
        // section 5). connectionTimeout is the separate TCP
        // connection-attempt timeout on the underlying HTTP client
        // (ApacheHttpClient, already an existing transitive dependency
        // of the bedrockruntime SDK module - no new dependency added).
        // Both default to a finite value (2000ms) rather than the SDK's
        // own much longer built-in defaults, so an authenticated UI
        // request (Award Evidence Search) can never hang indefinitely.
        return BedrockRuntimeClient.builder()
                .region(Region.of(awsRegion))
                .httpClientBuilder(
                        ApacheHttpClient.builder()
                                .connectionTimeout(Duration.ofMillis(
                                        properties.getConnectionTimeoutMs()
                                ))
                )
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofMillis(
                                        properties.getBedrockTimeoutMs()
                                ))
                                .build()
                )
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.search.semantic.enabled",
            havingValue = "true"
    )
    EmbeddingProvider embeddingProvider(
            BedrockRuntimeClient bedrockRuntimeClient,
            ObjectMapper objectMapper,
            SemanticSearchProperties properties
    ) {
        return new BedrockEmbeddingProvider(
                bedrockRuntimeClient,
                objectMapper,
                properties
        );
    }
}
