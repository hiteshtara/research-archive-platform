package edu.bu.archive.application.award;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardSearchPatternTest {

    @Test
    void plainTermDefaultsToASubstringMatch() {
        assertThat(AwardSearchPattern.toLikePattern("cancer"))
                .isEqualTo("%cancer%");
    }

    @Test
    void applicationWildcardSyntaxBecomesAnIlikeWildcard() {
        assertThat(AwardSearchPattern.toLikePattern("*105698*"))
                .isEqualTo("%105698%");
    }

    @Test
    void aLeadingOrTrailingWildcardIsPreservedExactlyOnce() {
        assertThat(AwardSearchPattern.toLikePattern("105698*"))
                .isEqualTo("105698%");
        assertThat(AwardSearchPattern.toLikePattern("*105698"))
                .isEqualTo("%105698");
    }

    @Test
    void aLiteralPercentSignIsEscapedNotInterpretedAsAWildcard() {
        // The escaped "\%" still contains a raw '%' character - it must
        // not be mistaken for the user having supplied their own '*'
        // wildcard, or the default substring wrap would be skipped.
        assertThat(AwardSearchPattern.toLikePattern("50%"))
                .isEqualTo("%50\\%%");
    }

    @Test
    void aLiteralUnderscoreIsEscapedNotInterpretedAsASingleCharWildcard() {
        assertThat(AwardSearchPattern.toLikePattern("A_B"))
                .isEqualTo("%A\\_B%");
    }

    @Test
    void aLiteralBackslashIsEscapedBeforeWildcardsAreTranslated() {
        assertThat(AwardSearchPattern.toLikePattern("A\\B"))
                .isEqualTo("%A\\\\B%");
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsInertLiteralText() {
        // The pattern is always bound as a single parameter, never
        // concatenated into SQL text - this just confirms the pattern
        // itself doesn't gain any special meaning from quote/semicolon
        // characters. The underscore in "award_version" is itself an
        // ILIKE metacharacter and is escaped like any other literal
        // underscore.
        assertThat(
                AwardSearchPattern.toLikePattern(
                        "'; DROP TABLE archive.award_version; --"
                )
        ).isEqualTo("%'; DROP TABLE archive.award\\_version; --%");
    }

    @Test
    void anEmptyQueryProducesAWrappedEmptyPattern() {
        assertThat(AwardSearchPattern.toLikePattern(""))
                .isEqualTo("%%");
    }
}
