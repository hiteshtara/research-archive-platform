"""One-off diagnostics task for the Global Search Performance Sprint.

Runs entirely READ-ONLY against the archive database: row counts, family/
current-record counts, EXPLAIN (ANALYZE, BUFFERS) for representative
searches per domain, index inspection, pg_extension/pg_available_extensions
checks, and pg_stat_user_indexes/pg_stat_user_tables. Never writes to the
archive schema.

Deliberately reuses the existing loader task's own VPC/subnet/security-
group/Secrets-Manager wiring (see scripts/run-subaward-loader.sh and
archive_etl.config.ecs.configure_ecs_environment) rather than any new
infrastructure - it is launched the same way an existing load_*.py script
is: build/push the loader image once, register a task-definition revision,
run this script as a command override on the
research-archive-platform-dev-loader task family, then let ECS tear the
task down when it exits. No bastion, no public RDS, no long-lived
infrastructure.

Usage (locally, against a tunneled Postgres):
    uv run python run_search_diagnostics.py --suite global-search-baseline

Usage (--ecs mode, run as a one-off Fargate task):
    uv run python run_search_diagnostics.py --ecs --suite global-search-baseline

Usage (arbitrary SQL file, one statement per line or ;-separated):
    uv run python run_search_diagnostics.py --ecs --sql-file benchmarks/my-check.sql
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import boto3
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.upload.postgres import create_postgres_engine


def _run_ecs_setup() -> None:
    """Postgres-only credential setup - no Oracle secret required for a
    diagnostics task that never touches Oracle."""
    configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)


def _print_step(name: str, payload: Any) -> None:
    print(f"=== {name} ===")
    print(json.dumps(payload, default=str, indent=2))
    print()


def _run_query(engine: Engine, name: str, sql: str, params: dict | None = None) -> list[dict]:
    """Runs one query, prints its result as JSON, and returns the rows -
    a failing individual query is logged and skipped, never aborts the
    whole suite (the same "one bad step doesn't kill the run" principle
    already used by this project's loaders)."""
    try:
        with engine.connect() as connection:
            result = connection.execute(text(sql), params or {})
            rows = [dict(row._mapping) for row in result]
        _print_step(name, rows)
        return rows
    except Exception as error:  # noqa: BLE001 - diagnostics must survive a bad step
        _print_step(name, {"ERROR": str(error)})
        return []


def _sample_value(rows: list[dict], column: str) -> str | None:
    for row in rows:
        value = row.get(column)
        if value:
            return str(value)
    return None


# --- Fixed, always-safe steps -------------------------------------------

ROW_COUNT_QUERIES: dict[str, str] = {
    "award_version_total": "SELECT COUNT(*) AS count FROM archive.award_version",
    "award_version_current": (
        "SELECT COUNT(*) AS count FROM archive.award_version "
        "WHERE is_primary_current = TRUE"
    ),
    "proposal_version_total": "SELECT COUNT(*) AS count FROM archive.proposal_version",
    "proposal_families": (
        "SELECT COUNT(DISTINCT proposal_number) AS count FROM archive.proposal_version"
    ),
    "negotiation_total": "SELECT COUNT(*) AS count FROM archive.negotiation",
    "subaward_total": "SELECT COUNT(*) AS count FROM archive.subaward",
    "subaward_families": (
        "SELECT COUNT(DISTINCT subaward_code) AS count FROM archive.subaward"
    ),
    "subaward_active": (
        "SELECT COUNT(*) AS count FROM archive.subaward "
        "WHERE subaward_sequence_status = 'ACTIVE'"
    ),
    "irb_protocol_version_total": (
        "SELECT COUNT(*) AS count FROM archive.irb_protocol_version"
    ),
    "irb_protocol_families": (
        "SELECT COUNT(DISTINCT protocol_base) AS count FROM archive.irb_protocol_version"
    ),
    "v_global_search_rows": "SELECT COUNT(*) AS count FROM archive.v_global_search",
}

EXTENSION_CHECK_SQL = "SELECT extname, extversion FROM pg_extension ORDER BY extname"

AVAILABLE_EXTENSIONS_SQL = """
    SELECT name, default_version, installed_version
    FROM pg_available_extensions
    WHERE name IN ('pg_trgm', 'vector')
    ORDER BY name
"""

INDEX_INSPECTION_SQL = """
    SELECT schemaname, tablename, indexname, indexdef
    FROM pg_indexes
    WHERE schemaname = 'archive'
      AND tablename IN (
          'award_version', 'award_person', 'proposal_version',
          'negotiation', 'subaward', 'irb_protocol_version'
      )
    ORDER BY tablename, indexname
"""

STAT_USER_INDEXES_SQL = """
    SELECT schemaname, relname, indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
    FROM pg_stat_user_indexes
    WHERE schemaname = 'archive'
    ORDER BY relname, indexrelname
"""

STAT_USER_TABLES_SQL = """
    SELECT schemaname, relname, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch,
           n_live_tup, n_dead_tup
    FROM pg_stat_user_tables
    WHERE schemaname = 'archive'
    ORDER BY seq_scan DESC
"""

NO_MATCH_QUERY = "ZZZZ-NO-SUCH-RECORD-999999"


def _run_global_search_baseline(engine: Engine) -> None:
    # 1. Row counts / family counts - the numbers this session's earlier
    # audit found genuinely undocumented anywhere in the repo.
    for name, sql in ROW_COUNT_QUERIES.items():
        _run_query(engine, f"row_count.{name}", sql)

    # 2. Extension availability/installed state - confirms what the
    # AWS-docs-only check from the audit could not: whether pg_trgm/
    # vector are actually CREATEd in this database yet.
    _run_query(engine, "pg_extension", EXTENSION_CHECK_SQL)
    _run_query(engine, "pg_available_extensions", AVAILABLE_EXTENSIONS_SQL)

    # 3. Existing indexes on every table any domain's search touches.
    _run_query(engine, "pg_indexes", INDEX_INSPECTION_SQL)

    # 4. Usage stats - which indexes are actually being hit, which
    # tables are taking sequential scans.
    _run_query(engine, "pg_stat_user_indexes", STAT_USER_INDEXES_SQL)
    _run_query(engine, "pg_stat_user_tables", STAT_USER_TABLES_SQL)

    # 5. Sample one real row per domain so the EXPLAIN benchmarks below
    # run against real data, not guessed values - the whole point of a
    # "capture live baselines" step is not to already know the answer.
    award_sample = _run_query(
        engine, "sample.award",
        "SELECT award_number, title, sponsor_name, lead_unit_name "
        "FROM archive.award_version WHERE is_primary_current = TRUE "
        "AND title IS NOT NULL LIMIT 1",
    )
    proposal_sample = _run_query(
        engine, "sample.proposal",
        "SELECT proposal_number, title, sponsor_name, "
        "principal_investigator_name FROM archive.proposal_version "
        "WHERE title IS NOT NULL ORDER BY version_number DESC LIMIT 1",
    )
    negotiation_sample = _run_query(
        engine, "sample.negotiation",
        "SELECT document_number, negotiator_full_name "
        "FROM archive.negotiation WHERE document_number IS NOT NULL LIMIT 1",
    )
    subaward_sample = _run_query(
        engine, "sample.subaward",
        "SELECT subaward_code, title, award_sponsor_name "
        "FROM archive.subaward WHERE subaward_sequence_status = 'ACTIVE' "
        "AND title IS NOT NULL LIMIT 1",
    )
    irb_sample = _run_query(
        engine, "sample.irb",
        "SELECT protocol_number, title, pi_full_name "
        "FROM archive.v_global_search WHERE module = 'IRB' "
        "AND title IS NOT NULL LIMIT 1",
    )

    # 6. EXPLAIN (ANALYZE, BUFFERS) for representative searches per
    # domain, mirroring each repository's real WHERE clause - see the
    # comment above each block for the exact source method it mirrors.
    # "Representative," not a byte-for-byte copy of every column - the
    # goal is a real, indexable-or-not query shape per search type, not
    # a perfect transcription.

    award_number = _sample_value(award_sample, "award_number")
    award_title = _sample_value(award_sample, "title")
    award_sponsor = _sample_value(award_sample, "sponsor_name")
    if award_number:
        # Mirrors AwardArchiveRepository.searchAwards - exact identifier
        _run_query(engine, "explain.award.exact_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT av.award_id, av.award_number, av.title
            FROM archive.award_version av
            WHERE av.is_primary_current = TRUE
              AND UPPER(av.award_number) = UPPER(:value)
        """, {"value": award_number})
        _run_query(engine, "explain.award.partial_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT av.award_id, av.award_number, av.title
            FROM archive.award_version av
            WHERE av.is_primary_current = TRUE
              AND av.award_number ILIKE :pattern
        """, {"pattern": f"%{award_number[:4]}%"})
    if award_title:
        _run_query(engine, "explain.award.title_keyword", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT av.award_id, av.award_number, av.title
            FROM archive.award_version av
            WHERE av.is_primary_current = TRUE
              AND av.title ILIKE :pattern
        """, {"pattern": f"%{award_title.split()[0]}%"})
    if award_sponsor:
        _run_query(engine, "explain.award.sponsor", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT av.award_id, av.award_number, av.sponsor_name
            FROM archive.award_version av
            WHERE av.is_primary_current = TRUE
              AND av.sponsor_name ILIKE :pattern
        """, {"pattern": f"%{award_sponsor[:6]}%"})
    _run_query(engine, "explain.award.no_match", """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
        SELECT av.award_id FROM archive.award_version av
        WHERE av.is_primary_current = TRUE
          AND av.award_number ILIKE :pattern
    """, {"pattern": f"%{NO_MATCH_QUERY}%"})

    # Mirrors ProposalArchiveRepository.findFamilies
    proposal_number = _sample_value(proposal_sample, "proposal_number")
    proposal_pi = _sample_value(proposal_sample, "principal_investigator_name")
    if proposal_number:
        _run_query(engine, "explain.proposal.exact_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            WITH ranked AS (
                SELECT proposal_id, proposal_number, title,
                       ROW_NUMBER() OVER (
                           PARTITION BY proposal_number
                           ORDER BY version_number DESC,
                               source_update_timestamp DESC NULLS LAST,
                               proposal_id DESC
                       ) AS row_rank
                FROM archive.proposal_version
                WHERE proposal_number ILIKE :pattern
            )
            SELECT * FROM ranked WHERE row_rank = 1
        """, {"pattern": f"%{proposal_number}%"})
    if proposal_pi:
        _run_query(engine, "explain.proposal.pi_person", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT proposal_id, proposal_number, principal_investigator_name
            FROM archive.proposal_version
            WHERE principal_investigator_name ILIKE :pattern
        """, {"pattern": f"%{proposal_pi.split()[0]}%"})
    _run_query(engine, "explain.proposal.no_match", """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
        SELECT proposal_id FROM archive.proposal_version
        WHERE proposal_number ILIKE :pattern
    """, {"pattern": f"%{NO_MATCH_QUERY}%"})

    # Mirrors NegotiationArchiveRepository.findNegotiations
    negotiation_doc = _sample_value(negotiation_sample, "document_number")
    negotiator_name = _sample_value(negotiation_sample, "negotiator_full_name")
    if negotiation_doc:
        _run_query(engine, "explain.negotiation.exact_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT negotiation_id, document_number
            FROM archive.negotiation
            WHERE document_number ILIKE :pattern
        """, {"pattern": f"%{negotiation_doc}%"})
    if negotiator_name:
        _run_query(engine, "explain.negotiation.person", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT negotiation_id, negotiator_full_name
            FROM archive.negotiation
            WHERE negotiator_full_name ILIKE :pattern
        """, {"pattern": f"%{negotiator_name.split()[0]}%"})
    _run_query(engine, "explain.negotiation.no_match", """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
        SELECT negotiation_id FROM archive.negotiation
        WHERE document_number ILIKE :pattern
    """, {"pattern": f"%{NO_MATCH_QUERY}%"})

    # Mirrors SubawardArchiveRepository.findSubawards/subawardFilter -
    # NOTE: the real query has no subaward_sequence_status filter at
    # all (confirmed by reading the source directly), so this EXPLAIN
    # deliberately does not add one either - it must reflect what
    # production actually runs, including that gap.
    subaward_code = _sample_value(subaward_sample, "subaward_code")
    subaward_title = _sample_value(subaward_sample, "title")
    if subaward_code:
        _run_query(engine, "explain.subaward.exact_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT subaward_id, subaward_code, sequence_number
            FROM archive.subaward
            WHERE subaward_code ILIKE :pattern
            ORDER BY source_update_timestamp DESC NULLS LAST,
                sequence_number DESC, subaward_id DESC
        """, {"pattern": f"%{subaward_code}%"})
    if subaward_title:
        _run_query(engine, "explain.subaward.title_keyword", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT subaward_id, subaward_code, title
            FROM archive.subaward
            WHERE title ILIKE :pattern
            ORDER BY source_update_timestamp DESC NULLS LAST,
                sequence_number DESC, subaward_id DESC
        """, {"pattern": f"%{subaward_title.split()[0]}%"})
    _run_query(engine, "explain.subaward.no_match", """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
        SELECT subaward_id FROM archive.subaward
        WHERE subaward_code ILIKE :pattern
    """, {"pattern": f"%{NO_MATCH_QUERY}%"})

    # Mirrors archive.v_global_search's own search_text ILIKE (see
    # V010__expand_global_search_to_history.sql) - the view has no
    # per-column WHERE, only one concatenated search_text column.
    irb_protocol_number = _sample_value(irb_sample, "protocol_number")
    irb_pi = _sample_value(irb_sample, "pi_full_name")
    if irb_protocol_number:
        _run_query(engine, "explain.irb.exact_identifier", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT record_id, protocol_number, title
            FROM archive.v_global_search
            WHERE search_text ILIKE :pattern ESCAPE '\\'
        """, {"pattern": f"%{irb_protocol_number}%"})
    if irb_pi:
        _run_query(engine, "explain.irb.person", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT record_id, pi_full_name
            FROM archive.v_global_search
            WHERE search_text ILIKE :pattern ESCAPE '\\'
        """, {"pattern": f"%{irb_pi.split()[0]}%"})
    _run_query(engine, "explain.irb.no_match", """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
        SELECT record_id FROM archive.v_global_search
        WHERE search_text ILIKE :pattern ESCAPE '\\'
    """, {"pattern": f"%{NO_MATCH_QUERY}%"})


def _run_isolate_anomaly(engine: Engine) -> None:
    """Re-runs the two queries that showed anomalous timing on the first
    global-search-baseline run (Award exact_identifier: 855ms, Negotiation
    exact_identifier: 379ms - both far slower than sibling queries in the
    same domain, and both happened to be the FIRST EXPLAIN run for their
    domain in that suite). Runs each twice in a row to distinguish a real
    missing-index cost from a one-time warmup/CPU-credit artifact on the
    t4g.micro dev instance."""
    for attempt in (1, 2):
        _run_query(engine, f"isolate.award_exact_identifier.attempt_{attempt}", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT av.award_id, av.award_number, av.title
            FROM archive.award_version av
            WHERE av.is_primary_current = TRUE
              AND UPPER(av.award_number) = UPPER(:value)
        """, {"value": "100803-00001"})
    for attempt in (1, 2):
        _run_query(engine, f"isolate.negotiation_exact_identifier.attempt_{attempt}", """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
            SELECT negotiation_id, document_number
            FROM archive.negotiation
            WHERE document_number ILIKE :pattern
        """, {"pattern": "%367756%"})


# Exact candidate-selection SQL for each of the 4 domains
# build_search_embedding.py actually embeds - copied verbatim (not just
# a generic family count) so this suite reports the REAL number of
# Bedrock calls a full population run will make, including the
# `title IS NOT NULL`-style filters that a bare COUNT(DISTINCT ...)
# would miss.
SEMANTIC_SCOPE_CANDIDATE_QUERIES: dict[str, str] = {
    "award": """
        SELECT COUNT(*) AS count
        FROM archive.award_version av
        WHERE av.is_primary_current = TRUE AND av.title IS NOT NULL
    """,
    "proposal": """
        WITH ranked AS (
            SELECT proposal_id,
                   ROW_NUMBER() OVER (
                       PARTITION BY proposal_number
                       ORDER BY version_number DESC,
                           source_update_timestamp DESC NULLS LAST,
                           proposal_id DESC
                   ) AS row_rank
            FROM archive.proposal_version
            WHERE title IS NOT NULL
        )
        SELECT COUNT(*) AS count FROM ranked WHERE row_rank = 1
    """,
    "negotiation": (
        "SELECT COUNT(*) AS count FROM archive.negotiation "
        "WHERE document_number IS NOT NULL"
    ),
    "subaward": (
        "SELECT COUNT(*) AS count FROM archive.subaward "
        "WHERE subaward_sequence_status = 'ACTIVE' AND title IS NOT NULL"
    ),
}

POC_EMBEDDING_STATS_SQL = """
    SELECT module,
           COUNT(*) AS row_count,
           MIN(LENGTH(source_text)) AS min_chars,
           MAX(LENGTH(source_text)) AS max_chars,
           ROUND(AVG(LENGTH(source_text))) AS avg_chars,
           ROUND(STDDEV(LENGTH(source_text))) AS stddev_chars
    FROM archive.search_embedding_poc
    GROUP BY module
    ORDER BY module
"""

POC_EMBEDDING_OVERALL_STATS_SQL = """
    SELECT COUNT(*) AS row_count,
           MIN(LENGTH(source_text)) AS min_chars,
           MAX(LENGTH(source_text)) AS max_chars,
           ROUND(AVG(LENGTH(source_text))) AS avg_chars,
           ROUND(STDDEV(LENGTH(source_text))) AS stddev_chars,
           SUM(LENGTH(source_text)) AS total_chars
    FROM archive.search_embedding_poc
"""


def _run_semantic_search_scope(engine: Engine) -> None:
    """Real production population sizing for the semantic-search
    integration - candidate counts per domain (the exact set
    build_search_embedding.py will embed), archive.search_embedding_poc's
    real observed source_text sizes (for token/cost estimation), and
    IRB's current-family count as an informational figure only (IRB is
    NOT part of build_search_embedding.py's DOMAIN_QUERIES / the
    semantic-search scope - see GlobalSearchService's own comments)."""
    total = 0
    for name, sql in SEMANTIC_SCOPE_CANDIDATE_QUERIES.items():
        rows = _run_query(engine, f"candidates.{name}", sql)
        if rows:
            total += rows[0]["count"] or 0
    _print_step("candidates.total_in_scope", {"count": total})

    _run_query(
        engine, "irb.current_families_informational_only",
        "SELECT COUNT(DISTINCT protocol_base) AS count "
        "FROM archive.irb_protocol_version"
    )

    _run_query(engine, "poc_embedding.stats_by_module", POC_EMBEDDING_STATS_SQL)
    _run_query(engine, "poc_embedding.stats_overall", POC_EMBEDDING_OVERALL_STATS_SQL)


SUITES = {
    "global-search-baseline": _run_global_search_baseline,
    "isolate-anomaly": _run_isolate_anomaly,
    "semantic-search-scope": _run_semantic_search_scope,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Resolve PostgreSQL credentials from Secrets Manager via the "
            "ECS task's environment instead of requiring local exports - "
            "see archive_etl.config.ecs."
        ),
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--suite",
        choices=sorted(SUITES.keys()),
        help="Run a named, predefined benchmark suite.",
    )
    group.add_argument(
        "--sql-file",
        type=Path,
        help="Run every ;-separated statement in this SQL file, in order.",
    )
    arguments = parser.parse_args(argv)

    if arguments.ecs:
        _run_ecs_setup()

    engine = create_postgres_engine()

    print("===GLOBAL_SEARCH_DIAGNOSTICS_START===")

    if arguments.suite:
        SUITES[arguments.suite](engine)
    else:
        sql_text = arguments.sql_file.read_text()
        statements = [s.strip() for s in sql_text.split(";") if s.strip()]
        for index, statement in enumerate(statements):
            _run_query(engine, f"sql_file.statement_{index}", statement)

    print("===GLOBAL_SEARCH_DIAGNOSTICS_END===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
