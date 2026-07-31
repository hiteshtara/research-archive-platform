"""Tests for Phase 4A: Award's incremental UPSERT layer (--load-award-id,
--create-batch/--load-batch/--show-batch) - see docs/architecture/ETL_BATCH_FRAMEWORK.md
and the Award domain research this was designed from.

Scoped strictly to the four tables load_awards_from_csv.py's full load
already populates (archive.award_version, archive.award_amount_info,
archive.award_person, archive.award_funding_proposal) plus nine Tier 1
subsystem tables added to the same incremental UPSERT path since each
depends only on award_version(award_id) or a table that itself does:
archive.award_custom_data; archive.award_person_unit,
archive.award_person_credit_split, and
archive.award_person_unit_credit_split (see
docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md);
archive.award_sponsor_term, archive.award_report_term, and
archive.award_report_term_recipient (see
docs/architecture/AWARD_TERMS_DESIGN.md); and
archive.award_sponsor_contact and archive.award_unit_contact (see
docs/architecture/AWARD_CONTACTS_DESIGN.md). No Award Budget,
Reporting, Time and Money, or SAP transmission is touched anywhere in
this file, and Award.basisOfPaymentCode/methodOfPaymentCode are
deliberately not captured (see AWARD_TERMS_DESIGN.md).

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
import re
import unittest
import uuid
from collections import Counter
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

import load_awards_from_csv as award_loader
from archive_etl.pipeline.validation import normalize_column_name
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

    def _read_filtered(
        *, column: str, values, chunk_size: int = 1000
    ) -> pd.DataFrame:
        # Test-only stand-in for the real OracleDataSource.read_filtered:
        # simulates Oracle-side WHERE <column> IN (...) filtering by
        # doing the equivalent pandas filter over the same fixture rows
        # read_batches() would have yielded - production code (see
        # load_awards_from_csv.read_award_number_for_award_id and
        # friends) no longer scans/filters client-side itself, so the
        # mock takes over exactly that responsibility for these tests.
        if not values:
            return pd.DataFrame()
        non_empty = [batch for batch in batches if not batch.empty]
        if not non_empty:
            return pd.DataFrame()
        combined = pd.concat(non_empty, ignore_index=True)
        column_name = column.lower()
        if column_name not in combined.columns:
            return pd.DataFrame()
        mask = combined[column_name].isin(list(values))
        if not mask.any():
            return pd.DataFrame()
        return combined[mask].reset_index(drop=True)

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    stub.read_filtered.side_effect = _read_filtered
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


def _sponsor_term_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_sponsor_term_id": 1201,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "sponsor_term_id": 55,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _report_term_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_report_term_id": 1301,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "report_class_code": "RC1",
        "report_code": "R1",
        "frequency_code": "F1",
        "frequency_base_code": "FB1",
        "osp_distribution_code": "D1",
        "due_date": "2025-06-01",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _report_term_recipient_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_report_term_recipient_id": 1401,
        "award_report_term_id": 1301,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "contact_id": 7001,
        "contact_type_code": "PI",
        "rolodex_id": None,
        "number_of_copies": 2,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _sponsor_contact_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_sponsor_contact_id": 1501,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "rolodex_id": 8001,
        "full_name": "Sponsor Contact",
        "contact_role_code": "PO",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _unit_contact_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_unit_contact_id": 1601,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "person_id": "P456",
        "full_name": "Unit Contact",
        "unit_contact_type": "UNIT_CONTACT",
        "unit_administrator_type_code": "UA",
        "unit_administrator_unit_number": "001",
        "default_unit_contact": "Y",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


# --- SQL/transform contract: SQL output columns vs prepare_* -----------
#
# Bug this guards against: 10_award_report_terms.sql originally selected
# art.AWARD_REPORT_TERMS_ID unaliased. Oracle's real column name for that
# (AWARD_REPORT_TERMS_ID, matching the table name's plural "TERMS") lowercases
# to award_report_terms_id - one letter off from the loader's own
# award_report_term_id (singular, matching the Kuali Java field
# awardReportTermId per repository-award.xml). Every hand-written fixture
# above already uses the *correct* singular name, so those tests alone
# could never catch a SQL-side aliasing mistake - only parsing the actual
# .sql file's real SELECT list can. Do not "fix" this by loosening
# require_columns, synthesizing an id, or falling back to a business key;
# the correct fix is always an alias at the SQL boundary (or, if the
# authoritative mapping disagrees, a rename in the loader) - never a
# validation workaround.

_COMMENT_LINE = re.compile(r"^\s*--")
_SQLPLUS_SET_LINE = re.compile(r"^\s*SET\s+\w+", re.IGNORECASE)


def _split_top_level_commas(text: str) -> list[str]:
    """Split a SELECT column list on commas, but only at paren-depth 0 -
    a naive str.split(",") breaks on expressions like
    NVL(aai.ANTICIPATED_TOTAL_DIRECT, 0) (02_award_amounts.sql), whose
    own internal comma isn't a column separator. Found via the Award
    load-performance benchmark script, which parses every extraction
    file including that one; none of this module's own contract tests
    happened to exercise it, so this latent bug had never been
    triggered here - fixed proactively since it's the same parsing
    logic."""
    parts = []
    depth = 0
    current: list[str] = []
    for char in text:
        if char == "(":
            depth += 1
            current.append(char)
        elif char == ")":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return parts


def _oracle_output_columns(sql_path: Path) -> list[str]:
    """Parse a SELECT ... FROM column list the same way a real Oracle
    cursor.description + normalize_column_name would name each result
    column: an explicit "AS alias" wins, otherwise the part of the
    expression after the last '.', then lowercased/underscored. This is
    independent of load_awards_from_csv.py's own column-name
    assumptions - it only knows how to read the .sql file's literal
    text, so it fails the same way a real Oracle run would if the SQL
    and the loader's expected columns ever drift apart again."""
    lines = [
        line
        for line in sql_path.read_text(encoding="utf-8").splitlines()
        if not _COMMENT_LINE.match(line) and not _SQLPLUS_SET_LINE.match(line)
    ]
    text = "\n".join(lines)
    match = re.search(
        r"SELECT\s+(.*?)\s+FROM\s", text, re.IGNORECASE | re.DOTALL
    )
    if match is None:
        raise AssertionError(f"could not find a SELECT ... FROM in {sql_path}")

    columns = []
    for raw_expr in _split_top_level_commas(match.group(1)):
        expr = raw_expr.strip()
        if not expr:
            continue
        as_match = re.search(r"\bAS\b\s+([A-Za-z0-9_]+)\s*$", expr, re.IGNORECASE)
        name = as_match.group(1) if as_match else expr.split(".")[-1]
        columns.append(normalize_column_name(name))
    return columns


class AwardTermsSqlColumnContractTest(unittest.TestCase):
    """No Postgres, no Oracle - just proves each Award Terms extraction
    SQL file's real output columns satisfy its own prepare_* function's
    required columns. Uses "1" as a placeholder value for every column;
    convert_numeric/convert_dates both use errors="coerce", so any
    placeholder is safe - only column *names*, not values, are under
    test here."""

    def test_sponsor_terms_sql_columns_satisfy_prepare_sponsor_terms(self) -> None:
        columns = _oracle_output_columns(award_loader.SPONSOR_TERMS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_sponsor_terms(dataframe)
        self.assertIn("award_sponsor_term_id", prepared.columns)

    def test_report_terms_sql_columns_satisfy_prepare_report_terms(self) -> None:
        columns = _oracle_output_columns(award_loader.REPORT_TERMS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_report_terms(dataframe)
        self.assertIn("award_report_term_id", prepared.columns)

    def test_report_term_recipients_sql_columns_satisfy_prepare_report_term_recipients(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.REPORT_TERM_RECIPIENTS_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_report_term_recipients(dataframe)
        self.assertIn("award_report_term_recipient_id", prepared.columns)
        self.assertIn("award_report_term_id", prepared.columns)


class AwardContactsSqlColumnContractTest(unittest.TestCase):
    """Same rationale as AwardTermsSqlColumnContractTest - run
    specifically against the two Award Contacts extraction files given
    how recently the 10_award_report_terms.sql aliasing bug was found
    and fixed. Neither AWARD_SPONSOR_CONTACT_ID nor
    AWARD_UNIT_CONTACT_ID has a plural/singular mismatch against its
    table name, but this proves that, rather than assuming it."""

    def test_sponsor_contacts_sql_columns_satisfy_prepare_sponsor_contacts(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.SPONSOR_CONTACTS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_sponsor_contacts(dataframe)
        self.assertIn("award_sponsor_contact_id", prepared.columns)

    def test_unit_contacts_sql_columns_satisfy_prepare_unit_contacts(self) -> None:
        columns = _oracle_output_columns(award_loader.UNIT_CONTACTS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_unit_contacts(dataframe)
        self.assertIn("award_unit_contact_id", prepared.columns)


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
        sponsor_terms: list[dict] | None = None,
        report_terms: list[dict] | None = None,
        report_term_recipients: list[dict] | None = None,
        sponsor_contacts: list[dict] | None = None,
        unit_contacts: list[dict] | None = None,
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
        sponsor_terms_df = pd.DataFrame(sponsor_terms or [])
        report_terms_df = pd.DataFrame(report_terms or [])
        report_term_recipients_df = pd.DataFrame(
            report_term_recipients or []
        )
        sponsor_contacts_df = pd.DataFrame(sponsor_contacts or [])
        unit_contacts_df = pd.DataFrame(unit_contacts or [])

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
            if sql_path == award_loader.SPONSOR_TERMS_ORACLE_SQL:
                return _oracle_batches_stub([sponsor_terms_df])
            if sql_path == award_loader.REPORT_TERMS_ORACLE_SQL:
                return _oracle_batches_stub([report_terms_df])
            if sql_path == award_loader.REPORT_TERM_RECIPIENTS_ORACLE_SQL:
                return _oracle_batches_stub([report_term_recipients_df])
            if sql_path == award_loader.SPONSOR_CONTACTS_ORACLE_SQL:
                return _oracle_batches_stub([sponsor_contacts_df])
            if sql_path == award_loader.UNIT_CONTACTS_ORACLE_SQL:
                return _oracle_batches_stub([unit_contacts_df])
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

    def test_read_award_versions_matching_award_numbers_filters_by_bind_variables(
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

    def test_read_award_children_matching_award_ids_filters_by_bind_variables(
        self,
    ) -> None:
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

    def test_read_award_numbers_for_award_ids_resolves_every_id_in_one_call(
        self,
    ) -> None:
        source = _oracle_batches_stub(
            [
                pd.DataFrame(
                    [
                        _version_row(award_id=1, award_number="A-1"),
                        _version_row(award_id=2, award_number="A-2"),
                        _version_row(award_id=3, award_number="A-3"),
                    ]
                )
            ]
        )
        result = award_loader.read_award_numbers_for_award_ids(source, {1, 2, 999})

        self.assertEqual(result, {1: "A-1", 2: "A-2"})
        self.assertNotIn(999, result)

    def test_read_award_numbers_for_award_ids_returns_empty_dict_for_empty_input(
        self,
    ) -> None:
        source = _oracle_batches_stub([pd.DataFrame([_version_row()])])
        self.assertEqual(
            award_loader.read_award_numbers_for_award_ids(source, set()), {}
        )


# --- _run_load_award_id --------------------------------------------------


class RunLoadAwardIdTest(_AwardPostgresTestCase):
    def test_first_load_inserts_all_thirteen_tables(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            people=[_person_row()],
            proposals=[_proposal_row()],
            custom_data=[_custom_data_row()],
            person_units=[_person_unit_row()],
            person_credit_splits=[_person_credit_split_row()],
            person_unit_credit_splits=[_person_unit_credit_split_row()],
            sponsor_terms=[_sponsor_term_row()],
            report_terms=[_report_term_row()],
            report_term_recipients=[_report_term_recipient_row()],
            sponsor_contacts=[_sponsor_contact_row()],
            unit_contacts=[_unit_contact_row()],
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
        self.assertEqual(report["sponsor_term_inserted"], 1)
        self.assertEqual(report["report_term_inserted"], 1)
        self.assertEqual(report["report_term_recipient_inserted"], 1)
        self.assertEqual(report["sponsor_contact_inserted"], 1)
        self.assertEqual(report["unit_contact_inserted"], 1)

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

        sponsor_term_row = self._row(
            "award_sponsor_term", award_sponsor_term_id=1201
        )
        self.assertEqual(sponsor_term_row["sponsor_term_id"], 55)

        report_term_row = self._row(
            "award_report_term", award_report_term_id=1301
        )
        self.assertEqual(report_term_row["report_class_code"], "RC1")
        self.assertEqual(report_term_row["osp_distribution_code"], "D1")

        report_term_recipient_row = self._row(
            "award_report_term_recipient",
            award_report_term_recipient_id=1401,
        )
        self.assertEqual(report_term_recipient_row["award_report_term_id"], 1301)
        self.assertEqual(report_term_recipient_row["number_of_copies"], 2)

        sponsor_contact_row = self._row(
            "award_sponsor_contact", award_sponsor_contact_id=1501
        )
        self.assertEqual(sponsor_contact_row["full_name"], "Sponsor Contact")
        self.assertEqual(sponsor_contact_row["contact_role_code"], "PO")

        unit_contact_row = self._row(
            "award_unit_contact", award_unit_contact_id=1601
        )
        self.assertEqual(unit_contact_row["person_id"], "P456")
        self.assertEqual(unit_contact_row["default_unit_contact"], "Y")

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
            sponsor_terms=[_sponsor_term_row()],
            report_terms=[_report_term_row()],
            report_term_recipients=[_report_term_recipient_row()],
            sponsor_contacts=[_sponsor_contact_row()],
            unit_contacts=[_unit_contact_row()],
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
        self.assertEqual(report["sponsor_term_unchanged"], 1)
        self.assertEqual(report["report_term_unchanged"], 1)
        self.assertEqual(report["report_term_recipient_unchanged"], 1)
        self.assertEqual(report["sponsor_contact_unchanged"], 1)
        self.assertEqual(report["unit_contact_unchanged"], 1)

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

    def test_report_term_recipient_loads_correctly_when_its_parent_term_is_new(
        self,
    ) -> None:
        # award_report_term_recipient's FK parent (award_report_term) is
        # being inserted for the very first time in this same
        # transaction - proves the load-order decision (report_term
        # before report_term_recipient) actually holds.
        with self._patched_oracle(
            versions=[_version_row()],
            report_terms=[_report_term_row()],
            report_term_recipients=[_report_term_recipient_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["report_term_inserted"], 1)
        self.assertEqual(report["report_term_recipient_inserted"], 1)

        row = self._row(
            "award_report_term_recipient",
            award_report_term_recipient_id=1401,
        )
        self.assertEqual(row["award_report_term_id"], 1301)

    def test_report_term_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], report_terms=[_report_term_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            report_terms=[_report_term_row(report_code="R2")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["report_term_updated"], 1)
        row = self._row("award_report_term", award_report_term_id=1301)
        self.assertEqual(row["report_code"], "R2")

    def test_sponsor_terms_do_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            sponsor_terms=[
                _sponsor_term_row(
                    award_sponsor_term_id=1202,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            sponsor_terms=[_sponsor_term_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_sponsor_term")
        self.assertEqual(total, 2)

    def test_sponsor_contact_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], sponsor_contacts=[_sponsor_contact_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            sponsor_contacts=[_sponsor_contact_row(full_name="Changed Name")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["sponsor_contact_updated"], 1)
        row = self._row("award_sponsor_contact", award_sponsor_contact_id=1501)
        self.assertEqual(row["full_name"], "Changed Name")

    def test_unit_contact_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], unit_contacts=[_unit_contact_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            unit_contacts=[_unit_contact_row(default_unit_contact="N")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["unit_contact_updated"], 1)
        row = self._row("award_unit_contact", award_unit_contact_id=1601)
        self.assertEqual(row["default_unit_contact"], "N")

    def test_unit_contacts_do_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            unit_contacts=[
                _unit_contact_row(
                    award_unit_contact_id=1602,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            unit_contacts=[_unit_contact_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_unit_contact")
        self.assertEqual(total, 2)

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
            sponsor_terms=[_sponsor_term_row()],
            report_terms=[_report_term_row()],
            report_term_recipients=[_report_term_recipient_row()],
            sponsor_contacts=[_sponsor_contact_row()],
            unit_contacts=[_unit_contact_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1, dry_run=True)

        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["custom_data_inserted"], 1)
        self.assertEqual(report["person_unit_inserted"], 1)
        self.assertEqual(report["person_credit_split_inserted"], 1)
        self.assertEqual(report["person_unit_credit_split_inserted"], 1)
        self.assertEqual(report["sponsor_term_inserted"], 1)
        self.assertEqual(report["report_term_inserted"], 1)
        self.assertEqual(report["report_term_recipient_inserted"], 1)
        self.assertEqual(report["sponsor_contact_inserted"], 1)
        self.assertEqual(report["unit_contact_inserted"], 1)

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
        sponsor_term_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_sponsor_term"
        )
        self.assertEqual(sponsor_term_count, 0)
        report_term_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_report_term"
        )
        self.assertEqual(report_term_count, 0)
        sponsor_contact_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_sponsor_contact"
        )
        self.assertEqual(sponsor_contact_count, 0)
        unit_contact_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_unit_contact"
        )
        self.assertEqual(unit_contact_count, 0)
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
            report_terms=[
                _report_term_row(award_report_term_id=1301, award_id=1),
                _report_term_row(
                    award_report_term_id=1302,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            unit_contacts=[
                _unit_contact_row(award_unit_contact_id=1601, award_id=1),
                _unit_contact_row(
                    award_unit_contact_id=1602,
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
        self.assertEqual(report["report_term_inserted"], 2)
        self.assertEqual(report["unit_contact_inserted"], 2)

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
        report_term_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_report_term"
        )
        self.assertEqual(report_term_total, 2)
        unit_contact_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_unit_contact"
        )
        self.assertEqual(unit_contact_total, 2)

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

    def test_reads_each_oracle_table_exactly_once_for_the_whole_batch(self) -> None:
        # The core guarantee of the bulk-batch refactor: every one of
        # the thirteen Award extraction sources is read exactly once
        # for this whole 3-family batch, not once per family (which
        # would be the families x tables scaling this refactor
        # removes). VERSIONS_ORACLE_SQL is legitimately read twice -
        # once to resolve every requested award_id's award_number,
        # once to resolve the batch-wide family version rows - still
        # O(1) per batch, not O(families).
        batch_id = self._create_batch([1, 2, 3])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
                _version_row(award_id=3, award_number="A-0003"),
            ],
            amounts=[
                _amount_row(award_amount_info_id=501, award_id=1),
                _amount_row(
                    award_amount_info_id=502, award_id=2, award_number="A-0002"
                ),
                _amount_row(
                    award_amount_info_id=503, award_id=3, award_number="A-0003"
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            call_paths = [
                call.args[0]
                for call in award_loader.OracleDataSource.call_args_list  # type: ignore[attr-defined]
            ]

        counts = Counter(call_paths)
        self.assertEqual(counts[award_loader.VERSIONS_ORACLE_SQL], 2)
        for sql_path in (
            award_loader.AMOUNTS_ORACLE_SQL,
            award_loader.PEOPLE_ORACLE_SQL,
            award_loader.PROPOSALS_ORACLE_SQL,
            award_loader.CUSTOM_DATA_ORACLE_SQL,
            award_loader.PERSON_UNITS_ORACLE_SQL,
            award_loader.PERSON_CREDIT_SPLITS_ORACLE_SQL,
            award_loader.PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL,
            award_loader.SPONSOR_TERMS_ORACLE_SQL,
            award_loader.REPORT_TERMS_ORACLE_SQL,
            award_loader.REPORT_TERM_RECIPIENTS_ORACLE_SQL,
            award_loader.SPONSOR_CONTACTS_ORACLE_SQL,
            award_loader.UNIT_CONTACTS_ORACLE_SQL,
        ):
            self.assertEqual(
                counts[sql_path],
                1,
                f"{sql_path.name} was read {counts[sql_path]} time(s), expected 1",
            )

    def test_dry_run_persists_nothing_across_the_whole_batch(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["inserted"], 2)
        total = self._scalar("SELECT COUNT(*) FROM archive.award_version")
        self.assertEqual(total, 0)
        load_run_count = self._scalar("SELECT COUNT(*) FROM archive.load_run")
        self.assertEqual(load_run_count, 0)

        # Batch-item status bookkeeping is separate, always-committed
        # bookkeeping, unaffected by the load transaction's rollback -
        # exactly as before the refactor, now scoped to the whole batch.
        item_1 = self._row("etl_batch_item", batch_id=batch_id, entity_key=1)
        self.assertEqual(item_1["status"], "COMPLETED")

    def test_one_bad_family_rolls_back_the_whole_batch(self) -> None:
        # award_id=2's person_unit_credit_split references a
        # person_unit that was never loaded in this batch - a genuine
        # FK violation, deliberately injected to prove the whole batch
        # (including the otherwise-valid award_id=1 family) rolls back
        # together as one unit of work, per the refactor's "treat the
        # batch as one unit of work" transaction design.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            person_unit_credit_splits=[
                _person_unit_credit_split_row(
                    award_person_unit_credit_split_id=1101,
                    award_person_unit_id=99999,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            with self.assertRaises(IntegrityError):
                award_loader._run_load_award_batch(self.engine, batch_id)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_version")
        self.assertEqual(total, 0)


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
