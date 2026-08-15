"""Tests for the Negotiation external-BLOB correction: NegotiationAttachmentPlugin
must resolve a physical file from whichever of ATTACHMENT_FILE.FILE_DATA
(INLINE) or FILE_DATA.DATA via FILE_DATA_ID (EXTERNAL) actually has
content - see docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md's
"External-BLOB correction" section and oracle_blob.py's
InlineOrExternalBlobReader docstring for the full incident this fixes
(26,572 of 28,923 Negotiation attachments were archived as MISSING
despite having real, retrievable EXTERNAL content).

CSV-parsing/reference-encoding tests run with no I/O beyond a temp CSV
file. process_attachment() integration tests use a Mock reader (same
pattern as test_attachment_archival.py) - real Oracle BLOB resolution
itself is covered separately by test_oracle_blob_readers.py.
"""

from __future__ import annotations

import csv
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from archive_etl.attachments.models import ArchiveCounts, MissingBlobError
from archive_etl.attachments.plugins.negotiation import (
    NegotiationAttachmentPlugin,
)
from archive_etl.attachments.runner import process_attachment

FIXTURE_UUID = "995577d2-b20f-4b10-a4aa-5bc0d32f64b4"

CSV_FIELDNAMES = [
    "attachment_id",
    "activity_id",
    "negotiation_id",
    "document_number",
    "associated_document_id",
    "file_id",
    "file_data_id",
    "file_name",
    "content_type",
    "blob_source",
    "file_size_bytes",
    "description",
    "restricted",
    "update_timestamp",
    "update_user",
]


def _row(**overrides: object) -> dict:
    row = {
        "attachment_id": "29373",
        "activity_id": "33279",
        "negotiation_id": "12788",
        "document_number": "DOC-1",
        "associated_document_id": "",
        "file_id": "164229",
        "file_data_id": "",
        "file_name": "Hua_MMRRC_MTA_New submission from Incoming MTA Form.msg",
        "content_type": "application/octet-stream",
        "blob_source": "",
        "file_size_bytes": "",
        "description": "MTA submission",
        "restricted": "Y",
        "update_timestamp": "2025-07-28T15:09:23",
        "update_user": "kcuser",
    }
    row.update({k: str(v) for k, v in overrides.items()})
    return row


def _write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=CSV_FIELDNAMES)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


class NegotiationIterRecordsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.plugin = NegotiationAttachmentPlugin()

    def _records(self, rows: list[dict]) -> list:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "negotiation_attachments.csv"
            _write_csv(path, rows)
            return list(self.plugin.iter_records(path, None, None))

    def test_inline_row_parses_with_no_external_reference_needed(
        self,
    ) -> None:
        records = self._records(
            [_row(blob_source="INLINE", restricted="N")]
        )
        record = records[0]
        self.assertEqual(record.attributes["blob_source"], "INLINE")
        self.assertEqual(record.attributes["restricted"], "N")
        self.assertEqual(
            self.plugin.file_data_id(record), "INLINE:164229"
        )

    def test_external_row_preserves_uuid_unchanged(self) -> None:
        records = self._records(
            [
                _row(
                    blob_source="EXTERNAL",
                    file_data_id=FIXTURE_UUID,
                    restricted="N",
                )
            ]
        )
        record = records[0]
        self.assertEqual(
            record.attributes["attachment_file_data_id"], FIXTURE_UUID
        )
        self.assertEqual(
            self.plugin.file_data_id(record), f"EXTERNAL:{FIXTURE_UUID}"
        )

    def test_external_restricted_row_preserves_uuid_unchanged(self) -> None:
        records = self._records(
            [
                _row(
                    blob_source="EXTERNAL",
                    file_data_id=FIXTURE_UUID,
                    restricted="Y",
                )
            ]
        )
        record = records[0]
        self.assertEqual(record.attributes["restricted"], "Y")
        self.assertEqual(
            self.plugin.file_data_id(record), f"EXTERNAL:{FIXTURE_UUID}"
        )

    def test_genuinely_missing_row_has_no_reader_reference(self) -> None:
        for restricted in ("N", "Y"):
            with self.subTest(restricted=restricted):
                records = self._records(
                    [_row(blob_source="", file_data_id="", restricted=restricted)]
                )
                record = records[0]
                self.assertIsNone(self.plugin.file_data_id(record))

    def test_inline_takes_precedence_when_both_pointers_present(
        self,
    ) -> None:
        # Oracle's own CASE only ever emits blob_source='INLINE' when
        # ATTACHMENT_FILE.FILE_DATA is non-null, even if FILE_DATA_ID is
        # also populated - this proves the Python side honors that
        # classification (uses FILE_ID, not the also-present UUID).
        records = self._records(
            [_row(blob_source="INLINE", file_data_id=FIXTURE_UUID)]
        )
        record = records[0]
        self.assertEqual(
            self.plugin.file_data_id(record), "INLINE:164229"
        )

    def test_restricted_never_affects_which_rows_are_yielded(self) -> None:
        records = self._records(
            [
                _row(
                    attachment_id="1",
                    restricted="Y",
                    blob_source="EXTERNAL",
                    file_data_id=FIXTURE_UUID,
                ),
                _row(
                    attachment_id="2",
                    restricted="N",
                    blob_source="EXTERNAL",
                    file_data_id=FIXTURE_UUID,
                ),
            ]
        )
        self.assertEqual(len(records), 2)
        self.assertEqual(
            {r.attachment_id for r in records}, {1, 2}
        )

    def test_missing_required_column_raises(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "negotiation_attachments.csv"
            with path.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(
                    stream,
                    fieldnames=[
                        c for c in CSV_FIELDNAMES if c != "blob_source"
                    ],
                )
                writer.writeheader()
                row = _row()
                del row["blob_source"]
                writer.writerow(row)
            with self.assertRaises(RuntimeError):
                list(self.plugin.iter_records(path, None, None))


class NegotiationProcessAttachmentTest(unittest.TestCase):
    """Integration coverage of runner.process_attachment() driving the
    real NegotiationAttachmentPlugin (file_data_id()/manifest_values()),
    with a Mock reader standing in for real Oracle I/O - mirrors
    test_attachment_archival.py's own pattern for the other modules."""

    def setUp(self) -> None:
        self.plugin = NegotiationAttachmentPlugin()

    def _record(self, **row_overrides: object):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "negotiation_attachments.csv"
            _write_csv(path, [_row(**row_overrides)])
            return list(self.plugin.iter_records(path, None, None))[0]

    def _reader(self, payload: bytes) -> Mock:
        reader = Mock()

        def stream_to_path(reference: str, destination: Path):
            destination.write_bytes(payload)
            return len(payload), hashlib.sha256(payload).hexdigest()

        reader.stream_to_path.side_effect = stream_to_path
        return reader

    def _run(
        self,
        record,
        reader,
        *,
        manifest=None,
        head_object_effects: list | None = None,
    ):
        """head_object_effects models what S3 HeadObject would actually
        return on each successive call - process_attachment() always
        calls it once before deciding whether to resume, and (only if it
        proceeds to upload) once again afterward to verify. Letting the
        real checksum_matches() run against these values (rather than
        mocking checksum_matches itself) is what makes the resume-vs-
        upload branching in these tests trustworthy."""
        manifest = manifest or Mock()
        if manifest.get.return_value is None:
            manifest.get.return_value = None
        counts = ArchiveCounts()
        with patch(
            "archive_etl.attachments.runner.head_object",
            side_effect=head_object_effects or [None],
        ), patch(
            "archive_etl.attachments.runner.upload_object"
        ) as upload_object:
            process_attachment(
                record,
                plugin=self.plugin,
                reader=reader,
                manifest=manifest,
                s3_client=Mock(),
                bucket="bucket",
                prefix="negotiations",
                sse="AES256",
                kms_key_id=None,
                attempts=1,
                verify_only=False,
                counts=counts,
            )
        return counts, manifest, upload_object

    def _upload_head_effects(self, payload: bytes) -> list:
        digest = hashlib.sha256(payload).hexdigest()
        return [
            None,
            {
                "ContentLength": len(payload),
                "Metadata": {"sha256": digest},
            },
        ]

    def test_inline_n_attachment_archives(self) -> None:
        record = self._record(blob_source="INLINE", restricted="N")
        payload = b"inline content"
        counts, manifest, upload_object = self._run(
            record,
            self._reader(payload),
            head_object_effects=self._upload_head_effects(payload),
        )
        self.assertEqual(counts.uploaded_count, 1)
        self.assertEqual(counts.missing_blob_count, 0)
        upload_object.assert_called_once()
        status = manifest.upsert.call_args[0][0]["archive_status"]
        self.assertEqual(status, "ARCHIVED")

    def test_external_n_attachment_archives(self) -> None:
        record = self._record(
            blob_source="EXTERNAL", file_data_id=FIXTURE_UUID, restricted="N"
        )
        payload = b"external content"
        counts, manifest, upload_object = self._run(
            record,
            self._reader(payload),
            head_object_effects=self._upload_head_effects(payload),
        )
        self.assertEqual(counts.uploaded_count, 1)
        upload_object.assert_called_once()
        self.assertEqual(
            manifest.upsert.call_args[0][0]["archive_status"], "ARCHIVED"
        )

    def test_external_y_attachment_archives_identically_to_n(self) -> None:
        # RESTRICTED is informational only - Y and N attachments are
        # processed identically by the loader; authorization is enforced
        # entirely at the API layer (ArchiveAttachmentViewer), never here.
        record = self._record(
            blob_source="EXTERNAL", file_data_id=FIXTURE_UUID, restricted="Y"
        )
        payload = b"external restricted content"
        counts, manifest, upload_object = self._run(
            record,
            self._reader(payload),
            head_object_effects=self._upload_head_effects(payload),
        )
        self.assertEqual(counts.uploaded_count, 1)
        upload_object.assert_called_once()
        values = manifest.upsert.call_args[0][0]
        self.assertEqual(values["archive_status"], "ARCHIVED")
        self.assertEqual(values["restricted"], "Y")

    def test_genuinely_missing_n_is_marked_missing_without_upload(
        self,
    ) -> None:
        record = self._record(
            blob_source="", file_data_id="", restricted="N"
        )
        reader = Mock()
        counts, manifest, upload_object = self._run(record, reader)
        self.assertEqual(counts.missing_blob_count, 1)
        self.assertEqual(counts.uploaded_count, 0)
        upload_object.assert_not_called()
        reader.stream_to_path.assert_not_called()
        self.assertEqual(
            manifest.upsert.call_args[0][0]["archive_status"], "MISSING"
        )

    def test_genuinely_missing_y_is_marked_missing_without_upload(
        self,
    ) -> None:
        record = self._record(
            blob_source="", file_data_id="", restricted="Y"
        )
        reader = Mock()
        counts, manifest, upload_object = self._run(record, reader)
        self.assertEqual(counts.missing_blob_count, 1)
        upload_object.assert_not_called()
        reader.stream_to_path.assert_not_called()
        values = manifest.upsert.call_args[0][0]
        self.assertEqual(values["archive_status"], "MISSING")
        self.assertEqual(values["restricted"], "Y")

    def test_missing_blob_error_from_reader_marks_missing(self) -> None:
        # Distinct from the "no reference at all" case above - here the
        # metadata claimed EXTERNAL, but the actual Oracle row/BLOB
        # turned out to be null at read time (a genuine race/
        # inconsistency, not a classification bug).
        record = self._record(
            blob_source="EXTERNAL", file_data_id=FIXTURE_UUID
        )
        reader = Mock()
        reader.stream_to_path.side_effect = MissingBlobError("gone")
        counts, manifest, upload_object = self._run(record, reader)
        self.assertEqual(counts.missing_blob_count, 1)
        upload_object.assert_not_called()
        self.assertEqual(
            manifest.upsert.call_args[0][0]["archive_status"], "MISSING"
        )

    def test_already_archived_matching_manifest_and_s3_skips_reupload(
        self,
    ) -> None:
        record = self._record(
            blob_source="EXTERNAL", file_data_id=FIXTURE_UUID
        )
        payload = b"already archived content"
        digest = hashlib.sha256(payload).hexdigest()
        manifest = Mock()
        manifest.get.return_value = self.plugin.manifest_values(
            record,
            "bucket",
            self.plugin.s3_key("negotiations", record),
            byte_size=len(payload),
            sha256=digest,
            status="ARCHIVED",
            archived_timestamp="2026-01-03T10:00:00+00:00",
            error_message=None,
        )
        counts, _manifest, upload_object = self._run(
            record,
            self._reader(payload),
            manifest=manifest,
            head_object_effects=[
                {
                    "ContentLength": len(payload),
                    "Metadata": {"sha256": digest},
                }
            ],
        )
        self.assertEqual(counts.resumed_count, 1)
        self.assertEqual(counts.uploaded_count, 0)
        upload_object.assert_not_called()
        manifest.upsert.assert_not_called()

    def test_idempotent_rerun_after_real_upload_also_skips_reupload(
        self,
    ) -> None:
        # First run: nothing archived yet -> uploads.
        record = self._record(
            blob_source="EXTERNAL", file_data_id=FIXTURE_UUID
        )
        payload = b"idempotency check content"
        digest = hashlib.sha256(payload).hexdigest()
        manifest = Mock()
        manifest.get.return_value = None
        first_counts, _m, first_upload = self._run(
            record,
            self._reader(payload),
            manifest=manifest,
            head_object_effects=self._upload_head_effects(payload),
        )
        self.assertEqual(first_counts.uploaded_count, 1)
        first_upload.assert_called_once()

        # Second run: manifest/S3 now reflect that upload -> no re-upload.
        manifest.get.return_value = self.plugin.manifest_values(
            record,
            "bucket",
            self.plugin.s3_key("negotiations", record),
            byte_size=len(payload),
            sha256=digest,
            status="ARCHIVED",
            archived_timestamp="2026-01-03T10:00:00+00:00",
            error_message=None,
        )
        second_counts, _m2, second_upload = self._run(
            record,
            self._reader(payload),
            manifest=manifest,
            head_object_effects=[
                {
                    "ContentLength": len(payload),
                    "Metadata": {"sha256": digest},
                }
            ],
        )
        self.assertEqual(second_counts.resumed_count, 1)
        self.assertEqual(second_counts.uploaded_count, 0)
        second_upload.assert_not_called()


if __name__ == "__main__":
    unittest.main()
