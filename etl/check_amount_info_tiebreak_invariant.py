"""Read-only Postgres invariant check: does AwardArchiveRepository's
two-column tiebreak (source_version_number DESC NULLS LAST,
award_amount_info_id DESC) ever select a DIFFERENT award_amount_info row
than the plain one-column Kuali rule (MAX(award_amount_info_id)) used
elsewhere in the same file? Also reports how common the "orphan tail"
pattern (a TNM document with tnm_document_number set but transaction_id
NULL) is across the whole archive. Developer aid, never writes anything.

Usage:
    uv run python check_amount_info_tiebreak_invariant.py --ecs --json
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid

import boto3
from loguru import logger
from sqlalchemy import text

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.startup_validation import validate_aws_identity
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.structured_logging import configure_structured_logging

DIVERGENCE_SQL = """
WITH two_column AS (
    SELECT DISTINCT ON (award_id)
        award_id, award_amount_info_id AS two_column_id
    FROM archive.award_amount_info
    ORDER BY award_id, source_version_number DESC NULLS LAST, award_amount_info_id DESC
),
one_column AS (
    SELECT DISTINCT ON (award_id)
        award_id, award_amount_info_id AS one_column_id
    FROM archive.award_amount_info
    ORDER BY award_id, award_amount_info_id DESC
)
SELECT t.award_id, t.two_column_id, o.one_column_id
FROM two_column t
JOIN one_column o ON o.award_id = t.award_id
WHERE t.two_column_id <> o.one_column_id
"""

ORPHAN_TAIL_SQL = """
SELECT award_id, award_amount_info_id, tnm_document_number, transaction_id
FROM archive.award_amount_info
WHERE tnm_document_number IS NOT NULL
  AND transaction_id IS NULL
ORDER BY award_id, award_amount_info_id
"""

MULTI_ROW_COUNT_SQL = """
SELECT COUNT(*) FROM (
    SELECT award_id
    FROM archive.award_amount_info
    GROUP BY award_id
    HAVING COUNT(*) > 1
) sub
"""


def run() -> dict:
    engine = create_postgres_engine()
    with engine.connect() as connection:
        divergences = [
            dict(row._mapping)
            for row in connection.execute(text(DIVERGENCE_SQL)).fetchall()
        ]
        orphans = [
            dict(row._mapping)
            for row in connection.execute(text(ORPHAN_TAIL_SQL)).fetchall()
        ]
        multi_row_award_count = connection.execute(
            text(MULTI_ROW_COUNT_SQL)
        ).scalar_one()

    return {
        "multi_row_award_count": multi_row_award_count,
        "two_column_vs_one_column_divergence_count": len(divergences),
        "two_column_vs_one_column_divergences": divergences,
        "orphan_tail_row_count": len(orphans),
        "orphan_tail_rows": orphans,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ecs", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    if args.ecs:
        run_id = str(uuid.uuid4())
        configure_structured_logging(run_id)
        identity = validate_aws_identity(boto3.client("sts"))
        logger.bind(stage="startup").info(
            "AWS identity resolved via ECS task role: account={}", identity["account"]
        )
        configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)

    report = run()
    if args.json:
        print(json.dumps(report, indent=2, default=str))
    else:
        print(f"multi_row_award_count: {report['multi_row_award_count']}")
        print(
            "two_column_vs_one_column_divergence_count: "
            f"{report['two_column_vs_one_column_divergence_count']}"
        )
        for row in report["two_column_vs_one_column_divergences"]:
            print("  DIVERGENCE:", row)
        print(f"orphan_tail_row_count: {report['orphan_tail_row_count']}")
        for row in report["orphan_tail_rows"]:
            print("  ORPHAN:", row)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001
        logger.exception("check_amount_info_tiebreak_invariant failed")
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
