package edu.bu.archive.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared database setup and handler-mapping helpers for the two IRB release
 * exclusion context tests. Not a test class itself.
 */
final class IrbApiExclusionFixtures {

    /** A token present only in the seeded IRB row, so Global Search finds exactly it. */
    static final String SEEDED_IRB_TOKEN = "ZQIRBEXCLUSIONSEED";
    static final String SEEDED_PI_EMAIL = "irb.exclusion.seed@example.edu";
    static final long SEEDED_PROTOCOL_ID = 990001L;
    static final long SEEDED_AWARD_ID = 880001L;

    /**
     * Route prefixes that belong to the IRB-only API. Any handler registered
     * under one of these counts as an IRB route, whatever its controller is
     * called, so a newly added IRB route is caught by the exclusion test.
     */
    static final List<String> IRB_ROUTE_PREFIXES = List.of("/api/irb", "/api/investigators");

    /** Every IRB-only route that exists today, as registered path patterns. */
    static final Set<String> EXPECTED_IRB_PATTERNS = new TreeSet<>(Set.of(
            "/api/irb",
            "/api/irb/record/{recordId}",
            "/api/irb/{studyId}",
            "/api/irb/families",
            "/api/irb/history",
            "/api/irb/history/{protocolId}",
            "/api/irb/record/{recordId}/workspace",
            "/api/investigators"));

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private IrbApiExclusionFixtures() {
    }

    /** Applies every committed migration, then seeds one IRB protocol. Returns its record_id. */
    static long migrateAndSeed(PostgreSQLContainer<?> postgres) throws Exception {
        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrationsDirectory())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(IrbApiExclusionFixtures::migrationVersion))
                    .toList();
        }
        try (Connection connection = postgres.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }
            statement.execute("""
                    INSERT INTO archive.research_record
                        (record_type, source_identifier, business_identifier, title)
                    VALUES ('IRB', 'IRB-EXCL-1', 'IRB-EXCL-1', 'ZQIRBEXCLUSIONSEED study')
                    """);
            long recordId;
            try (var rs = statement.executeQuery(
                    "SELECT record_id FROM archive.research_record "
                            + "WHERE source_identifier = 'IRB-EXCL-1'")) {
                rs.next();
                recordId = rs.getLong(1);
            }
            statement.execute("""
                    INSERT INTO archive.irb_protocol
                        (record_id, protocol_id, study_id, protocol_base, protocol_number,
                         protocol_type, protocol_status, pi_full_name, pi_email, active_flag)
                    VALUES (%d, %d, 'ZQIRBEXCLUSIONSEED', 'IRBEXCL1', 'IRBEXCL1-01',
                            'Exempt', 'Approved', 'Seed Investigator', '%s', TRUE)
                    """.formatted(recordId, SEEDED_PROTOCOL_ID, SEEDED_PI_EMAIL));
            statement.execute("""
                    INSERT INTO archive.irb_protocol_version
                        (protocol_id, protocol_base, protocol_number, sequence_number,
                         title, protocol_type, protocol_status, pi_id, pi_email)
                    VALUES (%d, 'IRBEXCL1', 'IRBEXCL1-01', 1,
                            'ZQIRBEXCLUSIONSEED study', 'Exempt', 'Approved', 'U0000001', '%s')
                    """.formatted(SEEDED_PROTOCOL_ID, SEEDED_PI_EMAIL));
            // One Award, so the related-record endpoints (PR #159) have a
            // parent to answer for.
            statement.execute("""
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title, status_description,
                        sponsor_code, sponsor_name, lead_unit_number, lead_unit_name,
                        is_primary_current, workflow_document_number
                    ) VALUES (%d, 'IRBEXCL-00001', 1, 'IRB exclusion award', 'Active',
                              '0200', 'NIH', '7777', 'CHEMISTRY', TRUE, 'DOC-IRBEXCL-1')
                    """.formatted(SEEDED_AWARD_ID));
            return recordId;
        }
    }

    /** Every registered path pattern under an IRB route prefix, from the live handler mapping. */
    static Set<String> irbPatterns(RequestMappingHandlerMapping mapping) {
        Set<String> patterns = new TreeSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (isIrbRoute(pattern)) {
                    patterns.add(pattern);
                }
            }
        }
        return patterns;
    }

    /** Every registered handler whose controller class name marks it as IRB/investigator code. */
    static Set<String> irbControllerHandlers(RequestMappingHandlerMapping mapping) {
        Set<String> handlers = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            String name = entry.getValue().getBeanType().getSimpleName();
            if (name.startsWith("Irb") || name.startsWith("Investigator")) {
                handlers.add(name + "#" + entry.getValue().getMethod().getName());
            }
        }
        return handlers;
    }

    /** True if any registered pattern starts with the given prefix. */
    static boolean hasPatternUnder(RequestMappingHandlerMapping mapping, String prefix) {
        return mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .anyMatch(p -> p.equals(prefix) || p.startsWith(prefix + "/"));
    }

    private static boolean isIrbRoute(String pattern) {
        return IRB_ROUTE_PREFIXES.stream()
                .anyMatch(prefix -> pattern.equals(prefix) || pattern.startsWith(prefix + "/"));
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + path);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() {
        Path candidate = Path.of("database", "migrations");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path fromModule = Path.of("..", "database", "migrations");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("cannot locate database/migrations");
    }
}
