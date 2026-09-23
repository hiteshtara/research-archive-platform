package edu.bu.archive.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * The detector's rule is measured, not assumed - see
 * SubawardFrnQueryDetector for the dev figures behind it. These cases
 * pin the two things the measurement established: every genuine FRN in
 * the archive is a 9- or 10-digit number, and no Subaward code or
 * document number is, so ordinary lookups never trip the expensive
 * family-history predicates.
 */
class SubawardFrnQueryDetectorTest {

    @Test
    void realFrnsFromTheArchiveAreRecognised() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("4500002829")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("4500003867")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("4500005236")).isTrue();
    }

    /*
     * Not every FRN carries SAP's 45xxxxxxxx shape - 0000000000,
     * 4000000245, 9500309346 and 0123456789 all occur on dev - so the
     * rule deliberately does not pin the prefix.
     */
    @Test
    void frnsThatDoNotUseTheSapPrefixAreStillRecognised() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("0000000000")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("4000000245")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("9500309346")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("0123456789")).isTrue();
    }

    /* The real 9-digit family (450000311, 450000437, ...). */
    @Test
    void nineDigitFrnsAreRecognised() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("450000311")).isTrue();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("450000437")).isTrue();
    }

    /*
     * Subaward codes and workflow document numbers must never trip the
     * gate: zero of either is 9- or 10-digit numeric on dev.
     */
    @Test
    void subawardCodesAndDocumentNumbersDoNotLookLikeFrns() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("1920")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("4046")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("1091920")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("847944")).isFalse();
    }

    @Test
    void textAndPartialNumbersDoNotLookLikeFrns() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("Trustees")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("45000028")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("45000028291")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("45000-2829")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("")).isFalse();
        assertThat(SubawardFrnQueryDetector.looksLikeFrn(null)).isFalse();
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(SubawardFrnQueryDetector.looksLikeFrn("  4500002829 ")).isTrue();
    }
}
