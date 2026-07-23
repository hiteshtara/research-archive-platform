from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Iterator

from archive_etl.attachments.models import AttachmentRecord
from archive_etl.attachments.plugins.attachment_file import (
    AttachmentFilePlugin,
)


class IrbProtocolAttachmentPlugin(AttachmentFilePlugin):
    module_name = "irb"
    postgres_module_code = "IRB_PROTOCOL"
    source_identifier_fields = {
        "pa_protocol_id": "attachment_id",
        "protocol_id_fk": "record_id",
        "protocol_number": "business_key",
        "sequence_number": "sequence_number",
        "document_id": "document_id",
        "file_id": "file_reference",
        "attachment_version": "attachment_version",
    }
    default_metadata_csv = (
        Path.home() / "Downloads" / "irb_protocol_attachments.csv"
    )
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "irb"
        / "irb_protocol_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "test/irb"
    bucket_environment_variable = "IRB_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "IRB_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "IRB_ATTACHMENT_SSE"
    kms_environment_variable = "IRB_ATTACHMENT_KMS_KEY_ID"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--protocol-id", type=int)

    def selected_record_id(self, args: argparse.Namespace) -> int | None:
        return args.protocol_id

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        required = {
            "pa_protocol_id", "protocol_id_fk", "protocol_number",
            "sequence_number", "document_id", "file_id", "file_name",
            "content_type", "description", "status_cd", "update_timestamp",
            "create_timestamp", "attachment_version",
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
                protocol_id = int(row["protocol_id_fk"])
                if record_id is not None and protocol_id != record_id:
                    continue
                if limit is not None and emitted >= limit:
                    break
                emitted += 1
                yield AttachmentRecord(
                    module=self.module_name,
                    record_id=protocol_id,
                    attachment_id=int(row["pa_protocol_id"]),
                    file_data_id=row["file_id"].strip() or None,
                    original_file_name=row["file_name"].strip() or None,
                    mime_type=row["content_type"].strip() or None,
                    attributes={
                        "business_key": row["protocol_number"].strip(),
                        "sequence_number": int(row["sequence_number"]),
                        "document_id": row["document_id"].strip() or None,
                        "description": row["description"].strip() or None,
                        "status": row["status_cd"].strip() or None,
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                        "created_timestamp":
                            row["create_timestamp"].strip() or None,
                        "attachment_version":
                            row["attachment_version"].strip() or None,
                        "document_status_code":
                            row["document_status_code"].strip() or None,
                        "attachment_file_data_id": _optional(
                            row,
                            "attachment_file_data_id",
                        ),
                        "attachment_file_sequence_number": _optional(
                            row,
                            "attachment_file_sequence_number",
                        ),
                        "attachment_file_update_timestamp": _optional(
                            row,
                            "attachment_file_update_timestamp",
                        ),
                    },
                )


class IrbPersonnelAttachmentPlugin(AttachmentFilePlugin):
    module_name = "irb-personnel"
    postgres_module_code = "IRB_PERSONNEL"
    source_identifier_fields = {
        "pa_personnel_id": "attachment_id",
        "protocol_id_fk": "record_id",
        "protocol_number": "business_key",
        "sequence_number": "sequence_number",
        "document_id": "document_id",
        "person_id": "person_id",
        "file_id": "file_reference",
    }
    default_metadata_csv = (
        Path.home() / "Downloads" / "irb_personnel_attachments.csv"
    )
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "irb"
        / "irb_personnel_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "test/irb/personnel"
    bucket_environment_variable = "IRB_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "IRB_PERSONNEL_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "IRB_ATTACHMENT_SSE"
    kms_environment_variable = "IRB_ATTACHMENT_KMS_KEY_ID"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--protocol-id", type=int)

    def selected_record_id(self, args: argparse.Namespace) -> int | None:
        return args.protocol_id

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        required = {
            "pa_personnel_id",
            "protocol_id_fk",
            "protocol_number",
            "sequence_number",
            "type_cd",
            "document_id",
            "file_id",
            "description",
            "person_id",
            "update_timestamp",
            "file_name",
            "content_type",
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
                protocol_id = int(row["protocol_id_fk"])
                if record_id is not None and protocol_id != record_id:
                    continue
                if limit is not None and emitted >= limit:
                    break
                emitted += 1
                yield AttachmentRecord(
                    module=self.module_name,
                    record_id=protocol_id,
                    attachment_id=int(row["pa_personnel_id"]),
                    file_data_id=row["file_id"].strip() or None,
                    original_file_name=row["file_name"].strip() or None,
                    mime_type=row["content_type"].strip() or None,
                    attributes={
                        "business_key": row["protocol_number"].strip(),
                        "sequence_number": int(row["sequence_number"]),
                        "document_id": row["document_id"].strip() or None,
                        "description": row["description"].strip() or None,
                        "type_code": row["type_cd"].strip() or None,
                        "person_id": row["person_id"].strip() or None,
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                        "attachment_file_data_id": _optional(
                            row,
                            "attachment_file_data_id",
                        ),
                        "attachment_file_sequence_number": _optional(
                            row,
                            "attachment_file_sequence_number",
                        ),
                        "attachment_file_update_timestamp": _optional(
                            row,
                            "attachment_file_update_timestamp",
                        ),
                    },
                )


def _optional(row: dict[str, str], name: str) -> str | None:
    return row.get(name, "").strip() or None
