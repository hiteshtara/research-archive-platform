from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


DOWNLOAD_DIR = Path.home() / "Downloads"

FILES = {
    "versions": DOWNLOAD_DIR / "award_versions.csv",
    "amounts": DOWNLOAD_DIR / "award_amounts.csv",
    "people": DOWNLOAD_DIR / "award_people.csv",
    "proposals": DOWNLOAD_DIR / "award_proposals.csv",
}


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


def require_files() -> None:
    missing = [
        str(path)
        for path in FILES.values()
        if not path.exists()
    ]

    if missing:
        raise RuntimeError(
            "Missing required Award CSV files:\n"
            + "\n".join(missing)
        )


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

    duplicate_count = dataframe.duplicated(
        subset=["award_number", "sequence_number"],
        keep=False,
    ).sum()

    if duplicate_count:
        raise RuntimeError(
            "award_versions.csv contains "
            f"{duplicate_count} duplicate rows for "
            "award_number + sequence_number"
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
                'award CSV export set',
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
        column
        for column in columns
        if column in dataframe.columns
    ]

    target = dataframe[available_columns].copy()

    target = target.rename(
        columns={
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
            "active": "active_flag",
        }
    )

    target["load_id"] = load_id

    target.to_sql(
        name=table_name,
        schema="archive",
        con=connection,
        if_exists="append",
        index=False,
        method="multi",
        chunksize=500,
    )

    return len(target)


def clear_existing_award_data(
    connection: Connection,
) -> None:
    logger.info("Clearing existing Award archive data")

    connection.execute(
        text(
            """
            DELETE FROM archive.award_funding_proposal;
            DELETE FROM archive.award_person;
            DELETE FROM archive.award_amount_info;
            DELETE FROM archive.award_version;
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
                "error_message": error_message[:4000],
            },
        )


def main() -> None:
    require_files()

    versions = prepare_versions(
        read_csv(FILES["versions"])
    )
    amounts = prepare_amounts(
        read_csv(FILES["amounts"])
    )
    people = prepare_people(
        read_csv(FILES["people"])
    )
    proposals = prepare_proposals(
        read_csv(FILES["proposals"])
    )

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

    load_id: int | None = None

    try:
        with engine.begin() as connection:
            load_id = create_load_run(
                connection,
                total_rows,
            )

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
        if load_id is not None:
            mark_load_failed(
                engine,
                load_id,
                str(error),
            )

        logger.exception("Award load failed")
        raise


if __name__ == "__main__":
    main()
