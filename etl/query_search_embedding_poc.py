"""Global Search semantic-search proof-of-concept: runs a fixed set of
semantic test queries against archive.search_embedding_poc (cosine
similarity via pgvector) AND the equivalent lexical search (ILIKE against
the same source_text), reporting both side by side with latency, so a
human reviewer can judge relevance and false positives directly from the
real returned records - this script does not claim to auto-determine
"correct" results; there is no ground-truth label set.

Read-only against archive.search_embedding_poc. Does not touch
GlobalSearchService, GlobalSearchRepository, or any production search
path - this is comparison-only, per the experiment's explicit scope.

Usage:
    uv run python query_search_embedding_poc.py --ecs
    uv run python query_search_embedding_poc.py --ecs --query "diabetes research involving children"
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from typing import Any

import boto3
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.upload.postgres import create_postgres_engine
from build_search_embedding_poc import EMBEDDING_MODEL, _vector_literal, embed_text

DEFAULT_QUERIES = [
    "diabetes research involving children",
    "neuroimaging and PTSD research",
    "pharmaceutical clinical trial agreements",
    "autism research involving adolescents",
    "large federally funded biomedical projects",
]

VECTOR_SEARCH_SQL = """
    SELECT module, record_id, business_number, title,
           embedding <=> CAST(:query_embedding AS vector) AS distance
    FROM archive.search_embedding_poc
    ORDER BY distance
    LIMIT :top_k
"""

LEXICAL_SEARCH_SQL = """
    SELECT module, record_id, business_number, title
    FROM archive.search_embedding_poc
    WHERE source_text ILIKE :pattern
    LIMIT 10
"""


def _run_ecs_setup() -> None:
    configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)


def run_query(
    engine: Engine, bedrock_client: Any, query_text: str, top_k: int = 10
) -> dict:
    lexical_start = time.perf_counter()
    with engine.connect() as connection:
        lexical_rows = connection.execute(
            text(LEXICAL_SEARCH_SQL), {"pattern": f"%{query_text}%"}
        ).mappings().all()
    lexical_ms = (time.perf_counter() - lexical_start) * 1000

    vector_start = time.perf_counter()
    query_embedding = embed_text(bedrock_client, query_text)
    embedding_ms = (time.perf_counter() - vector_start) * 1000

    vector_query_start = time.perf_counter()
    with engine.connect() as connection:
        vector_rows = connection.execute(
            text(VECTOR_SEARCH_SQL),
            {"query_embedding": _vector_literal(query_embedding), "top_k": top_k},
        ).mappings().all()
    vector_query_ms = (time.perf_counter() - vector_query_start) * 1000

    return {
        "query": query_text,
        "lexical": {
            "latency_ms": round(lexical_ms, 2),
            "result_count": len(lexical_rows),
            "results": [dict(r) for r in lexical_rows],
        },
        "vector": {
            "embedding_latency_ms": round(embedding_ms, 2),
            "query_latency_ms": round(vector_query_ms, 2),
            "total_latency_ms": round(embedding_ms + vector_query_ms, 2),
            "result_count": len(vector_rows),
            "results": [dict(r) for r in vector_rows],
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ecs", action="store_true")
    parser.add_argument(
        "--query", action="append", dest="queries",
        help="Run a specific query instead of the default 5-query set "
             "(repeatable).",
    )
    parser.add_argument(
        "--top-k", type=int, default=10,
        help="Number of vector results to return per query (default 10).",
    )
    arguments = parser.parse_args(argv)

    if arguments.ecs:
        _run_ecs_setup()

    engine = create_postgres_engine()
    bedrock_client = boto3.client("bedrock-runtime")

    queries = arguments.queries or DEFAULT_QUERIES

    print("===SEARCH_EMBEDDING_POC_EXPERIMENT_START===")
    print(f"embedding_model: {EMBEDDING_MODEL}")

    for query_text in queries:
        result = run_query(engine, bedrock_client, query_text, arguments.top_k)
        print(f"=== query: {query_text} ===")
        print(json.dumps(result, default=str, indent=2))

    print("===SEARCH_EMBEDDING_POC_EXPERIMENT_END===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
