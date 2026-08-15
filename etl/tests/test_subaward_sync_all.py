"""Tests for the Subaward nightly-sync additions to
load_subawards_from_csv.py: --sync-all, --reconcile-only, and the
removal of the destructive no-verb default (see
docs/architecture/SUBAWARD_NIGHTLY_SYNC_DESIGN.md).

CLI-parsing tests run against the real argparse parser (no PostgreSQL).
Everything that touches PostgreSQL runs against a real, uniquely-named,
throwaway database (mirroring tests/test_award_incremental_upsert.py) -
the advisory-lock and UPSERT-vs-TRUNCATE distinctions depend on genuine
Postgres semantics a mock cannot exercise correctly. Oracle is always
mocked via an OracleDataSource-shaped stub - no real infrastructure is
ever touched. Skips entirely if no local PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import load_subawards_from_csv as subaward_loader
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")


def _maintenance_engine() -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:{POSTGRES_PORT}/{MAINTENANCE_DB}"
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


def _subaward_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "subaward_id": 9001,
        "document_number": "DOC-9001",
        "sequence_number": 0,
        "subaward_code": "SC-0001",
        "organization_id": "ORG-1",
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "subaward_type_code": 1,
        "purchase_order_num": "PO-1",
        "title": "Test Subaward",
        "status_code": 1,
        "status_description": "Active",
        "account_number": "ACC-1",
        "vendor_number": "VEND-1",
        "requisitioner_id": "REQ-1",
        "requisitioner_unit": "UNIT-1",
        "archive_location": None,
        "closeout_date": None,
        "comments": None,
        "site_investigator": None,
        "cost_type": None,
        "date_of_fully_executed": None,
        "requisition_number": None,
        "fed_award_proj_desc": None,
        "f_and_a_rate": None,
        "de_minimus": None,
        "subaward_sequence_status": "ACTIVE",
        "ffata_required": None,
        "fsrs_subaward_number": None,
        "award_prime_sponsor_name": None,
        "award_sponsor_name": None,
        "extension_date_received": None,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
        "obj_id": "OBJ-1",
        "document_update_timestamp": "2025-01-01 00:00:00",
        "document_update_user": "kcuser",
        "document_ver_nbr": 1,
        "document_obj_id": "DOBJ-1",
    }
    row.update(overrides)
    return row


def _attachment_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "attachment_id": 501,
        "subaward_id": 9001,
        "subaward_code": "SC-0001",
        "sequence_number": 0,
        "attachment_type_code": 1,
        "attachment_type_description": "Agreement",
        "document_id": 1001,
        "file_data_id": "FD-1",
        "file_name": "agreement.pdf",
        "mime_type": "application/pdf",
        "document_status_code": "F",
        "description": "Executed agreement",
        "last_update_timestamp": "2025-01-01 00:00:00",
        "last_update_user": "kcuser",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
        "obj_id": "OBJ-ATT-1",
    }
    row.update(overrides)
    return row


def _dataset_stub(dataframe: pd.DataFrame) -> MagicMock:
    def _read() -> pd.DataFrame:
        return dataframe.copy()

    def _read_filtered(*, column: str, values, chunk_size: int = 1000) -> pd.DataFrame:
        if not values or dataframe.empty or column not in dataframe.columns:
            return pd.DataFrame(columns=dataframe.columns)
        mask = dataframe[column].isin(list(values))
        return dataframe[mask].reset_index(drop=True)

    stub = MagicMock()
    stub.read.side_effect = _read
    stub.read_filtered.side_effect = _read_filtered
    return stub


def _patched_oracle(fixtures: dict[str, list[dict]]):
    """fixtures keyed by DatasetSpec.key -> list of raw-Oracle-column
    rows. Any DATASETS key not given defaults to an empty DataFrame with
    that spec's own raw Oracle column names, so callers only need to
    supply the datasets a given test actually cares about."""
    frames = {}
    for spec in subaward_loader.DATASETS:
        rows = fixtures.get(spec.key)
        if rows:
            frames[spec.key] = pd.DataFrame(rows)
        else:
            frames[spec.key] = pd.DataFrame(columns=list(spec.columns))

    def _source(oracle_path: Path):
        for spec in subaward_loader.DATASETS:
            if oracle_path == spec.oracle_path:
                return _dataset_stub(frames[spec.key])
        raise AssertionError(f"unexpected Oracle source: {oracle_path}")

    return patch.object(subaward_loader, "OracleDataSource", side_effect=_source)


# --- parse_args: no default/no-verb destructive path --------------------


class ParseArgsSubawardSyncTest(unittest.TestCase):
    def test_bare_invocation_is_rejected(self) -> None:
        with self.assertRaises(SystemExit):
            subaward_loader.parse_args([])

    def test_limit_alone_is_accepted(self) -> None:
        arguments = subaward_loader.parse_args(["--limit", "5"])
        self.assertEqual(arguments.limit, 5)
        self.assertFalse(arguments.full_refresh)
        self.assertFalse(arguments.sync_all)
        self.assertFalse(arguments.reconcile_only)

    def test_sync_all_alone_is_accepted(self) -> None:
        arguments = subaward_loader.parse_args(["--sync-all"])
        self.assertTrue(arguments.sync_all)

    def test_reconcile_only_alone_is_accepted(self) -> None:
        arguments = subaward_loader.parse_args(["--reconcile-only"])
        self.assertTrue(arguments.reconcile_only)

    def test_full_refresh_alone_is_accepted(self) -> None:
        arguments = subaward_loader.parse_args(["--full-refresh"])
        self.assertTrue(arguments.full_refresh)

    def test_sync_all_and_reconcile_only_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            subaward_loader.parse_args(["--sync-all", "--reconcile-only"])

    def test_sync_all_and_load_subaward_code_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            subaward_loader.parse_args(["--sync-all", "--load-subaward-code", "SC-0001"])

    def test_full_refresh_and_max_families_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            subaward_loader.parse_args(["--full-refresh", "--max-families", "10"])

    def test_targeted_selector_still_parses(self) -> None:
        arguments = subaward_loader.parse_args(["--load-subaward-code", "SC-0001"])
        self.assertEqual(arguments.subaward_code, ["SC-0001"])


# --- main() exit-code contract (no PostgreSQL needed - run_sync_all/ ---
# --- reconcile_subaward_codes/create_postgres_engine are all mocked) ---


class MainExitCodeContractTest(unittest.TestCase):
    def test_sync_all_failed_family_exits_nonzero(self) -> None:
        with (
            patch.object(subaward_loader, "parse_args") as parse_args,
            patch.object(subaward_loader, "run_sync_all") as run_sync_all,
        ):
            parse_args.return_value = MagicMock(ecs=False, sync_all=True, reconcile_only=False)
            run_sync_all.return_value = {
                "requested": 2,
                "completed": 1,
                "failed": 1,
                "failed_codes": ["SC-BAD"],
                "totals": {},
                "reconciliation": {"oracle_only": [], "rds_only": []},
            }
            with self.assertRaises(SystemExit):
                subaward_loader.main()

    def test_sync_all_reconciliation_gap_exits_nonzero(self) -> None:
        with (
            patch.object(subaward_loader, "parse_args") as parse_args,
            patch.object(subaward_loader, "run_sync_all") as run_sync_all,
        ):
            parse_args.return_value = MagicMock(ecs=False, sync_all=True, reconcile_only=False)
            run_sync_all.return_value = {
                "requested": 2,
                "completed": 2,
                "failed": 0,
                "failed_codes": [],
                "totals": {},
                "reconciliation": {"oracle_only": ["SC-MISSING"], "rds_only": []},
            }
            with self.assertRaises(SystemExit):
                subaward_loader.main()

    def test_sync_all_clean_run_exits_zero(self) -> None:
        with (
            patch.object(subaward_loader, "parse_args") as parse_args,
            patch.object(subaward_loader, "run_sync_all") as run_sync_all,
        ):
            parse_args.return_value = MagicMock(ecs=False, sync_all=True, reconcile_only=False)
            run_sync_all.return_value = {
                "requested": 2,
                "completed": 2,
                "failed": 0,
                "failed_codes": [],
                "totals": {},
                "reconciliation": {"oracle_only": [], "rds_only": []},
            }
            subaward_loader.main()  # must not raise

    def test_sync_all_skipped_by_lock_exits_zero(self) -> None:
        with (
            patch.object(subaward_loader, "parse_args") as parse_args,
            patch.object(subaward_loader, "run_sync_all") as run_sync_all,
        ):
            parse_args.return_value = MagicMock(ecs=False, sync_all=True, reconcile_only=False)
            run_sync_all.return_value = {"skipped": True, "reason": "lock_held"}
            subaward_loader.main()  # must not raise

    def test_reconcile_only_gap_exits_nonzero(self) -> None:
        with (
            patch.object(subaward_loader, "parse_args") as parse_args,
            patch.object(subaward_loader, "create_postgres_engine"),
            patch.object(subaward_loader, "reconcile_subaward_codes") as reconcile,
        ):
            parse_args.return_value = MagicMock(ecs=False, sync_all=False, reconcile_only=True)
            reconcile.return_value = {
                "oracle_count": 5,
                "rds_count": 4,
                "oracle_only": ["SC-MISSING"],
                "rds_only": [],
            }
            with self.assertRaises(SystemExit):
                subaward_loader.main()


# --- Real-Postgres behavior ----------------------------------------------


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class _SubawardPostgresTestCase(unittest.TestCase):
    db_prefix = "pytest_subaward_sync"

    def setUp(self) -> None:
        self.db_name = f"{self.db_prefix}_{uuid.uuid4().hex[:12]}"

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'CREATE DATABASE "{self.db_name}"'))
        maintenance.dispose()

        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:{POSTGRES_PORT}/{self.db_name}"
        )
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def _codes(self) -> set[str]:
        with self.engine.connect() as connection:
            return set(
                connection.execute(text("SELECT subaward_code FROM archive.subaward"))
                .scalars()
                .all()
            )

    def _row_count(self, table: str) -> int:
        with self.engine.connect() as connection:
            return int(
                connection.execute(text(f"SELECT COUNT(*) FROM archive.{table}")).scalar_one()
            )

    def _run_sync_all(self, fixtures: dict[str, list[dict]]) -> dict[str, object]:
        with (
            _patched_oracle(fixtures),
            patch.object(subaward_loader, "create_postgres_engine", return_value=self.engine),
        ):
            return subaward_loader.run_sync_all()


class SyncAllNeverTruncatesTest(_SubawardPostgresTestCase):
    def test_sync_all_preserves_a_code_absent_from_oracle(self) -> None:
        # Seed a family directly via the existing safe path (never
        # --full-refresh) that will NOT appear in --sync-all's Oracle
        # fixture below.
        with (
            _patched_oracle({"subawards": [_subaward_row(subaward_id=1, subaward_code="SC-OLD")]}),
            patch.object(subaward_loader, "create_postgres_engine", return_value=self.engine),
            patch.object(subaward_loader, "apply_migrations"),
        ):
            subaward_loader.run_targeted_load(["SC-OLD"])

        self.assertEqual(self._codes(), {"SC-OLD"})

        result = self._run_sync_all(
            {"subawards": [_subaward_row(subaward_id=2, subaward_code="SC-NEW")]}
        )

        self.assertEqual(result["failed"], 0)
        # SC-OLD must still exist - --sync-all never deletes/truncates
        # rows merely because they disappeared from Oracle.
        self.assertEqual(self._codes(), {"SC-OLD", "SC-NEW"})
        self.assertEqual(result["reconciliation"]["rds_only"], ["SC-OLD"])
        self.assertEqual(result["reconciliation"]["oracle_only"], [])

    def test_sync_all_never_calls_clear_existing_data(self) -> None:
        with patch.object(subaward_loader, "clear_existing_data") as clear_existing_data:
            self._run_sync_all({"subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")]})
            clear_existing_data.assert_not_called()


class SyncAllIdempotencyTest(_SubawardPostgresTestCase):
    def test_rerun_with_no_oracle_changes_is_unchanged(self) -> None:
        fixtures = {
            "subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")],
            "attachments": [
                _attachment_row(attachment_id=501, subaward_id=1, subaward_code="SC-1")
            ],
        }

        first = self._run_sync_all(fixtures)
        self.assertEqual(first["failed"], 0)
        self.assertEqual(first["totals"]["subaward"]["inserted"], 1)
        self.assertEqual(first["totals"]["subaward_attachment"]["inserted"], 1)

        second = self._run_sync_all(fixtures)
        self.assertEqual(second["failed"], 0)
        self.assertEqual(second["totals"]["subaward"]["inserted"], 0)
        self.assertEqual(second["totals"]["subaward"]["updated"], 0)
        self.assertEqual(second["totals"]["subaward"]["unchanged"], 1)
        self.assertEqual(second["totals"]["subaward_attachment"]["unchanged"], 1)
        self.assertEqual(second["reconciliation"]["oracle_only"], [])

    def test_changed_source_metadata_produces_an_update(self) -> None:
        base = {"subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")]}
        self._run_sync_all(base)

        changed = {
            "subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1", title="Renamed")]
        }
        result = self._run_sync_all(changed)
        self.assertEqual(result["totals"]["subaward"]["updated"], 1)
        self.assertEqual(result["totals"]["subaward"]["inserted"], 0)

        with self.engine.connect() as connection:
            row = connection.execute(
                text("SELECT title FROM archive.subaward WHERE subaward_id = 1")
            ).scalar_one()
        self.assertEqual(row, "Renamed")


class SyncAllFamilyIsolationTest(_SubawardPostgresTestCase):
    def test_one_bad_family_does_not_block_the_others(self) -> None:
        fixtures = {
            "subawards": [
                _subaward_row(subaward_id=1, subaward_code="SC-GOOD"),
                # f_and_a_rate is NUMERIC(18,2) - this value overflows
                # that column, so it passes the pandas-level
                # prepare_dataset() checks (required values are all
                # present) and only fails once _upsert_rows actually
                # executes this family's INSERT, inside its own
                # per-family transaction.
                _subaward_row(
                    subaward_id=2,
                    subaward_code="SC-BAD",
                    f_and_a_rate=10**17,
                ),
            ],
        }
        result = self._run_sync_all(fixtures)

        self.assertEqual(result["completed"], 1)
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["failed_codes"], ["SC-BAD"])
        self.assertEqual(self._codes(), {"SC-GOOD"})


class SyncAllAdvisoryLockTest(_SubawardPostgresTestCase):
    def test_concurrent_sync_is_skipped_without_doing_work(self) -> None:
        holder = self.engine.connect()
        try:
            acquired = holder.execute(
                text("SELECT pg_try_advisory_lock(:key)"),
                {"key": subaward_loader.SUBAWARD_SYNC_ADVISORY_LOCK_KEY},
            ).scalar_one()
            self.assertTrue(acquired)

            result = self._run_sync_all(
                {"subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")]}
            )
            self.assertEqual(result, {"skipped": True, "reason": "lock_held"})
            self.assertEqual(self._codes(), set())
        finally:
            holder.execute(
                text("SELECT pg_advisory_unlock(:key)"),
                {"key": subaward_loader.SUBAWARD_SYNC_ADVISORY_LOCK_KEY},
            )
            holder.close()


class SyncAllAttachmentArchiveUntouchedTest(_SubawardPostgresTestCase):
    def test_binary_tracking_row_is_byte_identical_after_sync(self) -> None:
        fixtures = {
            "subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")],
            "attachments": [
                _attachment_row(attachment_id=501, subaward_id=1, subaward_code="SC-1")
            ],
        }
        self._run_sync_all(fixtures)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.subaward_attachment_archive (
                        attachment_id, subaward_id, subaward_code,
                        sequence_number, s3_bucket, s3_key, byte_size,
                        sha256, archive_status
                    ) VALUES (
                        501, 1, 'SC-1', 0, 'test-bucket', 'test/key',
                        1234, repeat('a', 64), 'ARCHIVED'
                    )
                    """
                )
            )

        def _checksum() -> str:
            with self.engine.connect() as connection:
                return connection.execute(
                    text(
                        """
                        SELECT md5(string_agg(md5(t.*::text), '' ORDER BY attachment_id))
                        FROM archive.subaward_attachment_archive t
                        """
                    )
                ).scalar_one()

        before = _checksum()

        # A second sync, including a metadata change to the parent
        # subaward, must not touch subaward_attachment_archive at all.
        self._run_sync_all(
            {
                "subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1", title="Changed")],
                "attachments": fixtures["attachments"],
            }
        )

        self.assertEqual(_checksum(), before)


class SyncAllChildTableReconciliationTest(_SubawardPostgresTestCase):
    def test_child_rows_reconcile_against_their_parent(self) -> None:
        fixtures = {
            "subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")],
            "attachments": [
                _attachment_row(attachment_id=501, subaward_id=1, subaward_code="SC-1")
            ],
            "notifications": [
                {
                    "notification_id": 701,
                    "owning_document_id_fk": 1,
                    "document_number": "DOC-9001",
                    "subaward_code": "SC-1",
                    "notification_type_id": 1,
                    "recipients": "a@example.edu",
                    "subject": "Test",
                    "message": "Test message",
                    "create_timestamp": "2025-01-01 00:00:00",
                    "update_timestamp": "2025-01-01 00:00:00",
                    "update_user": "kcuser",
                    "ver_nbr": 1,
                    "obj_id": "OBJ-NOTIF-1",
                }
            ],
        }
        result = self._run_sync_all(fixtures)

        self.assertEqual(result["failed"], 0)
        self.assertEqual(self._row_count("subaward"), 1)
        self.assertEqual(self._row_count("subaward_attachment"), 1)
        self.assertEqual(self._row_count("subaward_notification"), 1)

        with self.engine.connect() as connection:
            orphans = connection.execute(
                text(
                    """
                    SELECT COUNT(*) FROM archive.subaward_notification child
                    LEFT JOIN archive.subaward parent
                        ON parent.subaward_id = child.owning_document_id_fk
                    WHERE parent.subaward_id IS NULL
                    """
                )
            ).scalar_one()
        self.assertEqual(orphans, 0)


class ReconcileOnlyTest(_SubawardPostgresTestCase):
    def test_reconcile_only_writes_nothing(self) -> None:
        with (
            _patched_oracle({"subawards": [_subaward_row(subaward_id=1, subaward_code="SC-1")]}),
            patch.object(subaward_loader, "create_postgres_engine", return_value=self.engine),
            patch.object(subaward_loader, "apply_migrations"),
        ):
            subaward_loader.run_targeted_load(["SC-1"])

        before = self._row_count("subaward")

        with _patched_oracle(
            {
                "subawards": [
                    _subaward_row(subaward_id=1, subaward_code="SC-1"),
                    _subaward_row(subaward_id=2, subaward_code="SC-MISSING"),
                ]
            }
        ):
            result = subaward_loader.reconcile_subaward_codes(self.engine)

        self.assertEqual(self._row_count("subaward"), before)
        self.assertEqual(result["oracle_only"], ["SC-MISSING"])
        self.assertEqual(result["rds_only"], [])


if __name__ == "__main__":
    unittest.main()
