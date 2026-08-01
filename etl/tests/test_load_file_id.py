"""Regression tests for --load-file-id: a bounded, idempotent metadata
load for exactly one physical file_id and its reference rows.

Added to fix a real gap: Oracle can contain FILE_ID=1 while
archive.attachment_object has no row for it at all (e.g. a fresh
database, or a file added to Oracle after the last full load) - in that
state, `--upload --file-id 1` correctly selects zero candidates,
because there is nothing in PostgreSQL to select. --load-file-id UPSERTs
just that one file_id's metadata (and its award_attachment reference
rows) so a subsequent --upload has something to find, without
truncating or replacing the full tables the way the ordinary metadata
load does.

Runs the real UPSERT SQL (INSERT ... ON CONFLICT ... RETURNING
(xmax = 0)) against a real, throwaway PostgreSQL database - the
insert/update/unchanged distinction depends on genuine Postgres
semantics a mock cannot exercise correctly. Skips entirely if no local
PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import load_award_attachments as attachment_loader
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")


def _maintenance_engine() -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{MAINTENANCE_DB}"
    )


def _postgres_available() -> bool:
    try:
        engine = _maintenance_engine()
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        engine.dispose()
    except Exception:
        return False
    return True


def _file_row(**overrides) -> pd.DataFrame:
    row = {
        "file_id": 1,
        "file_data_id": None,
        "file_name": "Agreement.pdf",
        "content_type": "application/pdf",
        "blob_source": "INLINE",
        "file_size_bytes": 12345,
        "oracle_update_timestamp": "2025-01-01",
        "oracle_update_user": "kcuser",
    }
    row.update(overrides)
    return pd.DataFrame([row])


def _reference_row(**overrides) -> pd.DataFrame:
    row = {
        "award_attachment_id": 501,
        "award_id": 1001,
        "award_number": "A-1",
        "sequence_number": 1,
        "document_id": "D1",
        "file_id": 1,
        "type_code": "T1",
        "description": "desc",
        "document_status_code": "S1",
        "oracle_update_timestamp": "2025-01-01",
        "oracle_update_user": "kcuser",
    }
    row.update(overrides)
    return pd.DataFrame([row])


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class LoadFileIdRegressionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db_name = f"pytest_load_file_id_{uuid.uuid4().hex[:12]}"

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'CREATE DATABASE "{self.db_name}"'))
        maintenance.dispose()

        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
            f"{POSTGRES_PORT}/{self.db_name}"
        )
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def _row(self, table: str, **where) -> dict:
        clause = " AND ".join(f"{key} = :{key}" for key in where)
        with self.engine.connect() as connection:
            return dict(
                connection.execute(
                    text(f"SELECT * FROM archive.{table} WHERE {clause}"),
                    where,
                )
                .mappings()
                .one()
            )

    def test_first_load_inserts_file_and_reference(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            report = attachment_loader._run_load_file_id(self.engine, 1)

        self.assertEqual(report, {
            "file_id": 1,
            "inserted": 2,
            "updated": 0,
            "unchanged": 0,
            "missing": 0,
        })

        file_row = self._row("attachment_object", file_id=1)
        self.assertEqual(file_row["file_name"], "Agreement.pdf")
        self.assertEqual(file_row["upload_status"], "PENDING")

        reference_row = self._row("award_attachment", award_attachment_id=501)
        self.assertEqual(reference_row["award_number"], "A-1")

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 1)
            report = attachment_loader._run_load_file_id(self.engine, 1)

        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(report["unchanged"], 2)
        self.assertEqual(report["missing"], 0)

    def test_existing_upload_state_is_preserved_across_reload(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 1)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.attachment_object "
                    "SET upload_status = 'UPLOADED', upload_attempts = 1, "
                    "s3_bucket = 'my-bucket', s3_key = 'my-key' "
                    "WHERE file_id = 1"
                )
            )

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            report = attachment_loader._run_load_file_id(self.engine, 1)

        self.assertEqual(report["unchanged"], 2)

        file_row = self._row("attachment_object", file_id=1)
        self.assertEqual(file_row["upload_status"], "UPLOADED")
        self.assertEqual(file_row["upload_attempts"], 1)
        self.assertEqual(file_row["s3_bucket"], "my-bucket")
        self.assertEqual(file_row["s3_key"], "my-key")

    def test_metadata_change_in_oracle_produces_an_update_not_an_overwrite_of_upload_state(
        self,
    ) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 1)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.attachment_object "
                    "SET upload_status = 'UPLOADED' WHERE file_id = 1"
                )
            )

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(file_name="Renamed.pdf"),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            report = attachment_loader._run_load_file_id(self.engine, 1)

        self.assertEqual(report["updated"], 1)
        self.assertEqual(report["unchanged"], 1)

        file_row = self._row("attachment_object", file_id=1)
        self.assertEqual(file_row["file_name"], "Renamed.pdf")
        self.assertEqual(file_row["upload_status"], "UPLOADED")

    def test_new_reference_row_added_for_an_already_loaded_file_is_inserted(
        self,
    ) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 1)

        two_references = pd.concat(
            [_reference_row(), _reference_row(award_attachment_id=502)],
            ignore_index=True,
        )
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=two_references,
            ),
        ):
            report = attachment_loader._run_load_file_id(self.engine, 1)

        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["unchanged"], 2)

        with self.engine.connect() as connection:
            count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.award_attachment "
                    "WHERE file_id = 1"
                )
            ).scalar_one()
        self.assertEqual(count, 2)

    def test_file_not_found_in_oracle_reports_missing_and_writes_nothing(
        self,
    ) -> None:
        with patch.object(
            attachment_loader,
            "read_files_matching_ids",
            return_value=pd.DataFrame(),
        ):
            report = attachment_loader._run_load_file_id(self.engine, 999)

        self.assertEqual(
            report,
            {"file_id": 999, "inserted": 0, "updated": 0, "unchanged": 0, "missing": 1},
        )

        with self.engine.connect() as connection:
            count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.attachment_object "
                    "WHERE file_id = 999"
                )
            ).scalar_one()
        self.assertEqual(count, 0)

    def test_dry_run_reports_accurate_counts_but_persists_nothing(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            report = attachment_loader._run_load_file_id(
                self.engine, 1, dry_run=True
            )

        self.assertEqual(report["inserted"], 2)

        with self.engine.connect() as connection:
            file_count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.attachment_object "
                    "WHERE file_id = 1"
                )
            ).scalar_one()
            load_run_count = connection.execute(
                text("SELECT COUNT(*) FROM archive.load_run")
            ).scalar_one()
        self.assertEqual(file_count, 0)
        self.assertEqual(load_run_count, 0)

    def test_never_creates_an_s3_client(self) -> None:
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
            patch.object(attachment_loader, "create_s3_client") as create_s3,
        ):
            attachment_loader._run_load_file_id(self.engine, 1)

        create_s3.assert_not_called()

    def test_does_not_truncate_unrelated_existing_rows(self) -> None:
        # A second, unrelated file already loaded (as the full load or a
        # prior --load-file-id would leave it) must survive untouched.
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(file_id=2, file_name="Other.pdf"),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(
                    award_attachment_id=777, file_id=2
                ),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 2)

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 1)

        with self.engine.connect() as connection:
            total_files = connection.execute(
                text("SELECT COUNT(*) FROM archive.attachment_object")
            ).scalar_one()
        self.assertEqual(total_files, 2)


if __name__ == "__main__":
    unittest.main()
