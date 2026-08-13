"""Read-only Oracle richness audit for one Award family, by award_number.

Developer/investigation aid, NOT a production loader - never writes to
Oracle or PostgreSQL. Every query is a targeted, bind-variable-scoped
read against exactly one award_number's family (never a full table
scan), following the same OracleDataSource / bind-list-chunking
conventions as load_awards_from_csv.py's --diff-award-versions and
--investigate-workflow-document-number developer aids.

Usage:
    uv run python investigate_award_family.py --award-number 204713-00133
    uv run python investigate_award_family.py --award-number 204713-00133 --ecs

All Oracle table/column names below are taken directly from
sql/extract/award/*.sql and oracle/{negotiation,subaward}/*.sql (never
invented) - see the module docstrings in load_awards_from_csv.py,
load_negotiations_from_csv.py, load_subawards_from_csv.py, and
load_award_attachments.py for the source of truth each query mirrors.
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from dataclasses import dataclass, field

import boto3
import oracledb
from loguru import logger

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.config.startup_validation import (
    validate_aws_identity,
    validate_oracle_reachable,
)
from archive_etl.utils.structured_logging import configure_structured_logging

MAX_IN_LIST_SIZE = 1000


def _connect_oracle() -> oracledb.Connection:
    credentials = require_oracle_environment()
    return oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    )


def _chunked(values, size=MAX_IN_LIST_SIZE):
    values = list(values)
    for i in range(0, len(values), size):
        yield values[i : i + size]


def _count_in(cursor, table, column, values) -> int:
    """SELECT COUNT(*) FROM <table> WHERE <column> IN (:b0,...), chunked."""
    values = list(values)
    if not values:
        return 0
    total = 0
    for chunk in _chunked(values):
        placeholders = ",".join(f":b{i}" for i in range(len(chunk)))
        sql = f"SELECT COUNT(*) FROM {table} WHERE {column} IN ({placeholders})"  # noqa: S608 - table/column are hardcoded literals below, never user input
        binds = {f"b{i}": v for i, v in enumerate(chunk)}
        cursor.execute(sql, binds)
        total += cursor.fetchone()[0]
    return total


def _count_in_any(cursor, table, columns, values) -> int:
    """SELECT COUNT(*) FROM <table> WHERE col1 IN (...) OR col2 IN (...), chunked."""
    values = list(values)
    if not values:
        return 0
    total_ids: set[object] = set()
    # caller passes a distinguishing id column last for dedup via a
    # second pass; see call sites
    id_column = columns[-1]
    for chunk in _chunked(values):
        placeholders = ",".join(f":b{i}" for i in range(len(chunk)))
        clauses = " OR ".join(f"{c} IN ({placeholders})" for c in columns[:-1])
        binds = {f"b{i}": v for i, v in enumerate(chunk)}
        sql = f"SELECT {id_column} FROM {table} WHERE {clauses}"  # noqa: S608
        cursor.execute(sql, binds)
        total_ids.update(row[0] for row in cursor.fetchall())
    return len(total_ids)


@dataclass
class FamilyAuditReport:
    award_number: str
    current_award_id: int | None = None
    historical_award_ids: list[int] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=dict)
    attachments_with_blob: int = 0
    attachments_total_bytes: int = 0

    def as_dict(self) -> dict:
        return {
            "award_number": self.award_number,
            "current_award_id": self.current_award_id,
            "historical_award_ids": self.historical_award_ids,
            "counts": self.counts,
            "attachments_with_blob": self.attachments_with_blob,
            "attachments_total_bytes": self.attachments_total_bytes,
        }


def investigate(award_number: str) -> FamilyAuditReport:
    report = FamilyAuditReport(award_number=award_number)
    connection = _connect_oracle()
    try:
        cursor = connection.cursor()

        # --- Award versions + current/historical AWARD_ID resolution ----
        cursor.execute(
            """
            SELECT AWARD_ID, SEQUENCE_NUMBER, AWARD_SEQUENCE_STATUS,
                   UPDATE_TIMESTAMP,
                   CASE WHEN SEQUENCE_NUMBER =
                            MAX(SEQUENCE_NUMBER) OVER (PARTITION BY AWARD_NUMBER)
                        THEN 'Y' ELSE 'N' END AS IS_CURRENT_VERSION
            FROM AWARD
            WHERE AWARD_NUMBER = :award_number
            ORDER BY SEQUENCE_NUMBER
            """,
            {"award_number": award_number},
        )
        version_rows = cursor.fetchall()
        report.counts["award_versions"] = len(version_rows)
        award_ids = [int(row[0]) for row in version_rows]
        report.historical_award_ids = award_ids
        current_rows = [row for row in version_rows if row[4] == "Y"]
        # Tie-break exactly like prepare_versions(): current, then highest
        # sequence_number, then ACTIVE status, then latest update_timestamp,
        # then highest award_id - all descending.
        if current_rows:
            def sort_key(row):
                _award_id, sequence_number, status, updated, _is_current = row
                active_rank = 1 if (status or "").strip().upper() == "ACTIVE" else 0
                return (sequence_number, active_rank, updated, _award_id)

            best = max(current_rows, key=sort_key)
            report.current_award_id = int(best[0])

        if not award_ids:
            logger.warning(
                "No AWARD rows found for award_number={}", award_number
            )
            return report

        # --- People / units / amounts -----------------------------------
        report.counts["people"] = _count_in(cursor, "AWARD_PERSONS", "AWARD_ID", award_ids)
        report.counts["units"] = _count_in(
            cursor,
            "AWARD_PERSON_UNITS apu JOIN AWARD_PERSONS ap "
            "ON apu.AWARD_PERSON_ID = ap.AWARD_PERSON_ID",
            "ap.AWARD_ID",
            award_ids,
        )
        report.counts["amount_rows"] = _count_in(
            cursor, "AWARD_AMOUNT_INFO", "AWARD_ID", award_ids
        )

        # --- Budget (join-chain through AWARD_BUDGET_EXT) ---------------
        report.counts["budget_versions"] = _count_in(
            cursor, "AWARD_BUDGET_EXT", "AWARD_ID", award_ids
        )
        report.counts["budget_periods"] = _count_in(
            cursor,
            "AWARD_BUDGET_PERIOD_EXT abpe "
            "JOIN BUDGET_PERIODS bp ON bp.BUDGET_PERIOD_NUMBER = abpe.BUDGET_PERIOD_NUMBER "
            "JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bp.BUDGET_ID",
            "abe.AWARD_ID",
            award_ids,
        )
        report.counts["budget_line_items"] = _count_in(
            cursor,
            "AWARD_BUDGET_DETAILS_EXT abde "
            "JOIN BUDGET_DETAILS bd ON bd.BUDGET_DETAILS_ID = abde.BUDGET_DETAILS_ID "
            "JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bd.BUDGET_ID",
            "abe.AWARD_ID",
            award_ids,
        )
        report.counts["budget_personnel"] = _count_in(
            cursor,
            "AWD_BUDGET_PER_DET_EXT abpde "
            "JOIN BUDGET_PERSONNEL_DETAILS bpd "
            "ON bpd.BUDGET_PERSONNEL_DETAILS_ID = abpde.BUDGET_PERSONNEL_DETAILS_ID "
            "JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bpd.BUDGET_ID",
            "abe.AWARD_ID",
            award_ids,
        )

        # --- Time & Money -------------------------------------------------
        tnm_document = _count_in(
            cursor, "TIME_AND_MONEY_DOCUMENT", "AWARD_NUMBER", [award_number]
        )
        pending_transactions = _count_in_any(
            cursor,
            "PENDING_TRANSACTIONS",
            ["SOURCE_AWARD_NUMBER", "DESTINATION_AWARD_NUMBER", "TRANSACTION_ID"],
            [award_number],
        )
        transaction_details = _count_in(
            cursor, "TRANSACTION_DETAILS", "AWARD_NUMBER", [award_number]
        )
        amount_transactions = _count_in(
            cursor, "AWARD_AMOUNT_TRANSACTION", "AWARD_NUMBER", [award_number]
        )
        report.counts["time_and_money_document"] = tnm_document
        report.counts["time_and_money_pending_transactions"] = pending_transactions
        report.counts["time_and_money_transaction_details"] = transaction_details
        report.counts["time_and_money_amount_transactions"] = amount_transactions
        report.counts["time_and_money_total"] = (
            tnm_document + pending_transactions + transaction_details + amount_transactions
        )

        # --- Funding proposal links --------------------------------------
        report.counts["funding_proposal_links"] = _count_in(
            cursor, "AWARD_FUNDING_PROPOSALS", "AWARD_ID", award_ids
        )

        # --- Negotiations (association_type='AWD', matches
        # AwardArchiveRepository.findAssociatedNegotiationRows) ---
        cursor.execute(
            """
            SELECT COUNT(*)
            FROM KCOEUS.NEGOTIATION n
            JOIN KCOEUS.NEGOTIATION_ASSOCIATION_TYPE nat
                ON nat.negotiation_assc_type_id = n.negotiation_assc_type_id
            WHERE nat.negotiation_assc_type_code = 'AWD'
                AND n.associated_document_id = :award_number
            """,
            {"award_number": award_number},
        )
        report.counts["negotiations"] = cursor.fetchone()[0]

        # --- Subawards (funding source, joined via AWARD_ID -> AWARD_NUMBER) ---
        cursor.execute(
            """
            SELECT COUNT(DISTINCT sfs.subaward_id)
            FROM KCOEUS.SUBAWARD_FUNDING_SOURCE sfs
            JOIN AWARD a ON a.award_id = sfs.award_id
            WHERE a.award_number = :award_number
            """,
            {"award_number": award_number},
        )
        report.counts["subawards"] = cursor.fetchone()[0]

        # --- Comments / notepad -------------------------------------------
        report.counts["comments"] = _count_in(
            cursor, "AWARD_COMMENT", "AWARD_ID", award_ids
        )
        report.counts["notepad"] = _count_in(
            cursor, "AWARD_NOTEPAD", "AWARD_ID", award_ids
        )

        # --- Terms / report terms -----------------------------------------
        report.counts["sponsor_terms"] = _count_in(
            cursor, "AWARD_SPONSOR_TERM", "AWARD_ID", award_ids
        )
        report.counts["report_terms"] = _count_in(
            cursor, "AWARD_REPORT_TERMS", "AWARD_ID", award_ids
        )

        # --- Attachments: metadata + BLOB retrievability -------------------
        report.counts["attachments_metadata"] = _count_in(
            cursor, "KCOEUS.AWARD_ATTACHMENT", "AWARD_ID", award_ids
        )
        cursor.execute(
            """
            SELECT
                COUNT(*),
                SUM(CASE WHEN af.FILE_DATA IS NOT NULL OR fd.DATA IS NOT NULL THEN 1 ELSE 0 END),
                SUM(
                    CASE
                        WHEN af.FILE_DATA IS NOT NULL THEN DBMS_LOB.GETLENGTH(af.FILE_DATA)
                        WHEN fd.DATA IS NOT NULL THEN DBMS_LOB.GETLENGTH(fd.DATA)
                        ELSE 0
                    END
                )
            FROM (
                SELECT DISTINCT FILE_ID
                FROM KCOEUS.AWARD_ATTACHMENT
                WHERE FILE_ID IS NOT NULL
                    AND AWARD_ID IN ({placeholders})
            ) referenced
            JOIN KCOEUS.ATTACHMENT_FILE af ON af.FILE_ID = referenced.FILE_ID
            LEFT JOIN KCOEUS.FILE_DATA fd ON fd.ID = af.FILE_DATA_ID
            """.format(
                placeholders=",".join(
                    f":b{i}"
                    for i in range(min(len(award_ids), MAX_IN_LIST_SIZE))
                )
            ),
            {f"b{i}": v for i, v in enumerate(award_ids[:MAX_IN_LIST_SIZE])},
        )
        blob_row = cursor.fetchone()
        report.counts["attachments_files_referenced"] = int(blob_row[0] or 0)
        report.attachments_with_blob = int(blob_row[1] or 0)
        report.attachments_total_bytes = int(blob_row[2] or 0)

        return report
    finally:
        connection.close()


def _print_report(report: FamilyAuditReport) -> None:
    print(f"\n=== Award family audit: {report.award_number} ===")
    print(f"Current AWARD_ID: {report.current_award_id}")
    print(
        f"Historical AWARD_IDs ({len(report.historical_award_ids)}): "
        f"{report.historical_award_ids}"
    )
    print()
    label_width = max(len(k) for k in report.counts) + 2
    for key, value in report.counts.items():
        print(f"  {key:<{label_width}} {value}")
    print()
    print(f"  attachments_with_blob: {report.attachments_with_blob}")
    print(f"  attachments_total_bytes: {report.attachments_total_bytes}")
    print()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Read-only Oracle richness audit for one Award family."
    )
    parser.add_argument("--award-number", required=True, metavar="AWARD_NUMBER")
    parser.add_argument("--ecs", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print JSON instead of a table.")
    args = parser.parse_args()

    if args.ecs:
        run_id = str(uuid.uuid4())
        configure_structured_logging(run_id)
        identity = validate_aws_identity(boto3.client("sts"))
        logger.bind(stage="startup").info(
            "AWS identity resolved via ECS task role: account={}",
            identity["account"],
        )
        configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=True)
        validate_oracle_reachable(_connect_oracle)
        logger.bind(stage="startup").info("Oracle reachable")

    logger.bind(stage="investigate_award_family", award_number=args.award_number).info(
        "Starting read-only Oracle audit"
    )

    report = investigate(args.award_number)

    if args.json:
        print(json.dumps(report.as_dict(), indent=2))
    else:
        _print_report(report)

    logger.bind(stage="investigate_award_family", award_number=args.award_number).info(
        "Audit complete"
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 - top-level CLI error boundary, mirrors load_awards_from_csv.py's own
        logger.exception("investigate_award_family failed")
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
