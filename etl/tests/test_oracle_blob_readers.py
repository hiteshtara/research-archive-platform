from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from archive_etl.attachments.oracle_blob import (
    AttachmentFileBlobReader,
    FileDataBlobReader,
    InlineOrExternalBlobReader,
)
from archive_etl.attachments.plugins.award import AwardAttachmentPlugin
from archive_etl.attachments.plugins.negotiation import (
    NegotiationAttachmentPlugin,
)
from archive_etl.attachments.plugins.subaward import SubawardAttachmentPlugin


class FakeBlob:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload

    def read(self, offset: int, size: int) -> bytes:
        start = offset - 1
        return self.payload[start:start + size]


class FakeCursor:
    def __init__(self, payload: bytes) -> None:
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
        return (FakeBlob(self.payload),)


class FakeConnection:
    def __init__(self, cursor: FakeCursor) -> None:
        self.test_cursor = cursor

    def cursor(self) -> FakeCursor:
        return self.test_cursor

    def close(self) -> None:
        pass


class FlakyCursor(FakeCursor):
    """Raises on its first N execute() calls, then behaves like
    FakeCursor - proves stream_to_path's shared retry() wrapper covers
    InlineOrExternalBlobReader exactly like every other reader, not just
    by code inspection."""

    def __init__(self, payload: bytes, failures_before_success: int) -> None:
        super().__init__(payload)
        self.remaining_failures = failures_before_success
        self.attempts_made = 0

    def execute(self, sql: str, **parameters) -> None:
        self.attempts_made += 1
        if self.remaining_failures > 0:
            self.remaining_failures -= 1
            raise RuntimeError("simulated transient Oracle error")
        super().execute(sql, **parameters)


class OracleBlobReaderTest(unittest.TestCase):
    def test_plugins_select_the_confirmed_reader(self) -> None:
        self.assertIsInstance(
            SubawardAttachmentPlugin().create_blob_reader(1, 4),
            FileDataBlobReader,
        )
        # Award's generic plugin path is unchanged by the Negotiation
        # external-BLOB fix - tracked as a separate follow-up
        # investigation, not touched here (see
        # docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md).
        self.assertIsInstance(
            AwardAttachmentPlugin().create_blob_reader(1, 4),
            AttachmentFileBlobReader,
        )
        # Negotiation attachments are inline for some rows and external
        # for others (decided per physical file, not per module) - see
        # oracle_blob.py's InlineOrExternalBlobReader.
        self.assertIsInstance(
            NegotiationAttachmentPlugin().create_blob_reader(1, 4),
            InlineOrExternalBlobReader,
        )

    def test_file_data_blob_reader_uses_file_data_id(self) -> None:
        self._assert_reader(
            FileDataBlobReader,
            "KCOEUS.FILE_DATA",
            "source.ID = :file_reference",
            "source.DATA",
        )

    def test_attachment_file_blob_reader_uses_file_id(self) -> None:
        self._assert_reader(
            AttachmentFileBlobReader,
            "KCOEUS.ATTACHMENT_FILE",
            "source.FILE_ID = :file_reference",
            "source.FILE_DATA",
        )

    def test_inline_or_external_reader_resolves_inline_reference(
        self,
    ) -> None:
        self._assert_reader(
            InlineOrExternalBlobReader,
            "KCOEUS.ATTACHMENT_FILE",
            "source.FILE_ID = :file_reference",
            "source.FILE_DATA",
            reference="INLINE:164229",
            expected_parameter="164229",
        )

    def test_inline_or_external_reader_resolves_external_reference(
        self,
    ) -> None:
        # The UUID must survive byte-for-byte - never int()-coerced, per
        # V072's incident (see oracle_blob.py's InlineOrExternalBlobReader
        # docstring).
        uuid_value = "995577d2-b20f-4b10-a4aa-5bc0d32f64b4"
        self._assert_reader(
            InlineOrExternalBlobReader,
            "KCOEUS.FILE_DATA",
            "source.ID = :file_reference",
            "source.DATA",
            reference=f"EXTERNAL:{uuid_value}",
            expected_parameter=uuid_value,
        )

    def test_inline_or_external_reader_rejects_malformed_reference(
        self,
    ) -> None:
        reader = InlineOrExternalBlobReader(attempts=1, chunk_size=4)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "payload.bin"
            with self.assertRaises(ValueError):
                reader.stream_to_path("no-separator-here", destination)

    def test_inline_or_external_reader_rejects_unknown_blob_source(
        self,
    ) -> None:
        reader = InlineOrExternalBlobReader(attempts=1, chunk_size=4)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "payload.bin"
            with self.assertRaises(ValueError):
                reader.stream_to_path("BOGUS:123", destination)

    def test_encode_reference_inline(self) -> None:
        self.assertEqual(
            InlineOrExternalBlobReader.encode_reference(
                "INLINE", file_id=164229, file_data_id=None,
            ),
            "INLINE:164229",
        )

    def test_encode_reference_external_preserves_uuid_unchanged(
        self,
    ) -> None:
        uuid_value = "995577d2-b20f-4b10-a4aa-5bc0d32f64b4"
        self.assertEqual(
            InlineOrExternalBlobReader.encode_reference(
                "EXTERNAL", file_id=164229, file_data_id=uuid_value,
            ),
            f"EXTERNAL:{uuid_value}",
        )

    def test_encode_reference_inline_takes_precedence_when_both_present(
        self,
    ) -> None:
        # Oracle's own CASE expression already prefers INLINE when both
        # ATTACHMENT_FILE.FILE_DATA and a FILE_DATA_ID are present (see
        # export_negotiation_attachments.sql) - this proves the Python
        # side respects whatever blob_source Oracle classified the row
        # as, using FILE_ID (not the also-present FILE_DATA_ID) when
        # blob_source says INLINE.
        self.assertEqual(
            InlineOrExternalBlobReader.encode_reference(
                "INLINE",
                file_id=164229,
                file_data_id="995577d2-b20f-4b10-a4aa-5bc0d32f64b4",
            ),
            "INLINE:164229",
        )

    def test_encode_reference_external_with_no_file_data_id_is_missing(
        self,
    ) -> None:
        self.assertIsNone(
            InlineOrExternalBlobReader.encode_reference(
                "EXTERNAL", file_id=164229, file_data_id=None,
            )
        )
        self.assertIsNone(
            InlineOrExternalBlobReader.encode_reference(
                "EXTERNAL", file_id=164229, file_data_id="   ",
            )
        )

    def test_inline_or_external_reader_retries_transient_failure(
        self,
    ) -> None:
        payload = b"streamed in chunks"
        cursor = FlakyCursor(payload, failures_before_success=2)
        connection = FakeConnection(cursor)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "payload.bin"
            with patch(
                "archive_etl.attachments.oracle_blob.oracledb.connect",
                return_value=connection,
            ), patch(
                "archive_etl.attachments.oracle_blob.time.sleep"
            ), patch.dict(
                "os.environ",
                {
                    "ORACLE_USER": "user",
                    "ORACLE_PASSWORD": "password",
                    "ORACLE_DSN": "dsn",
                },
            ):
                reader = InlineOrExternalBlobReader(attempts=3, chunk_size=4)
                byte_size, sha256 = reader.stream_to_path(
                    "EXTERNAL:995577d2-b20f-4b10-a4aa-5bc0d32f64b4",
                    destination,
                )

        self.assertEqual(cursor.attempts_made, 3)
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())

    def test_encode_reference_missing_blob_source_is_missing(self) -> None:
        for blob_source in (None, "", "MISSING"):
            with self.subTest(blob_source=blob_source):
                self.assertIsNone(
                    InlineOrExternalBlobReader.encode_reference(
                        blob_source,
                        file_id=164229,
                        file_data_id="995577d2-b20f-4b10-a4aa-5bc0d32f64b4",
                    )
                )

    def _assert_reader(
        self,
        reader_type,
        table: str,
        predicate: str,
        blob_column: str,
        *,
        reference: str = "FILE-1",
        expected_parameter: str = "FILE-1",
    ) -> None:
        payload = b"streamed in chunks"
        cursor = FakeCursor(payload)
        connection = FakeConnection(cursor)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "payload.bin"
            with patch(
                "archive_etl.attachments.oracle_blob.oracledb.connect",
                return_value=connection,
            ):
                with patch.dict(
                    "os.environ",
                    {
                        "ORACLE_USER": "user",
                        "ORACLE_PASSWORD": "password",
                        "ORACLE_DSN": "dsn",
                    },
                ):
                    reader = reader_type(attempts=1, chunk_size=4)
                    byte_size, sha256 = reader.stream_to_path(
                        reference,
                        destination,
                    )
                    archived_payload = destination.read_bytes()

        self.assertIn(table, cursor.sql)
        self.assertIn(predicate, cursor.sql)
        self.assertIn(blob_column, cursor.sql)
        self.assertEqual(
            cursor.parameters, {"file_reference": expected_parameter}
        )
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())
        self.assertEqual(archived_payload, payload)


if __name__ == "__main__":
    unittest.main()
