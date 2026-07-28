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
                        "AI_ENABLED=true",
                        "AI_STUB_ENABLED=true",
                        "AI_PROVIDER=stub"
                )
                .run(context -> {
                    AiProperties properties =
                            context.getBean(AiProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isStubEnabled()).isTrue();
                    assertThat(properties.getProvider())
                            .isEqualTo("stub");
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
                    assertThat(properties.getProvider()).isEmpty();
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
