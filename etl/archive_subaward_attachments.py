from __future__ import annotations

import argparse
import csv
import hashlib
import os
import re
import sqlite3
import tempfile
import time
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, TypeVar

import boto3
import oracledb
from botocore.config import Config
from botocore.exceptions import ClientError
from dateutil.parser import parse as parse_datetime
from loguru import logger
from sqlalchemy import text

from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


DEFAULT_METADATA_CSV = Path.home() / "Downloads" / "subaward_attachments.csv"
DEFAULT_MANIFEST = (
    Path(__file__).resolve().parent
    / "exports"
    / "subawards"
    / "subaward_attachment_manifest.sqlite3"
)
MANIFEST_COLUMNS = (
    "attachment_id",
    "subaward_id",
    "subaward_code",
    "sequence_number",
    "file_data_id",
    "original_file_name",
    "mime_type",
    "document_id",
    "attachment_source_update_timestamp",
    "attachment_last_update_timestamp",
    "s3_bucket",
    "s3_key",
    "byte_size",
    "sha256",
    "archive_status",
    "archived_timestamp",
    "error_message",
    "manifest_updated_at",
)

T = TypeVar("T")


class MissingBlobError(RuntimeError):
    pass


@dataclass(frozen=True)
class AttachmentMetadata:
    attachment_id: int
    subaward_id: int
    subaward_code: str
    sequence_number: int
    file_data_id: str | None
    original_file_name: str | None
    mime_type: str | None
    document_id: int | None
    source_update_timestamp: str | None
    last_update_timestamp: str | None


@dataclass
class ArchiveCounts:
    attachment_metadata_count: int = 0
    file_data_match_count: int = 0
    uploaded_count: int = 0
    resumed_count: int = 0
    missing_blob_count: int = 0
    failed_upload_count: int = 0
    total_archived_bytes: int = 0
    checksum_mismatch_count: int = 0
    manifest_orphan_count: int = 0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def safe_error_message(error: Exception) -> str:
    return f"{type(error).__name__}: archival operation failed"[:1000]


def retry(
    operation: Callable[[], T],
    *,
    attempts: int,
    operation_name: str,
) -> T:
    for attempt in range(1, attempts + 1):
        try:
            return operation()
        except MissingBlobError:
            raise
        except Exception:
            if attempt == attempts:
                raise
            delay = min(2 ** (attempt - 1), 30)
            logger.warning(
                "{} failed on attempt {}/{}; retrying in {} seconds",
                operation_name,
                attempt,
                attempts,
                delay,
            )
            time.sleep(delay)
    raise AssertionError("retry loop exited unexpectedly")


def sanitize_file_name(file_name: str | None, attachment_id: int) -> str:
    candidate = Path(file_name or "").name.strip()
    candidate = unicodedata.normalize("NFKC", candidate)
    candidate = re.sub(r"[^A-Za-z0-9._ -]+", "_", candidate)
    candidate = re.sub(r"\s+", "_", candidate).strip("._-")

    if not candidate:
        candidate = f"attachment-{attachment_id}.bin"

    if len(candidate) > 180:
        suffix = Path(candidate).suffix[:20]
        stem_limit = 180 - len(suffix)
        candidate = f"{Path(candidate).stem[:stem_limit]}{suffix}"

    return candidate


def object_key(
    prefix: str,
    metadata: AttachmentMetadata,
) -> str:
    clean_prefix = prefix.strip("/")
    name = sanitize_file_name(
        metadata.original_file_name,
        metadata.attachment_id,
    )
    parts = [
        clean_prefix,
        str(metadata.subaward_id),
        str(metadata.attachment_id),
        name,
    ]
    return "/".join(part for part in parts if part)


class ManifestStore:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS attachment_manifest (
                attachment_id INTEGER PRIMARY KEY,
                subaward_id INTEGER NOT NULL,
                subaward_code TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                file_data_id TEXT,
                original_file_name TEXT,
                mime_type TEXT,
                document_id INTEGER,
                attachment_source_update_timestamp TEXT,
                attachment_last_update_timestamp TEXT,
                s3_bucket TEXT,
                s3_key TEXT,
                byte_size INTEGER,
                sha256 TEXT,
                archive_status TEXT NOT NULL,
                archived_timestamp TEXT,
                error_message TEXT,
                manifest_updated_at TEXT NOT NULL
            )
            """
        )
        self.connection.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_attachment_manifest_subaward
                ON attachment_manifest (subaward_id, attachment_id)
            """
        )
        self.connection.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_attachment_manifest_file_data
                ON attachment_manifest (file_data_id)
            """
        )
        self.connection.commit()

    def get(self, attachment_id: int) -> dict[str, Any] | None:
        row = self.connection.execute(
            """
            SELECT *
            FROM attachment_manifest
            WHERE attachment_id = ?
            """,
            (attachment_id,),
        ).fetchone()
        return dict(row) if row else None

    def upsert(self, values: dict[str, Any]) -> None:
        placeholders = ", ".join("?" for _ in MANIFEST_COLUMNS)
        assignments = ", ".join(
            f"{column} = excluded.{column}"
            for column in MANIFEST_COLUMNS
            if column != "attachment_id"
        )
        self.connection.execute(
            f"""
            INSERT INTO attachment_manifest (
                {", ".join(MANIFEST_COLUMNS)}
            )
            VALUES ({placeholders})
            ON CONFLICT (attachment_id) DO UPDATE SET
                {assignments}
            """,
            [values.get(column) for column in MANIFEST_COLUMNS],
        )
        self.connection.commit()

    def rows(
        self,
        subaward_id: int | None = None,
        limit: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        sql = "SELECT * FROM attachment_manifest"
        parameters: list[Any] = []
        if subaward_id is not None:
            sql += " WHERE subaward_id = ?"
            parameters.append(subaward_id)
        sql += " ORDER BY attachment_id"
        if limit is not None:
            sql += " LIMIT ?"
            parameters.append(limit)

        for row in self.connection.execute(sql, parameters):
            yield dict(row)

    def close(self) -> None:
        self.connection.close()


class OracleBlobReader:
    def __init__(self, attempts: int, chunk_size: int) -> None:
        self.attempts = attempts
        self.chunk_size = chunk_size
        self.connection: oracledb.Connection | None = None

    def connect(self) -> None:
        required = ["ORACLE_USER", "ORACLE_PASSWORD", "ORACLE_DSN"]
        missing = [name for name in required if not os.getenv(name)]
        if missing:
            raise RuntimeError(
                "Missing Oracle environment variables: " + ", ".join(missing)
            )
        self.connection = oracledb.connect(
            user=os.environ["ORACLE_USER"],
            password=os.environ["ORACLE_PASSWORD"],
            dsn=os.environ["ORACLE_DSN"],
        )

    def close(self) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def stream_to_path(
        self,
        file_data_id: str,
        destination: Path,
    ) -> tuple[int, str]:
        def operation() -> tuple[int, str]:
            try:
                if self.connection is None:
                    self.connect()

                assert self.connection is not None
                with self.connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT fd.data
                        FROM KCOEUS.FILE_DATA fd
                        WHERE fd.id = :file_data_id
                        """,
                        file_data_id=file_data_id,
                    )
                    row = cursor.fetchone()
                    if row is None or row[0] is None:
                        raise MissingBlobError(
                            f"FILE_DATA row or BLOB missing for {file_data_id}"
                        )

                    blob = row[0]
                    digest = hashlib.sha256()
                    byte_size = 0
                    offset = 1

                    with destination.open("wb") as output:
                        while True:
                            chunk = blob.read(offset, self.chunk_size)
                            if not chunk:
                                break
                            output.write(chunk)
                            digest.update(chunk)
                            byte_size += len(chunk)
                            offset += len(chunk)

                    return byte_size, digest.hexdigest()
            except MissingBlobError:
                raise
            except Exception:
                self.close()
                raise

        return retry(
            operation,
            attempts=self.attempts,
            operation_name="Oracle BLOB read",
        )


def create_s3_client(region: str, attempts: int):
    return boto3.client(
        "s3",
        region_name=region,
        config=Config(
            retries={
                "max_attempts": attempts,
                "mode": "standard",
            }
        ),
    )


def head_object(
    s3_client,
    bucket: str,
    key: str,
    attempts: int,
) -> dict[str, Any] | None:
    def operation() -> dict[str, Any] | None:
        try:
            return s3_client.head_object(Bucket=bucket, Key=key)
        except ClientError as error:
            status = error.response.get("ResponseMetadata", {}).get(
                "HTTPStatusCode"
            )
            if status == 404:
                return None
            raise

    return retry(
        operation,
        attempts=attempts,
        operation_name="S3 HEAD",
    )


def upload_object(
    s3_client,
    path: Path,
    metadata: AttachmentMetadata,
    bucket: str,
    key: str,
    sha256: str,
    sse: str,
    kms_key_id: str | None,
    attempts: int,
) -> None:
    extra_args: dict[str, Any] = {
        "Metadata": {
            "sha256": sha256,
            "attachment-id": str(metadata.attachment_id),
            "subaward-id": str(metadata.subaward_id),
            "file-data-id": metadata.file_data_id or "",
        },
        "ServerSideEncryption": sse,
    }
    if metadata.mime_type:
        extra_args["ContentType"] = metadata.mime_type
    if sse == "aws:kms":
        if not kms_key_id:
            raise RuntimeError("--kms-key-id is required with --sse aws:kms")
        extra_args["SSEKMSKeyId"] = kms_key_id

    retry(
        lambda: s3_client.upload_file(
            str(path),
            bucket,
            key,
            ExtraArgs=extra_args,
        ),
        attempts=attempts,
        operation_name="S3 upload",
    )


def manifest_matches(
    manifest: dict[str, Any] | None,
    metadata: AttachmentMetadata,
    bucket: str,
    key: str,
    byte_size: int,
    sha256: str,
) -> bool:
    if not manifest or manifest.get("archive_status") != "ARCHIVED":
        return False

    expected = {
        "attachment_id": metadata.attachment_id,
        "subaward_id": metadata.subaward_id,
        "subaward_code": metadata.subaward_code,
        "sequence_number": metadata.sequence_number,
        "file_data_id": metadata.file_data_id,
        "original_file_name": metadata.original_file_name,
        "mime_type": metadata.mime_type,
        "document_id": metadata.document_id,
        "attachment_source_update_timestamp":
            metadata.source_update_timestamp,
        "attachment_last_update_timestamp": metadata.last_update_timestamp,
        "s3_bucket": bucket,
        "s3_key": key,
        "byte_size": byte_size,
        "sha256": sha256,
    }
    return all(manifest.get(name) == value for name, value in expected.items())


def s3_checksum_matches(
    head: dict[str, Any] | None,
    byte_size: int,
    sha256: str,
) -> bool:
    if head is None:
        return False
    metadata = head.get("Metadata", {})
    return (
        head.get("ContentLength") == byte_size
        and metadata.get("sha256") == sha256
    )


def manifest_values(
    metadata: AttachmentMetadata,
    bucket: str,
    key: str,
    *,
    byte_size: int | None,
    sha256: str | None,
    status: str,
    archived_timestamp: str | None,
    error_message: str | None,
) -> dict[str, Any]:
    return {
        "attachment_id": metadata.attachment_id,
        "subaward_id": metadata.subaward_id,
        "subaward_code": metadata.subaward_code,
        "sequence_number": metadata.sequence_number,
        "file_data_id": metadata.file_data_id,
        "original_file_name": metadata.original_file_name,
        "mime_type": metadata.mime_type,
        "document_id": metadata.document_id,
        "attachment_source_update_timestamp":
            metadata.source_update_timestamp,
        "attachment_last_update_timestamp": metadata.last_update_timestamp,
        "s3_bucket": bucket,
        "s3_key": key,
        "byte_size": byte_size,
        "sha256": sha256,
        "archive_status": status,
        "archived_timestamp": archived_timestamp,
        "error_message": error_message,
        "manifest_updated_at": utc_now(),
    }


def optional_int(value: str | None) -> int | None:
    if value is None or not value.strip():
        return None
    return int(value)


def iter_attachment_metadata(
    path: Path,
    subaward_id: int | None,
    limit: int | None,
) -> Iterator[AttachmentMetadata]:
    required_columns = {
        "attachment_id",
        "subaward_id",
        "subaward_code",
        "sequence_number",
        "file_data_id",
        "file_name",
        "mime_type",
        "document_id",
        "update_timestamp",
        "last_update_timestamp",
    }
    emitted = 0

    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None:
            raise RuntimeError(f"{path} has no CSV header")
        reader.fieldnames = [
            field.strip().lower()
            for field in reader.fieldnames
        ]
        missing = sorted(required_columns - set(reader.fieldnames))
        if missing:
            raise RuntimeError(
                f"{path.name} is missing columns: " + ", ".join(missing)
            )

        for row in reader:
            row_subaward_id = int(row["subaward_id"])
            if subaward_id is not None and row_subaward_id != subaward_id:
                continue
            if limit is not None and emitted >= limit:
                break

            emitted += 1
            yield AttachmentMetadata(
                attachment_id=int(row["attachment_id"]),
                subaward_id=row_subaward_id,
                subaward_code=row["subaward_code"],
                sequence_number=int(row["sequence_number"]),
                file_data_id=row["file_data_id"].strip() or None,
                original_file_name=row["file_name"].strip() or None,
                mime_type=row["mime_type"].strip() or None,
                document_id=optional_int(row["document_id"]),
                source_update_timestamp=
                    row["update_timestamp"].strip() or None,
                last_update_timestamp=
                    row["last_update_timestamp"].strip() or None,
            )


def process_attachment(
    metadata: AttachmentMetadata,
    *,
    reader: OracleBlobReader,
    manifest: ManifestStore,
    s3_client,
    bucket: str,
    prefix: str,
    sse: str,
    kms_key_id: str | None,
    attempts: int,
    verify_only: bool,
    counts: ArchiveCounts,
) -> None:
    key = object_key(prefix, metadata)

    if metadata.file_data_id is None:
        counts.missing_blob_count += 1
        if not verify_only:
            manifest.upsert(
                manifest_values(
                    metadata,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="MISSING",
                    archived_timestamp=None,
                    error_message="FILE_DATA_ID is missing",
                )
            )
        logger.error(
            "Attachment {} has no FILE_DATA_ID",
            metadata.attachment_id,
        )
        return

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f"subaward-{metadata.attachment_id}-",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)

        byte_size, sha256 = reader.stream_to_path(
            metadata.file_data_id,
            temporary_path,
        )
        counts.file_data_match_count += 1
        current_manifest = manifest.get(metadata.attachment_id)
        head = head_object(s3_client, bucket, key, attempts)
        manifest_ok = manifest_matches(
            current_manifest,
            metadata,
            bucket,
            key,
            byte_size,
            sha256,
        )
        s3_ok = s3_checksum_matches(head, byte_size, sha256)

        if verify_only:
            if not manifest_ok or not s3_ok:
                counts.checksum_mismatch_count += 1
            else:
                counts.resumed_count += 1
                counts.total_archived_bytes += byte_size
            return

        if manifest_ok and s3_ok:
            counts.resumed_count += 1
            counts.total_archived_bytes += byte_size
            return

        upload_object(
            s3_client,
            temporary_path,
            metadata,
            bucket,
            key,
            sha256,
            sse,
            kms_key_id,
            attempts,
        )
        uploaded_head = head_object(s3_client, bucket, key, attempts)
        if not s3_checksum_matches(uploaded_head, byte_size, sha256):
            raise RuntimeError("uploaded S3 checksum metadata did not match")

        archived_timestamp = utc_now()
        manifest.upsert(
            manifest_values(
                metadata,
                bucket,
                key,
                byte_size=byte_size,
                sha256=sha256,
                status="ARCHIVED",
                archived_timestamp=archived_timestamp,
                error_message=None,
            )
        )
        counts.uploaded_count += 1
        counts.total_archived_bytes += byte_size
    except MissingBlobError:
        counts.missing_blob_count += 1
        if not verify_only:
            manifest.upsert(
                manifest_values(
                    metadata,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="MISSING",
                    archived_timestamp=None,
                    error_message=(
                        "KCOEUS.FILE_DATA row or DATA BLOB is missing"
                    ),
                )
            )
        logger.error(
            "Missing FILE_DATA BLOB for attachment {} and FILE_DATA_ID {}",
            metadata.attachment_id,
            metadata.file_data_id,
        )
    except Exception as error:
        counts.failed_upload_count += 1
        if not verify_only:
            manifest.upsert(
                manifest_values(
                    metadata,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="FAILED",
                    archived_timestamp=None,
                    error_message=safe_error_message(error),
                )
            )
        logger.exception(
            "Attachment {} archival failed",
            metadata.attachment_id,
        )
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def parse_manifest_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return parse_datetime(value)


def sync_manifest_to_postgres(
    manifest: ManifestStore,
    subaward_id: int | None,
) -> int:
    engine = create_postgres_engine()
    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1] / "database" / "migrations",
    )
    upsert_sql = text(
        """
        INSERT INTO archive.subaward_attachment_archive (
            attachment_id,
            subaward_id,
            subaward_code,
            sequence_number,
            file_data_id,
            original_file_name,
            mime_type,
            document_id,
            attachment_source_update_timestamp,
            attachment_last_update_timestamp,
            s3_bucket,
            s3_key,
            byte_size,
            sha256,
            archive_status,
            archived_timestamp,
            error_message,
            manifest_updated_at
        )
        VALUES (
            :attachment_id,
            :subaward_id,
            :subaward_code,
            :sequence_number,
            :file_data_id,
            :original_file_name,
            :mime_type,
            :document_id,
            :attachment_source_update_timestamp,
            :attachment_last_update_timestamp,
            :s3_bucket,
            :s3_key,
            :byte_size,
            :sha256,
            :archive_status,
            :archived_timestamp,
            :error_message,
            :manifest_updated_at
        )
        ON CONFLICT (attachment_id) DO UPDATE SET
            subaward_id = EXCLUDED.subaward_id,
            subaward_code = EXCLUDED.subaward_code,
            sequence_number = EXCLUDED.sequence_number,
            file_data_id = EXCLUDED.file_data_id,
            original_file_name = EXCLUDED.original_file_name,
            mime_type = EXCLUDED.mime_type,
            document_id = EXCLUDED.document_id,
            attachment_source_update_timestamp =
                EXCLUDED.attachment_source_update_timestamp,
            attachment_last_update_timestamp =
                EXCLUDED.attachment_last_update_timestamp,
            s3_bucket = EXCLUDED.s3_bucket,
            s3_key = EXCLUDED.s3_key,
            byte_size = EXCLUDED.byte_size,
            sha256 = EXCLUDED.sha256,
            archive_status = EXCLUDED.archive_status,
            archived_timestamp = EXCLUDED.archived_timestamp,
            error_message = EXCLUDED.error_message,
            manifest_updated_at = EXCLUDED.manifest_updated_at
        """
    )

    batch: list[dict[str, Any]] = []
    synced = 0
    with engine.begin() as connection:
        for row in manifest.rows(subaward_id):
            if row["archive_status"] != "ARCHIVED":
                continue
            row["attachment_source_update_timestamp"] = (
                parse_manifest_timestamp(
                    row["attachment_source_update_timestamp"]
                )
            )
            row["attachment_last_update_timestamp"] = (
                parse_manifest_timestamp(
                    row["attachment_last_update_timestamp"]
                )
            )
            row["archived_timestamp"] = parse_manifest_timestamp(
                row["archived_timestamp"]
            )
            row["manifest_updated_at"] = parse_manifest_timestamp(
                row["manifest_updated_at"]
            )
            batch.append(row)
            if len(batch) == 1000:
                connection.execute(upsert_sql, batch)
                synced += len(batch)
                batch.clear()
        if batch:
            connection.execute(upsert_sql, batch)
            synced += len(batch)

    logger.info("Synchronized {:,} manifest rows to PostgreSQL", synced)
    return synced


def verify_manifest_orphans(
    metadata_path: Path,
    manifest: ManifestStore,
    subaward_id: int | None,
    limit: int | None,
) -> int:
    attachment_ids = {
        row.attachment_id
        for row in iter_attachment_metadata(
            metadata_path,
            subaward_id,
            limit,
        )
    }
    return sum(
        1
        for row in manifest.rows(subaward_id, limit)
        if row["attachment_id"] not in attachment_ids
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Archive verified Subaward attachment BLOBs from local Oracle "
            "to approved S3 storage."
        )
    )
    parser.add_argument(
        "--metadata-csv",
        type=Path,
        default=DEFAULT_METADATA_CSV,
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
    )
    parser.add_argument(
        "--s3-bucket",
        default=os.getenv("SUBAWARD_ATTACHMENT_S3_BUCKET"),
    )
    parser.add_argument(
        "--s3-prefix",
        default=os.getenv("SUBAWARD_ATTACHMENT_S3_PREFIX", "subawards"),
    )
    parser.add_argument(
        "--aws-region",
        default=os.getenv("AWS_REGION", "us-east-1"),
    )
    parser.add_argument("--subaward-id", type=int)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--sync-postgres", action="store_true")
    parser.add_argument("--max-retries", type=int, default=4)
    parser.add_argument("--blob-chunk-size", type=int, default=1024 * 1024)
    parser.add_argument(
        "--sse",
        choices=("AES256", "aws:kms"),
        default=os.getenv("SUBAWARD_ATTACHMENT_SSE", "AES256"),
    )
    parser.add_argument(
        "--kms-key-id",
        default=os.getenv("SUBAWARD_ATTACHMENT_KMS_KEY_ID"),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.limit is not None and args.limit < 1:
        raise RuntimeError("--limit must be positive")
    if args.subaward_id is not None and args.subaward_id < 1:
        raise RuntimeError("--subaward-id must be positive")

    manifest = ManifestStore(args.manifest)
    if args.sync_postgres:
        try:
            synced = sync_manifest_to_postgres(
                manifest,
                args.subaward_id,
            )
        finally:
            manifest.close()
        if args.subaward_id == 94202 and synced != 10:
            raise RuntimeError(
                "Subaward 94202 synchronization expected 10 archived rows"
            )
        return

    if not args.metadata_csv.exists():
        raise FileNotFoundError(
            f"Attachment metadata CSV not found: {args.metadata_csv}"
        )
    if not args.s3_bucket:
        raise RuntimeError(
            "--s3-bucket or SUBAWARD_ATTACHMENT_S3_BUCKET is required"
        )

    reader = OracleBlobReader(args.max_retries, args.blob_chunk_size)
    s3_client = create_s3_client(args.aws_region, args.max_retries)
    counts = ArchiveCounts()

    try:
        for metadata in iter_attachment_metadata(
            args.metadata_csv,
            args.subaward_id,
            args.limit,
        ):
            counts.attachment_metadata_count += 1
            process_attachment(
                metadata,
                reader=reader,
                manifest=manifest,
                s3_client=s3_client,
                bucket=args.s3_bucket,
                prefix=args.s3_prefix,
                sse=args.sse,
                kms_key_id=args.kms_key_id,
                attempts=args.max_retries,
                verify_only=args.verify_only,
                counts=counts,
            )
            if counts.attachment_metadata_count % 100 == 0:
                logger.info(
                    "Processed {:,} attachment metadata rows",
                    counts.attachment_metadata_count,
                )

        counts.manifest_orphan_count = verify_manifest_orphans(
            args.metadata_csv,
            manifest,
            args.subaward_id,
            args.limit,
        )
    finally:
        reader.close()
        manifest.close()

    logger.info(
        "Attachment metadata count: {:,}",
        counts.attachment_metadata_count,
    )
    logger.info(
        "FILE_DATA match count: {:,}",
        counts.file_data_match_count,
    )
    logger.info("Uploaded count: {:,}", counts.uploaded_count)
    logger.info("Resumed count: {:,}", counts.resumed_count)
    logger.info("Missing BLOB count: {:,}", counts.missing_blob_count)
    logger.info("Failed upload count: {:,}", counts.failed_upload_count)
    logger.info(
        "Total archived bytes: {:,}",
        counts.total_archived_bytes,
    )
    logger.info(
        "Checksum mismatches: {:,}",
        counts.checksum_mismatch_count,
    )
    logger.info(
        "Manifest-to-attachment orphans: {:,}",
        counts.manifest_orphan_count,
    )

    if args.subaward_id == 94202:
        if counts.attachment_metadata_count != 10:
            raise RuntimeError(
                "Subaward 94202 validation expected 10 attachment rows"
            )
        if counts.file_data_match_count != 10:
            raise RuntimeError(
                "Subaward 94202 validation expected 10 FILE_DATA matches"
            )

    if (
        counts.missing_blob_count
        or counts.failed_upload_count
        or counts.checksum_mismatch_count
        or counts.manifest_orphan_count
    ):
        raise RuntimeError(
            "Subaward attachment archival completed with validation failures"
        )


if __name__ == "__main__":
    main()
