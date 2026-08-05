"""Regression tests for the Proposal batch framework (--create-batch/
--load-batch/--show-batch), mirroring test_award_incremental_upsert.py's
own _AwardPostgresTestCase pattern: a real, throwaway local PostgreSQL
database per test (migrations applied for real), with Oracle stubbed via
OracleDataSource's read_filtered()/read_batches(). Skipped entirely when
no local PostgreSQL is reachable, exactly like the Award tests."""

from __future__ import annotations

import getpass
import os
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import load_proposals_from_csv as proposal_loader
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


def _row(columns: list[str], **overrides: object) -> dict:
    """A dict with every column in `columns` defaulted to None, then
    overridden - avoids KeyErrors from upsert_*'s dataframe[COLUMNS]
    selection when a fixture only cares about a few real values."""
    fixture = {column: None for column in columns}
    fixture.update(overrides)
    return fixture


def _oracle_batches_stub(batches: list[pd.DataFrame]) -> MagicMock:
    def _generator():
        yield from batches

    def _read_filtered(*, column: str, values, chunk_size: int = 1000) -> pd.DataFrame:
        if not values:
            return pd.DataFrame()
        non_empty = [batch for batch in batches if not batch.empty]
        if not non_empty:
            return pd.DataFrame()
        combined = pd.concat(non_empty, ignore_index=True)
        column_name = column.lower()
        if column_name not in combined.columns:
            return pd.DataFrame()
        mask = combined[column_name].isin(list(values))
        if not mask.any():
            return pd.DataFrame()
        return combined[mask].reset_index(drop=True)

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    stub.read_filtered.side_effect = _read_filtered
    return stub


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class _ProposalPostgresTestCase(unittest.TestCase):
    db_prefix = "pytest_proposal_batch"

    def setUp(self) -> None:
        import uuid

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

    def _patched_oracle(
        self,
        *,
        proposal_numbers_ascending: list[dict] | None = None,
        versions: list[dict] | None = None,
        awards: list[dict] | None = None,
        attachments: list[dict] | None = None,
        persons: list[dict] | None = None,
        person_units: list[dict] | None = None,
        unit_contacts: list[dict] | None = None,
        comments: list[dict] | None = None,
        custom_data: list[dict] | None = None,
    ):
        proposal_numbers_df = pd.DataFrame(proposal_numbers_ascending or [])
        versions_df = pd.DataFrame(versions or [])
        awards_df = pd.DataFrame(awards or [])
        attachments_df = pd.DataFrame(attachments or [])
        persons_df = pd.DataFrame(persons or [])
        person_units_df = pd.DataFrame(person_units or [])
        unit_contacts_df = pd.DataFrame(unit_contacts or [])
        comments_df = pd.DataFrame(comments or [])
        custom_data_df = pd.DataFrame(custom_data or [])

        def _source(sql_path):
            if sql_path == proposal_loader.PROPOSAL_NUMBERS_ASCENDING_ORACLE_SQL:
                return _oracle_batches_stub([proposal_numbers_df])
            if sql_path == proposal_loader.VERSIONS_ORACLE_SQL:
                return _oracle_batches_stub([versions_df])
            if sql_path == proposal_loader.AWARDS_ORACLE_SQL:
                return _oracle_batches_stub([awards_df])
            if sql_path == proposal_loader.ATTACHMENTS_ORACLE_SQL:
                return _oracle_batches_stub([attachments_df])
            if sql_path == proposal_loader.PERSONS_ORACLE_SQL:
                return _oracle_batches_stub([persons_df])
            if sql_path == proposal_loader.PERSON_UNITS_ORACLE_SQL:
                return _oracle_batches_stub([person_units_df])
            if sql_path == proposal_loader.UNIT_CONTACTS_ORACLE_SQL:
                return _oracle_batches_stub([unit_contacts_df])
            if sql_path == proposal_loader.COMMENTS_ORACLE_SQL:
                return _oracle_batches_stub([comments_df])
            if sql_path == proposal_loader.CUSTOM_DATA_ORACLE_SQL:
                return _oracle_batches_stub([custom_data_df])
            raise AssertionError(f"unexpected Oracle source: {sql_path}")

        return patch.object(proposal_loader, "OracleDataSource", side_effect=_source)

    def _insert_archived_version(self, proposal_number: str, proposal_id: int) -> None:
        """Simulates a family loaded before/outside the batch framework
        (--load-proposal-number, or the removed --max-families) - a real
        archive.proposal_version row with no etl_batch_proposal_item
        history at all."""
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.proposal_version (
                        proposal_id, proposal_number, version_number,
                        proposal_sequence_status
                    ) VALUES (:proposal_id, :proposal_number, 1, 'ACTIVE')
                    """
                ),
                {"proposal_id": proposal_id, "proposal_number": proposal_number},
            )

    def _batch_item(self, batch_id: int, proposal_number: str) -> dict:
        with self.engine.connect() as connection:
            return dict(
                connection.execute(
                    text(
                        "SELECT * FROM archive.etl_batch_proposal_item "
                        "WHERE batch_id = :batch_id AND proposal_number = :proposal_number"
                    ),
                    {"batch_id": batch_id, "proposal_number": proposal_number},
                )
                .mappings()
                .one()
            )

    def _version_count(self, proposal_number: str) -> int:
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.proposal_version "
                    "WHERE proposal_number = :proposal_number"
                ),
                {"proposal_number": proposal_number},
            ).scalar_one()


class ExcludedProposalNumbersTest(_ProposalPostgresTestCase):
    def test_already_archived_family_with_no_batch_history_is_excluded(self) -> None:
        # The real gap this whole framework closes: a family loaded
        # directly (e.g. --load-proposal-number) has archive.proposal_version
        # rows but zero etl_batch_proposal_item history.
        self._insert_archived_version("900", 9001)

        excluded = proposal_loader._excluded_proposal_numbers(self.engine)

        self.assertIn("900", excluded)

    def test_completed_batch_item_is_excluded(self) -> None:
        # Not present in archive.proposal_version at all - isolates the
        # batch-tracking exclusion path from the archive-awareness path.
        result = proposal_loader._create_proposal_batch_record(
            self.engine,
            requested_size=1,
            selected_proposal_numbers=["910"],
            selection_strategy="TEST",
            run_id=None,
        )
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.etl_batch_proposal_item "
                    "SET status = 'COMPLETED' "
                    "WHERE batch_id = :batch_id AND proposal_number = '910'"
                ),
                {"batch_id": result["batch_id"]},
            )

        excluded = proposal_loader._excluded_proposal_numbers(self.engine)

        self.assertIn("910", excluded)

    def test_active_batch_item_is_excluded(self) -> None:
        # A batch in READY/PROCESSING (still active, not yet resolved)
        # claims "920" - it must not be reselected by a concurrent/
        # second --create-batch call even though its own item status is
        # still PENDING. Mirrors
        # load_awards_from_csv._excluded_completed_and_active_award_ids's
        # own READY/PROCESSING check exactly - a freshly CREATED batch
        # (before it starts processing) is not itself "active" in this
        # sense, so the test advances it to READY explicitly.
        result = proposal_loader._create_proposal_batch_record(
            self.engine,
            requested_size=1,
            selected_proposal_numbers=["920"],
            selection_strategy="TEST",
            run_id=None,
        )
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.etl_batch SET status = 'READY' "
                    "WHERE batch_id = :batch_id"
                ),
                {"batch_id": result["batch_id"]},
            )

        excluded = proposal_loader._excluded_proposal_numbers(self.engine)

        self.assertIn("920", excluded)

    def test_genuinely_new_family_is_selected(self) -> None:
        with self._patched_oracle(
            proposal_numbers_ascending=[{"proposal_number": "930"}]
        ):
            result = proposal_loader._run_create_proposal_batch(self.engine, 1)

        self.assertEqual(result["selected_proposal_numbers"], ["930"])
        self.assertEqual(result["selected_count"], 1)


class LoadProposalBatchTest(_ProposalPostgresTestCase):
    def test_all_versions_of_one_family_load_together(self) -> None:
        with self._patched_oracle(
            versions=[
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=9401,
                    proposal_number="940",
                    version_number=1,
                    proposal_sequence_status="ARCHIVED",
                ),
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=9402,
                    proposal_number="940",
                    version_number=2,
                    proposal_sequence_status="ACTIVE",
                ),
            ]
        ):
            result = proposal_loader._create_proposal_batch_record(
                self.engine,
                requested_size=1,
                selected_proposal_numbers=["940"],
                selection_strategy="TEST",
                run_id=None,
            )
            report = proposal_loader._run_load_proposal_batch(
                self.engine, result["batch_id"]
            )

        self.assertEqual(report["completed_families"], 1)
        self.assertEqual(report["failed_families"], 0)
        self.assertEqual(self._version_count("940"), 2)

    def test_deliberate_targeted_reload_still_works(self) -> None:
        # --load-proposal-number's underlying function, entirely outside
        # the batch framework - must still work unchanged after the
        # upsert_* return-type refactor. run_targeted_load() creates its
        # own engine from POSTGRES_* env vars (real --ecs/CLI usage) -
        # patched here to reuse this test's throwaway database instead.
        with (
            patch.object(
                proposal_loader, "create_postgres_engine", return_value=self.engine
            ),
            self._patched_oracle(
                versions=[
                    _row(
                        proposal_loader.VERSION_COLUMNS,
                        proposal_id=9501,
                        proposal_number="950",
                        version_number=1,
                        proposal_sequence_status="ACTIVE",
                    ),
                ]
            ),
        ):
            proposal_loader.run_targeted_load(["950"])

        self.assertEqual(self._version_count("950"), 1)

    def test_retry_of_failed_family_works_without_reloading_the_batch(self) -> None:
        oracle_data = self._patched_oracle(
            versions=[
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=9601,
                    proposal_number="960",
                    version_number=1,
                    proposal_sequence_status="ACTIVE",
                ),
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=9701,
                    proposal_number="970",
                    version_number=1,
                    proposal_sequence_status="ACTIVE",
                ),
            ]
        )

        call_count = {"960": 0}
        real_upsert = proposal_loader.upsert_proposal_versions

        def flaky_upsert(connection, dataframe):
            if not dataframe.empty and (dataframe["proposal_number"] == "960").any():
                call_count["960"] += 1
                if call_count["960"] == 1:
                    raise RuntimeError("simulated failure for family 960")
            return real_upsert(connection, dataframe)

        with oracle_data:
            result = proposal_loader._create_proposal_batch_record(
                self.engine,
                requested_size=2,
                selected_proposal_numbers=["960", "970"],
                selection_strategy="TEST",
                run_id=None,
            )
            batch_id = result["batch_id"]

            with patch.object(
                proposal_loader, "upsert_proposal_versions", side_effect=flaky_upsert
            ):
                first_report = proposal_loader._run_load_proposal_batch(
                    self.engine, batch_id
                )

        self.assertEqual(first_report["completed_families"], 1)
        self.assertEqual(first_report["failed_families"], 1)

        failed_item = self._batch_item(batch_id, "960")
        self.assertEqual(failed_item["status"], "FAILED")
        self.assertEqual(failed_item["attempt_count"], 1)

        completed_item = self._batch_item(batch_id, "970")
        self.assertEqual(completed_item["status"], "COMPLETED")
        completed_at_first_pass = completed_item["completed_at"]

        with oracle_data:
            second_report = proposal_loader._run_load_proposal_batch(
                self.engine, batch_id
            )

        # Only the FAILED family is retried - the batch is never reloaded
        # wholesale.
        self.assertEqual(second_report["requested_families"], 2)
        self.assertEqual(second_report["selected_families"], 1)
        self.assertEqual(second_report["completed_families"], 1)
        self.assertEqual(second_report["failed_families"], 0)

        retried_item = self._batch_item(batch_id, "960")
        self.assertEqual(retried_item["status"], "COMPLETED")
        self.assertEqual(retried_item["attempt_count"], 1)

        untouched_item = self._batch_item(batch_id, "970")
        self.assertEqual(untouched_item["completed_at"], completed_at_first_pass)

    def test_missing_linked_award_does_not_abort_unrelated_families(self) -> None:
        with self._patched_oracle(
            versions=[
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=10001,
                    proposal_number="1000",
                    version_number=1,
                    proposal_sequence_status="ACTIVE",
                ),
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=10101,
                    proposal_number="1010",
                    version_number=1,
                    proposal_sequence_status="ACTIVE",
                ),
            ],
            awards=[
                # award_id 555555 is not (and never will be, for this
                # test) present in archive.award_version.
                _row(
                    proposal_loader.AWARD_COLUMNS,
                    award_funding_proposal_id=1,
                    proposal_id=10001,
                    award_id=555555,
                    active=True,
                ),
            ],
        ):
            result = proposal_loader._create_proposal_batch_record(
                self.engine,
                requested_size=2,
                selected_proposal_numbers=["1000", "1010"],
                selection_strategy="TEST",
                run_id=None,
            )
            report = proposal_loader._run_load_proposal_batch(
                self.engine, result["batch_id"]
            )

        self.assertEqual(report["completed_families"], 2)
        self.assertEqual(report["failed_families"], 0)
        self.assertEqual(report["missing_linked_awards"], 1)
        self.assertEqual(self._version_count("1000"), 1)
        self.assertEqual(self._version_count("1010"), 1)

        item_1000 = self._batch_item(result["batch_id"], "1000")
        self.assertEqual(item_1000["status"], "COMPLETED")


class UpsertIdempotencyTest(_ProposalPostgresTestCase):
    def test_second_identical_load_reports_unchanged_not_updated(self) -> None:
        versions = pd.DataFrame(
            [
                _row(
                    proposal_loader.VERSION_COLUMNS,
                    proposal_id=9901,
                    proposal_number="990",
                    version_number=1,
                    proposal_sequence_status="ACTIVE",
                )
            ]
        )

        # Two separate transactions, matching real usage: each upsert_*
        # call's CREATE TEMPORARY TABLE ... ON COMMIT DROP only actually
        # drops the stage table when its own transaction commits.
        with self.engine.begin() as connection:
            first = proposal_loader.upsert_proposal_versions(connection, versions)
        with self.engine.begin() as connection:
            second = proposal_loader.upsert_proposal_versions(connection, versions)

        self.assertEqual(
            first, {"inserted": 1, "updated": 0, "unchanged": 0, "skipped": 0}
        )
        self.assertEqual(
            second, {"inserted": 0, "updated": 0, "unchanged": 1, "skipped": 0}
        )


if __name__ == "__main__":
    unittest.main()
