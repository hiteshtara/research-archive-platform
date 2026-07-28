package edu.bu.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.out.ai.OpenAiProvider;
import edu.bu.archive.adapter.out.ai.StubAiProvider;
import edu.bu.archive.application.port.out.AiProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "app.ai.stub-enabled",
            havingValue = "true"
    )
    AiProvider stubAiProvider() {
        return new StubAiProvider();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.ai.openai-enabled",
            havingValue = "true"
    )
    AiProvider openAiProvider(
            ObjectMapper objectMapper,
            AiProperties properties,
            @Value("${OPENAI_API_KEY:}") String apiKey
    ) {
        return new OpenAiProvider(
                objectMapper,
                properties,
                apiKey
        );
    }
}
