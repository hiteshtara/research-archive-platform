from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from sqlalchemy import text

from archive_etl.attachments.manifest import (
    FlexibleManifestStore,
    ManifestStore,
)
from archive_etl.attachments.models import (
    AttachmentRecord,
    sanitize_file_name,
    utc_now,
)
from archive_etl.attachments.oracle_blob import AttachmentFileBlobReader
from archive_etl.attachments.plugins.base import AttachmentPlugin
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


class AttachmentFilePlugin(AttachmentPlugin):
    file_reference_label = "FILE_ID"
    postgres_module_code: str
    source_identifier_fields: dict[str, str] = {}

    def create_manifest(self, path: Path) -> FlexibleManifestStore:
        return FlexibleManifestStore(path)

    def create_blob_reader(
        self,
        attempts: int,
        chunk_size: int,
    ) -> AttachmentFileBlobReader:
        return AttachmentFileBlobReader(attempts, chunk_size)

    def s3_key(
        self,
        prefix: str,
        record: AttachmentRecord,
    ) -> str:
        name = sanitize_file_name(
            record.original_file_name,
            record.attachment_id,
        )
        return "/".join(
            part
            for part in (
                prefix.strip("/"),
                str(record.record_id),
                str(record.attachment_id),
                name,
            )
            if part
        )

    def s3_object_metadata(
        self,
        record: AttachmentRecord,
        sha256: str,
    ) -> dict[str, str]:
        return {
            "sha256": sha256,
            "module": self.module_name,
            "attachment-id": str(record.attachment_id),
            "record-id": str(record.record_id),
            "file-id": record.file_data_id or "",
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
            "record_id": record.record_id,
            "file_reference": record.file_data_id,
            "original_file_name": record.original_file_name,
            "mime_type": record.mime_type,
            **record.attributes,
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
        for name in (
            "manifest_updated_at",
            "archived_timestamp",
            "archive_status",
            "error_message",
        ):
            expected.pop(name)
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
            INSERT INTO archive.archived_attachment (
                module_code,
                source_attachment_id,
                parent_record_id,
                business_key,
                sequence_number,
                source_file_id,
                document_id,
                original_file_name,
                content_type,
                description,
                source_update_timestamp,
                source_last_update_timestamp,
                s3_bucket,
                s3_key,
                byte_size,
                sha256,
                archive_status,
                archived_timestamp,
                error_message,
                source_metadata,
                manifest_updated_at
            )
            VALUES (
                :module_code,
                :source_attachment_id,
                :parent_record_id,
                :business_key,
                :sequence_number,
                :source_file_id,
                :document_id,
                :original_file_name,
                :content_type,
                :description,
                :source_update_timestamp,
                :source_last_update_timestamp,
                :s3_bucket,
                :s3_key,
                :byte_size,
                :sha256,
                :archive_status,
                :archived_timestamp,
                :error_message,
                CAST(:source_metadata AS JSONB),
                :manifest_updated_at
            )
            ON CONFLICT (module_code, source_attachment_id) DO UPDATE SET
                parent_record_id = EXCLUDED.parent_record_id,
                business_key = EXCLUDED.business_key,
                sequence_number = EXCLUDED.sequence_number,
                source_file_id = EXCLUDED.source_file_id,
                document_id = EXCLUDED.document_id,
                original_file_name = EXCLUDED.original_file_name,
                content_type = EXCLUDED.content_type,
                description = EXCLUDED.description,
                source_update_timestamp =
                    EXCLUDED.source_update_timestamp,
                source_last_update_timestamp =
                    EXCLUDED.source_last_update_timestamp,
                s3_bucket = EXCLUDED.s3_bucket,
                s3_key = EXCLUDED.s3_key,
                byte_size = EXCLUDED.byte_size,
                sha256 = EXCLUDED.sha256,
                archive_status = EXCLUDED.archive_status,
                archived_timestamp = EXCLUDED.archived_timestamp,
                error_message = EXCLUDED.error_message,
                source_metadata = EXCLUDED.source_metadata,
                manifest_updated_at = EXCLUDED.manifest_updated_at
            """
        )

        batch: list[dict[str, Any]] = []
        synced = 0
        with engine.begin() as connection:
            for row in manifest.rows(record_id):
                batch.append(self._postgres_values(row))
                if len(batch) == 500:
                    connection.execute(upsert_sql, batch)
                    synced += len(batch)
                    batch.clear()
            if batch:
                connection.execute(upsert_sql, batch)
                synced += len(batch)
        return synced

    def _postgres_values(self, row: dict[str, Any]) -> dict[str, Any]:
        common_fields = {
            "attachment_id",
            "record_id",
            "file_reference",
            "original_file_name",
            "mime_type",
            "business_key",
            "sequence_number",
            "document_id",
            "description",
            "title",
            "source_update_timestamp",
            "last_update_timestamp",
            "s3_bucket",
            "s3_key",
            "byte_size",
            "sha256",
            "archive_status",
            "archived_timestamp",
            "error_message",
            "manifest_updated_at",
        }
        source_metadata = {
            key: value
            for key, value in row.items()
            if key not in common_fields
        }
        if row.get("title") is not None:
            source_metadata["title"] = row["title"]
        source_metadata.update(
            {
                source_name: row.get(manifest_name)
                for source_name, manifest_name
                in self.source_identifier_fields.items()
            }
        )
        return {
            "module_code": self.postgres_module_code,
            "source_attachment_id": row["attachment_id"],
            "parent_record_id": row["record_id"],
            "business_key": row.get("business_key"),
            "sequence_number": row.get("sequence_number"),
            "source_file_id": row.get("file_reference"),
            "document_id": row.get("document_id"),
            "original_file_name": row.get("original_file_name"),
            "content_type": row.get("mime_type"),
            "description": row.get("description") or row.get("title"),
            "source_update_timestamp":
                row.get("source_update_timestamp"),
            "source_last_update_timestamp":
                row.get("last_update_timestamp"),
            "s3_bucket": row.get("s3_bucket"),
            "s3_key": row.get("s3_key"),
            "byte_size": row.get("byte_size"),
            "sha256": row.get("sha256"),
            "archive_status": row["archive_status"],
            "archived_timestamp": row.get("archived_timestamp"),
            "error_message": row.get("error_message"),
            "source_metadata": json.dumps(
                source_metadata,
                sort_keys=True,
            ),
            "manifest_updated_at": row["manifest_updated_at"],
        }
