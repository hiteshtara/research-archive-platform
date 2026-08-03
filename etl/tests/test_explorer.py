"""Regression tests for archive_etl.explorer - the read-only Archive
Explorer CLI (`python -m archive_etl explore <resource>`). Every
resource uses fixed, predefined SQL (never arbitrary SQL) - these tests
prove (1) bad identifiers are rejected before ever reaching SQL, and
(2) award-contacts' Central Administration Contacts derivation
reproduces the real Kuali rule exactly (Award.lead_unit_number ->
unit_administrator -> unit_administrator_type WHERE
default_group_flag='C'), using the real, live-Oracle-confirmed fixture
for Unit 1203250000 (CAS SPACE PHYSICS): Nancy Schindele/PAFO
Administrator (group 'C'), Anthony J Moy/OSP Administrator (group 'C'),
and a third, deliberately-'U'-group administrator who must NOT appear
in Central Administration Contacts - see
docs/architecture/AWARD_CONTACTS_DESIGN.md.
"""

from __future__ import annotations

import argparse
import getpass
import os
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl import explorer
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


class ExplorerInputValidationTest(unittest.TestCase):
    def test_rejects_award_number_with_unsafe_characters(self) -> None:
        with self.assertRaises(explorer.ExplorerInputError):
            explorer._validate(
                "100012-00002; DROP TABLE archive.award_version;",
                explorer._AWARD_NUMBER_RE,
                "award-number",
            )

    def test_accepts_a_real_award_number(self) -> None:
        self.assertEqual(
            explorer._validate(
                "100012-00002", explorer._AWARD_NUMBER_RE, "award-number"
            ),
            "100012-00002",
        )

    def test_rejects_non_positive_award_id(self) -> None:
        with self.assertRaises(explorer.ExplorerInputError):
            explorer._validate_int(-1, "award-id")
        with self.assertRaises(explorer.ExplorerInputError):
            explorer._validate_int(0, "award-id")

    def test_rejects_unit_number_with_unsafe_characters(self) -> None:
        with self.assertRaises(explorer.ExplorerInputError):
            explorer._validate(
                "1203250000' OR '1'='1", explorer._UNIT_NUMBER_RE, "unit-number"
            )


class ExplorerParserTest(unittest.TestCase):
    def test_every_resource_is_registered_and_parses(self) -> None:
        cases = [
            (["award", "--award-number", "100012-00002"], "award_number"),
            (["award-version", "--award-id", "13"], "award_id"),
            (["workflow", "--document-number", "328797"], "document_number"),
            (["unit", "--unit-number", "1203250000"], "unit_number"),
            (
                ["unit-administrators", "--unit-number", "1203250000"],
                "unit_number",
            ),
            (["award-contacts", "--award-id", "13"], "award_id"),
            (["person", "--person-id", "U44984650"], "person_id"),
            (["rolodex", "--rolodex-id", "501"], "rolodex_id"),
            (["sponsor", "--award-id", "13"], "award_id"),
            (["attachments", "--award-id", "13"], "award_id"),
        ]
        for argv, identifier_attr in cases:
            with self.subTest(argv=argv):
                args = explorer.build_parser().parse_args(argv)
                self.assertTrue(hasattr(args, identifier_attr))

    def test_output_defaults_to_table(self) -> None:
        args = explorer.build_parser().parse_args(
            ["award", "--award-number", "100012-00002"]
        )
        self.assertEqual(args.output, "table")

    def test_output_json_is_accepted(self) -> None:
        args = explorer.build_parser().parse_args(
            ["award", "--award-number", "100012-00002", "--output", "json"]
        )
        self.assertEqual(args.output, "json")


class RenderFunctionsAreNullSafeTest(unittest.TestCase):
    def test_render_award_handles_no_match(self) -> None:
        self.assertIn("no current version", explorer.render_award({"award": None}))

    def test_render_unit_handles_no_match(self) -> None:
        self.assertIn("no such unit_number", explorer.render_unit({"unit": None}))

    def test_render_award_contacts_handles_no_match(self) -> None:
        self.assertIn(
            "no such award_id", explorer.render_award_contacts({"award": None})
        )

    def test_render_workflow_handles_no_matches(self) -> None:
        self.assertIn(
            "no archived version",
            explorer.render_workflow({"matches": []}),
        )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class ExplorerPostgresIntegrationTest(unittest.TestCase):
    db_prefix = "pytest_explorer"

    AWARD_ID = 13
    AWARD_NUMBER = "100012-00002"
    LEAD_UNIT_NUMBER = "1203250000"
    WORKFLOW_DOCUMENT_NUMBER = "456789"

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
        self._seed()

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def _seed(self) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number,
                        is_primary_current, status_description,
                        workflow_document_number, lead_unit_number,
                        lead_unit_name
                    ) VALUES (
                        :award_id, :award_number, 1, TRUE,
                        'Approved Award', :workflow_document_number,
                        :lead_unit_number, 'CAS SPACE PHYSICS'
                    )
                    """
                ),
                {
                    "award_id": self.AWARD_ID,
                    "award_number": self.AWARD_NUMBER,
                    "workflow_document_number": self.WORKFLOW_DOCUMENT_NUMBER,
                    "lead_unit_number": self.LEAD_UNIT_NUMBER,
                },
            )

            connection.execute(
                text(
                    """
                    INSERT INTO archive.unit (unit_number, unit_name,
                        parent_unit_number, active)
                    VALUES (:unit_number, 'CAS SPACE PHYSICS',
                        '1200000000', TRUE)
                    """
                ),
                {"unit_number": self.LEAD_UNIT_NUMBER},
            )

            for code, description, group_flag in (
                ("3", "OSP Administrator", "C"),
                ("4", "PAFO Administrator", "C"),
                ("1", "Pre-Award - Department Administrator", "U"),
            ):
                connection.execute(
                    text(
                        """
                        INSERT INTO archive.unit_administrator_type (
                            unit_administrator_type_code, description,
                            default_group_flag
                        ) VALUES (:code, :description, :group_flag)
                        """
                    ),
                    {"code": code, "description": description, "group_flag": group_flag},
                )

            for person_id, code in (
                ("U44984650", "4"),  # Nancy Schindele, PAFO (group C)
                ("U98756203", "3"),  # Anthony J Moy, OSP (group C)
                ("U11111111", "1"),  # a 'U'-group admin - must NOT appear
            ):
                connection.execute(
                    text(
                        """
                        INSERT INTO archive.unit_administrator (
                            unit_number, person_id,
                            unit_administrator_type_code
                        ) VALUES (:unit_number, :person_id, :code)
                        """
                    ),
                    {
                        "unit_number": self.LEAD_UNIT_NUMBER,
                        "person_id": person_id,
                        "code": code,
                    },
                )

            for person_id, first, middle, last, email, phone in (
                ("U44984650", "NANCY", None, "SCHINDELE", "NANCYSCH@BU.EDU", "617-358-5117"),
                ("U98756203", "ANTHONY", "J", "MOY", "TMOY@BU.EDU", "617-353-4365"),
                ("U11111111", "SOMEONE", None, "ELSE", "someone@bu.edu", None),
            ):
                full_name = " ".join(p for p in (first, middle, last) if p)
                connection.execute(
                    text(
                        """
                        INSERT INTO archive.person (
                            person_id, first_name, middle_name, last_name,
                            full_name, email_address, phone_number
                        ) VALUES (
                            :person_id, :first, :middle, :last,
                            :full_name, :email, :phone
                        )
                        """
                    ),
                    {
                        "person_id": person_id,
                        "first": first,
                        "middle": middle,
                        "last": last,
                        "full_name": full_name,
                        "email": email,
                        "phone": phone,
                    },
                )

    def test_fetch_award_returns_current_version(self) -> None:
        with self.engine.connect() as connection:
            data = explorer.fetch_award(
                connection, argparse.Namespace(award_number=self.AWARD_NUMBER)
            )
        self.assertIsNotNone(data["award"])
        self.assertEqual(data["award"]["award_id"], self.AWARD_ID)
        self.assertEqual(
            data["award"]["workflow_document_number"],
            self.WORKFLOW_DOCUMENT_NUMBER,
        )

    def test_fetch_workflow_finds_the_award_by_document_number(self) -> None:
        with self.engine.connect() as connection:
            data = explorer.fetch_workflow(
                connection,
                argparse.Namespace(document_number=self.WORKFLOW_DOCUMENT_NUMBER),
            )
        self.assertEqual(len(data["matches"]), 1)
        self.assertEqual(data["matches"][0]["award_id"], self.AWARD_ID)
        self.assertEqual(data["matches"][0]["award_number"], self.AWARD_NUMBER)

    def test_fetch_unit_returns_unit_details_and_administrators(self) -> None:
        with self.engine.connect() as connection:
            data = explorer.fetch_unit(
                connection, argparse.Namespace(unit_number=self.LEAD_UNIT_NUMBER)
            )
        self.assertEqual(data["unit"]["unit_name"], "CAS SPACE PHYSICS")
        self.assertEqual(data["unit"]["parent_unit_number"], "1200000000")
        self.assertEqual(len(data["administrators"]), 3)

    def test_fetch_award_contacts_central_admin_matches_the_real_kuali_rule(
        self,
    ) -> None:
        """The exact proof the user asked for: Award.lead_unit_number ->
        unit_administrator -> unit_administrator_type WHERE
        default_group_flag='C', reproducing Award.initCentralAdminContacts()
        - not a guessed rule. The 'U'-group administrator must be excluded."""
        with self.engine.connect() as connection:
            data = explorer.fetch_award_contacts(
                connection, argparse.Namespace(award_id=self.AWARD_ID)
            )

        admins = data["central_administration_contacts"]
        names = {row["full_name"] for row in admins}
        self.assertEqual(names, {"NANCY SCHINDELE", "ANTHONY J MOY"})
        self.assertNotIn("SOMEONE ELSE", names)

        by_name = {row["full_name"]: row for row in admins}
        self.assertEqual(
            by_name["NANCY SCHINDELE"]["administrator_type_description"],
            "PAFO Administrator",
        )
        self.assertEqual(
            by_name["ANTHONY J MOY"]["administrator_type_description"],
            "OSP Administrator",
        )

    def test_fetch_award_contacts_returns_none_for_missing_award(self) -> None:
        with self.engine.connect() as connection:
            data = explorer.fetch_award_contacts(
                connection, argparse.Namespace(award_id=999999)
            )
        self.assertIsNone(data["award"])

    def test_render_award_contacts_shows_all_four_sections(self) -> None:
        with self.engine.connect() as connection:
            data = explorer.fetch_award_contacts(
                connection, argparse.Namespace(award_id=self.AWARD_ID)
            )
        rendered = explorer.render_award_contacts(data)
        self.assertIn("KEY PERSONNEL", rendered)
        self.assertIn("UNIT CONTACTS", rendered)
        self.assertIn("SPONSOR CONTACTS", rendered)
        self.assertIn("CENTRAL ADMINISTRATION CONTACTS", rendered)
        self.assertIn("NANCY SCHINDELE", rendered)
        self.assertIn("PAFO Administrator", rendered)
