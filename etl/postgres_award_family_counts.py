"""Read-only PostgreSQL archive count report for one Award family, by
award_number. Companion to investigate_award_family.py (the Oracle-side
audit) - used only to build the Phase 4 Oracle-vs-archive reconciliation
table. Never writes anything.

Usage:
    uv run python postgres_award_family_counts.py --award-number 204713-00133 --ecs --json
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

_AWARD_IDS_SUBQUERY = (
    "(SELECT award_id FROM archive.award_version WHERE award_number = :award_number)"
)
_BUDGET_IDS_SUBQUERY = (
    f"(SELECT budget_id FROM archive.award_budget WHERE award_id IN {_AWARD_IDS_SUBQUERY})"
)

QUERIES = {
    # These tables carry award_number directly (verified against the
    # migration DDL, not assumed).
    "award_versions": (
        "SELECT COUNT(*) FROM archive.award_version "
        "WHERE award_number = :award_number"
    ),
    "people": (
        "SELECT COUNT(*) FROM archive.award_person "
        "WHERE award_number = :award_number"
    ),
    "units": (
        "SELECT COUNT(*) FROM archive.award_person_unit "
        "WHERE award_number = :award_number"
    ),
    "amount_rows": (
        "SELECT COUNT(*) FROM archive.award_amount_info "
        "WHERE award_number = :award_number"
    ),
    "comments": (
        "SELECT COUNT(*) FROM archive.award_comment "
        "WHERE award_number = :award_number"
    ),
    "notepad": (
        "SELECT COUNT(*) FROM archive.award_notepad "
        "WHERE award_number = :award_number"
    ),
    "sponsor_terms": (
        "SELECT COUNT(*) FROM archive.award_sponsor_term "
        "WHERE award_number = :award_number"
    ),
    "report_terms": (
        "SELECT COUNT(*) FROM archive.award_report_term "
        "WHERE award_number = :award_number"
    ),
    "attachments_metadata": (
        "SELECT COUNT(*) FROM archive.award_attachment "
        "WHERE award_number = :award_number"
    ),
    "time_and_money_document": (
        "SELECT COUNT(*) FROM archive.time_and_money_document "
        "WHERE root_award_number = :award_number"
    ),
    "time_and_money_pending_transactions": (
        "SELECT COUNT(*) FROM archive.pending_transaction "
        "WHERE source_award_number = :award_number "
        "OR destination_award_number = :award_number"
    ),
    "time_and_money_transaction_details": (
        "SELECT COUNT(*) FROM archive.transaction_detail "
        "WHERE award_number = :award_number"
    ),
    "time_and_money_amount_transactions": (
        "SELECT COUNT(*) FROM archive.award_amount_transaction "
        "WHERE award_number = :award_number"
    ),
    "subawards": (
        "SELECT COUNT(DISTINCT subaward_id) FROM archive.subaward_funding "
        "WHERE award_number = :award_number"
    ),
    "negotiations": (
        "SELECT COUNT(*) FROM archive.negotiation "
        "WHERE negotiation_association_type_code = 'AWD' "
        "AND associated_document_id = :award_number"
    ),
    # These tables only carry award_id (verified - no award_number column
    # exists), so resolve via archive.award_version first.
    "budget_versions": (
        f"SELECT COUNT(*) FROM archive.award_budget "
        f"WHERE award_id IN {_AWARD_IDS_SUBQUERY}"
    ),
    "funding_proposal_links": (
        f"SELECT COUNT(*) FROM archive.award_funding_proposal "
        f"WHERE award_id IN {_AWARD_IDS_SUBQUERY}"
    ),
    # These only carry budget_id, resolved one hop further through award_budget.
    "budget_periods": (
        f"SELECT COUNT(*) FROM archive.award_budget_period "
        f"WHERE budget_id IN {_BUDGET_IDS_SUBQUERY}"
    ),
    "budget_line_items": (
        f"SELECT COUNT(*) FROM archive.award_budget_line_item "
        f"WHERE budget_id IN {_BUDGET_IDS_SUBQUERY}"
    ),
    "budget_personnel": (
        f"SELECT COUNT(*) FROM archive.award_budget_personnel_detail "
        f"WHERE budget_id IN {_BUDGET_IDS_SUBQUERY}"
    ),
}


def investigate(award_number: str) -> dict:
    engine = create_postgres_engine()
    counts = {}
    with engine.connect() as connection:
        for key, sql in QUERIES.items():
            result = connection.execute(text(sql), {"award_number": award_number})
            counts[key] = result.scalar_one()
    counts["time_and_money_total"] = (
        counts["time_and_money_document"]
        + counts["time_and_money_pending_transactions"]
        + counts["time_and_money_transaction_details"]
        + counts["time_and_money_amount_transactions"]
    )
    return {"award_number": award_number, "counts": counts}


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only PostgreSQL archive count report.")
    parser.add_argument("--award-number", required=True, metavar="AWARD_NUMBER")
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

    report = investigate(args.award_number)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for key, value in report["counts"].items():
            print(f"  {key}: {value}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001
        logger.exception("postgres_award_family_counts failed")
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
