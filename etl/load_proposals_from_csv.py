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

PROJECT_ROOT = Path(__file__).resolve().parents[1]

# Oracle extraction queries exist and match the loader's expected columns
# for versions and awards. proposal_people has no equivalent Oracle query:
# oracle/proposal's only people-shaped query
# (sql/extract/proposal/04_proposal_3892_people.sql) is a one-off diagnostic
# hardcoded to a single proposal_id, and doesn't produce
# academic_year_effort/calendar_year_effort/summer_effort/total_effort/
# ver_nbr/source_update_user - columns the retired CSV path used to load.
# Rather than guess at Oracle columns that may not exist on
# PROPOSAL_PERSONS, this dataset is no longer loaded at all as of the CSV
# retirement - see docs/DECISIONS.md. Unlike award_unit_contact,
# archive.proposal_person has no FK to proposal_version, so it is not
# included in clear_existing_proposal_data()'s TRUNCATE - existing rows are
# left completely untouched, not emptied.
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
    "title",
    "proposal_sequence_status",
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
    return parser.parse_args(arguments)


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

    convert_numeric(
        dataframe,
        [
            "proposal_id",
            "version_number",
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
        ["proposal_id", "award_id"],
    )

    require_values(
        dataframe,
        ["proposal_id", "award_id"],
        "award_proposals.csv",
    )

    duplicate_links = dataframe.duplicated(
        subset=["proposal_id", "award_id"],
        keep="first",
    )

    if duplicate_links.any():
        logger.warning(
            "Removed {} duplicate Proposal/Award relationships",
            int(duplicate_links.sum()),
        )

        dataframe = dataframe.loc[
            ~duplicate_links
        ].copy()

    return dataframe[["proposal_id", "award_id"]].copy()


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

    # archive.proposal_person is intentionally not truncated here: this
    # dataset is no longer loaded (see the module docstring comment near
    # the top of this file), and unlike award_unit_contact it has no FK to
    # proposal_version, so existing rows are simply left untouched rather
    # than truncated with nothing to replace them.
    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.proposal_award,
                archive.proposal_version;
            """
        )
    )


def load_proposal_awards(
    connection: Connection,
    awards: pd.DataFrame,
) -> int:
    connection.execute(
        text(
            """
            CREATE TEMPORARY TABLE proposal_award_stage (
                proposal_id BIGINT NOT NULL,
                award_id BIGINT NOT NULL
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
        dataframe=awards,
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
        "INSERT {:<28} {:,} rows",
        "proposal_award",
        len(awards),
    )

    result = connection.execute(
        text(
            """
            INSERT INTO archive.proposal_award (
                proposal_id,
                award_id,
                award_number
            )
            SELECT
                stage.proposal_id,
                stage.award_id,
                award.award_number
            FROM proposal_award_stage stage
            JOIN archive.award_version award
                ON award.award_id = stage.award_id
            """
        )
    )

    return int(result.rowcount)


def main() -> None:
    arguments = parse_args()

    logger.info("Reading Proposal versions/awards from Oracle")
    versions = prepare_versions(OracleDataSource(VERSIONS_ORACLE_SQL).read())
    awards = prepare_awards(OracleDataSource(AWARDS_ORACLE_SQL).read())

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

            award_rows = load_proposal_awards(
                connection,
                awards,
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
