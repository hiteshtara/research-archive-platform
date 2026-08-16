"""Real-PostgreSQL regression test for the 2026-08-16 runaway-batch
incident: run_orchestration's Subaward Stage 1 ("while True: ... if
selected_count == 0: break") never exhausted, because
_run_create_subaward_attachment_batch only excluded file_data_ids
already confirmed ARCHIVED (_subaward_excluded_file_data_ids) - a
status Stage 2 (binary/upload) alone can set, and Stage 2 never runs
until Stage 1's own loop exits. The same 13 real candidates stayed
"eligible" forever, so Stage 1 re-selected and re-batched them on every
iteration: 3,424 duplicate batches (batch_id 219-3642) were created in
~94 minutes before the task finally crashed on an unrelated Oracle
connection failure, with zero binaries ever actually uploaded. See
docs/runbooks/attachments/SUBAWARD_ATTACHMENT_ORCHESTRATOR.md and
project memory for the full incident.

Fix: _subaward_already_batched_file_data_ids excludes any file_data_id
already present in ANY domain=SUBAWARD_ATTACHMENT
entity_type=SUBAWARD_ATTACHMENT_FILE batch's selection_parameters,
regardless of that batch's status - combined with the existing
ARCHIVED-only exclusion at both call sites
(_run_create_subaward_attachment_batch and its --dry-run mirror,
plan_subaward_batch).

Only Oracle I/O is simulated (mocked) - the database layer, including
the real archive.etl_batch/archive.etl_batch_item rows this bug
actually wrote, is never mocked, mirroring
test_subaward_attachment_metadata_column_mapping.py's own established
"exercise the real schema" pattern. All fixtures are synthetic; none of
this reads or references real BU/3595 data or the real batch_id 218.
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
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import attachment_orchestrator as orch
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


# 13 synthetic file_data_ids - matching the real pilot's own candidate
# count (never the real file_data_id values) - each referencing the
# same single synthetic subaward_id, mirroring how one Subaward version
# can have multiple attachment rows.
_CANDIDATE_FILE_DATA_IDS = [
    f"88888888-0000-0000-0000-{i:012d}" for i in range(1, 14)
]


def _synthetic_oracle_shaped_rows(*, subaward_id: int, code: str) -> pd.DataFrame:
    # A full Oracle-shaped row per candidate (not just file_data_id) -
    # the file-id scan only reads the file_data_id column, but the
    # metadata upsert (_upsert_subaward_attachments, reached via
    # subaward_metadata_stage) needs every other column too. Using one
    # full DataFrame for every OracleDataSource(...).read_filtered(...)
    # call mirrors test_subaward_attachment_metadata_column_mapping.py's
    # own established, working mock shape.
    from datetime import datetime

    return pd.DataFrame(
        [
            {
                "attachment_id": 970100 + i,
                "subaward_id": subaward_id,
                "subaward_code": code,
                "sequence_number": 1,
                "attachment_type_code": 10,
                "attachment_type_description": "Subrecipient Proposal",
                "document_id": 1,
                "file_data_id": file_data_id,
                "file_name": f"synthetic-fixture-{i}.pdf",
                "mime_type": "application/pdf",
                "document_status_code": "A",
                "description": "Synthetic fixture attachment",
                "last_update_timestamp": datetime(2025, 1, 1, 12, 0, 0),
                "last_update_user": "synthetic_last_update_user",
                "update_timestamp": datetime(2025, 6, 15, 9, 30, 0),
                "update_user": "synthetic_update_user",
                "ver_nbr": 1,
                "obj_id": f"77777777-0000-0000-0000-{i:012d}",
            }
            for i, file_data_id in enumerate(_CANDIDATE_FILE_DATA_IDS, start=1)
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SubawardAttachmentStage1LoopExhaustionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_stage1loop_{uuid.uuid4().hex[:12]}"
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

    def _fake_oracle_read(self, *, subaward_id: int, code: str):
        # Oracle has no notion of Postgres batch/archive state - it
        # returns the same reference rows on every call, exactly like
        # the real incident. The fix must come from Postgres-side
        # exclusion, not from Oracle somehow "knowing" what's already
        # batched. One full-row DataFrame satisfies both the file-id
        # scan (file_data_id column only) and the metadata upsert
        # (every other column).
        rows = _synthetic_oracle_shaped_rows(subaward_id=subaward_id, code=code)
        return lambda *_args, **_kwargs: rows

    def test_two_consecutive_batch_creation_calls_never_reselect_the_same_files(
        self,
    ) -> None:
        """The minimal reproduction of the real incident: nothing ever
        gets archived between two calls (Stage 2 never ran, exactly as
        in production), so a call immediately following a successful
        batch-create must select zero files, not the same 13 again -
        this is the exact step that looped 3,424 times in production."""
        subaward_id, code = 970001, "STAGE1-LOOP-TEST"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)

        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.side_effect = (
                self._fake_oracle_read(subaward_id=subaward_id, code=code)
            )

            first = orch._run_create_subaward_attachment_batch(
                self.engine, 2000, run_id="synthetic-run-1"
            )
            second = orch._run_create_subaward_attachment_batch(
                self.engine, 2000, run_id="synthetic-run-2"
            )

        self.assertEqual(first["selected_count"], 13)
        self.assertEqual(sorted(first["selected_file_data_ids"]), sorted(_CANDIDATE_FILE_DATA_IDS))
        # The regression: before the fix, this was also 13 (a brand
        # new, fully-duplicate batch) - not 0.
        self.assertEqual(second["selected_count"], 0)
        self.assertEqual(second["selected_file_data_ids"], [])

        with self.engine.connect() as connection:
            all_file_data_ids = connection.execute(
                text(
                    "SELECT selection_parameters FROM archive.etl_batch "
                    "WHERE domain = :domain AND entity_type = :entity_type "
                    "ORDER BY batch_id"
                ),
                {
                    "domain": orch.SUBAWARD_ATTACHMENT_DOMAIN,
                    "entity_type": orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                },
            ).fetchall()
        # create_batch always persists a row, even for an empty
        # selection (pre-existing behavior, shared with Award/
        # Proposal's own "loop until exhausted" pattern, not something
        # this fix changes) - so exhaustion takes exactly two batches
        # (one real, one empty "nothing left" probe), not one. What
        # actually regressed in production is duplicate *candidates*
        # across batches, so that is what this asserts.
        self.assertEqual(len(all_file_data_ids), 2)
        seen_file_data_ids: list[str] = []
        for row in all_file_data_ids:
            seen_file_data_ids.extend(row.selection_parameters.get("file_data_ids", []))
        self.assertEqual(
            sorted(seen_file_data_ids),
            sorted(_CANDIDATE_FILE_DATA_IDS),
            "the same file_data_id must never appear in more than one "
            "batch's selection_parameters",
        )

    def test_stage1_style_loop_terminates_within_a_bounded_number_of_iterations(
        self,
    ) -> None:
        """Mirrors run_orchestration's actual Stage 1 loop shape
        ('while True: result = subaward_metadata_stage(...); if
        selected_count == 0: break') directly, capped at a small
        iteration bound so this test itself cannot hang the suite if
        the exhaustion logic regresses again."""
        subaward_id, code = 970002, "STAGE1-LOOP-BOUNDED-TEST"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)

        MAX_ITERATIONS = 5
        iterations = 0
        exhausted = False

        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.side_effect = (
                self._fake_oracle_read(subaward_id=subaward_id, code=code)
            )

            while iterations < MAX_ITERATIONS:
                iterations += 1
                result = orch.subaward_metadata_stage(
                    self.engine, batch_size=2000, run_id=f"synthetic-run-{iterations}"
                )
                if result.get("selected_count", 0) == 0:
                    exhausted = True
                    break

        self.assertTrue(
            exhausted,
            f"Stage 1 loop did not exhaust within {MAX_ITERATIONS} iterations - "
            "this is exactly the runaway-batch-creation regression",
        )
        # One real batch (all 13 candidates) plus the immediate
        # exhaustion check - never more than 2 iterations for a
        # candidate set smaller than requested_size. Before the fix,
        # this ran the full MAX_ITERATIONS without ever exhausting
        # (the real incident took 3,424 iterations over 94 minutes).
        self.assertLessEqual(iterations, 2)

        with self.engine.connect() as connection:
            batch_rows = connection.execute(
                text(
                    "SELECT selection_parameters FROM archive.etl_batch "
                    "WHERE domain = :domain AND entity_type = :entity_type "
                    "ORDER BY batch_id"
                ),
                {
                    "domain": orch.SUBAWARD_ATTACHMENT_DOMAIN,
                    "entity_type": orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                },
            ).fetchall()
        # create_batch always persists a row, even for an empty
        # selection (pre-existing, shared with Award/Proposal) - so two
        # batches is the correct, bounded shape: one real, one empty
        # "nothing left" probe that ends the loop.
        self.assertEqual(len(batch_rows), 2)
        seen_file_data_ids: list[str] = []
        for row in batch_rows:
            seen_file_data_ids.extend(row.selection_parameters.get("file_data_ids", []))
        self.assertEqual(sorted(seen_file_data_ids), sorted(_CANDIDATE_FILE_DATA_IDS))

    def test_dry_run_preview_matches_the_real_selection_after_a_batch_exists(
        self,
    ) -> None:
        """plan_subaward_batch's own docstring promises it previews
        'exactly what a real _run_create_subaward_attachment_batch call
        would select' - it must apply the same already-batched
        exclusion, or a --dry-run run after a real batch exists would
        misleadingly still show the same 13 files as available."""
        subaward_id, code = 970003, "STAGE1-LOOP-DRYRUN-TEST"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)

        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.side_effect = (
                self._fake_oracle_read(subaward_id=subaward_id, code=code)
            )

            real = orch._run_create_subaward_attachment_batch(
                self.engine, 2000, run_id="synthetic-real-run"
            )
            preview = orch.plan_subaward_batch(self.engine, 2000)

        self.assertEqual(real["selected_count"], 13)
        self.assertEqual(preview["candidate_file_data_id_count"], 0)


if __name__ == "__main__":
    unittest.main()
