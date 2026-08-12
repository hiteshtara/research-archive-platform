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
import os
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
from sqlalchemy import bindparam, text
from sqlalchemy.engine import Connection, Engine

from archive_etl.attachments.models import sanitize_file_name
from archive_etl.batch import framework as batch_framework
from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.config.startup_validation import (
    validate_bucket_exists,
    validate_oracle_reachable,
    validate_postgres_reachable,
    validate_table_exists,
    validate_upload_status_schema,
)
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

# --ecs mode's production default for --bucket. Deliberately NOT
# DATA_BUCKET_NAME - that variable is wired to a different, IRB-only S3
# bucket (see terraform/modules/s3/main.tf's "DATA BUCKET" comment and
# docs/AWARD_ATTACHMENT_ECS_EXECUTION.md) and using it here would silently
# upload Award attachments to the wrong destination.
AWARD_ATTACHMENT_BUCKET_NAME_VARIABLE = "AWARD_ATTACHMENT_BUCKET_NAME"

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
    # FILE_ID (ATTACHMENT_FILE, the INLINE case) is a numeric Oracle
    # surrogate key; FILE_DATA_ID (FILE_DATA, the EXTERNAL case) is a
    # UUID string (e.g. 'f6f4d6d2-9a3f-4a32-a4e4-b6ffb8647847') -
    # confirmed directly against live Oracle data, not assumed. Never
    # coerce this to int - see V072's migration for the incident this
    # fixed.
    reference_id: int | str


def resolve_blob_location(
    *, file_id: int, file_data_id: Any, blob_source: str | None
) -> BlobLocation | None:
    """Decide where to stream a physical file's BLOB content from, per
    the blob selection rule: ATTACHMENT_FILE.FILE_DATA (INLINE) is
    primary; FILE_DATA.DATA (EXTERNAL), joined by
    ATTACHMENT_FILE.FILE_DATA_ID = FILE_DATA.ID, is the fallback. Returns
    None when there is nothing to upload at all (blob_source is neither -
    a MISSING_SOURCE_CONTENT row).

    file_data_id is passed through as a plain string, never int()-
    coerced - FILE_DATA.ID is a UUID, not a numeric surrogate key (see
    the module-level incident note near BlobLocation). A prior version
    of this function called int(file_data_id), which - combined with
    prepare_files() numerically coercing the same column - meant every
    EXTERNAL-sourced Award attachment archive-wide was silently
    unloadable despite a real, retrievable Oracle blob."""
    if blob_source == "INLINE":
        return BlobLocation("ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", file_id)
    if blob_source == "EXTERNAL":
        if file_data_id is None or (
            isinstance(file_data_id, float) and pd.isna(file_data_id)
        ):
            return None
        text_value = str(file_data_id).strip()
        if not text_value:
            return None
        return BlobLocation("FILE_DATA", "ID", "DATA", text_value)
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

    sha256 = digest.hexdigest()
    # S3 multipart uploads cannot carry an object Metadata tag set at
    # create_multipart_upload time here, because the SHA-256 digest is
    # only fully known once every chunk has streamed through - and
    # complete_multipart_upload has no Metadata parameter to set it
    # afterward either. The minimal, standard fix is a same-bucket/
    # same-key server-side copy (no re-upload of bytes from this
    # process) with MetadataDirective="REPLACE" immediately after
    # completion, to attach the now-known digest. ContentType must be
    # re-specified here too - REPLACE overwrites all metadata/headers,
    # not just the ones being added.
    #
    # Size limit: a single copy_object call supports source objects up
    # to 5 GiB. Live-verified (2026-08-12) against every attachment
    # blob currently reachable in Oracle (KCOEUS.ATTACHMENT_FILE.FILE_DATA
    # and KCOEUS.FILE_DATA.DATA, via DBMS_LOB.GETLENGTH() - length only,
    # no content read): the largest is 194,350,900 bytes (~185 MiB),
    # about 27x under the 5 GiB limit. UploadPartCopy (required above
    # that limit) is not implemented here - re-verify this assumption
    # against Oracle before relying on it again if the source ever
    # changes.
    #
    # Encryption: deliberately never sets ServerSideEncryption/
    # SSEKMSKeyId here - the destination (this same bucket/key) applies
    # its own configured default encryption automatically when none is
    # requested explicitly (see terraform/modules/s3/main.tf's documents
    # bucket: SSE-S3/AES256 bucket-default). Live-verified (2026-08-12,
    # same-bucket self-copy against the real dev bucket under this
    # loader's own IAM role): ContentLength, body bytes, and
    # ServerSideEncryption were all unchanged before/after; only
    # VersionId changed, because the bucket has versioning enabled - the
    # prior version remains fully intact and recoverable, so this is a
    # new version, never an in-place overwrite/truncation. Hardcoding a
    # specific algorithm here would fight a future bucket policy change
    # instead of tracking it, so this intentionally relies on (and
    # re-verifies, above) the bucket's own default rather than asserting
    # one in code.
    copy_kwargs: dict[str, Any] = {
        "Bucket": bucket,
        "Key": key,
        "CopySource": {"Bucket": bucket, "Key": key},
        "Metadata": {"sha256": sha256},
        "MetadataDirective": "REPLACE",
    }
    if content_type:
        copy_kwargs["ContentType"] = content_type
    s3_client.copy_object(**copy_kwargs)

    return total_bytes, sha256


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
        sha256 = digest.hexdigest()
        extra_args: dict[str, Any] = {"Metadata": {"sha256": sha256}}
        if content_type:
            extra_args["ContentType"] = content_type
        s3_client.put_object(
            Bucket=bucket, Key=key, Body=bytes(buffer), **extra_args
        )
        return len(buffer), sha256

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


def read_references_matching_file_ids(
    source: OracleDataSource, target_file_ids: set[int]
) -> pd.DataFrame:
    """Scan the Award attachment reference Oracle source batch by batch,
    keeping only rows whose file_id is an exact match in target_file_ids.
    Unlike read_files_matching_ids, this can never stop early once every
    target has been "found once" - file_id is not unique on this source
    (the same physical file is legitimately referenced by many
    award_attachment rows), so an early stop after the first match per
    file_id would silently drop later reference rows for that same
    file_id. Always scans the full source."""
    if not target_file_ids:
        return pd.DataFrame()

    targets = set(target_file_ids)
    collected: list[pd.DataFrame] = []
    batches = source.read_batches()
    try:
        for batch in batches:
            batch_ids = pd.to_numeric(batch["file_id"], errors="coerce")
            mask = batch_ids.isin(targets)
            if mask.any():
                collected.append(batch[mask])
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

    # file_data_id is deliberately excluded here - it's a UUID string
    # (FILE_DATA.ID), not numeric. Coercing it with convert_numeric was
    # the root cause of every EXTERNAL-sourced Award attachment being
    # silently unloadable archive-wide - see V072's migration comment
    # and resolve_blob_location()'s docstring for the full incident.
    convert_numeric(dataframe, ["file_id", "file_size_bytes"])
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


# --- Bounded single-file metadata load (--load-file-id) --------------------
#
# Unlike the full load above (TRUNCATE + bulk COPY of everything), this is
# an idempotent UPSERT scoped to exactly one physical file_id and its
# reference rows - safe to run against a database that already has other
# rows loaded, and safe to re-run. Never reads a BLOB (neither Oracle
# query here selects a blob column) and never touches S3.

_ATTACHMENT_OBJECT_METADATA_COLUMNS = [
    "file_data_id",
    "file_name",
    "content_type",
    "blob_source",
    "file_size_bytes",
    "oracle_update_timestamp",
    "oracle_update_user",
]

_AWARD_ATTACHMENT_COLUMNS = [
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


def _sql_value(value: Any) -> Any:
    """Convert a pandas scalar into a value safe to bind as a SQL
    parameter - NaN/NaT become NULL, and a whole-number float (pandas'
    representation of a nullable integer column, e.g. 9001.0) becomes a
    real int. Mirrors archive_etl.upload.bulk_copy's _copy_value, applied
    here to parameterized UPSERTs instead of a COPY buffer."""
    if pd.isna(value):
        return None
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def upsert_attachment_object(
    connection: Connection, file_row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.attachment_object row,
    from Oracle-sourced metadata only. Deliberately never sets
    upload_status/upload_attempts/last_error/sha256/s3_bucket/s3_key/
    s3_etag/uploaded_at on an existing row - an existing row's upload
    state (whatever a prior --upload run has done) is always preserved.
    A brand-new row gets upload_status/upload_attempts/etc. from
    file_row (as prepare_files() sets them: PENDING or
    MISSING_SOURCE_CONTENT, zero attempts, no error/S3 state yet).
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params = {
        "file_id": _sql_value(file_row["file_id"]),
        "load_id": load_id,
        "upload_status": _sql_value(file_row.get("upload_status")),
        "upload_attempts": _sql_value(file_row.get("upload_attempts", 0)),
        "last_error": _sql_value(file_row.get("last_error")),
        "sha256": _sql_value(file_row.get("sha256")),
        "s3_bucket": _sql_value(file_row.get("s3_bucket")),
        "s3_key": _sql_value(file_row.get("s3_key")),
        "s3_etag": _sql_value(file_row.get("s3_etag")),
        "uploaded_at": _sql_value(file_row.get("uploaded_at")),
    }
    for column in _ATTACHMENT_OBJECT_METADATA_COLUMNS:
        params[column] = _sql_value(file_row.get(column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.attachment_object (
                file_id, file_data_id, file_name, content_type, blob_source,
                file_size_bytes, upload_status, upload_attempts, last_error,
                sha256, s3_bucket, s3_key, s3_etag, uploaded_at,
                oracle_update_timestamp, oracle_update_user, load_id
            ) VALUES (
                :file_id, :file_data_id, :file_name, :content_type,
                :blob_source, :file_size_bytes, :upload_status,
                :upload_attempts, :last_error, :sha256, :s3_bucket, :s3_key,
                :s3_etag, :uploaded_at, :oracle_update_timestamp,
                :oracle_update_user, :load_id
            )
            ON CONFLICT (file_id) DO UPDATE SET
                file_data_id = EXCLUDED.file_data_id,
                file_name = EXCLUDED.file_name,
                content_type = EXCLUDED.content_type,
                blob_source = EXCLUDED.blob_source,
                file_size_bytes = EXCLUDED.file_size_bytes,
                oracle_update_timestamp = EXCLUDED.oracle_update_timestamp,
                oracle_update_user = EXCLUDED.oracle_update_user,
                load_id = EXCLUDED.load_id
            WHERE
                archive.attachment_object.file_data_id
                    IS DISTINCT FROM EXCLUDED.file_data_id
                OR archive.attachment_object.file_name
                    IS DISTINCT FROM EXCLUDED.file_name
                OR archive.attachment_object.content_type
                    IS DISTINCT FROM EXCLUDED.content_type
                OR archive.attachment_object.blob_source
                    IS DISTINCT FROM EXCLUDED.blob_source
                OR archive.attachment_object.file_size_bytes
                    IS DISTINCT FROM EXCLUDED.file_size_bytes
                OR archive.attachment_object.oracle_update_timestamp
                    IS DISTINCT FROM EXCLUDED.oracle_update_timestamp
                OR archive.attachment_object.oracle_update_user
                    IS DISTINCT FROM EXCLUDED.oracle_update_user
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_attachment(
    connection: Connection, reference_row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_attachment row.
    Unlike attachment_object, this table carries no loader-owned mutable
    state (no upload tracking) - every column is refreshed from Oracle on
    conflict. Returns exactly one of "inserted", "updated", "unchanged"."""
    params = {
        "award_attachment_id": _sql_value(reference_row["award_attachment_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_ATTACHMENT_COLUMNS:
        params[column] = _sql_value(reference_row.get(column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_attachment (
                award_attachment_id, award_id, award_number, sequence_number,
                document_id, file_id, type_code, description,
                document_status_code, oracle_update_timestamp,
                oracle_update_user, load_id
            ) VALUES (
                :award_attachment_id, :award_id, :award_number,
                :sequence_number, :document_id, :file_id, :type_code,
                :description, :document_status_code,
                :oracle_update_timestamp, :oracle_update_user, :load_id
            )
            ON CONFLICT (award_attachment_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                document_id = EXCLUDED.document_id,
                file_id = EXCLUDED.file_id,
                type_code = EXCLUDED.type_code,
                description = EXCLUDED.description,
                document_status_code = EXCLUDED.document_status_code,
                oracle_update_timestamp = EXCLUDED.oracle_update_timestamp,
                oracle_update_user = EXCLUDED.oracle_update_user,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_attachment.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_attachment.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_attachment.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_attachment.document_id
                    IS DISTINCT FROM EXCLUDED.document_id
                OR archive.award_attachment.file_id
                    IS DISTINCT FROM EXCLUDED.file_id
                OR archive.award_attachment.type_code
                    IS DISTINCT FROM EXCLUDED.type_code
                OR archive.award_attachment.description
                    IS DISTINCT FROM EXCLUDED.description
                OR archive.award_attachment.document_status_code
                    IS DISTINCT FROM EXCLUDED.document_status_code
                OR archive.award_attachment.oracle_update_timestamp
                    IS DISTINCT FROM EXCLUDED.oracle_update_timestamp
                OR archive.award_attachment.oracle_update_user
                    IS DISTINCT FROM EXCLUDED.oracle_update_user
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def _run_load_file_id(
    engine: Engine, file_id: int, *, dry_run: bool = False, run_id: str | None = None
) -> dict[str, int]:
    """--load-file-id: bounded, idempotent metadata load for exactly one
    physical file_id (and its reference rows only) - the fix for "Oracle
    has this file, but archive.attachment_object doesn't, so --upload
    --file-id selects zero candidates." Never truncates or replaces the
    full attachment tables (unlike the full load), never reads a BLOB,
    never uploads to S3. With dry_run=True, every UPSERT still runs (so
    the reported counts are accurate) but the transaction is rolled back
    instead of committed - nothing is persisted."""
    load_logger = logger.bind(stage="load_file_id", file_id=file_id, run_id=run_id)

    files_raw = read_files_matching_ids(OracleDataSource(FILES_ORACLE_SQL), {file_id})
    if files_raw.empty:
        load_logger.info(
            "file_id={} not found in Oracle - nothing to load "
            "(inserted=0 updated=0 unchanged=0 missing=1)",
            file_id,
        )
        return {
            "file_id": file_id,
            "inserted": 0,
            "updated": 0,
            "unchanged": 0,
            "missing": 1,
        }

    files = prepare_files(files_raw)
    file_row = files.iloc[0]

    references_raw = read_references_matching_file_ids(
        OracleDataSource(REFERENCES_ORACLE_SQL), {file_id}
    )
    references = (
        prepare_references(references_raw)
        if not references_raw.empty
        else references_raw
    )

    report = {"file_id": file_id, "inserted": 0, "updated": 0, "unchanged": 0, "missing": 0}

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = create_load_run(connection, 1 + len(references))

            file_result = upsert_attachment_object(connection, file_row, load_id)
            report[file_result] += 1

            for _, reference_row in references.iterrows():
                reference_result = upsert_award_attachment(
                    connection, reference_row, load_id
                )
                report[reference_result] += 1

            mark_load_complete(
                connection,
                load_id,
                1 + len(references),
                {
                    "inserted": report["inserted"],
                    "updated": report["updated"],
                    "unchanged": report["unchanged"],
                },
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    load_logger.info(
        "Bounded metadata load for file_id={} ({} reference row(s)){}: "
        "inserted={} updated={} unchanged={} missing={}",
        file_id,
        len(references),
        " [DRY RUN - not persisted]" if dry_run else "",
        report["inserted"],
        report["updated"],
        report["unchanged"],
        report["missing"],
    )

    return report


def _run_load_file_ids(
    engine: Engine,
    target_file_ids: set[int],
    *,
    dry_run: bool = False,
    run_id: str | None = None,
) -> dict[str, Any]:
    """--load-file-ids: bounded, idempotent metadata load for exactly the
    given physical file_ids (and their reference rows only) - the
    plural generalization of --load-file-id, for backfilling a known,
    specific set of file_ids in one pass (e.g. "these are the exact 10
    file_ids a --diff-award-attachments run proved were never loaded")
    rather than one file_id, one task invocation, at a time. Same
    guarantees as --load-file-id: never truncates or replaces the full
    attachment tables, never reads a BLOB, never uploads to S3, and
    everything happens in one transaction (rolled back on any failure,
    or on dry_run=True instead of committed). A deliberately independent
    implementation, not a shared refactor of --load-file-id, so the
    single-file_id path's existing behavior and report shape (including
    its early-return-without-opening-Postgres case for a file_id not
    found in Oracle at all) are left completely unchanged."""
    load_logger = logger.bind(
        stage="load_file_ids", file_ids=sorted(target_file_ids), run_id=run_id
    )

    files_raw = read_files_matching_ids(
        OracleDataSource(FILES_ORACLE_SQL), target_file_ids
    )
    files = prepare_files(files_raw) if not files_raw.empty else files_raw
    found_file_ids = (
        set(files["file_id"].astype("int64").tolist()) if not files.empty else set()
    )
    missing_file_ids = target_file_ids - found_file_ids

    references_raw = read_references_matching_file_ids(
        OracleDataSource(REFERENCES_ORACLE_SQL), found_file_ids
    )
    references = (
        prepare_references(references_raw)
        if not references_raw.empty
        else references_raw
    )

    report: dict[str, Any] = {
        "file_ids": sorted(target_file_ids),
        "inserted": 0,
        "updated": 0,
        "unchanged": 0,
        "missing": len(missing_file_ids),
        "per_file": [],
    }

    if not target_file_ids or (files.empty and references.empty):
        for missing_file_id in sorted(missing_file_ids):
            load_logger.info(
                "file_id={} not found in Oracle - nothing to load",
                missing_file_id,
            )
        load_logger.info(
            "Bounded metadata load for {} file_id(s): nothing found in "
            "Oracle - inserted=0 updated=0 unchanged=0 missing={}",
            len(target_file_ids),
            report["missing"],
        )
        return report

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = create_load_run(connection, len(files) + len(references))

            for _, file_row in files.iterrows():
                file_result = upsert_attachment_object(connection, file_row, load_id)
                report[file_result] += 1
                report["per_file"].append(
                    {
                        "file_id": int(file_row["file_id"]),
                        "file_name": file_row.get("file_name"),
                        "result": file_result,
                    }
                )
                load_logger.info(
                    "file_id={} file_name={} -> {}",
                    file_row["file_id"],
                    file_row.get("file_name"),
                    file_result,
                )

            for missing_file_id in sorted(missing_file_ids):
                load_logger.info(
                    "file_id={} not found in Oracle - nothing to load",
                    missing_file_id,
                )

            for _, reference_row in references.iterrows():
                reference_result = upsert_award_attachment(
                    connection, reference_row, load_id
                )
                report[reference_result] += 1

            mark_load_complete(
                connection,
                load_id,
                len(files) + len(references),
                {
                    "inserted": report["inserted"],
                    "updated": report["updated"],
                    "unchanged": report["unchanged"],
                },
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    load_logger.info(
        "Bounded metadata load for {} file_id(s) ({} reference row(s)){}: "
        "inserted={} updated={} unchanged={} missing={}",
        len(target_file_ids),
        len(references),
        " [DRY RUN - not persisted]" if dry_run else "",
        report["inserted"],
        report["updated"],
        report["unchanged"],
        report["missing"],
    )

    return report


# --- Sprint 3: deterministic batch workflow --------------------------------
#
# Neither --limit (metadata load, bounded Oracle sampling) nor --limit
# (upload candidate selection, a live PostgreSQL query) is a persisted
# selection - see V037's migration header for the full explanation. A
# batch is a durable manifest (archive.etl_batch/archive.etl_batch_item,
# the generic batch framework in archive_etl.batch.framework): once
# created, membership never changes, so --load-batch and --upload
# --batch-id always operate on the exact same file_id set, and the whole
# workflow can be paused and resumed at any point.
#
# Award attachment physical files are one domain/entity_type pair on that
# shared, generic framework (domain=AWARD_ATTACHMENT_BATCH_DOMAIN,
# entity_type=AWARD_ATTACHMENT_BATCH_ENTITY_TYPE) - everything below is
# domain-specific glue: deciding which file_ids are candidates (Oracle
# scan), doing the actual metadata load/upload for a file_id, and joining
# the generic etl_batch_item table to this domain's own
# attachment_object.upload_status. See docs/architecture/ETL_BATCH_FRAMEWORK.md.

AWARD_ATTACHMENT_BATCH_DOMAIN = "AWARD_ATTACHMENT"
AWARD_ATTACHMENT_BATCH_ENTITY_TYPE = "PHYSICAL_FILE"


def _run_create_batch(
    engine: Engine,
    requested_size: int,
    *,
    include_already_uploaded: bool = False,
    run_id: str | None = None,
) -> dict[str, Any]:
    """--create-batch: select exactly `requested_size` distinct physical
    file_id values from Oracle, in stable ascending file_id order (the
    physical-file Oracle export is itself ORDER BY FILE_ID - see
    oracle/award/export_award_attachment_files.sql), and persist that
    exact membership as a new batch via the generic batch framework.
    Never reads a BLOB (the Oracle query never selects blob content) and
    never touches S3. By default, excludes every file_id that already
    has ANY archive.attachment_object row - its metadata has already
    been loaded (by this batch or an earlier one), regardless of
    upload_status, so there is nothing left for a new metadata batch to
    do for it; pass include_already_uploaded=True to select from the
    full candidate pool regardless. Raises ValueError for a non-positive
    requested_size, before touching Oracle or PostgreSQL.

    Live-verified incident (2026-08-12): this previously excluded only
    upload_status='UPLOADED' rows. Any caller that creates metadata
    batches repeatedly without an upload in between - which is exactly
    what attachment_orchestrator.run_orchestration does (metadata stage
    runs to full exhaustion before the binary stage ever starts) -
    would re-select the SAME still-PENDING file_ids into a new batch
    every single time, since PENDING was never excluded: batches 86-91
    of a real dev run all had byte-for-byte identical 2000-file
    membership, making zero forward progress and never reaching the
    binary stage at all. The CLI's own historical usage
    (--create-batch, --load-batch, --upload for one batch before ever
    creating the next) never triggered this, since --upload always
    flipped the batch's own files to UPLOADED before the next
    --create-batch call ran."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    excluded_file_ids: set[int] = set()
    if not include_already_uploaded:
        with engine.connect() as connection:
            rows = connection.execute(
                text("SELECT file_id FROM archive.attachment_object")
            ).scalars()
            excluded_file_ids = {int(value) for value in rows}

    selected_file_ids = batch_framework.select_distinct_ascending_from_oracle_batches(
        OracleDataSource(FILES_ORACLE_SQL).read_batches(),
        id_column="file_id",
        requested_size=requested_size,
        excluded=excluded_file_ids,
    )

    result = batch_framework.create_batch(
        engine,
        domain=AWARD_ATTACHMENT_BATCH_DOMAIN,
        entity_type=AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
        requested_size=requested_size,
        selection_strategy="ORACLE_SCAN_ASCENDING_FILE_ID",
        selected_keys=selected_file_ids,
        selection_parameters={
            "exclude_already_uploaded": not include_already_uploaded
        },
        run_id=run_id,
    )

    return {
        "batch_id": result["batch_id"],
        "requested_size": result["requested_size"],
        "selected_count": result["selected_count"],
        "selected_file_ids": result["selected_keys"],
    }


def _run_load_batch(
    engine: Engine, batch_id: int, *, dry_run: bool = False, run_id: str | None = None
) -> dict[str, Any]:
    """--load-batch: idempotent metadata load for exactly the file_ids
    already recorded as this batch's membership - never truncates or
    replaces the full tables, never reads a BLOB, never touches S3.
    Batch membership itself is never modified here, only
    etl_batch(_item)'s status columns and the two attachment archive
    tables. With dry_run=True, every UPSERT still runs (so the reported
    counts are accurate) but everything is rolled back instead of
    committed."""
    load_logger = logger.bind(stage="load_batch", batch_id=batch_id, run_id=run_id)

    with engine.connect() as connection:
        file_ids = batch_framework.load_batch_membership(
            connection,
            batch_id,
            domain=AWARD_ATTACHMENT_BATCH_DOMAIN,
            entity_type=AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
        )

    target_ids = set(file_ids)
    files_raw = read_files_matching_ids(OracleDataSource(FILES_ORACLE_SQL), target_ids)
    files = prepare_files(files_raw) if not files_raw.empty else files_raw
    found_file_ids = (
        set(files["file_id"].astype("int64").tolist()) if not files.empty else set()
    )
    missing_file_ids = target_ids - found_file_ids

    references_raw = read_references_matching_file_ids(
        OracleDataSource(REFERENCES_ORACLE_SQL), found_file_ids
    )
    references = (
        prepare_references(references_raw)
        if not references_raw.empty
        else references_raw
    )

    report = {
        "batch_id": batch_id,
        "physical_files_requested": len(target_ids),
        "physical_files_found": len(found_file_ids),
        "inserted": 0,
        "updated": 0,
        "unchanged": 0,
        "missing_in_oracle": len(missing_file_ids),
        "reference_rows_inserted": 0,
        "reference_rows_updated": 0,
        "reference_rows_unchanged": 0,
    }

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = create_load_run(connection, len(files) + len(references))

            for _, file_row in files.iterrows():
                file_result = upsert_attachment_object(connection, file_row, load_id)
                report[file_result] += 1
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    int(file_row["file_id"]),
                    status=batch_framework.ITEM_STATUS_COMPLETED,
                )

            for missing_file_id in missing_file_ids:
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    missing_file_id,
                    status=batch_framework.ITEM_STATUS_MISSING_SOURCE,
                )

            for _, reference_row in references.iterrows():
                reference_result = upsert_award_attachment(
                    connection, reference_row, load_id
                )
                report[f"reference_rows_{reference_result}"] += 1

            mark_load_complete(
                connection,
                load_id,
                len(files) + len(references),
                {
                    "inserted": report["inserted"],
                    "updated": report["updated"],
                    "unchanged": report["unchanged"],
                },
            )

            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    load_logger.info(
        "Batch metadata load for batch_id={}{}: requested={} found={} "
        "inserted={} updated={} unchanged={} missing_in_oracle={} "
        "reference_rows(inserted={} updated={} unchanged={})",
        batch_id,
        " [DRY RUN - not persisted]" if dry_run else "",
        report["physical_files_requested"],
        report["physical_files_found"],
        report["inserted"],
        report["updated"],
        report["unchanged"],
        report["missing_in_oracle"],
        report["reference_rows_inserted"],
        report["reference_rows_updated"],
        report["reference_rows_unchanged"],
    )

    return report


def _run_list_awards_with_attachments(
    engine: Engine, limit: int | None
) -> list[dict[str, Any]]:
    """--list-awards-with-attachments: developer-only, read-only report
    of every Award *version* (award_id) that has at least one
    archive.award_attachment row, newest-highest-count first - a quick
    way to find a real award_id to open in the UI's Attachments tab.
    Never writes anything, never touches Oracle or S3.

    LEFT JOINs archive.award_version rather than requiring a match:
    award_attachment.award_id is NOT NULL but has no enforced FK to
    award_version (see V035's migration), so an award_id present in
    award_attachment with no matching award_version row is possible.
    An INNER JOIN would silently under-count (or drop entirely) any
    award_id like that - exactly the "join behavior" failure mode this
    tool exists to rule out, not reproduce. award_number is read from
    award_attachment itself (award_attachment.award_number, always
    present) rather than the joined award_version row, so grouping
    never depends on the join succeeding; title falls back to NULL
    (rendered as "(untitled)") when there is no matching award_version
    row."""
    query = """
        SELECT
            aa.award_number,
            aa.award_id,
            av.title,
            COUNT(aa.award_attachment_id) AS attachment_count
        FROM archive.award_attachment aa
        LEFT JOIN archive.award_version av ON av.award_id = aa.award_id
        GROUP BY aa.award_number, aa.award_id, av.title
        ORDER BY attachment_count DESC, aa.award_number
    """
    if limit is not None:
        query += " LIMIT :limit"

    with engine.connect() as connection:
        rows = connection.execute(
            text(query), {"limit": limit} if limit is not None else {}
        ).all()

    results = [
        {
            "award_number": row.award_number,
            "award_id": row.award_id,
            "title": row.title,
            "attachment_count": row.attachment_count,
        }
        for row in rows
    ]

    if not results:
        logger.bind(stage="list_awards_with_attachments").info(
            "No Award attachments are loaded yet - "
            "archive.award_attachment is empty."
        )
        return results

    header = f"{'AWARD NUMBER':<20} {'AWARD ID':>10} {'ATTACHMENTS':>11}  TITLE"
    print(header)
    print("-" * len(header))
    for result in results:
        title = result["title"] or "(untitled)"
        if len(title) > 60:
            title = title[:57] + "..."
        print(
            f"{result['award_number']:<20} {result['award_id']:>10} "
            f"{result['attachment_count']:>11}  {title}"
        )

    logger.bind(
        stage="list_awards_with_attachments", award_count=len(results)
    ).info(
        "Listed {} Award version(s) with attachments", len(results)
    )
    return results


def _run_diff_award_attachments(
    engine: Engine, award_id: int
) -> dict[str, Any]:
    """--diff-award-attachments: developer-only, read-only side-by-side
    comparison of KCOEUS.AWARD_ATTACHMENT (Oracle) against
    archive.award_attachment (Postgres) for exactly one award_id -
    explains, per Oracle-side row, exactly why it either is or isn't
    archived yet. Never writes to either database.

    Reads Oracle via OracleDataSource.read_filtered (a targeted
    bind-variable WHERE AWARD_ID = :award_id push-down against
    export_award_attachments.sql - the same production extraction SQL
    the real loader uses, unmodified - not a full-table scan, and not a
    new/duplicate Oracle query). Classifies each Oracle-only row using
    only what the archive can prove about that row's file_id:
      - present in archive.attachment_object -> file metadata was
        loaded but this specific reference wasn't (a genuine gap, since
        --load-batch/--load-file-id always load both together in one
        transaction - see upsert_award_attachment/upsert_attachment_object
        call sites in _run_load_batch)
      - a member of an archive.etl_batch_item row for the
        AWARD_ATTACHMENT/PHYSICAL_FILE domain -> batch filtering: this
        file_id was selected into a batch whose processing hasn't
        completed for it yet
      - neither -> not yet loaded: this file_id has never been reached
        by the batch-based loading strategy at all (--create-batch
        selects file_ids in ascending order across every Award, not per
        Award, so a single Award's references can legitimately still be
        partially loaded)
    """
    oracle_rows = OracleDataSource(REFERENCES_ORACLE_SQL).read_filtered(
        column="AWARD_ID", values=[award_id]
    )

    if oracle_rows.empty:
        logger.bind(
            stage="diff_award_attachments", award_id=award_id
        ).info(
            "No AWARD_ATTACHMENT rows found in Oracle for award_id={}",
            award_id,
        )
        return {
            "award_id": award_id,
            "oracle_count": 0,
            "archive_count": 0,
            "missing_count": 0,
            "rows": [],
        }

    oracle_rows = prepare_references(oracle_rows)
    oracle_file_ids = [
        int(value)
        for value in oracle_rows["file_id"].dropna().astype("int64").tolist()
    ]

    with engine.connect() as connection:
        archive_rows = connection.execute(
            text(
                """
                SELECT award_attachment_id, file_id
                FROM archive.award_attachment
                WHERE award_id = :award_id
                """
            ),
            {"award_id": award_id},
        ).all()

        file_object_rows = []
        batch_rows = []
        if oracle_file_ids:
            file_object_rows = connection.execute(
                text(
                    """
                    SELECT file_id, upload_status
                    FROM archive.attachment_object
                    WHERE file_id IN :file_ids
                    """
                ).bindparams(bindparam("file_ids", expanding=True)),
                {"file_ids": oracle_file_ids},
            ).all()

            batch_rows = connection.execute(
                text(
                    """
                    SELECT
                        ebi.entity_key AS file_id,
                        ebi.status AS item_status,
                        ebi.batch_id
                    FROM archive.etl_batch_item ebi
                    JOIN archive.etl_batch eb
                        ON eb.batch_id = ebi.batch_id
                    WHERE eb.domain = :domain
                      AND eb.entity_type = :entity_type
                      AND ebi.entity_key IN :file_ids
                    """
                ).bindparams(bindparam("file_ids", expanding=True)),
                {
                    "domain": AWARD_ATTACHMENT_BATCH_DOMAIN,
                    "entity_type": AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
                    "file_ids": oracle_file_ids,
                },
            ).all()

    archive_attachment_ids = {row.award_attachment_id for row in archive_rows}
    file_ids_with_object = {row.file_id for row in file_object_rows}
    batch_status_by_file_id = {
        row.file_id: (row.batch_id, row.item_status) for row in batch_rows
    }

    report_rows = []
    for _, oracle_row in oracle_rows.iterrows():
        attachment_id = int(oracle_row["award_attachment_id"])
        file_id = (
            int(oracle_row["file_id"])
            if pd.notna(oracle_row["file_id"])
            else None
        )
        archive_present = attachment_id in archive_attachment_ids

        if archive_present:
            reason = "present"
        elif file_id is None:
            reason = "Oracle row has no FILE_ID at all - nothing to load"
        elif file_id in file_ids_with_object:
            reason = (
                "UPSERT logic gap: attachment_object metadata for this "
                "file_id IS loaded, but this specific reference row is "
                "not - needs manual investigation, since --load-batch/"
                "--load-file-id normally load both together in one "
                "transaction"
            )
        elif file_id in batch_status_by_file_id:
            batch_id, item_status = batch_status_by_file_id[file_id]
            reason = (
                f"batch filtering: file_id {file_id} is a member of "
                f"batch {batch_id}, item status={item_status} (not yet "
                "loaded for this file_id)"
            )
        else:
            reason = (
                "not yet loaded: this file_id has never been selected "
                "into any --create-batch batch or loaded via "
                "--load-file-id - the global, ascending-file_id batch "
                "progression has not reached it yet"
            )

        report_rows.append(
            {
                "attachment_id": attachment_id,
                "file_id": file_id,
                "oracle_present": True,
                "archive_present": archive_present,
                "reason": reason,
            }
        )

    missing_rows = [row for row in report_rows if not row["archive_present"]]

    header = (
        f"{'ATTACHMENT_ID':>14} {'FILE_ID':>10} "
        f"{'ORACLE':>7} {'ARCHIVE':>8}  REASON"
    )
    print(header)
    print("-" * len(header))
    for row in report_rows:
        file_id_display = (
            row["file_id"] if row["file_id"] is not None else "(null)"
        )
        print(
            f"{row['attachment_id']:>14} {file_id_display:>10} "
            f"{'yes':>7} {'yes' if row['archive_present'] else 'no':>8}  "
            f"{row['reason']}"
        )

    logger.bind(
        stage="diff_award_attachments",
        award_id=award_id,
        oracle_count=len(report_rows),
        archive_count=len(report_rows) - len(missing_rows),
        missing_count=len(missing_rows),
    ).info(
        "Award {}: Oracle has {} attachment row(s), archive has {}, "
        "{} missing",
        award_id,
        len(report_rows),
        len(report_rows) - len(missing_rows),
        len(missing_rows),
    )

    return {
        "award_id": award_id,
        "oracle_count": len(report_rows),
        "archive_count": len(report_rows) - len(missing_rows),
        "missing_count": len(missing_rows),
        "rows": report_rows,
    }


def _run_show_batch(engine: Engine, batch_id: int) -> dict[str, Any]:
    """--show-batch: read-only status report for one batch. Never writes
    anything. Uses the generic batch_framework.show_batch for the
    batch-shape fields (found/requested_size/created_at/status/
    total_files/metadata_loaded/missing_metadata), then augments with the
    attachment-specific upload_status breakdown (pending/uploading/
    uploaded/failed/missing_source_content), which is business logic the
    generic framework has no knowledge of."""
    generic_report = batch_framework.show_batch(
        engine,
        batch_id,
        domain=AWARD_ATTACHMENT_BATCH_DOMAIN,
        entity_type=AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
    )

    if not generic_report["found"]:
        logger.info("batch_id={} does not exist", batch_id)
        return {"batch_id": batch_id, "found": False}

    with engine.connect() as connection:
        upload_status_rows = connection.execute(
            text(
                """
                SELECT ao.upload_status, COUNT(*)
                FROM archive.etl_batch_item ebi
                JOIN archive.attachment_object ao ON ao.file_id = ebi.entity_key
                WHERE ebi.batch_id = :batch_id
                  AND ebi.status = :completed_status
                GROUP BY ao.upload_status
                """
            ),
            {
                "batch_id": batch_id,
                "completed_status": batch_framework.ITEM_STATUS_COMPLETED,
            },
        ).all()
        upload_status_counts: dict[str, int] = {
            row[0]: row[1] for row in upload_status_rows
        }

    report = {
        "batch_id": generic_report["batch_id"],
        "found": True,
        "requested_size": generic_report["requested_size"],
        "created_at": generic_report["created_at"],
        "status": generic_report["status"],
        "total_files": generic_report["total_items"],
        "metadata_loaded": generic_report["completed"],
        "pending": upload_status_counts.get("PENDING", 0),
        "uploading": upload_status_counts.get("UPLOADING", 0),
        "uploaded": upload_status_counts.get("UPLOADED", 0),
        "failed": upload_status_counts.get("FAILED", 0),
        "missing_source_content": upload_status_counts.get(
            "MISSING_SOURCE_CONTENT", 0
        ),
        "missing_metadata": generic_report["total_items"] - generic_report["completed"],
    }

    logger.bind(stage="show_batch", batch_id=batch_id).info(
        "batch_id={} status={} total_files={} metadata_loaded={} "
        "pending={} uploading={} uploaded={} failed={} "
        "missing_source_content={} missing_metadata={}",
        report["batch_id"],
        report["status"],
        report["total_files"],
        report["metadata_loaded"],
        report["pending"],
        report["uploading"],
        report["uploaded"],
        report["failed"],
        report["missing_source_content"],
        report["missing_metadata"],
    )

    return report


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
    batch_id: int | None = None,
) -> pd.DataFrame:
    """batch_id, when given, scopes selection to exactly that batch's
    membership (archive.etl_batch_item, the generic batch framework) -
    never any other PENDING/UPLOADING/FAILED row, no matter how many
    exist. A batch member whose metadata load hasn't happened (or found
    nothing in Oracle) yet has no matching attachment_object row, so the
    inner join naturally excludes it - no separate etl_batch_item.status
    filter is needed here. Mutually exclusive with file_id at the CLI
    level (see parse_args)."""
    statuses = list(_DEFAULT_CANDIDATE_STATUSES)
    if retry_failed:
        statuses.append("FAILED")

    if batch_id is not None:
        query = """
            SELECT
                ao.file_id,
                ao.file_data_id,
                ao.file_name,
                ao.content_type,
                ao.blob_source,
                ao.file_size_bytes,
                ao.upload_status,
                ao.s3_bucket,
                ao.s3_key
            FROM archive.attachment_object ao
            JOIN archive.etl_batch_item ebi
                ON ebi.entity_key = ao.file_id
            WHERE ebi.batch_id = :batch_id
              AND ao.upload_status = ANY(:statuses)
        """
        params: dict[str, Any] = {"batch_id": batch_id, "statuses": statuses}
        query += " ORDER BY ao.file_id"
    else:
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
        params = {"statuses": statuses}
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

    batch_id = getattr(arguments, "batch_id", None)
    if batch_id is not None:
        # started_at is set only the first time (see
        # batch_framework.begin_batch_processing) - a resumed upload
        # (after a partial completion) must not overwrite the original
        # start time.
        batch_framework.begin_batch_processing(
            engine,
            batch_id,
            domain=AWARD_ATTACHMENT_BATCH_DOMAIN,
            entity_type=AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
            status=batch_framework.BATCH_STATUS_PROCESSING,
        )
        run_logger = run_logger.bind(batch_id=batch_id)

    with engine.begin() as connection:
        candidates = select_upload_candidates(
            connection,
            limit=arguments.limit,
            file_id=arguments.file_id,
            retry_failed=arguments.retry_failed,
            batch_id=batch_id,
        )

    start_time = datetime.now(UTC)
    start_monotonic = time.monotonic()

    report: dict[str, Any] = {
        "run_id": run_id,
        "batch_id": batch_id,
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

    if batch_id is not None:
        batch_framework.finish_batch_processing(
            engine, batch_id, status=batch_framework.BATCH_STATUS_COMPLETED
        )

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


def _parse_file_id_list(
    raw: str, parser: argparse.ArgumentParser
) -> set[int]:
    """Parse --load-file-ids's comma-separated FILE_ID list into a set of
    positive ints, failing via parser.error (not a raw exception) on any
    malformed or empty entry - consistent with every other --load-file-ids
    validation error."""
    file_ids: set[int] = set()
    for piece in raw.split(","):
        piece = piece.strip()
        if not piece:
            continue
        try:
            file_id = int(piece)
        except ValueError:
            parser.error(
                f"--load-file-ids: {piece!r} is not a valid integer file_id"
            )
        if file_id <= 0:
            parser.error(
                f"--load-file-ids: file_id must be positive, got {file_id}"
            )
        file_ids.add(file_id)
    if not file_ids:
        parser.error("--load-file-ids requires at least one file_id")
    return file_ids


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
        "--load-file-id",
        type=int,
        default=None,
        help=(
            "Bounded, idempotent metadata load for exactly one physical "
            "FILE_ID (and its reference rows only) - fixes 'Oracle has "
            "this file, but archive.attachment_object doesn't, so "
            "--upload --file-id selects zero candidates.' UPSERTs "
            "archive.attachment_object and archive.award_attachment for "
            "this file_id only; never truncates or replaces the full "
            "tables, never reads a BLOB, never uploads to S3. An "
            "existing row's upload_status/upload_attempts/last_error/S3 "
            "fields are always preserved, never overwritten. Logs "
            "inserted/updated/unchanged/missing counts. Combine with "
            "--dry-run to see those counts without persisting anything. "
            "Takes priority over --upload/--file-id/--limit if given."
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
            "PostgreSQL/Oracle credentials from AWS Secrets Manager only "
            "(POSTGRES_SECRET_ID/ORACLE_SECRET_ID - never a plaintext "
            "environment variable, never a local .env export), switch to "
            "structured JSON logging for CloudWatch, apply production "
            "defaults (--bucket defaults to AWARD_ATTACHMENT_BUCKET_NAME "
            "if not given - deliberately not DATA_BUCKET_NAME, a "
            "different, IRB-only bucket), and run startup validation "
            "(AWS identity, secrets, "
            "PostgreSQL/Oracle reachable, S3 bucket exists, Award "
            "attachment tables present, upload_status schema matches "
            "V036) before processing anything - aborts immediately on "
            "any failure. Requires the tables/schema to already exist - "
            "see --migrate-only to bootstrap a fresh database."
        ),
    )
    parser.add_argument(
        "--migrate-only",
        action="store_true",
        help=(
            "Valid only with --ecs. Resolve AWS identity and the "
            "PostgreSQL secret, verify PostgreSQL connectivity, apply "
            "pending database migrations, validate the resulting schema "
            "(Award attachment tables + upload_status constraint), then "
            "exit successfully - without ever touching Oracle, S3, or "
            "any attachment data. Use this once to bootstrap a fresh "
            "database; every other --ecs invocation requires migrations "
            "to already be applied and never applies them itself."
        ),
    )
    parser.add_argument(
        "--show-upload-status",
        action="store_true",
        help=(
            "Valid only with --ecs; requires --file-id. Read-only "
            "diagnostic: query archive.attachment_object for the exact "
            "given file_id and log its file_name, blob_source, "
            "upload_status, upload_attempts, s3_bucket, s3_key, "
            "uploaded_at, and last_error, then exit successfully - "
            "including when no row exists (logged clearly as metadata "
            "not yet loaded, not an error). Never reads a BLOB, never "
            "writes to PostgreSQL, never uploads to S3. Intended to be "
            "run inside the ECS task so PostgreSQL upload state can be "
            "inspected without a direct connection to private RDS."
        ),
    )
    parser.add_argument(
        "--create-batch",
        type=int,
        default=None,
        metavar="N",
        help=(
            "Select exactly N distinct physical file_ids from Oracle, in "
            "stable ascending file_id order, and persist that exact "
            "membership as a new batch (archive.etl_batch/"
            "etl_batch_item, the generic batch framework) - a durable "
            "manifest --load-batch and --upload --batch-id always operate "
            "on, unlike --limit "
            "(a live, unpersisted query re-evaluated on every "
            "invocation - see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md). "
            "Excludes file_ids already fully UPLOADED unless "
            "--include-already-uploaded is given. Never reads a BLOB, "
            "never touches S3. N must be positive."
        ),
    )
    parser.add_argument(
        "--include-already-uploaded",
        action="store_true",
        help=(
            "With --create-batch, include file_ids already fully "
            "UPLOADED in the candidate pool instead of excluding them."
        ),
    )
    parser.add_argument(
        "--load-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "Idempotent metadata load for exactly this batch's recorded "
            "membership (never truncates or replaces the full tables, "
            "never reads a BLOB, never touches S3, preserves existing "
            "upload state) - the batch equivalent of --load-file-id."
        ),
    )
    parser.add_argument(
        "--show-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "Read-only status report for one batch: requested_size, "
            "status, and file counts by metadata-load/upload state. "
            "Never writes anything."
        ),
    )
    parser.add_argument(
        "--batch-id",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "With --upload, restrict candidate selection to exactly this "
            "batch's membership - never any other PENDING/UPLOADING/"
            "FAILED row. Only valid together with --upload."
        ),
    )
    parser.add_argument(
        "--list-awards-with-attachments",
        action="store_true",
        help=(
            "Developer aid, not a production feature: read-only report "
            "of every Award version (award_number, award_id, title) that "
            "has at least one archive.award_attachment row, with its "
            "attachment count, sorted highest-count first - use this to "
            "find a real award_id worth opening in the UI's Attachments "
            "tab. PostgreSQL-only, never touches Oracle or S3. Runs "
            "either locally (via the same POSTGRES_* environment as the "
            "rest of this loader, e.g. a scripts/start-db-tunnel.sh "
            "tunnel) or as a one-off --ecs task - like --show-batch, it "
            "is exempt from --ecs's usual ORACLE_SECRET_ID requirement "
            "since it never reads Oracle. See "
            "scripts/run-award-attachment-loader.sh "
            "--list-awards-with-attachments for the one-off-ECS-task "
            "form (no bastion host required)."
        ),
    )
    parser.add_argument(
        "--diff-award-attachments",
        type=int,
        default=None,
        metavar="AWARD_ID",
        help=(
            "Developer/investigation aid, not a production feature: "
            "read-only side-by-side comparison of Oracle's "
            "KCOEUS.AWARD_ATTACHMENT rows for exactly this award_id "
            "against archive.award_attachment, explaining per-row "
            "exactly why any Oracle-only row hasn't been archived yet "
            "(never yet targeted by a batch, targeted but not "
            "completed, or a genuine upsert gap). Reads both Oracle "
            "(a targeted bind-variable filter, not a full-table scan) "
            "and PostgreSQL - unlike --list-awards-with-attachments, "
            "this is NOT exempt from --ecs's usual ORACLE_SECRET_ID "
            "requirement. Never writes anything."
        ),
    )
    parser.add_argument(
        "--load-file-ids",
        type=str,
        default=None,
        metavar="FILE_ID,FILE_ID,...",
        help=(
            "Bounded, idempotent metadata load for exactly these physical "
            "FILE_IDs (and their reference rows only, all in one "
            "transaction) - the plural form of --load-file-id, for "
            "backfilling a known, specific set of file_ids (e.g. the "
            "exact set --diff-award-attachments proved were never "
            "loaded) in one pass instead of one task invocation per "
            "file_id. Comma-separated list of positive integers, e.g. "
            "'5993,5994,5995'. Same guarantees as --load-file-id: UPSERTs "
            "only, never truncates or replaces the full tables, never "
            "reads a BLOB, never uploads to S3, and an existing row's "
            "upload_status/upload_attempts/last_error/S3 fields are "
            "always preserved. Cannot be combined with --load-file-id, "
            "--file-id, --batch-id, or any batch verb."
        ),
    )
    parsed = parser.parse_args(arguments)
    if parsed.migrate_only and not parsed.ecs:
        parser.error("--migrate-only is only valid together with --ecs")
    if parsed.show_upload_status and not parsed.ecs:
        parser.error("--show-upload-status is only valid together with --ecs")
    if parsed.show_upload_status and parsed.file_id is None:
        parser.error("--show-upload-status requires --file-id")
    if parsed.list_awards_with_attachments and parsed.limit is not None and parsed.limit <= 0:
        parser.error("--limit must be a positive integer")

    batch_verbs = [
        ("--create-batch", parsed.create_batch),
        ("--load-batch", parsed.load_batch),
        ("--show-batch", parsed.show_batch),
    ]
    active_batch_verbs = [name for name, value in batch_verbs if value is not None]
    if len(active_batch_verbs) > 1:
        parser.error(
            f"{' and '.join(active_batch_verbs)} cannot be combined - "
            "choose one batch operation at a time"
        )
    if parsed.create_batch is not None and parsed.create_batch <= 0:
        parser.error("--create-batch must be a positive integer")
    if parsed.include_already_uploaded and parsed.create_batch is None:
        parser.error("--include-already-uploaded requires --create-batch")

    # Each batch verb (--create-batch/--load-batch/--show-batch) dispatches
    # to its own, complete code path in main() and returns immediately -
    # combining one with --upload, --load-file-id, or --file-id would
    # silently ignore whichever flag lost dispatch priority, so all three
    # are rejected explicitly here instead.
    if active_batch_verbs:
        if parsed.upload:
            parser.error(
                f"{active_batch_verbs[0]} cannot be combined with --upload "
                "- use --upload --batch-id <BATCH_ID> to upload a batch"
            )
        if parsed.load_file_id is not None:
            parser.error(
                f"{active_batch_verbs[0]} cannot be combined with "
                "--load-file-id"
            )
        if parsed.file_id is not None:
            parser.error(
                f"{active_batch_verbs[0]} cannot be combined with --file-id"
            )

    if parsed.batch_id is not None:
        if not parsed.upload:
            parser.error("--batch-id is only valid together with --upload")
        if active_batch_verbs:
            parser.error(
                f"--batch-id cannot be combined with {active_batch_verbs[0]}"
            )
        if parsed.load_file_id is not None:
            parser.error("--batch-id cannot be combined with --load-file-id")
    if parsed.file_id is not None and parsed.batch_id is not None:
        parser.error("--file-id and --batch-id cannot be combined")

    if parsed.load_file_ids is not None:
        if parsed.load_file_id is not None:
            parser.error(
                "--load-file-id and --load-file-ids cannot be combined"
            )
        if parsed.file_id is not None:
            parser.error("--file-id cannot be combined with --load-file-ids")
        if parsed.batch_id is not None:
            parser.error(
                "--batch-id cannot be combined with --load-file-ids"
            )
        if active_batch_verbs:
            parser.error(
                f"{active_batch_verbs[0]} cannot be combined with "
                "--load-file-ids"
            )
        parsed.load_file_ids = _parse_file_id_list(
            parsed.load_file_ids, parser
        )

    return parsed


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


def _run_show_upload_status(engine: Engine, file_id: int) -> dict[str, Any]:
    """--show-upload-status: a targeted, read-only diagnostic for exactly
    one file_id's PostgreSQL upload state - lets an operator inspect
    archive.attachment_object from inside the ECS task without a direct
    connection to private RDS. Only ever SELECTs; never writes to
    PostgreSQL, never reads a BLOB (attachment_object has no BLOB column -
    source content lives only in Oracle), and never touches S3. Logs
    clearly, and returns successfully, whether or not a row exists for
    this file_id - a missing row is expected/normal (metadata not yet
    loaded), not an error condition."""
    with engine.connect() as connection:
        row = connection.execute(
            text(
                """
                SELECT
                    file_id,
                    file_name,
                    blob_source,
                    upload_status,
                    upload_attempts,
                    s3_bucket,
                    s3_key,
                    uploaded_at,
                    last_error
                FROM archive.attachment_object
                WHERE file_id = :file_id
                """
            ),
            {"file_id": file_id},
        ).mappings().one_or_none()

    if row is None:
        logger.info(
            "file_id={}: no archive.attachment_object row found - "
            "metadata has not been loaded for this file_id",
            file_id,
        )
        return {"file_id": file_id, "found": False}

    last_error = (
        redact_error_message(row["last_error"])
        if row["last_error"] is not None
        else None
    )

    logger.info(
        "file_id={} file_name={} blob_source={} upload_status={} "
        "upload_attempts={} s3_bucket={} s3_key={} uploaded_at={} "
        "last_error={}",
        row["file_id"],
        row["file_name"],
        row["blob_source"],
        row["upload_status"],
        row["upload_attempts"],
        row["s3_bucket"],
        row["s3_key"],
        row["uploaded_at"],
        last_error,
    )

    return {
        "file_id": row["file_id"],
        "found": True,
        "file_name": row["file_name"],
        "blob_source": row["blob_source"],
        "upload_status": row["upload_status"],
        "upload_attempts": row["upload_attempts"],
        "s3_bucket": row["s3_bucket"],
        "s3_key": row["s3_key"],
        "uploaded_at": row["uploaded_at"],
        "last_error": last_error,
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


def _run_ecs_setup(arguments: argparse.Namespace, run_id: str) -> bool:
    """--ecs mode setup, in exactly this order:

    1. structured logging
    2. AWS task-role identity via STS
    3. one Secrets Manager client for the whole startup
    4. load the PostgreSQL secret (always required)
    5. load the Oracle secret (skipped entirely for --migrate-only,
       --show-upload-status, --show-batch, and
       --list-awards-with-attachments - none of them touch Oracle)
    6. verify PostgreSQL connectivity
    7. if --migrate-only: apply migrations, validate the resulting
       schema, and return True so main() exits without ever reaching
       Oracle, S3, metadata, or upload code
    7a. if --show-upload-status: run the read-only diagnostic query for
        the given --file-id and return True, same as --migrate-only -
        never reaches Oracle, S3, or upload code either
    7b. if --show-batch: run the read-only batch status report and
        return True, same as --migrate-only - PostgreSQL only
    7c. if --list-awards-with-attachments: run the read-only Awards-
        with-attachments report and return True, same as --migrate-only
        - PostgreSQL only. This is what lets the report run as a one-off
        ECS task using the existing loader task definition instead of
        requiring a dedicated bastion host (see
        scripts/run-award-attachment-loader.sh).
    8. verify Oracle connectivity
    9. verify S3 bucket access (skipped, not failed, if no bucket is
       configured at all)
    10. verify the Award attachment tables exist
    11. verify the upload_status constraint matches migration V036

    Aborts immediately (lets the raised exception propagate) if any step
    fails - no upload or BLOB read may begin before every required check
    for the requested mode passes. Returns True when --migrate-only,
    --show-upload-status, --show-batch, or
    --list-awards-with-attachments completed successfully (the caller
    must not proceed further), False otherwise."""
    configure_structured_logging(run_id)
    logger.bind(stage="startup").info(
        "Starting in --ecs mode: run_id={}", run_id
    )

    identity = validate_aws_identity()
    logger.bind(stage="startup").info(
        "AWS identity resolved via ECS task role: account={}",
        identity["account"],
    )

    secrets_client = boto3.client("secretsmanager")

    configure_ecs_environment(
        secrets_client,
        include_oracle=not (
            arguments.migrate_only
            or arguments.show_upload_status
            or arguments.show_batch is not None
            or arguments.list_awards_with_attachments
        ),
    )

    engine = create_postgres_engine()
    validate_postgres_reachable(engine)
    logger.bind(stage="startup").info("PostgreSQL reachable")

    if arguments.migrate_only:
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        logger.bind(stage="startup").info("Migrations applied")

        validate_table_exists(engine, "attachment_object")
        validate_table_exists(engine, "award_attachment")
        validate_upload_status_schema(engine)
        logger.bind(stage="startup", status="migrate_only_complete").info(
            "Migration and schema validation complete"
        )
        return True

    if arguments.show_upload_status:
        _run_show_upload_status(engine, arguments.file_id)
        logger.bind(
            stage="startup", status="show_upload_status_complete"
        ).info("Upload-status lookup complete")
        return True

    if arguments.show_batch is not None:
        _run_show_batch(engine, arguments.show_batch)
        logger.bind(stage="startup", status="show_batch_complete").info(
            "Batch status report complete"
        )
        return True

    if arguments.list_awards_with_attachments:
        _run_list_awards_with_attachments(engine, arguments.limit)
        logger.bind(
            stage="startup", status="list_awards_with_attachments_complete"
        ).info("Awards-with-attachments report complete")
        return True

    validate_oracle_reachable(_connect_oracle)
    logger.bind(stage="startup").info("Oracle reachable")

    if arguments.diff_award_attachments is not None:
        _run_diff_award_attachments(engine, arguments.diff_award_attachments)
        logger.bind(
            stage="startup", status="diff_award_attachments_complete"
        ).info("Award/Oracle attachment diff complete")
        return True

    if not arguments.bucket:
        arguments.bucket = (
            os.environ.get(AWARD_ATTACHMENT_BUCKET_NAME_VARIABLE) or None
        )

    if arguments.bucket:
        s3_client = create_s3_client()
        validate_bucket_exists(s3_client, arguments.bucket)
        logger.bind(stage="startup").info(
            "S3 bucket exists: {}", arguments.bucket
        )
    else:
        logger.bind(stage="startup").info(
            "No bucket configured - skipping S3 bucket existence check"
        )

    validate_table_exists(engine, "attachment_object")
    validate_table_exists(engine, "award_attachment")
    logger.bind(stage="startup").info("Award attachment tables present")

    validate_upload_status_schema(engine)
    logger.bind(stage="startup").info(
        "upload_status schema matches expected migration"
    )

    logger.bind(stage="startup").info("Startup validation passed")
    return False


def main() -> None:
    arguments = parse_args()
    run_id = str(uuid.uuid4())

    if arguments.list_awards_with_attachments and not arguments.ecs:
        engine = create_postgres_engine()
        _run_list_awards_with_attachments(engine, arguments.limit)
        return

    if arguments.diff_award_attachments is not None and not arguments.ecs:
        engine = create_postgres_engine()
        _run_diff_award_attachments(engine, arguments.diff_award_attachments)
        return

    if arguments.ecs:
        ecs_setup_short_circuited = _run_ecs_setup(arguments, run_id)
        if ecs_setup_short_circuited:
            return

    if arguments.create_batch is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_create_batch(
            engine,
            arguments.create_batch,
            include_already_uploaded=arguments.include_already_uploaded,
            run_id=run_id,
        )
        return

    if arguments.load_batch is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_batch(
            engine,
            arguments.load_batch,
            dry_run=arguments.dry_run,
            run_id=run_id,
        )
        return

    if arguments.show_batch is not None:
        engine = create_postgres_engine()
        _run_show_batch(engine, arguments.show_batch)
        return

    if arguments.load_file_id is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_file_id(
            engine,
            arguments.load_file_id,
            dry_run=arguments.dry_run,
            run_id=run_id,
        )
        return

    if arguments.load_file_ids is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_file_ids(
            engine,
            arguments.load_file_ids,
            dry_run=arguments.dry_run,
            run_id=run_id,
        )
        return

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
