"""Award attachment loader - metadata (Sprint 1), resumable S3 upload of
distinct physical files (Sprint 2), and ECS production execution (Sprint 3).

Sprint 1 reads Award attachment references (KCOEUS.AWARD_ATTACHMENT) and
deduplicated physical-file metadata (KCOEUS.ATTACHMENT_FILE, with a
KCOEUS.FILE_DATA fallback) directly from Oracle - metadata only, no blob
content. Sprint 2 (--upload) streams each distinct physical file's BLOB
content from Oracle in chunks (never fully in memory), computing SHA-256
incrementally, and uploads it to S3 - ordinary upload for small files,
manual multipart (aborted on any failure) above --multipart-threshold-bytes.
Uploads are resumable: already-UPLOADED rows with a matching bucket/key are
skipped, upload_attempts is tracked, and status only advances to UPLOADED
after S3 confirms the write.

Sprint 3 (--ecs) is a production execution mode intended for the ECS loader
task (see terraform/modules/ecs/main.tf - not modified here): it resolves
PostgreSQL/Oracle credentials without requiring local exports (Secrets
Manager or ECS environment variables - see archive_etl/config/ecs.py),
switches to CloudWatch-friendly structured JSON logging (see
archive_etl/utils/structured_logging.py), and runs read-only startup
validation (see archive_etl/config/startup_validation.py) before processing
anything. An API, a UI, presigned URLs, and download endpoints remain out
of scope. See docs/DECISIONS.md,
database/migrations/V035__create_award_attachment_archive.sql, and
database/migrations/V036__extend_award_attachment_upload_status.sql for the
schema this loader populates.
"""

from __future__ import annotations

import argparse
import hashlib
import time
import uuid
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import boto3
import oracledb
import pandas as pd
from botocore.exceptions import BotoCoreError, ClientError
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.attachments.models import sanitize_file_name
from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import (
    ConfigurationError,
    get_data_bucket_name,
    require_oracle_environment,
)
from archive_etl.config.startup_validation import run_startup_validation
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message
from archive_etl.utils.structured_logging import configure_structured_logging


def _resolve_project_root() -> Path:
    """Locate the directory containing oracle/ and database/migrations/
    relative to this file. Two layouts are supported: the local repo
    checkout (this file at <repo>/etl/load_award_attachments.py, so the
    project root is two levels up) and the ECS loader container image
    (this file copied flatly to /app/load_award_attachments.py alongside
    oracle/ and database/migrations/ copied directly under /app - see
    etl/Dockerfile.loader), where the project root is this file's own
    parent directory."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

_ORACLE_DIR = PROJECT_ROOT / "oracle" / "award"
REFERENCES_ORACLE_SQL = _ORACLE_DIR / "export_award_attachments.sql"
FILES_ORACLE_SQL = _ORACLE_DIR / "export_award_attachment_files.sql"

# Sprint 2 (upload) defaults.
DEFAULT_S3_KEY_PREFIX = "award-files/by-file-id"
DEFAULT_MULTIPART_THRESHOLD_BYTES = 16 * 1024 * 1024
DEFAULT_UPLOAD_CHUNK_SIZE = 1024 * 1024
# S3 requires every part but the last to be at least 5 MiB.
_S3_MIN_PART_SIZE = 5 * 1024 * 1024

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


class MissingSourceContentError(RuntimeError):
    """Raised when the Oracle row/BLOB backing a resolved BlobLocation
    turns out to be null at upload time, despite metadata saying
    otherwise - a genuine data-race/inconsistency between the Sprint 1
    metadata snapshot and Oracle's current state, not the ordinary
    MISSING_SOURCE_CONTENT classification (which is decided purely from
    the stored blob_source column, before ever touching Oracle for the
    blob content itself). Treated as a normal upload failure (FAILED),
    not silently downgraded to MISSING_SOURCE_CONTENT."""


@dataclass(frozen=True)
class BlobLocation:
    table: str
    id_column: str
    blob_column: str
    reference_id: int


def resolve_blob_location(
    *, file_id: int, file_data_id: Any, blob_source: str | None
) -> BlobLocation | None:
    """Decide where to stream a physical file's BLOB content from, per
    the blob selection rule: ATTACHMENT_FILE.FILE_DATA (INLINE) is
    primary; FILE_DATA.DATA (EXTERNAL), joined by
    ATTACHMENT_FILE.FILE_DATA_ID = FILE_DATA.ID, is the fallback. Returns
    None when there is nothing to upload at all (blob_source is neither -
    a MISSING_SOURCE_CONTENT row)."""
    if blob_source == "INLINE":
        return BlobLocation("ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", file_id)
    if blob_source == "EXTERNAL":
        if file_data_id is None or (
            isinstance(file_data_id, float) and pd.isna(file_data_id)
        ):
            return None
        return BlobLocation("FILE_DATA", "ID", "DATA", int(file_data_id))
    return None


def iter_blob_chunks(
    connection: Any,
    location: BlobLocation,
    chunk_size: int,
) -> Iterator[bytes]:
    """Yield raw BLOB chunks for `location` without ever holding the full
    BLOB in memory - streamed straight from Oracle into whatever the
    caller does with each chunk (e.g. an S3 upload), one chunk_size
    fetch at a time. Raises MissingSourceContentError if the row or its
    BLOB column is null at read time."""
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT source.{location.blob_column}
            FROM KCOEUS.{location.table} source
            WHERE source.{location.id_column} = :reference_id
            """,
            reference_id=location.reference_id,
        )
        row = cursor.fetchone()
        if row is None or row[0] is None:
            raise MissingSourceContentError(
                f"{location.table} row or BLOB missing for "
                f"{location.reference_id}"
            )

        blob = row[0]
        offset = 1
        while True:
            chunk = blob.read(offset, chunk_size)
            if not chunk:
                break
            yield chunk
            offset += len(chunk)


def build_s3_key(prefix: str, file_id: int, file_name: str | None) -> str:
    """Deterministic key: {prefix}/{file_id}/{sanitized_file_name} - the
    same file_id always produces the same key, which is what makes the
    UPLOADED-with-matching-bucket/key resume check meaningful."""
    sanitized = sanitize_file_name(file_name, file_id)
    return f"{prefix.strip('/')}/{file_id}/{sanitized}"


def _connect_oracle() -> oracledb.Connection:
    credentials = require_oracle_environment()
    return oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    )


def create_s3_client() -> Any:
    return boto3.client("s3")


def validate_aws_identity() -> dict[str, str]:
    """Fail closed before any upload if AWS credentials are missing or
    invalid - never attempt an upload against an unverified identity."""
    try:
        identity = boto3.client("sts").get_caller_identity()
    except (BotoCoreError, ClientError) as error:
        raise RuntimeError(
            "AWS identity validation failed - refusing to upload: "
            f"{redact_error_message(str(error))}"
        ) from error
    return {
        "account": str(identity.get("Account", "")),
        "arn": str(identity.get("Arn", "")),
    }


def validate_bucket_accessible(s3_client: Any, bucket: str) -> None:
    """Fail closed if the bucket is missing or inaccessible. Never creates
    a bucket - a missing bucket is always an error, not a fallback path."""
    try:
        s3_client.head_bucket(Bucket=bucket)
    except (BotoCoreError, ClientError) as error:
        raise RuntimeError(
            f"S3 bucket '{bucket}' is not accessible - refusing to "
            "upload (this loader never creates buckets): "
            f"{redact_error_message(str(error))}"
        ) from error


def _multipart_upload(
    chunks: Iterator[bytes],
    digest: hashlib._Hash,
    s3_client: Any,
    *,
    bucket: str,
    key: str,
    content_type: str | None,
    part_size: int,
) -> tuple[int, str]:
    effective_part_size = max(part_size, _S3_MIN_PART_SIZE)

    create_kwargs: dict[str, str] = {"Bucket": bucket, "Key": key}
    if content_type:
        create_kwargs["ContentType"] = content_type
    upload = s3_client.create_multipart_upload(**create_kwargs)
    upload_id = upload["UploadId"]

    parts: list[dict[str, Any]] = []
    total_bytes = 0
    part_number = 1
    buffer = bytearray()

    try:
        for chunk in chunks:
            digest.update(chunk)
            buffer.extend(chunk)
            total_bytes += len(chunk)
            # A single incoming chunk can itself be larger than
            # effective_part_size (e.g. a large Oracle read chunk against
            # a small configured part size) - keep slicing off full parts
            # rather than uploading one oversized part per chunk.
            while len(buffer) >= effective_part_size:
                part_payload = bytes(buffer[:effective_part_size])
                del buffer[:effective_part_size]
                part = s3_client.upload_part(
                    Bucket=bucket,
                    Key=key,
                    UploadId=upload_id,
                    PartNumber=part_number,
                    Body=part_payload,
                )
                parts.append({"ETag": part["ETag"], "PartNumber": part_number})
                part_number += 1

        if buffer or not parts:
            # The final part (or the only part, if the whole stream fit
            # in fewer bytes than part_size) - S3 allows the last part to
            # be smaller than the minimum part size.
            part = s3_client.upload_part(
                Bucket=bucket,
                Key=key,
                UploadId=upload_id,
                PartNumber=part_number,
                Body=bytes(buffer),
            )
            parts.append({"ETag": part["ETag"], "PartNumber": part_number})

        s3_client.complete_multipart_upload(
            Bucket=bucket,
            Key=key,
            UploadId=upload_id,
            MultipartUpload={"Parts": parts},
        )
    except Exception:
        s3_client.abort_multipart_upload(
            Bucket=bucket, Key=key, UploadId=upload_id
        )
        raise

    return total_bytes, digest.hexdigest()


def stream_upload(
    connection: Any,
    location: BlobLocation,
    s3_client: Any,
    *,
    bucket: str,
    key: str,
    content_type: str | None,
    file_size_bytes: int | None,
    multipart_threshold: int,
    chunk_size: int = DEFAULT_UPLOAD_CHUNK_SIZE,
) -> tuple[int, str]:
    """Stream a physical file's BLOB content from Oracle to S3 in chunks,
    computing SHA-256 incrementally, never holding the full BLOB in
    memory for anything at or above multipart_threshold. The upload
    strategy (ordinary vs. multipart) is decided from the already-known
    file_size_bytes metadata rather than by buffering to detect size at
    runtime; an unknown size defaults to multipart (the safe assumption
    when the size can't be trusted)."""
    digest = hashlib.sha256()
    chunks = iter_blob_chunks(connection, location, chunk_size)

    use_multipart = (
        file_size_bytes is None or file_size_bytes >= multipart_threshold
    )

    if not use_multipart:
        buffer = bytearray()
        for chunk in chunks:
            digest.update(chunk)
            buffer.extend(chunk)
        extra_args: dict[str, str] = {}
        if content_type:
            extra_args["ContentType"] = content_type
        s3_client.put_object(
            Bucket=bucket, Key=key, Body=bytes(buffer), **extra_args
        )
        return len(buffer), digest.hexdigest()

    return _multipart_upload(
        chunks,
        digest,
        s3_client,
        bucket=bucket,
        key=key,
        content_type=content_type,
        part_size=multipart_threshold,
    )


def read_bounded_references(source: OracleDataSource, limit: int) -> pd.DataFrame:
    """Read at most `limit` rows from the Award attachment references
    Oracle source, stopping the fetch as soon as enough rows have been
    collected instead of reading the full result set. Used only for
    --limit sampling - the full load reads every reference unconditionally
    via read()."""
    collected: list[pd.DataFrame] = []
    total = 0
    batches = source.read_batches()
    try:
        for batch in batches:
            collected.append(batch)
            total += len(batch)
            if total >= limit:
                break
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True).head(limit)


def read_files_matching_ids(
    source: OracleDataSource, target_file_ids: set[int]
) -> pd.DataFrame:
    """Scan the Award attachment physical-file Oracle source batch by
    batch, keeping only rows whose file_id is an exact match in
    target_file_ids, and stopping as soon as every target ID has been
    found - or the source is exhausted, in which case some targets remain
    unresolved (reported, not silently dropped). Used only for --limit
    sampling; the full load reads every physical file unconditionally via
    read(). No blob column is ever selected by the underlying query
    regardless - this only ever narrows *which rows* are kept client-side.
    """
    if not target_file_ids:
        return pd.DataFrame()

    remaining = set(target_file_ids)
    collected: list[pd.DataFrame] = []
    batches = source.read_batches()
    try:
        for batch in batches:
            batch_ids = pd.to_numeric(batch["file_id"], errors="coerce")
            mask = batch_ids.isin(remaining)
            if mask.any():
                collected.append(batch[mask])
                remaining -= set(batch_ids[mask].astype("int64").tolist())
            if not remaining:
                break
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True)


def build_sample_validation_report(
    references: pd.DataFrame,
    files: pd.DataFrame,
    sampled_file_ids: set[int],
) -> dict[str, int]:
    matched_physical_file_count = len(files)
    return {
        "sampled_reference_count": len(references),
        "distinct_sampled_file_id_count": len(sampled_file_ids),
        "matched_physical_file_count": matched_physical_file_count,
        "unresolved_file_id_count": (
            len(sampled_file_ids) - matched_physical_file_count
        ),
        "inline_count": int((files["blob_source"] == "INLINE").sum())
        if not files.empty
        else 0,
        "external_count": int((files["blob_source"] == "EXTERNAL").sum())
        if not files.empty
        else 0,
        "missing_count": int(files["blob_source"].isna().sum())
        if not files.empty
        else 0,
    }


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
    # can never be uploaded and is MISSING_SOURCE_CONTENT rather than
    # PENDING.
    dataframe["upload_status"] = dataframe["blob_source"].apply(
        lambda value: "PENDING" if pd.notna(value) else "MISSING_SOURCE_CONTENT"
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


# --- Sprint 2: resumable S3 upload -----------------------------------------

# PENDING/UPLOADING are always upload candidates - UPLOADING included so a
# row left mid-upload by a crashed prior run gets picked up again rather
# than looking untouched forever. FAILED is only a candidate with
# --retry-failed. MISSING_SOURCE_CONTENT is never a candidate - there is
# structurally nothing to upload for it.
_DEFAULT_CANDIDATE_STATUSES = ["PENDING", "UPLOADING"]


def select_upload_candidates(
    connection: Connection,
    *,
    limit: int | None,
    file_id: int | None,
    retry_failed: bool,
) -> pd.DataFrame:
    statuses = list(_DEFAULT_CANDIDATE_STATUSES)
    if retry_failed:
        statuses.append("FAILED")

    query = """
        SELECT
            file_id,
            file_data_id,
            file_name,
            content_type,
            blob_source,
            file_size_bytes,
            upload_status,
            s3_bucket,
            s3_key
        FROM archive.attachment_object
        WHERE upload_status = ANY(:statuses)
    """
    params: dict[str, Any] = {"statuses": statuses}
    if file_id is not None:
        query += " AND file_id = :file_id"
        params["file_id"] = file_id
    query += " ORDER BY file_id"
    if limit is not None:
        query += " LIMIT :limit"
        params["limit"] = limit

    result = connection.execute(text(query), params)
    return pd.DataFrame(result.mappings().all())


def mark_file_uploading(engine: Engine, file_id: int) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.attachment_object
                   SET upload_status = 'UPLOADING',
                       upload_attempts = upload_attempts + 1
                 WHERE file_id = :file_id
                """
            ),
            {"file_id": file_id},
        )


def mark_file_uploaded(
    engine: Engine,
    file_id: int,
    *,
    bucket: str,
    key: str,
    sha256: str,
    byte_size: int,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.attachment_object
                   SET upload_status = 'UPLOADED',
                       s3_bucket = :bucket,
                       s3_key = :key,
                       sha256 = :sha256,
                       file_size_bytes = :byte_size,
                       last_error = NULL,
                       uploaded_at = CURRENT_TIMESTAMP
                 WHERE file_id = :file_id
                """
            ),
            {
                "file_id": file_id,
                "bucket": bucket,
                "key": key,
                "sha256": sha256,
                "byte_size": byte_size,
            },
        )


def mark_file_upload_failed(
    engine: Engine, file_id: int, error_message: str
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.attachment_object
                   SET upload_status = 'FAILED',
                       last_error = :last_error
                 WHERE file_id = :file_id
                """
            ),
            {
                "file_id": file_id,
                "last_error": redact_error_message(error_message),
            },
        )


def mark_file_missing_source_content(engine: Engine, file_id: int) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.attachment_object
                   SET upload_status = 'MISSING_SOURCE_CONTENT'
                 WHERE file_id = :file_id
                """
            ),
            {"file_id": file_id},
        )


def _run_upload(
    arguments: argparse.Namespace, run_id: str | None = None
) -> dict[str, Any]:
    """Upload every selected candidate physical file's BLOB content to
    S3. Never called unless --upload was explicitly given (see main()).
    Each row's status transition (UPLOADING -> UPLOADED/FAILED) is its
    own immediately-committed transaction - deliberately NOT one big
    transaction for the whole batch, unlike the metadata load, since the
    whole point of --upload is that a crash partway through must leave
    durable, resumable progress rather than rolling everything back."""
    run_id = run_id or str(uuid.uuid4())
    run_logger = logger.bind(stage="upload", run_id=run_id)

    if not arguments.bucket:
        raise RuntimeError(
            "--bucket is required with --upload - refusing to guess an "
            "upload destination"
        )

    identity = validate_aws_identity()
    run_logger.info(
        "AWS identity validated for upload: account={}",
        identity["account"],
    )

    s3_client = create_s3_client()
    validate_bucket_accessible(s3_client, arguments.bucket)

    engine = create_postgres_engine()

    with engine.begin() as connection:
        candidates = select_upload_candidates(
            connection,
            limit=arguments.limit,
            file_id=arguments.file_id,
            retry_failed=arguments.retry_failed,
        )

    start_time = datetime.now(UTC)
    start_monotonic = time.monotonic()

    report: dict[str, Any] = {
        "run_id": run_id,
        "physical_files_selected": len(candidates),
        "uploaded": 0,
        "skipped_already_uploaded": 0,
        "failed": 0,
        "missing_source_content": 0,
        "bytes_uploaded": 0,
        "inline_source_count": 0,
        "file_data_source_count": 0,
    }

    prefix = arguments.prefix or DEFAULT_S3_KEY_PREFIX
    multipart_threshold = (
        arguments.multipart_threshold_bytes or DEFAULT_MULTIPART_THRESHOLD_BYTES
    )

    oracle_connection: oracledb.Connection | None = None
    try:
        for row in candidates.itertuples(index=False):
            file_id = int(row.file_id)
            file_start = time.monotonic()
            file_logger = run_logger.bind(file_id=file_id)
            target_key = build_s3_key(
                prefix, file_id, getattr(row, "file_name", None)
            )

            if (
                row.upload_status == "UPLOADED"
                and row.s3_bucket == arguments.bucket
                and row.s3_key == target_key
            ):
                file_logger.bind(status="skipped").info(
                    "SKIP file_id={} already uploaded to s3://{}/{}",
                    file_id,
                    arguments.bucket,
                    target_key,
                )
                report["skipped_already_uploaded"] += 1
                continue

            location = resolve_blob_location(
                file_id=file_id,
                file_data_id=getattr(row, "file_data_id", None),
                blob_source=getattr(row, "blob_source", None),
            )
            if location is None:
                mark_file_missing_source_content(engine, file_id)
                file_logger.bind(status="missing_source_content").info(
                    "file_id={} has no source content to upload", file_id
                )
                report["missing_source_content"] += 1
                continue

            if oracle_connection is None:
                oracle_connection = _connect_oracle()

            mark_file_uploading(engine, file_id)
            file_logger.bind(status="uploading").info(
                "file_id={} upload starting", file_id
            )
            try:
                byte_size, sha256 = stream_upload(
                    oracle_connection,
                    location,
                    s3_client,
                    bucket=arguments.bucket,
                    key=target_key,
                    content_type=getattr(row, "content_type", None),
                    file_size_bytes=getattr(row, "file_size_bytes", None),
                    multipart_threshold=multipart_threshold,
                )
            except Exception as error:
                elapsed_ms = round((time.monotonic() - file_start) * 1000, 2)
                file_logger.bind(
                    status="failed", elapsed_ms=elapsed_ms
                ).error("file_id={} upload failed", file_id)
                mark_file_upload_failed(engine, file_id, str(error))
                report["failed"] += 1
                continue

            mark_file_uploaded(
                engine,
                file_id,
                bucket=arguments.bucket,
                key=target_key,
                sha256=sha256,
                byte_size=byte_size,
            )
            elapsed_ms = round((time.monotonic() - file_start) * 1000, 2)
            file_logger.bind(status="uploaded", elapsed_ms=elapsed_ms).info(
                "file_id={} upload succeeded ({:,} bytes)", file_id, byte_size
            )
            report["uploaded"] += 1
            report["bytes_uploaded"] += byte_size
            if location.table == "ATTACHMENT_FILE":
                report["inline_source_count"] += 1
            else:
                report["file_data_source_count"] += 1
    finally:
        if oracle_connection is not None:
            oracle_connection.close()

    finish_time = datetime.now(UTC)
    duration_seconds = time.monotonic() - start_monotonic
    average_throughput = (
        report["bytes_uploaded"] / duration_seconds if duration_seconds > 0 else 0.0
    )

    report.update(
        {
            "start_time": start_time.isoformat(),
            "finish_time": finish_time.isoformat(),
            "duration_seconds": round(duration_seconds, 3),
            "average_throughput_bytes_per_second": round(average_throughput, 2),
        }
    )

    run_logger.bind(stage="summary").info(
        "Award attachment upload run summary: {}", report
    )
    return report


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Award attachment metadata from Oracle, and (--upload) "
            "stream each distinct physical file's BLOB content to S3. See "
            "this module's docstring."
        )
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help=(
            "Without --upload: truncate references and files to at most "
            "this many rows after reading (independently per dataset, "
            "matching every other domain loader's --limit). With "
            "--upload: cap how many physical files are selected for "
            "upload in this run."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Read and validate metadata and report reconciliation counts, "
            "but never connect to, write to, or migrate PostgreSQL. Has "
            "no effect with --upload (use a non-existent --bucket in a "
            "throwaway account, or just don't pass --upload, to avoid a "
            "real upload)."
        ),
    )
    parser.add_argument(
        "--upload",
        action="store_true",
        help=(
            "Upload physical files already loaded into "
            "archive.attachment_object to S3, streaming BLOB content "
            "from Oracle in chunks. Requires --bucket. Never runs unless "
            "this flag is explicitly given - --limit/--file-id/"
            "--retry-failed alone do nothing without it."
        ),
    )
    parser.add_argument(
        "--bucket",
        type=str,
        default=None,
        help="S3 bucket to upload to. Required with --upload.",
    )
    parser.add_argument(
        "--prefix",
        type=str,
        default=None,
        help=(
            "S3 key prefix (default: "
            f"'{DEFAULT_S3_KEY_PREFIX}'). The full key is always "
            "{prefix}/{file_id}/{sanitized_file_name}."
        ),
    )
    parser.add_argument(
        "--file-id",
        type=int,
        default=None,
        help=(
            "Without --upload: look up and report a single physical file "
            "by its exact FILE_ID (filename, content type, source "
            "location, size) - read-only, never touches PostgreSQL, "
            "never reads or logs BLOB content, and takes priority over "
            "--limit (never just samples the first reference). Fails "
            "cleanly if the FILE_ID isn't found. With --upload, instead "
            "restricts the upload to just this file_id."
        ),
    )
    parser.add_argument(
        "--retry-failed",
        action="store_true",
        help=(
            "With --upload, also include FAILED rows as upload "
            "candidates, in addition to PENDING/UPLOADING."
        ),
    )
    parser.add_argument(
        "--multipart-threshold-bytes",
        type=int,
        default=None,
        help=(
            "With --upload, files at or above this size use multipart "
            "upload; smaller files use a single put_object (default: "
            f"{DEFAULT_MULTIPART_THRESHOLD_BYTES:,} bytes = 16 MiB)."
        ),
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Production execution mode for the ECS loader task: resolve "
            "PostgreSQL/Oracle credentials via Secrets Manager or ECS "
            "environment variables (never requires a local .env export), "
            "switch to structured JSON logging for CloudWatch, apply "
            "production defaults (--bucket defaults to DATA_BUCKET_NAME "
            "if not given), and run read-only startup validation "
            "(PostgreSQL/Oracle reachable, S3 bucket exists, Award "
            "attachment tables present, upload_status schema matches "
            "V036) before processing anything - aborts immediately on "
            "any failure."
        ),
    )
    return parser.parse_args(arguments)


def _run_file_id_lookup(file_id: int) -> dict[str, Any]:
    """Look up a single physical file by its exact FILE_ID - a targeted,
    read-only diagnostic for --file-id (without --upload). Unlike --limit,
    this never samples an arbitrary reference and hopes it matches: it
    scans the physical-file source with an exact file_id filter (the same
    read_files_matching_ids() used for coherent --limit sampling, here
    with a single-element target set), and fails cleanly if nothing
    matches. Never connects to PostgreSQL, and never reads or logs BLOB
    content - the underlying query never selects a blob column value at
    all, only NULL-checks and DBMS_LOB.GETLENGTH()."""
    logger.info(
        "Looking up Award attachment physical file: requested file_id={}",
        file_id,
    )

    files_raw = read_files_matching_ids(
        OracleDataSource(FILES_ORACLE_SQL), {file_id}
    )

    if files_raw.empty:
        logger.error(
            "Requested file_id={} was not found among Award attachment "
            "physical files (KCOEUS.ATTACHMENT_FILE, scoped to files "
            "referenced by KCOEUS.AWARD_ATTACHMENT)",
            file_id,
        )
        raise RuntimeError(
            f"file_id {file_id} was not found - no matching physical "
            "file to report"
        )

    files = prepare_files(files_raw)
    row = files.iloc[0]
    matched_file_id = int(row["file_id"])

    logger.info(
        "Requested file_id={} matched file_id={}", file_id, matched_file_id
    )
    logger.info(
        "file_id={} file_name={} content_type={} source_location={} "
        "file_size_bytes={}",
        matched_file_id,
        row.get("file_name"),
        row.get("content_type"),
        row.get("blob_source"),
        row.get("file_size_bytes"),
    )

    return {
        "requested_file_id": file_id,
        "matched_file_id": matched_file_id,
        "file_name": row.get("file_name"),
        "content_type": row.get("content_type"),
        "source_location": row.get("blob_source"),
        "file_size_bytes": row.get("file_size_bytes"),
    }


def _read_coherent_sample(
    limit: int,
) -> tuple[pd.DataFrame, pd.DataFrame, dict[str, int]]:
    """Sample up to `limit` Award attachment references (stopping the
    Oracle fetch early), then scan the physical-file source only for the
    distinct file_id values that sample actually references - never an
    independent head(limit) of files, which could pull in physical files
    unrelated to the sampled references or miss ones that are related."""
    logger.info(
        "Sampling up to {} Award attachment references from Oracle "
        "(bounded read, no PostgreSQL connection will be made)",
        limit,
    )

    references = prepare_references(
        read_bounded_references(OracleDataSource(REFERENCES_ORACLE_SQL), limit)
    )

    sampled_file_ids = {
        int(value) for value in references["file_id"].dropna().unique()
    }

    files_raw = read_files_matching_ids(
        OracleDataSource(FILES_ORACLE_SQL), sampled_file_ids
    )
    files = prepare_files(files_raw) if not files_raw.empty else files_raw

    report = build_sample_validation_report(references, files, sampled_file_ids)

    logger.info(
        "Dry run (--limit {}) - bounded, coherent sample:\n"
        "  sampled references:         {}\n"
        "  distinct sampled file_ids:  {}\n"
        "  matched physical files:     {}\n"
        "  unresolved file_ids:        {}\n"
        "  inline:                     {}\n"
        "  external:                   {}\n"
        "  missing:                    {}",
        limit,
        report["sampled_reference_count"],
        report["distinct_sampled_file_id_count"],
        report["matched_physical_file_count"],
        report["unresolved_file_id_count"],
        report["inline_count"],
        report["external_count"],
        report["missing_count"],
    )

    return references, files, report


def _run_ecs_setup(arguments: argparse.Namespace, run_id: str) -> None:
    """--ecs mode setup: structured logging, credential resolution
    (never requires a local export), production defaults, and read-only
    startup validation. Aborts immediately (lets the raised exception
    propagate) if any of it fails."""
    configure_structured_logging(run_id)
    logger.bind(stage="startup").info(
        "Starting in --ecs mode: run_id={}", run_id
    )

    secrets_client = boto3.client("secretsmanager")
    configure_ecs_environment(secrets_client)

    if not arguments.bucket:
        try:
            arguments.bucket = get_data_bucket_name()
        except ConfigurationError:
            pass

    engine = create_postgres_engine()
    s3_client = create_s3_client() if arguments.bucket else None
    run_startup_validation(
        engine=engine,
        connect_oracle=_connect_oracle,
        s3_client=s3_client,
        bucket=arguments.bucket,
    )


def main() -> None:
    arguments = parse_args()
    run_id = str(uuid.uuid4())

    if arguments.ecs:
        _run_ecs_setup(arguments, run_id)

    if arguments.upload:
        _run_upload(arguments, run_id=run_id)
        return

    if arguments.file_id is not None:
        _run_file_id_lookup(arguments.file_id)
        return

    if arguments.limit is not None:
        references, files, validation_report = _read_coherent_sample(
            arguments.limit
        )
    else:
        logger.info("Reading Award attachment references/files from Oracle")
        references = prepare_references(
            OracleDataSource(REFERENCES_ORACLE_SQL).read()
        )
        files = prepare_files(OracleDataSource(FILES_ORACLE_SQL).read())

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
            "connection, write, migration, or BLOB payload read performed."
        )
        return

    total_rows = len(references) + len(files)

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        PROJECT_ROOT / "database" / "migrations",
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
