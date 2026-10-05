package edu.bu.archive.adapter.in.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestParameterTextPolicyTest {

    private static final char NUL = (char) 0;
    private static final char BELL = (char) 7;
    private static final char ESCAPE = (char) 27;
    private static final char DELETE = (char) 127;
    private static final char C1_CONTROL = (char) 0x9B;

    @Test
    void aNulByteIsRejectedAndNamedByCodePoint() {
        // The TC-017 reproduction exactly: "smith%00cohort" in the URL.
        assertThat(RequestParameterTextPolicy.firstDisallowed("smith" + NUL + "cohort"))
                .isEqualTo("U+0000");
    }

    @Test
    void otherNonPrintableControlCharactersAreRejectedToo() {
        assertThat(RequestParameterTextPolicy.firstDisallowed("a" + BELL))
                .isEqualTo("U+0007");
        assertThat(RequestParameterTextPolicy.firstDisallowed("a" + ESCAPE + "[2J"))
                .isEqualTo("U+001B");
        assertThat(RequestParameterTextPolicy.firstDisallowed("a" + DELETE))
                .isEqualTo("U+007F");
        assertThat(RequestParameterTextPolicy.firstDisallowed("a" + C1_CONTROL))
                .isEqualTo("U+009B");
    }

    @Test
    void theFirstDisallowedCharacterIsTheOneReported() {
        assertThat(RequestParameterTextPolicy.firstDisallowed("a" + ESCAPE + "b" + NUL))
                .isEqualTo("U+001B");
    }

    @Test
    void tabLineFeedAndCarriageReturnAreAllowedAsOrdinaryWhitespace() {
        // Pasted from a spreadsheet cell or a wrapped line - PostgreSQL
        // accepts these and each domain already trims them.
        assertThat(RequestParameterTextPolicy.firstDisallowed("smith\tcohort")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("smith\ncohort")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("smith\r\ncohort")).isNull();
    }

    @Test
    void ordinarySearchTextIsUntouched() {
        assertThat(RequestParameterTextPolicy.firstDisallowed("105698")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("SAR OCCUPATIONAL THERAPY")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("Workforce-driven")).isNull();
    }

    @Test
    void theSearchSyntaxAndItsEscapableCharactersStayValidInput() {
        // These must keep reaching AwardSearchPattern unchanged - the
        // wildcard and the literal ILIKE metacharacters are the
        // application's own syntax, not rejected input (TC-017's
        // "literal % and _" requirement, and TC-009's wildcard).
        assertThat(RequestParameterTextPolicy.firstDisallowed("*105698*")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("50%")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("A_B")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("A\\B")).isNull();
    }

    @Test
    void injectionShapedTextIsStillAcceptedAsInertLiteralInput() {
        // Rejecting these would be the wrong fix: they are bound as a
        // single parameter and already proven inert (TC-017's 174 API
        // probes). Only unprintable characters are refused.
        assertThat(
                RequestParameterTextPolicy.firstDisallowed("'; DROP TABLE archive.award_version; --")
        ).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("' OR '1'='1")).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("<script>alert(1)</script>")).isNull();
    }

    @Test
    void nullAndEmptyValuesAreAcceptable() {
        assertThat(RequestParameterTextPolicy.firstDisallowed(null)).isNull();
        assertThat(RequestParameterTextPolicy.firstDisallowed("")).isNull();
    }
}
