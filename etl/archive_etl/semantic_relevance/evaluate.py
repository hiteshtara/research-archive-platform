"""Candidate rejection strategies for semantic results, and how wrong each is.

QA TC-041. The pipeline returns its five nearest vectors with no cutoff. The
question is what rule, if any, should reject them - and the answer has to be
measured, because a prior experiment already found that no single global
distance cutoff worked.

Four strategies are implemented so they can be compared on the same evidence:

``absolute``
    Reject when the nearest distance exceeds a fixed value. The obvious rule,
    and the one that failed before. Kept because "it failed" should be
    demonstrable rather than remembered.

``relative_margin``
    Reject when the nearest neighbour is not much closer than the candidate
    window's median. The intuition: for a meaningful query the nearest result
    stands out; for a meaningless one every candidate is roughly equidistant,
    because the query vector is nowhere near anything. Needs no absolute
    constant, so it cannot be mis-tuned by corpus drift the way a fixed
    distance can.

``hybrid``
    Keep semantic results only when the query also matched lexically. This is
    PR #27, which was MERGED AND REVERTED by #28 - it rejects the synonym case
    that is the whole point of semantic search. Implemented so that cost is a
    number in a table rather than an argument.

``query_quality``
    Reject before embedding, on the shape of the query itself: a term with no
    vowel pattern resembling a word, or no token present in the corpus
    vocabulary, cannot be helped by semantic search. Cheapest of the four and
    the only one that saves the Bedrock call.

None of these is chosen here. ``compare`` reports false positives and false
negatives for each so a choice can be made on evidence.
"""

from __future__ import annotations

import re
import statistics
from dataclasses import dataclass, field

from .labelled_queries import Label, LabelledQuery


@dataclass(frozen=True)
class QueryMeasurement:
    """What was observed for one query. Scores only - never record content.

    ``candidate_distances`` is every distance in the oversampled window
    (the repository asks for 50), ascending. ``lexical_hits`` is how many
    structured results the same query produced.
    """

    query: str
    candidate_distances: tuple[float, ...]
    lexical_hits: int

    @property
    def nearest(self) -> float:
        return self.candidate_distances[0]

    @property
    def median(self) -> float:
        return statistics.median(self.candidate_distances)

    @property
    def nearest_to_median_ratio(self) -> float:
        """How much the best candidate stands out. Lower means it stands out
        more; 1.0 means the nearest is no better than typical."""
        median = self.median
        return 1.0 if median == 0 else self.nearest / median


@dataclass(frozen=True)
class Outcome:
    """Whether a strategy kept this query's semantic results, and why."""

    query: str
    kept: bool
    explanation: str


@dataclass
class StrategyScore:
    """How often a strategy is wrong, in both directions.

    ``false_positives`` are nonsense queries whose results were KEPT - the
    complaint TC-041 is about. ``false_negatives`` are useful queries whose
    results were REJECTED - the complaint PR #27 caused, and the more
    expensive of the two: a searcher who gets noise can ignore it, and one
    whose only useful result was withheld never learns it existed.
    """

    name: str
    false_positives: list[str] = field(default_factory=list)
    false_negatives: list[str] = field(default_factory=list)
    correct: list[str] = field(default_factory=list)
    unmeasured: list[str] = field(default_factory=list)

    @property
    def evaluated(self) -> int:
        return len(self.correct) + len(self.false_positives) + len(self.false_negatives)

    @property
    def accuracy(self) -> float:
        return 0.0 if self.evaluated == 0 else len(self.correct) / self.evaluated

    def summary(self) -> str:
        return (
            f"{self.name}: {len(self.correct)}/{self.evaluated} correct, "
            f"{len(self.false_positives)} noise kept, "
            f"{len(self.false_negatives)} useful results withheld"
            + (f", {len(self.unmeasured)} unmeasured" if self.unmeasured else "")
        )


# --- the strategies ----------------------------------------------------


def absolute(measurement: QueryMeasurement, max_distance: float) -> Outcome:
    kept = measurement.nearest <= max_distance
    return Outcome(
        measurement.query,
        kept,
        f"nearest {measurement.nearest:.4f} "
        f"{'<=' if kept else '>'} {max_distance:.4f}",
    )


def relative_margin(measurement: QueryMeasurement, max_ratio: float) -> Outcome:
    ratio = measurement.nearest_to_median_ratio
    kept = ratio <= max_ratio
    return Outcome(
        measurement.query,
        kept,
        f"nearest/median {ratio:.4f} {'<=' if kept else '>'} {max_ratio:.4f}",
    )


def hybrid(measurement: QueryMeasurement) -> Outcome:
    kept = measurement.lexical_hits > 0
    return Outcome(
        measurement.query,
        kept,
        f"{measurement.lexical_hits} lexical hit(s)",
    )


_VOWELS = re.compile(r"[aeiouy]", re.IGNORECASE)
_LETTERS_ONLY = re.compile(r"^[A-Za-z]+$")


def query_quality(measurement: QueryMeasurement, known_words: frozenset[str]) -> Outcome:
    """Reject on the query's own shape, before any embedding call.

    Two tests, both cheap. A token is word-like if it is a known corpus word,
    or if it has a plausible vowel density - "zzzznotfound123" and
    "aavvvlkkjhuipkknbhgkj" fail that, "NSF" and "CRISPR" would too, which is
    exactly why a known-vocabulary check has to come first. An abbreviation
    that appears in the corpus is a real word for this purpose.
    """
    tokens = [t for t in re.split(r"[^A-Za-z0-9]+", measurement.query) if t]
    if not tokens:
        return Outcome(measurement.query, False, "no usable token")

    for token in tokens:
        lowered = token.lower()
        if lowered in known_words:
            return Outcome(measurement.query, True, f"'{token}' is in the corpus")
        if _LETTERS_ONLY.match(token) and len(token) <= 6:
            # Short alphabetic tokens are plausibly acronyms. Rejecting them
            # on vowel density would reject NSF, and NSF is the case that
            # made TC-040 worth fixing.
            return Outcome(measurement.query, True, f"'{token}' may be an acronym")
        vowels = len(_VOWELS.findall(token))
        if vowels and len(token) / vowels <= 4:
            return Outcome(measurement.query, True, f"'{token}' is word-like")

    return Outcome(measurement.query, False, "no token is word-like or known")


# --- comparison --------------------------------------------------------


def score(
    name: str,
    labelled: tuple[LabelledQuery, ...],
    outcomes: dict[str, Outcome],
) -> StrategyScore:
    """Turn a strategy's outcomes into its error counts."""
    result = StrategyScore(name=name)
    for item in labelled:
        outcome = outcomes.get(item.query)
        if outcome is None:
            result.unmeasured.append(item.query)
            continue
        wanted = item.label is Label.SHOULD_RETURN
        if outcome.kept == wanted:
            result.correct.append(item.query)
        elif outcome.kept:
            result.false_positives.append(item.query)
        else:
            result.false_negatives.append(item.query)
    return result


def sweep_absolute(
    labelled: tuple[LabelledQuery, ...],
    measurements: dict[str, QueryMeasurement],
    candidates: tuple[float, ...],
) -> list[tuple[float, StrategyScore]]:
    """Every absolute cutoff worth trying, scored.

    A sweep rather than a guess: this is what makes a chosen value defensible,
    and what shows whether ANY value separates the two groups at all. If the
    best cutoff still has false negatives, the strategy is wrong rather than
    badly tuned.
    """
    results = []
    for cutoff in candidates:
        outcomes = {
            q: absolute(m, cutoff) for q, m in measurements.items()
        }
        results.append((cutoff, score(f"absolute<={cutoff:.3f}", labelled, outcomes)))
    return results


def sweep_relative(
    labelled: tuple[LabelledQuery, ...],
    measurements: dict[str, QueryMeasurement],
    candidates: tuple[float, ...],
) -> list[tuple[float, StrategyScore]]:
    results = []
    for ratio in candidates:
        outcomes = {
            q: relative_margin(m, ratio) for q, m in measurements.items()
        }
        results.append((ratio, score(f"relative<={ratio:.3f}", labelled, outcomes)))
    return results


def best(sweep: list[tuple[float, StrategyScore]]) -> tuple[float, StrategyScore] | None:
    """The best-performing parameter, preferring fewer withheld useful results.

    False negatives are weighted above false positives deliberately. Noise a
    searcher can ignore; a result they never saw, they never learn about - and
    that asymmetry is why PR #27 was reverted.
    """
    if not sweep:
        return None
    return min(
        sweep,
        key=lambda entry: (
            len(entry[1].false_negatives),
            len(entry[1].false_positives),
            entry[0],
        ),
    )
