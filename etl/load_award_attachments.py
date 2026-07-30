"""Award attachment metadata loader (Sprint 1: foundation only).

Reads Award attachment references (KCOEUS.AWARD_ATTACHMENT) and
deduplicated physical-file metadata (KCOEUS.ATTACHMENT_FILE, with a
KCOEUS.FILE_DATA fallback) directly from Oracle - metadata only. This
sprint deliberately does not implement blob streaming, S3 upload, SHA256
hashing, multipart upload, an API, a UI, presigned URLs, or download
endpoints - those are out of scope until a later sprint. See
docs/DECISIONS.md and database/migrations/V035__create_award_attachment_archive.sql
for the schema this loader populates.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

PROJECT_ROOT = Path(__file__).resolve().parents[1]

_ORACLE_DIR = PROJECT_ROOT / "oracle" / "award"
REFERENCES_ORACLE_SQL = _ORACLE_DIR / "export_award_attachments.sql"
FILES_ORACLE_SQL = _ORACLE_DIR / "export_award_attachment_files.sql"

REFERENCE_REQUIRED_COLUMNS = {
    "award_attachment_id",
    "award_id",
    "award_number",
    "sequence_number",
}

FILE_REQUIRED_COLUMNS = {
    "file_id",
}

REFERENCE_COLUMNS = [
    "award_attachment_id",
    "award_id",
    "award_number",
    "sequence_number",
    "document_id",
    "file_id",
    "type_code",
    "description",
    "document_status_code",
    "oracle_update_timestamp",
    "oracle_update_user",
]

FILE_COLUMNS = [
    "file_id",
    "file_data_id",
    "file_name",
    "content_type",
    "blob_source",
    "file_size_bytes",
    "sha256",
    "s3_bucket",
    "s3_key",
    "s3_etag",
    "upload_status",
    "upload_attempts",
    "last_error",
    "oracle_update_timestamp",
    "oracle_update_user",
    "uploaded_at",
]


def require_columns(
    dataframe: pd.DataFrame,
    required_columns: set[str],
    source_name: str,
) -> None:
    missing = sorted(required_columns - set(dataframe.columns))
    if missing:
        raise RuntimeError(
            f"{source_name} is missing columns: " + ", ".join(missing)
        )


def convert_numeric(dataframe: pd.DataFrame, columns: list[str]) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_numeric(
                dataframe[column], errors="coerce"
            )


def convert_dates(dataframe: pd.DataFrame, columns: list[str]) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_datetime(
                dataframe[column], errors="coerce"
            )


def require_values(
    dataframe: pd.DataFrame,
    columns: list[str],
    source_name: str,
) -> None:
    invalid = dataframe[dataframe[columns].isna().any(axis=1)]
    if not invalid.empty:
        raise RuntimeError(
            f"{source_name} contains {len(invalid)} rows missing required "
            "values"
        )


def prepare_references(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe, REFERENCE_REQUIRED_COLUMNS, "award attachment references"
    )

    convert_numeric(
        dataframe,
        ["award_attachment_id", "award_id", "sequence_number", "file_id"],
    )
    convert_dates(dataframe, ["oracle_update_timestamp"])

    require_values(
        dataframe,
        ["award_attachment_id", "award_id", "award_number", "sequence_number"],
        "award attachment references",
    )

    duplicate_ids = dataframe.duplicated(
        subset=["award_attachment_id"], keep=False
    )
    if duplicate_ids.any():
        raise RuntimeError(
            "award attachment references contains duplicate "
            f"award_attachment_id values: {int(duplicate_ids.sum())}"
        )

    return dataframe


def prepare_files(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(dataframe, FILE_REQUIRED_COLUMNS, "award attachment files")

    convert_numeric(dataframe, ["file_id", "file_data_id", "file_size_bytes"])
    convert_dates(dataframe, ["oracle_update_timestamp"])

    require_values(dataframe, ["file_id"], "award attachment files")

    duplicate_ids = dataframe.duplicated(subset=["file_id"], keep=False)
    if duplicate_ids.any():
        raise RuntimeError(
            "award attachment files contains duplicate file_id values: "
            f"{int(duplicate_ids.sum())}"
        )

    if "blob_source" not in dataframe.columns:
        dataframe["blob_source"] = None

    missing_blob = int(dataframe["blob_source"].isna().sum())
    if missing_blob:
        logger.warning(
            "{} award attachment physical files have neither an inline "
            "nor an external blob (missing entirely)",
            missing_blob,
        )

    # Sprint 1 is metadata-only: no blob is ever streamed, hashed, or
    # uploaded here. upload_status only reflects whether a *future* upload
    # sprint has anything to do for this row - a file with no blob at all
    # can never be uploaded and is SKIPPED rather than PENDING.
    dataframe["upload_status"] = dataframe["blob_source"].apply(
        lambda value: "PENDING" if pd.notna(value) else "SKIPPED"
    )
    dataframe["upload_attempts"] = 0
    dataframe["last_error"] = None
    dataframe["sha256"] = None
    dataframe["s3_bucket"] = None
    dataframe["s3_key"] = None
    dataframe["s3_etag"] = None
    dataframe["uploaded_at"] = None

    return dataframe


def build_validation_report(
    references: pd.DataFrame, files: pd.DataFrame
) -> dict[str, int]:
    return {
        "award_references_read": len(references),
        "physical_files_read": len(files),
        "inline_blob_count": int((files["blob_source"] == "INLINE").sum()),
        "external_blob_count": int((files["blob_source"] == "EXTERNAL").sum()),
        "missing_blob_count": int(files["blob_source"].isna().sum()),
    }


def create_load_run(connection: Connection, total_rows: int) -> int:
    load_id = connection.execute(
        text(
            """
            INSERT INTO archive.load_run (
                domain,
                source_system,
                source_file_name,
                rows_read,
                status
            )
            VALUES (
                'AWARD_ATTACHMENT',
                'KUALI',
                'Oracle KCOEUS export',
                :rows_read,
                'STARTED'
            )
            RETURNING load_id
            """
        ),
        {"rows_read": total_rows},
    ).scalar_one()
    return int(load_id)


def mark_load_complete(
    connection: Connection,
    load_id: int,
    rows_loaded: int,
    validation_report: dict[str, int],
) -> None:
    connection.execute(
        text(
            """
            UPDATE archive.load_run
               SET status = 'LOADED',
                   rows_staged = :rows_loaded,
                   rows_loaded = :rows_loaded,
                   rows_rejected = 0,
                   validation_report = :validation_report,
                   completed_at = CURRENT_TIMESTAMP
             WHERE load_id = :load_id
            """
        ),
        {
            "load_id": load_id,
            "rows_loaded": rows_loaded,
            "validation_report": pd.Series(validation_report).to_json(),
        },
    )


def mark_load_failed(engine: Engine, load_id: int, error_message: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                   SET status = 'FAILED',
                       completed_at = CURRENT_TIMESTAMP,
                       error_message = :error_message
                 WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "error_message": redact_error_message(error_message),
            },
        )


def clear_existing_award_attachment_data(connection: Connection) -> None:
    logger.info("Clearing existing Award attachment archive data")
    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.award_attachment,
                archive.attachment_object
            RESTART IDENTITY;
            """
        )
    )


def load_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    table_name: str,
    columns: list[str],
    load_id: int,
) -> int:
    available_columns = [c for c in columns if c in dataframe.columns]
    target = dataframe[available_columns].copy()
    target["load_id"] = load_id

    logger.info("COPY {:<30} {:,} rows", table_name, len(target))

    return bulk_copy_dataframe(
        connection=connection,
        dataframe=target,
        schema="archive",
        table=table_name,
    )


def verify_loaded_data(
    connection: Connection,
    expected_counts: dict[str, int],
) -> None:
    for table_name, expected_count in expected_counts.items():
        actual_count = int(
            connection.execute(
                text(f"SELECT COUNT(*) FROM archive.{table_name}")
            ).scalar_one()
        )
        logger.info(
            "VERIFY {:<20} expected={:,} actual={:,}",
            table_name,
            expected_count,
            actual_count,
        )
        if actual_count != expected_count:
            raise RuntimeError(
                f"archive.{table_name} row-count mismatch: expected "
                f"{expected_count}, found {actual_count}"
            )

    orphan_references = int(
        connection.execute(
            text(
                """
                SELECT COUNT(*)
                FROM archive.award_attachment child
                WHERE child.file_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM archive.attachment_object parent
                      WHERE parent.file_id = child.file_id
                  )
                """
            )
        ).scalar_one()
    )
    if orphan_references:
        raise RuntimeError(
            f"archive.award_attachment contains {orphan_references} rows "
            "referencing a file_id absent from archive.attachment_object"
        )


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Award attachment metadata (not blob content) from "
            "Oracle. Sprint 1 scope: no blob streaming, no S3 upload, no "
            "SHA256 - see this module's docstring."
        )
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help=(
            "Truncate references and files to at most this many rows "
            "after reading (independently per dataset, matching every "
            "other domain loader's --limit)."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Read and validate metadata and report reconciliation counts, "
            "but never connect to, write to, or migrate PostgreSQL."
        ),
    )
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()

    logger.info("Reading Award attachment references/files from Oracle")
    references = prepare_references(
        OracleDataSource(REFERENCES_ORACLE_SQL).read()
    )
    files = prepare_files(OracleDataSource(FILES_ORACLE_SQL).read())

    if arguments.limit is not None:
        references = references.head(arguments.limit)
        files = files.head(arguments.limit)

    validation_report = build_validation_report(references, files)

    logger.info(
        "Award attachment reconciliation: references={:,} files={:,} "
        "inline={:,} external={:,} missing={:,}",
        validation_report["award_references_read"],
        validation_report["physical_files_read"],
        validation_report["inline_blob_count"],
        validation_report["external_blob_count"],
        validation_report["missing_blob_count"],
    )

    if arguments.dry_run:
        logger.info(
            "Dry run: metadata read and validated - no PostgreSQL "
            "connection, write, or migration performed."
        )
        return

    total_rows = len(references) + len(files)

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1] / "database" / "migrations",
    )

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins - otherwise a failure would roll back the
    # STARTED row along with everything else, and mark_load_failed would
    # silently update zero rows, leaving no trace of the failure.
    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
            clear_existing_award_attachment_data(connection)

            file_rows = load_dataframe(
                connection, files, "attachment_object", FILE_COLUMNS, load_id
            )
            reference_rows = load_dataframe(
                connection,
                references,
                "award_attachment",
                REFERENCE_COLUMNS,
                load_id,
            )

            verify_loaded_data(
                connection,
                {
                    "attachment_object": len(files),
                    "award_attachment": len(references),
                },
            )

            rows_loaded = file_rows + reference_rows
            mark_load_complete(connection, load_id, rows_loaded, validation_report)

        logger.success(
            "Award attachment load completed and verified. "
            "load_id={} files={} references={} total={}",
            load_id,
            file_rows,
            reference_rows,
            rows_loaded,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Award attachment load failed")
        raise


if __name__ == "__main__":
    main()
