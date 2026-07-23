from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Iterator

from archive_etl.attachments.models import AttachmentRecord
from archive_etl.attachments.plugins.attachment_file import (
    AttachmentFilePlugin,
)


class AwardAttachmentPlugin(AttachmentFilePlugin):
    module_name = "award"
    postgres_module_code = "AWARD"
    source_identifier_fields = {
        "award_attachment_id": "attachment_id",
        "award_id": "record_id",
        "award_number": "business_key",
        "sequence_number": "sequence_number",
        "document_id": "document_id",
        "file_id": "file_reference",
    }
    default_metadata_csv = Path.home() / "Downloads" / "award_attachments.csv"
    default_manifest = (
        Path(__file__).resolve().parents[3]
        / "exports"
        / "awards"
        / "award_attachment_manifest.sqlite3"
    )
    default_s3_prefix = "test/awards"
    bucket_environment_variable = "AWARD_ATTACHMENT_S3_BUCKET"
    prefix_environment_variable = "AWARD_ATTACHMENT_S3_PREFIX"
    sse_environment_variable = "AWARD_ATTACHMENT_SSE"
    kms_environment_variable = "AWARD_ATTACHMENT_KMS_KEY_ID"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--award-id", type=int)

    def selected_record_id(self, args: argparse.Namespace) -> int | None:
        return args.award_id

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        yield from _read_records(
            path,
            record_id,
            limit,
            module=self.module_name,
            id_column="award_id",
            attachment_column="award_attachment_id",
            business_key_column="award_number",
        )


def _read_records(
    path: Path,
    record_id: int | None,
    limit: int | None,
    *,
    module: str,
    id_column: str,
    attachment_column: str,
    business_key_column: str,
) -> Iterator[AttachmentRecord]:
    required = {
        attachment_column, id_column, business_key_column, "sequence_number",
        "file_id", "file_name", "content_type", "document_id", "description",
        "update_timestamp", "last_update_timestamp", "document_status_code",
    }
    emitted = 0
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None:
            raise RuntimeError(f"{path} has no CSV header")
        reader.fieldnames = [name.strip().lower() for name in reader.fieldnames]
        missing = sorted(required - set(reader.fieldnames))
        if missing:
            raise RuntimeError(
                f"{path.name} is missing columns: " + ", ".join(missing)
            )
        for row in reader:
            parent_id = int(row[id_column])
            if record_id is not None and parent_id != record_id:
                continue
            if limit is not None and emitted >= limit:
                break
            emitted += 1
            yield AttachmentRecord(
                module=module,
                record_id=parent_id,
                attachment_id=int(row[attachment_column]),
                file_data_id=row["file_id"].strip() or None,
                original_file_name=row["file_name"].strip() or None,
                mime_type=row["content_type"].strip() or None,
                attributes={
                    "business_key": row[business_key_column].strip(),
                    "sequence_number": int(row["sequence_number"]),
                    "document_id": row["document_id"].strip() or None,
                    "description": row["description"].strip() or None,
                    "source_update_timestamp":
                        row["update_timestamp"].strip() or None,
                    "last_update_timestamp":
                        row["last_update_timestamp"].strip() or None,
                    "document_status_code":
                        row["document_status_code"].strip() or None,
                    "attachment_file_data_id":
                        row.get("attachment_file_data_id", "").strip()
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
