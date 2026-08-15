from __future__ import annotations

import argparse
import csv
from collections.abc import Iterator
from pathlib import Path

from archive_etl.attachments.models import AttachmentRecord
from archive_etl.attachments.oracle_blob import (
    InlineOrExternalBlobReader,
    OracleBlobReader,
)
from archive_etl.attachments.plugins.attachment_file import (
    AttachmentFilePlugin,
)


class NegotiationAttachmentPlugin(AttachmentFilePlugin):
    module_name = "negotiation"
    postgres_module_code = "NEGOTIATION"
    # Overrides AttachmentFilePlugin's "FILE_ID" - a Negotiation row can
    # have a FILE_ID and still be genuinely missing (neither
    # ATTACHMENT_FILE.FILE_DATA nor FILE_DATA.DATA has content), so
    # "FILE_ID is missing" would be misleading here.
    file_reference_label = "INLINE/EXTERNAL blob"
    source_identifier_fields = {
        "attachment_id": "attachment_id",
        "activity_id": "parent_activity_id",
        "negotiation_id": "record_id",
        "document_number": "document_number",
        "associated_document_id": "associated_document_id",
        "file_id": "file_reference",
    }
    default_metadata_csv = (
        Path.home() / "Downloads" / "negotiation_attachments.csv"
    )
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "negotiations"
        / "negotiation_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "test/negotiations"
    bucket_environment_variable = "NEGOTIATION_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "NEGOTIATION_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "NEGOTIATION_ATTACHMENT_SSE"
    kms_environment_variable = "NEGOTIATION_ATTACHMENT_KMS_KEY_ID"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--negotiation-id", type=int)

    def selected_record_id(self, args: argparse.Namespace) -> int | None:
        return args.negotiation_id

    def create_blob_reader(
        self,
        attempts: int,
        chunk_size: int,
    ) -> OracleBlobReader:
        # Negotiation attachments are stored inline (ATTACHMENT_FILE.
        # FILE_DATA) for some rows and externally (FILE_DATA.DATA, via
        # FILE_DATA_ID) for others - decided per physical file, not per
        # module - so this overrides AttachmentFilePlugin's inherited
        # inline-only default. See file_data_id() below for how each
        # record's reference is built; see oracle_blob.py's
        # InlineOrExternalBlobReader docstring for the full rationale.
        return InlineOrExternalBlobReader(attempts, chunk_size)

    def file_data_id(self, record: AttachmentRecord) -> str | None:
        # Overrides AttachmentPlugin.file_data_id (base.py), which
        # process_attachment() calls to get the reference it passes to
        # the blob reader - deliberately NOT the same value as
        # record.file_data_id itself, which stays the plain
        # ATTACHMENT_FILE.FILE_ID for manifest_values()/Postgres
        # source_file_id (record.file_data_id is read directly there,
        # bypassing this method - see attachment_file.py's
        # manifest_values). This only changes what the READER receives.
        return InlineOrExternalBlobReader.encode_reference(
            record.attributes.get("blob_source"),
            file_id=record.file_data_id,
            file_data_id=record.attributes.get("attachment_file_data_id"),
        )

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        required = {
            "attachment_id", "activity_id", "negotiation_id", "file_id",
            "file_data_id", "file_name", "content_type", "blob_source",
            "description", "restricted", "update_timestamp",
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
                negotiation_id = int(row["negotiation_id"])
                if record_id is not None and negotiation_id != record_id:
                    continue
                if limit is not None and emitted >= limit:
                    break
                emitted += 1
                yield AttachmentRecord(
                    module=self.module_name,
                    record_id=negotiation_id,
                    attachment_id=int(row["attachment_id"]),
                    file_data_id=row["file_id"].strip() or None,
                    original_file_name=row["file_name"].strip() or None,
                    mime_type=row["content_type"].strip() or None,
                    attributes={
                        "parent_activity_id": int(row["activity_id"]),
                        "business_key":
                            row.get("document_number", "").strip()
                            or row.get(
                                "associated_document_id",
                                "",
                            ).strip()
                            or None,
                        "document_number":
                            row.get("document_number", "").strip()
                            or None,
                        "associated_document_id":
                            row.get(
                                "associated_document_id",
                                "",
                            ).strip()
                            or None,
                        "description": row["description"].strip() or None,
                        "restricted": row["restricted"].strip() or None,
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                        "source_update_user":
                            row.get("update_user", "").strip() or None,
                        # The real KCOEUS.ATTACHMENT_FILE.FILE_DATA_ID
                        # (a UUID string - never int()-coerced), needed
                        # by file_data_id() above to resolve an
                        # EXTERNAL-sourced physical file via FILE_DATA.
                        # Attribute name kept as "attachment_file_data_id"
                        # (not renamed to "file_data_id") to avoid
                        # colliding with AttachmentRecord's own
                        # file_data_id field, which carries FILE_ID here,
                        # not FILE_DATA_ID.
                        "attachment_file_data_id":
                            row.get("file_data_id", "").strip() or None,
                        "blob_source":
                            row.get("blob_source", "").strip().upper()
                            or None,
                        "file_size_bytes":
                            row.get("file_size_bytes", "").strip()
                            or None,
                        "attachment_file_sequence_number":
                            row.get(
                                "attachment_file_sequence_number",
                                "",
                            ).strip()
                            or None,
                        "attachment_file_update_timestamp":
                            row.get(
                                "attachment_file_update_timestamp",
                                "",
                            ).strip()
                            or None,
                    },
                )
