"""Regression tests for the archive.sponsor reference-data load (V079).

Runs the real UPSERT against a real, throwaway PostgreSQL database, for
the same reason as test_reference_data.py: the insert/update/unchanged
distinction depends on genuine Postgres semantics.

Fixture values are live-verified KCOEUS rows (2026-09-22): sponsor
303630 is Addgene - the sponsor carried by the Negotiation 120
reconciliation fixture - and 301045 is NIH/National Institute on Aging.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl import reference_data
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


def _sponsor_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "SPONSOR_CODE": "303630",
                "SPONSOR_NAME": "Addgene",
                "ACRONYM": None,
                "SPONSOR_TYPE_CODE": "O",
                # Real BU values are 'Y'/'N'; the padded and lower-case
                # variants guard the mapping, not a hypothetical.
                "ACTV_IND": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "SPONSOR_CODE": "301045",
                "SPONSOR_NAME": "NIH/National Institute on Aging",
                "ACRONYM": "NIA",
                "SPONSOR_TYPE_CODE": "F",
                "ACTV_IND": "y ",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "SPONSOR_CODE": "999999",
                "SPONSOR_NAME": "Retired Sponsor",
                "ACRONYM": None,
                "SPONSOR_TYPE_CODE": "O",
                "ACTV_IND": "N",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SponsorReferenceDataLoadTest(unittest.TestCase):
    db_prefix = "pytest_sponsor_reference_data"

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

    def _patched_oracle(self, frame: pd.DataFrame | None = None):
        payload = _sponsor_frame() if frame is None else frame
        return patch.multiple(
            reference_data.OracleDataSource,
            __init__=lambda self, sql_path, **kwargs: setattr(
                self, "sql_path", sql_path
            ),
            read=lambda self: payload.copy(),
        )

    def _row(self, sponsor_code: str) -> dict:
        with self.engine.connect() as connection:
            return dict(
                connection.execute(
                    text(
                        "SELECT * FROM archive.sponsor "
                        "WHERE sponsor_code = :code"
                    ),
                    {"code": sponsor_code},
                )
                .mappings()
                .one()
            )

    def test_loads_real_fixture_sponsors(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_sponsor_reference_data(self.engine)

        addgene = self._row("303630")
        self.assertEqual(addgene["sponsor_name"], "Addgene")
        self.assertEqual(addgene["sponsor_type_code"], "O")
        self.assertTrue(addgene["active"])

        nia = self._row("301045")
        self.assertEqual(nia["sponsor_name"], "NIH/National Institute on Aging")
        self.assertEqual(nia["acronym"], "NIA")

    def test_active_flag_mapping_tolerates_padding_and_case(self) -> None:
        """'y ' is still active; only a real 'N' is inactive."""
        with self._patched_oracle():
            reference_data.run_load_sponsor_reference_data(self.engine)

        self.assertTrue(self._row("301045")["active"])
        self.assertFalse(self._row("999999")["active"])

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with self._patched_oracle():
            first = reference_data.run_load_sponsor_reference_data(self.engine)
            second = reference_data.run_load_sponsor_reference_data(self.engine)

        self.assertEqual(first["sponsor"]["inserted"], 3)
        self.assertEqual(second["sponsor"]["inserted"], 0)
        self.assertEqual(second["sponsor"]["updated"], 0)

    def test_renamed_sponsor_updates_in_place(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_sponsor_reference_data(self.engine)

        renamed = _sponsor_frame()
        renamed.loc[
            renamed["SPONSOR_CODE"] == "303630", "SPONSOR_NAME"
        ] = "Addgene, Inc."
        with self._patched_oracle(renamed):
            report = reference_data.run_load_sponsor_reference_data(self.engine)

        self.assertEqual(report["sponsor"]["updated"], 1)
        self.assertEqual(self._row("303630")["sponsor_name"], "Addgene, Inc.")

    def test_dry_run_does_not_persist(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_sponsor_reference_data(
                self.engine, dry_run=True
            )

        with self.engine.connect() as connection:
            remaining = connection.execute(
                text("SELECT COUNT(*) FROM archive.sponsor")
            ).scalar_one()
        self.assertEqual(remaining, 0)
