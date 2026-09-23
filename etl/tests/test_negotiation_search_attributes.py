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


class AwardAssociatedPrecedenceRegressionTest(unittest.TestCase):
    """The verified Kuali precedence rule, pinned on the real records
    that establish it.

    For an Award-associated Negotiation EVERY resolved attribute comes
    from the CURRENT ACTIVE associated Award - title, PI, sponsor and
    lead unit alike. Confirmed against the Kuali UI on 2026-09-23.

    These three negotiations are the ONLY ones in the archive where the
    candidate rules disagree on PI; the other 18 Award-associated
    records carrying a detail PI have a detail PI identical to the
    Award's and prove nothing. 1641 additionally rules out an "as of the
    negotiation date" rule - Harrison Farber WAS the Award PI during
    that 2015 negotiation, yet Kuali shows the current ACTIVE PI.

    The detail rows seeded here are REAL archived data, not stale or
    erroneous values. Kuali simply does not display them once the
    Negotiation is Award-associated.
    """

    db_prefix = "pytest_negotiation_precedence"

    setUp = NegotiationSearchAttributeRebuildTest.setUp
    tearDown = NegotiationSearchAttributeRebuildTest.tearDown
    _negotiation = NegotiationSearchAttributeRebuildTest._negotiation
    _detail = NegotiationSearchAttributeRebuildTest._detail
    _attributes = NegotiationSearchAttributeRebuildTest._attributes

    # negotiation_id -> (award_number, detail PI, ACTIVE Award PI)
    PI_CASES = {
        1641: ("204120-00001", "HARRISON W FARBER", "ELIZABETH S KLINGS"),
        2587: ("205034-00001", "HARRISON W FARBER", "ROBERT W SIMMS"),
        2676: ("203818-00001", "JANE E FOX", "JORGE DELVA"),
    }

    def _seed(self) -> None:
        with self.engine.connect() as connection:
            connection.execute(text(
                """
                INSERT INTO archive.unit (unit_number, unit_name) VALUES
                  ('2444020000', 'SPH Ctr Advancing Hlth Policy & Practice'),
                  ('2442430000', 'SPH HEALTH LAW, POLICY & MANAGEMENT'),
                  ('2574000000', 'CNTR MED--ARTHRITIS CENTER'),
                  ('2573180000', 'MED-MEDICINE')
                """))
            connection.execute(text(
                """
                INSERT INTO archive.sponsor (sponsor_code, sponsor_name) VALUES
                  ('301028', 'HHS/Health Resources and Services Administration'),
                  ('304143', 'Southern Nevada Health District'),
                  ('304091', 'EMD Serono Research & Development Institute, Inc.'),
                  ('302986', 'EMD Serono, Inc (Merck)')
                """))

            award_id = 1
            person_id = 1
            for nid, (award_number, detail_pi, award_pi) in self.PI_CASES.items():
                # An older ARCHIVED version whose PI is the one the detail
                # row also names - this is what makes the case ambiguous.
                connection.execute(text(
                    """
                    INSERT INTO archive.award_version (
                        award_id, award_number, sequence_number,
                        award_sequence_status, title,
                        sponsor_code, sponsor_name,
                        lead_unit_number, lead_unit_name
                    ) VALUES
                      (:old_id, :num, 1, 'ARCHIVED', 'OLD AWARD TITLE',
                       '301028', 'HHS/Health Resources and Services Administration',
                       '2444020000', 'SPH Ctr Advancing Hlth Policy & Practice'),
                      (:new_id, :num, 2, 'ACTIVE', 'CURRENT AWARD TITLE',
                       '301028', 'HHS/Health Resources and Services Administration',
                       '2444020000', 'SPH Ctr Advancing Hlth Policy & Practice')
                    """), {"old_id": award_id, "new_id": award_id + 1, "num": award_number})
                connection.execute(text(
                    """
                    INSERT INTO archive.award_person (
                        award_person_id, award_id, award_number, sequence_number,
                        person_id, full_name, contact_role_code
                    ) VALUES
                      (:p1, :old_id, :num, 1, 'UOLD', :detail_pi, 'PI'),
                      (:p2, :new_id, :num, 2, 'UNEW', :award_pi, 'PI')
                    """), {"p1": person_id, "p2": person_id + 1,
                           "old_id": award_id, "new_id": award_id + 1,
                           "num": award_number, "detail_pi": detail_pi,
                           "award_pi": award_pi})
                self._negotiation(connection, nid, "Award", award_number)
                self._detail(connection, 9000 + nid, nid,
                             title="DETAIL TITLE (real, but not displayed)",
                             pi_name=detail_pi, pi_person_id="UOLD",
                             lead_unit="2442430000", sponsor_code="304143")
                award_id += 2
                person_id += 2
            connection.commit()

    def _rebuild(self) -> None:
        run_rebuild_negotiation_search_attributes(self.engine)

    def test_pi_resolves_to_the_current_active_award_not_the_detail_row(self) -> None:
        self._rebuild()
        for nid, (_num, detail_pi, award_pi) in self.PI_CASES.items():
            with self.subTest(negotiation=nid):
                got = self._attributes(nid)
                self.assertEqual(award_pi, got["principal_investigator_name"])
                self.assertNotEqual(detail_pi, got["principal_investigator_name"])

    def test_sponsor_resolves_to_the_award_for_2676(self) -> None:
        self._rebuild()
        got = self._attributes(2676)
        self.assertEqual(
            "HHS/Health Resources and Services Administration",
            got["sponsor_name"])
        self.assertNotEqual("Southern Nevada Health District", got["sponsor_name"])

    def test_lead_unit_resolves_to_the_award_for_2676(self) -> None:
        self._rebuild()
        got = self._attributes(2676)
        self.assertEqual(
            "SPH Ctr Advancing Hlth Policy & Practice", got["lead_unit_name"])
        self.assertNotEqual("SPH HEALTH LAW, POLICY & MANAGEMENT",
                            got["lead_unit_name"])

    def test_title_resolves_to_the_award_not_the_detail_row(self) -> None:
        self._rebuild()
        for nid in self.PI_CASES:
            with self.subTest(negotiation=nid):
                got = self._attributes(nid)
                self.assertEqual("CURRENT AWARD TITLE", got["title"])

    def test_every_one_of_the_three_is_sourced_from_the_award(self) -> None:
        self._rebuild()
        for nid in self.PI_CASES:
            with self.subTest(negotiation=nid):
                self.assertEqual("AWARD", self._attributes(nid)["attribute_source"])

    def test_detail_rows_are_preserved_untouched_by_the_rebuild(self) -> None:
        """Not displayed is not the same as deleted."""
        self._rebuild()
        with self.engine.connect() as connection:
            rows = connection.execute(text(
                "SELECT negotiation_id, pi_name, lead_unit, sponsor_code "
                "FROM archive.negotiation_unassociated_detail "
                "ORDER BY negotiation_id")).mappings().all()
        self.assertEqual(len(self.PI_CASES), len(rows))
        for row in rows:
            _num, detail_pi, _award_pi = self.PI_CASES[row["negotiation_id"]]
            self.assertEqual(detail_pi, row["pi_name"])
            self.assertEqual("2442430000", row["lead_unit"])
            self.assertEqual("304143", row["sponsor_code"])


class AwardVersionSelectionTest(unittest.TestCase):
    """Which Award version an Award-associated Negotiation resolves to.

    Regression for a real defect: the loader selected MAX(award_id),
    which is NOT the ACTIVE version for 230 of 40,732 Award families.
    Award 205270-00001 is the concrete case - award_id 3142987 is
    sequence 11 and CANCELED, while the ACTIVE row is 3142979,
    sequence 10. is_primary_current (V013) is no better here: it ranks
    sequence_number DESC ahead of ACTIVE and picks the same CANCELED row.
    """

    db_prefix = "pytest_negotiation_award_version"

    setUp = NegotiationSearchAttributeRebuildTest.setUp
    tearDown = NegotiationSearchAttributeRebuildTest.tearDown
    _negotiation = NegotiationSearchAttributeRebuildTest._negotiation
    _attributes = NegotiationSearchAttributeRebuildTest._attributes

    def _seed(self) -> None:
        with self.engine.connect() as connection:
            connection.execute(text(
                """
                INSERT INTO archive.award_version (
                    award_id, award_number, sequence_number,
                    award_sequence_status, title
                ) VALUES
                  (3142979, '205270-00001', 10, 'ACTIVE',   'ACTIVE TITLE'),
                  (3142987, '205270-00001', 11, 'CANCELED', 'CANCELED TITLE'),
                  (3352140, '200421-00001',  3, 'ARCHIVED', 'NO ACTIVE TITLE')
                """))
            # 2680 and 5246 both associate to the CANCELED-latest family.
            self._negotiation(connection, 2680, "Award", "205270-00001")
            self._negotiation(connection, 5246, "Award", "205270-00001")
            # Negotiation 1's award family has no ACTIVE version at all.
            self._negotiation(connection, 1, "Award", "200421-00001")
            connection.commit()

    def test_active_version_wins_over_a_higher_canceled_award_id(self) -> None:
        run_rebuild_negotiation_search_attributes(self.engine)
        for nid in (2680, 5246):
            with self.subTest(negotiation=nid):
                self.assertEqual("ACTIVE TITLE", self._attributes(nid)["title"])

    def test_family_with_no_active_version_still_resolves(self) -> None:
        """The tie-breaks are load-bearing: without them this would
        resolve to nothing and the record would go blank."""
        run_rebuild_negotiation_search_attributes(self.engine)
        got = self._attributes(1)
        self.assertEqual("NO ACTIVE TITLE", got["title"])
        self.assertEqual("AWARD", got["attribute_source"])
