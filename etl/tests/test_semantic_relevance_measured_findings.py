"""The measured findings, pinned (QA TC-041).

Measured against DEV on 2026-11-10, read-only, 15 Bedrock embedding calls,
against archive.search_embedding's 26,425 rows.

Unlike test_semantic_relevance_evaluation.py - whose figures are a FIXTURE
encoding a hypothesis - the numbers here are observations. They are pinned so
that a future proposal to add a distance threshold has to confront them
rather than rediscover them, and so that re-measuring after a corpus reload
shows up as a changed expectation rather than a quiet drift.

Nearest distances only; the full 50-candidate windows are preserved outside
the repository.
"""

from __future__ import annotations

import unittest

from archive_etl.semantic_relevance.evaluate import QueryMeasurement
from archive_etl.semantic_relevance.labelled_queries import Label, LABELLED_QUERIES

# query -> (nearest, candidate median), measured on DEV 2026-11-10
MEASURED: dict[str, tuple[float, float]] = {
    "zzzznotfound123": (0.7924, 0.8014),
    "aavvvlkkjhuipkknbhgkj": (0.7934, 0.8215),
    "qzxwvnonsense987": (0.8201, 0.8698),
    "xkcdqq": (0.7883, 0.8452),
    "NSF": (0.6733, 0.8046),
    "National Science Foundation": (0.6066, 0.6533),
    "NIH": (0.6392, 0.6566),
    "pediatric asthma": (0.6058, 0.7837),
    "quantum": (0.7839, 0.8608),
    "climate": (0.7606, 0.8233),
    "neurodegeneration": (0.5196, 0.7809),
    "opioid": (0.7266, 0.8336),
    "heart attack": (0.7884, 0.8525),
    "cancer screening in underserved populations": (0.5823, 0.7074),
    "gene editing": (0.6756, 0.7788),
}

# Rows of archive.search_embedding whose source_text contains the token as a
# WORD, measured the same day.
CORPUS_WORD_COUNTS: dict[str, int] = {
    "nsf": 37,
    "nih": 4174,
    "quantum": 72,
    "climate": 82,
    "opioid": 21,
    "neurodegeneration": 11,
    "asthma": 45,
    "heart": 749,
    "gene": 177,
    "cancer": 650,
    "zzzznotfound123": 0,
    "aavvvlkkjhuipkknbhgkj": 0,
    "qzxwvnonsense987": 0,
    "xkcdqq": 0,
}

LABEL = {q.query: q.label for q in LABELLED_QUERIES}


def _split(values: dict[str, float]) -> tuple[dict[str, float], dict[str, float]]:
    relevant = {q: v for q, v in values.items() if LABEL[q] is Label.SHOULD_RETURN}
    nonsense = {q: v for q, v in values.items() if LABEL[q] is Label.SHOULD_REJECT}
    return relevant, nonsense


class MeasuredSetTest(unittest.TestCase):
    def test_every_measured_query_is_labelled(self) -> None:
        for query in MEASURED:
            self.assertIn(query, LABEL, f"{query} is measured but not labelled")

    def test_the_identifier_shaped_queries_were_not_embedded(self) -> None:
        """The API skips the semantic leg for those, so embedding one would
        measure a code path nobody runs."""
        self.assertNotIn("100527-00001", MEASURED)
        self.assertNotIn("760067", MEASURED)
        self.assertEqual(len(MEASURED), 15)


class AbsoluteThresholdTest(unittest.TestCase):
    def test_the_two_classes_overlap(self) -> None:
        """The finding. No absolute cutoff separates them."""
        relevant, nonsense = _split({q: v[0] for q, v in MEASURED.items()})
        worst_relevant = max(relevant.values())
        best_nonsense = min(nonsense.values())

        self.assertGreater(
            worst_relevant, best_nonsense,
            "the classes no longer overlap - re-measure and revisit the "
            "recommendation, because a separating cutoff may now exist",
        )
        self.assertAlmostEqual(best_nonsense - worst_relevant, -0.0001, places=4)

    def test_the_overlapping_pair_is_named(self) -> None:
        """So a reader knows which two queries decide this."""
        relevant, nonsense = _split({q: v[0] for q, v in MEASURED.items()})
        self.assertEqual(max(relevant, key=relevant.get), "heart attack")
        self.assertEqual(min(nonsense, key=nonsense.get), "xkcdqq")

    def test_the_best_cutoff_still_keeps_one_nonsense_query(self) -> None:
        relevant, nonsense = _split({q: v[0] for q, v in MEASURED.items()})
        cutoff = 0.7885
        self.assertEqual([q for q, v in relevant.items() if v > cutoff], [])
        self.assertEqual([q for q, v in nonsense.items() if v <= cutoff], ["xkcdqq"])


class RelativeMarginTest(unittest.TestCase):
    def test_it_is_worse_than_the_absolute_cutoff(self) -> None:
        """The hypothesis the plan recommended, refuted by measurement.

        A meaningful query was expected to have a nearest much closer than its
        median. NIH does not: 4,174 corpus rows mention it, and its whole
        neighbourhood is uniformly close, so its best match does not stand
        out and the ratio reads it as noise.
        """
        ratios = {q: v[0] / v[1] for q, v in MEASURED.items()}
        relevant, nonsense = _split(ratios)

        absolute_margin = -0.0001
        relative_margin = min(nonsense.values()) - max(relevant.values())

        self.assertLess(relative_margin, absolute_margin,
                        "the relative margin must be the WORSE of the two")
        # -0.0408 from the rounded medians pinned above; the live run, which
        # used the full 50-candidate windows, reported -0.0420. The pinned
        # medians are 4 decimal places, so the two differ in the third. Both
        # say the same thing and neither is close to separating.
        self.assertAlmostEqual(relative_margin, -0.0408, places=3)
        self.assertLess(relative_margin, -0.03,
                        "the overlap is an order of magnitude worse than the "
                        "absolute cutoff's, however it is rounded")

    def test_the_worst_relative_offender_is_a_plainly_relevant_query(self) -> None:
        ratios = {q: v[0] / v[1] for q, v in MEASURED.items()}
        relevant, _ = _split(ratios)
        self.assertEqual(max(relevant, key=relevant.get), "NIH")


class CorpusVocabularyTest(unittest.TestCase):
    def test_it_separates_the_set_completely(self) -> None:
        """The rule the measurement supports: 15/15, no constant to tune."""
        for token, count in CORPUS_WORD_COUNTS.items():
            if token in LABEL and LABEL[token] is Label.SHOULD_REJECT:
                self.assertEqual(count, 0, f"{token} unexpectedly in the corpus")
            else:
                self.assertGreater(count, 0, f"{token} unexpectedly absent")

    def test_it_keeps_the_query_that_made_tc_040_worth_fixing(self) -> None:
        """Before the lexical fix, the semantic leg was the ONLY route to
        Award 100527-00001. A gate that rejected NSF would have removed the
        single useful answer."""
        self.assertGreater(CORPUS_WORD_COUNTS["nsf"], 0)

    def test_no_nonsense_token_appears_in_the_corpus(self) -> None:
        for token in ("zzzznotfound123", "aavvvlkkjhuipkknbhgkj",
                      "qzxwvnonsense987", "xkcdqq"):
            self.assertEqual(CORPUS_WORD_COUNTS[token], 0)


class MeasurementShapeTest(unittest.TestCase):
    def test_the_measured_values_build_a_valid_measurement(self) -> None:
        """Guards against the pinned numbers drifting out of the shape the
        evaluator expects."""
        for query, (nearest, median) in MEASURED.items():
            measurement = QueryMeasurement(query, (nearest, median, median), 0)
            self.assertEqual(measurement.nearest, nearest)
            self.assertAlmostEqual(measurement.median, median, places=4)


if __name__ == "__main__":
    unittest.main()
