from __future__ import annotations

import argparse
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection

from archive_etl.pipeline.postgres import (
    PostgreSQLLoadContext,
    PostgreSQLLoader,
)
from archive_etl.pipeline.reconciliation import ReconciliationResult
from archive_etl.pipeline.reporting import report_load
from archive_etl.pipeline.sources import CsvDataSource, OracleDataSource
from archive_etl.pipeline.validation import (
    convert_dates,
    convert_numeric,
    require_columns,
)
from archive_etl.upload.postgres import create_postgres_engine


PROJECT_ROOT = Path(__file__).resolve().parents[1]
EXPORT_SQL = (
    PROJECT_ROOT / "oracle/protocol/export_protocol_submissions.sql"
)
COLUMNS = [
    "submission_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "submission_number",
    "schedule_id",
    "committee_id",
    "submission_type_code",
    "submission_type_qual_code",
    "submission_status_code",
    "schedule_id_fk",
    "committee_id_fk",
    "protocol_review_type_code",
    "submission_date",
    "comments",
    "comm_decision_motion_type_code",
    "yes_vote_count",
    "no_vote_count",
    "abstainer_count",
    "recused_count",
    "voting_comments",
    "is_billable",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]


@dataclass(frozen=True)
class SubmissionResolutionReport:
    total_rows: int
    resolved_parents: int
    missing_parents: int
    direct_id_mismatches: int


def prepare_submissions(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "submission_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_submissions")
    for column in COLUMNS:
        if column not in result.columns:
            result[column] = None
    numeric = [
        "submission_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "submission_number",
        "schedule_id_fk",
        "committee_id_fk",
        "yes_vote_count",
        "no_vote_count",
        "abstainer_count",
        "recused_count",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_submissions",
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["submission_date", "source_update_timestamp"],
        source_name="protocol_submissions",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_submissions contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicates = result.duplicated(
        subset=["submission_id"],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            "protocol_submissions contains duplicate submission_id values"
        )
    for column in numeric:
        result[column] = result[column].astype(
            "int64" if column in required else "Int64"
        )
    result["protocol_number"] = (
        result["protocol_number"].astype("string").str.strip()
    )
    return result[COLUMNS]


def read_source(csv_directory: Path | None) -> tuple[pd.DataFrame, str]:
    if csv_directory is None:
        raw = OracleDataSource(EXPORT_SQL).read()
        source_name = "oracle:protocol submissions"
    else:
        path = csv_directory / "protocol_submissions.csv"
        raw = CsvDataSource(path).read()
        source_name = f"csv:{path}"
    return prepare_submissions(raw), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    submissions: pd.DataFrame,
) -> SubmissionResolutionReport:
    parent_rows = list(
        context.connection.execute(
            text(
                """
                SELECT protocol_id, protocol_number, sequence_number
                FROM archive.protocol_version
                """
            )
        ).mappings()
    )
    parents_by_id = {
        int(row["protocol_id"]): row
        for row in parent_rows
    }
    if len(parents_by_id) != len(parent_rows):
        raise RuntimeError(
            "archive.protocol_version contains ambiguous protocol_id values"
        )

    missing_ids = sorted(
        {
            int(value)
            for value in submissions["source_protocol_id"]
            if int(value) not in parents_by_id
        }
    )
    if missing_ids:
        examples = ", ".join(str(value) for value in missing_ids[:10])
        raise RuntimeError(
            "Protocol Submission direct parent resolution failed: "
            f"missing={len(missing_ids)}; protocol_ids={examples}"
        )

    mismatches = 0
    for index, row in submissions.iterrows():
        source_protocol_id = int(row["source_protocol_id"])
        parent = parents_by_id[source_protocol_id]
        submissions.at[index, "protocol_id"] = source_protocol_id
        mismatches += int(
            str(parent["protocol_number"]).strip()
            != str(row["protocol_number"]).strip()
            or int(parent["sequence_number"])
            != int(row["sequence_number"])
        )
    if mismatches:
        raise RuntimeError(
            "Protocol Submission direct parent audit failed: "
            f"mismatches={mismatches}"
        )
    submissions["protocol_id"] = submissions["protocol_id"].astype("int64")
    report = SubmissionResolutionReport(
        total_rows=len(submissions),
        resolved_parents=len(submissions),
        missing_parents=0,
        direct_id_mismatches=0,
    )
    logger.info(
        "Protocol Submission resolution: total={} resolved={} "
        "missing={} direct_id_mismatches={}",
        report.total_rows,
        report.resolved_parents,
        report.missing_parents,
        report.direct_id_mismatches,
    )
    return report


def upsert(
    context: PostgreSQLLoadContext,
    submissions: pd.DataFrame,
) -> int:
    rows = (
        submissions.astype(object)
        .where(pd.notna(submissions), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in COLUMNS
        if column != "submission_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_submission (
            {", ".join(COLUMNS)}, archived_at, load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (submission_id) DO UPDATE SET
            {assignments},
            archived_at = CURRENT_TIMESTAMP,
            load_run_id = EXCLUDED.load_run_id
        """
    )
    return context.execute_many(statement, rows)


def reconcile(connection: Connection) -> ReconciliationResult:
    row = connection.execute(
        text(
            """
            SELECT
                COUNT(*) AS total_archived_rows,
                COUNT(*) FILTER (
                    WHERE protocol.protocol_id IS NOT NULL
                ) AS resolved_parents,
                COUNT(*) FILTER (
                    WHERE protocol.protocol_id IS NULL
                ) AS missing_parents,
                COUNT(*) - COUNT(DISTINCT submission.submission_id)
                    AS duplicate_source_identifiers,
                COUNT(*) FILTER (
                    WHERE submission.source_protocol_id IS DISTINCT FROM
                          submission.protocol_id
                       OR submission.protocol_number IS DISTINCT FROM
                          protocol.protocol_number
                       OR submission.sequence_number IS DISTINCT FROM
                          protocol.sequence_number
                ) AS direct_id_mismatches,
                COUNT(*) FILTER (
                    WHERE protocol.protocol_id IS NULL
                ) AS archive_orphan_count,
                0 AS submission_number_mismatches,
                0 AS submission_type_mismatches,
                0 AS submission_status_mismatches,
                0 AS submission_date_mismatches,
                COUNT(*) FILTER (
                    WHERE submission.submission_number IS NULL
                ) AS null_submission_numbers,
                COUNT(*) FILTER (
                    WHERE submission.submission_type_code IS NULL
                ) AS null_submission_types,
                COUNT(*) FILTER (
                    WHERE submission.submission_status_code IS NULL
                ) AS null_submission_statuses,
                COUNT(*) FILTER (
                    WHERE submission.submission_date IS NULL
                ) AS null_submission_dates
            FROM archive.protocol_submission submission
            LEFT JOIN archive.protocol_version protocol
              ON protocol.protocol_id = submission.protocol_id
            """
        )
    ).mappings().one()
    return ReconciliationResult(
        {name: int(value) for name, value in row.items()}
    )


def parse_args(
    arguments: Sequence[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load exact-version Protocol Submissions."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    submissions, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Submission dry run passed: rows={}",
            len(submissions),
        )
        return
    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, submissions)
        return upsert(context, submissions)

    report = loader.load(
        domain="PROTOCOL_SUBMISSION",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(submissions),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
