"""EXPLAIN (ANALYZE, BUFFERS) for the real Award dashboard queries
(copied verbatim from AwardArchiveRepository.java), scoped to one
award_id/award_number. Read-only performance measurement, never writes.

Usage:
    uv run python postgres_award_performance.py --award-id 3187665 \
        --award-number 204713-00133 --ecs --json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid

import boto3
from loguru import logger
from sqlalchemy import text

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.startup_validation import validate_aws_identity
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.structured_logging import configure_structured_logging

QUERIES = {
    "summary": (
        """
        SELECT
            av.award_id, av.award_number, av.sequence_number, av.title,
            av.status_description AS status, av.sponsor_name AS sponsor,
            av.prime_sponsor_name AS prime_sponsor, pi.full_name AS principal_investigator,
            av.lead_unit_name AS lead_unit, av.award_effective_date, av.award_execution_date,
            av.begin_date, av.closeout_date,
            amt.obligated_total_amount, amt.anticipated_total_amount,
            av.basis_of_payment_code, av.basis_of_payment_description,
            av.method_of_payment_code, av.method_of_payment_description,
            ah.root_award_number, ah.parent_award_number
        FROM archive.award_version av
        LEFT JOIN LATERAL (
            SELECT ap.full_name FROM archive.award_person ap
            WHERE ap.award_id = av.award_id
            ORDER BY CASE WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI' THEN 0 ELSE 1 END,
                     ap.full_name NULLS LAST, ap.award_person_id
            LIMIT 1
        ) pi ON TRUE
        LEFT JOIN LATERAL (
            SELECT ai.obligated_total_amount, ai.anticipated_total_amount
            FROM archive.award_amount_info ai
            WHERE ai.award_id = av.award_id
            ORDER BY ai.source_version_number DESC NULLS LAST, ai.award_amount_info_id DESC
            LIMIT 1
        ) amt ON TRUE
        LEFT JOIN archive.award_hierarchy ah ON ah.award_number = av.award_number
        WHERE av.award_id = :award_id
        """,
        {"award_id": None},
    ),
    "versions_page1": (
        """
        SELECT award_id, award_number, sequence_number, status_description AS status,
               transaction_type_code, transaction_type, award_effective_date,
               source_update_timestamp AS update_timestamp,
               workflow_document_number AS document_number, modification_number,
               is_primary_current AS primary_current
        FROM archive.award_version
        WHERE award_number = :award_number
        ORDER BY sequence_number DESC, source_update_timestamp DESC NULLS LAST, award_id DESC
        LIMIT 10 OFFSET 0
        """,
        {"award_number": None},
    ),
    "people_rows": (
        """
        SELECT * FROM archive.award_person WHERE award_id = :award_id
        """,
        {"award_id": None},
    ),
    "amount_history": (
        """
        SELECT * FROM archive.award_amount_info WHERE award_id = :award_id
        ORDER BY source_version_number DESC NULLS LAST, award_amount_info_id DESC
        """,
        {"award_id": None},
    ),
    "budget_in_scope": (
        """
        SELECT * FROM archive.award_budget WHERE award_id = :award_id
        ORDER BY budget_version_number DESC
        """,
        {"award_id": None},
    ),
    "time_and_money_actions": (
        """
        SELECT * FROM archive.award_amount_transaction
        WHERE award_number = :award_number
        ORDER BY document_number DESC
        LIMIT 25 OFFSET 0
        """,
        {"award_number": None},
    ),
    "relationship_negotiations": (
        """
        SELECT negotiation_id, document_number, negotiation_status_description,
               negotiation_agreement_type_description, negotiator_full_name,
               negotiation_start_date, negotiation_end_date
        FROM archive.negotiation
        WHERE negotiation_association_type_code = 'AWD' AND associated_document_id = :award_number
        ORDER BY negotiation_start_date DESC NULLS LAST, negotiation_id DESC
        """,
        {"award_number": None},
    ),
    "attachments": (
        """
        SELECT aa.award_attachment_id, aa.award_number, aa.sequence_number,
               ao.file_name, ao.content_type, aa.description, aa.type_code,
               aa.document_status_code, ao.file_size_bytes, ao.upload_status
        FROM archive.award_attachment aa
        LEFT JOIN archive.attachment_object ao ON ao.file_id = aa.file_id
        WHERE aa.award_id = :award_id
        ORDER BY aa.oracle_update_timestamp DESC NULLS LAST, aa.award_attachment_id DESC
        LIMIT 25 OFFSET 0
        """,
        {"award_id": None},
    ),
}

# budget_line_items needs a resolved budget_id, handled specially in main()
BUDGET_LINE_ITEMS_SQL = """
    SELECT bli.budget_line_item_id, bli.budget_period_id, bli.line_item_number,
           bli.line_item_description AS description, bli.cost_element,
           bli.start_date, bli.end_date, bli.line_item_cost, bli.cost_sharing_amount
    FROM archive.award_budget_line_item bli
    JOIN archive.award_budget_period bp ON bp.budget_period_id = bli.budget_period_id
    WHERE bp.budget_id = :budget_id
    ORDER BY bp.budget_period, bli.line_item_number, bli.budget_line_item_id
    LIMIT 25 OFFSET 0
"""


def run(award_id: int, award_number: str) -> dict:
    engine = create_postgres_engine()
    results = {}
    with engine.connect() as connection:
        for name, (sql, params) in QUERIES.items():
            bound = {k: (award_id if k == "award_id" else award_number) for k in params}
            start = time.perf_counter()
            explain_sql = f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {sql}"
            plan = connection.execute(text(explain_sql), bound).scalar_one()
            elapsed_ms = (time.perf_counter() - start) * 1000
            root = plan[0]
            results[name] = {
                "wall_clock_ms": round(elapsed_ms, 2),
                "planner_total_time_ms": round(root["Execution Time"], 2),
                "planning_time_ms": round(root["Planning Time"], 2),
                "shared_hit_blocks": root["Plan"].get("Shared Hit Blocks"),
                "shared_read_blocks": root["Plan"].get("Shared Read Blocks"),
                "actual_rows": root["Plan"].get("Actual Rows"),
            }

        # Resolve a real budget_id for this award, then time the line-items query.
        budget_id = connection.execute(
            text("SELECT budget_id FROM archive.award_budget WHERE award_id = :award_id LIMIT 1"),
            {"award_id": award_id},
        ).scalar()
        if budget_id is not None:
            start = time.perf_counter()
            plan = connection.execute(
                text(f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {BUDGET_LINE_ITEMS_SQL}"),
                {"budget_id": budget_id},
            ).scalar_one()
            elapsed_ms = (time.perf_counter() - start) * 1000
            root = plan[0]
            results["budget_line_items_page1"] = {
                "budget_id": budget_id,
                "wall_clock_ms": round(elapsed_ms, 2),
                "planner_total_time_ms": round(root["Execution Time"], 2),
                "planning_time_ms": round(root["Planning Time"], 2),
                "shared_hit_blocks": root["Plan"].get("Shared Hit Blocks"),
                "shared_read_blocks": root["Plan"].get("Shared Read Blocks"),
                "actual_rows": root["Plan"].get("Actual Rows"),
            }
        else:
            results["budget_line_items_page1"] = {"error": "no budget row found"}

    return {"award_id": award_id, "award_number": award_number, "queries": results}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--award-id", required=True, type=int)
    parser.add_argument("--award-number", required=True)
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

    report = run(args.award_id, args.award_number)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for name, stats in report["queries"].items():
            print(f"{name}: {stats}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001
        logger.exception("postgres_award_performance failed")
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
