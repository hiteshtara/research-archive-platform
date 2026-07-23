from __future__ import annotations

import hashlib
import contextlib
import io
import unittest
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
from archive_etl.attachments.runner import process_attachment
from archive_etl.attachments.runner import selected_plugin


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

    def test_unconfirmed_modules_are_rejected_before_execution(self) -> None:
        for module in ("award", "proposal", "negotiation", "irb"):
            with self.subTest(module=module):
                error_output = io.StringIO()
                with contextlib.redirect_stderr(error_output):
                    with self.assertRaises(SystemExit) as raised:
                        selected_plugin(["--module", module])
                self.assertEqual(raised.exception.code, 2)
                self.assertIn(
                    f"module '{module}' is unavailable",
                    error_output.getvalue(),
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
