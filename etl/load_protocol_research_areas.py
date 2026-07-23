from __future__ import annotations

import argparse
from collections.abc import Sequence
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
    PROJECT_ROOT
    / "oracle/protocol/export_protocol_research_areas.sql"
)
COLUMNS = [
    "protocol_research_area_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "research_area_code",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]


def prepare_research_areas(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "protocol_research_area_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_research_areas")
    for column in COLUMNS:
        if column not in result.columns:
            result[column] = None
    numeric = [
        "protocol_research_area_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_research_areas",
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["source_update_timestamp"],
        source_name="protocol_research_areas",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_research_areas contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicate = result.duplicated(
        subset=["protocol_research_area_id"],
        keep=False,
    )
    if duplicate.any():
        raise RuntimeError(
            "protocol_research_areas contains duplicate "
            "protocol_research_area_id values"
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
        source_name = "oracle:protocol research areas"
    else:
        path = csv_directory / "protocol_research_areas.csv"
        raw = CsvDataSource(path).read()
        source_name = f"csv:{path}"
    return prepare_research_areas(raw), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    areas: pd.DataFrame,
) -> int:
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
    examples: list[str] = []
    for index, row in areas.iterrows():
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
        areas.at[index, "protocol_id"] = parent.protocol_id
        source_mismatches += int(parent.source_protocol_id_differs)
    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Research Area parent resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(examples)
        )
    areas["protocol_id"] = areas["protocol_id"].astype("int64")
    logger.info(
        "Protocol Research Area resolution: total={} resolved={} "
        "missing=0 ambiguous=0 source_id_mismatches={}",
        len(areas),
        len(areas),
        source_mismatches,
    )
    return source_mismatches


def upsert(
    context: PostgreSQLLoadContext,
    areas: pd.DataFrame,
) -> int:
    rows = (
        areas.astype(object)
        .where(pd.notna(areas), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in COLUMNS
        if column != "protocol_research_area_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_research_area (
            {", ".join(COLUMNS)}, archived_at, load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (protocol_research_area_id) DO UPDATE SET
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
                (SELECT COUNT(*) FROM archive.protocol_research_area)
                    AS total_source_rows,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area area
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = area.protocol_id
                  AND protocol.protocol_number = area.protocol_number
                  AND protocol.sequence_number = area.sequence_number)
                    AS resolved_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area area
                 WHERE NOT EXISTS (
                     SELECT 1 FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number = area.protocol_number
                       AND protocol.sequence_number = area.sequence_number
                 )) AS missing_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area area
                 WHERE 1 < (
                     SELECT COUNT(*) FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number = area.protocol_number
                       AND protocol.sequence_number = area.sequence_number
                 )) AS ambiguous_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area
                 WHERE source_protocol_id IS DISTINCT FROM protocol_id)
                    AS source_protocol_id_mismatches,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area area
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = area.protocol_id
                 WHERE area.protocol_number IS DISTINCT FROM
                       protocol.protocol_number
                    OR area.sequence_number IS DISTINCT FROM
                       protocol.sequence_number)
                    AS protocol_mismatches,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area
                 WHERE research_area_code IS NULL
                    OR BTRIM(research_area_code) = '')
                    AS research_area_value_mismatches,
                (SELECT COUNT(*) - COUNT(DISTINCT
                     protocol_research_area_id)
                 FROM archive.protocol_research_area)
                    AS duplicate_source_identifiers,
                (SELECT COUNT(*)
                 FROM archive.protocol_research_area area
                 LEFT JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = area.protocol_id
                 WHERE protocol.protocol_id IS NULL)
                    AS archive_orphan_count
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
        description="Load exact-version Protocol Research Areas."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    areas, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Research Area dry run passed: rows={}",
            len(areas),
        )
        return
    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, areas)
        return upsert(context, areas)

    report = loader.load(
        domain="PROTOCOL_RESEARCH_AREA",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(areas),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
