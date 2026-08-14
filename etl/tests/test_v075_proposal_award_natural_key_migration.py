"""Regression test proving V075 (drop archive.proposal_award's leftover
uq_proposal_award natural-key uniqueness) applies cleanly and actually
fixes the real failure it was written for.

Real failure this corrects: Oracle staging AWARD_FUNDING_PROPOSALS has
two genuinely distinct rows (different award_funding_proposal_id, the
real Oracle PK) sharing the same (proposal_id, award_id, award_number)
tuple for Proposal families 2975 and 4120 - a real --load-batch run hit
psycopg.errors.UniqueViolation on uq_proposal_award for both. Nothing in
the source data (ACTIVE, timestamps, OBJ_ID) identifies either row as
authoritative, so this archive's exact-source-preservation principle
requires preserving both, not guessing which one to drop - see
etl/tests/test_proposal_load.py::
test_deduplicates_by_the_real_award_funding_proposal_id_not_by_the_tuple,
which already asserts this at the pandas layer; this file proves it also
holds through the real Postgres schema and the real upsert_proposal_awards
UPSERT.

Skips entirely if no local PostgreSQL is reachable - mirrors
test_v036_upload_status_migration.py's pattern exactly (throwaway,
uniquely-named database per test, dropped afterward).
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from datetime import datetime
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from archive_etl.upload.migrations import apply_migrations
from load_proposals_from_csv import AWARD_COLUMNS, upsert_proposal_awards

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


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class V075ProposalAwardNaturalKeyMigrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db_name = f"pytest_v075_{uuid.uuid4().hex[:12]}"

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

        # A minimal archive.award_version fixture row - upsert_proposal_awards
        # only inserts a proposal_award row for an award_id it can resolve
        # via this table (real behavior: unresolved award_ids are skipped,
        # not fabricated).
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.award_version "
                    "(award_id, award_number, sequence_number) "
                    "VALUES (462515, '201498-00001', 1)"
                )
            )

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def test_full_migration_chain_applies_cleanly(self) -> None:
        # apply_migrations already ran once in setUp - re-running here
        # (idempotent, tracked via schema_migration) is the exact code
        # path --migrate-only runs in production and must not raise.
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def test_uq_proposal_award_no_longer_exists(self) -> None:
        with self.engine.connect() as connection:
            exists = connection.execute(
                text(
                    "SELECT 1 FROM pg_constraint WHERE conname = 'uq_proposal_award'"
                )
            ).first()
        self.assertIsNone(exists)

    def test_award_funding_proposal_id_uniqueness_still_enforced(self) -> None:
        # The real Oracle PK is the only uniqueness this table should
        # have after V075 - proven by exercise, not just by reading the
        # index definition: a second row reusing the same
        # award_funding_proposal_id must still fail.
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.proposal_award "
                    "(award_funding_proposal_id, proposal_id, award_id, "
                    "award_number) VALUES (999, 1, 462515, '201498-00001')"
                )
            )

        with self.assertRaises(IntegrityError):
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.proposal_award "
                        "(award_funding_proposal_id, proposal_id, award_id, "
                        "award_number) VALUES (999, 1, 462515, '201498-00001')"
                    )
                )

    def test_two_distinct_oracle_rows_sharing_the_natural_key_both_survive(
        self,
    ) -> None:
        # Real fixture: Proposal family 2975, proposal_id 7125,
        # award_id 462515 - two genuinely distinct
        # AWARD_FUNDING_PROPOSAL_ID rows (501508, 511830), 13 days apart,
        # both ACTIVE='Y', distinct OBJ_ID - live-verified in Oracle
        # staging. Neither is more authoritative than the other, so both
        # must persist.
        awards = pd.DataFrame(
            [
                {
                    "award_funding_proposal_id": 501508,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 2, 22, 9, 49, 45),
                    "source_update_user": "mkousheh",
                },
                {
                    "award_funding_proposal_id": 511830,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 3, 7, 13, 55, 50),
                    "source_update_user": "mkousheh",
                },
            ]
        )[AWARD_COLUMNS]

        with self.engine.begin() as connection:
            report = upsert_proposal_awards(connection, awards)

        self.assertEqual(report["inserted"], 2)
        self.assertEqual(report["skipped"], 0)

        with self.engine.connect() as connection:
            rows = connection.execute(
                text(
                    "SELECT award_funding_proposal_id, proposal_id, award_id, "
                    "award_number FROM archive.proposal_award "
                    "WHERE proposal_id = 7125 ORDER BY award_funding_proposal_id"
                )
            ).all()

        self.assertEqual(len(rows), 2)
        self.assertEqual(
            [row.award_funding_proposal_id for row in rows], [501508, 511830]
        )
        # award_number is resolved server-side from archive.award_version,
        # not carried in the input frame - both rows resolve identically,
        # which is exactly the condition that used to violate the old
        # natural-key constraint.
        self.assertTrue(all(row.award_number == "201498-00001" for row in rows))

    def test_upsert_remains_idempotent_by_award_funding_proposal_id(self) -> None:
        awards = pd.DataFrame(
            [
                {
                    "award_funding_proposal_id": 501508,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 2, 22, 9, 49, 45),
                    "source_update_user": "mkousheh",
                }
            ]
        )[AWARD_COLUMNS]

        with self.engine.begin() as connection:
            first_report = upsert_proposal_awards(connection, awards)
        with self.engine.begin() as connection:
            second_report = upsert_proposal_awards(connection, awards)

        self.assertEqual(first_report["inserted"], 1)
        self.assertEqual(second_report["inserted"], 0)
        self.assertEqual(second_report["unchanged"], 1)

        with self.engine.connect() as connection:
            count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.proposal_award "
                    "WHERE award_funding_proposal_id = 501508"
                )
            ).scalar_one()
        self.assertEqual(count, 1)

    def test_rerunning_one_source_row_updates_only_that_row(self) -> None:
        # Load both siblings, then re-run with only one of them changed
        # (active flips false->true would be the real-world case; here
        # source_update_user changes) - the untouched sibling's own row
        # must not be touched at all.
        original = pd.DataFrame(
            [
                {
                    "award_funding_proposal_id": 501508,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 2, 22, 9, 49, 45),
                    "source_update_user": "mkousheh",
                },
                {
                    "award_funding_proposal_id": 511830,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 3, 7, 13, 55, 50),
                    "source_update_user": "mkousheh",
                },
            ]
        )[AWARD_COLUMNS]

        with self.engine.begin() as connection:
            upsert_proposal_awards(connection, original)

        updated = pd.DataFrame(
            [
                {
                    "award_funding_proposal_id": 501508,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": False,
                    "source_update_timestamp": datetime(2013, 4, 1, 0, 0, 0),
                    "source_update_user": "someone_else",
                },
                {
                    "award_funding_proposal_id": 511830,
                    "proposal_id": 7125,
                    "award_id": 462515,
                    "active": True,
                    "source_update_timestamp": datetime(2013, 3, 7, 13, 55, 50),
                    "source_update_user": "mkousheh",
                },
            ]
        )[AWARD_COLUMNS]

        with self.engine.begin() as connection:
            report = upsert_proposal_awards(connection, updated)

        self.assertEqual(report["updated"], 1)
        self.assertEqual(report["unchanged"], 1)

        with self.engine.connect() as connection:
            row_501508 = connection.execute(
                text(
                    "SELECT active, source_update_user FROM archive.proposal_award "
                    "WHERE award_funding_proposal_id = 501508"
                )
            ).one()
            row_511830 = connection.execute(
                text(
                    "SELECT active, source_update_user FROM archive.proposal_award "
                    "WHERE award_funding_proposal_id = 511830"
                )
            ).one()

        self.assertFalse(row_501508.active)
        self.assertEqual(row_501508.source_update_user, "someone_else")
        self.assertTrue(row_511830.active)
        self.assertEqual(row_511830.source_update_user, "mkousheh")


if __name__ == "__main__":
    unittest.main()
