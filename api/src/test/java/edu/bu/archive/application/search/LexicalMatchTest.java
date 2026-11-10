package edu.bu.archive.application.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The matching rule, against the exact strings that produced QA TC-040.
 *
 * <p>Java's regex is used here as a stand-in for PostgreSQL's: {@code \m} is
 * Postgres-specific, so it is translated to Java's {@code \b(?=\w)} for these
 * assertions. That is a genuine limitation of a unit test - the real operator
 * is exercised against a real database in
 * {@code GlobalSearchLexicalRelevanceIntegrationTest}, and these tests exist
 * to pin the RULE, not the dialect.
 */
@DisplayName("Lexical matching: word start, not word boundary, and not identifiers")
class LexicalMatchTest {

    /** The real titles, read from the DEV API on 2026-11-10. */
    private static final String NSF_AWARD_TITLE =
            "NATIONAL SCIENCE FOUNDATION GRADUATE RESEARCH FELLOWSHIP PROGRAM";
    private static final String TRANSFER_AWARD_TITLE =
            "PROBING THE PRINCIPLES OF DYNAMICS AND ENERGY TRANSFER IN PROTEINS";
    private static final String TRANSFORMING_AWARD_TITLE =
            "PIRE: ORIGINS OF HIGH MARINE BIODIVERSITY IN THE INDO-MALAY-PHILIPIN E "
                    + "ARCHIPELAGO: TRANSFORMING A BIODIVERSITY HOTSPOT INTO A RESEARCH &";
    private static final String MTA_TYPE = "Material Transfer Agreement";
    private static final String NSF_SPONSOR = "National Science Foundation";

    /** Postgres {@code \m} (start of word) as Java understands it. */
    private static boolean matchesWordStart(String term, String text) {
        String javaRegex = LexicalMatch.wordStartRegex(term)
                .replace("\\m", "\\b(?=\\w)");
        Matcher matcher = Pattern.compile(javaRegex, Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find();
    }

    private static boolean matchesSubstring(String term, String text) {
        return text.toLowerCase().contains(term.toLowerCase());
    }

    // ── the defect ───────────────────────────────────────────────────

    @Nested
    @DisplayName("The NSF inversion")
    class NsfInversion {

        @Test
        void substringMatchingIsWhatCausedIt() {
            // Not a hypothesis. This is why the wrong records won.
            assertThat(matchesSubstring("NSF", TRANSFER_AWARD_TITLE)).isTrue();
            assertThat(matchesSubstring("NSF", TRANSFORMING_AWARD_TITLE)).isTrue();
            assertThat(matchesSubstring("NSF", MTA_TYPE)).isTrue();
            assertThat(matchesSubstring("NSF", NSF_AWARD_TITLE))
                    .as("while the award that IS NSF-funded never matched")
                    .isFalse();
        }

        @Test
        void anchoringStopsNsfMatchingInsideTransfer() {
            assertThat(matchesWordStart("NSF", TRANSFER_AWARD_TITLE)).isFalse();
            assertThat(matchesWordStart("NSF", TRANSFORMING_AWARD_TITLE)).isFalse();
            assertThat(matchesWordStart("NSF", MTA_TYPE)).isFalse();
        }

        @Test
        void anchoringStillMatchesNsfWhereItIsAWord() {
            assertThat(matchesWordStart("NSF", "NSF-Trace formula, analytic number theory"))
                    .as("a hyphen ends a word, so NSF- is a word start")
                    .isTrue();
            assertThat(matchesWordStart("NSF", "Rick Murray - NSF IPA Assignment")).isTrue();
            assertThat(matchesWordStart("nsf", "funded by NSF")).isTrue();
        }

        @Test
        void theNsfAwardIsReachedThroughItsSponsorAcronymNotItsTitle() {
            /*
             * Anchoring alone does not fix the NSF case - it only stops the
             * wrong records winning. The right record has no "NSF" in it at
             * all, so it is reached by matching archive.sponsor.acronym,
             * which is real Kuali reference data (the ETL's own fixture has
             * sponsor 301045 "NIH/National Institute on Aging" with acronym
             * "NIA"). Award 100527-00001's sponsor_code is 301573.
             */
            assertThat(matchesWordStart("NSF", NSF_AWARD_TITLE)).isFalse();
            assertThat(matchesWordStart("NSF", NSF_SPONSOR)).isFalse();
            assertThat(matchesWordStart("NSF", "NSF"))
                    .as("the acronym column is the path to it")
                    .isTrue();
        }
    }

    // ── what must not break ──────────────────────────────────────────

    @Nested
    @DisplayName("Prefix searching keeps working")
    class PrefixSearching {

        @Test
        void aPrefixStillMatchesALongerWord() {
            /*
             * The reason this anchors the word START and not the whole word.
             * Requiring a whole word would have broken every one of these.
             */
            assertThat(matchesWordStart("cardio", "Cardiology and vascular health")).isTrue();
            assertThat(matchesWordStart("quantum", "quantums of light")).isTrue();
            assertThat(matchesWordStart("neuro", "Neurodegeneration in aging")).isTrue();
            assertThat(matchesWordStart("transfer", MTA_TYPE))
                    .as("searching for transfer must still find Transfer")
                    .isTrue();
        }

        @Test
        void aMultiWordNameStillMatches() {
            assertThat(matchesWordStart("WILSON WONG", "WILSON WONG")).isTrue();
            assertThat(matchesWordStart("wilson wong", "Dr WILSON WONG, PI")).isTrue();
        }

        @Test
        void aSurnameMatchesAtItsOwnWordStart() {
            assertThat(matchesWordStart("wong", "WILSON WONG")).isTrue();
        }
    }

    // ── identifiers are untouched ────────────────────────────────────

    @Nested
    @DisplayName("Identifier searching is deliberately left alone")
    class Identifiers {

        @Test
        void aTermWithADigitIsNeverAnchored() {
            /*
             * The search people rely on most: pasting a fragment of a number.
             * Anchoring these would break finding 105698-00001 by "105698",
             * or an account number by its middle digits.
             */
            assertThat(LexicalMatch.anchorsToWordStart("105698")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("100527-00001")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("760067")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("DGE-0741448")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("9500234095")).isFalse();
        }

        @Test
        void aPunctuatedTermIsNeverAnchored() {
            assertThat(LexicalMatch.anchorsToWordStart("non-disclosure")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("a.b")).isFalse();
        }

        @Test
        void anExplicitWildcardIsRespectedRatherThanOverridden() {
            assertThat(LexicalMatch.anchorsToWordStart("*nsf*")).isFalse();
            assertThat(LexicalMatch.likePattern("*nsf*")).isEqualTo("%nsf%");
        }

        @Test
        void aSingleCharacterIsLeftOnTheOldBehaviour() {
            assertThat(LexicalMatch.anchorsToWordStart("a")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("nsf")).isTrue();
        }

        @Test
        void anEmptyOrNullTermIsNotAnchored() {
            assertThat(LexicalMatch.anchorsToWordStart(null)).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("")).isFalse();
            assertThat(LexicalMatch.anchorsToWordStart("   ")).isFalse();
        }
    }

    // ── the shared predicate the UI explanation uses ─────────────────

    @Nested
    @DisplayName("matches() agrees with the SQL, so a card never contradicts the query")
    class Matches {

        @Test
        void itRefusesTheMidWordMatchThatProducedAMeaninglessExplanation() {
            /*
             * The old explanation used a plain contains, so a search for NSF
             * reported "Matched on: Title" against ENERGY TRANSFER IN
             * PROTEINS. Honest about the letters, useless to the reader.
             */
            assertThat(LexicalMatch.matches("NSF", TRANSFER_AWARD_TITLE)).isFalse();
            assertThat(LexicalMatch.matches("NSF", MTA_TYPE)).isFalse();
            assertThat(LexicalMatch.matches("NSF", NSF_AWARD_TITLE)).isFalse();
        }

        @Test
        void itAcceptsAWordStartAndAPrefix() {
            assertThat(LexicalMatch.matches("NSF", "NSF-Trace formula")).isTrue();
            assertThat(LexicalMatch.matches("cardio", "Cardiology")).isTrue();
            assertThat(LexicalMatch.matches("wong", "WILSON WONG")).isTrue();
            assertThat(LexicalMatch.matches("WILSON WONG", "Dr WILSON WONG")).isTrue();
        }

        @Test
        void itKeepsSubstringBehaviourForIdentifiers() {
            assertThat(LexicalMatch.matches("105698", "105698-00001")).isTrue();
            assertThat(LexicalMatch.matches("00001", "105698-00001"))
                    .as("a mid-string identifier fragment must still match")
                    .isTrue();
        }

        @Test
        void itIsNullAndEmptySafe() {
            assertThat(LexicalMatch.matches("nsf", null)).isFalse();
            assertThat(LexicalMatch.matches(null, "text")).isFalse();
            assertThat(LexicalMatch.matches("", "text")).isFalse();
            assertThat(LexicalMatch.matches("   ", "text")).isFalse();
        }

        @Test
        void itFindsALaterWordStartAfterSkippingAMidWordHit() {
            /*
             * "nsf" appears first inside "transfer" and then as its own word.
             * Stopping at the first occurrence would wrongly report no match.
             */
            assertThat(LexicalMatch.matches("nsf", "transfer agreement with NSF")).isTrue();
        }
    }

    // ── the pattern itself ───────────────────────────────────────────

    @Nested
    @DisplayName("Patterns are safe to bind")
    class Patterns {

        @Test
        void theLikePatternBehavesExactlyAsBefore() {
            assertThat(LexicalMatch.likePattern("NSF")).isEqualTo("%NSF%");
            assertThat(LexicalMatch.likePattern("50%")).isEqualTo("%50\\%%");
            assertThat(LexicalMatch.likePattern("a_b")).isEqualTo("%a\\_b%");
            assertThat(LexicalMatch.likePattern("*105698*")).isEqualTo("%105698%");
        }

        @Test
        void everyRegexMetacharacterInATermIsEscaped() {
            /*
             * The term is user input. Unescaped, a typed "(" is not a
             * different result - it is a thrown error, and one a caller
             * could provoke on purpose.
             */
            String regex = LexicalMatch.wordStartRegex("wilson wong");
            assertThat(regex).isEqualTo("\\mwilson\\ wong");
            assertThat(Pattern.compile(regex.replace("\\m", "\\b(?=\\w)")))
                    .as("it must compile")
                    .isNotNull();
        }

        @Test
        void aNonQualifyingTermYieldsAnUnmatchablePatternRatherThanAWideOne() {
            /*
             * Fails closed. A caller that binds the regex without checking
             * anchorsToWordStart gets nothing, not everything.
             */
            String regex = LexicalMatch.wordStartRegex("105698");
            assertThat(regex).doesNotContain("105698");
        }
    }
}
