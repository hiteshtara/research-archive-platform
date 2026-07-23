from __future__ import annotations

import argparse
import csv
import re
import unicodedata
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator

from dateutil.parser import parse as parse_datetime
from loguru import logger
from sqlalchemy import text

from archive_etl.attachments.manifest import ManifestStore
from archive_etl.attachments.models import (
    ArchiveCounts,
    AttachmentRecord,
    utc_now,
)
from archive_etl.attachments.plugins.base import AttachmentPlugin
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


def sanitize_file_name(
    file_name: str | None,
    attachment_id: int,
) -> str:
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


def optional_int(value: str | None) -> int | None:
    if value is None or not value.strip():
        return None
    return int(value)


def parse_manifest_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return parse_datetime(value)


class SubawardAttachmentPlugin(AttachmentPlugin):
    module_name = "subaward"
    default_metadata_csv = (
        Path.home() / "Downloads" / "subaward_attachments.csv"
    )
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "subawards"
        / "subaward_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "subawards"
    bucket_environment_variable = "SUBAWARD_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "SUBAWARD_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "SUBAWARD_ATTACHMENT_SSE"
    kms_environment_variable = "SUBAWARD_ATTACHMENT_KMS_KEY_ID"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--subaward-id", type=int)

    def selected_record_id(
        self,
        args: argparse.Namespace,
    ) -> int | None:
        return args.subaward_id

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
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
                    f"{path.name} is missing columns: "
                    + ", ".join(missing)
                )

            for row in reader:
                subaward_id = int(row["subaward_id"])
                if record_id is not None and subaward_id != record_id:
                    continue
                if limit is not None and emitted >= limit:
                    break

                emitted += 1
                yield AttachmentRecord(
                    module=self.module_name,
                    record_id=subaward_id,
                    attachment_id=int(row["attachment_id"]),
                    file_data_id=row["file_data_id"].strip() or None,
                    original_file_name=row["file_name"].strip() or None,
                    mime_type=row["mime_type"].strip() or None,
                    attributes={
                        "subaward_code": row["subaward_code"],
                        "sequence_number": int(row["sequence_number"]),
                        "document_id": optional_int(row["document_id"]),
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                        "last_update_timestamp":
                            row["last_update_timestamp"].strip() or None,
                    },
                )

    def record_id(self, record: AttachmentRecord) -> int:
        return record.record_id

    def attachment_id(self, record: AttachmentRecord) -> int:
        return record.attachment_id

    def file_data_id(self, record: AttachmentRecord) -> str | None:
        return record.file_data_id

    def s3_key(
        self,
        prefix: str,
        record: AttachmentRecord,
    ) -> str:
        clean_prefix = prefix.strip("/")
        name = sanitize_file_name(
            record.original_file_name,
            record.attachment_id,
        )
        parts = [
            clean_prefix,
            str(record.record_id),
            str(record.attachment_id),
            name,
        ]
        return "/".join(part for part in parts if part)

    def s3_object_metadata(
        self,
        record: AttachmentRecord,
        sha256: str,
    ) -> dict[str, str]:
        return {
            "sha256": sha256,
            "attachment-id": str(record.attachment_id),
            "subaward-id": str(record.record_id),
            "file-data-id": record.file_data_id or "",
        }

    def manifest_values(
        self,
        record: AttachmentRecord,
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
            "attachment_id": record.attachment_id,
            "subaward_id": record.record_id,
            "subaward_code": record.attributes["subaward_code"],
            "sequence_number": record.attributes["sequence_number"],
            "file_data_id": record.file_data_id,
            "original_file_name": record.original_file_name,
            "mime_type": record.mime_type,
            "document_id": record.attributes["document_id"],
            "attachment_source_update_timestamp":
                record.attributes["source_update_timestamp"],
            "attachment_last_update_timestamp":
                record.attributes["last_update_timestamp"],
            "s3_bucket": bucket,
            "s3_key": key,
            "byte_size": byte_size,
            "sha256": sha256,
            "archive_status": status,
            "archived_timestamp": archived_timestamp,
            "error_message": error_message,
            "manifest_updated_at": utc_now(),
        }

    def manifest_matches(
        self,
        manifest: dict[str, Any] | None,
        record: AttachmentRecord,
        bucket: str,
        key: str,
        byte_size: int,
        sha256: str,
    ) -> bool:
        if not manifest or manifest.get("archive_status") != "ARCHIVED":
            return False

        expected = self.manifest_values(
            record,
            bucket,
            key,
            byte_size=byte_size,
            sha256=sha256,
            status="ARCHIVED",
            archived_timestamp=manifest.get("archived_timestamp"),
            error_message=None,
        )
        expected.pop("manifest_updated_at")
        expected.pop("archived_timestamp")
        expected.pop("archive_status")
        expected.pop("error_message")
        return all(
            manifest.get(name) == value
            for name, value in expected.items()
        )

    def sync_postgres(
        self,
        manifest: ManifestStore,
        record_id: int | None,
    ) -> int:
        engine = create_postgres_engine()
        apply_migrations(
            engine,
            Path(__file__).resolve().parents[4]
            / "database"
            / "migrations",
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
            for row in manifest.rows(record_id):
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

        logger.info(
            "Synchronized {:,} manifest rows to PostgreSQL",
            synced,
        )
        return synced

    def validate_counts(
        self,
        record_id: int | None,
        counts: ArchiveCounts,
    ) -> None:
        if record_id == 94202:
            if counts.attachment_metadata_count != 10:
                raise RuntimeError(
                    "Subaward 94202 validation expected 10 attachment rows"
                )
            if counts.file_data_match_count != 10:
                raise RuntimeError(
                    "Subaward 94202 validation expected 10 FILE_DATA matches"
                )

    def validate_sync_count(
        self,
        record_id: int | None,
        synced: int,
    ) -> None:
        if record_id == 94202 and synced != 10:
            raise RuntimeError(
                "Subaward 94202 synchronization expected 10 archived rows"
            )
