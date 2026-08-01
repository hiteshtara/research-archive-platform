"""Tests for Phase 4A: Award's incremental UPSERT layer (--load-award-id,
--create-batch/--load-batch/--show-batch) - see docs/architecture/ETL_BATCH_FRAMEWORK.md
and the Award domain research this was designed from.

Scoped strictly to the four tables load_awards_from_csv.py's full load
already populates (archive.award_version, archive.award_amount_info,
archive.award_person, archive.award_funding_proposal) plus twenty-five
Tier 1 subsystem tables added to the same incremental UPSERT path
since each depends only on award_version(award_id) or a table that
itself does: archive.award_custom_data; archive.award_person_unit,
archive.award_person_credit_split, and
archive.award_person_unit_credit_split (see
docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md);
archive.award_sponsor_term, archive.award_report_term, and
archive.award_report_term_recipient (see
docs/architecture/AWARD_TERMS_DESIGN.md);
archive.award_sponsor_contact and archive.award_unit_contact (see
docs/architecture/AWARD_CONTACTS_DESIGN.md); archive.award_notepad
(see docs/architecture/AWARD_NOTEPAD_DESIGN.md);
archive.award_closeout, archive.award_payment_schedule, and
archive.award_approved_subaward (see
docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md);
archive.award_cfda, archive.award_cost_share, archive.award_fanda_rate,
archive.award_science_keyword, archive.award_special_review,
archive.award_special_review_exemption,
archive.award_approved_equipment, archive.award_approved_foreign_travel,
and archive.award_subcontracting_budgeted_goals (see
docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md); and
archive.award_comment, confirmed distinct from archive.award_notepad
(see docs/architecture/AWARD_COMMENT_DESIGN.md); and archive.award_extension
and archive.award_cgb, the two confirmed 1:1 Award extension tables (see
docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md); plus the full Award
Time and Money subsystem (archive.award_hierarchy,
archive.time_and_money_document, archive.pending_transaction,
archive.pending_transaction_extension, archive.transaction_detail,
archive.award_amount_transaction,
archive.award_direct_fanda_distribution - see
docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md) and the full Award
Budget subsystem (archive.award_budget, archive.award_budget_limit,
archive.award_budget_period, archive.award_budget_line_item,
archive.award_budget_line_item_calculated_amount,
archive.award_budget_personnel_detail,
archive.award_budget_personnel_calculated_amount,
archive.award_budget_period_summary_calculated_amount - see
docs/architecture/AWARD_BUDGET_DESIGN.md). No SAP transmission is
touched anywhere in this file.

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

    def _read_filtered_any_column(
        *, columns, values, chunk_size: int = 1000
    ) -> pd.DataFrame:
        # Test-only stand-in for OracleDataSource.read_filtered_any_column:
        # simulates Oracle-side WHERE col1 IN (...) OR col2 IN (...) by
        # doing the equivalent pandas filter over the same fixture rows.
        if not values:
            return pd.DataFrame()
        non_empty = [batch for batch in batches if not batch.empty]
        if not non_empty:
            return pd.DataFrame()
        combined = pd.concat(non_empty, ignore_index=True)
        mask = pd.Series(False, index=combined.index)
        for column in columns:
            column_name = column.lower()
            if column_name in combined.columns:
                mask = mask | combined[column_name].isin(list(values))
        if not mask.any():
            return pd.DataFrame()
        return combined[mask].reset_index(drop=True)

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    stub.read_filtered.side_effect = _read_filtered
    stub.read_filtered_any_column.side_effect = _read_filtered_any_column
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
        "basis_of_payment_code": "01",
        "basis_of_payment_description": "Cost Reimbursement",
        "method_of_payment_code": "02",
        "method_of_payment_description": "Letter of Credit",
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
        "transaction_id": 9001,
        "originating_award_version": 0,
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


def _notepad_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_notepad_id": 1701,
        "award_id": 1,
        "award_number": "A-0001",
        "entry_number": 1,
        "note_topic": "Test Topic",
        "comments": "Test comment body",
        "restricted_view": "N",
        "create_timestamp": "2025-01-01 00:00:00",
        "create_user": "kcuser",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _closeout_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_closeout_id": 1801,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "closeout_report_code": "FIN",
        "closeout_report_name": "Final Report",
        "due_date": "2025-06-01",
        "final_submission_date": None,
        "multiple": "N",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _payment_schedule_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_payment_schedule_id": 1901,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "award_report_term_id": None,
        "award_report_term_description": None,
        "due_date": "2025-03-01",
        "amount": 500.00,
        "submit_date": None,
        "submitted_by": None,
        "submitted_by_person_id": None,
        "invoice_number": None,
        "status_description": None,
        "status": "PEND",
        "report_status_code": None,
        "overdue": None,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "source_last_update_timestamp": None,
        "source_last_update_user": None,
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _approved_subaward_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_approved_subaward_id": 2001,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "organization_name": "Test Subrecipient",
        "organization_id": "ORG1",
        "amount": 25000.00,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _cfda_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_cfda_id": 2101,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "cfda_number": "93.701",
        "cfda_description": "Test CFDA Program",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _cost_share_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_cost_share_id": 2201,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "project_period": "1",
        "cost_share_percentage": 10.00,
        "cost_share_type_code": 1,
        "unit_number": "001",
        "source": "Test Source",
        "destination": "Test Destination",
        "commitment_amount": 5000.00,
        "cost_share_met": 5000.00,
        "verification_date": "2025-01-01",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _fanda_rate_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_fanda_rate_id": 2301,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "applicable_fanda_rate": 55.00,
        "fanda_rate_type_code": 1,
        "fiscal_year": "2025",
        "on_campus_flag": "Y",
        "underrecovery_of_indirect_cost": 100.00,
        "source_account": "SRC1",
        "destination_account": "DST1",
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _science_keyword_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_science_keyword_id": 2401,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "science_keyword_code": "SK001",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _special_review_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_special_review_id": 2501,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "special_review_number": 1,
        "special_review_type_code": 1,
        "approval_type_code": 1,
        "protocol_number": "PROTO-001",
        "application_date": "2025-01-01",
        "approval_date": "2025-02-01",
        "expiration_date": "2026-02-01",
        "comments": "Test special review comment",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _special_review_exemption_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_special_review_exemption_id": 2601,
        "award_special_review_id": 2501,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "exemption_type_code": "E1",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _approved_equipment_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_approved_equipment_id": 2701,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "item": "Test Microscope",
        "model": "M-100",
        "vendor": "Test Vendor",
        "amount": 15000.00,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _approved_foreign_travel_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_approved_foreign_travel_id": 2801,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "person_id": "P789",
        "rolodex_id": 42,
        "traveler_name": "Jane Traveler",
        "destination": "Geneva, Switzerland",
        "start_date": "2025-03-01",
        "end_date": "2025-03-10",
        "amount": 3000.00,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _subcontracting_budgeted_goals_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_number": "A-0001",
        "large_business_goal_amount": 10000.00,
        "small_business_goal_amount": 5000.00,
        "woman_owned_goal_amount": 2000.00,
        "eight_a_disadvantage_goal_amount": 1000.00,
        "hub_zone_goal_amount": 500.00,
        "veteran_owned_goal_amount": 750.00,
        "service_disabled_veteran_owned_goal_amount": 250.00,
        "historical_black_college_goal_amount": 300.00,
        "comments": "Test subcontracting goals",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_comment_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_comment_id": 2901,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "comment_type_code": "GEN",
        "checklist_print_flag": "N",
        "comments": "Test award comment body",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_extension_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "proposed_for_transmission_indicator": "N",
        "last_transmission_date": None,
        "child_type": "SUPPLEMENT",
        "child_description": "Test child description",
        "major_project": "Y",
        "arra_code": None,
        "avc_indicator": "N",
        "a133_cluster": None,
        "fringe_not_allowed_indicator": "N",
        "interest_earned": "N",
        "interest_earned_account_number": None,
        "stepped_up_rate": None,
        "bu_bmc_fa_split": None,
        "conference_grant": "N",
        "program_income": "N",
        "stock_award": "N",
        "foreign_currency_award": "N",
        "nce_notification_date": None,
        "clinical_trial_initiated_by": None,
        "ind_ide_responsibility": None,
        "clinical_trial_registration_date": None,
        "spuds_record_number": None,
        "walker_source_number": None,
        "prime_sponsor_award_id": None,
        "grant_number": None,
        "federal_clinical_trial": "N",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_cgb_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "additional_forms_required": "N",
        "auto_approve_invoice": "N",
        "stop_work": "N",
        "min_invoice_amount": 100.00,
        "invoicing_option": "MONTHLY",
        "dunning_campaign_id": None,
        "last_billed_date": "2025-01-01",
        "previous_last_billed_date": None,
        "final_bill": "N",
        "amount_to_draw": 5000.00,
        "letter_of_credit_review_indicator": "N",
        "invoice_document_status": "PEND",
        "loc_creation_type": None,
        "suspend_invoicing": "N",
        "bill_freq_cd": "M",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_hierarchy_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_hierarchy_id": 10001,
        "root_award_number": "A-0001",
        "award_number": "A-0001",
        "parent_award_number": "000000-00000",
        "originating_award_number": "A-0001",
        "active": "Y",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _time_and_money_document_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "document_number": "TNM-1",
        "award_number": "A-0001",
        "document_status": "FINAL",
        "creation_date": "2025-01-01 00:00:00",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _pending_transaction_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "transaction_id": 9001,
        "document_number": "TNM-1",
        "source_award_number": "000000-00000",
        "destination_award_number": "A-0001",
        "obligated_amount": 5000.00,
        "obligated_direct_amount": 4000.00,
        "obligated_indirect_amount": 1000.00,
        "anticipated_amount": 5000.00,
        "anticipated_direct_amount": 4000.00,
        "anticipated_indirect_amount": 1000.00,
        "comments": "Test pending transaction",
        "processed_flag": "Y",
        "single_node_transaction": "Y",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _pending_transaction_extension_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "transaction_id": 9001,
        "budget_period": "1",
        "source_award_number": "000000-00000",
        "destination_award_number": "A-0001",
    }
    row.update(overrides)
    return row


def _transaction_detail_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "transaction_detail_id": 11001,
        "award_number": "A-0001",
        "sequence_number": 0,
        "transaction_id": 9001,
        "time_and_money_document_number": "TNM-1",
        "source_award_number": "000000-00000",
        "destination_award_number": "A-0001",
        "obligated_amount": 5000.00,
        "obligated_direct_amount": 4000.00,
        "obligated_indirect_amount": 1000.00,
        "anticipated_amount": 5000.00,
        "anticipated_direct_amount": 4000.00,
        "anticipated_indirect_amount": 1000.00,
        "comments": "Test transaction detail",
        "transaction_detail_type": "PRIMARY",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_amount_transaction_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_amount_transaction_id": 12001,
        "award_number": "A-0001",
        "document_number": "TNM-1",
        "transaction_type_code": "1",
        "transaction_type_description": "New",
        "notice_date": "2025-01-01",
        "comments": "Test award amount transaction",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_direct_fanda_distribution_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_direct_fanda_distribution_id": 13001,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "amount_sequence_number": 1,
        "award_amount_info_id": 501,
        "budget_period": 1,
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "direct_cost": 4000.00,
        "indirect_cost": 1000.00,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_id": 14001,
        "award_id": 1,
        "document_number": "BUD-1",
        "award_budget_status_code": "F",
        "award_budget_status_description": "Final",
        "award_budget_type_code": "1",
        "award_budget_type_description": "Original",
        "budget_version_number": 1,
        "name": "Test Budget",
        "description": "Test budget description",
        "budget_initiator": "kcuser",
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "total_cost": 110.0,
        "total_direct_cost": 100.0,
        "total_indirect_cost": 10.0,
        "total_cost_limit": 200.0,
        "cost_sharing_amount": 0.0,
        "underrecovery_amount": 0.0,
        "residual_funds": 0.0,
        "obligated_amount": 110.0,
        "obligated_total": 110.0,
        "oh_rate_class_code": "OC",
        "oh_rate_type_code": "OT",
        "ur_rate_class_code": "UC",
        "modular_budget_flag": "N",
        "on_off_campus_flag": "N",
        "submit_cost_sharing_flag": "N",
        "parent_document_type_code": "AWD",
        "budget_adjustment_document_number": None,
        "comments": "Test comments",
        "budget_justification": "Test justification",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_period_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_period_id": 14201,
        "budget_id": 14001,
        "award_id": 1,
        "budget_period": 1,
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "total_cost": 110.0,
        "total_direct_cost": 100.0,
        "total_indirect_cost": 10.0,
        "total_cost_limit": 200.0,
        "cost_sharing_amount": 0.0,
        "underrecovery_amount": 0.0,
        "number_of_participants": 2,
        "obligated_amount": 110.0,
        "total_fringe_amount": 5.0,
        "fringe_overridden": "N",
        "f_and_a_overridden": "N",
        "comments": "Test period comments",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_line_item_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_line_item_id": 14301,
        "budget_period_id": 14201,
        "budget_id": 14001,
        "award_id": 1,
        "budget_period": 1,
        "line_item_number": 1,
        "budget_category_code": "PERS",
        "cost_element": "6000",
        "line_item_description": "Test line item",
        "group_name": "Personnel",
        "based_on_line_item": None,
        "line_item_sequence": 1,
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "line_item_cost": 100.0,
        "cost_sharing_amount": 0.0,
        "underrecovery_amount": 0.0,
        "obligated_amount": 100.0,
        "quantity": 1,
        "on_off_campus_flag": "N",
        "apply_in_rate_flag": "Y",
        "submit_cost_sharing_flag": "N",
        "formulated_cost_element_flag": "N",
        "subaward_number": None,
        "hierarchy_proposal_number": None,
        "hidden_in_hierarchy": "N",
        "budget_justification": "Test line item justification",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_line_item_calculated_amount_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_line_item_calculated_amount_id": 14401,
        "budget_line_item_id": 14301,
        "budget_period_id": 14201,
        "budget_id": 14001,
        "award_id": 1,
        "budget_period": 1,
        "line_item_number": 1,
        "rate_class_code": "OVERHEAD",
        "rate_type_code": "OH1",
        "rate_type_description": "On Campus Overhead",
        "apply_rate_flag": "Y",
        "calculated_cost": 10.0,
        "calculated_cost_sharing": 0.0,
        "obligated_amount": 10.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_personnel_detail_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_personnel_line_item_id": 14501,
        "budget_line_item_id": 14301,
        "budget_period_id": 14201,
        "budget_id": 14001,
        "award_id": 1,
        "budget_period": 1,
        "line_item_number": 1,
        "person_number": 1,
        "person_sequence_number": 1,
        "person_id": "P123",
        "job_code": "FAC1",
        "period_type_code": "AC",
        "line_item_description": "Test personnel line item",
        "sequence_number": 1,
        "start_date": "2025-01-01",
        "end_date": "2025-12-31",
        "salary_requested": 100.0,
        "percent_charged": 10.0,
        "percent_effort": 10.0,
        "cost_sharing_percent": 0.0,
        "cost_sharing_amount": 0.0,
        "underrecovery_amount": 0.0,
        "obligated_amount": 100.0,
        "on_off_campus_flag": "N",
        "apply_in_rate_flag": "Y",
        "budget_justification": "Test personnel justification",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_personnel_calculated_amount_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_personnel_calculated_amount_id": 14601,
        "budget_personnel_line_item_id": 14501,
        "budget_period_id": 14201,
        "budget_id": 14001,
        "award_id": 1,
        "budget_period": 1,
        "line_item_number": 1,
        "person_number": 1,
        "rate_class_code": "OVERHEAD",
        "rate_type_code": "OH1",
        "rate_type_description": "On Campus Overhead",
        "apply_rate_flag": "Y",
        "calculated_cost": 10.0,
        "calculated_cost_sharing": 0.0,
        "obligated_amount": 10.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_period_summary_calculated_amount_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_budget_period_summary_calculated_amount_id": 14701,
        "budget_period_id": 14201,
        "award_id": 1,
        "cost_element": "6000",
        "on_off_campus_flag": "N",
        "rate_class_type": "E",
        "calculated_cost": 5.0,
        "calculated_cost_sharing": 0.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_limit_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_limit_id": 14101,
        "award_id": 1,
        "budget_id": 14001,
        "limit_type_code": "T",
        "limit_amount": 200.0,
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_budget_person_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "budget_id": 14001,
        "person_sequence_number": 1,
        "award_id": 1,
        "effective_date": "2025-01-01",
        "job_code": "FAC1",
        "non_employee_flag": "N",
        "person_id": "P123",
        "appointment_type_code": "AC",
        "rolodex_id": None,
        "tbn_id": None,
        "calculation_base": 100000.00,
        "person_name": "Jane Researcher",
        "salary_anniversary_date": "2025-07-01",
        "hierarchy_proposal_number": None,
        "hidden_in_hierarchy": "N",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_transferring_sponsor_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "award_transferring_sponsor_id": 14801,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "sponsor_code": "NIH",
        "sponsor_name": "National Institutes of Health",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_transmission_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "transmission_id": 15001,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "initiator_id": "kcuser",
        "transmitter_id": "sapuser",
        "success_indicator": "Y",
        "transmission_date": "2025-01-01",
        "sent_data": (
            '<SI_KCRMPROCESS_OUTBOUND xmlns="urn:sap">'
            '<Header sponsor="NIH &amp; Co">Grant &lt;A-0001&gt;</Header>'
            "</SI_KCRMPROCESS_OUTBOUND>"
        ),
        "returned_data": '<Response><Status code="0">SUCCESS</Status></Response>',
        "basis_of_payment_code": "01",
        "account_type_code": 1,
        "sponsor_code": "NIH",
        "method_of_payment_code": "02",
        "document_number": "DOC-0001",
        "update_timestamp": "2025-01-01 00:00:00",
        "update_user": "kcuser",
        "ver_nbr": 1,
    }
    row.update(overrides)
    return row


def _award_transmission_child_row(**overrides: object) -> dict:
    row: dict[str, object] = {
        "transmission_child_id": 15101,
        "transmission_id": 15001,
        "award_id": 1,
        "award_number": "A-0001",
        "sequence_number": 0,
        "parent_document_number": "DOC-0001",
        "child_document_number": "DOC-0001-C1",
        "lead_unit_number": "001",
        "child_type": "SPONSORED_PROGRAM",
        "overhead_key": "MTDC",
        "base_code": "01",
        "off_campus": "N",
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


class AwardNotepadSqlColumnContractTest(unittest.TestCase):
    """Same rationale as AwardTermsSqlColumnContractTest -
    AWARD_NOTEPAD_ID has no plural/singular mismatch against its table
    name, but this proves that against the real .sql file rather than
    assuming it."""

    def test_notepad_sql_columns_satisfy_prepare_notepad(self) -> None:
        columns = _oracle_output_columns(award_loader.NOTEPAD_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_notepad(dataframe)
        self.assertIn("award_notepad_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("entry_number", prepared.columns)


class AwardReportingSubawardSummarySqlColumnContractTest(unittest.TestCase):
    """Same rationale as AwardTermsSqlColumnContractTest - run
    specifically against the three Award Reporting/Subaward Summary
    extraction files. 16_award_payment_schedule.sql is the one most
    worth double-checking here: it aliases AWARD_REPORT_TERM_DESC and
    LAST_UPDATE_TIMESTAMP/LAST_UPDATE_USER to their archive column
    names directly at the SQL boundary (see
    docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md), so
    this proves those aliases actually parse and produce the columns
    prepare_payment_schedule expects, rather than assuming it."""

    def test_closeout_sql_columns_satisfy_prepare_closeout(self) -> None:
        columns = _oracle_output_columns(award_loader.CLOSEOUT_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_closeout(dataframe)
        self.assertIn("award_closeout_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)

    def test_payment_schedule_sql_columns_satisfy_prepare_payment_schedule(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.PAYMENT_SCHEDULE_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_payment_schedule(dataframe)
        self.assertIn("award_payment_schedule_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_report_term_description", prepared.columns)
        self.assertIn("source_last_update_timestamp", prepared.columns)
        self.assertIn("source_last_update_user", prepared.columns)

    def test_approved_subaward_sql_columns_satisfy_prepare_approved_subaward(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.APPROVED_SUBAWARD_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_approved_subaward(dataframe)
        self.assertIn("award_approved_subaward_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)


class AwardSpecialApprovalsComplianceSqlColumnContractTest(unittest.TestCase):
    """Same rationale as AwardTermsSqlColumnContractTest - run
    specifically against the nine Award Special Approvals and
    Compliance extraction files. 20_award_fanda_rate.sql and
    23_award_special_review_exemption.sql are the ones most worth
    double-checking here: both alias Oracle's literal "IDC"/
    "AWARD_EXEMPT_NUMBER" naming to their authoritative Java-side
    "fanda"/"special_review_exemption" names, and
    21_award_science_keyword.sql/22_award_special_review.sql/
    23_award_special_review_exemption.sql all denormalize
    award_number/sequence_number via a JOIN rather than a native
    column - see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md."""

    def test_cfda_sql_columns_satisfy_prepare_cfda(self) -> None:
        columns = _oracle_output_columns(award_loader.CFDA_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_cfda(dataframe)
        self.assertIn("award_cfda_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)

    def test_cost_share_sql_columns_satisfy_prepare_cost_share(self) -> None:
        # FISCAL_YEAR is deliberately NOT selected by
        # 19_award_cost_share.sql - real BU Oracle has no such column
        # on AWARD_COST_SHARE, despite the generic Kuali source tree's
        # bootstrap DDL showing one. See
        # docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
        columns = _oracle_output_columns(award_loader.COST_SHARE_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_cost_share(dataframe)
        self.assertIn("award_cost_share_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("verification_date", prepared.columns)
        self.assertNotIn("fiscal_year", prepared.columns)

    def test_fanda_rate_sql_columns_satisfy_prepare_fanda_rate(self) -> None:
        columns = _oracle_output_columns(award_loader.FANDA_RATE_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_fanda_rate(dataframe)
        self.assertIn("award_fanda_rate_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("applicable_fanda_rate", prepared.columns)
        self.assertIn("fanda_rate_type_code", prepared.columns)

    def test_science_keyword_sql_columns_satisfy_prepare_science_keyword(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.SCIENCE_KEYWORD_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_science_keyword(dataframe)
        self.assertIn("award_science_keyword_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("sequence_number", prepared.columns)

    def test_special_review_sql_columns_satisfy_prepare_special_review(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.SPECIAL_REVIEW_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_special_review(dataframe)
        self.assertIn("award_special_review_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("special_review_number", prepared.columns)

    def test_special_review_exemption_sql_columns_satisfy_prepare_special_review_exemption(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_special_review_exemption(dataframe)
        self.assertIn("award_special_review_exemption_id", prepared.columns)
        self.assertIn("award_special_review_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)

    def test_approved_equipment_sql_columns_satisfy_prepare_approved_equipment(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.APPROVED_EQUIPMENT_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_approved_equipment(dataframe)
        self.assertIn("award_approved_equipment_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)

    def test_approved_foreign_travel_sql_columns_satisfy_prepare_approved_foreign_travel(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.APPROVED_FOREIGN_TRAVEL_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_approved_foreign_travel(dataframe)
        self.assertIn("award_approved_foreign_travel_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)

    def test_subcontracting_goals_sql_columns_satisfy_prepare_subcontracting_goals(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_subcontracting_budgeted_goals(dataframe)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("historical_black_college_goal_amount", prepared.columns)


class AwardCommentSqlColumnContractTest(unittest.TestCase):
    """Same rationale as AwardTermsSqlColumnContractTest -
    AWARD_COMMENT_ID has no plural/singular mismatch against its table
    name, but this proves that against the real .sql file rather than
    assuming it."""

    def test_award_comment_sql_columns_satisfy_prepare_award_comments(self) -> None:
        columns = _oracle_output_columns(award_loader.COMMENT_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_comments(dataframe)
        self.assertIn("award_comment_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)


class AwardExtensionCgbSqlColumnContractTest(unittest.TestCase):
    """Proves the aliased columns (STEPPED_UP_RATE/BILL_FREQ_CD etc.)
    actually reach prepare_award_extension/prepare_award_cgb against the
    real .sql files, rather than assuming the alias was spelled right."""

    def test_award_extension_sql_columns_satisfy_prepare_award_extension(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.EXTENSION_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_extension(dataframe)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("sequence_number", prepared.columns)
        self.assertIn("stepped_up_rate", prepared.columns)

    def test_award_cgb_sql_columns_satisfy_prepare_award_cgb(self) -> None:
        columns = _oracle_output_columns(award_loader.CGB_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_cgb(dataframe)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("sequence_number", prepared.columns)
        self.assertIn("additional_forms_required", prepared.columns)
        self.assertIn("bill_freq_cd", prepared.columns)


class AwardBasisMethodOfPaymentSqlColumnContractTest(unittest.TestCase):
    """Proves BASIS_OF_PAYMENT_CODE/BASIS_OF_PAYMENT_DESCRIPTION/
    METHOD_OF_PAYMENT_CODE/METHOD_OF_PAYMENT_DESCRIPTION actually reach
    prepare_versions against the real 01_award_versions.sql file, the
    same discipline every other aliased/joined column in this bundle
    gets."""

    def test_versions_sql_columns_satisfy_prepare_versions(self) -> None:
        columns = _oracle_output_columns(award_loader.VERSIONS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_versions(dataframe)
        self.assertIn("basis_of_payment_code", prepared.columns)
        self.assertIn("basis_of_payment_description", prepared.columns)
        self.assertIn("method_of_payment_code", prepared.columns)
        self.assertIn("method_of_payment_description", prepared.columns)


class AwardTimeAndMoneySqlColumnContractTest(unittest.TestCase):
    """Proves every Time and Money table's real .sql output actually
    reaches its prepare_* function - most importantly, that
    AWARD_AMOUNT_TRANSACTION's own confusingly-named VARCHAR2
    "TRANSACTION_ID" column is aliased to document_number at the SQL
    boundary and never reaches prepare_award_amount_transaction under
    the name "transaction_id" - the numeric/character TRANSACTION_ID
    distinction this whole bundle exists to preserve. See
    docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md."""

    def test_amounts_sql_columns_include_transaction_id_and_originating_version(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.AMOUNTS_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_amounts(dataframe)
        self.assertIn("transaction_id", prepared.columns)
        self.assertIn("originating_award_version", prepared.columns)

    def test_hierarchy_sql_columns_satisfy_prepare_award_hierarchy(self) -> None:
        columns = _oracle_output_columns(award_loader.HIERARCHY_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_hierarchy(dataframe)
        self.assertIn("award_hierarchy_id", prepared.columns)
        self.assertIn("root_award_number", prepared.columns)
        self.assertIn("award_number", prepared.columns)
        self.assertIn("parent_award_number", prepared.columns)
        self.assertIn("originating_award_number", prepared.columns)
        self.assertIn("active", prepared.columns)

    def test_tnm_document_sql_columns_satisfy_prepare_time_and_money_document(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.TIME_AND_MONEY_DOCUMENT_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_time_and_money_document(dataframe)
        self.assertIn("document_number", prepared.columns)
        self.assertIn("root_award_number", prepared.columns)
        self.assertIn("document_status", prepared.columns)
        self.assertIn("creation_date", prepared.columns)

    def test_pending_transaction_sql_columns_satisfy_prepare_pending_transaction(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.PENDING_TRANSACTION_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_pending_transaction(dataframe)
        self.assertIn("transaction_id", prepared.columns)
        self.assertIn("source_award_number", prepared.columns)
        self.assertIn("destination_award_number", prepared.columns)

    def test_pending_transaction_extension_sql_columns_include_join_denormalized_award_numbers(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.PENDING_TRANSACTION_EXTENSION_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_pending_transaction_extension(dataframe)
        self.assertIn("transaction_id", prepared.columns)
        self.assertIn("budget_period", prepared.columns)
        self.assertIn("source_award_number", columns_lower := [c.lower() for c in columns])
        self.assertIn("destination_award_number", columns_lower)

    def test_transaction_detail_sql_columns_satisfy_prepare_transaction_detail(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.TRANSACTION_DETAIL_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_transaction_detail(dataframe)
        self.assertIn("transaction_id", prepared.columns)
        self.assertIn("time_and_money_document_number", prepared.columns)
        self.assertIn("transaction_detail_type", prepared.columns)

    def test_award_amount_transaction_sql_transaction_id_becomes_document_number(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.AWARD_AMOUNT_TRANSACTION_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_amount_transaction(dataframe)
        self.assertIn("document_number", prepared.columns)
        self.assertNotIn("transaction_id", prepared.columns)
        self.assertIn("transaction_type_code", prepared.columns)
        self.assertIn("transaction_type_description", prepared.columns)

    def test_fanda_distribution_sql_columns_satisfy_prepare_function(self) -> None:
        columns = _oracle_output_columns(
            award_loader.AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_direct_fanda_distribution(dataframe)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("award_amount_info_id", prepared.columns)
        self.assertIn("budget_period", prepared.columns)


class AwardBudgetSqlColumnContractTest(unittest.TestCase):
    """Proves every Award Budget table's real .sql output actually reaches
    its prepare_* function, including every Oracle-name-to-Java-name
    alias found during research (BUDGET_PERIOD_NUMBER -> budget_period_id,
    IS_FORMULATED_COST_ELELMENT -> formulated_cost_element_flag,
    HIDE_IN_HIERARCHY -> hidden_in_hierarchy, BUDGET_NAME -> name,
    LIMIT_TYPE -> limit_type_code, PERIOD_TYPE -> period_type_code,
    SUBMIT_COST_SHARING -> submit_cost_sharing_flag,
    BUDGET_ADJUSTMENT_DOC_NBR -> budget_adjustment_document_number). See
    docs/architecture/AWARD_BUDGET_DESIGN.md."""

    def test_budget_sql_columns_satisfy_prepare_award_budget(self) -> None:
        columns = _oracle_output_columns(award_loader.BUDGET_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget(dataframe)
        self.assertIn("budget_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("document_number", prepared.columns)
        self.assertIn("name", prepared.columns)
        self.assertIn("submit_cost_sharing_flag", prepared.columns)
        self.assertIn("budget_adjustment_document_number", prepared.columns)

    def test_budget_period_sql_columns_satisfy_prepare_award_budget_period(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.BUDGET_PERIOD_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_period(dataframe)
        self.assertIn("budget_period_id", prepared.columns)
        self.assertIn("budget_id", prepared.columns)
        self.assertIn("number_of_participants", prepared.columns)

    def test_budget_line_item_sql_columns_satisfy_prepare_award_budget_line_item(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.BUDGET_LINE_ITEM_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_line_item(dataframe)
        self.assertIn("budget_line_item_id", prepared.columns)
        self.assertIn("budget_period_id", prepared.columns)
        self.assertIn("submit_cost_sharing_flag", prepared.columns)
        self.assertIn("formulated_cost_element_flag", prepared.columns)
        self.assertIn("hidden_in_hierarchy", prepared.columns)

    def test_budget_line_item_calculated_amount_sql_columns_satisfy_prepare(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_line_item_calculated_amount(
            dataframe
        )
        self.assertIn("budget_line_item_calculated_amount_id", prepared.columns)
        self.assertIn("budget_line_item_id", prepared.columns)
        self.assertIn("rate_type_description", prepared.columns)

    def test_budget_personnel_detail_sql_columns_satisfy_prepare(self) -> None:
        columns = _oracle_output_columns(
            award_loader.BUDGET_PERSONNEL_DETAIL_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_personnel_detail(dataframe)
        self.assertIn("budget_personnel_line_item_id", prepared.columns)
        self.assertIn("period_type_code", prepared.columns)
        self.assertIn("person_sequence_number", prepared.columns)

    def test_budget_personnel_calculated_amount_sql_columns_satisfy_prepare(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_personnel_calculated_amount(
            dataframe
        )
        self.assertIn("budget_personnel_calculated_amount_id", prepared.columns)
        self.assertIn("budget_personnel_line_item_id", prepared.columns)

    def test_budget_period_summary_calculated_amount_sql_columns_satisfy_prepare(
        self,
    ) -> None:
        columns = _oracle_output_columns(
            award_loader.BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = (
            award_loader.prepare_award_budget_period_summary_calculated_amount(
                dataframe
            )
        )
        self.assertIn(
            "award_budget_period_summary_calculated_amount_id", prepared.columns
        )
        self.assertIn("budget_period_id", prepared.columns)
        self.assertIn("rate_class_type", prepared.columns)

    def test_budget_limit_sql_columns_satisfy_prepare_award_budget_limit(
        self,
    ) -> None:
        columns = _oracle_output_columns(award_loader.BUDGET_LIMIT_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_limit(dataframe)
        self.assertIn("budget_limit_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("limit_type_code", prepared.columns)

    def test_budget_person_sql_columns_satisfy_prepare_award_budget_person(
        self,
    ) -> None:
        # Proves the join through BUDGET_PERSONS -> BUDGET ->
        # AWARD_BUDGET_EXT actually reaches prepare_award_budget_person,
        # and that PROPOSAL_NUMBER/VERSION_NUMBER (real DDL columns with
        # no OJB field-descriptor - see AWARD_COMPLETENESS_REPORT.md)
        # are never selected in the first place.
        columns = _oracle_output_columns(award_loader.BUDGET_PERSON_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_budget_person(dataframe)
        self.assertIn("budget_id", prepared.columns)
        self.assertIn("person_sequence_number", prepared.columns)
        self.assertIn("calculation_base", prepared.columns)
        self.assertIn("salary_anniversary_date", prepared.columns)
        self.assertIn("appointment_type_code", prepared.columns)
        self.assertNotIn("proposal_number", prepared.columns)
        self.assertNotIn("version_number", prepared.columns)

    def test_transferring_sponsor_sql_columns_satisfy_prepare(self) -> None:
        columns = _oracle_output_columns(
            award_loader.TRANSFERRING_SPONSOR_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_transferring_sponsor(dataframe)
        self.assertIn("award_transferring_sponsor_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("sponsor_code", prepared.columns)
        self.assertIn("sponsor_name", prepared.columns)

    def test_award_transmission_sql_columns_satisfy_prepare(self) -> None:
        columns = _oracle_output_columns(award_loader.AWARD_TRANSMISSION_ORACLE_SQL)
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_transmission(dataframe)
        self.assertIn("transmission_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("sent_data", prepared.columns)
        self.assertIn("returned_data", prepared.columns)

    def test_award_transmission_child_sql_columns_satisfy_prepare(self) -> None:
        columns = _oracle_output_columns(
            award_loader.AWARD_TRANSMISSION_CHILD_ORACLE_SQL
        )
        dataframe = pd.DataFrame([{column: "1" for column in columns}])
        prepared = award_loader.prepare_award_transmission_child(dataframe)
        self.assertIn("transmission_child_id", prepared.columns)
        self.assertIn("transmission_id", prepared.columns)
        self.assertIn("award_id", prepared.columns)
        self.assertIn("overhead_key", prepared.columns)
        self.assertIn("base_code", prepared.columns)
        self.assertIn("off_campus", prepared.columns)

    def test_budget_person_sql_restricts_to_award_budgets_via_join_chain(
        self,
    ) -> None:
        # BUDGET_PERSONS is shared with Proposal Development and has no
        # Award-specific discriminator column of its own - a pandas
        # mock cannot exercise a real Oracle JOIN's filtering behavior,
        # so this asserts directly against the extraction SQL text that
        # the required BUDGET_PERSONS -> BUDGET -> AWARD_BUDGET_EXT
        # join chain is actually present, not just documented. This is
        # what excludes Proposal Development's own BUDGET_PERSONS rows
        # (there is no column on BUDGET_PERSONS/BUDGET that says "this
        # one belongs to an Award budget" - only the join does).
        sql_text = award_loader.BUDGET_PERSON_ORACLE_SQL.read_text()
        self.assertIn("FROM BUDGET_PERSONS", sql_text)
        self.assertIn("JOIN BUDGET ", sql_text)
        self.assertIn("JOIN AWARD_BUDGET_EXT", sql_text)
        # And the join must resolve AWARD_ID for filtering, since
        # BUDGET_PERSONS carries no AWARD_ID column of its own.
        self.assertIn("abe.AWARD_ID", sql_text)


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
        notepad: list[dict] | None = None,
        closeout: list[dict] | None = None,
        payment_schedule: list[dict] | None = None,
        approved_subaward: list[dict] | None = None,
        cfda: list[dict] | None = None,
        cost_share: list[dict] | None = None,
        fanda_rate: list[dict] | None = None,
        science_keyword: list[dict] | None = None,
        special_review: list[dict] | None = None,
        special_review_exemption: list[dict] | None = None,
        approved_equipment: list[dict] | None = None,
        approved_foreign_travel: list[dict] | None = None,
        subcontracting_budgeted_goals: list[dict] | None = None,
        comment: list[dict] | None = None,
        extension: list[dict] | None = None,
        cgb: list[dict] | None = None,
        hierarchy: list[dict] | None = None,
        tnm_document: list[dict] | None = None,
        pending_transaction: list[dict] | None = None,
        pending_transaction_extension: list[dict] | None = None,
        transaction_detail: list[dict] | None = None,
        award_amount_transaction: list[dict] | None = None,
        fanda_distribution: list[dict] | None = None,
        budget: list[dict] | None = None,
        budget_limit: list[dict] | None = None,
        budget_period: list[dict] | None = None,
        budget_line_item: list[dict] | None = None,
        budget_period_summary_calculated_amount: list[dict] | None = None,
        budget_line_item_calculated_amount: list[dict] | None = None,
        budget_personnel_detail: list[dict] | None = None,
        budget_personnel_calculated_amount: list[dict] | None = None,
        budget_person: list[dict] | None = None,
        transferring_sponsor: list[dict] | None = None,
        award_transmission: list[dict] | None = None,
        award_transmission_child: list[dict] | None = None,
        award_ids: list[dict] | None = None,
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
        notepad_df = pd.DataFrame(notepad or [])
        closeout_df = pd.DataFrame(closeout or [])
        payment_schedule_df = pd.DataFrame(payment_schedule or [])
        approved_subaward_df = pd.DataFrame(approved_subaward or [])
        cfda_df = pd.DataFrame(cfda or [])
        cost_share_df = pd.DataFrame(cost_share or [])
        fanda_rate_df = pd.DataFrame(fanda_rate or [])
        science_keyword_df = pd.DataFrame(science_keyword or [])
        special_review_df = pd.DataFrame(special_review or [])
        special_review_exemption_df = pd.DataFrame(special_review_exemption or [])
        approved_equipment_df = pd.DataFrame(approved_equipment or [])
        approved_foreign_travel_df = pd.DataFrame(approved_foreign_travel or [])
        subcontracting_budgeted_goals_df = pd.DataFrame(
            subcontracting_budgeted_goals or []
        )
        comment_df = pd.DataFrame(comment or [])
        extension_df = pd.DataFrame(extension or [])
        cgb_df = pd.DataFrame(cgb or [])
        hierarchy_df = pd.DataFrame(hierarchy or [])
        tnm_document_df = pd.DataFrame(tnm_document or [])
        pending_transaction_df = pd.DataFrame(pending_transaction or [])
        pending_transaction_extension_df = pd.DataFrame(
            pending_transaction_extension or []
        )
        transaction_detail_df = pd.DataFrame(transaction_detail or [])
        award_amount_transaction_df = pd.DataFrame(award_amount_transaction or [])
        fanda_distribution_df = pd.DataFrame(fanda_distribution or [])
        budget_df = pd.DataFrame(budget or [])
        budget_limit_df = pd.DataFrame(budget_limit or [])
        budget_period_df = pd.DataFrame(budget_period or [])
        budget_line_item_df = pd.DataFrame(budget_line_item or [])
        budget_period_summary_calculated_amount_df = pd.DataFrame(
            budget_period_summary_calculated_amount or []
        )
        budget_line_item_calculated_amount_df = pd.DataFrame(
            budget_line_item_calculated_amount or []
        )
        budget_personnel_detail_df = pd.DataFrame(budget_personnel_detail or [])
        budget_personnel_calculated_amount_df = pd.DataFrame(
            budget_personnel_calculated_amount or []
        )
        budget_person_df = pd.DataFrame(budget_person or [])
        transferring_sponsor_df = pd.DataFrame(transferring_sponsor or [])
        award_transmission_df = pd.DataFrame(award_transmission or [])
        award_transmission_child_df = pd.DataFrame(award_transmission_child or [])
        award_ids_df = pd.DataFrame(award_ids or [])

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
            if sql_path == award_loader.NOTEPAD_ORACLE_SQL:
                return _oracle_batches_stub([notepad_df])
            if sql_path == award_loader.CLOSEOUT_ORACLE_SQL:
                return _oracle_batches_stub([closeout_df])
            if sql_path == award_loader.PAYMENT_SCHEDULE_ORACLE_SQL:
                return _oracle_batches_stub([payment_schedule_df])
            if sql_path == award_loader.APPROVED_SUBAWARD_ORACLE_SQL:
                return _oracle_batches_stub([approved_subaward_df])
            if sql_path == award_loader.CFDA_ORACLE_SQL:
                return _oracle_batches_stub([cfda_df])
            if sql_path == award_loader.COST_SHARE_ORACLE_SQL:
                return _oracle_batches_stub([cost_share_df])
            if sql_path == award_loader.FANDA_RATE_ORACLE_SQL:
                return _oracle_batches_stub([fanda_rate_df])
            if sql_path == award_loader.SCIENCE_KEYWORD_ORACLE_SQL:
                return _oracle_batches_stub([science_keyword_df])
            if sql_path == award_loader.SPECIAL_REVIEW_ORACLE_SQL:
                return _oracle_batches_stub([special_review_df])
            if sql_path == award_loader.SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL:
                return _oracle_batches_stub([special_review_exemption_df])
            if sql_path == award_loader.APPROVED_EQUIPMENT_ORACLE_SQL:
                return _oracle_batches_stub([approved_equipment_df])
            if sql_path == award_loader.APPROVED_FOREIGN_TRAVEL_ORACLE_SQL:
                return _oracle_batches_stub([approved_foreign_travel_df])
            if sql_path == award_loader.SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL:
                return _oracle_batches_stub([subcontracting_budgeted_goals_df])
            if sql_path == award_loader.COMMENT_ORACLE_SQL:
                return _oracle_batches_stub([comment_df])
            if sql_path == award_loader.EXTENSION_ORACLE_SQL:
                return _oracle_batches_stub([extension_df])
            if sql_path == award_loader.CGB_ORACLE_SQL:
                return _oracle_batches_stub([cgb_df])
            if sql_path == award_loader.HIERARCHY_ORACLE_SQL:
                return _oracle_batches_stub([hierarchy_df])
            if sql_path == award_loader.TIME_AND_MONEY_DOCUMENT_ORACLE_SQL:
                return _oracle_batches_stub([tnm_document_df])
            if sql_path == award_loader.PENDING_TRANSACTION_ORACLE_SQL:
                return _oracle_batches_stub([pending_transaction_df])
            if sql_path == award_loader.PENDING_TRANSACTION_EXTENSION_ORACLE_SQL:
                return _oracle_batches_stub([pending_transaction_extension_df])
            if sql_path == award_loader.TRANSACTION_DETAIL_ORACLE_SQL:
                return _oracle_batches_stub([transaction_detail_df])
            if sql_path == award_loader.AWARD_AMOUNT_TRANSACTION_ORACLE_SQL:
                return _oracle_batches_stub([award_amount_transaction_df])
            if sql_path == award_loader.AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL:
                return _oracle_batches_stub([fanda_distribution_df])
            if sql_path == award_loader.BUDGET_ORACLE_SQL:
                return _oracle_batches_stub([budget_df])
            if sql_path == award_loader.BUDGET_LIMIT_ORACLE_SQL:
                return _oracle_batches_stub([budget_limit_df])
            if sql_path == award_loader.BUDGET_PERIOD_ORACLE_SQL:
                return _oracle_batches_stub([budget_period_df])
            if sql_path == award_loader.BUDGET_LINE_ITEM_ORACLE_SQL:
                return _oracle_batches_stub([budget_line_item_df])
            if (
                sql_path
                == award_loader.BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL
            ):
                return _oracle_batches_stub(
                    [budget_period_summary_calculated_amount_df]
                )
            if (
                sql_path
                == award_loader.BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL
            ):
                return _oracle_batches_stub([budget_line_item_calculated_amount_df])
            if sql_path == award_loader.BUDGET_PERSONNEL_DETAIL_ORACLE_SQL:
                return _oracle_batches_stub([budget_personnel_detail_df])
            if (
                sql_path
                == award_loader.BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL
            ):
                return _oracle_batches_stub([budget_personnel_calculated_amount_df])
            if sql_path == award_loader.BUDGET_PERSON_ORACLE_SQL:
                return _oracle_batches_stub([budget_person_df])
            if sql_path == award_loader.TRANSFERRING_SPONSOR_ORACLE_SQL:
                return _oracle_batches_stub([transferring_sponsor_df])
            if sql_path == award_loader.AWARD_TRANSMISSION_ORACLE_SQL:
                return _oracle_batches_stub([award_transmission_df])
            if sql_path == award_loader.AWARD_TRANSMISSION_CHILD_ORACLE_SQL:
                return _oracle_batches_stub([award_transmission_child_df])
            if sql_path == award_loader.AWARD_IDS_ASCENDING_ORACLE_SQL:
                return _oracle_batches_stub([award_ids_df])
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

    def test_ecs_and_migrate_only_default_to_false(self) -> None:
        args = award_loader.parse_args([])
        self.assertFalse(args.ecs)
        self.assertFalse(args.migrate_only)

    def test_ecs_parses_alone(self) -> None:
        args = award_loader.parse_args(["--ecs"])
        self.assertTrue(args.ecs)
        self.assertFalse(args.migrate_only)

    def test_ecs_migrate_only_parses_together(self) -> None:
        args = award_loader.parse_args(["--ecs", "--migrate-only"])
        self.assertTrue(args.ecs)
        self.assertTrue(args.migrate_only)

    def test_migrate_only_requires_ecs(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(["--migrate-only"])

    def test_migrate_only_cannot_combine_with_create_batch(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--ecs", "--migrate-only", "--create-batch", "10"]
            )

    def test_migrate_only_cannot_combine_with_load_batch(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--ecs", "--migrate-only", "--load-batch", "5"]
            )

    def test_migrate_only_cannot_combine_with_show_batch(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--ecs", "--migrate-only", "--show-batch", "5"]
            )

    def test_migrate_only_cannot_combine_with_load_award_id(self) -> None:
        with self.assertRaises(SystemExit):
            award_loader.parse_args(
                ["--ecs", "--migrate-only", "--load-award-id", "1"]
            )

    def test_show_batch_does_not_require_ecs(self) -> None:
        # --show-batch is a local-mode-compatible verb too (no --ecs
        # requirement of its own) - only --migrate-only is gated on --ecs.
        args = award_loader.parse_args(["--show-batch", "5"])
        self.assertEqual(args.show_batch, 5)
        self.assertFalse(args.ecs)


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
    def test_first_load_inserts_all_forty_eight_tables(self) -> None:
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
            notepad=[_notepad_row()],
            closeout=[_closeout_row()],
            payment_schedule=[_payment_schedule_row()],
            approved_subaward=[_approved_subaward_row()],
            cfda=[_cfda_row()],
            cost_share=[_cost_share_row()],
            fanda_rate=[_fanda_rate_row()],
            science_keyword=[_science_keyword_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
            approved_equipment=[_approved_equipment_row()],
            approved_foreign_travel=[_approved_foreign_travel_row()],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
            comment=[_award_comment_row()],
            extension=[_award_extension_row()],
            cgb=[_award_cgb_row()],
            hierarchy=[_award_hierarchy_row()],
            tnm_document=[_time_and_money_document_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
            transaction_detail=[_transaction_detail_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
            budget=[_award_budget_row()],
            budget_limit=[_award_budget_limit_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row()
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row()
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row()
            ],
            budget_person=[_award_budget_person_row()],
            transferring_sponsor=[_award_transferring_sponsor_row()],
            award_transmission=[_award_transmission_row()],
            award_transmission_child=[_award_transmission_child_row()],
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
        self.assertEqual(report["notepad_inserted"], 1)
        self.assertEqual(report["closeout_inserted"], 1)
        self.assertEqual(report["payment_schedule_inserted"], 1)
        self.assertEqual(report["approved_subaward_inserted"], 1)
        self.assertEqual(report["cfda_inserted"], 1)
        self.assertEqual(report["cost_share_inserted"], 1)
        self.assertEqual(report["fanda_rate_inserted"], 1)
        self.assertEqual(report["science_keyword_inserted"], 1)
        self.assertEqual(report["special_review_inserted"], 1)
        self.assertEqual(report["special_review_exemption_inserted"], 1)
        self.assertEqual(report["approved_equipment_inserted"], 1)
        self.assertEqual(report["approved_foreign_travel_inserted"], 1)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 1)
        self.assertEqual(report["comment_inserted"], 1)
        self.assertEqual(report["extension_inserted"], 1)
        self.assertEqual(report["cgb_inserted"], 1)
        self.assertEqual(report["hierarchy_inserted"], 1)
        self.assertEqual(report["tnm_document_inserted"], 1)
        self.assertEqual(report["pending_transaction_inserted"], 1)
        self.assertEqual(report["pending_transaction_extension_inserted"], 1)
        self.assertEqual(report["transaction_detail_inserted"], 1)
        self.assertEqual(report["award_amount_transaction_inserted"], 1)
        self.assertEqual(report["fanda_distribution_inserted"], 1)
        self.assertEqual(report["budget_inserted"], 1)
        self.assertEqual(report["budget_limit_inserted"], 1)
        self.assertEqual(report["budget_period_inserted"], 1)
        self.assertEqual(report["budget_line_item_inserted"], 1)
        self.assertEqual(report["budget_line_item_calculated_amount_inserted"], 1)
        self.assertEqual(report["budget_personnel_detail_inserted"], 1)
        self.assertEqual(report["budget_personnel_calculated_amount_inserted"], 1)
        self.assertEqual(
            report["budget_period_summary_calculated_amount_inserted"], 1
        )
        self.assertEqual(report["budget_person_inserted"], 1)
        self.assertEqual(report["transferring_sponsor_inserted"], 1)
        self.assertEqual(report["award_transmission_inserted"], 1)
        self.assertEqual(report["award_transmission_child_inserted"], 1)

        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["title"], "Test Award")
        self.assertTrue(version_row["is_primary_current"])
        self.assertEqual(version_row["basis_of_payment_code"], "01")
        self.assertEqual(
            version_row["basis_of_payment_description"], "Cost Reimbursement"
        )
        self.assertEqual(version_row["method_of_payment_code"], "02")
        self.assertEqual(
            version_row["method_of_payment_description"], "Letter of Credit"
        )

        amount_row = self._row("award_amount_info", award_amount_info_id=501)
        self.assertEqual(float(amount_row["obligated_total_amount"]), 110.0)
        self.assertEqual(amount_row["transaction_id"], 9001)
        self.assertEqual(amount_row["originating_award_version"], 0)

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

        notepad_row = self._row("award_notepad", award_notepad_id=1701)
        self.assertEqual(notepad_row["note_topic"], "Test Topic")
        self.assertEqual(notepad_row["comments"], "Test comment body")
        self.assertEqual(notepad_row["source_create_user"], "kcuser")
        self.assertEqual(notepad_row["award_number"], "A-0001")
        self.assertEqual(notepad_row["entry_number"], 1)

        closeout_row = self._row("award_closeout", award_closeout_id=1801)
        self.assertEqual(closeout_row["closeout_report_code"], "FIN")
        self.assertEqual(closeout_row["closeout_report_name"], "Final Report")
        self.assertEqual(closeout_row["multiple_flag"], "N")
        self.assertEqual(closeout_row["sequence_number"], 0)

        payment_schedule_row = self._row(
            "award_payment_schedule", award_payment_schedule_id=1901
        )
        self.assertEqual(float(payment_schedule_row["amount"]), 500.0)
        self.assertEqual(payment_schedule_row["status"], "PEND")
        self.assertIsNone(payment_schedule_row["award_report_term_id"])

        approved_subaward_row = self._row(
            "award_approved_subaward", award_approved_subaward_id=2001
        )
        self.assertEqual(
            approved_subaward_row["organization_name"], "Test Subrecipient"
        )
        self.assertEqual(approved_subaward_row["organization_id"], "ORG1")
        self.assertEqual(float(approved_subaward_row["amount"]), 25000.0)

        cfda_row = self._row("award_cfda", award_cfda_id=2101)
        self.assertEqual(cfda_row["cfda_number"], "93.701")
        self.assertEqual(cfda_row["cfda_description"], "Test CFDA Program")

        cost_share_row = self._row("award_cost_share", award_cost_share_id=2201)
        self.assertEqual(float(cost_share_row["commitment_amount"]), 5000.0)
        self.assertEqual(cost_share_row["destination"], "Test Destination")
        self.assertEqual(float(cost_share_row["cost_share_met"]), 5000.0)
        self.assertIsNone(cost_share_row["fiscal_year"])

        fanda_rate_row = self._row("award_fanda_rate", award_fanda_rate_id=2301)
        self.assertEqual(float(fanda_rate_row["applicable_fanda_rate"]), 55.0)
        self.assertEqual(fanda_rate_row["destination_account"], "DST1")

        science_keyword_row = self._row(
            "award_science_keyword", award_science_keyword_id=2401
        )
        self.assertEqual(science_keyword_row["science_keyword_code"], "SK001")
        self.assertEqual(science_keyword_row["award_number"], "A-0001")

        special_review_row = self._row(
            "award_special_review", award_special_review_id=2501
        )
        self.assertEqual(special_review_row["special_review_number"], 1)
        self.assertEqual(special_review_row["protocol_number"], "PROTO-001")
        self.assertEqual(special_review_row["award_number"], "A-0001")

        special_review_exemption_row = self._row(
            "award_special_review_exemption",
            award_special_review_exemption_id=2601,
        )
        self.assertEqual(special_review_exemption_row["exemption_type_code"], "E1")
        self.assertEqual(
            special_review_exemption_row["award_special_review_id"], 2501
        )
        self.assertEqual(special_review_exemption_row["award_id"], 1)

        approved_equipment_row = self._row(
            "award_approved_equipment", award_approved_equipment_id=2701
        )
        self.assertEqual(approved_equipment_row["item"], "Test Microscope")
        self.assertEqual(float(approved_equipment_row["amount"]), 15000.0)

        approved_foreign_travel_row = self._row(
            "award_approved_foreign_travel",
            award_approved_foreign_travel_id=2801,
        )
        self.assertEqual(
            approved_foreign_travel_row["destination"], "Geneva, Switzerland"
        )
        self.assertEqual(approved_foreign_travel_row["traveler_name"], "Jane Traveler")

        subcontracting_row = self._row(
            "award_subcontracting_budgeted_goals", award_number="A-0001"
        )
        self.assertEqual(
            float(subcontracting_row["large_business_goal_amount"]), 10000.0
        )
        self.assertEqual(
            float(subcontracting_row["historical_black_college_goal_amount"]), 300.0
        )

        comment_row = self._row("award_comment", award_comment_id=2901)
        self.assertEqual(comment_row["comment_type_code"], "GEN")
        self.assertEqual(comment_row["comments"], "Test award comment body")
        self.assertEqual(comment_row["checklist_print_flag"], "N")
        self.assertEqual(comment_row["sequence_number"], 0)

        extension_row = self._row("award_extension", award_id=1)
        self.assertEqual(extension_row["child_type"], "SUPPLEMENT")
        self.assertEqual(extension_row["major_project"], "Y")
        self.assertEqual(extension_row["award_number"], "A-0001")
        self.assertIsNone(extension_row["stepped_up_rate"])

        cgb_row = self._row("award_cgb", award_id=1)
        self.assertEqual(float(cgb_row["min_invoice_amount"]), 100.0)
        self.assertEqual(float(cgb_row["amount_to_draw"]), 5000.0)
        self.assertEqual(cgb_row["invoicing_option"], "MONTHLY")
        self.assertEqual(cgb_row["bill_freq_cd"], "M")

        hierarchy_row = self._row("award_hierarchy", award_hierarchy_id=10001)
        self.assertEqual(hierarchy_row["award_number"], "A-0001")
        self.assertEqual(hierarchy_row["parent_award_number"], "000000-00000")
        self.assertEqual(hierarchy_row["active"], "Y")

        tnm_document_row = self._row("time_and_money_document", document_number="TNM-1")
        self.assertEqual(tnm_document_row["root_award_number"], "A-0001")
        self.assertEqual(tnm_document_row["document_status"], "FINAL")

        pending_transaction_row = self._row("pending_transaction", transaction_id=9001)
        self.assertEqual(pending_transaction_row["destination_award_number"], "A-0001")
        self.assertEqual(float(pending_transaction_row["obligated_amount"]), 5000.0)

        pte_row = self._row("pending_transaction_extension", transaction_id=9001)
        self.assertEqual(pte_row["budget_period"], "1")

        transaction_detail_row = self._row(
            "transaction_detail", transaction_detail_id=11001
        )
        self.assertEqual(transaction_detail_row["award_number"], "A-0001")
        self.assertEqual(transaction_detail_row["transaction_id"], 9001)
        self.assertEqual(transaction_detail_row["transaction_detail_type"], "PRIMARY")

        aat_row = self._row(
            "award_amount_transaction", award_amount_transaction_id=12001
        )
        self.assertEqual(aat_row["document_number"], "TNM-1")
        self.assertEqual(aat_row["transaction_type_description"], "New")

        fanda_row = self._row(
            "award_direct_fanda_distribution",
            award_direct_fanda_distribution_id=13001,
        )
        self.assertEqual(fanda_row["award_amount_info_id"], 501)
        self.assertEqual(float(fanda_row["direct_cost"]), 4000.0)

        budget_row = self._row("award_budget", budget_id=14001)
        self.assertEqual(budget_row["award_id"], 1)
        self.assertEqual(budget_row["document_number"], "BUD-1")
        self.assertEqual(budget_row["name"], "Test Budget")

        budget_limit_row = self._row("award_budget_limit", budget_limit_id=14101)
        self.assertEqual(budget_limit_row["budget_id"], 14001)
        self.assertEqual(float(budget_limit_row["limit_amount"]), 200.0)

        budget_period_row = self._row("award_budget_period", budget_period_id=14201)
        self.assertEqual(budget_period_row["budget_id"], 14001)
        self.assertEqual(float(budget_period_row["total_cost"]), 110.0)

        budget_line_item_row = self._row(
            "award_budget_line_item", budget_line_item_id=14301
        )
        self.assertEqual(budget_line_item_row["budget_period_id"], 14201)
        self.assertEqual(
            budget_line_item_row["formulated_cost_element_flag"], "N"
        )
        self.assertEqual(budget_line_item_row["hidden_in_hierarchy"], "N")

        budget_line_item_cal_row = self._row(
            "award_budget_line_item_calculated_amount",
            budget_line_item_calculated_amount_id=14401,
        )
        self.assertEqual(budget_line_item_cal_row["budget_line_item_id"], 14301)
        self.assertEqual(float(budget_line_item_cal_row["calculated_cost"]), 10.0)

        budget_personnel_detail_row = self._row(
            "award_budget_personnel_detail", budget_personnel_line_item_id=14501
        )
        self.assertEqual(budget_personnel_detail_row["budget_line_item_id"], 14301)
        self.assertEqual(budget_personnel_detail_row["period_type_code"], "AC")

        budget_personnel_cal_row = self._row(
            "award_budget_personnel_calculated_amount",
            budget_personnel_calculated_amount_id=14601,
        )
        self.assertEqual(
            budget_personnel_cal_row["budget_personnel_line_item_id"], 14501
        )
        self.assertEqual(float(budget_personnel_cal_row["calculated_cost"]), 10.0)

        budget_period_summary_row = self._row(
            "award_budget_period_summary_calculated_amount",
            award_budget_period_summary_calculated_amount_id=14701,
        )
        self.assertEqual(budget_period_summary_row["budget_period_id"], 14201)
        self.assertEqual(budget_period_summary_row["rate_class_type"], "E")

        budget_person_row = self._row(
            "award_budget_person", budget_id=14001, person_sequence_number=1
        )
        self.assertEqual(budget_person_row["person_name"], "Jane Researcher")
        self.assertEqual(float(budget_person_row["calculation_base"]), 100000.0)
        self.assertEqual(budget_person_row["appointment_type_code"], "AC")

        transferring_sponsor_row = self._row(
            "award_transferring_sponsor", award_transferring_sponsor_id=14801
        )
        self.assertEqual(transferring_sponsor_row["sponsor_code"], "NIH")
        self.assertEqual(
            transferring_sponsor_row["sponsor_name"],
            "National Institutes of Health",
        )

        transmission_row = self._row(
            "award_transmission", transmission_id=15001
        )
        self.assertEqual(transmission_row["success_indicator"], "Y")
        self.assertEqual(
            transmission_row["sent_data"],
            '<SI_KCRMPROCESS_OUTBOUND xmlns="urn:sap">'
            '<Header sponsor="NIH &amp; Co">Grant &lt;A-0001&gt;</Header>'
            "</SI_KCRMPROCESS_OUTBOUND>",
        )
        self.assertEqual(
            transmission_row["returned_data"],
            '<Response><Status code="0">SUCCESS</Status></Response>',
        )

        transmission_child_row = self._row(
            "award_transmission_child", transmission_child_id=15101
        )
        self.assertEqual(transmission_child_row["transmission_id"], 15001)
        self.assertEqual(transmission_child_row["overhead_key"], "MTDC")
        self.assertEqual(transmission_child_row["base_code"], "01")
        self.assertEqual(transmission_child_row["off_campus"], "N")

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
            notepad=[_notepad_row()],
            closeout=[_closeout_row()],
            payment_schedule=[_payment_schedule_row()],
            approved_subaward=[_approved_subaward_row()],
            cfda=[_cfda_row()],
            cost_share=[_cost_share_row()],
            fanda_rate=[_fanda_rate_row()],
            science_keyword=[_science_keyword_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
            approved_equipment=[_approved_equipment_row()],
            approved_foreign_travel=[_approved_foreign_travel_row()],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
            comment=[_award_comment_row()],
            extension=[_award_extension_row()],
            cgb=[_award_cgb_row()],
            hierarchy=[_award_hierarchy_row()],
            tnm_document=[_time_and_money_document_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
            transaction_detail=[_transaction_detail_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
            budget=[_award_budget_row()],
            budget_limit=[_award_budget_limit_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row()
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row()
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row()
            ],
            budget_person=[_award_budget_person_row()],
            transferring_sponsor=[_award_transferring_sponsor_row()],
            award_transmission=[_award_transmission_row()],
            award_transmission_child=[_award_transmission_child_row()],
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
        self.assertEqual(report["notepad_inserted"], 0)
        self.assertEqual(report["notepad_updated"], 0)
        self.assertEqual(report["notepad_unchanged"], 1)
        self.assertEqual(report["closeout_inserted"], 0)
        self.assertEqual(report["closeout_updated"], 0)
        self.assertEqual(report["closeout_unchanged"], 1)
        self.assertEqual(report["payment_schedule_inserted"], 0)
        self.assertEqual(report["payment_schedule_updated"], 0)
        self.assertEqual(report["payment_schedule_unchanged"], 1)
        self.assertEqual(report["approved_subaward_inserted"], 0)
        self.assertEqual(report["approved_subaward_updated"], 0)
        self.assertEqual(report["approved_subaward_unchanged"], 1)
        self.assertEqual(report["cfda_inserted"], 0)
        self.assertEqual(report["cfda_updated"], 0)
        self.assertEqual(report["cfda_unchanged"], 1)
        self.assertEqual(report["cost_share_inserted"], 0)
        self.assertEqual(report["cost_share_updated"], 0)
        self.assertEqual(report["cost_share_unchanged"], 1)
        self.assertEqual(report["fanda_rate_inserted"], 0)
        self.assertEqual(report["fanda_rate_updated"], 0)
        self.assertEqual(report["fanda_rate_unchanged"], 1)
        self.assertEqual(report["science_keyword_inserted"], 0)
        self.assertEqual(report["science_keyword_updated"], 0)
        self.assertEqual(report["science_keyword_unchanged"], 1)
        self.assertEqual(report["special_review_inserted"], 0)
        self.assertEqual(report["special_review_updated"], 0)
        self.assertEqual(report["special_review_unchanged"], 1)
        self.assertEqual(report["special_review_exemption_inserted"], 0)
        self.assertEqual(report["special_review_exemption_updated"], 0)
        self.assertEqual(report["special_review_exemption_unchanged"], 1)
        self.assertEqual(report["approved_equipment_inserted"], 0)
        self.assertEqual(report["approved_equipment_updated"], 0)
        self.assertEqual(report["approved_equipment_unchanged"], 1)
        self.assertEqual(report["approved_foreign_travel_inserted"], 0)
        self.assertEqual(report["approved_foreign_travel_updated"], 0)
        self.assertEqual(report["approved_foreign_travel_unchanged"], 1)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 0)
        self.assertEqual(report["subcontracting_budgeted_goals_updated"], 0)
        self.assertEqual(report["subcontracting_budgeted_goals_unchanged"], 1)
        self.assertEqual(report["comment_inserted"], 0)
        self.assertEqual(report["comment_updated"], 0)
        self.assertEqual(report["comment_unchanged"], 1)
        self.assertEqual(report["extension_inserted"], 0)
        self.assertEqual(report["extension_updated"], 0)
        self.assertEqual(report["extension_unchanged"], 1)
        self.assertEqual(report["cgb_inserted"], 0)
        self.assertEqual(report["cgb_updated"], 0)
        self.assertEqual(report["cgb_unchanged"], 1)
        self.assertEqual(report["hierarchy_inserted"], 0)
        self.assertEqual(report["hierarchy_updated"], 0)
        self.assertEqual(report["hierarchy_unchanged"], 1)
        self.assertEqual(report["tnm_document_inserted"], 0)
        self.assertEqual(report["tnm_document_updated"], 0)
        self.assertEqual(report["tnm_document_unchanged"], 1)
        self.assertEqual(report["pending_transaction_inserted"], 0)
        self.assertEqual(report["pending_transaction_updated"], 0)
        self.assertEqual(report["pending_transaction_unchanged"], 1)
        self.assertEqual(report["pending_transaction_extension_inserted"], 0)
        self.assertEqual(report["pending_transaction_extension_updated"], 0)
        self.assertEqual(report["pending_transaction_extension_unchanged"], 1)
        self.assertEqual(report["transaction_detail_inserted"], 0)
        self.assertEqual(report["transaction_detail_updated"], 0)
        self.assertEqual(report["transaction_detail_unchanged"], 1)
        self.assertEqual(report["award_amount_transaction_inserted"], 0)
        self.assertEqual(report["award_amount_transaction_updated"], 0)
        self.assertEqual(report["award_amount_transaction_unchanged"], 1)
        self.assertEqual(report["fanda_distribution_inserted"], 0)
        self.assertEqual(report["fanda_distribution_updated"], 0)
        self.assertEqual(report["fanda_distribution_unchanged"], 1)
        self.assertEqual(report["budget_unchanged"], 1)
        self.assertEqual(report["budget_limit_unchanged"], 1)
        self.assertEqual(report["budget_period_unchanged"], 1)
        self.assertEqual(report["budget_line_item_unchanged"], 1)
        self.assertEqual(report["budget_line_item_calculated_amount_unchanged"], 1)
        self.assertEqual(report["budget_personnel_detail_unchanged"], 1)
        self.assertEqual(report["budget_personnel_calculated_amount_unchanged"], 1)
        self.assertEqual(
            report["budget_period_summary_calculated_amount_unchanged"], 1
        )
        self.assertEqual(report["budget_person_unchanged"], 1)
        self.assertEqual(report["transferring_sponsor_unchanged"], 1)
        self.assertEqual(report["award_transmission_unchanged"], 1)
        self.assertEqual(report["award_transmission_child_unchanged"], 1)

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

    def test_notepad_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], notepad=[_notepad_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            notepad=[_notepad_row(comments="Changed comment body")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["notepad_updated"], 1)
        row = self._row("award_notepad", award_notepad_id=1701)
        self.assertEqual(row["comments"], "Changed comment body")

    def test_notepad_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            notepad=[
                _notepad_row(
                    award_notepad_id=1702,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            notepad=[_notepad_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_notepad")
        self.assertEqual(total, 2)

    def test_closeout_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], closeout=[_closeout_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            closeout=[_closeout_row(closeout_report_name="Amended Final Report")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["closeout_updated"], 1)
        row = self._row("award_closeout", award_closeout_id=1801)
        self.assertEqual(row["closeout_report_name"], "Amended Final Report")

    def test_closeout_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            closeout=[
                _closeout_row(
                    award_closeout_id=1802,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            closeout=[_closeout_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_closeout")
        self.assertEqual(total, 2)

    def test_payment_schedule_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], payment_schedule=[_payment_schedule_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            payment_schedule=[_payment_schedule_row(amount=750.00, status="SUBM")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["payment_schedule_updated"], 1)
        row = self._row(
            "award_payment_schedule", award_payment_schedule_id=1901
        )
        self.assertEqual(float(row["amount"]), 750.0)
        self.assertEqual(row["status"], "SUBM")

    def test_payment_schedule_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            payment_schedule=[
                _payment_schedule_row(
                    award_payment_schedule_id=1902,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            payment_schedule=[_payment_schedule_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_payment_schedule"
        )
        self.assertEqual(total, 2)

    def test_approved_subaward_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            approved_subaward=[_approved_subaward_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            approved_subaward=[_approved_subaward_row(amount=30000.00)],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["approved_subaward_updated"], 1)
        row = self._row(
            "award_approved_subaward", award_approved_subaward_id=2001
        )
        self.assertEqual(float(row["amount"]), 30000.0)

    def test_approved_subaward_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            approved_subaward=[
                _approved_subaward_row(
                    award_approved_subaward_id=2002,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            approved_subaward=[_approved_subaward_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_subaward"
        )
        self.assertEqual(total, 2)

    def test_cfda_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(versions=[_version_row()], cfda=[_cfda_row()]):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            cfda=[_cfda_row(cfda_description="Amended CFDA Program")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["cfda_updated"], 1)
        row = self._row("award_cfda", award_cfda_id=2101)
        self.assertEqual(row["cfda_description"], "Amended CFDA Program")

    def test_cfda_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            cfda=[
                _cfda_row(award_cfda_id=2102, award_id=2, award_number="A-0002")
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            cfda=[_cfda_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_cfda")
        self.assertEqual(total, 2)

    def test_cost_share_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], cost_share=[_cost_share_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            cost_share=[_cost_share_row(commitment_amount=7500.00)],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["cost_share_updated"], 1)
        row = self._row("award_cost_share", award_cost_share_id=2201)
        self.assertEqual(float(row["commitment_amount"]), 7500.0)

    def test_cost_share_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            cost_share=[
                _cost_share_row(
                    award_cost_share_id=2202, award_id=2, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            cost_share=[_cost_share_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_cost_share")
        self.assertEqual(total, 2)

    def test_fanda_rate_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], fanda_rate=[_fanda_rate_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            fanda_rate=[_fanda_rate_row(applicable_fanda_rate=60.00)],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["fanda_rate_updated"], 1)
        row = self._row("award_fanda_rate", award_fanda_rate_id=2301)
        self.assertEqual(float(row["applicable_fanda_rate"]), 60.0)

    def test_fanda_rate_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            fanda_rate=[
                _fanda_rate_row(
                    award_fanda_rate_id=2302, award_id=2, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            fanda_rate=[_fanda_rate_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_fanda_rate")
        self.assertEqual(total, 2)

    def test_science_keyword_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], science_keyword=[_science_keyword_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            science_keyword=[_science_keyword_row(science_keyword_code="SK002")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["science_keyword_updated"], 1)
        row = self._row("award_science_keyword", award_science_keyword_id=2401)
        self.assertEqual(row["science_keyword_code"], "SK002")

    def test_science_keyword_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            science_keyword=[
                _science_keyword_row(
                    award_science_keyword_id=2402,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            science_keyword=[_science_keyword_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_science_keyword")
        self.assertEqual(total, 2)

    def test_special_review_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], special_review=[_special_review_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            special_review=[_special_review_row(comments="Amended comment")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["special_review_updated"], 1)
        row = self._row("award_special_review", award_special_review_id=2501)
        self.assertEqual(row["comments"], "Amended comment")

    def test_special_review_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            special_review=[
                _special_review_row(
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            special_review=[_special_review_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_special_review")
        self.assertEqual(total, 2)

    def test_special_review_exemption_loads_correctly_when_its_parent_review_is_new(
        self,
    ) -> None:
        # award_special_review_exemption's FK parent (award_special_review)
        # is being inserted for the very first time in this same
        # transaction - proves the load-order decision (special_review
        # before special_review_exemption) actually holds. This is the
        # one table in the whole Award domain with no AWARD_ID column
        # at all - its only Oracle FK is to its parent.
        with self._patched_oracle(
            versions=[_version_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["special_review_inserted"], 1)
        self.assertEqual(report["special_review_exemption_inserted"], 1)

        row = self._row(
            "award_special_review_exemption",
            award_special_review_exemption_id=2601,
        )
        self.assertEqual(row["award_special_review_id"], 2501)
        self.assertEqual(row["award_id"], 1)

    def test_special_review_exemption_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[
                _special_review_exemption_row(exemption_type_code="E2")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["special_review_exemption_updated"], 1)
        row = self._row(
            "award_special_review_exemption",
            award_special_review_exemption_id=2601,
        )
        self.assertEqual(row["exemption_type_code"], "E2")

    def test_special_review_exemption_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            special_review=[
                _special_review_row(
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
            special_review_exemption=[
                _special_review_exemption_row(
                    award_special_review_exemption_id=2602,
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_special_review_exemption"
        )
        self.assertEqual(total, 2)

    def test_approved_equipment_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], approved_equipment=[_approved_equipment_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            approved_equipment=[_approved_equipment_row(amount=20000.00)],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["approved_equipment_updated"], 1)
        row = self._row(
            "award_approved_equipment", award_approved_equipment_id=2701
        )
        self.assertEqual(float(row["amount"]), 20000.0)

    def test_approved_equipment_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            approved_equipment=[
                _approved_equipment_row(
                    award_approved_equipment_id=2702,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            approved_equipment=[_approved_equipment_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_equipment"
        )
        self.assertEqual(total, 2)

    def test_approved_foreign_travel_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            approved_foreign_travel=[_approved_foreign_travel_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            approved_foreign_travel=[
                _approved_foreign_travel_row(destination="Nairobi, Kenya")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["approved_foreign_travel_updated"], 1)
        row = self._row(
            "award_approved_foreign_travel",
            award_approved_foreign_travel_id=2801,
        )
        self.assertEqual(row["destination"], "Nairobi, Kenya")

    def test_approved_foreign_travel_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            approved_foreign_travel=[
                _approved_foreign_travel_row(
                    award_approved_foreign_travel_id=2802,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            approved_foreign_travel=[_approved_foreign_travel_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_foreign_travel"
        )
        self.assertEqual(total, 2)

    def test_subcontracting_budgeted_goals_value_change_produces_an_update(
        self,
    ) -> None:
        # Proves the natural-key (award_number, not a surrogate id)
        # UPSERT conflict key actually produces a true UPDATE on
        # re-run, not a duplicate row - the one table in the Award
        # domain with this shape.
        with self._patched_oracle(
            versions=[_version_row()],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(large_business_goal_amount=99999.00)
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["subcontracting_budgeted_goals_updated"], 1)
        row = self._row(
            "award_subcontracting_budgeted_goals", award_number="A-0001"
        )
        self.assertEqual(float(row["large_business_goal_amount"]), 99999.0)
        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
        )
        self.assertEqual(total, 1)

    def test_subcontracting_budgeted_goals_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(award_number="A-0002")
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
        )
        self.assertEqual(total, 2)

    def test_award_comment_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], comment=[_award_comment_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            comment=[_award_comment_row(comments="Amended comment body")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["comment_updated"], 1)
        row = self._row("award_comment", award_comment_id=2901)
        self.assertEqual(row["comments"], "Amended comment body")

    def test_award_comment_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            comment=[
                _award_comment_row(
                    award_comment_id=2902, award_id=2, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            comment=[_award_comment_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_comment")
        self.assertEqual(total, 2)

    def test_award_extension_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], extension=[_award_extension_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            extension=[_award_extension_row(child_type="SUPPLEMENT-2")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["extension_updated"], 1)
        row = self._row("award_extension", award_id=1)
        self.assertEqual(row["child_type"], "SUPPLEMENT-2")

    def test_award_extension_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            extension=[
                _award_extension_row(award_id=2, award_number="A-0002")
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            extension=[_award_extension_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_extension")
        self.assertEqual(total, 2)

    def test_award_cgb_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], cgb=[_award_cgb_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            cgb=[_award_cgb_row(bill_freq_cd="Q")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["cgb_updated"], 1)
        row = self._row("award_cgb", award_id=1)
        self.assertEqual(row["bill_freq_cd"], "Q")

    def test_award_cgb_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            cgb=[_award_cgb_row(award_id=2, award_number="A-0002")],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            cgb=[_award_cgb_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_cgb")
        self.assertEqual(total, 2)

    def test_award_hierarchy_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], hierarchy=[_award_hierarchy_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            hierarchy=[_award_hierarchy_row(active="N")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["hierarchy_updated"], 1)
        row = self._row("award_hierarchy", award_hierarchy_id=10001)
        self.assertEqual(row["active"], "N")

    def test_award_hierarchy_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            hierarchy=[
                _award_hierarchy_row(
                    award_hierarchy_id=10002,
                    root_award_number="A-0002",
                    award_number="A-0002",
                    originating_award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            hierarchy=[_award_hierarchy_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy")
        self.assertEqual(total, 2)

    def test_award_hierarchy_is_version_agnostic_with_no_sequence_number(self) -> None:
        # AWARD_HIERARCHY has no SEQUENCE_NUMBER column at all - it is
        # scoped to the whole award_number family, not a specific
        # version, per its own Java class's documented contract. Loading
        # either version of a two-sequence family must resolve to the
        # SAME one archive.award_hierarchy row.
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001", sequence_number=0),
                _version_row(award_id=2, award_number="A-0001", sequence_number=1),
            ],
            hierarchy=[_award_hierarchy_row()],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy")
        self.assertEqual(total, 1)
        row = self._row("award_hierarchy", award_hierarchy_id=10001)
        self.assertEqual(row["award_number"], "A-0001")

    def test_time_and_money_document_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], tnm_document=[_time_and_money_document_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            tnm_document=[_time_and_money_document_row(document_status="PENDING")],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["tnm_document_updated"], 1)
        row = self._row("time_and_money_document", document_number="TNM-1")
        self.assertEqual(row["document_status"], "PENDING")

    def test_time_and_money_document_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            tnm_document=[
                _time_and_money_document_row(
                    document_number="TNM-2", award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            tnm_document=[_time_and_money_document_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.time_and_money_document")
        self.assertEqual(total, 2)

    def test_pending_transaction_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            pending_transaction=[_pending_transaction_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            pending_transaction=[
                _pending_transaction_row(obligated_amount=9999.00)
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["pending_transaction_updated"], 1)
        row = self._row("pending_transaction", transaction_id=9001)
        self.assertEqual(float(row["obligated_amount"]), 9999.0)

    def test_pending_transaction_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            pending_transaction=[
                _pending_transaction_row(
                    transaction_id=9002,
                    source_award_number="000000-00000",
                    destination_award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            pending_transaction=[_pending_transaction_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.pending_transaction")
        self.assertEqual(total, 2)

    def test_pending_transaction_extension_value_change_produces_an_update(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[
                _pending_transaction_extension_row(budget_period="2")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["pending_transaction_extension_updated"], 1)
        row = self._row("pending_transaction_extension", transaction_id=9001)
        self.assertEqual(row["budget_period"], "2")

    def test_transaction_detail_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()], transaction_detail=[_transaction_detail_row()]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            transaction_detail=[
                _transaction_detail_row(transaction_detail_type="INTERMEDIATE")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["transaction_detail_updated"], 1)
        row = self._row("transaction_detail", transaction_detail_id=11001)
        self.assertEqual(row["transaction_detail_type"], "INTERMEDIATE")

    def test_transaction_detail_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            transaction_detail=[
                _transaction_detail_row(
                    transaction_detail_id=11002, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            transaction_detail=[_transaction_detail_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.transaction_detail")
        self.assertEqual(total, 2)

    def test_award_amount_transaction_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            award_amount_transaction=[
                _award_amount_transaction_row(comments="Amended comments")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["award_amount_transaction_updated"], 1)
        row = self._row(
            "award_amount_transaction", award_amount_transaction_id=12001
        )
        self.assertEqual(row["comments"], "Amended comments")

    def test_award_amount_transaction_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            award_amount_transaction=[
                _award_amount_transaction_row(
                    award_amount_transaction_id=12002, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            award_amount_transaction=[_award_amount_transaction_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_amount_transaction"
        )
        self.assertEqual(total, 2)

    def test_award_amount_transaction_transaction_id_column_is_document_number(
        self,
    ) -> None:
        # AWARD_AMOUNT_TRANSACTION's own Oracle "TRANSACTION_ID" column
        # is a VARCHAR2 that actually stores the Time and Money document
        # number - confirmed end to end here by loading a row and
        # checking the archive column is named document_number and
        # holds the document number string, never a numeric
        # transaction_id.
        with self._patched_oracle(
            versions=[_version_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        row = self._row(
            "award_amount_transaction", award_amount_transaction_id=12001
        )
        self.assertEqual(row["document_number"], "TNM-1")
        self.assertNotIn("transaction_id", row)

    def test_fanda_distribution_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(direct_cost=8000.00)
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["fanda_distribution_updated"], 1)
        row = self._row(
            "award_direct_fanda_distribution",
            award_direct_fanda_distribution_id=13001,
        )
        self.assertEqual(float(row["direct_cost"]), 8000.0)

    def test_fanda_distribution_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            amounts=[_amount_row(award_amount_info_id=502, award_id=2, award_number="A-0002")],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(
                    award_direct_fanda_distribution_id=13002,
                    award_id=2,
                    award_number="A-0002",
                    award_amount_info_id=502,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            amounts=[_amount_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_direct_fanda_distribution"
        )
        self.assertEqual(total, 2)

    def test_one_pending_transaction_maps_to_multiple_award_amount_info_rows(
        self,
    ) -> None:
        # The core "do not assume or enforce a 1:1 mapping" requirement:
        # one PendingTransaction/transaction_id can produce several
        # archive.award_amount_info rows (one per Award hop along the
        # hierarchy path, potentially once each for a pending and an
        # active Award version) - archive.award_amount_info.transaction_id
        # must allow duplicates, never be treated as unique.
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[
                _amount_row(award_amount_info_id=501, transaction_id=9001),
                _amount_row(award_amount_info_id=502, transaction_id=9001),
            ],
            pending_transaction=[_pending_transaction_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["amount_info_inserted"], 2)
        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_amount_info WHERE transaction_id = 9001"
        )
        self.assertEqual(total, 2)

    def test_budget_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[
                _award_budget_row(
                    budget_id=14001, award_id=1, description="Amended budget description"
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_updated"], 1)
        row = self._row("award_budget", budget_id=14001)
        self.assertEqual(row["description"], "Amended budget description")

    def test_budget_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget")
        self.assertEqual(total, 2)

    def test_budget_limit_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_limit=[
                _award_budget_limit_row(budget_limit_id=14101, budget_id=14001, award_id=1)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_limit=[
                _award_budget_limit_row(
                    budget_limit_id=14101, budget_id=14001, award_id=1, limit_amount=999.00
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_limit_updated"], 1)
        row = self._row("award_budget_limit", budget_limit_id=14101)
        self.assertEqual(float(row["limit_amount"]), 999.00)

    def test_budget_limit_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_limit=[
                _award_budget_limit_row(budget_limit_id=14102, budget_id=14002, award_id=2)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_limit=[
                _award_budget_limit_row(budget_limit_id=14101, budget_id=14001, award_id=1)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget_limit")
        self.assertEqual(total, 2)

    def test_budget_period_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(
                    budget_period_id=14201, budget_id=14001, award_id=1, total_cost=999.00
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_period_updated"], 1)
        row = self._row("award_budget_period", budget_period_id=14201)
        self.assertEqual(float(row["total_cost"]), 999.00)

    def test_budget_period_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget_period")
        self.assertEqual(total, 2)

    def test_budget_line_item_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301,
                    budget_id=14001,
                    budget_period_id=14201,
                    award_id=1,
                    line_item_cost=999.00,
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_line_item_updated"], 1)
        row = self._row("award_budget_line_item", budget_line_item_id=14301)
        self.assertEqual(float(row["line_item_cost"]), 999.00)

    def test_budget_line_item_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14302, budget_id=14002, budget_period_id=14202, award_id=2
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget_line_item")
        self.assertEqual(total, 2)

    def test_budget_line_item_calculated_amount_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14401,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14401,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                    calculated_cost=999.00,
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_line_item_calculated_amount_updated"], 1)
        row = self._row(
            "award_budget_line_item_calculated_amount", budget_line_item_calculated_amount_id=14401
        )
        self.assertEqual(float(row["calculated_cost"]), 999.00)

    def test_budget_line_item_calculated_amount_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14302, budget_id=14002, budget_period_id=14202, award_id=2
                )
            ],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14402,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    award_id=2,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14401,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_line_item_calculated_amount"
        )
        self.assertEqual(total, 2)

    def test_budget_personnel_detail_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                    salary_requested=999.00,
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_personnel_detail_updated"], 1)
        row = self._row("award_budget_personnel_detail", budget_personnel_line_item_id=14501)
        self.assertEqual(float(row["salary_requested"]), 999.00)

    def test_budget_personnel_detail_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14302, budget_id=14002, budget_period_id=14202, award_id=2
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14502,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    award_id=2,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget_personnel_detail")
        self.assertEqual(total, 2)

    def test_budget_personnel_calculated_amount_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14601,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    budget_personnel_line_item_id=14501,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14601,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    budget_personnel_line_item_id=14501,
                    award_id=1,
                    calculated_cost=999.00,
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_personnel_calculated_amount_updated"], 1)
        row = self._row(
            "award_budget_personnel_calculated_amount", budget_personnel_calculated_amount_id=14601
        )
        self.assertEqual(float(row["calculated_cost"]), 999.00)

    def test_budget_personnel_calculated_amount_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14302, budget_id=14002, budget_period_id=14202, award_id=2
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14502,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    award_id=2,
                )
            ],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14602,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    budget_personnel_line_item_id=14502,
                    award_id=2,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_line_item=[
                _award_budget_line_item_row(
                    budget_line_item_id=14301, budget_id=14001, budget_period_id=14201, award_id=1
                )
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14501,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    award_id=1,
                )
            ],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14601,
                    budget_id=14001,
                    budget_period_id=14201,
                    budget_line_item_id=14301,
                    budget_personnel_line_item_id=14501,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_personnel_calculated_amount"
        )
        self.assertEqual(total, 2)

    def test_budget_period_summary_calculated_amount_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14701,
                    budget_id=14001,
                    budget_period_id=14201,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14701,
                    budget_id=14001,
                    budget_period_id=14201,
                    award_id=1,
                    calculated_cost=999.00,
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_period_summary_calculated_amount_updated"], 1)
        row = self._row(
            "award_budget_period_summary_calculated_amount",
            award_budget_period_summary_calculated_amount_id=14701,
        )
        self.assertEqual(float(row["calculated_cost"]), 999.00)

    def test_budget_period_summary_calculated_amount_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14202, budget_id=14002, award_id=2)
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14702,
                    budget_id=14002,
                    budget_period_id=14202,
                    award_id=2,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_period=[
                _award_budget_period_row(budget_period_id=14201, budget_id=14001, award_id=1)
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14701,
                    budget_id=14001,
                    budget_period_id=14201,
                    award_id=1,
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_period_summary_calculated_amount"
        )
        self.assertEqual(total, 2)

    def test_budget_bundle_loads_in_fk_safe_parent_child_grandchild_order(
        self,
    ) -> None:
        # Award Budget is five levels deep (the deepest bundle in the
        # domain) and every level below award_budget has a REAL,
        # Postgres-enforced FK to its parent - budget_period.budget_id,
        # budget_line_item.budget_period_id,
        # budget_line_item_calculated_amount/budget_personnel_detail
        # .budget_line_item_id,
        # budget_personnel_calculated_amount.budget_personnel_line_item_id,
        # and budget_period_summary_calculated_amount.budget_period_id
        # (see docs/architecture/AWARD_BUDGET_DESIGN.md). If the upsert
        # loop ever inserted a child before its parent, this whole load
        # would fail with an IntegrityError - so a clean, single-pass
        # load proves the FK-safe ordering end to end, and every level's
        # own parent-pointer column is checked to prove it resolved to
        # the right ancestor row, not just any row.
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_limit=[_award_budget_limit_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row()
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row()
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row()
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_inserted"], 1)
        self.assertEqual(report["budget_limit_inserted"], 1)
        self.assertEqual(report["budget_period_inserted"], 1)
        self.assertEqual(report["budget_line_item_inserted"], 1)
        self.assertEqual(report["budget_line_item_calculated_amount_inserted"], 1)
        self.assertEqual(report["budget_personnel_detail_inserted"], 1)
        self.assertEqual(report["budget_personnel_calculated_amount_inserted"], 1)
        self.assertEqual(
            report["budget_period_summary_calculated_amount_inserted"], 1
        )

        self.assertEqual(
            self._row("award_budget_limit", budget_limit_id=14101)["budget_id"],
            14001,
        )
        self.assertEqual(
            self._row("award_budget_period", budget_period_id=14201)["budget_id"],
            14001,
        )
        self.assertEqual(
            self._row(
                "award_budget_line_item", budget_line_item_id=14301
            )["budget_period_id"],
            14201,
        )
        self.assertEqual(
            self._row(
                "award_budget_line_item_calculated_amount",
                budget_line_item_calculated_amount_id=14401,
            )["budget_line_item_id"],
            14301,
        )
        self.assertEqual(
            self._row(
                "award_budget_personnel_detail",
                budget_personnel_line_item_id=14501,
            )["budget_line_item_id"],
            14301,
        )
        self.assertEqual(
            self._row(
                "award_budget_personnel_calculated_amount",
                budget_personnel_calculated_amount_id=14601,
            )["budget_personnel_line_item_id"],
            14501,
        )
        self.assertEqual(
            self._row(
                "award_budget_period_summary_calculated_amount",
                award_budget_period_summary_calculated_amount_id=14701,
            )["budget_period_id"],
            14201,
        )

    def test_budget_line_item_calculated_amount_allows_multiple_rate_rows_per_line_item(
        self,
    ) -> None:
        # A single budget_line_item legitimately has more than one
        # calculated-amount row - e.g. one for on-campus overhead and a
        # separate one for the employee-benefit/fringe rate - so
        # budget_line_item_calculated_amount_id must be the only unique
        # key; budget_line_item_id must never be treated as unique.
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14401,
                    rate_class_code="OVERHEAD",
                    rate_type_code="OH1",
                ),
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14402,
                    rate_class_code="EB",
                    rate_type_code="EB1",
                ),
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_line_item_calculated_amount_inserted"], 2)
        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_line_item_calculated_amount "
            "WHERE budget_line_item_id = 14301"
        )
        self.assertEqual(total, 2)

    def test_budget_period_summary_calculated_amount_allows_both_rate_class_types(
        self,
    ) -> None:
        # AWD_BGT_PER_SUM_CALC_AMT serves two logical roles from one
        # table - fringe ('E') and F&A/overhead ('O') amounts for the
        # same budget_period - kept as one archive table with
        # rate_class_type intact (see the design doc's Findings), so
        # both rows for the same budget_period must coexist, never
        # collide on a spurious per-period uniqueness constraint.
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_period=[_award_budget_period_row()],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14701,
                    rate_class_type="E",
                ),
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14702,
                    rate_class_type="O",
                ),
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(
            report["budget_period_summary_calculated_amount_inserted"], 2
        )
        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_period_summary_calculated_amount "
            "WHERE budget_period_id = 14201"
        )
        self.assertEqual(total, 2)
        rate_types = self._scalar(
            "SELECT COUNT(DISTINCT rate_class_type) FROM "
            "archive.award_budget_period_summary_calculated_amount "
            "WHERE budget_period_id = 14201"
        )
        self.assertEqual(rate_types, 2)

    def test_budget_person_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_person=[_award_budget_person_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_person=[
                _award_budget_person_row(calculation_base=125000.00)
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_person_updated"], 1)
        row = self._row(
            "award_budget_person", budget_id=14001, person_sequence_number=1
        )
        self.assertEqual(float(row["calculation_base"]), 125000.00)

    def test_budget_person_does_not_touch_unrelated_existing_award(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            budget=[_award_budget_row(budget_id=14002, award_id=2)],
            budget_person=[
                _award_budget_person_row(budget_id=14002, award_id=2)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            budget=[_award_budget_row(budget_id=14001, award_id=1)],
            budget_person=[
                _award_budget_person_row(budget_id=14001, award_id=1)
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_budget_person")
        self.assertEqual(total, 2)

    def test_budget_person_composite_pk_allows_multiple_people_per_budget(
        self,
    ) -> None:
        # BUDGET_PERSONS has no surrogate id - PERSON_SEQUENCE_NUMBER is
        # only unique within one BUDGET_ID, never treated as globally
        # unique, so a budget with several people must insert one row
        # per (budget_id, person_sequence_number) pair.
        with self._patched_oracle(
            versions=[_version_row()],
            budget=[_award_budget_row()],
            budget_person=[
                _award_budget_person_row(person_sequence_number=1),
                _award_budget_person_row(
                    person_sequence_number=2,
                    person_id="P456",
                    person_name="Second Researcher",
                ),
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["budget_person_inserted"], 2)
        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_person WHERE budget_id = 14001"
        )
        self.assertEqual(total, 2)

    def test_transferring_sponsor_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            transferring_sponsor=[_award_transferring_sponsor_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            transferring_sponsor=[
                _award_transferring_sponsor_row(
                    sponsor_code="NSF", sponsor_name="National Science Foundation"
                )
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["transferring_sponsor_updated"], 1)
        row = self._row(
            "award_transferring_sponsor", award_transferring_sponsor_id=14801
        )
        self.assertEqual(row["sponsor_code"], "NSF")
        self.assertEqual(row["sponsor_name"], "National Science Foundation")

    def test_transferring_sponsor_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            transferring_sponsor=[
                _award_transferring_sponsor_row(
                    award_transferring_sponsor_id=14802,
                    award_id=2,
                    award_number="A-0002",
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            transferring_sponsor=[_award_transferring_sponsor_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_transferring_sponsor"
        )
        self.assertEqual(total, 2)

    def test_award_transmission_value_change_produces_an_update(self) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission=[_award_transmission_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission=[
                _award_transmission_row(success_indicator="N")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["award_transmission_updated"], 1)
        row = self._row("award_transmission", transmission_id=15001)
        self.assertEqual(row["success_indicator"], "N")

    def test_award_transmission_award_id_reassignment_updates_in_place_without_duplicating(
        self,
    ) -> None:
        # AwardServiceImpl.updateTransmissionHistory can UPDATE an
        # existing AwardTransmission row's AWARD_ID in place to point
        # at a later Award version, rather than inserting a new row.
        # This archive captures whatever AWARD_ID Oracle shows today
        # (the same "capture what's there now" discipline used
        # throughout this project) - re-extracting after such a
        # reassignment must update the existing row's award_id in
        # place, keyed by the immutable transmission_id, never
        # duplicate it into a second row.
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            award_transmission=[_award_transmission_row(award_id=1)],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            award_transmission=[
                _award_transmission_row(award_id=2, award_number="A-0002")
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 2)

        self.assertEqual(report["award_transmission_updated"], 1)
        total = self._scalar("SELECT COUNT(*) FROM archive.award_transmission")
        self.assertEqual(total, 1)
        row = self._row("award_transmission", transmission_id=15001)
        self.assertEqual(row["award_id"], 2)

    def test_award_transmission_does_not_touch_unrelated_existing_award(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=2, award_number="A-0002")],
            award_transmission=[
                _award_transmission_row(
                    transmission_id=15002, award_id=2, award_number="A-0002"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 2)

        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")],
            award_transmission=[_award_transmission_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        total = self._scalar("SELECT COUNT(*) FROM archive.award_transmission")
        self.assertEqual(total, 2)

    def test_award_transmission_retransmission_preserves_prior_attempt_as_separate_row(
        self,
    ) -> None:
        # A retransmission of the same Award must never collapse into
        # the prior attempt's row - Oracle assigns a fresh
        # TRANSMISSION_ID per genuine attempt, and the archive UPSERT
        # is keyed on that real PK, so two distinct attempts always
        # insert two distinct rows (see
        # docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md).
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission=[
                _award_transmission_row(
                    transmission_id=15001, success_indicator="N"
                ),
                _award_transmission_row(
                    transmission_id=15002, success_indicator="Y"
                ),
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["award_transmission_inserted"], 2)
        total = self._scalar("SELECT COUNT(*) FROM archive.award_transmission")
        self.assertEqual(total, 2)
        first_attempt = self._row("award_transmission", transmission_id=15001)
        second_attempt = self._row("award_transmission", transmission_id=15002)
        self.assertEqual(first_attempt["success_indicator"], "N")
        self.assertEqual(second_attempt["success_indicator"], "Y")

    def test_award_transmission_raw_xml_round_trips_byte_for_byte(self) -> None:
        sent_data = (
            "<Outbound>\n"
            '  <Line quote="&quot;quoted&quot;">Tab\there &amp; more</Line>\n'
            "  <Unicode>café — ßé</Unicode>\n"
            "</Outbound>\n"
        )
        returned_data = (
            "<Inbound><Message>SAP said: it&apos;s <b>done</b></Message>"
            "</Inbound>"
        )
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission=[
                _award_transmission_row(
                    sent_data=sent_data, returned_data=returned_data
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        row = self._row("award_transmission", transmission_id=15001)
        self.assertEqual(row["sent_data"], sent_data)
        self.assertEqual(row["returned_data"], returned_data)

    def test_award_transmission_child_overhead_key_base_code_off_campus_preserved_exactly(
        self,
    ) -> None:
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission_child=[
                _award_transmission_child_row(
                    overhead_key="OFFCAMP", base_code="02", off_campus="Y"
                )
            ],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        row = self._row(
            "award_transmission_child", transmission_child_id=15101
        )
        self.assertEqual(row["overhead_key"], "OFFCAMP")
        self.assertEqual(row["base_code"], "02")
        self.assertEqual(row["off_campus"], "Y")

        # Re-extraction with the SAME F&A basis values must report
        # unchanged, not a spurious update - and a genuine change to
        # any one of the three must be preserved exactly, since these
        # are frequently unrecoverable from any other archived table
        # once the source budget has moved past "to be posted".
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission_child=[
                _award_transmission_child_row(
                    overhead_key="OFFCAMP", base_code="02", off_campus="Y"
                )
            ],
        ):
            unchanged_report = award_loader._run_load_award_id(self.engine, 1)
        self.assertEqual(unchanged_report["award_transmission_child_unchanged"], 1)

        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission_child=[
                _award_transmission_child_row(
                    overhead_key="MTDC", base_code="01", off_campus="N"
                )
            ],
        ):
            updated_report = award_loader._run_load_award_id(self.engine, 1)
        self.assertEqual(updated_report["award_transmission_child_updated"], 1)
        updated_row = self._row(
            "award_transmission_child", transmission_child_id=15101
        )
        self.assertEqual(updated_row["overhead_key"], "MTDC")
        self.assertEqual(updated_row["base_code"], "01")
        self.assertEqual(updated_row["off_campus"], "N")

    def test_award_transmission_child_bare_transmission_id_persists_without_fk_enforcement(
        self,
    ) -> None:
        # transmission_id on award_transmission_child is deliberately a
        # bare, unenforced column (no Postgres FK to
        # archive.award_transmission) - a child Award's family can be
        # loaded before its parent transmission's own root Award
        # family, since they routinely belong to different
        # award_number families and are loaded independently.
        with self._patched_oracle(
            versions=[_version_row()],
            award_transmission_child=[
                _award_transmission_child_row(transmission_id=999999)
            ],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["award_transmission_child_inserted"], 1)
        row = self._row(
            "award_transmission_child", transmission_child_id=15101
        )
        self.assertEqual(row["transmission_id"], 999999)

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

    def test_basis_and_method_of_payment_change_produces_an_update(self) -> None:
        with self._patched_oracle(versions=[_version_row()]):
            award_loader._run_load_award_id(self.engine, 1)

        with self._patched_oracle(
            versions=[
                _version_row(
                    basis_of_payment_code="03",
                    basis_of_payment_description="Fixed Price",
                    method_of_payment_code="04",
                    method_of_payment_description="Advance",
                )
            ]
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["updated"], 1)
        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["basis_of_payment_code"], "03")
        self.assertEqual(version_row["basis_of_payment_description"], "Fixed Price")
        self.assertEqual(version_row["method_of_payment_code"], "04")
        self.assertEqual(version_row["method_of_payment_description"], "Advance")

    def test_basis_and_method_of_payment_code_preserves_leading_zero(self) -> None:
        # basis_of_payment_code/method_of_payment_code are VARCHAR2(3) on
        # AWARD, not INTEGER like status_code/transaction_type_code - a
        # leading zero (e.g. "01") is meaningful data, not a formatting
        # artifact, and must never be numeric-converted away.
        with self._patched_oracle(
            versions=[
                _version_row(
                    basis_of_payment_code="01", method_of_payment_code="02"
                )
            ]
        ):
            award_loader._run_load_award_id(self.engine, 1)

        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["basis_of_payment_code"], "01")
        self.assertEqual(version_row["method_of_payment_code"], "02")

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
            notepad=[_notepad_row()],
            closeout=[_closeout_row()],
            payment_schedule=[_payment_schedule_row()],
            approved_subaward=[_approved_subaward_row()],
            cfda=[_cfda_row()],
            cost_share=[_cost_share_row()],
            fanda_rate=[_fanda_rate_row()],
            science_keyword=[_science_keyword_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
            approved_equipment=[_approved_equipment_row()],
            approved_foreign_travel=[_approved_foreign_travel_row()],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
            comment=[_award_comment_row()],
            extension=[_award_extension_row()],
            cgb=[_award_cgb_row()],
            hierarchy=[_award_hierarchy_row()],
            tnm_document=[_time_and_money_document_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
            transaction_detail=[_transaction_detail_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
            budget=[_award_budget_row()],
            budget_limit=[_award_budget_limit_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row()
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row()
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row()
            ],
            budget_person=[_award_budget_person_row()],
            transferring_sponsor=[_award_transferring_sponsor_row()],
            award_transmission=[_award_transmission_row()],
            award_transmission_child=[_award_transmission_child_row()],
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
        self.assertEqual(report["notepad_inserted"], 1)
        self.assertEqual(report["closeout_inserted"], 1)
        self.assertEqual(report["payment_schedule_inserted"], 1)
        self.assertEqual(report["approved_subaward_inserted"], 1)
        self.assertEqual(report["cfda_inserted"], 1)
        self.assertEqual(report["cost_share_inserted"], 1)
        self.assertEqual(report["fanda_rate_inserted"], 1)
        self.assertEqual(report["science_keyword_inserted"], 1)
        self.assertEqual(report["special_review_inserted"], 1)
        self.assertEqual(report["special_review_exemption_inserted"], 1)
        self.assertEqual(report["approved_equipment_inserted"], 1)
        self.assertEqual(report["approved_foreign_travel_inserted"], 1)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 1)
        self.assertEqual(report["comment_inserted"], 1)
        self.assertEqual(report["extension_inserted"], 1)
        self.assertEqual(report["cgb_inserted"], 1)
        self.assertEqual(report["hierarchy_inserted"], 1)
        self.assertEqual(report["tnm_document_inserted"], 1)
        self.assertEqual(report["pending_transaction_inserted"], 1)
        self.assertEqual(report["pending_transaction_extension_inserted"], 1)
        self.assertEqual(report["transaction_detail_inserted"], 1)
        self.assertEqual(report["award_amount_transaction_inserted"], 1)
        self.assertEqual(report["fanda_distribution_inserted"], 1)
        self.assertEqual(report["budget_inserted"], 1)
        self.assertEqual(report["budget_limit_inserted"], 1)
        self.assertEqual(report["budget_period_inserted"], 1)
        self.assertEqual(report["budget_line_item_inserted"], 1)
        self.assertEqual(report["budget_line_item_calculated_amount_inserted"], 1)
        self.assertEqual(report["budget_personnel_detail_inserted"], 1)
        self.assertEqual(report["budget_personnel_calculated_amount_inserted"], 1)
        self.assertEqual(
            report["budget_period_summary_calculated_amount_inserted"], 1
        )
        self.assertEqual(report["budget_person_inserted"], 1)
        self.assertEqual(report["transferring_sponsor_inserted"], 1)
        self.assertEqual(report["award_transmission_inserted"], 1)
        self.assertEqual(report["award_transmission_child_inserted"], 1)

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
        notepad_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_notepad"
        )
        self.assertEqual(notepad_count, 0)
        closeout_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_closeout"
        )
        self.assertEqual(closeout_count, 0)
        payment_schedule_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_payment_schedule"
        )
        self.assertEqual(payment_schedule_count, 0)
        approved_subaward_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_subaward"
        )
        self.assertEqual(approved_subaward_count, 0)
        self.assertEqual(self._scalar("SELECT COUNT(*) FROM archive.award_cfda"), 0)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cost_share"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_fanda_rate"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_science_keyword"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_special_review"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_special_review_exemption"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_approved_equipment"),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_approved_foreign_travel"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_comment"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_extension"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cgb"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.time_and_money_document"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.pending_transaction"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.pending_transaction_extension"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.transaction_detail"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_amount_transaction"),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_direct_fanda_distribution"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_limit"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_period"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_line_item"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM "
                "archive.award_budget_line_item_calculated_amount"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_budget_personnel_detail"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM "
                "archive.award_budget_personnel_calculated_amount"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM "
                "archive.award_budget_period_summary_calculated_amount"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_person"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_transferring_sponsor"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_transmission"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_transmission_child"
            ),
            0,
        )
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
        # --validation-overlap specifically: always the smallest N,
        # sorted in Python from an unsorted underlying source - see
        # CreateAwardBatchProductionSelectionTest for the default
        # (production) selection mode's own behavior, which reads a
        # different, already-ascending Oracle source instead.
        with self._patched_oracle(
            versions=[
                _version_row(award_id=aid, award_number=f"A-{aid:04d}")
                for aid in [5, 3, 1, 4, 2]
            ]
        ):
            result = award_loader._run_create_award_batch(
                self.engine, 3, validation_overlap=True
            )

        self.assertEqual(result["selected_award_ids"], [1, 2, 3])
        self.assertEqual(result["selected_count"], 3)

    def test_persists_membership_with_generic_batch_domain(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            result = award_loader._run_create_award_batch(
                self.engine, 1, validation_overlap=True
            )

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(batch_row["domain"], "AWARD")
        self.assertEqual(batch_row["entity_type"], "AWARD")


class CreateAwardBatchProductionSelectionTest(_AwardPostgresTestCase):
    """Default (validation_overlap=False) --create-batch selection mode:
    excludes award_ids already COMPLETED in a prior batch, and award_ids
    claimed by a still-active (READY/PROCESSING) batch, so repeated
    calls advance through the population instead of reselecting the
    same smallest N every time. Uses AWARD_IDS_ASCENDING_ORACLE_SQL
    (patched via the award_ids= kwarg), never VERSIONS_ORACLE_SQL."""

    def _seed_batch(
        self, award_ids: list[int], *, batch_status: str, item_status: str
    ) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES ('AWARD', 'AWARD', :size, :batch_status, "
                    "'TEST_FIXTURE') RETURNING batch_id"
                ),
                {"size": len(award_ids), "batch_status": batch_status},
            ).scalar_one()
            for ordinal, award_id in enumerate(award_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :award_id, :ordinal, :item_status)"
                    ),
                    {
                        "batch_id": batch_id,
                        "award_id": award_id,
                        "ordinal": ordinal,
                        "item_status": item_status,
                    },
                )
        return int(batch_id)

    def test_first_production_batch_selects_ids_1_through_5000(self) -> None:
        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 5001)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5000)

        self.assertEqual(result["selected_count"], 5000)
        self.assertEqual(result["selected_award_ids"][0], 1)
        self.assertEqual(result["selected_award_ids"][-1], 5000)
        self.assertEqual(
            result["selected_award_ids"], list(range(1, 5001))
        )

    def test_next_production_batch_selects_5001_through_10000_after_completion(
        self,
    ) -> None:
        self._seed_batch(
            list(range(1, 5001)),
            batch_status="COMPLETED",
            item_status="COMPLETED",
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 10001)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5000)

        self.assertEqual(result["selected_count"], 5000)
        self.assertEqual(
            result["selected_award_ids"], list(range(5001, 10001))
        )

    def test_completed_ids_are_excluded_even_if_their_batch_is_not_fully_completed(
        self,
    ) -> None:
        # A batch can be PARTIAL/FAILED overall while some of its own
        # items individually succeeded - those specific award_ids must
        # still never be reselected.
        self._seed_batch(
            [1, 2, 3], batch_status="PARTIAL", item_status="COMPLETED"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        self.assertEqual(result["selected_award_ids"], [4, 5, 6, 7, 8])

    def test_failed_ids_remain_eligible_once_their_batch_is_resolved(self) -> None:
        self._seed_batch(
            [1, 2, 3], batch_status="FAILED", item_status="FAILED"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        # FAILED items in a resolved (non-active) batch are eligible
        # again - production mode never permanently excludes a failure.
        self.assertEqual(result["selected_award_ids"], [1, 2, 3, 4, 5])

    def test_pending_ids_remain_eligible_once_their_batch_is_resolved(self) -> None:
        self._seed_batch(
            [1, 2, 3], batch_status="ABANDONED", item_status="PENDING"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        self.assertEqual(result["selected_award_ids"], [1, 2, 3, 4, 5])

    def test_ready_batch_items_are_not_selected_twice(self) -> None:
        self._seed_batch(
            [1, 2, 3], batch_status="READY", item_status="PENDING"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        self.assertEqual(result["selected_award_ids"], [4, 5, 6, 7, 8])

    def test_processing_batch_items_are_not_selected_twice(self) -> None:
        self._seed_batch(
            [1, 2, 3], batch_status="PROCESSING", item_status="PENDING"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        self.assertEqual(result["selected_award_ids"], [4, 5, 6, 7, 8])

    def test_created_and_metadata_loading_batches_do_not_exclude_their_items(
        self,
    ) -> None:
        # Only READY/PROCESSING count as "active" for exclusion purposes
        # - a batch that's merely CREATED or METADATA_LOADING (i.e. not
        # yet confirmed READY to load) does not lock out its own
        # award_ids from a fresh production batch.
        self._seed_batch(
            [1, 2, 3], batch_status="CREATED", item_status="PENDING"
        )

        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 11)]
        ):
            result = award_loader._run_create_award_batch(self.engine, 5)

        self.assertEqual(result["selected_award_ids"], [1, 2, 3, 4, 5])

    def test_validation_overlap_still_selects_smallest_n_every_time(self) -> None:
        self._seed_batch(
            list(range(1, 5001)),
            batch_status="COMPLETED",
            item_status="COMPLETED",
        )

        with self._patched_oracle(
            versions=[
                _version_row(award_id=aid, award_number=f"A-{aid:04d}")
                for aid in range(1, 10001)
            ]
        ):
            result = award_loader._run_create_award_batch(
                self.engine, 5000, validation_overlap=True
            )

        # Completion state is entirely ignored in validation-overlap
        # mode - always the smallest N, regardless of what's already
        # COMPLETED elsewhere.
        self.assertEqual(result["selected_award_ids"], list(range(1, 5001)))

    def test_deterministic_rerun_with_no_state_change(self) -> None:
        with self._patched_oracle(
            award_ids=[{"award_id": aid} for aid in range(1, 5001)]
        ):
            first = award_loader._run_create_award_batch(self.engine, 5000)
            second = award_loader._run_create_award_batch(self.engine, 5000)

        # No batch was completed/activated between the two calls, so
        # the exclusion set is identical both times - same selection.
        self.assertEqual(
            first["selected_award_ids"], second["selected_award_ids"]
        )
        self.assertEqual(first["selected_award_ids"], list(range(1, 5001)))

    def test_production_selection_strategy_is_recorded(self) -> None:
        with self._patched_oracle(
            award_ids=[{"award_id": 1}]
        ):
            result = award_loader._run_create_award_batch(self.engine, 1)

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(
            batch_row["selection_strategy"],
            "ORACLE_SCAN_ASCENDING_AWARD_ID_EXCL_COMPLETED",
        )

    def test_validation_overlap_selection_strategy_is_recorded(self) -> None:
        with self._patched_oracle(
            versions=[_version_row(award_id=1, award_number="A-0001")]
        ):
            result = award_loader._run_create_award_batch(
                self.engine, 1, validation_overlap=True
            )

        batch_row = self._row("etl_batch", batch_id=result["batch_id"])
        self.assertEqual(
            batch_row["selection_strategy"],
            "ORACLE_SCAN_ASCENDING_AWARD_ID_VALIDATION_OVERLAP",
        )


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
            notepad=[
                _notepad_row(award_notepad_id=1701, award_id=1),
                _notepad_row(
                    award_notepad_id=1702,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            closeout=[
                _closeout_row(award_closeout_id=1801, award_id=1),
                _closeout_row(
                    award_closeout_id=1802,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            payment_schedule=[
                _payment_schedule_row(award_payment_schedule_id=1901, award_id=1),
                _payment_schedule_row(
                    award_payment_schedule_id=1902,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_subaward=[
                _approved_subaward_row(
                    award_approved_subaward_id=2001, award_id=1
                ),
                _approved_subaward_row(
                    award_approved_subaward_id=2002,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            cfda=[
                _cfda_row(award_cfda_id=2101, award_id=1),
                _cfda_row(award_cfda_id=2102, award_id=2, award_number="A-0002"),
            ],
            cost_share=[
                _cost_share_row(award_cost_share_id=2201, award_id=1),
                _cost_share_row(
                    award_cost_share_id=2202, award_id=2, award_number="A-0002"
                ),
            ],
            fanda_rate=[
                _fanda_rate_row(award_fanda_rate_id=2301, award_id=1),
                _fanda_rate_row(
                    award_fanda_rate_id=2302, award_id=2, award_number="A-0002"
                ),
            ],
            science_keyword=[
                _science_keyword_row(award_science_keyword_id=2401, award_id=1),
                _science_keyword_row(
                    award_science_keyword_id=2402,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            special_review=[
                _special_review_row(award_special_review_id=2501, award_id=1),
                _special_review_row(
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            special_review_exemption=[
                _special_review_exemption_row(
                    award_special_review_exemption_id=2601,
                    award_special_review_id=2501,
                    award_id=1,
                ),
                _special_review_exemption_row(
                    award_special_review_exemption_id=2602,
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_equipment=[
                _approved_equipment_row(
                    award_approved_equipment_id=2701, award_id=1
                ),
                _approved_equipment_row(
                    award_approved_equipment_id=2702,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_foreign_travel=[
                _approved_foreign_travel_row(
                    award_approved_foreign_travel_id=2801, award_id=1
                ),
                _approved_foreign_travel_row(
                    award_approved_foreign_travel_id=2802,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(award_number="A-0001"),
                _subcontracting_budgeted_goals_row(award_number="A-0002"),
            ],
            comment=[
                _award_comment_row(award_comment_id=2901, award_id=1),
                _award_comment_row(
                    award_comment_id=2902, award_id=2, award_number="A-0002"
                ),
            ],
            extension=[
                _award_extension_row(award_id=1),
                _award_extension_row(award_id=2, award_number="A-0002"),
            ],
            cgb=[
                _award_cgb_row(award_id=1),
                _award_cgb_row(award_id=2, award_number="A-0002"),
            ],
            hierarchy=[
                _award_hierarchy_row(award_hierarchy_id=10001),
                _award_hierarchy_row(
                    award_hierarchy_id=10002,
                    root_award_number="A-0002",
                    award_number="A-0002",
                    originating_award_number="A-0002",
                ),
            ],
            tnm_document=[
                _time_and_money_document_row(),
                _time_and_money_document_row(
                    document_number="TNM-2", award_number="A-0002"
                ),
            ],
            pending_transaction=[
                _pending_transaction_row(),
                _pending_transaction_row(
                    transaction_id=9002, destination_award_number="A-0002"
                ),
            ],
            pending_transaction_extension=[
                _pending_transaction_extension_row(),
                _pending_transaction_extension_row(
                    transaction_id=9002,
                    source_award_number="000000-00000",
                    destination_award_number="A-0002",
                ),
            ],
            transaction_detail=[
                _transaction_detail_row(),
                _transaction_detail_row(
                    transaction_detail_id=11002, award_number="A-0002"
                ),
            ],
            award_amount_transaction=[
                _award_amount_transaction_row(),
                _award_amount_transaction_row(
                    award_amount_transaction_id=12002, award_number="A-0002"
                ),
            ],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(award_amount_info_id=None),
                _award_direct_fanda_distribution_row(
                    award_direct_fanda_distribution_id=13002,
                    award_id=2,
                    award_number="A-0002",
                    award_amount_info_id=None,
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
        self.assertEqual(report["notepad_inserted"], 2)
        self.assertEqual(report["closeout_inserted"], 2)
        self.assertEqual(report["payment_schedule_inserted"], 2)
        self.assertEqual(report["approved_subaward_inserted"], 2)
        self.assertEqual(report["cfda_inserted"], 2)
        self.assertEqual(report["cost_share_inserted"], 2)
        self.assertEqual(report["fanda_rate_inserted"], 2)
        self.assertEqual(report["science_keyword_inserted"], 2)
        self.assertEqual(report["special_review_inserted"], 2)
        self.assertEqual(report["special_review_exemption_inserted"], 2)
        self.assertEqual(report["approved_equipment_inserted"], 2)
        self.assertEqual(report["approved_foreign_travel_inserted"], 2)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 2)
        self.assertEqual(report["comment_inserted"], 2)
        self.assertEqual(report["extension_inserted"], 2)
        self.assertEqual(report["cgb_inserted"], 2)
        self.assertEqual(report["hierarchy_inserted"], 2)
        self.assertEqual(report["tnm_document_inserted"], 2)
        self.assertEqual(report["pending_transaction_inserted"], 2)
        self.assertEqual(report["pending_transaction_extension_inserted"], 2)
        self.assertEqual(report["transaction_detail_inserted"], 2)
        self.assertEqual(report["award_amount_transaction_inserted"], 2)
        self.assertEqual(report["fanda_distribution_inserted"], 2)

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
        notepad_total = self._scalar("SELECT COUNT(*) FROM archive.award_notepad")
        self.assertEqual(notepad_total, 2)
        closeout_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_closeout"
        )
        self.assertEqual(closeout_total, 2)
        payment_schedule_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_payment_schedule"
        )
        self.assertEqual(payment_schedule_total, 2)
        approved_subaward_total = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_subaward"
        )
        self.assertEqual(approved_subaward_total, 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cfda"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cost_share"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_fanda_rate"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_science_keyword"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_special_review"), 2
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_special_review_exemption"
            ),
            2,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_approved_equipment"),
            2,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_approved_foreign_travel"
            ),
            2,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
            ),
            2,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_comment"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_extension"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cgb"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.time_and_money_document"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.pending_transaction"), 2
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.pending_transaction_extension"
            ),
            2,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.transaction_detail"), 2
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_amount_transaction"),
            2,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_direct_fanda_distribution"
            ),
            2,
        )

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
        # the forty-seven non-versions Award extraction sources is read
        # exactly once for this whole 3-family batch, not once per
        # family (which would be the families x tables scaling this
        # refactor removes). VERSIONS_ORACLE_SQL is legitimately read twice -
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
            award_loader.NOTEPAD_ORACLE_SQL,
            award_loader.CLOSEOUT_ORACLE_SQL,
            award_loader.PAYMENT_SCHEDULE_ORACLE_SQL,
            award_loader.APPROVED_SUBAWARD_ORACLE_SQL,
            award_loader.CFDA_ORACLE_SQL,
            award_loader.COST_SHARE_ORACLE_SQL,
            award_loader.FANDA_RATE_ORACLE_SQL,
            award_loader.SCIENCE_KEYWORD_ORACLE_SQL,
            award_loader.SPECIAL_REVIEW_ORACLE_SQL,
            award_loader.SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL,
            award_loader.APPROVED_EQUIPMENT_ORACLE_SQL,
            award_loader.APPROVED_FOREIGN_TRAVEL_ORACLE_SQL,
            award_loader.SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL,
            award_loader.COMMENT_ORACLE_SQL,
            award_loader.EXTENSION_ORACLE_SQL,
            award_loader.CGB_ORACLE_SQL,
            award_loader.HIERARCHY_ORACLE_SQL,
            award_loader.TIME_AND_MONEY_DOCUMENT_ORACLE_SQL,
            award_loader.PENDING_TRANSACTION_ORACLE_SQL,
            award_loader.PENDING_TRANSACTION_EXTENSION_ORACLE_SQL,
            award_loader.TRANSACTION_DETAIL_ORACLE_SQL,
            award_loader.AWARD_AMOUNT_TRANSACTION_ORACLE_SQL,
            award_loader.AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL,
            award_loader.BUDGET_ORACLE_SQL,
            award_loader.BUDGET_LIMIT_ORACLE_SQL,
            award_loader.BUDGET_PERIOD_ORACLE_SQL,
            award_loader.BUDGET_LINE_ITEM_ORACLE_SQL,
            award_loader.BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL,
            award_loader.BUDGET_PERSONNEL_DETAIL_ORACLE_SQL,
            award_loader.BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL,
            award_loader.BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL,
            award_loader.BUDGET_PERSON_ORACLE_SQL,
            award_loader.TRANSFERRING_SPONSOR_ORACLE_SQL,
            award_loader.AWARD_TRANSMISSION_ORACLE_SQL,
            award_loader.AWARD_TRANSMISSION_CHILD_ORACLE_SQL,
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

    def test_notepad_dry_run_persists_nothing_across_the_whole_batch(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            notepad=[
                _notepad_row(award_notepad_id=1701, award_id=1),
                _notepad_row(
                    award_notepad_id=1702, award_id=2, award_number="A-0002"
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["notepad_inserted"], 2)
        notepad_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_notepad"
        )
        self.assertEqual(notepad_count, 0)

    def test_budget_dry_run_persists_nothing_across_the_whole_batch(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[
                _award_budget_row(),
                _award_budget_row(budget_id=14002, award_id=2),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["budget_inserted"], 2)
        budget_count = self._scalar("SELECT COUNT(*) FROM archive.award_budget")
        self.assertEqual(budget_count, 0)

    def test_reporting_subaward_summary_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            closeout=[
                _closeout_row(award_closeout_id=1801, award_id=1),
                _closeout_row(
                    award_closeout_id=1802, award_id=2, award_number="A-0002"
                ),
            ],
            payment_schedule=[
                _payment_schedule_row(award_payment_schedule_id=1901, award_id=1),
                _payment_schedule_row(
                    award_payment_schedule_id=1902,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_subaward=[
                _approved_subaward_row(
                    award_approved_subaward_id=2001, award_id=1
                ),
                _approved_subaward_row(
                    award_approved_subaward_id=2002,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["closeout_inserted"], 2)
        self.assertEqual(report["payment_schedule_inserted"], 2)
        self.assertEqual(report["approved_subaward_inserted"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_closeout"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_payment_schedule"),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_approved_subaward"),
            0,
        )

    def test_reporting_subaward_summary_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            closeout=[
                _closeout_row(award_closeout_id=1801, award_id=1),
                _closeout_row(
                    award_closeout_id=1802, award_id=2, award_number="A-0002"
                ),
            ],
            payment_schedule=[
                _payment_schedule_row(award_payment_schedule_id=1901, award_id=1),
                _payment_schedule_row(
                    award_payment_schedule_id=1902,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_subaward=[
                _approved_subaward_row(
                    award_approved_subaward_id=2001, award_id=1
                ),
                _approved_subaward_row(
                    award_approved_subaward_id=2002,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["closeout_inserted"], 0)
        self.assertEqual(report["closeout_updated"], 0)
        self.assertEqual(report["closeout_unchanged"], 2)
        self.assertEqual(report["payment_schedule_inserted"], 0)
        self.assertEqual(report["payment_schedule_updated"], 0)
        self.assertEqual(report["payment_schedule_unchanged"], 2)
        self.assertEqual(report["approved_subaward_inserted"], 0)
        self.assertEqual(report["approved_subaward_updated"], 0)
        self.assertEqual(report["approved_subaward_unchanged"], 2)

    def test_special_approvals_compliance_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            cfda=[
                _cfda_row(award_cfda_id=2101, award_id=1),
                _cfda_row(award_cfda_id=2102, award_id=2, award_number="A-0002"),
            ],
            special_review=[
                _special_review_row(award_special_review_id=2501, award_id=1),
                _special_review_row(
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            special_review_exemption=[
                _special_review_exemption_row(
                    award_special_review_exemption_id=2601,
                    award_special_review_id=2501,
                    award_id=1,
                ),
                _special_review_exemption_row(
                    award_special_review_exemption_id=2602,
                    award_special_review_id=2502,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(award_number="A-0001"),
                _subcontracting_budgeted_goals_row(award_number="A-0002"),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["cfda_inserted"], 2)
        self.assertEqual(report["special_review_inserted"], 2)
        self.assertEqual(report["special_review_exemption_inserted"], 2)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 2)
        self.assertEqual(self._scalar("SELECT COUNT(*) FROM archive.award_cfda"), 0)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_special_review"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_special_review_exemption"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
            ),
            0,
        )

    def test_special_approvals_compliance_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            cost_share=[
                _cost_share_row(award_cost_share_id=2201, award_id=1),
                _cost_share_row(
                    award_cost_share_id=2202, award_id=2, award_number="A-0002"
                ),
            ],
            fanda_rate=[
                _fanda_rate_row(award_fanda_rate_id=2301, award_id=1),
                _fanda_rate_row(
                    award_fanda_rate_id=2302, award_id=2, award_number="A-0002"
                ),
            ],
            science_keyword=[
                _science_keyword_row(award_science_keyword_id=2401, award_id=1),
                _science_keyword_row(
                    award_science_keyword_id=2402,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_equipment=[
                _approved_equipment_row(
                    award_approved_equipment_id=2701, award_id=1
                ),
                _approved_equipment_row(
                    award_approved_equipment_id=2702,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            approved_foreign_travel=[
                _approved_foreign_travel_row(
                    award_approved_foreign_travel_id=2801, award_id=1
                ),
                _approved_foreign_travel_row(
                    award_approved_foreign_travel_id=2802,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(award_number="A-0001"),
                _subcontracting_budgeted_goals_row(award_number="A-0002"),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["cost_share_inserted"], 0)
        self.assertEqual(report["cost_share_updated"], 0)
        self.assertEqual(report["cost_share_unchanged"], 2)
        self.assertEqual(report["fanda_rate_inserted"], 0)
        self.assertEqual(report["fanda_rate_updated"], 0)
        self.assertEqual(report["fanda_rate_unchanged"], 2)
        self.assertEqual(report["science_keyword_inserted"], 0)
        self.assertEqual(report["science_keyword_updated"], 0)
        self.assertEqual(report["science_keyword_unchanged"], 2)
        self.assertEqual(report["approved_equipment_inserted"], 0)
        self.assertEqual(report["approved_equipment_updated"], 0)
        self.assertEqual(report["approved_equipment_unchanged"], 2)
        self.assertEqual(report["approved_foreign_travel_inserted"], 0)
        self.assertEqual(report["approved_foreign_travel_updated"], 0)
        self.assertEqual(report["approved_foreign_travel_unchanged"], 2)
        self.assertEqual(report["subcontracting_budgeted_goals_inserted"], 0)
        self.assertEqual(report["subcontracting_budgeted_goals_updated"], 0)
        self.assertEqual(report["subcontracting_budgeted_goals_unchanged"], 2)

    def test_award_comment_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            comment=[
                _award_comment_row(award_comment_id=2901, award_id=1),
                _award_comment_row(
                    award_comment_id=2902, award_id=2, award_number="A-0002"
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["comment_inserted"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_comment"), 0
        )

    def test_award_comment_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            comment=[
                _award_comment_row(award_comment_id=2901, award_id=1),
                _award_comment_row(
                    award_comment_id=2902, award_id=2, award_number="A-0002"
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["comment_inserted"], 0)
        self.assertEqual(report["comment_updated"], 0)
        self.assertEqual(report["comment_unchanged"], 2)

    def test_award_extension_cgb_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            extension=[
                _award_extension_row(award_id=1),
                _award_extension_row(award_id=2, award_number="A-0002"),
            ],
            cgb=[
                _award_cgb_row(award_id=1),
                _award_cgb_row(award_id=2, award_number="A-0002"),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["extension_inserted"], 2)
        self.assertEqual(report["cgb_inserted"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_extension"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cgb"), 0
        )

    def test_award_extension_cgb_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            extension=[
                _award_extension_row(award_id=1),
                _award_extension_row(award_id=2, award_number="A-0002"),
            ],
            cgb=[
                _award_cgb_row(award_id=1),
                _award_cgb_row(award_id=2, award_number="A-0002"),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["extension_inserted"], 0)
        self.assertEqual(report["extension_updated"], 0)
        self.assertEqual(report["extension_unchanged"], 2)
        self.assertEqual(report["cgb_inserted"], 0)
        self.assertEqual(report["cgb_updated"], 0)
        self.assertEqual(report["cgb_unchanged"], 2)

    def test_time_and_money_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            hierarchy=[
                _award_hierarchy_row(award_hierarchy_id=10001),
                _award_hierarchy_row(
                    award_hierarchy_id=10002,
                    root_award_number="A-0002",
                    award_number="A-0002",
                    originating_award_number="A-0002",
                ),
            ],
            tnm_document=[
                _time_and_money_document_row(),
                _time_and_money_document_row(
                    document_number="TNM-2", award_number="A-0002"
                ),
            ],
            pending_transaction=[
                _pending_transaction_row(),
                _pending_transaction_row(
                    transaction_id=9002, destination_award_number="A-0002"
                ),
            ],
            pending_transaction_extension=[
                _pending_transaction_extension_row(),
                _pending_transaction_extension_row(
                    transaction_id=9002,
                    source_award_number="000000-00000",
                    destination_award_number="A-0002",
                ),
            ],
            transaction_detail=[
                _transaction_detail_row(),
                _transaction_detail_row(
                    transaction_detail_id=11002, award_number="A-0002"
                ),
            ],
            award_amount_transaction=[
                _award_amount_transaction_row(),
                _award_amount_transaction_row(
                    award_amount_transaction_id=12002, award_number="A-0002"
                ),
            ],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(award_amount_info_id=None),
                _award_direct_fanda_distribution_row(
                    award_direct_fanda_distribution_id=13002,
                    award_id=2,
                    award_number="A-0002",
                    award_amount_info_id=None,
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["hierarchy_inserted"], 2)
        self.assertEqual(report["tnm_document_inserted"], 2)
        self.assertEqual(report["pending_transaction_inserted"], 2)
        self.assertEqual(report["pending_transaction_extension_inserted"], 2)
        self.assertEqual(report["transaction_detail_inserted"], 2)
        self.assertEqual(report["award_amount_transaction_inserted"], 2)
        self.assertEqual(report["fanda_distribution_inserted"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.time_and_money_document"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.pending_transaction"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.pending_transaction_extension"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.transaction_detail"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_amount_transaction"),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_direct_fanda_distribution"
            ),
            0,
        )

    def test_time_and_money_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            hierarchy=[
                _award_hierarchy_row(award_hierarchy_id=10001),
                _award_hierarchy_row(
                    award_hierarchy_id=10002,
                    root_award_number="A-0002",
                    award_number="A-0002",
                    originating_award_number="A-0002",
                ),
            ],
            tnm_document=[
                _time_and_money_document_row(),
                _time_and_money_document_row(
                    document_number="TNM-2", award_number="A-0002"
                ),
            ],
            pending_transaction=[
                _pending_transaction_row(),
                _pending_transaction_row(
                    transaction_id=9002, destination_award_number="A-0002"
                ),
            ],
            pending_transaction_extension=[
                _pending_transaction_extension_row(),
                _pending_transaction_extension_row(
                    transaction_id=9002,
                    source_award_number="000000-00000",
                    destination_award_number="A-0002",
                ),
            ],
            transaction_detail=[
                _transaction_detail_row(),
                _transaction_detail_row(
                    transaction_detail_id=11002, award_number="A-0002"
                ),
            ],
            award_amount_transaction=[
                _award_amount_transaction_row(),
                _award_amount_transaction_row(
                    award_amount_transaction_id=12002, award_number="A-0002"
                ),
            ],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(award_amount_info_id=None),
                _award_direct_fanda_distribution_row(
                    award_direct_fanda_distribution_id=13002,
                    award_id=2,
                    award_number="A-0002",
                    award_amount_info_id=None,
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["hierarchy_inserted"], 0)
        self.assertEqual(report["hierarchy_updated"], 0)
        self.assertEqual(report["hierarchy_unchanged"], 2)
        self.assertEqual(report["tnm_document_inserted"], 0)
        self.assertEqual(report["tnm_document_updated"], 0)
        self.assertEqual(report["tnm_document_unchanged"], 2)
        self.assertEqual(report["pending_transaction_inserted"], 0)
        self.assertEqual(report["pending_transaction_updated"], 0)
        self.assertEqual(report["pending_transaction_unchanged"], 2)
        self.assertEqual(report["pending_transaction_extension_inserted"], 0)
        self.assertEqual(report["pending_transaction_extension_updated"], 0)
        self.assertEqual(report["pending_transaction_extension_unchanged"], 2)
        self.assertEqual(report["transaction_detail_inserted"], 0)
        self.assertEqual(report["transaction_detail_updated"], 0)
        self.assertEqual(report["transaction_detail_unchanged"], 2)
        self.assertEqual(report["award_amount_transaction_inserted"], 0)
        self.assertEqual(report["award_amount_transaction_updated"], 0)
        self.assertEqual(report["award_amount_transaction_unchanged"], 2)
        self.assertEqual(report["fanda_distribution_inserted"], 0)
        self.assertEqual(report["fanda_distribution_updated"], 0)
        self.assertEqual(report["fanda_distribution_unchanged"], 2)

    def test_budget_batch_rerun_is_idempotent(self) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[
                _award_budget_row(),
                _award_budget_row(budget_id=14002, award_id=2),
            ],
            budget_limit=[
                _award_budget_limit_row(),
                _award_budget_limit_row(
                    budget_limit_id=14102, budget_id=14002, award_id=2
                ),
            ],
            budget_period=[
                _award_budget_period_row(),
                _award_budget_period_row(
                    budget_period_id=14202, budget_id=14002, award_id=2
                ),
            ],
            budget_line_item=[
                _award_budget_line_item_row(),
                _award_budget_line_item_row(
                    budget_line_item_id=14302,
                    budget_id=14002,
                    budget_period_id=14202,
                    award_id=2,
                ),
            ],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row(),
                _award_budget_line_item_calculated_amount_row(
                    budget_line_item_calculated_amount_id=14402,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    award_id=2,
                ),
            ],
            budget_personnel_detail=[
                _award_budget_personnel_detail_row(),
                _award_budget_personnel_detail_row(
                    budget_personnel_line_item_id=14502,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    award_id=2,
                ),
            ],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(),
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14602,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    budget_personnel_line_item_id=14502,
                    award_id=2,
                ),
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row(),
                _award_budget_period_summary_calculated_amount_row(
                    award_budget_period_summary_calculated_amount_id=14702,
                    budget_id=14002,
                    budget_period_id=14202,
                    award_id=2,
                ),
            ],
        ):
            batch_id = self._create_batch([1, 2])
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["budget_inserted"], 0)
        self.assertEqual(report["budget_updated"], 0)
        self.assertEqual(report["budget_unchanged"], 2)
        self.assertEqual(report["budget_limit_unchanged"], 2)
        self.assertEqual(report["budget_period_unchanged"], 2)
        self.assertEqual(report["budget_line_item_unchanged"], 2)
        self.assertEqual(report["budget_line_item_calculated_amount_unchanged"], 2)
        self.assertEqual(report["budget_personnel_detail_unchanged"], 2)
        self.assertEqual(report["budget_personnel_calculated_amount_unchanged"], 2)
        self.assertEqual(
            report["budget_period_summary_calculated_amount_unchanged"], 2
        )

    def test_one_bad_deep_budget_row_rolls_back_the_whole_batch(self) -> None:
        # award_id=2's budget_personnel_calculated_amount references a
        # budget_personnel_line_item_id that was never loaded in this
        # batch - a genuine FK violation deliberately injected five
        # levels deep in the deepest bundle in the Award domain, to
        # prove the whole batch (including the otherwise-valid
        # award_id=1 family, which has its own complete 5-level Budget
        # chain here) rolls back together as one unit of work.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[
                _award_budget_row(),
                _award_budget_row(budget_id=14002, award_id=2),
            ],
            budget_period=[
                _award_budget_period_row(),
                _award_budget_period_row(
                    budget_period_id=14202, budget_id=14002, award_id=2
                ),
            ],
            budget_line_item=[
                _award_budget_line_item_row(),
                _award_budget_line_item_row(
                    budget_line_item_id=14302,
                    budget_id=14002,
                    budget_period_id=14202,
                    award_id=2,
                ),
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row(),
                _award_budget_personnel_calculated_amount_row(
                    budget_personnel_calculated_amount_id=14602,
                    budget_id=14002,
                    budget_period_id=14202,
                    budget_line_item_id=14302,
                    budget_personnel_line_item_id=99999,
                    award_id=2,
                ),
            ],
        ):
            with self.assertRaises(IntegrityError):
                award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_version"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_period"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_line_item"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_budget_personnel_detail"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM "
                "archive.award_budget_personnel_calculated_amount"
            ),
            0,
        )

    def test_budget_person_and_transferring_sponsor_batch_rerun_is_idempotent(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[
                _award_budget_row(),
                _award_budget_row(budget_id=14002, award_id=2),
            ],
            budget_person=[
                _award_budget_person_row(),
                _award_budget_person_row(budget_id=14002, award_id=2),
            ],
            transferring_sponsor=[
                _award_transferring_sponsor_row(),
                _award_transferring_sponsor_row(
                    award_transferring_sponsor_id=14802,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["budget_person_inserted"], 0)
        self.assertEqual(report["budget_person_updated"], 0)
        self.assertEqual(report["budget_person_unchanged"], 2)
        self.assertEqual(report["transferring_sponsor_inserted"], 0)
        self.assertEqual(report["transferring_sponsor_updated"], 0)
        self.assertEqual(report["transferring_sponsor_unchanged"], 2)

    def test_budget_person_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[
                _award_budget_row(),
                _award_budget_row(budget_id=14002, award_id=2),
            ],
            budget_person=[
                _award_budget_person_row(),
                _award_budget_person_row(budget_id=14002, award_id=2),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["budget_person_inserted"], 2)
        budget_person_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_budget_person"
        )
        self.assertEqual(budget_person_count, 0)

    def test_one_bad_budget_person_row_rolls_back_the_whole_batch(self) -> None:
        # award_id=2's budget_person references a budget_id that was
        # never loaded in this batch - a genuine FK violation to
        # archive.award_budget, deliberately injected to prove the
        # whole batch (including the otherwise-valid award_id=1
        # family, which has its own valid budget_person row here)
        # rolls back together as one unit of work.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            budget=[_award_budget_row()],
            budget_person=[
                _award_budget_person_row(),
                _award_budget_person_row(
                    budget_id=99999, award_id=2, award_number="A-0002"
                ),
            ],
        ):
            with self.assertRaises(IntegrityError):
                award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_version"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_budget_person"), 0
        )

    def test_award_transmission_and_child_batch_propagates_and_rerun_is_idempotent(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            award_transmission=[
                _award_transmission_row(),
                _award_transmission_row(
                    transmission_id=15002, award_id=2, award_number="A-0002"
                ),
            ],
            award_transmission_child=[
                _award_transmission_child_row(),
                _award_transmission_child_row(
                    transmission_child_id=15102,
                    transmission_id=15002,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            award_loader._run_load_award_batch(self.engine, batch_id)
            report = award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(report["award_transmission_inserted"], 0)
        self.assertEqual(report["award_transmission_updated"], 0)
        self.assertEqual(report["award_transmission_unchanged"], 2)
        self.assertEqual(report["award_transmission_child_inserted"], 0)
        self.assertEqual(report["award_transmission_child_updated"], 0)
        self.assertEqual(report["award_transmission_child_unchanged"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_transmission"), 2
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_transmission_child"
            ),
            2,
        )

    def test_award_transmission_dry_run_persists_nothing_across_the_whole_batch(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            award_transmission=[
                _award_transmission_row(),
                _award_transmission_row(
                    transmission_id=15002, award_id=2, award_number="A-0002"
                ),
            ],
            award_transmission_child=[
                _award_transmission_child_row(),
                _award_transmission_child_row(
                    transmission_child_id=15102,
                    transmission_id=15002,
                    award_id=2,
                    award_number="A-0002",
                ),
            ],
        ):
            report = award_loader._run_load_award_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["award_transmission_inserted"], 2)
        self.assertEqual(report["award_transmission_child_inserted"], 2)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_transmission"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_transmission_child"
            ),
            0,
        )

    def test_one_bad_award_transmission_row_rolls_back_the_whole_batch(
        self,
    ) -> None:
        # award_id=2's award_transmission has a NULL transmission_id -
        # a genuine NOT NULL/primary-key violation, deliberately
        # injected to prove the whole batch (including the
        # otherwise-valid award_id=1 family, which has its own valid
        # award_transmission row here) rolls back together as one unit
        # of work. award_id itself can't be used to construct this
        # violation the way budget_person's bad budget_id is used
        # elsewhere in this file: AWARD_ID is exactly what
        # read_award_children_matching_award_ids filters this table's
        # rows by, so any row with an award_id outside the batch's own
        # family is excluded before it ever reaches Postgres - it can
        # never reach the FK check. transmission_id carries no such
        # filter, so a NULL there is the reachable way to prove the
        # same whole-batch-rollback guarantee for this table.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            award_transmission=[
                _award_transmission_row(),
                _award_transmission_row(
                    transmission_id=None, award_id=2, award_number="A-0002"
                ),
            ],
        ):
            with self.assertRaises(IntegrityError):
                award_loader._run_load_award_batch(self.engine, batch_id)

        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_version"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_transmission"), 0
        )

    def test_one_bad_family_rolls_back_the_whole_batch(self) -> None:
        # award_id=2's person_unit_credit_split references a
        # person_unit that was never loaded in this batch - a genuine
        # FK violation, deliberately injected to prove the whole batch
        # (including the otherwise-valid award_id=1 family, which also
        # has its own notepad/closeout/payment_schedule/
        # approved_subaward/special_review/special_review_exemption/
        # subcontracting_budgeted_goals rows here) rolls back together
        # as one unit of work, per the refactor's "treat the batch as
        # one unit of work" transaction design. special_review_exemption
        # is the one table in this batch whose own FK (to
        # special_review, not award_version) could theoretically fail
        # independently - included here specifically to prove it still
        # rides along in, and is rolled back by, the very same
        # transaction a failure anywhere else in the batch triggers,
        # not a separate one.
        batch_id = self._create_batch([1, 2])
        with self._patched_oracle(
            versions=[
                _version_row(award_id=1, award_number="A-0001"),
                _version_row(award_id=2, award_number="A-0002"),
            ],
            notepad=[_notepad_row(award_notepad_id=1701, award_id=1)],
            closeout=[_closeout_row(award_closeout_id=1801, award_id=1)],
            payment_schedule=[
                _payment_schedule_row(award_payment_schedule_id=1901, award_id=1)
            ],
            approved_subaward=[
                _approved_subaward_row(
                    award_approved_subaward_id=2001, award_id=1
                )
            ],
            special_review=[
                _special_review_row(award_special_review_id=2501, award_id=1)
            ],
            special_review_exemption=[
                _special_review_exemption_row(
                    award_special_review_exemption_id=2601,
                    award_special_review_id=2501,
                    award_id=1,
                )
            ],
            subcontracting_budgeted_goals=[
                _subcontracting_budgeted_goals_row(award_number="A-0001")
            ],
            comment=[_award_comment_row(award_comment_id=2901, award_id=1)],
            extension=[_award_extension_row(award_id=1)],
            cgb=[_award_cgb_row(award_id=1)],
            hierarchy=[_award_hierarchy_row()],
            tnm_document=[_time_and_money_document_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
            transaction_detail=[_transaction_detail_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
            fanda_distribution=[
                _award_direct_fanda_distribution_row(award_amount_info_id=None)
            ],
            award_transmission=[_award_transmission_row()],
            award_transmission_child=[_award_transmission_child_row()],
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
        notepad_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_notepad"
        )
        self.assertEqual(notepad_count, 0)
        closeout_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_closeout"
        )
        self.assertEqual(closeout_count, 0)
        payment_schedule_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_payment_schedule"
        )
        self.assertEqual(payment_schedule_count, 0)
        approved_subaward_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_approved_subaward"
        )
        self.assertEqual(approved_subaward_count, 0)
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_special_review"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_special_review_exemption"
            ),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_subcontracting_budgeted_goals"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_comment"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_extension"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_cgb"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_hierarchy"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.time_and_money_document"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.pending_transaction"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.pending_transaction_extension"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.transaction_detail"), 0
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_amount_transaction"),
            0,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_direct_fanda_distribution"
            ),
            0,
        )
        self.assertEqual(
            self._scalar("SELECT COUNT(*) FROM archive.award_transmission"), 0
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_transmission_child"
            ),
            0,
        )


class ShowAwardBatchTest(_AwardPostgresTestCase):
    def test_generic_show_batch_works_for_award_domain(self) -> None:
        with self._patched_oracle(
            award_ids=[{"award_id": 1}]
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


class ClearExistingAwardDataTest(_AwardPostgresTestCase):
    """clear_existing_award_data() is the legacy full load's reset step -
    --load-award-id/--load-batch never call it (both UPSERT). Proves it
    clears every one of the 48 Award-owned tables through V052 without
    an FK violation, and never touches a Proposal/Negotiation/Protocol/
    Subaward/Attachment table."""

    def test_clears_every_award_table_without_fk_violation_and_leaves_other_domains_untouched(
        self,
    ) -> None:
        # Populate representative rows across the full Award hierarchy
        # via the real incremental loader (not hand-written INSERTs) -
        # this exercises the exact same 44 child tables (plus the 4
        # core tables) a real load creates, including the deepest
        # Budget bundle and both SAP transmission tables.
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
            notepad=[_notepad_row()],
            closeout=[_closeout_row()],
            payment_schedule=[_payment_schedule_row()],
            approved_subaward=[_approved_subaward_row()],
            cfda=[_cfda_row()],
            cost_share=[_cost_share_row()],
            fanda_rate=[_fanda_rate_row()],
            science_keyword=[_science_keyword_row()],
            special_review=[_special_review_row()],
            special_review_exemption=[_special_review_exemption_row()],
            approved_equipment=[_approved_equipment_row()],
            approved_foreign_travel=[_approved_foreign_travel_row()],
            subcontracting_budgeted_goals=[_subcontracting_budgeted_goals_row()],
            comment=[_award_comment_row()],
            extension=[_award_extension_row()],
            cgb=[_award_cgb_row()],
            hierarchy=[_award_hierarchy_row()],
            tnm_document=[_time_and_money_document_row()],
            pending_transaction=[_pending_transaction_row()],
            pending_transaction_extension=[_pending_transaction_extension_row()],
            transaction_detail=[_transaction_detail_row()],
            award_amount_transaction=[_award_amount_transaction_row()],
            fanda_distribution=[_award_direct_fanda_distribution_row()],
            budget=[_award_budget_row()],
            budget_limit=[_award_budget_limit_row()],
            budget_period=[_award_budget_period_row()],
            budget_line_item=[_award_budget_line_item_row()],
            budget_line_item_calculated_amount=[
                _award_budget_line_item_calculated_amount_row()
            ],
            budget_personnel_detail=[_award_budget_personnel_detail_row()],
            budget_personnel_calculated_amount=[
                _award_budget_personnel_calculated_amount_row()
            ],
            budget_period_summary_calculated_amount=[
                _award_budget_period_summary_calculated_amount_row()
            ],
            budget_person=[_award_budget_person_row()],
            transferring_sponsor=[_award_transferring_sponsor_row()],
            award_transmission=[_award_transmission_row()],
            award_transmission_child=[_award_transmission_child_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)
        self.assertEqual(report["inserted"], 1)

        # Plant one row in a representative table from every domain
        # clear_existing_award_data() must never touch, so an accidental
        # widening of its table list (or a switch to CASCADE) would be
        # caught by the "still there afterward" assertions below.
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.negotiation (negotiation_id) "
                    "VALUES (900001)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.proposal_version "
                    "(proposal_id, proposal_number, version_number) "
                    "VALUES (900001, 'P-900001', 1)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.protocol_version "
                    "(protocol_id, protocol_number, sequence_number) "
                    "VALUES (900001, 'PROT-900001', 1)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.subaward "
                    "(subaward_id, sequence_number, subaward_code) "
                    "VALUES (900001, 1, 'SA-900001')"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.attachment_object (file_id) "
                    "VALUES (900001)"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO archive.award_attachment "
                    "(award_attachment_id, award_id, award_number, "
                    "sequence_number) VALUES (900001, 1, 'A-0001', 0)"
                )
            )

        # Every Award table genuinely has data before clearing - a
        # sanity check that the fixture load above actually worked, so
        # the "empty afterward" assertions aren't vacuously true.
        for table in award_loader._AWARD_OWNED_TABLES:
            row_count = int(self._scalar(f"SELECT COUNT(*) FROM archive.{table}"))  # type: ignore[call-overload]
            self.assertTrue(
                row_count > 0,
                f"expected archive.{table} to have rows before clearing",
            )

        # The actual behavior under test: must not raise (no FK
        # violation), despite clearing 48 interrelated tables via a
        # single combined TRUNCATE with no CASCADE keyword.
        with self.engine.begin() as connection:
            award_loader.clear_existing_award_data(connection)

        for table in award_loader._AWARD_OWNED_TABLES:
            self.assertEqual(
                self._scalar(f"SELECT COUNT(*) FROM archive.{table}"),
                0,
                f"expected archive.{table} to be empty after clearing",
            )

        # Every non-Award domain's planted row must still be there,
        # completely untouched.
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.negotiation "
                "WHERE negotiation_id = 900001"
            ),
            1,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.proposal_version "
                "WHERE proposal_id = 900001"
            ),
            1,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.protocol_version "
                "WHERE protocol_id = 900001"
            ),
            1,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.subaward "
                "WHERE subaward_id = 900001"
            ),
            1,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.attachment_object "
                "WHERE file_id = 900001"
            ),
            1,
        )
        self.assertEqual(
            self._scalar(
                "SELECT COUNT(*) FROM archive.award_attachment "
                "WHERE award_attachment_id = 900001"
            ),
            1,
        )

        # load_run itself (shared provenance across every domain, not
        # Award-owned) must also survive - clear_existing_award_data()
        # only ever clears tables Award data is inserted into, never
        # the shared load-tracking table those inserts reference.
        load_run_count = int(self._scalar("SELECT COUNT(*) FROM archive.load_run"))  # type: ignore[call-overload]
        self.assertTrue(load_run_count > 0)

    def test_uses_restart_identity_and_is_a_harmless_no_op_on_these_tables(
        self,
    ) -> None:
        # Every Award-owned table's PK is populated directly from
        # Oracle's own real business/surrogate key (award_id,
        # transmission_id, etc.) - none uses a Postgres SERIAL/IDENTITY
        # column, so RESTART IDENTITY has no sequence to reset for any
        # of them. Kept in the TRUNCATE statement anyway (matching the
        # original 4-table implementation) since it is a no-op here,
        # not a functional requirement - this test proves it stays a
        # harmless no-op (no error) rather than something relied upon.
        with self._patched_oracle(versions=[_version_row()]):
            award_loader._run_load_award_id(self.engine, 1)

        with self.engine.begin() as connection:
            award_loader.clear_existing_award_data(connection)

        sequence_count = self._scalar(
            "SELECT COUNT(*) FROM pg_sequences WHERE schemaname = 'archive' "
            "AND sequencename LIKE 'award_%'"
        )
        self.assertEqual(sequence_count, 0)

    def test_reload_after_clear_reinserts_cleanly(self) -> None:
        # A full load run always clears then reloads in the same
        # transaction (see main()) - proves that sequence genuinely
        # works end to end: the exact same award_id can be re-inserted
        # immediately after clearing, with no leftover row/constraint
        # blocking it.
        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            award_transmission=[_award_transmission_row()],
        ):
            award_loader._run_load_award_id(self.engine, 1)

        with self.engine.begin() as connection:
            award_loader.clear_existing_award_data(connection)

        with self._patched_oracle(
            versions=[_version_row()],
            amounts=[_amount_row()],
            award_transmission=[_award_transmission_row()],
        ):
            report = award_loader._run_load_award_id(self.engine, 1)

        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["amount_info_inserted"], 1)
        self.assertEqual(report["award_transmission_inserted"], 1)
        version_row = self._row("award_version", award_id=1)
        self.assertEqual(version_row["title"], "Test Award")


class RunEcsSetupOrchestrationTest(unittest.TestCase):
    """Orchestration tests for _run_ecs_setup - every collaborator
    (Secrets Manager resolution, startup-validation checks, migrations)
    has its own focused unit tests elsewhere (archive_etl.config.ecs,
    archive_etl.config.startup_validation); these prove _run_ecs_setup
    wires them together in the exact required order and short-circuits
    correctly for --migrate-only/--show-batch. Mirrors
    load_award_attachments.py's own equivalent test class in shape -
    that file is not modified or imported from here."""

    def _run(
        self, *, migrate_only: bool, show_batch: int | None = None
    ) -> dict:
        arguments = MagicMock(
            migrate_only=migrate_only,
            show_batch=show_batch,
        )
        calls: list[str] = []

        def _track(name, retval=None):
            def _fn(*args, **kwargs):
                calls.append(name)
                return retval

            return _fn

        def _boto3_client_side_effect(service_name, *args, **kwargs):
            calls.append(f"boto3.client({service_name})")
            return MagicMock()

        with (
            patch.object(
                award_loader,
                "configure_structured_logging",
                side_effect=_track("configure_structured_logging"),
            ) as configure_logging,
            patch.object(
                award_loader,
                "validate_aws_identity",
                side_effect=_track(
                    "validate_aws_identity", {"account": "123", "arn": "arn:x"}
                ),
            ) as validate_identity,
            patch.object(
                award_loader.boto3,
                "client",
                side_effect=_boto3_client_side_effect,
            ) as boto3_client,
            patch.object(
                award_loader,
                "configure_ecs_environment",
                side_effect=_track("configure_ecs_environment"),
            ) as configure_env,
            patch.object(
                award_loader,
                "create_postgres_engine",
                side_effect=_track("create_postgres_engine", MagicMock()),
            ),
            patch.object(
                award_loader,
                "validate_postgres_reachable",
                side_effect=_track("validate_postgres_reachable"),
            ),
            patch.object(
                award_loader,
                "apply_migrations",
                side_effect=_track("apply_migrations"),
            ) as apply_migrations,
            patch.object(
                award_loader,
                "_run_show_batch",
                side_effect=_track("_run_show_batch"),
            ) as run_show_batch,
            patch.object(
                award_loader,
                "validate_table_exists",
                side_effect=_track("validate_table_exists"),
            ) as validate_table,
            patch.object(
                award_loader,
                "validate_oracle_reachable",
                side_effect=_track("validate_oracle_reachable"),
            ) as validate_oracle,
        ):
            result = award_loader._run_ecs_setup(arguments, "run-1")

        return {
            "result": result,
            "calls": calls,
            "validate_identity": validate_identity,
            "boto3_client": boto3_client,
            "configure_env": configure_env,
            "apply_migrations": apply_migrations,
            "validate_oracle": validate_oracle,
            "validate_table": validate_table,
            "run_show_batch": run_show_batch,
            "configure_logging": configure_logging,
        }

    def test_structured_logging_configured_first_with_run_id(self) -> None:
        result = self._run(migrate_only=True)

        result["configure_logging"].assert_called_once_with("run-1")
        self.assertEqual(result["calls"][0], "configure_structured_logging")

    def test_migrate_only_reaches_apply_migrations_and_returns_true(self) -> None:
        result = self._run(migrate_only=True)

        self.assertIn("apply_migrations", result["calls"])
        self.assertTrue(result["result"])

    def test_migrate_only_validates_schema_after_migrating_not_before(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("apply_migrations"), calls.index("validate_table_exists")
        )

    def test_migrate_only_never_contacts_oracle(self) -> None:
        result = self._run(migrate_only=True)

        self.assertNotIn("validate_oracle_reachable", result["calls"])
        result["validate_oracle"].assert_not_called()
        result["configure_env"].assert_called_once()
        self.assertFalse(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_migrate_only_validates_two_award_tables(self) -> None:
        result = self._run(migrate_only=True)

        self.assertEqual(result["validate_table"].call_count, 2)
        checked_tables = {
            call.args[1] for call in result["validate_table"].call_args_list
        }
        self.assertEqual(
            checked_tables, {"award_version", "award_transmission_child"}
        )

    def test_show_batch_reaches_the_report_and_returns_true(self) -> None:
        result = self._run(migrate_only=False, show_batch=5)

        self.assertIn("_run_show_batch", result["calls"])
        self.assertTrue(result["result"])
        result["run_show_batch"].assert_called_once()
        self.assertEqual(result["run_show_batch"].call_args.args[1], 5)

    def test_show_batch_never_contacts_oracle(self) -> None:
        result = self._run(migrate_only=False, show_batch=5)

        self.assertNotIn("validate_oracle_reachable", result["calls"])
        result["validate_oracle"].assert_not_called()
        result["configure_env"].assert_called_once()
        self.assertFalse(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_show_batch_never_applies_migrations(self) -> None:
        result = self._run(migrate_only=False, show_batch=5)

        result["apply_migrations"].assert_not_called()

    def test_identity_resolved_before_secrets_manager_client_created(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("validate_aws_identity"),
            calls.index("boto3.client(secretsmanager)"),
        )

    def test_creates_exactly_one_secrets_manager_client(self) -> None:
        result = self._run(migrate_only=True)

        secretsmanager_calls = [
            call
            for call in result["boto3_client"].call_args_list
            if call.args == ("secretsmanager",)
        ]
        self.assertEqual(len(secretsmanager_calls), 1)

    def test_secrets_loaded_before_postgres_connectivity_check(self) -> None:
        calls = self._run(migrate_only=True)["calls"]

        self.assertLess(
            calls.index("configure_ecs_environment"),
            calls.index("validate_postgres_reachable"),
        )

    def test_normal_flow_reaches_oracle_after_postgres_and_returns_false(
        self,
    ) -> None:
        result = self._run(migrate_only=False)
        calls = result["calls"]

        self.assertNotIn("apply_migrations", calls)
        self.assertFalse(result["result"])
        self.assertLess(
            calls.index("validate_postgres_reachable"),
            calls.index("validate_oracle_reachable"),
        )

    def test_normal_flow_resolves_oracle_credentials(self) -> None:
        result = self._run(migrate_only=False)

        result["configure_env"].assert_called_once()
        self.assertTrue(result["configure_env"].call_args.kwargs["include_oracle"])

    def test_missing_secret_failure_propagates_uncaught(self) -> None:
        # _run_ecs_setup must never swallow a Secrets Manager resolution
        # failure (e.g. POSTGRES_SECRET_ID unset, or the secret missing a
        # required key) - configure_ecs_environment's own
        # ConfigurationError (see archive_etl.config.ecs, already
        # covered by its own extensive test suite in
        # tests/test_ecs_config.py - not duplicated here) must reach the
        # caller so the ECS task exits nonzero, never reaching
        # PostgreSQL, migrations, or Oracle.
        from archive_etl.config.settings import ConfigurationError

        arguments = MagicMock(migrate_only=True, show_batch=None)

        with (
            patch.object(award_loader, "configure_structured_logging"),
            patch.object(
                award_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(award_loader.boto3, "client", return_value=MagicMock()),
            patch.object(
                award_loader,
                "configure_ecs_environment",
                side_effect=ConfigurationError(
                    "POSTGRES_SECRET_ID is not set"
                ),
            ),
            patch.object(
                award_loader, "create_postgres_engine"
            ) as create_engine,
            patch.object(award_loader, "apply_migrations") as apply_migrations,
        ):
            with self.assertRaises(ConfigurationError):
                award_loader._run_ecs_setup(arguments, "run-1")

        create_engine.assert_not_called()
        apply_migrations.assert_not_called()


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
                ecs=False,
                migrate_only=False,
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
                ecs=False,
                migrate_only=False,
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
                ecs=False,
                migrate_only=False,
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
                ecs=False,
                migrate_only=False,
            )
            award_loader.main()

        show_batch.assert_called_once()
        apply_migrations.assert_not_called()

    def test_ecs_mode_runs_startup_setup_before_dispatch(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "_run_ecs_setup", return_value=False
            ) as run_ecs_setup,
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations") as apply_migrations,
            patch.object(award_loader, "_run_create_award_batch") as run_create,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=10,
                load_batch=None,
                show_batch=None,
                dry_run=False,
                ecs=True,
                migrate_only=False,
            )
            award_loader.main()

        run_ecs_setup.assert_called_once()
        run_create.assert_called_once()
        # --ecs mode never applies migrations itself - only --migrate-only
        # does, inside _run_ecs_setup (already proven separately by
        # RunEcsSetupOrchestrationTest); every other --ecs invocation
        # requires migrations to already be applied.
        apply_migrations.assert_not_called()

    def test_ecs_mode_short_circuits_main_when_migrate_only_completes(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "_run_ecs_setup", return_value=True
            ) as run_ecs_setup,
            patch.object(award_loader, "create_postgres_engine") as create_engine,
            patch.object(award_loader, "_run_create_award_batch") as run_create,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                dry_run=False,
                ecs=True,
                migrate_only=True,
            )
            award_loader.main()

        run_ecs_setup.assert_called_once()
        # main() must return immediately once _run_ecs_setup signals
        # --migrate-only completed - never reach any dispatch branch or
        # call create_postgres_engine() a second time.
        create_engine.assert_not_called()
        run_create.assert_not_called()

    def test_ecs_mode_load_batch_never_applies_migrations(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "_run_ecs_setup", return_value=False
            ),
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations") as apply_migrations,
            patch.object(award_loader, "_run_load_award_batch") as run_load_batch,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=None,
                create_batch=None,
                load_batch=5,
                show_batch=None,
                dry_run=True,
                ecs=True,
                migrate_only=False,
            )
            award_loader.main()

        run_load_batch.assert_called_once()
        self.assertEqual(run_load_batch.call_args.args[1], 5)
        self.assertTrue(run_load_batch.call_args.kwargs["dry_run"])
        apply_migrations.assert_not_called()

    def test_ecs_mode_load_award_id_never_applies_migrations(self) -> None:
        with (
            patch.object(award_loader, "parse_args") as parse_args,
            patch.object(
                award_loader, "_run_ecs_setup", return_value=False
            ),
            patch.object(
                award_loader, "create_postgres_engine", return_value=MagicMock()
            ),
            patch.object(award_loader, "apply_migrations") as apply_migrations,
            patch.object(award_loader, "_run_load_award_id") as run_load_award_id,
        ):
            parse_args.return_value = MagicMock(
                load_award_id=7,
                create_batch=None,
                load_batch=None,
                show_batch=None,
                dry_run=False,
                ecs=True,
                migrate_only=False,
            )
            award_loader.main()

        run_load_award_id.assert_called_once()
        self.assertEqual(run_load_award_id.call_args.args[1], 7)
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
                ecs=False,
                migrate_only=False,
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
