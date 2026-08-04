from __future__ import annotations

import argparse
import os
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import boto3
import oracledb
import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.batch import framework as batch_framework
from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.config.startup_validation import (
    validate_aws_identity,
    validate_oracle_reachable,
    validate_postgres_reachable,
    validate_table_exists,
)
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.reference_data import (
    run_load_comment_type_reference_data,
    run_load_unit_reference_data,
)
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message
from archive_etl.utils.structured_logging import configure_structured_logging


def _resolve_project_root() -> Path:
    """Locate the directory containing sql/extract/award/ and
    database/migrations/ relative to this file. Two layouts are
    supported, mirroring load_award_attachments.py's own
    _resolve_project_root() exactly (not shared code - kept local to
    each loader, same as _connect_oracle - but the same technique): the
    local repo checkout (this file at
    <repo>/etl/load_awards_from_csv.py, so the project root is one
    level up) and the ECS loader container image (this file copied
    flatly to /app/load_awards_from_csv.py alongside sql/ and
    database/migrations/ copied directly under /app - see
    etl/Dockerfile.loader), where the project root is this file's own
    parent directory."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "sql").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

# Oracle extraction queries exist for versions/amounts/people/proposals.
# Award unit contacts had no verified Oracle extraction query and has been
# removed entirely (API, UI, ETL, and the archive.award_unit_contact table)
# - see docs/DECISIONS.md.
VERSIONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "01_award_versions.sql"
)
# Candidate-enumeration query for --create-batch's production selection
# mode only (see _run_create_award_batch) - not part of the 48-table
# extraction/load sequence, never populates any archive.* table.
AWARD_IDS_ASCENDING_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "award_ids_ascending.sql"
)
AMOUNTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "02_award_amounts.sql"
)
PEOPLE_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "03_award_people.sql"
)
PROPOSALS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "04_award_proposals.sql"
)
CUSTOM_DATA_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "05_award_custom_data.sql"
)
PERSON_UNITS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "06_award_person_units.sql"
)
PERSON_CREDIT_SPLITS_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "07_award_person_credit_splits.sql"
)
PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "08_award_person_unit_credit_splits.sql"
)
SPONSOR_TERMS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "09_award_sponsor_terms.sql"
)
REPORT_TERMS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "10_award_report_terms.sql"
)
REPORT_TERM_RECIPIENTS_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "11_award_report_term_recipients.sql"
)
SPONSOR_CONTACTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "12_award_sponsor_contacts.sql"
)
UNIT_CONTACTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "13_award_unit_contacts.sql"
)
NOTEPAD_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "14_award_notepad.sql"
)
CLOSEOUT_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "15_award_closeout.sql"
)
PAYMENT_SCHEDULE_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "16_award_payment_schedule.sql"
)
APPROVED_SUBAWARD_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "17_award_approved_subaward.sql"
)
CFDA_ORACLE_SQL = PROJECT_ROOT / "sql" / "extract" / "award" / "18_award_cfda.sql"
COST_SHARE_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "19_award_cost_share.sql"
)
FANDA_RATE_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "20_award_fanda_rate.sql"
)
SCIENCE_KEYWORD_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "21_award_science_keyword.sql"
)
SPECIAL_REVIEW_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "22_award_special_review.sql"
)
SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "23_award_special_review_exemption.sql"
)
APPROVED_EQUIPMENT_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "24_award_approved_equipment.sql"
)
APPROVED_FOREIGN_TRAVEL_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "25_award_approved_foreign_travel.sql"
)
SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "26_award_subcontracting_budgeted_goals.sql"
)
COMMENT_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "27_award_comment.sql"
)
EXTENSION_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "28_award_extension.sql"
)
CGB_ORACLE_SQL = PROJECT_ROOT / "sql" / "extract" / "award" / "29_award_cgb.sql"
HIERARCHY_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "30_award_hierarchy.sql"
)
TIME_AND_MONEY_DOCUMENT_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "31_time_and_money_document.sql"
)
PENDING_TRANSACTION_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "32_pending_transaction.sql"
)
PENDING_TRANSACTION_EXTENSION_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "33_pending_transaction_extension.sql"
)
TRANSACTION_DETAIL_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "34_transaction_detail.sql"
)
AWARD_AMOUNT_TRANSACTION_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "35_award_amount_transaction.sql"
)
AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "36_award_direct_fanda_distribution.sql"
)
BUDGET_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "37_award_budget.sql"
)
BUDGET_PERIOD_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "38_award_budget_period.sql"
)
BUDGET_LINE_ITEM_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "39_award_budget_line_item.sql"
)
BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "40_award_budget_line_item_calculated_amount.sql"
)
BUDGET_PERSONNEL_DETAIL_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "41_award_budget_personnel_detail.sql"
)
BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "42_award_budget_personnel_calculated_amount.sql"
)
BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "43_award_budget_period_summary_calculated_amount.sql"
)
BUDGET_LIMIT_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "44_award_budget_limit.sql"
)
BUDGET_PERSON_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "45_award_budget_person.sql"
)
TRANSFERRING_SPONSOR_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "46_award_transferring_sponsor.sql"
)
AWARD_TRANSMISSION_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "47_award_transmission.sql"
)
AWARD_TRANSMISSION_CHILD_ORACLE_SQL = (
    PROJECT_ROOT
    / "sql"
    / "extract"
    / "award"
    / "48_award_transmission_child.sql"
)

VERSION_REQUIRED_COLUMNS = {
    "award_id",
    "award_number",
    "sequence_number",
    "title",
}

AMOUNT_REQUIRED_COLUMNS = {
    "award_amount_info_id",
    "award_id",
    "award_number",
    "sequence_number",
}

PERSON_REQUIRED_COLUMNS = {
    "award_person_id",
    "award_id",
    "award_number",
    "sequence_number",
}

PROPOSAL_REQUIRED_COLUMNS = {
    "award_funding_proposal_id",
    "award_id",
    "proposal_id",
}

CUSTOM_DATA_REQUIRED_COLUMNS = {
    "award_custom_data_id",
    "award_id",
}

PERSON_UNIT_REQUIRED_COLUMNS = {
    "award_person_unit_id",
    "award_person_id",
    "award_id",
}

PERSON_CREDIT_SPLIT_REQUIRED_COLUMNS = {
    "award_person_credit_split_id",
    "award_person_id",
    "award_id",
}

PERSON_UNIT_CREDIT_SPLIT_REQUIRED_COLUMNS = {
    "award_person_unit_credit_split_id",
    "award_person_unit_id",
    "award_id",
}

SPONSOR_TERM_REQUIRED_COLUMNS = {
    "award_sponsor_term_id",
    "award_id",
}

REPORT_TERM_REQUIRED_COLUMNS = {
    "award_report_term_id",
    "award_id",
}

REPORT_TERM_RECIPIENT_REQUIRED_COLUMNS = {
    "award_report_term_recipient_id",
    "award_report_term_id",
    "award_id",
}

SPONSOR_CONTACT_REQUIRED_COLUMNS = {
    "award_sponsor_contact_id",
    "award_id",
}

UNIT_CONTACT_REQUIRED_COLUMNS = {
    "award_unit_contact_id",
    "award_id",
}

NOTEPAD_REQUIRED_COLUMNS = {
    "award_notepad_id",
    "award_id",
    "award_number",
    "entry_number",
}

CLOSEOUT_REQUIRED_COLUMNS = {
    "award_closeout_id",
    "award_id",
}

PAYMENT_SCHEDULE_REQUIRED_COLUMNS = {
    "award_payment_schedule_id",
    "award_id",
}

APPROVED_SUBAWARD_REQUIRED_COLUMNS = {
    "award_approved_subaward_id",
    "award_id",
}

CFDA_REQUIRED_COLUMNS = {
    "award_cfda_id",
    "award_id",
}

COST_SHARE_REQUIRED_COLUMNS = {
    "award_cost_share_id",
    "award_id",
}

FANDA_RATE_REQUIRED_COLUMNS = {
    "award_fanda_rate_id",
    "award_id",
}

SCIENCE_KEYWORD_REQUIRED_COLUMNS = {
    "award_science_keyword_id",
    "award_id",
}

SPECIAL_REVIEW_REQUIRED_COLUMNS = {
    "award_special_review_id",
    "award_id",
}

SPECIAL_REVIEW_EXEMPTION_REQUIRED_COLUMNS = {
    "award_special_review_exemption_id",
    "award_special_review_id",
    "award_id",
}

APPROVED_EQUIPMENT_REQUIRED_COLUMNS = {
    "award_approved_equipment_id",
    "award_id",
}

APPROVED_FOREIGN_TRAVEL_REQUIRED_COLUMNS = {
    "award_approved_foreign_travel_id",
    "award_id",
}

SUBCONTRACTING_BUDGETED_GOALS_REQUIRED_COLUMNS = {
    "award_number",
}

COMMENT_REQUIRED_COLUMNS = {
    "award_comment_id",
    "award_id",
}

EXTENSION_REQUIRED_COLUMNS = {
    "award_id",
}

CGB_REQUIRED_COLUMNS = {
    "award_id",
}

HIERARCHY_REQUIRED_COLUMNS = {
    "award_hierarchy_id",
    "award_number",
}

TIME_AND_MONEY_DOCUMENT_REQUIRED_COLUMNS = {
    "document_number",
}

PENDING_TRANSACTION_REQUIRED_COLUMNS = {
    "transaction_id",
}

PENDING_TRANSACTION_EXTENSION_REQUIRED_COLUMNS = {
    "transaction_id",
}

TRANSACTION_DETAIL_REQUIRED_COLUMNS = {
    "transaction_detail_id",
    "award_number",
}

AWARD_AMOUNT_TRANSACTION_REQUIRED_COLUMNS = {
    "award_amount_transaction_id",
    "award_number",
}

AWARD_DIRECT_FANDA_DISTRIBUTION_REQUIRED_COLUMNS = {
    "award_direct_fanda_distribution_id",
}

BUDGET_REQUIRED_COLUMNS = {
    "budget_id",
    "award_id",
}

BUDGET_PERIOD_REQUIRED_COLUMNS = {
    "budget_period_id",
}

BUDGET_LINE_ITEM_REQUIRED_COLUMNS = {
    "budget_line_item_id",
}

BUDGET_LINE_ITEM_CALCULATED_AMOUNT_REQUIRED_COLUMNS = {
    "budget_line_item_calculated_amount_id",
}

BUDGET_PERSONNEL_DETAIL_REQUIRED_COLUMNS = {
    "budget_personnel_line_item_id",
}

BUDGET_PERSONNEL_CALCULATED_AMOUNT_REQUIRED_COLUMNS = {
    "budget_personnel_calculated_amount_id",
}

BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_REQUIRED_COLUMNS = {
    "award_budget_period_summary_calculated_amount_id",
}

BUDGET_LIMIT_REQUIRED_COLUMNS = {
    "budget_limit_id",
}

BUDGET_PERSON_REQUIRED_COLUMNS = {
    "budget_id",
    "person_sequence_number",
}

TRANSFERRING_SPONSOR_REQUIRED_COLUMNS = {
    "award_transferring_sponsor_id",
    "award_id",
}

AWARD_TRANSMISSION_REQUIRED_COLUMNS = {
    "transmission_id",
    "award_id",
}

AWARD_TRANSMISSION_CHILD_REQUIRED_COLUMNS = {
    "transmission_child_id",
    "award_id",
}


def require_columns(
    dataframe: pd.DataFrame,
    required_columns: set[str],
    file_name: str,
) -> None:
    missing = sorted(
        required_columns - set(dataframe.columns)
    )

    if missing:
        raise RuntimeError(
            f"{file_name} is missing columns: "
            + ", ".join(missing)
        )


def convert_numeric(
    dataframe: pd.DataFrame,
    columns: list[str],
) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_numeric(
                dataframe[column],
                errors="coerce",
            )


def convert_dates(
    dataframe: pd.DataFrame,
    columns: list[str],
) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_datetime(
                dataframe[column],
                errors="coerce",
            )


def convert_boolean(value: Any) -> bool:
    if value is None or pd.isna(value):
        return False

    return str(value).strip().upper() in {
        "Y",
        "YES",
        "TRUE",
        "1",
    }


def prepare_versions(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        VERSION_REQUIRED_COLUMNS,
        "award_versions.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_id",
            "sequence_number",
            "status_code",
            "transaction_type_code",
        ],
    )

    convert_dates(
        dataframe,
        [
            "award_effective_date",
            "award_execution_date",
            "begin_date",
            "closeout_date",
            "update_timestamp",
        ],
    )

    if "is_current_version" in dataframe.columns:
        dataframe["is_current_version"] = (
            dataframe["is_current_version"]
            .map(convert_boolean)
        )
    else:
        max_sequence = dataframe.groupby(
            "award_number"
        )["sequence_number"].transform("max")

        dataframe["is_current_version"] = (
            dataframe["sequence_number"] == max_sequence
        )

    # Select exactly one authoritative current row for each
    # Award Number. Multiple source rows can share the latest
    # sequence, so use deterministic Kuali-style precedence.
    dataframe["is_primary_current"] = False

    primary_candidates = dataframe.copy()

    primary_candidates["_active_sequence_rank"] = (
        primary_candidates["award_sequence_status"]
        .fillna("")
        .astype(str)
        .str.strip()
        .str.upper()
        .eq("ACTIVE")
        .astype(int)
    )

    primary_candidates = primary_candidates.sort_values(
        by=[
            "award_number",
            "is_current_version",
            "sequence_number",
            "_active_sequence_rank",
            "update_timestamp",
            "award_id",
        ],
        ascending=[
            True,
            False,
            False,
            False,
            False,
            False,
        ],
        na_position="last",
        kind="stable",
    )

    primary_indexes = (
        primary_candidates
        .drop_duplicates(
            subset=["award_number"],
            keep="first",
        )
        .index
    )

    dataframe.loc[
        primary_indexes,
        "is_primary_current",
    ] = True

    primary_counts = dataframe.groupby(
        "award_number"
    )["is_primary_current"].sum()

    invalid_primary_counts = primary_counts[
        primary_counts != 1
    ]

    if not invalid_primary_counts.empty:
        raise RuntimeError(
            "Each Award Number must have exactly one "
            "primary current row. Invalid Award Numbers: "
            f"{len(invalid_primary_counts):,}"
        )

    logger.info(
        "Selected {:,} primary current Award rows",
        int(dataframe["is_primary_current"].sum()),
    )

    duplicate_award_ids = dataframe.duplicated(
        subset=["award_id"],
        keep=False,
    )

    if duplicate_award_ids.any():
        duplicate_count = int(duplicate_award_ids.sum())

        duplicate_preview = (
            dataframe.loc[
                duplicate_award_ids,
                [
                    "award_id",
                    "award_number",
                    "sequence_number",
                ],
            ]
            .head(20)
            .to_string(index=False)
        )

        raise RuntimeError(
            "award_versions.csv contains duplicate AWARD_ID values. "
            f"Duplicate rows: {duplicate_count}\n"
            + duplicate_preview
        )

    repeated_sequences = int(
        dataframe.duplicated(
            subset=["award_number", "sequence_number"],
            keep=False,
        ).sum()
    )

    if repeated_sequences:
        logger.warning(
            "{} Award rows share an award_number + sequence_number; "
            "all source AWARD_ID rows will be preserved",
            repeated_sequences,
        )

    invalid = dataframe[
        dataframe["award_id"].isna()
        | dataframe["award_number"].isna()
        | dataframe["sequence_number"].isna()
        | dataframe["title"].isna()
    ]

    if not invalid.empty:
        raise RuntimeError(
            "award_versions.csv contains "
            f"{len(invalid)} rows missing required values"
        )

    return dataframe


def prepare_amounts(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        AMOUNT_REQUIRED_COLUMNS,
        "award_amounts.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_amount_info_id",
            "award_id",
            "sequence_number",
            "anticipated_change_direct",
            "anticipated_change_indirect",
            "anticipated_total_direct",
            "anticipated_total_indirect",
            "obligated_total_direct",
            "obligated_total_indirect",
            "anticipated_total_amount",
            "obligated_total_amount",
            "ver_nbr",
        ],
    )

    return dataframe


def prepare_people(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_REQUIRED_COLUMNS,
        "award_people.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_person_id",
            "award_id",
            "sequence_number",
            "rolodex_id",
            "academic_year_effort",
            "calendar_year_effort",
            "summer_effort",
            "total_effort",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_proposals(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PROPOSAL_REQUIRED_COLUMNS,
        "award_proposals.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_funding_proposal_id",
            "award_id",
            "proposal_id",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    duplicate_links = dataframe.duplicated(
        subset=["award_id", "proposal_id"],
        keep="first",
    )

    if duplicate_links.any():
        logger.warning(
            "Removed {} duplicate Award/Proposal relationships",
            int(duplicate_links.sum()),
        )

        dataframe = dataframe.loc[
            ~duplicate_links
        ].copy()

    return dataframe


def prepare_custom_data(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        CUSTOM_DATA_REQUIRED_COLUMNS,
        "award_custom_data.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_custom_data_id",
            "award_id",
            "sequence_number",
            "custom_attribute_id",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_person_units(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_UNIT_REQUIRED_COLUMNS,
        "award_person_units.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_person_unit_id",
            "award_person_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_person_credit_splits(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_CREDIT_SPLIT_REQUIRED_COLUMNS,
        "award_person_credit_splits.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_person_credit_split_id",
            "award_person_id",
            "award_id",
            "sequence_number",
            "credit",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_person_unit_credit_splits(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_UNIT_CREDIT_SPLIT_REQUIRED_COLUMNS,
        "award_person_unit_credit_splits.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_person_unit_credit_split_id",
            "award_person_unit_id",
            "award_id",
            "sequence_number",
            "credit",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_sponsor_terms(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SPONSOR_TERM_REQUIRED_COLUMNS,
        "award_sponsor_terms.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_sponsor_term_id",
            "award_id",
            "sequence_number",
            "sponsor_term_id",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_report_terms(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        REPORT_TERM_REQUIRED_COLUMNS,
        "award_report_terms.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_report_term_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp", "due_date"],
    )

    return dataframe


def prepare_report_term_recipients(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        REPORT_TERM_RECIPIENT_REQUIRED_COLUMNS,
        "award_report_term_recipients.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_report_term_recipient_id",
            "award_report_term_id",
            "award_id",
            "sequence_number",
            "contact_id",
            "rolodex_id",
            "number_of_copies",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_sponsor_contacts(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SPONSOR_CONTACT_REQUIRED_COLUMNS,
        "award_sponsor_contacts.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_sponsor_contact_id",
            "award_id",
            "sequence_number",
            "rolodex_id",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_unit_contacts(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        UNIT_CONTACT_REQUIRED_COLUMNS,
        "award_unit_contacts.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_unit_contact_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_notepad(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        NOTEPAD_REQUIRED_COLUMNS,
        "award_notepad.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_notepad_id",
            "award_id",
            "entry_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["create_timestamp", "update_timestamp"],
    )

    return dataframe


def prepare_closeout(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        CLOSEOUT_REQUIRED_COLUMNS,
        "award_closeout.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_closeout_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["due_date", "final_submission_date", "update_timestamp"],
    )

    return dataframe


def prepare_payment_schedule(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PAYMENT_SCHEDULE_REQUIRED_COLUMNS,
        "award_payment_schedule.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_payment_schedule_id",
            "award_id",
            "sequence_number",
            "award_report_term_id",
            "amount",
            "overdue",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        [
            "due_date",
            "submit_date",
            "update_timestamp",
            "source_last_update_timestamp",
        ],
    )

    return dataframe


def prepare_approved_subaward(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        APPROVED_SUBAWARD_REQUIRED_COLUMNS,
        "award_approved_subaward.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_approved_subaward_id",
            "award_id",
            "sequence_number",
            "amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_cfda(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        CFDA_REQUIRED_COLUMNS,
        "award_cfda.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_cfda_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_cost_share(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        COST_SHARE_REQUIRED_COLUMNS,
        "award_cost_share.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_cost_share_id",
            "award_id",
            "sequence_number",
            "cost_share_percentage",
            "cost_share_type_code",
            "commitment_amount",
            "cost_share_met",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["verification_date", "update_timestamp"],
    )

    return dataframe


def prepare_fanda_rate(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        FANDA_RATE_REQUIRED_COLUMNS,
        "award_fanda_rate.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_fanda_rate_id",
            "award_id",
            "sequence_number",
            "applicable_fanda_rate",
            "fanda_rate_type_code",
            "underrecovery_of_indirect_cost",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_science_keyword(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SCIENCE_KEYWORD_REQUIRED_COLUMNS,
        "award_science_keyword.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_science_keyword_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_special_review(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SPECIAL_REVIEW_REQUIRED_COLUMNS,
        "award_special_review.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_special_review_id",
            "award_id",
            "sequence_number",
            "special_review_number",
            "special_review_type_code",
            "approval_type_code",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        [
            "application_date",
            "approval_date",
            "expiration_date",
            "update_timestamp",
        ],
    )

    return dataframe


def prepare_special_review_exemption(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SPECIAL_REVIEW_EXEMPTION_REQUIRED_COLUMNS,
        "award_special_review_exemption.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_special_review_exemption_id",
            "award_special_review_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_approved_equipment(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        APPROVED_EQUIPMENT_REQUIRED_COLUMNS,
        "award_approved_equipment.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_approved_equipment_id",
            "award_id",
            "sequence_number",
            "amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_approved_foreign_travel(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        APPROVED_FOREIGN_TRAVEL_REQUIRED_COLUMNS,
        "award_approved_foreign_travel.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_approved_foreign_travel_id",
            "award_id",
            "sequence_number",
            "rolodex_id",
            "amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_subcontracting_budgeted_goals(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        SUBCONTRACTING_BUDGETED_GOALS_REQUIRED_COLUMNS,
        "award_subcontracting_budgeted_goals.csv",
    )

    convert_numeric(
        dataframe,
        [
            "large_business_goal_amount",
            "small_business_goal_amount",
            "woman_owned_goal_amount",
            "eight_a_disadvantage_goal_amount",
            "hub_zone_goal_amount",
            "veteran_owned_goal_amount",
            "service_disabled_veteran_owned_goal_amount",
            "historical_black_college_goal_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_comments(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        COMMENT_REQUIRED_COLUMNS,
        "award_comment.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_comment_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_extension(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        EXTENSION_REQUIRED_COLUMNS,
        "award_extension.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        [
            "last_transmission_date",
            "nce_notification_date",
            "clinical_trial_registration_date",
            "update_timestamp",
        ],
    )

    return dataframe


def prepare_award_cgb(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        CGB_REQUIRED_COLUMNS,
        "award_cgb.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_id",
            "sequence_number",
            "min_invoice_amount",
            "amount_to_draw",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["last_billed_date", "previous_last_billed_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_hierarchy(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        HIERARCHY_REQUIRED_COLUMNS,
        "award_hierarchy.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_hierarchy_id",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_time_and_money_document(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        TIME_AND_MONEY_DOCUMENT_REQUIRED_COLUMNS,
        "time_and_money_document.csv",
    )

    # Renamed here, not in SQL, so this table can still be read via the
    # shared read_award_children_matching_award_numbers bounded reader,
    # which filters on a literal AWARD_NUMBER column - see
    # 31_time_and_money_document.sql.
    if "award_number" in dataframe.columns:
        dataframe = dataframe.rename(columns={"award_number": "root_award_number"})

    convert_numeric(
        dataframe,
        ["ver_nbr"],
    )

    convert_dates(
        dataframe,
        ["creation_date", "update_timestamp"],
    )

    return dataframe


def prepare_pending_transaction(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PENDING_TRANSACTION_REQUIRED_COLUMNS,
        "pending_transaction.csv",
    )

    convert_numeric(
        dataframe,
        [
            "transaction_id",
            "obligated_amount",
            "obligated_direct_amount",
            "obligated_indirect_amount",
            "anticipated_amount",
            "anticipated_direct_amount",
            "anticipated_indirect_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_pending_transaction_extension(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PENDING_TRANSACTION_EXTENSION_REQUIRED_COLUMNS,
        "pending_transaction_extension.csv",
    )

    convert_numeric(
        dataframe,
        ["transaction_id"],
    )

    return dataframe


def prepare_transaction_detail(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        TRANSACTION_DETAIL_REQUIRED_COLUMNS,
        "transaction_detail.csv",
    )

    convert_numeric(
        dataframe,
        [
            "transaction_detail_id",
            "sequence_number",
            "transaction_id",
            "obligated_amount",
            "obligated_direct_amount",
            "obligated_indirect_amount",
            "anticipated_amount",
            "anticipated_direct_amount",
            "anticipated_indirect_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_amount_transaction(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        AWARD_AMOUNT_TRANSACTION_REQUIRED_COLUMNS,
        "award_amount_transaction.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_amount_transaction_id",
            "transaction_type_code",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["notice_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_direct_fanda_distribution(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        AWARD_DIRECT_FANDA_DISTRIBUTION_REQUIRED_COLUMNS,
        "award_direct_fanda_distribution.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_direct_fanda_distribution_id",
            "award_id",
            "sequence_number",
            "amount_sequence_number",
            "award_amount_info_id",
            "budget_period",
            "direct_cost",
            "indirect_cost",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_budget(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_REQUIRED_COLUMNS,
        "award_budget.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_id",
            "award_id",
            "budget_version_number",
            "total_cost",
            "total_direct_cost",
            "total_indirect_cost",
            "total_cost_limit",
            "cost_sharing_amount",
            "underrecovery_amount",
            "residual_funds",
            "obligated_amount",
            "obligated_total",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_budget_period(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_PERIOD_REQUIRED_COLUMNS,
        "award_budget_period.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_period_id",
            "budget_id",
            "award_id",
            "budget_period",
            "total_cost",
            "total_direct_cost",
            "total_indirect_cost",
            "total_cost_limit",
            "cost_sharing_amount",
            "underrecovery_amount",
            "number_of_participants",
            "obligated_amount",
            "total_fringe_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_budget_line_item(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_LINE_ITEM_REQUIRED_COLUMNS,
        "award_budget_line_item.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_line_item_id",
            "budget_period_id",
            "budget_id",
            "award_id",
            "budget_period",
            "line_item_number",
            "based_on_line_item",
            "line_item_sequence",
            "line_item_cost",
            "cost_sharing_amount",
            "underrecovery_amount",
            "obligated_amount",
            "quantity",
            "subaward_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_budget_line_item_calculated_amount(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_LINE_ITEM_CALCULATED_AMOUNT_REQUIRED_COLUMNS,
        "award_budget_line_item_calculated_amount.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_line_item_calculated_amount_id",
            "budget_line_item_id",
            "budget_period_id",
            "budget_id",
            "award_id",
            "budget_period",
            "line_item_number",
            "calculated_cost",
            "calculated_cost_sharing",
            "obligated_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_budget_personnel_detail(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_PERSONNEL_DETAIL_REQUIRED_COLUMNS,
        "award_budget_personnel_detail.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_personnel_line_item_id",
            "budget_line_item_id",
            "budget_period_id",
            "budget_id",
            "award_id",
            "budget_period",
            "line_item_number",
            "person_number",
            "person_sequence_number",
            "sequence_number",
            "salary_requested",
            "percent_charged",
            "percent_effort",
            "cost_sharing_percent",
            "cost_sharing_amount",
            "underrecovery_amount",
            "obligated_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["start_date", "end_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_budget_personnel_calculated_amount(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_PERSONNEL_CALCULATED_AMOUNT_REQUIRED_COLUMNS,
        "award_budget_personnel_calculated_amount.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_personnel_calculated_amount_id",
            "budget_personnel_line_item_id",
            "budget_period_id",
            "budget_id",
            "award_id",
            "budget_period",
            "line_item_number",
            "person_number",
            "calculated_cost",
            "calculated_cost_sharing",
            "obligated_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_budget_period_summary_calculated_amount(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_REQUIRED_COLUMNS,
        "award_budget_period_summary_calculated_amount.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_budget_period_summary_calculated_amount_id",
            "budget_period_id",
            "award_id",
            "calculated_cost",
            "calculated_cost_sharing",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_budget_limit(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_LIMIT_REQUIRED_COLUMNS,
        "award_budget_limit.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_limit_id",
            "award_id",
            "budget_id",
            "limit_amount",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_budget_person(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        BUDGET_PERSON_REQUIRED_COLUMNS,
        "award_budget_person.csv",
    )

    convert_numeric(
        dataframe,
        [
            "budget_id",
            "person_sequence_number",
            "award_id",
            "rolodex_id",
            "calculation_base",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["effective_date", "salary_anniversary_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_transferring_sponsor(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        TRANSFERRING_SPONSOR_REQUIRED_COLUMNS,
        "award_transferring_sponsor.csv",
    )

    convert_numeric(
        dataframe,
        [
            "award_transferring_sponsor_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def prepare_award_transmission(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    """Prepares AWARD_TRANSMISSION rows for archival. sent_data/returned_data
    are deliberately left untouched by any conversion below - they are
    the raw historical SOAP request/response XML this table exists to
    preserve, never parsed, normalized, or reformatted.
    """
    require_columns(
        dataframe,
        AWARD_TRANSMISSION_REQUIRED_COLUMNS,
        "award_transmission.csv",
    )

    convert_numeric(
        dataframe,
        [
            "transmission_id",
            "award_id",
            "sequence_number",
            "account_type_code",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["transmission_date", "update_timestamp"],
    )

    return dataframe


def prepare_award_transmission_child(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    """Prepares AWARD_TRANSMISSION_CHILD rows for archival.
    overhead_key/base_code/off_campus are the actual F&A rate basis
    values used for this specific transmission and are left untouched
    - see docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md.
    """
    require_columns(
        dataframe,
        AWARD_TRANSMISSION_CHILD_REQUIRED_COLUMNS,
        "award_transmission_child.csv",
    )

    convert_numeric(
        dataframe,
        [
            "transmission_child_id",
            "transmission_id",
            "award_id",
            "sequence_number",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["update_timestamp"],
    )

    return dataframe


def create_load_run(
    connection: Connection,
    total_rows: int,
) -> int:
    load_id = connection.execute(
        text(
            """
            INSERT INTO archive.load_run (
                domain,
                source_system,
                source_file_name,
                rows_read,
                status
            )
            VALUES (
                'AWARD',
                'KUALI',
                'Oracle KCOEUS export',
                :rows_read,
                'STARTED'
            )
            RETURNING load_id
            """
        ),
        {"rows_read": total_rows},
    ).scalar_one()

    return int(load_id)



def load_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    table_name: str,
    columns: list[str],
    load_id: int,
) -> int:

    available_columns = [
        c
        for c in columns
        if c in dataframe.columns
    ]

    target = dataframe[
        available_columns
    ].copy()

    target = target.rename(
        columns={
            "update_timestamp":
                "source_update_timestamp",

            "update_user":
                "source_update_user",

            "ver_nbr":
                "source_version_number",

            "active":
                "active_flag",

            # AWARD.DOCUMENT_NUMBER (post-normalize_columns:
            # document_number) is the real Kuali workflow document
            # number - KREW_DOC_HDR_T.DOC_HDR_ID - renamed the same way
            # _CHILD_COLUMN_RENAMES does for the incremental
            # (--load-award-id/--load-batch) path, so the full load and
            # incremental load never diverge on this column. A no-op for
            # every other table load_dataframe() is called for (only
            # award_version's own call site passes "document_number" in
            # its columns list).
            "document_number":
                "workflow_document_number",
        }
    )

    target["load_id"] = load_id

    logger.info(
        "COPY {:<30} {:,} rows",
        table_name,
        len(target),
    )

    return bulk_copy_dataframe(
        connection=connection,
        dataframe=target,
        schema="archive",
        table=table_name,
    )

# Every Award-owned archive table as of V052 (confirmed by inventorying
# every INSERT INTO archive.* target across every upsert_award_*/
# load_dataframe call in this module, then cross-checking against a real
# Postgres instance with every migration through V052 applied - not
# hand-maintained from memory). 44 of these have a real Postgres FK,
# direct or transitive, into one of the four original full-load tables
# (award_version/award_amount_info/award_person/award_funding_proposal);
# the remaining 7 (award_amount_transaction, award_hierarchy,
# award_subcontracting_budgeted_goals, pending_transaction,
# pending_transaction_extension, time_and_money_document,
# transaction_detail) reference Award only via a bare, unenforced
# award_id/award_number column - the same cross-award_number-family
# treatment documented on AWARD_TIME_AND_MONEY_DESIGN.md and
# SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md - and have no Postgres FK to
# any of the four at all. Deliberately excludes archive.award_attachment/
# archive.attachment_object/archive.archived_attachment: those belong to
# the separate Award Attachment loader (etl/load_award_attachments.py),
# never to this module's full load. Deliberately excludes archive.load_run:
# shared provenance table across every domain, not Award-owned.
_AWARD_OWNED_TABLES: tuple[str, ...] = (
    # Budget (5-level bundle, deepest children first)
    "award_budget_line_item_calculated_amount",
    "award_budget_personnel_calculated_amount",
    "award_budget_period_summary_calculated_amount",
    "award_budget_line_item",
    "award_budget_personnel_detail",
    "award_budget_person",
    "award_budget_period",
    "award_budget_limit",
    "award_budget",
    # Budget Person / Transferring Sponsor bundle
    "award_transferring_sponsor",
    # SAP Award Transmission History bundle
    "award_transmission_child",
    "award_transmission",
    # Extension / CGB bundle
    "award_extension",
    "award_cgb",
    # Comment bundle
    "award_comment",
    # Special Approvals / Compliance bundle
    "award_special_review_exemption",
    "award_special_review",
    "award_science_keyword",
    "award_cost_share",
    "award_approved_foreign_travel",
    "award_approved_equipment",
    "award_subcontracting_budgeted_goals",
    # Reporting / Subaward Summary bundle
    "award_approved_subaward",
    "award_payment_schedule",
    "award_closeout",
    # Notepad bundle
    "award_notepad",
    # Contacts bundle
    "award_unit_contact",
    "award_sponsor_contact",
    # Terms bundle
    "award_report_term_recipient",
    "award_report_term",
    "award_sponsor_term",
    "award_fanda_rate",
    "award_cfda",
    # People hierarchy bundle
    "award_person_unit_credit_split",
    "award_person_credit_split",
    "award_person_unit",
    # Custom data bundle
    "award_custom_data",
    # Time and Money bundle (bare Award-number references - see comment above)
    "award_direct_fanda_distribution",
    "award_amount_transaction",
    "transaction_detail",
    "pending_transaction_extension",
    "pending_transaction",
    "time_and_money_document",
    "award_hierarchy",
    # Core Award (the original four full-load tables)
    "award_funding_proposal",
    "award_person",
    "award_amount_info",
    "award_version",
)


def clear_existing_award_data(
    connection: Connection,
) -> None:
    """Reset every Award-owned table for the legacy full load
    (--load-award-id/--load-batch are unaffected - they UPSERT and never
    call this). A single combined TRUNCATE naming every Award-owned
    table explicitly, rather than TRUNCATE ... CASCADE on just the four
    original tables: Postgres requires every table that has an FK into
    any table named in a TRUNCATE statement to also appear in that same
    statement (or be CASCADEd), so this list is both the mechanism and
    the safety boundary - if a future bundle adds a 49th Award table and
    someone forgets to add it here, this raises a real Postgres error
    the next full load run, rather than CASCADE silently reaching it (or
    worse, reaching into a table this function was never meant to
    touch). Confirmed (see docs/architecture/AWARD_FULL_LOAD_RESET.md)
    that no table outside this list - in particular no Proposal,
    Negotiation, Protocol, Subaward, or Attachment table - has any FK
    into any table in this list, so this single statement can never
    reach outside the Award domain. Listed in leaf-to-root order for
    readability only - a single combined TRUNCATE is one atomic
    statement, so intra-list order has no effect on correctness here."""
    logger.info(
        "Clearing existing Award archive data ({} tables)",
        len(_AWARD_OWNED_TABLES),
    )

    table_list = ",\n                ".join(
        f"archive.{table}" for table in _AWARD_OWNED_TABLES
    )
    connection.execute(
        text(
            f"""
            TRUNCATE TABLE
                {table_list}
            RESTART IDENTITY;
            """
        )
    )


def validate_child_award_ids(
    versions: pd.DataFrame,
    child: pd.DataFrame,
    child_name: str,
) -> None:
    valid_ids = set(
        versions["award_id"]
        .dropna()
        .astype("int64")
        .tolist()
    )

    child_ids = set(
        child["award_id"]
        .dropna()
        .astype("int64")
        .tolist()
    )

    missing_ids = sorted(child_ids - valid_ids)

    if missing_ids:
        preview = ", ".join(
            str(value)
            for value in missing_ids[:20]
        )

        raise RuntimeError(
            f"{child_name} contains Award IDs that do not "
            f"exist in award_versions.csv: {preview}"
        )


def mark_load_complete(
    connection: Connection,
    load_id: int,
    rows_loaded: int,
) -> None:
    connection.execute(
        text(
            """
            UPDATE archive.load_run
               SET status = 'LOADED',
                   rows_staged = :rows_loaded,
                   rows_loaded = :rows_loaded,
                   rows_rejected = 0,
                   completed_at = CURRENT_TIMESTAMP
             WHERE load_id = :load_id
            """
        ),
        {
            "load_id": load_id,
            "rows_loaded": rows_loaded,
        },
    )


def mark_load_failed(
    engine: Engine,
    load_id: int,
    error_message: str,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                   SET status = 'FAILED',
                       completed_at = CURRENT_TIMESTAMP,
                       error_message = :error_message
                 WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "error_message": redact_error_message(error_message),
            },
        )


# --- Phase 4A: incremental UPSERT (--load-award-id / batch framework) -----
#
# Unlike the full load above (TRUNCATE + bulk COPY of everything), this is
# an idempotent UPSERT scoped to exactly one Award's version family and its
# amount_info/person/funding_proposal/custom_data/person_unit/
# person_credit_split/person_unit_credit_split/sponsor_term/report_term/
# report_term_recipient/sponsor_contact/unit_contact/notepad/closeout/
# payment_schedule/approved_subaward/cfda/cost_share/fanda_rate/
# science_keyword/special_review/special_review_exemption/
# approved_equipment/approved_foreign_travel/subcontracting_budgeted_goals/
# comment/extension/cgb/hierarchy/tnm_document/pending_transaction/
# pending_transaction_extension/transaction_detail/
# award_amount_transaction/fanda_distribution/budget/budget_limit/
# budget_period/budget_line_item/
# budget_period_summary_calculated_amount/
# budget_line_item_calculated_amount/budget_personnel_detail/
# budget_personnel_calculated_amount/budget_person/
# transferring_sponsor child rows - safe to run
# against a database that already has other Award data loaded, and safe
# to re-run.
# award_custom_data, the three Award People expansion tables, the three
# Award Terms tables, the two Award Contacts tables, award_notepad, the
# three Award Reporting/Subaward Summary tables, the nine Award Special
# Approvals and Compliance tables, award_comment, the two Award
# Extension/CGB tables, and the full Award Time and Money subsystem
# (seven tables: hierarchy/tnm_document/pending_transaction/
# pending_transaction_extension/transaction_detail/
# award_amount_transaction/fanda_distribution) and the full Award Budget
# subsystem (eight tables: budget/budget_limit/budget_period/
# budget_line_item/budget_period_summary_calculated_amount/
# budget_line_item_calculated_amount/budget_personnel_detail/
# budget_personnel_calculated_amount - each merging an Award-specific
# _EXT table into the generic table it shares with Proposal Development,
# see docs/architecture/AWARD_BUDGET_DESIGN.md) were added here alongside
# the original Phase 4A four (all Tier 1, see
# docs/architecture/AWARD_DOMAIN_DECOMPOSITION.md,
# docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md,
# docs/architecture/AWARD_TERMS_DESIGN.md,
# docs/architecture/AWARD_CONTACTS_DESIGN.md,
# docs/architecture/AWARD_NOTEPAD_DESIGN.md,
# docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md,
# docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md,
# docs/architecture/AWARD_COMMENT_DESIGN.md,
# docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md,
# docs/architecture/AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md,
# docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md,
# docs/architecture/AWARD_BUDGET_DESIGN.md, and
# docs/architecture/AWARD_COMPLETENESS_REPORT.md - budget_person and
# transferring_sponsor, the final Award gap bundle: budget_person
# merges nothing (BUDGET_PERSONS has no Award-specific _EXT table at
# all) and is scoped to Award by joining BUDGET_PERSONS -> BUDGET ->
# AWARD_BUDGET_EXT, keyed by Oracle's own composite PK
# (budget_id, person_sequence_number); transferring_sponsor is a
# simple per-version child table, structurally identical to
# award_sponsor_term); each depends only on
# award_version(award_id) or a table that itself does (or, for
# special_review_exemption/subcontracting_budgeted_goals/extension/
# hierarchy/tnm_document/transaction_detail/award_amount_transaction,
# resolves to one via a join or a distinct award_number lookup - see the
# design docs; pending_transaction/pending_transaction_extension have no
# AWARD_NUMBER column at all and resolve via
# read_filtered_any_column across SOURCE_AWARD_NUMBER/
# DESTINATION_AWARD_NUMBER instead; the eight Budget tables plus
# budget_person resolve AWARD_ID via their own extraction SQL's join
# chain to AWARD_BUDGET_EXT and so are read with the ordinary
# award_id-based bounded reader), so
# they all ride along on the same family-widened load with no separate
# top-level load function. SAP transmission is out of scope entirely.
# Award.basisOfPaymentCode/methodOfPaymentCode ARE captured (see
# AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md - a prior gap this session
# already closed).
#
# WHY THIS WIDENS TO THE WHOLE award_number FAMILY, NOT JUST ONE award_id:
# archive.award_version.is_primary_current is enforced by a partial unique
# index (V013's ux_award_one_primary_current: "at most one TRUE row per
# award_number"). Correctly maintaining that invariant for a single
# award_id in isolation is impossible - deciding which one row in a
# version family is primary requires comparing it against every sibling
# row for the same award_number. So --load-award-id resolves the requested
# award_id's award_number, re-reads that ENTIRE family fresh from Oracle,
# and re-upserts every member together in one transaction. This is more
# than "just this one award_id" by design, not an accident of scope creep.
#
# is_current_version, by contrast, needs no such widening: it is computed
# by Oracle's own window function (PARTITION BY AWARD_NUMBER) in
# sql/extract/award/01_award_versions.sql, server-side, before any
# client-side filtering - so it is already correct per-row regardless of
# how the result set is later narrowed.

AWARD_BATCH_DOMAIN = "AWARD"
AWARD_BATCH_ENTITY_TYPE = "AWARD"


def _connect_oracle() -> oracledb.Connection:
    """Mirrors load_award_attachments.py's own private _connect_oracle()
    exactly (not shared from there - that file is not modified as part
    of this change) - reads the same already-shared
    require_oracle_environment() credential resolver, so --ecs mode's
    configure_ecs_environment() (which writes ORACLE_USER/PASSWORD/DSN
    into os.environ after resolving them from Secrets Manager) works
    unchanged for this loader too."""
    credentials = require_oracle_environment()
    return oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    )


def _run_show_batch(engine: Engine, batch_id: int) -> dict[str, Any]:
    """Read-only batch status report - shared by main()'s local
    --show-batch dispatch and --ecs mode's startup short-circuit, so the
    two never drift apart."""
    report = batch_framework.show_batch(
        engine,
        batch_id,
        domain=AWARD_BATCH_DOMAIN,
        entity_type=AWARD_BATCH_ENTITY_TYPE,
    )
    logger.bind(stage="show_batch", batch_id=batch_id).info(
        "batch_id={} found={} status={} total_items={} pending={} "
        "processing={} completed={} failed={} missing_source={} "
        "skipped={}",
        report["batch_id"],
        report["found"],
        report.get("status"),
        report.get("total_items"),
        report.get("pending"),
        report.get("processing"),
        report.get("completed"),
        report.get("failed"),
        report.get("missing_source"),
        report.get("skipped"),
    )
    return report


def _run_ecs_setup(arguments: argparse.Namespace, run_id: str) -> bool:
    """--ecs mode setup, mirroring load_award_attachments.py's
    _run_ecs_setup() exactly in shape (not shared code - that file is
    not modified as part of this change - but the same sequence and the
    same underlying shared utilities: configure_structured_logging,
    validate_aws_identity, configure_ecs_environment,
    create_postgres_engine, validate_postgres_reachable,
    validate_oracle_reachable, validate_table_exists), in exactly this
    order:

    1. structured logging
    2. AWS task-role identity via STS
    3. one Secrets Manager client for the whole startup
    4. load the PostgreSQL secret (always required)
    5. load the Oracle secret (skipped entirely for --migrate-only and
       --show-batch - neither touches Oracle)
    6. verify PostgreSQL connectivity
    7. if --migrate-only: apply migrations, validate the resulting
       schema, and return True so main() exits without ever reaching
       Oracle or Award data
    7a. if --show-batch: run the read-only batch status report and
        return True, same as --migrate-only - PostgreSQL only
    8. verify Oracle connectivity

    Aborts immediately (lets the raised exception propagate) if any step
    fails - no Award data may be read before every required check for
    the requested mode passes. Returns True when --migrate-only or
    --show-batch completed successfully (the caller must not proceed
    further), False otherwise."""
    configure_structured_logging(run_id)
    logger.bind(stage="startup").info(
        "Starting in --ecs mode: run_id={}", run_id
    )

    identity = validate_aws_identity(boto3.client("sts"))
    logger.bind(stage="startup").info(
        "AWS identity resolved via ECS task role: account={}",
        identity["account"],
    )

    secrets_client = boto3.client("secretsmanager")

    configure_ecs_environment(
        secrets_client,
        include_oracle=not (
            arguments.migrate_only or arguments.show_batch is not None
        ),
    )

    engine = create_postgres_engine()
    validate_postgres_reachable(engine)
    logger.bind(stage="startup").info("PostgreSQL reachable")

    if arguments.migrate_only:
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        logger.bind(stage="startup").info("Migrations applied")

        validate_table_exists(engine, "award_version")
        validate_table_exists(engine, "award_transmission_child")
        logger.bind(stage="startup", status="migrate_only_complete").info(
            "Migration and schema validation complete"
        )
        return True

    if arguments.show_batch is not None:
        _run_show_batch(engine, arguments.show_batch)
        logger.bind(stage="startup", status="show_batch_complete").info(
            "Batch status report complete"
        )
        return True

    validate_oracle_reachable(_connect_oracle)
    logger.bind(stage="startup").info("Oracle reachable")

    if arguments.diff_award_versions is not None:
        _run_diff_award_versions(engine, arguments.diff_award_versions)
        logger.bind(
            stage="startup", status="diff_award_versions_complete"
        ).info("Award/Oracle version diff complete")
        return True

    if arguments.investigate_workflow_document_number is not None:
        _run_investigate_workflow_document_number(
            arguments.investigate_workflow_document_number
        )
        logger.bind(
            stage="startup",
            status="investigate_workflow_document_number_complete",
        ).info("Workflow document number investigation complete")
        return True

    logger.bind(stage="startup").info("Startup validation passed")
    return False


# Schema investigation only - confirms whether AWARD.DOCUMENT_NUMBER and
# KREW_DOC_HDR_T/KREW_DOC_TYP_T exist, are reachable, and have the exact
# column names/datatypes a prior local checkout of the open-source Kuali
# Research schema (coeus-db-sql's V300_002__schema.sql/V300_107__schema.sql)
# suggests, before writing any extraction SQL, migration, or DTO change
# against them. That open-source schema is strong evidence but is not by
# itself proof of BU's actual deployed schema - see this function's own
# report output for what it directly confirmed against BU's real Oracle.
_WORKFLOW_DOCUMENT_INTROSPECTION_SQL = """
    SELECT table_name, column_name, data_type, data_length, nullable
    FROM all_tab_columns
    WHERE table_name IN ('AWARD', 'KREW_DOC_HDR_T', 'KREW_DOC_TYP_T')
      AND column_name IN (
          'DOCUMENT_NUMBER', 'MODIFICATION_NUMBER', 'AWARD_ID',
          'AWARD_NUMBER', 'SEQUENCE_NUMBER',
          'DOC_HDR_ID', 'DOC_TYP_ID', 'DOC_HDR_STAT_CD', 'APP_DOC_ID',
          'TTL', 'CRTE_DT', 'FNL_DT', 'INITR_PRNCPL_ID',
          'DOC_TYP_NM'
      )
    ORDER BY table_name, column_name
"""

_WORKFLOW_DOCUMENT_JOIN_SQL = """
    SELECT
        a.award_id,
        a.award_number,
        a.sequence_number,
        a.document_number AS award_document_reference,
        h.doc_hdr_id       AS workflow_document_number,
        t.doc_typ_nm,
        h.doc_hdr_stat_cd,
        h.app_doc_id,
        h.ttl,
        h.crte_dt,
        h.fnl_dt,
        h.initr_prncpl_id
    FROM award a
    LEFT JOIN krew_doc_hdr_t h
        ON TO_CHAR(h.doc_hdr_id) = a.document_number
    LEFT JOIN krew_doc_typ_t t
        ON t.doc_typ_id = h.doc_typ_id
    WHERE a.award_number = :award_number
    ORDER BY a.sequence_number
"""


def _run_investigate_workflow_document_number(award_number: str) -> dict[str, Any]:
    """--investigate-workflow-document-number: read-only Oracle schema
    investigation, NOT a production feature and NOT yet wired to
    PostgreSQL/the archive in any way. Confirms (1) whether
    AWARD.DOCUMENT_NUMBER, KREW_DOC_HDR_T, and KREW_DOC_TYP_T actually
    exist and are reachable from this Oracle connection, with their real
    column names/datatypes, and (2), if so, runs the proposed
    AWARD.DOCUMENT_NUMBER -> KREW_DOC_HDR_T.DOC_HDR_ID join for exactly
    one award_number family, reporting per-sequence whether a workflow
    document header was found. Uses TO_CHAR(h.doc_hdr_id) = a.document_number
    (not TO_NUMBER(a.document_number) = h.doc_hdr_id) deliberately: Oracle
    would raise ORA-01722 for the whole query if even one document_number
    value were ever non-numeric, whereas TO_CHAR on the NUMBER side can
    never fail. Never writes anything, to Oracle or PostgreSQL."""
    investigate_logger = logger.bind(
        stage="investigate_workflow_document_number",
        award_number=award_number,
    )

    connection = _connect_oracle()
    try:
        with connection.cursor() as cursor:
            cursor.execute(_WORKFLOW_DOCUMENT_INTROSPECTION_SQL)
            columns_found = cursor.fetchall()

        print("=== Schema introspection (all_tab_columns) ===")
        print(f"{'TABLE_NAME':<20}{'COLUMN_NAME':<20}{'DATA_TYPE':<15}{'LENGTH':>8}  NULLABLE")
        for table_name, column_name, data_type, data_length, nullable in columns_found:
            print(
                f"{table_name:<20}{column_name:<20}{data_type:<15}"
                f"{data_length:>8}  {nullable}"
            )

        found_tables = {row[0] for row in columns_found}
        missing_tables = {"AWARD", "KREW_DOC_HDR_T", "KREW_DOC_TYP_T"} - found_tables
        if missing_tables:
            print(
                f"\nWARNING: no columns visible at all for: {sorted(missing_tables)} "
                "- either these tables don't exist under these exact names in "
                "BU's schema, or this Oracle user has no SELECT grant on them."
            )
            investigate_logger.info(
                "Schema introspection incomplete - missing/inaccessible tables: {}",
                sorted(missing_tables),
            )
            return {
                "award_number": award_number,
                "columns_found": [
                    {
                        "table_name": row[0],
                        "column_name": row[1],
                        "data_type": row[2],
                    }
                    for row in columns_found
                ],
                "missing_tables": sorted(missing_tables),
                "rows": [],
            }

        with connection.cursor() as cursor:
            cursor.execute(
                _WORKFLOW_DOCUMENT_JOIN_SQL, {"award_number": award_number}
            )
            join_columns = [d[0].lower() for d in cursor.description]
            join_rows = cursor.fetchall()

        print(f"\n=== Workflow document join for award_number={award_number} ===")
        print("  ".join(join_columns))
        report_rows = []
        for row in join_rows:
            row_dict = dict(zip(join_columns, row, strict=True))
            print(row_dict)
            report_rows.append(row_dict)

        has_workflow_doc = sum(
            1 for row in report_rows if row.get("workflow_document_number") is not None
        )
        investigate_logger.info(
            "award_number={}: {} sequence(s), {} with a matched workflow "
            "document header, {} without",
            award_number,
            len(report_rows),
            has_workflow_doc,
            len(report_rows) - has_workflow_doc,
        )

        return {
            "award_number": award_number,
            "columns_found": [
                {
                    "table_name": row[0],
                    "column_name": row[1],
                    "data_type": row[2],
                }
                for row in columns_found
            ],
            "missing_tables": [],
            "rows": report_rows,
        }
    finally:
        connection.close()


def _run_diff_award_versions(
    engine: Engine, award_number: str
) -> dict[str, Any]:
    """--diff-award-versions: developer/investigation aid, read-only
    side-by-side comparison of Oracle's AWARD rows for exactly this
    award_number family against archive.award_version - explains, per
    Oracle-side sequence, whether it is archived at all and whether its
    modification_number ("document number" - see
    AwardVersionSummaryResponse's own doc-comment for why this column,
    not a fabricated one, is what "document number" means) value
    matches. Never writes to either database.

    Reads Oracle via a targeted, bind-variable AWARD_NUMBER IN (...)
    filter (read_award_versions_matching_award_numbers ->
    OracleDataSource.read_filtered), never a full-table scan - unlike
    --show-batch, this DOES require ORACLE_SECRET_ID/Oracle
    connectivity."""
    diff_logger = logger.bind(
        stage="diff_award_versions", award_number=award_number
    )

    oracle_rows = read_award_versions_matching_award_numbers(
        OracleDataSource(VERSIONS_ORACLE_SQL), {award_number}
    )
    if oracle_rows.empty:
        diff_logger.info(
            "award_number={} not found in Oracle at all", award_number
        )
        return {
            "award_number": award_number,
            "oracle_count": 0,
            "archive_count": 0,
            "rows": [],
        }

    oracle_rows = prepare_versions(oracle_rows)

    with engine.connect() as connection:
        archive_rows = connection.execute(
            text(
                """
                SELECT award_id, sequence_number, modification_number,
                       transaction_type, source_update_timestamp
                FROM archive.award_version
                WHERE award_number = :award_number
                """
            ),
            {"award_number": award_number},
        ).mappings().all()

    archive_by_award_id = {
        int(row["award_id"]): row for row in archive_rows
    }

    report_rows: list[dict[str, Any]] = []
    for _, oracle_row in oracle_rows.sort_values("sequence_number").iterrows():
        award_id = int(oracle_row["award_id"])
        archive_row = archive_by_award_id.get(award_id)

        oracle_doc_number = oracle_row.get("modification_number")
        oracle_doc_number = (
            None if pd.isna(oracle_doc_number) else str(oracle_doc_number)
        )

        if archive_row is None:
            reason = "award_id not archived at all - ETL has never loaded this sequence"
            archive_doc_number = None
        else:
            archive_doc_number = archive_row["modification_number"]
            if oracle_doc_number == archive_doc_number:
                reason = (
                    "present and matches Oracle"
                    if oracle_doc_number
                    else (
                        "blank/null in both Oracle and archive - not a "
                        "bug, Oracle genuinely has no value here"
                    )
                )
            else:
                reason = (
                    f"MISMATCH: Oracle has {oracle_doc_number!r} but "
                    f"archive has {archive_doc_number!r} - a real ETL "
                    "load gap, not a naming/mapping bug"
                )

        report_rows.append(
            {
                "award_id": award_id,
                "sequence_number": int(oracle_row["sequence_number"]),
                "oracle_document_number": oracle_doc_number,
                "archive_document_number": archive_doc_number,
                "oracle_transaction_type": oracle_row.get("transaction_type"),
                "oracle_update_timestamp": str(
                    oracle_row.get("update_timestamp")
                ),
                "reason": reason,
            }
        )

    header = (
        f"{'AWARD_ID':>10}  {'SEQ':>4}  {'ORACLE_DOC_NUM':<20}"
        f"  {'ARCHIVE_DOC_NUM':<20}  {'TXN_TYPE':<25}  REASON"
    )
    print(header)
    print("-" * len(header))
    for row in report_rows:
        print(
            f"{row['award_id']:>10}  {row['sequence_number']:>4}  "
            f"{str(row['oracle_document_number']):<20}  "
            f"{str(row['archive_document_number']):<20}  "
            f"{str(row['oracle_transaction_type'])[:25]:<25}  {row['reason']}"
        )

    mismatches = [
        r
        for r in report_rows
        if "MISMATCH" in r["reason"] or "not archived" in r["reason"]
    ]
    diff_logger.info(
        "award_number={}: Oracle has {} sequence(s), archive has {}, "
        "{} discrepant",
        award_number,
        len(report_rows),
        len(archive_by_award_id),
        len(mismatches),
    )

    return {
        "award_number": award_number,
        "oracle_count": len(report_rows),
        "archive_count": len(archive_by_award_id),
        "rows": report_rows,
    }


_AWARD_VERSION_COLUMNS = [
    "award_number",
    "sequence_number",
    "award_sequence_status",
    "status_code",
    "status_description",
    "title",
    "sponsor_code",
    "sponsor_name",
    "prime_sponsor_code",
    "prime_sponsor_name",
    "lead_unit_number",
    "lead_unit_name",
    "proposal_number",
    "account_number",
    "sponsor_award_number",
    "award_effective_date",
    "award_execution_date",
    "begin_date",
    "closeout_date",
    "transaction_type_code",
    "transaction_type",
    "basis_of_payment_code",
    "basis_of_payment_description",
    "method_of_payment_code",
    "method_of_payment_description",
    "modification_number",
    "workflow_document_number",
    "source_update_timestamp",
    "source_update_user",
    "is_current_version",
    "is_primary_current",
]

_AWARD_AMOUNT_INFO_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "anticipated_change_direct",
    "anticipated_change_indirect",
    "anticipated_total_direct",
    "anticipated_total_indirect",
    "obligated_total_direct",
    "obligated_total_indirect",
    "anticipated_total_amount",
    "obligated_total_amount",
    "tnm_document_number",
    "transaction_id",
    "originating_award_version",
    "source_version_number",
]

_AWARD_PERSON_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "person_id",
    "rolodex_id",
    "full_name",
    "contact_role_code",
    "key_person_project_role",
    "faculty_flag",
    "academic_year_effort",
    "calendar_year_effort",
    "summer_effort",
    "total_effort",
    "source_update_timestamp",
    "source_update_user",
]

_AWARD_FUNDING_PROPOSAL_COLUMNS = [
    "award_id",
    "proposal_id",
    "active_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_CUSTOM_DATA_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "custom_attribute_id",
    "value",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_PERSON_UNIT_COLUMNS = [
    "award_person_id",
    "award_id",
    "award_number",
    "sequence_number",
    "unit_number",
    "lead_unit_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_PERSON_CREDIT_SPLIT_COLUMNS = [
    "award_person_id",
    "award_id",
    "award_number",
    "sequence_number",
    "inv_credit_type_code",
    "credit",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_PERSON_UNIT_CREDIT_SPLIT_COLUMNS = [
    "award_person_unit_id",
    "award_id",
    "award_number",
    "sequence_number",
    "inv_credit_type_code",
    "credit",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SPONSOR_TERM_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "sponsor_term_id",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_REPORT_TERM_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "report_class_code",
    "report_code",
    "frequency_code",
    "frequency_base_code",
    "osp_distribution_code",
    "due_date",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_REPORT_TERM_RECIPIENT_COLUMNS = [
    "award_report_term_id",
    "award_id",
    "award_number",
    "sequence_number",
    "contact_id",
    "contact_type_code",
    "rolodex_id",
    "number_of_copies",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SPONSOR_CONTACT_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "rolodex_id",
    "full_name",
    "contact_role_code",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_UNIT_CONTACT_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "person_id",
    "full_name",
    "unit_contact_type",
    "unit_administrator_type_code",
    "unit_administrator_unit_number",
    "default_unit_contact",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_NOTEPAD_COLUMNS = [
    "award_id",
    "award_number",
    "entry_number",
    "note_topic",
    "comments",
    "restricted_view",
    "source_create_timestamp",
    "source_create_user",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_CLOSEOUT_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "closeout_report_code",
    "closeout_report_name",
    "due_date",
    "final_submission_date",
    "multiple_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_PAYMENT_SCHEDULE_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "award_report_term_id",
    "award_report_term_description",
    "due_date",
    "amount",
    "submit_date",
    "submitted_by",
    "submitted_by_person_id",
    "invoice_number",
    "status_description",
    "status",
    "report_status_code",
    "overdue",
    "source_update_timestamp",
    "source_update_user",
    "source_last_update_timestamp",
    "source_last_update_user",
    "source_version_number",
]

_AWARD_APPROVED_SUBAWARD_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "organization_name",
    "organization_id",
    "amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_CFDA_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "cfda_number",
    "cfda_description",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_COST_SHARE_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "project_period",
    "cost_share_percentage",
    "cost_share_type_code",
    "unit_number",
    "source",
    "destination",
    "commitment_amount",
    "cost_share_met",
    "verification_date",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_FANDA_RATE_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "applicable_fanda_rate",
    "fanda_rate_type_code",
    "fiscal_year",
    "on_campus_flag",
    "underrecovery_of_indirect_cost",
    "source_account",
    "destination_account",
    "start_date",
    "end_date",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SCIENCE_KEYWORD_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "science_keyword_code",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SPECIAL_REVIEW_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "special_review_number",
    "special_review_type_code",
    "approval_type_code",
    "protocol_number",
    "application_date",
    "approval_date",
    "expiration_date",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SPECIAL_REVIEW_EXEMPTION_COLUMNS = [
    "award_special_review_id",
    "award_id",
    "award_number",
    "sequence_number",
    "exemption_type_code",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_APPROVED_EQUIPMENT_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "item",
    "model",
    "vendor",
    "amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_APPROVED_FOREIGN_TRAVEL_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "person_id",
    "rolodex_id",
    "traveler_name",
    "destination",
    "start_date",
    "end_date",
    "amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_SUBCONTRACTING_BUDGETED_GOALS_COLUMNS = [
    "large_business_goal_amount",
    "small_business_goal_amount",
    "woman_owned_goal_amount",
    "eight_a_disadvantage_goal_amount",
    "hub_zone_goal_amount",
    "veteran_owned_goal_amount",
    "service_disabled_veteran_owned_goal_amount",
    "historical_black_college_goal_amount",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_COMMENT_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "comment_type_code",
    "checklist_print_flag",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_EXTENSION_COLUMNS = [
    "award_number",
    "sequence_number",
    "proposed_for_transmission_indicator",
    "last_transmission_date",
    "child_type",
    "child_description",
    "major_project",
    "arra_code",
    "avc_indicator",
    "a133_cluster",
    "fringe_not_allowed_indicator",
    "interest_earned",
    "interest_earned_account_number",
    "stepped_up_rate",
    "bu_bmc_fa_split",
    "conference_grant",
    "program_income",
    "stock_award",
    "foreign_currency_award",
    "nce_notification_date",
    "clinical_trial_initiated_by",
    "ind_ide_responsibility",
    "clinical_trial_registration_date",
    "spuds_record_number",
    "walker_source_number",
    "prime_sponsor_award_id",
    "grant_number",
    "federal_clinical_trial",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_CGB_COLUMNS = [
    "award_number",
    "sequence_number",
    "additional_forms_required",
    "auto_approve_invoice",
    "stop_work",
    "min_invoice_amount",
    "invoicing_option",
    "dunning_campaign_id",
    "last_billed_date",
    "previous_last_billed_date",
    "final_bill",
    "amount_to_draw",
    "letter_of_credit_review_indicator",
    "invoice_document_status",
    "loc_creation_type",
    "suspend_invoicing",
    "bill_freq_cd",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_HIERARCHY_COLUMNS = [
    "root_award_number",
    "award_number",
    "parent_award_number",
    "originating_award_number",
    "active",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_TIME_AND_MONEY_DOCUMENT_COLUMNS = [
    "root_award_number",
    "document_status",
    "creation_date",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_PENDING_TRANSACTION_COLUMNS = [
    "document_number",
    "source_award_number",
    "destination_award_number",
    "obligated_amount",
    "obligated_direct_amount",
    "obligated_indirect_amount",
    "anticipated_amount",
    "anticipated_direct_amount",
    "anticipated_indirect_amount",
    "comments",
    "processed_flag",
    "single_node_transaction",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_PENDING_TRANSACTION_EXTENSION_COLUMNS = [
    "budget_period",
]

_TRANSACTION_DETAIL_COLUMNS = [
    "award_number",
    "sequence_number",
    "transaction_id",
    "time_and_money_document_number",
    "source_award_number",
    "destination_award_number",
    "obligated_amount",
    "obligated_direct_amount",
    "obligated_indirect_amount",
    "anticipated_amount",
    "anticipated_direct_amount",
    "anticipated_indirect_amount",
    "comments",
    "transaction_detail_type",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_AMOUNT_TRANSACTION_COLUMNS = [
    "award_number",
    "document_number",
    "transaction_type_code",
    "transaction_type_description",
    "notice_date",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_DIRECT_FANDA_DISTRIBUTION_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "amount_sequence_number",
    "award_amount_info_id",
    "budget_period",
    "start_date",
    "end_date",
    "direct_cost",
    "indirect_cost",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_COLUMNS = [
    "award_id",
    "document_number",
    "award_budget_status_code",
    "award_budget_status_description",
    "award_budget_type_code",
    "award_budget_type_description",
    "budget_version_number",
    "name",
    "description",
    "budget_initiator",
    "start_date",
    "end_date",
    "total_cost",
    "total_direct_cost",
    "total_indirect_cost",
    "total_cost_limit",
    "cost_sharing_amount",
    "underrecovery_amount",
    "residual_funds",
    "obligated_amount",
    "obligated_total",
    "oh_rate_class_code",
    "oh_rate_type_code",
    "ur_rate_class_code",
    "modular_budget_flag",
    "on_off_campus_flag",
    "submit_cost_sharing_flag",
    "parent_document_type_code",
    "budget_adjustment_document_number",
    "comments",
    "budget_justification",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_PERIOD_COLUMNS = [
    "budget_id",
    "budget_period",
    "start_date",
    "end_date",
    "total_cost",
    "total_direct_cost",
    "total_indirect_cost",
    "total_cost_limit",
    "cost_sharing_amount",
    "underrecovery_amount",
    "number_of_participants",
    "obligated_amount",
    "total_fringe_amount",
    "fringe_overridden",
    "f_and_a_overridden",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_LINE_ITEM_COLUMNS = [
    "budget_period_id",
    "budget_id",
    "budget_period",
    "line_item_number",
    "budget_category_code",
    "cost_element",
    "line_item_description",
    "group_name",
    "based_on_line_item",
    "line_item_sequence",
    "start_date",
    "end_date",
    "line_item_cost",
    "cost_sharing_amount",
    "underrecovery_amount",
    "obligated_amount",
    "quantity",
    "on_off_campus_flag",
    "apply_in_rate_flag",
    "submit_cost_sharing_flag",
    "formulated_cost_element_flag",
    "subaward_number",
    "hierarchy_proposal_number",
    "hidden_in_hierarchy",
    "budget_justification",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_LINE_ITEM_CALCULATED_AMOUNT_COLUMNS = [
    "budget_line_item_id",
    "budget_period_id",
    "budget_id",
    "budget_period",
    "line_item_number",
    "rate_class_code",
    "rate_type_code",
    "rate_type_description",
    "apply_rate_flag",
    "calculated_cost",
    "calculated_cost_sharing",
    "obligated_amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_PERSONNEL_DETAIL_COLUMNS = [
    "budget_line_item_id",
    "budget_period_id",
    "budget_id",
    "budget_period",
    "line_item_number",
    "person_number",
    "person_sequence_number",
    "person_id",
    "job_code",
    "period_type_code",
    "line_item_description",
    "sequence_number",
    "start_date",
    "end_date",
    "salary_requested",
    "percent_charged",
    "percent_effort",
    "cost_sharing_percent",
    "cost_sharing_amount",
    "underrecovery_amount",
    "obligated_amount",
    "on_off_campus_flag",
    "apply_in_rate_flag",
    "budget_justification",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_PERSONNEL_CALCULATED_AMOUNT_COLUMNS = [
    "budget_personnel_line_item_id",
    "budget_period_id",
    "budget_id",
    "budget_period",
    "line_item_number",
    "person_number",
    "rate_class_code",
    "rate_type_code",
    "rate_type_description",
    "apply_rate_flag",
    "calculated_cost",
    "calculated_cost_sharing",
    "obligated_amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_COLUMNS = [
    "budget_period_id",
    "cost_element",
    "on_off_campus_flag",
    "rate_class_type",
    "calculated_cost",
    "calculated_cost_sharing",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_BUDGET_LIMIT_COLUMNS = [
    "award_id",
    "budget_id",
    "limit_type_code",
    "limit_amount",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

# archive.award_budget_person's PK is composite (budget_id,
# person_sequence_number - Oracle's own real composite PK, no surrogate
# id exists) - both PK members are set directly in
# upsert_award_budget_person rather than looped from this list, the
# same convention single-PK tables use for their own PK column.
_BUDGET_PERSON_COLUMNS = [
    "effective_date",
    "job_code",
    "non_employee_flag",
    "person_id",
    "appointment_type_code",
    "rolodex_id",
    "tbn_id",
    "calculation_base",
    "person_name",
    "salary_anniversary_date",
    "hierarchy_proposal_number",
    "hidden_in_hierarchy",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_TRANSFERRING_SPONSOR_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "sponsor_code",
    "sponsor_name",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_TRANSMISSION_COLUMNS = [
    "award_id",
    "award_number",
    "sequence_number",
    "initiator_id",
    "transmitter_id",
    "success_indicator",
    "transmission_date",
    "sent_data",
    "returned_data",
    "basis_of_payment_code",
    "account_type_code",
    "sponsor_code",
    "method_of_payment_code",
    "document_number",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_AWARD_TRANSMISSION_CHILD_COLUMNS = [
    "transmission_id",
    "award_id",
    "award_number",
    "sequence_number",
    "parent_document_number",
    "child_document_number",
    "lead_unit_number",
    "child_type",
    "overhead_key",
    "base_code",
    "off_campus",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

# Oracle-column-name -> archive-column-name renames, matching load_dataframe's
# own rename table for the full load, applied here per-row via .get() with
# the Oracle-side name below (prepare_amounts/prepare_people/prepare_proposals
# don't rename these columns themselves - only the full load's load_dataframe
# does today, so the incremental path must apply the same rename).
_CHILD_COLUMN_RENAMES = {
    "update_timestamp": "source_update_timestamp",
    "update_user": "source_update_user",
    "ver_nbr": "source_version_number",
    "active": "active_flag",
    "create_timestamp": "source_create_timestamp",
    "create_user": "source_create_user",
    "multiple": "multiple_flag",
    # AWARD.DOCUMENT_NUMBER (post-normalize_columns: document_number) is
    # the real Kuali workflow document number - KREW_DOC_HDR_T.DOC_HDR_ID
    # - renamed on the way into the archive to avoid any reader assuming
    # it's the same thing as the separate, often-NULL modification_number
    # column (see V055's migration header for the full investigation).
    "document_number": "workflow_document_number",
}


def _renamed(row: pd.Series, column: str) -> Any:
    """Read `column` from `row`, honoring the same Oracle->archive column
    renames load_dataframe() applies for the full load (e.g. ver_nbr ->
    source_version_number), so a single shared column-name list can be
    used for both the INSERT column list and the per-row value lookup."""
    for oracle_name, archive_name in _CHILD_COLUMN_RENAMES.items():
        if archive_name == column and column not in row.index and oracle_name in row.index:
            return row.get(oracle_name)
    return row.get(column)


def _sql_value(value: Any) -> Any:
    """Convert a pandas scalar into a value safe to bind as a SQL
    parameter - NaN/NaT become NULL, and a whole-number float (pandas'
    representation of a nullable integer column) becomes a real int."""
    if pd.isna(value):
        return None
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def read_award_number_for_award_id(
    source: OracleDataSource, award_id: int
) -> str | None:
    """Resolve exactly one award_id's award_number via a single
    Oracle-side WHERE AWARD_ID IN (:b0) bind-variable query
    (OracleDataSource.read_filtered) instead of scanning the full Award
    version source - award_id is unique per row (it is AWARD's own
    primary key), so at most one row can match. Returns None if
    award_id isn't found at all. Used only to resolve which whole
    award_number version family a bounded --load-award-id request
    belongs to. For resolving many award_ids at once (e.g.
    --load-batch), use read_award_numbers_for_award_ids instead - it
    does this in O(1) Oracle round trips per 1000 award_ids rather than
    one round trip per award_id."""
    result = source.read_filtered(column="AWARD_ID", values=[award_id])
    if result.empty:
        return None
    return str(result.iloc[0]["award_number"])


def read_award_numbers_for_award_ids(
    source: OracleDataSource, award_ids: set[int]
) -> dict[int, str]:
    """Batch form of read_award_number_for_award_id: resolve every
    award_id's award_number in one (chunked) set of Oracle-side
    WHERE AWARD_ID IN (...) bind-variable queries, instead of one
    Oracle round trip per award_id. Used by --load-batch to resolve an
    entire batch's award_id -> award_number mapping up front. award_ids
    absent from Oracle simply have no entry in the returned dict - the
    caller distinguishes "missing" the same way the single-id form
    does (a missing key vs. a None return)."""
    if not award_ids:
        return {}
    result = source.read_filtered(column="AWARD_ID", values=list(award_ids))
    if result.empty:
        return {}
    return {
        int(row["award_id"]): str(row["award_number"])
        for _, row in result.iterrows()
    }


def read_award_versions_matching_award_numbers(
    source: OracleDataSource, target_award_numbers: set[str]
) -> pd.DataFrame:
    """Resolve an entire award_number version family via a single
    (chunked) set of Oracle-side WHERE AWARD_NUMBER IN (...)
    bind-variable queries (OracleDataSource.read_filtered), instead of
    scanning the full Award version source. Always resolves every
    matching row - award_number is not unique per row (one row per
    sequence_number in the family)."""
    if not target_award_numbers:
        return pd.DataFrame()
    return source.read_filtered(
        column="AWARD_NUMBER", values=list(target_award_numbers)
    )


def read_award_children_matching_award_ids(
    source: OracleDataSource, target_award_ids: set[int]
) -> pd.DataFrame:
    """Shared by every Award child table: resolve rows for exactly this
    family's award_id values via a single (chunked) set of Oracle-side
    WHERE AWARD_ID IN (...) bind-variable queries
    (OracleDataSource.read_filtered), instead of scanning the full
    source. award_id is not unique on any child table (many rows can
    share one award_id)."""
    if not target_award_ids:
        return pd.DataFrame()
    return source.read_filtered(column="AWARD_ID", values=list(target_award_ids))


def read_award_children_matching_award_numbers(
    source: OracleDataSource, target_award_numbers: set[str]
) -> pd.DataFrame:
    """Like read_award_children_matching_award_ids, but for the one
    table in this schema with no AWARD_ID column at all:
    SUBCONTRACTING_BUD (archive.award_subcontracting_budgeted_goals) is
    keyed directly by AWARD_NUMBER, with no surrogate ID and no tie to
    any specific Award version - see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
    Resolves rows for exactly the requested award_number set via a
    single (chunked) set of Oracle-side WHERE AWARD_NUMBER IN (...)
    bind-variable queries (OracleDataSource.read_filtered), instead of
    scanning the full source."""
    if not target_award_numbers:
        return pd.DataFrame()
    return source.read_filtered(
        column="AWARD_NUMBER", values=list(target_award_numbers)
    )


def read_pending_transactions_matching_award_numbers(
    source: OracleDataSource, target_award_numbers: set[str]
) -> pd.DataFrame:
    """PENDING_TRANSACTIONS has no bare AWARD_NUMBER column at all - only
    SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER - so a transaction
    belongs to a loaded Award if it appears on EITHER side. Uses
    OracleDataSource.read_filtered_any_column instead of two separate
    read_filtered calls, so this table is still read exactly once per
    batch (not twice) - see
    docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md."""
    if not target_award_numbers:
        return pd.DataFrame()
    return source.read_filtered_any_column(
        columns=["SOURCE_AWARD_NUMBER", "DESTINATION_AWARD_NUMBER"],
        values=list(target_award_numbers),
    )


def upsert_award_version(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_version row.
    Returns exactly one of "inserted", "updated", "unchanged". Callers
    MUST have already cleared this award_number's old is_primary_current
    flag (see _run_load_award_id) before calling this for the new
    primary-current row, or the partial unique index
    ux_award_one_primary_current will reject the write."""
    params: dict[str, Any] = {
        "award_id": _sql_value(row["award_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_VERSION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_version (
                award_id, award_number, sequence_number, award_sequence_status,
                status_code, status_description, title, sponsor_code,
                sponsor_name, prime_sponsor_code, prime_sponsor_name,
                lead_unit_number, lead_unit_name, proposal_number,
                account_number, sponsor_award_number, award_effective_date,
                award_execution_date, begin_date, closeout_date,
                transaction_type_code, transaction_type,
                basis_of_payment_code, basis_of_payment_description,
                method_of_payment_code, method_of_payment_description,
                modification_number, workflow_document_number,
                source_update_timestamp, source_update_user,
                is_current_version, is_primary_current, load_id
            ) VALUES (
                :award_id, :award_number, :sequence_number,
                :award_sequence_status, :status_code, :status_description,
                :title, :sponsor_code, :sponsor_name, :prime_sponsor_code,
                :prime_sponsor_name, :lead_unit_number, :lead_unit_name,
                :proposal_number, :account_number, :sponsor_award_number,
                :award_effective_date, :award_execution_date, :begin_date,
                :closeout_date, :transaction_type_code, :transaction_type,
                :basis_of_payment_code, :basis_of_payment_description,
                :method_of_payment_code, :method_of_payment_description,
                :modification_number, :workflow_document_number,
                :source_update_timestamp,
                :source_update_user, :is_current_version, :is_primary_current,
                :load_id
            )
            ON CONFLICT (award_id) DO UPDATE SET
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                award_sequence_status = EXCLUDED.award_sequence_status,
                status_code = EXCLUDED.status_code,
                status_description = EXCLUDED.status_description,
                title = EXCLUDED.title,
                sponsor_code = EXCLUDED.sponsor_code,
                sponsor_name = EXCLUDED.sponsor_name,
                prime_sponsor_code = EXCLUDED.prime_sponsor_code,
                prime_sponsor_name = EXCLUDED.prime_sponsor_name,
                lead_unit_number = EXCLUDED.lead_unit_number,
                lead_unit_name = EXCLUDED.lead_unit_name,
                proposal_number = EXCLUDED.proposal_number,
                account_number = EXCLUDED.account_number,
                sponsor_award_number = EXCLUDED.sponsor_award_number,
                award_effective_date = EXCLUDED.award_effective_date,
                award_execution_date = EXCLUDED.award_execution_date,
                begin_date = EXCLUDED.begin_date,
                closeout_date = EXCLUDED.closeout_date,
                transaction_type_code = EXCLUDED.transaction_type_code,
                transaction_type = EXCLUDED.transaction_type,
                basis_of_payment_code = EXCLUDED.basis_of_payment_code,
                basis_of_payment_description =
                    EXCLUDED.basis_of_payment_description,
                method_of_payment_code = EXCLUDED.method_of_payment_code,
                method_of_payment_description =
                    EXCLUDED.method_of_payment_description,
                modification_number = EXCLUDED.modification_number,
                workflow_document_number = EXCLUDED.workflow_document_number,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                is_current_version = EXCLUDED.is_current_version,
                is_primary_current = EXCLUDED.is_primary_current,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_version.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_version.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_version.award_sequence_status
                    IS DISTINCT FROM EXCLUDED.award_sequence_status
                OR archive.award_version.status_code
                    IS DISTINCT FROM EXCLUDED.status_code
                OR archive.award_version.status_description
                    IS DISTINCT FROM EXCLUDED.status_description
                OR archive.award_version.title
                    IS DISTINCT FROM EXCLUDED.title
                OR archive.award_version.sponsor_code
                    IS DISTINCT FROM EXCLUDED.sponsor_code
                OR archive.award_version.sponsor_name
                    IS DISTINCT FROM EXCLUDED.sponsor_name
                OR archive.award_version.prime_sponsor_code
                    IS DISTINCT FROM EXCLUDED.prime_sponsor_code
                OR archive.award_version.prime_sponsor_name
                    IS DISTINCT FROM EXCLUDED.prime_sponsor_name
                OR archive.award_version.lead_unit_number
                    IS DISTINCT FROM EXCLUDED.lead_unit_number
                OR archive.award_version.lead_unit_name
                    IS DISTINCT FROM EXCLUDED.lead_unit_name
                OR archive.award_version.proposal_number
                    IS DISTINCT FROM EXCLUDED.proposal_number
                OR archive.award_version.account_number
                    IS DISTINCT FROM EXCLUDED.account_number
                OR archive.award_version.sponsor_award_number
                    IS DISTINCT FROM EXCLUDED.sponsor_award_number
                OR archive.award_version.award_effective_date
                    IS DISTINCT FROM EXCLUDED.award_effective_date
                OR archive.award_version.award_execution_date
                    IS DISTINCT FROM EXCLUDED.award_execution_date
                OR archive.award_version.begin_date
                    IS DISTINCT FROM EXCLUDED.begin_date
                OR archive.award_version.closeout_date
                    IS DISTINCT FROM EXCLUDED.closeout_date
                OR archive.award_version.transaction_type_code
                    IS DISTINCT FROM EXCLUDED.transaction_type_code
                OR archive.award_version.transaction_type
                    IS DISTINCT FROM EXCLUDED.transaction_type
                OR archive.award_version.basis_of_payment_code
                    IS DISTINCT FROM EXCLUDED.basis_of_payment_code
                OR archive.award_version.basis_of_payment_description
                    IS DISTINCT FROM EXCLUDED.basis_of_payment_description
                OR archive.award_version.method_of_payment_code
                    IS DISTINCT FROM EXCLUDED.method_of_payment_code
                OR archive.award_version.method_of_payment_description
                    IS DISTINCT FROM EXCLUDED.method_of_payment_description
                OR archive.award_version.modification_number
                    IS DISTINCT FROM EXCLUDED.modification_number
                OR archive.award_version.workflow_document_number
                    IS DISTINCT FROM EXCLUDED.workflow_document_number
                OR archive.award_version.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_version.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_version.is_current_version
                    IS DISTINCT FROM EXCLUDED.is_current_version
                OR archive.award_version.is_primary_current
                    IS DISTINCT FROM EXCLUDED.is_primary_current
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_amount_info(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_amount_info row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_amount_info_id": _sql_value(row["award_amount_info_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_AMOUNT_INFO_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_amount_info (
                award_amount_info_id, award_id, award_number, sequence_number,
                anticipated_change_direct, anticipated_change_indirect,
                anticipated_total_direct, anticipated_total_indirect,
                obligated_total_direct, obligated_total_indirect,
                anticipated_total_amount, obligated_total_amount,
                tnm_document_number, transaction_id, originating_award_version,
                source_version_number, load_id
            ) VALUES (
                :award_amount_info_id, :award_id, :award_number,
                :sequence_number, :anticipated_change_direct,
                :anticipated_change_indirect, :anticipated_total_direct,
                :anticipated_total_indirect, :obligated_total_direct,
                :obligated_total_indirect, :anticipated_total_amount,
                :obligated_total_amount, :tnm_document_number,
                :transaction_id, :originating_award_version,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_amount_info_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                anticipated_change_direct = EXCLUDED.anticipated_change_direct,
                anticipated_change_indirect = EXCLUDED.anticipated_change_indirect,
                anticipated_total_direct = EXCLUDED.anticipated_total_direct,
                anticipated_total_indirect = EXCLUDED.anticipated_total_indirect,
                obligated_total_direct = EXCLUDED.obligated_total_direct,
                obligated_total_indirect = EXCLUDED.obligated_total_indirect,
                anticipated_total_amount = EXCLUDED.anticipated_total_amount,
                obligated_total_amount = EXCLUDED.obligated_total_amount,
                tnm_document_number = EXCLUDED.tnm_document_number,
                transaction_id = EXCLUDED.transaction_id,
                originating_award_version = EXCLUDED.originating_award_version,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_amount_info.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_amount_info.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_amount_info.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_amount_info.anticipated_change_direct
                    IS DISTINCT FROM EXCLUDED.anticipated_change_direct
                OR archive.award_amount_info.anticipated_change_indirect
                    IS DISTINCT FROM EXCLUDED.anticipated_change_indirect
                OR archive.award_amount_info.anticipated_total_direct
                    IS DISTINCT FROM EXCLUDED.anticipated_total_direct
                OR archive.award_amount_info.anticipated_total_indirect
                    IS DISTINCT FROM EXCLUDED.anticipated_total_indirect
                OR archive.award_amount_info.obligated_total_direct
                    IS DISTINCT FROM EXCLUDED.obligated_total_direct
                OR archive.award_amount_info.obligated_total_indirect
                    IS DISTINCT FROM EXCLUDED.obligated_total_indirect
                OR archive.award_amount_info.anticipated_total_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_total_amount
                OR archive.award_amount_info.obligated_total_amount
                    IS DISTINCT FROM EXCLUDED.obligated_total_amount
                OR archive.award_amount_info.tnm_document_number
                    IS DISTINCT FROM EXCLUDED.tnm_document_number
                OR archive.award_amount_info.transaction_id
                    IS DISTINCT FROM EXCLUDED.transaction_id
                OR archive.award_amount_info.originating_award_version
                    IS DISTINCT FROM EXCLUDED.originating_award_version
                OR archive.award_amount_info.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_person(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_person row.
    Returns exactly one of "inserted", "updated", "unchanged".
    archive.award_person's underlying Oracle table (AWARD_PERSONS) has no
    natural uniqueness constraint beyond its own surrogate PK - duplicate
    person/role rows per award_id are legitimate, so award_person_id is
    the only safe conflict key."""
    params: dict[str, Any] = {
        "award_person_id": _sql_value(row["award_person_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_PERSON_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_person (
                award_person_id, award_id, award_number, sequence_number,
                person_id, rolodex_id, full_name, contact_role_code,
                key_person_project_role, faculty_flag, academic_year_effort,
                calendar_year_effort, summer_effort, total_effort,
                source_update_timestamp, source_update_user, load_id
            ) VALUES (
                :award_person_id, :award_id, :award_number, :sequence_number,
                :person_id, :rolodex_id, :full_name, :contact_role_code,
                :key_person_project_role, :faculty_flag,
                :academic_year_effort, :calendar_year_effort, :summer_effort,
                :total_effort, :source_update_timestamp, :source_update_user,
                :load_id
            )
            ON CONFLICT (award_person_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                person_id = EXCLUDED.person_id,
                rolodex_id = EXCLUDED.rolodex_id,
                full_name = EXCLUDED.full_name,
                contact_role_code = EXCLUDED.contact_role_code,
                key_person_project_role = EXCLUDED.key_person_project_role,
                faculty_flag = EXCLUDED.faculty_flag,
                academic_year_effort = EXCLUDED.academic_year_effort,
                calendar_year_effort = EXCLUDED.calendar_year_effort,
                summer_effort = EXCLUDED.summer_effort,
                total_effort = EXCLUDED.total_effort,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_person.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_person.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_person.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_person.person_id
                    IS DISTINCT FROM EXCLUDED.person_id
                OR archive.award_person.rolodex_id
                    IS DISTINCT FROM EXCLUDED.rolodex_id
                OR archive.award_person.full_name
                    IS DISTINCT FROM EXCLUDED.full_name
                OR archive.award_person.contact_role_code
                    IS DISTINCT FROM EXCLUDED.contact_role_code
                OR archive.award_person.key_person_project_role
                    IS DISTINCT FROM EXCLUDED.key_person_project_role
                OR archive.award_person.faculty_flag
                    IS DISTINCT FROM EXCLUDED.faculty_flag
                OR archive.award_person.academic_year_effort
                    IS DISTINCT FROM EXCLUDED.academic_year_effort
                OR archive.award_person.calendar_year_effort
                    IS DISTINCT FROM EXCLUDED.calendar_year_effort
                OR archive.award_person.summer_effort
                    IS DISTINCT FROM EXCLUDED.summer_effort
                OR archive.award_person.total_effort
                    IS DISTINCT FROM EXCLUDED.total_effort
                OR archive.award_person.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_person.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_funding_proposal(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_funding_proposal
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_funding_proposal_id": _sql_value(row["award_funding_proposal_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_FUNDING_PROPOSAL_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_funding_proposal (
                award_funding_proposal_id, award_id, proposal_id, active_flag,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_funding_proposal_id, :award_id, :proposal_id,
                :active_flag, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_funding_proposal_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                proposal_id = EXCLUDED.proposal_id,
                active_flag = EXCLUDED.active_flag,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_funding_proposal.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_funding_proposal.proposal_id
                    IS DISTINCT FROM EXCLUDED.proposal_id
                OR archive.award_funding_proposal.active_flag
                    IS DISTINCT FROM EXCLUDED.active_flag
                OR archive.award_funding_proposal.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_funding_proposal.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_funding_proposal.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_custom_data(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_custom_data
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_custom_data_id": _sql_value(row["award_custom_data_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_CUSTOM_DATA_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_custom_data (
                award_custom_data_id, award_id, award_number, sequence_number,
                custom_attribute_id, value,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_custom_data_id, :award_id, :award_number,
                :sequence_number, :custom_attribute_id, :value,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_custom_data_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                custom_attribute_id = EXCLUDED.custom_attribute_id,
                value = EXCLUDED.value,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_custom_data.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_custom_data.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_custom_data.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_custom_data.custom_attribute_id
                    IS DISTINCT FROM EXCLUDED.custom_attribute_id
                OR archive.award_custom_data.value
                    IS DISTINCT FROM EXCLUDED.value
                OR archive.award_custom_data.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_custom_data.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_custom_data.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_person_unit(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_person_unit
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_person_unit_id": _sql_value(row["award_person_unit_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_PERSON_UNIT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_person_unit (
                award_person_unit_id, award_person_id, award_id,
                award_number, sequence_number, unit_number, lead_unit_flag,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_person_unit_id, :award_person_id, :award_id,
                :award_number, :sequence_number, :unit_number,
                :lead_unit_flag, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_person_unit_id) DO UPDATE SET
                award_person_id = EXCLUDED.award_person_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                unit_number = EXCLUDED.unit_number,
                lead_unit_flag = EXCLUDED.lead_unit_flag,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_person_unit.award_person_id
                    IS DISTINCT FROM EXCLUDED.award_person_id
                OR archive.award_person_unit.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_person_unit.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_person_unit.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_person_unit.unit_number
                    IS DISTINCT FROM EXCLUDED.unit_number
                OR archive.award_person_unit.lead_unit_flag
                    IS DISTINCT FROM EXCLUDED.lead_unit_flag
                OR archive.award_person_unit.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_person_unit.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_person_unit.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_person_credit_split(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_person_credit_split row. Returns exactly one of
    "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_person_credit_split_id": _sql_value(
            row["award_person_credit_split_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_PERSON_CREDIT_SPLIT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_person_credit_split (
                award_person_credit_split_id, award_person_id, award_id,
                award_number, sequence_number, inv_credit_type_code, credit,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_person_credit_split_id, :award_person_id, :award_id,
                :award_number, :sequence_number, :inv_credit_type_code,
                :credit, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_person_credit_split_id) DO UPDATE SET
                award_person_id = EXCLUDED.award_person_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                inv_credit_type_code = EXCLUDED.inv_credit_type_code,
                credit = EXCLUDED.credit,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_person_credit_split.award_person_id
                    IS DISTINCT FROM EXCLUDED.award_person_id
                OR archive.award_person_credit_split.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_person_credit_split.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_person_credit_split.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_person_credit_split.inv_credit_type_code
                    IS DISTINCT FROM EXCLUDED.inv_credit_type_code
                OR archive.award_person_credit_split.credit
                    IS DISTINCT FROM EXCLUDED.credit
                OR archive.award_person_credit_split.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_person_credit_split.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_person_credit_split.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_person_unit_credit_split(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_person_unit_credit_split row. Returns exactly one of
    "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_person_unit_credit_split_id": _sql_value(
            row["award_person_unit_credit_split_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_PERSON_UNIT_CREDIT_SPLIT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_person_unit_credit_split (
                award_person_unit_credit_split_id, award_person_unit_id,
                award_id, award_number, sequence_number,
                inv_credit_type_code, credit, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :award_person_unit_credit_split_id, :award_person_unit_id,
                :award_id, :award_number, :sequence_number,
                :inv_credit_type_code, :credit, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_person_unit_credit_split_id) DO UPDATE SET
                award_person_unit_id = EXCLUDED.award_person_unit_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                inv_credit_type_code = EXCLUDED.inv_credit_type_code,
                credit = EXCLUDED.credit,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_person_unit_credit_split.award_person_unit_id
                    IS DISTINCT FROM EXCLUDED.award_person_unit_id
                OR archive.award_person_unit_credit_split.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_person_unit_credit_split.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_person_unit_credit_split.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_person_unit_credit_split.inv_credit_type_code
                    IS DISTINCT FROM EXCLUDED.inv_credit_type_code
                OR archive.award_person_unit_credit_split.credit
                    IS DISTINCT FROM EXCLUDED.credit
                OR archive.award_person_unit_credit_split.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_person_unit_credit_split.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_person_unit_credit_split.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_sponsor_term(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_sponsor_term
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_sponsor_term_id": _sql_value(row["award_sponsor_term_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_SPONSOR_TERM_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_sponsor_term (
                award_sponsor_term_id, award_id, award_number,
                sequence_number, sponsor_term_id,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_sponsor_term_id, :award_id, :award_number,
                :sequence_number, :sponsor_term_id,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_sponsor_term_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                sponsor_term_id = EXCLUDED.sponsor_term_id,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_sponsor_term.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_sponsor_term.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_sponsor_term.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_sponsor_term.sponsor_term_id
                    IS DISTINCT FROM EXCLUDED.sponsor_term_id
                OR archive.award_sponsor_term.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_sponsor_term.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_sponsor_term.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_report_term(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_report_term
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_report_term_id": _sql_value(row["award_report_term_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_REPORT_TERM_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_report_term (
                award_report_term_id, award_id, award_number,
                sequence_number, report_class_code, report_code,
                frequency_code, frequency_base_code, osp_distribution_code,
                due_date, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_report_term_id, :award_id, :award_number,
                :sequence_number, :report_class_code, :report_code,
                :frequency_code, :frequency_base_code,
                :osp_distribution_code, :due_date,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_report_term_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                report_class_code = EXCLUDED.report_class_code,
                report_code = EXCLUDED.report_code,
                frequency_code = EXCLUDED.frequency_code,
                frequency_base_code = EXCLUDED.frequency_base_code,
                osp_distribution_code = EXCLUDED.osp_distribution_code,
                due_date = EXCLUDED.due_date,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_report_term.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_report_term.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_report_term.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_report_term.report_class_code
                    IS DISTINCT FROM EXCLUDED.report_class_code
                OR archive.award_report_term.report_code
                    IS DISTINCT FROM EXCLUDED.report_code
                OR archive.award_report_term.frequency_code
                    IS DISTINCT FROM EXCLUDED.frequency_code
                OR archive.award_report_term.frequency_base_code
                    IS DISTINCT FROM EXCLUDED.frequency_base_code
                OR archive.award_report_term.osp_distribution_code
                    IS DISTINCT FROM EXCLUDED.osp_distribution_code
                OR archive.award_report_term.due_date
                    IS DISTINCT FROM EXCLUDED.due_date
                OR archive.award_report_term.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_report_term.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_report_term.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_report_term_recipient(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_report_term_recipient row. Returns exactly one of
    "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_report_term_recipient_id": _sql_value(
            row["award_report_term_recipient_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_REPORT_TERM_RECIPIENT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_report_term_recipient (
                award_report_term_recipient_id, award_report_term_id,
                award_id, award_number, sequence_number, contact_id,
                contact_type_code, rolodex_id, number_of_copies,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_report_term_recipient_id, :award_report_term_id,
                :award_id, :award_number, :sequence_number, :contact_id,
                :contact_type_code, :rolodex_id, :number_of_copies,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_report_term_recipient_id) DO UPDATE SET
                award_report_term_id = EXCLUDED.award_report_term_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                contact_id = EXCLUDED.contact_id,
                contact_type_code = EXCLUDED.contact_type_code,
                rolodex_id = EXCLUDED.rolodex_id,
                number_of_copies = EXCLUDED.number_of_copies,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_report_term_recipient.award_report_term_id
                    IS DISTINCT FROM EXCLUDED.award_report_term_id
                OR archive.award_report_term_recipient.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_report_term_recipient.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_report_term_recipient.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_report_term_recipient.contact_id
                    IS DISTINCT FROM EXCLUDED.contact_id
                OR archive.award_report_term_recipient.contact_type_code
                    IS DISTINCT FROM EXCLUDED.contact_type_code
                OR archive.award_report_term_recipient.rolodex_id
                    IS DISTINCT FROM EXCLUDED.rolodex_id
                OR archive.award_report_term_recipient.number_of_copies
                    IS DISTINCT FROM EXCLUDED.number_of_copies
                OR archive.award_report_term_recipient.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_report_term_recipient.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_report_term_recipient.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_sponsor_contact(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_sponsor_contact
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_sponsor_contact_id": _sql_value(row["award_sponsor_contact_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_SPONSOR_CONTACT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_sponsor_contact (
                award_sponsor_contact_id, award_id, award_number,
                sequence_number, rolodex_id, full_name, contact_role_code,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_sponsor_contact_id, :award_id, :award_number,
                :sequence_number, :rolodex_id, :full_name,
                :contact_role_code, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_sponsor_contact_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                rolodex_id = EXCLUDED.rolodex_id,
                full_name = EXCLUDED.full_name,
                contact_role_code = EXCLUDED.contact_role_code,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_sponsor_contact.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_sponsor_contact.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_sponsor_contact.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_sponsor_contact.rolodex_id
                    IS DISTINCT FROM EXCLUDED.rolodex_id
                OR archive.award_sponsor_contact.full_name
                    IS DISTINCT FROM EXCLUDED.full_name
                OR archive.award_sponsor_contact.contact_role_code
                    IS DISTINCT FROM EXCLUDED.contact_role_code
                OR archive.award_sponsor_contact.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_sponsor_contact.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_sponsor_contact.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_unit_contact(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_unit_contact
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_unit_contact_id": _sql_value(row["award_unit_contact_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_UNIT_CONTACT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_unit_contact (
                award_unit_contact_id, award_id, award_number,
                sequence_number, person_id, full_name, unit_contact_type,
                unit_administrator_type_code,
                unit_administrator_unit_number, default_unit_contact,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_unit_contact_id, :award_id, :award_number,
                :sequence_number, :person_id, :full_name,
                :unit_contact_type, :unit_administrator_type_code,
                :unit_administrator_unit_number, :default_unit_contact,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_unit_contact_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                person_id = EXCLUDED.person_id,
                full_name = EXCLUDED.full_name,
                unit_contact_type = EXCLUDED.unit_contact_type,
                unit_administrator_type_code = EXCLUDED.unit_administrator_type_code,
                unit_administrator_unit_number = EXCLUDED.unit_administrator_unit_number,
                default_unit_contact = EXCLUDED.default_unit_contact,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_unit_contact.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_unit_contact.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_unit_contact.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_unit_contact.person_id
                    IS DISTINCT FROM EXCLUDED.person_id
                OR archive.award_unit_contact.full_name
                    IS DISTINCT FROM EXCLUDED.full_name
                OR archive.award_unit_contact.unit_contact_type
                    IS DISTINCT FROM EXCLUDED.unit_contact_type
                OR archive.award_unit_contact.unit_administrator_type_code
                    IS DISTINCT FROM EXCLUDED.unit_administrator_type_code
                OR archive.award_unit_contact.unit_administrator_unit_number
                    IS DISTINCT FROM EXCLUDED.unit_administrator_unit_number
                OR archive.award_unit_contact.default_unit_contact
                    IS DISTINCT FROM EXCLUDED.default_unit_contact
                OR archive.award_unit_contact.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_unit_contact.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_unit_contact.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_notepad(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_notepad row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_notepad_id": _sql_value(row["award_notepad_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_NOTEPAD_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_notepad (
                award_notepad_id, award_id, award_number, entry_number,
                note_topic, comments, restricted_view,
                source_create_timestamp, source_create_user,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_notepad_id, :award_id, :award_number,
                :entry_number, :note_topic, :comments, :restricted_view,
                :source_create_timestamp, :source_create_user,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_notepad_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                entry_number = EXCLUDED.entry_number,
                note_topic = EXCLUDED.note_topic,
                comments = EXCLUDED.comments,
                restricted_view = EXCLUDED.restricted_view,
                source_create_timestamp = EXCLUDED.source_create_timestamp,
                source_create_user = EXCLUDED.source_create_user,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_notepad.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_notepad.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_notepad.entry_number
                    IS DISTINCT FROM EXCLUDED.entry_number
                OR archive.award_notepad.note_topic
                    IS DISTINCT FROM EXCLUDED.note_topic
                OR archive.award_notepad.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_notepad.restricted_view
                    IS DISTINCT FROM EXCLUDED.restricted_view
                OR archive.award_notepad.source_create_timestamp
                    IS DISTINCT FROM EXCLUDED.source_create_timestamp
                OR archive.award_notepad.source_create_user
                    IS DISTINCT FROM EXCLUDED.source_create_user
                OR archive.award_notepad.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_notepad.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_notepad.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_closeout(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_closeout row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_closeout_id": _sql_value(row["award_closeout_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_CLOSEOUT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_closeout (
                award_closeout_id, award_id, award_number, sequence_number,
                closeout_report_code, closeout_report_name, due_date,
                final_submission_date, multiple_flag,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_closeout_id, :award_id, :award_number,
                :sequence_number, :closeout_report_code,
                :closeout_report_name, :due_date, :final_submission_date,
                :multiple_flag, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_closeout_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                closeout_report_code = EXCLUDED.closeout_report_code,
                closeout_report_name = EXCLUDED.closeout_report_name,
                due_date = EXCLUDED.due_date,
                final_submission_date = EXCLUDED.final_submission_date,
                multiple_flag = EXCLUDED.multiple_flag,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_closeout.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_closeout.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_closeout.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_closeout.closeout_report_code
                    IS DISTINCT FROM EXCLUDED.closeout_report_code
                OR archive.award_closeout.closeout_report_name
                    IS DISTINCT FROM EXCLUDED.closeout_report_name
                OR archive.award_closeout.due_date
                    IS DISTINCT FROM EXCLUDED.due_date
                OR archive.award_closeout.final_submission_date
                    IS DISTINCT FROM EXCLUDED.final_submission_date
                OR archive.award_closeout.multiple_flag
                    IS DISTINCT FROM EXCLUDED.multiple_flag
                OR archive.award_closeout.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_closeout.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_closeout.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_payment_schedule(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_payment_schedule
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_payment_schedule_id": _sql_value(
            row["award_payment_schedule_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_PAYMENT_SCHEDULE_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_payment_schedule (
                award_payment_schedule_id, award_id, award_number,
                sequence_number, award_report_term_id,
                award_report_term_description, due_date, amount,
                submit_date, submitted_by, submitted_by_person_id,
                invoice_number, status_description, status,
                report_status_code, overdue, source_update_timestamp,
                source_update_user, source_last_update_timestamp,
                source_last_update_user, source_version_number, load_id
            ) VALUES (
                :award_payment_schedule_id, :award_id, :award_number,
                :sequence_number, :award_report_term_id,
                :award_report_term_description, :due_date, :amount,
                :submit_date, :submitted_by, :submitted_by_person_id,
                :invoice_number, :status_description, :status,
                :report_status_code, :overdue, :source_update_timestamp,
                :source_update_user, :source_last_update_timestamp,
                :source_last_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_payment_schedule_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                award_report_term_id = EXCLUDED.award_report_term_id,
                award_report_term_description =
                    EXCLUDED.award_report_term_description,
                due_date = EXCLUDED.due_date,
                amount = EXCLUDED.amount,
                submit_date = EXCLUDED.submit_date,
                submitted_by = EXCLUDED.submitted_by,
                submitted_by_person_id = EXCLUDED.submitted_by_person_id,
                invoice_number = EXCLUDED.invoice_number,
                status_description = EXCLUDED.status_description,
                status = EXCLUDED.status,
                report_status_code = EXCLUDED.report_status_code,
                overdue = EXCLUDED.overdue,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_last_update_timestamp =
                    EXCLUDED.source_last_update_timestamp,
                source_last_update_user = EXCLUDED.source_last_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_payment_schedule.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_payment_schedule.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_payment_schedule.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_payment_schedule.award_report_term_id
                    IS DISTINCT FROM EXCLUDED.award_report_term_id
                OR archive.award_payment_schedule.award_report_term_description
                    IS DISTINCT FROM EXCLUDED.award_report_term_description
                OR archive.award_payment_schedule.due_date
                    IS DISTINCT FROM EXCLUDED.due_date
                OR archive.award_payment_schedule.amount
                    IS DISTINCT FROM EXCLUDED.amount
                OR archive.award_payment_schedule.submit_date
                    IS DISTINCT FROM EXCLUDED.submit_date
                OR archive.award_payment_schedule.submitted_by
                    IS DISTINCT FROM EXCLUDED.submitted_by
                OR archive.award_payment_schedule.submitted_by_person_id
                    IS DISTINCT FROM EXCLUDED.submitted_by_person_id
                OR archive.award_payment_schedule.invoice_number
                    IS DISTINCT FROM EXCLUDED.invoice_number
                OR archive.award_payment_schedule.status_description
                    IS DISTINCT FROM EXCLUDED.status_description
                OR archive.award_payment_schedule.status
                    IS DISTINCT FROM EXCLUDED.status
                OR archive.award_payment_schedule.report_status_code
                    IS DISTINCT FROM EXCLUDED.report_status_code
                OR archive.award_payment_schedule.overdue
                    IS DISTINCT FROM EXCLUDED.overdue
                OR archive.award_payment_schedule.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_payment_schedule.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_payment_schedule.source_last_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_last_update_timestamp
                OR archive.award_payment_schedule.source_last_update_user
                    IS DISTINCT FROM EXCLUDED.source_last_update_user
                OR archive.award_payment_schedule.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_approved_subaward(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_approved_subaward
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_approved_subaward_id": _sql_value(
            row["award_approved_subaward_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_APPROVED_SUBAWARD_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_approved_subaward (
                award_approved_subaward_id, award_id, award_number,
                sequence_number, organization_name, organization_id,
                amount, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_approved_subaward_id, :award_id, :award_number,
                :sequence_number, :organization_name, :organization_id,
                :amount, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_approved_subaward_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                organization_name = EXCLUDED.organization_name,
                organization_id = EXCLUDED.organization_id,
                amount = EXCLUDED.amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_approved_subaward.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_approved_subaward.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_approved_subaward.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_approved_subaward.organization_name
                    IS DISTINCT FROM EXCLUDED.organization_name
                OR archive.award_approved_subaward.organization_id
                    IS DISTINCT FROM EXCLUDED.organization_id
                OR archive.award_approved_subaward.amount
                    IS DISTINCT FROM EXCLUDED.amount
                OR archive.award_approved_subaward.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_approved_subaward.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_approved_subaward.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_cfda(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_cfda row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_cfda_id": _sql_value(row["award_cfda_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_CFDA_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_cfda (
                award_cfda_id, award_id, award_number, sequence_number,
                cfda_number, cfda_description, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :award_cfda_id, :award_id, :award_number, :sequence_number,
                :cfda_number, :cfda_description, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_cfda_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                cfda_number = EXCLUDED.cfda_number,
                cfda_description = EXCLUDED.cfda_description,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_cfda.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_cfda.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_cfda.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_cfda.cfda_number
                    IS DISTINCT FROM EXCLUDED.cfda_number
                OR archive.award_cfda.cfda_description
                    IS DISTINCT FROM EXCLUDED.cfda_description
                OR archive.award_cfda.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_cfda.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_cfda.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_cost_share(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_cost_share row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_cost_share_id": _sql_value(row["award_cost_share_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_COST_SHARE_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_cost_share (
                award_cost_share_id, award_id, award_number, sequence_number,
                project_period, cost_share_percentage, cost_share_type_code,
                unit_number, source, destination, commitment_amount,
                cost_share_met, verification_date,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_cost_share_id, :award_id, :award_number,
                :sequence_number, :project_period, :cost_share_percentage,
                :cost_share_type_code, :unit_number, :source, :destination,
                :commitment_amount, :cost_share_met, :verification_date,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_cost_share_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                project_period = EXCLUDED.project_period,
                cost_share_percentage = EXCLUDED.cost_share_percentage,
                cost_share_type_code = EXCLUDED.cost_share_type_code,
                unit_number = EXCLUDED.unit_number,
                source = EXCLUDED.source,
                destination = EXCLUDED.destination,
                commitment_amount = EXCLUDED.commitment_amount,
                cost_share_met = EXCLUDED.cost_share_met,
                verification_date = EXCLUDED.verification_date,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_cost_share.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_cost_share.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_cost_share.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_cost_share.project_period
                    IS DISTINCT FROM EXCLUDED.project_period
                OR archive.award_cost_share.cost_share_percentage
                    IS DISTINCT FROM EXCLUDED.cost_share_percentage
                OR archive.award_cost_share.cost_share_type_code
                    IS DISTINCT FROM EXCLUDED.cost_share_type_code
                OR archive.award_cost_share.unit_number
                    IS DISTINCT FROM EXCLUDED.unit_number
                OR archive.award_cost_share.source
                    IS DISTINCT FROM EXCLUDED.source
                OR archive.award_cost_share.destination
                    IS DISTINCT FROM EXCLUDED.destination
                OR archive.award_cost_share.commitment_amount
                    IS DISTINCT FROM EXCLUDED.commitment_amount
                OR archive.award_cost_share.cost_share_met
                    IS DISTINCT FROM EXCLUDED.cost_share_met
                OR archive.award_cost_share.verification_date
                    IS DISTINCT FROM EXCLUDED.verification_date
                OR archive.award_cost_share.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_cost_share.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_cost_share.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_fanda_rate(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_fanda_rate row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_fanda_rate_id": _sql_value(row["award_fanda_rate_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_FANDA_RATE_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_fanda_rate (
                award_fanda_rate_id, award_id, award_number, sequence_number,
                applicable_fanda_rate, fanda_rate_type_code, fiscal_year,
                on_campus_flag, underrecovery_of_indirect_cost,
                source_account, destination_account, start_date, end_date,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_fanda_rate_id, :award_id, :award_number,
                :sequence_number, :applicable_fanda_rate,
                :fanda_rate_type_code, :fiscal_year, :on_campus_flag,
                :underrecovery_of_indirect_cost, :source_account,
                :destination_account, :start_date, :end_date,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_fanda_rate_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                applicable_fanda_rate = EXCLUDED.applicable_fanda_rate,
                fanda_rate_type_code = EXCLUDED.fanda_rate_type_code,
                fiscal_year = EXCLUDED.fiscal_year,
                on_campus_flag = EXCLUDED.on_campus_flag,
                underrecovery_of_indirect_cost =
                    EXCLUDED.underrecovery_of_indirect_cost,
                source_account = EXCLUDED.source_account,
                destination_account = EXCLUDED.destination_account,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_fanda_rate.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_fanda_rate.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_fanda_rate.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_fanda_rate.applicable_fanda_rate
                    IS DISTINCT FROM EXCLUDED.applicable_fanda_rate
                OR archive.award_fanda_rate.fanda_rate_type_code
                    IS DISTINCT FROM EXCLUDED.fanda_rate_type_code
                OR archive.award_fanda_rate.fiscal_year
                    IS DISTINCT FROM EXCLUDED.fiscal_year
                OR archive.award_fanda_rate.on_campus_flag
                    IS DISTINCT FROM EXCLUDED.on_campus_flag
                OR archive.award_fanda_rate.underrecovery_of_indirect_cost
                    IS DISTINCT FROM EXCLUDED.underrecovery_of_indirect_cost
                OR archive.award_fanda_rate.source_account
                    IS DISTINCT FROM EXCLUDED.source_account
                OR archive.award_fanda_rate.destination_account
                    IS DISTINCT FROM EXCLUDED.destination_account
                OR archive.award_fanda_rate.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_fanda_rate.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_fanda_rate.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_fanda_rate.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_fanda_rate.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_science_keyword(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_science_keyword
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_science_keyword_id": _sql_value(row["award_science_keyword_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_SCIENCE_KEYWORD_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_science_keyword (
                award_science_keyword_id, award_id, award_number,
                sequence_number, science_keyword_code,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_science_keyword_id, :award_id, :award_number,
                :sequence_number, :science_keyword_code,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_science_keyword_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                science_keyword_code = EXCLUDED.science_keyword_code,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_science_keyword.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_science_keyword.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_science_keyword.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_science_keyword.science_keyword_code
                    IS DISTINCT FROM EXCLUDED.science_keyword_code
                OR archive.award_science_keyword.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_science_keyword.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_science_keyword.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_special_review(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_special_review
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_special_review_id": _sql_value(row["award_special_review_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_SPECIAL_REVIEW_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_special_review (
                award_special_review_id, award_id, award_number,
                sequence_number, special_review_number,
                special_review_type_code, approval_type_code,
                protocol_number, application_date, approval_date,
                expiration_date, comments, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :award_special_review_id, :award_id, :award_number,
                :sequence_number, :special_review_number,
                :special_review_type_code, :approval_type_code,
                :protocol_number, :application_date, :approval_date,
                :expiration_date, :comments, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_special_review_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                special_review_number = EXCLUDED.special_review_number,
                special_review_type_code = EXCLUDED.special_review_type_code,
                approval_type_code = EXCLUDED.approval_type_code,
                protocol_number = EXCLUDED.protocol_number,
                application_date = EXCLUDED.application_date,
                approval_date = EXCLUDED.approval_date,
                expiration_date = EXCLUDED.expiration_date,
                comments = EXCLUDED.comments,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_special_review.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_special_review.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_special_review.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_special_review.special_review_number
                    IS DISTINCT FROM EXCLUDED.special_review_number
                OR archive.award_special_review.special_review_type_code
                    IS DISTINCT FROM EXCLUDED.special_review_type_code
                OR archive.award_special_review.approval_type_code
                    IS DISTINCT FROM EXCLUDED.approval_type_code
                OR archive.award_special_review.protocol_number
                    IS DISTINCT FROM EXCLUDED.protocol_number
                OR archive.award_special_review.application_date
                    IS DISTINCT FROM EXCLUDED.application_date
                OR archive.award_special_review.approval_date
                    IS DISTINCT FROM EXCLUDED.approval_date
                OR archive.award_special_review.expiration_date
                    IS DISTINCT FROM EXCLUDED.expiration_date
                OR archive.award_special_review.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_special_review.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_special_review.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_special_review.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_special_review_exemption(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_special_review_exemption row. Returns exactly one of
    "inserted", "updated", "unchanged". Callers MUST have already
    upserted this row's parent archive.award_special_review row (see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md) -
    the FK would otherwise fail."""
    params: dict[str, Any] = {
        "award_special_review_exemption_id": _sql_value(
            row["award_special_review_exemption_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_SPECIAL_REVIEW_EXEMPTION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_special_review_exemption (
                award_special_review_exemption_id, award_special_review_id,
                award_id, award_number, sequence_number, exemption_type_code,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_special_review_exemption_id, :award_special_review_id,
                :award_id, :award_number, :sequence_number,
                :exemption_type_code, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_special_review_exemption_id) DO UPDATE SET
                award_special_review_id = EXCLUDED.award_special_review_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                exemption_type_code = EXCLUDED.exemption_type_code,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_special_review_exemption.award_special_review_id
                    IS DISTINCT FROM EXCLUDED.award_special_review_id
                OR archive.award_special_review_exemption.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_special_review_exemption.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_special_review_exemption.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_special_review_exemption.exemption_type_code
                    IS DISTINCT FROM EXCLUDED.exemption_type_code
                OR archive.award_special_review_exemption.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_special_review_exemption.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_special_review_exemption.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_approved_equipment(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_approved_equipment
    row. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_approved_equipment_id": _sql_value(
            row["award_approved_equipment_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_APPROVED_EQUIPMENT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_approved_equipment (
                award_approved_equipment_id, award_id, award_number,
                sequence_number, item, model, vendor, amount,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_approved_equipment_id, :award_id, :award_number,
                :sequence_number, :item, :model, :vendor, :amount,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_approved_equipment_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                item = EXCLUDED.item,
                model = EXCLUDED.model,
                vendor = EXCLUDED.vendor,
                amount = EXCLUDED.amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_approved_equipment.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_approved_equipment.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_approved_equipment.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_approved_equipment.item
                    IS DISTINCT FROM EXCLUDED.item
                OR archive.award_approved_equipment.model
                    IS DISTINCT FROM EXCLUDED.model
                OR archive.award_approved_equipment.vendor
                    IS DISTINCT FROM EXCLUDED.vendor
                OR archive.award_approved_equipment.amount
                    IS DISTINCT FROM EXCLUDED.amount
                OR archive.award_approved_equipment.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_approved_equipment.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_approved_equipment.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_approved_foreign_travel(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_approved_foreign_travel row. Returns exactly one of
    "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_approved_foreign_travel_id": _sql_value(
            row["award_approved_foreign_travel_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_APPROVED_FOREIGN_TRAVEL_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_approved_foreign_travel (
                award_approved_foreign_travel_id, award_id, award_number,
                sequence_number, person_id, rolodex_id, traveler_name,
                destination, start_date, end_date, amount,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_approved_foreign_travel_id, :award_id, :award_number,
                :sequence_number, :person_id, :rolodex_id, :traveler_name,
                :destination, :start_date, :end_date, :amount,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_approved_foreign_travel_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                person_id = EXCLUDED.person_id,
                rolodex_id = EXCLUDED.rolodex_id,
                traveler_name = EXCLUDED.traveler_name,
                destination = EXCLUDED.destination,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                amount = EXCLUDED.amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_approved_foreign_travel.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_approved_foreign_travel.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_approved_foreign_travel.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_approved_foreign_travel.person_id
                    IS DISTINCT FROM EXCLUDED.person_id
                OR archive.award_approved_foreign_travel.rolodex_id
                    IS DISTINCT FROM EXCLUDED.rolodex_id
                OR archive.award_approved_foreign_travel.traveler_name
                    IS DISTINCT FROM EXCLUDED.traveler_name
                OR archive.award_approved_foreign_travel.destination
                    IS DISTINCT FROM EXCLUDED.destination
                OR archive.award_approved_foreign_travel.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_approved_foreign_travel.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_approved_foreign_travel.amount
                    IS DISTINCT FROM EXCLUDED.amount
                OR archive.award_approved_foreign_travel.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_approved_foreign_travel.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_approved_foreign_travel.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_subcontracting_budgeted_goals(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_subcontracting_budgeted_goals row, keyed by
    award_number - the one table in the Award domain with no surrogate
    PK at all (Oracle's own SUBCONTRACTING_BUD table is itself keyed by
    AWARD_NUMBER - see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md).
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_number": _sql_value(row["award_number"]),
        "load_id": load_id,
    }
    for column in _AWARD_SUBCONTRACTING_BUDGETED_GOALS_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_subcontracting_budgeted_goals (
                award_number, large_business_goal_amount,
                small_business_goal_amount, woman_owned_goal_amount,
                eight_a_disadvantage_goal_amount, hub_zone_goal_amount,
                veteran_owned_goal_amount,
                service_disabled_veteran_owned_goal_amount,
                historical_black_college_goal_amount, comments,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_number, :large_business_goal_amount,
                :small_business_goal_amount, :woman_owned_goal_amount,
                :eight_a_disadvantage_goal_amount, :hub_zone_goal_amount,
                :veteran_owned_goal_amount,
                :service_disabled_veteran_owned_goal_amount,
                :historical_black_college_goal_amount, :comments,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_number) DO UPDATE SET
                large_business_goal_amount =
                    EXCLUDED.large_business_goal_amount,
                small_business_goal_amount =
                    EXCLUDED.small_business_goal_amount,
                woman_owned_goal_amount = EXCLUDED.woman_owned_goal_amount,
                eight_a_disadvantage_goal_amount =
                    EXCLUDED.eight_a_disadvantage_goal_amount,
                hub_zone_goal_amount = EXCLUDED.hub_zone_goal_amount,
                veteran_owned_goal_amount =
                    EXCLUDED.veteran_owned_goal_amount,
                service_disabled_veteran_owned_goal_amount =
                    EXCLUDED.service_disabled_veteran_owned_goal_amount,
                historical_black_college_goal_amount =
                    EXCLUDED.historical_black_college_goal_amount,
                comments = EXCLUDED.comments,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_subcontracting_budgeted_goals.large_business_goal_amount
                    IS DISTINCT FROM EXCLUDED.large_business_goal_amount
                OR archive.award_subcontracting_budgeted_goals.small_business_goal_amount
                    IS DISTINCT FROM EXCLUDED.small_business_goal_amount
                OR archive.award_subcontracting_budgeted_goals.woman_owned_goal_amount
                    IS DISTINCT FROM EXCLUDED.woman_owned_goal_amount
                OR archive.award_subcontracting_budgeted_goals.eight_a_disadvantage_goal_amount
                    IS DISTINCT FROM EXCLUDED.eight_a_disadvantage_goal_amount
                OR archive.award_subcontracting_budgeted_goals.hub_zone_goal_amount
                    IS DISTINCT FROM EXCLUDED.hub_zone_goal_amount
                OR archive.award_subcontracting_budgeted_goals.veteran_owned_goal_amount
                    IS DISTINCT FROM EXCLUDED.veteran_owned_goal_amount
                OR archive.award_subcontracting_budgeted_goals
                    .service_disabled_veteran_owned_goal_amount
                    IS DISTINCT FROM EXCLUDED.service_disabled_veteran_owned_goal_amount
                OR archive.award_subcontracting_budgeted_goals.historical_black_college_goal_amount
                    IS DISTINCT FROM EXCLUDED.historical_black_college_goal_amount
                OR archive.award_subcontracting_budgeted_goals.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_subcontracting_budgeted_goals.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_subcontracting_budgeted_goals.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_subcontracting_budgeted_goals.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_comments(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_comment row.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_comment_id": _sql_value(row["award_comment_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_COMMENT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_comment (
                award_comment_id, award_id, award_number, sequence_number,
                comment_type_code, checklist_print_flag, comments,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_comment_id, :award_id, :award_number, :sequence_number,
                :comment_type_code, :checklist_print_flag, :comments,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_comment_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                comment_type_code = EXCLUDED.comment_type_code,
                checklist_print_flag = EXCLUDED.checklist_print_flag,
                comments = EXCLUDED.comments,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_comment.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_comment.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_comment.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_comment.comment_type_code
                    IS DISTINCT FROM EXCLUDED.comment_type_code
                OR archive.award_comment.checklist_print_flag
                    IS DISTINCT FROM EXCLUDED.checklist_print_flag
                OR archive.award_comment.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_comment.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_comment.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_comment.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_extension(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_extension row,
    keyed by award_id itself - a true 1:1 extension table, no
    surrogate id (see docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md).
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_id": _sql_value(row["award_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_EXTENSION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_extension (
                award_id, award_number, sequence_number,
                proposed_for_transmission_indicator, last_transmission_date,
                child_type, child_description, major_project, arra_code,
                avc_indicator, a133_cluster, fringe_not_allowed_indicator,
                interest_earned, interest_earned_account_number,
                stepped_up_rate, bu_bmc_fa_split, conference_grant,
                program_income, stock_award, foreign_currency_award,
                nce_notification_date, clinical_trial_initiated_by,
                ind_ide_responsibility, clinical_trial_registration_date,
                spuds_record_number, walker_source_number,
                prime_sponsor_award_id, grant_number, federal_clinical_trial,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_id, :award_number, :sequence_number,
                :proposed_for_transmission_indicator, :last_transmission_date,
                :child_type, :child_description, :major_project, :arra_code,
                :avc_indicator, :a133_cluster, :fringe_not_allowed_indicator,
                :interest_earned, :interest_earned_account_number,
                :stepped_up_rate, :bu_bmc_fa_split, :conference_grant,
                :program_income, :stock_award, :foreign_currency_award,
                :nce_notification_date, :clinical_trial_initiated_by,
                :ind_ide_responsibility, :clinical_trial_registration_date,
                :spuds_record_number, :walker_source_number,
                :prime_sponsor_award_id, :grant_number, :federal_clinical_trial,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_id) DO UPDATE SET
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                proposed_for_transmission_indicator =
                    EXCLUDED.proposed_for_transmission_indicator,
                last_transmission_date = EXCLUDED.last_transmission_date,
                child_type = EXCLUDED.child_type,
                child_description = EXCLUDED.child_description,
                major_project = EXCLUDED.major_project,
                arra_code = EXCLUDED.arra_code,
                avc_indicator = EXCLUDED.avc_indicator,
                a133_cluster = EXCLUDED.a133_cluster,
                fringe_not_allowed_indicator =
                    EXCLUDED.fringe_not_allowed_indicator,
                interest_earned = EXCLUDED.interest_earned,
                interest_earned_account_number =
                    EXCLUDED.interest_earned_account_number,
                stepped_up_rate = EXCLUDED.stepped_up_rate,
                bu_bmc_fa_split = EXCLUDED.bu_bmc_fa_split,
                conference_grant = EXCLUDED.conference_grant,
                program_income = EXCLUDED.program_income,
                stock_award = EXCLUDED.stock_award,
                foreign_currency_award = EXCLUDED.foreign_currency_award,
                nce_notification_date = EXCLUDED.nce_notification_date,
                clinical_trial_initiated_by =
                    EXCLUDED.clinical_trial_initiated_by,
                ind_ide_responsibility = EXCLUDED.ind_ide_responsibility,
                clinical_trial_registration_date =
                    EXCLUDED.clinical_trial_registration_date,
                spuds_record_number = EXCLUDED.spuds_record_number,
                walker_source_number = EXCLUDED.walker_source_number,
                prime_sponsor_award_id = EXCLUDED.prime_sponsor_award_id,
                grant_number = EXCLUDED.grant_number,
                federal_clinical_trial = EXCLUDED.federal_clinical_trial,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_extension.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_extension.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_extension.proposed_for_transmission_indicator
                    IS DISTINCT FROM EXCLUDED.proposed_for_transmission_indicator
                OR archive.award_extension.last_transmission_date
                    IS DISTINCT FROM EXCLUDED.last_transmission_date
                OR archive.award_extension.child_type
                    IS DISTINCT FROM EXCLUDED.child_type
                OR archive.award_extension.child_description
                    IS DISTINCT FROM EXCLUDED.child_description
                OR archive.award_extension.major_project
                    IS DISTINCT FROM EXCLUDED.major_project
                OR archive.award_extension.arra_code
                    IS DISTINCT FROM EXCLUDED.arra_code
                OR archive.award_extension.avc_indicator
                    IS DISTINCT FROM EXCLUDED.avc_indicator
                OR archive.award_extension.a133_cluster
                    IS DISTINCT FROM EXCLUDED.a133_cluster
                OR archive.award_extension.fringe_not_allowed_indicator
                    IS DISTINCT FROM EXCLUDED.fringe_not_allowed_indicator
                OR archive.award_extension.interest_earned
                    IS DISTINCT FROM EXCLUDED.interest_earned
                OR archive.award_extension.interest_earned_account_number
                    IS DISTINCT FROM EXCLUDED.interest_earned_account_number
                OR archive.award_extension.stepped_up_rate
                    IS DISTINCT FROM EXCLUDED.stepped_up_rate
                OR archive.award_extension.bu_bmc_fa_split
                    IS DISTINCT FROM EXCLUDED.bu_bmc_fa_split
                OR archive.award_extension.conference_grant
                    IS DISTINCT FROM EXCLUDED.conference_grant
                OR archive.award_extension.program_income
                    IS DISTINCT FROM EXCLUDED.program_income
                OR archive.award_extension.stock_award
                    IS DISTINCT FROM EXCLUDED.stock_award
                OR archive.award_extension.foreign_currency_award
                    IS DISTINCT FROM EXCLUDED.foreign_currency_award
                OR archive.award_extension.nce_notification_date
                    IS DISTINCT FROM EXCLUDED.nce_notification_date
                OR archive.award_extension.clinical_trial_initiated_by
                    IS DISTINCT FROM EXCLUDED.clinical_trial_initiated_by
                OR archive.award_extension.ind_ide_responsibility
                    IS DISTINCT FROM EXCLUDED.ind_ide_responsibility
                OR archive.award_extension.clinical_trial_registration_date
                    IS DISTINCT FROM EXCLUDED.clinical_trial_registration_date
                OR archive.award_extension.spuds_record_number
                    IS DISTINCT FROM EXCLUDED.spuds_record_number
                OR archive.award_extension.walker_source_number
                    IS DISTINCT FROM EXCLUDED.walker_source_number
                OR archive.award_extension.prime_sponsor_award_id
                    IS DISTINCT FROM EXCLUDED.prime_sponsor_award_id
                OR archive.award_extension.grant_number
                    IS DISTINCT FROM EXCLUDED.grant_number
                OR archive.award_extension.federal_clinical_trial
                    IS DISTINCT FROM EXCLUDED.federal_clinical_trial
                OR archive.award_extension.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_extension.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_extension.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_cgb(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_cgb row, keyed
    by award_id itself - a true 1:1 extension table, no surrogate id
    (see docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md). Returns
    exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_id": _sql_value(row["award_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_CGB_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_cgb (
                award_id, award_number, sequence_number,
                additional_forms_required, auto_approve_invoice, stop_work,
                min_invoice_amount, invoicing_option, dunning_campaign_id,
                last_billed_date, previous_last_billed_date, final_bill,
                amount_to_draw, letter_of_credit_review_indicator,
                invoice_document_status, loc_creation_type,
                suspend_invoicing, bill_freq_cd, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :award_id, :award_number, :sequence_number,
                :additional_forms_required, :auto_approve_invoice, :stop_work,
                :min_invoice_amount, :invoicing_option, :dunning_campaign_id,
                :last_billed_date, :previous_last_billed_date, :final_bill,
                :amount_to_draw, :letter_of_credit_review_indicator,
                :invoice_document_status, :loc_creation_type,
                :suspend_invoicing, :bill_freq_cd, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_id) DO UPDATE SET
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                additional_forms_required = EXCLUDED.additional_forms_required,
                auto_approve_invoice = EXCLUDED.auto_approve_invoice,
                stop_work = EXCLUDED.stop_work,
                min_invoice_amount = EXCLUDED.min_invoice_amount,
                invoicing_option = EXCLUDED.invoicing_option,
                dunning_campaign_id = EXCLUDED.dunning_campaign_id,
                last_billed_date = EXCLUDED.last_billed_date,
                previous_last_billed_date = EXCLUDED.previous_last_billed_date,
                final_bill = EXCLUDED.final_bill,
                amount_to_draw = EXCLUDED.amount_to_draw,
                letter_of_credit_review_indicator =
                    EXCLUDED.letter_of_credit_review_indicator,
                invoice_document_status = EXCLUDED.invoice_document_status,
                loc_creation_type = EXCLUDED.loc_creation_type,
                suspend_invoicing = EXCLUDED.suspend_invoicing,
                bill_freq_cd = EXCLUDED.bill_freq_cd,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_cgb.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_cgb.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_cgb.additional_forms_required
                    IS DISTINCT FROM EXCLUDED.additional_forms_required
                OR archive.award_cgb.auto_approve_invoice
                    IS DISTINCT FROM EXCLUDED.auto_approve_invoice
                OR archive.award_cgb.stop_work
                    IS DISTINCT FROM EXCLUDED.stop_work
                OR archive.award_cgb.min_invoice_amount
                    IS DISTINCT FROM EXCLUDED.min_invoice_amount
                OR archive.award_cgb.invoicing_option
                    IS DISTINCT FROM EXCLUDED.invoicing_option
                OR archive.award_cgb.dunning_campaign_id
                    IS DISTINCT FROM EXCLUDED.dunning_campaign_id
                OR archive.award_cgb.last_billed_date
                    IS DISTINCT FROM EXCLUDED.last_billed_date
                OR archive.award_cgb.previous_last_billed_date
                    IS DISTINCT FROM EXCLUDED.previous_last_billed_date
                OR archive.award_cgb.final_bill
                    IS DISTINCT FROM EXCLUDED.final_bill
                OR archive.award_cgb.amount_to_draw
                    IS DISTINCT FROM EXCLUDED.amount_to_draw
                OR archive.award_cgb.letter_of_credit_review_indicator
                    IS DISTINCT FROM EXCLUDED.letter_of_credit_review_indicator
                OR archive.award_cgb.invoice_document_status
                    IS DISTINCT FROM EXCLUDED.invoice_document_status
                OR archive.award_cgb.loc_creation_type
                    IS DISTINCT FROM EXCLUDED.loc_creation_type
                OR archive.award_cgb.suspend_invoicing
                    IS DISTINCT FROM EXCLUDED.suspend_invoicing
                OR archive.award_cgb.bill_freq_cd
                    IS DISTINCT FROM EXCLUDED.bill_freq_cd
                OR archive.award_cgb.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_cgb.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_cgb.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_hierarchy(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_hierarchy row,
    keyed by award_hierarchy_id - Oracle's own real surrogate PK, not
    award_number (this table has no version tie and is version-agnostic
    per its own Java class's documented contract - see
    docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md). Returns exactly
    one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_hierarchy_id": _sql_value(row["award_hierarchy_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_HIERARCHY_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_hierarchy (
                award_hierarchy_id, root_award_number, award_number,
                parent_award_number, originating_award_number, active,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_hierarchy_id, :root_award_number, :award_number,
                :parent_award_number, :originating_award_number, :active,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_hierarchy_id) DO UPDATE SET
                root_award_number = EXCLUDED.root_award_number,
                award_number = EXCLUDED.award_number,
                parent_award_number = EXCLUDED.parent_award_number,
                originating_award_number = EXCLUDED.originating_award_number,
                active = EXCLUDED.active,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_hierarchy.root_award_number
                    IS DISTINCT FROM EXCLUDED.root_award_number
                OR archive.award_hierarchy.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_hierarchy.parent_award_number
                    IS DISTINCT FROM EXCLUDED.parent_award_number
                OR archive.award_hierarchy.originating_award_number
                    IS DISTINCT FROM EXCLUDED.originating_award_number
                OR archive.award_hierarchy.active
                    IS DISTINCT FROM EXCLUDED.active
                OR archive.award_hierarchy.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_hierarchy.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_hierarchy.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_time_and_money_document(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.time_and_money_document row, keyed by document_number - a
    real KEW-assigned document number, not a surrogate sequence (the
    same shape as AWARD_DOCUMENT). Returns exactly one of "inserted",
    "updated", "unchanged"."""
    params: dict[str, Any] = {
        "document_number": _sql_value(row["document_number"]),
        "load_id": load_id,
    }
    for column in _TIME_AND_MONEY_DOCUMENT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.time_and_money_document (
                document_number, root_award_number, document_status,
                creation_date, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :document_number, :root_award_number, :document_status,
                :creation_date, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (document_number) DO UPDATE SET
                root_award_number = EXCLUDED.root_award_number,
                document_status = EXCLUDED.document_status,
                creation_date = EXCLUDED.creation_date,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.time_and_money_document.root_award_number
                    IS DISTINCT FROM EXCLUDED.root_award_number
                OR archive.time_and_money_document.document_status
                    IS DISTINCT FROM EXCLUDED.document_status
                OR archive.time_and_money_document.creation_date
                    IS DISTINCT FROM EXCLUDED.creation_date
                OR archive.time_and_money_document.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.time_and_money_document.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.time_and_money_document.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_pending_transaction(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.pending_transaction
    row, keyed by transaction_id - Oracle's own real surrogate PK.
    Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "transaction_id": _sql_value(row["transaction_id"]),
        "load_id": load_id,
    }
    for column in _PENDING_TRANSACTION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.pending_transaction (
                transaction_id, document_number, source_award_number,
                destination_award_number, obligated_amount,
                obligated_direct_amount, obligated_indirect_amount,
                anticipated_amount, anticipated_direct_amount,
                anticipated_indirect_amount, comments, processed_flag,
                single_node_transaction, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :transaction_id, :document_number, :source_award_number,
                :destination_award_number, :obligated_amount,
                :obligated_direct_amount, :obligated_indirect_amount,
                :anticipated_amount, :anticipated_direct_amount,
                :anticipated_indirect_amount, :comments, :processed_flag,
                :single_node_transaction, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (transaction_id) DO UPDATE SET
                document_number = EXCLUDED.document_number,
                source_award_number = EXCLUDED.source_award_number,
                destination_award_number = EXCLUDED.destination_award_number,
                obligated_amount = EXCLUDED.obligated_amount,
                obligated_direct_amount = EXCLUDED.obligated_direct_amount,
                obligated_indirect_amount = EXCLUDED.obligated_indirect_amount,
                anticipated_amount = EXCLUDED.anticipated_amount,
                anticipated_direct_amount = EXCLUDED.anticipated_direct_amount,
                anticipated_indirect_amount =
                    EXCLUDED.anticipated_indirect_amount,
                comments = EXCLUDED.comments,
                processed_flag = EXCLUDED.processed_flag,
                single_node_transaction = EXCLUDED.single_node_transaction,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.pending_transaction.document_number
                    IS DISTINCT FROM EXCLUDED.document_number
                OR archive.pending_transaction.source_award_number
                    IS DISTINCT FROM EXCLUDED.source_award_number
                OR archive.pending_transaction.destination_award_number
                    IS DISTINCT FROM EXCLUDED.destination_award_number
                OR archive.pending_transaction.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.pending_transaction.obligated_direct_amount
                    IS DISTINCT FROM EXCLUDED.obligated_direct_amount
                OR archive.pending_transaction.obligated_indirect_amount
                    IS DISTINCT FROM EXCLUDED.obligated_indirect_amount
                OR archive.pending_transaction.anticipated_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_amount
                OR archive.pending_transaction.anticipated_direct_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_direct_amount
                OR archive.pending_transaction.anticipated_indirect_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_indirect_amount
                OR archive.pending_transaction.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.pending_transaction.processed_flag
                    IS DISTINCT FROM EXCLUDED.processed_flag
                OR archive.pending_transaction.single_node_transaction
                    IS DISTINCT FROM EXCLUDED.single_node_transaction
                OR archive.pending_transaction.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.pending_transaction.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.pending_transaction.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_pending_transaction_extension(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.pending_transaction_extension row, keyed by transaction_id
    itself - a true 1:1 BU-specific extension table, no surrogate id
    (see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md). Returns
    exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "transaction_id": _sql_value(row["transaction_id"]),
        "load_id": load_id,
    }
    for column in _PENDING_TRANSACTION_EXTENSION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.pending_transaction_extension (
                transaction_id, budget_period, load_id
            ) VALUES (
                :transaction_id, :budget_period, :load_id
            )
            ON CONFLICT (transaction_id) DO UPDATE SET
                budget_period = EXCLUDED.budget_period,
                load_id = EXCLUDED.load_id
            WHERE
                archive.pending_transaction_extension.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_transaction_detail(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.transaction_detail row,
    keyed by transaction_detail_id - Oracle's own real surrogate PK.
    This is the durable, permanent Time and Money history ledger, as
    opposed to archive.pending_transaction's working state. Returns
    exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "transaction_detail_id": _sql_value(row["transaction_detail_id"]),
        "load_id": load_id,
    }
    for column in _TRANSACTION_DETAIL_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.transaction_detail (
                transaction_detail_id, award_number, sequence_number,
                transaction_id, time_and_money_document_number,
                source_award_number, destination_award_number,
                obligated_amount, obligated_direct_amount,
                obligated_indirect_amount, anticipated_amount,
                anticipated_direct_amount, anticipated_indirect_amount,
                comments, transaction_detail_type,
                source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :transaction_detail_id, :award_number, :sequence_number,
                :transaction_id, :time_and_money_document_number,
                :source_award_number, :destination_award_number,
                :obligated_amount, :obligated_direct_amount,
                :obligated_indirect_amount, :anticipated_amount,
                :anticipated_direct_amount, :anticipated_indirect_amount,
                :comments, :transaction_detail_type,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (transaction_detail_id) DO UPDATE SET
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                transaction_id = EXCLUDED.transaction_id,
                time_and_money_document_number =
                    EXCLUDED.time_and_money_document_number,
                source_award_number = EXCLUDED.source_award_number,
                destination_award_number = EXCLUDED.destination_award_number,
                obligated_amount = EXCLUDED.obligated_amount,
                obligated_direct_amount = EXCLUDED.obligated_direct_amount,
                obligated_indirect_amount = EXCLUDED.obligated_indirect_amount,
                anticipated_amount = EXCLUDED.anticipated_amount,
                anticipated_direct_amount = EXCLUDED.anticipated_direct_amount,
                anticipated_indirect_amount =
                    EXCLUDED.anticipated_indirect_amount,
                comments = EXCLUDED.comments,
                transaction_detail_type = EXCLUDED.transaction_detail_type,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.transaction_detail.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.transaction_detail.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.transaction_detail.transaction_id
                    IS DISTINCT FROM EXCLUDED.transaction_id
                OR archive.transaction_detail.time_and_money_document_number
                    IS DISTINCT FROM EXCLUDED.time_and_money_document_number
                OR archive.transaction_detail.source_award_number
                    IS DISTINCT FROM EXCLUDED.source_award_number
                OR archive.transaction_detail.destination_award_number
                    IS DISTINCT FROM EXCLUDED.destination_award_number
                OR archive.transaction_detail.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.transaction_detail.obligated_direct_amount
                    IS DISTINCT FROM EXCLUDED.obligated_direct_amount
                OR archive.transaction_detail.obligated_indirect_amount
                    IS DISTINCT FROM EXCLUDED.obligated_indirect_amount
                OR archive.transaction_detail.anticipated_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_amount
                OR archive.transaction_detail.anticipated_direct_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_direct_amount
                OR archive.transaction_detail.anticipated_indirect_amount
                    IS DISTINCT FROM EXCLUDED.anticipated_indirect_amount
                OR archive.transaction_detail.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.transaction_detail.transaction_detail_type
                    IS DISTINCT FROM EXCLUDED.transaction_detail_type
                OR archive.transaction_detail.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.transaction_detail.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.transaction_detail.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_amount_transaction(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_amount_transaction row, keyed by
    award_amount_transaction_id - Oracle's own real surrogate PK.
    document_number is Oracle's own confusingly-named VARCHAR2
    "TRANSACTION_ID" column, renamed at the archive boundary - it is
    NOT the same concept as the numeric transaction_id used by
    archive.pending_transaction/archive.transaction_detail/
    archive.award_amount_info (see
    docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md). Returns exactly
    one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_amount_transaction_id": _sql_value(
            row["award_amount_transaction_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_AMOUNT_TRANSACTION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_amount_transaction (
                award_amount_transaction_id, award_number, document_number,
                transaction_type_code, transaction_type_description,
                notice_date, comments, source_update_timestamp,
                source_update_user, source_version_number, load_id
            ) VALUES (
                :award_amount_transaction_id, :award_number, :document_number,
                :transaction_type_code, :transaction_type_description,
                :notice_date, :comments, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (award_amount_transaction_id) DO UPDATE SET
                award_number = EXCLUDED.award_number,
                document_number = EXCLUDED.document_number,
                transaction_type_code = EXCLUDED.transaction_type_code,
                transaction_type_description =
                    EXCLUDED.transaction_type_description,
                notice_date = EXCLUDED.notice_date,
                comments = EXCLUDED.comments,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_amount_transaction.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_amount_transaction.document_number
                    IS DISTINCT FROM EXCLUDED.document_number
                OR archive.award_amount_transaction.transaction_type_code
                    IS DISTINCT FROM EXCLUDED.transaction_type_code
                OR archive.award_amount_transaction.transaction_type_description
                    IS DISTINCT FROM EXCLUDED.transaction_type_description
                OR archive.award_amount_transaction.notice_date
                    IS DISTINCT FROM EXCLUDED.notice_date
                OR archive.award_amount_transaction.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_amount_transaction.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_amount_transaction.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_amount_transaction.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_direct_fanda_distribution(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_direct_fanda_distribution row, keyed by
    award_direct_fanda_distribution_id - Oracle's own real surrogate
    PK. Returns exactly one of "inserted", "updated", "unchanged"."""
    params: dict[str, Any] = {
        "award_direct_fanda_distribution_id": _sql_value(
            row["award_direct_fanda_distribution_id"]
        ),
        "load_id": load_id,
    }
    for column in _AWARD_DIRECT_FANDA_DISTRIBUTION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_direct_fanda_distribution (
                award_direct_fanda_distribution_id, award_id, award_number,
                sequence_number, amount_sequence_number, award_amount_info_id,
                budget_period, start_date, end_date, direct_cost,
                indirect_cost, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_direct_fanda_distribution_id, :award_id, :award_number,
                :sequence_number, :amount_sequence_number,
                :award_amount_info_id, :budget_period, :start_date,
                :end_date, :direct_cost, :indirect_cost,
                :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_direct_fanda_distribution_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                amount_sequence_number = EXCLUDED.amount_sequence_number,
                award_amount_info_id = EXCLUDED.award_amount_info_id,
                budget_period = EXCLUDED.budget_period,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                direct_cost = EXCLUDED.direct_cost,
                indirect_cost = EXCLUDED.indirect_cost,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_direct_fanda_distribution.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_direct_fanda_distribution.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_direct_fanda_distribution.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_direct_fanda_distribution.amount_sequence_number
                    IS DISTINCT FROM EXCLUDED.amount_sequence_number
                OR archive.award_direct_fanda_distribution.award_amount_info_id
                    IS DISTINCT FROM EXCLUDED.award_amount_info_id
                OR archive.award_direct_fanda_distribution.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_direct_fanda_distribution.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_direct_fanda_distribution.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_direct_fanda_distribution.direct_cost
                    IS DISTINCT FROM EXCLUDED.direct_cost
                OR archive.award_direct_fanda_distribution.indirect_cost
                    IS DISTINCT FROM EXCLUDED.indirect_cost
                OR archive.award_direct_fanda_distribution.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_direct_fanda_distribution.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_direct_fanda_distribution.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget row, keyed by
    budget_id - Oracle's own real surrogate PK, shared verbatim with the
    generic BUDGET table it merges (see
    docs/architecture/AWARD_BUDGET_DESIGN.md). Returns exactly one of
    "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "budget_id": _sql_value(row["budget_id"]),
        "load_id": load_id,
    }
    for column in _BUDGET_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget (
                budget_id, award_id, document_number, award_budget_status_code,
                award_budget_status_description, award_budget_type_code,
                award_budget_type_description, budget_version_number, name, description,
                budget_initiator, start_date, end_date, total_cost, total_direct_cost,
                total_indirect_cost, total_cost_limit, cost_sharing_amount,
                underrecovery_amount, residual_funds, obligated_amount, obligated_total,
                oh_rate_class_code, oh_rate_type_code, ur_rate_class_code,
                modular_budget_flag, on_off_campus_flag, submit_cost_sharing_flag,
                parent_document_type_code, budget_adjustment_document_number, comments,
                budget_justification, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :budget_id, :award_id, :document_number, :award_budget_status_code,
                :award_budget_status_description, :award_budget_type_code,
                :award_budget_type_description, :budget_version_number, :name,
                :description, :budget_initiator, :start_date, :end_date, :total_cost,
                :total_direct_cost, :total_indirect_cost, :total_cost_limit,
                :cost_sharing_amount, :underrecovery_amount, :residual_funds,
                :obligated_amount, :obligated_total, :oh_rate_class_code,
                :oh_rate_type_code, :ur_rate_class_code, :modular_budget_flag,
                :on_off_campus_flag, :submit_cost_sharing_flag, :parent_document_type_code,
                :budget_adjustment_document_number, :comments, :budget_justification,
                :source_update_timestamp, :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (budget_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                document_number = EXCLUDED.document_number,
                award_budget_status_code = EXCLUDED.award_budget_status_code,
                award_budget_status_description = EXCLUDED.award_budget_status_description,
                award_budget_type_code = EXCLUDED.award_budget_type_code,
                award_budget_type_description = EXCLUDED.award_budget_type_description,
                budget_version_number = EXCLUDED.budget_version_number,
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                budget_initiator = EXCLUDED.budget_initiator,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                total_cost = EXCLUDED.total_cost,
                total_direct_cost = EXCLUDED.total_direct_cost,
                total_indirect_cost = EXCLUDED.total_indirect_cost,
                total_cost_limit = EXCLUDED.total_cost_limit,
                cost_sharing_amount = EXCLUDED.cost_sharing_amount,
                underrecovery_amount = EXCLUDED.underrecovery_amount,
                residual_funds = EXCLUDED.residual_funds,
                obligated_amount = EXCLUDED.obligated_amount,
                obligated_total = EXCLUDED.obligated_total,
                oh_rate_class_code = EXCLUDED.oh_rate_class_code,
                oh_rate_type_code = EXCLUDED.oh_rate_type_code,
                ur_rate_class_code = EXCLUDED.ur_rate_class_code,
                modular_budget_flag = EXCLUDED.modular_budget_flag,
                on_off_campus_flag = EXCLUDED.on_off_campus_flag,
                submit_cost_sharing_flag = EXCLUDED.submit_cost_sharing_flag,
                parent_document_type_code = EXCLUDED.parent_document_type_code,
                budget_adjustment_document_number = EXCLUDED.budget_adjustment_document_number,
                comments = EXCLUDED.comments,
                budget_justification = EXCLUDED.budget_justification,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_budget.document_number
                    IS DISTINCT FROM EXCLUDED.document_number
                OR archive.award_budget.award_budget_status_code
                    IS DISTINCT FROM EXCLUDED.award_budget_status_code
                OR archive.award_budget.award_budget_status_description
                    IS DISTINCT FROM EXCLUDED.award_budget_status_description
                OR archive.award_budget.award_budget_type_code
                    IS DISTINCT FROM EXCLUDED.award_budget_type_code
                OR archive.award_budget.award_budget_type_description
                    IS DISTINCT FROM EXCLUDED.award_budget_type_description
                OR archive.award_budget.budget_version_number
                    IS DISTINCT FROM EXCLUDED.budget_version_number
                OR archive.award_budget.name
                    IS DISTINCT FROM EXCLUDED.name
                OR archive.award_budget.description
                    IS DISTINCT FROM EXCLUDED.description
                OR archive.award_budget.budget_initiator
                    IS DISTINCT FROM EXCLUDED.budget_initiator
                OR archive.award_budget.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_budget.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_budget.total_cost
                    IS DISTINCT FROM EXCLUDED.total_cost
                OR archive.award_budget.total_direct_cost
                    IS DISTINCT FROM EXCLUDED.total_direct_cost
                OR archive.award_budget.total_indirect_cost
                    IS DISTINCT FROM EXCLUDED.total_indirect_cost
                OR archive.award_budget.total_cost_limit
                    IS DISTINCT FROM EXCLUDED.total_cost_limit
                OR archive.award_budget.cost_sharing_amount
                    IS DISTINCT FROM EXCLUDED.cost_sharing_amount
                OR archive.award_budget.underrecovery_amount
                    IS DISTINCT FROM EXCLUDED.underrecovery_amount
                OR archive.award_budget.residual_funds
                    IS DISTINCT FROM EXCLUDED.residual_funds
                OR archive.award_budget.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget.obligated_total
                    IS DISTINCT FROM EXCLUDED.obligated_total
                OR archive.award_budget.oh_rate_class_code
                    IS DISTINCT FROM EXCLUDED.oh_rate_class_code
                OR archive.award_budget.oh_rate_type_code
                    IS DISTINCT FROM EXCLUDED.oh_rate_type_code
                OR archive.award_budget.ur_rate_class_code
                    IS DISTINCT FROM EXCLUDED.ur_rate_class_code
                OR archive.award_budget.modular_budget_flag
                    IS DISTINCT FROM EXCLUDED.modular_budget_flag
                OR archive.award_budget.on_off_campus_flag
                    IS DISTINCT FROM EXCLUDED.on_off_campus_flag
                OR archive.award_budget.submit_cost_sharing_flag
                    IS DISTINCT FROM EXCLUDED.submit_cost_sharing_flag
                OR archive.award_budget.parent_document_type_code
                    IS DISTINCT FROM EXCLUDED.parent_document_type_code
                OR archive.award_budget.budget_adjustment_document_number
                    IS DISTINCT FROM EXCLUDED.budget_adjustment_document_number
                OR archive.award_budget.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_budget.budget_justification
                    IS DISTINCT FROM EXCLUDED.budget_justification
                OR archive.award_budget.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_period(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget_period row,
    keyed by budget_period_id - Oracle's own real surrogate PK (column
    BUDGET_PERIOD_NUMBER). Returns exactly one of "inserted", "updated",
    "unchanged".
    """
    params: dict[str, Any] = {
        "budget_period_id": _sql_value(row["budget_period_id"]),
        "load_id": load_id,
    }
    for column in _BUDGET_PERIOD_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_period (
                budget_period_id, budget_id, budget_period, start_date, end_date,
                total_cost, total_direct_cost, total_indirect_cost, total_cost_limit,
                cost_sharing_amount, underrecovery_amount, number_of_participants,
                obligated_amount, total_fringe_amount, fringe_overridden,
                f_and_a_overridden, comments, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :budget_period_id, :budget_id, :budget_period, :start_date, :end_date,
                :total_cost, :total_direct_cost, :total_indirect_cost, :total_cost_limit,
                :cost_sharing_amount, :underrecovery_amount, :number_of_participants,
                :obligated_amount, :total_fringe_amount, :fringe_overridden,
                :f_and_a_overridden, :comments, :source_update_timestamp,
                :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (budget_period_id) DO UPDATE SET
                budget_id = EXCLUDED.budget_id,
                budget_period = EXCLUDED.budget_period,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                total_cost = EXCLUDED.total_cost,
                total_direct_cost = EXCLUDED.total_direct_cost,
                total_indirect_cost = EXCLUDED.total_indirect_cost,
                total_cost_limit = EXCLUDED.total_cost_limit,
                cost_sharing_amount = EXCLUDED.cost_sharing_amount,
                underrecovery_amount = EXCLUDED.underrecovery_amount,
                number_of_participants = EXCLUDED.number_of_participants,
                obligated_amount = EXCLUDED.obligated_amount,
                total_fringe_amount = EXCLUDED.total_fringe_amount,
                fringe_overridden = EXCLUDED.fringe_overridden,
                f_and_a_overridden = EXCLUDED.f_and_a_overridden,
                comments = EXCLUDED.comments,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_period.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_period.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_budget_period.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_budget_period.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_budget_period.total_cost
                    IS DISTINCT FROM EXCLUDED.total_cost
                OR archive.award_budget_period.total_direct_cost
                    IS DISTINCT FROM EXCLUDED.total_direct_cost
                OR archive.award_budget_period.total_indirect_cost
                    IS DISTINCT FROM EXCLUDED.total_indirect_cost
                OR archive.award_budget_period.total_cost_limit
                    IS DISTINCT FROM EXCLUDED.total_cost_limit
                OR archive.award_budget_period.cost_sharing_amount
                    IS DISTINCT FROM EXCLUDED.cost_sharing_amount
                OR archive.award_budget_period.underrecovery_amount
                    IS DISTINCT FROM EXCLUDED.underrecovery_amount
                OR archive.award_budget_period.number_of_participants
                    IS DISTINCT FROM EXCLUDED.number_of_participants
                OR archive.award_budget_period.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget_period.total_fringe_amount
                    IS DISTINCT FROM EXCLUDED.total_fringe_amount
                OR archive.award_budget_period.fringe_overridden
                    IS DISTINCT FROM EXCLUDED.fringe_overridden
                OR archive.award_budget_period.f_and_a_overridden
                    IS DISTINCT FROM EXCLUDED.f_and_a_overridden
                OR archive.award_budget_period.comments
                    IS DISTINCT FROM EXCLUDED.comments
                OR archive.award_budget_period.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_period.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_period.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_line_item(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget_line_item row,
    keyed by budget_line_item_id - Oracle's own real surrogate PK (column
    BUDGET_DETAILS_ID). Returns exactly one of "inserted", "updated",
    "unchanged".
    """
    params: dict[str, Any] = {
        "budget_line_item_id": _sql_value(row["budget_line_item_id"]),
        "load_id": load_id,
    }
    for column in _BUDGET_LINE_ITEM_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_line_item (
                budget_line_item_id, budget_period_id, budget_id, budget_period,
                line_item_number, budget_category_code, cost_element,
                line_item_description, group_name, based_on_line_item, line_item_sequence,
                start_date, end_date, line_item_cost, cost_sharing_amount,
                underrecovery_amount, obligated_amount, quantity, on_off_campus_flag,
                apply_in_rate_flag, submit_cost_sharing_flag, formulated_cost_element_flag,
                subaward_number, hierarchy_proposal_number, hidden_in_hierarchy,
                budget_justification, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :budget_line_item_id, :budget_period_id, :budget_id, :budget_period,
                :line_item_number, :budget_category_code, :cost_element,
                :line_item_description, :group_name, :based_on_line_item,
                :line_item_sequence, :start_date, :end_date, :line_item_cost,
                :cost_sharing_amount, :underrecovery_amount, :obligated_amount, :quantity,
                :on_off_campus_flag, :apply_in_rate_flag, :submit_cost_sharing_flag,
                :formulated_cost_element_flag, :subaward_number,
                :hierarchy_proposal_number, :hidden_in_hierarchy, :budget_justification,
                :source_update_timestamp, :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (budget_line_item_id) DO UPDATE SET
                budget_period_id = EXCLUDED.budget_period_id,
                budget_id = EXCLUDED.budget_id,
                budget_period = EXCLUDED.budget_period,
                line_item_number = EXCLUDED.line_item_number,
                budget_category_code = EXCLUDED.budget_category_code,
                cost_element = EXCLUDED.cost_element,
                line_item_description = EXCLUDED.line_item_description,
                group_name = EXCLUDED.group_name,
                based_on_line_item = EXCLUDED.based_on_line_item,
                line_item_sequence = EXCLUDED.line_item_sequence,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                line_item_cost = EXCLUDED.line_item_cost,
                cost_sharing_amount = EXCLUDED.cost_sharing_amount,
                underrecovery_amount = EXCLUDED.underrecovery_amount,
                obligated_amount = EXCLUDED.obligated_amount,
                quantity = EXCLUDED.quantity,
                on_off_campus_flag = EXCLUDED.on_off_campus_flag,
                apply_in_rate_flag = EXCLUDED.apply_in_rate_flag,
                submit_cost_sharing_flag = EXCLUDED.submit_cost_sharing_flag,
                formulated_cost_element_flag = EXCLUDED.formulated_cost_element_flag,
                subaward_number = EXCLUDED.subaward_number,
                hierarchy_proposal_number = EXCLUDED.hierarchy_proposal_number,
                hidden_in_hierarchy = EXCLUDED.hidden_in_hierarchy,
                budget_justification = EXCLUDED.budget_justification,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_line_item.budget_period_id
                    IS DISTINCT FROM EXCLUDED.budget_period_id
                OR archive.award_budget_line_item.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_line_item.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_budget_line_item.line_item_number
                    IS DISTINCT FROM EXCLUDED.line_item_number
                OR archive.award_budget_line_item.budget_category_code
                    IS DISTINCT FROM EXCLUDED.budget_category_code
                OR archive.award_budget_line_item.cost_element
                    IS DISTINCT FROM EXCLUDED.cost_element
                OR archive.award_budget_line_item.line_item_description
                    IS DISTINCT FROM EXCLUDED.line_item_description
                OR archive.award_budget_line_item.group_name
                    IS DISTINCT FROM EXCLUDED.group_name
                OR archive.award_budget_line_item.based_on_line_item
                    IS DISTINCT FROM EXCLUDED.based_on_line_item
                OR archive.award_budget_line_item.line_item_sequence
                    IS DISTINCT FROM EXCLUDED.line_item_sequence
                OR archive.award_budget_line_item.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_budget_line_item.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_budget_line_item.line_item_cost
                    IS DISTINCT FROM EXCLUDED.line_item_cost
                OR archive.award_budget_line_item.cost_sharing_amount
                    IS DISTINCT FROM EXCLUDED.cost_sharing_amount
                OR archive.award_budget_line_item.underrecovery_amount
                    IS DISTINCT FROM EXCLUDED.underrecovery_amount
                OR archive.award_budget_line_item.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget_line_item.quantity
                    IS DISTINCT FROM EXCLUDED.quantity
                OR archive.award_budget_line_item.on_off_campus_flag
                    IS DISTINCT FROM EXCLUDED.on_off_campus_flag
                OR archive.award_budget_line_item.apply_in_rate_flag
                    IS DISTINCT FROM EXCLUDED.apply_in_rate_flag
                OR archive.award_budget_line_item.submit_cost_sharing_flag
                    IS DISTINCT FROM EXCLUDED.submit_cost_sharing_flag
                OR archive.award_budget_line_item.formulated_cost_element_flag
                    IS DISTINCT FROM EXCLUDED.formulated_cost_element_flag
                OR archive.award_budget_line_item.subaward_number
                    IS DISTINCT FROM EXCLUDED.subaward_number
                OR archive.award_budget_line_item.hierarchy_proposal_number
                    IS DISTINCT FROM EXCLUDED.hierarchy_proposal_number
                OR archive.award_budget_line_item.hidden_in_hierarchy
                    IS DISTINCT FROM EXCLUDED.hidden_in_hierarchy
                OR archive.award_budget_line_item.budget_justification
                    IS DISTINCT FROM EXCLUDED.budget_justification
                OR archive.award_budget_line_item.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_line_item.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_line_item.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_line_item_calculated_amount(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_budget_line_item_calculated_amount row, keyed by
    budget_line_item_calculated_amount_id - Oracle's own real surrogate
    PK. Returns exactly one of "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "budget_line_item_calculated_amount_id": _sql_value(
            row["budget_line_item_calculated_amount_id"]
        ),
        "load_id": load_id,
    }
    for column in _BUDGET_LINE_ITEM_CALCULATED_AMOUNT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_line_item_calculated_amount (
                budget_line_item_calculated_amount_id, budget_line_item_id,
                budget_period_id, budget_id, budget_period, line_item_number,
                rate_class_code, rate_type_code, rate_type_description, apply_rate_flag,
                calculated_cost, calculated_cost_sharing, obligated_amount,
                source_update_timestamp, source_update_user, source_version_number, load_id
            ) VALUES (
                :budget_line_item_calculated_amount_id, :budget_line_item_id,
                :budget_period_id, :budget_id, :budget_period, :line_item_number,
                :rate_class_code, :rate_type_code, :rate_type_description,
                :apply_rate_flag, :calculated_cost, :calculated_cost_sharing,
                :obligated_amount, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (budget_line_item_calculated_amount_id) DO UPDATE SET
                budget_line_item_id = EXCLUDED.budget_line_item_id,
                budget_period_id = EXCLUDED.budget_period_id,
                budget_id = EXCLUDED.budget_id,
                budget_period = EXCLUDED.budget_period,
                line_item_number = EXCLUDED.line_item_number,
                rate_class_code = EXCLUDED.rate_class_code,
                rate_type_code = EXCLUDED.rate_type_code,
                rate_type_description = EXCLUDED.rate_type_description,
                apply_rate_flag = EXCLUDED.apply_rate_flag,
                calculated_cost = EXCLUDED.calculated_cost,
                calculated_cost_sharing = EXCLUDED.calculated_cost_sharing,
                obligated_amount = EXCLUDED.obligated_amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_line_item_calculated_amount.budget_line_item_id
                    IS DISTINCT FROM EXCLUDED.budget_line_item_id
                OR archive.award_budget_line_item_calculated_amount.budget_period_id
                    IS DISTINCT FROM EXCLUDED.budget_period_id
                OR archive.award_budget_line_item_calculated_amount.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_line_item_calculated_amount.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_budget_line_item_calculated_amount.line_item_number
                    IS DISTINCT FROM EXCLUDED.line_item_number
                OR archive.award_budget_line_item_calculated_amount.rate_class_code
                    IS DISTINCT FROM EXCLUDED.rate_class_code
                OR archive.award_budget_line_item_calculated_amount.rate_type_code
                    IS DISTINCT FROM EXCLUDED.rate_type_code
                OR archive.award_budget_line_item_calculated_amount.rate_type_description
                    IS DISTINCT FROM EXCLUDED.rate_type_description
                OR archive.award_budget_line_item_calculated_amount.apply_rate_flag
                    IS DISTINCT FROM EXCLUDED.apply_rate_flag
                OR archive.award_budget_line_item_calculated_amount.calculated_cost
                    IS DISTINCT FROM EXCLUDED.calculated_cost
                OR archive.award_budget_line_item_calculated_amount.calculated_cost_sharing
                    IS DISTINCT FROM EXCLUDED.calculated_cost_sharing
                OR archive.award_budget_line_item_calculated_amount.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget_line_item_calculated_amount.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_line_item_calculated_amount.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_line_item_calculated_amount.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_personnel_detail(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget_personnel_detail
    row, keyed by budget_personnel_line_item_id - Oracle's own real
    surrogate PK. Returns exactly one of "inserted", "updated",
    "unchanged".
    """
    params: dict[str, Any] = {
        "budget_personnel_line_item_id": _sql_value(row["budget_personnel_line_item_id"]),
        "load_id": load_id,
    }
    for column in _BUDGET_PERSONNEL_DETAIL_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_personnel_detail (
                budget_personnel_line_item_id, budget_line_item_id, budget_period_id,
                budget_id, budget_period, line_item_number, person_number,
                person_sequence_number, person_id, job_code, period_type_code,
                line_item_description, sequence_number, start_date, end_date,
                salary_requested, percent_charged, percent_effort, cost_sharing_percent,
                cost_sharing_amount, underrecovery_amount, obligated_amount,
                on_off_campus_flag, apply_in_rate_flag, budget_justification,
                source_update_timestamp, source_update_user, source_version_number, load_id
            ) VALUES (
                :budget_personnel_line_item_id, :budget_line_item_id, :budget_period_id,
                :budget_id, :budget_period, :line_item_number, :person_number,
                :person_sequence_number, :person_id, :job_code, :period_type_code,
                :line_item_description, :sequence_number, :start_date, :end_date,
                :salary_requested, :percent_charged, :percent_effort,
                :cost_sharing_percent, :cost_sharing_amount, :underrecovery_amount,
                :obligated_amount, :on_off_campus_flag, :apply_in_rate_flag,
                :budget_justification, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (budget_personnel_line_item_id) DO UPDATE SET
                budget_line_item_id = EXCLUDED.budget_line_item_id,
                budget_period_id = EXCLUDED.budget_period_id,
                budget_id = EXCLUDED.budget_id,
                budget_period = EXCLUDED.budget_period,
                line_item_number = EXCLUDED.line_item_number,
                person_number = EXCLUDED.person_number,
                person_sequence_number = EXCLUDED.person_sequence_number,
                person_id = EXCLUDED.person_id,
                job_code = EXCLUDED.job_code,
                period_type_code = EXCLUDED.period_type_code,
                line_item_description = EXCLUDED.line_item_description,
                sequence_number = EXCLUDED.sequence_number,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                salary_requested = EXCLUDED.salary_requested,
                percent_charged = EXCLUDED.percent_charged,
                percent_effort = EXCLUDED.percent_effort,
                cost_sharing_percent = EXCLUDED.cost_sharing_percent,
                cost_sharing_amount = EXCLUDED.cost_sharing_amount,
                underrecovery_amount = EXCLUDED.underrecovery_amount,
                obligated_amount = EXCLUDED.obligated_amount,
                on_off_campus_flag = EXCLUDED.on_off_campus_flag,
                apply_in_rate_flag = EXCLUDED.apply_in_rate_flag,
                budget_justification = EXCLUDED.budget_justification,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_personnel_detail.budget_line_item_id
                    IS DISTINCT FROM EXCLUDED.budget_line_item_id
                OR archive.award_budget_personnel_detail.budget_period_id
                    IS DISTINCT FROM EXCLUDED.budget_period_id
                OR archive.award_budget_personnel_detail.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_personnel_detail.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_budget_personnel_detail.line_item_number
                    IS DISTINCT FROM EXCLUDED.line_item_number
                OR archive.award_budget_personnel_detail.person_number
                    IS DISTINCT FROM EXCLUDED.person_number
                OR archive.award_budget_personnel_detail.person_sequence_number
                    IS DISTINCT FROM EXCLUDED.person_sequence_number
                OR archive.award_budget_personnel_detail.person_id
                    IS DISTINCT FROM EXCLUDED.person_id
                OR archive.award_budget_personnel_detail.job_code
                    IS DISTINCT FROM EXCLUDED.job_code
                OR archive.award_budget_personnel_detail.period_type_code
                    IS DISTINCT FROM EXCLUDED.period_type_code
                OR archive.award_budget_personnel_detail.line_item_description
                    IS DISTINCT FROM EXCLUDED.line_item_description
                OR archive.award_budget_personnel_detail.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_budget_personnel_detail.start_date
                    IS DISTINCT FROM EXCLUDED.start_date
                OR archive.award_budget_personnel_detail.end_date
                    IS DISTINCT FROM EXCLUDED.end_date
                OR archive.award_budget_personnel_detail.salary_requested
                    IS DISTINCT FROM EXCLUDED.salary_requested
                OR archive.award_budget_personnel_detail.percent_charged
                    IS DISTINCT FROM EXCLUDED.percent_charged
                OR archive.award_budget_personnel_detail.percent_effort
                    IS DISTINCT FROM EXCLUDED.percent_effort
                OR archive.award_budget_personnel_detail.cost_sharing_percent
                    IS DISTINCT FROM EXCLUDED.cost_sharing_percent
                OR archive.award_budget_personnel_detail.cost_sharing_amount
                    IS DISTINCT FROM EXCLUDED.cost_sharing_amount
                OR archive.award_budget_personnel_detail.underrecovery_amount
                    IS DISTINCT FROM EXCLUDED.underrecovery_amount
                OR archive.award_budget_personnel_detail.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget_personnel_detail.on_off_campus_flag
                    IS DISTINCT FROM EXCLUDED.on_off_campus_flag
                OR archive.award_budget_personnel_detail.apply_in_rate_flag
                    IS DISTINCT FROM EXCLUDED.apply_in_rate_flag
                OR archive.award_budget_personnel_detail.budget_justification
                    IS DISTINCT FROM EXCLUDED.budget_justification
                OR archive.award_budget_personnel_detail.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_personnel_detail.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_personnel_detail.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_personnel_calculated_amount(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_budget_personnel_calculated_amount row, keyed by
    budget_personnel_calculated_amount_id - Oracle's own real surrogate
    PK. Returns exactly one of "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "budget_personnel_calculated_amount_id": _sql_value(
            row["budget_personnel_calculated_amount_id"]
        ),
        "load_id": load_id,
    }
    for column in _BUDGET_PERSONNEL_CALCULATED_AMOUNT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_personnel_calculated_amount (
                budget_personnel_calculated_amount_id, budget_personnel_line_item_id,
                budget_period_id, budget_id, budget_period, line_item_number,
                person_number, rate_class_code, rate_type_code, rate_type_description,
                apply_rate_flag, calculated_cost, calculated_cost_sharing,
                obligated_amount, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :budget_personnel_calculated_amount_id, :budget_personnel_line_item_id,
                :budget_period_id, :budget_id, :budget_period, :line_item_number,
                :person_number, :rate_class_code, :rate_type_code, :rate_type_description,
                :apply_rate_flag, :calculated_cost, :calculated_cost_sharing,
                :obligated_amount, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (budget_personnel_calculated_amount_id) DO UPDATE SET
                budget_personnel_line_item_id = EXCLUDED.budget_personnel_line_item_id,
                budget_period_id = EXCLUDED.budget_period_id,
                budget_id = EXCLUDED.budget_id,
                budget_period = EXCLUDED.budget_period,
                line_item_number = EXCLUDED.line_item_number,
                person_number = EXCLUDED.person_number,
                rate_class_code = EXCLUDED.rate_class_code,
                rate_type_code = EXCLUDED.rate_type_code,
                rate_type_description = EXCLUDED.rate_type_description,
                apply_rate_flag = EXCLUDED.apply_rate_flag,
                calculated_cost = EXCLUDED.calculated_cost,
                calculated_cost_sharing = EXCLUDED.calculated_cost_sharing,
                obligated_amount = EXCLUDED.obligated_amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_personnel_calculated_amount.budget_personnel_line_item_id
                    IS DISTINCT FROM EXCLUDED.budget_personnel_line_item_id
                OR archive.award_budget_personnel_calculated_amount.budget_period_id
                    IS DISTINCT FROM EXCLUDED.budget_period_id
                OR archive.award_budget_personnel_calculated_amount.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_personnel_calculated_amount.budget_period
                    IS DISTINCT FROM EXCLUDED.budget_period
                OR archive.award_budget_personnel_calculated_amount.line_item_number
                    IS DISTINCT FROM EXCLUDED.line_item_number
                OR archive.award_budget_personnel_calculated_amount.person_number
                    IS DISTINCT FROM EXCLUDED.person_number
                OR archive.award_budget_personnel_calculated_amount.rate_class_code
                    IS DISTINCT FROM EXCLUDED.rate_class_code
                OR archive.award_budget_personnel_calculated_amount.rate_type_code
                    IS DISTINCT FROM EXCLUDED.rate_type_code
                OR archive.award_budget_personnel_calculated_amount.rate_type_description
                    IS DISTINCT FROM EXCLUDED.rate_type_description
                OR archive.award_budget_personnel_calculated_amount.apply_rate_flag
                    IS DISTINCT FROM EXCLUDED.apply_rate_flag
                OR archive.award_budget_personnel_calculated_amount.calculated_cost
                    IS DISTINCT FROM EXCLUDED.calculated_cost
                OR archive.award_budget_personnel_calculated_amount.calculated_cost_sharing
                    IS DISTINCT FROM EXCLUDED.calculated_cost_sharing
                OR archive.award_budget_personnel_calculated_amount.obligated_amount
                    IS DISTINCT FROM EXCLUDED.obligated_amount
                OR archive.award_budget_personnel_calculated_amount.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_personnel_calculated_amount.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_personnel_calculated_amount.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_period_summary_calculated_amount(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one
    archive.award_budget_period_summary_calculated_amount row, keyed by
    award_budget_period_summary_calculated_amount_id - Oracle's own real
    surrogate PK. Serves two logical roles (fringe and F&A amounts)
    distinguished only by rate_class_type - see the design doc's Findings.
    Returns exactly one of "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "award_budget_period_summary_calculated_amount_id": _sql_value(
            row["award_budget_period_summary_calculated_amount_id"]
        ),
        "load_id": load_id,
    }
    for column in _BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_period_summary_calculated_amount (
                award_budget_period_summary_calculated_amount_id, budget_period_id,
                cost_element, on_off_campus_flag, rate_class_type, calculated_cost,
                calculated_cost_sharing, source_update_timestamp, source_update_user,
                source_version_number, load_id
            ) VALUES (
                :award_budget_period_summary_calculated_amount_id, :budget_period_id,
                :cost_element, :on_off_campus_flag, :rate_class_type, :calculated_cost,
                :calculated_cost_sharing, :source_update_timestamp, :source_update_user,
                :source_version_number, :load_id
            )
            ON CONFLICT (award_budget_period_summary_calculated_amount_id) DO UPDATE SET
                budget_period_id = EXCLUDED.budget_period_id,
                cost_element = EXCLUDED.cost_element,
                on_off_campus_flag = EXCLUDED.on_off_campus_flag,
                rate_class_type = EXCLUDED.rate_class_type,
                calculated_cost = EXCLUDED.calculated_cost,
                calculated_cost_sharing = EXCLUDED.calculated_cost_sharing,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_period_summary_calculated_amount.budget_period_id
                    IS DISTINCT FROM EXCLUDED.budget_period_id
                OR archive.award_budget_period_summary_calculated_amount.cost_element
                    IS DISTINCT FROM EXCLUDED.cost_element
                OR archive.award_budget_period_summary_calculated_amount.on_off_campus_flag
                    IS DISTINCT FROM EXCLUDED.on_off_campus_flag
                OR archive.award_budget_period_summary_calculated_amount.rate_class_type
                    IS DISTINCT FROM EXCLUDED.rate_class_type
                OR archive.award_budget_period_summary_calculated_amount.calculated_cost
                    IS DISTINCT FROM EXCLUDED.calculated_cost
                OR archive.award_budget_period_summary_calculated_amount.calculated_cost_sharing
                    IS DISTINCT FROM EXCLUDED.calculated_cost_sharing
                OR archive.award_budget_period_summary_calculated_amount.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_period_summary_calculated_amount.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_period_summary_calculated_amount.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_limit(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget_limit row, keyed
    by budget_limit_id - Oracle's own real surrogate PK. Returns exactly
    one of "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "budget_limit_id": _sql_value(row["budget_limit_id"]),
        "load_id": load_id,
    }
    for column in _BUDGET_LIMIT_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_limit (
                budget_limit_id, award_id, budget_id, limit_type_code, limit_amount,
                source_update_timestamp, source_update_user, source_version_number, load_id
            ) VALUES (
                :budget_limit_id, :award_id, :budget_id, :limit_type_code, :limit_amount,
                :source_update_timestamp, :source_update_user, :source_version_number, :load_id
            )
            ON CONFLICT (budget_limit_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                budget_id = EXCLUDED.budget_id,
                limit_type_code = EXCLUDED.limit_type_code,
                limit_amount = EXCLUDED.limit_amount,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_limit.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_budget_limit.budget_id
                    IS DISTINCT FROM EXCLUDED.budget_id
                OR archive.award_budget_limit.limit_type_code
                    IS DISTINCT FROM EXCLUDED.limit_type_code
                OR archive.award_budget_limit.limit_amount
                    IS DISTINCT FROM EXCLUDED.limit_amount
                OR archive.award_budget_limit.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_limit.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_limit.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_budget_person(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_budget_person row,
    keyed by Oracle's own real composite PK (budget_id,
    person_sequence_number) - BUDGET_PERSONS has no surrogate id at
    all. Returns exactly one of "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "budget_id": _sql_value(row["budget_id"]),
        "person_sequence_number": _sql_value(row["person_sequence_number"]),
        "load_id": load_id,
    }
    for column in _BUDGET_PERSON_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_budget_person (
                budget_id, person_sequence_number, effective_date, job_code,
                non_employee_flag, person_id, appointment_type_code, rolodex_id,
                tbn_id, calculation_base, person_name, salary_anniversary_date,
                hierarchy_proposal_number, hidden_in_hierarchy,
                source_update_timestamp, source_update_user, source_version_number,
                load_id
            ) VALUES (
                :budget_id, :person_sequence_number, :effective_date, :job_code,
                :non_employee_flag, :person_id, :appointment_type_code, :rolodex_id,
                :tbn_id, :calculation_base, :person_name, :salary_anniversary_date,
                :hierarchy_proposal_number, :hidden_in_hierarchy,
                :source_update_timestamp, :source_update_user, :source_version_number,
                :load_id
            )
            ON CONFLICT (budget_id, person_sequence_number) DO UPDATE SET
                effective_date = EXCLUDED.effective_date,
                job_code = EXCLUDED.job_code,
                non_employee_flag = EXCLUDED.non_employee_flag,
                person_id = EXCLUDED.person_id,
                appointment_type_code = EXCLUDED.appointment_type_code,
                rolodex_id = EXCLUDED.rolodex_id,
                tbn_id = EXCLUDED.tbn_id,
                calculation_base = EXCLUDED.calculation_base,
                person_name = EXCLUDED.person_name,
                salary_anniversary_date = EXCLUDED.salary_anniversary_date,
                hierarchy_proposal_number = EXCLUDED.hierarchy_proposal_number,
                hidden_in_hierarchy = EXCLUDED.hidden_in_hierarchy,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_budget_person.effective_date
                    IS DISTINCT FROM EXCLUDED.effective_date
                OR archive.award_budget_person.job_code
                    IS DISTINCT FROM EXCLUDED.job_code
                OR archive.award_budget_person.non_employee_flag
                    IS DISTINCT FROM EXCLUDED.non_employee_flag
                OR archive.award_budget_person.person_id
                    IS DISTINCT FROM EXCLUDED.person_id
                OR archive.award_budget_person.appointment_type_code
                    IS DISTINCT FROM EXCLUDED.appointment_type_code
                OR archive.award_budget_person.rolodex_id
                    IS DISTINCT FROM EXCLUDED.rolodex_id
                OR archive.award_budget_person.tbn_id
                    IS DISTINCT FROM EXCLUDED.tbn_id
                OR archive.award_budget_person.calculation_base
                    IS DISTINCT FROM EXCLUDED.calculation_base
                OR archive.award_budget_person.person_name
                    IS DISTINCT FROM EXCLUDED.person_name
                OR archive.award_budget_person.salary_anniversary_date
                    IS DISTINCT FROM EXCLUDED.salary_anniversary_date
                OR archive.award_budget_person.hierarchy_proposal_number
                    IS DISTINCT FROM EXCLUDED.hierarchy_proposal_number
                OR archive.award_budget_person.hidden_in_hierarchy
                    IS DISTINCT FROM EXCLUDED.hidden_in_hierarchy
                OR archive.award_budget_person.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_budget_person.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_budget_person.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_transferring_sponsor(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_transferring_sponsor
    row, keyed by award_transferring_sponsor_id - Oracle's own real
    surrogate PK. Returns exactly one of "inserted", "updated",
    "unchanged".
    """
    params: dict[str, Any] = {
        "award_transferring_sponsor_id": _sql_value(
            row["award_transferring_sponsor_id"]
        ),
        "load_id": load_id,
    }
    for column in _TRANSFERRING_SPONSOR_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_transferring_sponsor (
                award_transferring_sponsor_id, award_id, award_number,
                sequence_number, sponsor_code, sponsor_name,
                source_update_timestamp, source_update_user, source_version_number,
                load_id
            ) VALUES (
                :award_transferring_sponsor_id, :award_id, :award_number,
                :sequence_number, :sponsor_code, :sponsor_name,
                :source_update_timestamp, :source_update_user, :source_version_number,
                :load_id
            )
            ON CONFLICT (award_transferring_sponsor_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                sponsor_code = EXCLUDED.sponsor_code,
                sponsor_name = EXCLUDED.sponsor_name,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_transferring_sponsor.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_transferring_sponsor.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_transferring_sponsor.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_transferring_sponsor.sponsor_code
                    IS DISTINCT FROM EXCLUDED.sponsor_code
                OR archive.award_transferring_sponsor.sponsor_name
                    IS DISTINCT FROM EXCLUDED.sponsor_name
                OR archive.award_transferring_sponsor.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_transferring_sponsor.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_transferring_sponsor.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_transmission(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_transmission row,
    keyed by transmission_id - Oracle's own real surrogate PK, assigned
    fresh per real transmission attempt. Every retransmission is a
    genuinely new Oracle row with its own transmission_id, so this
    UPSERT never collapses or overwrites prior transmission history -
    it only makes re-extracting the SAME already-archived attempt
    idempotent. sent_data/returned_data are compared and written
    byte-for-byte, never parsed or reformatted. Returns exactly one of
    "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "transmission_id": _sql_value(row["transmission_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_TRANSMISSION_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_transmission (
                transmission_id, award_id, award_number, sequence_number,
                initiator_id, transmitter_id, success_indicator, transmission_date,
                sent_data, returned_data, basis_of_payment_code, account_type_code,
                sponsor_code, method_of_payment_code, document_number,
                source_update_timestamp, source_update_user, source_version_number,
                load_id
            ) VALUES (
                :transmission_id, :award_id, :award_number, :sequence_number,
                :initiator_id, :transmitter_id, :success_indicator, :transmission_date,
                :sent_data, :returned_data, :basis_of_payment_code, :account_type_code,
                :sponsor_code, :method_of_payment_code, :document_number,
                :source_update_timestamp, :source_update_user, :source_version_number,
                :load_id
            )
            ON CONFLICT (transmission_id) DO UPDATE SET
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                initiator_id = EXCLUDED.initiator_id,
                transmitter_id = EXCLUDED.transmitter_id,
                success_indicator = EXCLUDED.success_indicator,
                transmission_date = EXCLUDED.transmission_date,
                sent_data = EXCLUDED.sent_data,
                returned_data = EXCLUDED.returned_data,
                basis_of_payment_code = EXCLUDED.basis_of_payment_code,
                account_type_code = EXCLUDED.account_type_code,
                sponsor_code = EXCLUDED.sponsor_code,
                method_of_payment_code = EXCLUDED.method_of_payment_code,
                document_number = EXCLUDED.document_number,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_transmission.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_transmission.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_transmission.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_transmission.initiator_id
                    IS DISTINCT FROM EXCLUDED.initiator_id
                OR archive.award_transmission.transmitter_id
                    IS DISTINCT FROM EXCLUDED.transmitter_id
                OR archive.award_transmission.success_indicator
                    IS DISTINCT FROM EXCLUDED.success_indicator
                OR archive.award_transmission.transmission_date
                    IS DISTINCT FROM EXCLUDED.transmission_date
                OR archive.award_transmission.sent_data
                    IS DISTINCT FROM EXCLUDED.sent_data
                OR archive.award_transmission.returned_data
                    IS DISTINCT FROM EXCLUDED.returned_data
                OR archive.award_transmission.basis_of_payment_code
                    IS DISTINCT FROM EXCLUDED.basis_of_payment_code
                OR archive.award_transmission.account_type_code
                    IS DISTINCT FROM EXCLUDED.account_type_code
                OR archive.award_transmission.sponsor_code
                    IS DISTINCT FROM EXCLUDED.sponsor_code
                OR archive.award_transmission.method_of_payment_code
                    IS DISTINCT FROM EXCLUDED.method_of_payment_code
                OR archive.award_transmission.document_number
                    IS DISTINCT FROM EXCLUDED.document_number
                OR archive.award_transmission.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_transmission.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_transmission.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def upsert_award_transmission_child(
    connection: Connection, row: pd.Series, load_id: int
) -> str:
    """Idempotent UPSERT of exactly one archive.award_transmission_child
    row, keyed by transmission_child_id - Oracle's own real surrogate
    PK. transmission_id is carried through as a bare value (see
    docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md for why
    it is not a Postgres FK). overhead_key/base_code/off_campus are
    compared and written exactly as extracted. Returns exactly one of
    "inserted", "updated", "unchanged".
    """
    params: dict[str, Any] = {
        "transmission_child_id": _sql_value(row["transmission_child_id"]),
        "load_id": load_id,
    }
    for column in _AWARD_TRANSMISSION_CHILD_COLUMNS:
        params[column] = _sql_value(_renamed(row, column))

    result = connection.execute(
        text(
            """
            INSERT INTO archive.award_transmission_child (
                transmission_child_id, transmission_id, award_id, award_number,
                sequence_number, parent_document_number, child_document_number,
                lead_unit_number, child_type, overhead_key, base_code, off_campus,
                source_update_timestamp, source_update_user, source_version_number,
                load_id
            ) VALUES (
                :transmission_child_id, :transmission_id, :award_id, :award_number,
                :sequence_number, :parent_document_number, :child_document_number,
                :lead_unit_number, :child_type, :overhead_key, :base_code, :off_campus,
                :source_update_timestamp, :source_update_user, :source_version_number,
                :load_id
            )
            ON CONFLICT (transmission_child_id) DO UPDATE SET
                transmission_id = EXCLUDED.transmission_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                sequence_number = EXCLUDED.sequence_number,
                parent_document_number = EXCLUDED.parent_document_number,
                child_document_number = EXCLUDED.child_document_number,
                lead_unit_number = EXCLUDED.lead_unit_number,
                child_type = EXCLUDED.child_type,
                overhead_key = EXCLUDED.overhead_key,
                base_code = EXCLUDED.base_code,
                off_campus = EXCLUDED.off_campus,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user,
                source_version_number = EXCLUDED.source_version_number,
                load_id = EXCLUDED.load_id
            WHERE
                archive.award_transmission_child.transmission_id
                    IS DISTINCT FROM EXCLUDED.transmission_id
                OR archive.award_transmission_child.award_id
                    IS DISTINCT FROM EXCLUDED.award_id
                OR archive.award_transmission_child.award_number
                    IS DISTINCT FROM EXCLUDED.award_number
                OR archive.award_transmission_child.sequence_number
                    IS DISTINCT FROM EXCLUDED.sequence_number
                OR archive.award_transmission_child.parent_document_number
                    IS DISTINCT FROM EXCLUDED.parent_document_number
                OR archive.award_transmission_child.child_document_number
                    IS DISTINCT FROM EXCLUDED.child_document_number
                OR archive.award_transmission_child.lead_unit_number
                    IS DISTINCT FROM EXCLUDED.lead_unit_number
                OR archive.award_transmission_child.child_type
                    IS DISTINCT FROM EXCLUDED.child_type
                OR archive.award_transmission_child.overhead_key
                    IS DISTINCT FROM EXCLUDED.overhead_key
                OR archive.award_transmission_child.base_code
                    IS DISTINCT FROM EXCLUDED.base_code
                OR archive.award_transmission_child.off_campus
                    IS DISTINCT FROM EXCLUDED.off_campus
                OR archive.award_transmission_child.source_update_timestamp
                    IS DISTINCT FROM EXCLUDED.source_update_timestamp
                OR archive.award_transmission_child.source_update_user
                    IS DISTINCT FROM EXCLUDED.source_update_user
                OR archive.award_transmission_child.source_version_number
                    IS DISTINCT FROM EXCLUDED.source_version_number
            RETURNING (xmax = 0) AS inserted
            """
        ),
        params,
    ).mappings().one_or_none()

    if result is None:
        return "unchanged"
    return "inserted" if result["inserted"] else "updated"


def _empty_load_award_id_report(award_id: int) -> dict[str, Any]:
    return {
        "award_id": award_id,
        "award_number": None,
        "family_size": 0,
        "inserted": 0,
        "updated": 0,
        "unchanged": 0,
        "amount_info_inserted": 0,
        "amount_info_updated": 0,
        "amount_info_unchanged": 0,
        "person_inserted": 0,
        "person_updated": 0,
        "person_unchanged": 0,
        "funding_proposal_inserted": 0,
        "funding_proposal_updated": 0,
        "funding_proposal_unchanged": 0,
        "custom_data_inserted": 0,
        "custom_data_updated": 0,
        "custom_data_unchanged": 0,
        "person_unit_inserted": 0,
        "person_unit_updated": 0,
        "person_unit_unchanged": 0,
        "person_credit_split_inserted": 0,
        "person_credit_split_updated": 0,
        "person_credit_split_unchanged": 0,
        "person_unit_credit_split_inserted": 0,
        "person_unit_credit_split_updated": 0,
        "person_unit_credit_split_unchanged": 0,
        "sponsor_term_inserted": 0,
        "sponsor_term_updated": 0,
        "sponsor_term_unchanged": 0,
        "report_term_inserted": 0,
        "report_term_updated": 0,
        "report_term_unchanged": 0,
        "report_term_recipient_inserted": 0,
        "report_term_recipient_updated": 0,
        "report_term_recipient_unchanged": 0,
        "sponsor_contact_inserted": 0,
        "sponsor_contact_updated": 0,
        "sponsor_contact_unchanged": 0,
        "unit_contact_inserted": 0,
        "unit_contact_updated": 0,
        "unit_contact_unchanged": 0,
        "notepad_inserted": 0,
        "notepad_updated": 0,
        "notepad_unchanged": 0,
        "closeout_inserted": 0,
        "closeout_updated": 0,
        "closeout_unchanged": 0,
        "payment_schedule_inserted": 0,
        "payment_schedule_updated": 0,
        "payment_schedule_unchanged": 0,
        "approved_subaward_inserted": 0,
        "approved_subaward_updated": 0,
        "approved_subaward_unchanged": 0,
        "cfda_inserted": 0,
        "cfda_updated": 0,
        "cfda_unchanged": 0,
        "cost_share_inserted": 0,
        "cost_share_updated": 0,
        "cost_share_unchanged": 0,
        "fanda_rate_inserted": 0,
        "fanda_rate_updated": 0,
        "fanda_rate_unchanged": 0,
        "science_keyword_inserted": 0,
        "science_keyword_updated": 0,
        "science_keyword_unchanged": 0,
        "special_review_inserted": 0,
        "special_review_updated": 0,
        "special_review_unchanged": 0,
        "special_review_exemption_inserted": 0,
        "special_review_exemption_updated": 0,
        "special_review_exemption_unchanged": 0,
        "approved_equipment_inserted": 0,
        "approved_equipment_updated": 0,
        "approved_equipment_unchanged": 0,
        "approved_foreign_travel_inserted": 0,
        "approved_foreign_travel_updated": 0,
        "approved_foreign_travel_unchanged": 0,
        "subcontracting_budgeted_goals_inserted": 0,
        "subcontracting_budgeted_goals_updated": 0,
        "subcontracting_budgeted_goals_unchanged": 0,
        "comment_inserted": 0,
        "comment_updated": 0,
        "comment_unchanged": 0,
        "extension_inserted": 0,
        "extension_updated": 0,
        "extension_unchanged": 0,
        "cgb_inserted": 0,
        "cgb_updated": 0,
        "cgb_unchanged": 0,
        "hierarchy_inserted": 0,
        "hierarchy_updated": 0,
        "hierarchy_unchanged": 0,
        "tnm_document_inserted": 0,
        "tnm_document_updated": 0,
        "tnm_document_unchanged": 0,
        "pending_transaction_inserted": 0,
        "pending_transaction_updated": 0,
        "pending_transaction_unchanged": 0,
        "pending_transaction_extension_inserted": 0,
        "pending_transaction_extension_updated": 0,
        "pending_transaction_extension_unchanged": 0,
        "transaction_detail_inserted": 0,
        "transaction_detail_updated": 0,
        "transaction_detail_unchanged": 0,
        "award_amount_transaction_inserted": 0,
        "award_amount_transaction_updated": 0,
        "award_amount_transaction_unchanged": 0,
        "fanda_distribution_inserted": 0,
        "fanda_distribution_updated": 0,
        "fanda_distribution_unchanged": 0,
        "budget_inserted": 0,
        "budget_updated": 0,
        "budget_unchanged": 0,
        "budget_limit_inserted": 0,
        "budget_limit_updated": 0,
        "budget_limit_unchanged": 0,
        "budget_period_inserted": 0,
        "budget_period_updated": 0,
        "budget_period_unchanged": 0,
        "budget_line_item_inserted": 0,
        "budget_line_item_updated": 0,
        "budget_line_item_unchanged": 0,
        "budget_period_summary_calculated_amount_inserted": 0,
        "budget_period_summary_calculated_amount_updated": 0,
        "budget_period_summary_calculated_amount_unchanged": 0,
        "budget_line_item_calculated_amount_inserted": 0,
        "budget_line_item_calculated_amount_updated": 0,
        "budget_line_item_calculated_amount_unchanged": 0,
        "budget_personnel_detail_inserted": 0,
        "budget_personnel_detail_updated": 0,
        "budget_personnel_detail_unchanged": 0,
        "budget_personnel_calculated_amount_inserted": 0,
        "budget_personnel_calculated_amount_updated": 0,
        "budget_personnel_calculated_amount_unchanged": 0,
        "budget_person_inserted": 0,
        "budget_person_updated": 0,
        "budget_person_unchanged": 0,
        "transferring_sponsor_inserted": 0,
        "transferring_sponsor_updated": 0,
        "transferring_sponsor_unchanged": 0,
        "award_transmission_inserted": 0,
        "award_transmission_updated": 0,
        "award_transmission_unchanged": 0,
        "award_transmission_child_inserted": 0,
        "award_transmission_child_updated": 0,
        "award_transmission_child_unchanged": 0,
        "missing": 0,
        "elapsed_ms": 0.0,
    }


def _run_load_award_id(
    engine: Engine, award_id: int, *, dry_run: bool = False, run_id: str | None = None
) -> dict[str, Any]:
    """--load-award-id: idempotent incremental UPSERT for exactly one
    award_id's ENTIRE award_number version family (see the module-level
    comment above for why this widens beyond the single requested
    award_id) plus that family's amount_info/person/funding_proposal/
    custom_data/person_unit/person_credit_split/person_unit_credit_split/
    sponsor_term/report_term/report_term_recipient/sponsor_contact/
    unit_contact/notepad/closeout/payment_schedule/approved_subaward/
    cfda/cost_share/fanda_rate/science_keyword/special_review/
    special_review_exemption/approved_equipment/approved_foreign_travel/
    subcontracting_budgeted_goals/comment/extension/cgb/hierarchy/
    tnm_document/pending_transaction/pending_transaction_extension/
    transaction_detail/award_amount_transaction/fanda_distribution/
    budget/budget_limit/budget_period/budget_line_item/
    budget_period_summary_calculated_amount/
    budget_line_item_calculated_amount/budget_personnel_detail/
    budget_personnel_calculated_amount/budget_person/
    transferring_sponsor child rows. Never truncates or
    replaces the full tables, never touches SAP transmission (including
    archive.award_extension's own real AWARD_TRANSMISSION child table,
    deliberately not archived). Award.basisOfPaymentCode/
    methodOfPaymentCode ARE captured (see
    docs/architecture/AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md) - a
    prior gap this session already closed, not part of this bundle.
    budget/budget_limit/budget_period/budget_line_item/
    budget_period_summary_calculated_amount/
    budget_line_item_calculated_amount/budget_personnel_detail/
    budget_personnel_calculated_amount are the full Award Budget
    subsystem, archived as one bundle of 8 tables each merging an
    Award-specific _EXT table into the generic Proposal-shared budget
    table it extends (the INNER JOIN to the _EXT table itself is what
    excludes Proposal Development's own budget rows - no discriminator
    column exists on the generic side) - see
    docs/architecture/AWARD_BUDGET_DESIGN.md. Loaded/upserted in strict
    FK-safe order: budget -> budget_limit -> budget_period ->
    {budget_line_item, budget_period_summary_calculated_amount} ->
    {budget_line_item_calculated_amount, budget_personnel_detail} ->
    budget_personnel_calculated_amount.
    budget_person/transferring_sponsor are the final Award gap bundle
    (see docs/architecture/AWARD_COMPLETENESS_REPORT.md).
    budget_person (BUDGET_PERSONS) is shared with Proposal Development
    like the rest of Budget, but has no Award-specific _EXT table at
    all - scoped to Award by joining BUDGET_PERSONS -> BUDGET ->
    AWARD_BUDGET_EXT, and keyed by Oracle's own real composite PK
    (budget_id, person_sequence_number), not a surrogate id.
    transferring_sponsor (AWARD_TRANSFERRING_SPONSOR) is a simple,
    per-version child table structurally identical to
    award_sponsor_term, with sponsor_name denormalized via LEFT JOIN
    SPONSOR (the same convention 01_award_versions.sql already uses),
    not left as a bare code the way award_sponsor_term/
    award_sponsor_contact's own lookup codes are.
    hierarchy/tnm_document/pending_transaction/
    pending_transaction_extension/transaction_detail/
    award_amount_transaction/fanda_distribution are the full Award Time
    and Money subsystem, archived together as one bundle reusing the
    already-archived amount_info as its anchor (which gained two new
    columns, transaction_id/originating_award_version, for this bundle)
    - see docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md.
    person_unit_credit_split
    is upserted after person_unit (its FK parent) and before
    person_credit_split (an unrelated sibling, no ordering requirement
    against it); similarly report_term_recipient is upserted after
    report_term (its FK parent), and special_review_exemption is
    upserted after special_review (its FK parent - the ONLY table in
    this pass with no AWARD_ID column of its own, denormalized via a
    join through special_review then Award) - see
    docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md,
    docs/architecture/AWARD_TERMS_DESIGN.md, and
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
    comment is a specific-Award-version-scoped record (a real,
    backfilled sequence_number) confirmed distinct from notepad (whole-
    family-scoped, no sequence_number at all) - see
    docs/architecture/AWARD_COMMENT_DESIGN.md.
    sponsor_contact/unit_contact/notepad have no FK relationship to each
    other or to any other table added in this pass - see
    docs/architecture/AWARD_CONTACTS_DESIGN.md, which also records why
    archive.award_unit_contact (dropped in V033) was reintroduced here
    with a corrected, double-verified schema rather than restored as
    originally shipped, and docs/architecture/AWARD_NOTEPAD_DESIGN.md,
    which records why archive.award_notepad has no sequence_number
    column at all (notes are scoped to the whole award_number family,
    not a version). closeout/payment_schedule/approved_subaward DO
    carry sequence_number (they belong to a specific Award version, not
    the whole family, unlike notepad) and have no FK relationship to
    each other or to any table added in this pass -
    award_payment_schedule.award_report_term_id is a real, nullable
    cross-reference into archive.award_report_term but is intentionally
    stored unenforced (bare column, no physical FK, no load-ordering
    requirement against report_term) - see
    docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md.
    subcontracting_budgeted_goals is the one table in the whole Award
    domain with no surrogate PK and no award_id at all - it is keyed
    directly by award_number (Oracle's own SUBCONTRACTING_BUD table has
    the same shape) and read via
    read_award_children_matching_award_numbers, not the shared
    award_id-based bounded reader - see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
    extension/cgb are true 1:1 Award extension tables keyed by award_id
    itself (no surrogate id) - see
    docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md, which also records
    why extension's award_number/sequence_number are JOIN-derived (the
    table has neither column) and flags award_cgb.bill_freq_cd as
    unverified against real BU Oracle, the same risk class as the
    award_cost_share.fiscal_year column already found and corrected
    this session. With dry_run=True, every UPSERT still runs (so the
    reported counts are accurate) but the whole transaction is rolled
    back instead of committed."""
    load_logger = logger.bind(stage="load_award_id", award_id=award_id, run_id=run_id)
    started = time.perf_counter()

    award_number = read_award_number_for_award_id(
        OracleDataSource(VERSIONS_ORACLE_SQL), award_id
    )
    if award_number is None:
        load_logger.info(
            "award_id={} not found in Oracle - nothing to load", award_id
        )
        report = _empty_load_award_id_report(award_id)
        report["missing"] = 1
        report["elapsed_ms"] = (time.perf_counter() - started) * 1000
        return report

    versions = prepare_versions(
        read_award_versions_matching_award_numbers(
            OracleDataSource(VERSIONS_ORACLE_SQL), {award_number}
        )
    )
    family_award_ids: set[int] = set(
        versions["award_id"].dropna().astype("int64").tolist()
    )

    amounts_raw = read_award_children_matching_award_ids(
        OracleDataSource(AMOUNTS_ORACLE_SQL), family_award_ids
    )
    amounts = prepare_amounts(amounts_raw) if not amounts_raw.empty else amounts_raw

    people_raw = read_award_children_matching_award_ids(
        OracleDataSource(PEOPLE_ORACLE_SQL), family_award_ids
    )
    people = prepare_people(people_raw) if not people_raw.empty else people_raw

    proposals_raw = read_award_children_matching_award_ids(
        OracleDataSource(PROPOSALS_ORACLE_SQL), family_award_ids
    )
    proposals = (
        prepare_proposals(proposals_raw) if not proposals_raw.empty else proposals_raw
    )

    custom_data_raw = read_award_children_matching_award_ids(
        OracleDataSource(CUSTOM_DATA_ORACLE_SQL), family_award_ids
    )
    custom_data = (
        prepare_custom_data(custom_data_raw)
        if not custom_data_raw.empty
        else custom_data_raw
    )

    person_units_raw = read_award_children_matching_award_ids(
        OracleDataSource(PERSON_UNITS_ORACLE_SQL), family_award_ids
    )
    person_units = (
        prepare_person_units(person_units_raw)
        if not person_units_raw.empty
        else person_units_raw
    )

    person_credit_splits_raw = read_award_children_matching_award_ids(
        OracleDataSource(PERSON_CREDIT_SPLITS_ORACLE_SQL), family_award_ids
    )
    person_credit_splits = (
        prepare_person_credit_splits(person_credit_splits_raw)
        if not person_credit_splits_raw.empty
        else person_credit_splits_raw
    )

    person_unit_credit_splits_raw = read_award_children_matching_award_ids(
        OracleDataSource(PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL), family_award_ids
    )
    person_unit_credit_splits = (
        prepare_person_unit_credit_splits(person_unit_credit_splits_raw)
        if not person_unit_credit_splits_raw.empty
        else person_unit_credit_splits_raw
    )

    sponsor_terms_raw = read_award_children_matching_award_ids(
        OracleDataSource(SPONSOR_TERMS_ORACLE_SQL), family_award_ids
    )
    sponsor_terms = (
        prepare_sponsor_terms(sponsor_terms_raw)
        if not sponsor_terms_raw.empty
        else sponsor_terms_raw
    )

    report_terms_raw = read_award_children_matching_award_ids(
        OracleDataSource(REPORT_TERMS_ORACLE_SQL), family_award_ids
    )
    report_terms = (
        prepare_report_terms(report_terms_raw)
        if not report_terms_raw.empty
        else report_terms_raw
    )

    report_term_recipients_raw = read_award_children_matching_award_ids(
        OracleDataSource(REPORT_TERM_RECIPIENTS_ORACLE_SQL), family_award_ids
    )
    report_term_recipients = (
        prepare_report_term_recipients(report_term_recipients_raw)
        if not report_term_recipients_raw.empty
        else report_term_recipients_raw
    )

    sponsor_contacts_raw = read_award_children_matching_award_ids(
        OracleDataSource(SPONSOR_CONTACTS_ORACLE_SQL), family_award_ids
    )
    sponsor_contacts = (
        prepare_sponsor_contacts(sponsor_contacts_raw)
        if not sponsor_contacts_raw.empty
        else sponsor_contacts_raw
    )

    unit_contacts_raw = read_award_children_matching_award_ids(
        OracleDataSource(UNIT_CONTACTS_ORACLE_SQL), family_award_ids
    )
    unit_contacts = (
        prepare_unit_contacts(unit_contacts_raw)
        if not unit_contacts_raw.empty
        else unit_contacts_raw
    )

    notepad_raw = read_award_children_matching_award_ids(
        OracleDataSource(NOTEPAD_ORACLE_SQL), family_award_ids
    )
    notepad = (
        prepare_notepad(notepad_raw) if not notepad_raw.empty else notepad_raw
    )

    closeout_raw = read_award_children_matching_award_ids(
        OracleDataSource(CLOSEOUT_ORACLE_SQL), family_award_ids
    )
    closeout = (
        prepare_closeout(closeout_raw) if not closeout_raw.empty else closeout_raw
    )

    payment_schedule_raw = read_award_children_matching_award_ids(
        OracleDataSource(PAYMENT_SCHEDULE_ORACLE_SQL), family_award_ids
    )
    payment_schedule = (
        prepare_payment_schedule(payment_schedule_raw)
        if not payment_schedule_raw.empty
        else payment_schedule_raw
    )

    approved_subaward_raw = read_award_children_matching_award_ids(
        OracleDataSource(APPROVED_SUBAWARD_ORACLE_SQL), family_award_ids
    )
    approved_subaward = (
        prepare_approved_subaward(approved_subaward_raw)
        if not approved_subaward_raw.empty
        else approved_subaward_raw
    )

    cfda_raw = read_award_children_matching_award_ids(
        OracleDataSource(CFDA_ORACLE_SQL), family_award_ids
    )
    cfda = prepare_cfda(cfda_raw) if not cfda_raw.empty else cfda_raw

    cost_share_raw = read_award_children_matching_award_ids(
        OracleDataSource(COST_SHARE_ORACLE_SQL), family_award_ids
    )
    cost_share = (
        prepare_cost_share(cost_share_raw) if not cost_share_raw.empty else cost_share_raw
    )

    fanda_rate_raw = read_award_children_matching_award_ids(
        OracleDataSource(FANDA_RATE_ORACLE_SQL), family_award_ids
    )
    fanda_rate = (
        prepare_fanda_rate(fanda_rate_raw) if not fanda_rate_raw.empty else fanda_rate_raw
    )

    science_keyword_raw = read_award_children_matching_award_ids(
        OracleDataSource(SCIENCE_KEYWORD_ORACLE_SQL), family_award_ids
    )
    science_keyword = (
        prepare_science_keyword(science_keyword_raw)
        if not science_keyword_raw.empty
        else science_keyword_raw
    )

    special_review_raw = read_award_children_matching_award_ids(
        OracleDataSource(SPECIAL_REVIEW_ORACLE_SQL), family_award_ids
    )
    special_review = (
        prepare_special_review(special_review_raw)
        if not special_review_raw.empty
        else special_review_raw
    )

    special_review_exemption_raw = read_award_children_matching_award_ids(
        OracleDataSource(SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL), family_award_ids
    )
    special_review_exemption = (
        prepare_special_review_exemption(special_review_exemption_raw)
        if not special_review_exemption_raw.empty
        else special_review_exemption_raw
    )

    approved_equipment_raw = read_award_children_matching_award_ids(
        OracleDataSource(APPROVED_EQUIPMENT_ORACLE_SQL), family_award_ids
    )
    approved_equipment = (
        prepare_approved_equipment(approved_equipment_raw)
        if not approved_equipment_raw.empty
        else approved_equipment_raw
    )

    approved_foreign_travel_raw = read_award_children_matching_award_ids(
        OracleDataSource(APPROVED_FOREIGN_TRAVEL_ORACLE_SQL), family_award_ids
    )
    approved_foreign_travel = (
        prepare_approved_foreign_travel(approved_foreign_travel_raw)
        if not approved_foreign_travel_raw.empty
        else approved_foreign_travel_raw
    )

    subcontracting_budgeted_goals_raw = read_award_children_matching_award_numbers(
        OracleDataSource(SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL), {award_number}
    )
    subcontracting_budgeted_goals = (
        prepare_subcontracting_budgeted_goals(subcontracting_budgeted_goals_raw)
        if not subcontracting_budgeted_goals_raw.empty
        else subcontracting_budgeted_goals_raw
    )

    comment_raw = read_award_children_matching_award_ids(
        OracleDataSource(COMMENT_ORACLE_SQL), family_award_ids
    )
    comment = (
        prepare_award_comments(comment_raw) if not comment_raw.empty else comment_raw
    )

    extension_raw = read_award_children_matching_award_ids(
        OracleDataSource(EXTENSION_ORACLE_SQL), family_award_ids
    )
    extension = (
        prepare_award_extension(extension_raw)
        if not extension_raw.empty
        else extension_raw
    )

    cgb_raw = read_award_children_matching_award_ids(
        OracleDataSource(CGB_ORACLE_SQL), family_award_ids
    )
    cgb = prepare_award_cgb(cgb_raw) if not cgb_raw.empty else cgb_raw

    hierarchy_raw = read_award_children_matching_award_numbers(
        OracleDataSource(HIERARCHY_ORACLE_SQL), {award_number}
    )
    hierarchy = (
        prepare_award_hierarchy(hierarchy_raw)
        if not hierarchy_raw.empty
        else hierarchy_raw
    )

    tnm_document_raw = read_award_children_matching_award_numbers(
        OracleDataSource(TIME_AND_MONEY_DOCUMENT_ORACLE_SQL), {award_number}
    )
    tnm_document = (
        prepare_time_and_money_document(tnm_document_raw)
        if not tnm_document_raw.empty
        else tnm_document_raw
    )

    pending_transaction_raw = read_pending_transactions_matching_award_numbers(
        OracleDataSource(PENDING_TRANSACTION_ORACLE_SQL), {award_number}
    )
    pending_transaction = (
        prepare_pending_transaction(pending_transaction_raw)
        if not pending_transaction_raw.empty
        else pending_transaction_raw
    )

    pending_transaction_extension_raw = read_pending_transactions_matching_award_numbers(
        OracleDataSource(PENDING_TRANSACTION_EXTENSION_ORACLE_SQL), {award_number}
    )
    pending_transaction_extension = (
        prepare_pending_transaction_extension(pending_transaction_extension_raw)
        if not pending_transaction_extension_raw.empty
        else pending_transaction_extension_raw
    )

    transaction_detail_raw = read_award_children_matching_award_numbers(
        OracleDataSource(TRANSACTION_DETAIL_ORACLE_SQL), {award_number}
    )
    transaction_detail = (
        prepare_transaction_detail(transaction_detail_raw)
        if not transaction_detail_raw.empty
        else transaction_detail_raw
    )

    award_amount_transaction_raw = read_award_children_matching_award_numbers(
        OracleDataSource(AWARD_AMOUNT_TRANSACTION_ORACLE_SQL), {award_number}
    )
    award_amount_transaction = (
        prepare_award_amount_transaction(award_amount_transaction_raw)
        if not award_amount_transaction_raw.empty
        else award_amount_transaction_raw
    )

    fanda_distribution_raw = read_award_children_matching_award_ids(
        OracleDataSource(AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL),
        family_award_ids,
    )
    fanda_distribution = (
        prepare_award_direct_fanda_distribution(fanda_distribution_raw)
        if not fanda_distribution_raw.empty
        else fanda_distribution_raw
    )

    budget_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_ORACLE_SQL), family_award_ids
    )
    budget = prepare_award_budget(budget_raw) if not budget_raw.empty else budget_raw

    budget_limit_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_LIMIT_ORACLE_SQL), family_award_ids
    )
    budget_limit = (
        prepare_award_budget_limit(budget_limit_raw)
        if not budget_limit_raw.empty
        else budget_limit_raw
    )

    budget_period_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_PERIOD_ORACLE_SQL), family_award_ids
    )
    budget_period = (
        prepare_award_budget_period(budget_period_raw)
        if not budget_period_raw.empty
        else budget_period_raw
    )

    budget_line_item_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_LINE_ITEM_ORACLE_SQL), family_award_ids
    )
    budget_line_item = (
        prepare_award_budget_line_item(budget_line_item_raw)
        if not budget_line_item_raw.empty
        else budget_line_item_raw
    )

    budget_period_summary_calculated_amount_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL),
        family_award_ids,
    )
    budget_period_summary_calculated_amount = (
        prepare_award_budget_period_summary_calculated_amount(
            budget_period_summary_calculated_amount_raw
        )
        if not budget_period_summary_calculated_amount_raw.empty
        else budget_period_summary_calculated_amount_raw
    )

    budget_line_item_calculated_amount_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL),
        family_award_ids,
    )
    budget_line_item_calculated_amount = (
        prepare_award_budget_line_item_calculated_amount(
            budget_line_item_calculated_amount_raw
        )
        if not budget_line_item_calculated_amount_raw.empty
        else budget_line_item_calculated_amount_raw
    )

    budget_personnel_detail_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_PERSONNEL_DETAIL_ORACLE_SQL), family_award_ids
    )
    budget_personnel_detail = (
        prepare_award_budget_personnel_detail(budget_personnel_detail_raw)
        if not budget_personnel_detail_raw.empty
        else budget_personnel_detail_raw
    )

    budget_personnel_calculated_amount_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL),
        family_award_ids,
    )
    budget_personnel_calculated_amount = (
        prepare_award_budget_personnel_calculated_amount(
            budget_personnel_calculated_amount_raw
        )
        if not budget_personnel_calculated_amount_raw.empty
        else budget_personnel_calculated_amount_raw
    )

    budget_person_raw = read_award_children_matching_award_ids(
        OracleDataSource(BUDGET_PERSON_ORACLE_SQL), family_award_ids
    )
    budget_person = (
        prepare_award_budget_person(budget_person_raw)
        if not budget_person_raw.empty
        else budget_person_raw
    )

    transferring_sponsor_raw = read_award_children_matching_award_ids(
        OracleDataSource(TRANSFERRING_SPONSOR_ORACLE_SQL), family_award_ids
    )
    transferring_sponsor = (
        prepare_award_transferring_sponsor(transferring_sponsor_raw)
        if not transferring_sponsor_raw.empty
        else transferring_sponsor_raw
    )

    award_transmission_raw = read_award_children_matching_award_ids(
        OracleDataSource(AWARD_TRANSMISSION_ORACLE_SQL), family_award_ids
    )
    award_transmission = (
        prepare_award_transmission(award_transmission_raw)
        if not award_transmission_raw.empty
        else award_transmission_raw
    )

    award_transmission_child_raw = read_award_children_matching_award_ids(
        OracleDataSource(AWARD_TRANSMISSION_CHILD_ORACLE_SQL), family_award_ids
    )
    award_transmission_child = (
        prepare_award_transmission_child(award_transmission_child_raw)
        if not award_transmission_child_raw.empty
        else award_transmission_child_raw
    )

    report = _empty_load_award_id_report(award_id)
    report["award_number"] = award_number
    report["family_size"] = len(family_award_ids)

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            total_rows = (
                len(versions)
                + len(amounts)
                + len(people)
                + len(proposals)
                + len(custom_data)
                + len(person_units)
                + len(person_credit_splits)
                + len(person_unit_credit_splits)
                + len(sponsor_terms)
                + len(report_terms)
                + len(report_term_recipients)
                + len(sponsor_contacts)
                + len(unit_contacts)
                + len(notepad)
                + len(closeout)
                + len(payment_schedule)
                + len(approved_subaward)
                + len(cfda)
                + len(cost_share)
                + len(fanda_rate)
                + len(science_keyword)
                + len(special_review)
                + len(special_review_exemption)
                + len(approved_equipment)
                + len(approved_foreign_travel)
                + len(subcontracting_budgeted_goals)
                + len(comment)
                + len(extension)
                + len(cgb)
                + len(hierarchy)
                + len(tnm_document)
                + len(pending_transaction)
                + len(pending_transaction_extension)
                + len(transaction_detail)
                + len(award_amount_transaction)
                + len(fanda_distribution)
                + len(budget)
                + len(budget_limit)
                + len(budget_period)
                + len(budget_line_item)
                + len(budget_period_summary_calculated_amount)
                + len(budget_line_item_calculated_amount)
                + len(budget_personnel_detail)
                + len(budget_personnel_calculated_amount)
                + len(budget_person)
                + len(transferring_sponsor)
                + len(award_transmission)
                + len(award_transmission_child)
            )
            load_id = create_load_run(connection, total_rows)

            # Clear the family's old primary-current flag first, in its
            # own statement, before any per-row UPSERT below might set a
            # *different* row to TRUE - see the module-level comment on
            # ux_award_one_primary_current for why ordering matters here.
            # Deliberately excludes the freshly-computed winner: clearing
            # it too would make its own UPSERT immediately below always
            # look like a change (FALSE -> TRUE), even when nothing
            # actually changed from before this whole load started.
            primary_rows = versions.loc[versions["is_primary_current"] == True]  # noqa: E712
            winning_award_id = (
                int(primary_rows.iloc[0]["award_id"])
                if not primary_rows.empty
                else None
            )
            connection.execute(
                text(
                    "UPDATE archive.award_version SET is_primary_current = FALSE "
                    "WHERE award_number = :award_number AND is_primary_current = TRUE "
                    "AND award_id IS DISTINCT FROM :winning_award_id"
                ),
                {
                    "award_number": award_number,
                    "winning_award_id": winning_award_id,
                },
            )

            for _, version_row in versions.iterrows():
                result = upsert_award_version(connection, version_row, load_id)
                report[result] += 1

            for _, amount_row in amounts.iterrows():
                result = upsert_award_amount_info(connection, amount_row, load_id)
                report[f"amount_info_{result}"] += 1

            for _, person_row in people.iterrows():
                result = upsert_award_person(connection, person_row, load_id)
                report[f"person_{result}"] += 1

            for _, proposal_row in proposals.iterrows():
                result = upsert_award_funding_proposal(
                    connection, proposal_row, load_id
                )
                report[f"funding_proposal_{result}"] += 1

            for _, custom_data_row in custom_data.iterrows():
                result = upsert_award_custom_data(
                    connection, custom_data_row, load_id
                )
                report[f"custom_data_{result}"] += 1

            for _, person_unit_row in person_units.iterrows():
                result = upsert_award_person_unit(
                    connection, person_unit_row, load_id
                )
                report[f"person_unit_{result}"] += 1

            for _, person_unit_credit_split_row in person_unit_credit_splits.iterrows():
                result = upsert_award_person_unit_credit_split(
                    connection, person_unit_credit_split_row, load_id
                )
                report[f"person_unit_credit_split_{result}"] += 1

            for _, person_credit_split_row in person_credit_splits.iterrows():
                result = upsert_award_person_credit_split(
                    connection, person_credit_split_row, load_id
                )
                report[f"person_credit_split_{result}"] += 1

            for _, sponsor_term_row in sponsor_terms.iterrows():
                result = upsert_award_sponsor_term(
                    connection, sponsor_term_row, load_id
                )
                report[f"sponsor_term_{result}"] += 1

            for _, report_term_row in report_terms.iterrows():
                result = upsert_award_report_term(
                    connection, report_term_row, load_id
                )
                report[f"report_term_{result}"] += 1

            for _, report_term_recipient_row in report_term_recipients.iterrows():
                result = upsert_award_report_term_recipient(
                    connection, report_term_recipient_row, load_id
                )
                report[f"report_term_recipient_{result}"] += 1

            for _, sponsor_contact_row in sponsor_contacts.iterrows():
                result = upsert_award_sponsor_contact(
                    connection, sponsor_contact_row, load_id
                )
                report[f"sponsor_contact_{result}"] += 1

            for _, unit_contact_row in unit_contacts.iterrows():
                result = upsert_award_unit_contact(
                    connection, unit_contact_row, load_id
                )
                report[f"unit_contact_{result}"] += 1

            for _, notepad_row in notepad.iterrows():
                result = upsert_award_notepad(connection, notepad_row, load_id)
                report[f"notepad_{result}"] += 1

            for _, closeout_row in closeout.iterrows():
                result = upsert_award_closeout(connection, closeout_row, load_id)
                report[f"closeout_{result}"] += 1

            for _, payment_schedule_row in payment_schedule.iterrows():
                result = upsert_award_payment_schedule(
                    connection, payment_schedule_row, load_id
                )
                report[f"payment_schedule_{result}"] += 1

            for _, approved_subaward_row in approved_subaward.iterrows():
                result = upsert_award_approved_subaward(
                    connection, approved_subaward_row, load_id
                )
                report[f"approved_subaward_{result}"] += 1

            for _, cfda_row in cfda.iterrows():
                result = upsert_award_cfda(connection, cfda_row, load_id)
                report[f"cfda_{result}"] += 1

            for _, cost_share_row in cost_share.iterrows():
                result = upsert_award_cost_share(connection, cost_share_row, load_id)
                report[f"cost_share_{result}"] += 1

            for _, fanda_rate_row in fanda_rate.iterrows():
                result = upsert_award_fanda_rate(connection, fanda_rate_row, load_id)
                report[f"fanda_rate_{result}"] += 1

            for _, science_keyword_row in science_keyword.iterrows():
                result = upsert_award_science_keyword(
                    connection, science_keyword_row, load_id
                )
                report[f"science_keyword_{result}"] += 1

            # special_review before special_review_exemption (its FK
            # parent) - see
            # docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
            for _, special_review_row in special_review.iterrows():
                result = upsert_award_special_review(
                    connection, special_review_row, load_id
                )
                report[f"special_review_{result}"] += 1

            for _, special_review_exemption_row in special_review_exemption.iterrows():
                result = upsert_award_special_review_exemption(
                    connection, special_review_exemption_row, load_id
                )
                report[f"special_review_exemption_{result}"] += 1

            for _, approved_equipment_row in approved_equipment.iterrows():
                result = upsert_award_approved_equipment(
                    connection, approved_equipment_row, load_id
                )
                report[f"approved_equipment_{result}"] += 1

            for _, approved_foreign_travel_row in approved_foreign_travel.iterrows():
                result = upsert_award_approved_foreign_travel(
                    connection, approved_foreign_travel_row, load_id
                )
                report[f"approved_foreign_travel_{result}"] += 1

            for _, subcontracting_row in subcontracting_budgeted_goals.iterrows():
                result = upsert_award_subcontracting_budgeted_goals(
                    connection, subcontracting_row, load_id
                )
                report[f"subcontracting_budgeted_goals_{result}"] += 1

            for _, comment_row in comment.iterrows():
                result = upsert_award_comments(connection, comment_row, load_id)
                report[f"comment_{result}"] += 1

            for _, extension_row in extension.iterrows():
                result = upsert_award_extension(connection, extension_row, load_id)
                report[f"extension_{result}"] += 1

            for _, cgb_row in cgb.iterrows():
                result = upsert_award_cgb(connection, cgb_row, load_id)
                report[f"cgb_{result}"] += 1

            for _, hierarchy_row in hierarchy.iterrows():
                result = upsert_award_hierarchy(connection, hierarchy_row, load_id)
                report[f"hierarchy_{result}"] += 1

            for _, tnm_document_row in tnm_document.iterrows():
                result = upsert_time_and_money_document(
                    connection, tnm_document_row, load_id
                )
                report[f"tnm_document_{result}"] += 1

            for _, pending_transaction_row in pending_transaction.iterrows():
                result = upsert_pending_transaction(
                    connection, pending_transaction_row, load_id
                )
                report[f"pending_transaction_{result}"] += 1

            for _, pte_row in pending_transaction_extension.iterrows():
                result = upsert_pending_transaction_extension(
                    connection, pte_row, load_id
                )
                report[f"pending_transaction_extension_{result}"] += 1

            for _, transaction_detail_row in transaction_detail.iterrows():
                result = upsert_transaction_detail(
                    connection, transaction_detail_row, load_id
                )
                report[f"transaction_detail_{result}"] += 1

            for _, aat_row in award_amount_transaction.iterrows():
                result = upsert_award_amount_transaction(
                    connection, aat_row, load_id
                )
                report[f"award_amount_transaction_{result}"] += 1

            for _, fanda_row in fanda_distribution.iterrows():
                result = upsert_award_direct_fanda_distribution(
                    connection, fanda_row, load_id
                )
                report[f"fanda_distribution_{result}"] += 1

            for _, budget_row in budget.iterrows():
                result = upsert_award_budget(connection, budget_row, load_id)
                report[f"budget_{result}"] += 1

            for _, budget_limit_row in budget_limit.iterrows():
                result = upsert_award_budget_limit(
                    connection, budget_limit_row, load_id
                )
                report[f"budget_limit_{result}"] += 1

            for _, budget_period_row in budget_period.iterrows():
                result = upsert_award_budget_period(
                    connection, budget_period_row, load_id
                )
                report[f"budget_period_{result}"] += 1

            for _, budget_line_item_row in budget_line_item.iterrows():
                result = upsert_award_budget_line_item(
                    connection, budget_line_item_row, load_id
                )
                report[f"budget_line_item_{result}"] += 1

            for (
                _,
                summary_row,
            ) in budget_period_summary_calculated_amount.iterrows():
                result = upsert_award_budget_period_summary_calculated_amount(
                    connection, summary_row, load_id
                )
                report[f"budget_period_summary_calculated_amount_{result}"] += 1

            for (
                _,
                line_item_cal_row,
            ) in budget_line_item_calculated_amount.iterrows():
                result = upsert_award_budget_line_item_calculated_amount(
                    connection, line_item_cal_row, load_id
                )
                report[f"budget_line_item_calculated_amount_{result}"] += 1

            for (
                _,
                personnel_detail_row,
            ) in budget_personnel_detail.iterrows():
                result = upsert_award_budget_personnel_detail(
                    connection, personnel_detail_row, load_id
                )
                report[f"budget_personnel_detail_{result}"] += 1

            for (
                _,
                personnel_cal_row,
            ) in budget_personnel_calculated_amount.iterrows():
                result = upsert_award_budget_personnel_calculated_amount(
                    connection, personnel_cal_row, load_id
                )
                report[f"budget_personnel_calculated_amount_{result}"] += 1

            for _, budget_person_row in budget_person.iterrows():
                result = upsert_award_budget_person(
                    connection, budget_person_row, load_id
                )
                report[f"budget_person_{result}"] += 1

            for (
                _,
                transferring_sponsor_row,
            ) in transferring_sponsor.iterrows():
                result = upsert_award_transferring_sponsor(
                    connection, transferring_sponsor_row, load_id
                )
                report[f"transferring_sponsor_{result}"] += 1

            for (
                _,
                award_transmission_row,
            ) in award_transmission.iterrows():
                result = upsert_award_transmission(
                    connection, award_transmission_row, load_id
                )
                report[f"award_transmission_{result}"] += 1

            for (
                _,
                award_transmission_child_row,
            ) in award_transmission_child.iterrows():
                result = upsert_award_transmission_child(
                    connection, award_transmission_child_row, load_id
                )
                report[f"award_transmission_child_{result}"] += 1

            mark_load_complete(connection, load_id, total_rows)
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    report["elapsed_ms"] = (time.perf_counter() - started) * 1000

    load_logger.info(
        "Incremental Award load for award_id={} (award_number={} "
        "family_size={}){} in {:.1f}ms: version(inserted={} updated={} unchanged={}) "
        "amount_info(inserted={} updated={} unchanged={}) "
        "person(inserted={} updated={} unchanged={}) "
        "funding_proposal(inserted={} updated={} unchanged={}) "
        "custom_data(inserted={} updated={} unchanged={}) "
        "person_unit(inserted={} updated={} unchanged={}) "
        "person_credit_split(inserted={} updated={} unchanged={}) "
        "person_unit_credit_split(inserted={} updated={} unchanged={}) "
        "sponsor_term(inserted={} updated={} unchanged={}) "
        "report_term(inserted={} updated={} unchanged={}) "
        "report_term_recipient(inserted={} updated={} unchanged={}) "
        "sponsor_contact(inserted={} updated={} unchanged={}) "
        "unit_contact(inserted={} updated={} unchanged={}) "
        "notepad(inserted={} updated={} unchanged={}) "
        "closeout(inserted={} updated={} unchanged={}) "
        "payment_schedule(inserted={} updated={} unchanged={}) "
        "approved_subaward(inserted={} updated={} unchanged={}) "
        "cfda(inserted={} updated={} unchanged={}) "
        "cost_share(inserted={} updated={} unchanged={}) "
        "fanda_rate(inserted={} updated={} unchanged={}) "
        "science_keyword(inserted={} updated={} unchanged={}) "
        "special_review(inserted={} updated={} unchanged={}) "
        "special_review_exemption(inserted={} updated={} unchanged={}) "
        "approved_equipment(inserted={} updated={} unchanged={}) "
        "approved_foreign_travel(inserted={} updated={} unchanged={}) "
        "subcontracting_budgeted_goals(inserted={} updated={} unchanged={}) "
        "comment(inserted={} updated={} unchanged={}) "
        "extension(inserted={} updated={} unchanged={}) "
        "cgb(inserted={} updated={} unchanged={}) "
        "hierarchy(inserted={} updated={} unchanged={}) "
        "tnm_document(inserted={} updated={} unchanged={}) "
        "pending_transaction(inserted={} updated={} unchanged={}) "
        "pending_transaction_extension(inserted={} updated={} unchanged={}) "
        "transaction_detail(inserted={} updated={} unchanged={}) "
        "award_amount_transaction(inserted={} updated={} unchanged={}) "
        "fanda_distribution(inserted={} updated={} unchanged={}) "
        "budget(inserted={} updated={} unchanged={}) "
        "budget_limit(inserted={} updated={} unchanged={}) "
        "budget_period(inserted={} updated={} unchanged={}) "
        "budget_line_item(inserted={} updated={} unchanged={}) "
        "budget_period_summary_calculated_amount(inserted={} updated={} unchanged={}) "
        "budget_line_item_calculated_amount(inserted={} updated={} unchanged={}) "
        "budget_personnel_detail(inserted={} updated={} unchanged={}) "
        "budget_personnel_calculated_amount(inserted={} updated={} unchanged={}) "
        "budget_person(inserted={} updated={} unchanged={}) "
        "transferring_sponsor(inserted={} updated={} unchanged={}) "
        "award_transmission(inserted={} updated={} unchanged={}) "
        "award_transmission_child(inserted={} updated={} unchanged={})",
        award_id,
        award_number,
        report["family_size"],
        " [DRY RUN - not persisted]" if dry_run else "",
        report["elapsed_ms"],
        report["inserted"],
        report["updated"],
        report["unchanged"],
        report["amount_info_inserted"],
        report["amount_info_updated"],
        report["amount_info_unchanged"],
        report["person_inserted"],
        report["person_updated"],
        report["person_unchanged"],
        report["funding_proposal_inserted"],
        report["funding_proposal_updated"],
        report["funding_proposal_unchanged"],
        report["custom_data_inserted"],
        report["custom_data_updated"],
        report["custom_data_unchanged"],
        report["person_unit_inserted"],
        report["person_unit_updated"],
        report["person_unit_unchanged"],
        report["person_credit_split_inserted"],
        report["person_credit_split_updated"],
        report["person_credit_split_unchanged"],
        report["person_unit_credit_split_inserted"],
        report["person_unit_credit_split_updated"],
        report["person_unit_credit_split_unchanged"],
        report["sponsor_term_inserted"],
        report["sponsor_term_updated"],
        report["sponsor_term_unchanged"],
        report["report_term_inserted"],
        report["report_term_updated"],
        report["report_term_unchanged"],
        report["report_term_recipient_inserted"],
        report["report_term_recipient_updated"],
        report["report_term_recipient_unchanged"],
        report["sponsor_contact_inserted"],
        report["sponsor_contact_updated"],
        report["sponsor_contact_unchanged"],
        report["unit_contact_inserted"],
        report["unit_contact_updated"],
        report["unit_contact_unchanged"],
        report["notepad_inserted"],
        report["notepad_updated"],
        report["notepad_unchanged"],
        report["closeout_inserted"],
        report["closeout_updated"],
        report["closeout_unchanged"],
        report["payment_schedule_inserted"],
        report["payment_schedule_updated"],
        report["payment_schedule_unchanged"],
        report["approved_subaward_inserted"],
        report["approved_subaward_updated"],
        report["approved_subaward_unchanged"],
        report["cfda_inserted"],
        report["cfda_updated"],
        report["cfda_unchanged"],
        report["cost_share_inserted"],
        report["cost_share_updated"],
        report["cost_share_unchanged"],
        report["fanda_rate_inserted"],
        report["fanda_rate_updated"],
        report["fanda_rate_unchanged"],
        report["science_keyword_inserted"],
        report["science_keyword_updated"],
        report["science_keyword_unchanged"],
        report["special_review_inserted"],
        report["special_review_updated"],
        report["special_review_unchanged"],
        report["special_review_exemption_inserted"],
        report["special_review_exemption_updated"],
        report["special_review_exemption_unchanged"],
        report["approved_equipment_inserted"],
        report["approved_equipment_updated"],
        report["approved_equipment_unchanged"],
        report["approved_foreign_travel_inserted"],
        report["approved_foreign_travel_updated"],
        report["approved_foreign_travel_unchanged"],
        report["subcontracting_budgeted_goals_inserted"],
        report["subcontracting_budgeted_goals_updated"],
        report["subcontracting_budgeted_goals_unchanged"],
        report["comment_inserted"],
        report["comment_updated"],
        report["comment_unchanged"],
        report["extension_inserted"],
        report["extension_updated"],
        report["extension_unchanged"],
        report["cgb_inserted"],
        report["cgb_updated"],
        report["cgb_unchanged"],
        report["hierarchy_inserted"],
        report["hierarchy_updated"],
        report["hierarchy_unchanged"],
        report["tnm_document_inserted"],
        report["tnm_document_updated"],
        report["tnm_document_unchanged"],
        report["pending_transaction_inserted"],
        report["pending_transaction_updated"],
        report["pending_transaction_unchanged"],
        report["pending_transaction_extension_inserted"],
        report["pending_transaction_extension_updated"],
        report["pending_transaction_extension_unchanged"],
        report["transaction_detail_inserted"],
        report["transaction_detail_updated"],
        report["transaction_detail_unchanged"],
        report["award_amount_transaction_inserted"],
        report["award_amount_transaction_updated"],
        report["award_amount_transaction_unchanged"],
        report["fanda_distribution_inserted"],
        report["fanda_distribution_updated"],
        report["fanda_distribution_unchanged"],
        report["budget_inserted"],
        report["budget_updated"],
        report["budget_unchanged"],
        report["budget_limit_inserted"],
        report["budget_limit_updated"],
        report["budget_limit_unchanged"],
        report["budget_period_inserted"],
        report["budget_period_updated"],
        report["budget_period_unchanged"],
        report["budget_line_item_inserted"],
        report["budget_line_item_updated"],
        report["budget_line_item_unchanged"],
        report["budget_period_summary_calculated_amount_inserted"],
        report["budget_period_summary_calculated_amount_updated"],
        report["budget_period_summary_calculated_amount_unchanged"],
        report["budget_line_item_calculated_amount_inserted"],
        report["budget_line_item_calculated_amount_updated"],
        report["budget_line_item_calculated_amount_unchanged"],
        report["budget_personnel_detail_inserted"],
        report["budget_personnel_detail_updated"],
        report["budget_personnel_detail_unchanged"],
        report["budget_personnel_calculated_amount_inserted"],
        report["budget_personnel_calculated_amount_updated"],
        report["budget_personnel_calculated_amount_unchanged"],
        report["budget_person_inserted"],
        report["budget_person_updated"],
        report["budget_person_unchanged"],
        report["transferring_sponsor_inserted"],
        report["transferring_sponsor_updated"],
        report["transferring_sponsor_unchanged"],
        report["award_transmission_inserted"],
        report["award_transmission_updated"],
        report["award_transmission_unchanged"],
        report["award_transmission_child_inserted"],
        report["award_transmission_child_updated"],
        report["award_transmission_child_unchanged"],
    )
    return report


def _select_award_ids_ascending(
    source: OracleDataSource, requested_size: int
) -> list[int]:
    """--validation-overlap selection only (see _run_create_award_batch).
    Deliberately does NOT reuse
    batch_framework.select_distinct_ascending_from_oracle_batches's
    early-stop optimization. That optimization is only correct when the
    underlying Oracle source is already ORDER BY the same column being
    selected (true for Award Attachment's physical-file scan, ORDER BY
    FILE_ID, and true for this module's own AWARD_IDS_ASCENDING_ORACLE_SQL
    used by the production selection path below). 01_award_versions.sql
    is ORDER BY AWARD_NUMBER, SEQUENCE_NUMBER instead - award_id has no
    relationship to that sort order - so stopping early after N distinct
    award_ids would not select the N globally-smallest ones. This always
    scans the full source and sorts every distinct award_id in Python
    instead - the same "smallest N, every time" behavior every batch
    scale from 10 through 5000 has used so far, preserved here
    unchanged and now reachable only via --validation-overlap."""
    all_award_ids: set[int] = set()
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            ids = pd.to_numeric(batch["award_id"], errors="coerce")
            all_award_ids.update(int(value) for value in ids.dropna())
    finally:
        batches.close()
    return sorted(all_award_ids)[:requested_size]


def _excluded_completed_and_active_award_ids(engine: Engine) -> set[int]:
    """Production --create-batch's exclusion set: every award_id that
    either

    (a) is already COMPLETED as an etl_batch_item - regardless of that
        item's own batch's overall status, since a batch can finish
        PARTIAL/FAILED overall while still containing individually-
        COMPLETED items, and those specific award_ids must never be
        reselected - or

    (b) belongs to a batch that is still active (READY or PROCESSING) -
        so two batches can never concurrently claim the same award_id,
        regardless of that item's own individual status within the
        active batch - or

    (c) is already present in archive.award_version, regardless of
        whether any etl_batch_item row exists for it at all. This
        closes a real gap found in production: this archive's initial
        ~5,000-family population was loaded before/outside the batch
        framework (direct --load-award-id calls, or a pre-batch-
        framework bulk load), so those award_ids have no etl_batch_item
        history whatsoever - (a)/(b) alone let a later --create-batch
        reselect them for free, producing a batch that looks fine
        (task exit 0, no missing_in_oracle) but adds zero new families
        (inserted=0, unchanged=<already-archived total>). Checking
        archive.award_version directly makes selection archive-aware
        instead of relying solely on batch-tracking history, so this
        never matters again regardless of how an award_id first got
        archived. Safe to check by award_id specifically (not
        award_number): --load-award-id always widens to load a
        family's EVERY award_id in one pass (see its own docstring), so
        if any award_id of a family is archived, every sibling award_id
        of that same family is guaranteed to be archived too - a bare
        per-award_id existence check is already family-complete.

    Deliberately does NOT exclude FAILED or PENDING items belonging to
    an already-resolved batch (COMPLETED/FAILED/PARTIAL/ABANDONED) -
    the entire point of production selection is that an award_id which
    never successfully completed remains eligible for a later batch to
    pick up again, not permanently skipped. Read-only; touches only
    archive.etl_batch/etl_batch_item/award_version, never Oracle. Only
    affects --create-batch's selection - --load-award-id and
    --load-batch remain fully callable against an already-archived
    award_id regardless of this exclusion set (both are idempotent
    UPSERTs by design, used deliberately for backfills/reloads)."""
    with engine.connect() as connection:
        batch_tracked_rows = connection.execute(
            text(
                """
                SELECT DISTINCT ebi.entity_key
                FROM archive.etl_batch_item ebi
                JOIN archive.etl_batch eb ON eb.batch_id = ebi.batch_id
                WHERE eb.domain = :domain
                  AND eb.entity_type = :entity_type
                  AND (
                        ebi.status = :completed_status
                        OR eb.status IN (:ready_status, :processing_status)
                  )
                """
            ),
            {
                "domain": AWARD_BATCH_DOMAIN,
                "entity_type": AWARD_BATCH_ENTITY_TYPE,
                "completed_status": batch_framework.ITEM_STATUS_COMPLETED,
                "ready_status": batch_framework.BATCH_STATUS_READY,
                "processing_status": batch_framework.BATCH_STATUS_PROCESSING,
            },
        ).scalars()
        already_archived_rows = connection.execute(
            text("SELECT DISTINCT award_id FROM archive.award_version")
        ).scalars()
        return {int(value) for value in batch_tracked_rows} | {
            int(value) for value in already_archived_rows
        }


def _run_create_award_batch(
    engine: Engine,
    requested_size: int,
    *,
    validation_overlap: bool = False,
    run_id: str | None = None,
) -> dict[str, Any]:
    """--create-batch: select exactly `requested_size` distinct award_ids,
    in stable ascending award_id order, and persist that exact membership
    as a new batch via the generic batch framework
    (archive.etl_batch/etl_batch_item).

    Two selection modes:

    - Production (default, validation_overlap=False): excludes every
      award_id already COMPLETED in a prior batch, every award_id
      currently claimed by a still-active (READY/PROCESSING) batch, AND
      every award_id already present in archive.award_version regardless
      of batch-tracking history (see
      _excluded_completed_and_active_award_ids - this last exclusion is
      what makes selection archive-aware rather than trusting
      etl_batch_item alone, closing a real gap where this archive's
      initial population, loaded before/outside the batch framework,
      had no batch-tracking history to exclude it by) - so repeated
      `--create-batch N` calls advance through the Award population
      (batch 1: the smallest N eligible award_ids; batch 2: the next N
      after excluding batch 1's now-COMPLETED items; and so on) instead
      of reselecting the same smallest N every time. Uses
      AWARD_IDS_ASCENDING_ORACLE_SQL (ORDER BY AWARD_ID) via the generic
      batch_framework.select_distinct_ascending_from_oracle_batches,
      which stops scanning as soon as `requested_size` non-excluded
      distinct award_ids are found - the same early-stop pattern Award
      Attachment's own _run_create_batch already uses for FILE_ID - so a
      production call never loads the entire Oracle Award population
      into memory.
    - Validation/testing (validation_overlap=True): the original
      always-smallest-N-award_ids behavior (see
      _select_award_ids_ascending), preserved unchanged and documented
      in AWARD_IMPLEMENTATION_ROADMAP.md as intentionally overlapping,
      useful for repeat-idempotency checks at increasing scale - never
      excludes anything, always does a full Oracle scan.

    Unlike Award Attachment, there is no "already uploaded"/BLOB/S3
    concept anywhere in this domain - "already loaded" here means
    "COMPLETED as an etl_batch_item", checked entirely in PostgreSQL,
    never against Oracle. Raises ValueError for a non-positive
    requested_size, before touching Oracle or PostgreSQL."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    if validation_overlap:
        selected_award_ids = _select_award_ids_ascending(
            OracleDataSource(VERSIONS_ORACLE_SQL), requested_size
        )
        selection_strategy = "ORACLE_SCAN_ASCENDING_AWARD_ID_VALIDATION_OVERLAP"
    else:
        excluded_award_ids = _excluded_completed_and_active_award_ids(engine)
        selected_award_ids = batch_framework.select_distinct_ascending_from_oracle_batches(
            OracleDataSource(AWARD_IDS_ASCENDING_ORACLE_SQL).read_batches(),
            id_column="award_id",
            requested_size=requested_size,
            excluded=excluded_award_ids,
        )
        selection_strategy = "ORACLE_SCAN_ASCENDING_AWARD_ID_EXCL_COMPLETED"

    result = batch_framework.create_batch(
        engine,
        domain=AWARD_BATCH_DOMAIN,
        entity_type=AWARD_BATCH_ENTITY_TYPE,
        requested_size=requested_size,
        selection_strategy=selection_strategy,
        selected_keys=selected_award_ids,
        selection_parameters={"validation_overlap": validation_overlap},
        run_id=run_id,
    )

    return {
        "batch_id": result["batch_id"],
        "requested_size": result["requested_size"],
        "selected_count": result["selected_count"],
        "selected_award_ids": result["selected_keys"],
    }


def _run_load_award_batch(
    engine: Engine, batch_id: int, *, dry_run: bool = False, run_id: str | None = None
) -> dict[str, Any]:
    """--load-batch: idempotent bulk load for this batch's entire
    recorded award_id membership as ONE unit of work - this no longer
    loops over _run_load_award_id. Every one of the forty-eight Award
    tables is read from Oracle exactly ONCE for the whole batch
    (bind-variable WHERE ... IN pushdown, chunked at Oracle's
    1000-element IN-list limit - see OracleDataSource.read_filtered),
    instead of once per family: runtime now scales with the number of
    Oracle tables, not families x tables. See
    docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md "Bulk batch load
    refactor" for the full design record and local benchmark.
    subcontracting_budgeted_goals/hierarchy/tnm_document/
    transaction_detail/award_amount_transaction are read by
    AWARD_NUMBER rather than AWARD_ID (see
    docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md and
    docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md);
    pending_transaction/pending_transaction_extension have no bare
    AWARD_NUMBER column at all and are read via
    OracleDataSource.read_filtered_any_column (OR across
    SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER) - every one of these
    is still exactly ONE Oracle read for the whole batch, scoped to
    every distinct award_number in this batch together.

    Every award_number family requested (directly, or indirectly via a
    shared award_number with another batch member) is widened and
    reloaded together, exactly as _run_load_award_id does for one
    family - this reimplements that same family-widening UPSERT logic
    at batch scale directly rather than delegating to it, so the two
    functions no longer share a call relationship, only the same
    per-row upsert_* functions and the same prepare_* functions
    (called once across every family's rows together, not once per
    family - prepare_versions's own ranking logic is already scoped
    per award_number via groupby, so batching every family's rows into
    one call is equivalent to, not a change from, calling it once per
    family).

    The whole batch's UPSERTs are ONE Postgres transaction - "treat the
    batch as one unit of work" - a deliberate change from the old
    per-family-transaction design: a single bad row anywhere now rolls
    back every family in this batch, not just the families after it in
    iteration order. Batch membership itself, and each award_id's
    batch-item status update, remain separate, always-committed
    bookkeeping - unaffected by dry_run or by a load-transaction
    rollback, exactly as before."""
    load_logger = logger.bind(stage="load_award_batch", batch_id=batch_id, run_id=run_id)
    batch_started = time.perf_counter()

    with engine.connect() as connection:
        award_ids = batch_framework.load_batch_membership(
            connection,
            batch_id,
            domain=AWARD_BATCH_DOMAIN,
            entity_type=AWARD_BATCH_ENTITY_TYPE,
        )

    report = {
        "batch_id": batch_id,
        "requested_award_ids": len(award_ids),
        "families_loaded": 0,
        "inserted": 0,
        "updated": 0,
        "unchanged": 0,
        "amount_info_inserted": 0,
        "amount_info_updated": 0,
        "amount_info_unchanged": 0,
        "person_inserted": 0,
        "person_updated": 0,
        "person_unchanged": 0,
        "funding_proposal_inserted": 0,
        "funding_proposal_updated": 0,
        "funding_proposal_unchanged": 0,
        "custom_data_inserted": 0,
        "custom_data_updated": 0,
        "custom_data_unchanged": 0,
        "person_unit_inserted": 0,
        "person_unit_updated": 0,
        "person_unit_unchanged": 0,
        "person_credit_split_inserted": 0,
        "person_credit_split_updated": 0,
        "person_credit_split_unchanged": 0,
        "person_unit_credit_split_inserted": 0,
        "person_unit_credit_split_updated": 0,
        "person_unit_credit_split_unchanged": 0,
        "sponsor_term_inserted": 0,
        "sponsor_term_updated": 0,
        "sponsor_term_unchanged": 0,
        "report_term_inserted": 0,
        "report_term_updated": 0,
        "report_term_unchanged": 0,
        "report_term_recipient_inserted": 0,
        "report_term_recipient_updated": 0,
        "report_term_recipient_unchanged": 0,
        "sponsor_contact_inserted": 0,
        "sponsor_contact_updated": 0,
        "sponsor_contact_unchanged": 0,
        "unit_contact_inserted": 0,
        "unit_contact_updated": 0,
        "unit_contact_unchanged": 0,
        "notepad_inserted": 0,
        "notepad_updated": 0,
        "notepad_unchanged": 0,
        "closeout_inserted": 0,
        "closeout_updated": 0,
        "closeout_unchanged": 0,
        "payment_schedule_inserted": 0,
        "payment_schedule_updated": 0,
        "payment_schedule_unchanged": 0,
        "approved_subaward_inserted": 0,
        "approved_subaward_updated": 0,
        "approved_subaward_unchanged": 0,
        "cfda_inserted": 0,
        "cfda_updated": 0,
        "cfda_unchanged": 0,
        "cost_share_inserted": 0,
        "cost_share_updated": 0,
        "cost_share_unchanged": 0,
        "fanda_rate_inserted": 0,
        "fanda_rate_updated": 0,
        "fanda_rate_unchanged": 0,
        "science_keyword_inserted": 0,
        "science_keyword_updated": 0,
        "science_keyword_unchanged": 0,
        "special_review_inserted": 0,
        "special_review_updated": 0,
        "special_review_unchanged": 0,
        "special_review_exemption_inserted": 0,
        "special_review_exemption_updated": 0,
        "special_review_exemption_unchanged": 0,
        "approved_equipment_inserted": 0,
        "approved_equipment_updated": 0,
        "approved_equipment_unchanged": 0,
        "approved_foreign_travel_inserted": 0,
        "approved_foreign_travel_updated": 0,
        "approved_foreign_travel_unchanged": 0,
        "subcontracting_budgeted_goals_inserted": 0,
        "subcontracting_budgeted_goals_updated": 0,
        "subcontracting_budgeted_goals_unchanged": 0,
        "comment_inserted": 0,
        "comment_updated": 0,
        "comment_unchanged": 0,
        "extension_inserted": 0,
        "extension_updated": 0,
        "extension_unchanged": 0,
        "cgb_inserted": 0,
        "cgb_updated": 0,
        "cgb_unchanged": 0,
        "hierarchy_inserted": 0,
        "hierarchy_updated": 0,
        "hierarchy_unchanged": 0,
        "tnm_document_inserted": 0,
        "tnm_document_updated": 0,
        "tnm_document_unchanged": 0,
        "pending_transaction_inserted": 0,
        "pending_transaction_updated": 0,
        "pending_transaction_unchanged": 0,
        "pending_transaction_extension_inserted": 0,
        "pending_transaction_extension_updated": 0,
        "pending_transaction_extension_unchanged": 0,
        "transaction_detail_inserted": 0,
        "transaction_detail_updated": 0,
        "transaction_detail_unchanged": 0,
        "award_amount_transaction_inserted": 0,
        "award_amount_transaction_updated": 0,
        "award_amount_transaction_unchanged": 0,
        "fanda_distribution_inserted": 0,
        "fanda_distribution_updated": 0,
        "fanda_distribution_unchanged": 0,
        "budget_inserted": 0,
        "budget_updated": 0,
        "budget_unchanged": 0,
        "budget_limit_inserted": 0,
        "budget_limit_updated": 0,
        "budget_limit_unchanged": 0,
        "budget_period_inserted": 0,
        "budget_period_updated": 0,
        "budget_period_unchanged": 0,
        "budget_line_item_inserted": 0,
        "budget_line_item_updated": 0,
        "budget_line_item_unchanged": 0,
        "budget_period_summary_calculated_amount_inserted": 0,
        "budget_period_summary_calculated_amount_updated": 0,
        "budget_period_summary_calculated_amount_unchanged": 0,
        "budget_line_item_calculated_amount_inserted": 0,
        "budget_line_item_calculated_amount_updated": 0,
        "budget_line_item_calculated_amount_unchanged": 0,
        "budget_personnel_detail_inserted": 0,
        "budget_personnel_detail_updated": 0,
        "budget_personnel_detail_unchanged": 0,
        "budget_personnel_calculated_amount_inserted": 0,
        "budget_personnel_calculated_amount_updated": 0,
        "budget_personnel_calculated_amount_unchanged": 0,
        "budget_person_inserted": 0,
        "budget_person_updated": 0,
        "budget_person_unchanged": 0,
        "transferring_sponsor_inserted": 0,
        "transferring_sponsor_updated": 0,
        "transferring_sponsor_unchanged": 0,
        "award_transmission_inserted": 0,
        "award_transmission_updated": 0,
        "award_transmission_unchanged": 0,
        "award_transmission_child_inserted": 0,
        "award_transmission_child_updated": 0,
        "award_transmission_child_unchanged": 0,
        "missing_in_oracle": 0,
        "elapsed_ms": 0.0,
    }

    def _finish(missing_award_ids: list[int], completed_award_ids: list[int]) -> dict[str, Any]:
        with engine.begin() as connection:
            for award_id in missing_award_ids:
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    award_id,
                    status=batch_framework.ITEM_STATUS_MISSING_SOURCE,
                )
            for award_id in completed_award_ids:
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    award_id,
                    status=batch_framework.ITEM_STATUS_COMPLETED,
                )
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )

        report["elapsed_ms"] = (time.perf_counter() - batch_started) * 1000
        load_logger.info(
            "Batch Award load for batch_id={}{}: requested_award_ids={} "
            "families_loaded={} in {:.1f}ms version(inserted={} updated={} "
            "unchanged={}) missing_in_oracle={}",
            batch_id,
            " [DRY RUN - not persisted]" if dry_run else "",
            report["requested_award_ids"],
            report["families_loaded"],
            report["elapsed_ms"],
            report["inserted"],
            report["updated"],
            report["unchanged"],
            report["missing_in_oracle"],
        )
        return report

    if not award_ids:
        return _finish([], [])

    # Step 1 ("resolve all Award families first"): resolve every batch
    # member's award_number in one (chunked) set of Oracle round trips,
    # instead of one query per award_id.
    started = time.perf_counter()
    award_numbers_by_id = read_award_numbers_for_award_ids(
        OracleDataSource(VERSIONS_ORACLE_SQL), set(award_ids)
    )
    load_logger.info(
        "Resolved {} of {} requested award_id(s) to award_number in {:.1f}ms",
        len(award_numbers_by_id),
        len(award_ids),
        (time.perf_counter() - started) * 1000,
    )

    missing_award_ids = [
        award_id for award_id in award_ids if award_id not in award_numbers_by_id
    ]
    completed_award_ids = [
        award_id for award_id in award_ids if award_id in award_numbers_by_id
    ]
    distinct_award_numbers: set[str] = set(award_numbers_by_id.values())
    report["missing_in_oracle"] = len(missing_award_ids)
    report["families_loaded"] = len(distinct_award_numbers)

    if not distinct_award_numbers:
        return _finish(missing_award_ids, completed_award_ids)

    # Step 2 ("resolve the complete Award family once"): resolve every
    # distinct award_number's entire version family in one (chunked)
    # Oracle round trip, for every family in this batch together.
    versions = prepare_versions(
        read_award_versions_matching_award_numbers(
            OracleDataSource(VERSIONS_ORACLE_SQL), distinct_award_numbers
        )
    )
    family_award_ids: set[int] = set(
        versions["award_id"].dropna().astype("int64").tolist()
    )

    # Step 3 ("query each child table only for that family's award_id
    # values", now for every family in the batch at once): read every
    # child table exactly once, scoped to the union of every family's
    # award_ids in this batch.
    def _read_and_prepare(
        sql_path: Path, prepare: Callable[[pd.DataFrame], pd.DataFrame]
    ) -> pd.DataFrame:
        raw = read_award_children_matching_award_ids(
            OracleDataSource(sql_path), family_award_ids
        )
        return prepare(raw) if not raw.empty else raw

    amounts = _read_and_prepare(AMOUNTS_ORACLE_SQL, prepare_amounts)
    people = _read_and_prepare(PEOPLE_ORACLE_SQL, prepare_people)
    proposals = _read_and_prepare(PROPOSALS_ORACLE_SQL, prepare_proposals)
    custom_data = _read_and_prepare(CUSTOM_DATA_ORACLE_SQL, prepare_custom_data)
    person_units = _read_and_prepare(PERSON_UNITS_ORACLE_SQL, prepare_person_units)
    person_credit_splits = _read_and_prepare(
        PERSON_CREDIT_SPLITS_ORACLE_SQL, prepare_person_credit_splits
    )
    person_unit_credit_splits = _read_and_prepare(
        PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL, prepare_person_unit_credit_splits
    )
    sponsor_terms = _read_and_prepare(SPONSOR_TERMS_ORACLE_SQL, prepare_sponsor_terms)
    report_terms = _read_and_prepare(REPORT_TERMS_ORACLE_SQL, prepare_report_terms)
    report_term_recipients = _read_and_prepare(
        REPORT_TERM_RECIPIENTS_ORACLE_SQL, prepare_report_term_recipients
    )
    sponsor_contacts = _read_and_prepare(
        SPONSOR_CONTACTS_ORACLE_SQL, prepare_sponsor_contacts
    )
    unit_contacts = _read_and_prepare(UNIT_CONTACTS_ORACLE_SQL, prepare_unit_contacts)
    notepad = _read_and_prepare(NOTEPAD_ORACLE_SQL, prepare_notepad)
    closeout = _read_and_prepare(CLOSEOUT_ORACLE_SQL, prepare_closeout)
    payment_schedule = _read_and_prepare(
        PAYMENT_SCHEDULE_ORACLE_SQL, prepare_payment_schedule
    )
    approved_subaward = _read_and_prepare(
        APPROVED_SUBAWARD_ORACLE_SQL, prepare_approved_subaward
    )
    cfda = _read_and_prepare(CFDA_ORACLE_SQL, prepare_cfda)
    cost_share = _read_and_prepare(COST_SHARE_ORACLE_SQL, prepare_cost_share)
    fanda_rate = _read_and_prepare(FANDA_RATE_ORACLE_SQL, prepare_fanda_rate)
    science_keyword = _read_and_prepare(
        SCIENCE_KEYWORD_ORACLE_SQL, prepare_science_keyword
    )
    special_review = _read_and_prepare(
        SPECIAL_REVIEW_ORACLE_SQL, prepare_special_review
    )
    special_review_exemption = _read_and_prepare(
        SPECIAL_REVIEW_EXEMPTION_ORACLE_SQL, prepare_special_review_exemption
    )
    approved_equipment = _read_and_prepare(
        APPROVED_EQUIPMENT_ORACLE_SQL, prepare_approved_equipment
    )
    approved_foreign_travel = _read_and_prepare(
        APPROVED_FOREIGN_TRAVEL_ORACLE_SQL, prepare_approved_foreign_travel
    )

    # subcontracting_budgeted_goals is read by AWARD_NUMBER, not
    # AWARD_ID - the one table in this schema with that shape (see
    # docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md) -
    # still exactly one Oracle read for the whole batch, scoped to
    # every distinct award_number already resolved above.
    subcontracting_budgeted_goals_raw = read_award_children_matching_award_numbers(
        OracleDataSource(SUBCONTRACTING_BUDGETED_GOALS_ORACLE_SQL),
        distinct_award_numbers,
    )
    subcontracting_budgeted_goals = (
        prepare_subcontracting_budgeted_goals(subcontracting_budgeted_goals_raw)
        if not subcontracting_budgeted_goals_raw.empty
        else subcontracting_budgeted_goals_raw
    )
    comment = _read_and_prepare(COMMENT_ORACLE_SQL, prepare_award_comments)
    extension = _read_and_prepare(EXTENSION_ORACLE_SQL, prepare_award_extension)
    cgb = _read_and_prepare(CGB_ORACLE_SQL, prepare_award_cgb)

    # hierarchy/tnm_document/transaction_detail/award_amount_transaction
    # all carry a native AWARD_NUMBER column - read via
    # read_award_children_matching_award_numbers, the same shape as
    # subcontracting_budgeted_goals above, still exactly one Oracle read
    # per table for the whole batch.
    hierarchy_raw = read_award_children_matching_award_numbers(
        OracleDataSource(HIERARCHY_ORACLE_SQL), distinct_award_numbers
    )
    hierarchy = (
        prepare_award_hierarchy(hierarchy_raw)
        if not hierarchy_raw.empty
        else hierarchy_raw
    )

    tnm_document_raw = read_award_children_matching_award_numbers(
        OracleDataSource(TIME_AND_MONEY_DOCUMENT_ORACLE_SQL), distinct_award_numbers
    )
    tnm_document = (
        prepare_time_and_money_document(tnm_document_raw)
        if not tnm_document_raw.empty
        else tnm_document_raw
    )

    # pending_transaction/pending_transaction_extension have no bare
    # AWARD_NUMBER column - only SOURCE_AWARD_NUMBER/
    # DESTINATION_AWARD_NUMBER - read via read_filtered_any_column
    # (OR across both columns), still exactly one Oracle read per table
    # for the whole batch, not two.
    pending_transaction_raw = read_pending_transactions_matching_award_numbers(
        OracleDataSource(PENDING_TRANSACTION_ORACLE_SQL), distinct_award_numbers
    )
    pending_transaction = (
        prepare_pending_transaction(pending_transaction_raw)
        if not pending_transaction_raw.empty
        else pending_transaction_raw
    )

    pending_transaction_extension_raw = read_pending_transactions_matching_award_numbers(
        OracleDataSource(PENDING_TRANSACTION_EXTENSION_ORACLE_SQL),
        distinct_award_numbers,
    )
    pending_transaction_extension = (
        prepare_pending_transaction_extension(pending_transaction_extension_raw)
        if not pending_transaction_extension_raw.empty
        else pending_transaction_extension_raw
    )

    transaction_detail_raw = read_award_children_matching_award_numbers(
        OracleDataSource(TRANSACTION_DETAIL_ORACLE_SQL), distinct_award_numbers
    )
    transaction_detail = (
        prepare_transaction_detail(transaction_detail_raw)
        if not transaction_detail_raw.empty
        else transaction_detail_raw
    )

    award_amount_transaction_raw = read_award_children_matching_award_numbers(
        OracleDataSource(AWARD_AMOUNT_TRANSACTION_ORACLE_SQL), distinct_award_numbers
    )
    award_amount_transaction = (
        prepare_award_amount_transaction(award_amount_transaction_raw)
        if not award_amount_transaction_raw.empty
        else award_amount_transaction_raw
    )

    fanda_distribution = _read_and_prepare(
        AWARD_DIRECT_FANDA_DISTRIBUTION_ORACLE_SQL,
        prepare_award_direct_fanda_distribution,
    )

    budget = _read_and_prepare(BUDGET_ORACLE_SQL, prepare_award_budget)
    budget_limit = _read_and_prepare(BUDGET_LIMIT_ORACLE_SQL, prepare_award_budget_limit)
    budget_period = _read_and_prepare(
        BUDGET_PERIOD_ORACLE_SQL, prepare_award_budget_period
    )
    budget_line_item = _read_and_prepare(
        BUDGET_LINE_ITEM_ORACLE_SQL, prepare_award_budget_line_item
    )
    budget_period_summary_calculated_amount = _read_and_prepare(
        BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ORACLE_SQL,
        prepare_award_budget_period_summary_calculated_amount,
    )
    budget_line_item_calculated_amount = _read_and_prepare(
        BUDGET_LINE_ITEM_CALCULATED_AMOUNT_ORACLE_SQL,
        prepare_award_budget_line_item_calculated_amount,
    )
    budget_personnel_detail = _read_and_prepare(
        BUDGET_PERSONNEL_DETAIL_ORACLE_SQL, prepare_award_budget_personnel_detail
    )
    budget_personnel_calculated_amount = _read_and_prepare(
        BUDGET_PERSONNEL_CALCULATED_AMOUNT_ORACLE_SQL,
        prepare_award_budget_personnel_calculated_amount,
    )
    budget_person = _read_and_prepare(
        BUDGET_PERSON_ORACLE_SQL, prepare_award_budget_person
    )
    transferring_sponsor = _read_and_prepare(
        TRANSFERRING_SPONSOR_ORACLE_SQL, prepare_award_transferring_sponsor
    )
    award_transmission = _read_and_prepare(
        AWARD_TRANSMISSION_ORACLE_SQL, prepare_award_transmission
    )
    award_transmission_child = _read_and_prepare(
        AWARD_TRANSMISSION_CHILD_ORACLE_SQL, prepare_award_transmission_child
    )

    # Step 4 ("build in-memory dictionaries keyed by award_id"): one
    # winning (primary-current) award_id per award_number, computed
    # once from the batch-wide versions dataframe.
    primary_rows = versions.loc[versions["is_primary_current"] == True]  # noqa: E712
    winning_award_id_by_number: dict[str, int] = dict(
        zip(
            primary_rows["award_number"],
            primary_rows["award_id"].astype("int64"),
            strict=True,
        )
    )

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            total_rows = (
                len(versions)
                + len(amounts)
                + len(people)
                + len(proposals)
                + len(custom_data)
                + len(person_units)
                + len(person_credit_splits)
                + len(person_unit_credit_splits)
                + len(sponsor_terms)
                + len(report_terms)
                + len(report_term_recipients)
                + len(sponsor_contacts)
                + len(unit_contacts)
                + len(notepad)
                + len(closeout)
                + len(payment_schedule)
                + len(approved_subaward)
                + len(cfda)
                + len(cost_share)
                + len(fanda_rate)
                + len(science_keyword)
                + len(special_review)
                + len(special_review_exemption)
                + len(approved_equipment)
                + len(approved_foreign_travel)
                + len(subcontracting_budgeted_goals)
                + len(comment)
                + len(extension)
                + len(cgb)
                + len(hierarchy)
                + len(tnm_document)
                + len(pending_transaction)
                + len(pending_transaction_extension)
                + len(transaction_detail)
                + len(award_amount_transaction)
                + len(fanda_distribution)
                + len(budget)
                + len(budget_limit)
                + len(budget_period)
                + len(budget_line_item)
                + len(budget_period_summary_calculated_amount)
                + len(budget_line_item_calculated_amount)
                + len(budget_personnel_detail)
                + len(budget_personnel_calculated_amount)
                + len(budget_person)
                + len(transferring_sponsor)
                + len(award_transmission)
                + len(award_transmission_child)
            )
            load_id = create_load_run(connection, total_rows)

            # Clear every family's stale is_primary_current flag before
            # any per-row UPSERT below might set a *different* row to
            # TRUE - see the module-level comment on
            # ux_award_one_primary_current for why ordering matters.
            # One UPDATE per distinct award_number (executemany-style,
            # a single connection.execute() call with a list of
            # parameter dicts) - still pure Postgres work, not an
            # Oracle round trip, so doing it per-family here does not
            # reintroduce the families x tables scaling problem this
            # refactor removes.
            connection.execute(
                text(
                    "UPDATE archive.award_version SET is_primary_current = FALSE "
                    "WHERE award_number = :award_number AND is_primary_current = TRUE "
                    "AND award_id IS DISTINCT FROM :winning_award_id"
                ),
                [
                    {
                        "award_number": award_number,
                        "winning_award_id": winning_award_id_by_number.get(
                            award_number
                        ),
                    }
                    for award_number in distinct_award_numbers
                ],
            )

            # Step 5 ("perform bulk UPSERTs table by table"): every
            # table's rows, across every family in the batch together -
            # not grouped or re-looped by family.
            for _, version_row in versions.iterrows():
                result = upsert_award_version(connection, version_row, load_id)
                report[result] += 1

            for _, amount_row in amounts.iterrows():
                result = upsert_award_amount_info(connection, amount_row, load_id)
                report[f"amount_info_{result}"] += 1

            for _, person_row in people.iterrows():
                result = upsert_award_person(connection, person_row, load_id)
                report[f"person_{result}"] += 1

            for _, proposal_row in proposals.iterrows():
                result = upsert_award_funding_proposal(
                    connection, proposal_row, load_id
                )
                report[f"funding_proposal_{result}"] += 1

            for _, custom_data_row in custom_data.iterrows():
                result = upsert_award_custom_data(
                    connection, custom_data_row, load_id
                )
                report[f"custom_data_{result}"] += 1

            # person_unit before person_unit_credit_split (its FK
            # parent) - see docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md.
            for _, person_unit_row in person_units.iterrows():
                result = upsert_award_person_unit(
                    connection, person_unit_row, load_id
                )
                report[f"person_unit_{result}"] += 1

            for _, person_unit_credit_split_row in person_unit_credit_splits.iterrows():
                result = upsert_award_person_unit_credit_split(
                    connection, person_unit_credit_split_row, load_id
                )
                report[f"person_unit_credit_split_{result}"] += 1

            for _, person_credit_split_row in person_credit_splits.iterrows():
                result = upsert_award_person_credit_split(
                    connection, person_credit_split_row, load_id
                )
                report[f"person_credit_split_{result}"] += 1

            for _, sponsor_term_row in sponsor_terms.iterrows():
                result = upsert_award_sponsor_term(
                    connection, sponsor_term_row, load_id
                )
                report[f"sponsor_term_{result}"] += 1

            # report_term before report_term_recipient (its FK parent) -
            # see docs/architecture/AWARD_TERMS_DESIGN.md.
            for _, report_term_row in report_terms.iterrows():
                result = upsert_award_report_term(
                    connection, report_term_row, load_id
                )
                report[f"report_term_{result}"] += 1

            for _, report_term_recipient_row in report_term_recipients.iterrows():
                result = upsert_award_report_term_recipient(
                    connection, report_term_recipient_row, load_id
                )
                report[f"report_term_recipient_{result}"] += 1

            for _, sponsor_contact_row in sponsor_contacts.iterrows():
                result = upsert_award_sponsor_contact(
                    connection, sponsor_contact_row, load_id
                )
                report[f"sponsor_contact_{result}"] += 1

            for _, unit_contact_row in unit_contacts.iterrows():
                result = upsert_award_unit_contact(
                    connection, unit_contact_row, load_id
                )
                report[f"unit_contact_{result}"] += 1

            for _, notepad_row in notepad.iterrows():
                result = upsert_award_notepad(connection, notepad_row, load_id)
                report[f"notepad_{result}"] += 1

            for _, closeout_row in closeout.iterrows():
                result = upsert_award_closeout(connection, closeout_row, load_id)
                report[f"closeout_{result}"] += 1

            for _, payment_schedule_row in payment_schedule.iterrows():
                result = upsert_award_payment_schedule(
                    connection, payment_schedule_row, load_id
                )
                report[f"payment_schedule_{result}"] += 1

            for _, approved_subaward_row in approved_subaward.iterrows():
                result = upsert_award_approved_subaward(
                    connection, approved_subaward_row, load_id
                )
                report[f"approved_subaward_{result}"] += 1

            for _, cfda_row in cfda.iterrows():
                result = upsert_award_cfda(connection, cfda_row, load_id)
                report[f"cfda_{result}"] += 1

            for _, cost_share_row in cost_share.iterrows():
                result = upsert_award_cost_share(connection, cost_share_row, load_id)
                report[f"cost_share_{result}"] += 1

            for _, fanda_rate_row in fanda_rate.iterrows():
                result = upsert_award_fanda_rate(connection, fanda_rate_row, load_id)
                report[f"fanda_rate_{result}"] += 1

            for _, science_keyword_row in science_keyword.iterrows():
                result = upsert_award_science_keyword(
                    connection, science_keyword_row, load_id
                )
                report[f"science_keyword_{result}"] += 1

            # special_review before special_review_exemption (its FK
            # parent) - see
            # docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
            for _, special_review_row in special_review.iterrows():
                result = upsert_award_special_review(
                    connection, special_review_row, load_id
                )
                report[f"special_review_{result}"] += 1

            for _, special_review_exemption_row in special_review_exemption.iterrows():
                result = upsert_award_special_review_exemption(
                    connection, special_review_exemption_row, load_id
                )
                report[f"special_review_exemption_{result}"] += 1

            for _, approved_equipment_row in approved_equipment.iterrows():
                result = upsert_award_approved_equipment(
                    connection, approved_equipment_row, load_id
                )
                report[f"approved_equipment_{result}"] += 1

            for _, approved_foreign_travel_row in approved_foreign_travel.iterrows():
                result = upsert_award_approved_foreign_travel(
                    connection, approved_foreign_travel_row, load_id
                )
                report[f"approved_foreign_travel_{result}"] += 1

            for _, subcontracting_row in subcontracting_budgeted_goals.iterrows():
                result = upsert_award_subcontracting_budgeted_goals(
                    connection, subcontracting_row, load_id
                )
                report[f"subcontracting_budgeted_goals_{result}"] += 1

            for _, comment_row in comment.iterrows():
                result = upsert_award_comments(connection, comment_row, load_id)
                report[f"comment_{result}"] += 1

            for _, extension_row in extension.iterrows():
                result = upsert_award_extension(connection, extension_row, load_id)
                report[f"extension_{result}"] += 1

            for _, cgb_row in cgb.iterrows():
                result = upsert_award_cgb(connection, cgb_row, load_id)
                report[f"cgb_{result}"] += 1

            for _, hierarchy_row in hierarchy.iterrows():
                result = upsert_award_hierarchy(connection, hierarchy_row, load_id)
                report[f"hierarchy_{result}"] += 1

            for _, tnm_document_row in tnm_document.iterrows():
                result = upsert_time_and_money_document(
                    connection, tnm_document_row, load_id
                )
                report[f"tnm_document_{result}"] += 1

            for _, pending_transaction_row in pending_transaction.iterrows():
                result = upsert_pending_transaction(
                    connection, pending_transaction_row, load_id
                )
                report[f"pending_transaction_{result}"] += 1

            for _, pte_row in pending_transaction_extension.iterrows():
                result = upsert_pending_transaction_extension(
                    connection, pte_row, load_id
                )
                report[f"pending_transaction_extension_{result}"] += 1

            for _, transaction_detail_row in transaction_detail.iterrows():
                result = upsert_transaction_detail(
                    connection, transaction_detail_row, load_id
                )
                report[f"transaction_detail_{result}"] += 1

            for _, aat_row in award_amount_transaction.iterrows():
                result = upsert_award_amount_transaction(
                    connection, aat_row, load_id
                )
                report[f"award_amount_transaction_{result}"] += 1

            for _, fanda_row in fanda_distribution.iterrows():
                result = upsert_award_direct_fanda_distribution(
                    connection, fanda_row, load_id
                )
                report[f"fanda_distribution_{result}"] += 1

            for _, budget_row in budget.iterrows():
                result = upsert_award_budget(connection, budget_row, load_id)
                report[f"budget_{result}"] += 1

            for _, budget_limit_row in budget_limit.iterrows():
                result = upsert_award_budget_limit(
                    connection, budget_limit_row, load_id
                )
                report[f"budget_limit_{result}"] += 1

            for _, budget_period_row in budget_period.iterrows():
                result = upsert_award_budget_period(
                    connection, budget_period_row, load_id
                )
                report[f"budget_period_{result}"] += 1

            for _, budget_line_item_row in budget_line_item.iterrows():
                result = upsert_award_budget_line_item(
                    connection, budget_line_item_row, load_id
                )
                report[f"budget_line_item_{result}"] += 1

            for (
                _,
                summary_row,
            ) in budget_period_summary_calculated_amount.iterrows():
                result = upsert_award_budget_period_summary_calculated_amount(
                    connection, summary_row, load_id
                )
                report[f"budget_period_summary_calculated_amount_{result}"] += 1

            for (
                _,
                line_item_cal_row,
            ) in budget_line_item_calculated_amount.iterrows():
                result = upsert_award_budget_line_item_calculated_amount(
                    connection, line_item_cal_row, load_id
                )
                report[f"budget_line_item_calculated_amount_{result}"] += 1

            for (
                _,
                personnel_detail_row,
            ) in budget_personnel_detail.iterrows():
                result = upsert_award_budget_personnel_detail(
                    connection, personnel_detail_row, load_id
                )
                report[f"budget_personnel_detail_{result}"] += 1

            for (
                _,
                personnel_cal_row,
            ) in budget_personnel_calculated_amount.iterrows():
                result = upsert_award_budget_personnel_calculated_amount(
                    connection, personnel_cal_row, load_id
                )
                report[f"budget_personnel_calculated_amount_{result}"] += 1

            for _, budget_person_row in budget_person.iterrows():
                result = upsert_award_budget_person(
                    connection, budget_person_row, load_id
                )
                report[f"budget_person_{result}"] += 1

            for (
                _,
                transferring_sponsor_row,
            ) in transferring_sponsor.iterrows():
                result = upsert_award_transferring_sponsor(
                    connection, transferring_sponsor_row, load_id
                )
                report[f"transferring_sponsor_{result}"] += 1

            for (
                _,
                award_transmission_row,
            ) in award_transmission.iterrows():
                result = upsert_award_transmission(
                    connection, award_transmission_row, load_id
                )
                report[f"award_transmission_{result}"] += 1

            for (
                _,
                award_transmission_child_row,
            ) in award_transmission_child.iterrows():
                result = upsert_award_transmission_child(
                    connection, award_transmission_child_row, load_id
                )
                report[f"award_transmission_child_{result}"] += 1

            mark_load_complete(connection, load_id, total_rows)
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    return _finish(missing_award_ids, completed_award_ids)


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Award versions/amounts/people/proposals from Oracle. "
            "Unit contacts are not loaded - see docs/DECISIONS.md."
        )
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help=(
            "Truncate every dataset to at most this many rows after "
            "reading, skip cross-dataset validation, and skip the "
            "database write entirely (a bounded dry run for testing "
            "connectivity/transform logic - not a partial load)."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "With --load-award-id or --load-batch: every UPSERT still "
            "runs (so reported counts are accurate) but the transaction "
            "is rolled back instead of committed. Has no effect on the "
            "full load or --create-batch/--show-batch."
        ),
    )
    parser.add_argument(
        "--load-award-id",
        type=int,
        default=None,
        metavar="AWARD_ID",
        help=(
            "Idempotent incremental UPSERT for exactly one award_id's "
            "entire award_number version family (not just that one "
            "award_id - see the module docstring above parse_args for "
            "why) plus its amount_info/person/funding_proposal/"
            "custom_data/person_unit/person_credit_split/"
            "person_unit_credit_split/sponsor_term/report_term/"
            "report_term_recipient/sponsor_contact/unit_contact/notepad/"
            "closeout/payment_schedule/approved_subaward/cfda/"
            "cost_share/fanda_rate/science_keyword/special_review/"
            "special_review_exemption/approved_equipment/"
            "approved_foreign_travel/subcontracting_budgeted_goals/"
            "comment/extension/cgb/hierarchy/tnm_document/"
            "pending_transaction/pending_transaction_extension/"
            "transaction_detail/award_amount_transaction/"
            "fanda_distribution/budget/budget_limit/budget_period/"
            "budget_line_item/budget_period_summary_calculated_amount/"
            "budget_line_item_calculated_amount/"
            "budget_personnel_detail/"
            "budget_personnel_calculated_amount/budget_person/"
            "transferring_sponsor/award_transmission/"
            "award_transmission_child child rows. Never "
            "truncates or replaces the full tables. Scoped strictly "
            "to these forty-eight tables."
        ),
    )
    parser.add_argument(
        "--create-batch",
        type=int,
        default=None,
        metavar="N",
        help=(
            "Select exactly N distinct award_ids, in stable ascending "
            "award_id order, and persist that exact membership as a new "
            "batch (archive.etl_batch/etl_batch_item, the generic ETL "
            "batch framework shared with Award Attachment). By default "
            "(production mode), excludes award_ids already COMPLETED in "
            "a prior batch and award_ids claimed by a still-active "
            "READY/PROCESSING batch, so repeated calls advance through "
            "the population instead of reselecting the same N every "
            "time - see --validation-overlap for the old behavior. N "
            "must be positive."
        ),
    )
    parser.add_argument(
        "--validation-overlap",
        action="store_true",
        help=(
            "Valid only with --create-batch. Selects the smallest N "
            "award_ids every time, with no exclusion of prior batches - "
            "the original --create-batch behavior, intentionally "
            "overlapping across increasing scales (see "
            "AWARD_IMPLEMENTATION_ROADMAP.md), useful for repeat-"
            "idempotency validation. Never use this for ongoing "
            "production loading - it will keep reselecting and "
            "reprocessing the same low-numbered award_ids."
        ),
    )
    parser.add_argument(
        "--load-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "Idempotent incremental load for exactly this batch's "
            "recorded award_id membership - the batch equivalent of "
            "--load-award-id."
        ),
    )
    parser.add_argument(
        "--show-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "Read-only status report for one batch: requested_size, "
            "status, and item counts by load state. Never writes "
            "anything."
        ),
    )
    parser.add_argument(
        "--diff-award-versions",
        type=str,
        default=None,
        metavar="AWARD_NUMBER",
        help=(
            "Developer/investigation aid, not a production feature: "
            "read-only side-by-side comparison of Oracle's AWARD rows "
            "for exactly this award_number family against "
            "archive.award_version, explaining per-sequence whether it "
            "is archived at all and whether its modification_number "
            "('document number') value matches Oracle. Reads Oracle (a "
            "targeted bind-variable filter, not a full-table scan) and "
            "PostgreSQL - requires ORACLE_SECRET_ID. Never writes "
            "anything."
        ),
    )
    parser.add_argument(
        "--investigate-workflow-document-number",
        type=str,
        default=None,
        metavar="AWARD_NUMBER",
        help=(
            "Schema-investigation aid, NOT a production feature and NOT "
            "yet wired to PostgreSQL/the archive: confirms whether "
            "AWARD.DOCUMENT_NUMBER, KREW_DOC_HDR_T, and KREW_DOC_TYP_T "
            "exist and are reachable (with their real column names/"
            "datatypes) in BU's actual Oracle schema, then - only if so "
            "- runs the proposed AWARD.DOCUMENT_NUMBER -> "
            "KREW_DOC_HDR_T.DOC_HDR_ID join for exactly this "
            "award_number family. Never writes anything."
        ),
    )
    parser.add_argument(
        "--load-unit-reference-data",
        action="store_true",
        help=(
            "Loads the shared reference-data entities backing Award "
            "Contacts (archive.unit, unit_administrator, "
            "unit_administrator_type, rolodex, person) - full loads for "
            "the first four (each small/bounded on BU's real Oracle: "
            "~5.1K/~1K/11/~12.5K rows), a targeted Rice-KIM read for "
            "person (scoped to person_ids already referenced by "
            "unit_administrator/award_unit_contact, never a full scan). "
            "Idempotent - combine with --dry-run to roll back. See "
            "docs/architecture/AWARD_CONTACTS_DESIGN.md."
        ),
    )
    parser.add_argument(
        "--load-comment-type-reference-data",
        action="store_true",
        help=(
            "Loads archive.comment_type - Oracle's COMMENT_TYPE lookup "
            "table, the real FK target of "
            "archive.award_comment.comment_type_code. A small, bounded "
            "full reference-data load, independent of the Unit/Person "
            "reference bundle (--load-unit-reference-data). Idempotent "
            "- combine with --dry-run to roll back. See "
            "docs/architecture/AWARD_COMMENT_DESIGN.md."
        ),
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Production execution mode for the ECS loader task: resolve "
            "PostgreSQL/Oracle credentials from AWS Secrets Manager only "
            "(POSTGRES_SECRET_ID/ORACLE_SECRET_ID - never a plaintext "
            "environment variable, never a local .env export), switch to "
            "structured JSON logging for CloudWatch, and run startup "
            "validation (AWS identity, secrets, PostgreSQL/Oracle "
            "reachable) before processing anything - aborts immediately "
            "on any failure. Requires the schema to already exist - see "
            "--migrate-only to bootstrap a fresh database. Local "
            "execution (no --ecs) is completely unaffected: it continues "
            "reading POSTGRES_*/ORACLE_* directly from the environment, "
            "exactly as before."
        ),
    )
    parser.add_argument(
        "--migrate-only",
        action="store_true",
        help=(
            "Valid only with --ecs. Resolve AWS identity and the "
            "PostgreSQL secret, verify PostgreSQL connectivity, apply "
            "pending database migrations, validate the resulting schema, "
            "then exit successfully - without ever touching Oracle or "
            "any Award data. Use this once to bootstrap a fresh "
            "database; every other --ecs invocation requires migrations "
            "to already be applied and never applies them itself."
        ),
    )
    parsed = parser.parse_args(arguments)

    if parsed.migrate_only and not parsed.ecs:
        parser.error("--migrate-only is only valid together with --ecs")

    batch_verbs = [
        ("--create-batch", parsed.create_batch),
        ("--load-batch", parsed.load_batch),
        ("--show-batch", parsed.show_batch),
    ]
    active_batch_verbs = [name for name, value in batch_verbs if value is not None]
    if len(active_batch_verbs) > 1:
        parser.error(
            f"{' and '.join(active_batch_verbs)} cannot be combined - "
            "choose one batch operation at a time"
        )
    if parsed.create_batch is not None and parsed.create_batch <= 0:
        parser.error("--create-batch must be a positive integer")
    if active_batch_verbs and parsed.load_award_id is not None:
        parser.error(
            f"{active_batch_verbs[0]} cannot be combined with "
            "--load-award-id"
        )
    if parsed.migrate_only and active_batch_verbs:
        parser.error(
            f"--migrate-only cannot be combined with {active_batch_verbs[0]}"
        )
    if parsed.migrate_only and parsed.load_award_id is not None:
        parser.error("--migrate-only cannot be combined with --load-award-id")
    if parsed.validation_overlap and parsed.create_batch is None:
        parser.error("--validation-overlap is only valid together with --create-batch")

    return parsed


def main() -> None:
    arguments = parse_args()
    run_id = str(uuid.uuid4())

    if arguments.ecs:
        ecs_setup_short_circuited = _run_ecs_setup(arguments, run_id)
        if ecs_setup_short_circuited:
            return

    if arguments.create_batch is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_create_award_batch(
            engine,
            arguments.create_batch,
            validation_overlap=arguments.validation_overlap,
            run_id=run_id,
        )
        return

    if arguments.load_batch is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_award_batch(
            engine, arguments.load_batch, dry_run=arguments.dry_run, run_id=run_id
        )
        return

    if arguments.show_batch is not None:
        engine = create_postgres_engine()
        _run_show_batch(engine, arguments.show_batch)
        return

    if arguments.diff_award_versions is not None and not arguments.ecs:
        engine = create_postgres_engine()
        _run_diff_award_versions(engine, arguments.diff_award_versions)
        return

    if (
        arguments.investigate_workflow_document_number is not None
        and not arguments.ecs
    ):
        _run_investigate_workflow_document_number(
            arguments.investigate_workflow_document_number
        )
        return

    if arguments.load_unit_reference_data:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        run_load_unit_reference_data(engine, dry_run=arguments.dry_run)
        return

    if arguments.load_comment_type_reference_data:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        run_load_comment_type_reference_data(engine, dry_run=arguments.dry_run)
        return

    if arguments.load_award_id is not None:
        engine = create_postgres_engine()
        if not arguments.ecs:
            apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_award_id(
            engine,
            arguments.load_award_id,
            dry_run=arguments.dry_run,
            run_id=run_id,
        )
        return

    logger.info("Reading Award versions/amounts/people/proposals from Oracle")
    versions = prepare_versions(
        OracleDataSource(VERSIONS_ORACLE_SQL).read()
    )
    amounts = prepare_amounts(
        OracleDataSource(AMOUNTS_ORACLE_SQL).read()
    )
    people = prepare_people(
        OracleDataSource(PEOPLE_ORACLE_SQL).read()
    )
    proposals = prepare_proposals(
        OracleDataSource(PROPOSALS_ORACLE_SQL).read()
    )

    if arguments.limit is not None:
        versions = versions.head(arguments.limit)
        amounts = amounts.head(arguments.limit)
        people = people.head(arguments.limit)
        proposals = proposals.head(arguments.limit)
        logger.info(
            "Dry run (--limit {}): read versions={} amounts={} people={} "
            "proposals={} - skipping validation and database write.",
            arguments.limit,
            len(versions),
            len(amounts),
            len(people),
            len(proposals),
        )
        return

    validate_child_award_ids(
        versions,
        amounts,
        "award_amounts.csv",
    )
    validate_child_award_ids(
        versions,
        people,
        "award_people.csv",
    )
    validate_child_award_ids(
        versions,
        proposals,
        "award_proposals.csv",
    )

    total_rows = (
        len(versions)
        + len(amounts)
        + len(people)
        + len(proposals)
    )

    engine = create_postgres_engine()

    migration_path = os.getenv(
        "MIGRATION_PATH",
        "database/migrations",
    )

    if not arguments.ecs:
        apply_migrations(
            engine,
            migration_path,
        )

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins. If it were created inside the same
    # transaction as the load itself, a failure would roll back the
    # STARTED row along with everything else, and the mark_load_failed
    # UPDATE in the except block below would silently match zero rows -
    # leaving no trace of the failure in archive.load_run at all.
    with engine.begin() as connection:
        load_id = create_load_run(
            connection,
            total_rows,
        )

    try:
        with engine.begin() as connection:
            clear_existing_award_data(connection)

            version_rows = load_dataframe(
                connection,
                versions,
                "award_version",
                [
                    "award_id",
                    "award_number",
                    "sequence_number",
                    "award_sequence_status",
                    "status_code",
                    "status_description",
                    "title",
                    "sponsor_code",
                    "sponsor_name",
                    "prime_sponsor_code",
                    "prime_sponsor_name",
                    "lead_unit_number",
                    "lead_unit_name",
                    "proposal_number",
                    "account_number",
                    "sponsor_award_number",
                    "award_effective_date",
                    "award_execution_date",
                    "begin_date",
                    "closeout_date",
                    "transaction_type_code",
                    "transaction_type",
                    "basis_of_payment_code",
                    "basis_of_payment_description",
                    "method_of_payment_code",
                    "method_of_payment_description",
                    "modification_number",
                    "document_number",
                    "update_timestamp",
                    "update_user",
                    "is_current_version",
                    "is_primary_current",
                ],
                load_id,
            )

            amount_rows = load_dataframe(
                connection,
                amounts,
                "award_amount_info",
                [
                    "award_amount_info_id",
                    "award_id",
                    "award_number",
                    "sequence_number",
                    "anticipated_change_direct",
                    "anticipated_change_indirect",
                    "anticipated_total_direct",
                    "anticipated_total_indirect",
                    "obligated_total_direct",
                    "obligated_total_indirect",
                    "anticipated_total_amount",
                    "obligated_total_amount",
                    "tnm_document_number",
                    "ver_nbr",
                ],
                load_id,
            )

            person_rows = load_dataframe(
                connection,
                people,
                "award_person",
                [
                    "award_person_id",
                    "award_id",
                    "award_number",
                    "sequence_number",
                    "person_id",
                    "rolodex_id",
                    "full_name",
                    "contact_role_code",
                    "key_person_project_role",
                    "faculty_flag",
                    "academic_year_effort",
                    "calendar_year_effort",
                    "summer_effort",
                    "total_effort",
                    "update_timestamp",
                    "update_user",
                ],
                load_id,
            )

            proposal_rows = load_dataframe(
                connection,
                proposals,
                "award_funding_proposal",
                [
                    "award_funding_proposal_id",
                    "award_id",
                    "proposal_id",
                    "active",
                    "update_timestamp",
                    "update_user",
                    "ver_nbr",
                ],
                load_id,
            )

            rows_loaded = (
                version_rows
                + amount_rows
                + person_rows
                + proposal_rows
            )

            mark_load_complete(
                connection,
                load_id,
                rows_loaded,
            )

        logger.success(
            "Award load completed. "
            "load_id={} versions={} amounts={} people={} proposals={}",
            load_id,
            len(versions),
            len(amounts),
            len(people),
            len(proposals),
        )

    except Exception as error:
        mark_load_failed(
            engine,
            load_id,
            str(error),
        )

        logger.exception("Award load failed")
        raise


if __name__ == "__main__":
    main()
