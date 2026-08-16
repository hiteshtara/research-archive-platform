"""Real-PostgreSQL regression test for the 2026-08-16 real pilot-run
failure: psycopg.errors.UndefinedColumn: column "update_timestamp" of
relation "subaward_attachment" does not exist.

Root cause: _SUBAWARD_ATTACHMENT_COLUMNS used Oracle's own raw column
names (update_timestamp/update_user/ver_nbr/obj_id - see
oracle/subaward/export_subaward_attachments.sql) directly as the
archive.subaward_attachment (V018) INSERT's target column list, but
that table has no columns by those names - only
source_update_timestamp/source_update_user/source_version_number/
source_object_id. load_subawards_from_csv.py's own SOURCE_COLUMN_RENAMES
already established the correct mapping for the rest of Subaward's
business data; _upsert_subaward_attachments now reuses it unchanged
rather than reinventing it.

This had never fired before because Subaward binary-stage execution had
never actually run against real Postgres until the 2026-08-16 pilot
attempt (see the module's own docstring) - and every existing test of
this code path mocks either _upsert_subaward_attachments itself or the
connection it runs against (see test_subaward_attachment_pilot_scope.py),
so none of them could ever have caught a real column-name mismatch. This
file is deliberately the one place that does not mock the database
layer, mirroring test_v077_subaward_attachment_archive_migration.py's
and test_subaward_batch_selection_strategy_length.py's own established
"exercise the real schema" pattern for exactly this reason.

Only Oracle I/O is simulated (mocked) - the database layer, including
the real archive.subaward_attachment INSERT and its actual column
names, is never mocked. All fixtures are synthetic; none of this reads
or references real BU/3595 data or the real batch_id 218.
"""

from __future__ import annotations

import getpass
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import attachment_orchestrator as orch
from archive_etl.batch import framework as batch_framework
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]

_MIGRATION_VERSION_PATTERN = re.compile(r"^V(\d+)__")

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


def _git_tracked_migration_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", "database/migrations"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    paths = [REPO_ROOT / line for line in result.stdout.splitlines() if line.strip()]
    return [path for path in paths if path.exists()]


def _clean_migrations_dir() -> Path:
    tracked = _git_tracked_migration_files()
    destination = Path(tempfile.mkdtemp(prefix="clean_migrations_"))
    for source_path in tracked:
        shutil.copy2(source_path, destination / source_path.name)
    return destination


def _create_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'CREATE DATABASE "{name}"'))
    maintenance.dispose()


def _drop_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'DROP DATABASE IF EXISTS "{name}"'))
    maintenance.dispose()


def _database_engine(name: str) -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{name}"
    )


# Synthetic, Oracle-shaped raw column names throughout - matching
# oracle/subaward/export_subaward_attachments.sql's own aliases exactly
# (update_timestamp/update_user/ver_nbr/obj_id, not the renamed target
# names) - this is the exact shape _upsert_subaward_attachments receives
# in production, before its own internal rename.
def _synthetic_oracle_shaped_row(*, attachment_id: int, subaward_id: int, code: str) -> dict:
    return {
        "attachment_id": attachment_id,
        "subaward_id": subaward_id,
        "subaward_code": code,
        "sequence_number": 1,
        "attachment_type_code": 10,
        "attachment_type_description": "Subrecipient Proposal",
        "document_id": 1,
        "file_data_id": "99999999-0000-0000-0000-000000000001",
        "file_name": "synthetic-fixture.pdf",
        "mime_type": "application/pdf",
        "document_status_code": "A",
        "description": "Synthetic fixture attachment",
        "last_update_timestamp": datetime(2025, 1, 1, 12, 0, 0),
        "last_update_user": "synthetic_last_update_user",
        # Oracle's raw OJB optimistic-locking column names - the exact
        # ones the real incident's INSERT choked on.
        "update_timestamp": datetime(2025, 6, 15, 9, 30, 0),
        "update_user": "synthetic_update_user",
        "ver_nbr": 7,
        "obj_id": "11111111-2222-3333-4444-555555555555",
    }


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SubawardAttachmentMetadataColumnMappingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_colmap_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def _seed_core_subaward(self, *, subaward_id: int, code: str) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.subaward "
                    "(subaward_id, sequence_number, subaward_code) VALUES (:id, 1, :code)"
                ),
                {"id": subaward_id, "code": code},
            )

    def test_every_source_field_lands_in_the_correct_real_target_column(self) -> None:
        subaward_id, attachment_id, code = 980001, 980001, "COLMAP-TEST"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)
        raw = pd.DataFrame(
            [
                _synthetic_oracle_shaped_row(
                    attachment_id=attachment_id, subaward_id=subaward_id, code=code
                )
            ]
        )

        with self.engine.begin() as connection:
            result = orch._upsert_subaward_attachments(connection, raw)

        self.assertEqual(result["inserted"], 1)

        with self.engine.connect() as connection:
            row = dict(
                connection.execute(
                    text(
                        "SELECT source_update_timestamp, source_update_user, "
                        "source_version_number, source_object_id, "
                        "last_update_timestamp, last_update_user, "
                        "file_name, mime_type, description "
                        "FROM archive.subaward_attachment WHERE attachment_id = :id"
                    ),
                    {"id": attachment_id},
                ).mappings().one()
            )

        # The 4 renamed fields - the exact ones the real incident's
        # UndefinedColumn error was raised on.
        self.assertEqual(row["source_update_timestamp"], datetime(2025, 6, 15, 9, 30, 0))
        self.assertEqual(row["source_update_user"], "synthetic_update_user")
        self.assertEqual(row["source_version_number"], 7)
        self.assertEqual(row["source_object_id"], "11111111-2222-3333-4444-555555555555")

        # Unchanged-name fields - same source and target column name,
        # no rename needed or applied.
        self.assertEqual(row["last_update_timestamp"], datetime(2025, 1, 1, 12, 0, 0))
        self.assertEqual(row["last_update_user"], "synthetic_last_update_user")

        # A few already-correct plain fields, as a sanity check that the
        # rename didn't disturb anything it shouldn't have.
        self.assertEqual(row["file_name"], "synthetic-fixture.pdf")
        self.assertEqual(row["mime_type"], "application/pdf")
        self.assertEqual(row["description"], "Synthetic fixture attachment")

        # Also proves the archive-state PENDING row was created alongside
        # (this INSERT never ran at all in the real incident, since the
        # metadata INSERT raised first).
        with self.engine.connect() as connection:
            archive_status = connection.execute(
                text(
                    "SELECT archive_status FROM archive.subaward_attachment_archive "
                    "WHERE attachment_id = :id"
                ),
                {"id": attachment_id},
            ).scalar()
        self.assertEqual(archive_status, "PENDING")

    def test_rerunning_the_upsert_is_idempotent(self) -> None:
        subaward_id, attachment_id, code = 980002, 980002, "COLMAP-IDEMPOTENT-TEST"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)
        raw = pd.DataFrame(
            [
                _synthetic_oracle_shaped_row(
                    attachment_id=attachment_id, subaward_id=subaward_id, code=code
                )
            ]
        )

        with self.engine.begin() as connection:
            first = orch._upsert_subaward_attachments(connection, raw)
        with self.engine.begin() as connection:
            second = orch._upsert_subaward_attachments(connection, raw)

        self.assertEqual(first["inserted"], 1)
        self.assertEqual(second["updated"], 1)

        with self.engine.connect() as connection:
            row_count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.subaward_attachment WHERE attachment_id = :id"
                ),
                {"id": attachment_id},
            ).scalar()
            archive_row_count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.subaward_attachment_archive "
                    "WHERE attachment_id = :id"
                ),
                {"id": attachment_id},
            ).scalar()
        self.assertEqual(row_count, 1)
        # ON CONFLICT ... DO NOTHING for the archive-state row - a rerun
        # never creates a second archive-state row nor disturbs an
        # existing one's status.
        self.assertEqual(archive_row_count, 1)


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class Batch218StyleResumeWithoutDuplicationTest(unittest.TestCase):
    """Reproduces the real production shape (a batch stuck CREATED after
    its metadata upsert failed) with entirely synthetic data - never the
    real batch_id 218, never manipulated, per instruction. Proves that
    once the column-mapping fix is in place, resuming that same
    incomplete batch completes it (advances to READY) without ever
    creating a second, duplicate batch for the same domain/entity_type."""

    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_resume218_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def test_resuming_a_stuck_created_batch_completes_it_without_a_duplicate(self) -> None:
        code = "RESUME218-SYNTHETIC-TEST"
        subaward_id = 990001
        attachment_id = 990001
        file_data_id = "99999999-0000-0000-0000-000000000002"

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.subaward "
                    "(subaward_id, sequence_number, subaward_code) VALUES (:id, 1, :code)"
                ),
                {"id": subaward_id, "code": code},
            )
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_attachment "
                    "(attachment_id, subaward_id, subaward_code, sequence_number, "
                    "file_data_id, file_name, mime_type) "
                    "VALUES (:id, :id, :code, 1, :fid, 'fixture.pdf', 'application/pdf')"
                ),
                {"id": attachment_id, "code": code, "fid": file_data_id},
            )

        # Seed a batch in exactly the real, live-confirmed post-incident
        # shape: created successfully (selection_strategy already fixed
        # to fit VARCHAR(50) - see test_subaward_batch_selection_strategy_length.py),
        # left CREATED because its own metadata upsert failed downstream -
        # never manually altered afterward, mirroring "do not touch batch
        # 218" for this synthetic stand-in.
        stuck_batch_id = batch_framework.create_batch(
            self.engine,
            domain=orch.SUBAWARD_ATTACHMENT_DOMAIN,
            entity_type=orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
            requested_size=2000,
            selection_strategy="SUBAWARD_CODE_SCOPE_EXCL_ARCHIVED",
            selected_keys=[1],
            selection_parameters={"file_data_ids": [file_data_id], "subaward_codes": [code]},
            run_id="synthetic-original-run",
        )["batch_id"]

        raw = pd.DataFrame(
            [
                _synthetic_oracle_shaped_row(
                    attachment_id=attachment_id, subaward_id=subaward_id, code=code
                )
            ]
        )
        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = raw
            result = orch.subaward_metadata_stage(
                self.engine, batch_size=2000, run_id="synthetic-resume-run", subaward_codes=[code]
            )

        self.assertEqual(result["batch_id"], stuck_batch_id)
        self.assertTrue(result["batch_advanced_to_ready"])

        with self.engine.connect() as connection:
            batch_count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.etl_batch "
                    "WHERE domain = :domain AND entity_type = :entity_type"
                ),
                {
                    "domain": orch.SUBAWARD_ATTACHMENT_DOMAIN,
                    "entity_type": orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                },
            ).scalar()
            final_status = connection.execute(
                text("SELECT status FROM archive.etl_batch WHERE batch_id = :id"),
                {"id": stuck_batch_id},
            ).scalar()

        # Exactly one batch, still the same one, now READY - never a
        # second, duplicate batch created alongside it.
        self.assertEqual(batch_count, 1)
        self.assertEqual(final_status, batch_framework.BATCH_STATUS_READY)


if __name__ == "__main__":
    unittest.main()
