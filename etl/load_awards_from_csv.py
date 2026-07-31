from __future__ import annotations

import argparse
import os
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.batch import framework as batch_framework
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

PROJECT_ROOT = Path(__file__).resolve().parents[1]

# Oracle extraction queries exist for versions/amounts/people/proposals.
# Award unit contacts had no verified Oracle extraction query and has been
# removed entirely (API, UI, ETL, and the archive.award_unit_contact table)
# - see docs/DECISIONS.md.
VERSIONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "01_award_versions.sql"
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

def clear_existing_award_data(
    connection: Connection,
) -> None:
    logger.info("Clearing existing Award archive data")

    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.award_funding_proposal,
                archive.award_person,
                archive.award_amount_info,
                archive.award_version
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
# report_term_recipient/sponsor_contact/unit_contact child rows - safe to
# run against a database that already has other Award data loaded, and safe
# to re-run. award_custom_data, the three Award People expansion tables, the
# three Award Terms tables, and the two Award Contacts tables (all Tier 1,
# see docs/architecture/AWARD_DOMAIN_DECOMPOSITION.md,
# docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md,
# docs/architecture/AWARD_TERMS_DESIGN.md, and
# docs/architecture/AWARD_CONTACTS_DESIGN.md) were added here alongside the
# original Phase 4A four; each depends only on award_version(award_id) or a
# table that itself does, so they all ride along on the same family-widened
# load with no separate top-level load function. No Award Budget, Award
# Reporting, or Time & Money workflow tables are touched here, SAP
# transmission is out of scope entirely, and Award.basisOfPaymentCode/
# methodOfPaymentCode are deliberately not captured (see
# AWARD_TERMS_DESIGN.md - would require a TRUNCATE-path change this work is
# scoped not to make).
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
    "modification_number",
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
                transaction_type_code, transaction_type, modification_number,
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
                :modification_number, :source_update_timestamp,
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
                modification_number = EXCLUDED.modification_number,
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
                OR archive.award_version.modification_number
                    IS DISTINCT FROM EXCLUDED.modification_number
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
                tnm_document_number, source_version_number, load_id
            ) VALUES (
                :award_amount_info_id, :award_id, :award_number,
                :sequence_number, :anticipated_change_direct,
                :anticipated_change_indirect, :anticipated_total_direct,
                :anticipated_total_indirect, :obligated_total_direct,
                :obligated_total_indirect, :anticipated_total_amount,
                :obligated_total_amount, :tnm_document_number,
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
    unit_contact child rows. Never truncates or replaces the full
    tables, never touches Award Budget/Reporting/Time and Money or SAP
    transmission, and does not capture Award.basisOfPaymentCode/
    methodOfPaymentCode (see docs/architecture/AWARD_TERMS_DESIGN.md - a
    deliberately deferred gap, not an oversight). person_unit_credit_split
    is upserted after person_unit (its FK parent) and before
    person_credit_split (an unrelated sibling, no ordering requirement
    against it); similarly report_term_recipient is upserted after
    report_term (its FK parent) - see
    docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md and
    docs/architecture/AWARD_TERMS_DESIGN.md. sponsor_contact/unit_contact
    have no FK relationship to each other or to any other table added in
    this pass - see docs/architecture/AWARD_CONTACTS_DESIGN.md, which
    also records why archive.award_unit_contact (dropped in V033) was
    reintroduced here with a corrected, double-verified schema rather
    than restored as originally shipped. With dry_run=True, every UPSERT
    still runs (so the reported counts are accurate) but the whole
    transaction is rolled back instead of committed."""
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
        "unit_contact(inserted={} updated={} unchanged={})",
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
    )
    return report


def _select_award_ids_ascending(
    source: OracleDataSource, requested_size: int
) -> list[int]:
    """Award-specific selection - deliberately does NOT reuse
    batch_framework.select_distinct_ascending_from_oracle_batches's
    early-stop optimization. That optimization is only correct when the
    underlying Oracle source is already ORDER BY the same column being
    selected (true for Award Attachment's physical-file scan, ORDER BY
    FILE_ID). 01_award_versions.sql is ORDER BY AWARD_NUMBER,
    SEQUENCE_NUMBER instead - award_id has no relationship to that sort
    order - so stopping early after N distinct award_ids would not
    select the N globally-smallest ones. This always scans the full
    source and sorts every distinct award_id in Python instead."""
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


def _run_create_award_batch(
    engine: Engine, requested_size: int, *, run_id: str | None = None
) -> dict[str, Any]:
    """--create-batch: select exactly `requested_size` distinct award_ids
    from Oracle, in stable ascending award_id order, and persist that
    exact membership as a new batch via the generic batch framework
    (archive.etl_batch/etl_batch_item). Unlike Award Attachment, there is
    no "already uploaded" concept here - every award_id is an equally
    valid candidate, and there is no BLOB/S3 concern anywhere in this
    domain. Raises ValueError for a non-positive requested_size."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    selected_award_ids = _select_award_ids_ascending(
        OracleDataSource(VERSIONS_ORACLE_SQL), requested_size
    )

    result = batch_framework.create_batch(
        engine,
        domain=AWARD_BATCH_DOMAIN,
        entity_type=AWARD_BATCH_ENTITY_TYPE,
        requested_size=requested_size,
        selection_strategy="ORACLE_SCAN_ASCENDING_AWARD_ID",
        selected_keys=selected_award_ids,
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
    loops over _run_load_award_id. Every one of the thirteen Award
    tables is read from Oracle exactly ONCE for the whole batch
    (bind-variable WHERE ... IN pushdown, chunked at Oracle's
    1000-element IN-list limit - see OracleDataSource.read_filtered),
    instead of once per family: runtime now scales with the number of
    Oracle tables, not families x tables. See
    docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md "Bulk batch load
    refactor" for the full design record and local benchmark.

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
            "report_term_recipient/sponsor_contact/unit_contact child "
            "rows. Never truncates or replaces the full tables. Scoped "
            "strictly to these thirteen tables - no Award Budget/"
            "Reporting/Time and Money/SAP transmission, and does not "
            "capture Award.basisOfPaymentCode/methodOfPaymentCode."
        ),
    )
    parser.add_argument(
        "--create-batch",
        type=int,
        default=None,
        metavar="N",
        help=(
            "Select exactly N distinct award_ids from Oracle, in stable "
            "ascending award_id order, and persist that exact membership "
            "as a new batch (archive.etl_batch/etl_batch_item, the "
            "generic ETL batch framework shared with Award Attachment). "
            "N must be positive."
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
    parsed = parser.parse_args(arguments)

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

    return parsed


def main() -> None:
    arguments = parse_args()
    run_id = str(uuid.uuid4())

    if arguments.create_batch is not None:
        engine = create_postgres_engine()
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_create_award_batch(
            engine, arguments.create_batch, run_id=run_id
        )
        return

    if arguments.load_batch is not None:
        engine = create_postgres_engine()
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        _run_load_award_batch(
            engine, arguments.load_batch, dry_run=arguments.dry_run, run_id=run_id
        )
        return

    if arguments.show_batch is not None:
        engine = create_postgres_engine()
        report = batch_framework.show_batch(
            engine,
            arguments.show_batch,
            domain=AWARD_BATCH_DOMAIN,
            entity_type=AWARD_BATCH_ENTITY_TYPE,
        )
        logger.bind(stage="show_batch", batch_id=arguments.show_batch).info(
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
        return

    if arguments.load_award_id is not None:
        engine = create_postgres_engine()
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
                    "modification_number",
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
