"""Compare rejection strategies over measured scores (QA TC-041).

Reads the output of ``measure_semantic_relevance.py`` and reports, for each
candidate strategy, how many nonsense queries it would KEEP (false positives
- the TC-041 complaint) and how many useful queries it would WITHHOLD (false
negatives - the PR #27 complaint, and the worse of the two).

It prints a table and recommends nothing on its own. The recommendation is a
judgement about which errors are acceptable, and that belongs to a person
looking at the numbers.

    uv run python compare_semantic_relevance.py --input=/path/semantic-relevance.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from archive_etl.semantic_relevance import evaluate
from archive_etl.semantic_relevance.evaluate import QueryMeasurement
from archive_etl.semantic_relevance.labelled_queries import LABELLED_QUERIES


def load(path: Path) -> dict[str, QueryMeasurement]:
    payload = json.loads(path.read_text())
    measurements: dict[str, QueryMeasurement] = {}
    for raw in payload["measurements"]:
        measurements[raw["query"]] = QueryMeasurement(
            query=raw["query"],
            candidate_distances=tuple(raw["candidate_distances"]),
            lexical_hits=int(raw.get("lexical_hits", 0)),
        )
    return measurements


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    args = parser.parse_args(argv)

    measurements = load(Path(args.input))
    if not measurements:
        print("No measurements in the input file; nothing to compare.")
        return 1

    print(f"Loaded {len(measurements)} measured queries.\n")

    print("Distance shape per query (distances only - no record content):")
    for query, measurement in measurements.items():
        print(
            f"  {query!r:48} nearest={measurement.nearest:.4f} "
            f"median={measurement.median:.4f} "
            f"ratio={measurement.nearest_to_median_ratio:.4f}"
        )

    print("\nAbsolute cutoff sweep:")
    absolute_sweep = evaluate.sweep_absolute(
        LABELLED_QUERIES, measurements, tuple(round(0.20 + i * 0.02, 3) for i in range(21))
    )
    for cutoff, result in absolute_sweep:
        print(f"  <= {cutoff:.3f}  {result.summary()}")

    print("\nRelative-margin sweep (nearest / candidate median):")
    relative_sweep = evaluate.sweep_relative(
        LABELLED_QUERIES, measurements, tuple(round(0.40 + i * 0.05, 3) for i in range(13))
    )
    for ratio, result in relative_sweep:
        print(f"  <= {ratio:.3f}  {result.summary()}")

    print("\nHybrid (PR #27: keep semantic only when something matched lexically):")
    hybrid_outcomes = {q: evaluate.hybrid(m) for q, m in measurements.items()}
    print("  " + evaluate.score("hybrid", LABELLED_QUERIES, hybrid_outcomes).summary())

    print("\nQuery quality (reject before embedding):")
    quality_outcomes = {
        q: evaluate.query_quality(m, frozenset()) for q, m in measurements.items()
    }
    print("  " + evaluate.score("query_quality", LABELLED_QUERIES, quality_outcomes).summary())
    print(
        "  (run with a real corpus vocabulary to let known abbreviations through;\n"
        "   an empty vocabulary is the pessimistic case)"
    )

    print("\nBest parameter by fewest withheld useful results:")
    for label, sweep in (("absolute", absolute_sweep), ("relative", relative_sweep)):
        chosen = evaluate.best(sweep)
        if chosen:
            value, result = chosen
            print(f"  {label}: {value:.3f} -> {result.summary()}")
            if result.false_negatives:
                print(
                    f"    withheld: {', '.join(result.false_negatives)} "
                    "- a strategy that withholds a useful answer at its BEST "
                    "setting is the wrong strategy, not a badly tuned one."
                )

    print(
        "\nNo cutoff is chosen here. Record the table above, decide which errors "
        "are acceptable, and only then pick a rule."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
