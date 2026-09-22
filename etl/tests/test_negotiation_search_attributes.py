"""Regression tests for archive_etl.negotiation_search_attributes.

Runs the real resolution SQL against a real, throwaway PostgreSQL
database. The whole point of this table is a source-selection rule that
LEFT JOIN / LATERAL / COALESCE semantics decide, so a mock would prove
nothing.

The central rule under test (measured live 2026-09-22, Oracle and
archive agreeing): archive.negotiation_unassociated_detail is NOT a
per-negotiation attributes table. It covers 8,554 of 10,775
negotiations. Award-associated negotiations resolve their attributes
from the associated Award instead. Reading either source alone silently
blanks ~20% of the archive.

Fixture values are the real, live-verified Negotiation 120 record:
Lead Unit 1242040000 -> ENG BIOMEDICAL ENG, Sponsor 303630 -> Addgene,
PI AHMAD KHALIL.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl.negotiation_search_attributes import (
    run_rebuild_negotiation_search_attributes,
)
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


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class NegotiationSearchAttributeRebuildTest(unittest.TestCase):
    db_prefix = "pytest_negotiation_search_attribute"

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
        """Four negotiations, one per resolution path."""
        with self.engine.connect() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO archive.unit (unit_number, unit_name)
                    VALUES ('1242040000', 'ENG BIOMEDICAL ENG'),
                           ('1240000000', 'ENG DEANS OFFICE')
                    """
                )
            )
            connection.execute(
                text(
                    """
                    INSERT INTO archive.sponsor (sponsor_code, sponsor_name)
                    VALUES ('303630', 'Addgene'),
                           ('301045', 'NIH/National Institute on Aging')
                    """
                )
            )

            # Negotiation 120 - unassociated, the real fixture.
            self._negotiation(connection, 120, "None", "119")
            self._detail(
                connection,
                1,
                120,
                title="168333",
                pi_name="AHMAD KHALIL",
                pi_person_id="U62893002",
                lead_unit="1242040000",
                sponsor_code="303630",
            )

            # 200 - Award-associated, NO detail row (the 2,202 case).
            self._negotiation(connection, 200, "Award", "105698-00001")

            # 300 - Award-associated AND carrying a stale detail row
            # (the 21 case). The Award must win.
            self._negotiation(connection, 300, "Award", "105698-00001")
            self._detail(
                connection,
                2,
                300,
                title="STALE TITLE",
                pi_name="STALE PI",
                pi_person_id="STALE",
                lead_unit="1240000000",
                sponsor_code="303630",
            )

            # 400 - Subaward-associated, no detail and no fallback.
            self._negotiation(connection, 400, "Subaward", "SUB-1")

            # The Award the two Award-associated negotiations resolve to.
            connection.execute(
                text(
                    """
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number, title,
                        sponsor_code, sponsor_name,
                        prime_sponsor_code, prime_sponsor_name,
                        lead_unit_number, lead_unit_name,
                        sponsor_award_number
                    ) VALUES
                      (1, '105698-00001', 1, 'OLD AWARD TITLE',
                       '301045', 'NIH/National Institute on Aging',
                       NULL, NULL, '1240000000', 'ENG DEANS OFFICE', 'OLD'),
                      (2, '105698-00001', 2, 'Autism Study',
                       '301045', 'NIH/National Institute on Aging',
                       NULL, NULL, '1242040000', 'ENG BIOMEDICAL ENG',
                       'R01AG008768')
                    """
                )
            )
            connection.execute(
                text(
                    """
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number,
                        sequence_number, person_id, full_name,
                        contact_role_code
                    ) VALUES
                      (1, 2, '105698-00001', 2, 'U111',
                       'ZZ COINVESTIGATOR', 'COI'),
                      (2, 2, '105698-00001', 2, 'U222',
                       'REAL AWARD PI', 'PI')
                    """
                )
            )
            connection.commit()

    def _negotiation(
        self, connection, negotiation_id: int, assoc: str, assoc_doc: str
    ) -> None:
        connection.execute(
            text(
                """
                INSERT INTO archive.negotiation (
                    negotiation_id, document_number,
                    negotiation_status_description,
                    negotiation_agreement_type_description,
                    negotiation_association_type_description,
                    negotiator_person_id, negotiator_full_name,
                    negotiation_start_date, negotiation_end_date,
                    associated_document_id, loaded_at
                ) VALUES (
                    :nid, :doc, 'Fully Executed',
                    'Material Transfer Agreement', :assoc,
                    'U93001494', 'JESSICA L RIVIECCIO',
                    DATE '2014-05-24', DATE '2014-06-02',
                    :assoc_doc, CURRENT_TIMESTAMP
                )
                """
            ),
            {
                "nid": negotiation_id,
                "doc": str(360000 + negotiation_id),
                "assoc": assoc,
                "assoc_doc": assoc_doc,
            },
        )

    def _detail(self, connection, detail_id: int, negotiation_id: int, **v) -> None:
        connection.execute(
            text(
                """
                INSERT INTO archive.negotiation_unassociated_detail (
                    negotiation_unassoc_detail_id, negotiation_id,
                    title, pi_name, pi_person_id, lead_unit, sponsor_code,
                    loaded_at
                ) VALUES (
                    :did, :nid, :title, :pi_name, :pi_person_id,
                    :lead_unit, :sponsor_code, CURRENT_TIMESTAMP
                )
                """
            ),
            {"did": detail_id, "nid": negotiation_id, **v},
        )

    def _attributes(self, negotiation_id: int) -> dict:
        with self.engine.connect() as connection:
            return dict(
                connection.execute(
                    text(
                        "SELECT * FROM archive.negotiation_search_attribute "
                        "WHERE negotiation_id = :nid"
                    ),
                    {"nid": negotiation_id},
                )
                .mappings()
                .one()
            )

    # ---------------------------------------------------------------

    def test_unassociated_negotiation_resolves_from_detail_and_reference_data(
        self,
    ) -> None:
        run_rebuild_negotiation_search_attributes(self.engine)
        row = self._attributes(120)

        self.assertEqual(row["attribute_source"], "UNASSOCIATED_DETAIL")
        self.assertEqual(row["title"], "168333")
        self.assertEqual(row["principal_investigator_name"], "AHMAD KHALIL")
        self.assertEqual(row["lead_unit_number"], "1242040000")
        # Resolved through archive.unit, never derived from the number.
        self.assertEqual(row["lead_unit_name"], "ENG BIOMEDICAL ENG")
        self.assertEqual(row["sponsor_code"], "303630")
        # Resolved through archive.sponsor - the whole point of V079.
        self.assertEqual(row["sponsor_name"], "Addgene")

    def test_award_associated_negotiation_without_detail_is_not_blank(
        self,
    ) -> None:
        """The 2,202-record case that a detail-only read silently loses."""
        run_rebuild_negotiation_search_attributes(self.engine)
        row = self._attributes(200)

        self.assertEqual(row["attribute_source"], "AWARD")
        self.assertEqual(row["title"], "Autism Study")
        self.assertEqual(row["principal_investigator_name"], "REAL AWARD PI")
        self.assertEqual(row["sponsor_name"], "NIH/National Institute on Aging")
        self.assertEqual(row["lead_unit_name"], "ENG BIOMEDICAL ENG")
        self.assertEqual(row["sponsor_award_number"], "R01AG008768")

    def test_award_association_wins_over_a_stale_detail_row(self) -> None:
        """The 21-record case: both sources present, Award must win."""
        run_rebuild_negotiation_search_attributes(self.engine)
        row = self._attributes(300)

        self.assertEqual(row["attribute_source"], "AWARD")
        self.assertEqual(row["title"], "Autism Study")
        self.assertEqual(row["principal_investigator_name"], "REAL AWARD PI")
        self.assertNotEqual(row["title"], "STALE TITLE")
        self.assertNotEqual(row["principal_investigator_name"], "STALE PI")

    def test_award_fallback_uses_the_current_award_row_not_the_first(
        self,
    ) -> None:
        """Highest award_id wins, matching AwardArchiveRepository."""
        run_rebuild_negotiation_search_attributes(self.engine)
        self.assertEqual(self._attributes(200)["title"], "Autism Study")
        self.assertNotEqual(self._attributes(200)["title"], "OLD AWARD TITLE")

    def test_pi_selection_prefers_the_pi_role_over_other_contacts(self) -> None:
        run_rebuild_negotiation_search_attributes(self.engine)
        self.assertEqual(
            self._attributes(200)["principal_investigator_name"],
            "REAL AWARD PI",
        )

    def test_unresolvable_association_is_recorded_as_none_not_dropped(
        self,
    ) -> None:
        """Subaward/Institutional Proposal keep a row, blank but present."""
        run_rebuild_negotiation_search_attributes(self.engine)
        row = self._attributes(400)

        self.assertEqual(row["attribute_source"], "NONE")
        self.assertIsNone(row["principal_investigator_name"])
        self.assertIsNone(row["sponsor_name"])

    def test_every_negotiation_gets_exactly_one_row(self) -> None:
        report = run_rebuild_negotiation_search_attributes(self.engine)
        self.assertEqual(report["negotiations"], 4)
        with self.engine.connect() as connection:
            negotiations = connection.execute(
                text("SELECT COUNT(*) FROM archive.negotiation")
            ).scalar_one()
        self.assertEqual(report["negotiations"], negotiations)

    def test_rebuild_is_idempotent_and_never_deletes(self) -> None:
        first = run_rebuild_negotiation_search_attributes(self.engine)
        second = run_rebuild_negotiation_search_attributes(self.engine)

        self.assertEqual(first["negotiations"], second["negotiations"])
        self.assertEqual(self._attributes(120)["sponsor_name"], "Addgene")
        self.assertEqual(self._attributes(200)["title"], "Autism Study")

    def test_rebuild_reflects_later_reference_data_corrections(self) -> None:
        """A sponsor name fixed after the fact is picked up on re-run."""
        run_rebuild_negotiation_search_attributes(self.engine)
        with self.engine.connect() as connection:
            connection.execute(
                text(
                    "UPDATE archive.sponsor SET sponsor_name = 'Addgene, Inc.' "
                    "WHERE sponsor_code = '303630'"
                )
            )
            connection.commit()

        run_rebuild_negotiation_search_attributes(self.engine)
        self.assertEqual(self._attributes(120)["sponsor_name"], "Addgene, Inc.")

    def test_dry_run_does_not_persist(self) -> None:
        run_rebuild_negotiation_search_attributes(self.engine, dry_run=True)
        with self.engine.connect() as connection:
            remaining = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.negotiation_search_attribute"
                )
            ).scalar_one()
        self.assertEqual(remaining, 0)

    def test_coverage_report_counts_each_source(self) -> None:
        report = run_rebuild_negotiation_search_attributes(self.engine)
        self.assertEqual(report["source_award"], 2)
        self.assertEqual(report["source_unassociated_detail"], 1)
        self.assertEqual(report["source_none"], 1)
        self.assertEqual(report["with_principal_investigator"], 3)
        self.assertEqual(report["with_lead_unit_name"], 3)
