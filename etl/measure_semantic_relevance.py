"""Measure semantic-search score distributions against DEV (QA TC-041).

Produces the evidence needed to choose - or reject - a relevance rule, and
nothing else. For each labelled query it embeds the text with the same model
the API uses, asks pgvector for the same oversampled candidate window the API
asks for, and records the DISTANCES.

WHAT IT WRITES, AND WHAT IT CANNOT WRITE
    One row per query: the query, its candidate distances, and how many
    structured results the same term produced where that was supplied. There
    is nowhere in the output for a title, a business number, an identifier or
    any other record content - the shape of QueryMeasurement enforces that,
    and a test asserts it. The point is to measure relevance, not to export
    the archive.

WHAT IT DOES TO THE DATABASE
    One SELECT per query, against archive.search_embedding only. No INSERT,
    UPDATE, DELETE or DDL, no migrations, and no other table. The connection
    is opened read-only and that is asserted before any query runs, because a
    guard that is set but never verified is not a guard.

WHY IT IS A SEPARATE SCRIPT
    It needs two things the API does not hand out: read access to
    archive.search_embedding and Bedrock InvokeModel. Both are approval-gated,
    so this exists ready to run rather than run already. Until it is run,
    TC-041 stays open - the alternative is choosing a cutoff from a handful of
    examples, which is how the last one was chosen.

USAGE
    uv run python measure_semantic_relevance.py \
        --environment=dev \
        --output=/path/outside/the/repo/semantic-relevance.json \
        --confirm-read-only

Refuses production outright. Requires --confirm-read-only so that running it
is a deliberate act rather than a default.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.semantic_relevance.labelled_queries import Kind, all_queries
from archive_etl.semantic_relevance.evaluate import QueryMeasurement

# The same model and window the API uses. If either drifts from
# build_search_embedding.py or SemanticSearchRepository, the measurement
# describes a system nobody is running.
EMBEDDING_MODEL = "amazon.titan-embed-text-v2:0"
EMBEDDING_DIMENSIONS = 1024
CANDIDATE_WINDOW = 50

PRODUCTION_NAMES = {"prod", "production", "prd"}


def embed(bedrock_client: Any, value: str) -> list[float]:
    response = bedrock_client.invoke_model(
        modelId=EMBEDDING_MODEL,
        body=json.dumps({"inputText": value}),
    )
    payload = json.loads(response["body"].read())
    embedding = payload["embedding"]
    if len(embedding) != EMBEDDING_DIMENSIONS:
        raise ValueError(
            f"Expected {EMBEDDING_DIMENSIONS} dimensions, got {len(embedding)}. "
            "The model no longer matches the stored vectors, so any distance "
            "measured here would be meaningless."
        )
    return embedding


def _vector_literal(embedding: list[float]) -> str:
    return "[" + ",".join(str(value) for value in embedding) + "]"


def create_read_only_engine() -> Engine:
    """A read-only engine, with the guard bound AT CONNECT.

    Built here rather than through ``create_postgres_engine`` because that
    helper takes no connect arguments and is shared with every loader, which
    writes. Credentials still come from ``require_postgres_environment`` so
    there is one place that reads them.

    The option is passed in ``connect_args`` deliberately. Setting
    ``default_transaction_read_only`` on a connection that is already in use
    silently does nothing - that has caught this project before - so it is
    bound as the connection is made and then verified by
    :func:`assert_read_only`.
    """
    from sqlalchemy import create_engine
    from sqlalchemy.engine import URL

    from archive_etl.config.settings import require_postgres_environment

    values = require_postgres_environment()
    connection_url = URL.create(
        drivername="postgresql+psycopg",
        username=values["POSTGRES_USER"],
        password=values["POSTGRES_PASSWORD"],
        host=values["POSTGRES_HOST"],
        port=int(values["POSTGRES_PORT"]),
        database=values["POSTGRES_DB"],
        query={"sslmode": values["POSTGRES_SSLMODE"]},
    )
    return create_engine(
        connection_url,
        pool_pre_ping=True,
        future=True,
        connect_args={"options": "-c default_transaction_read_only=on"},
    )


def assert_read_only(engine: Engine) -> None:
    """Verify, not assume.

    A read-only setting that is applied and never checked has failed silently
    at least once on this project - see the note in the authorization
    validation runbook about binding the guard at connect time.
    """
    with engine.connect() as connection:
        value = connection.execute(text("SHOW default_transaction_read_only")).scalar()
        if str(value).lower() not in {"on", "true"}:
            raise SystemExit(
                "Refusing to run: the connection is not read-only "
                f"(default_transaction_read_only={value!r})."
            )


def candidate_distances(engine: Engine, embedding: list[float]) -> tuple[float, ...]:
    """The same query the API's SemanticSearchRepository runs, distances only.

    Deliberately selects NO identifying column - not module, not record id,
    not business number. Only the distance, so the output cannot carry record
    content even by accident.
    """
    with engine.connect() as connection:
        rows = connection.execute(
            text(
                """
                SELECT embedding <=> CAST(:query_embedding AS vector) AS distance
                  FROM archive.search_embedding
                 ORDER BY distance
                 LIMIT :window
                """
            ),
            {"query_embedding": _vector_literal(embedding), "window": CANDIDATE_WINDOW},
        ).all()
    return tuple(float(row[0]) for row in rows)


def vocabulary_counts(engine: Engine, query: str) -> dict[str, int]:
    """How many embedded rows contain each of the query's tokens as a word.

    What the corpus-vocabulary gate needs in order to be evaluated rather than
    asserted. Counts only: the SELECT is a ``count(*)``, so no title, no
    identifier and no source text is returned - the token being tested is an
    input, not an output.
    """
    tokens = [t.lower() for t in re.split(r"[^A-Za-z0-9]+", query) if t]
    counts: dict[str, int] = {}
    with engine.connect() as connection:
        for token in tokens:
            counts[token] = int(connection.execute(
                text(
                    # Raw string: \m and \M are Postgres word boundaries and
                    # not Python escapes. Without the r-prefix this is a
                    # SyntaxWarning today and an error in a future Python.
                    r"""
                    SELECT count(*)
                      FROM archive.search_embedding
                     WHERE source_text ~* ('\m' || :token || '\M')
                    """
                ),
                {"token": token},
            ).scalar() or 0)
    return counts


def measure(
    engine: Engine,
    bedrock_client: Any,
    vocabulary: dict[str, dict[str, int]] | None = None,
) -> list[QueryMeasurement]:
    """Embed every labelled query and record its distances and vocabulary.

    ``vocabulary`` is filled in place when supplied, so a caller gets the
    corpus-token counts beside the distances without a second pass.
    """
    if vocabulary is None:
        vocabulary = {}
    measurements: list[QueryMeasurement] = []
    for item in all_queries():
        if item.kind is Kind.IDENTIFIER_SHAPED:
            # The API skips the semantic leg entirely for these, so embedding
            # one here would measure a code path nobody runs.
            print(f"  skipped (identifier-shaped, never embedded): {item.query}")
            continue
        distances = candidate_distances(engine, embed(bedrock_client, item.query))
        if not distances:
            print(f"  NO CANDIDATES for {item.query!r} - is search_embedding populated?")
            continue
        counts = vocabulary_counts(engine, item.query)
        measurements.append(
            QueryMeasurement(
                query=item.query,
                candidate_distances=distances,
                lexical_hits=item.measured_lexical_hits or 0,
            )
        )
        vocabulary[item.query] = counts
        gate = "PASS" if any(n > 0 for n in counts.values()) else "REJECT"
        print(
            f"  {item.label.value:14} {item.kind.value:22} {item.query[:34]:36} "
            f"nearest={distances[0]:.4f} "
            f"ratio={distances[0] / distances[len(distances) // 2]:.4f} "
            f"gate={gate:6} vocab={counts}"
        )
    return measurements


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True)
    parser.add_argument(
        "--output",
        required=True,
        help="Where to write the score evidence. Keep it OUTSIDE the repository.",
    )
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument(
        "--confirm-read-only",
        action="store_true",
        help="Required. Running this is a deliberate act, not a default.",
    )
    args = parser.parse_args(argv)

    if args.environment.strip().lower() in PRODUCTION_NAMES:
        print(
            "Refusing to run against production. Relevance is measured on DEV; "
            "production has its own approved procedures.",
            file=sys.stderr,
        )
        return 1
    if not args.confirm_read_only:
        print(
            "Refusing to run without --confirm-read-only. This reads "
            "archive.search_embedding and calls Bedrock, both of which are "
            "approval-gated.",
            file=sys.stderr,
        )
        return 1

    import boto3  # imported here so --help works with no AWS configuration

    engine = create_read_only_engine()
    assert_read_only(engine)
    print("Read-only connection verified.")

    bedrock_client = boto3.client("bedrock-runtime", region_name=args.region)

    print(f"Measuring {len(all_queries())} labelled queries...")
    vocabulary: dict[str, dict[str, int]] = {}
    measurements = measure(engine, bedrock_client, vocabulary)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "environment": args.environment,
                "embeddingModel": EMBEDDING_MODEL,
                "candidateWindow": CANDIDATE_WINDOW,
                "note": (
                    "Distances only. No record content: the query selects no "
                    "identifying column at all."
                ),
                "measurements": [asdict(m) for m in measurements],
                # Token counts, so the corpus-vocabulary gate can be scored
                # without re-reading the database.
                "vocabulary": vocabulary,
            },
            indent=2,
        )
        + "\n"
    )
    print(f"Wrote {len(measurements)} measurement(s) to {output}")
    print(
        "Next: run the strategy comparison over this file and record the "
        "false-positive and false-negative behaviour before choosing a rule."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
