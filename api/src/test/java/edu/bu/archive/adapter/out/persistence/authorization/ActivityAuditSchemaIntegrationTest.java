package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.bu.archive.application.authorization.audit.ActivityAuditEvent;
import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityAction;
import edu.bu.archive.application.authorization.audit.ActivityAuditEvent.ActivityOutcome;

/**
 * SEC-008: the V089 schema against a real PostgreSQL.
 *
 * <p>What a unit test cannot prove: that rows route to the right monthly
 * partition, that the default partition catches what no month claims, that the
 * append-only trigger actually fires, and that the CHECK constraints reject
 * what the Java enums would otherwise be the only guard against.
 *
 * <p>Collection is OFF in every environment; this exercises the storage
 * mechanism so that turning it on later is a configuration change rather than
 * a first encounter with the schema.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ActivityAuditSchemaIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static JdbcActivityAuditPort port;

    @BeforeAll
    static void applyMigrations() throws Exception {
        Path dir = Files.isDirectory(Path.of("database", "migrations"))
                ? Path.of("database", "migrations")
                : Path.of("..", "database", "migrations");
        List<Path> migrations;
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ActivityAuditSchemaIntegrationTest::version))
                    .toList();
        }
        var dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            /*
             * V089 is held back from the release and lives outside the
             * directory the ETL runner globs, so it has to be applied
             * explicitly. See database/migrations-pending-approval/README.md.
             */
            Path pending = Files.isDirectory(Path.of("database", "migrations-pending-approval"))
                    ? Path.of("database", "migrations-pending-approval")
                    : Path.of("..", "database", "migrations-pending-approval");
            try (Stream<Path> held = Files.list(pending)) {
                for (Path migration : held
                        .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparingInt(ActivityAuditSchemaIntegrationTest::version))
                        .toList()) {
                    statement.execute(Files.readString(migration));
                }
            }
        }
        port = new JdbcActivityAuditPort(jdbc, new ObjectMapper());
    }

    private static ActivityAuditEvent event(Instant at, ActivityOutcome outcome) {
        return ActivityAuditEvent.of(at, "ARP-SYN-0001", ActivityAction.RECORD_VIEWED,
                "AWARD", "SYN-AWD-A", outcome, "DEPARTMENT", "corr-" + at.toEpochMilli());
    }

    @Test
    @DisplayName("a row routes to its monthly partition")
    void rowsRouteToTheMonthlyPartition() {
        port.append(event(Instant.parse("2026-11-15T10:00:00Z"), ActivityOutcome.PERMITTED));

        String partition = jdbc.sql("""
                        SELECT tableoid::regclass::text FROM authz.activity_audit
                         WHERE correlation_id = :c
                        """)
                .param("c", "corr-" + Instant.parse("2026-11-15T10:00:00Z").toEpochMilli())
                .query(String.class).single();
        assertThat(partition).isEqualTo("authz.activity_audit_2026_11");
    }

    @Test
    @DisplayName("a row outside every declared month lands in the default partition, not an error")
    void theDefaultPartitionCatchesTheRest() {
        /*
         * This is the one that matters operationally. An INSERT with no
         * matching partition FAILS - and a failing audit write is exactly the
         * event the failure policy then has to arbitrate. The default
         * partition means a missed monthly rollover degrades to "rows in the
         * overflow table", which is visible, rather than to an outage.
         */
        Instant farFuture = Instant.parse("2031-06-01T00:00:00Z");
        assertThatCode(() -> port.append(event(farFuture, ActivityOutcome.PERMITTED)))
                .doesNotThrowAnyException();

        String partition = jdbc.sql("""
                        SELECT tableoid::regclass::text FROM authz.activity_audit
                         WHERE correlation_id = :c
                        """)
                .param("c", "corr-" + farFuture.toEpochMilli())
                .query(String.class).single();
        assertThat(partition).isEqualTo("authz.activity_audit_overflow");
    }

    @Test
    @DisplayName("the table is append-only")
    void theTableIsAppendOnly() {
        port.append(event(Instant.parse("2026-12-01T09:00:00Z"), ActivityOutcome.DENIED));

        assertThatThrownBy(() -> jdbc.sql("UPDATE authz.activity_audit SET actor = 'x'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM authz.activity_audit").update())
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("retention by DROP PARTITION does not fight the append-only trigger")
    void droppingAPartitionIsNotBlockedByTheTrigger() {
        /*
         * The retention design depends on this: a row-level trigger does not
         * fire for a partition drop. If it did, the only way to delete
         * anything would be to disable the trigger, and the append-only
         * guarantee would become advisory.
         */
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS authz.activity_audit_2027_02
                    PARTITION OF authz.activity_audit
                    FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00')
                """).update();
        port.append(event(Instant.parse("2027-02-10T09:00:00Z"), ActivityOutcome.PERMITTED));

        assertThatCode(() -> jdbc.sql("DROP TABLE authz.activity_audit_2027_02").update())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown actions and outcomes are rejected by the database, not only by Java")
    void checkConstraintsRejectUnknownValues() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.activity_audit (actor, action, outcome)
                VALUES ('ARP-SYN-0001', 'SOMETHING_ELSE', 'PERMITTED')
                """).update())
                .hasMessageContaining("ck_activity_audit_action");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO authz.activity_audit (actor, action, outcome)
                VALUES ('ARP-SYN-0001', 'RECORD_VIEWED', 'MAYBE')
                """).update())
                .hasMessageContaining("ck_activity_audit_outcome");
    }

    @Test
    @DisplayName("structured detail round-trips as JSONB")
    void detailRoundTrips() {
        Instant at = Instant.parse("2026-11-20T08:00:00Z");
        port.append(ActivityAuditEvent
                .of(at, "ARP-SYN-0001", ActivityAction.SEARCH_PERFORMED, "AWARD", null,
                        ActivityOutcome.PERMITTED, null, "corr-search")
                .withDetail("resultCount", 17));

        String detail = jdbc.sql("""
                        SELECT detail ->> 'resultCount' FROM authz.activity_audit
                         WHERE correlation_id = 'corr-search'
                        """)
                .query(String.class).single();
        assertThat(detail).isEqualTo("17");

        String resourceKey = jdbc.sql("""
                        SELECT COALESCE(resource_key, '(null)') FROM authz.activity_audit
                         WHERE correlation_id = 'corr-search'
                        """)
                .query(String.class).single();
        assertThat(resourceKey)
                .as("a search records that it happened and how many rows came back, "
                        + "never what was searched for")
                .isEqualTo("(null)");
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
