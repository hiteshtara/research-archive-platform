package edu.bu.archive.config;

import edu.bu.archive.adapter.in.web.AwardAiController;
import edu.bu.archive.application.ai.AwardAiSummaryService;

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
}
