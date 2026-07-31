"""Tests for the generic ETL batch framework (archive_etl.batch.framework
/ archive.etl_batch / archive.etl_batch_item) - see
database/migrations/V037__create_etl_batch_framework.sql and
docs/ETL_BATCH_FRAMEWORK.md.

Deliberately uses a made-up domain/entity_type pair ("TEST_DOMAIN"/
"TEST_ENTITY") throughout, not "AWARD_ATTACHMENT"/"PHYSICAL_FILE" - the
whole point of this file is to prove the framework has no attachment-
specific (or any other domain-specific) knowledge baked in. Attachment's
own integration with the framework is covered separately in
tests/test_batch_workflow.py.

Runs against a real, throwaway PostgreSQL database (mirroring every other
real-Postgres test file in this directory) - skips entirely if no local
PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import MagicMock

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl.batch import framework
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")

DOMAIN = "TEST_DOMAIN"
ENTITY_TYPE = "TEST_ENTITY"
OTHER_DOMAIN = "OTHER_TEST_DOMAIN"
OTHER_ENTITY_TYPE = "OTHER_TEST_ENTITY"


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


class SelectDistinctAscendingFromOracleBatchesTest(unittest.TestCase):
    def test_selects_exactly_n_distinct_ascending_values(self) -> None:
        batches = _oracle_batches_stub(
            [pd.DataFrame({"id": [5, 3, 3, 1, 4, 2, 6, 7]})]
        )

        selected = framework.select_distinct_ascending_from_oracle_batches(
            batches.read_batches(), id_column="id", requested_size=5
        )

        self.assertEqual(selected, [1, 2, 3, 4, 5])

    def test_stops_early_once_enough_are_found(self) -> None:
        consumed_batches = {"count": 0}

        def _tracking_generator():
            for i in range(1000):
                consumed_batches["count"] += 1
                yield pd.DataFrame({"id": [i]})

        stub = MagicMock()
        stub.read_batches.side_effect = _tracking_generator

        framework.select_distinct_ascending_from_oracle_batches(
            stub.read_batches(), id_column="id", requested_size=3
        )

        self.assertEqual(consumed_batches["count"], 3)

    def test_returns_a_smaller_list_when_source_is_exhausted_first(self) -> None:
        batches = _oracle_batches_stub([pd.DataFrame({"id": [1, 2]})])

        selected = framework.select_distinct_ascending_from_oracle_batches(
            batches.read_batches(), id_column="id", requested_size=10
        )

        self.assertEqual(selected, [1, 2])

    def test_excludes_given_keys(self) -> None:
        batches = _oracle_batches_stub([pd.DataFrame({"id": [1, 2, 3, 4]})])

        selected = framework.select_distinct_ascending_from_oracle_batches(
            batches.read_batches(),
            id_column="id",
            requested_size=2,
            excluded={1, 2},
        )

        self.assertEqual(selected, [3, 4])

    def test_skips_nan_values(self) -> None:
        batches = _oracle_batches_stub(
            [pd.DataFrame({"id": [1, None, 2, "not-a-number"]})]
        )

        selected = framework.select_distinct_ascending_from_oracle_batches(
            batches.read_batches(), id_column="id", requested_size=10
        )

        self.assertEqual(selected, [1, 2])

    def test_closes_the_batches_generator_even_on_early_stop(self) -> None:
        stub = MagicMock()
        real_generator_holder: dict = {}

        def _generator():
            for i in range(1000):
                yield pd.DataFrame({"id": [i]})

        gen = _generator()
        real_generator_holder["gen"] = gen
        stub.read_batches.return_value = gen

        framework.select_distinct_ascending_from_oracle_batches(
            stub.read_batches(), id_column="id", requested_size=1
        )

        with self.assertRaises(StopIteration):
            next(real_generator_holder["gen"])


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class BatchFrameworkPostgresTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db_name = f"pytest_batch_framework_{uuid.uuid4().hex[:12]}"

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

    # --- create_batch -------------------------------------------------

    def test_create_batch_raises_for_non_positive_size(self) -> None:
        with self.assertRaises(ValueError):
            framework.create_batch(
                self.engine,
                domain=DOMAIN,
                entity_type=ENTITY_TYPE,
                requested_size=0,
                selection_strategy="TEST_STRATEGY",
                selected_keys=[],
            )

    def test_create_batch_persists_domain_and_entity_type(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=2,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[10, 20],
        )

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(batch_row["domain"], DOMAIN)
        self.assertEqual(batch_row["entity_type"], ENTITY_TYPE)
        self.assertEqual(batch_row["status"], "CREATED")
        self.assertEqual(batch_row["selection_strategy"], "TEST_STRATEGY")

    def test_create_batch_persists_membership_in_ordinal_order(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=3,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[7, 8, 9],
        )

        with self.engine.connect() as connection:
            rows = connection.execute(
                text(
                    "SELECT entity_key, ordinal FROM archive.etl_batch_item "
                    "WHERE batch_id = :batch_id ORDER BY ordinal"
                ),
                {"batch_id": result["batch_id"]},
            ).mappings().all()

        self.assertEqual(
            [(row["entity_key"], row["ordinal"]) for row in rows],
            [(7, 1), (8, 2), (9, 3)],
        )

    def test_create_batch_stores_selection_parameters_as_jsonb(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
            selection_parameters={"exclude_already_uploaded": True},
        )

        stored = self._scalar(
            "SELECT selection_parameters FROM archive.etl_batch "
            "WHERE batch_id = :batch_id",
            batch_id=result["batch_id"],
        )
        self.assertEqual(stored, {"exclude_already_uploaded": True})

    def test_two_domains_produce_independent_batches(self) -> None:
        # The same framework, called for two different domain/entity_type
        # pairs, must never mix membership or identity between them -
        # this is the central genericness guarantee.
        first = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=2,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1, 2],
        )
        second = framework.create_batch(
            self.engine,
            domain=OTHER_DOMAIN,
            entity_type=OTHER_ENTITY_TYPE,
            requested_size=2,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1, 2],
        )

        self.assertNotEqual(first["batch_id"], second["batch_id"])

        first_row = self._row("etl_batch", batch_id=first["batch_id"])
        second_row = self._row("etl_batch", batch_id=second["batch_id"])
        self.assertEqual(first_row["domain"], DOMAIN)
        self.assertEqual(second_row["domain"], OTHER_DOMAIN)

    # --- load_batch_membership / assert_batch_matches ------------------

    def test_load_batch_membership_raises_for_a_nonexistent_batch(self) -> None:
        with self.engine.connect() as connection:
            with self.assertRaises(RuntimeError):
                framework.load_batch_membership(
                    connection, 999999, domain=DOMAIN, entity_type=ENTITY_TYPE
                )

    def test_load_batch_membership_returns_ordinal_order(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=3,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[5, 6, 7],
        )

        with self.engine.connect() as connection:
            membership = framework.load_batch_membership(
                connection,
                result["batch_id"],
                domain=DOMAIN,
                entity_type=ENTITY_TYPE,
            )

        self.assertEqual(membership, [5, 6, 7])

    def test_domain_mismatch_is_rejected(self) -> None:
        # A batch created for one domain must never be silently usable by
        # a different domain's loader, even if the batch_id happens to be
        # valid - the whole point of domain/entity_type tagging.
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        with self.engine.connect() as connection:
            with self.assertRaises(RuntimeError):
                framework.load_batch_membership(
                    connection,
                    result["batch_id"],
                    domain=OTHER_DOMAIN,
                    entity_type=OTHER_ENTITY_TYPE,
                )

    def test_entity_type_mismatch_is_rejected(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        with self.engine.connect() as connection:
            with self.assertRaises(RuntimeError):
                framework.load_batch_membership(
                    connection,
                    result["batch_id"],
                    domain=DOMAIN,
                    entity_type=OTHER_ENTITY_TYPE,
                )

    # --- set_item_status / set_batch_status ----------------------------

    def test_set_item_status_updates_only_the_target_item(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=2,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1, 2],
        )

        with self.engine.begin() as connection:
            framework.set_item_status(
                connection,
                result["batch_id"],
                1,
                status=framework.ITEM_STATUS_COMPLETED,
            )

        item_1 = self._row(
            "etl_batch_item", batch_id=result["batch_id"], entity_key=1
        )
        item_2 = self._row(
            "etl_batch_item", batch_id=result["batch_id"], entity_key=2
        )
        self.assertEqual(item_1["status"], "COMPLETED")
        self.assertEqual(item_2["status"], "PENDING")

    def test_set_batch_status_updates_status_only(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        with self.engine.begin() as connection:
            framework.set_batch_status(
                connection, result["batch_id"], status=framework.BATCH_STATUS_READY
            )

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(batch_row["status"], "READY")
        self.assertIsNone(batch_row["started_at"])
        self.assertIsNone(batch_row["completed_at"])

    # --- begin_batch_processing / finish_batch_processing --------------

    def test_begin_batch_processing_sets_started_at_once(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        framework.begin_batch_processing(
            self.engine,
            result["batch_id"],
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            status=framework.BATCH_STATUS_PROCESSING,
        )
        first_started_at = self._row("etl_batch", batch_id=result["batch_id"])[
            "started_at"
        ]

        framework.begin_batch_processing(
            self.engine,
            result["batch_id"],
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            status=framework.BATCH_STATUS_PROCESSING,
        )
        second_started_at = self._row("etl_batch", batch_id=result["batch_id"])[
            "started_at"
        ]

        self.assertIsNotNone(first_started_at)
        self.assertEqual(first_started_at, second_started_at)

    def test_begin_batch_processing_rejects_domain_mismatch(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        with self.assertRaises(RuntimeError):
            framework.begin_batch_processing(
                self.engine,
                result["batch_id"],
                domain=OTHER_DOMAIN,
                entity_type=OTHER_ENTITY_TYPE,
                status=framework.BATCH_STATUS_PROCESSING,
            )

    def test_finish_batch_processing_sets_completed_at_and_status(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        framework.finish_batch_processing(
            self.engine, result["batch_id"], status=framework.BATCH_STATUS_COMPLETED
        )

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(batch_row["status"], "COMPLETED")
        self.assertIsNotNone(batch_row["completed_at"])

    # --- show_batch -----------------------------------------------------

    def test_show_batch_reports_found_false_for_a_nonexistent_batch(self) -> None:
        report = framework.show_batch(
            self.engine, 999999, domain=DOMAIN, entity_type=ENTITY_TYPE
        )

        self.assertEqual(report, {"batch_id": 999999, "found": False})

    def test_show_batch_rejects_domain_mismatch(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=1,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1],
        )

        with self.assertRaises(RuntimeError):
            framework.show_batch(
                self.engine,
                result["batch_id"],
                domain=OTHER_DOMAIN,
                entity_type=OTHER_ENTITY_TYPE,
            )

    def test_show_batch_reports_generic_item_status_breakdown(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=3,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1, 2, 3],
        )
        with self.engine.begin() as connection:
            framework.set_item_status(
                connection, result["batch_id"], 1, status=framework.ITEM_STATUS_COMPLETED
            )
            framework.set_item_status(
                connection, result["batch_id"], 2, status=framework.ITEM_STATUS_FAILED
            )

        report = framework.show_batch(
            self.engine, result["batch_id"], domain=DOMAIN, entity_type=ENTITY_TYPE
        )

        self.assertTrue(report["found"])
        self.assertEqual(report["total_items"], 3)
        self.assertEqual(report["completed"], 1)
        self.assertEqual(report["failed"], 1)
        self.assertEqual(report["pending"], 1)

    def test_show_batch_is_read_only(self) -> None:
        result = framework.create_batch(
            self.engine,
            domain=DOMAIN,
            entity_type=ENTITY_TYPE,
            requested_size=2,
            selection_strategy="TEST_STRATEGY",
            selected_keys=[1, 2],
        )

        before = self._row("etl_batch", batch_id=result["batch_id"])
        framework.show_batch(
            self.engine, result["batch_id"], domain=DOMAIN, entity_type=ENTITY_TYPE
        )
        after = self._row("etl_batch", batch_id=result["batch_id"])

        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
