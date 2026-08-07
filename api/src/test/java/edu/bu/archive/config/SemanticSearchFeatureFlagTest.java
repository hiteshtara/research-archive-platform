package edu.bu.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Mirrors AiFeatureFlagTest's ApplicationContextRunner pattern - proves
 * app.search.semantic.enabled=false means no BedrockRuntimeClient or
 * EmbeddingProvider bean is ever constructed (no network setup, no
 * credential resolution), exactly like the existing app.ai.* flags
 * gate their own beans. GlobalSearchService itself is not gated this
 * way (it's a single always-present @Service - see its own semantic
 * branch guard, covered by GlobalSearchServiceTest instead).
 */
class SemanticSearchFeatureFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(SemanticSearchConfiguration.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void bedrockBeansAreAbsentWhenSemanticSearchIsDisabled() {
        contextRunner
                .withPropertyValues("app.search.semantic.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("bedrockRuntimeClient");
                    assertThat(context).doesNotHaveBean("embeddingProvider");
                });
    }

    @Test
    void bedrockBeansExistWhenSemanticSearchIsEnabled() {
        contextRunner
                .withPropertyValues("app.search.semantic.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("bedrockRuntimeClient");
                    assertThat(context).hasBean("embeddingProvider");
                });
    }
}
