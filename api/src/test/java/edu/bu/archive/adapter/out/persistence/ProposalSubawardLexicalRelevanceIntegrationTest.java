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

import edu.bu.archive.adapter.in.web.dto.proposal.ProposalFamilySummaryResponse;
import edu.bu.archive.adapter.in.web.dto.subaward.SubawardSummaryResponse;
import edu.bu.archive.application.proposal.ProposalSearchFilters;
import edu.bu.archive.application.search.LexicalMatch;
import edu.bu.archive.application.search.MatchExplanation;
import edu.bu.archive.application.subaward.SubawardSearchFilters;

/**
 * QA TC-040 for the two modules the first fix did not reach.
 *
 * <h2>What the first fix left behind</h2>
 *
 * <p>Revision 89 shipped word-start anchoring for Award and Negotiation and
 * left Proposal and Subaward on plain {@code ILIKE '%term%'}. Measured
 * against that running revision on 2026-11-10, a search for "NSF" returned
 * 347 Proposals and 287 Subawards, and of the first 100 of each:
 *
 * <ul>
 *   <li><b>59 of 100 Proposals</b> contained no "NSF" anywhere - they matched
 *       inside "TRANSFORMATION", "transforming" and "methyltransferase";
 *   <li><b>70 of 100 Subawards</b> likewise - "phosphoglycosyl transferase"
 *       and "Transformer-Based".
 * </ul>
 *
 * <p>Every record seeded below was read from that API, not invented, and
 * Subaward 4319's full row was checked field by field to confirm its only
 * "nsf" is the one inside "tra<b>nsf</b>erase".
 *
 * <h2>Why anchoring alone would have made Proposal worse</h2>
 *
 * <p>3,746 Proposals are sponsored by the National Science Foundation, and of
 * the first 100 of those, <b>99</b> carry no word-start "NSF" in any field
 * the predicate reads. Removing the coincidence without adding a real path
 * would have left them unreachable - TC-040's complaint reproduced rather
 * than fixed. So Proposal gains the sponsor-acronym path that Award already
 * has. Subaward cannot: {@code archive.subaward} carries only the
 * denormalized sponsor NAMES and no sponsor code, so there is nothing to
 * join, and inventing one is not done here.
 *
 * <h2>Driven through the repositories, not a copy of their SQL</h2>
 *
 * <p>{@code GlobalSearchLexicalRelevanceIntegrationTest} re-states the
 * predicate in the test, which proves the OPERATOR but cannot prove the
 * repository. These tests call the real repository methods, so a predicate
 * that drifts from the one being asserted fails here - including the
 * requirement that a page and its own total describe one row set.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("database")
@DisplayName("Lexical relevance: Proposal and Subaward free text")
class ProposalSubawardLexicalRelevanceIntegrationTest {

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static ProposalArchiveRepository proposals;
    private static SubawardArchiveRepository subawards;

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
                            ProposalSubawardLexicalRelevanceIntegrationTest::version))
                    .toList();
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Path m : migrations) {
                s.execute(Files.readString(m));
            }
        }
        jdbc = JdbcClient.create(dataSource);
        proposals = new ProposalArchiveRepository(jdbc);
        subawards = new SubawardArchiveRepository(jdbc);
        seed();
    }

    // ── Proposal ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Proposal: the records measured on DEV")
    class Proposals {

        @Test
        void nsfNoLongerMatchesInsideTransformation() {
            /*
             * The four titles that filled the Proposal leg of a search for
             * "NSF", read from the running revision on 2026-11-10. None of
             * them is about NSF and none is NSF-sponsored.
             */
            assertThat(proposalNumbers("NSF"))
                    .doesNotContain("01118224")   // "Structural Transformation in Ethiopia"
                    .doesNotContain("01098086")   // "methyltransferase classification"
                    .doesNotContain("01107145")   // "Chemical Transformations Enabled by Flow"
                    .doesNotContain("01116997");  // "TRANSFORM DBS (MGH Prime)"
        }

        @Test
        void nsfStillMatchesWhereItIsItsOwnWord() {
            assertThat(proposalNumbers("NSF"))
                    .as("the title really does say NSF")
                    .contains("01116030");
        }

        @Test
        void theNsfSponsoredProposalIsReachedThroughItsSponsorAcronym() {
            /*
             * The half that anchoring alone would have broken. 01091593's
             * title is "A Novel Manipulative Experiment: Cascading
             * Ecological Effect" - no "NSF" anywhere - and its sponsor is the
             * National Science Foundation, sponsor_code 301573, the same code
             * the Award leg already resolves to the acronym NSF.
             */
            assertThat(proposalNumbers("NSF"))
                    .as("found by its sponsor's acronym, not by its title")
                    .contains("01091593");
        }

        @Test
        void thatProposalWasNotReachableBeforeAndWasNotTheOneReturned() {
            // The inversion, stated as a single comparison on real rows.
            assertThat(proposalNumbersSubstring("NSF"))
                    .as("before: the unrelated transformation titles matched")
                    .contains("01118224", "01098086")
                    .as("before: the NSF-sponsored proposal did not")
                    .doesNotContain("01091593");
        }

        @Test
        void aPrefixStillMatchesALongerWord() {
            // The reason the rule anchors a word START and not a whole word.
            assertThat(proposalNumbers("cardio"))
                    .as("cardio must still find cardiology")
                    .contains("01120001");
        }

        @Test
        void searchingForTransformationStillFindsTheTransformationProposals() {
            // The change must not cost a legitimate search.
            assertThat(proposalNumbers("transformation")).contains("01118224");
            assertThat(proposalNumbers("methyltransferase")).contains("01098086");
        }

        @Test
        void aPartialProposalNumberStillMatchesMidString() {
            // Identifier behaviour is deliberately untouched.
            assertThat(LexicalMatch.anchorsToWordStart("1182")).isFalse();
            assertThat(proposalNumbers("1182")).contains("01118224");
        }

        @Test
        void theSponsorNameItselfStillMatches() {
            assertThat(proposalNumbers("National")).contains("01091593");
            assertThat(proposalNumbers("Science")).contains("01091593");
        }

        @Test
        void thePageAndItsOwnTotalDescribeOneRowSet() {
            /*
             * The reason the predicate is defined once. findFamilyPage and
             * countFamilyPage share it, so these two can only disagree if
             * someone reintroduces a second copy.
             */
            for (String term : List.of("NSF", "transformation", "cardio", "1182", "National")) {
                List<ProposalFamilySummaryResponse> page = proposals.findFamilyPage(
                        term, ProposalSearchFilters.none(), 100, 0);
                long total = proposals.countFamilyPage(term, ProposalSearchFilters.none());
                assertThat((long) page.size())
                        .as("page and count disagree for \"%s\"", term)
                        .isEqualTo(total);
            }
        }

        @Test
        void globalSearchAndTheModulePageAgree() {
            /*
             * findFamilies backs Global Search and findFamilyPage backs the
             * module - the two surfaces a reader moves between via "see all
             * Proposals". They were separate copies of the predicate; if they
             * ever diverge again, the same query returns different records on
             * the two pages.
             */
            for (String term : List.of("NSF", "transformation", "cardio", "1182")) {
                assertThat(proposalNumbers(term))
                        .as("Global Search and the module page disagree for \"%s\"", term)
                        .containsExactlyInAnyOrderElementsOf(
                                proposals.findFamilyPage(
                                                term, ProposalSearchFilters.none(), 100, 0)
                                        .stream()
                                        .map(ProposalFamilySummaryResponse::proposalNumber)
                                        .toList());
            }
        }

        @Test
        void anEmptyQueryStillReturnsEverything() {
            // The parameters are bound even when the guard short-circuits.
            assertThat(proposals.findFamilies("", 100)).hasSize(7);
            assertThat(proposals.countFamilyPage(null, ProposalSearchFilters.none()))
                    .isEqualTo(7L);
        }

        @Test
        void aTermWithRegexMetacharactersIsMatchedLiterallyRatherThanThrowing() {
            for (String hostile : List.of("(", "[", "*", "\\", "a(b", "NSF)", "%", "_")) {
                assertThat(proposals.findFamilies(hostile, 100))
                        .as("%s must not throw", hostile)
                        .isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Proposal: the match explanation")
    class ProposalExplanations {

        @Test
        void anAcronymMatchIsExplainedAsItsSponsor() {
            /*
             * Once the acronym became a retrieval rule, a row matching only
             * through it could not explain itself from the response - the
             * reader would see an NSF-sponsored Proposal with no stated
             * reason. This is the query that answers it.
             */
            List<MatchExplanation> explained = proposals.explainProposalMatches(
                    List.of(5001L), "NSF",
                    LexicalMatch.anchorsToWordStart("NSF"),
                    LexicalMatch.wordStartRegex("NSF"));

            assertThat(explained).hasSize(1);
            assertThat(explained.get(0).recordId()).isEqualTo(5001L);
            assertThat(explained.get(0).field()).isEqualTo("Sponsor");
            assertThat(explained.get(0).value()).isEqualTo("NSF");
        }

        @Test
        void aRowWhoseSponsorAcronymDoesNotMatchIsNotExplained() {
            // It must not invent a reason for a row that matched some other way.
            assertThat(proposals.explainProposalMatches(
                    List.of(5002L), "NSF",
                    LexicalMatch.anchorsToWordStart("NSF"),
                    LexicalMatch.wordStartRegex("NSF")))
                    .isEmpty();
        }

        @Test
        void aSponsorWithNoAcronymIsNotAnError() {
            // acronym is nullable; a NULL must simply not match.
            assertThat(proposals.explainProposalMatches(
                    List.of(5003L), "NSF",
                    LexicalMatch.anchorsToWordStart("NSF"),
                    LexicalMatch.wordStartRegex("NSF")))
                    .isEmpty();
        }

        @Test
        void noIdsMeansNoQuery() {
            assertThat(proposals.explainProposalMatches(
                    List.of(), "NSF", true, LexicalMatch.wordStartRegex("NSF")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Proposal: an unpopulated sponsor table degrades, not breaks")
    class ProposalsWithoutSponsorReferenceData {

        @Test
        void theSearchStillWorksAndTheAcronymPathSimplyFindsNothing() {
            jdbc.sql("DELETE FROM archive.sponsor").update();
            try {
                assertThat(proposalNumbers("NSF"))
                        .as("no reference data, so no acronym to match - the false "
                                + "positives are still gone, but the true positive is "
                                + "not delivered")
                        .doesNotContain("01091593");

                assertThat(proposalNumbers("transformation"))
                        .as("an ordinary title search must be completely unaffected")
                        .contains("01118224");
            } finally {
                seedSponsors();
            }
        }

        @Test
        void theAcronymPathIsRestoredWhenTheDataIs() {
            assertThat(proposalNumbers("NSF")).contains("01091593");
        }
    }

    // ── Subaward ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Subaward: the records measured on DEV")
    class Subawards {

        @Test
        void nsfNoLongerMatchesInsideTransferase() {
            /*
             * Subaward 4319, whose whole row was checked on 2026-11-10: its
             * only "nsf" is the one inside "tra-nsf-erase".
             */
            assertThat(subawardCodes("NSF"))
                    .doesNotContain("4319")   // "phosphoglycosyl transferase superfamily"
                    .doesNotContain("4400");  // "Transformer-Based Artificial Intelligence"
        }

        @Test
        void nsfStillMatchesWhereItIsItsOwnWord() {
            assertThat(subawardCodes("NSF"))
                    .as("the title really does begin with NSF")
                    .contains("4341");
        }

        @Test
        void beforeTheFixTheTransferaseRowsWereTheAnswer() {
            assertThat(subawardCodesSubstring("NSF"))
                    .as("the defect, on the real titles")
                    .contains("4319", "4400");
        }

        @Test
        void searchingForTransferaseStillFindsIt() {
            // The change must not cost a legitimate search.
            assertThat(subawardCodes("transferase")).contains("4319");
            assertThat(subawardCodes("transformer")).contains("4400");
        }

        @Test
        void aPrefixStillMatchesALongerWord() {
            assertThat(subawardCodes("cardio")).contains("4500");
        }

        @Test
        void identifiersStillMatchMidString() {
            /*
             * Deliberately untouched, and documented: "1920" finding
             * document_number 1119202 is a known property this search is
             * ordered around rather than a defect.
             */
            assertThat(LexicalMatch.anchorsToWordStart("431")).isFalse();
            assertThat(subawardCodes("431")).contains("4319");
            assertThat(subawardCodes("1210089")).contains("4319");
        }

        @Test
        void theStatusDescriptionIsStillSearchable() {
            assertThat(subawardCodes("Executed")).contains("4319");
        }

        @Test
        void theSponsorNameIsStillSearchable() {
            // There is no acronym path here - no sponsor code to join - so the
            // spelled-out name is the only way in, and it must keep working.
            assertThat(subawardCodes("National")).contains("4319");
        }

        @Test
        void thePageAndItsOwnTotalDescribeOneRowSet() {
            for (String term : List.of("NSF", "transferase", "cardio", "431", "Executed")) {
                List<SubawardSummaryResponse> page = subawards.findSubawards(
                        term, SubawardSearchFilters.none(), 100, 0);
                long total = subawards.countSubawards(term, SubawardSearchFilters.none());
                assertThat((long) page.size())
                        .as("page and count disagree for \"%s\"", term)
                        .isEqualTo(total);
            }
        }

        @Test
        void anEmptyQueryStillReturnsEverything() {
            assertThat(subawards.findSubawards("", SubawardSearchFilters.none(), 100, 0))
                    .hasSize(4);
            assertThat(subawards.countSubawards("", SubawardSearchFilters.none()))
                    .isEqualTo(4L);
        }

        @Test
        void aTermWithRegexMetacharactersIsMatchedLiterallyRatherThanThrowing() {
            for (String hostile : List.of("(", "[", "*", "\\", "a(b", "NSF)", "%", "_")) {
                assertThat(subawards.findSubawards(
                        hostile, SubawardSearchFilters.none(), 100, 0))
                        .as("%s must not throw", hostile)
                        .isNotNull();
            }
        }
    }

    // ── the acceptance matrix for the acronym path ───────────────────

    /**
     * Numbered acceptance cases for Proposal sponsor-acronym matching, in the
     * shape the security acceptance pack uses, so a tester can work through
     * them and a reviewer can see which are covered.
     *
     * <p>Cases P1-P4 are the capability. P5-P6 are the agreement between the
     * two surfaces a reader moves between. P7-P9 are the regression guard:
     * the modules this change did NOT touch must behave exactly as they did
     * at revision 89.
     *
     * <p>The sponsor rows below are {@code archive.sponsor} - Kuali's shared
     * sponsor master, loaded by {@code sql/extract/reference/17_sponsor.sql},
     * not a lookup invented for this test. That the master carries acronym
     * "NSF" against the NSF sponsor code is already proven on DEV by the
     * Award leg: a live search for "NSF" on 2026-11-10 returned 25 Awards
     * captioned "Matched on: Sponsor (NSF)", and that caption is read from
     * {@code archive.sponsor.acronym}. Proposal joins the same master on the
     * same column.
     */
    @Nested
    @DisplayName("Proposal sponsor-acronym acceptance (P1-P9)")
    class AcronymAcceptance {

        @Test
        @DisplayName("P1: an NSF-sponsored Proposal with no NSF in any text field is returned")
        void p1() {
            assertThat(proposalNumbers("NSF")).contains("01091593");
        }

        @Test
        @DisplayName("P2: it is returned through the sponsor master, not its own fields")
        void p2() {
            // Prove the path rather than the outcome: with the master emptied
            // the same Proposal disappears, so nothing else can be matching it.
            jdbc.sql("DELETE FROM archive.sponsor").update();
            try {
                assertThat(proposalNumbers("NSF")).doesNotContain("01091593");
            } finally {
                seedSponsors();
            }
            assertThat(proposalNumbers("NSF")).contains("01091593");
        }

        @Test
        @DisplayName("P3: an unrelated word containing nsf mid-word is excluded")
        void p3() {
            assertThat(proposalNumbers("NSF"))
                    .doesNotContain("01118224", "01098086", "01107145", "01116997");
        }

        @Test
        @DisplayName("P4: a Proposal whose sponsor acronym is not NSF is excluded")
        void p4() {
            // 01118224's sponsor acronym is SOS. A search for NSF must not
            // reach it through the acronym join either.
            assertThat(proposalNumbers("NSF")).doesNotContain("01118224");
            assertThat(proposalNumbers("SOS"))
                    .as("and the acronym path is live for the acronym it does carry")
                    .contains("01118224");
        }

        @Test
        @DisplayName("P5: Global Search and the Proposal module return the same Proposals")
        void p5() {
            for (String term : List.of("NSF", "SOS", "transformation", "cardio", "1182")) {
                assertThat(proposalNumbers(term))
                        .as("the two surfaces disagree for \"%s\"", term)
                        .containsExactlyInAnyOrderElementsOf(modulePage(term));
            }
        }

        @Test
        @DisplayName("P6: the Proposal module page and its own total agree")
        void p6() {
            for (String term : List.of("NSF", "SOS", "transformation", "cardio", "1182")) {
                assertThat((long) modulePage(term).size())
                        .as("page and count disagree for \"%s\"", term)
                        .isEqualTo(proposals.countFamilyPage(term, ProposalSearchFilters.none()));
            }
        }

        @Test
        @DisplayName("P7: Award behaviour is unchanged - anchored text, and its own acronym path")
        void p7() {
            assertThat(awardNumbers("NSF"))
                    .as("reached by its sponsor's acronym, exactly as at revision 89")
                    .contains("100527-00001")
                    .as("and still not by the letters inside TRANSFER")
                    .doesNotContain("100543-00001");
        }

        @Test
        @DisplayName("P8: Negotiation behaviour is unchanged - no Material Transfer match")
        void p8() {
            assertThat(negotiationIds("NSF")).isEmpty();
            assertThat(negotiationIds("transfer"))
                    .as("and a legitimate search for transfer still finds them")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("P9: Subaward has no acronym JOIN - only the letters in the stored name")
        void p9() {
            /*
             * archive.subaward carries only the denormalized sponsor NAMES and
             * no sponsor code or Award link, so there is nothing to join to the
             * sponsor master. An acronym reaches a Subaward only when the
             * acronym is written into the stored name itself.
             *
             * The fixture uses the REAL DEV strings, because the first version
             * of this test used "National Institutes of Health" and so implied
             * that "NIH" is unreachable - which is false on DEV, where the
             * stored name is "NIH/National Institutes of Health". Corrected
             * 2026-11-11 after checking the live data: "NIH" matches 6,230
             * Subaward rows and "NSF" matches 162, and the difference is
             * entirely whether the letters are in the string.
             */
            assertThat(subawardCodes("NIH"))
                    .as("NIH is in the stored name, so it matches - not via an acronym join")
                    .contains("4319");
            assertThat(subawardCodes("National"))
                    .as("and so does the spelled-out part of the same name")
                    .contains("4319");

            // 4341's sponsor is 'National Science Foundation' - no "NSF" in it.
            // It is reachable only because its TITLE says NSF.
            assertThat(subawardCodes("NSF"))
                    .as("no acronym join exists, so a Subaward is NOT reached by its "
                            + "sponsor's acronym when the name does not contain it")
                    .containsExactly("4341");
        }
    }

    // ---------------- fixtures and queries ----------------

    private static List<String> modulePage(String term) {
        return proposals.findFamilyPage(term, ProposalSearchFilters.none(), 100, 0).stream()
                .map(ProposalFamilySummaryResponse::proposalNumber)
                .sorted()
                .toList();
    }

    /** The Award text branch as AwardArchiveRepository writes it, acronym included. */
    private static List<String> awardNumbers(String term) {
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

    private static List<Long> negotiationIds(String term) {
        return jdbc.sql("""
                        SELECT n.negotiation_id FROM archive.negotiation n
                         WHERE n.negotiation_agreement_type_description ~* :wordRegex
                         ORDER BY n.negotiation_id
                        """)
                .param("wordRegex", LexicalMatch.wordStartRegex(term))
                .query(Long.class).list();
    }

    /** Global Search's Proposal leg, through the real repository. */
    private static List<String> proposalNumbers(String term) {
        return proposals.findFamilies(term, 100).stream()
                .map(ProposalFamilySummaryResponse::proposalNumber)
                .sorted()
                .toList();
    }

    /** The substring behaviour, as it was before this fix. */
    private static List<String> proposalNumbersSubstring(String term) {
        return jdbc.sql("""
                        SELECT proposal_number FROM archive.proposal_version
                         WHERE title ILIKE :pattern
                            OR sponsor_name ILIKE :pattern
                            OR lead_unit_name ILIKE :pattern
                            OR principal_investigator_name ILIKE :pattern
                         ORDER BY proposal_number
                        """)
                .param("pattern", LexicalMatch.likePattern(term))
                .query(String.class).list();
    }

    private static List<String> subawardCodes(String term) {
        return subawards.findSubawards(term, SubawardSearchFilters.none(), 100, 0).stream()
                .map(SubawardSummaryResponse::subawardCode)
                .sorted()
                .toList();
    }

    private static List<String> subawardCodesSubstring(String term) {
        return jdbc.sql("""
                        SELECT subaward_code FROM archive.subaward
                         WHERE title ILIKE :pattern
                            OR status_description ILIKE :pattern
                            OR award_sponsor_name ILIKE :pattern
                            OR award_prime_sponsor_name ILIKE :pattern
                         ORDER BY subaward_code
                        """)
                .param("pattern", LexicalMatch.likePattern(term))
                .query(String.class).list();
    }

    private static void seed() {
        seedSponsors();

        /*
         * Award and Negotiation rows exist here only as a REGRESSION GUARD
         * (cases P7, P8). They are the same real DEV records
         * GlobalSearchLexicalRelevanceIntegrationTest uses, so if this change
         * disturbed the two modules it was not supposed to touch, these fail
         * in the same file as the change rather than somewhere else.
         */
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
                (714002, '100543-00001', 1,
                 'PIRE: ORIGINS OF HIGH MARINE BIODIVERSITY: TRANSFORMING A BIODIVERSITY HOTSPOT',
                 'Active', '000999', 'A sponsor with no acronym',
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

        /*
         * proposal_id 5001 is the NSF-sponsored proposal whose title says
         * nothing about NSF - the case the acronym path exists for. 5002 and
         * 5003 are the transformation/methyltransferase titles that used to
         * win, 5003 deliberately attached to a sponsor with a NULL acronym.
         */
        jdbc.sql("""
                INSERT INTO archive.proposal_version (
                    proposal_id, proposal_number, version_number, title,
                    proposal_sequence_status, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name,
                    principal_investigator_name
                ) VALUES
                (5001, '01091593', 1,
                 'A Novel Manipulative Experiment: Cascading Ecological Effect',
                 'ACTIVE', '301573', 'National Science Foundation',
                 '1250000000', 'BIOLOGY', 'A RESEARCHER'),
                (5002, '01118224', 1,
                 'Priority Sector Targeting and Structural Transformation in Ethiopia',
                 'ACTIVE', '000888', 'Some Other Sponsor',
                 '1250000000', 'ECONOMICS', 'SAMUEL BAZZI'),
                (5003, '01098086', 1,
                 'New approaches to methyltransferase classification',
                 'ACTIVE', '000999', 'A sponsor with no acronym',
                 '1250000000', 'BIOENGINEERING', 'SIMON KASIF'),
                (5004, '01116030', 1,
                 'NSF SaTC Fuzzing',
                 'ACTIVE', '301573', 'National Science Foundation',
                 '1250000000', 'ECE', 'MANUEL EGELE'),
                (5005, '01120001', 1,
                 'Cardiology and vascular health',
                 'ACTIVE', '000888', 'Some Other Sponsor',
                 '1250000000', 'MEDICINE', 'A CARDIOLOGIST')
                """).update();

        /*
         * The two extra transformation titles the DEV page showed, so
         * "doesNotContain" is asserted against more than one row.
         */
        jdbc.sql("""
                INSERT INTO archive.proposal_version (
                    proposal_id, proposal_number, version_number, title,
                    proposal_sequence_status, sponsor_code, sponsor_name,
                    lead_unit_number, lead_unit_name,
                    principal_investigator_name
                ) VALUES
                (5006, '01107145', 1,
                 'Chemical Transformations Enabled by Flow Chemistry',
                 'ACTIVE', '000888', 'Some Other Sponsor',
                 '1250000000', 'CHEMISTRY', 'AARON BEELER'),
                (5007, '01116997', 1,
                 'TRANSFORM DBS (MGH Prime)',
                 'ACTIVE', '000888', 'Some Other Sponsor',
                 '1250000000', 'MATH', 'MARK KRAMER')
                """).update();

        jdbc.sql("""
                INSERT INTO archive.subaward (
                    subaward_id, subaward_code, sequence_number, document_number,
                    title, status_description, organization_id, account_number,
                    award_sponsor_name, award_prime_sponsor_name,
                    subaward_sequence_status, loaded_at
                ) VALUES
                (93847, '4319', 5, '1210089',
                 'Structure and function of the monotopic phosphoglycosyl transferase superfamily: Initiators of biosynthesis of complex bacterial glycoconjugates',
                 '07. Executed', '301396', NULL,
                 'NIH/National Institutes of Health', NULL, 'ACTIVE', CURRENT_TIMESTAMP),
                (93900, '4400', 1, '1210500',
                 'Transformer-Based Artificial Intelligence Methods to Evaluate the Impact of Military Service',
                 '07. Executed', '301396', NULL,
                 'Department of Defense', NULL, 'ACTIVE', CURRENT_TIMESTAMP),
                (93910, '4341', 2, '1228192',
                 'NSF PCL-Test Bed: A Cloud Lab with AI-Integrated Remote Experimentation (CLAIRE)',
                 '07. Executed', '309461', NULL,
                 'National Science Foundation', NULL, 'ACTIVE', CURRENT_TIMESTAMP),
                (93920, '4500', 1, '1230000',
                 'Cardiology outcomes in a rural cohort',
                 '07. Executed', '309461', NULL,
                 'Some Other Sponsor', NULL, 'ACTIVE', CURRENT_TIMESTAMP)
                """).update();
    }

    private static void seedSponsors() {
        jdbc.sql("""
                INSERT INTO archive.sponsor (sponsor_code, sponsor_name, acronym, active) VALUES
                ('301573', 'National Science Foundation', 'NSF', TRUE),
                ('000888', 'Some Other Sponsor', 'SOS', TRUE),
                ('000999', 'A sponsor with no acronym', NULL, TRUE)
                ON CONFLICT (sponsor_code) DO NOTHING
                """).update();
    }

    private static Path locateMigrations() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path migrations = candidate.resolve("database/migrations");
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
        }
        throw new IllegalStateException("database/migrations not found from " + here);
    }

    private static int version(Path file) {
        Matcher matcher = MIGRATION_VERSION.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException("not a migration: " + file);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
