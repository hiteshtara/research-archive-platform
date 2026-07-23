from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
import re
from typing import Any
import unicodedata


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
        candidate = (
            f"{Path(candidate).stem[:180 - len(suffix)]}{suffix}"
        )
    return candidate
