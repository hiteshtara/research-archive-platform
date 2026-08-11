"""Tests for build_evidence_embedding.py - Phase 2 of Award evidence
indexing (docs/architecture/AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md).

Pure-logic tests (text builders, hashing) never touch a database or
Bedrock. Integration tests run against a real, ephemeral local Postgres
database (same pattern as test_award_amount_info_current_row_selection.py
and test_semantic_search_document_type_guard.py) and use a FakeBedrockClient
test double matching build_evidence_embedding.embed_text()'s exact
interface - no real AWS/Bedrock call is ever made by these tests.

Real pinned fixtures (never invented placeholder values):
- 204713-00133 (award_id 3187665, the golden fixture) for the ordinary
  Award evidence types.
- 204713-00001 (award_id 1768700, CARB-X's sibling award) for
  RELATED_PROPOSAL - one of only 60 archive.award_funding_proposal rows
  (out of 372) whose proposal_id actually resolves to a real
  archive.proposal_version row.
- 104949-00002 (award_id 1648412) for RELATED_NEGOTIATION and
  RELATED_SUBAWARD - the only Award family in the archive with both a
  real negotiation and a real subaward link.
"""

from __future__ import annotations

import getpass
import json
import os
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import build_evidence_embedding as evidence
from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")


# --- Fake embedding provider - never calls real Bedrock -------------------


class FakeBedrockBody:
    def __init__(self, payload: dict) -> None:
        self._payload = json.dumps(payload).encode("utf-8")

    def read(self) -> bytes:
        return self._payload


class FakeBedrockClient:
    """Matches build_evidence_embedding.embed_text()'s exact expected
    interface (invoke_model(modelId=, body=) -> {"body": <has .read()>})
    - a deterministic, content-derived fake vector, never a real AWS call."""

    def __init__(self) -> None:
        self.calls: list[str] = []

    def invoke_model(self, *, modelId: str, body: str) -> dict:
        request = json.loads(body)
        input_text = request["inputText"]
        self.calls.append(input_text)
        seed = sum(ord(c) for c in input_text) % 997
        embedding = [((seed + i) % 1000) / 1000.0 for i in range(evidence.EMBEDDING_DIMENSIONS)]
        return {"body": FakeBedrockBody({"embedding": embedding})}


def fake_embed_fn(client: FakeBedrockClient):
    return lambda text_value: evidence.embed_text(client, text_value)


# --- Pure text-builder tests (no database, no network) ---------------------


class AwardVersionTextBuilderTest(unittest.TestCase):
    def test_full_row_renders_every_field(self) -> None:
        row = {
            "award_number": "204713-00133", "sequence_number": 125,
            "workflow_document_number": "923140", "title": "CARB-X",
            "sponsor_name": "HHS/Assistant Secretary for Preparedness and Response",
            "lead_unit_name": "LAW CARB-X Grant", "status_description": "Approved Award",
            "award_effective_date": "2016-08-01", "begin_date": "2016-08-01",
            "closeout_date": None,
        }
        result = evidence.build_award_version_text(row)
        self.assertIn("204713-00133 version 125", result)
        self.assertIn("(document 923140)", result)
        self.assertIn("CARB-X", result)
        self.assertIn("Sponsor: HHS/Assistant Secretary for Preparedness and Response", result)
        self.assertIn("begins 2016-08-01", result)

    def test_null_begin_and_closeout_dates_never_render_as_none(self) -> None:
        """Regression: the Phase 1 design's own predecessor draft
        produced literal "begins None" for this exact real row shape
        (204713-00133's current version has begin_date=None,
        closeout_date=None) - this must never happen again."""
        row = {
            "award_number": "204713-00133", "sequence_number": 125,
            "workflow_document_number": "923140", "title": "CARB-X",
            "sponsor_name": "HHS/Assistant Secretary for Preparedness and Response",
            "lead_unit_name": "LAW CARB-X Grant", "status_description": "Approved Award",
            "award_effective_date": "2016-08-01", "begin_date": None,
            "closeout_date": None,
        }
        result = evidence.build_award_version_text(row)
        self.assertNotIn("None", result)
        self.assertNotIn("begins", result)
        self.assertNotIn("closes", result)

    def test_missing_workflow_document_number_omits_parenthetical(self) -> None:
        row = {
            "award_number": "X-1", "sequence_number": 1, "workflow_document_number": None,
            "title": "T", "sponsor_name": None, "lead_unit_name": None,
            "status_description": None, "award_effective_date": None,
            "begin_date": None, "closeout_date": None,
        }
        result = evidence.build_award_version_text(row)
        self.assertNotIn("(document", result)
        self.assertNotIn("None", result)


class AwardPersonTextBuilderTest(unittest.TestCase):
    def test_real_fixture_row(self) -> None:
        # Real row: archive.award_person, award_id=3187665.
        row = {
            "full_name": "MICHAEL KEVIN OUTTERSON", "contact_role_code": "PI",
            "key_person_project_role": None, "award_number": "204713-00133",
            "sequence_number": 125,
        }
        result = evidence.build_award_person_text(row)
        self.assertEqual(
            result,
            "MICHAEL KEVIN OUTTERSON (PI) on Award 204713-00133 version 125.",
        )
        self.assertNotIn("None", result)
        self.assertNotIn(" — ", result)

    def test_role_text_included_when_present(self) -> None:
        row = {
            "full_name": "JANE DOE", "contact_role_code": "COI",
            "key_person_project_role": "Co-Investigator", "award_number": "X-1",
            "sequence_number": 1,
        }
        result = evidence.build_award_person_text(row)
        self.assertIn("JANE DOE — Co-Investigator (COI)", result)


class AwardAmountTextBuilderTest(unittest.TestCase):
    def test_real_fixture_current_row(self) -> None:
        # Real row: archive.award_amount_info, award_amount_info_id=3195982.
        row = {
            "award_number": "204713-00133", "sequence_number": 125,
            "exact_record_id": 3195982, "tnm_document_number": "925932",
            "obligated_total_amount": "0.00", "anticipated_total_amount": "0.00",
        }
        result = evidence.build_award_amount_text(row)
        self.assertIn("amount record 3195982", result)
        self.assertIn("document 925932", result)
        self.assertIn("obligated $0.00", result)

    def test_missing_document_number_omits_clause(self) -> None:
        row = {
            "award_number": "204713-00133", "sequence_number": 125,
            "exact_record_id": 3187674, "tnm_document_number": None,
            "obligated_total_amount": "280607.11", "anticipated_total_amount": "280607.11",
        }
        result = evidence.build_award_amount_text(row)
        self.assertNotIn("document None", result)
        self.assertNotIn(", document", result)


class AwardCommentTextBuilderTest(unittest.TestCase):
    def test_real_fixture_row(self) -> None:
        row = {
            "award_number": "204713-00133", "sequence_number": 125,
            "comment_type_code": "21",
            "comments": "Rebudget 5-18-22_Pattern Opt1_3156_funder realloc_BARDA & WT",
        }
        result = evidence.build_award_comment_text(row)
        self.assertEqual(
            result,
            "Comment (21) on Award 204713-00133 version 125: "
            "Rebudget 5-18-22_Pattern Opt1_3156_funder realloc_BARDA & WT",
        )


class AwardTermTextBuilderTest(unittest.TestCase):
    def test_sponsor_term_real_fixture(self) -> None:
        row = {"sponsor_term_id": 449, "award_number": "204713-00133", "sequence_number": 125}
        result = evidence.build_award_term_sponsor_text(row)
        self.assertEqual(result, "Sponsor term 449 on Award 204713-00133 version 125.")

    def test_report_term_real_fixture_with_null_due_date(self) -> None:
        row = {
            "report_class_code": "3", "report_code": "21", "frequency_code": "6",
            "due_date": None, "award_number": "204713-00133", "sequence_number": 125,
        }
        result = evidence.build_award_term_report_text(row)
        self.assertNotIn("None", result)
        self.assertNotIn("due", result)
        self.assertIn("class 3, code 21, frequency 6", result)


class RelatedProposalTextBuilderTest(unittest.TestCase):
    def test_real_fixture_row(self) -> None:
        # Real row: archive.award_funding_proposal, award_number=204713-00001.
        row = {
            "award_number": "204713-00001", "sequence_number": 1,
            "proposal_number": "01128961", "title": "CARB-X", "active_flag": "Y",
        }
        result = evidence.build_related_proposal_text(row)
        self.assertEqual(
            result,
            "Award 204713-00001 version 1 is funded by Proposal 01128961: CARB-X.",
        )

    def test_inactive_relationship_is_labeled(self) -> None:
        row = {
            "award_number": "X-1", "sequence_number": 1,
            "proposal_number": "P-1", "title": "T", "active_flag": "N",
        }
        result = evidence.build_related_proposal_text(row)
        self.assertTrue(result.endswith("(inactive relationship)"))


class RelatedNegotiationTextBuilderTest(unittest.TestCase):
    def test_real_fixture_row(self) -> None:
        # Real row: archive.negotiation, negotiation_id=11241, Award 104949-00002.
        row = {
            "document_number": "1060608",
            "negotiation_agreement_type_description": "Data Use Agreement",
            "award_number": "104949-00002", "negotiator_full_name": "WILLIAM P SEGARRA",
            "negotiation_status_description": "Fully Executed",
        }
        result = evidence.build_related_negotiation_text(row)
        self.assertEqual(
            result,
            "Negotiation 1060608 (Data Use Agreement) associated with Award "
            "104949-00002, negotiator WILLIAM P SEGARRA, status Fully Executed.",
        )


class RelatedSubawardTextBuilderTest(unittest.TestCase):
    def test_real_fixture_row(self) -> None:
        # Real row: archive.subaward_funding, subaward_funding_source_id=11185.
        row = {
            "subaward_code": "1008", "document_number": "433858",
            "award_number": "104949-00002", "status_description": "07. Executed",
        }
        result = evidence.build_related_subaward_text(row)
        self.assertEqual(
            result,
            "Subaward 1008 (document 433858) is linked to Award 104949-00002, "
            "status 07. Executed.",
        )


# --- Regressions for the two defects found/fixed during final review ------
# (pure-logic, no database needed)


class QueriesForDocumentTypeRegressionTest(unittest.TestCase):
    """Regression: _queries_for_document_type() originally returned the
    *resolved SQL text* for every type (including non-AWARD_TERM types),
    not the dict keys - so populate_evidence()'s own
    DOCUMENT_TYPE_QUERIES[query_key] lookup raised KeyError on every
    single invocation, for every type. Verified by calling the real
    function and using its return value as an actual dict key, not by
    reading the source."""

    def test_returns_keys_for_a_plain_type(self) -> None:
        result = evidence._queries_for_document_type("AWARD_PERSON")
        self.assertEqual(result, ["AWARD_PERSON"])
        for key in result:
            self.assertIn(key, evidence.DOCUMENT_TYPE_QUERIES)

    def test_returns_keys_not_sql_text_for_award_term(self) -> None:
        result = evidence._queries_for_document_type("AWARD_TERM")
        self.assertEqual(result, ["AWARD_TERM_SPONSOR", "AWARD_TERM_REPORT"])
        for key in result:
            # This is the exact lookup populate_evidence() performs -
            # if this raised KeyError, the bug is back.
            sql = evidence.DOCUMENT_TYPE_QUERIES[key]
            self.assertIsInstance(sql, str)
            self.assertNotEqual(sql.strip(), "")


class ExactRecordIdDisambiguationTest(unittest.TestCase):
    """AWARD_TERM_SPONSOR and AWARD_TERM_REPORT share one document_type
    but draw IDs from two independent Oracle sequences - nothing
    guarantees those ID spaces never overlap. Verifies the fix that
    negates report-term IDs so a numeric collision between the two
    source tables can never collapse into the same
    (module, document_type, exact_record_id) row."""

    def test_sponsor_term_id_is_unchanged(self) -> None:
        self.assertEqual(evidence._exact_record_id_for("AWARD_TERM_SPONSOR", 500), 500)

    def test_report_term_id_is_negated(self) -> None:
        self.assertEqual(evidence._exact_record_id_for("AWARD_TERM_REPORT", 500), -500)

    def test_colliding_raw_ids_never_produce_the_same_exact_record_id(self) -> None:
        raw_id = 777
        sponsor_id = evidence._exact_record_id_for("AWARD_TERM_SPONSOR", raw_id)
        report_id = evidence._exact_record_id_for("AWARD_TERM_REPORT", raw_id)
        self.assertNotEqual(sponsor_id, report_id)


# --- Hash determinism -------------------------------------------------------


class HashDeterminismTest(unittest.TestCase):
    def test_source_hash_is_deterministic(self) -> None:
        first = evidence.source_hash("some text")
        second = evidence.source_hash("some text")
        self.assertEqual(first, second)
        self.assertEqual(len(first), 64)

    def test_source_hash_changes_with_text(self) -> None:
        self.assertNotEqual(evidence.source_hash("A"), evidence.source_hash("B"))

    def test_source_row_hash_is_deterministic_and_order_independent(self) -> None:
        row_a = {"exact_record_id": 1, "b": "2", "a": "1"}
        row_b = {"a": "1", "b": "2", "exact_record_id": 1}
        self.assertEqual(evidence.source_row_hash(row_a), evidence.source_row_hash(row_b))

    def test_source_row_hash_excludes_exact_record_id(self) -> None:
        row_a = {"exact_record_id": 1, "a": "1"}
        row_b = {"exact_record_id": 2, "a": "1"}
        self.assertEqual(evidence.source_row_hash(row_a), evidence.source_row_hash(row_b))

    def test_source_row_hash_changes_when_a_field_changes(self) -> None:
        row_a = {"exact_record_id": 1, "a": "1"}
        row_b = {"exact_record_id": 1, "a": "2"}
        self.assertNotEqual(evidence.source_row_hash(row_a), evidence.source_row_hash(row_b))


# --- Integration tests (real Postgres, fake Bedrock) ------------------------


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
class BuildEvidenceEmbeddingIntegrationTest(unittest.TestCase):
    db_prefix = "pytest_evidence_embedding"

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
        self.bedrock = FakeBedrockClient()
        self.embed_fn = fake_embed_fn(self.bedrock)

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    # -- fixture seeding helpers --

    def _seed_award_204713_00133(self) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.award_version "
                    "(award_id, award_number, sequence_number, title, sponsor_name, "
                    " lead_unit_name, status_description, is_current_version, is_primary_current) "
                    "VALUES (3187665, '204713-00133', 125, 'CARB-X', "
                    "'HHS/Assistant Secretary for Preparedness and Response', "
                    "'LAW CARB-X Grant', 'Approved Award', TRUE, TRUE)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_person "
                    "(award_person_id, award_id, award_number, sequence_number, full_name, contact_role_code) "
                    "VALUES (3187666, 3187665, '204713-00133', 125, 'MICHAEL KEVIN OUTTERSON', 'PI')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_amount_info "
                    "(award_amount_info_id, award_id, award_number, sequence_number, "
                    " obligated_total_amount, anticipated_total_amount, tnm_document_number) "
                    "VALUES (3195982, 3187665, '204713-00133', 125, 0.00, 0.00, '925932')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_comment "
                    "(award_comment_id, award_id, award_number, sequence_number, comment_type_code, comments) "
                    "VALUES (1801726, 3187665, '204713-00133', 125, '21', "
                    "'Rebudget 5-18-22_Pattern Opt1_3156_funder realloc_BARDA & WT')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_sponsor_term "
                    "(award_sponsor_term_id, award_id, award_number, sequence_number, sponsor_term_id) "
                    "VALUES (3025610, 3187665, '204713-00133', 125, 449)"
                )
            )

    def _seed_award_204713_00001_with_proposal(self) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.award_version "
                    "(award_id, award_number, sequence_number, title, is_current_version, is_primary_current) "
                    "VALUES (1768700, '204713-00001', 1, 'CARB-X', FALSE, FALSE)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.proposal_version "
                    "(proposal_id, proposal_number, version_number, title) "
                    "VALUES (1139478, '01128961', 1, 'CARB-X')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_funding_proposal "
                    "(award_funding_proposal_id, award_id, proposal_id, active_flag) "
                    "VALUES (1768708, 1768700, 1139478, 'Y')"
                )
            )

    def _seed_award_104949_00002_with_negotiation_and_subaward(self) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.award_version "
                    "(award_id, award_number, sequence_number, title, is_current_version, is_primary_current) "
                    "VALUES (1648412, '104949-00002', 16, "
                    "'An internet based prospective study of time to pregnancy', TRUE, TRUE)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.negotiation "
                    "(negotiation_id, document_number, negotiation_agreement_type_description, "
                    " negotiator_full_name, negotiation_status_description, "
                    " negotiation_association_type_code, associated_document_id) "
                    "VALUES (11241, '1060608', 'Data Use Agreement', 'WILLIAM P SEGARRA', "
                    "'Fully Executed', 'AWD', '104949-00002')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.subaward "
                    "(subaward_id, sequence_number, subaward_code, status_description, "
                    " subaward_sequence_status, document_number) "
                    "VALUES (9736, 1, '1008', '07. Executed', 'ACTIVE', '433858')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_funding "
                    "(subaward_funding_source_id, subaward_id, subaward_code, sequence_number, award_number) "
                    "VALUES (11185, 9736, '1008', 1, '104949-00002')"
                )
            )

    def _rows(self, document_type: str | None = None):
        with self.engine.connect() as connection:
            sql = "SELECT * FROM archive.search_embedding"
            params = {}
            if document_type:
                sql += " WHERE document_type = :dt"
                params["dt"] = document_type
            return connection.execute(text(sql), params).mappings().all()

    # -- 1-3: builders already covered above (pure tests); this class covers 4-18 --

    def test_stable_evidence_ids_and_document_keys(self) -> None:
        self._seed_award_204713_00133()
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        rows = self._rows("AWARD_PERSON")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["module"], "AWARD")
        self.assertEqual(rows[0]["document_type"], "AWARD_PERSON")
        self.assertEqual(rows[0]["exact_record_id"], 3187666)
        self.assertEqual(report["inserted"], 1)

    def test_deterministic_text_and_hash_generation(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_COMMENT"], dry_run=False
        )
        rows = self._rows("AWARD_COMMENT")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["source_hash"], evidence.source_hash(rows[0]["source_text"]))

    def test_insert_behavior(self) -> None:
        self._seed_award_204713_00133()
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_VERSION"], dry_run=False
        )
        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(len(self._rows("AWARD_VERSION")), 1)

    def test_update_behavior_when_source_changes(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_COMMENT"], dry_run=False
        )
        with self.engine.begin() as connection:
            connection.execute(
                text("UPDATE archive.award_comment SET comments = 'changed text' WHERE award_comment_id = 1801726")
            )
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_COMMENT"], dry_run=False
        )
        self.assertEqual(report["updated"], 1)
        self.assertEqual(report["unchanged"], 0)
        rows = self._rows("AWARD_COMMENT")
        self.assertIn("changed text", rows[0]["source_text"])

    def test_unchanged_row_is_reused_without_a_bedrock_call(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_TERM"], dry_run=False
        )
        calls_after_first_run = len(self.bedrock.calls)
        self.assertGreater(calls_after_first_run, 0)

        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_TERM"], dry_run=False
        )
        self.assertEqual(report["unchanged"], 1)
        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        # No new Bedrock calls on the second, unchanged run.
        self.assertEqual(len(self.bedrock.calls), calls_after_first_run)

    def test_duplicate_prevention_via_unique_constraint(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        self.assertEqual(len(self._rows("AWARD_PERSON")), 1)

    def test_hard_deletion_of_stale_managed_evidence(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        self.assertEqual(len(self._rows("AWARD_PERSON")), 1)

        with self.engine.begin() as connection:
            connection.execute(
                text("DELETE FROM archive.award_person WHERE award_person_id = 3187666")
            )
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        self.assertEqual(report["deleted"], 1)
        self.assertEqual(len(self._rows("AWARD_PERSON")), 0)

    def test_protection_of_evidence_types_outside_the_current_run(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133",
            ["AWARD_PERSON", "AWARD_COMMENT"], dry_run=False,
        )
        self.assertEqual(len(self._rows("AWARD_PERSON")), 1)
        self.assertEqual(len(self._rows("AWARD_COMMENT")), 1)

        with self.engine.begin() as connection:
            connection.execute(
                text("DELETE FROM archive.award_person WHERE award_person_id = 3187666")
            )
        # Only AWARD_PERSON is in scope for this run - AWARD_COMMENT's
        # row must survive even though nothing about it changed.
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        self.assertEqual(len(self._rows("AWARD_PERSON")), 0)
        self.assertEqual(len(self._rows("AWARD_COMMENT")), 1)

    def test_dry_run_reports_proposed_deletions_without_applying_them(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        with self.engine.begin() as connection:
            connection.execute(
                text("DELETE FROM archive.award_person WHERE award_person_id = 3187666")
            )
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=True
        )
        self.assertEqual(report["deleted"], 0)
        self.assertEqual(len(report["proposed_deletions"]), 1)
        self.assertEqual(report["proposed_deletions"][0]["exact_record_id"], 3187666)
        # Row still present - dry-run never applies the deletion.
        self.assertEqual(len(self._rows("AWARD_PERSON")), 1)

    def test_dry_run_never_calls_bedrock(self) -> None:
        self._seed_award_204713_00133()
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_VERSION"], dry_run=True
        )
        self.assertEqual(len(self.bedrock.calls), 0)
        self.assertEqual(report["skipped"], 1)
        self.assertEqual(len(self._rows("AWARD_VERSION")), 0)

    def test_failure_before_reconciliation_preserves_old_rows(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        self.assertEqual(len(self._rows("AWARD_PERSON")), 1)

        # A broken embed_fn simulates an embedding failure mid-run - the
        # existing AWARD_PERSON row (from a different, successful type in
        # this same run) must survive because reconciliation never runs.
        def broken_embed_fn(text_value: str):
            raise RuntimeError("simulated embedding failure")

        with self.engine.begin() as connection:
            # Force AWARD_PERSON to look changed so it would otherwise
            # be re-embedded (and its old row is the one we're proving
            # survives a failure elsewhere in the same run). AWARD_TERM's
            # source row (award_sponsor_term_id=3025610) already exists
            # from _seed_award_204713_00133().
            connection.execute(
                text(
                    "UPDATE archive.award_person SET full_name = 'CHANGED NAME' "
                    "WHERE award_person_id = 3187666"
                )
            )

        report = evidence.populate_evidence(
            self.engine, broken_embed_fn, "204713-00133",
            ["AWARD_PERSON", "AWARD_TERM"], dry_run=False,
        )
        self.assertEqual(report["failed"], 2)  # both types fail via the broken embed_fn
        self.assertTrue(report.get("reconciliation_skipped_due_to_failure"))
        # The row is untouched - old content preserved, not deleted, not updated.
        rows = self._rows("AWARD_PERSON")
        self.assertEqual(len(rows), 1)
        self.assertIn("MICHAEL KEVIN OUTTERSON", rows[0]["source_text"])

    def test_award_family_scoping_excludes_other_awards(self) -> None:
        self._seed_award_204713_00133()
        self._seed_award_104949_00002_with_negotiation_and_subaward()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_PERSON"], dry_run=False
        )
        evidence.populate_evidence(
            self.engine, self.embed_fn, "104949-00002", ["RELATED_NEGOTIATION"], dry_run=False
        )
        person_rows = self._rows("AWARD_PERSON")
        negotiation_rows = self._rows("RELATED_NEGOTIATION")
        self.assertEqual(len(person_rows), 1)
        self.assertEqual(person_rows[0]["parent_business_identifier"], "204713-00133")
        self.assertEqual(len(negotiation_rows), 1)
        self.assertEqual(negotiation_rows[0]["parent_business_identifier"], "104949-00002")

    def test_correct_document_type_stored_per_type(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133",
            ["AWARD_PERSON", "AWARD_AMOUNT", "AWARD_COMMENT", "AWARD_TERM"], dry_run=False,
        )
        for expected_type, expected_count in (
            ("AWARD_PERSON", 1), ("AWARD_AMOUNT", 1),
            ("AWARD_COMMENT", 1), ("AWARD_TERM", 1),
        ):
            rows = self._rows(expected_type)
            self.assertEqual(len(rows), expected_count, expected_type)
            for row in rows:
                self.assertEqual(row["document_type"], expected_type)

    def test_citation_metadata_is_populated(self) -> None:
        self._seed_award_204713_00133()
        evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_AMOUNT"], dry_run=False
        )
        rows = self._rows("AWARD_AMOUNT")
        self.assertEqual(rows[0]["source_table"], "archive.award_amount_info")
        self.assertEqual(rows[0]["source_primary_key"], 3195982)
        self.assertEqual(rows[0]["parent_module"], "AWARD")
        self.assertEqual(rows[0]["parent_business_identifier"], "204713-00133")
        self.assertEqual(rows[0]["version_label"], "125")

    def test_real_pinned_fixture_ordinary_award_evidence_types(self) -> None:
        """204713-00133 (award_id 3187665) - the golden fixture - proves
        every ordinary (non-RELATED_*) evidence type end-to-end.
        AWARD_SUMMARY is deliberately excluded - it is not implemented by
        this script (see test_award_summary_is_not_an_approved_type)."""
        self._seed_award_204713_00133()
        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133",
            ["AWARD_VERSION", "AWARD_PERSON", "AWARD_AMOUNT",
             "AWARD_COMMENT", "AWARD_TERM"],
            dry_run=False,
        )
        self.assertEqual(report["failed"], 0)
        self.assertEqual(report["inserted"], 5)
        for document_type in (
            "AWARD_VERSION", "AWARD_PERSON",
            "AWARD_AMOUNT", "AWARD_COMMENT", "AWARD_TERM",
        ):
            self.assertEqual(len(self._rows(document_type)), 1, document_type)

    def test_real_pinned_fixtures_for_all_three_related_types(self) -> None:
        """204713-00001 (RELATED_PROPOSAL) and 104949-00002
        (RELATED_NEGOTIATION + RELATED_SUBAWARD) - the fixtures this
        session's read-only SQL search identified as the minimum
        covering set, since no single Award family has all three
        relationship types."""
        self._seed_award_204713_00001_with_proposal()
        self._seed_award_104949_00002_with_negotiation_and_subaward()

        proposal_report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00001", ["RELATED_PROPOSAL"], dry_run=False
        )
        related_report = evidence.populate_evidence(
            self.engine, self.embed_fn, "104949-00002",
            ["RELATED_NEGOTIATION", "RELATED_SUBAWARD"], dry_run=False,
        )

        self.assertEqual(proposal_report["failed"], 0)
        self.assertEqual(proposal_report["inserted"], 1)
        self.assertEqual(related_report["failed"], 0)
        self.assertEqual(related_report["inserted"], 2)

        proposal_rows = self._rows("RELATED_PROPOSAL")
        self.assertEqual(proposal_rows[0]["exact_record_id"], 1768708)
        self.assertIn("01128961", proposal_rows[0]["source_text"])

        negotiation_rows = self._rows("RELATED_NEGOTIATION")
        self.assertEqual(negotiation_rows[0]["exact_record_id"], 11241)
        self.assertIsNone(negotiation_rows[0]["version_label"])

        subaward_rows = self._rows("RELATED_SUBAWARD")
        self.assertEqual(subaward_rows[0]["exact_record_id"], 11185)
        self.assertIsNone(subaward_rows[0]["version_label"])

    def test_only_approved_document_types_can_be_requested(self) -> None:
        with self.assertRaises(SystemExit):
            evidence.main(["--award-number", "204713-00133", "--document-types", "AWARD_BUDGET", "--dry-run"])

    def test_populate_evidence_rejects_unapproved_type_directly(self) -> None:
        """The allowlist must be enforced inside populate_evidence()
        itself, not only by main()'s argparse layer - so the function is
        safe to call directly (e.g. from a future caller that bypasses
        the CLI) without an unapproved type ever reaching a SQL lookup
        keyed by it."""
        with self.assertRaises(ValueError):
            evidence.populate_evidence(
                self.engine, self.embed_fn, "204713-00133", ["AWARD_BUDGET"], dry_run=True
            )

    def test_award_summary_is_not_an_approved_type(self) -> None:
        """Regression: AWARD_SUMMARY is the pre-existing, Global-Search-
        facing family-level row owned by build_search_embedding.py (see
        that script's own UPSERT_SQL comment and the Phase 1 design's
        Section 3 "Already implemented" annotation for AWARD_SUMMARY).
        This script must never be able to write, update, or hard-delete
        it - enforced at both the CLI and function layers."""
        self.assertNotIn("AWARD_SUMMARY", evidence.APPROVED_DOCUMENT_TYPES)
        self.assertNotIn("AWARD_SUMMARY", evidence.DOCUMENT_TYPE_QUERIES)
        with self.assertRaises(SystemExit):
            evidence.main(["--award-number", "204713-00133", "--document-types", "AWARD_SUMMARY", "--dry-run"])
        with self.assertRaises(ValueError):
            evidence.populate_evidence(
                self.engine, self.embed_fn, "204713-00133", ["AWARD_SUMMARY"], dry_run=True
            )

    def test_related_negotiation_query_returns_award_number(self) -> None:
        """Regression: RELATED_NEGOTIATION's SQL originally omitted
        award_number from its SELECT list entirely, even though
        build_related_negotiation_text() requires row['award_number'] -
        every real invocation would have raised KeyError. Verified by
        running the real query against real Postgres and inspecting the
        returned row's own keys/values, not by reading the source."""
        self._seed_award_104949_00002_with_negotiation_and_subaward()
        with self.engine.connect() as connection:
            rows = connection.execute(
                text(evidence.DOCUMENT_TYPE_QUERIES["RELATED_NEGOTIATION"]),
                {"award_number": "104949-00002"},
            ).mappings().all()
        self.assertEqual(len(rows), 1)
        self.assertIn("award_number", rows[0].keys())
        self.assertEqual(rows[0]["award_number"], "104949-00002")
        # And the builder that consumes this row must not raise KeyError.
        evidence.build_related_negotiation_text(dict(rows[0]))

    def test_related_subaward_query_returns_award_number(self) -> None:
        """Same regression as above, for RELATED_SUBAWARD's SQL and
        build_related_subaward_text()."""
        self._seed_award_104949_00002_with_negotiation_and_subaward()
        with self.engine.connect() as connection:
            rows = connection.execute(
                text(evidence.DOCUMENT_TYPE_QUERIES["RELATED_SUBAWARD"]),
                {"award_number": "104949-00002"},
            ).mappings().all()
        self.assertEqual(len(rows), 1)
        self.assertIn("award_number", rows[0].keys())
        self.assertEqual(rows[0]["award_number"], "104949-00002")
        evidence.build_related_subaward_text(dict(rows[0]))

    def test_colliding_term_ids_do_not_collapse_into_one_row(self) -> None:
        """award_sponsor_term_id and award_report_term_id are independent
        Oracle sequences (see V040's migration comment) - nothing
        guarantees they never share a numeric value. Seeds both source
        tables with the SAME raw ID and proves both evidence rows are
        written distinctly, not silently collapsed by the shared
        AWARD_TERM document_type's unique index."""
        self._seed_award_204713_00133()
        colliding_id = 999999
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.award_report_term "
                    "(award_report_term_id, award_id, award_number, sequence_number, "
                    " report_class_code, report_code, frequency_code) "
                    "VALUES (:id, 3187665, '204713-00133', 125, '3', '21', '6')"
                ),
                {"id": colliding_id},
            )
            connection.execute(
                text(
                    "UPDATE archive.award_sponsor_term SET award_sponsor_term_id = :id "
                    "WHERE award_id = 3187665"
                ),
                {"id": colliding_id},
            )

        report = evidence.populate_evidence(
            self.engine, self.embed_fn, "204713-00133", ["AWARD_TERM"], dry_run=False
        )
        self.assertEqual(report["failed"], 0)
        self.assertEqual(report["inserted"], 2)

        rows = self._rows("AWARD_TERM")
        self.assertEqual(len(rows), 2)
        exact_record_ids = {row["exact_record_id"] for row in rows}
        self.assertEqual(exact_record_ids, {colliding_id, -colliding_id})
        source_primary_keys = {row["source_primary_key"] for row in rows}
        self.assertEqual(source_primary_keys, {colliding_id})


if __name__ == "__main__":
    unittest.main()
