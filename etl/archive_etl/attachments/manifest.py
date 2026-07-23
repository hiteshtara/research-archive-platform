from __future__ import annotations

import sqlite3
import json
from pathlib import Path
from typing import Any, Iterator


MANIFEST_COLUMNS = (
    "attachment_id",
    "subaward_id",
    "subaward_code",
    "sequence_number",
    "file_data_id",
    "original_file_name",
    "mime_type",
    "document_id",
    "attachment_source_update_timestamp",
    "attachment_last_update_timestamp",
    "s3_bucket",
    "s3_key",
    "byte_size",
    "sha256",
    "archive_status",
    "archived_timestamp",
    "error_message",
    "manifest_updated_at",
)


class ManifestStore:
    """Current SQLite contract; intentionally remains Subaward-compatible."""

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS attachment_manifest (
                attachment_id INTEGER PRIMARY KEY,
                subaward_id INTEGER NOT NULL,
                subaward_code TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                file_data_id TEXT,
                original_file_name TEXT,
                mime_type TEXT,
                document_id INTEGER,
                attachment_source_update_timestamp TEXT,
                attachment_last_update_timestamp TEXT,
                s3_bucket TEXT,
                s3_key TEXT,
                byte_size INTEGER,
                sha256 TEXT,
                archive_status TEXT NOT NULL,
                archived_timestamp TEXT,
                error_message TEXT,
                manifest_updated_at TEXT NOT NULL
            )
            """
        )
        self.connection.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_attachment_manifest_subaward
                ON attachment_manifest (subaward_id, attachment_id)
            """
        )
        self.connection.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_attachment_manifest_file_data
                ON attachment_manifest (file_data_id)
            """
        )
        self.connection.commit()

    def get(self, attachment_id: int) -> dict[str, Any] | None:
        row = self.connection.execute(
            """
            SELECT *
            FROM attachment_manifest
            WHERE attachment_id = ?
            """,
            (attachment_id,),
        ).fetchone()
        return dict(row) if row else None

    def upsert(self, values: dict[str, Any]) -> None:
        placeholders = ", ".join("?" for _ in MANIFEST_COLUMNS)
        assignments = ", ".join(
            f"{column} = excluded.{column}"
            for column in MANIFEST_COLUMNS
            if column != "attachment_id"
        )
        self.connection.execute(
            f"""
            INSERT INTO attachment_manifest (
                {", ".join(MANIFEST_COLUMNS)}
            )
            VALUES ({placeholders})
            ON CONFLICT (attachment_id) DO UPDATE SET
                {assignments}
            """,
            [values.get(column) for column in MANIFEST_COLUMNS],
        )
        self.connection.commit()

    def rows(
        self,
        record_id: int | None = None,
        limit: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        sql = "SELECT * FROM attachment_manifest"
        parameters: list[Any] = []
        if record_id is not None:
            sql += " WHERE subaward_id = ?"
            parameters.append(record_id)
        sql += " ORDER BY attachment_id"
        if limit is not None:
            sql += " LIMIT ?"
            parameters.append(limit)

        for row in self.connection.execute(sql, parameters):
            yield dict(row)

    def close(self) -> None:
        self.connection.close()


class FlexibleManifestStore:
    """SQLite manifest for modules without a PostgreSQL destination yet."""

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS attachment_manifest (
                attachment_id INTEGER PRIMARY KEY,
                record_id INTEGER NOT NULL,
                manifest_json TEXT NOT NULL
            )
            """
        )
        self.connection.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_attachment_manifest_record
                ON attachment_manifest (record_id, attachment_id)
            """
        )
        self.connection.commit()

    def get(self, attachment_id: int) -> dict[str, Any] | None:
        row = self.connection.execute(
            """
            SELECT manifest_json
            FROM attachment_manifest
            WHERE attachment_id = ?
            """,
            (attachment_id,),
        ).fetchone()
        return json.loads(row["manifest_json"]) if row else None

    def upsert(self, values: dict[str, Any]) -> None:
        self.connection.execute(
            """
            INSERT INTO attachment_manifest (
                attachment_id,
                record_id,
                manifest_json
            )
            VALUES (?, ?, ?)
            ON CONFLICT (attachment_id) DO UPDATE SET
                record_id = excluded.record_id,
                manifest_json = excluded.manifest_json
            """,
            (
                values["attachment_id"],
                values["record_id"],
                json.dumps(values, sort_keys=True),
            ),
        )
        self.connection.commit()

    def rows(
        self,
        record_id: int | None = None,
        limit: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        sql = "SELECT manifest_json FROM attachment_manifest"
        parameters: list[Any] = []
        if record_id is not None:
            sql += " WHERE record_id = ?"
            parameters.append(record_id)
        sql += " ORDER BY attachment_id"
        if limit is not None:
            sql += " LIMIT ?"
            parameters.append(limit)
        for row in self.connection.execute(sql, parameters):
            yield json.loads(row["manifest_json"])

    def close(self) -> None:
        self.connection.close()
