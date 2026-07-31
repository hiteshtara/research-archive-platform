"""Tests for Phase 4A: Award's incremental UPSERT layer (--load-award-id,
--create-batch/--load-batch/--show-batch) - see docs/architecture/ETL_BATCH_FRAMEWORK.md
and the Award domain research this was designed from.

Scoped strictly to the four tables load_awards_from_csv.py's full load
already populates (archive.award_version, archive.award_amount_info,
archive.award_person, archive.award_funding_proposal) plus four Tier 1
subsystem tables added to the same incremental UPSERT path since each
depends only on award_version(award_id) or a table that itself does:
archive.award_custom_data, archive.award_person_unit,
archive.award_person_credit_split, and
archive.award_person_unit_credit_split (see
docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md). No Award Budget,
Reporting, Contacts, Terms, or Time and Money table is touched anywhere
in this file.

CLI-parsing tests run against the real argparse parser (no PostgreSQL).
Everything that touches PostgreSQL runs against a real, uniquely-named,
throwaway database (mirroring tests/test_load_file_id.py and
tests/test_batch_workflow.py) - the insert/update/unchanged UPSERT
distinction, and the ux_award_one_primary_current partial unique index,
depend on genuine Postgres semantics a mock cannot exercise correctly.
Oracle is always mocked via an OracleDataSource-shaped stub - no real
infrastructure is ever touched. Skips entirely if no local PostgreSQL is
reachable.
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

import load_awards_from_csv as award_loader
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


def _oracle_batches_stub(batches: list[pd.DataFrame]) -> MagicMock:
    def _generator():
        yield from batches

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    return stub


def _version_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "award_sequence_status": "ACTIVE",
        "status_code": "16",
        "status_description": "Active",
        "title": "Test Award",
        "sponsor_code": "NIH",
        "sponsor_name": "National Institutes of Health",
        "prime_sponsor_code": None,
        "prime_sponsor_name": None,
        "lead_unit_number": "001",
        "lead_unit_name": "Test Unit",
        "proposal_number": "P-0001",
        "account_number": "12345",
        "sponsor_award_number": "R01-1234",
        "award_effective_date": "2025-01-01",
        "award_execution_date": "2025-01-01",
        "begin_date": "2025-01-01",
        "closeout_date": None,
        "transaction_type_code": "1",
        "transaction_type": "New",
        "modification_number": None,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "is_current_version": True,
    }
    row.update(overrides)
    return row


def _amount_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_amount_info_id": 501,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "anticipated_change_direct": 100.0,
        "anticipated_change_indirect": 10.0,
        "anticipated_total_direct": 100.0,
        "anticipated_total_indirect": 10.0,
        "obligated_total_direct": 100.0,
        "obligated_total_indirect": 10.0,
        "anticipated_total_amount": 110.0,
        "obligated_total_amount": 110.0,
        "tnm_document_number": "TNM-1",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _person_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_person_id": 601,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "person_id": "P123",
        "rolodex_id": None,
        "full_name": "Jane Researcher",
        "contact_role_code": "PI",
        "key_person_project_role": "Principal Investigator",
        "faculty_flag": "Y",
        "academic_year_effort": 10.0,
        "calendar_year_effort": None,
        "summer_effort": None,
        "total_effort": 10.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
    }
    row.update(overrides)
    return row


def _proposal_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_funding_proposal_id": 701,
        "award_id": 1,
        "proposal_id": 9001,
        "active": "Y",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _custom_data_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_custom_data_id": 801,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "custom_attribute_id": 42,
        "value": "Some Value",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _person_unit_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_person_unit_id": 901,
        "award_person_id": 601,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "unit_number": "001",
        "lead_unit_flag": "Y",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _person_credit_split_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_person_credit_split_id": 1001,
        "award_person_id": 601,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "inv_credit_type_code": "PRIME",
        "credit": 100.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _person_unit_credit_split_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_person_unit_credit_split_id": 1101,
        "award_person_unit_id": 901,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "inv_credit_type_code": "PRIME",
        "credit": 100.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class _AwardPostgresTestCase(unittest.TestCase):
    db_prefix = "pytest_award_incremental"

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

    def _row(self, table: str, **where: object) -> dict:
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

    def _scalar(self, sql: str, **params: object) -> object:
        with self.engine.connect() as connection:
            return connection.execute(text(sql), params).scalar_one()

    def _patched_oracle(
        self,
        *,
        versions: list[dict] | None = None,
        amounts: list[dict] | None = None,
        people: list[dict] | None = None,
        proposals: list[dict] | None = None,
        custom_data: list[dict] | None = None,
        person_units: list[dict] | None = None,
        person_credit_splits: list[dict] | None = None,
        person_unit_credit_splits: list[dict] | None = None,
    ):
        versions_df = pd.DataFrame(versions or [])
        amounts_df = pd.DataFrame(amounts or [])
        people_df = pd.DataFrame(people or [])
        proposals_df = pd.DataFrame(proposals or [])
        custom_data_df = pd.DataFrame(custom_data or [])
        person_units_df = pd.DataFrame(person_units or [])
        person_credit_splits_df = pd.DataFrame(person_credit_splits or [])
        person_unit_credit_splits_df = pd.DataFrame(
            person_unit_credit_splits or []
        )

        def _source(sql_path):
            if sql_path == award_loader.VERSIONS_ORACLE_SQL:
                return _oracle_batches_stub([versions_df])
            if sql_path == award_loader.AMOUNTS_ORACLE_SQL:
                return _oracle_batches_stub([amounts_df])
            if sql_path == award_loader.PEOPLE_ORACLE_SQL:
                return _oracle_batches_stub([people_df])
            if sql_path == award_loader.PROPOSALS_ORACLE_SQL:
                return _oracle_batches_stub([proposals_df])
            if sql_path == award_loader.CUSTOM_DATA_ORACLE_SQL:
                return _oracle_batches_stub([custom_data_df])
            if sql_path == award_loader.PERSON_UNITS_ORACLE_SQL:
                return _oracle_batches_stub([person_units_df])
            if sql_path == award_loader.PERSON_CREDIT_SPLITS_ORACLE_SQL:
                return _oracle_batches_stub([person_credit_splits_df])
            if sql_path == award_loader.PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL:
                return _oracle_batches_stub([person_unit_credit_splits_df])
            raise AssertionError(f"unexpected Oracle source: {sql_path}")

        return patch.object(
            award_loader, "OracleDataSource", side_effect=_source
        )


# --- parse_args --------------------------------------------------------


class ParseArgsAwardIncrementalTest(unittest.TestCase):
    def test_load_award_id_parses(self) -> None:
        args = award_loader.parse_args(["--load-award-id", "1"])
        self.assertEqual(args.load_award_id, 1)

    def test_defaults_are_none(self) -> None:
        args = award_loader.parse_args([])
        self.assertIsNone(args.load_award_id)
        self.assertIsNone(args.create_batch)
        self.assertIsNone(args.load_batch)
        self.assertIsNone(args.show_batch)
        self.assertFalse(args.dry_run)

    def test_create_batch_rejects_non_positive(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(["--create-batch", "0"])
        with self.assertRaises(SystemExit):
            award_loader.parse_args(["--create-batch", "-1"])

    def test_batch_verbs_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--create-batch", "10", "--show-batch", "1"]
            )

    def test_batch_verb_cannot_combine_with_load_award_id(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--create-batch", "10", "--load-award-id", "1"]
            )

    def test_dry_run_combines_with_load_award_id(self) -> None:
        args = award_loader.parse_args(["--load-award-id", "1", "--dry-run"])
        self.assertEqual(args.load_award_id, 1)
        self.assertTrue(args.dry_run)


# --- bounded Oracle readers (mocked Oracle, no Postgres needed) ---------


class BoundedOracleReadersTest(unittest.TestCase):
    def test_read_award_number_for_award_id_finds_exact_match(self) -> None:
        source = _oracle_batches_stub(
            [pd.DataFrame([_version_row(award_id=1, award_number="A-1")])]
        )
        result = award_loader.read_award_number_for_award_id(source, 1)
        self.assertEqual(result, "A-1")

    def test_read_award_number_for_award_id_returns_none_when_absent(self) -> None:
        source = _oracle_batches_stub(
            [pd.DataFrame([_version_row(award_id=1, award_number="A-1")])]
        )
        result = award_loader.read_award_number_for_award_id(source, 999)
        self.assertIsNone(result)

    def test_read_award_versions_matching_award_numbers_scans_full_source(
        self,
    ) -> None:
        source = _oracle_batches_stub(
            [
                pd.DataFrame(
                    [
                        _version_row(award_id=1, award_number="A-1", sequence_number=0),
                        _version_row(award_id=2, award_number="A-1", sequence_number=1),
                        _version_row(award_id=3, award_number="A-2", sequence_number=0),
                    ]
                )
            ]
        )
        result = award_loader.read_award_versions_matching_award_numbers(
            source, {"A-1"}
        )
        self.assertEqual(sorted(result["award_id"].tolist()), [1, 2])

    def test_read_award_children_matching_award_ids_scans_full_source(self) -> None:
        source = _oracle_batches_stub(
            [
                pd.DataFrame(
                    [
                        _amount_row(award_amount_info_id=1, award_id=1),
                        _amount_row(award_amount_info_id=2, award_id=1),
                        _amount_row(award_amount_info_id=3, award_id=2),
                    ]
                )
            ]
        )
        result = award_loader.read_award_children_matching_award_ids(source, {1})
        self.assertEqual(sorted(result["award_amount_info_id"].tolist()), [1, 2])

    def test_readers_return_empty_dataframe_for_empty_target_set(self) -> None:
        source = _oracle_batches_stub([pd.DataFrame([_version_row()])])
        self.assertTrue(
            award_loader.read_award_versions_matching_award_numbers(
                source, set()
            ).empty
        )
        self.assertTrue(
            award_loader.read_award_children_matching_award_ids(
                source, set()
            ).empty
        )


# --- _run_load_award_id --------------------------------------------------


class RunLoadAwardIdTest(_AwardPostgresTestCase):
    def test_first_load_inserts_all_eight_tables(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            people=[_person_row()],
            proposals=[_proposal_row()],
            custom_data=[_custom_data_row()],
            person_units=[_person_unit_row()],
            person_credit_splits=[_person_credit_split_row()],
            person_unit_credit_splits=[_person_unit_credit_split_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["award_number"], "A-0001")
        self.assertEqual(report["family_size"], 1)
        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["amount_info_inserted"], 1)
        self.assertEqual(report["person_inserted"], 1)
        self.assertEqual(report["funding_proposal_inserted"], 1)
        self.assertEqual(report["custom_data_inserted"], 1)
        self.assertEqual(report["person_unit_inserted"], 1)
        self.assertEqual(report["person_credit_split_inserted"], 1)
        self.assertEqual(report["person_unit_credit_split_inserted"], 1)

        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["title"], "Test Award")
        self.assertTrue(version_row["is_primary_current"])

        amount_row = self._row("award_amount_info", award_amount_info_id=501)
        self.assertEqual(float(amount_row["obligated_total_amount"]), 110.0)

        person_row = self._row("award_person", award_person_id=601)
        self.assertEqual(person_row["full_name"], "Jane Researcher")

        proposal_row = self._row(
            "award_funding_proposal", award_funding_proposal_id=701
        )
        self.assertEqual(proposal_row["proposal_id"], 9001)

        custom_data_row = self._row(
            "award_custom_data", award_custom_data_id=801
        )
        self.assertEqual(custom_data_row["value"], "Some Value")
        self.assertEqual(custom_data_row["custom_attribute_id"], 42)

        person_unit_row = self._row(
            "award_person_unit", award_person_unit_id=901
        )
        self.assertEqual(person_unit_row["unit_number"], "001")
        self.assertEqual(person_unit_row["lead_unit_flag"], "Y")

        person_credit_split_row = self._row(
            "award_person_credit_split", award_person_credit_split_id=1001
        )
        self.assertEqual(float(person_credit_split_row["credit"]), 100.0)

        person_unit_credit_split_row = self._row(
            "award_person_unit_credit_split",
            award_person_unit_credit_split_id=1101,
        )
        self.assertEqual(
            float(person_unit_credit_split_row["credit"]), 100.0
        )
        self.assertEqual(
            person_unit_credit_split_row["award_person_unit_id"], 901
        )

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            people=[_person_row()],
            proposals=[_proposal_row()],
            custom_data=[_custom_data_row()],
            person_units=[_person_unit_row()],
            person_credit_splits=[_person_credit_split_row()],
            person_unit_credit_splits=[_person_unit_credit_split_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(report["unchanged"], 1)
        self.assertEqual(report["amount_info_unchanged"], 1)
        self.assertEqual(report["person_unchanged"], 1)
        self.assertEqual(report["funding_proposal_unchanged"], 1)
        self.assertEqual(report["custom_data_unchanged"], 1)
        self.assertEqual(report["person_unit_unchanged"], 1)
        self.assertEqual(report["person_credit_split_unchanged"], 1)
        self.assertEqual(report["person_unit_credit_split_unchanged"], 1)

    def test_person_unit_credit_split_loads_correctly_when_its_parent_unit_is_new(
        self,
    ) -> None:
        # award_person_unit_credit_split's FK parent (award_person_unit)
        # is being inserted for the very first time in this same
        # transaction - proves the load-order decision (unit before
        # unit_credit_split) actually holds.
        with self._patched_oracle(
            versions=[_version_row()],
            people=[_person_row()],
            person_units=[_person_unit_row()],
            person_unit_credit_splits=[_person_unit_credit_split_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["person_unit_inserted"], 1)
        self.assertEqual(report["person_unit_credit_split_inserted"], 1)

        row = self._row(
            "award_person_unit_credit_split",
            award_person_unit_credit_split_id=1101,
        )
        self.assertEqual(row["award_person_unit_id"], 901)

    def test_person_credit_split_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            people=[_person_row()],
            person_credit_splits=[_person_credit_split_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            people=[_person_row()],
            person_credit_splits=[_person_credit_split_row(credit=50.0)],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["person_credit_split_updated"], 1)
        row = self._row(
            "award_person_credit_split", award_person_credit_split_id=1001
        )
        self.assertEqual(float(row["credit"]), 50.0)

    def test_custom_data_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], custom_data=[_custom_data_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            custom_data=[_custom_data_row(value="Changed Value")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["custom_data_updated"], 1)
        custom_data_row = self._row(
            "award_custom_data", award_custom_data_id=801
        )
        self.assertEqual(custom_data_row["value"], "Changed Value")

    def test_metadata_change_produces_an_update(self) -> None:
        with self._patched_oracle(versions=[_version_row()]):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(versions=[_version_row(title="Renamed Award")]):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["updated"], 1)
        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["title"], "Renamed Award")

    def test_award_id_not_found_in_oracle_reports_missing_and_writes_nothing(
        self,
    ) -> None:
        with self._patched_oracle(versions=[]):
            report = award_loader._run_load_award_id(self.engine, 999)

        self.assertEqual(report["missing"], 1)
        self.assertIsNone(report["award_number"])

        count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_version WHERE award_id = 999"
        )
        self.assertEqual(count, 0)

    def test_dry_run_reports_accurate_counts_but_persists_nothing(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            people=[_person_row()],
            proposals=[_proposal_row()],
            custom_data=[_custom_data_row()],
            person_units=[_person_unit_row()],
            person_credit_splits=[_person_credit_split_row()],
            person_unit_credit_splits=[_person_unit_credit_split_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1, dry_run=True)

        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["custom_data_inserted"], 1)
        self.assertEqual(report["person_unit_inserted"], 1)
        self.assertEqual(report["person_credit_split_inserted"], 1)
        self.assertEqual(report["person_unit_credit_split_inserted"], 1)

        count = self._scalar("SELECT COUNT(*) FROM archive.award_version")
        self.assertEqual(count, 0)
        custom_data_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_custom_data"
        )
        self.assertEqual(custom_data_count, 0)
        person_unit_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_person_unit"
        )
        self.assertEqual(person_unit_count, 0)
        load_run_count = self._scalar("SELECT COUNT(*) FROM archive.load_run")
        self.assertEqual(load_run_count, 0)

    def test_does_not_truncate_unrelated_existing_award(self) -> None:
        with self._patched_oracle(versions=[_version_row(award_id=2, award_number="A-0002")]):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(versions=[_version_row(award_id=1, award_number="A-0001")]):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_version")
        self.assertEqual(total, 2)

    def test_family_widening_flips_old_primary_current_to_false(self) -> None:
        # award_id=1 (sequence 0) is the only version at first, so it's
        # primary. A new sequence (award_id=2) is later created for the
        # same award_number - loading award_id=2 must widen to the whole
        # family and correctly flip award_id=1's is_primary_current to
        # FALSE, or the partial unique index would allow two TRUE rows to
        # coexist incorrectly (it would actually reject the second TRUE,
        # proving this test would fail loudly if the widening didn't
        # happen).
        with self._patched_oracle(
            versions=[
                _version_row(
                    award_id=1,
                    award_number="A-0001",
                    sequence_number=0,
                    is_current_version=True,
                )
            ]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        first = self._row("award_version", award_id=1)
        self.assertTrue(first["is_primary_current"])

        with self._patched_oracle(
            versions=[
                _version_row(
                    award_id=1,
                    award_number="A-0001",
                    sequence_number=0,
                    is_current_version=False,
                ),
                _version_row(
                    award_id=2,
                    award_number="A-0001",
                    sequence_number=1,
                    is_current_version=True,
                ),
            ]
        ):
            report = award_loader._run_load_award_id(self.engine, 2)

        self.assertEqual(report["family_size"], 2)

        old_row = self._row("award_version", award_id=1)
        new_row = self._row("award_version", award_id=2)
        self.assertFalse(old_row["is_primary_current"])
        self.assertTrue(new_row["is_primary_current"])

        primary_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_version "
            "WHERE award_number = 'A-0001' AND is_primary_current = TRUE"
        )
        self.assertEqual(primary_count, 1)

    def test_custom_data_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            custom_data=[
                _custom_data_row(
                    award_custom_data_id=802, award_id=2, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            custom_data=[_custom_data_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_custom_data")
        self.assertEqual(total, 2)

    def test_person_units_do_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            people=[_person_row(award_person_id=602, award_id=2, award_number="A-0002")],
            person_units=[
                _person_unit_row(
                    award_person_unit_id=902,
                    award_person_id=602,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            people=[_person_row()],
            person_units=[_person_unit_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_person_unit")
        self.assertEqual(total, 2)

    def test_never_creates_an_s3_client_or_touches_unrelated_domains(self) -> None:
        # Award has no BLOB/S3 concept at all - this is a structural
        # sanity check that _run_load_award_id's own module has no such
        # import to accidentally invoke.
        self.assertFalse(hasattr(award_loader, "create_s3_client"))


# --- Batch framework integration -----------------------------------------


class RunCreateAwardBatchTest(_AwardPostgresTestCase):
    def test_raises_for_non_positive_size(self) -> None:
        with self.assertRaises(ValueError):
            award_loader._run_create_award_batch(self.engine, 0)

    def test_selects_exactly_n_distinct_award_ids_ascending(self) -> None:
        with self._patched_oracle(
            versions=[
                _version_row(award_id=aid, award_number=f"A-{aid:04d}")
                for aid in [5, 3, 1, 4, 2]
            ]
        ):
            result = award_loader._run_create_award_batch(self.engine, 3)

        self.assertEqual(result["selected_award_ids"], [1, 2, 3])
        self.assertEqual(result["selected_count"], 3)

    def test_persists_membership_with_generic_batch_domain(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            result = award_loader._run_create_award_batch(self.engine, 1)

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(batch_row["domain"], "AWARD")
        self.assertEqual(batch_row["entity_type"], "AWARD")


class RunLoadAwardBatchTest(_AwardPostgresTestCase):
    def _create_batch(self, award_ids: list[int]) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES ('AWARD', 'AWARD', :size, 'CREATED', "
                    "'TEST_FIXTURE') RETURNING batch_id"
                ),
                {"size": len(award_ids)},
            ).scalar_one()
            for ordinal, award_id in enumerate(award_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :award_id, :ordinal, 'PENDING')"
                    ),
                    {"batch_id": batch_id, "award_id": award_id, "ordinal": ordinal},
                )
        return int(batch_id)

    def test_loads_every_batch_member(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            people=[
                _person_row(award_person_id=601, award_id=1),
                _person_row(
                    award_person_id=602, award_id=2, award_number="A-0002"
                ),
            ],
            custom_data=[
                _custom_data_row(award_custom_data_id=801, award_id=1),
                _custom_data_row(
                    award_custom_data_id=802, award_id=2, award_number="A-0002"
                ),
            ],
            person_units=[
                _person_unit_row(
                    award_person_unit_id=901, award_person_id=601, award_id=1
                ),
                _person_unit_row(
                    award_person_unit_id=902,
                    award_person_id=602,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["families_loaded"], 2)
        self.assertEqual(report["inserted"], 2)
        self.assertEqual(report["custom_data_inserted"], 2)
        self.assertEqual(report["person_unit_inserted"], 2)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_version")
        self.assertEqual(total, 2)
        custom_data_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_custom_data"
        )
        self.assertEqual(custom_data_total, 2)
        person_unit_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_person_unit"
        )
        self.assertEqual(person_unit_total, 2)

    def test_deduplicates_award_ids_sharing_one_award_number(self) -> None:
        # award_id 1 and 2 are two sequence versions of the SAME
        # award_number - both are batch members, but only one Oracle
        # scan/upsert pass should happen for that family.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(
                    award_id=1, award_number="A-0001", sequence_number=0,
                    is_current_version=False,
                ),
                _version_row(
                    award_id=2, award_number="A-0001", sequence_number=1,
                    is_current_version=True,
                ),
            ]
        ):
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["families_loaded"], 1)
        self.assertEqual(report["inserted"], 2)

        item_1 = self._row("etl_batch_item", batch_id=batch_id, entity_key=1)
        item_2 = self._row("etl_batch_item", batch_id=batch_id, entity_key=2)
        self.assertEqual(item_1["status"], "COMPLETED")
        self.assertEqual(item_2["status"], "COMPLETED")

    def test_missing_award_id_is_reported_and_flagged(self) -> None:
        batch_id = self._create_batch([1, 999999])
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["missing_in_oracle"], 1)
        missing_item = self._row(
            "etl_batch_item", batch_id=batch_id, entity_key=999999
        )
        self.assertEqual(missing_item["status"], "MISSING_SOURCE")

    def test_batch_status_becomes_ready_on_success(self) -> None:
        batch_id = self._create_batch([1])
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)

        batch_row = self._row("etl_batch", batch_id=batch_id)
        self.assertEqual(batch_row["status"], "READY")

    def test_does_not_touch_unrelated_pending_award(self) -> None:
        batch_id = self._create_batch([1])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ]
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)

        count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_version WHERE award_id = 2"
        )
        self.assertEqual(count, 0)


class ShowAwardBatchTest(_AwardPostgresTestCase):
    def test_generic_show_batch_works_for_award_domain(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            result = award_loader._run_create_award_batch(self.engine, 1)

        from archive_etl.batch import framework as batch_framework

        report = batch_framework.show_batch(
            self.engine,
            result["batch_id"],
            domain=award_loader.AWARD_BATCH_DOMAIN,
            entity_type=award_loader.AWARD_BATCH_ENTITY_TYPE,
        )
        self.assertTrue(report["found"])
        self.assertEqual(report["domain"], "AWARD")
        self.assertEqual(report["total_items"], 1)


class MainDispatchTest(unittest.TestCase):
    """Proves main() routes each new verb to its own function and returns
    immediately, without ever falling through to the full-load path -
    fully mocked, no real Postgres/Oracle needed."""

    def test_load_award_id_short_circuits_full_load(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations"),
            patch.object(award_loader, "_run_load_award_id") as run_load_award_id,
            patch.object(award_loader.OracleDataSource, "__init__", return_value=None),
        ):
            parse_args.return_value = MagicMock(
                load_award_id=1,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                dry_run=False,
            )
            award_loader.main()

        run_load_award_id.assert_called_once()
        self.assertEqual(run_load_award_id.call_args.args[1], 1)

    def test_create_batch_short_circuits_full_load(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations"),
            patch.object(award_loader, "_run_create_award_batch") as run_create,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=10,
                load_batch=None,
                show_batch=None,
                dry_run=False,
            )
            award_loader.main()

        run_create.assert_called_once()
        self.assertEqual(run_create.call_args.args[1], 10)

    def test_load_batch_short_circuits_full_load(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations"),
            patch.object(award_loader, "_run_load_award_batch") as run_load_batch,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=None,
                load_batch=5,
                show_batch=None,
                dry_run=True,
            )
            award_loader.main()

        run_load_batch.assert_called_once()
        self.assertEqual(run_load_batch.call_args.args[1], 5)
        self.assertTrue(run_load_batch.call_args.kwargs["dry_run"])

    def test_show_batch_short_circuits_full_load_and_never_migrates(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations") as apply_migrations,
            patch.object(
                award_loader.batch_framework,
                "show_batch",
                return_value={"batch_id": 5, "found": False},
            ) as show_batch,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=5,
                dry_run=False,
            )
            award_loader.main()

        show_batch.assert_called_once()
        apply_migrations.assert_not_called()

    def test_none_of_the_new_verbs_run_the_full_load(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(award_loader, "OracleDataSource") as oracle_source,
            patch.object(award_loader, "create_postgres_engine") as create_engine,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=1,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                dry_run=False,
            )
            create_engine.return_value = MagicMock()
            with patch.object(award_loader, "apply_migrations"):
                with patch.object(award_loader, "_run_load_award_id"):
                    award_loader.main()

        # The full load's own unconditional Oracle reads
        # (VERSIONS_ORACLE_SQL etc.) must never happen when a new verb
        # is active - only _run_load_award_id (mocked above) may touch
        # Oracle for this dispatch.
        oracle_source.assert_not_called()


if __name__ == "__main__":
    unittest.main()
