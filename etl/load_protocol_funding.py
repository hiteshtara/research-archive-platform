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
FUNDING_SQL = (
    PROJECT_ROOT / "oracle/protocol/export_protocol_funding.sql"
)
FUNDING_COLUMNS = [
    "protocol_funding_source_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "funding_source_type_code",
    "funding_source_number",
    "funding_source_name",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]
@dataclass(frozen=True)
class FundingResolutionReport:
    total_rows: int
    resolved_parents: int
    missing_parents: int
    ambiguous_parents: int
    source_protocol_id_mismatches: int


def prepare_funding(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "protocol_funding_source_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_funding")
    for column in FUNDING_COLUMNS:
        if column not in result.columns:
            result[column] = None

    numeric = [
        "protocol_funding_source_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_funding",
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["source_update_timestamp"],
        source_name="protocol_funding",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_funding contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicates = result.duplicated(
        subset=["protocol_funding_source_id"],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            "protocol_funding contains duplicate "
            "protocol_funding_source_id values"
        )

    for column in numeric:
        if column in required:
            result[column] = result[column].astype("int64")
        else:
            result[column] = result[column].astype("Int64")
    result["protocol_number"] = (
        result["protocol_number"].astype("string").str.strip()
    )
    return result[FUNDING_COLUMNS]


def read_source(csv_directory: Path | None) -> tuple[pd.DataFrame, str]:
    if csv_directory is None:
        dataframe = OracleDataSource(FUNDING_SQL).read()
        source_name = "oracle:protocol funding"
    else:
        dataframe = CsvDataSource(
            csv_directory / "protocol_funding.csv"
        ).read()
        source_name = f"csv:{csv_directory}/protocol_funding.csv"
    return prepare_funding(dataframe), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    funding: pd.DataFrame,
) -> FundingResolutionReport:
    parent_rows = context.connection.execute(
        text(
            """
            SELECT protocol_id, protocol_number, sequence_number
            FROM archive.protocol_version
            """
        )
    ).mappings()
    resolver = NumberSequenceParentResolver(parent_rows)
    missing = 0
    ambiguous = 0
    source_mismatches = 0
    failures: list[str] = []

    for index, row in funding.iterrows():
        try:
            resolved = resolver.resolve(
                protocol_number=str(row["protocol_number"]),
                sequence_number=int(row["sequence_number"]),
                source_protocol_id=int(row["source_protocol_id"]),
            )
        except MissingParentError as error:
            missing += 1
            if len(failures) < 10:
                failures.append(str(error))
            continue
        except AmbiguousParentError as error:
            ambiguous += 1
            if len(failures) < 10:
                failures.append(str(error))
            continue
        funding.at[index, "protocol_id"] = resolved.protocol_id
        source_mismatches += int(resolved.source_protocol_id_differs)

    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Funding parent resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(failures)
        )

    funding["protocol_id"] = funding["protocol_id"].astype("int64")
    report = FundingResolutionReport(
        total_rows=len(funding),
        resolved_parents=len(funding),
        missing_parents=0,
        ambiguous_parents=0,
        source_protocol_id_mismatches=source_mismatches,
    )
    logger.info(
        "Protocol Funding parent resolution: total={} resolved={} "
        "missing={} ambiguous={} source_id_mismatches={}",
        report.total_rows,
        report.resolved_parents,
        report.missing_parents,
        report.ambiguous_parents,
        report.source_protocol_id_mismatches,
    )
    return report


def upsert_funding(
    context: PostgreSQLLoadContext,
    funding: pd.DataFrame,
) -> int:
    rows = (
        funding.astype(object)
        .where(pd.notna(funding), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in FUNDING_COLUMNS
        if column != "protocol_funding_source_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_funding (
            {", ".join(FUNDING_COLUMNS)},
            archived_at,
            load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in FUNDING_COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (protocol_funding_source_id) DO UPDATE SET
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
                (SELECT COUNT(*)
                 FROM archive.protocol_funding) AS total_rows,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding funding
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = funding.protocol_id
                  AND protocol.protocol_number =
                      funding.protocol_number
                  AND protocol.sequence_number =
                      funding.sequence_number) AS resolved_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding funding
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number =
                           funding.protocol_number
                       AND protocol.sequence_number =
                           funding.sequence_number
                 )) AS missing_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding funding
                 WHERE 1 < (
                     SELECT COUNT(*)
                     FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number =
                           funding.protocol_number
                       AND protocol.sequence_number =
                           funding.sequence_number
                 )) AS ambiguous_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding
                 WHERE source_protocol_id IS DISTINCT FROM protocol_id)
                    AS source_protocol_id_mismatches,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding funding
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = funding.protocol_id
                 WHERE funding.protocol_number IS DISTINCT FROM
                       protocol.protocol_number
                    OR funding.sequence_number IS DISTINCT FROM
                       protocol.sequence_number)
                    AS protocol_mismatches,
                (SELECT COUNT(*)
                 FROM archive.protocol_funding
                 WHERE funding_source_number IS NULL
                   AND funding_source_name IS NULL)
                    AS funding_value_mismatches
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
        description="Load exact-version Protocol Funding."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    funding, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Funding dry run passed: rows={}",
            len(funding),
        )
        return

    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, funding)
        return upsert_funding(context, funding)

    report = loader.load(
        domain="PROTOCOL_FUNDING",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(funding),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
