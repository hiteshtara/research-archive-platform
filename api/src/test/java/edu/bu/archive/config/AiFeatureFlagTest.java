package edu.bu.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.bu.archive.adapter.in.web.AwardAiController;
import edu.bu.archive.adapter.in.web.AwardAiQuestionController;
import edu.bu.archive.application.ai.AwardAiQuestionService;
import edu.bu.archive.application.ai.AwardAiSummaryService;
import edu.bu.archive.application.port.out.AiProvider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiFeatureFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(AwardAiController.class)
                    .withBean(
                            AwardAiSummaryService.class,
                            () -> mock(AwardAiSummaryService.class)
                    );

    private final ApplicationContextRunner providerContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(AiConfiguration.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new);

    private final ApplicationContextRunner questionContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            AwardAiQuestionController.class
                    )
                    .withBean(
                            AwardAiQuestionService.class,
                            () -> mock(AwardAiQuestionService.class)
                    );

    @Test
    void endpointBeanIsAbsentWhenFeatureIsDisabled() {
        contextRunner
                .withPropertyValues("app.ai.enabled=false")
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(
                                        AwardAiController.class
                                )
                );
    }

    @Test
    void endpointBeanExistsWhenFeatureIsEnabled() {
        contextRunner
                .withPropertyValues("app.ai.enabled=true")
                .run(context ->
                        assertThat(context)
                                .hasSingleBean(
                                        AwardAiController.class
                                )
                );
    }

    @Test
    void questionEndpointRequiresBothFeatureFlags() {
        questionContextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.questions-enabled=false"
                )
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(
                                        AwardAiQuestionController.class
                                )
                );

        questionContextRunner
                .withPropertyValues(
                        "app.ai.enabled=false",
                        "app.ai.questions-enabled=true"
                )
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(
                                        AwardAiQuestionController.class
                                )
                );

        questionContextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.questions-enabled=true"
                )
                .run(context ->
                        assertThat(context)
                                .hasSingleBean(
                                        AwardAiQuestionController.class
                                )
                );
    }

    @Test
    void openAiProviderIsAbsentUnlessBothFlagsAreEnabled() {
        providerContextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.stub-enabled=true",
                        "app.ai.openai-enabled=false",
                        "OPENAI_API_KEY=test-key"
                )
                .run(context -> {
                    assertThat(context)
                            .hasBean("stubAiProvider");
                    assertThat(context)
                            .doesNotHaveBean("openAiProvider");
                });

        providerContextRunner
                .withPropertyValues(
                        "app.ai.enabled=false",
                        "app.ai.stub-enabled=true",
                        "app.ai.openai-enabled=true",
                        "OPENAI_API_KEY=test-key"
                )
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean("stubAiProvider");
                    assertThat(context)
                            .doesNotHaveBean("openAiProvider");
                });
    }

    @Test
    void openAiProviderIsRegisteredWhenBothFlagsAreEnabled() {
        providerContextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.stub-enabled=true",
                        "app.ai.openai-enabled=true",
                        "app.ai.open-ai-model=gpt-5.1",
                        "OPENAI_API_KEY=test-key"
                )
                .run(context -> {
                    assertThat(context)
                            .hasBean("stubAiProvider");
                    assertThat(context)
                            .hasBean("openAiProvider");
                    AiProvider provider =
                            context.getBean(
                                    "openAiProvider",
                                    AiProvider.class
                            );
                    assertThat(provider.providerName())
                            .isEqualTo("openai");
                    assertThat(provider.modelName())
                            .isEqualTo("gpt-5.1");
                });
    }
}
