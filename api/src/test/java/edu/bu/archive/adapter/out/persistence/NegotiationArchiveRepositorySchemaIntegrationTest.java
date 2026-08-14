package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/*
 * Applies every committed database/migrations/*.sql file, in order, to
 * a real ephemeral Postgres container, then runs
 * NegotiationArchiveRepository's actual SQL against that real schema -
 * a mocked JdbcClient (every other repository test in this codebase)
 * only ever verifies SQL text and parameter binding, never that a
 * selected/joined column genuinely exists.
 *
 * This would have caught a column-name/migration mismatch written into
 * the repository and the migration set together - it would NOT have
 * caught 2026-08-14's actual live incident, where the code and the
 * committed V076 migration were both correct, but V076 itself had
 * simply never been applied to dev RDS (an operational/deployment gap,
 * not a code defect - see docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md's
 * incident note). Guards against the next column/migration mismatch
 * being written in the first place.
 *
 * pgvector/pgvector:pg17 (not the bare postgres:17 image) because
 * V069/V070's `CREATE EXTENSION vector` would otherwise fail - matches
 * the pgvector-enabled Postgres 17 this project runs on RDS.
 */
@Testcontainers
class NegotiationArchiveRepositorySchemaIntegrationTest {

    private static final Pattern MIGRATION_VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    private static NegotiationArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndBuildRepository(@TempDir Path ignoredTempDir)
            throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION
                            .matcher(path.getFileName().toString())
                            .matches())
                    .sorted(Comparator.comparingInt(
                            NegotiationArchiveRepositorySchemaIntegrationTest
                                    ::migrationVersion
                    ))
                    .toList();
        }
        assertThat(migrations)
                .as("expected to find the real database/migrations/*.sql "
                        + "files - resolved directory: " + migrationsDir)
                .isNotEmpty();

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                String sql = Files.readString(migration);
                statement.execute(sql);
            }
        }

        repository = new NegotiationArchiveRepository(
                JdbcClient.create(dataSource)
        );
    }

    private static int migrationVersion(Path path) {
        Matcher matcher =
                MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Not a migration file: " + path
            );
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Path locateMigrationsDirectory() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
            candidate = candidate.getParent();
        }
        throw new IOException(
                "Could not locate database/migrations/ above "
                        + Path.of("").toAbsolutePath()
        );
    }

    /*
     * The exact query that produced the live 500 - every selected/
     * joined column (including legacy_restricted_flag, source_attachment_id,
     * source_file_id, description) must exist on the real,
     * fully-migrated archive.archived_attachment table.
     */
    @Test
    void findAttachmentsRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findAttachments(420L))
                .doesNotThrowAnyException();
        assertThat(repository.findAttachments(420L)).isEmpty();
    }

    @Test
    void findByIdRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findById(420L))
                .doesNotThrowAnyException();
        assertThat(repository.findById(420L)).isEqualTo(Optional.empty());
    }

    @Test
    void findNegotiationsRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findNegotiations("420", 25, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void findActivitiesRunsCleanlyAgainstTheRealMigratedSchema() {
        assertThatCode(() -> repository.findActivities(420L))
                .doesNotThrowAnyException();
    }
}
