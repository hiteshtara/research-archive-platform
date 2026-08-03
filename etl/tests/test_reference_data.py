"""Regression tests for archive_etl.reference_data - the shared Unit /
UnitAdministrator / UnitAdministratorType / Rolodex / Person loader
backing the Award Contacts feature. Runs the real UPSERT SQL against a
real, throwaway PostgreSQL database - the insert/update/unchanged
distinction depends on genuine Postgres semantics a mock cannot exercise
correctly. Uses the real, live-Oracle-confirmed fixture values for Unit
1203250000 (CAS SPACE PHYSICS) and its two Central Administration
Contacts administrators (Nancy Schindele/PAFO, Anthony J Moy/OSP) - see
docs/architecture/AWARD_CONTACTS_DESIGN.md.
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


def _unit_administrator_type_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "UNIT_ADMINISTRATOR_TYPE_CODE": "3",
                "DESCRIPTION": "OSP Administrator",
                "DEFAULT_GROUP_FLAG": "C",
                "MULTIPLES_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "UNIT_ADMINISTRATOR_TYPE_CODE": "4",
                "DESCRIPTION": "PAFO Administrator",
                "DEFAULT_GROUP_FLAG": "C",
                "MULTIPLES_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
        ]
    )


def _unit_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "UNIT_NUMBER": "1203250000",
                "UNIT_NAME": "CAS SPACE PHYSICS",
                "PARENT_UNIT_NUMBER": "1200000000",
                "ORGANIZATION_ID": "1",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            }
        ]
    )


def _unit_administrator_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "UNIT_NUMBER": "1203250000",
                "PERSON_ID": "U44984650",
                "UNIT_ADMINISTRATOR_TYPE_CODE": "4",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "UNIT_NUMBER": "1203250000",
                "PERSON_ID": "U98756203",
                "UNIT_ADMINISTRATOR_TYPE_CODE": "3",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
        ]
    )


def _rolodex_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "ROLODEX_ID": 501,
                "LAST_NAME": "Smith",
                "FIRST_NAME": "Jane",
                "MIDDLE_NAME": None,
                "SUFFIX": None,
                "PREFIX": None,
                "TITLE": None,
                "ORGANIZATION": "NIH",
                "PHONE_NUMBER": "301-555-0100",
                "EMAIL_ADDRESS": "jane.smith@nih.gov",
                "ADDRESS_LINE_1": None,
                "ADDRESS_LINE_2": None,
                "ADDRESS_LINE_3": None,
                "CITY": "Bethesda",
                "COUNTY": None,
                "STATE": "MD",
                "POSTAL_CODE": "20892",
                "COUNTRY_CODE": "USA",
                "OWNED_BY_UNIT": "1203250000",
                "ACTV_IND": "Y",
                "DELETE_FLAG": "N",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            }
        ]
    )


def _person_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "PERSON_ID": "U44984650",
                "FIRST_NM": "NANCY",
                "MIDDLE_NM": None,
                "LAST_NM": "SCHINDELE",
                "EMAIL_ADDR": "NANCYSCH@BU.EDU",
                "PHONE_NBR": "617-358-5117",
            },
            {
                "PERSON_ID": "U98756203",
                "FIRST_NM": "ANTHONY",
                "MIDDLE_NM": "J",
                "LAST_NM": "MOY",
                "EMAIL_ADDR": "TMOY@BU.EDU",
                "PHONE_NBR": "617-353-4365",
            },
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class UnitReferenceDataLoadTest(unittest.TestCase):
    db_prefix = "pytest_unit_reference_data"

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

    def _patched_oracle(self):
        return patch.multiple(
            reference_data.OracleDataSource,
            __init__=lambda self, sql_path, **kwargs: setattr(
                self, "sql_path", sql_path
            ),
            read=lambda self: {
                reference_data.UNIT_ADMINISTRATOR_TYPE_SQL: _unit_administrator_type_frame(),
                reference_data.UNIT_SQL: _unit_frame(),
                reference_data.UNIT_ADMINISTRATOR_SQL: _unit_administrator_frame(),
                reference_data.ROLODEX_SQL: _rolodex_frame(),
            }[self.sql_path].copy(),
            read_filtered=lambda self, *, column, values: (
                _person_frame()[_person_frame()["PERSON_ID"].isin(values)].copy()
            ),
        )

    def test_loads_real_fixture_unit_administrators_and_resolves_person_names(
        self,
    ) -> None:
        with self._patched_oracle():
            report = reference_data.run_load_unit_reference_data(self.engine)

        self.assertEqual(
            report["unit_administrator_type"],
            {"inserted": 2, "updated": 0, "unchanged": 0},
        )
        self.assertEqual(
            report["unit"], {"inserted": 1, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["unit_administrator"],
            {"inserted": 2, "updated": 0, "unchanged": 0},
        )
        self.assertEqual(
            report["rolodex"], {"inserted": 1, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["person"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )

        unit = self._row("unit", unit_number="1203250000")
        self.assertEqual(unit["unit_name"], "CAS SPACE PHYSICS")
        self.assertEqual(unit["parent_unit_number"], "1200000000")
        self.assertTrue(unit["active"])

        nancy = self._row("person", person_id="U44984650")
        self.assertEqual(nancy["full_name"], "NANCY SCHINDELE")
        self.assertEqual(nancy["email_address"], "NANCYSCH@BU.EDU")
        self.assertEqual(nancy["phone_number"], "617-358-5117")

        anthony = self._row("person", person_id="U98756203")
        self.assertEqual(anthony["full_name"], "ANTHONY J MOY")

        pafo = self._row(
            "unit_administrator",
            unit_number="1203250000",
            person_id="U44984650",
            unit_administrator_type_code="4",
        )
        self.assertEqual(pafo["unit_administrator_type_code"], "4")

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_unit_reference_data(self.engine)
            report = reference_data.run_load_unit_reference_data(self.engine)

        self.assertEqual(
            report["unit"], {"inserted": 0, "updated": 0, "unchanged": 1}
        )
        self.assertEqual(
            report["unit_administrator"],
            {"inserted": 0, "updated": 0, "unchanged": 2},
        )
        self.assertEqual(
            report["person"], {"inserted": 0, "updated": 0, "unchanged": 2}
        )

    def test_dry_run_does_not_persist(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_unit_reference_data(self.engine, dry_run=True)

        with self.engine.connect() as connection:
            count = connection.execute(
                text("SELECT COUNT(*) FROM archive.unit")
            ).scalar_one()
        self.assertEqual(count, 0)

    def test_person_extraction_is_scoped_to_referenced_person_ids_only(self) -> None:
        # Person_ids that appear ONLY in the Oracle person source, never
        # referenced by unit_administrator/award_unit_contact, must never
        # be archived - person is a targeted read, never a full KRIM scan.
        extra_person_frame = pd.concat(
            [
                _person_frame(),
                pd.DataFrame(
                    [
                        {
                            "PERSON_ID": "U00000000",
                            "FIRST_NM": "UNRELATED",
                            "MIDDLE_NM": None,
                            "LAST_NM": "PERSON",
                            "EMAIL_ADDR": "nobody@bu.edu",
                            "PHONE_NBR": None,
                        }
                    ]
                ),
            ],
            ignore_index=True,
        )
        with patch.multiple(
            reference_data.OracleDataSource,
            __init__=lambda self, sql_path, **kwargs: setattr(
                self, "sql_path", sql_path
            ),
            read=lambda self: {
                reference_data.UNIT_ADMINISTRATOR_TYPE_SQL: _unit_administrator_type_frame(),
                reference_data.UNIT_SQL: _unit_frame(),
                reference_data.UNIT_ADMINISTRATOR_SQL: _unit_administrator_frame(),
                reference_data.ROLODEX_SQL: _rolodex_frame(),
            }[self.sql_path].copy(),
            read_filtered=lambda self, *, column, values: (
                extra_person_frame[
                    extra_person_frame["PERSON_ID"].isin(values)
                ].copy()
            ),
        ):
            reference_data.run_load_unit_reference_data(self.engine)

        with self.engine.connect() as connection:
            count = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.person "
                    "WHERE person_id = 'U00000000'"
                )
            ).scalar_one()
        self.assertEqual(count, 0)
