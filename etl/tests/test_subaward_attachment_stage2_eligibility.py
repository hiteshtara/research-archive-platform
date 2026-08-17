"""Real-PostgreSQL regression test for the Stage-2 duplicate-batch
eligibility fix.

Background: the 2026-08-16 runaway-batch incident (see
test_subaward_attachment_stage1_loop_exhaustion.py, project memory, and
docs/runbooks/attachments/SUBAWARD_ATTACHMENT_ORCHESTRATOR.md) left
3,424 duplicate READY batches (batch_id 219-3642) in real dev Postgres,
all sharing the identical 13-file_data_id selection as the one real
batch, batch_id=218 - which was later completed via the explicit
--batch-id pilot path, ARCHIVing all 13 of those files.

That fixed Stage 1 (batch *creation*) but not Stage 2. Before this fix,
`_next_ready_batch` returned ANY READY batch for the Subaward domain/
entity_type in ascending batch_id order, with no awareness that a
batch's own file_data_ids might already be fully ARCHIVED. Since 218
would already be COMPLETED, batches 219-3642 would be the *first* 3,424
things a full `--all-subawards` run's Stage 2 loop encountered - each
one a real-work no-op (their files are already ARCHIVED, so
select_subaward_upload_candidates finds nothing), but
run_orchestration's Stage 2 body unconditionally reconciles and then
calls set_batch_status(..., COMPLETED) on every batch it is handed,
regardless of whether it did any real work. That is a real write,
silently flipping all 3,424 batches from READY to COMPLETED - directly
overwriting the READY state deliberately preserved as incident evidence
- before the loop could ever reach a newly-created, genuinely new batch
from the same run.

Fix: `_next_ready_batch`, for the Subaward module only, now only
returns a READY batch that has at least one file_data_id still
PENDING/UPLOADING (a general `WHERE EXISTS (...)` clause against
archive.subaward_attachment_archive - never a hardcoded batch_id
range), so a batch whose candidates are all already ARCHIVED is simply
never returned, and its status is never touched.

Real Postgres throughout (archive.etl_batch/archive.subaward_attachment/
archive.subaward_attachment_archive rows) - no DB-layer mocking - mirrors
test_subaward_attachment_stage1_loop_exhaustion.py's own established
pattern. All fixtures are synthetic; none of this reads or references
real BU/3595 data or the real batch_id 218/219-3642 range.
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


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SubawardAttachmentStage2EligibilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_stage2elig_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)
        self._next_attachment_id = 1

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

    def _seed_file(
        self, *, subaward_id: int, code: str, file_data_id: str, archive_status: str
    ) -> None:
        """Seeds one archive.subaward_attachment row (metadata - always
        present once Stage 1 has loaded a reference) plus one
        archive.subaward_attachment_archive row at the given
        archive_status - the exact durable state Stage 2 eligibility is
        decided from."""
        attachment_id = self._next_attachment_id
        self._next_attachment_id += 1
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_attachment "
                    "(attachment_id, subaward_id, subaward_code, sequence_number, file_data_id) "
                    "VALUES (:attachment_id, :subaward_id, :code, 1, :file_data_id)"
                ),
                {
                    "attachment_id": attachment_id,
                    "subaward_id": subaward_id,
                    "code": code,
                    "file_data_id": file_data_id,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_attachment_archive "
                    "(attachment_id, subaward_id, subaward_code, sequence_number, "
                    "file_data_id, archive_status) "
                    "VALUES (:attachment_id, :subaward_id, :code, 1, :file_data_id, :status)"
                ),
                {
                    "attachment_id": attachment_id,
                    "subaward_id": subaward_id,
                    "code": code,
                    "file_data_id": file_data_id,
                    "status": archive_status,
                },
            )

    def _create_ready_batch(self, *, file_data_ids: list[str], run_id: str) -> int:
        created = batch_framework.create_batch(
            self.engine,
            domain=orch.SUBAWARD_ATTACHMENT_DOMAIN,
            entity_type=orch.SUBAWARD_ATTACHMENT_ENTITY_TYPE,
            requested_size=len(file_data_ids),
            selection_strategy="TEST_FIXTURE_STAGE2_ELIGIBILITY",
            selected_keys=list(range(1, len(file_data_ids) + 1)),
            selection_parameters={"file_data_ids": file_data_ids},
            run_id=run_id,
        )
        batch_id = created["batch_id"]
        with self.engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        return batch_id

    def _batch_status(self, batch_id: int) -> str:
        with self.engine.connect() as connection:
            return connection.execute(
                text("SELECT status FROM archive.etl_batch WHERE batch_id = :b"),
                {"b": batch_id},
            ).scalar_one()

    def test_stale_duplicate_ready_batches_are_skipped(self) -> None:
        """The core regression: a READY batch whose every file_data_id
        is already ARCHIVED (exactly the shape of batches 219-3642)
        must never be returned by _next_ready_batch."""
        subaward_id, code = 970101, "STAGE2-ELIG-STALE"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)
        file_data_ids = [f"99990001-0000-0000-0000-{i:012d}" for i in range(1, 4)]
        for file_data_id in file_data_ids:
            self._seed_file(
                subaward_id=subaward_id, code=code,
                file_data_id=file_data_id, archive_status="ARCHIVED",
            )

        stale_batch_id = self._create_ready_batch(
            file_data_ids=file_data_ids, run_id="synthetic-stale-run"
        )

        result = orch._next_ready_batch(self.engine, module=orch.SUBAWARD)

        self.assertIsNone(
            result,
            "a READY batch whose files are all already ARCHIVED must never be selected",
        )
        self.assertEqual(self._batch_status(stale_batch_id), batch_framework.BATCH_STATUS_READY)

    def test_stale_batch_status_remains_ready_after_being_skipped(self) -> None:
        """Repeats the check explicitly against many stale duplicates
        (mirroring the real 219-3642 shape at a bounded scale) - proves
        no status is ever touched, not just that _next_ready_batch
        returns None once."""
        subaward_id, code = 970102, "STAGE2-ELIG-MANY-STALE"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)
        file_data_ids = [f"99990002-0000-0000-0000-{i:012d}" for i in range(1, 4)]
        for file_data_id in file_data_ids:
            self._seed_file(
                subaward_id=subaward_id, code=code,
                file_data_id=file_data_id, archive_status="ARCHIVED",
            )

        stale_batch_ids = [
            self._create_ready_batch(file_data_ids=file_data_ids, run_id=f"synthetic-dup-{i}")
            for i in range(5)
        ]

        for _ in range(3):
            self.assertIsNone(orch._next_ready_batch(self.engine, module=orch.SUBAWARD))

        for batch_id in stale_batch_ids:
            self.assertEqual(
                self._batch_status(batch_id),
                batch_framework.BATCH_STATUS_READY,
                f"stale duplicate batch_id={batch_id} must remain READY, never touched",
            )

    def test_a_later_eligible_ready_batch_is_still_selected(self) -> None:
        """Stale duplicates must never mask a genuinely new, eligible
        batch created afterward (the real shape of a full backfill run:
        thousands of stale duplicates followed by real new work)."""
        stale_subaward_id, stale_code = 970103, "STAGE2-ELIG-STALE-2"
        self._seed_core_subaward(subaward_id=stale_subaward_id, code=stale_code)
        stale_file_data_ids = [f"99990003-0000-0000-0000-{i:012d}" for i in range(1, 4)]
        for file_data_id in stale_file_data_ids:
            self._seed_file(
                subaward_id=stale_subaward_id, code=stale_code,
                file_data_id=file_data_id, archive_status="ARCHIVED",
            )
        self._create_ready_batch(file_data_ids=stale_file_data_ids, run_id="synthetic-stale-run")

        eligible_subaward_id, eligible_code = 970104, "STAGE2-ELIG-REAL"
        self._seed_core_subaward(subaward_id=eligible_subaward_id, code=eligible_code)
        eligible_file_data_ids = [f"99990004-0000-0000-0000-{i:012d}" for i in range(1, 3)]
        for file_data_id in eligible_file_data_ids:
            self._seed_file(
                subaward_id=eligible_subaward_id, code=eligible_code,
                file_data_id=file_data_id, archive_status="PENDING",
            )
        eligible_batch_id = self._create_ready_batch(
            file_data_ids=eligible_file_data_ids, run_id="synthetic-eligible-run"
        )

        result = orch._next_ready_batch(self.engine, module=orch.SUBAWARD)

        self.assertEqual(result, eligible_batch_id)

    def test_a_batch_with_both_archived_and_pending_files_remains_eligible(self) -> None:
        """A batch is eligible if ANY of its own files still has a real
        candidate - partial progress (e.g. a prior partial upload/
        reconciliation) must not make the whole batch unreachable."""
        subaward_id, code = 970105, "STAGE2-ELIG-MIXED"
        self._seed_core_subaward(subaward_id=subaward_id, code=code)
        archived_file_data_id = "99990005-0000-0000-0000-000000000001"
        pending_file_data_id = "99990005-0000-0000-0000-000000000002"
        self._seed_file(
            subaward_id=subaward_id, code=code,
            file_data_id=archived_file_data_id, archive_status="ARCHIVED",
        )
        self._seed_file(
            subaward_id=subaward_id, code=code,
            file_data_id=pending_file_data_id, archive_status="PENDING",
        )
        mixed_batch_id = self._create_ready_batch(
            file_data_ids=[archived_file_data_id, pending_file_data_id],
            run_id="synthetic-mixed-run",
        )

        result = orch._next_ready_batch(self.engine, module=orch.SUBAWARD)

        self.assertEqual(result, mixed_batch_id)

    def test_no_eligible_subaward_batch_returns_none(self) -> None:
        """Absent any READY batch at all (the terminal state after a
        full backfill completes), _next_ready_batch must return None,
        not raise or hang - proven separately from the
        all-stale-duplicates case above."""
        result = orch._next_ready_batch(self.engine, module=orch.SUBAWARD)
        self.assertIsNone(result)

    def test_award_and_proposal_modules_are_unaffected(self) -> None:
        """The fix is scoped to the Subaward module only - Award/
        Proposal must keep returning any READY batch regardless of its
        own archive-status shape (they have no equivalent eligibility
        concept, and this fix must not silently change that)."""
        award_batch = batch_framework.create_batch(
            self.engine,
            domain=orch.award_attachments.AWARD_ATTACHMENT_BATCH_DOMAIN,
            entity_type=orch.award_attachments.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_FIXTURE_STAGE2_ELIGIBILITY",
            selected_keys=[1],
            run_id="synthetic-award-run",
        )
        with self.engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, award_batch["batch_id"], status=batch_framework.BATCH_STATUS_READY
            )

        proposal_batch = batch_framework.create_batch(
            self.engine,
            domain=orch.PROPOSAL_ATTACHMENT_DOMAIN,
            entity_type=orch.PROPOSAL_ATTACHMENT_ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_FIXTURE_STAGE2_ELIGIBILITY",
            selected_keys=[1],
            selection_parameters={"file_data_ids": ["not-a-real-uuid"]},
            run_id="synthetic-proposal-run",
        )
        with self.engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, proposal_batch["batch_id"], status=batch_framework.BATCH_STATUS_READY
            )

        self.assertEqual(
            orch._next_ready_batch(self.engine, module=orch.AWARD), award_batch["batch_id"]
        )
        self.assertEqual(
            orch._next_ready_batch(self.engine, module=orch.PROPOSAL), proposal_batch["batch_id"]
        )


if __name__ == "__main__":
    unittest.main()
