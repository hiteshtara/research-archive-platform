from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

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
# for versions and awards. proposal_people.csv has no equivalent Oracle
# query yet: oracle/proposal's only people-shaped query
# (sql/extract/proposal/04_proposal_3892_people.sql) is a one-off diagnostic
# hardcoded to a single proposal_id, and doesn't produce
# academic_year_effort/calendar_year_effort/summer_effort/total_effort/
# ver_nbr/source_update_user - columns the CSV path currently loads. Rather
# than guess at Oracle columns that may not exist on PROPOSAL_PERSONS,
# people is always read from CSV until a verified extraction query exists.
VERSIONS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "01_proposal_versions.sql"
)
AWARDS_ORACLE_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "award" / "04_award_proposals.sql"
)

DOWNLOAD_DIR = Path.home() / "Downloads"

FILES = {
    "versions": DOWNLOAD_DIR / "proposal_versions.csv",
    "people": DOWNLOAD_DIR / "proposal_people.csv",
    "awards": DOWNLOAD_DIR / "award_proposals.csv",
}

VERSION_REQUIRED_COLUMNS = {
    "proposal_id",
    "proposal_number",
    "version_number",
}

PERSON_REQUIRED_COLUMNS = {
    "proposal_id",
    "version_number",
    "role_code",
    "principal_investigator",
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

PERSON_COLUMNS = [
    "proposal_id",
    "version_number",
    "person_id",
    "full_name",
    "role",
    "project_role",
    "principal_investigator",
    "faculty_flag",
    "academic_year_effort",
    "calendar_year_effort",
    "summer_effort",
    "total_effort",
    "source_update_timestamp",
    "source_update_user",
    "ver_nbr",
]


def require_files(paths: list[Path]) -> None:
    missing = [
        str(path)
        for path in paths
        if not path.exists()
    ]

    if missing:
        raise RuntimeError(
            "Missing required Proposal CSV files:\n"
            + "\n".join(missing)
        )


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load Proposal versions/awards from Oracle (default) or a CSV "
            "export set. People has no Oracle extraction query yet and is "
            "always read from CSV."
        )
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument(
        "--oracle",
        action="store_true",
        help="Read versions/awards directly from Oracle (the default).",
    )
    source.add_argument(
        "--csv",
        action="store_true",
        help="Read all three datasets from the existing CSV export set.",
    )
    parser.add_argument(
        "--csv-dir",
        type=Path,
        default=DOWNLOAD_DIR,
        help=(
            "Directory containing the CSV export set. Used for --csv, "
            "and always for proposal_people.csv. "
            f"Defaults to {DOWNLOAD_DIR}."
        ),
    )
    return parser.parse_args(arguments)


def normalize_column_name(column: str) -> str:
    return (
        column.strip()
        .lower()
        .replace(" ", "_")
        .replace("-", "_")
    )


def read_csv(path: Path) -> pd.DataFrame:
    logger.info("Reading {}", path)

    dataframe = pd.read_csv(
        path,
        dtype=str,
        keep_default_na=True,
        na_values=["", "NULL", "null"],
        low_memory=False,
    )

    dataframe.columns = [
        normalize_column_name(column)
        for column in dataframe.columns
    ]

    dataframe = dataframe.replace(
        {
            "": None,
            "NULL": None,
            "null": None,
            "NaN": None,
            "nan": None,
        }
    )

    logger.info(
        "{} rows read from {}",
        len(dataframe),
        path.name,
    )

    return dataframe


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


def prepare_people(
    dataframe: pd.DataFrame,
) -> pd.DataFrame:
    require_columns(
        dataframe,
        PERSON_REQUIRED_COLUMNS,
        "proposal_people.csv",
    )

    convert_numeric(
        dataframe,
        [
            "proposal_id",
            "version_number",
            "academic_year_effort",
            "calendar_year_effort",
            "summer_effort",
            "total_effort",
            "ver_nbr",
        ],
    )

    convert_dates(
        dataframe,
        ["source_update_timestamp"],
    )

    dataframe["principal_investigator"] = (
        dataframe["principal_investigator"]
        .map(convert_boolean)
    )

    dataframe = dataframe.rename(
        columns={"role_code": "role"}
    )

    require_values(
        dataframe,
        ["proposal_id", "version_number"],
        "proposal_people.csv",
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
                'proposal CSV export set',
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
                archive.proposal_person,
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
    use_oracle = not arguments.csv
    people_path = arguments.csv_dir / "proposal_people.csv"

    if use_oracle:
        require_files([people_path])
        versions = prepare_versions(OracleDataSource(VERSIONS_ORACLE_SQL).read())
        awards = prepare_awards(OracleDataSource(AWARDS_ORACLE_SQL).read())
    else:
        versions_path = arguments.csv_dir / "proposal_versions.csv"
        awards_path = arguments.csv_dir / "award_proposals.csv"
        require_files([versions_path, people_path, awards_path])
        versions = prepare_versions(read_csv(versions_path))
        awards = prepare_awards(read_csv(awards_path))

    people = prepare_people(read_csv(people_path))

    logger.info(
        "Prepared Proposal rows: versions={:,} people={:,} awards={:,}",
        len(versions),
        len(people),
        len(awards),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1]
        / "database"
        / "migrations",
    )

    total_rows = len(versions) + len(people) + len(awards)

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

            person_rows = load_dataframe(
                connection,
                people,
                "proposal_person",
                PERSON_COLUMNS,
            )

            award_rows = load_proposal_awards(
                connection,
                awards,
            )

            mark_load_complete(
                connection,
                load_id,
                version_rows + person_rows + award_rows,
            )

        logger.success(
            "Proposal load completed. "
            "load_id={} versions={} people={} awards={} total={}",
            load_id,
            version_rows,
            person_rows,
            award_rows,
            version_rows + person_rows + award_rows,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Proposal load failed")
        raise


if __name__ == "__main__":
    main()
