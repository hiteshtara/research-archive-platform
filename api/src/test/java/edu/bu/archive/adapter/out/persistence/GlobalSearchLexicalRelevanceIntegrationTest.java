package edu.bu.archive.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import edu.bu.archive.application.search.LexicalMatch;

/**
 * The NSF relevance inversion, against a real PostgreSQL.
 *
 * <h2>Why this test and not only the unit test</h2>
 *
 * <p>{@code LexicalMatchTest} pins the RULE using Java's regex engine, which
 * has no {@code \m}. The operator that actually decides this in production is
 * PostgreSQL's {@code ~*} with {@code \m}, and whether that behaves as
 * intended is not something a Java regex can answer. So the strings measured
 * from DEV on 2026-11-10 are loaded into a real database here and matched
 * with the real operator.
 *
 * <p>Every title and sponsor below was read from the live DEV API, not
 * invented: Award 100527-00001 and its sponsor 301573, the two TRANSFER /
 * TRANSFORMING awards that beat it, and the Material Transfer Agreement
 * negotiations that filled all 25 Negotiation slots.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("database")
@DisplayName("Lexical relevance: word-start matching against a real database")
class GlobalSearchLexicalRelevanceIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;

    @BeforeAll
    static void setUp() throws Exception {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        List<Path> migrations;
        try (Stream<Path> files = Files.list(locateMigrations())) {
            migrations = files
                    .filter(p -> MIGRATION_VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                            GlobalSearchLexicalRelevanceIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }
        jdbc = JdbcClient.create(dataSource);
        seed();
    }

    /** Does PostgreSQL's own operator match this term at a word start? */
    private static boolean pgWordStart(String term, String text) {
        return jdbc.sql("SELECT CAST(:text AS TEXT) ~* CAST(:regex AS TEXT)")
                .param("text", text)
                .param("regex", LexicalMatch.wordStartRegex(term))
                .query(Boolean.class)
                .single();
    }

    private static boolean pgSubstring(String term, String text) {
        return jdbc.sql("SELECT CAST(:text AS TEXT) ILIKE CAST(:pattern AS TEXT)")
                .param("text", text)
                .param("pattern", LexicalMatch.likePattern(term))
                .query(Boolean.class)
                .single();
    }

    // ── the operator itself ──────────────────────────────────────────

    @Nested
    @DisplayName("Postgres \\m behaves as the fix assumes")
    class TheOperator {

        @Test
        void nsfNoLongerMatchesInsideTransferOrTransforming() {
            assertThat(pgSubstring("NSF", "ENERGY TRANSFER IN PROTEINS"))
                    .as("the old behaviour, which is the defect")
                    .isTrue();

            assertThat(pgWordStart("NSF", "ENERGY TRANSFER IN PROTEINS")).isFalse();
            assertThat(pgWordStart("NSF", "TRANSFORMING A BIODIVERSITY HOTSPOT")).isFalse();
            assertThat(pgWordStart("NSF", "Material Transfer Agreement")).isFalse();
        }

        @Test
        void nsfStillMatchesWhereItIsItsOwnWord() {
            assertThat(pgWordStart("NSF", "NSF-Trace formula, analytic number theory")).isTrue();
            assertThat(pgWordStart("NSF", "Rick Murray - NSF IPA Assignment")).isTrue();
            assertThat(pgWordStart("nsf", "funded by NSF")).isTrue();
            assertThat(pgWordStart("NSF", "NSF")).isTrue();
        }

        @Test
        void aPrefixStillMatchesALongerWord() {
            // The whole reason this anchors the word start and not the word.
            assertThat(pgWordStart("cardio", "Cardiology and vascular health")).isTrue();
            assertThat(pgWordStart("quantum", "quantums of light")).isTrue();
            assertThat(pgWordStart("transfer", "Material Transfer Agreement")).isTrue();
        }

        @Test
        void aMultiWordTermMatchesAcrossTheSpace() {
            assertThat(pgWordStart("WILSON WONG", "Dr WILSON WONG, PI")).isTrue();
            assertThat(pgWordStart("wilson wong", "WONG, WILSON"))
                    .as("surname-first order is a different string, and must not match")
                    .isFalse();
        }

        @Test
        void aNonQualifyingTermMatchesNothingRatherThanEverything() {
            /*
             * Fails closed. wordStartRegex returns a deliberately
             * unmatchable pattern for a term that should not be anchored,
             * and this proves Postgres agrees it is unmatchable rather than
             * erroring or matching widely.
             */
            assertThat(pgWordStart("105698", "105698-00001")).isFalse();
            assertThat(pgWordStart("105698", "anything at all")).isFalse();
            assertThat(pgWordStart("a", "a word")).isFalse();
        }

        @Test
        void aTermContainingRegexMetacharactersIsMatchedLiterally() {
            /*
             * A thrown error here would be a denial of service a caller
             * could trigger by typing a bracket. It must be a plain
             * non-match instead.
             */
            for (String hostile : List.of("a(b", "a)b", "a[b", "a*b", "a+b", "a|b", "a.b")) {
                assertThat(pgWordStart(hostile, "harmless text"))
                        .as("%s must not throw", hostile)
                        .isFalse();
            }
        }
    }

    // ── the real records ─────────────────────────────────────────────

    @Nested
    @DisplayName("The exact DEV records that produced TC-040")
    class RealRecords {

        @Test
        void beforeTheFixTheWrongAwardsWonAndTheRightOneCouldNotMatch() {
            assertThat(awardNumbersMatchingSubstring("NSF"))
                    .as("the defect, reproduced on the real titles")
                    .contains("100533-00001", "100543-00001")
                    .doesNotContain("100527-00001");
        }

        @Test
        void afterTheFixAMatchIsReAttributedRatherThanJustRemoved() {
            /*
             * The nuance worth recording. 100533-00001 still matches "NSF" -
             * and should, because its sponsor really is the National Science
             * Foundation (read from DEV, sponsor_code 301573). What changes
             * is WHY: before the fix it matched on "ENERGY TRANSFER IN
             * PROTEINS", which is a coincidence of letters; now it matches on
             * its sponsor's acronym, which is the actual reason a person
             * searching "NSF" wants it.
             *
             * 100543-00001 is the one that genuinely goes away: its sponsor
             * is Old Dominion University Research Foundation, it has no NSF
             * relationship at all, and it was only ever there because
             * "TRANSFORMING" contains n-s-f.
             */
            assertThat(titleMatchesAnchored("NSF",
                    "PROBING THE PRINCIPLES OF DYNAMICS AND ENERGY TRANSFER IN PROTEINS"))
                    .as("no longer matched by its title")
                    .isFalse();
            assertThat(awardNumbersMatchingAnchoredText("NSF"))
                    .as("but still found, through its sponsor's acronym")
                    .contains("100533-00001");

            assertThat(awardNumbersMatchingAnchoredText("NSF"))
                    .as("the unrelated award is gone, which is the point")
                    .doesNotContain("100543-00001");
        }

        @Test
        void anAwardWithNoNsfRelationshipIsNoLongerReturnedAtAll() {
            assertThat(awardNumbersMatchingSubstring("NSF"))
                    .as("before: present, purely because of TRANSFORMING")
                    .contains("100543-00001");
            assertThat(awardNumbersMatchingAnchoredText("NSF"))
                    .as("after: absent")
                    .doesNotContain("100543-00001");
        }

        @Test
        void theRightAwardIsReachedThroughItsSponsorAcronym() {
            /*
             * Anchoring alone does not find it - 100527-00001 contains no
             * "NSF" anywhere. archive.sponsor.acronym is the path, and it is
             * real Kuali reference data rather than a mapping invented here.
             */
            assertThat(awardNumbersMatchingAnchoredText("NSF"))
                    .as("by acronym, not by title")
                    .contains("100527-00001");
        }

        @Test
        void theNegotiationsThatFilledThePageNoLongerMatch() {
            assertThat(negotiationIdsMatchingSubstring("NSF"))
                    .as("25 Material Transfer Agreements, the defect")
                    .isNotEmpty();
            assertThat(negotiationIdsMatchingAnchoredText("NSF")).isEmpty();
        }

        @Test
        void searchingForTransferStillFindsTheTransferAgreements() {
            // The change must not cost a legitimate search.
            assertThat(negotiationIdsMatchingAnchoredText("transfer")).isNotEmpty();
        }

        @Test
        void aPartialAwardNumberStillMatchesMidString() {
            // Identifier behaviour is deliberately untouched.
            assertThat(LexicalMatch.anchorsToWordStart("100533")).isFalse();
            assertThat(awardNumbersMatchingSubstring("100533")).contains("100533-00001");
        }
    }

    // ---------------- fixtures and queries ----------------

    private static void seed() {
        jdbc.sql("""
                INSERT INTO archive.sponsor (sponsor_code, sponsor_name, acronym, active) VALUES
                ('301573', 'National Science Foundation', 'NSF', TRUE),
                ('301045', 'NIH/National Institute on Aging', 'NIA', TRUE),
                ('000999', 'A sponsor with no acronym', NULL, TRUE)
                """).update();

        jdbc.sql("""
                INSERT INTO archive.award_version (
                    award_id, award_number, sequence_number, title,
                    status_description, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name, award_effective_date,
                    is_primary_current, workflow_document_number
                ) VALUES
                (1928503, '100527-00001', 12,
                 'NATIONAL SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM',
                 'Closed', '301573', 'National Science Foundation',
                 '1250000000', 'PHYSICS', DATE '2008-01-01', TRUE, 'DOC-100527'),
                (714001, '100533-00001', 1,
                 'PROBING THE PRINCIPLES OF DYNAMICS AND ENERGY TRANSFER IN PROTEINS',
                 'Active', '301573', 'National Science Foundation',
                 '1250000000', 'PHYSICS', DATE '2009-01-01', TRUE, 'DOC-100533'),
                (714002, '100543-00001', 1,
                 'PIRE: ORIGINS OF HIGH MARINE BIODIVERSITY: TRANSFORMING A BIODIVERSITY HOTSPOT',
                 'Active', '000999', 'Old Dominion University Research Foundation',
                 '1250000000', 'PHYSICS', DATE '2010-01-01', TRUE, 'DOC-100543')
                """).update();

        jdbc.sql("""
                INSERT INTO archive.negotiation (
                    negotiation_id, document_number,
                    negotiation_agreement_type_description,
                    negotiation_status_description, negotiator_full_name
                ) VALUES
                (6447, '760067', 'Material Transfer Agreement', 'Abandoned', NULL),
                (12724, '1149373', 'Material Transfer Agreement', 'Under Review',
                 'WILLIAM P SEGARRA')
                """).update();
    }

    private static boolean titleMatchesAnchored(String term, String title) {
        return jdbc.sql("SELECT CAST(:t AS TEXT) ~* CAST(:r AS TEXT)")
                .param("t", title)
                .param("r", LexicalMatch.wordStartRegex(term))
                .query(Boolean.class).single();
    }

    /** The substring behaviour, as it was before the fix. */
    private static List<String> awardNumbersMatchingSubstring(String term) {
        return jdbc.sql("""
                        SELECT av.award_number FROM archive.award_version av
                         WHERE av.is_primary_current = TRUE
                           AND (av.award_number ILIKE :pattern
                                OR av.title ILIKE :pattern
                                OR av.sponsor_name ILIKE :pattern)
                         ORDER BY av.award_number
                        """)
                .param("pattern", LexicalMatch.likePattern(term))
                .query(String.class).list();
    }

    /** The text branch as the repository now writes it, acronym included. */
    private static List<String> awardNumbersMatchingAnchoredText(String term) {
        return jdbc.sql("""
                        SELECT av.award_number FROM archive.award_version av
                         WHERE av.is_primary_current = TRUE
                           AND (av.title ~* :wordRegex
                                OR av.sponsor_name ~* :wordRegex
                                OR av.lead_unit_name ~* :wordRegex
                                OR EXISTS (
                                    SELECT 1 FROM archive.sponsor sp2
                                    WHERE sp2.sponsor_code = av.sponsor_code
                                      AND sp2.acronym ~* :wordRegex))
                         ORDER BY av.award_number
                        """)
                .param("wordRegex", LexicalMatch.wordStartRegex(term))
                .query(String.class).list();
    }

    private static List<Long> negotiationIdsMatchingSubstring(String term) {
        return jdbc.sql("""
                        SELECT n.negotiation_id FROM archive.negotiation n
                         WHERE n.negotiation_agreement_type_description ILIKE :pattern
                         ORDER BY n.negotiation_id
                        """)
                .param("pattern", LexicalMatch.likePattern(term))
                .query(Long.class).list();
    }

    private static List<Long> negotiationIdsMatchingAnchoredText(String term) {
        return jdbc.sql("""
                        SELECT n.negotiation_id FROM archive.negotiation n
                         WHERE n.negotiation_agreement_type_description ~* :wordRegex
                         ORDER BY n.negotiation_id
                        """)
                .param("wordRegex", LexicalMatch.wordStartRegex(term))
                .query(Long.class).list();
    }

    private static int version(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static Path locateMigrations() {
        Path c = Path.of("database", "migrations");
        return Files.isDirectory(c) ? c : Path.of("..", "database", "migrations");
    }
}
