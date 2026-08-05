from __future__ import annotations

import argparse
import time
import uuid
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
)
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message
from archive_etl.utils.structured_logging import configure_structured_logging

def _resolve_project_root() -> Path:
    """Locate the directory containing sql/extract/proposal/ and
    database/migrations/ relative to this file. Two layouts are
    supported, mirroring load_awards_from_csv.py's own
    _resolve_project_root() exactly (not shared code - kept local to
    each loader, same as _connect_oracle - but the same technique): the
    local repo checkout (this file at
    <repo>/etl/load_proposals_from_csv.py, so the project root is one
    level up) and the ECS loader container image (this file copied
    flatly to /app/load_proposals_from_csv.py alongside sql/ and
    database/migrations/ copied directly under /app - see
    etl/Dockerfile.loader), where the project root is this file's own
    parent directory. This loader had never been run in a container
    before this fix - the naive parents[1] resolution silently worked
    locally (repo root really is one level up) and only broke inside
    the ECS image, which is exactly why it went unnoticed."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "sql").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

# Proposal People & Units (PROPOSAL_PERSONS/PROPOSAL_PERSON_UNITS/
# PROPOSAL_UNIT_CONTACTS) previously had no verified Oracle extraction
# query and archive.proposal_person was dropped entirely (V033) - see
# docs/DECISIONS.md. A live schema + fixture-data probe this session
# proved the real columns and the family-205 fixture relationship
# (PI Lois K Horwitz via PROPOSAL_PERSONS/PROPOSAL_PERSON_UNITS vs. a
# genuinely different Unit Contact, Andrea Cozzi, via the separate
# PROPOSAL_UNIT_CONTACTS table) - see
# docs/kuali-business-rules/InstitutionalProposal.md's People & Units
# section and V061's own migration comment. That reverses the "removed
# entirely" state for this specific decision, not the Protocol Archive
# or any other removal in docs/DECISIONS.md.
VERSIONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "01_proposal_versions.sql"
)
# Candidate-enumeration query for --create-batch's production selection
# mode only (see _run_create_proposal_batch) - not part of the
# 8-dataset extraction/load sequence, never populates any archive.*
# table. Mirrors AWARD_IDS_ASCENDING_ORACLE_SQL.
PROPOSAL_NUMBERS_ASCENDING_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "proposal_numbers_ascending.sql"
)
AWARDS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "04_award_proposals.sql"
)
# PROPOSAL_ATTACHMENTS metadata only - the binary content behind
# FILE_DATA_ID is loaded separately by the generic attachment pipeline
# (archive_etl.attachments.runner + ProposalAttachmentPlugin), never by
# this script. See docs/kuali-business-rules/InstitutionalProposal.md's
# Attachments section for the proven FILE_DATA/FILE_DATA_ID shape
# (Subaward-shaped, not Award-shaped).
ATTACHMENTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "02_proposal_attachments.sql"
)
PERSONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "03_proposal_persons.sql"
)
PERSON_UNITS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "04_proposal_person_units.sql"
)
UNIT_CONTACTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "05_proposal_unit_contacts.sql"
)
COMMENTS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "06_proposal_comments.sql"
)
CUSTOM_DATA_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "07_proposal_custom_data.sql"
)

VERSION_REQUIRED_COLUMNS = {
    "proposal_id",
    "proposal_number",
    "version_number",
}

AWARD_REQUIRED_COLUMNS = {
    "proposal_id",
    "award_id",
}

ATTACHMENT_REQUIRED_COLUMNS = {
    "proposal_attachment_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
}

PERSON_REQUIRED_COLUMNS = {
    "proposal_person_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
}

PERSON_UNIT_REQUIRED_COLUMNS = {
    "proposal_person_unit_id",
    "proposal_person_id",
    "proposal_id",
}

UNIT_CONTACT_REQUIRED_COLUMNS = {
    "proposal_unit_contact_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
}

COMMENT_REQUIRED_COLUMNS = {
    "proposal_comment_id",
    "proposal_id",
}

CUSTOM_DATA_REQUIRED_COLUMNS = {
    "proposal_custom_data_id",
    "proposal_id",
}

VERSION_COLUMNS = [
    "proposal_id",
    "proposal_number",
    "version_number",
    "document_number",
    "title",
    "proposal_sequence_status",
    "status_code",
    "status_description",
    "proposal_type_code",
    "proposal_type",
    "activity_type_code",
    "activity_type",
    "sponsor_code",
    "sponsor_name",
    "lead_unit_number",
    "lead_unit_name",
    "principal_investigator_id",
    "principal_investigator_name",
    "initial_start_date",
    "initial_end_date",
    "initial_direct_cost",
    "initial_indirect_cost",
    "initial_total_cost",
    "total_start_date",
    "total_end_date",
    "total_direct_cost",
    "total_indirect_cost",
    "total_cost",
    "source_update_timestamp",
    "source_update_user",
]

# AWARD_FUNDING_PROPOSALS is loaded as exact awardId<->proposalId rows,
# including its own real PK and row-level active flag - never reduced to
# an award_number/proposal_number family relationship here (that
# resolution belongs in the application layer - see
# docs/kuali-business-rules/InstitutionalProposal.md's Award relationship
# section, which proved Kuali itself only does that reduction at query
# time, never at storage time).
AWARD_COLUMNS = [
    "award_funding_proposal_id",
    "proposal_id",
    "award_id",
    "active",
    "source_update_timestamp",
    "source_update_user",
]

# Metadata columns only - deliberately excludes the binary-archival
# lifecycle columns (upload_status/s3_bucket/object_key/file_size/
# checksum/uploaded_at/error_message), which this loader never writes -
# those are owned exclusively by the generic attachment pipeline
# (ProposalAttachmentPlugin). upsert_proposal_attachments() must never
# touch them on a re-run, or it would silently wipe upload progress.
ATTACHMENT_COLUMNS = [
    "proposal_attachment_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "attachment_number",
    "attachment_title",
    "attachment_type_code",
    "attachment_type_description",
    "file_name",
    "content_type",
    "comments",
    "document_status_code",
    "file_data_id",
    "source_update_timestamp",
    "source_update_user",
]

# PROPOSAL_PERSONS - PI/MPI/COI/KP via contact_role_code, the same
# shared vocabulary already proven for archive.award_person.
PERSON_COLUMNS = [
    "proposal_person_id",
    "proposal_id",
    "proposal_number",
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

# PROPOSAL_PERSON_UNITS - a person's associated unit(s), each with its
# own lead_unit_flag. proposal_id/proposal_number/sequence_number are
# denormalized via the extraction SQL's join back to PROPOSAL_PERSONS
# (see 04_proposal_person_units.sql) - never inferred client-side.
PERSON_UNIT_COLUMNS = [
    "proposal_person_unit_id",
    "proposal_person_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "unit_number",
    "lead_unit_flag",
    "source_update_timestamp",
    "source_update_user",
]

# PROPOSAL_UNIT_CONTACTS - a genuinely separate sibling table, never
# merged with proposal_person (see V061's migration comment for the
# live-verified fixture proof that this is a different person than
# the PI).
UNIT_CONTACT_COLUMNS = [
    "proposal_unit_contact_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "person_id",
    "full_name",
    "unit_administrator_type_code",
    "unit_contact_type",
    "source_update_timestamp",
    "source_update_user",
]

# PROPOSAL_COMMENTS - comment_type_code is a bare lookup code into the
# shared archive.comment_type table (same table Award's own comments
# already reuse), kept unjoined here - the API layer resolves the
# description and filters to the categories a Proposal actually
# displays.
COMMENT_COLUMNS = [
    "proposal_comment_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "comment_type_code",
    "comments",
    "source_update_timestamp",
    "source_update_user",
]

# PROPOSAL_CUSTOM_DATA - version-scoped (its own real sequence_number
# per row, never family-wide - live-verified: fixture 01157400 has 161
# rows across only 30 distinct custom_attribute_ids spread over 6
# different sequence_numbers). custom_attribute_id is kept unjoined -
# the shared archive.custom_attribute/custom_attribute_document
# reference tables (loaded independently, see
# archive_etl/reference_data.py) resolve the label at query time.
CUSTOM_DATA_COLUMNS = [
    "proposal_custom_data_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "custom_attribute_id",
    "value",
    "source_update_timestamp",
    "source_update_user",
]


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Proposal versions/awards/attachments/people/units from "
            "Oracle."
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
        "--proposal-number",
        "--load-proposal-number",
        dest="proposal_number",
        action="append",
        default=None,
        help=(
            "Load only the given PROPOSAL_NUMBER family/families "
            "(repeatable; --load-proposal-number is an alias, matching "
            "--load-award-id's naming convention). A real, bounded, "
            "idempotent UPSERT - never a dry run. Every version in "
            "scope is loaded regardless of proposal_sequence_status "
            "(preserve every version - see "
            "docs/kuali-business-rules/InstitutionalProposal.md)."
        ),
    )
    parser.add_argument(
        "--create-batch",
        type=int,
        default=None,
        metavar="N",
        help=(
            "Select exactly N genuinely new, archive-aware Proposal "
            "families (excludes already-archived proposal_numbers, "
            "COMPLETED batch items, and proposal_numbers claimed by a "
            "still-active batch) and persist that membership as a new "
            "batch. Does not load anything - see --load-batch."
        ),
    )
    parser.add_argument(
        "--load-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help=(
            "Idempotently load a batch's PENDING/FAILED proposal_number "
            "membership - one Postgres transaction per family, so a "
            "failure in one family never blocks or rolls back its "
            "siblings. Re-running on the same batch_id only retries "
            "families that didn't already COMPLETE."
        ),
    )
    parser.add_argument(
        "--show-batch",
        type=int,
        default=None,
        metavar="BATCH_ID",
        help="Print a read-only status report for one Proposal batch.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "With --load-batch: read Oracle and report what would be "
            "loaded, without writing to Postgres or changing any batch "
            "item's status."
        ),
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Resolve ORACLE_USER/PASSWORD/DSN and POSTGRES_* from AWS "
            "Secrets Manager at startup (ORACLE_SECRET_ID/"
            "POSTGRES_SECRET_ID env vars), for running inside ECS - "
            "mirrors load_awards_from_csv.py's own --ecs. Oracle "
            "credentials are skipped for --show-batch, which is "
            "PostgreSQL-only."
        ),
    )
    args = parser.parse_args(arguments)

    exclusive_count = sum(
        1
        for value in (
            args.proposal_number,
            args.create_batch,
            args.load_batch,
            args.show_batch,
        )
        if value
    )
    if exclusive_count > 1:
        parser.error(
            "--proposal-number/--load-proposal-number, --create-batch, "
            "--load-batch, and --show-batch are mutually exclusive"
        )

    return args


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


def convert_booleans(
    dataframe: pd.DataFrame,
    columns: list[str],
) -> None:
    # Oracle's ACTIVE column is a real CHAR('Y'/'N') flag (see
    # AwardFundingProposal's own OjbCharBooleanConversion) - preserved
    # here as an actual boolean, never dropped or reinterpreted.
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = (
                dataframe[column]
                .astype(str)
                .str.strip()
                .str.upper()
                .map({"Y": True, "N": False})
            )


def require_values(
    dataframe: pd.DataFrame,
    columns: list[str],
    file_name: str,
) -> None:
    invalid = dataframe[
        dataframe[columns].isna().any(axis=1)
    ]

    if not invalid.empty:
        raise RuntimeError(
            f"{file_name} contains {len(invalid)} rows "
            "missing required values"
        )


def prepare_versions(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        VERSION_REQUIRED_COLUMNS,
        "proposal_versions.csv",
    )

    # proposal_type_code and activity_type_code are both real Oracle
    # VARCHAR2 columns (confirmed via ALL_TAB_COLUMNS - the OJB
    # mapping's "INTEGER" jdbc-type label for proposal_type_code does
    # not match live DDL, the same kind of mapping-vs-reality gap this
    # project has hit before). Their real value domains are proven
    # 100% numeric-string across the whole table (live-verified: zero
    # non-numeric PROPOSAL_TYPE_CODE/ACTIVITY_TYPE_CODE rows in Oracle),
    # matching archive.proposal_version's existing INTEGER columns for
    # both - safe to convert numerically in practice, unlike a
    # genuinely alphanumeric code would be.
    convert_numeric(
        dataframe,
        [
            "proposal_id",
            "version_number",
            "status_code",
            "proposal_type_code",
            "activity_type_code",
            "initial_direct_cost",
            "initial_indirect_cost",
            "initial_total_cost",
            "total_direct_cost",
            "total_indirect_cost",
            "total_cost",
        ],
    )

    convert_dates(
        dataframe,
        [
            "initial_start_date",
            "initial_end_date",
            "total_start_date",
            "total_end_date",
            "source_update_timestamp",
        ],
    )

    require_values(
        dataframe,
        [
            "proposal_id",
            "proposal_number",
            "version_number",
        ],
        "proposal_versions.csv",
    )

    duplicate_versions = dataframe.duplicated(
        subset=["proposal_id", "version_number"],
        keep=False,
    )

    if duplicate_versions.any():
        duplicate_count = int(duplicate_versions.sum())

        duplicate_preview = (
            dataframe.loc[
                duplicate_versions,
                ["proposal_id", "version_number"],
            ]
            .head(20)
            .to_string(index=False)
        )

        raise RuntimeError(
            "proposal_versions.csv contains duplicate "
            "proposal_id + version_number rows. "
            f"Duplicate rows: {duplicate_count}\n"
            + duplicate_preview
        )

    return dataframe


def prepare_awards(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        AWARD_REQUIRED_COLUMNS,
        "award_proposals.csv",
    )

    # 04_award_proposals.sql is shared with Award's own
    # archive.award_funding_proposal loader (already COMPLETE) and
    # selects the raw Oracle column names UPDATE_TIMESTAMP/UPDATE_USER
    # verbatim, normalized to update_timestamp/update_user - never
    # renamed in the SQL file itself, since that file must not change
    # shape out from under Award's own consumption of it. Renamed here,
    # Proposal-side only, to match archive.proposal_award's real
    # source_update_timestamp/source_update_user columns (the same
    # target names Award's own archive.award_funding_proposal uses).
    dataframe = dataframe.rename(
        columns={
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
        }
    )

    convert_numeric(
        dataframe,
        ["award_funding_proposal_id", "proposal_id", "award_id"],
    )

    convert_booleans(dataframe, ["active"])

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        ["proposal_id", "award_id"],
        "award_proposals.csv",
    )

    # AWARD_FUNDING_PROPOSAL_ID is AWARD_FUNDING_PROPOSALS' own real
    # Oracle PK - the correct de-duplication/UPSERT key. proposal_id +
    # award_id alone is not guaranteed unique in Oracle (a relationship
    # could in principle be recorded, deactivated, and re-recorded as a
    # new row) and must never be silently collapsed - see
    # docs/kuali-business-rules/InstitutionalProposal.md's explicit
    # "preserve the row-level active flag, do not reduce it" rule.
    duplicate_links = dataframe.duplicated(
        subset=["award_funding_proposal_id"],
        keep="first",
    )

    if duplicate_links.any():
        logger.warning(
            "Removed {} duplicate AWARD_FUNDING_PROPOSAL_ID rows",
            int(duplicate_links.sum()),
        )

        dataframe = dataframe.loc[
            ~duplicate_links
        ].copy()

    available_columns = [
        column for column in AWARD_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_attachments(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        ATTACHMENT_REQUIRED_COLUMNS,
        "proposal_attachments.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_attachment_id",
            "proposal_id",
            "sequence_number",
            "attachment_number",
            "attachment_type_code",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        [
            "proposal_attachment_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
        ],
        "proposal_attachments.csv",
    )

    # PROPOSAL_ATTACHMENTS_ID is Oracle's own real PK - the correct
    # UPSERT key. Never de-duplicate by file_data_id: multiple real,
    # distinct historical attachment references legitimately share one
    # underlying file (live-verified: 149,432 distinct file_data_id
    # values across 405,779 total rows) - see
    # docs/kuali-business-rules/InstitutionalProposal.md's Attachments
    # section. Preserve every row.
    duplicate_attachments = dataframe.duplicated(
        subset=["proposal_attachment_id"],
        keep="first",
    )

    if duplicate_attachments.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_ATTACHMENTS_ID rows",
            int(duplicate_attachments.sum()),
        )

        dataframe = dataframe.loc[
            ~duplicate_attachments
        ].copy()

    available_columns = [
        column for column in ATTACHMENT_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_persons(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_REQUIRED_COLUMNS,
        "proposal_persons.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_person_id",
            "proposal_id",
            "sequence_number",
            "rolodex_id",
            "academic_year_effort",
            "calendar_year_effort",
            "summer_effort",
            "total_effort",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        [
            "proposal_person_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
        ],
        "proposal_persons.csv",
    )

    # PROPOSAL_PERSON_ID is PROPOSAL_PERSONS' own real Oracle PK - the
    # correct UPSERT key. Preserve every row: a new Proposal version
    # can carry its own PROPOSAL_PERSON_ID rows distinct from a prior
    # version's (mirrors archive.award_person's own per-version grain).
    duplicate_persons = dataframe.duplicated(
        subset=["proposal_person_id"],
        keep="first",
    )

    if duplicate_persons.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_PERSON_ID rows",
            int(duplicate_persons.sum()),
        )

        dataframe = dataframe.loc[~duplicate_persons].copy()

    available_columns = [
        column for column in PERSON_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_person_units(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_UNIT_REQUIRED_COLUMNS,
        "proposal_person_units.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_person_unit_id",
            "proposal_person_id",
            "proposal_id",
            "sequence_number",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        [
            "proposal_person_unit_id",
            "proposal_person_id",
            "proposal_id",
        ],
        "proposal_person_units.csv",
    )

    # PROPOSAL_PERSON_UNIT_ID is PROPOSAL_PERSON_UNITS' own real Oracle
    # PK - the correct UPSERT key. A single person legitimately carries
    # more than one unit row (live-verified shape mirrors
    # archive.award_person_unit) - never collapsed to one row per
    # person.
    duplicate_person_units = dataframe.duplicated(
        subset=["proposal_person_unit_id"],
        keep="first",
    )

    if duplicate_person_units.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_PERSON_UNIT_ID rows",
            int(duplicate_person_units.sum()),
        )

        dataframe = dataframe.loc[~duplicate_person_units].copy()

    available_columns = [
        column for column in PERSON_UNIT_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_unit_contacts(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        UNIT_CONTACT_REQUIRED_COLUMNS,
        "proposal_unit_contacts.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_unit_contact_id",
            "proposal_id",
            "sequence_number",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        [
            "proposal_unit_contact_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
        ],
        "proposal_unit_contacts.csv",
    )

    # PROPOSAL_UNIT_CONTACT_ID is PROPOSAL_UNIT_CONTACTS' own real
    # Oracle PK - the correct UPSERT key. Never merged with
    # proposal_person: live-verified as a genuinely distinct person in
    # the reference fixture (see V061's migration comment).
    duplicate_unit_contacts = dataframe.duplicated(
        subset=["proposal_unit_contact_id"],
        keep="first",
    )

    if duplicate_unit_contacts.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_UNIT_CONTACT_ID rows",
            int(duplicate_unit_contacts.sum()),
        )

        dataframe = dataframe.loc[~duplicate_unit_contacts].copy()

    available_columns = [
        column for column in UNIT_CONTACT_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_comments(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        COMMENT_REQUIRED_COLUMNS,
        "proposal_comments.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_comment_id",
            "proposal_id",
            "sequence_number",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        ["proposal_comment_id", "proposal_id"],
        "proposal_comments.csv",
    )

    # PROPOSAL_COMMENTS_ID is PROPOSAL_COMMENTS' own real Oracle PK -
    # the correct UPSERT key.
    duplicate_comments = dataframe.duplicated(
        subset=["proposal_comment_id"],
        keep="first",
    )

    if duplicate_comments.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_COMMENTS_ID rows",
            int(duplicate_comments.sum()),
        )

        dataframe = dataframe.loc[~duplicate_comments].copy()

    available_columns = [
        column for column in COMMENT_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def prepare_custom_data(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        CUSTOM_DATA_REQUIRED_COLUMNS,
        "proposal_custom_data.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_custom_data_id",
            "proposal_id",
            "sequence_number",
            "custom_attribute_id",
        ],
    )

    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        ["proposal_custom_data_id", "proposal_id"],
        "proposal_custom_data.csv",
    )

    # PROPOSAL_CUSTOM_DATA_ID is PROPOSAL_CUSTOM_DATA's own real Oracle
    # PK - the correct UPSERT key.
    duplicate_custom_data = dataframe.duplicated(
        subset=["proposal_custom_data_id"],
        keep="first",
    )

    if duplicate_custom_data.any():
        logger.warning(
            "Removed {} duplicate PROPOSAL_CUSTOM_DATA_ID rows",
            int(duplicate_custom_data.sum()),
        )

        dataframe = dataframe.loc[~duplicate_custom_data].copy()

    available_columns = [
        column for column in CUSTOM_DATA_COLUMNS if column in dataframe.columns
    ]
    return dataframe[available_columns].copy()


def load_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    table_name: str,
    columns: list[str],
) -> int:
    available_columns = [
        column
        for column in columns
        if column in dataframe.columns
    ]

    target = dataframe[
        available_columns
    ].copy()

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


def create_load_run(connection: Connection, total_rows: int) -> int:
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
                'PROPOSAL',
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


def clear_existing_proposal_data(
    connection: Connection,
) -> None:
    logger.info("Clearing existing Proposal archive data")

    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.proposal_award,
                archive.proposal_version;
            """
        )
    )


def _distinct_where_clause(table: str, update_columns: list[str]) -> str:
    """Builds the IS DISTINCT FROM guard shared by every bulk upsert
    below - mirrors archive_etl.reference_data._upsert_rows's per-row
    convention, but at bulk-statement scale: one INSERT ... SELECT ...
    ON CONFLICT DO UPDATE ... RETURNING for the whole staged dataframe,
    not one round trip per row."""
    return " OR\n                ".join(
        f"archive.{table}.{column} IS DISTINCT FROM EXCLUDED.{column}"
        for column in update_columns
    )


def _upsert_report(
    result: Any, attempted_rows: int, *, skipped: int = 0
) -> dict[str, int]:
    """Turns a bulk UPSERT's RETURNING (xmax = 0) AS inserted rows into
    an {inserted, updated, unchanged, skipped} breakdown. A row that
    matched an existing key but changed nothing never appears in
    RETURNING at all (the WHERE ... IS DISTINCT FROM guard makes ON
    CONFLICT DO UPDATE's WHERE clause false for it) - so unchanged =
    attempted_rows minus however many rows DID come back. attempted_rows
    must already exclude any rows skipped entirely for an unresolved
    foreign key; `skipped` carries that count through instead (e.g. a
    proposal_award row whose award_id isn't yet loaded - see
    upsert_proposal_awards) so callers can report "missing linked
    Awards" without a second query."""
    rows = result.mappings().all()
    inserted = sum(1 for row in rows if row["inserted"])
    updated = len(rows) - inserted
    unchanged = attempted_rows - len(rows)
    return {
        "inserted": inserted,
        "updated": updated,
        "unchanged": unchanged,
        "skipped": skipped,
    }


def upsert_proposal_versions(
    connection: Connection,
    versions: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_version, keyed by
    (proposal_id, version_number) - preserves every version regardless
    of proposal_sequence_status (never filters CANCELED/ARCHIVED/PENDING
    out), safe to re-run for the same families without truncating
    unrelated data. Mirrors the UPSERT pattern established for Award/
    Budget (see load_awards_from_csv.py's upsert_award_budget)."""
    connection.execute(
        text(
            f"""
            CREATE TEMPORARY TABLE proposal_version_stage (
                {", ".join(
                    f"{column} TIMESTAMP" if column == "source_update_timestamp"
                    else f"{column} DATE" if column in (
                        "initial_start_date", "initial_end_date",
                        "total_start_date", "total_end_date",
                    )
                    else f"{column} NUMERIC" if column in (
                        "proposal_id", "version_number", "status_code",
                        "proposal_type_code", "activity_type_code",
                        "initial_direct_cost", "initial_indirect_cost",
                        "initial_total_cost", "total_direct_cost",
                        "total_indirect_cost", "total_cost",
                    )
                    else f"{column} TEXT"
                    for column in VERSION_COLUMNS
                )}
            ) ON COMMIT DROP
            """
        )
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=versions[VERSION_COLUMNS],
        schema="pg_temp",
        table="proposal_version_stage",
    )

    update_columns = [
        column
        for column in VERSION_COLUMNS
        if column not in ("proposal_id", "version_number")
    ]
    distinct_where = _distinct_where_clause("proposal_version", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_version (
                {", ".join(VERSION_COLUMNS)}
            )
            SELECT {", ".join(VERSION_COLUMNS)}
            FROM proposal_version_stage
            ON CONFLICT (proposal_id, version_number) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(versions))


def upsert_proposal_awards(
    connection: Connection,
    awards: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_award, keyed by
    award_funding_proposal_id (AWARD_FUNDING_PROPOSALS' own real Oracle
    PK) - exact awardId<->proposalId rows, row-level active flag
    preserved verbatim, never reduced to an award_number/proposal_number
    family relationship (see
    docs/kuali-business-rules/InstitutionalProposal.md)."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_award_stage (
                award_funding_proposal_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                award_id BIGINT NOT NULL,
                active BOOLEAN,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_award_stage",
        len(awards),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=awards[AWARD_COLUMNS],
        schema="pg_temp",
        table="proposal_award_stage",
    )

    unresolved_award_ids = connection.execute(
        text(
            """
            SELECT DISTINCT stage.award_id
            FROM proposal_award_stage stage
            LEFT JOIN archive.award_version award
                ON award.award_id = stage.award_id
            WHERE award.award_id IS NULL
            ORDER BY stage.award_id
            """
        )
    ).scalars().all()

    skipped_row_count = connection.execute(
        text(
            """
            SELECT COUNT(*)
            FROM proposal_award_stage stage
            LEFT JOIN archive.award_version award
                ON award.award_id = stage.award_id
            WHERE award.award_id IS NULL
            """
        )
    ).scalar_one()

    if unresolved_award_ids:
        # A real, expected state, not an error: this archive currently
        # holds only a fraction of all Oracle Awards (loaded
        # incrementally, batch by batch - see docs/kuali-business-rules/
        # InstitutionalProposal.md), so a Proposal batch will routinely
        # reference Award IDs not yet loaded. "Preserve every Proposal
        # version" takes priority over "every award link must resolve
        # today" - these specific links are skipped (never fabricated,
        # never silently dropped without a trace) and will UPSERT
        # cleanly on a future re-run once their Award is loaded, since
        # this whole loader is idempotent.
        preview = ", ".join(
            str(award_id)
            for award_id in unresolved_award_ids[:20]
        )

        logger.warning(
            "Skipping {} proposal_award row(s) whose Award ID is not "
            "yet loaded in archive.award_version: {}{}",
            len(unresolved_award_ids),
            preview,
            " ..." if len(unresolved_award_ids) > 20 else "",
        )

    logger.info(
        "UPSERT {:<28} attempting {:,} row(s), some may be skipped above",
        "proposal_award",
        len(awards),
    )

    distinct_where = _distinct_where_clause(
        "proposal_award",
        [
            "proposal_id",
            "award_id",
            "award_number",
            "active",
            "source_update_timestamp",
            "source_update_user",
        ],
    )

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_award (
                award_funding_proposal_id,
                proposal_id,
                award_id,
                award_number,
                active,
                source_update_timestamp,
                source_update_user
            )
            SELECT
                stage.award_funding_proposal_id,
                stage.proposal_id,
                stage.award_id,
                award.award_number,
                stage.active,
                stage.source_update_timestamp,
                stage.source_update_user
            FROM proposal_award_stage stage
            JOIN archive.award_version award
                ON award.award_id = stage.award_id
            ON CONFLICT (award_funding_proposal_id) DO UPDATE SET
                proposal_id = EXCLUDED.proposal_id,
                award_id = EXCLUDED.award_id,
                award_number = EXCLUDED.award_number,
                active = EXCLUDED.active,
                source_update_timestamp = EXCLUDED.source_update_timestamp,
                source_update_user = EXCLUDED.source_update_user
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(
        result,
        len(awards) - int(skipped_row_count),
        skipped=int(skipped_row_count),
    )


def upsert_proposal_attachments(
    connection: Connection,
    attachments: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_attachment metadata,
    keyed by proposal_attachment_id (PROPOSAL_ATTACHMENTS' own real
    Oracle PK). Deliberately updates ONLY the metadata columns -
    upload_status/s3_bucket/object_key/file_size/checksum/uploaded_at/
    error_message are never touched here, since they are owned
    exclusively by the binary-archival pipeline
    (ProposalAttachmentPlugin) and re-running this metadata load must
    never wipe upload progress."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_attachment_stage (
                proposal_attachment_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                attachment_number INTEGER,
                attachment_title TEXT,
                attachment_type_code INTEGER,
                attachment_type_description TEXT,
                file_name TEXT,
                content_type TEXT,
                comments TEXT,
                document_status_code TEXT,
                file_data_id TEXT,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_attachment_stage",
        len(attachments),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=attachments[ATTACHMENT_COLUMNS],
        schema="pg_temp",
        table="proposal_attachment_stage",
    )

    update_columns = [
        column
        for column in ATTACHMENT_COLUMNS
        if column != "proposal_attachment_id"
    ]
    distinct_where = _distinct_where_clause("proposal_attachment", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_attachment (
                {", ".join(ATTACHMENT_COLUMNS)}
            )
            SELECT {", ".join(ATTACHMENT_COLUMNS)}
            FROM proposal_attachment_stage
            ON CONFLICT (proposal_attachment_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(attachments))


def upsert_proposal_persons(
    connection: Connection,
    persons: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_person, keyed by
    proposal_person_id (PROPOSAL_PERSONS' own real Oracle PK)."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_person_stage (
                proposal_person_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                person_id TEXT,
                rolodex_id BIGINT,
                full_name TEXT,
                contact_role_code TEXT,
                key_person_project_role TEXT,
                faculty_flag TEXT,
                academic_year_effort NUMERIC,
                calendar_year_effort NUMERIC,
                summer_effort NUMERIC,
                total_effort NUMERIC,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_person_stage",
        len(persons),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=persons[PERSON_COLUMNS],
        schema="pg_temp",
        table="proposal_person_stage",
    )

    update_columns = [
        column for column in PERSON_COLUMNS if column != "proposal_person_id"
    ]
    distinct_where = _distinct_where_clause("proposal_person", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_person (
                {", ".join(PERSON_COLUMNS)}
            )
            SELECT {", ".join(PERSON_COLUMNS)}
            FROM proposal_person_stage
            ON CONFLICT (proposal_person_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(persons))


def upsert_proposal_person_units(
    connection: Connection,
    person_units: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_person_unit, keyed by
    proposal_person_unit_id (PROPOSAL_PERSON_UNITS' own real Oracle
    PK). A row whose proposal_person_id is not yet loaded in
    archive.proposal_person is skipped with a warning rather than
    aborting the whole batch - same "preserve everything resolvable
    now, re-run resolves the rest later" precedent already established
    for upsert_proposal_awards."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_person_unit_stage (
                proposal_person_unit_id BIGINT NOT NULL,
                proposal_person_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT,
                sequence_number INTEGER,
                unit_number TEXT,
                lead_unit_flag TEXT,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_person_unit_stage",
        len(person_units),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=person_units[PERSON_UNIT_COLUMNS],
        schema="pg_temp",
        table="proposal_person_unit_stage",
    )

    unresolved_person_ids = (
        connection.execute(
            text(
                """
                SELECT DISTINCT stage.proposal_person_id
                FROM proposal_person_unit_stage stage
                LEFT JOIN archive.proposal_person person
                    ON person.proposal_person_id = stage.proposal_person_id
                WHERE person.proposal_person_id IS NULL
                """
            )
        )
        .scalars()
        .all()
    )

    if unresolved_person_ids:
        logger.warning(
            "Skipping {} proposal_person_unit row(s) whose Proposal "
            "Person ID is not yet loaded in archive.proposal_person: {}",
            len(unresolved_person_ids),
            ", ".join(str(value) for value in unresolved_person_ids[:20]),
        )

    skipped_row_count = connection.execute(
        text(
            """
            SELECT COUNT(*)
            FROM proposal_person_unit_stage stage
            LEFT JOIN archive.proposal_person person
                ON person.proposal_person_id = stage.proposal_person_id
            WHERE person.proposal_person_id IS NULL
            """
        )
    ).scalar_one()

    update_columns = [
        column
        for column in PERSON_UNIT_COLUMNS
        if column != "proposal_person_unit_id"
    ]
    distinct_where = _distinct_where_clause("proposal_person_unit", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_person_unit (
                {", ".join(PERSON_UNIT_COLUMNS)}
            )
            SELECT {", ".join(f"stage.{column}" for column in PERSON_UNIT_COLUMNS)}
            FROM proposal_person_unit_stage stage
            JOIN archive.proposal_person person
                ON person.proposal_person_id = stage.proposal_person_id
            ON CONFLICT (proposal_person_unit_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(
        result,
        len(person_units) - int(skipped_row_count),
        skipped=int(skipped_row_count),
    )


def upsert_proposal_unit_contacts(
    connection: Connection,
    unit_contacts: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_unit_contact, keyed by
    proposal_unit_contact_id (PROPOSAL_UNIT_CONTACTS' own real Oracle
    PK). Never merged with archive.proposal_person - a genuinely
    separate sibling table (see V061's migration comment)."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_unit_contact_stage (
                proposal_unit_contact_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                person_id TEXT,
                full_name TEXT,
                unit_administrator_type_code TEXT,
                unit_contact_type TEXT,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_unit_contact_stage",
        len(unit_contacts),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=unit_contacts[UNIT_CONTACT_COLUMNS],
        schema="pg_temp",
        table="proposal_unit_contact_stage",
    )

    update_columns = [
        column
        for column in UNIT_CONTACT_COLUMNS
        if column != "proposal_unit_contact_id"
    ]
    distinct_where = _distinct_where_clause("proposal_unit_contact", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_unit_contact (
                {", ".join(UNIT_CONTACT_COLUMNS)}
            )
            SELECT {", ".join(UNIT_CONTACT_COLUMNS)}
            FROM proposal_unit_contact_stage
            ON CONFLICT (proposal_unit_contact_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(unit_contacts))


def upsert_proposal_comments(
    connection: Connection,
    comments: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_comment, keyed by
    proposal_comment_id (PROPOSAL_COMMENTS' own real Oracle PK)."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_comment_stage (
                proposal_comment_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT,
                sequence_number INTEGER,
                comment_type_code TEXT,
                comments TEXT,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_comment_stage",
        len(comments),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=comments[COMMENT_COLUMNS],
        schema="pg_temp",
        table="proposal_comment_stage",
    )

    update_columns = [
        column for column in COMMENT_COLUMNS if column != "proposal_comment_id"
    ]
    distinct_where = _distinct_where_clause("proposal_comment", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_comment (
                {", ".join(COMMENT_COLUMNS)}
            )
            SELECT {", ".join(COMMENT_COLUMNS)}
            FROM proposal_comment_stage
            ON CONFLICT (proposal_comment_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(comments))


def upsert_proposal_custom_data(
    connection: Connection,
    custom_data: pd.DataFrame,
) -> dict[str, int]:
    """Idempotent UPSERT of archive.proposal_custom_data, keyed by
    proposal_custom_data_id (PROPOSAL_CUSTOM_DATA's own real Oracle
    PK). custom_attribute_id is not FK-checked here - see V064's
    migration comment."""
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_custom_data_stage (
                proposal_custom_data_id BIGINT NOT NULL,
                proposal_id BIGINT NOT NULL,
                proposal_number TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                custom_attribute_id BIGINT,
                value TEXT,
                source_update_timestamp TIMESTAMP,
                source_update_user TEXT
            ) ON COMMIT DROP
            """
        )
    )

    logger.info(
        "COPY {:<30} {:,} rows",
        "proposal_custom_data_stage",
        len(custom_data),
    )

    bulk_copy_dataframe(
        connection=connection,
        dataframe=custom_data[CUSTOM_DATA_COLUMNS],
        schema="pg_temp",
        table="proposal_custom_data_stage",
    )

    update_columns = [
        column
        for column in CUSTOM_DATA_COLUMNS
        if column != "proposal_custom_data_id"
    ]
    distinct_where = _distinct_where_clause("proposal_custom_data", update_columns)

    result = connection.execute(
        text(
            f"""
            INSERT INTO archive.proposal_custom_data (
                {", ".join(CUSTOM_DATA_COLUMNS)}
            )
            SELECT {", ".join(CUSTOM_DATA_COLUMNS)}
            FROM proposal_custom_data_stage
            ON CONFLICT (proposal_custom_data_id) DO UPDATE SET
                {", ".join(
                    f"{column} = EXCLUDED.{column}"
                    for column in update_columns
                )}
            WHERE
                {distinct_where}
            RETURNING (xmax = 0) AS inserted
            """
        )
    )

    return _upsert_report(result, len(custom_data))


_EMPTY_UPSERT_REPORT: dict[str, int] = {
    "inserted": 0,
    "updated": 0,
    "unchanged": 0,
    "skipped": 0,
}


def _sum_upsert_report(report: dict[str, int]) -> int:
    return report["inserted"] + report["updated"] + report["unchanged"]


def _fetch_and_prepare_proposal_data(
    proposal_numbers: list[str],
) -> dict[str, pd.DataFrame]:
    """One bulk Oracle read per dataset (IN-list pushdown across every
    requested proposal_number at once, via read_filtered) + prepare_*
    for all eight Proposal datasets. Shared by run_targeted_load
    (--proposal-number/--load-proposal-number: one Postgres transaction
    for the whole list) and _run_load_proposal_batch (--load-batch: one
    Postgres transaction PER family) - both read Oracle exactly once
    regardless of how many families are requested; only the Postgres
    write side differs between the two callers."""
    versions_source = OracleDataSource(VERSIONS_ORACLE_SQL)
    versions_raw = versions_source.read_filtered(
        column="proposal_number",
        values=proposal_numbers,
    )
    versions = prepare_versions(versions_raw)

    if versions.empty:
        logger.warning(
            "No PROPOSAL rows found for the requested "
            "proposal_number(s): {}",
            ", ".join(proposal_numbers),
        )

    proposal_ids = versions["proposal_id"].dropna().astype("int64").tolist()
    # proposal_id -> proposal_number, for datasets (awards) whose own
    # staged columns don't carry proposal_number at all - needed only to
    # split such a dataset back out per family for batch loading.
    proposal_number_by_id = dict(
        zip(versions["proposal_id"], versions["proposal_number"])
    )

    awards_source = OracleDataSource(AWARDS_ORACLE_SQL)
    awards_raw = (
        awards_source.read_filtered(column="proposal_id", values=proposal_ids)
        if proposal_ids
        else pd.DataFrame()
    )
    awards = prepare_awards(awards_raw) if not awards_raw.empty else awards_raw
    if not awards.empty:
        awards = awards.assign(
            proposal_number=awards["proposal_id"].map(proposal_number_by_id)
        )

    attachments_source = OracleDataSource(ATTACHMENTS_ORACLE_SQL)
    attachments_raw = (
        attachments_source.read_filtered(
            column="proposal_id", values=proposal_ids
        )
        if proposal_ids
        else pd.DataFrame()
    )
    attachments = (
        prepare_attachments(attachments_raw)
        if not attachments_raw.empty
        else attachments_raw
    )

    persons_source = OracleDataSource(PERSONS_ORACLE_SQL)
    persons_raw = (
        persons_source.read_filtered(column="proposal_id", values=proposal_ids)
        if proposal_ids
        else pd.DataFrame()
    )
    persons = prepare_persons(persons_raw) if not persons_raw.empty else persons_raw

    person_units_source = OracleDataSource(PERSON_UNITS_ORACLE_SQL)
    person_units_raw = (
        person_units_source.read_filtered(
            column="proposal_id", values=proposal_ids
        )
        if proposal_ids
        else pd.DataFrame()
    )
    person_units = (
        prepare_person_units(person_units_raw)
        if not person_units_raw.empty
        else person_units_raw
    )

    unit_contacts_source = OracleDataSource(UNIT_CONTACTS_ORACLE_SQL)
    unit_contacts_raw = (
        unit_contacts_source.read_filtered(
            column="proposal_id", values=proposal_ids
        )
        if proposal_ids
        else pd.DataFrame()
    )
    unit_contacts = (
        prepare_unit_contacts(unit_contacts_raw)
        if not unit_contacts_raw.empty
        else unit_contacts_raw
    )

    comments_source = OracleDataSource(COMMENTS_ORACLE_SQL)
    comments_raw = (
        comments_source.read_filtered(column="proposal_id", values=proposal_ids)
        if proposal_ids
        else pd.DataFrame()
    )
    comments = (
        prepare_comments(comments_raw)
        if not comments_raw.empty
        else comments_raw
    )

    custom_data_source = OracleDataSource(CUSTOM_DATA_ORACLE_SQL)
    custom_data_raw = (
        custom_data_source.read_filtered(column="proposal_id", values=proposal_ids)
        if proposal_ids
        else pd.DataFrame()
    )
    custom_data = (
        prepare_custom_data(custom_data_raw)
        if not custom_data_raw.empty
        else custom_data_raw
    )

    logger.info(
        "Prepared Proposal rows: versions={:,} awards={:,} attachments={:,} "
        "persons={:,} person_units={:,} unit_contacts={:,} comments={:,} "
        "custom_data={:,}",
        len(versions),
        len(awards),
        len(attachments),
        len(persons),
        len(person_units),
        len(unit_contacts),
        len(comments),
        len(custom_data),
    )

    return {
        "versions": versions,
        "awards": awards,
        "attachments": attachments,
        "persons": persons,
        "person_units": person_units,
        "unit_contacts": unit_contacts,
        "comments": comments,
        "custom_data": custom_data,
    }


def run_targeted_load(proposal_numbers: list[str]) -> None:
    logger.info(
        "Loading {} Proposal family/families: {}",
        len(proposal_numbers),
        ", ".join(proposal_numbers),
    )

    data = _fetch_and_prepare_proposal_data(proposal_numbers)
    versions = data["versions"]
    awards = data["awards"]
    attachments = data["attachments"]
    persons = data["persons"]
    person_units = data["person_units"]
    unit_contacts = data["unit_contacts"]
    comments = data["comments"]
    custom_data = data["custom_data"]

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        PROJECT_ROOT / "database" / "migrations",
    )

    total_rows = sum(len(dataframe) for dataframe in data.values())

    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
            version_report = upsert_proposal_versions(connection, versions)
            award_report = (
                upsert_proposal_awards(connection, awards)
                if not awards.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            attachment_report = (
                upsert_proposal_attachments(connection, attachments)
                if not attachments.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            # Persons before person_units: the latter's insert JOINs
            # back to archive.proposal_person and must see this
            # batch's parent rows already committed within the same
            # transaction.
            person_report = (
                upsert_proposal_persons(connection, persons)
                if not persons.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            person_unit_report = (
                upsert_proposal_person_units(connection, person_units)
                if not person_units.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            unit_contact_report = (
                upsert_proposal_unit_contacts(connection, unit_contacts)
                if not unit_contacts.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            comment_report = (
                upsert_proposal_comments(connection, comments)
                if not comments.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            custom_data_report = (
                upsert_proposal_custom_data(connection, custom_data)
                if not custom_data.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )

            total_written = (
                _sum_upsert_report(version_report)
                + _sum_upsert_report(award_report)
                + _sum_upsert_report(attachment_report)
                + _sum_upsert_report(person_report)
                + _sum_upsert_report(person_unit_report)
                + _sum_upsert_report(unit_contact_report)
                + _sum_upsert_report(comment_report)
                + _sum_upsert_report(custom_data_report)
            )

            mark_load_complete(
                connection,
                load_id,
                total_written,
            )

        logger.success(
            "Proposal targeted load completed. "
            "load_id={} families={} versions={} awards={} attachments={} "
            "persons={} person_units={} unit_contacts={} comments={} "
            "custom_data={} total={}",
            load_id,
            len(proposal_numbers),
            version_report,
            award_report,
            attachment_report,
            person_report,
            person_unit_report,
            unit_contact_report,
            comment_report,
            custom_data_report,
            total_written,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Proposal targeted load failed")
        raise


# --- Proposal batch framework --------------------------------------------
#
# Mirrors load_awards_from_csv.py's --create-batch/--load-batch/
# --show-batch (_run_create_award_batch/_run_load_award_batch/
# _run_show_batch), but Proposal's natural batch key is proposal_number -
# a string with significant leading zeros (e.g. "01157400"), never
# losslessly representable as archive.etl_batch_item.entity_key
# (BIGINT). archive.etl_batch (the parent manifest) is reused as-is;
# membership instead lives in the new archive.etl_batch_proposal_item
# table (see V065's migration comment), so these functions talk to that
# table directly rather than through archive_etl.batch.framework's
# entity_key-based helpers.
#
# Unlike Award's bulk-batch-as-one-transaction load, each family here is
# its own transaction: a failure in one family's write is caught,
# recorded on that family's own etl_batch_proposal_item row, and never
# blocks or rolls back its siblings. Oracle is still read only once for
# the whole batch (_fetch_and_prepare_proposal_data), only the Postgres
# write side is per-family.

PROPOSAL_BATCH_DOMAIN = "PROPOSAL"
PROPOSAL_BATCH_ENTITY_TYPE = "PROPOSAL_NUMBER"


def _excluded_proposal_numbers(engine: Engine) -> set[str]:
    """Production --create-batch's exclusion set: every proposal_number
    that either

    (a) is already COMPLETED as an etl_batch_proposal_item - regardless
        of that item's own batch's overall status, or
    (b) belongs to a batch that is still active (READY or PROCESSING),
        or
    (c) is already present in archive.proposal_version, regardless of
        whether any etl_batch_proposal_item row exists for it at all -
        this is what makes selection archive-aware rather than trusting
        batch-tracking history alone, closing the same real gap Award
        hit: every Proposal family loaded before this framework existed
        (via --proposal-number/--load-proposal-number, or the removed
        --max-families) has no batch-tracking history whatsoever, and
        (a)/(b) alone would let a later --create-batch reselect it for
        free.

    Mirrors load_awards_from_csv._excluded_completed_and_active_award_ids
    exactly, including deliberately NOT excluding FAILED/PENDING items
    belonging to an already-resolved batch - those proposal_numbers
    remain eligible for a later batch to pick up. Read-only; touches
    only archive.etl_batch/etl_batch_proposal_item/proposal_version,
    never Oracle."""
    with engine.connect() as connection:
        batch_tracked_rows = connection.execute(
            text(
                """
                SELECT DISTINCT ebpi.proposal_number
                FROM archive.etl_batch_proposal_item ebpi
                JOIN archive.etl_batch eb ON eb.batch_id = ebpi.batch_id
                WHERE eb.domain = :domain
                  AND eb.entity_type = :entity_type
                  AND (
                        ebpi.status = :completed_status
                        OR eb.status IN (:ready_status, :processing_status)
                  )
                """
            ),
            {
                "domain": PROPOSAL_BATCH_DOMAIN,
                "entity_type": PROPOSAL_BATCH_ENTITY_TYPE,
                "completed_status": batch_framework.ITEM_STATUS_COMPLETED,
                "ready_status": batch_framework.BATCH_STATUS_READY,
                "processing_status": batch_framework.BATCH_STATUS_PROCESSING,
            },
        ).scalars()
        already_archived_rows = connection.execute(
            text(
                "SELECT DISTINCT proposal_number FROM archive.proposal_version"
            )
        ).scalars()
        return {str(value) for value in batch_tracked_rows} | {
            str(value) for value in already_archived_rows
        }


def _select_proposal_numbers_ascending_excluding(
    source: OracleDataSource,
    requested_size: int,
    excluded: set[str],
) -> list[str]:
    """String-keyed equivalent of
    batch_framework.select_distinct_ascending_from_oracle_batches - that
    generic helper assumes a numeric entity_key (pd.to_numeric(...)),
    which would corrupt a proposal_number's significant leading zeros
    (e.g. "01157400" -> 1157400). Scans
    PROPOSAL_NUMBERS_ASCENDING_ORACLE_SQL (already ORDER BY
    PROPOSAL_NUMBER) batch by batch, keeping the first requested_size
    distinct, non-excluded values - stopping as soon as enough are
    found, or the source is exhausted first, in which case a smaller
    list is returned. Always closes the batch iterator via try/finally."""
    selected: list[str] = []
    seen: set[str] = set(excluded)
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            for value in batch["proposal_number"]:
                if value is None or (isinstance(value, float) and pd.isna(value)):
                    continue
                candidate = str(value)
                if candidate in seen:
                    continue
                seen.add(candidate)
                selected.append(candidate)
                if len(selected) >= requested_size:
                    break
            if len(selected) >= requested_size:
                break
    finally:
        batches.close()
    return selected


def _create_proposal_batch_record(
    engine: Engine,
    *,
    requested_size: int,
    selected_proposal_numbers: list[str],
    selection_strategy: str,
    run_id: str | None,
) -> dict[str, Any]:
    """Persists a new archive.etl_batch row (the shared, generic parent
    table, reused as-is) plus its archive.etl_batch_proposal_item
    membership - the Proposal-specific counterpart to
    batch_framework.create_batch, needed only because proposal_number
    doesn't fit that function's BIGINT entity_key contract. Raises
    ValueError for a non-positive requested_size."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    if len(selected_proposal_numbers) < requested_size:
        logger.warning(
            "Only {} of the requested {} distinct, eligible Proposal "
            "families were available - creating a smaller batch",
            len(selected_proposal_numbers),
            requested_size,
        )

    with engine.begin() as connection:
        batch_id = connection.execute(
            text(
                """
                INSERT INTO archive.etl_batch (
                    domain, entity_type, requested_size, status,
                    selection_strategy, created_by_run_id
                ) VALUES (
                    :domain, :entity_type, :requested_size, :status,
                    :selection_strategy, :run_id
                )
                RETURNING batch_id
                """
            ),
            {
                "domain": PROPOSAL_BATCH_DOMAIN,
                "entity_type": PROPOSAL_BATCH_ENTITY_TYPE,
                "requested_size": requested_size,
                "status": batch_framework.BATCH_STATUS_CREATED,
                "selection_strategy": selection_strategy,
                "run_id": run_id,
            },
        ).scalar_one()

        for ordinal, proposal_number in enumerate(
            selected_proposal_numbers, start=1
        ):
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch_proposal_item (
                        batch_id, proposal_number, ordinal, status
                    ) VALUES (
                        :batch_id, :proposal_number, :ordinal, :status
                    )
                    """
                ),
                {
                    "batch_id": batch_id,
                    "proposal_number": proposal_number,
                    "ordinal": ordinal,
                    "status": batch_framework.ITEM_STATUS_PENDING,
                },
            )

    logger.bind(
        stage="create_proposal_batch",
        batch_id=batch_id,
        run_id=run_id,
    ).info(
        "Created batch_id={} requested_size={} selected={} "
        "proposal_numbers={}",
        batch_id,
        requested_size,
        len(selected_proposal_numbers),
        selected_proposal_numbers,
    )

    return {
        "batch_id": int(batch_id),
        "requested_size": requested_size,
        "selected_count": len(selected_proposal_numbers),
        "selected_proposal_numbers": selected_proposal_numbers,
    }


def _run_create_proposal_batch(
    engine: Engine, requested_size: int, *, run_id: str | None = None
) -> dict[str, Any]:
    """--create-batch: select exactly `requested_size` distinct,
    genuinely-new proposal_numbers, in stable ascending order, and
    persist that exact membership as a new batch. See
    _excluded_proposal_numbers for what "genuinely new" excludes."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    excluded_proposal_numbers = _excluded_proposal_numbers(engine)
    selected_proposal_numbers = _select_proposal_numbers_ascending_excluding(
        OracleDataSource(PROPOSAL_NUMBERS_ASCENDING_ORACLE_SQL),
        requested_size,
        excluded_proposal_numbers,
    )

    return _create_proposal_batch_record(
        engine,
        requested_size=requested_size,
        selected_proposal_numbers=selected_proposal_numbers,
        selection_strategy="ASCENDING_PROPOSAL_NUMBER_EXCL_COMPLETED",
        run_id=run_id,
    )


def _assert_proposal_batch(connection: Connection, batch_id: int) -> None:
    """Raises RuntimeError if batch_id doesn't exist, or exists but was
    created for a different domain/entity_type - the same defense
    batch_framework.assert_batch_matches provides for entity_key-based
    domains, reimplemented here since Proposal batches never go through
    that function."""
    row = connection.execute(
        text(
            "SELECT domain, entity_type FROM archive.etl_batch "
            "WHERE batch_id = :batch_id"
        ),
        {"batch_id": batch_id},
    ).mappings().one_or_none()

    if row is None:
        raise RuntimeError(f"batch_id={batch_id} does not exist")

    if (
        row["domain"] != PROPOSAL_BATCH_DOMAIN
        or row["entity_type"] != PROPOSAL_BATCH_ENTITY_TYPE
    ):
        raise RuntimeError(
            f"batch_id={batch_id} was created for domain={row['domain']!r} "
            f"entity_type={row['entity_type']!r}, not a Proposal batch"
        )


def _run_show_proposal_batch(engine: Engine, batch_id: int) -> dict[str, Any]:
    """--show-batch: read-only status report for one Proposal batch.
    Mirrors batch_framework.show_batch's shape exactly, scoped to
    archive.etl_batch_proposal_item instead of etl_batch_item."""
    with engine.connect() as connection:
        batch_row = connection.execute(
            text(
                "SELECT batch_id, domain, entity_type, requested_size, "
                "status, created_at FROM archive.etl_batch "
                "WHERE batch_id = :batch_id"
            ),
            {"batch_id": batch_id},
        ).mappings().one_or_none()

        if batch_row is None:
            return {"batch_id": batch_id, "found": False}

        if (
            batch_row["domain"] != PROPOSAL_BATCH_DOMAIN
            or batch_row["entity_type"] != PROPOSAL_BATCH_ENTITY_TYPE
        ):
            raise RuntimeError(
                f"batch_id={batch_id} was created for domain="
                f"{batch_row['domain']!r} entity_type="
                f"{batch_row['entity_type']!r}, not a Proposal batch"
            )

        total_items = connection.execute(
            text(
                "SELECT COUNT(*) FROM archive.etl_batch_proposal_item "
                "WHERE batch_id = :batch_id"
            ),
            {"batch_id": batch_id},
        ).scalar_one()

        status_rows = connection.execute(
            text(
                "SELECT status, COUNT(*) FROM archive.etl_batch_proposal_item "
                "WHERE batch_id = :batch_id GROUP BY status"
            ),
            {"batch_id": batch_id},
        ).all()
        item_status_counts: dict[str, int] = {
            row[0]: row[1] for row in status_rows
        }

    return {
        "batch_id": batch_row["batch_id"],
        "found": True,
        "domain": batch_row["domain"],
        "entity_type": batch_row["entity_type"],
        "requested_size": batch_row["requested_size"],
        "created_at": batch_row["created_at"],
        "status": batch_row["status"],
        "total_items": total_items,
        "pending": item_status_counts.get(
            batch_framework.ITEM_STATUS_PENDING, 0
        ),
        "processing": item_status_counts.get(
            batch_framework.ITEM_STATUS_PROCESSING, 0
        ),
        "completed": item_status_counts.get(
            batch_framework.ITEM_STATUS_COMPLETED, 0
        ),
        "failed": item_status_counts.get(
            batch_framework.ITEM_STATUS_FAILED, 0
        ),
        "missing_source": item_status_counts.get(
            batch_framework.ITEM_STATUS_MISSING_SOURCE, 0
        ),
        "skipped": item_status_counts.get(
            batch_framework.ITEM_STATUS_SKIPPED, 0
        ),
    }


_UPSERT_REPORT_KEYS = (
    "version",
    "award",
    "attachment",
    "person",
    "person_unit",
    "unit_contact",
    "comment",
    "custom_data",
)


def _run_load_proposal_batch(
    engine: Engine,
    batch_id: int,
    *,
    dry_run: bool = False,
    run_id: str | None = None,
) -> dict[str, Any]:
    """--load-batch: idempotent load for this batch's PENDING/FAILED
    proposal_number membership. Oracle is read exactly ONCE for the
    whole batch (via _fetch_and_prepare_proposal_data, the same bulk
    IN-list-pushdown reads run_targeted_load uses), but each family is
    its own Postgres transaction - a failure in one family's write is
    caught, recorded on that family's own etl_batch_proposal_item row
    (status=FAILED, last_error, attempt_count incremented), and never
    blocks or rolls back its siblings. A family already COMPLETED is
    never reprocessed, so re-running --load-batch on a partially-failed
    batch only retries the families that didn't finish - it never
    reloads the whole batch."""
    load_logger = logger.bind(
        stage="load_proposal_batch", batch_id=batch_id, run_id=run_id
    )
    batch_started = time.perf_counter()

    with engine.connect() as connection:
        _assert_proposal_batch(connection, batch_id)
        item_rows = connection.execute(
            text(
                "SELECT proposal_number, status "
                "FROM archive.etl_batch_proposal_item "
                "WHERE batch_id = :batch_id ORDER BY ordinal"
            ),
            {"batch_id": batch_id},
        ).mappings().all()

    all_proposal_numbers = [row["proposal_number"] for row in item_rows]
    pending_proposal_numbers = [
        row["proposal_number"]
        for row in item_rows
        if row["status"]
        in (
            batch_framework.ITEM_STATUS_PENDING,
            batch_framework.ITEM_STATUS_FAILED,
        )
    ]

    report: dict[str, Any] = {
        "batch_id": batch_id,
        "requested_families": len(all_proposal_numbers),
        "selected_families": len(pending_proposal_numbers),
        "completed_families": 0,
        "failed_families": 0,
        "missing_linked_awards": 0,
        "attachment_metadata_count": 0,
    }
    for key in _UPSERT_REPORT_KEYS:
        report[f"{key}_inserted"] = 0
        report[f"{key}_updated"] = 0
        report[f"{key}_unchanged"] = 0

    if not pending_proposal_numbers:
        load_logger.info(
            "No PENDING/FAILED items to load for batch_id={}", batch_id
        )
        return report

    if dry_run:
        load_logger.info(
            "Dry run: would load {} Proposal family/families for "
            "batch_id={} - skipping database write.",
            len(pending_proposal_numbers),
            batch_id,
        )
        return report

    apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    data = _fetch_and_prepare_proposal_data(pending_proposal_numbers)

    with engine.begin() as connection:
        batch_framework.set_batch_status(
            connection, batch_id, status=batch_framework.BATCH_STATUS_PROCESSING
        )

    for proposal_number in pending_proposal_numbers:
        family_logger = load_logger.bind(proposal_number=proposal_number)

        family_data = {
            key: (
                dataframe[dataframe["proposal_number"] == proposal_number]
                if not dataframe.empty
                else dataframe
            )
            for key, dataframe in data.items()
        }

        with engine.begin() as connection:
            connection.execute(
                text(
                    """
                    UPDATE archive.etl_batch_proposal_item
                       SET status = :status,
                           started_at = COALESCE(started_at, CURRENT_TIMESTAMP)
                     WHERE batch_id = :batch_id
                       AND proposal_number = :proposal_number
                    """
                ),
                {
                    "status": batch_framework.ITEM_STATUS_PROCESSING,
                    "batch_id": batch_id,
                    "proposal_number": proposal_number,
                },
            )

        try:
            with engine.begin() as connection:
                upsert_reports = {
                    "version": upsert_proposal_versions(
                        connection, family_data["versions"]
                    ),
                    "award": (
                        upsert_proposal_awards(connection, family_data["awards"])
                        if not family_data["awards"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "attachment": (
                        upsert_proposal_attachments(
                            connection, family_data["attachments"]
                        )
                        if not family_data["attachments"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "person": (
                        upsert_proposal_persons(connection, family_data["persons"])
                        if not family_data["persons"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "person_unit": (
                        upsert_proposal_person_units(
                            connection, family_data["person_units"]
                        )
                        if not family_data["person_units"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "unit_contact": (
                        upsert_proposal_unit_contacts(
                            connection, family_data["unit_contacts"]
                        )
                        if not family_data["unit_contacts"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "comment": (
                        upsert_proposal_comments(connection, family_data["comments"])
                        if not family_data["comments"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                    "custom_data": (
                        upsert_proposal_custom_data(
                            connection, family_data["custom_data"]
                        )
                        if not family_data["custom_data"].empty
                        else dict(_EMPTY_UPSERT_REPORT)
                    ),
                }

                connection.execute(
                    text(
                        """
                        UPDATE archive.etl_batch_proposal_item
                           SET status = :status,
                               completed_at = CURRENT_TIMESTAMP
                         WHERE batch_id = :batch_id
                           AND proposal_number = :proposal_number
                        """
                    ),
                    {
                        "status": batch_framework.ITEM_STATUS_COMPLETED,
                        "batch_id": batch_id,
                        "proposal_number": proposal_number,
                    },
                )

            report["completed_families"] += 1
            report["missing_linked_awards"] += upsert_reports["award"]["skipped"]
            report["attachment_metadata_count"] += len(
                family_data["attachments"]
            )
            for key in _UPSERT_REPORT_KEYS:
                report[f"{key}_inserted"] += upsert_reports[key]["inserted"]
                report[f"{key}_updated"] += upsert_reports[key]["updated"]
                report[f"{key}_unchanged"] += upsert_reports[key]["unchanged"]

        except Exception as error:
            with engine.begin() as connection:
                connection.execute(
                    text(
                        """
                        UPDATE archive.etl_batch_proposal_item
                           SET status = :status,
                               last_error = :error,
                               attempt_count = attempt_count + 1
                         WHERE batch_id = :batch_id
                           AND proposal_number = :proposal_number
                        """
                    ),
                    {
                        "status": batch_framework.ITEM_STATUS_FAILED,
                        "error": redact_error_message(str(error)),
                        "batch_id": batch_id,
                        "proposal_number": proposal_number,
                    },
                )
            report["failed_families"] += 1
            family_logger.exception(
                "Proposal batch family {} failed - continuing with "
                "remaining families",
                proposal_number,
            )
            continue

    final_batch_status = (
        batch_framework.BATCH_STATUS_COMPLETED
        if report["failed_families"] == 0
        else batch_framework.BATCH_STATUS_PARTIAL
    )
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.etl_batch
                   SET status = :status,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE batch_id = :batch_id
                """
            ),
            {"status": final_batch_status, "batch_id": batch_id},
        )

    elapsed = time.perf_counter() - batch_started
    load_logger.success(
        "Proposal batch load completed in {:.1f}s. batch_id={} "
        "requested={} attempted={} completed={} failed={}",
        elapsed,
        batch_id,
        report["requested_families"],
        report["selected_families"],
        report["completed_families"],
        report["failed_families"],
    )
    return report


def _connect_oracle() -> oracledb.Connection:
    """Mirrors load_awards_from_csv.py's own private _connect_oracle()
    exactly (not shared from there) - reads the same already-shared
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


def _run_ecs_setup(arguments: argparse.Namespace, run_id: str) -> None:
    """--ecs mode setup, mirroring load_awards_from_csv.py's own
    _run_ecs_setup in shape and order: structured logging, AWS task-role
    identity via STS, Secrets Manager credential resolution (Oracle
    skipped for --show-batch, which is PostgreSQL-only), then a
    PostgreSQL reachability check and - unless --show-batch - an Oracle
    reachability check. Aborts immediately (lets the raised exception
    propagate) if any step fails."""
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
        include_oracle=arguments.show_batch is None,
    )

    engine = create_postgres_engine()
    validate_postgres_reachable(engine)
    logger.bind(stage="startup").info("PostgreSQL reachable")

    if arguments.show_batch is not None:
        return

    validate_oracle_reachable(_connect_oracle)
    logger.bind(stage="startup").info("Oracle reachable")
    logger.bind(stage="startup").info("Startup validation passed")


def main() -> None:
    arguments = parse_args()
    run_id = str(uuid.uuid4())

    if arguments.ecs:
        _run_ecs_setup(arguments, run_id)

    if arguments.create_batch is not None:
        engine = create_postgres_engine()
        apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")
        result = _run_create_proposal_batch(engine, arguments.create_batch)
        logger.info(
            "batch_id={} requested={} selected={} proposal_numbers={}",
            result["batch_id"],
            result["requested_size"],
            result["selected_count"],
            result["selected_proposal_numbers"],
        )
        return

    if arguments.load_batch is not None:
        engine = create_postgres_engine()
        report = _run_load_proposal_batch(
            engine, arguments.load_batch, dry_run=arguments.dry_run
        )
        logger.info("Proposal batch load report: {}", report)
        return

    if arguments.show_batch is not None:
        engine = create_postgres_engine()
        report = _run_show_proposal_batch(engine, arguments.show_batch)
        logger.info("Proposal batch status: {}", report)
        return

    if arguments.proposal_number:
        run_targeted_load(arguments.proposal_number)
        return

    logger.info("Reading Proposal versions/awards from Oracle")
    versions = prepare_versions(OracleDataSource(VERSIONS_ORACLE_SQL).read())
    awards_raw = OracleDataSource(AWARDS_ORACLE_SQL).read()
    awards = prepare_awards(awards_raw) if not awards_raw.empty else awards_raw

    if arguments.limit is not None:
        versions = versions.head(arguments.limit)
        awards = awards.head(arguments.limit)
        logger.info(
            "Dry run (--limit {}): read versions={} awards={} - "
            "skipping database write.",
            arguments.limit,
            len(versions),
            len(awards),
        )
        return

    logger.info(
        "Prepared Proposal rows: versions={:,} awards={:,}",
        len(versions),
        len(awards),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        PROJECT_ROOT / "database" / "migrations",
    )

    total_rows = len(versions) + len(awards)

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins - otherwise a failure would roll back
    # the STARTED row along with everything else, and mark_load_failed
    # would silently update zero rows, leaving no trace of the failure.
    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
            clear_existing_proposal_data(connection)

            version_rows = load_dataframe(
                connection,
                versions,
                "proposal_version",
                VERSION_COLUMNS,
            )

            award_report = (
                upsert_proposal_awards(connection, awards)
                if not awards.empty
                else dict(_EMPTY_UPSERT_REPORT)
            )
            award_rows = _sum_upsert_report(award_report)

            mark_load_complete(
                connection,
                load_id,
                version_rows + award_rows,
            )

        logger.success(
            "Proposal load completed. "
            "load_id={} versions={} awards={} total={}",
            load_id,
            version_rows,
            award_report,
            version_rows + award_rows,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Proposal load failed")
        raise


if __name__ == "__main__":
    main()
