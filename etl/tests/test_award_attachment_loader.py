from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import load_award_attachments as attachment_loader


class PrepareReferencesTest(unittest.TestCase):
    def _base_row(self, **overrides: object) -> dict:
        row = {
            "award_attachment_id": 1,
            "award_id": 101,
            "award_number": "000001",
            "sequence_number": 0,
            "document_id": "D-1",
            "file_id": 9001,
            "type_code": "AGR",
            "description": "Agreement",
            "document_status_code": "A",
            "oracle_update_timestamp": "2025-01-02 03:04:05",
            "oracle_update_user": "kcuser",
        }
        row.update(overrides)
        return row

    def test_accepts_valid_references(self) -> None:
        dataframe = pd.DataFrame([self._base_row()])

        prepared = attachment_loader.prepare_references(dataframe)

        self.assertEqual(prepared["award_attachment_id"].tolist(), [1])
        self.assertEqual(prepared["file_id"].tolist(), [9001])

    def test_rejects_duplicate_award_attachment_id(self) -> None:
        dataframe = pd.DataFrame(
            [
                self._base_row(award_attachment_id=1),
                self._base_row(award_attachment_id=1, file_id=9002),
            ]
        )

        with self.assertRaises(RuntimeError):
            attachment_loader.prepare_references(dataframe)

    def test_rejects_missing_required_values(self) -> None:
        dataframe = pd.DataFrame([self._base_row(award_number=None)])

        with self.assertRaises(RuntimeError):
            attachment_loader.prepare_references(dataframe)

    def test_allows_null_file_id(self) -> None:
        # A reference with no resolvable file has not been observed but is
        # not assumed impossible either - see the migration's column
        # comment. file_id must not be treated as a required value.
        dataframe = pd.DataFrame([self._base_row(file_id=None)])

        prepared = attachment_loader.prepare_references(dataframe)

        self.assertTrue(pd.isna(prepared["file_id"].iloc[0]))

    def test_raises_when_required_column_missing(self) -> None:
        dataframe = pd.DataFrame([self._base_row()]).drop(columns=["award_id"])

        with self.assertRaises(RuntimeError):
            attachment_loader.prepare_references(dataframe)


class PrepareFilesTest(unittest.TestCase):
    def _base_row(self, **overrides: object) -> dict:
        row: dict[str, object] = {
            "file_id": 9001,
            "file_data_id": None,
            "file_name": "agreement.pdf",
            "content_type": "application/pdf",
            "blob_source": "INLINE",
            "file_size_bytes": 12345,
            "oracle_update_timestamp": "2025-01-02 03:04:05",
            "oracle_update_user": "kcuser",
        }
        row.update(overrides)
        return row

    def test_inline_file_is_marked_pending(self) -> None:
        dataframe = pd.DataFrame([self._base_row(blob_source="INLINE")])

        prepared = attachment_loader.prepare_files(dataframe)

        self.assertEqual(prepared["upload_status"].tolist(), ["PENDING"])

    def test_external_file_is_marked_pending(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(blob_source="EXTERNAL", file_data_id=555)]
        )

        prepared = attachment_loader.prepare_files(dataframe)

        self.assertEqual(prepared["upload_status"].tolist(), ["PENDING"])

    def test_missing_blob_is_marked_skipped_and_warns(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(blob_source=None, file_size_bytes=None)]
        )

        with patch.object(attachment_loader.logger, "warning") as warning:
            prepared = attachment_loader.prepare_files(dataframe)

        self.assertEqual(prepared["upload_status"].tolist(), ["SKIPPED"])
        warning.assert_called_once()

    def test_sprint_1_never_populates_blob_upload_fields(self) -> None:
        dataframe = pd.DataFrame([self._base_row()])

        prepared = attachment_loader.prepare_files(dataframe)

        for column in ("sha256", "s3_bucket", "s3_key", "s3_etag", "uploaded_at"):
            self.assertIsNone(prepared[column].iloc[0])
        self.assertEqual(prepared["upload_attempts"].iloc[0], 0)

    def test_rejects_duplicate_file_id(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(file_id=9001), self._base_row(file_id=9001)]
        )

        with self.assertRaises(RuntimeError):
            attachment_loader.prepare_files(dataframe)

    def test_requires_file_id(self) -> None:
        dataframe = pd.DataFrame([self._base_row(file_id=None)])

        with self.assertRaises(RuntimeError):
            attachment_loader.prepare_files(dataframe)


class BuildValidationReportTest(unittest.TestCase):
    def test_computes_all_five_reconciliation_metrics(self) -> None:
        references = pd.DataFrame(
            [{"award_attachment_id": 1}, {"award_attachment_id": 2}]
        )
        files = pd.DataFrame(
            [
                {"file_id": 1, "blob_source": "INLINE"},
                {"file_id": 2, "blob_source": "EXTERNAL"},
                {"file_id": 3, "blob_source": "EXTERNAL"},
                {"file_id": 4, "blob_source": None},
            ]
        )

        report = attachment_loader.build_validation_report(references, files)

        self.assertEqual(
            report,
            {
                "award_references_read": 2,
                "physical_files_read": 4,
                "inline_blob_count": 1,
                "external_blob_count": 2,
                "missing_blob_count": 1,
            },
        )


class ClearExistingAwardAttachmentDataTest(unittest.TestCase):
    def test_truncates_both_tables_children_first(self) -> None:
        connection = MagicMock()

        attachment_loader.clear_existing_award_attachment_data(connection)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.award_attachment", statement)
        self.assertIn("archive.attachment_object", statement)
        self.assertIn("TRUNCATE", statement)
        # award_attachment (child, FK to attachment_object) must be listed
        # before attachment_object (parent) in the TRUNCATE statement.
        self.assertLess(
            statement.index("archive.award_attachment"),
            statement.index("archive.attachment_object"),
        )


class MarkLoadFailedRedactsErrorsTest(unittest.TestCase):
    def test_redacts_password_before_persisting(self) -> None:
        connection = MagicMock()
        engine = MagicMock()
        engine.begin.return_value.__enter__.return_value = connection

        attachment_loader.mark_load_failed(
            engine, load_id=1, error_message="connect failed: password=hunter2"
        )

        _, params = connection.execute.call_args.args
        self.assertNotIn("hunter2", params["error_message"])
        self.assertIn("[REDACTED]", params["error_message"])


class VerifyLoadedDataTest(unittest.TestCase):
    def test_raises_on_row_count_mismatch(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = 5

        with self.assertRaises(RuntimeError):
            attachment_loader.verify_loaded_data(
                connection, {"attachment_object": 10}
            )

    def test_raises_on_orphan_award_attachment_rows(self) -> None:
        connection = MagicMock()
        # Row-count checks pass (matching), then the orphan-reference check
        # returns a nonzero count.
        connection.execute.return_value.scalar_one.side_effect = [1, 1, 3]

        with self.assertRaises(RuntimeError):
            attachment_loader.verify_loaded_data(
                connection,
                {"attachment_object": 1, "award_attachment": 1},
            )


def _oracle_source_stub(dataframe: pd.DataFrame) -> MagicMock:
    return MagicMock(read=MagicMock(return_value=dataframe))


class DryRunIsReadOnlyTest(unittest.TestCase):
    def _references(self) -> pd.DataFrame:
        return pd.DataFrame(
            [
                {
                    "award_attachment_id": 1,
                    "award_id": 101,
                    "award_number": "000001",
                    "sequence_number": 0,
                    "document_id": "D-1",
                    "file_id": 9001,
                    "type_code": "AGR",
                    "description": "Agreement",
                    "document_status_code": "A",
                    "oracle_update_timestamp": "2025-01-02 03:04:05",
                    "oracle_update_user": "kcuser",
                }
            ]
        )

    def _files(self) -> pd.DataFrame:
        return pd.DataFrame(
            [
                {
                    "file_id": 9001,
                    "file_data_id": None,
                    "file_name": "agreement.pdf",
                    "content_type": "application/pdf",
                    "blob_source": "INLINE",
                    "file_size_bytes": 12345,
                    "oracle_update_timestamp": "2025-01-02 03:04:05",
                    "oracle_update_user": "kcuser",
                }
            ]
        )

    def test_dry_run_never_creates_a_postgres_engine(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[
                    _oracle_source_stub(self._references()),
                    _oracle_source_stub(self._files()),
                ],
            ),
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
            patch.object(attachment_loader, "apply_migrations") as apply_migrations,
        ):
            parse_args.return_value = MagicMock(limit=None, dry_run=True)
            attachment_loader.main()

        create_engine.assert_not_called()
        apply_migrations.assert_not_called()

    def test_dry_run_with_limit_never_creates_a_postgres_engine(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[
                    _oracle_source_stub(self._references()),
                    _oracle_source_stub(self._files()),
                ],
            ),
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
        ):
            parse_args.return_value = MagicMock(limit=10, dry_run=True)
            attachment_loader.main()

        create_engine.assert_not_called()

    def test_non_dry_run_without_limit_does_write(self) -> None:
        # Sanity check on the flip side: a real (non-dry-run) invocation
        # does reach create_postgres_engine - dry-run is the one and only
        # thing gating the write, not --limit.
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[
                    _oracle_source_stub(self._references()),
                    _oracle_source_stub(self._files()),
                ],
            ),
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
            patch.object(attachment_loader, "apply_migrations"),
            patch.object(attachment_loader, "create_load_run", return_value=1),
            patch.object(attachment_loader, "clear_existing_award_attachment_data"),
            patch.object(
                attachment_loader,
                "load_dataframe",
                side_effect=lambda connection, dataframe, table_name, columns, load_id: len(
                    dataframe
                ),
            ),
            patch.object(attachment_loader, "verify_loaded_data"),
            patch.object(attachment_loader, "mark_load_complete") as mark_complete,
        ):
            parse_args.return_value = MagicMock(limit=None, dry_run=False)
            create_engine.return_value = MagicMock()
            attachment_loader.main()

        create_engine.assert_called_once()
        mark_complete.assert_called_once()


class OracleExtractionSqlFilesExistTest(unittest.TestCase):
    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        for sql_path in (
            attachment_loader.REFERENCES_ORACLE_SQL,
            attachment_loader.FILES_ORACLE_SQL,
        ):
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
