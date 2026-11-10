"""The rejection strategies, and what each one gets wrong (QA TC-041).

IMPORTANT, and the reason this file reads the way it does: the distance
figures below are a FIXTURE encoding the hypothesis to be tested, not
measurements. The real distances are not observable yet - they are computed
inside the API, used for ordering and discarded, and reading them requires
access to archive.search_embedding on DEV plus Bedrock embedding calls.

So these tests prove the EVALUATOR is correct, so that when the real numbers
arrive the arithmetic is already trustworthy and the only new thing is the
data. They deliberately do not assert that any cutoff is right.

TC-041 stays open until the real distributions are measured.
"""

from __future__ import annotations

import unittest

from archive_etl.semantic_relevance import evaluate
from archive_etl.semantic_relevance.evaluate import QueryMeasurement
from archive_etl.semantic_relevance.labelled_queries import (
    LABELLED_QUERIES,
    Kind,
    Label,
    should_reject,
    should_return,
)


def flat(query: str, *, around: float, lexical: int = 0) -> QueryMeasurement:
    """A query with no standout neighbour - the hypothesised nonsense shape.

    Every candidate is roughly equidistant, because the query vector is
    nowhere near anything in the corpus.
    """
    distances = tuple(round(around + i * 0.0004, 6) for i in range(50))
    return QueryMeasurement(query=query, candidate_distances=distances, lexical_hits=lexical)


def peaked(query: str, *, nearest: float, rest: float, lexical: int = 0) -> QueryMeasurement:
    """A query with a clear best match - the hypothesised meaningful shape."""
    distances = (nearest,) + tuple(round(rest + i * 0.0004, 6) for i in range(49))
    return QueryMeasurement(query=query, candidate_distances=distances, lexical_hits=lexical)


class LabelledSetTest(unittest.TestCase):
    def test_every_query_carries_a_reason(self) -> None:
        """A set without reasons is one nobody can argue with later."""
        for item in LABELLED_QUERIES:
            self.assertTrue(item.reason.strip(), f"{item.query} has no reason")
            self.assertGreater(len(item.reason), 30, f"{item.query}'s reason is too thin")

    def test_it_covers_every_kind_the_request_named(self) -> None:
        kinds = {item.kind for item in LABELLED_QUERIES}
        for required in (
            Kind.NONSENSE,
            Kind.ABBREVIATION,
            Kind.EXPANDED_NAME,
            Kind.SCIENTIFIC_TERM,
            Kind.SYNONYM,
            Kind.IDENTIFIER_SHAPED,
        ):
            self.assertIn(required, kinds)

    def test_it_is_not_a_handful_of_examples(self) -> None:
        """Choosing a cutoff on five queries is how the last one was chosen."""
        self.assertGreaterEqual(len(should_return()), 8)
        self.assertGreaterEqual(len(should_reject()), 4)

    def test_both_nonsense_strings_from_qa_are_present_verbatim(self) -> None:
        queries = {item.query for item in LABELLED_QUERIES}
        self.assertIn("zzzznotfound123", queries)
        self.assertIn("aavvvlkkjhuipkknbhgkj", queries)


class MeasurementTest(unittest.TestCase):
    def test_the_ratio_describes_how_much_the_best_candidate_stands_out(self) -> None:
        self.assertAlmostEqual(peaked("q", nearest=0.20, rest=0.40).nearest_to_median_ratio,
                               0.20 / 0.4096, places=3)
        self.assertAlmostEqual(flat("q", around=0.40).nearest_to_median_ratio,
                               0.40 / 0.4096, places=3)

    def test_a_flat_distribution_has_a_ratio_near_one(self) -> None:
        self.assertGreater(flat("q", around=0.42).nearest_to_median_ratio, 0.97)

    def test_a_zero_median_cannot_divide_by_zero(self) -> None:
        measurement = QueryMeasurement("q", (0.0, 0.0, 0.0), 0)
        self.assertEqual(measurement.nearest_to_median_ratio, 1.0)


class StrategyTest(unittest.TestCase):
    def test_hybrid_withholds_exactly_the_synonym_cases(self) -> None:
        """PR #27's cost, as a number rather than an argument.

        The hybrid rule keeps semantic results only when the query also
        matched lexically. That is precisely wrong for a searcher using a
        different word from the archive - which is why #28 reverted it.
        """
        measurements = {
            "heart attack": peaked("heart attack", nearest=0.22, rest=0.45, lexical=0),
            "quantum": peaked("quantum", nearest=0.21, rest=0.44, lexical=87),
            "zzzznotfound123": flat("zzzznotfound123", around=0.47, lexical=0),
        }
        outcomes = {q: evaluate.hybrid(m) for q, m in measurements.items()}
        result = evaluate.score("hybrid", LABELLED_QUERIES, outcomes)

        self.assertIn("heart attack", result.false_negatives)
        self.assertIn("quantum", result.correct)
        self.assertIn("zzzznotfound123", result.correct)

    def test_query_quality_rejects_the_nonsense_and_keeps_nsf(self) -> None:
        """The cheapest rule, and the only one that saves the Bedrock call."""
        vocabulary = frozenset({"quantum", "asthma", "climate", "heart", "attack"})

        for nonsense in ("zzzznotfound123", "aavvvlkkjhuipkknbhgkj"):
            outcome = evaluate.query_quality(flat(nonsense, around=0.47), vocabulary)
            self.assertFalse(outcome.kept, f"{nonsense}: {outcome.explanation}")

        # NSF must survive: before the lexical fix it was the ONLY route to
        # Award 100527-00001.
        self.assertTrue(evaluate.query_quality(peaked("NSF", nearest=0.3, rest=0.5), vocabulary).kept)
        self.assertTrue(
            evaluate.query_quality(peaked("heart attack", nearest=0.2, rest=0.5), vocabulary).kept
        )

    def test_query_quality_keeps_a_long_phrase_it_has_never_seen(self) -> None:
        outcome = evaluate.query_quality(
            peaked("cancer screening in underserved populations", nearest=0.2, rest=0.5),
            frozenset(),
        )
        self.assertTrue(outcome.kept, outcome.explanation)

    def test_absolute_and_relative_disagree_where_it_matters(self) -> None:
        """Why the relative rule is worth measuring at all.

        A genuinely useful query whose whole neighbourhood is far away - a
        rare subject, where even the best match is distant - is rejected by an
        absolute cutoff and kept by a relative one, because its best match
        still stands out from its own neighbours.
        """
        rare_but_useful = peaked("gene editing", nearest=0.46, rest=0.70)

        self.assertFalse(evaluate.absolute(rare_but_useful, 0.45).kept)
        self.assertTrue(evaluate.relative_margin(rare_but_useful, 0.80).kept)


class SweepTest(unittest.TestCase):
    def _measurements(self) -> dict[str, QueryMeasurement]:
        """The hypothesis as a fixture: nonsense flat, meaningful peaked."""
        measurements: dict[str, QueryMeasurement] = {}
        for item in should_reject():
            if item.kind is Kind.IDENTIFIER_SHAPED:
                continue  # never embedded, so never measured
            measurements[item.query] = flat(item.query, around=0.47)
        for item in should_return():
            lexical = item.measured_lexical_hits or 0
            measurements[item.query] = peaked(item.query, nearest=0.24, rest=0.46, lexical=lexical)
        return measurements

    def test_a_sweep_reports_every_cutoff_rather_than_one_opinion(self) -> None:
        sweep = evaluate.sweep_absolute(
            LABELLED_QUERIES, self._measurements(), tuple(round(0.20 + i * 0.02, 3) for i in range(16))
        )
        self.assertEqual(len(sweep), 16)
        for cutoff, result in sweep:
            self.assertEqual(result.evaluated, len(self._measurements()))

    def test_best_prefers_withholding_nothing_useful(self) -> None:
        """The asymmetry stated as code.

        Noise a searcher can ignore. A useful result they never saw, they
        never learn about. So a candidate with no false negatives beats one
        with fewer total errors but a withheld answer.
        """
        sweep = evaluate.sweep_absolute(
            LABELLED_QUERIES, self._measurements(), (0.10, 0.35, 0.60)
        )
        cutoff, result = evaluate.best(sweep)

        self.assertEqual(result.false_negatives, [])
        self.assertEqual(cutoff, 0.35)

    def test_the_relative_sweep_also_separates_the_fixture(self) -> None:
        sweep = evaluate.sweep_relative(
            LABELLED_QUERIES, self._measurements(), tuple(round(0.5 + i * 0.05, 3) for i in range(10))
        )
        ratio, result = evaluate.best(sweep)

        self.assertEqual(result.false_negatives, [])
        self.assertEqual(result.false_positives, [])

    def test_an_unmeasured_query_is_reported_rather_than_counted_as_correct(self) -> None:
        """Silence must not look like success."""
        partial = {"quantum": peaked("quantum", nearest=0.2, rest=0.5, lexical=87)}
        outcomes = {q: evaluate.absolute(m, 0.4) for q, m in partial.items()}
        result = evaluate.score("partial", LABELLED_QUERIES, outcomes)

        self.assertEqual(result.evaluated, 1)
        self.assertEqual(len(result.unmeasured), len(LABELLED_QUERIES) - 1)
        self.assertIn("unmeasured", result.summary())


class EvidenceHandlingTest(unittest.TestCase):
    def test_a_measurement_carries_scores_and_never_record_content(self) -> None:
        """The restriction the request put on the evidence.

        A QueryMeasurement has room for distances and a lexical count, and
        nowhere to put a title, an identifier or a business number. That is
        structural, not a convention someone has to remember.
        """
        fields = set(QueryMeasurement.__dataclass_fields__)
        self.assertEqual(fields, {"query", "candidate_distances", "lexical_hits"})


if __name__ == "__main__":
    unittest.main()
