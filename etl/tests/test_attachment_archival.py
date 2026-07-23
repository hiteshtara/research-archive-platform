from __future__ import annotations

import csv
import hashlib
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock, patch

from archive_etl.attachments.models import (
    ArchiveCounts,
    AttachmentRecord,
    MissingBlobError,
)
from archive_etl.attachments.plugins.subaward import (
    SubawardAttachmentPlugin,
    sanitize_file_name,
)
from archive_etl.attachments.runner import process_attachment, selected_plugin


class AttachmentArchivalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.plugin = SubawardAttachmentPlugin()
        self.record = AttachmentRecord(
            module="subaward",
            record_id=94202,
            attachment_id=500,
            file_data_id="FILE-500",
            original_file_name="../Signed Agreement.pdf",
            mime_type="application/pdf",
            attributes={
                "subaward_code": "SUB-1",
                "sequence_number": 3,
                "document_id": 700,
                "source_update_timestamp": "2026-01-01T10:00:00",
                "last_update_timestamp": "2026-01-02T10:00:00",
            },
        )

    def test_filename_sanitization(self) -> None:
        self.assertEqual(
            sanitize_file_name("../Résumé final?.pdf", 500),
            "R_sum__final_.pdf",
        )
        self.assertEqual(
            sanitize_file_name("", 500),
            "attachment-500.bin",
        )

    def test_subaward_s3_key_generation(self) -> None:
        self.assertEqual(
            self.plugin.s3_key("test/subawards/", self.record),
            "test/subawards/94202/500/Signed_Agreement.pdf",
        )

    def test_subaward_csv_metadata_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "subaward_attachments.csv"
            with path.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(
                    stream,
                    fieldnames=[
                        "attachment_id",
                        "subaward_id",
                        "subaward_code",
                        "sequence_number",
                        "file_data_id",
                        "file_name",
                        "mime_type",
                        "document_id",
                        "update_timestamp",
                        "last_update_timestamp",
                    ],
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "attachment_id": "500",
                        "subaward_id": "94202",
                        "subaward_code": "SUB-1",
                        "sequence_number": "3",
                        "file_data_id": "FILE-500",
                        "file_name": "Signed Agreement.pdf",
                        "mime_type": "application/pdf",
                        "document_id": "700",
                        "update_timestamp": "2026-01-01T10:00:00",
                        "last_update_timestamp": "2026-01-02T10:00:00",
                    }
                )

            records = list(
                self.plugin.iter_records(path, 94202, 10)
            )

        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertEqual(record.record_id, 94202)
        self.assertEqual(record.attachment_id, 500)
        self.assertEqual(record.file_data_id, "FILE-500")
        self.assertEqual(record.original_file_name, "Signed Agreement.pdf")
        self.assertEqual(record.mime_type, "application/pdf")
        self.assertEqual(record.attributes["subaward_code"], "SUB-1")
        self.assertEqual(record.attributes["sequence_number"], 3)
        self.assertEqual(record.attributes["document_id"], 700)

    @patch("archive_etl.attachments.runner.upload_object")
    @patch("archive_etl.attachments.runner.head_object")
    def test_matching_manifest_and_s3_resume_without_upload(
        self,
        head_object: Mock,
        upload_object: Mock,
    ) -> None:
        payload = b"archived attachment"
        digest = hashlib.sha256(payload).hexdigest()
        reader = self._reader(payload)
        manifest = Mock()
        manifest.get.return_value = self.plugin.manifest_values(
            self.record,
            "bucket",
            self.plugin.s3_key("subawards", self.record),
            byte_size=len(payload),
            sha256=digest,
            status="ARCHIVED",
            archived_timestamp="2026-01-03T10:00:00+00:00",
            error_message=None,
        )
        head_object.return_value = {
            "ContentLength": len(payload),
            "Metadata": {"sha256": digest},
        }
        counts = ArchiveCounts()

        process_attachment(
            self.record,
            plugin=self.plugin,
            reader=reader,
            manifest=manifest,
            s3_client=Mock(),
            bucket="bucket",
            prefix="subawards",
            sse="AES256",
            kms_key_id=None,
            attempts=1,
            verify_only=False,
            counts=counts,
        )

        self.assertEqual(counts.resumed_count, 1)
        self.assertEqual(counts.uploaded_count, 0)
        upload_object.assert_not_called()
        manifest.upsert.assert_not_called()

    def test_missing_blob_is_recorded_without_upload(self) -> None:
        reader = Mock()
        reader.stream_to_path.side_effect = MissingBlobError("missing")
        manifest = Mock()
        counts = ArchiveCounts()

        process_attachment(
            self.record,
            plugin=self.plugin,
            reader=reader,
            manifest=manifest,
            s3_client=Mock(),
            bucket="bucket",
            prefix="subawards",
            sse="AES256",
            kms_key_id=None,
            attempts=1,
            verify_only=False,
            counts=counts,
        )

        self.assertEqual(counts.missing_blob_count, 1)
        values = manifest.upsert.call_args.args[0]
        self.assertEqual(values["archive_status"], "MISSING")
        self.assertIsNone(values["sha256"])

    @patch("archive_etl.attachments.runner.head_object")
    def test_verify_only_reports_checksum_mismatch(
        self,
        head_object: Mock,
    ) -> None:
        payload = b"archived attachment"
        digest = hashlib.sha256(payload).hexdigest()
        reader = self._reader(payload)
        manifest = Mock()
        manifest.get.return_value = self.plugin.manifest_values(
            self.record,
            "bucket",
            self.plugin.s3_key("subawards", self.record),
            byte_size=len(payload),
            sha256=digest,
            status="ARCHIVED",
            archived_timestamp="2026-01-03T10:00:00+00:00",
            error_message=None,
        )
        head_object.return_value = {
            "ContentLength": len(payload),
            "Metadata": {"sha256": "incorrect"},
        }
        counts = ArchiveCounts()

        process_attachment(
            self.record,
            plugin=self.plugin,
            reader=reader,
            manifest=manifest,
            s3_client=Mock(),
            bucket="bucket",
            prefix="subawards",
            sse="AES256",
            kms_key_id=None,
            attempts=1,
            verify_only=True,
            counts=counts,
        )

        self.assertEqual(counts.checksum_mismatch_count, 1)
        self.assertEqual(counts.resumed_count, 0)
        manifest.upsert.assert_not_called()

    def test_supported_modules_are_registered(self) -> None:
        expected_modules = {
            "award",
            "proposal",
            "negotiation",
            "irb",
            "irb-personnel",
            "subaward",
        }
        for module in expected_modules:
            with self.subTest(module=module):
                self.assertEqual(
                    selected_plugin(["--module", module]).module_name,
                    module,
                )

    @patch(
        "archive_etl.attachments.plugins.subaward.apply_migrations"
    )
    @patch(
        "archive_etl.attachments.plugins.subaward.create_postgres_engine"
    )
    def test_subaward_postgres_sync_maps_archived_manifest_rows(
        self,
        create_engine: Mock,
        apply_migrations: Mock,
    ) -> None:
        row = self.plugin.manifest_values(
            self.record,
            "bucket",
            self.plugin.s3_key("subawards", self.record),
            byte_size=20,
            sha256="a" * 64,
            status="ARCHIVED",
            archived_timestamp="2026-01-03T10:00:00+00:00",
            error_message=None,
        )
        manifest = Mock()
        manifest.rows.return_value = [row]
        connection = Mock()
        engine = Mock()
        engine.begin.return_value = nullcontext(connection)
        create_engine.return_value = engine

        synced = self.plugin.sync_postgres(manifest, 94202)

        self.assertEqual(synced, 1)
        manifest.rows.assert_called_once_with(94202)
        apply_migrations.assert_called_once()
        parameters = connection.execute.call_args.args[1]
        self.assertEqual(parameters[0]["attachment_id"], 500)
        self.assertEqual(parameters[0]["subaward_id"], 94202)
        self.assertEqual(parameters[0]["file_data_id"], "FILE-500")
        self.assertEqual(parameters[0]["s3_bucket"], "bucket")
        self.assertEqual(
            parameters[0]["s3_key"],
            "subawards/94202/500/Signed_Agreement.pdf",
        )

    def _reader(self, payload: bytes) -> Mock:
        reader = Mock()

        def stream_to_path(
            _file_data_id: str,
            destination: Path,
        ) -> tuple[int, str]:
            destination.write_bytes(payload)
            return len(payload), hashlib.sha256(payload).hexdigest()

        reader.stream_to_path.side_effect = stream_to_path
        return reader


if __name__ == "__main__":
    unittest.main()
