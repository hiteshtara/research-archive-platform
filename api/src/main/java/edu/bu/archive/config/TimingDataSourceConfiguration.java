package edu.bu.archive.config;

import edu.bu.archive.application.service.SearchTimingLog;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/*
 * TEMPORARY measurement instrumentation for QA TC-042.
 *
 * WHY THIS EXISTS. The statement timers in AwardArchiveService measure
 * the duration of a repository CALL, which bundles connection
 * acquisition, driver work, network, server execution and row
 * materialisation into one number. Connection acquisition is the one
 * part of that bundle we can separate without a query plan, and it is
 * also a live hypothesis: Global Search runs its legs on worker threads
 * while AwardV1Controller runs the same search inline on the Tomcat
 * worker, and the two could contend for the pool differently.
 *
 * Hikari already measures this and exports it through Micrometer - but
 * `management.endpoints.web.exposure.include` is health,info, so the
 * metrics endpoint is not reachable. Widening that exposure to read one
 * number would add HTTP surface to a deployed API for a temporary
 * investigation, which is a worse trade than this wrapper: it logs
 * through the same default-off SearchTimingLog, under the same
 * correlation id, and exposes nothing new.
 *
 * WHAT IT RECORDS: DB_CONNECTION_ACQUIRE, a duration in milliseconds.
 * No SQL, no parameters, no credentials - the wrapper never looks at
 * what the connection is used for.
 *
 * DISABLED the same single way as everything else: with
 * app.search.timing.enabled false (the default) the wrapper still sits
 * in the chain but takes no timestamps and logs nothing. Remove this
 * class with the rest of the TC-042 scaffolding.
 */
@Configuration
public class TimingDataSourceConfiguration {

    /*
     * THE BUG THIS REPLACES, because it is worth not repeating:
     * @ConfigurationProperties on a method binds to the object the
     * method RETURNS. The previous version returned the wrapper, so
     * spring.datasource.* bound to the wrapper and the Hikari pool
     * inside it was built with no jdbcUrl - "dataSource or
     * dataSourceClassName or jdbcUrl is required" - and the application
     * could not start at all. Every test passed, because the test
     * contexts build their own datasource.
     *
     * Now the properties bind to the things that actually need them:
     * DataSourceProperties for url/credentials, the Hikari bean for
     * pool settings (maximum-pool-size, minimum-idle,
     * connection-timeout). The wrapper is only a decorator and binds
     * nothing.
     */
    // DataSourceProperties comes from Spring Boot's own
    // DataSourceAutoConfiguration, already bound to spring.datasource.
    // Declaring a second one here collided with it
    // (NoUniqueBeanDefinitionException) and broke startup a second
    // time - caught by TimingDataSourceStartupTest.
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource timingHikariDataSource(DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource delegate, SearchTimingLog timingLog) {
        return new TimingDataSource(delegate, timingLog);
    }

    /**
     * Delegates everything, and times only {@link #getConnection()}.
     */
    static final class TimingDataSource implements DataSource {

        private final DataSource delegate;
        private final SearchTimingLog timingLog;

        TimingDataSource(DataSource delegate, SearchTimingLog timingLog) {
            this.delegate = delegate;
            this.timingLog = timingLog;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (!timingLog.isEnabled()) {
                return delegate.getConnection();
            }
            long startNanos = System.nanoTime();
            boolean acquired = false;
            try {
                Connection connection = delegate.getConnection();
                acquired = true;
                return connection;
            } finally {
                timingLog.record(
                        timingLog.correlationId(),
                        "DB_CONNECTION_ACQUIRE",
                        (System.nanoTime() - startNanos) / 1_000_000,
                        -1,
                        acquired
                );
            }
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}
