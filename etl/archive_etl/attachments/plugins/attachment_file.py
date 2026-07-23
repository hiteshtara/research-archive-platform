from __future__ import annotations

from pathlib import Path
from typing import Any

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


class AttachmentFilePlugin(AttachmentPlugin):
    file_reference_label = "FILE_ID"

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
        del manifest, record_id
        raise RuntimeError(
            f"{self.module_name.title()} PostgreSQL synchronization is "
            "unavailable: no module-specific attachment archive table "
            "exists"
        )
