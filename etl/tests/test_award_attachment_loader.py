from __future__ import annotations

import hashlib
import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import load_award_attachments as attachment_loader
from archive_etl.config.startup_validation import StartupValidationError


class FakeBlob:
    """Mirrors tests/test_oracle_blob_readers.py's FakeBlob - a 1-indexed
    Oracle LOB-shaped object whose read(offset, size) slices a payload."""

    def __init__(self, payload: bytes) -> None:
        self.payload = payload

    def read(self, offset: int, size: int) -> bytes:
        start = offset - 1
        return self.payload[start : start + size]


class FakeBlobCursor:
    def __init__(self, payload: bytes | None) -> None:
        self.payload = payload
        self.sql = ""
        self.parameters: dict[str, object] = {}

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        pass

    def execute(self, sql: str, **parameters) -> None:
        self.sql = sql
        self.parameters = parameters

    def fetchone(self):
        if self.payload is None:
            return None
        return (FakeBlob(self.payload),)


class FakeBlobConnection:
    def __init__(self, cursor: FakeBlobCursor) -> None:
        self.test_cursor = cursor

    def cursor(self) -> FakeBlobCursor:
        return self.test_cursor


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

    def test_missing_blob_is_marked_missing_source_content_and_warns(
        self,
    ) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(blob_source=None, file_size_bytes=None)]
        )

        with patch.object(attachment_loader.logger, "warning") as warning:
            prepared = attachment_loader.prepare_files(dataframe)

        self.assertEqual(
            prepared["upload_status"].tolist(), ["MISSING_SOURCE_CONTENT"]
        )
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
            parse_args.return_value = MagicMock(
                limit=None,
                dry_run=True,
                upload=False,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
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
            parse_args.return_value = MagicMock(
                limit=10,
                dry_run=True,
                upload=False,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
            attachment_loader.main()

        create_engine.assert_not_called()

    def test_metadata_mode_never_creates_an_s3_client(self) -> None:
        # Sprint 2 introduces upload capability, but --upload gates it
        # entirely - the ordinary metadata read/--limit/--dry-run path
        # (arguments.upload is False) must never touch S3 at all.
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
            patch.object(attachment_loader, "create_postgres_engine"),
            patch.object(
                attachment_loader, "create_s3_client"
            ) as create_s3_client,
        ):
            parse_args.return_value = MagicMock(
                limit=10,
                dry_run=True,
                upload=False,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
            attachment_loader.main()

        create_s3_client.assert_not_called()

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
            parse_args.return_value = MagicMock(
                limit=None,
                dry_run=False,
                upload=False,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
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


class ReadReferencesMatchingFileIdsTest(unittest.TestCase):
    def test_collects_every_matching_reference_row_not_just_the_first(
        self,
    ) -> None:
        # Unlike read_files_matching_ids, file_id is NOT unique on the
        # reference source - the same physical file is legitimately
        # referenced by many award_attachment rows. An early stop after
        # the first match would silently drop the later ones.
        def fake_batches():
            yield pd.DataFrame(
                [{"award_attachment_id": 1, "file_id": 9001}]
            )
            yield pd.DataFrame(
                [{"award_attachment_id": 2, "file_id": 42}]
            )
            yield pd.DataFrame(
                [{"award_attachment_id": 3, "file_id": 9001}]
            )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_references_matching_file_ids(
            source, {9001}
        )

        self.assertEqual(
            sorted(result["award_attachment_id"].tolist()), [1, 3]
        )

    def test_never_stops_early_even_after_every_target_has_a_match(
        self,
    ) -> None:
        produced = {"batches": 0}

        def fake_batches():
            for i in range(50):
                produced["batches"] += 1
                yield pd.DataFrame(
                    [{"award_attachment_id": i, "file_id": 1}]
                )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_references_matching_file_ids(
            source, {1}
        )

        self.assertEqual(produced["batches"], 50)
        self.assertEqual(len(result), 50)

    def test_excludes_non_matching_rows(self) -> None:
        def fake_batches():
            yield pd.DataFrame(
                [
                    {"award_attachment_id": 1, "file_id": 1},
                    {"award_attachment_id": 2, "file_id": 2},
                ]
            )

        source = MagicMock()
        source.read_batches.side_effect = lambda: fake_batches()

        result = attachment_loader.read_references_matching_file_ids(
            source, {1}
        )

        self.assertEqual(result["award_attachment_id"].tolist(), [1])

    def test_returns_empty_dataframe_for_no_targets(self) -> None:
        source = MagicMock()

        result = attachment_loader.read_references_matching_file_ids(
            source, set()
        )

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


class ResolveBlobLocationTest(unittest.TestCase):
    def test_inline_resolves_via_attachment_file_by_file_id(self) -> None:
        location = attachment_loader.resolve_blob_location(
            file_id=9001, file_data_id=None, blob_source="INLINE"
        )

        assert location is not None
        self.assertEqual(location.table, "ATTACHMENT_FILE")
        self.assertEqual(location.id_column, "FILE_ID")
        self.assertEqual(location.blob_column, "FILE_DATA")
        self.assertEqual(location.reference_id, 9001)

    def test_external_resolves_via_file_data_by_file_data_id(self) -> None:
        location = attachment_loader.resolve_blob_location(
            file_id=9001, file_data_id=555, blob_source="EXTERNAL"
        )

        assert location is not None
        self.assertEqual(location.table, "FILE_DATA")
        self.assertEqual(location.id_column, "ID")
        self.assertEqual(location.blob_column, "DATA")
        self.assertEqual(location.reference_id, 555)

    def test_external_without_file_data_id_is_unresolvable(self) -> None:
        location = attachment_loader.resolve_blob_location(
            file_id=9001, file_data_id=None, blob_source="EXTERNAL"
        )

        self.assertIsNone(location)

    def test_missing_blob_source_is_unresolvable(self) -> None:
        location = attachment_loader.resolve_blob_location(
            file_id=9001, file_data_id=None, blob_source=None
        )

        self.assertIsNone(location)


class IterBlobChunksTest(unittest.TestCase):
    def test_streams_attachment_file_in_chunks(self) -> None:
        payload = b"streamed in chunks, never all at once"
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 9001
        )

        chunks = list(
            attachment_loader.iter_blob_chunks(connection, location, chunk_size=4)
        )

        self.assertEqual(b"".join(chunks), payload)
        # Proves actual chunking happened, not one big read.
        self.assertGreater(len(chunks), 1)
        for chunk in chunks[:-1]:
            self.assertEqual(len(chunk), 4)

    def test_streams_file_data_in_chunks(self) -> None:
        payload = b"external fallback payload"
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation("FILE_DATA", "ID", "DATA", 555)

        chunks = list(
            attachment_loader.iter_blob_chunks(connection, location, chunk_size=8)
        )

        self.assertEqual(b"".join(chunks), payload)
        cursor = connection.test_cursor
        self.assertIn("KCOEUS.FILE_DATA", cursor.sql)
        self.assertIn("source.ID = :reference_id", cursor.sql)
        self.assertIn("source.DATA", cursor.sql)
        self.assertEqual(cursor.parameters, {"reference_id": 555})

    def test_raises_missing_source_content_when_row_is_absent(self) -> None:
        connection = FakeBlobConnection(FakeBlobCursor(None))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 9001
        )

        with self.assertRaises(attachment_loader.MissingSourceContentError):
            list(
                attachment_loader.iter_blob_chunks(
                    connection, location, chunk_size=4
                )
            )


class BuildS3KeyTest(unittest.TestCase):
    def test_key_is_deterministic_and_uses_by_file_id_shape(self) -> None:
        key = attachment_loader.build_s3_key(
            "award-files/by-file-id", 9001, "Agreement Final.pdf"
        )

        self.assertEqual(key, "award-files/by-file-id/9001/Agreement_Final.pdf")

    def test_same_file_id_always_produces_the_same_key(self) -> None:
        first = attachment_loader.build_s3_key(
            "award-files/by-file-id", 9001, "agreement.pdf"
        )
        second = attachment_loader.build_s3_key(
            "award-files/by-file-id", 9001, "agreement.pdf"
        )

        self.assertEqual(first, second)


class StreamUploadTest(unittest.TestCase):
    def test_inline_source_uploads_via_attachment_file(self) -> None:
        payload = b"a small inline attachment"
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 9001
        )
        s3_client = MagicMock()

        byte_size, sha256 = attachment_loader.stream_upload(
            connection,
            location,
            s3_client,
            bucket="test-bucket",
            key="award-files/by-file-id/9001/agreement.pdf",
            content_type="application/pdf",
            file_size_bytes=len(payload),
            multipart_threshold=attachment_loader.DEFAULT_MULTIPART_THRESHOLD_BYTES,
        )

        s3_client.put_object.assert_called_once()
        s3_client.create_multipart_upload.assert_not_called()
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())
        call_kwargs = s3_client.put_object.call_args.kwargs
        self.assertEqual(call_kwargs["Bucket"], "test-bucket")
        self.assertEqual(call_kwargs["Body"], payload)

    def test_external_source_uploads_via_file_data(self) -> None:
        payload = b"an external FILE_DATA fallback attachment"
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation("FILE_DATA", "ID", "DATA", 555)
        s3_client = MagicMock()

        byte_size, sha256 = attachment_loader.stream_upload(
            connection,
            location,
            s3_client,
            bucket="test-bucket",
            key="award-files/by-file-id/9002/report.pdf",
            content_type=None,
            file_size_bytes=len(payload),
            multipart_threshold=attachment_loader.DEFAULT_MULTIPART_THRESHOLD_BYTES,
        )

        s3_client.put_object.assert_called_once()
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())

    def test_sha256_matches_streamed_content_across_many_chunks(self) -> None:
        payload = bytes(range(256)) * 50  # 12,800 bytes, many small chunks
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 1
        )
        s3_client = MagicMock()

        _, sha256 = attachment_loader.stream_upload(
            connection,
            location,
            s3_client,
            bucket="test-bucket",
            key="k",
            content_type=None,
            file_size_bytes=len(payload),
            multipart_threshold=attachment_loader.DEFAULT_MULTIPART_THRESHOLD_BYTES,
        )

        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())

    def test_uses_put_object_below_threshold(self) -> None:
        payload = b"x" * 100
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 1
        )
        s3_client = MagicMock()

        attachment_loader.stream_upload(
            connection,
            location,
            s3_client,
            bucket="test-bucket",
            key="k",
            content_type=None,
            file_size_bytes=len(payload),
            multipart_threshold=1000,
        )

        s3_client.put_object.assert_called_once()
        s3_client.create_multipart_upload.assert_not_called()

    def test_uses_multipart_above_threshold(self) -> None:
        payload = b"y" * 1000
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 1
        )
        s3_client = MagicMock()
        s3_client.create_multipart_upload.return_value = {"UploadId": "upload-1"}
        s3_client.upload_part.side_effect = (
            lambda **kwargs: {"ETag": f"etag-{kwargs['PartNumber']}"}
        )

        # Force multiple parts by using a tiny effective part size - the
        # loader still floors it to S3's real 5 MiB minimum, but that
        # flooring only matters for a real upload; here we assert on call
        # shape, not real S3 semantics.
        with patch.object(attachment_loader, "_S3_MIN_PART_SIZE", 100):
            byte_size, sha256 = attachment_loader.stream_upload(
                connection,
                location,
                s3_client,
                bucket="test-bucket",
                key="k",
                content_type="application/octet-stream",
                file_size_bytes=len(payload),
                multipart_threshold=100,
            )

        s3_client.put_object.assert_not_called()
        s3_client.create_multipart_upload.assert_called_once()
        self.assertGreaterEqual(s3_client.upload_part.call_count, 2)
        s3_client.complete_multipart_upload.assert_called_once()
        complete_kwargs = s3_client.complete_multipart_upload.call_args.kwargs
        self.assertEqual(complete_kwargs["UploadId"], "upload-1")
        self.assertEqual(
            [part["PartNumber"] for part in complete_kwargs["MultipartUpload"]["Parts"]],
            list(range(1, len(complete_kwargs["MultipartUpload"]["Parts"]) + 1)),
        )
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())
        s3_client.abort_multipart_upload.assert_not_called()

    def test_multipart_upload_is_aborted_on_failure(self) -> None:
        payload = b"z" * 1000
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 1
        )
        s3_client = MagicMock()
        s3_client.create_multipart_upload.return_value = {"UploadId": "upload-1"}
        s3_client.upload_part.side_effect = RuntimeError("network blip")

        with patch.object(attachment_loader, "_S3_MIN_PART_SIZE", 100):
            with self.assertRaises(RuntimeError):
                attachment_loader.stream_upload(
                    connection,
                    location,
                    s3_client,
                    bucket="test-bucket",
                    key="k",
                    content_type=None,
                    file_size_bytes=len(payload),
                    multipart_threshold=100,
                )

        s3_client.abort_multipart_upload.assert_called_once_with(
            Bucket="test-bucket", Key="k", UploadId="upload-1"
        )
        s3_client.complete_multipart_upload.assert_not_called()

    def test_unknown_file_size_defaults_to_multipart(self) -> None:
        payload = b"small but size unknown"
        connection = FakeBlobConnection(FakeBlobCursor(payload))
        location = attachment_loader.BlobLocation(
            "ATTACHMENT_FILE", "FILE_ID", "FILE_DATA", 1
        )
        s3_client = MagicMock()
        s3_client.create_multipart_upload.return_value = {"UploadId": "upload-1"}
        s3_client.upload_part.return_value = {"ETag": "etag-1"}

        attachment_loader.stream_upload(
            connection,
            location,
            s3_client,
            bucket="test-bucket",
            key="k",
            content_type=None,
            file_size_bytes=None,
            multipart_threshold=attachment_loader.DEFAULT_MULTIPART_THRESHOLD_BYTES,
        )

        s3_client.create_multipart_upload.assert_called_once()
        s3_client.put_object.assert_not_called()


class SelectUploadCandidatesTest(unittest.TestCase):
    def test_default_statuses_exclude_failed(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        attachment_loader.select_upload_candidates(
            connection, limit=None, file_id=None, retry_failed=False
        )

        params = connection.execute.call_args.args[1]
        self.assertEqual(set(params["statuses"]), {"PENDING", "UPLOADING"})

    def test_retry_failed_includes_failed_status(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        attachment_loader.select_upload_candidates(
            connection, limit=None, file_id=None, retry_failed=True
        )

        params = connection.execute.call_args.args[1]
        self.assertEqual(
            set(params["statuses"]), {"PENDING", "UPLOADING", "FAILED"}
        )

    def test_queries_attachment_object_not_award_attachment(self) -> None:
        # Structural guarantee behind "duplicate FILE_ID uploaded once":
        # attachment_object has file_id as its primary key (deduplicated
        # by Sprint 1), unlike award_attachment where the same file_id can
        # legitimately repeat across many reference rows.
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        attachment_loader.select_upload_candidates(
            connection, limit=None, file_id=None, retry_failed=False
        )

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.attachment_object", statement)
        self.assertNotIn("award_attachment", statement)

    def test_filters_by_file_id_and_limit_when_given(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        attachment_loader.select_upload_candidates(
            connection, limit=5, file_id=9001, retry_failed=False
        )

        params = connection.execute.call_args.args[1]
        self.assertEqual(params["file_id"], 9001)
        self.assertEqual(params["limit"], 5)


class MarkFileUploadFailedRedactsErrorsTest(unittest.TestCase):
    def test_redacts_password_before_persisting(self) -> None:
        connection = MagicMock()
        engine = MagicMock()
        engine.begin.return_value.__enter__.return_value = connection

        attachment_loader.mark_file_upload_failed(
            engine, 9001, "connect failed: password=hunter2"
        )

        _, params = connection.execute.call_args.args
        self.assertNotIn("hunter2", params["last_error"])
        self.assertIn("[REDACTED]", params["last_error"])


def _candidate_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "file_id": 9001,
        "file_data_id": None,
        "file_name": "agreement.pdf",
        "content_type": "application/pdf",
        "blob_source": "INLINE",
        "file_size_bytes": 100,
        "upload_status": "PENDING",
        "s3_bucket": None,
        "s3_key": None,
    }
    row.update(overrides)
    return row


class RunUploadSafetyTest(unittest.TestCase):
    def test_fails_closed_without_bucket(self) -> None:
        arguments = MagicMock(bucket=None)

        with self.assertRaises(RuntimeError):
            attachment_loader._run_upload(arguments)

    def test_fails_closed_when_aws_identity_is_invalid(self) -> None:
        arguments = MagicMock(bucket="test-bucket")

        with (
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                side_effect=RuntimeError("no credentials"),
            ),
            patch.object(attachment_loader, "create_s3_client") as create_s3,
            patch.object(attachment_loader, "create_postgres_engine") as create_pg,
        ):
            with self.assertRaises(RuntimeError):
                attachment_loader._run_upload(arguments)

        create_s3.assert_not_called()
        create_pg.assert_not_called()

    def test_fails_closed_when_bucket_is_inaccessible_and_never_creates_one(
        self,
    ) -> None:
        arguments = MagicMock(bucket="test-bucket")
        s3_client = MagicMock()
        s3_client.head_bucket.side_effect = RuntimeError("403 Forbidden")

        with (
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:aws:iam::123:user/x"},
            ),
            patch.object(
                attachment_loader, "create_s3_client", return_value=s3_client
            ),
            patch.object(attachment_loader, "create_postgres_engine") as create_pg,
        ):
            with self.assertRaises(RuntimeError):
                attachment_loader._run_upload(arguments)

        s3_client.create_bucket.assert_not_called()
        create_pg.assert_not_called()


class RunUploadTest(unittest.TestCase):
    def _patched_run_upload(
        self,
        candidate_rows: list[dict],
        *,
        arguments: MagicMock | None = None,
        stream_upload_side_effect=None,
    ) -> dict:
        arguments = arguments or MagicMock(
            bucket="test-bucket",
            prefix=None,
            limit=None,
            file_id=None,
            batch_id=None,
            retry_failed=False,
            multipart_threshold_bytes=None,
        )
        candidates = pd.DataFrame(candidate_rows)

        def _default_stream_upload(connection, location, s3_client, **kwargs):
            return 100, "deadbeef" * 8

        with (
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(attachment_loader, "create_s3_client", return_value=MagicMock()),
            patch.object(attachment_loader, "validate_bucket_accessible"),
            patch.object(attachment_loader, "create_postgres_engine") as create_engine,
            patch.object(
                attachment_loader,
                "select_upload_candidates",
                return_value=candidates,
            ),
            patch.object(attachment_loader, "_connect_oracle") as connect_oracle,
            patch.object(
                attachment_loader,
                "stream_upload",
                side_effect=stream_upload_side_effect or _default_stream_upload,
            ) as stream_upload,
            patch.object(attachment_loader, "mark_file_uploading") as mark_uploading,
            patch.object(attachment_loader, "mark_file_uploaded") as mark_uploaded,
            patch.object(
                attachment_loader, "mark_file_upload_failed"
            ) as mark_failed,
            patch.object(
                attachment_loader, "mark_file_missing_source_content"
            ) as mark_missing,
        ):
            create_engine.return_value = MagicMock()
            report = attachment_loader._run_upload(arguments)

        return {
            "report": report,
            "stream_upload": stream_upload,
            "mark_uploading": mark_uploading,
            "mark_uploaded": mark_uploaded,
            "mark_failed": mark_failed,
            "mark_missing": mark_missing,
            "connect_oracle": connect_oracle,
        }

    def test_uploads_each_distinct_file_id_exactly_once(self) -> None:
        # attachment_object has file_id as its primary key (Sprint 1
        # dedup), so a candidates DataFrame can never legitimately contain
        # the same file_id twice - this proves the loop uploads the one
        # row it gets exactly once, not more.
        result = self._patched_run_upload([_candidate_row(file_id=9001)])

        result["stream_upload"].assert_called_once()
        result["mark_uploaded"].assert_called_once()
        self.assertEqual(result["report"]["uploaded"], 1)
        self.assertEqual(result["report"]["physical_files_selected"], 1)

    def test_missing_source_content_is_marked_and_not_uploaded(self) -> None:
        result = self._patched_run_upload(
            [_candidate_row(blob_source=None, file_data_id=None)]
        )

        result["stream_upload"].assert_not_called()
        result["mark_missing"].assert_called_once()
        self.assertEqual(result["report"]["missing_source_content"], 1)
        self.assertEqual(result["report"]["uploaded"], 0)

    def test_skips_already_uploaded_with_matching_bucket_and_key(self) -> None:
        matching_key = attachment_loader.build_s3_key(
            attachment_loader.DEFAULT_S3_KEY_PREFIX, 9001, "agreement.pdf"
        )
        result = self._patched_run_upload(
            [
                _candidate_row(
                    upload_status="UPLOADED",
                    s3_bucket="test-bucket",
                    s3_key=matching_key,
                )
            ]
        )

        result["stream_upload"].assert_not_called()
        result["connect_oracle"].assert_not_called()
        self.assertEqual(result["report"]["skipped_already_uploaded"], 1)
        self.assertEqual(result["report"]["uploaded"], 0)

    def test_reuploads_when_bucket_or_key_differs(self) -> None:
        # Previously uploaded to a different bucket/prefix - the target
        # destination changed, so the old upload no longer satisfies it.
        result = self._patched_run_upload(
            [
                _candidate_row(
                    upload_status="UPLOADED",
                    s3_bucket="other-bucket",
                    s3_key="something/else",
                )
            ]
        )

        result["stream_upload"].assert_called_once()
        self.assertEqual(result["report"]["skipped_already_uploaded"], 0)
        self.assertEqual(result["report"]["uploaded"], 1)

    def test_failed_upload_marks_failed_and_continues(self) -> None:
        result = self._patched_run_upload(
            [_candidate_row(file_id=9001), _candidate_row(file_id=9002)],
            stream_upload_side_effect=[RuntimeError("boom"), (50, "abc123")],
        )

        result["mark_failed"].assert_called_once()
        result["mark_uploaded"].assert_called_once()
        self.assertEqual(result["report"]["failed"], 1)
        self.assertEqual(result["report"]["uploaded"], 1)

    def test_marks_uploading_before_attempting_upload(self) -> None:
        result = self._patched_run_upload([_candidate_row(file_id=9001)])

        result["mark_uploading"].assert_called_once()
        self.assertEqual(result["mark_uploading"].call_args.args[1], 9001)

    def test_reports_inline_and_file_data_source_counts_separately(self) -> None:
        result = self._patched_run_upload(
            [
                _candidate_row(file_id=1, blob_source="INLINE"),
                _candidate_row(
                    file_id=2, blob_source="EXTERNAL", file_data_id=555
                ),
            ]
        )

        self.assertEqual(result["report"]["inline_source_count"], 1)
        self.assertEqual(result["report"]["file_data_source_count"], 1)

    def test_bytes_uploaded_accumulates_across_files(self) -> None:
        result = self._patched_run_upload(
            [_candidate_row(file_id=1), _candidate_row(file_id=2)],
            stream_upload_side_effect=[(100, "a" * 64), (250, "b" * 64)],
        )

        self.assertEqual(result["report"]["bytes_uploaded"], 350)


class UploadGatingTest(unittest.TestCase):
    def test_main_never_runs_upload_without_the_flag(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[
                    _oracle_batches_stub(
                        [
                            pd.DataFrame(
                                [
                                    {
                                        "award_attachment_id": 1,
                                        "award_id": 101,
                                        "award_number": "000001",
                                        "sequence_number": 0,
                                        "file_id": 9001,
                                    }
                                ]
                            )
                        ]
                    ),
                    _oracle_batches_stub(
                        [
                            pd.DataFrame(
                                [{"file_id": 9001, "blob_source": "INLINE"}]
                            )
                        ]
                    ),
                ],
            ),
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(attachment_loader, "_run_upload") as run_upload,
        ):
            parse_args.return_value = MagicMock(
                limit=10,
                dry_run=True,
                upload=False,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
            attachment_loader.main()

        run_upload.assert_not_called()

    def test_main_runs_upload_when_the_flag_is_given(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(attachment_loader, "_run_upload") as run_upload,
        ):
            parse_args.return_value = MagicMock(
                upload=True,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                ecs=False,
            )
            attachment_loader.main()

        run_upload.assert_called_once()


class RunFileIdLookupTest(unittest.TestCase):
    def _files_batch(self) -> pd.DataFrame:
        return pd.DataFrame(
            [
                {
                    "file_id": 1001,
                    "file_data_id": None,
                    "file_name": "unrelated.pdf",
                    "content_type": "application/pdf",
                    "blob_source": "INLINE",
                    "file_size_bytes": 111,
                    "oracle_update_timestamp": "2025-01-01 00:00:00",
                    "oracle_update_user": "kcuser",
                },
                {
                    "file_id": 9001,
                    "file_data_id": None,
                    "file_name": "Agreement Final.pdf",
                    "content_type": "application/pdf",
                    "blob_source": "INLINE",
                    "file_size_bytes": 424242,
                    "oracle_update_timestamp": "2025-01-02 03:04:05",
                    "oracle_update_user": "kcuser",
                },
                {
                    "file_id": 1002,
                    "file_data_id": None,
                    "file_name": "also-unrelated.pdf",
                    "content_type": "application/pdf",
                    "blob_source": "EXTERNAL",
                    "file_size_bytes": 222,
                    "oracle_update_timestamp": "2025-01-01 00:00:00",
                    "oracle_update_user": "kcuser",
                },
            ]
        )

    def test_finds_exact_file_id_and_reports_details(self) -> None:
        # Only ONE OracleDataSource is ever instantiated (the files
        # source) - a second, unexpected instantiation (e.g. reading
        # references) would raise StopIteration against this
        # single-item side_effect list.
        with patch.object(
            attachment_loader,
            "OracleDataSource",
            side_effect=[_oracle_batches_stub([self._files_batch()])],
        ):
            report = attachment_loader._run_file_id_lookup(9001)

        self.assertEqual(report["requested_file_id"], 9001)
        self.assertEqual(report["matched_file_id"], 9001)
        self.assertEqual(report["file_name"], "Agreement Final.pdf")
        self.assertEqual(report["content_type"], "application/pdf")
        self.assertEqual(report["source_location"], "INLINE")
        self.assertEqual(report["file_size_bytes"], 424242)

    def test_raises_cleanly_when_file_id_not_found(self) -> None:
        with patch.object(
            attachment_loader,
            "OracleDataSource",
            side_effect=[_oracle_batches_stub([self._files_batch()])],
        ):
            with self.assertRaises(RuntimeError) as raised:
                attachment_loader._run_file_id_lookup(999999)

        self.assertIn("999999", str(raised.exception))

    def test_logs_requested_and_matched_file_id(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[_oracle_batches_stub([self._files_batch()])],
            ),
            patch.object(attachment_loader.logger, "info") as info,
        ):
            attachment_loader._run_file_id_lookup(9001)

        logged = " ".join(str(call) for call in info.call_args_list)
        self.assertIn("9001", logged)

    def test_never_reads_or_logs_blob_content(self) -> None:
        # The physical-file extraction query never selects a blob column
        # value (only NULL-checks/DBMS_LOB.GETLENGTH()), so there is no
        # blob content anywhere in the batch for this to leak - assert
        # the reported fields are exactly the metadata fields, nothing
        # blob-shaped.
        with patch.object(
            attachment_loader,
            "OracleDataSource",
            side_effect=[_oracle_batches_stub([self._files_batch()])],
        ):
            report = attachment_loader._run_file_id_lookup(9001)

        self.assertEqual(
            set(report.keys()),
            {
                "requested_file_id",
                "matched_file_id",
                "file_name",
                "content_type",
                "source_location",
                "file_size_bytes",
            },
        )


class FileIdModeIsReadOnlyAndTakesPriorityTest(unittest.TestCase):
    def test_main_never_connects_to_postgres_for_file_id_lookup(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "OracleDataSource",
                side_effect=[
                    _oracle_batches_stub(
                        [pd.DataFrame([{"file_id": 9001, "blob_source": "INLINE"}])]
                    )
                ],
            ),
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
        ):
            parse_args.return_value = MagicMock(
                upload=False,
                file_id=9001,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                limit=None,
                dry_run=True,
                ecs=False,
            )
            attachment_loader.main()

        create_engine.assert_not_called()

    def test_file_id_takes_priority_over_limit_not_a_reference_sample(
        self,
    ) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "_run_file_id_lookup"
            ) as run_lookup,
            patch.object(
                attachment_loader, "_read_coherent_sample"
            ) as read_sample,
        ):
            parse_args.return_value = MagicMock(
                upload=False,
                file_id=9001,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                limit=10,
                dry_run=True,
                ecs=False,
            )
            attachment_loader.main()

        run_lookup.assert_called_once_with(9001)
        read_sample.assert_not_called()


class RunShowUploadStatusTest(unittest.TestCase):
    """--show-upload-status: a read-only PostgreSQL diagnostic for one
    exact file_id. Never writes, never reads a BLOB (attachment_object
    has no BLOB column), never touches S3."""

    def _connection(self, *, row: dict | None) -> MagicMock:
        engine = MagicMock()
        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.return_value.mappings.return_value.one_or_none.return_value = (
            row
        )
        return engine

    def test_row_found_logs_every_required_field(self) -> None:
        row = {
            "file_id": 1,
            "file_name": "Agreement.pdf",
            "blob_source": "INLINE",
            "upload_status": "UPLOADED",
            "upload_attempts": 1,
            "s3_bucket": "my-bucket",
            "s3_key": "award-files/by-file-id/1/Agreement.pdf",
            "uploaded_at": "2026-01-01 00:00:00",
            "last_error": None,
        }
        engine = self._connection(row=row)

        result = attachment_loader._run_show_upload_status(engine, 1)

        self.assertEqual(result["found"], True)
        self.assertEqual(result["file_id"], 1)
        self.assertEqual(result["file_name"], "Agreement.pdf")
        self.assertEqual(result["blob_source"], "INLINE")
        self.assertEqual(result["upload_status"], "UPLOADED")
        self.assertEqual(result["upload_attempts"], 1)
        self.assertEqual(result["s3_bucket"], "my-bucket")
        self.assertEqual(
            result["s3_key"], "award-files/by-file-id/1/Agreement.pdf"
        )
        self.assertEqual(result["uploaded_at"], "2026-01-01 00:00:00")
        self.assertIsNone(result["last_error"])

    def test_queries_attachment_object_by_exact_file_id(self) -> None:
        engine = self._connection(row=None)

        attachment_loader._run_show_upload_status(engine, 42)

        connection = engine.connect.return_value.__enter__.return_value
        statement = str(connection.execute.call_args.args[0])
        params = connection.execute.call_args.args[1]
        self.assertIn("archive.attachment_object", statement)
        self.assertEqual(params["file_id"], 42)

    def test_no_row_logs_clearly_and_reports_not_found(self) -> None:
        engine = self._connection(row=None)

        result = attachment_loader._run_show_upload_status(engine, 999)

        self.assertEqual(result, {"file_id": 999, "found": False})

    def test_no_row_is_not_an_error(self) -> None:
        # Exercises the exact requirement: exit 0 (no exception) when no
        # row exists, not just "found=False" in the return value.
        engine = self._connection(row=None)

        try:
            attachment_loader._run_show_upload_status(engine, 999)
        except Exception as error:  # noqa: BLE001
            self.fail(f"unexpected exception for a missing row: {error}")

    def test_last_error_is_redacted_when_present(self) -> None:
        row = {
            "file_id": 1,
            "file_name": "f.pdf",
            "blob_source": "INLINE",
            "upload_status": "FAILED",
            "upload_attempts": 1,
            "s3_bucket": None,
            "s3_key": None,
            "uploaded_at": None,
            "last_error": "password=hunter2 failed",
        }
        engine = self._connection(row=row)

        result = attachment_loader._run_show_upload_status(engine, 1)

        self.assertNotIn("hunter2", result["last_error"])

    def test_never_calls_execute_more_than_once(self) -> None:
        # A pure, single SELECT - never a write statement alongside it.
        engine = self._connection(row=None)

        attachment_loader._run_show_upload_status(engine, 1)

        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.assert_called_once()

    def test_never_uses_engine_begin(self) -> None:
        # engine.begin() is this module's write-transaction idiom
        # (mark_file_uploaded/mark_file_upload_failed/etc. all use it) -
        # a read-only diagnostic must never invoke it.
        engine = self._connection(row=None)

        attachment_loader._run_show_upload_status(engine, 1)

        engine.begin.assert_not_called()


class RunEcsSetupTest(unittest.TestCase):
    """Orchestration tests for _run_ecs_setup - every collaborator
    (Secrets Manager resolution, startup-validation checks, migrations)
    has its own focused unit tests elsewhere; these prove _run_ecs_setup
    wires them together in the exact required order and short-circuits
    correctly for --migrate-only."""

    def _run(self, *, migrate_only: bool, show_upload_status: bool = False) -> dict:
        arguments = MagicMock(
            migrate_only=migrate_only,
            show_upload_status=show_upload_status,
            show_batch=None,
                list_awards_with_attachments=False,
            bucket=None,
            file_id=9001,
        )
        calls: list[str] = []

        def _track(name, retval=None):
            def _fn(*args, **kwargs):
                calls.append(name)
                return retval

            return _fn

        def _boto3_client_side_effect(service_name, *args, **kwargs):
            calls.append(f"boto3.client({service_name})")
            return MagicMock()

        with (
            patch.object(attachment_loader, "configure_structured_logging"),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                side_effect=_track(
                    "validate_aws_identity", {"account": "123", "arn": "arn:x"}
                ),
            ) as validate_identity,
            patch.object(
                attachment_loader.boto3,
                "client",
                side_effect=_boto3_client_side_effect,
            ) as boto3_client,
            patch.object(
                attachment_loader,
                "configure_ecs_environment",
                side_effect=_track("configure_ecs_environment"),
            ) as configure_env,
            patch.object(
                attachment_loader,
                "create_postgres_engine",
                side_effect=_track("create_postgres_engine", MagicMock()),
            ),
            patch.object(
                attachment_loader,
                "validate_postgres_reachable",
                side_effect=_track("validate_postgres_reachable"),
            ),
            patch.object(
                attachment_loader,
                "apply_migrations",
                side_effect=_track("apply_migrations"),
            ) as apply_migrations,
            patch.object(
                attachment_loader,
                "_run_show_upload_status",
                side_effect=_track("_run_show_upload_status"),
            ) as run_show_upload_status,
            patch.object(
                attachment_loader,
                "validate_table_exists",
                side_effect=_track("validate_table_exists"),
            ),
            patch.object(
                attachment_loader,
                "validate_upload_status_schema",
                side_effect=_track("validate_upload_status_schema"),
            ),
            patch.object(
                attachment_loader,
                "validate_oracle_reachable",
                side_effect=_track("validate_oracle_reachable"),
            ) as validate_oracle,
            patch.object(
                attachment_loader,
                "create_s3_client",
                side_effect=_track("create_s3_client", MagicMock()),
            ) as create_s3,
            patch.object(
                attachment_loader,
                "validate_bucket_exists",
                side_effect=_track("validate_bucket_exists"),
            ) as validate_bucket,
            patch.dict(
                attachment_loader.os.environ,
                {"AWARD_ATTACHMENT_BUCKET_NAME": ""},
            ),
        ):
            result = attachment_loader._run_ecs_setup(arguments, "run-1")

        return {
            "result": result,
            "calls": calls,
            "validate_identity": validate_identity,
            "boto3_client": boto3_client,
            "configure_env": configure_env,
            "apply_migrations": apply_migrations,
            "validate_oracle": validate_oracle,
            "create_s3": create_s3,
            "validate_bucket": validate_bucket,
            "run_show_upload_status": run_show_upload_status,
        }

    def test_migrate_only_reaches_apply_migrations(self) -> None:
        result = self._run(migrate_only=True)

        self.assertIn("apply_migrations", result["calls"])
        self.assertTrue(result["result"])

    def test_migrate_only_validates_schema_after_migrating_not_before(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("apply_migrations"), calls.index("validate_table_exists")
        )
        self.assertLess(
            calls.index("apply_migrations"),
            calls.index("validate_upload_status_schema"),
        )

    def test_migrate_only_never_contacts_oracle(self) -> None:
        result = self._run(migrate_only=True)

        self.assertNotIn("validate_oracle_reachable", result["calls"])
        result["validate_oracle"].assert_not_called()
        result["configure_env"].assert_called_once()
        self.assertFalse(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_migrate_only_never_contacts_s3(self) -> None:
        result = self._run(migrate_only=True)

        result["create_s3"].assert_not_called()
        result["validate_bucket"].assert_not_called()

    def test_show_upload_status_reaches_the_lookup(self) -> None:
        result = self._run(migrate_only=False, show_upload_status=True)

        self.assertIn("_run_show_upload_status", result["calls"])
        self.assertTrue(result["result"])
        result["run_show_upload_status"].assert_called_once()
        self.assertEqual(result["run_show_upload_status"].call_args.args[1], 9001)

    def test_show_upload_status_never_contacts_oracle(self) -> None:
        result = self._run(migrate_only=False, show_upload_status=True)

        self.assertNotIn("validate_oracle_reachable", result["calls"])
        result["validate_oracle"].assert_not_called()
        result["configure_env"].assert_called_once()
        self.assertFalse(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_show_upload_status_never_contacts_s3(self) -> None:
        result = self._run(migrate_only=False, show_upload_status=True)

        result["create_s3"].assert_not_called()
        result["validate_bucket"].assert_not_called()

    def test_show_upload_status_never_applies_migrations(self) -> None:
        result = self._run(migrate_only=False, show_upload_status=True)

        result["apply_migrations"].assert_not_called()

    def test_identity_resolved_before_secrets_manager_client_created(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("validate_aws_identity"),
            calls.index("boto3.client(secretsmanager)"),
        )

    def test_creates_exactly_one_secrets_manager_client(self) -> None:
        result = self._run(migrate_only=True)

        result["boto3_client"].assert_called_once_with("secretsmanager")

    def test_secrets_loaded_before_postgres_connectivity_check(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("configure_ecs_environment"),
            calls.index("validate_postgres_reachable"),
        )

    def test_normal_flow_reaches_oracle_then_tables_then_schema_in_order(
        self,
    ) -> None:
        result = self._run(migrate_only=False)
        calls = result["calls"]

        self.assertNotIn("apply_migrations", calls)
        self.assertFalse(result["result"])
        self.assertLess(
            calls.index("validate_postgres_reachable"),
            calls.index("validate_oracle_reachable"),
        )
        self.assertLess(
            calls.index("validate_oracle_reachable"),
            calls.index("validate_table_exists"),
        )
        self.assertLess(
            calls.index("validate_table_exists"),
            calls.index("validate_upload_status_schema"),
        )

    def test_normal_flow_validates_bucket_between_oracle_and_tables_when_configured(
        self,
    ) -> None:
        arguments = MagicMock(
            migrate_only=False,
            show_upload_status=False,
            show_batch=None,
                list_awards_with_attachments=False,
            bucket="my-bucket",
        )
        calls: list[str] = []

        def _track(name, retval=None):
            def _fn(*args, **kwargs):
                calls.append(name)
                return retval

            return _fn

        with (
            patch.object(attachment_loader, "configure_structured_logging"),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(attachment_loader.boto3, "client", return_value=MagicMock()),
            patch.object(attachment_loader, "configure_ecs_environment"),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "validate_postgres_reachable"),
            patch.object(
                attachment_loader,
                "validate_oracle_reachable",
                side_effect=_track("validate_oracle_reachable"),
            ),
            patch.object(
                attachment_loader, "create_s3_client", return_value=MagicMock()
            ),
            patch.object(
                attachment_loader,
                "validate_bucket_exists",
                side_effect=_track("validate_bucket_exists"),
            ),
            patch.object(
                attachment_loader,
                "validate_table_exists",
                side_effect=_track("validate_table_exists"),
            ),
            patch.object(attachment_loader, "validate_upload_status_schema"),
        ):
            attachment_loader._run_ecs_setup(arguments, "run-1")

        self.assertLess(
            calls.index("validate_oracle_reachable"),
            calls.index("validate_bucket_exists"),
        )
        self.assertLess(
            calls.index("validate_bucket_exists"), calls.index("validate_table_exists")
        )

    def test_upload_without_bucket_flag_uses_bucket_env_never_data_bucket_name(
        self,
    ) -> None:
        """--ecs --upload with no --bucket must resolve its destination
        from AWARD_ATTACHMENT_BUCKET_NAME only. DATA_BUCKET_NAME is a
        different, IRB-only bucket wired into the same ECS task family -
        this proves the two can never be confused, even when both env
        vars are set to different values simultaneously."""
        arguments = MagicMock(
            migrate_only=False,
            show_upload_status=False,
            show_batch=None,
                list_awards_with_attachments=False,
            bucket=None,
        )

        with (
            patch.object(attachment_loader, "configure_structured_logging"),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(attachment_loader.boto3, "client", return_value=MagicMock()),
            patch.object(attachment_loader, "configure_ecs_environment"),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "validate_postgres_reachable"),
            patch.object(attachment_loader, "validate_oracle_reachable"),
            patch.object(
                attachment_loader, "create_s3_client", return_value=MagicMock()
            ),
            patch.object(
                attachment_loader, "validate_bucket_exists"
            ) as validate_bucket,
            patch.object(attachment_loader, "validate_table_exists"),
            patch.object(attachment_loader, "validate_upload_status_schema"),
            patch.dict(
                attachment_loader.os.environ,
                {
                    "AWARD_ATTACHMENT_BUCKET_NAME": "correct-documents-bucket",
                    "DATA_BUCKET_NAME": "wrong-irb-only-bucket",
                },
            ),
        ):
            attachment_loader._run_ecs_setup(arguments, "run-1")

        self.assertEqual(arguments.bucket, "correct-documents-bucket")
        validate_bucket.assert_called_once()
        self.assertEqual(validate_bucket.call_args.args[1], "correct-documents-bucket")

    def test_fails_fast_on_postgres_before_touching_oracle(self) -> None:
        arguments = MagicMock(
            migrate_only=False,
            show_upload_status=False,
            show_batch=None,
                list_awards_with_attachments=False,
            bucket=None,
        )

        with (
            patch.object(attachment_loader, "configure_structured_logging"),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(attachment_loader.boto3, "client", return_value=MagicMock()),
            patch.object(attachment_loader, "configure_ecs_environment"),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(
                attachment_loader,
                "validate_postgres_reachable",
                side_effect=StartupValidationError("PostgreSQL is not reachable"),
            ),
            patch.object(
                attachment_loader, "validate_oracle_reachable"
            ) as validate_oracle,
        ):
            with self.assertRaises(StartupValidationError):
                attachment_loader._run_ecs_setup(arguments, "run-1")

        validate_oracle.assert_not_called()


class ParseArgsMigrateOnlyTest(unittest.TestCase):
    def test_migrate_only_requires_ecs(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--migrate-only"])

    def test_migrate_only_with_ecs_is_accepted(self) -> None:
        args = attachment_loader.parse_args(["--ecs", "--migrate-only"])

        self.assertTrue(args.migrate_only)
        self.assertTrue(args.ecs)

    def test_ecs_without_migrate_only_defaults_to_false(self) -> None:
        args = attachment_loader.parse_args(["--ecs"])

        self.assertFalse(args.migrate_only)


class ParseArgsShowUploadStatusTest(unittest.TestCase):
    def test_requires_ecs(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--show-upload-status", "--file-id", "1"]
            )

    def test_requires_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--ecs", "--show-upload-status"])

    def test_with_ecs_and_file_id_is_accepted(self) -> None:
        args = attachment_loader.parse_args(
            ["--ecs", "--show-upload-status", "--file-id", "1"]
        )

        self.assertTrue(args.show_upload_status)
        self.assertTrue(args.ecs)
        self.assertEqual(args.file_id, 1)

    def test_ecs_without_show_upload_status_defaults_to_false(self) -> None:
        args = attachment_loader.parse_args(["--ecs"])

        self.assertFalse(args.show_upload_status)


class ParseArgsLoadFileIdTest(unittest.TestCase):
    def test_parses_load_file_id(self) -> None:
        args = attachment_loader.parse_args(["--load-file-id", "1"])

        self.assertEqual(args.load_file_id, 1)

    def test_defaults_to_none(self) -> None:
        args = attachment_loader.parse_args([])

        self.assertIsNone(args.load_file_id)

    def test_does_not_require_ecs(self) -> None:
        # Unlike --migrate-only/--show-upload-status, --load-file-id
        # works in local dev too (matching --upload/plain metadata load).
        args = attachment_loader.parse_args(["--load-file-id", "1"])

        self.assertFalse(args.ecs)

    def test_combines_with_dry_run(self) -> None:
        args = attachment_loader.parse_args(["--load-file-id", "1", "--dry-run"])

        self.assertEqual(args.load_file_id, 1)
        self.assertTrue(args.dry_run)


class MigrateOnlyMainIntegrationTest(unittest.TestCase):
    def test_main_returns_immediately_after_migrate_only_completes(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "_run_ecs_setup", return_value=True
            ) as run_ecs_setup,
            patch.object(attachment_loader, "_run_upload") as run_upload,
            patch.object(
                attachment_loader, "_run_file_id_lookup"
            ) as run_file_id_lookup,
        ):
            # upload/file_id are deliberately also set, to prove
            # migrate_only short-circuits main() before either runs.
            parse_args.return_value = MagicMock(
                ecs=True,
                migrate_only=True,
                upload=True,
                file_id=9001,
                limit=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        run_ecs_setup.assert_called_once()
        run_upload.assert_not_called()
        run_file_id_lookup.assert_not_called()

    def test_ecs_upload_fails_when_migrations_are_absent_and_never_uploads(
        self,
    ) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(attachment_loader, "configure_structured_logging"),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(attachment_loader.boto3, "client", return_value=MagicMock()),
            patch.object(attachment_loader, "configure_ecs_environment"),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "validate_postgres_reachable"),
            patch.object(attachment_loader, "validate_oracle_reachable"),
            patch.dict(
                attachment_loader.os.environ,
                {"AWARD_ATTACHMENT_BUCKET_NAME": ""},
            ),
            patch.object(
                attachment_loader,
                "validate_table_exists",
                side_effect=StartupValidationError(
                    "archive.attachment_object does not exist"
                ),
            ),
            patch.object(attachment_loader, "_run_upload") as run_upload,
        ):
            parse_args.return_value = MagicMock(
                ecs=True,
                migrate_only=False,
                show_upload_status=False,
                upload=True,
                bucket=None,
                file_id=None,
                load_file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
                limit=None,
                dry_run=False,
            )
            with self.assertRaises(StartupValidationError):
                attachment_loader.main()

        run_upload.assert_not_called()


class ShowUploadStatusMainIntegrationTest(unittest.TestCase):
    def test_main_returns_immediately_after_show_upload_status_completes(
        self,
    ) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "_run_ecs_setup", return_value=True
            ) as run_ecs_setup,
            patch.object(attachment_loader, "_run_upload") as run_upload,
            patch.object(
                attachment_loader, "_run_file_id_lookup"
            ) as run_file_id_lookup,
        ):
            # upload/file_id are deliberately also set, to prove
            # show_upload_status short-circuits main() before either runs.
            parse_args.return_value = MagicMock(
                ecs=True,
                migrate_only=False,
                show_upload_status=True,
                upload=True,
                file_id=1,
                limit=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        run_ecs_setup.assert_called_once()
        run_upload.assert_not_called()
        run_file_id_lookup.assert_not_called()


class LoadFileIdMainIntegrationTest(unittest.TestCase):
    def test_local_mode_applies_migrations_then_loads(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
            patch.object(attachment_loader, "apply_migrations") as apply_migrations,
            patch.object(
                attachment_loader, "_run_load_file_id"
            ) as run_load_file_id,
            patch.object(attachment_loader, "_run_upload") as run_upload,
            patch.object(
                attachment_loader, "_run_file_id_lookup"
            ) as run_file_id_lookup,
        ):
            engine = MagicMock()
            create_engine.return_value = engine
            parse_args.return_value = MagicMock(
                ecs=False,
                load_file_id=1,
                dry_run=False,
                upload=False,
                file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        apply_migrations.assert_called_once()
        self.assertEqual(apply_migrations.call_args.args[0], engine)
        run_load_file_id.assert_called_once()
        self.assertEqual(run_load_file_id.call_args.args[0], engine)
        self.assertEqual(run_load_file_id.call_args.args[1], 1)
        self.assertFalse(run_load_file_id.call_args.kwargs["dry_run"])
        run_upload.assert_not_called()
        run_file_id_lookup.assert_not_called()

    def test_dry_run_is_forwarded(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "apply_migrations"),
            patch.object(
                attachment_loader, "_run_load_file_id"
            ) as run_load_file_id,
        ):
            parse_args.return_value = MagicMock(
                ecs=False,
                load_file_id=1,
                dry_run=True,
                upload=False,
                file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        self.assertTrue(run_load_file_id.call_args.kwargs["dry_run"])

    def test_takes_priority_over_upload_and_file_id(self) -> None:
        # upload/file_id are deliberately also set, to prove load_file_id
        # short-circuits main() before either runs - --load-file-id must
        # never upload to S3, even if --upload is also passed.
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "apply_migrations"),
            patch.object(attachment_loader, "_run_load_file_id") as run_load_file_id,
            patch.object(attachment_loader, "_run_upload") as run_upload,
            patch.object(
                attachment_loader, "_run_file_id_lookup"
            ) as run_file_id_lookup,
        ):
            parse_args.return_value = MagicMock(
                ecs=False,
                load_file_id=1,
                dry_run=False,
                upload=True,
                file_id=9001,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        run_load_file_id.assert_called_once()
        run_upload.assert_not_called()
        run_file_id_lookup.assert_not_called()

    def test_ecs_mode_never_applies_migrations(self) -> None:
        # --ecs requires migrations to already exist (validated by
        # _run_ecs_setup) and never applies them itself - --load-file-id
        # must not be an exception to that rule.
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "_run_ecs_setup", return_value=False
            ),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "apply_migrations") as apply_migrations,
            patch.object(attachment_loader, "_run_load_file_id") as run_load_file_id,
        ):
            parse_args.return_value = MagicMock(
                ecs=True,
                migrate_only=False,
                show_upload_status=False,
                load_file_id=1,
                dry_run=False,
                upload=False,
                file_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                list_awards_with_attachments=False,
            )
            attachment_loader.main()

        apply_migrations.assert_not_called()
        run_load_file_id.assert_called_once()


class FakeRow:
    """A minimal stand-in for a SQLAlchemy Row - attribute access only,
    matching how _run_list_awards_with_attachments reads its columns."""

    def __init__(self, **fields) -> None:
        self.__dict__.update(fields)


class ListAwardsWithAttachmentsTest(unittest.TestCase):
    def _fake_engine(self, rows: list[FakeRow]) -> MagicMock:
        engine = MagicMock()
        connection = MagicMock()
        connection.execute.return_value.all.return_value = rows
        engine.connect.return_value.__enter__.return_value = connection
        return engine

    def test_returns_rows_sorted_and_shaped_as_documented(self) -> None:
        rows = [
            FakeRow(
                award_number="100004-00003",
                award_id=3,
                title="Cancer study",
                attachment_count=7,
            ),
            FakeRow(
                award_number="100004-00001",
                award_id=1,
                title=None,
                attachment_count=2,
            ),
        ]
        engine = self._fake_engine(rows)

        result = attachment_loader._run_list_awards_with_attachments(
            engine, limit=25
        )

        self.assertEqual(
            result,
            [
                {
                    "award_number": "100004-00003",
                    "award_id": 3,
                    "title": "Cancer study",
                    "attachment_count": 7,
                },
                {
                    "award_number": "100004-00001",
                    "award_id": 1,
                    "title": None,
                    "attachment_count": 2,
                },
            ],
        )

    def test_binds_limit_only_when_given(self) -> None:
        engine = self._fake_engine([])
        connection = engine.connect.return_value.__enter__.return_value

        attachment_loader._run_list_awards_with_attachments(engine, limit=25)

        sql_text = str(connection.execute.call_args.args[0])
        self.assertIn("LIMIT :limit", sql_text)
        self.assertEqual(connection.execute.call_args.args[1], {"limit": 25})

    def test_omits_limit_clause_when_no_limit_given(self) -> None:
        engine = self._fake_engine([])
        connection = engine.connect.return_value.__enter__.return_value

        attachment_loader._run_list_awards_with_attachments(engine, limit=None)

        sql_text = str(connection.execute.call_args.args[0])
        self.assertNotIn("LIMIT", sql_text)

    def test_returns_an_empty_list_without_error_when_nothing_is_loaded(
        self,
    ) -> None:
        engine = self._fake_engine([])

        result = attachment_loader._run_list_awards_with_attachments(
            engine, limit=25
        )

        self.assertEqual(result, [])

    def test_main_dispatches_to_the_report_and_returns_immediately(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
            patch.object(
                attachment_loader, "_run_list_awards_with_attachments"
            ) as run_report,
            patch.object(attachment_loader, "OracleDataSource") as oracle_ds,
        ):
            parse_args.return_value = MagicMock(
                list_awards_with_attachments=True,
                limit=25,
                ecs=False,
            )
            attachment_loader.main()

        create_engine.assert_called_once()
        run_report.assert_called_once_with(create_engine.return_value, 25)
        oracle_ds.assert_not_called()

    def test_parser_accepts_list_awards_with_attachments_combined_with_ecs(
        self,
    ) -> None:
        # Runnable as a one-off ECS task using the existing loader task
        # definition (PostgreSQL-only, same as --show-batch) instead of
        # requiring a dedicated bastion host - see
        # scripts/run-award-attachment-loader.sh.
        parsed = attachment_loader.parse_args(
            ["--list-awards-with-attachments", "--ecs"]
        )
        self.assertTrue(parsed.list_awards_with_attachments)
        self.assertTrue(parsed.ecs)

    def _run_ecs_setup(self, **arguments_kwargs) -> dict:
        calls: list[str] = []

        def _track(name, retval=None):
            def _fn(*args, **kwargs):
                calls.append(name)
                return retval

            return _fn

        base_kwargs = {
            "migrate_only": False,
            "show_upload_status": False,
            "show_batch": None,
            "list_awards_with_attachments": False,
            "file_id": None,
            "bucket": None,
        }
        base_kwargs.update(arguments_kwargs)
        arguments = MagicMock(**base_kwargs)

        with (
            patch.object(
                attachment_loader,
                "configure_structured_logging",
                side_effect=_track("configure_structured_logging"),
            ),
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                side_effect=_track(
                    "validate_aws_identity", {"account": "770203350335"}
                ),
            ),
            patch.object(attachment_loader, "boto3") as boto3_module,
            patch.object(
                attachment_loader,
                "configure_ecs_environment",
            ) as configure_env,
            patch.object(
                attachment_loader,
                "create_postgres_engine",
                side_effect=_track("create_postgres_engine", MagicMock()),
            ),
            patch.object(
                attachment_loader,
                "validate_postgres_reachable",
                side_effect=_track("validate_postgres_reachable"),
            ),
            patch.object(
                attachment_loader,
                "validate_oracle_reachable",
                side_effect=_track("validate_oracle_reachable"),
            ) as validate_oracle,
            patch.object(
                attachment_loader, "create_s3_client"
            ) as create_s3,
            patch.object(
                attachment_loader, "validate_bucket_exists"
            ) as validate_bucket,
            patch.object(
                attachment_loader,
                "_run_list_awards_with_attachments",
                side_effect=_track("_run_list_awards_with_attachments"),
            ) as run_report,
        ):
            boto3_module.client.side_effect = _track(
                "boto3.client(secretsmanager)", MagicMock()
            )
            result = attachment_loader._run_ecs_setup(arguments, run_id="test-run")

        return {
            "result": result,
            "calls": calls,
            "configure_env": configure_env,
            "validate_oracle": validate_oracle,
            "create_s3": create_s3,
            "validate_bucket": validate_bucket,
            "run_report": run_report,
        }

    def test_ecs_list_awards_with_attachments_reaches_the_report(self) -> None:
        result = self._run_ecs_setup(
            list_awards_with_attachments=True, limit=25
        )

        self.assertIn("_run_list_awards_with_attachments", result["calls"])
        self.assertTrue(result["result"])
        result["run_report"].assert_called_once()
        self.assertEqual(result["run_report"].call_args.args[1], 25)

    def test_ecs_list_awards_with_attachments_never_contacts_oracle(
        self,
    ) -> None:
        result = self._run_ecs_setup(
            list_awards_with_attachments=True, limit=25
        )

        self.assertNotIn("validate_oracle_reachable", result["calls"])
        result["validate_oracle"].assert_not_called()
        result["configure_env"].assert_called_once()
        self.assertFalse(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_ecs_list_awards_with_attachments_never_contacts_s3(self) -> None:
        result = self._run_ecs_setup(
            list_awards_with_attachments=True, limit=25
        )

        result["create_s3"].assert_not_called()
        result["validate_bucket"].assert_not_called()

    def test_main_routes_ecs_list_awards_with_attachments_through_ecs_setup(
        self,
    ) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(
                attachment_loader, "_run_ecs_setup", return_value=True
            ) as run_ecs_setup,
            patch.object(
                attachment_loader, "create_postgres_engine"
            ) as create_engine,
        ):
            parse_args.return_value = MagicMock(
                list_awards_with_attachments=True,
                ecs=True,
                limit=25,
            )
            attachment_loader.main()

        run_ecs_setup.assert_called_once()
        # main()'s own local (non-ecs) shortcut must not also fire -
        # _run_ecs_setup owns the PostgreSQL connection for --ecs runs.
        create_engine.assert_not_called()


class ContentTypeOverflowRegressionTest(unittest.TestCase):
    """Regression coverage for the batch-10 attachment-loader failure:
    PostgreSQL rejected archive.attachment_object.content_type because a
    real KCOEUS.ATTACHMENT_FILE.CONTENT_TYPE value exceeded VARCHAR(200)
    and looked like escaped JSON/string content rather than a MIME type.
    Root cause: the archive column was narrower than what Oracle's source
    data actually contains - not the extraction SQL or the Python
    mapping, both confirmed to be straight, untransformed passthroughs.
    See database/migrations/V054__widen_attachment_object_content_type.sql.

    This is a synthetic reproduction of the observed failure shape (long,
    JSON-like, not a MIME type) - the real Oracle FILE_ID=2812 value was
    not available in this environment (no Oracle/VPN connectivity here;
    see this investigation's findings for how to pull it via --file-id
    2812 on a VPN-connected machine). Swap in the real value here if it's
    later obtained, without changing what the test asserts.
    """

    # Representative of "escaped JSON/string content" rather than a MIME
    # type - deliberately > 200 characters, the exact failure mode
    # reported for batch 10.
    OVERSIZED_CONTENT_TYPE = (
        '{"contentType":"application/octet-stream",'
        '"originalRequest":"multipart/form-data; '
        'boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW",'
        '"errorDetail":"escaped \\"nested\\" string content that a real '
        'MIME type value should never contain, well past two hundred '
        'characters in length to reproduce the VARCHAR(200) overflow"}'
    )

    def setUp(self) -> None:
        self.assertGreater(
            len(self.OVERSIZED_CONTENT_TYPE),
            200,
            "fixture must actually exceed the old VARCHAR(200) bound",
        )

    def test_extraction_sql_maps_content_type_as_a_straight_passthrough(
        self,
    ) -> None:
        sql = attachment_loader.FILES_ORACLE_SQL.read_text(encoding="utf-8")

        self.assertIn("af.CONTENT_TYPE", sql)
        self.assertIn("AS content_type", sql)
        # Guard against a future regression where something gets
        # concatenated onto CONTENT_TYPE instead of selecting it as-is.
        content_type_line = next(
            line for line in sql.splitlines() if "content_type" in line.lower()
        )
        self.assertIn("af.CONTENT_TYPE", content_type_line)
        self.assertNotIn("||", content_type_line)

    def test_migration_widens_content_type_to_text_not_a_larger_varchar(
        self,
    ) -> None:
        migration_path = (
            attachment_loader.PROJECT_ROOT
            / "database"
            / "migrations"
            / "V054__widen_attachment_object_content_type.sql"
        )
        self.assertTrue(
            migration_path.is_file(),
            f"expected migration at {migration_path}",
        )

        migration_sql = migration_path.read_text(encoding="utf-8")
        # Only the executable statement matters here - the file's own
        # explanatory comments mention VARCHAR(200)/VARCHAR(N) by name,
        # which would otherwise false-positive a naive whole-file check.
        executable_lines = "\n".join(
            line
            for line in migration_sql.splitlines()
            if line.strip() and not line.strip().startswith("--")
        )
        self.assertIn("ALTER COLUMN content_type TYPE TEXT", executable_lines)
        # Never re-narrow to some other arbitrary VARCHAR(N) - the whole
        # point is not guessing a new, still-arbitrary upper bound.
        self.assertNotIn("VARCHAR", executable_lines)

    def test_upsert_never_truncates_an_oversized_content_type(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.one_or_none.return_value = {
            "inserted": True
        }
        file_row = pd.Series(
            {
                "file_id": 2812,
                "file_data_id": None,
                "file_name": "attachment.bin",
                "content_type": self.OVERSIZED_CONTENT_TYPE,
                "blob_source": "INLINE",
                "upload_status": "PENDING",
                "upload_attempts": 0,
            }
        )

        attachment_loader.upsert_attachment_object(connection, file_row, load_id=1)

        bound_params = connection.execute.call_args.args[1]
        self.assertEqual(
            bound_params["content_type"], self.OVERSIZED_CONTENT_TYPE
        )
        self.assertEqual(
            len(bound_params["content_type"]), len(self.OVERSIZED_CONTENT_TYPE)
        )


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
