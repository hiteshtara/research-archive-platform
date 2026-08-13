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


def _comment_type_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "COMMENT_TYPE_CODE": "2",
                "DESCRIPTION": "General Comments",
                "TEMPLATE_FLAG": "Y",
                "CHECKLIST_FLAG": "N",
                "AWARD_COMMENT_SCREEN_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "COMMENT_TYPE_CODE": "3",
                "DESCRIPTION": "Fiscal Report Comments",
                "TEMPLATE_FLAG": "Y",
                "CHECKLIST_FLAG": "N",
                "AWARD_COMMENT_SCREEN_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
            {
                "COMMENT_TYPE_CODE": "21",
                "DESCRIPTION": "Current Action Comments",
                "TEMPLATE_FLAG": "N",
                "CHECKLIST_FLAG": "N",
                "AWARD_COMMENT_SCREEN_FLAG": "N",
                "UPDATE_TIMESTAMP": pd.Timestamp("2020-01-01"),
                "UPDATE_USER": "kuali",
                "VER_NBR": 1,
            },
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class CommentTypeReferenceDataLoadTest(unittest.TestCase):
    db_prefix = "pytest_comment_type_reference_data"

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
            read=lambda self: _comment_type_frame().copy(),
        )

    def test_loads_real_fixture_comment_types(self) -> None:
        with self._patched_oracle():
            report = reference_data.run_load_comment_type_reference_data(
                self.engine
            )

        self.assertEqual(
            report["comment_type"],
            {"inserted": 3, "updated": 0, "unchanged": 0},
        )

        general = self._row("comment_type", comment_type_code="2")
        self.assertEqual(general["description"], "General Comments")
        self.assertEqual(general["award_comment_screen_flag"], "Y")

        current_action = self._row("comment_type", comment_type_code="21")
        self.assertEqual(current_action["description"], "Current Action Comments")
        self.assertEqual(current_action["award_comment_screen_flag"], "N")

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_comment_type_reference_data(self.engine)
            report = reference_data.run_load_comment_type_reference_data(
                self.engine
            )

        self.assertEqual(
            report["comment_type"],
            {"inserted": 0, "updated": 0, "unchanged": 3},
        )

    def test_dry_run_does_not_persist(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_comment_type_reference_data(
                self.engine, dry_run=True
            )

        with self.engine.connect() as connection:
            count = connection.execute(
                text("SELECT COUNT(*) FROM archive.comment_type")
            ).scalar_one()
        self.assertEqual(count, 0)


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


def _sponsor_term_type_frame() -> pd.DataFrame:
    # Real fixture, live-verified against BU Oracle staging 2026-08-14.
    return pd.DataFrame(
        [
            {
                "SPONSOR_TERM_TYPE_CODE": "6",
                "DESCRIPTION": "Equipment Approval Terms",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
            {
                "SPONSOR_TERM_TYPE_CODE": "2",
                "DESCRIPTION": "Invention Terms",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _sponsor_term_frame() -> pd.DataFrame:
    # Real fixture: Award 204713-00088 (award_id 2727052)'s own Sponsor
    # Terms - live-verified against BU Oracle staging 2026-08-14.
    # SPONSOR_TERM_ID (this table's own surrogate PK, what
    # archive.award_sponsor_term.sponsor_term_id actually points at) is
    # deliberately different from SPONSOR_TERM_CODE (the human-readable
    # value Kuali's UI displays) - 370 -> 64, not 370 -> 370.
    return pd.DataFrame(
        [
            {
                "SPONSOR_TERM_ID": 370,
                "SPONSOR_TERM_CODE": "64",
                "SPONSOR_TERM_TYPE_CODE": "6",
                "DESCRIPTION": (
                    "Converted Record.  Please refer to sponsor award "
                    "documentation for any Equipment Approval terms."
                ),
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
            {
                "SPONSOR_TERM_ID": 371,
                "SPONSOR_TERM_CODE": "65",
                "SPONSOR_TERM_TYPE_CODE": "2",
                "DESCRIPTION": (
                    "Converted Record.  Please refer to sponsor award "
                    "documentation for any Invention terms."
                ),
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _report_frame() -> pd.DataFrame:
    # Real fixture: Award 204713-00088's own Report Terms codes -
    # live-verified against BU Oracle staging 2026-08-14.
    return pd.DataFrame(
        [
            {
                "REPORT_CODE": "43",
                "DESCRIPTION": "Converted Record  - See Sponsor Documentation",
                "FINAL_REPORT_FLAG": "N",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
            {
                "REPORT_CODE": "26",
                "DESCRIPTION": "Standard BU Invoice",
                "FINAL_REPORT_FLAG": "N",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _report_class_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "REPORT_CLASS_CODE": "1",
                "DESCRIPTION": "Financial",
                "GENERATE_REPORT_REQUIREMENTS": "Y",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
            {
                "REPORT_CLASS_CODE": "6",
                "DESCRIPTION": "Payment/Invoice",
                "GENERATE_REPORT_REQUIREMENTS": "Y",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _frequency_frame() -> pd.DataFrame:
    # FREQUENCY_CODE 5 ("As required") genuinely has null advance-notice
    # columns on real Oracle - not a load gap, see 13_frequency.sql.
    return pd.DataFrame(
        [
            {
                "FREQUENCY_CODE": "5",
                "DESCRIPTION": "As required",
                "NUMBER_OF_DAYS": None,
                "NUMBER_OF_MONTHS": None,
                "REPEAT_FLAG": "N",
                "ADVANCE_NUMBER_OF_DAYS": None,
                "ADVANCE_NUMBER_OF_MONTHS": None,
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _frequency_base_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "FREQUENCY_BASE_CODE": "6",
                "DESCRIPTION": "As Required",
                "REGENERATION_TYPE_NAME": None,
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _distribution_frame() -> pd.DataFrame:
    # Genuinely binary Yes/No lookup - live-verified only 2 rows exist.
    return pd.DataFrame(
        [
            {
                "OSP_DISTRIBUTION_CODE": "1",
                "DESCRIPTION": "Yes",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
            {
                "OSP_DISTRIBUTION_CODE": "2",
                "DESCRIPTION": "No",
                "ACTIVE_FLAG": "Y",
                "UPDATE_TIMESTAMP": pd.Timestamp("2011-06-23"),
                "UPDATE_USER": "KCRM",
                "VER_NBR": 1,
            },
        ]
    )


def _contact_type_frame() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "CONTACT_TYPE_CODE": "34",
                "DESCRIPTION": "Administrative Contact",
                "UPDATE_TIMESTAMP": pd.Timestamp("2014-11-01"),
                "UPDATE_USER": "admin",
                "VER_NBR": 1,
            },
            {
                "CONTACT_TYPE_CODE": "35",
                "DESCRIPTION": "Financial Contact",
                "UPDATE_TIMESTAMP": pd.Timestamp("2014-11-01"),
                "UPDATE_USER": "admin",
                "VER_NBR": 1,
            },
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class TermsReferenceDataLoadTest(unittest.TestCase):
    """Regression tests for the eight lookups resolving Award Sponsor
    Terms/Report Terms/Report Term Recipients raw codes into readable
    labels - see docs/architecture/AWARD_TERMS_DESIGN.md and the
    Award 204713-00088 (award_id 2727052) verification fixture used
    throughout."""

    db_prefix = "pytest_terms_reference_data"

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
                reference_data.SPONSOR_TERM_TYPE_SQL: _sponsor_term_type_frame(),
                reference_data.SPONSOR_TERM_SQL: _sponsor_term_frame(),
                reference_data.REPORT_SQL: _report_frame(),
                reference_data.REPORT_CLASS_SQL: _report_class_frame(),
                reference_data.FREQUENCY_SQL: _frequency_frame(),
                reference_data.FREQUENCY_BASE_SQL: _frequency_base_frame(),
                reference_data.DISTRIBUTION_SQL: _distribution_frame(),
                reference_data.CONTACT_TYPE_SQL: _contact_type_frame(),
            }[self.sql_path].copy(),
        )

    def test_loads_real_fixture_terms_reference_data(self) -> None:
        with self._patched_oracle():
            report = reference_data.run_load_terms_reference_data(self.engine)

        self.assertEqual(
            report["sponsor_term_type"],
            {"inserted": 2, "updated": 0, "unchanged": 0},
        )
        self.assertEqual(
            report["sponsor_term"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["report"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["report_class"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["frequency"], {"inserted": 1, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["frequency_base"],
            {"inserted": 1, "updated": 0, "unchanged": 0},
        )
        self.assertEqual(
            report["distribution"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )
        self.assertEqual(
            report["contact_type"], {"inserted": 2, "updated": 0, "unchanged": 0}
        )

        # SPONSOR_TERM_ID 370 -> SPONSOR_TERM_CODE 64, never conflated.
        equipment_term = self._row("sponsor_term", sponsor_term_id=370)
        self.assertEqual(equipment_term["sponsor_term_code"], "64")
        self.assertEqual(equipment_term["sponsor_term_type_code"], "6")
        self.assertIn(
            "Equipment Approval terms", equipment_term["description"]
        )

        equipment_category = self._row(
            "sponsor_term_type", sponsor_term_type_code="6"
        )
        self.assertEqual(
            equipment_category["description"], "Equipment Approval Terms"
        )

        report_43 = self._row("report", report_code="43")
        self.assertEqual(
            report_43["description"],
            "Converted Record  - See Sponsor Documentation",
        )

        report_class_1 = self._row("report_class", report_class_code="1")
        self.assertEqual(report_class_1["description"], "Financial")

        frequency_5 = self._row("frequency", frequency_code="5")
        self.assertEqual(frequency_5["description"], "As required")
        self.assertIsNone(frequency_5["advance_number_of_days"])
        self.assertIsNone(frequency_5["advance_number_of_months"])

        distribution_2 = self._row("distribution", osp_distribution_code="2")
        self.assertEqual(distribution_2["description"], "No")

    def test_reload_with_no_oracle_changes_is_unchanged(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_terms_reference_data(self.engine)
            report = reference_data.run_load_terms_reference_data(self.engine)

        for table in (
            "sponsor_term_type",
            "sponsor_term",
            "report",
            "report_class",
            "distribution",
            "contact_type",
        ):
            self.assertEqual(report[table]["inserted"], 0)
            self.assertEqual(report[table]["updated"], 0)
        self.assertEqual(report["frequency"]["unchanged"], 1)
        self.assertEqual(report["frequency_base"]["unchanged"], 1)

    def test_dry_run_does_not_persist(self) -> None:
        with self._patched_oracle():
            reference_data.run_load_terms_reference_data(
                self.engine, dry_run=True
            )

        with self.engine.connect() as connection:
            count = connection.execute(
                text("SELECT COUNT(*) FROM archive.sponsor_term")
            ).scalar_one()
        self.assertEqual(count, 0)

    def test_unresolved_code_never_blocks_or_deletes_anything(self) -> None:
        # A code that doesn't exist in any of the eight lookups (e.g.
        # Oracle later adds one this archive hasn't refreshed yet) must
        # never be treated as an error by the loader - there is no
        # foreign key from award_sponsor_term/award_report_term to
        # these tables (see V074's migration comment) specifically so
        # this can never happen. This test proves the loader itself
        # tolerates being run against a family whose codes are a
        # strict subset of what's already archived, without erroring.
        with self._patched_oracle():
            reference_data.run_load_terms_reference_data(self.engine)
            # Re-running with a narrower Oracle result (as if some
            # codes were retired) must not raise or delete existing rows.
            with patch.multiple(
                reference_data.OracleDataSource,
                __init__=lambda self, sql_path, **kwargs: setattr(
                    self, "sql_path", sql_path
                ),
                read=lambda self: (
                    _sponsor_term_type_frame().iloc[:1].copy()
                    if self.sql_path == reference_data.SPONSOR_TERM_TYPE_SQL
                    else {
                        reference_data.SPONSOR_TERM_SQL: _sponsor_term_frame(),
                        reference_data.REPORT_SQL: _report_frame(),
                        reference_data.REPORT_CLASS_SQL: _report_class_frame(),
                        reference_data.FREQUENCY_SQL: _frequency_frame(),
                        reference_data.FREQUENCY_BASE_SQL: _frequency_base_frame(),
                        reference_data.DISTRIBUTION_SQL: _distribution_frame(),
                        reference_data.CONTACT_TYPE_SQL: _contact_type_frame(),
                    }[self.sql_path]
                ),
            ):
                reference_data.run_load_terms_reference_data(self.engine)

        with self.engine.connect() as connection:
            count = connection.execute(
                text("SELECT COUNT(*) FROM archive.sponsor_term_type")
            ).scalar_one()
        # The previously-loaded row (code 2, "Invention Terms") is
        # still present - a narrower Oracle result never deletes.
        self.assertEqual(count, 2)
