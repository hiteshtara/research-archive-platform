from __future__ import annotations

import hashlib
import os
import time
from pathlib import Path
from typing import Callable, TypeVar

import oracledb
from loguru import logger

from archive_etl.attachments.models import MissingBlobError


T = TypeVar("T")


def retry(
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
        required = ["ORACLE_USER", "ORACLE_PASSWORD", "ORACLE_DSN"]
        missing = [name for name in required if not os.getenv(name)]
        if missing:
            raise RuntimeError(
                "Missing Oracle environment variables: " + ", ".join(missing)
            )
        self.connection = oracledb.connect(
            user=os.environ["ORACLE_USER"],
            password=os.environ["ORACLE_PASSWORD"],
            dsn=os.environ["ORACLE_DSN"],
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
        def operation() -> tuple[int, str]:
            try:
                if self.connection is None:
                    self.connect()

                assert self.connection is not None
                with self.connection.cursor() as cursor:
                    cursor.execute(
                        f"""
                        SELECT source.{self.blob_column}
                        FROM KCOEUS.{self.table_name} source
                        WHERE source.{self.id_column} = :file_reference
                        """,
                        file_reference=file_data_id,
                    )
                    row = cursor.fetchone()
                    if row is None or row[0] is None:
                        raise MissingBlobError(
                            f"{self.reference_name} row or BLOB missing for "
                            f"{file_data_id}"
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
