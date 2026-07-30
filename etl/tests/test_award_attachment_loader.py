from __future__ import annotations

import hashlib
import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import load_award_attachments as attachment_loader


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
                limit=None, dry_run=True, upload=False, file_id=None, ecs=False
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
                limit=10, dry_run=True, upload=False, file_id=None, ecs=False
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
                limit=10, dry_run=True, upload=False, file_id=None, ecs=False
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
                limit=None, dry_run=False, upload=False, file_id=None, ecs=False
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
                limit=10, dry_run=True, upload=False, file_id=None, ecs=False
            )
            attachment_loader.main()

        run_upload.assert_not_called()

    def test_main_runs_upload_when_the_flag_is_given(self) -> None:
        with (
            patch.object(attachment_loader, "parse_args") as parse_args,
            patch.object(attachment_loader, "_run_upload") as run_upload,
        ):
            parse_args.return_value = MagicMock(upload=True, ecs=False)
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
                upload=False, file_id=9001, limit=None, dry_run=True, ecs=False
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
                upload=False, file_id=9001, limit=10, dry_run=True, ecs=False
            )
            attachment_loader.main()

        run_lookup.assert_called_once_with(9001)
        read_sample.assert_not_called()


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
