from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Iterator

from archive_etl.attachments.models import AttachmentRecord
from archive_etl.attachments.oracle_blob import FileDataBlobReader
from archive_etl.attachments.plugins.attachment_file import (
    AttachmentFilePlugin,
)


class ProposalAttachmentPlugin(AttachmentFilePlugin):
    module_name = "proposal"
    postgres_module_code = "PROPOSAL"
    source_identifier_fields = {
        "proposal_attachments_id": "attachment_id",
        "proposal_id": "record_id",
        "proposal_number": "business_key",
        "sequence_number": "sequence_number",
        "attachment_number": "document_id",
        "file_data_id": "file_reference",
    }
    file_reference_label = "FILE_DATA_ID"
    default_metadata_csv = (
        Path.home() / "Downloads" / "proposal_attachments.csv"
    )
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "proposals"
        / "proposal_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "test/proposals"
    bucket_environment_variable = "PROPOSAL_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "PROPOSAL_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "PROPOSAL_ATTACHMENT_SSE"
    kms_environment_variable = "PROPOSAL_ATTACHMENT_KMS_KEY_ID"

    def create_blob_reader(
        self,
        attempts: int,
        chunk_size: int,
    ) -> FileDataBlobReader:
        return FileDataBlobReader(attempts, chunk_size)

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--proposal-id", type=int)

    def selected_record_id(self, args: argparse.Namespace) -> int | None:
        return args.proposal_id

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        required = {
            "proposal_attachments_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "attachment_number",
            "attachment_title",
            "file_name",
            "file_data_id",
            "content_type",
            "update_timestamp",
            "last_update_timestamp",
            "document_status_code",
        }
        emitted = 0
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames is None:
                raise RuntimeError(f"{path} has no CSV header")
            reader.fieldnames = [
                name.strip().lower() for name in reader.fieldnames
            ]
            missing = sorted(required - set(reader.fieldnames))
            if missing:
                raise RuntimeError(
                    f"{path.name} is missing columns: "
                    + ", ".join(missing)
                )
            for row in reader:
                proposal_id = int(row["proposal_id"])
                if record_id is not None and proposal_id != record_id:
                    continue
                if limit is not None and emitted >= limit:
                    break
                emitted += 1
                yield AttachmentRecord(
                    module=self.module_name,
                    record_id=proposal_id,
                    attachment_id=int(row["proposal_attachments_id"]),
                    file_data_id=row["file_data_id"].strip() or None,
                    original_file_name=row["file_name"].strip() or None,
                    mime_type=row["content_type"].strip() or None,
                    attributes={
                        "business_key": row["proposal_number"].strip(),
                        "sequence_number": int(row["sequence_number"]),
                        "document_id":
                            row["attachment_number"].strip() or None,
                        "title": row["attachment_title"].strip() or None,
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                        "last_update_timestamp":
                            row["last_update_timestamp"].strip() or None,
                        "document_status_code":
                            row["document_status_code"].strip() or None,
                    },
                )
