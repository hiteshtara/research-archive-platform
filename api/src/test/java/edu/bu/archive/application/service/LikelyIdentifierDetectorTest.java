package edu.bu.archive.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikelyIdentifierDetectorTest {

    @Test
    void classifiesAnAwardNumberShapeAsAnIdentifier() {
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("202505-00002")).isTrue();
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("100803-00003")).isTrue();
    }

    @Test
    void classifiesAPureNumericQueryAsAnIdentifier() {
        // Covers Proposal numbers, Subaward codes, and workflow/document
        // numbers, all of which are plain digit strings in this archive.
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("01157400")).isTrue();
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("1012")).isTrue();
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("763869")).isTrue();
    }

    @Test
    void doesNotClassifyAPersonNameAsAnIdentifier() {
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("Jennifer Smacher")).isFalse();
    }

    @Test
    void doesNotClassifyATopicPhraseAsAnIdentifier() {
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier(
                "diabetes research involving children"
        )).isFalse();
    }

    @Test
    void treatsBlankOrNullQueriesAsNotIdentifiers() {
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier(null)).isFalse();
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("")).isFalse();
        assertThat(LikelyIdentifierDetector.looksLikeIdentifier("   ")).isFalse();
    }
}
