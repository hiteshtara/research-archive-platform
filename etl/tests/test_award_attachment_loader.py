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


def _oracle_batches_stub(batches: list[pd.DataFrame]) -> MagicMock:
    """An OracleDataSource-shaped mock whose read_batches() yields the
    given DataFrames lazily via a real generator (not iter(list)), so it
    has a .close() method matching OracleDataSource.read_batches()'s
    actual return type, and so tests can assert on how many were
    consumed."""

    def _generator():
        yield from batches

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    return stub


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
                    _oracle_batches_stub([self._references()]),
                    _oracle_batches_stub([self._files()]),
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

    def test_dry_run_with_limit_never_imports_or_uses_an_s3_client(self) -> None:
        # There is no S3 client anywhere in this module this sprint - the
        # module itself has no boto3/S3 dependency to mock, so the
        # strongest proof available is that this name is entirely absent.
        self.assertNotIn("boto3", dir(attachment_loader))
        self.assertFalse(
            hasattr(attachment_loader, "create_s3_client"),
            "Sprint 1 must not introduce any S3 client - blob upload is a "
            "later sprint",
        )

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


class ReadBoundedReferencesTest(unittest.TestCase):
    def test_stops_reading_once_the_limit_is_reached(self) -> None:
        produced = {"batches": 0}

        def fake_batches():
            for i in range(1000):
                produced["batches"] += 1
                yield pd.DataFrame(
                    [
                        {
                            "award_attachment_id": i,
                            "award_id": 100 + i,
                            "award_number": f"{i:06d}",
                            "sequence_number": 0,
                            "file_id": i,
                        }
                    ]
                )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_bounded_references(source, 5)

        self.assertEqual(len(result), 5)
        # The whole point of this helper: it must never exhaust a
        # 1,000-batch generator (i.e. the full 1.8M-row Oracle table) just
        # to satisfy a --limit of 5.
        self.assertLess(produced["batches"], 1000)


class ReadFilesMatchingIdsTest(unittest.TestCase):
    def test_stops_once_every_target_id_is_found(self) -> None:
        produced = {"batches": 0}

        def fake_batches():
            for file_id in range(1000):
                produced["batches"] += 1
                yield pd.DataFrame(
                    [{"file_id": file_id, "blob_source": "INLINE"}]
                )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_files_matching_ids(source, {3, 7})

        self.assertEqual(sorted(result["file_id"].tolist()), [3, 7])
        # Both targets appear well before batch 1000 - the scan must not
        # exhaust the full (138,538-row) physical-file source looking for
        # them.
        self.assertLess(produced["batches"], 1000)

    def test_reports_unresolved_ids_when_source_is_exhausted(self) -> None:
        def fake_batches():
            for file_id in range(5):
                yield pd.DataFrame(
                    [{"file_id": file_id, "blob_source": "INLINE"}]
                )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        # 999 is never present in the source at all.
        result = attachment_loader.read_files_matching_ids(source, {2, 999})

        self.assertEqual(result["file_id"].tolist(), [2])

    def test_duplicate_reference_file_ids_collapse_to_one_target(self) -> None:
        # Sampled references pointing at the same physical file must not
        # cause that file to be searched for (or returned) more than once.
        target_ids = {9001}

        def fake_batches():
            yield pd.DataFrame(
                [{"file_id": 9001, "blob_source": "INLINE"}]
            )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_files_matching_ids(source, target_ids)

        self.assertEqual(len(result), 1)

    def test_returns_empty_dataframe_for_no_targets(self) -> None:
        source = MagicMock()

        result = attachment_loader.read_files_matching_ids(source, set())

        self.assertTrue(result.empty)
        source.read_batches.assert_not_called()


class CoherentSampleTest(unittest.TestCase):
    def test_duplicate_reference_file_ids_produce_one_physical_file_row(
        self,
    ) -> None:
        # Two references share file_id=9001 - the coherent sample must
        # still only produce one attachment_object-bound row for it.
        references = pd.DataFrame(
            [
                {
                    "award_attachment_id": 1,
                    "award_id": 101,
                    "award_number": "000001",
                    "sequence_number": 0,
                    "file_id": 9001,
                },
                {
                    "award_attachment_id": 2,
                    "award_id": 102,
                    "award_number": "000002",
                    "sequence_number": 0,
                    "file_id": 9001,
                },
            ]
        )
        files = pd.DataFrame(
            [
                {
                    "file_id": 9001,
                    "file_data_id": None,
                    "blob_source": "INLINE",
                }
            ]
        )

        with patch.object(
            attachment_loader,
            "OracleDataSource",
            side_effect=[
                _oracle_batches_stub([references]),
                _oracle_batches_stub([files]),
            ],
        ):
            sampled_references, sampled_files, report = (
                attachment_loader._read_coherent_sample(10)
            )

        self.assertEqual(len(sampled_references), 2)
        self.assertEqual(len(sampled_files), 1)
        self.assertEqual(report["sampled_reference_count"], 2)
        self.assertEqual(report["distinct_sampled_file_id_count"], 1)
        self.assertEqual(report["matched_physical_file_count"], 1)
        self.assertEqual(report["unresolved_file_id_count"], 0)

    def test_unresolved_file_ids_are_reported_not_silently_dropped(self) -> None:
        references = pd.DataFrame(
            [
                {
                    "award_attachment_id": 1,
                    "award_id": 101,
                    "award_number": "000001",
                    "sequence_number": 0,
                    "file_id": 9001,
                },
                {
                    "award_attachment_id": 2,
                    "award_id": 102,
                    "award_number": "000002",
                    "sequence_number": 0,
                    "file_id": 9002,
                },
            ]
        )
        # Only 9001 is ever found in the (mocked) physical-file source -
        # 9002 does not exist there.
        files = pd.DataFrame(
            [{"file_id": 9001, "file_data_id": None, "blob_source": "INLINE"}]
        )

        with patch.object(
            attachment_loader,
            "OracleDataSource",
            side_effect=[
                _oracle_batches_stub([references]),
                _oracle_batches_stub([files]),
            ],
        ):
            _, sampled_files, report = attachment_loader._read_coherent_sample(10)

        self.assertEqual(report["distinct_sampled_file_id_count"], 2)
        self.assertEqual(report["matched_physical_file_count"], 1)
        self.assertEqual(report["unresolved_file_id_count"], 1)
        self.assertEqual(sampled_files["file_id"].tolist(), [9001])


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
