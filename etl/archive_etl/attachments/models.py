from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any


class MissingBlobError(RuntimeError):
    """Raised when Oracle FILE_DATA has no matching BLOB."""


@dataclass(frozen=True)
class AttachmentRecord:
    module: str
    record_id: int
    attachment_id: int
    file_data_id: str | None
    original_file_name: str | None
    mime_type: str | None
    attributes: dict[str, Any] = field(default_factory=dict)


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
