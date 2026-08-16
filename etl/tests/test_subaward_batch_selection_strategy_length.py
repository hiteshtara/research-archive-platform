"""Real-PostgreSQL regression test for the 2026-08-16 live pilot-run
failure: archive.etl_batch.selection_strategy is VARCHAR(50) (V037),
but both of _run_create_subaward_attachment_batch's own strategy labels
exceeded it (55/57 chars) - a defect that had never fired before
because this INSERT had never actually executed against real Postgres
(Subaward binary-stage execution was deferred throughout - see
etl/attachment_orchestrator.py's own module docstring). Every existing
test for this code path mocks batch_framework.create_batch entirely
(see etl/tests/test_subaward_attachment_pilot_scope.py), so none of
them could ever have caught a real column-length violation - this file
is deliberately the one place that does not mock it, mirroring
test_v077_subaward_attachment_archive_migration.py's own established
"exercise the real database layer" pattern for exactly this reason.

Only Oracle I/O is simulated (mocked) - the database layer, including
the real archive.etl_batch INSERT and its VARCHAR(50) constraint, is
never mocked. All fixtures are synthetic; none of this reads or
references real BU/3595 data.

Skips entirely if no local PostgreSQL is reachable - mirrors
test_v077_subaward_attachment_archive_migration.py's pattern exactly
(throwaway, uniquely-named database per test, dropped afterward).
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

# archive.etl_batch.selection_strategy's real column limit (V037) - the
# whole point of this file. Asserted directly against, never assumed.
SELECTION_STRATEGY_MAX_LENGTH = 50


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


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SelectionStrategyRealPersistenceTest(unittest.TestCase):
    """Exercises _run_create_subaward_attachment_batch's real INSERT via
    the real, unmocked batch_framework.create_batch, against a fully
    migrated (through the full committed chain), fresh throwaway
    database - only OracleDataSource is mocked."""

    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_selstrat_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def _seed_subaward_with_attachments(
        self, *, subaward_code: str, subaward_ids: list[int], file_data_ids: list[str]
    ) -> None:
        """One attachment (one distinct file_data_id) per subaward_id -
        small and synthetic, just enough to exercise the real INSERT
        path, not a full population reproduction (already covered,
        mocked, in test_subaward_attachment_pilot_scope.py)."""
        with self.engine.begin() as connection:
            for subaward_id, file_data_id in zip(subaward_ids, file_data_ids, strict=True):
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward "
                        "(subaward_id, sequence_number, subaward_code) "
                        "VALUES (:id, 1, :code)"
                    ),
                    {"id": subaward_id, "code": subaward_code},
                )
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment "
                        "(attachment_id, subaward_id, subaward_code, sequence_number, "
                        "file_data_id, file_name, mime_type) "
                        "VALUES (:id, :id, :code, 1, :fid, 'fixture.pdf', 'application/pdf')"
                    ),
                    {"id": subaward_id, "code": subaward_code, "fid": file_data_id},
                )

    def test_scoped_selection_strategy_inserts_successfully_and_fits_the_column(self) -> None:
        code = "SELSTRAT-SCOPED-TEST"
        subaward_ids = [960001, 960002]
        file_data_ids = [
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555",
        ]
        self._seed_subaward_with_attachments(
            subaward_code=code, subaward_ids=subaward_ids, file_data_ids=file_data_ids
        )
        oracle_rows = pd.DataFrame({"subaward_id": subaward_ids, "file_data_id": file_data_ids})

        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = oracle_rows
            result = orch._run_create_subaward_attachment_batch(
                self.engine, 100, run_id="r1", subaward_codes=[code]
            )

        self.assertEqual(result["selected_count"], 2)

        with self.engine.connect() as connection:
            stored_strategy = connection.execute(
                text(
                    "SELECT selection_strategy FROM archive.etl_batch WHERE batch_id = :b"
                ),
                {"b": result["batch_id"]},
            ).scalar()

        self.assertEqual(stored_strategy, "SUBAWARD_CODE_SCOPE_EXCL_ARCHIVED")
        assert stored_strategy is not None  # column is NOT NULL; narrows for mypy
        self.assertLessEqual(len(stored_strategy), SELECTION_STRATEGY_MAX_LENGTH)

    def test_unscoped_selection_strategy_inserts_successfully_and_fits_the_column(self) -> None:
        code = "SELSTRAT-UNSCOPED-TEST"
        subaward_ids = [960003, 960004]
        file_data_ids = [
            "66666666-6666-6666-6666-666666666666",
            "77777777-7777-7777-7777-777777777777",
        ]
        self._seed_subaward_with_attachments(
            subaward_code=code, subaward_ids=subaward_ids, file_data_ids=file_data_ids
        )
        oracle_rows = pd.DataFrame({"subaward_id": subaward_ids, "file_data_id": file_data_ids})

        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = oracle_rows
            # subaward_codes omitted entirely - the unscoped path.
            result = orch._run_create_subaward_attachment_batch(self.engine, 100, run_id="r1")

        self.assertEqual(result["selected_count"], 2)

        with self.engine.connect() as connection:
            stored_strategy = connection.execute(
                text(
                    "SELECT selection_strategy FROM archive.etl_batch WHERE batch_id = :b"
                ),
                {"b": result["batch_id"]},
            ).scalar()

        self.assertEqual(stored_strategy, "SUBAWARD_ALL_EXCL_ARCHIVED")
        assert stored_strategy is not None  # column is NOT NULL; narrows for mypy
        self.assertLessEqual(len(stored_strategy), SELECTION_STRATEGY_MAX_LENGTH)

    def test_both_selection_strategy_constants_fit_the_real_column_independent_of_a_batch(
        self,
    ) -> None:
        # Defense in depth, independent of the two tests above: proves
        # the column itself really is VARCHAR(50) against the real,
        # migrated schema (not assumed from reading the migration file),
        # by attempting a direct INSERT of a value one character over
        # the limit and confirming Postgres itself rejects it - the
        # exact failure mode of the original incident.
        oversized = "X" * (SELECTION_STRATEGY_MAX_LENGTH + 1)
        with self.assertRaises(Exception) as ctx:
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch "
                        "(domain, entity_type, requested_size, status, selection_strategy) "
                        "VALUES ('TEST', 'TEST', 1, 'CREATED', :s)"
                    ),
                    {"s": oversized},
                )
        # The exact real error the 2026-08-16 incident hit.
        self.assertIn("StringDataRightTruncation", str(ctx.exception))


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class BatchResumeScopeMismatchProtectionRealPersistenceTest(unittest.TestCase):
    """Proves the --subaward-code resume-scope guard (SubawardCodeScopeMismatch)
    still works correctly against a REAL incomplete batch row and its
    real JSONB selection_parameters round-trip - not a mocked
    _find_incomplete_batch/_batch_subaward_codes, unlike every existing
    test of this guard (see ResumeScopeGuardTest in
    test_subaward_attachment_pilot_scope.py). Confirms the fix (shorter
    selection_strategy values) did not disturb this separate,
    independent safety mechanism, which never reads selection_strategy
    at all - only selection_parameters (verified by search, see the
    fix's own commit message)."""

    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_resume_scope_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def _create_real_incomplete_batch(self, *, subaward_codes: list[str] | None) -> int:
        code = (subaward_codes or ["SELSTRAT-RESUME-TEST"])[0]
        subaward_id = 970001
        file_data_id = "88888888-8888-8888-8888-888888888888"
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
                {"id": subaward_id, "code": code, "fid": file_data_id},
            )

        oracle_rows = pd.DataFrame({"subaward_id": [subaward_id], "file_data_id": [file_data_id]})
        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = oracle_rows
            result = orch._run_create_subaward_attachment_batch(
                self.engine, 100, run_id="r1", subaward_codes=subaward_codes
            )
        # Batch is left CREATED (incomplete) by create_batch - never
        # advanced to READY here, matching a real interrupted run.
        return result["batch_id"]

    def test_resuming_with_a_different_scope_raises_scope_mismatch(self) -> None:
        self._create_real_incomplete_batch(subaward_codes=["SELSTRAT-ORIGINAL-SCOPE"])

        with patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch, \
             patch.object(orch, "_run_load_subaward_attachment_batch") as load_batch:
            with self.assertRaises(orch.SubawardCodeScopeMismatch) as ctx:
                orch.subaward_metadata_stage(
                    self.engine, batch_size=100, run_id="r2",
                    subaward_codes=["SELSTRAT-DIFFERENT-SCOPE"],
                )

        create_batch.assert_not_called()
        load_batch.assert_not_called()
        self.assertIn("SELSTRAT-ORIGINAL-SCOPE", str(ctx.exception))
        self.assertIn("SELSTRAT-DIFFERENT-SCOPE", str(ctx.exception))

    def test_resuming_with_the_matching_scope_does_not_raise(self) -> None:
        batch_id = self._create_real_incomplete_batch(subaward_codes=["SELSTRAT-SAME-SCOPE"])

        with patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch, \
             patch.object(
                 orch, "_run_load_subaward_attachment_batch",
                 return_value={"batch_id": batch_id},
             ) as load_batch:
            orch.subaward_metadata_stage(
                self.engine, batch_size=100, run_id="r2",
                subaward_codes=["SELSTRAT-SAME-SCOPE"],
            )

        create_batch.assert_not_called()
        load_batch.assert_called_once()
        self.assertEqual(load_batch.call_args.args[1], batch_id)

    def test_resuming_an_unscoped_batch_unscoped_does_not_raise(self) -> None:
        # Backward-compatibility check, real-DB version of the mocked
        # one in test_subaward_attachment_pilot_scope.py: a batch
        # created before --subaward-code existed (or created unscoped)
        # has no 'subaward_codes' key in selection_parameters at all -
        # must still match an unscoped request exactly as before.
        batch_id = self._create_real_incomplete_batch(subaward_codes=None)

        with patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch, \
             patch.object(
                 orch, "_run_load_subaward_attachment_batch",
                 return_value={"batch_id": batch_id},
             ) as load_batch:
            orch.subaward_metadata_stage(self.engine, batch_size=100, run_id="r2")

        create_batch.assert_not_called()
        load_batch.assert_called_once()


if __name__ == "__main__":
    unittest.main()
