package edu.bu.archive.adapter.out.persistence.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The documented, NON-scheduled retention procedure
 * ({@code ops/activity-audit/activity_audit_retention.sql}) against V089 in a
 * throwaway PostgreSQL. The procedure is executed only here.
 *
 * <p>The retention PERIOD is a business decision that has not been made; the
 * values used below are test inputs, not a proposal.
 */
@Tag("database")
@Testcontainers(disabledWithoutDocker = true)
class ActivityAuditRetentionProcedureIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final String MARKER = "-- END OF FUNCTION DEFINITION --";

    @Container
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private Connection connection;

    /** A fresh container per test: retention drops tables, so tests must not share one. */
    @BeforeEach
    void schemaWithRows() throws Exception {
        connection = postgres.createConnection("");
        try (Statement statement = connection.createStatement()) {
            for (Path migration : migrations(locate("migrations"))) {
                statement.execute(Files.readString(migration));
            }
            for (Path migration : migrations(locate("migrations-pending-approval"))) {
                statement.execute(Files.readString(migration));
            }
            statement.execute("""
                    INSERT INTO authz.activity_audit (occurred_at, actor, action, resource_type,
                                                      resource_key, outcome, correlation_id)
                    VALUES
                    ('2026-11-10 12:00:00+00', 'ARP-SYN-1', 'RECORD_VIEWED', 'AWARD', 'SYN-A', 'PERMITTED', 'c-nov'),
                    ('2026-12-10 12:00:00+00', 'ARP-SYN-1', 'RECORD_VIEWED', 'AWARD', 'SYN-A', 'PERMITTED', 'c-dec'),
                    ('2027-01-10 12:00:00+00', 'ARP-SYN-1', 'RECORD_VIEWED', 'AWARD', 'SYN-A', 'PERMITTED', 'c-jan'),
                    ('2031-06-01 12:00:00+00', 'ARP-SYN-1', 'RECORD_VIEWED', 'AWARD', 'SYN-A', 'PERMITTED', 'c-overflow'),
                    ('2020-01-01 12:00:00+00', 'ARP-SYN-1', 'RECORD_VIEWED', 'AWARD', 'SYN-A', 'PERMITTED', 'c-ancient-overflow')
                    """);
            // The function, exactly as the operator script defines it; psql
            // meta-commands are the only lines removed.
            statement.execute(functionDefinition());
        }
    }

    private Map<String, String> run(int retentionDays, String asOf, boolean execute) throws Exception {
        Map<String, String> actions = new LinkedHashMap<>();
        try (var call = connection.prepareStatement(
                "SELECT partition_name, action FROM pg_temp.activity_audit_retention(?, ?, ?)")) {
            call.setInt(1, retentionDays);
            call.setTimestamp(2, Timestamp.from(Instant.parse(asOf)));
            call.setBoolean(3, execute);
            try (var rs = call.executeQuery()) {
                while (rs.next()) {
                    actions.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return actions;
    }

    private List<String> correlationIds() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT correlation_id FROM authz.activity_audit ORDER BY occurred_at")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private boolean tableExists(String name) throws Exception {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT to_regclass('" + name + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @Test
    @DisplayName("dry run (the default) reports what would go and drops nothing")
    void dryRunDropsNothing() throws Exception {
        Map<String, String> actions = run(60, "2027-03-15T00:00:00Z", false);

        assertThat(actions).containsEntry("authz.activity_audit_2026_11", "WOULD_DROP")
                .containsEntry("authz.activity_audit_2026_12", "WOULD_DROP")
                .containsEntry("authz.activity_audit_2027_01", "RETAINED")
                .containsEntry("authz.activity_audit_overflow", "SKIPPED_DEFAULT_PARTITION");
        assertThat(tableExists("authz.activity_audit_2026_11")).isTrue();
        assertThat(correlationIds()).hasSize(5);
    }

    @Test
    @DisplayName("execute drops only whole months older than the period, and leaves the rest intact")
    void executeDropsOnlyExpiredWholeMonths() throws Exception {
        // as_of 2027-03-15 minus 60 days = 2027-01-14: November and December
        // end before it; January (ends 2027-02-01) is partly inside it and is kept whole.
        Map<String, String> actions = run(60, "2027-03-15T00:00:00Z", true);

        assertThat(actions).containsEntry("authz.activity_audit_2026_11", "DROPPED")
                .containsEntry("authz.activity_audit_2026_12", "DROPPED")
                .containsEntry("authz.activity_audit_2027_01", "RETAINED");
        assertThat(tableExists("authz.activity_audit_2026_11")).isFalse();
        assertThat(tableExists("authz.activity_audit_2026_12")).isFalse();
        assertThat(correlationIds())
                .as("January survives, and the DEFAULT partition is never touched - "
                        + "even its ancient row, which needs a person, not a job")
                .containsExactly("c-ancient-overflow", "c-jan", "c-overflow");
    }

    @Test
    @DisplayName("the period is parameterised: a longer period keeps more")
    void theRetentionPeriodIsAParameter() throws Exception {
        Map<String, String> actions = run(365, "2027-03-15T00:00:00Z", true);

        assertThat(actions.values()).doesNotContain("DROPPED");
        assertThat(correlationIds()).hasSize(5);
    }

    @Test
    @DisplayName("the append-only guarantee still holds afterwards - retention never disables it")
    void appendOnlyStillHolds() throws Exception {
        run(60, "2027-03-15T00:00:00Z", true);
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DELETE FROM authz.activity_audit"))
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    @DisplayName("a missing or non-positive period is refused")
    void aNonPositivePeriodIsRefused() {
        assertThatThrownBy(() -> run(0, "2027-03-15T00:00:00Z", true))
                .hasMessageContaining("retention_days must be a positive");
    }

    @Test
    @DisplayName("the operator script is dry-run by default and has no default period")
    void theScriptDefaultsToDryRunWithNoDefaultPeriod() throws Exception {
        String script = Files.readString(script());
        assertThat(script).contains("\\set execute false");
        assertThat(script).doesNotContainPattern("\\\\set retention_days");
        assertThat(script).contains("pg_temp.activity_audit_retention");
    }

    private static String functionDefinition() throws Exception {
        String script = Files.readString(script());
        int end = script.indexOf(MARKER);
        assertThat(end).as("function marker present").isPositive();
        return script.substring(0, end).lines()
                .filter(line -> !line.startsWith("\\"))
                .collect(Collectors.joining("\n"));
    }

    private static Path script() {
        Path candidate = Path.of("ops", "activity-audit", "activity_audit_retention.sql");
        return Files.isRegularFile(candidate) ? candidate
                : Path.of("..", "ops", "activity-audit", "activity_audit_retention.sql");
    }

    private static List<Path> migrations(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(ActivityAuditRetentionProcedureIntegrationTest::version))
                    .toList();
        }
    }

    private static int version(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locate(String name) {
        Path candidate = Path.of("database", name);
        return Files.isDirectory(candidate) ? candidate : Path.of("..", "database", name);
    }
}
