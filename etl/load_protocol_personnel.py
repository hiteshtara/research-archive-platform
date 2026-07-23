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
from archive_etl.pipeline.parent_resolution import (
    AmbiguousParentError,
    MissingParentError,
    NumberSequenceParentResolver,
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
PERSON_SQL = (
    PROJECT_ROOT / "oracle/protocol/export_protocol_persons.sql"
)
UNIT_SQL = PROJECT_ROOT / "oracle/protocol/export_protocol_units.sql"

PERSON_COLUMNS = [
    "protocol_person_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "person_id",
    "person_name",
    "protocol_person_role_id",
    "rolodex_id",
    "affiliation_type_code",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]
UNIT_COLUMNS = [
    "protocol_units_id",
    "protocol_person_id",
    "protocol_number",
    "sequence_number",
    "unit_number",
    "lead_unit_flag",
    "person_id",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]


def prepare_dataset(
    dataframe: pd.DataFrame,
    *,
    columns: list[str],
    required: set[str],
    numeric: list[str],
    source_name: str,
) -> pd.DataFrame:
    result = dataframe.copy()
    require_columns(result, required, source_name)
    for column in columns:
        if column not in result.columns:
            result[column] = None
    convert_numeric(
        result,
        numeric,
        source_name=source_name,
        reject_invalid=True,
    )
    convert_dates(
        result,
        ["source_update_timestamp"],
        source_name=source_name,
        reject_invalid=True,
    )

    missing = result[list(required)].isna().any(axis=1)
    if missing.any():
        raise RuntimeError(
            f"{source_name} contains {int(missing.sum())} rows "
            "with missing required values"
        )

    primary_key = columns[0]
    duplicates = result.duplicated(
        subset=[primary_key],
        keep=False,
    )
    if duplicates.any():
        raise RuntimeError(
            f"{source_name} contains duplicate {primary_key} values"
        )

    for column in numeric:
        if column in required:
            result[column] = result[column].astype("int64")
        else:
            result[column] = result[column].astype("Int64")
    result["protocol_number"] = (
        result["protocol_number"].astype("string").str.strip()
    )
    return result[columns]


def prepare_persons(dataframe: pd.DataFrame) -> pd.DataFrame:
    return prepare_dataset(
        dataframe,
        columns=PERSON_COLUMNS,
        required={
            "protocol_person_id",
            "source_protocol_id",
            "protocol_number",
            "sequence_number",
        },
        numeric=[
            "protocol_person_id",
            "protocol_id",
            "source_protocol_id",
            "sequence_number",
            "rolodex_id",
            "source_version_number",
        ],
        source_name="protocol_persons",
    )


def prepare_units(dataframe: pd.DataFrame) -> pd.DataFrame:
    return prepare_dataset(
        dataframe,
        columns=UNIT_COLUMNS,
        required={
            "protocol_units_id",
            "protocol_person_id",
            "protocol_number",
            "sequence_number",
        },
        numeric=[
            "protocol_units_id",
            "protocol_person_id",
            "sequence_number",
            "source_version_number",
        ],
        source_name="protocol_units",
    )


def read_sources(
    csv_directory: Path | None,
) -> tuple[pd.DataFrame, pd.DataFrame, str]:
    if csv_directory is None:
        persons = OracleDataSource(PERSON_SQL).read()
        units = OracleDataSource(UNIT_SQL).read()
        source_name = "oracle:protocol personnel"
    else:
        persons = CsvDataSource(
            csv_directory / "protocol_persons.csv"
        ).read()
        units = CsvDataSource(
            csv_directory / "protocol_units.csv"
        ).read()
        source_name = f"csv:{csv_directory}"
    return (
        prepare_persons(persons),
        prepare_units(units),
        source_name,
    )


@dataclass(frozen=True)
class PersonnelResolutionReport:
    total_persons: int
    uniquely_resolved_persons: int
    missing_number_sequence_parents: int
    ambiguous_number_sequence_parents: int
    source_protocol_id_differs_from_resolved_protocol_id: int


def validate_relationships(
    context: PostgreSQLLoadContext,
    persons: pd.DataFrame,
    units: pd.DataFrame,
) -> PersonnelResolutionReport:
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
    for index, row in persons.iterrows():
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
        persons.at[index, "protocol_id"] = resolved.protocol_id
        source_mismatches += int(resolved.source_protocol_id_differs)

    if missing or ambiguous:
        raise RuntimeError(
            "Protocol Personnel parent resolution failed: "
            f"missing={missing}, ambiguous={ambiguous}; "
            + "; ".join(failures)
        )

    persons["protocol_id"] = persons["protocol_id"].astype("int64")
    report = PersonnelResolutionReport(
        total_persons=len(persons),
        uniquely_resolved_persons=len(persons),
        missing_number_sequence_parents=0,
        ambiguous_number_sequence_parents=0,
        source_protocol_id_differs_from_resolved_protocol_id=(
            source_mismatches
        ),
    )
    logger.info(
        "Protocol Personnel parent resolution: total={} resolved={} "
        "missing={} ambiguous={} source_id_mismatches={}",
        report.total_persons,
        report.uniquely_resolved_persons,
        report.missing_number_sequence_parents,
        report.ambiguous_number_sequence_parents,
        report.source_protocol_id_differs_from_resolved_protocol_id,
    )

    people = {
        int(row["protocol_person_id"]): (
            row["protocol_number"],
            int(row["sequence_number"]),
            None if pd.isna(row["person_id"]) else row["person_id"],
        )
        for row in persons.to_dict(orient="records")
    }
    for row in units.to_dict(orient="records"):
        parent = people.get(int(row["protocol_person_id"]))
        if parent is None:
            raise RuntimeError(
                "protocol_units contains orphan protocol_person_id "
                f"{row['protocol_person_id']}"
            )
        actual = (
            row["protocol_number"],
            int(row["sequence_number"]),
        )
        if parent[:2] != actual:
            raise RuntimeError(
                "protocol_units version mismatch for "
                f"protocol_units_id {row['protocol_units_id']}"
            )
        unit_person_id = (
            None if pd.isna(row["person_id"]) else row["person_id"]
        )
        if (
            parent[2] is not None
            and unit_person_id is not None
            and parent[2] != unit_person_id
        ):
            raise RuntimeError(
                "protocol_units person mismatch for "
                f"protocol_units_id {row['protocol_units_id']}"
            )
    return report


def upsert_dataframe(
    context: PostgreSQLLoadContext,
    dataframe: pd.DataFrame,
    *,
    table: str,
    primary_key: str,
) -> int:
    rows = (
        dataframe.astype(object)
        .where(pd.notna(dataframe), None)
        .to_dict(orient="records")
    )
    for row in rows:
        row["load_run_id"] = context.load_id

    columns = list(dataframe.columns)
    assignments = ", ".join(
        f"{column} = EXCLUDED.{column}"
        for column in columns
        if column != primary_key
    )
    statement = text(
        f"""
        INSERT INTO archive.{table} (
            {", ".join(columns)},
            archived_at,
            load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in columns)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT ({primary_key}) DO UPDATE SET
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
                (SELECT COUNT(*) FROM archive.protocol_person)
                    AS total_persons,
                (SELECT COUNT(*)
                 FROM archive.protocol_person person
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = person.protocol_id
                  AND protocol.protocol_number =
                      person.protocol_number
                  AND protocol.sequence_number =
                      person.sequence_number)
                    AS uniquely_resolved_persons,
                (SELECT COUNT(*)
                 FROM archive.protocol_person person
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number =
                           person.protocol_number
                       AND protocol.sequence_number =
                           person.sequence_number
                 )) AS missing_number_sequence_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_person person
                 WHERE 1 < (
                     SELECT COUNT(*)
                     FROM archive.protocol_version protocol
                     WHERE protocol.protocol_number =
                           person.protocol_number
                       AND protocol.sequence_number =
                           person.sequence_number
                 )) AS ambiguous_number_sequence_parents,
                (SELECT COUNT(*)
                 FROM archive.protocol_person
                 WHERE source_protocol_id IS DISTINCT FROM protocol_id)
                    AS source_protocol_id_differs_from_resolved_protocol_id,
                (SELECT COUNT(*)
                 FROM archive.protocol_person person
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = person.protocol_id
                 WHERE person.protocol_number IS DISTINCT FROM
                       protocol.protocol_number)
                    AS protocol_number_mismatch,
                (SELECT COUNT(*)
                 FROM archive.protocol_person person
                 JOIN archive.protocol_version protocol
                   ON protocol.protocol_id = person.protocol_id
                 WHERE person.sequence_number IS DISTINCT FROM
                       protocol.sequence_number)
                    AS sequence_mismatch,
                (SELECT COUNT(*)
                 FROM archive.protocol_unit unit_row
                 LEFT JOIN archive.protocol_person person
                   ON person.protocol_person_id =
                      unit_row.protocol_person_id
                 WHERE person.protocol_person_id IS NULL)
                    AS unit_orphan_count,
                (SELECT COUNT(*)
                 FROM archive.protocol_unit unit_row
                 JOIN archive.protocol_person person
                   ON person.protocol_person_id =
                      unit_row.protocol_person_id
                 WHERE unit_row.protocol_number IS DISTINCT FROM
                           person.protocol_number
                    OR unit_row.sequence_number IS DISTINCT FROM
                           person.sequence_number)
                    AS unit_person_consistency_mismatch
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
        description="Load exact-version Protocol Personnel."
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--csv-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()
    persons, units, source_name = read_sources(arguments.csv_dir)
    if arguments.dry_run:
        logger.success(
            "Protocol Personnel dry run passed: persons={} units={}",
            len(persons),
            len(units),
        )
        return

    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database/migrations",
    )
    loader.apply_migrations()

    def load(context: PostgreSQLLoadContext) -> int:
        validate_relationships(context, persons, units)
        person_count = upsert_dataframe(
            context,
            persons,
            table="protocol_person",
            primary_key="protocol_person_id",
        )
        unit_count = upsert_dataframe(
            context,
            units,
            table="protocol_unit",
            primary_key="protocol_units_id",
        )
        return person_count + unit_count

    report = loader.load(
        domain="PROTOCOL_PERSONNEL",
        source_system="KUALI",
        source_name=source_name,
        rows_read=len(persons) + len(units),
        operation=load,
        reconciler=reconcile,
    )
    report_load(report)


if __name__ == "__main__":
    main()
