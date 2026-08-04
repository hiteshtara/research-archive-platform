from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

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
        action="append",
        default=None,
        help=(
            "Load only the given PROPOSAL_NUMBER family/families "
            "(repeatable). A real, bounded, idempotent UPSERT - never a "
            "dry run. Every version in scope is loaded regardless of "
            "proposal_sequence_status (preserve every version - see "
            "docs/kuali-business-rules/InstitutionalProposal.md)."
        ),
    )
    parser.add_argument(
        "--max-families",
        type=int,
        default=None,
        help=(
            "Resolve the first N distinct PROPOSAL_NUMBERs from Oracle "
            "(ordered by proposal_number) and load exactly those "
            "families for real - a bounded batch, same idempotent "
            "UPSERT path as --proposal-number. Mutually exclusive with "
            "--proposal-number."
        ),
    )
    args = parser.parse_args(arguments)

    if args.proposal_number and args.max_families is not None:
        parser.error("--proposal-number and --max-families are mutually exclusive")

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


def upsert_proposal_versions(
    connection: Connection,
    versions: pd.DataFrame,
) -> int:
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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_awards(
    connection: Connection,
    awards: pd.DataFrame,
) -> int:
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

    result = connection.execute(
        text(
            """
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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_attachments(
    connection: Connection,
    attachments: pd.DataFrame,
) -> int:
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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_persons(
    connection: Connection,
    persons: pd.DataFrame,
) -> int:
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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_person_units(
    connection: Connection,
    person_units: pd.DataFrame,
) -> int:
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

    update_columns = [
        column
        for column in PERSON_UNIT_COLUMNS
        if column != "proposal_person_unit_id"
    ]

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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_unit_contacts(
    connection: Connection,
    unit_contacts: pd.DataFrame,
) -> int:
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
            """
        )
    )

    return int(result.rowcount)


def upsert_proposal_comments(
    connection: Connection,
    comments: pd.DataFrame,
) -> int:
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
            """
        )
    )

    return int(result.rowcount)


def resolve_target_proposal_numbers(max_families: int) -> list[str]:
    """--max-families: resolve the first N distinct PROPOSAL_NUMBERs
    from Oracle (ordered), for a real, bounded batch load - the same
    "small, bounded, verify, then proceed" discipline used for Award's
    own batch population work, without building a persistent
    batch-tracking framework in this first Proposal loading pass."""
    source = OracleDataSource(VERSIONS_ORACLE_SQL)
    all_versions = source.read()
    distinct_numbers = (
        all_versions["proposal_number"]
        .drop_duplicates()
        .sort_values()
        .head(max_families)
        .tolist()
    )
    return distinct_numbers


def run_targeted_load(proposal_numbers: list[str]) -> None:
    logger.info(
        "Loading {} Proposal family/families: {}",
        len(proposal_numbers),
        ", ".join(proposal_numbers),
    )

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

    awards_source = OracleDataSource(AWARDS_ORACLE_SQL)
    awards_raw = (
        awards_source.read_filtered(column="proposal_id", values=proposal_ids)
        if proposal_ids
        else pd.DataFrame()
    )
    awards = prepare_awards(awards_raw) if not awards_raw.empty else awards_raw

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

    logger.info(
        "Prepared Proposal rows: versions={:,} awards={:,} attachments={:,} "
        "persons={:,} person_units={:,} unit_contacts={:,} comments={:,}",
        len(versions),
        len(awards),
        len(attachments),
        len(persons),
        len(person_units),
        len(unit_contacts),
        len(comments),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        PROJECT_ROOT / "database" / "migrations",
    )

    total_rows = (
        len(versions)
        + len(awards)
        + len(attachments)
        + len(persons)
        + len(person_units)
        + len(unit_contacts)
        + len(comments)
    )

    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
            version_rows = upsert_proposal_versions(connection, versions)
            award_rows = (
                upsert_proposal_awards(connection, awards)
                if not awards.empty
                else 0
            )
            attachment_rows = (
                upsert_proposal_attachments(connection, attachments)
                if not attachments.empty
                else 0
            )
            # Persons before person_units: the latter's insert JOINs
            # back to archive.proposal_person and must see this
            # batch's parent rows already committed within the same
            # transaction.
            person_rows = (
                upsert_proposal_persons(connection, persons)
                if not persons.empty
                else 0
            )
            person_unit_rows = (
                upsert_proposal_person_units(connection, person_units)
                if not person_units.empty
                else 0
            )
            unit_contact_rows = (
                upsert_proposal_unit_contacts(connection, unit_contacts)
                if not unit_contacts.empty
                else 0
            )
            comment_rows = (
                upsert_proposal_comments(connection, comments)
                if not comments.empty
                else 0
            )

            total_written = (
                version_rows
                + award_rows
                + attachment_rows
                + person_rows
                + person_unit_rows
                + unit_contact_rows
                + comment_rows
            )

            mark_load_complete(
                connection,
                load_id,
                total_written,
            )

        logger.success(
            "Proposal targeted load completed. "
            "load_id={} families={} versions={} awards={} attachments={} "
            "persons={} person_units={} unit_contacts={} comments={} total={}",
            load_id,
            len(proposal_numbers),
            version_rows,
            award_rows,
            attachment_rows,
            person_rows,
            person_unit_rows,
            unit_contact_rows,
            comment_rows,
            total_written,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Proposal targeted load failed")
        raise


def main() -> None:
    arguments = parse_args()

    if arguments.max_families is not None:
        proposal_numbers = resolve_target_proposal_numbers(arguments.max_families)
        run_targeted_load(proposal_numbers)
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

            award_rows = (
                upsert_proposal_awards(connection, awards)
                if not awards.empty
                else 0
            )

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
            award_rows,
            version_rows + award_rows,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Proposal load failed")
        raise


if __name__ == "__main__":
    main()
