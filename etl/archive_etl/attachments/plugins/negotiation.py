from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Iterator

from archive_etl.attachments.models import AttachmentRecord
from archive_etl.attachments.plugins.attachment_file import (
    AttachmentFilePlugin,
)


class NegotiationAttachmentPlugin(AttachmentFilePlugin):
    module_name = "negotiation"
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

    def iter_records(
        self,
        path: Path,
        record_id: int | None,
        limit: int | None,
    ) -> Iterator[AttachmentRecord]:
        required = {
            "attachment_id", "activity_id", "negotiation_id", "file_id",
            "file_name", "content_type", "description", "restricted",
            "update_timestamp",
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
                        "description": row["description"].strip() or None,
                        "restricted": row["restricted"].strip() or None,
                        "source_update_timestamp":
                            row["update_timestamp"].strip() or None,
                    },
                )
