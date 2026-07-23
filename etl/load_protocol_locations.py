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
EXPORT_SQL = PROJECT_ROOT / "oracle/protocol/export_protocol_location.sql"
COLUMNS = [
    "protocol_location_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "parent_resolution_method",
    "protocol_org_type_code",
    "organization_id",
    "rolodex_id",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]


@dataclass(frozen=True)
class LocationResolutionReport:
    total_rows: int
    number_sequence_rows: int
    direct_id_placeholder_rows: int
    distinct_placeholder_direct_ids: int
    rejected_rows: int
    missing_parents: int
    ambiguous_parents: int
    source_protocol_id_mismatches: int


def prepare_locations(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    required = {
        "protocol_location_id",
        "source_protocol_id",
        "protocol_number",
        "sequence_number",
    }
    require_columns(result, required, "protocol_locations")
    for column in COLUMNS:
        if column not in result.columns:
            result[column] = None
    numeric = [
        "protocol_location_id",
        "protocol_id",
        "source_protocol_id",
        "sequence_number",
        "rolodex_id",
        "source_version_number",
    ]
    convert_numeric(
        result,
        numeric,
        source_name="protocol_locations",
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["source_update_timestamp"],
        source_name="protocol_locations",
        reject_invalid=True,
    )
    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            "protocol_locations contains "
            f"{int(missing.sum())} rows with missing required values"
        )
    duplicates = result.duplicated(
        subset=["protocol_location_id"],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            "protocol_locations contains duplicate "
            "protocol_location_id values"
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
        source_name = "oracle:protocol locations"
    else:
        path = csv_directory / "protocol_locations.csv"
        raw = CsvDataSource(path).read()
        source_name = f"csv:{path}"
    return prepare_locations(raw), source_name


def resolve_parents(
    context: PostgreSQLLoadContext,
    locations: pd.DataFrame,
) -> LocationResolutionReport:
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
    direct_parents: dict[int, list[int]] = {}
    for row in parent_rows:
        protocol_id = int(row["protocol_id"])
        direct_parents.setdefault(protocol_id, []).append(protocol_id)

    missing = 0
    ambiguous = 0
    mismatches = 0
    number_sequence = 0
    direct_placeholder = 0
    placeholder_direct_ids: set[int] = set()
    examples: list[str] = []
    for index, row in locations.iterrows():
        try:
            parent = resolver.resolve(
                protocol_number=str(row["protocol_number"]),
                sequence_number=int(row["sequence_number"]),
                source_protocol_id=int(row["source_protocol_id"]),
            )
        except MissingParentError as error:
            placeholder = (
                str(row["protocol_number"]).strip() == "0"
                and int(row["sequence_number"]) == 0
            )
            if not placeholder:
                missing += 1
                if len(examples) < 10:
                    examples.append(str(error))
                continue
            direct_matches = direct_parents.get(
                int(row["source_protocol_id"]),
                [],
            )
            if len(direct_matches) != 1:
                missing += int(len(direct_matches) == 0)
                ambiguous += int(len(direct_matches) > 1)
                if len(examples) < 10:
                    examples.append(
                        "placeholder direct parent resolution failed for "
                        f"protocol_location_id "
                        f"{row['protocol_location_id']}: "
                        f"matches={direct_matches}"
                    )
                continue
            locations.at[index, "protocol_id"] = direct_matches[0]
            locations.at[
                index,
                "parent_resolution_method",
            ] = "DIRECT_ID_PLACEHOLDER"
            direct_placeholder += 1
            placeholder_direct_ids.add(int(row["source_protocol_id"]))
            continue
        except AmbiguousParentError as error:
            ambiguous += 1
            if len(examples) < 10:
                examples.append(str(error))
            continue
        locations.at[index, "protocol_id"] = parent.protocol_id
        locations.at[
            index,
            "parent_resolution_method",
        ] = "NUMBER_SEQUENCE"
        number_sequence += 1
        mismatches += int(parent.source_protocol_id_differs)
    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Location parent resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(examples)
        )
    locations["protocol_id"] = locations["protocol_id"].astype("int64")
    locations["parent_resolution_method"] = (
        locations["parent_resolution_method"].astype("string")
    )
    report = LocationResolutionReport(
        total_rows=len(locations),
        number_sequence_rows=number_sequence,
        direct_id_placeholder_rows=direct_placeholder,
        distinct_placeholder_direct_ids=len(placeholder_direct_ids),
        rejected_rows=0,
        missing_parents=0,
        ambiguous_parents=0,
        source_protocol_id_mismatches=mismatches,
    )
    logger.info(
        "Protocol Location resolution: total={} number_sequence={} "
        "direct_id_placeholder={} distinct_placeholder_direct_ids={} "
        "rejected={} missing={} ambiguous={} source_id_mismatches={}",
        report.total_rows,
        report.number_sequence_rows,
        report.direct_id_placeholder_rows,
        report.distinct_placeholder_direct_ids,
        report.rejected_rows,
        report.missing_parents,
        report.ambiguous_parents,
        report.source_protocol_id_mismatches,
    )
    return report


def upsert(
    context: PostgreSQLLoadContext,
    locations: pd.DataFrame,
) -> int:
    rows = (
        locations.astype(object)
        .where(pd.notna(locations), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in COLUMNS
        if column != "protocol_location_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_location (
            {", ".join(COLUMNS)}, archived_at, load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (protocol_location_id) DO UPDATE SET
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
                (SELECT COUNT(*) FROM archive.protocol_location)
                    AS total_rows,
                (SELECT COUNT(*) FROM archive.protocol_location
                 WHERE parent_resolution_method = 'NUMBER_SEQUENCE')
                    AS number_sequence_rows,
                (SELECT COUNT(*) FROM archive.protocol_location
                 WHERE parent_resolution_method =
                       'DIRECT_ID_PLACEHOLDER')
                    AS direct_id_placeholder_rows,
                (SELECT COUNT(DISTINCT source_protocol_id)
                 FROM archive.protocol_location
                 WHERE parent_resolution_method =
                       'DIRECT_ID_PLACEHOLDER')
                    AS distinct_placeholder_direct_ids,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 WHERE location.parent_resolution_method =
                       'DIRECT_ID_PLACEHOLDER'
                   AND NOT EXISTS (
                     SELECT 1 FROM archive.protocol_version protocol
                     WHERE protocol.protocol_id =
                           location.source_protocol_id
                 )) AS missing_archive_direct_parents,
                0 AS rejected_rows,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = location.protocol_id)
                    AS resolved_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 WHERE NOT EXISTS (
                         SELECT 1 FROM archive.protocol_version protocol
                         WHERE protocol.protocol_id = location.protocol_id
                       )
                    OR (
                         parent_resolution_method = 'NUMBER_SEQUENCE'
                         AND NOT EXISTS (
                           SELECT 1 FROM archive.protocol_version protocol
                           WHERE protocol.protocol_number =
                                 location.protocol_number
                             AND protocol.sequence_number =
                                 location.sequence_number
                         )
                       )) AS missing_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 WHERE parent_resolution_method = 'NUMBER_SEQUENCE'
                   AND 1 < (
                     SELECT COUNT(*) FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number =
                           location.protocol_number
                       AND protocol.sequence_number =
                           location.sequence_number
                 )) AS ambiguous_parents,
                (SELECT COUNT(*) FROM archive.protocol_location
                 WHERE source_protocol_id IS DISTINCT FROM protocol_id)
                    AS source_protocol_id_mismatches,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = location.protocol_id
                 WHERE (
                         location.parent_resolution_method =
                         'NUMBER_SEQUENCE'
                         AND (
                             location.protocol_number IS DISTINCT FROM
                             protocol.protocol_number
                             OR location.sequence_number IS DISTINCT FROM
                             protocol.sequence_number
                         )
                       )
                    OR (
                         location.parent_resolution_method =
                         'DIRECT_ID_PLACEHOLDER'
                         AND (
                             BTRIM(location.protocol_number) != '0'
                             OR location.sequence_number != 0
                         )
                       )) AS protocol_mismatches,
                (SELECT COUNT(*) FROM archive.protocol_location
                 WHERE protocol_org_type_code IS NULL
                   AND organization_id IS NULL
                   AND rolodex_id IS NULL) AS location_value_mismatches,
                (SELECT COUNT(*) - COUNT(DISTINCT protocol_location_id)
                 FROM archive.protocol_location)
                    AS duplicate_source_identifiers,
                (SELECT COUNT(*)
                 FROM archive.protocol_location location
                 LEFT JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = location.protocol_id
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
        description="Load exact-version Protocol Locations."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    locations, source_name = read_source(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Location dry run passed: rows={}",
            len(locations),
        )
        return
    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        resolve_parents(context, locations)
        return upsert(context, locations)

    report = loader.load(
        domain="PROTOCOL_LOCATION",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(locations),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
