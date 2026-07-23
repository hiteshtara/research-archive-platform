from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from archive_etl.attachments.oracle_blob import (
    AttachmentFileBlobReader,
    FileDataBlobReader,
)
from archive_etl.attachments.plugins.award import AwardAttachmentPlugin
from archive_etl.attachments.plugins.irb import IrbProtocolAttachmentPlugin
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
        self.parameters = {}

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


class OracleBlobReaderTest(unittest.TestCase):
    def test_plugins_select_the_confirmed_reader(self) -> None:
        self.assertIsInstance(
            SubawardAttachmentPlugin().create_blob_reader(1, 4),
            FileDataBlobReader,
        )
        for plugin in (
            AwardAttachmentPlugin(),
            NegotiationAttachmentPlugin(),
            IrbProtocolAttachmentPlugin(),
        ):
            with self.subTest(module=plugin.module_name):
                self.assertIsInstance(
                    plugin.create_blob_reader(1, 4),
                    AttachmentFileBlobReader,
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

    def _assert_reader(
        self,
        reader_type,
        table: str,
        predicate: str,
        blob_column: str,
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
                        "FILE-1",
                        destination,
                    )
                    archived_payload = destination.read_bytes()

        self.assertIn(table, cursor.sql)
        self.assertIn(predicate, cursor.sql)
        self.assertIn(blob_column, cursor.sql)
        self.assertEqual(cursor.parameters, {"file_reference": "FILE-1"})
        self.assertEqual(byte_size, len(payload))
        self.assertEqual(sha256, hashlib.sha256(payload).hexdigest())
        self.assertEqual(archived_payload, payload)


if __name__ == "__main__":
    unittest.main()
