package edu.bu.archive.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import edu.bu.archive.adapter.in.web.InvestigatorController;
import edu.bu.archive.adapter.in.web.IrbArchiveController;
import edu.bu.archive.adapter.in.web.IrbController;
import edu.bu.archive.adapter.in.web.IrbWorkspaceController;
import edu.bu.archive.adapter.out.persistence.InvestigatorRepository;
import edu.bu.archive.adapter.out.persistence.IrbArchiveRepository;
import edu.bu.archive.adapter.out.persistence.IrbWorkspaceRepository;
import edu.bu.archive.application.port.in.IrbQueryUseCase;

/**
 * Fast, database-free check of the flag's three states (absent, true, false)
 * on exactly the four IRB-only controllers. The full-context route checks are
 * in IrbApiReleaseExclusionIntegrationTest / IrbApiDefaultRegistrationIntegrationTest.
 */
class IrbApiFeatureFlagTest {

    private static final List<Class<?>> IRB_CONTROLLERS = List.of(
            IrbController.class,
            IrbArchiveController.class,
            IrbWorkspaceController.class,
            InvestigatorController.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    IrbController.class,
                    IrbArchiveController.class,
                    IrbWorkspaceController.class,
                    InvestigatorController.class)
            .withBean(IrbQueryUseCase.class, () -> mock(IrbQueryUseCase.class))
            .withBean(IrbArchiveRepository.class, () -> mock(IrbArchiveRepository.class))
            .withBean(IrbWorkspaceRepository.class, () -> mock(IrbWorkspaceRepository.class))
            .withBean(InvestigatorRepository.class, () -> mock(InvestigatorRepository.class));

    @Test
    void controllersAreRegisteredWhenTheFlagIsUnset() {
        contextRunner.run(context -> {
            for (Class<?> controller : IRB_CONTROLLERS) {
                assertThat(context).hasSingleBean(controller);
            }
        });
    }

    @Test
    void controllersAreRegisteredWhenTheFlagIsTrue() {
        contextRunner.withPropertyValues("app.irb.api.enabled=true").run(context -> {
            for (Class<?> controller : IRB_CONTROLLERS) {
                assertThat(context).hasSingleBean(controller);
            }
        });
    }

    @Test
    void controllersAreAbsentWhenTheFlagIsFalse() {
        contextRunner.withPropertyValues("app.irb.api.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> controller : IRB_CONTROLLERS) {
                assertThat(context).doesNotHaveBean(controller);
            }
        });
    }
}
