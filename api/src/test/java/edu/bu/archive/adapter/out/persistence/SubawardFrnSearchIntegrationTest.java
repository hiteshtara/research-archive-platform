package edu.bu.archive.adapter.out.persistence;

import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * FRN search, against a real Postgres with every committed migration
 * applied.
 *
 * "FRN" is BU's own label for Kuali's PURCHASE_ORDER_NUM - a BU
 * DataDictionary customization, not a column of its own and not a
 * field this archive needs to add. The search must reach it in three
 * already-archived places: archive.subaward.purchase_order_num,
 * archive.subaward.fsrs_subaward_number and
 * archive.subaward_amount.purchase_order_num.
 *
 * The fixture is a miniature of the real, live-verified family 1920:
 *
 *   seq 23  purchase_order_num 4500002829, fsrs 4500002829, ARCHIVED
 *           one amount row carrying 4500002829
 *   seq 56  purchase_order_num NULL, fsrs 4500003867, ACTIVE
 *           three amount rows: 4500003005, 4500003448, 4500003867
 *
 * That shape is the entire reason the correlation is on subaward_code
 * and not subaward_id: the ACTIVE record carries no purchase_order_num
 * at all, so a subaward_id-correlated predicate would let a historical
 * FRN find only retired versions and never the current record.
 *
 * Family 3210 covers the case no other column can reach - an FRN that
 * exists ONLY on an amount row (34 such FRNs exist in the archive).
 * Family 7777 is the control that must never be returned.
 *
 * These tests deliberately do NOT assert "exactly one row". This
 * search has always been version-grained - it returns one row per
 * archive.subaward version, not one per family - and this change
 * leaves that grain alone. What is asserted instead is that every
 * returned row belongs to a matching family, that no unrelated family
 * appears, and that the FRN predicates never multiply rows.
 */
@Testcontainers
class SubawardFrnSearchIntegrationTest {

    private static final Pattern MIGRATION_VERSION =
            Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres")
    );

    private static SubawardArchiveRepository repository;

    @BeforeAll
    static void applyMigrationsAndSeedTheFrnFixtures() throws Exception {
        Path migrationsDir = locateMigrationsDirectory();
        List<Path> migrations;
        try (Stream<Path> files = Files.list(migrationsDir)) {
            migrations = files
                    .filter(path -> MIGRATION_VERSION
                            .matcher(path.getFileName().toString())
                            .matches())
                    .sorted(Comparator.comparingInt(
                            SubawardFrnSearchIntegrationTest
                                    ::migrationVersion
                    ))
                    .toList();
        }
        assertThat(migrations).isNotEmpty();

        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (Path migration : migrations) {
                statement.execute(Files.readString(migration));
            }

            statement.execute("""
                    INSERT INTO archive.subaward (
                        subaward_id, subaward_code, sequence_number,
                        document_number, title, status_description,
                        organization_id, account_number,
                        purchase_order_num, fsrs_subaward_number,
                        subaward_sequence_status, source_update_timestamp
                    ) VALUES
                    (34262, '1920', 23, 'DOC-1920-23',
                     'Engineered Invasive Human Breast Tumors',
                     '07. Executed', 'ORG-1', 'ACCT-1920',
                     '4500002829', '4500002829',
                     'ARCHIVED', TIMESTAMP '2018-10-11 00:00:00'),
                    (69801, '1920', 56, 'DOC-1920-56',
                     'Engineered Invasive Human Breast Tumors',
                     '07. Executed', 'ORG-1', 'ACCT-1920',
                     NULL, '4500003867',
                     'ACTIVE', TIMESTAMP '2021-03-02 00:00:00'),
                    (90001, '3210', 1, 'DOC-3210-1',
                     'Amount-only FRN family', '07. Executed',
                     'ORG-2', 'ACCT-3210', NULL, NULL,
                     'ACTIVE', TIMESTAMP '2019-01-01 00:00:00'),
                    (90002, '7777', 1, 'DOC-7777-1',
                     'Unrelated control family', '07. Executed',
                     'ORG-3', 'ACCT-7777', '4500009999', '4500009999',
                     'ACTIVE', TIMESTAMP '2019-01-01 00:00:00'),
                    (90003, '8888', 1, 'DOC-8888-1',
                     'Leading zero FRN family', '07. Executed',
                     'ORG-4', 'ACCT-8888', '0000000000', '0000000000',
                     'ACTIVE', TIMESTAMP '2019-01-01 00:00:00'),
                    -- Incidental "1920" collisions, copied from the real
                    -- dev rows that outranked Subaward 1920: one matches
                    -- only on document_number, the other only on the
                    -- digits of its own primary key. Both are NEWER than
                    -- 1920's ACTIVE row, which is exactly why they used
                    -- to sort above it.
                    (81920, '3760', 1, 'DOC-3760-1',
                     'Unrelated, matches on subaward_id digits',
                     '07. Executed', 'ORG-5', 'ACCT-3760', NULL, NULL,
                     'ARCHIVED', TIMESTAMP '2023-11-13 11:21:23'),
                    (85686, '2580', 52, '1091920',
                     'Unrelated, matches on document_number',
                     '07. Executed', 'ORG-6', 'ACCT-2580', NULL, NULL,
                     'ACTIVE', TIMESTAMP '2024-08-29 16:09:55')
                    """);

            statement.execute("""
                    INSERT INTO archive.subaward_amount (
                        subaward_amount_info_id, subaward_id,
                        subaward_code, sequence_number,
                        purchase_order_num, obligated_change
                    ) VALUES
                    (1, 34262, '1920', 23, '4500002829', 100),
                    (2, 69801, '1920', 56, '4500003005', 200),
                    (3, 69801, '1920', 56, '4500003448', 300),
                    (4, 69801, '1920', 56, '4500003867', 400),
                    (5, 90001, '3210', 1,  '4500005236', 500),
                    (6, 90002, '7777', 1,  '4500009999', 600)
                    """);
        }

        repository = new SubawardArchiveRepository(
                JdbcClient.create(dataSource));
    }

    private static List<String> codesFor(String query) {
        return repository.findSubawards(query, 100, 0).stream()
                .map(SubawardSummaryResponse::subawardCode)
                .toList();
    }

    /* 1. Historical parent/version FRN, no longer on the ACTIVE row. */
    @Test
    void aHistoricalFrnMakesItsWholeFamilyDiscoverable() {
        assertThat(codesFor("4500002829"))
                .isNotEmpty()
                .containsOnly("1920");
    }

    /*
     * The same query must reach the CURRENT record, not only the
     * retired versions that still carry the value - this is what a
     * subaward_id-correlated predicate would get wrong.
     */
    @Test
    void aHistoricalFrnReachesTheFamilysActiveRecord() {
        assertThat(repository.findSubawards("4500002829", 100, 0))
                .extracting(SubawardSummaryResponse::subawardId)
                .contains(69801L);
    }

    /* 2. Current FSRS number. */
    @Test
    void theCurrentFsrsNumberFindsTheSameFamily() {
        assertThat(codesFor("4500003867")).containsOnly("1920");
    }

    /* 3. Amount-level FRNs, including ones on the ACTIVE version. */
    @Test
    void everyAmountLevelFrnOnTheFamilyFindsIt() {
        assertThat(codesFor("4500003005")).containsOnly("1920");
        assertThat(codesFor("4500003448")).containsOnly("1920");
    }

    /*
     * The clause no other column can substitute for: this FRN exists
     * only on an amount row.
     */
    @Test
    void anFrnThatExistsOnlyOnAnAmountRowIsStillDiscoverable() {
        assertThat(codesFor("4500005236"))
                .isNotEmpty()
                .containsOnly("3210");
    }

    /* 4. No unrelated family is ever dragged in. */
    @Test
    void anUnrelatedFrnReturnsOnlyItsOwnFamily() {
        assertThat(codesFor("4500009999")).containsOnly("7777");
    }

    @Test
    void anFrnThatMatchesNothingReturnsNothing() {
        assertThat(repository.findSubawards("4500000001", 100, 0)).isEmpty();
        assertThat(repository.countSubawards("4500000001")).isZero();
    }

    /* 5. The predicates are a semi-join: they never multiply rows. */
    @Test
    void theFrnPredicatesNeverDuplicateARow() {
        List<SubawardSummaryResponse> rows =
                repository.findSubawards("4500003867", 100, 0);

        assertThat(rows)
                .extracting(SubawardSummaryResponse::subawardId)
                .doesNotHaveDuplicates();
    }

    /*
     * Family 1920's ACTIVE version has three distinct amount-level
     * FRNs. If subaward_amount were joined rather than EXISTS-ed, that
     * row would appear three times.
     */
    @Test
    void aVersionWithSeveralAmountFrnsStillAppearsOnce() {
        assertThat(repository.findSubawards("4500003867", 100, 0))
                .filteredOn(row -> row.subawardId() == 69801L)
                .hasSize(1);
    }

    /* 6. Count and page agree. */
    @Test
    void theCountMatchesTheRowsActuallyReturned() {
        for (String query : List.of("4500002829", "4500003867",
                "4500005236", "4500009999", "1920")) {
            assertThat(repository.countSubawards(query))
                    .as("count must match page rows for " + query)
                    .isEqualTo(repository.findSubawards(query, 100, 0).size());
        }
    }

    /* 7. Every pre-existing search dimension still works. */
    @Test
    void theExistingSearchDimensionsStillWork() {
        assertThat(codesFor("1920"))
                .contains("1920", "3760", "2580");
        assertThat(codesFor("DOC-7777-1")).containsOnly("7777");
        assertThat(codesFor("ACCT-3210")).containsOnly("3210");
        assertThat(codesFor("Unrelated control")).containsOnly("7777");
        assertThat(codesFor("ORG-2")).containsOnly("3210");
    }

    /* 8. Leading zeros survive - these are text columns, never cast. */
    @Test
    void anFrnWithLeadingZerosIsMatchedExactlyAsStored() {
        assertThat(codesFor("0000000000")).containsOnly("8888");
    }

    /*
     * The grain is unchanged and is deliberately NOT one row per
     * family: family 1920 has two archived versions and both come back.
     */
    @Test
    void theResultGrainIsStillOneRowPerVersionNotPerFamily() {
        assertThat(repository.findSubawards("4500002829", 100, 0))
                .hasSize(2)
                .extracting(SubawardSummaryResponse::subawardId)
                .containsExactlyInAnyOrder(34262L, 69801L);
    }

    private static int migrationVersion(Path path) {
        Matcher matcher =
                MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a migration file: " + path);
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
        throw new IOException("Could not locate database/migrations/");
    }

    /*
     * --- Exact-code ranking -------------------------------------------
     *
     * Reproduces the real dev situation for q=1920: 1920's own ACTIVE
     * record used to sit at rank 6, behind rows that matched only the
     * digits of a document number or a primary key and happened to be
     * updated more recently.
     */
    @Test
    void anExactSubawardCodeRanksFirstAndItsActiveRecordLeads() {
        List<SubawardSummaryResponse> rows =
                repository.findSubawards("1920", 100, 0);

        assertThat(rows.get(0).subawardCode()).isEqualTo("1920");
        assertThat(rows.get(0).subawardId()).isEqualTo(69801L);
        assertThat(rows.get(0).subawardSequenceStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void theExactCodesArchivedVersionsFollowBeforeUnrelatedCollisions() {
        List<String> codes = repository.findSubawards("1920", 100, 0)
                .stream().map(SubawardSummaryResponse::subawardCode).toList();

        assertThat(codes.subList(0, 2)).containsOnly("1920");
        assertThat(codes.indexOf("3760")).isGreaterThan(codes.lastIndexOf("1920"));
        assertThat(codes.indexOf("2580")).isGreaterThan(codes.lastIndexOf("1920"));
    }

    /*
     * A query that matches no Subaward code must order exactly as it did
     * before ranking existed: both CASE keys collapse to one constant,
     * leaving source_update_timestamp DESC, sequence_number DESC,
     * subaward_id DESC.
     */
    @Test
    void aNonIdentifierTextQueryKeepsItsOriginalOrdering() {
        List<SubawardSummaryResponse> rows =
                repository.findSubawards("Unrelated", 100, 0);

        assertThat(rows)
                .extracting(SubawardSummaryResponse::subawardId)
                .containsExactly(85686L, 81920L, 90002L);
    }

    /* Ranking is ORDER BY only - it cannot change how many rows match. */
    @Test
    void rankingDoesNotChangeTheCount() {
        assertThat(repository.countSubawards("1920"))
                .isEqualTo(repository.findSubawards("1920", 100, 0).size());
        assertThat(repository.countSubawards("Unrelated"))
                .isEqualTo(repository.findSubawards("Unrelated", 100, 0).size());
    }

    /*
     * --- The FRN gate --------------------------------------------------
     *
     * A non-FRN-shaped query must not reach the FRN columns at all. This
     * is the behavioural half of the performance gate: "1920" is four
     * digits, so it can never be an FRN, and family 8888 - whose only
     * link to any query is its 0000000000 FRN - must not appear.
     */
    @Test
    void aQueryThatCannotBeAnFrnDoesNotSearchTheFrnColumns() {
        assertThat(codesFor("1920")).doesNotContain("8888");
        assertThat(codesFor("0000")).isEmpty();
    }

    @Test
    void aTenDigitQueryStillReachesTheFrnColumns() {
        assertThat(codesFor("0000000000")).containsOnly("8888");
    }
}
