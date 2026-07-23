from __future__ import annotations

import argparse
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any, Protocol as TypingProtocol

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
from archive_etl.pipeline.sources import (
    CsvDataSource,
    OracleDataSource,
)
from archive_etl.pipeline.validation import (
    convert_dates,
    convert_numeric,
    normalize_columns,
    require_columns,
)
from archive_etl.upload.postgres import create_postgres_engine


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORACLE_SQL = (
    PROJECT_ROOT
    / "oracle"
    / "protocol"
    / "export_protocol_versions.sql"
)
RECONCILIATION_SQL = (
    PROJECT_ROOT
    / "sql"
    / "verify"
    / "protocol_core_reconciliation.sql"
)
FETCH_SIZE = 5000

REQUIRED_COLUMNS = {
    "protocol_id",
    "protocol_number",
    "sequence_number",
}

TARGET_COLUMNS = [
    "protocol_id",
    "protocol_number",
    "sequence_number",
    "document_number",
    "active",
    "protocol_type_code",
    "protocol_type_description",
    "protocol_status_code",
    "protocol_status_description",
    "title",
    "description",
    "initial_submission_date",
    "approval_date",
    "expiration_date",
    "last_approval_date",
    "fda_application_number",
    "reference_number_1",
    "reference_number_2",
    "protocol_workflow_type",
    "rerouted_flag",
    "source_create_timestamp",
    "source_create_user",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]

DATE_COLUMNS = [
    "initial_submission_date",
    "approval_date",
    "expiration_date",
    "last_approval_date",
]

TIMESTAMP_COLUMNS = [
    "source_create_timestamp",
    "source_update_timestamp",
]


@dataclass(frozen=True)
class Protocol:
    protocol_id: int
    protocol_number: str
    sequence_number: int
    document_number: str | None
    active: str | None
    protocol_type_code: str | None
    protocol_type_description: str | None
    protocol_status_code: str | None
    protocol_status_description: str | None
    title: str | None
    description: str | None
    initial_submission_date: date | None
    approval_date: date | None
    expiration_date: date | None
    last_approval_date: date | None
    fda_application_number: str | None
    reference_number_1: str | None
    reference_number_2: str | None
    protocol_workflow_type: str | None
    rerouted_flag: str | None
    source_create_timestamp: datetime | None
    source_create_user: str | None
    source_update_timestamp: datetime | None
    source_update_user: str | None
    source_version_number: int | None
    source_object_id: str | None


class ProtocolReader(TypingProtocol):
    source_name: str

    def read(self) -> list[Protocol]:
        ...


def prepare_protocols(
    dataframe: pd.DataFrame,
    source_name: str = "Protocol source",
) -> pd.DataFrame:
    result = dataframe.copy()
    normalize_columns(result)
    require_columns(result, REQUIRED_COLUMNS, source_name)

    for column in TARGET_COLUMNS:
        if column not in result.columns:
            result[column] = None

    convert_numeric(
        result,
        [
            "protocol_id",
            "sequence_number",
            "source_version_number",
        ],
        source_name=source_name,
        reject_invalid=True,
    )
    convert_dates(
        result,
        DATE_COLUMNS,
        source_name=source_name,
        reject_invalid=True,
        date_only=True,
    )
    convert_dates(
        result,
        TIMESTAMP_COLUMNS,
        source_name=source_name,
        reject_invalid=True,
    )

    result["protocol_number"] = (
        result["protocol_number"]
        .astype("string")
        .str.strip()
    )

    missing_required = result[
        result["protocol_id"].isna()
        | result["protocol_number"].isna()
        | result["protocol_number"].eq("")
        | result["sequence_number"].isna()
    ]
    if not missing_required.empty:
        raise RuntimeError(
            f"{source_name} contains "
            f"{len(missing_required)} rows with missing required values"
        )

    duplicate_ids = result[
        result.duplicated(
            subset=["protocol_id"],
            keep=False,
        )
    ]
    if not duplicate_ids.empty:
        values = sorted(
            duplicate_ids["protocol_id"]
            .astype("int64")
            .unique()
            .tolist()
        )
        raise RuntimeError(
            f"{source_name} contains duplicate protocol_id "
            f"values: {values[:20]}"
        )

    repeated_sequences = (
        result.groupby(
            ["protocol_number", "sequence_number"],
            dropna=False,
        )
        .size()
        .loc[lambda counts: counts > 1]
    )
    logger.info(
        "{:,} Protocol number + sequence pairs contain multiple rows",
        len(repeated_sequences),
    )

    result["protocol_id"] = result["protocol_id"].astype("int64")
    result["sequence_number"] = (
        result["sequence_number"].astype("int64")
    )
    result["source_version_number"] = (
        result["source_version_number"].astype("Int64")
    )

    return result[TARGET_COLUMNS]


def _none_if_missing(value: Any) -> Any:
    return None if pd.isna(value) else value


def map_protocols(
    dataframe: pd.DataFrame,
    source_name: str,
) -> list[Protocol]:
    prepared = prepare_protocols(dataframe, source_name)
    mapped: list[Protocol] = []

    for row in prepared.to_dict(orient="records"):
        values = {
            column: _none_if_missing(value)
            for column, value in row.items()
        }
        mapped.append(Protocol(**values))

    return mapped


class CsvReader:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.source_name = path.name

    def read(self) -> list[Protocol]:
        dataframe = CsvDataSource(self.path).read()
        protocols = map_protocols(dataframe, self.source_name)
        return protocols


class OracleReader:
    def __init__(
        self,
        sql_path: Path = DEFAULT_ORACLE_SQL,
        *,
        connect: Any = None,
        environ: Any = None,
        fetch_size: int = FETCH_SIZE,
    ) -> None:
        self.sql_path = sql_path
        self.source_name = f"oracle:{sql_path.name}"
        options: dict[str, Any] = {"fetch_size": fetch_size}
        if connect is not None:
            options["connect"] = connect
        if environ is not None:
            options["environ"] = environ
        self.data_source = OracleDataSource(
            sql_path,
            **options,
        )

    def read(self) -> list[Protocol]:
        return map_protocols(
            self.data_source.read(),
            self.source_name,
        )


def records(
    protocols: Sequence[Protocol],
    load_run_id: int,
) -> list[dict[str, Any]]:
    result = [asdict(protocol) for protocol in protocols]
    for row in result:
        row["load_run_id"] = load_run_id
    return result


def upsert_protocols(
    connection: Connection,
    protocols: Sequence[Protocol] | pd.DataFrame,
    load_run_id: int,
) -> int:
    if isinstance(protocols, pd.DataFrame):
        protocols = map_protocols(
            protocols,
            "Protocol source",
        )

    assignments = ",\n                    ".join(
        f"{column} = EXCLUDED.{column}"
        for column in TARGET_COLUMNS
        if column != "protocol_id"
    )
    statement = text(
        f"""
        INSERT INTO archive.protocol_version (
            {", ".join(TARGET_COLUMNS)},
            archived_at,
            load_run_id
        )
        VALUES (
            {", ".join(":" + column for column in TARGET_COLUMNS)},
            CURRENT_TIMESTAMP,
            :load_run_id
        )
        ON CONFLICT (protocol_id) DO UPDATE SET
            {assignments},
            archived_at = CURRENT_TIMESTAMP,
            load_run_id = EXCLUDED.load_run_id
        """
    )

    rows = records(protocols, load_run_id)
    for offset in range(0, len(rows), 1000):
        connection.execute(
            statement,
            rows[offset:offset + 1000],
        )
    return len(rows)


def verify_load(
    connection: Connection,
    load_run_id: int,
    expected_rows: int,
) -> None:
    loaded_rows = int(
        connection.execute(
            text(
                """
                SELECT COUNT(*)
                FROM archive.protocol_version
                WHERE load_run_id = :load_run_id
                """
            ),
            {"load_run_id": load_run_id},
        ).scalar_one()
    )
    if loaded_rows != expected_rows:
        raise RuntimeError(
            "Protocol load row-count mismatch: "
            f"expected {expected_rows}, found {loaded_rows}"
        )


def reconcile_protocols(
    connection: Connection,
) -> ReconciliationResult:
    row = connection.execute(
        text(
            """
            WITH comparison AS (
                SELECT
                    legacy.protocol_id AS legacy_protocol_id,
                    canonical.protocol_id AS canonical_protocol_id,
                    legacy.protocol_number AS legacy_protocol_number,
                    canonical.protocol_number AS canonical_protocol_number,
                    legacy.sequence_number AS legacy_sequence_number,
                    canonical.sequence_number AS canonical_sequence_number,
                    legacy.protocol_status_code AS legacy_status_code,
                    canonical.protocol_status_code AS canonical_status_code,
                    legacy.protocol_status AS legacy_status,
                    canonical.protocol_status_description
                        AS canonical_status,
                    legacy.title AS legacy_title,
                    canonical.title AS canonical_title,
                    legacy.approval_date AS legacy_approval_date,
                    canonical.approval_date AS canonical_approval_date,
                    legacy.expiration_date AS legacy_expiration_date,
                    canonical.expiration_date
                        AS canonical_expiration_date
                FROM archive.irb_protocol_version legacy
                FULL OUTER JOIN archive.protocol_version canonical
                    ON canonical.protocol_id = legacy.protocol_id
            )
            SELECT
                COUNT(*) FILTER (
                    WHERE legacy_protocol_id IS NULL
                ) AS missing_from_legacy_count,
                COUNT(*) FILTER (
                    WHERE canonical_protocol_id IS NULL
                ) AS missing_from_canonical_count,
                COUNT(*) FILTER (
                    WHERE legacy_protocol_id IS NOT NULL
                      AND canonical_protocol_id IS NOT NULL
                      AND (
                          legacy_protocol_number IS DISTINCT FROM
                              canonical_protocol_number
                          OR legacy_sequence_number IS DISTINCT FROM
                              canonical_sequence_number
                          OR legacy_status_code IS DISTINCT FROM
                              canonical_status_code
                          OR legacy_status IS DISTINCT FROM canonical_status
                          OR legacy_title IS DISTINCT FROM canonical_title
                          OR legacy_approval_date IS DISTINCT FROM
                              canonical_approval_date
                          OR legacy_expiration_date IS DISTINCT FROM
                              canonical_expiration_date
                      )
                ) AS field_mismatch_count
            FROM comparison
            """
        )
    ).mappings().one()

    return ReconciliationResult(
        {
            "missing_from_legacy_count": int(
                row["missing_from_legacy_count"]
            ),
            "missing_from_canonical_count": int(
                row["missing_from_canonical_count"]
            ),
            "field_mismatch_count": int(
                row["field_mismatch_count"]
            ),
        }
    )


def run(
    reader: ProtocolReader,
    *,
    dry_run: bool,
) -> None:
    protocols = reader.read()
    if dry_run:
        logger.success(
            "Protocol dry run passed for {:,} rows from {}",
            len(protocols),
            reader.source_name,
        )
        return

    loader = PostgreSQLLoader(
        create_postgres_engine(),
        PROJECT_ROOT / "database" / "migrations",
    )
    loader.apply_migrations()

    def load_protocols(
        context: PostgreSQLLoadContext,
    ) -> int:
        rows_loaded = upsert_protocols(
            context.connection,
            protocols,
            context.load_id,
        )
        verify_load(
            context.connection,
            context.load_id,
            len(protocols),
        )
        return rows_loaded

    report = loader.load(
        domain="PROTOCOL_CORE",
        source_system="KUALI",
        source_name=reader.source_name,
        rows_read=len(protocols),
        operation=load_protocols,
        reconciler=reconcile_protocols,
    )
    report_load(report)
    logger.info(
        "Detailed reconciliation SQL: {}",
        RECONCILIATION_SQL,
    )


def parse_args(
    arguments: Sequence[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Protocol Core from Oracle (default) or a regression CSV."
        )
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument(
        "--oracle",
        action="store_true",
        help="Read directly from Oracle (the default).",
    )
    source.add_argument(
        "--csv",
        type=Path,
        help="Read the existing Protocol Core CSV contract.",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(arguments)


def select_reader(
    arguments: argparse.Namespace,
) -> ProtocolReader:
    if arguments.csv is not None:
        return CsvReader(arguments.csv)
    return OracleReader()


def main() -> None:
    arguments = parse_args()
    run(
        select_reader(arguments),
        dry_run=arguments.dry_run,
    )


if __name__ == "__main__":
    main()
