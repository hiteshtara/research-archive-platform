from __future__ import annotations

import argparse
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection

from archive_etl.pipeline.parent_resolution import (
    AmbiguousParentError,
    MissingParentError,
    NumberSequenceParentResolver,
)
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
EXPORT_SQL = PROJECT_ROOT / "oracle/protocol/export_protocol_actions.sql"
COLUMNS = [
    "protocol_action_id",
    "action_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "submission_number",
    "submission_id_fk",
    "protocol_action_type_code",
    "comments",
    "prev_submission_status_code",
    "submission_type_code",
    "prev_protocol_status_code",
    "source_create_timestamp",
    "source_create_user",
    "source_update_timestamp",
    "source_update_user",
    "action_date",
    "actual_action_date",
    "source_version_number",
    "source_object_id",
    "followup_action_code",
]


@dataclass(frozen=True)
class ActionResolutionReport:
    total_rows: int
    resolved_tuple_parents: int
    missing_tuple_parents: int
    ambiguous_tuple_parents: int
    direct_id_differences: int


def prepare_actions(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "protocol_action_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_actions")
    for column in COLUMNS:
        if column not in result.columns:
            result[column] = None
    numeric = [
        "protocol_action_id",
        "action_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "submission_number",
        "submission_id_fk",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_actions",
        reject_invalid=True,
    )
    convert_dates(
        result,
        [
            "source_create_timestamp",
            "source_update_timestamp",
            "action_date",
            "actual_action_date",
        ],
        source_name="protocol_actions",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_actions contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicates = result.duplicated(
        subset=["protocol_action_id"],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            "protocol_actions contains duplicate protocol_action_id values"
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
        source_name = "oracle:protocol actions"
    else:
        path = csv_directory / "protocol_actions.csv"
        raw = CsvDataSource(path).read()
        source_name = f"csv:{path}"
    return prepare_actions(raw), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    actions: pd.DataFrame,
) -> ActionResolutionReport:
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
    resolver = NumberSequenceParentResolver(parent_rows)
    missing = 0
    ambiguous = 0
    direct_differences = 0
    examples: list[str] = []
    for index, row in actions.iterrows():
        try:
            parent = resolver.resolve(
                protocol_number=str(row["protocol_number"]),
                sequence_number=int(row["sequence_number"]),
                source_protocol_id=int(row["source_protocol_id"]),
            )
        except MissingParentError as error:
            missing += 1
            if len(examples) < 10:
                examples.append(str(error))
            continue
        except AmbiguousParentError as error:
            ambiguous += 1
            if len(examples) < 10:
                examples.append(str(error))
            continue
        actions.at[index, "protocol_id"] = parent.protocol_id
        direct_differences += int(parent.source_protocol_id_differs)
    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Action tuple parent resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(examples)
        )
    actions["protocol_id"] = actions["protocol_id"].astype("int64")
    report = ActionResolutionReport(
        total_rows=len(actions),
        resolved_tuple_parents=len(actions),
        missing_tuple_parents=0,
        ambiguous_tuple_parents=0,
        direct_id_differences=direct_differences,
    )
    logger.info(
        "Protocol Action resolution: total={} resolved={} missing={} "
        "ambiguous={} direct_id_differences={}",
        report.total_rows,
        report.resolved_tuple_parents,
        report.missing_tuple_parents,
        report.ambiguous_tuple_parents,
        report.direct_id_differences,
    )
    return report


def upsert(
    context: PostgreSQLLoadContext,
    actions: pd.DataFrame,
) -> int:
    rows = (
        actions.astype(object)
        .where(pd.notna(actions), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in COLUMNS
        if column != "protocol_action_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_action (
            {", ".join(COLUMNS)}, archived_at, load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (protocol_action_id) DO UPDATE SET
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
                      AND 1 = (
                        SELECT COUNT(*)
                        FROM archive.protocol_version candidate
                        WHERE candidate.protocol_number =
                              action.protocol_number
                          AND candidate.sequence_number =
                              action.sequence_number
                      )
                ) AS resolved_tuple_parents,
                COUNT(*) FILTER (
                    WHERE 0 = (
                        SELECT COUNT(*)
                        FROM archive.protocol_version candidate
                        WHERE candidate.protocol_number =
                              action.protocol_number
                          AND candidate.sequence_number =
                              action.sequence_number
                    )
                ) AS missing_tuple_parents,
                COUNT(*) FILTER (
                    WHERE 1 < (
                        SELECT COUNT(*)
                        FROM archive.protocol_version candidate
                        WHERE candidate.protocol_number =
                              action.protocol_number
                          AND candidate.sequence_number =
                              action.sequence_number
                    )
                ) AS ambiguous_tuple_parents,
                COUNT(*) FILTER (
                    WHERE action.source_protocol_id IS DISTINCT FROM
                          action.protocol_id
                ) AS direct_id_differences,
                COUNT(*) - COUNT(DISTINCT action.protocol_action_id)
                    AS duplicate_source_identifiers,
                COUNT(*) FILTER (
                    WHERE protocol.protocol_id IS NULL
                ) AS archive_orphan_count
            FROM archive.protocol_action action
            LEFT JOIN archive.protocol_version protocol
              ON protocol.protocol_id = action.protocol_id
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
        description="Load exact-version Protocol Actions."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    actions, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Action dry run passed: rows={}",
            len(actions),
        )
        return
    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, actions)
        return upsert(context, actions)

    report = loader.load(
        domain="PROTOCOL_ACTION",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(actions),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
