package edu.bu.archive.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesBindingTest {

    @Test
    void bindsTheDocumentedEnvironmentVariableNames() {
        runner("application.yml")
                .withPropertyValues(
                        "APP_AI_ENABLED=true",
                        "APP_AI_PROVIDER=openai",
                        "APP_AI_OPENAI_ENABLED=true",
                        "APP_AI_OPENAI_MODEL=gpt-5.1",
                        "APP_AI_OPENAI_BASE_URL="
                                + "https://gateway.example/v1",
                        "APP_AI_OPENAI_TIMEOUT_SECONDS=45",
                        "APP_AI_OPENAI_CONNECT_TIMEOUT_SECONDS=9",
                        "APP_AI_PROMPT_VERSION=award-summary-v3",
                        "APP_AI_CACHE_ENABLED=true",
                        "APP_AI_CACHE_MAX_ENTRIES=75"
                )
                .run(context -> {
                    AiProperties properties =
                            context.getBean(AiProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isOpenaiEnabled()).isTrue();
                    assertThat(properties.getProvider())
                            .isEqualTo("openai");
                    assertThat(properties.getOpenAiModel())
                            .isEqualTo("gpt-5.1");
                    assertThat(properties.getOpenAiBaseUrl())
                            .isEqualTo(
                                    "https://gateway.example/v1"
                            );
                    assertThat(
                            properties.getOpenAiTimeoutSeconds()
                    ).isEqualTo(45);
                    assertThat(
                            properties
                                    .getOpenAiConnectTimeoutSeconds()
                    ).isEqualTo(9);
                    assertThat(properties.getPromptVersion())
                            .isEqualTo("award-summary-v3");
                    assertThat(properties.isCacheEnabled()).isTrue();
                    assertThat(properties.getCacheMaxEntries())
                            .isEqualTo(75);
                });
    }

    @Test
    void productionDefaultsAreDisabledAndFailClosed() {
        runner("application.yml")
                .run(context -> {
                    AiProperties properties =
                            context.getBean(AiProperties.class);

                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isStubEnabled()).isFalse();
                    assertThat(properties.isOpenaiEnabled()).isFalse();
                    assertThat(properties.getProvider()).isEmpty();
                    assertThat(properties.getOpenAiModel())
                            .isEqualTo("gpt-5-mini");
                    assertThat(properties.getOpenAiBaseUrl())
                            .isEqualTo(
                                    "https://api.openai.com/v1"
                            );
                    assertThat(
                            properties.getOpenAiTimeoutSeconds()
                    ).isEqualTo(60);
                    assertThat(
                            properties
                                    .getOpenAiConnectTimeoutSeconds()
                    ).isEqualTo(10);
                    assertThat(properties.getPromptVersion())
                            .isEqualTo("award-summary-v2");
                    assertThat(properties.isCacheEnabled()).isFalse();
                    assertThat(properties.getCacheMaxEntries())
                            .isEqualTo(250);
                });
    }

    @Test
    void localProfileDefaultsOnlyTheProviderToStub() {
        runner(
                "application.yml",
                "application-local.yml"
        ).run(context -> {
            AiProperties properties =
                    context.getBean(AiProperties.class);

            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isStubEnabled()).isFalse();
            assertThat(properties.getProvider())
                    .isEqualTo("stub");
        });
    }

    private ApplicationContextRunner runner(
            String... resources
    ) {
        return new ApplicationContextRunner()
                .withInitializer(context ->
                        loadYaml(context, resources)
                )
                .withUserConfiguration(PropertiesConfiguration.class);
    }

    private void loadYaml(
            ConfigurableApplicationContext context,
            String... resources
    ) {
        YamlPropertySourceLoader loader =
                new YamlPropertySourceLoader();

        for (String resource : resources) {
            try {
                loader.load(
                                resource,
                                new ClassPathResource(resource)
                        )
                        .forEach(propertySource ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(propertySource)
                        );
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not load " + resource,
                        exception
                );
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class PropertiesConfiguration {
    }
}
