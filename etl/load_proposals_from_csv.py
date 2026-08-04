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

# Oracle extraction queries exist and match the loader's expected columns
# for versions and awards. Proposal people had no verified Oracle extraction
# query and has been removed entirely (API, UI, ETL, and the
# archive.proposal_person table) - see docs/DECISIONS.md. That decision is
# about the Person/PersonUnit/UnitContact feature specifically, not about
# Proposal as a whole - see docs/kuali-business-rules/InstitutionalProposal.md
# for the live-verified People relationship this loader does not yet cover.
VERSIONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "01_proposal_versions.sql"
)
AWARDS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "04_award_proposals.sql"
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


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Proposal versions/awards from Oracle. People is not "
            "loaded - see docs/DECISIONS.md."
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
        preview = ", ".join(
            str(award_id)
            for award_id in unresolved_award_ids[:20]
        )

        raise RuntimeError(
            "award_proposals.csv contains Award IDs that do not "
            "exist in archive.award_version: "
            + preview
        )

    logger.info(
        "UPSERT {:<28} {:,} rows",
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

    logger.info(
        "Prepared Proposal rows: versions={:,} awards={:,}",
        len(versions),
        len(awards),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1] / "database" / "migrations",
    )

    total_rows = len(versions) + len(awards)

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

            mark_load_complete(
                connection,
                load_id,
                version_rows + award_rows,
            )

        logger.success(
            "Proposal targeted load completed. "
            "load_id={} families={} versions={} awards={} total={}",
            load_id,
            len(proposal_numbers),
            version_rows,
            award_rows,
            version_rows + award_rows,
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
        Path(__file__).resolve().parents[1]
        / "database"
        / "migrations",
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
