"""Tests for the V078 Award backfill batch-selection mode
(--create-batch N --backfill-v078).

WHY THIS MODE EXISTS: production --create-batch excludes every award_id
already present in archive.award_version (clause (c) of
_excluded_completed_and_active_award_ids). That is correct for "load new
families" and makes backfilling a newly-added column onto already-archived
families impossible - a real dead end hit on 2026-09-21, where
--create-batch 100 returned selected=0 against a fully populated archive.

These tests run the REAL selection SQL against a REAL PostgreSQL database
with every migration applied, because the whole behaviour under test is a
family-level GROUP BY plus an active-batch anti-join - reimplementing it in
mocks would test the mock.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"
LOADER = REPO_ROOT / "etl" / "load_awards_from_csv.py"
VERSIONS_SQL = REPO_ROOT / "sql" / "extract" / "award" / "01_award_versions.sql"

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



def _loader_module(alias: str):
    """Imports the Award loader by path under a private alias.

    spec_from_file_location and spec.loader are both Optional, so they are
    asserted rather than dereferenced blindly - a silent None here would
    surface as an unrelated AttributeError deep inside a test.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location(alias, LOADER)
    assert spec is not None and spec.loader is not None, (
        f"could not build an import spec for {LOADER}"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MarkerChoiceContractTest(unittest.TestCase):
    """The completion marker must be a column Oracle populates for EVERY
    Award row, and must NOT be one of the legitimately sparse ones."""

    def setUp(self) -> None:
        self.source = LOADER.read_text(encoding="utf-8")
        self.extract = VERSIONS_SQL.read_text(encoding="utf-8")

    def test_marker_is_account_type(self) -> None:
        self.assertIn(
            '_V078_COMPLETION_MARKER_COLUMN = "account_type"', self.source
        )

    def test_marker_column_is_resolved_by_the_extraction_from_its_lookup(
        self,
    ) -> None:
        # The marker is only sound if the extraction actually populates it
        # for every row. account_type comes from the ACCOUNT_TYPE lookup,
        # whose join was verified against live KCOEUS staging to resolve
        # 267,386/267,386 rows.
        self.assertIn("act.DESCRIPTION AS ACCOUNT_TYPE", self.extract)
        self.assertIn("LEFT JOIN ACCOUNT_TYPE act", self.extract)
        self.assertIn("a.ACCOUNT_TYPE_CODE", self.extract)

    def test_sparse_columns_are_never_used_as_the_marker(self) -> None:
        # fain_id 137,906/267,386 and nsf_science_code 95,692/267,386 at
        # source. Either as a marker would never converge.
        for sparse in ("fain_id", "nsf_science_code", "nsf_sequence_number"):
            self.assertNotIn(
                f'_V078_COMPLETION_MARKER_COLUMN = "{sparse}"', self.source
            )

    def test_backfill_cannot_be_combined_with_validation_overlap(self) -> None:
        self.assertIn(
            "--backfill-v078 cannot be combined with --validation-overlap",
            self.source,
        )

    def test_no_truncate_or_global_delete_is_reachable(self) -> None:
        # One TRUNCATE call site, in the legacy full load only; no DELETEs.
        self.assertEqual(
            self.source.count("clear_existing_award_data(connection)"), 1
        )
        self.assertEqual(self.source.count("DELETE FROM"), 0)

    def test_load_batch_still_uses_the_incremental_upsert_path(self) -> None:
        # Both --load-award-id and --load-batch must call the same
        # V078-carrying upsert functions.
        self.assertGreaterEqual(
            self.source.count("upsert_award_version(connection,"), 2
        )
        self.assertGreaterEqual(
            self.source.count("upsert_award_amount_info(connection,"), 2
        )

    def test_selection_is_postgres_only(self) -> None:
        """The backfill selector must not issue any Oracle read - only the
        subsequent --load-batch talks to Oracle. Asserted on executable
        lines only, since the docstring legitimately discusses Oracle."""
        start = self.source.index("def _select_v078_backfill_award_ids")
        end = self.source.index("def _run_create_award_batch")
        body = self.source[start:end]
        code = "\n".join(
            line
            for line in body.splitlines()
            if not line.strip().startswith("#")
        )
        # Strip the docstring before asserting on code.
        first = code.find('"""')
        last = code.find('"""', first + 3)
        code = code[:first] + code[last + 3:]
        for forbidden in ("OracleDataSource", "read_batches", "ORACLE_SQL"):
            self.assertNotIn(forbidden, code)


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class _AwardV078DatabaseTestCase(unittest.TestCase):
    """Throwaway-database fixture plus the Award row/batch helpers shared
    by every V078 test class below.

    These helpers used to be borrowed by unbound assignment
    (`_version = V078BackfillSelectionTest._version`), which runs but is
    not type-safe and hid the fact that three classes depend on one
    fixture. Inheritance states that dependency.
    """

    db_prefix = "pytest_v078_backfill"

    def setUp(self) -> None:
        self.db_name = f"{self.db_prefix}_{uuid.uuid4().hex[:12]}"
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

    # --- helpers ---------------------------------------------------------
    def _version(
        self,
        *,
        award_id: int,
        award_number: str,
        sequence_number: int = 1,
        account_type: str | None = None,
        fain_id: str | None = None,
        nsf_science_code: str | None = None,
        is_primary_current: bool = True,
    ) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number,
                        is_current_version, is_primary_current,
                        account_type, fain_id, nsf_science_code
                    ) VALUES (
                        :award_id, :award_number, :sequence_number,
                        TRUE, :primary_current,
                        :account_type, :fain_id, :nsf
                    )
                    """
                ),
                {
                    "award_id": award_id,
                    "award_number": award_number,
                    "sequence_number": sequence_number,
                    "primary_current": is_primary_current,
                    "account_type": account_type,
                    "fain_id": fain_id,
                    "nsf": nsf_science_code,
                },
            )

    def _batch(self, *, batch_id: int, status: str, award_ids: list[int]) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch (
                        batch_id, domain, entity_type, requested_size, status,
                        selection_strategy
                    ) VALUES (
                        :batch_id, 'AWARD', 'AWARD', :size, :status, 'TEST'
                    )
                    """
                ),
                {"batch_id": batch_id, "size": len(award_ids), "status": status},
            )
            for ordinal, award_id in enumerate(award_ids, start=1):
                connection.execute(
                    text(
                        """
                        INSERT INTO archive.etl_batch_item (
                            batch_id, entity_key, ordinal, status
                        ) VALUES (:batch_id, :key, :ordinal, 'COMPLETED')
                        """
                    ),
                    {"batch_id": batch_id, "key": award_id, "ordinal": ordinal},
                )

    def _select(self, size: int) -> list[int]:
        module = _loader_module("_loader_v078")
        return module._select_v078_backfill_award_ids(self.engine, size)

    def _progress(self) -> dict:
        module = _loader_module("_loader_v078p")
        return module.v078_backfill_progress(self.engine)

    def _batch_with_items(
        self,
        *,
        batch_id: int,
        status: str,
        items: list[tuple[int, str]],
    ) -> None:
        """A batch whose items carry EXPLICIT per-item statuses.

        Distinct from _batch above, which claims award_ids at one uniform
        status: the stale-complete and terminal-status rules turn on the
        mix of item statuses, so those tests must set each one."""
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch (
                        batch_id, domain, entity_type, requested_size,
                        status, selection_strategy
                    ) VALUES (
                        :batch_id, 'AWARD', 'AWARD', :size, :status, 'TEST'
                    )
                    """
                ),
                {"batch_id": batch_id, "size": max(len(items), 1),
                 "status": status},
            )
            for ordinal, (award_id, item_status) in enumerate(items, start=1):
                connection.execute(
                    text(
                        """
                        INSERT INTO archive.etl_batch_item (
                            batch_id, entity_key, ordinal, status
                        ) VALUES (:b, :k, :o, :s)
                        """
                    ),
                    {"b": batch_id, "k": award_id, "o": ordinal,
                     "s": item_status},
                )

    def _detect(self) -> list[int]:
        from archive_etl.batch import framework

        return framework.find_stale_complete_batches(
            self.engine, domain="AWARD", entity_type="AWARD"
        )

    # --- detection -------------------------------------------------------

class V078BackfillSelectionTest(_AwardV078DatabaseTestCase):
    """Which Award families the V078 backfill selector picks."""

    # --- tests -----------------------------------------------------------
    def test_selects_already_archived_families(self) -> None:
        """The whole point: a family already in award_version, with no
        batch history at all, must still be selectable."""
        self._version(award_id=10, award_number="A-1", account_type=None)
        self.assertEqual(self._select(10), [10])

    def test_fully_populated_family_is_excluded(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type="Federal")
        self.assertEqual(self._select(10), [])

    def test_partially_populated_family_is_included(self) -> None:
        """ANY NULL row selects the family, so an interrupted load is
        retried rather than left half-populated."""
        self._version(
            award_id=10, award_number="A-1", sequence_number=1,
            account_type="Federal", is_primary_current=False,
        )
        self._version(
            award_id=11, award_number="A-1", sequence_number=2,
            account_type=None,
        )
        # Represented by the family MIN award_id, not the NULL row's id.
        self.assertEqual(self._select(10), [10])

    def test_successful_backfill_makes_family_ineligible_next_selection(
        self,
    ) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self.assertEqual(self._select(10), [10])
        with self.engine.begin() as connection:  # simulate the load
            connection.execute(
                text(
                    "UPDATE archive.award_version SET account_type='Federal' "
                    "WHERE award_number='A-1'"
                )
            )
        self.assertEqual(self._select(10), [])

    def test_rolled_back_batch_leaves_family_eligible(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        try:
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "UPDATE archive.award_version "
                        "SET account_type='Federal' WHERE award_number='A-1'"
                    )
                )
                raise RuntimeError("simulated batch failure")
        except RuntimeError:
            pass
        self.assertEqual(
            self._select(10), [10], "a rolled-back family must stay eligible"
        )

    def test_active_batch_membership_prevents_duplicate_selection(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch(batch_id=901, status="READY", award_ids=[10])
        self.assertEqual(
            self._select(10), [20], "READY batch must claim award 10"
        )

    def test_processing_batch_also_claims(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch(batch_id=902, status="PROCESSING", award_ids=[10])
        self.assertEqual(self._select(10), [])

    def test_freshly_created_batch_blocks_reselection(self) -> None:
        """create_batch persists a new batch as CREATED, not READY. If
        CREATED were not treated as an active claim, two consecutive
        --create-batch --backfill-v078 calls would select the same
        families into two different batches."""
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch(batch_id=905, status="CREATED", award_ids=[10])
        self.assertEqual(self._select(10), [20])

    def test_metadata_loading_batch_blocks_reselection(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch(batch_id=906, status="METADATA_LOADING", award_ids=[10])
        self.assertEqual(self._select(10), [])

    def test_resolved_statuses_do_not_block_reselection(self) -> None:
        """FAILED/PARTIAL/ABANDONED are resolved - the family must be
        selectable again, or a failed batch would strand it forever."""
        for offset, status in enumerate(("FAILED", "PARTIAL", "ABANDONED")):
            award_id = 100 + offset
            self._version(
                award_id=award_id, award_number=f"R-{offset}",
                account_type=None,
            )
            self._batch(
                batch_id=910 + offset, status=status, award_ids=[award_id]
            )
        self.assertEqual(self._select(10), [100, 101, 102])

    def test_resolved_batch_does_not_block_reselection(self) -> None:
        """A COMPLETED item in a finished batch must NOT block backfill -
        every family here was loaded before V078 existed."""
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch(batch_id=903, status="COMPLETED", award_ids=[10])
        self.assertEqual(self._select(10), [10])

    def test_active_claim_is_family_wide(self) -> None:
        """Claiming any award_id of a family must protect its siblings."""
        self._version(
            award_id=10, award_number="A-1", sequence_number=1,
            account_type=None, is_primary_current=False,
        )
        self._version(
            award_id=11, award_number="A-1", sequence_number=2,
            account_type=None,
        )
        self._batch(batch_id=904, status="READY", award_ids=[11])
        self.assertEqual(self._select(10), [])

    def test_deterministic_order_and_pagination(self) -> None:
        for award_id in (50, 20, 40, 10, 30):
            self._version(
                award_id=award_id, award_number=f"A-{award_id}",
                account_type=None,
            )
        self.assertEqual(self._select(5), [10, 20, 30, 40, 50])
        self.assertEqual(self._select(2), [10, 20], "stable prefix")
        self.assertEqual(self._select(2), [10, 20], "repeatable")

    def test_sparse_fain_does_not_make_a_completed_family_incomplete(
        self,
    ) -> None:
        self._version(
            award_id=10, award_number="A-1",
            account_type="Federal", fain_id=None,
        )
        self.assertEqual(
            self._select(10), [],
            "NULL fain_id is normal at source and must not select",
        )

    def test_sparse_nsf_does_not_make_a_completed_family_incomplete(
        self,
    ) -> None:
        self._version(
            award_id=10, award_number="A-1",
            account_type="Federal", nsf_science_code=None,
        )
        self.assertEqual(self._select(10), [])

    def test_literal_unknown_fain_is_a_real_value_not_incompleteness(
        self,
    ) -> None:
        self._version(
            award_id=10, award_number="A-1",
            account_type="Federal", fain_id="unknown",
        )
        self.assertEqual(self._select(10), [])

    def test_progress_counts_families_not_rows(self) -> None:
        self._version(
            award_id=10, award_number="A-1", sequence_number=1,
            account_type="Federal", is_primary_current=False,
        )
        self._version(
            award_id=11, award_number="A-1", sequence_number=2,
            account_type=None,
        )
        self._version(award_id=20, award_number="B-1", account_type="Federal")
        progress = self._progress()
        self.assertEqual(progress["total_families"], 2)
        self.assertEqual(progress["completed_families"], 1)
        self.assertEqual(progress["remaining_families"], 1)
        self.assertEqual(progress["version_rows"], 3)
        self.assertEqual(progress["version_account_type_populated"], 2)

    def test_convergence_to_zero(self) -> None:
        for award_id in range(1, 6):
            self._version(
                award_id=award_id, award_number=f"A-{award_id}",
                account_type=None,
            )
        remaining = self._progress()["remaining_families"]
        self.assertEqual(remaining, 5)
        while True:
            selected = self._select(2)
            if not selected:
                break
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        """
                        UPDATE archive.award_version SET account_type='Federal'
                        WHERE award_number IN (
                            SELECT award_number FROM archive.award_version
                            WHERE award_id = ANY(:ids)
                        )
                        """
                    ),
                    {"ids": selected},
                )
        self.assertEqual(self._progress()["remaining_families"], 0)


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class StaleCompleteBatchLifecycleTest(_AwardV078DatabaseTestCase):
    """A batch whose every item is COMPLETED must not be able to sit in a
    non-terminal status undetected. Regression for the 2026-09-22 finding:
    32 AWARD batches stayed READY after completing and, because selection
    excludes entities claimed by non-terminal batches, made 40,919 of
    40,926 Award families unselectable."""

    db_prefix = "pytest_stale_batch_lifecycle"

    def test_detects_ready_batch_with_all_items_completed(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=800, status="READY",
                    items=[(10, "COMPLETED")])
        self.assertEqual(self._detect(), [800])

    def test_detects_created_batch_with_all_items_completed(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=801, status="CREATED",
                    items=[(10, "COMPLETED")])
        self.assertEqual(self._detect(), [801])

    def test_does_not_detect_batch_with_any_unresolved_item(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        for offset, bad in enumerate(
            ("FAILED", "PROCESSING", "PENDING", "MISSING_SOURCE", "SKIPPED")
        ):
            self._batch_with_items(
                batch_id=810 + offset, status="READY",
                items=[(10, "COMPLETED"), (20, bad)],
            )
        self.assertEqual(
            self._detect(), [],
            "a batch with any unresolved item is genuinely active",
        )

    def test_does_not_detect_empty_batch(self) -> None:
        """Zero items proves nothing finished - must stay claimed."""
        self._batch_with_items(batch_id=820, status="CREATED", items=[])
        self.assertEqual(self._detect(), [])

    def test_does_not_detect_already_terminal_batch(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        for offset, terminal in enumerate(
            ("COMPLETED", "FAILED", "PARTIAL", "ABANDONED")
        ):
            self._batch_with_items(batch_id=830 + offset, status=terminal,
                        items=[(10, "COMPLETED")])
        self.assertEqual(self._detect(), [])

    def test_does_not_detect_non_award_batches(self) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch (
                        batch_id, domain, entity_type, requested_size,
                        status, selection_strategy
                    ) VALUES (840, 'PROPOSAL', 'PROPOSAL_NUMBER', 1,
                              'READY', 'TEST')
                    """
                )
            )
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch_item (
                        batch_id, entity_key, ordinal, status
                    ) VALUES (840, 99, 1, 'COMPLETED')
                    """
                )
            )
        self.assertEqual(self._detect(), [])

    # --- reconciliation + selector ---------------------------------------
    def test_reconciliation_unblocks_the_selector(self) -> None:
        """The end-to-end regression: a stale READY batch blocks selection;
        promoting it with the real lifecycle helper unblocks it."""
        from archive_etl.batch import framework

        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=850, status="READY", items=[(10, "COMPLETED")])

        self.assertEqual(
            self._select(10), [], "stale READY batch must block selection"
        )
        self.assertEqual(self._detect(), [850])

        framework.finish_batch_processing(
            self.engine, 850, status=framework.BATCH_STATUS_COMPLETED
        )

        self.assertEqual(self._detect(), [], "no longer stale")
        self.assertEqual(
            self._select(10), [10],
            "a completed stale batch must not keep blocking selection",
        )

    def test_reconciliation_stamps_completed_at(self) -> None:
        from archive_etl.batch import framework

        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=860, status="READY", items=[(10, "COMPLETED")])
        framework.finish_batch_processing(
            self.engine, 860, status=framework.BATCH_STATUS_COMPLETED
        )
        with self.engine.connect() as connection:
            status, completed_at = connection.execute(
                text(
                    "SELECT status, completed_at FROM archive.etl_batch "
                    "WHERE batch_id = 860"
                )
            ).one()
        self.assertEqual(status, "COMPLETED")
        self.assertIsNotNone(completed_at)

    def test_reconciliation_does_not_touch_batch_items(self) -> None:
        from archive_etl.batch import framework

        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=870, status="READY", items=[(10, "COMPLETED")])
        framework.finish_batch_processing(
            self.engine, 870, status=framework.BATCH_STATUS_COMPLETED
        )
        with self.engine.connect() as connection:
            rows = connection.execute(
                text(
                    "SELECT entity_key, status FROM archive.etl_batch_item "
                    "WHERE batch_id = 870"
                )
            ).all()
        self.assertEqual(rows, [(10, "COMPLETED")])


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class AwardBatchTerminalStatusTest(_AwardV078DatabaseTestCase):
    """Regression for the Award batch finalization defect found
    2026-09-22: _run_load_award_batch set the parent to READY on success
    and never to a terminal status, so every successfully loaded Award
    batch stayed non-terminal and kept claiming its families forever.

    Terminal status must be derived from PERSISTED etl_batch_item rows,
    never from in-memory counters.
    """

    db_prefix = "pytest_award_batch_terminal"

    def _derive(self, batch_id: int):
        module = _loader_module("_loader_term")
        return module.derive_award_batch_terminal_status(self.engine, batch_id)

    def test_all_completed_yields_completed(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch_with_items(
            batch_id=700, status="PROCESSING",
            items=[(10, "COMPLETED"), (20, "COMPLETED")],
        )
        self.assertEqual(self._derive(700), "COMPLETED")

    def test_completed_plus_missing_source_yields_partial(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch_with_items(
            batch_id=701, status="PROCESSING",
            items=[(10, "COMPLETED"), (20, "MISSING_SOURCE")],
        )
        self.assertEqual(self._derive(701), "PARTIAL")

    def test_completed_plus_skipped_yields_partial(self) -> None:
        """SKIPPED is a resolved non-success outcome, so the batch
        finished but not every member landed - that is PARTIAL, and it
        must never read as COMPLETED."""
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch_with_items(
            batch_id=702, status="PROCESSING",
            items=[(10, "COMPLETED"), (20, "SKIPPED")],
        )
        self.assertEqual(self._derive(702), "PARTIAL")

    def test_any_failed_yields_failed(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._batch_with_items(
            batch_id=703, status="PROCESSING",
            items=[(10, "COMPLETED"), (20, "FAILED")],
        )
        self.assertEqual(self._derive(703), "FAILED")

    def test_failed_outranks_missing_source(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        self._version(award_id=30, award_number="C-1", account_type=None)
        self._batch_with_items(
            batch_id=704, status="PROCESSING",
            items=[(10, "COMPLETED"), (20, "MISSING_SOURCE"), (30, "FAILED")],
        )
        self.assertEqual(
            self._derive(704), "FAILED",
            "a hard failure must not be softened to PARTIAL",
        )

    def test_unresolved_items_cannot_produce_a_terminal_status(self) -> None:
        self._version(award_id=10, award_number="A-1", account_type=None)
        self._version(award_id=20, award_number="B-1", account_type=None)
        for offset, unresolved in enumerate(("PENDING", "PROCESSING")):
            self._batch_with_items(
                batch_id=710 + offset, status="PROCESSING",
                items=[(10, "COMPLETED"), (20, unresolved)],
            )
            self.assertIsNone(
                self._derive(710 + offset),
                f"{unresolved} item must block finalization",
            )

    def test_empty_batch_cannot_produce_completed(self) -> None:
        self._batch_with_items(batch_id=720, status="CREATED", items=[])
        self.assertIsNone(self._derive(720))

    def test_completed_parent_leaves_the_stale_detector(self) -> None:
        from archive_etl.batch import framework

        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(batch_id=730, status="READY", items=[(10, "COMPLETED")])
        self.assertEqual(self._detect(), [730])
        framework.finish_batch_processing(
            self.engine, 730, status=self._derive(730)
        )
        self.assertEqual(self._detect(), [])
        with self.engine.connect() as connection:
            status, completed_at = connection.execute(
                text(
                    "SELECT status, completed_at FROM archive.etl_batch "
                    "WHERE batch_id = 730"
                )
            ).one()
        self.assertEqual(status, "COMPLETED")
        self.assertIsNotNone(completed_at)

    def test_partial_and_failed_batches_do_not_claim_forever(self) -> None:
        """PARTIAL/FAILED are terminal, so they stop blocking selection -
        otherwise a failed batch would strand its families."""
        from archive_etl.batch import framework

        self._version(award_id=10, award_number="A-1", account_type=None)
        self._batch_with_items(
            batch_id=740, status="READY", items=[(10, "MISSING_SOURCE")]
        )
        self.assertEqual(self._select(10), [], "non-terminal blocks")
        framework.finish_batch_processing(
            self.engine, 740, status="PARTIAL"
        )
        self.assertEqual(
            self._select(10), [10], "terminal PARTIAL must release the claim"
        )

    def test_loader_no_longer_finalizes_to_ready(self) -> None:
        """Contract test on the real production source: the Award batch
        loader must not set its parent to READY on completion."""
        source = LOADER.read_text(encoding="utf-8")
        self.assertNotIn(
            "batch_id, status=batch_framework.BATCH_STATUS_READY", source
        )
        self.assertIn("derive_award_batch_terminal_status(", source)
        self.assertIn("batch_framework.finish_batch_processing(", source)

    def test_award_attachment_ready_semantics_are_untouched(self) -> None:
        """Award Attachment deliberately uses READY as an intermediate
        phase marker between metadata-load and upload. This fix is
        Award-scoped and must not have changed it."""
        attachments = (
            REPO_ROOT / "etl" / "load_award_attachments.py"
        ).read_text(encoding="utf-8")
        self.assertIn("status=batch_framework.BATCH_STATUS_READY", attachments)
        self.assertIn("finish_batch_processing(", attachments)


if __name__ == "__main__":
    unittest.main()
