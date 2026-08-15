from __future__ import annotations

import hashlib
import time
from collections.abc import Callable
from pathlib import Path

import oracledb
from loguru import logger

from archive_etl.attachments.models import MissingBlobError
from archive_etl.config.settings import require_oracle_environment


def retry[T](
    operation: Callable[[], T],
    *,
    attempts: int,
    operation_name: str,
) -> T:
    for attempt in range(1, attempts + 1):
        try:
            return operation()
        except MissingBlobError:
            raise
        except Exception:
            if attempt == attempts:
                raise
            delay = min(2 ** (attempt - 1), 30)
            logger.warning(
                "{} failed on attempt {}/{}; retrying in {} seconds",
                operation_name,
                attempt,
                attempts,
                delay,
            )
            time.sleep(delay)
    raise AssertionError("retry loop exited unexpectedly")


class OracleBlobReader:
    table_name: str
    id_column: str
    blob_column: str
    reference_name: str

    def __init__(self, attempts: int, chunk_size: int) -> None:
        self.attempts = attempts
        self.chunk_size = chunk_size
        self.connection: oracledb.Connection | None = None

    def connect(self) -> None:
        credentials = require_oracle_environment()
        self.connection = oracledb.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        )

    def close(self) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def stream_to_path(
        self,
        file_data_id: str,
        destination: Path,
    ) -> tuple[int, str]:
        return self._stream_from(
            table_name=self.table_name,
            id_column=self.id_column,
            blob_column=self.blob_column,
            reference_name=self.reference_name,
            reference_value=file_data_id,
            destination=destination,
        )

    def _stream_from(
        self,
        *,
        table_name: str,
        id_column: str,
        blob_column: str,
        reference_name: str,
        reference_value: str,
        destination: Path,
    ) -> tuple[int, str]:
        """Shared retry/streaming/hashing core every OracleBlobReader
        subclass ultimately runs through - table/id_column/blob_column
        are parameters here (not just class attributes) specifically so
        a subclass can pick a different source PER CALL (see
        InlineOrExternalBlobReader below) without duplicating this loop."""

        def operation() -> tuple[int, str]:
            try:
                if self.connection is None:
                    self.connect()

                assert self.connection is not None
                with self.connection.cursor() as cursor:
                    cursor.execute(
                        f"""
                        SELECT source.{blob_column}
                        FROM KCOEUS.{table_name} source
                        WHERE source.{id_column} = :file_reference
                        """,
                        file_reference=reference_value,
                    )
                    row = cursor.fetchone()
                    if row is None or row[0] is None:
                        raise MissingBlobError(
                            f"{reference_name} row or BLOB missing for "
                            f"{reference_value}"
                        )

                    blob = row[0]
                    digest = hashlib.sha256()
                    byte_size = 0
                    offset = 1

                    with destination.open("wb") as output:
                        while True:
                            chunk = blob.read(offset, self.chunk_size)
                            if not chunk:
                                break
                            output.write(chunk)
                            digest.update(chunk)
                            byte_size += len(chunk)
                            offset += len(chunk)

                    return byte_size, digest.hexdigest()
            except MissingBlobError:
                raise
            except Exception:
                self.close()
                raise

        return retry(
            operation,
            attempts=self.attempts,
            operation_name="Oracle BLOB read",
        )


class FileDataBlobReader(OracleBlobReader):
    table_name = "FILE_DATA"
    id_column = "ID"
    blob_column = "DATA"
    reference_name = "FILE_DATA"


class AttachmentFileBlobReader(OracleBlobReader):
    table_name = "ATTACHMENT_FILE"
    id_column = "FILE_ID"
    blob_column = "FILE_DATA"
    reference_name = "ATTACHMENT_FILE"


# blob_source values a plugin's per-record classification may emit -
# INLINE keys the ATTACHMENT_FILE.FILE_DATA read (via the row's numeric
# FILE_ID); EXTERNAL keys the FILE_DATA.DATA read (via the row's UUID
# FILE_DATA_ID, joined through ATTACHMENT_FILE.FILE_DATA_ID = FILE_DATA.ID).
# A row with neither is never routed here at all - see
# InlineOrExternalBlobReader.encode_reference's MISSING case, which
# returns None so callers take the existing "no file_data_id" path
# instead (archive_etl.attachments.runner.process_attachment).
BLOB_SOURCE_INLINE = "INLINE"
BLOB_SOURCE_EXTERNAL = "EXTERNAL"


class InlineOrExternalBlobReader(OracleBlobReader):
    """Resolves a physical file's BLOB from whichever of
    ATTACHMENT_FILE.FILE_DATA (INLINE) or FILE_DATA.DATA (EXTERNAL, via
    FILE_DATA_ID) a *specific row* actually has content in - unlike
    AttachmentFileBlobReader/FileDataBlobReader, which each only ever
    read from one fixed table for every row in a run.

    Needed because a single Kuali module (e.g. Negotiation, via
    ATTACHMENT_FILE) can have some attachments stored inline and others
    externally, decided per physical file, not per module - see
    load_award_attachments.py's resolve_blob_location()/BlobLocation for
    the equivalent, already-proven dual-source logic this mirrors (fixed
    by V072 there: FILE_DATA_ID is a UUID string, never int()-coerced).

    Reuses OracleBlobReader._stream_from for the actual retry/streaming/
    hashing work in both cases - this class only ever decides which
    table/column pair to pass it, never re-implements the read itself.
    """

    reference_name = "ATTACHMENT_FILE/FILE_DATA"
    # Never actually used directly (stream_to_path is overridden below to
    # pick a table per call) - set only because OracleBlobReader.__init__
    # doesn't require them, but leaving these unset could be misread as
    # "this class reads ATTACHMENT_FILE" by anything introspecting them.
    table_name = ""
    id_column = ""
    blob_column = ""

    @staticmethod
    def encode_reference(
        blob_source: str | None,
        *,
        file_id: int | str,
        file_data_id: str | None,
    ) -> str | None:
        """Builds the single string stream_to_path receives, encoding
        both which source to use and that source's own key - so the
        shared runner.process_attachment/plugin.file_data_id() call
        sites need no changes to support per-row dual-source resolution.
        Returns None for a genuinely missing row (blob_source is neither
        INLINE nor EXTERNAL), which callers must treat as "no blob" -
        never invent a reference for that case."""
        if blob_source == BLOB_SOURCE_INLINE:
            return f"{BLOB_SOURCE_INLINE}:{file_id}"
        if blob_source == BLOB_SOURCE_EXTERNAL:
            if file_data_id is None or not str(file_data_id).strip():
                return None
            return f"{BLOB_SOURCE_EXTERNAL}:{file_data_id.strip()}"
        return None

    def stream_to_path(
        self,
        reference: str,
        destination: Path,
    ) -> tuple[int, str]:
        blob_source, separator, value = reference.partition(":")
        if not separator:
            raise ValueError(
                f"malformed InlineOrExternalBlobReader reference: "
                f"{reference!r} (expected 'INLINE:<file_id>' or "
                f"'EXTERNAL:<file_data_id>')"
            )
        if blob_source == BLOB_SOURCE_INLINE:
            return self._stream_from(
                table_name="ATTACHMENT_FILE",
                id_column="FILE_ID",
                blob_column="FILE_DATA",
                reference_name="ATTACHMENT_FILE",
                reference_value=value,
                destination=destination,
            )
        if blob_source == BLOB_SOURCE_EXTERNAL:
            # value is FILE_DATA.ID, a UUID string - never int()-coerced,
            # passed through exactly as encode_reference received it.
            return self._stream_from(
                table_name="FILE_DATA",
                id_column="ID",
                blob_column="DATA",
                reference_name="FILE_DATA",
                reference_value=value,
                destination=destination,
            )
        raise ValueError(
            f"unrecognized blob_source {blob_source!r} in reference "
            f"{reference!r}"
        )
