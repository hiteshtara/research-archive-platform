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
EXPORT_SQL = (
    PROJECT_ROOT / "oracle/protocol/export_proto_amend_renewals.sql"
)
COLUMNS = [
    "proto_amend_renewal_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "proto_amend_ren_number",
    "date_created",
    "summary",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]


@dataclass(frozen=True)
class AmendRenewalResolutionReport:
    total_rows: int
    resolved_tuple_parents: int
    missing_tuple_parents: int
    ambiguous_tuple_parents: int
    direct_id_differences: int


def prepare_amend_renewals(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "proto_amend_renewal_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_amend_renewals")
    for column in COLUMNS:
        if column not in result.columns:
            result[column] = None
    numeric = [
        "proto_amend_renewal_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_amend_renewals",
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["date_created", "source_update_timestamp"],
        source_name="protocol_amend_renewals",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_amend_renewals contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicates = result.duplicated(
        subset=["proto_amend_renewal_id"],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            "protocol_amend_renewals contains duplicate "
            "proto_amend_renewal_id values"
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
        source_name = "oracle:protocol amendment renewals"
    else:
        path = csv_directory / "protocol_amend_renewals.csv"
        raw = CsvDataSource(path).read()
        source_name = f"csv:{path}"
    return prepare_amend_renewals(raw), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    renewals: pd.DataFrame,
) -> AmendRenewalResolutionReport:
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
    for index, row in renewals.iterrows():
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
        renewals.at[index, "protocol_id"] = parent.protocol_id
        direct_differences += int(parent.source_protocol_id_differs)
    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Amendment/Renewal tuple resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(examples)
        )
    renewals["protocol_id"] = renewals["protocol_id"].astype("int64")
    report = AmendRenewalResolutionReport(
        total_rows=len(renewals),
        resolved_tuple_parents=len(renewals),
        missing_tuple_parents=0,
        ambiguous_tuple_parents=0,
        direct_id_differences=direct_differences,
    )
    logger.info(
        "Protocol Amendment/Renewal resolution: total={} resolved={} "
        "missing={} ambiguous={} direct_id_differences={}",
        report.total_rows,
        report.resolved_tuple_parents,
        report.missing_tuple_parents,
        report.ambiguous_tuple_parents,
        report.direct_id_differences,
    )
    return report


def upsert(
    context: PostgreSQLLoadContext,
    renewals: pd.DataFrame,
) -> int:
    rows = (
        renewals.astype(object)
        .where(pd.notna(renewals), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in COLUMNS
        if column != "proto_amend_renewal_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_amend_renewal (
            {", ".join(COLUMNS)}, archived_at, load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (proto_amend_renewal_id) DO UPDATE SET
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
                              renewal.protocol_number
                          AND candidate.sequence_number =
                              renewal.sequence_number
                      )
                ) AS resolved_tuple_parents,
                COUNT(*) FILTER (
                    WHERE 0 = (
                        SELECT COUNT(*)
                        FROM archive.protocol_version candidate
                        WHERE candidate.protocol_number =
                              renewal.protocol_number
                          AND candidate.sequence_number =
                              renewal.sequence_number
                    )
                ) AS missing_tuple_parents,
                COUNT(*) FILTER (
                    WHERE 1 < (
                        SELECT COUNT(*)
                        FROM archive.protocol_version candidate
                        WHERE candidate.protocol_number =
                              renewal.protocol_number
                          AND candidate.sequence_number =
                              renewal.sequence_number
                    )
                ) AS ambiguous_tuple_parents,
                COUNT(*) FILTER (
                    WHERE renewal.source_protocol_id IS DISTINCT FROM
                          renewal.protocol_id
                ) AS direct_id_differences,
                COUNT(*) - COUNT(
                    DISTINCT renewal.proto_amend_renewal_id
                ) AS duplicate_source_identifiers,
                COUNT(*) FILTER (
                    WHERE protocol.protocol_id IS NULL
                ) AS archive_orphan_count,
                0 AS business_value_mismatches
            FROM archive.protocol_amend_renewal renewal
            LEFT JOIN archive.protocol_version protocol
              ON protocol.protocol_id = renewal.protocol_id
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
        description="Load exact-version Protocol Amendments/Renewals."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    renewals, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Amendment/Renewal dry run passed: rows={}",
            len(renewals),
        )
        return
    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, renewals)
        return upsert(context, renewals)

    report = loader.load(
        domain="PROTOCOL_AMEND_RENEWAL",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(renewals),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
