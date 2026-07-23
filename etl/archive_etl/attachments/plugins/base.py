from __future__ import annotations

import argparse
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Iterator

from archive_etl.attachments.manifest import ManifestStore
from archive_etl.attachments.models import ArchiveCounts, AttachmentRecord
from archive_etl.attachments.oracle_blob import FileDataBlobReader


class AttachmentPlugin(ABC):
    module_name: str
    default_metadata_csv: Path
    default_manifest: Path
    default_s3_prefix: str
    bucket_environment_variable: str
    prefix_environment_variable: str
    sse_environment_variable: str
    kms_environment_variable: str
    file_reference_label = "FILE_DATA_ID"

    def create_manifest(self, path: Path) -> ManifestStore:
        return ManifestStore(path)

    def create_blob_reader(
        self,
        attempts: int,
        chunk_size: int,
    ) -> FileDataBlobReader:
        return FileDataBlobReader(attempts, chunk_size)

    @abstractmethod
    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        pass

    @abstractmethod
    def selected_record_id(
        self,
        args: argparse.Namespace,
    ) -> int | None:
        pass

    @abstractmethod
    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        pass

    def record_id(self, record: AttachmentRecord) -> int:
        return record.record_id

    def attachment_id(self, record: AttachmentRecord) -> int:
        return record.attachment_id

    def file_data_id(self, record: AttachmentRecord) -> str | None:
        return record.file_data_id

    @abstractmethod
    def s3_key(
        self,
        prefix: str,
        record: AttachmentRecord,
    ) -> str:
        pass

    @abstractmethod
    def s3_object_metadata(
        self,
        record: AttachmentRecord,
        sha256: str,
    ) -> dict[str, str]:
        pass

    @abstractmethod
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
        pass

    @abstractmethod
    def manifest_matches(
        self,
        manifest: dict[str, Any] | None,
        record: AttachmentRecord,
        bucket: str,
        key: str,
        byte_size: int,
        sha256: str,
    ) -> bool:
        pass

    @abstractmethod
    def sync_postgres(
        self,
        manifest: ManifestStore,
        record_id: int | None,
    ) -> int:
        pass

    def validate_counts(
        self,
        record_id: int | None,
        counts: ArchiveCounts,
    ) -> None:
        del record_id, counts

    def validate_sync_count(
        self,
        record_id: int | None,
        synced: int,
    ) -> None:
        del record_id, synced
