from __future__ import annotations

import csv
import json
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock, patch

from archive_etl.attachments.oracle_blob import FileDataBlobReader
from archive_etl.attachments.plugins.award import AwardAttachmentPlugin
from archive_etl.attachments.plugins.irb import (
    IrbPersonnelAttachmentPlugin,
)
from archive_etl.attachments.plugins.proposal import (
    ProposalAttachmentPlugin,
)


class ArchivedAttachmentDestinationTest(unittest.TestCase):
    def test_proposal_maps_verified_file_data_contract(self) -> None:
        plugin = ProposalAttachmentPlugin()
        row = {
            "proposal_attachments_id": "81",
            "proposal_id": "91",
            "proposal_number": "0000091",
            "sequence_number": "2",
            "attachment_number": "7",
            "attachment_title": "Statement of work",
            "file_name": "scope.pdf",
            "file_data_id": "FD-81",
            "content_type": "application/pdf",
            "update_timestamp": "2026-01-01 10:00:00",
            "last_update_timestamp": "2026-01-02 10:00:00",
            "document_status_code": "1",
        }
        record = self._single_record(plugin, row)

        self.assertIsInstance(plugin.create_blob_reader(1, 1024),
                              FileDataBlobReader)
        self.assertEqual(record.attachment_id, 81)
        self.assertEqual(record.record_id, 91)
        self.assertEqual(record.file_data_id, "FD-81")
        self.assertEqual(record.attributes["business_key"], "0000091")
        self.assertEqual(record.attributes["title"], "Statement of work")
        self.assertEqual(
            plugin.s3_key("test/proposals", record),
            "test/proposals/91/81/scope.pdf",
        )

    def test_irb_personnel_preserves_verified_identifiers(self) -> None:
        plugin = IrbPersonnelAttachmentPlugin()
        row = {
            "pa_personnel_id": "71",
            "protocol_id_fk": "61",
            "protocol_number": "0000061",
            "sequence_number": "3",
            "type_cd": "2",
            "document_id": "51",
            "file_id": "FILE-71",
            "description": "CV",
            "person_id": "P0001",
            "update_timestamp": "2026-01-01 10:00:00",
            "file_name": "cv.docx",
            "content_type":
                "application/vnd.openxmlformats-officedocument."
                "wordprocessingml.document",
            "attachment_file_data_id": "LEGACY-71",
            "attachment_file_sequence_number": "4",
            "attachment_file_update_timestamp":
                "2026-01-02 10:00:00",
        }
        record = self._single_record(plugin, row)

        self.assertEqual(record.attachment_id, 71)
        self.assertEqual(record.record_id, 61)
        self.assertEqual(record.file_data_id, "FILE-71")
        self.assertEqual(record.attributes["person_id"], "P0001")
        self.assertEqual(record.attributes["type_code"], "2")
        self.assertEqual(
            record.attributes["attachment_file_data_id"],
            "LEGACY-71",
        )

    @patch(
        "archive_etl.attachments.plugins.attachment_file.apply_migrations"
    )
    @patch(
        "archive_etl.attachments.plugins.attachment_file."
        "create_postgres_engine"
    )
    def test_generic_sync_uses_composite_upsert_and_json_metadata(
        self,
        create_engine: Mock,
        apply_migrations: Mock,
    ) -> None:
        plugin = AwardAttachmentPlugin()
        manifest = Mock()
        manifest.rows.return_value = [
            {
                "attachment_id": 11,
                "record_id": 22,
                "file_reference": "FILE-11",
                "original_file_name": "award.pdf",
                "mime_type": "application/pdf",
                "business_key": "000022",
                "sequence_number": 5,
                "document_id": "DOC-11",
                "description": "Award document",
                "source_update_timestamp": "2026-01-01 10:00:00",
                "last_update_timestamp": "2026-01-02 10:00:00",
                "document_status_code": "1",
                "s3_bucket": "documents",
                "s3_key": "test/awards/22/11/award.pdf",
                "byte_size": 100,
                "sha256": "a" * 64,
                "archive_status": "ARCHIVED",
                "archived_timestamp": "2026-01-03T10:00:00+00:00",
                "error_message": None,
                "manifest_updated_at": "2026-01-03T10:00:00+00:00",
            }
        ]
        connection = Mock()
        engine = Mock()
        engine.begin.return_value = nullcontext(connection)
        create_engine.return_value = engine

        synced = plugin.sync_postgres(manifest, 22)

        self.assertEqual(synced, 1)
        apply_migrations.assert_called_once()
        statement, parameters = connection.execute.call_args.args
        self.assertIn(
            "ON CONFLICT (module_code, source_attachment_id)",
            str(statement),
        )
        values = parameters[0]
        self.assertEqual(values["module_code"], "AWARD")
        self.assertEqual(values["source_attachment_id"], 11)
        self.assertEqual(values["parent_record_id"], 22)
        self.assertEqual(values["source_file_id"], "FILE-11")
        self.assertEqual(
            json.loads(values["source_metadata"]),
            {
                "award_attachment_id": 11,
                "award_id": 22,
                "award_number": "000022",
                "document_id": "DOC-11",
                "document_status_code": "1",
                "file_id": "FILE-11",
                "sequence_number": 5,
            },
        )

    def test_migration_defines_generic_contract(self) -> None:
        migration = (
            Path(__file__).resolve().parents[2]
            / "database"
            / "migrations"
            / "V020__create_archived_attachment.sql"
        ).read_text(encoding="utf-8")

        self.assertIn("archive.archived_attachment", migration)
        self.assertIn("source_metadata", migration)
        self.assertIn("JSONB", migration)
        self.assertIn(
            "UNIQUE (module_code, source_attachment_id)",
            migration,
        )
        self.assertIn("'IRB_PERSONNEL'", migration)

    def _single_record(self, plugin, row):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "attachments.csv"
            with path.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(row))
                writer.writeheader()
                writer.writerow(row)
            return next(plugin.iter_records(path, None, None))


if __name__ == "__main__":
    unittest.main()
