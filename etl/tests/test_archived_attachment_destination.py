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
from archive_etl.attachments.plugins.proposal import (
    ProposalAttachmentPlugin,
    _resolve_migrations_directory,
)


class ArchivedAttachmentDestinationTest(unittest.TestCase):
    def test_proposal_maps_verified_file_data_contract(self) -> None:
        # archive.proposal_attachment is a dedicated table (not the
        # shared archive.archived_attachment AttachmentFilePlugin
        # destination) - the CSV here mirrors
        # export_proposal_attachments_csv.py's own column names, which
        # in turn mirror archive.proposal_attachment's real columns.
        plugin = ProposalAttachmentPlugin()
        row = {
            "proposal_attachment_id": "81",
            "proposal_id": "91",
            "proposal_number": "0000091",
            "sequence_number": "2",
            "attachment_number": "7",
            "attachment_title": "Statement of work",
            "file_name": "scope.pdf",
            "file_data_id": "FD-81",
            "content_type": "application/pdf",
            "comments": "Reviewed by sponsor",
            "document_status_code": "A",
            "source_update_timestamp": "2026-01-01 10:00:00",
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
            plugin.s3_key("proposal", record),
            "proposal/0000091/2/81/scope.pdf",
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


class ProposalAttachmentMigrationsDirectoryTest(unittest.TestCase):
    # Regression test for the same class of bug fixed in
    # load_proposals_from_csv.py's own _resolve_project_root(): a
    # hardcoded Path(__file__).resolve().parents[N] resolves correctly
    # in a local checkout but silently breaks inside the ECS loader
    # image, where archive_etl/ and database/migrations/ are copied
    # flatly under /app instead of nested under etl/. Caught live via
    # an ECS --sync-postgres run that raised FileNotFoundError for
    # "/database/migrations".
    def test_resolves_to_a_directory_containing_v060(self) -> None:
        migrations_directory = _resolve_migrations_directory()
        self.assertTrue(migrations_directory.is_dir())
        matches = list(
            migrations_directory.glob("V060__create_proposal_attachment.sql")
        )
        self.assertEqual(len(matches), 1)


class ProposalAttachmentPluginSyncTest(unittest.TestCase):
    # Unlike Award (INSERT into the shared archive.archived_attachment
    # table), Proposal's sync_postgres UPDATEs the lifecycle columns
    # already present on archive.proposal_attachment in place - the
    # metadata row is written earlier by
    # load_proposals_from_csv.py's upsert_proposal_attachments.
    @patch("archive_etl.attachments.plugins.proposal.apply_migrations")
    @patch(
        "archive_etl.attachments.plugins.proposal.create_postgres_engine"
    )
    def test_sync_maps_runner_status_vocabulary_onto_upload_status(
        self,
        create_engine: Mock,
        apply_migrations: Mock,
    ) -> None:
        plugin = ProposalAttachmentPlugin()
        manifest = Mock()
        manifest.rows.return_value = [
            {
                "attachment_id": 81,
                "proposal_id": 91,
                "s3_bucket": "documents",
                "s3_key": "proposal/0000091/2/81/scope.pdf",
                "byte_size": 100,
                "sha256": "a" * 64,
                "archive_status": "ARCHIVED",
                "archived_timestamp": "2026-01-03T10:00:00+00:00",
                "error_message": None,
            },
            {
                "attachment_id": 82,
                "proposal_id": 91,
                "s3_bucket": None,
                "s3_key": "proposal/0000091/2/82/missing.pdf",
                "byte_size": None,
                "sha256": None,
                "archive_status": "MISSING",
                "archived_timestamp": None,
                "error_message": "FILE_DATA_ID is missing",
            },
        ]
        connection = Mock()
        engine = Mock()
        engine.begin.return_value = nullcontext(connection)
        create_engine.return_value = engine

        synced = plugin.sync_postgres(manifest, 91)

        self.assertEqual(synced, 2)
        apply_migrations.assert_called_once()
        statement, batch = connection.execute.call_args.args
        self.assertIn("UPDATE archive.proposal_attachment", str(statement))
        self.assertIn(
            "WHERE proposal_attachment_id = :proposal_attachment_id",
            str(statement),
        )
        by_id = {row["proposal_attachment_id"]: row for row in batch}
        self.assertEqual(by_id[81]["upload_status"], "UPLOADED")
        self.assertEqual(by_id[81]["checksum"], "a" * 64)
        self.assertEqual(by_id[82]["upload_status"], "MISSING_SOURCE")
        self.assertEqual(by_id[82]["error_message"], "FILE_DATA_ID is missing")

    def test_validate_counts_enforces_the_five_attachment_fixture(
        self,
    ) -> None:
        from archive_etl.attachments.models import ArchiveCounts

        plugin = ProposalAttachmentPlugin()
        counts = ArchiveCounts(
            attachment_metadata_count=4,
            file_data_match_count=4,
        )
        with self.assertRaises(RuntimeError):
            plugin.validate_counts(1238613, counts)

        counts.attachment_metadata_count = 5
        counts.file_data_match_count = 5
        plugin.validate_counts(1238613, counts)

    def test_validate_sync_count_enforces_the_five_attachment_fixture(
        self,
    ) -> None:
        plugin = ProposalAttachmentPlugin()
        with self.assertRaises(RuntimeError):
            plugin.validate_sync_count(1238613, 4)
        plugin.validate_sync_count(1238613, 5)


if __name__ == "__main__":
    unittest.main()
