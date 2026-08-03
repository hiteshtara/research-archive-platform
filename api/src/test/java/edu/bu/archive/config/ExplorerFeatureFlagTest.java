package edu.bu.archive.config;

import edu.bu.archive.adapter.in.web.ExplorerController;
import edu.bu.archive.application.award.ExplorerService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExplorerFeatureFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(ExplorerController.class)
                    .withBean(
                            ExplorerService.class,
                            () -> mock(ExplorerService.class)
                    );

    @Test
    void endpointBeanIsAbsentWhenFeatureIsDisabled() {
        contextRunner
                .withPropertyValues("app.explorer.enabled=false")
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(ExplorerController.class)
                );
    }

    @Test
    void endpointBeanIsAbsentWhenFeatureIsUnset() {
        contextRunner.run(context ->
                assertThat(context)
                        .doesNotHaveBean(ExplorerController.class)
        );
    }

    @Test
    void endpointBeanExistsWhenFeatureIsEnabled() {
        contextRunner
                .withPropertyValues("app.explorer.enabled=true")
                .run(context ->
                        assertThat(context)
                                .hasSingleBean(ExplorerController.class)
                );
    }
}
