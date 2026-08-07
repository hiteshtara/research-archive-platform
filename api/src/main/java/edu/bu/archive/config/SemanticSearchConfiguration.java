package edu.bu.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.adapter.out.search.BedrockEmbeddingProvider;
import edu.bu.archive.application.port.out.EmbeddingProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

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
            String awsRegion
    ) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(awsRegion))
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
