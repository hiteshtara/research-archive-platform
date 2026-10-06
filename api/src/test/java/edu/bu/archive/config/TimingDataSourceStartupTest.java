package edu.bu.archive.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * The test that was missing.
 *
 * The first version of TimingDataSourceConfiguration put
 * @ConfigurationProperties("spring.datasource") on a method that
 * RETURNED the wrapper, so the url and credentials bound to the
 * decorator and the Hikari pool inside it was built with nothing:
 *
 *   HikariPool-1 - dataSource or dataSourceClassName or jdbcUrl is required
 *
 * The application could not start. The full API suite passed anyway -
 * 1102 tests - because every test context builds its own datasource and
 * none of them exercise this bean against real property binding. A green
 * suite said nothing about whether the deployed application could boot,
 * and it was deployed on that basis.
 *
 * This asserts the thing the suite could not: that the configuration
 * binds its properties and produces a usable DataSource. It needs no
 * database - a misbound pool fails when the configuration is built, long
 * before anything connects.
 */
class TimingDataSourceStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(TimingDataSourceConfiguration.class)
            .withBean(
                    edu.bu.archive.application.service.SearchTimingLog.class,
                    () -> new edu.bu.archive.application.service.SearchTimingLog(false)
            );

    @Test
    void theContextStartsAndTheDataSourceIsConfigured() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/research_archive",
                        "spring.datasource.username=someone",
                        "spring.datasource.password=secret",
                        "spring.datasource.driver-class-name=org.postgresql.Driver"
                )
                .run(context -> {
                    // The failure mode was a context that would not start
                    // at all, so this assertion is the point of the test.
                    // The failure mode was a context that would not start.
                    assertThat(context).hasNotFailed();
                    // Two DataSource beans by design: the Hikari delegate
                    // and the @Primary wrapper around it.
                    assertThat(context.getBeanNamesForType(DataSource.class))
                            .containsExactlyInAnyOrder("timingHikariDataSource", "dataSource");

                    com.zaxxer.hikari.HikariDataSource hikari =
                            context.getBean(com.zaxxer.hikari.HikariDataSource.class);
                    // The url actually reached the pool. Previously it did
                    // not, and Hikari threw on first use.
                    assertThat(hikari.getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://localhost:5432/research_archive");
                    assertThat(hikari.getUsername()).isEqualTo("someone");
                });
    }

    @Test
    void poolSettingsStillBindToTheRealPoolAndNotToTheWrapper() {
        // spring.datasource.hikari.* must reach Hikari. If these bound to
        // the decorator instead they would be silently ignored and the
        // deployed pool would quietly run on defaults.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/research_archive",
                        "spring.datasource.username=someone",
                        "spring.datasource.password=secret",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.hikari.maximum-pool-size=10",
                        "spring.datasource.hikari.minimum-idle=2",
                        "spring.datasource.hikari.connection-timeout=30000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    com.zaxxer.hikari.HikariDataSource hikari =
                            context.getBean(com.zaxxer.hikari.HikariDataSource.class);
                    assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
                    assertThat(hikari.getMinimumIdle()).isEqualTo(2);
                    assertThat(hikari.getConnectionTimeout()).isEqualTo(30000L);
                });
    }

    @Test
    void theWrapperIsThePrimaryDataSourceSoTheApplicationUsesIt() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/research_archive",
                        "spring.datasource.username=someone",
                        "spring.datasource.password=secret",
                        "spring.datasource.driver-class-name=org.postgresql.Driver"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataSource.class))
                            .isInstanceOf(TimingDataSourceConfiguration.TimingDataSource.class);
                });
    }
}
