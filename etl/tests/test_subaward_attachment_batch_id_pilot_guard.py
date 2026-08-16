"""Tests for run_single_subaward_batch / --batch-id: the explicit,
validated single-batch pilot path added after the 2026-08-16 runaway-
batch incident (see test_subaward_attachment_stage1_loop_exhaustion.py
and project memory). With batches 219-3642 now sitting alongside the
real batch 218, all READY, all with the identical 13-file selection,
the ordinary Stage 2 "while True: _next_ready_batch(...)" loop in
run_orchestration would process every one of them in a single run -
each a harmless no-op after the first, but not what "run the batch 218
pilot" should mean operationally, and not bounded. --batch-id targets
exactly one, already-READY batch by id, fails closed on any mismatch,
and never touches any other batch.

Validation-only tests (wrong status/domain/scope/count, missing batch)
return before ever reaching S3/Oracle, so they run against real
Postgres with no other mocking needed, mirroring
test_subaward_attachment_stage1_loop_exhaustion.py's own established
pattern. The "thousands of duplicate READY batches" tests mock
subaward_binary_stage/reconcile_batch at the module boundary (matching
this test suite's own established convention for orchestration-level
tests elsewhere in test_subaward_attachment_pilot_scope.py) rather than
the full S3/Oracle BLOB-streaming stack, since the property being
proven here is which batch gets touched, not upload mechanics already
covered elsewhere.

All fixtures are synthetic; none of this reads or references real
BU/3595 data or the real batch_id 218.
"""

from __future__ import annotations

import getpass
import os
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import attachment_orchestrator as orch
from archive_etl.batch import framework as batch_framework
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]

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


_FILE_DATA_IDS_13 = [f"66666666-0000-0000-0000-{i:012d}" for i in range(1, 14)]


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class RunSingleSubawardBatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_batchidguard_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def _create_ready_batch(
        self, *, file_data_ids: list[str], subaward_codes: list[str] | None = None
    ) -> int:
        with self.engine.begin() as connection:
            batch_id = batch_framework.create_batch(
                self.engine,
                domain=orch.SUBAWARD_ATTACHMENT_DOMAIN,
                entity_type=orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                requested_size=2000,
                selection_strategy="SUBAWARD_CODE_SCOPE_EXCL_ARCHIVED",
                selected_keys=list(range(1, len(file_data_ids) + 1)),
                selection_parameters={
                    "file_data_ids": file_data_ids,
                    "subaward_codes": subaward_codes,
                },
            )["batch_id"]
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        return batch_id

    def _batch_status(self, batch_id: int) -> str:
        with self.engine.connect() as connection:
            return connection.execute(
                text("SELECT status FROM archive.etl_batch WHERE batch_id = :b"),
                {"b": batch_id},
            ).scalar()

    def _all_ready_batch_ids(self) -> set[int]:
        with self.engine.connect() as connection:
            return set(
                connection.execute(
                    text(
                        "SELECT batch_id FROM archive.etl_batch "
                        "WHERE domain = :domain AND entity_type = :entity_type "
                        "AND status = 'READY'"
                    ),
                    {
                        "domain": orch.SUBAWARD_ATTACHMENT_DOMAIN,
                        "entity_type": orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                    },
                ).scalars()
            )

    # --- Validation-only paths: never reach S3/Oracle -----------------

    def test_rejects_a_nonexistent_batch_id(self) -> None:
        result = orch.run_single_subaward_batch(
            self.engine, bucket="synthetic-bucket", batch_id=999999, run_id="r"
        )
        self.assertIn("does not exist", result["stopped_reason"])

    def test_rejects_a_batch_from_a_different_domain_or_entity_type(self) -> None:
        with self.engine.begin() as connection:
            batch_id = batch_framework.create_batch(
                self.engine,
                domain="AWARD_ATTACHMENT",
                entity_type="AWARD_ATTACHMENT_FILE",
                requested_size=1,
                selection_strategy="X",
                selected_keys=[1],
            )["batch_id"]
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )

        result = orch.run_single_subaward_batch(
            self.engine, bucket="synthetic-bucket", batch_id=batch_id, run_id="r"
        )
        self.assertIn("not domain=", result["stopped_reason"])

    def test_rejects_a_batch_still_created_not_ready(self) -> None:
        batch_id = batch_framework.create_batch(
            self.engine,
            domain=orch.SUBAWARD_ATTACHMENT_DOMAIN,
            entity_type=orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
            requested_size=1,
            selection_strategy="SUBAWARD_ALL_EXCL_ARCHIVED",
            selected_keys=[1],
            selection_parameters={"file_data_ids": _FILE_DATA_IDS_13[:1], "subaward_codes": None},
        )["batch_id"]

        result = orch.run_single_subaward_batch(
            self.engine, bucket="synthetic-bucket", batch_id=batch_id, run_id="r"
        )
        self.assertIn("not 'READY'", result["stopped_reason"])

    def test_rejects_a_batch_already_completed(self) -> None:
        batch_id = self._create_ready_batch(
            file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
        )
        with self.engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_COMPLETED
            )

        result = orch.run_single_subaward_batch(
            self.engine, bucket="synthetic-bucket", batch_id=batch_id, run_id="r"
        )
        self.assertIn("not 'READY'", result["stopped_reason"])

    def test_rejects_a_scope_mismatch(self) -> None:
        batch_id = self._create_ready_batch(
            file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE-A"]
        )

        result = orch.run_single_subaward_batch(
            self.engine,
            bucket="synthetic-bucket",
            batch_id=batch_id,
            subaward_codes=["SYNTH-CODE-B"],
            run_id="r",
        )
        self.assertIn("does not match the requested scope", result["stopped_reason"])
        self.assertEqual(self._batch_status(batch_id), batch_framework.BATCH_STATUS_READY)

    def test_rejects_a_file_count_mismatch(self) -> None:
        batch_id = self._create_ready_batch(
            file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
        )

        result = orch.run_single_subaward_batch(
            self.engine,
            bucket="synthetic-bucket",
            batch_id=batch_id,
            expect_file_count=12,
            run_id="r",
        )
        self.assertIn("expected exactly 12", result["stopped_reason"])
        self.assertEqual(self._batch_status(batch_id), batch_framework.BATCH_STATUS_READY)

    # --- The core safety property: thousands of identical duplicate
    # READY batches never redirect or multiply the pilot -----------

    def test_thousands_of_duplicate_ready_batches_are_never_touched(self) -> None:
        # A realistic-shaped stand-in for the real incident: many
        # duplicate READY batches sharing the identical 13-file
        # selection, all with LOWER batch_ids than the target - so any
        # "just pick the next READY batch" logic would pick one of
        # these first, not the real target.
        duplicate_batch_ids = [
            self._create_ready_batch(
                file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
            )
            for _ in range(50)
        ]
        target_batch_id = self._create_ready_batch(
            file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
        )
        self.assertGreater(target_batch_id, max(duplicate_batch_ids))

        ready_before = self._all_ready_batch_ids()
        self.assertEqual(len(ready_before), 51)

        with (
            patch.object(orch, "subaward_binary_stage") as binary_stage,
            patch.object(orch, "reconcile_batch") as reconcile,
            patch("attachment_orchestrator.boto3.client"),
        ):
            binary_stage.return_value = {
                "batch_id": target_batch_id, "stage": "binary",
                "physical_files_selected": 13, "uploaded": 13,
                "skipped_already_uploaded": 0, "reused_from_s3": 0,
                "failed": 0, "missing_source_content": 0, "bytes_uploaded": 1234,
            }
            reconcile.return_value = {"clean": True, "mismatches": []}

            result = orch.run_single_subaward_batch(
                self.engine,
                bucket="synthetic-bucket",
                batch_id=target_batch_id,
                subaward_codes=["SYNTH-CODE"],
                expect_file_count=13,
                run_id="synthetic-pilot-run",
            )

        self.assertNotIn("stopped_reason", result)
        # subaward_binary_stage is called exactly once, with the target
        # batch_id specifically - never any of the 50 duplicates.
        binary_stage.assert_called_once_with(
            self.engine, bucket="synthetic-bucket", batch_id=target_batch_id,
            run_id="synthetic-pilot-run",
        )
        # reconcile_batch (no batch_id parameter of its own - it takes
        # the file_data_id list) is likewise called exactly once, not
        # once per duplicate batch.
        reconcile.assert_called_once()

        self.assertEqual(
            self._batch_status(target_batch_id), batch_framework.BATCH_STATUS_COMPLETED
        )
        for duplicate_id in duplicate_batch_ids:
            self.assertEqual(
                self._batch_status(duplicate_id),
                batch_framework.BATCH_STATUS_READY,
                f"duplicate batch_id={duplicate_id} must remain untouched",
            )

        # No new batch was created as a side effect of this call.
        with self.engine.connect() as connection:
            total_batches = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.etl_batch "
                    "WHERE domain = :domain AND entity_type = :entity_type"
                ),
                {
                    "domain": orch.SUBAWARD_ATTACHMENT_DOMAIN,
                    "entity_type": orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
                },
            ).scalar()
        self.assertEqual(total_batches, 51)

    def test_a_reconciliation_failure_leaves_duplicate_batches_untouched_too(self) -> None:
        duplicate_batch_ids = [
            self._create_ready_batch(
                file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
            )
            for _ in range(10)
        ]
        target_batch_id = self._create_ready_batch(
            file_data_ids=_FILE_DATA_IDS_13, subaward_codes=["SYNTH-CODE"]
        )

        with (
            patch.object(orch, "subaward_binary_stage") as binary_stage,
            patch.object(orch, "reconcile_batch") as reconcile,
            patch("attachment_orchestrator.boto3.client"),
        ):
            binary_stage.return_value = {
                "batch_id": target_batch_id, "stage": "binary",
                "physical_files_selected": 13, "uploaded": 13,
            }
            reconcile.return_value = {
                "clean": False,
                "mismatches": [{"key": "x", "reason": "sha256 mismatch"}],
            }

            result = orch.run_single_subaward_batch(
                self.engine,
                bucket="synthetic-bucket",
                batch_id=target_batch_id,
                run_id="r",
            )

        self.assertIn("Reconciliation failed", result["stopped_reason"])
        # A failed reconciliation must not silently mark the batch
        # COMPLETED - it stays READY so an operator can investigate and
        # explicitly retry, never automatically.
        self.assertEqual(self._batch_status(target_batch_id), batch_framework.BATCH_STATUS_READY)
        for duplicate_id in duplicate_batch_ids:
            self.assertEqual(
                self._batch_status(duplicate_id), batch_framework.BATCH_STATUS_READY
            )


if __name__ == "__main__":
    unittest.main()
