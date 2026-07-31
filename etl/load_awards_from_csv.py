from __future__ import annotations

import argparse
import os
import uuid
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
# amount_info/person/funding_proposal child rows - safe to run against a
# database that already has other Award data loaded, and safe to re-run.
# Deliberately scoped to exactly the four tables the full load already
# populates - no Award Budget, Award Custom Data, Award Reporting, Award
# Contacts, Award Terms, or Time & Money workflow tables are touched here.
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
    """Scan the Award version Oracle source, stopping as soon as the row
    for this exact award_id is found (award_id is unique per row - it is
    AWARD's own primary key), and return its award_number, or None if
    award_id isn't found at all. Used only to resolve which whole
    award_number version family a bounded --load-award-id request
    belongs to."""
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            ids = pd.to_numeric(batch["award_id"], errors="coerce")
            match = batch[ids == award_id]
            if not match.empty:
                return str(match.iloc[0]["award_number"])
    finally:
        batches.close()
    return None


def read_award_versions_matching_award_numbers(
    source: OracleDataSource, target_award_numbers: set[str]
) -> pd.DataFrame:
    """Scan the Award version Oracle source, keeping only rows whose
    award_number is an exact match in target_award_numbers. Always scans
    the full source - award_number is not unique per row (one row per
    sequence_number in the family), so an early stop after the first
    match per award_number would silently drop older versions."""
    if not target_award_numbers:
        return pd.DataFrame()

    collected: list[pd.DataFrame] = []
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            mask = batch["award_number"].isin(target_award_numbers)
            if mask.any():
                collected.append(batch[mask])
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True)


def read_award_children_matching_award_ids(
    source: OracleDataSource, target_award_ids: set[int]
) -> pd.DataFrame:
    """Shared by amounts/people/proposals: scan the full source, keeping
    only rows whose award_id is an exact match in target_award_ids.
    Always scans the full source - award_id is not unique on any of
    these three sources (many amount/person/funding-proposal rows can
    share one award_id)."""
    if not target_award_ids:
        return pd.DataFrame()

    collected: list[pd.DataFrame] = []
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            ids = pd.to_numeric(batch["award_id"], errors="coerce")
            mask = ids.isin(target_award_ids)
            if mask.any():
                collected.append(batch[mask])
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True)


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
        "missing": 0,
    }


def _run_load_award_id(
    engine: Engine, award_id: int, *, dry_run: bool = False, run_id: str | None = None
) -> dict[str, Any]:
    """--load-award-id: idempotent incremental UPSERT for exactly one
    award_id's ENTIRE award_number version family (see the module-level
    comment above for why this widens beyond the single requested
    award_id) plus that family's amount_info/person/funding_proposal
    child rows. Never truncates or replaces the full tables, never
    touches Award Budget/Custom Data/Reporting/Contacts/Terms/Time and
    Money. With dry_run=True, every UPSERT still runs (so the reported
    counts are accurate) but the whole transaction is rolled back
    instead of committed."""
    load_logger = logger.bind(stage="load_award_id", award_id=award_id, run_id=run_id)

    award_number = read_award_number_for_award_id(
        OracleDataSource(VERSIONS_ORACLE_SQL), award_id
    )
    if award_number is None:
        load_logger.info(
            "award_id={} not found in Oracle - nothing to load", award_id
        )
        report = _empty_load_award_id_report(award_id)
        report["missing"] = 1
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

    report = _empty_load_award_id_report(award_id)
    report["award_number"] = award_number
    report["family_size"] = len(family_award_ids)

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            total_rows = len(versions) + len(amounts) + len(people) + len(proposals)
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

            mark_load_complete(connection, load_id, total_rows)
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    load_logger.info(
        "Incremental Award load for award_id={} (award_number={} "
        "family_size={}){}: version(inserted={} updated={} unchanged={}) "
        "amount_info(inserted={} updated={} unchanged={}) "
        "person(inserted={} updated={} unchanged={}) "
        "funding_proposal(inserted={} updated={} unchanged={})",
        award_id,
        award_number,
        report["family_size"],
        " [DRY RUN - not persisted]" if dry_run else "",
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
    """--load-batch: idempotent incremental load for exactly this
    batch's recorded award_id membership. Each award_id's load widens to
    its whole award_number family internally (see _run_load_award_id) -
    batch *membership* itself is never modified by this. Distinct
    award_ids in the same batch that happen to share an award_number are
    only scanned from Oracle once (the second one's data was already
    upserted as a side effect of the first's family scan).

    Note on dry_run scope: each family's own UPSERT transaction rolls
    back independently under dry_run (see _run_load_award_id) - the
    batch-item status update for each award_id is a separate, always-
    committed bookkeeping step ("was this item attempted"), not part of
    that per-family rollback. This is an intentional, narrower dry_run
    scope than the Award Attachment domain's single-transaction
    metadata load, chosen because sharing one transaction across
    multiple independently-resolved award_number families would be
    awkward and wouldn't add real safety here."""
    load_logger = logger.bind(stage="load_award_batch", batch_id=batch_id, run_id=run_id)

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
        "missing_in_oracle": 0,
    }

    seen_award_numbers: set[str] = set()
    for award_id in award_ids:
        award_number = read_award_number_for_award_id(
            OracleDataSource(VERSIONS_ORACLE_SQL), award_id
        )
        if award_number is None:
            report["missing_in_oracle"] += 1
            with engine.begin() as connection:
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    award_id,
                    status=batch_framework.ITEM_STATUS_MISSING_SOURCE,
                )
            continue

        if award_number in seen_award_numbers:
            with engine.begin() as connection:
                batch_framework.set_item_status(
                    connection,
                    batch_id,
                    award_id,
                    status=batch_framework.ITEM_STATUS_COMPLETED,
                )
            continue
        seen_award_numbers.add(award_number)

        family_report = _run_load_award_id(
            engine, award_id, dry_run=dry_run, run_id=run_id
        )
        report["families_loaded"] += 1
        for key in (
            "inserted",
            "updated",
            "unchanged",
            "amount_info_inserted",
            "amount_info_updated",
            "amount_info_unchanged",
            "person_inserted",
            "person_updated",
            "person_unchanged",
            "funding_proposal_inserted",
            "funding_proposal_updated",
            "funding_proposal_unchanged",
        ):
            report[key] += family_report[key]

        with engine.begin() as connection:
            batch_framework.set_item_status(
                connection,
                batch_id,
                award_id,
                status=batch_framework.ITEM_STATUS_COMPLETED,
            )

    with engine.begin() as connection:
        batch_framework.set_batch_status(
            connection, batch_id, status=batch_framework.BATCH_STATUS_READY
        )

    load_logger.info(
        "Batch Award load for batch_id={}{}: requested_award_ids={} "
        "families_loaded={} version(inserted={} updated={} unchanged={}) "
        "missing_in_oracle={}",
        batch_id,
        " [DRY RUN - not persisted]" if dry_run else "",
        report["requested_award_ids"],
        report["families_loaded"],
        report["inserted"],
        report["updated"],
        report["unchanged"],
        report["missing_in_oracle"],
    )
    return report


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
            "why) plus its amount_info/person/funding_proposal child "
            "rows. Never truncates or replaces the full tables. Scoped "
            "strictly to these four tables - no Award Budget/Custom "
            "Data/Reporting/Contacts/Terms/Time and Money."
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
