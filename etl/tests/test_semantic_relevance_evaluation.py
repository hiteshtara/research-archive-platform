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
    EXPANDED_QUERIES,
    LABELLED_QUERIES,
    Kind,
    Label,
    all_queries,
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
        result = evaluate.score("hybrid", all_queries(), outcomes)

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
            all_queries(), self._measurements(), tuple(round(0.20 + i * 0.02, 3) for i in range(16))
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
            all_queries(), self._measurements(), (0.10, 0.35, 0.60)
        )
        cutoff, result = evaluate.best(sweep)

        self.assertEqual(result.false_negatives, [])
        self.assertEqual(cutoff, 0.35)

    def test_the_relative_sweep_also_separates_the_fixture(self) -> None:
        sweep = evaluate.sweep_relative(
            all_queries(), self._measurements(), tuple(round(0.5 + i * 0.05, 3) for i in range(10))
        )
        ratio, result = evaluate.best(sweep)

        self.assertEqual(result.false_negatives, [])
        self.assertEqual(result.false_positives, [])

    def test_an_unmeasured_query_is_reported_rather_than_counted_as_correct(self) -> None:
        """Silence must not look like success."""
        partial = {"quantum": peaked("quantum", nearest=0.2, rest=0.5, lexical=87)}
        outcomes = {q: evaluate.absolute(m, 0.4) for q, m in partial.items()}
        result = evaluate.score("partial", all_queries(), outcomes)

        self.assertEqual(result.evaluated, 1)
        self.assertEqual(len(result.unmeasured), len(all_queries()) - 1)
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


class CorpusVocabularyGateTest(unittest.TestCase):
    """The candidate rule, and the case that decides whether it can ship.

    Vocabulary counts here are the ones MEASURED on DEV 2026-11-10 for the
    original set. The expanded set's counts are not in yet, which is why this
    class asserts the gate's LOGIC and not a verdict on it.
    """

    def gate(self, query: str, vocab: dict[str, int], **kw) -> evaluate.Outcome:
        return evaluate.corpus_vocabulary(flat(query, around=0.8), vocab, **kw)

    def test_it_rejects_a_query_with_no_token_in_the_corpus(self) -> None:
        for nonsense in ("zzzznotfound123", "aavvvlkkjhuipkknbhgkj", "xkcdqq"):
            outcome = self.gate(nonsense, {nonsense: 0})
            self.assertFalse(outcome.kept, outcome.explanation)
            self.assertIn("no token is in the corpus", outcome.explanation)

    def test_it_keeps_nsf_which_the_original_set_proved_matters(self) -> None:
        """Before the TC-040 lexical fix, the semantic leg was the ONLY route
        to Award 100527-00001. 37 embedded rows mention nsf."""
        outcome = self.gate("NSF", {"nsf": 37})
        self.assertTrue(outcome.kept)
        self.assertIn("37 embedded rows", outcome.explanation)

    def test_the_any_form_decides_a_phrase_on_its_commonest_word(self) -> None:
        """Why the 15/15 score flattered it.

        "quantum error correction" passes on "quantum" alone - the archive may
        know nothing about error correction. On phrases the any-form tests
        whether the phrase contains a word the archive uses, which is a much
        weaker claim than relevance.
        """
        outcome = self.gate(
            "quantum error correction",
            {"quantum": 72, "error": 0, "correction": 0},
        )
        self.assertTrue(outcome.kept)
        self.assertIn("quantum", outcome.explanation)

    def test_the_strict_form_rejects_the_same_phrase(self) -> None:
        outcome = self.gate(
            "quantum error correction",
            {"quantum": 72, "error": 0, "correction": 0},
            require_all_tokens=True,
        )
        self.assertFalse(outcome.kept)
        self.assertIn("error", outcome.explanation)
        self.assertIn("correction", outcome.explanation)

    def test_both_forms_reject_a_spelling_variant(self) -> None:
        """The case that decides whether this can ship.

        "paediatric" is a real search for records the archive holds under
        "pediatric". No form of a vocabulary gate can pass it, because its
        only token is absent by construction. If semantic search returns
        something useful for it, the gate destroys useful discovery.
        """
        for strict in (False, True):
            outcome = self.gate("paediatric", {"paediatric": 0},
                                require_all_tokens=strict)
            self.assertFalse(outcome.kept, f"strict={strict}")

    def test_both_forms_reject_a_synonym_only_query(self) -> None:
        for strict in (False, True):
            outcome = self.gate(
                "myocardial infarction",
                {"myocardial": 0, "infarction": 0},
                require_all_tokens=strict,
            )
            self.assertFalse(outcome.kept, f"strict={strict}")

    def test_an_empty_vocabulary_map_is_a_rejection_not_a_crash(self) -> None:
        outcome = self.gate("", {})
        self.assertFalse(outcome.kept)
        self.assertEqual(outcome.explanation, "no usable token")

    def test_the_gate_is_case_insensitive_by_construction(self) -> None:
        """Counts are keyed on lowercased tokens by the measurement, so DoD
        and dod are the same question. Asserted so a future change to the
        measurement cannot silently make the gate case sensitive."""
        outcome = self.gate("DoD", {"dod": 12})
        self.assertTrue(outcome.kept)


class ExpandedSetTest(unittest.TestCase):
    """The expanded tier covers what the first 15 could not test."""

    def test_it_covers_every_category_the_expansion_asked_for(self) -> None:
        kinds = {q.kind for q in EXPANDED_QUERIES}
        for required in (
            Kind.SYNONYM_ONLY,
            Kind.ABBREVIATION,
            Kind.SPELLING_VARIANT,
            Kind.ABSENT_BUT_MEANINGFUL,
            Kind.NONSENSE,
        ):
            self.assertIn(required, kinds, required.value)

    def test_the_original_conclusion_was_untestable_on_the_original_set(self) -> None:
        """Why the expansion was necessary, as an assertion.

        Not one of the original "should return" queries is a spelling variant
        or a synonym-only search - so the gate was never asked to decide a
        case where the searcher's words and the archive's words differ, which
        is the only case that can refute it.
        """
        original_kinds = {q.kind for q in LABELLED_QUERIES
                          if q.label is Label.SHOULD_RETURN}
        self.assertNotIn(Kind.SPELLING_VARIANT, original_kinds)
        self.assertNotIn(Kind.SYNONYM_ONLY, original_kinds)

    def test_the_hardest_nonsense_cases_are_pronounceable(self) -> None:
        """The original nonsense strings were unpronounceable consonant runs,
        which a vowel-density heuristic catches for free. These do not look
        like noise and must still be rejected."""
        pronounceable = {q.query for q in EXPANDED_QUERIES
                         if q.kind is Kind.NONSENSE}
        self.assertIn("plorgle", pronounceable)
        self.assertIn("snorfulate biodynamics", pronounceable)

    def test_every_expanded_query_carries_a_reason(self) -> None:
        for item in EXPANDED_QUERIES:
            self.assertGreater(len(item.reason), 30, item.query)

    def test_no_query_appears_in_both_tiers(self) -> None:
        original = {q.query for q in LABELLED_QUERIES}
        for item in EXPANDED_QUERIES:
            self.assertNotIn(item.query, original, item.query)

    def test_the_set_is_now_large_enough_to_argue_from(self) -> None:
        self.assertGreaterEqual(len(all_queries()), 30)
        self.assertGreaterEqual(len(should_return()), 20)
        self.assertGreaterEqual(len(should_reject()), 8)
