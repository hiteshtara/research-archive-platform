from __future__ import annotations

from pathlib import Path

import pandas as pd
from loguru import logger
from pandas.errors import EmptyDataError
from sqlalchemy import text
from sqlalchemy.engine import Connection

from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


DOWNLOAD_DIR = Path.home() / "Downloads"

FILES = {
    "negotiations": DOWNLOAD_DIR / "negotiations.csv",
    "activities": DOWNLOAD_DIR / "negotiation_activities.csv",
    "custom_data": DOWNLOAD_DIR / "negotiation_custom_data.csv",
    "notifications": DOWNLOAD_DIR / "negotiation_notifications.csv",
    "unassociated": DOWNLOAD_DIR / "negotiation_unassociated.csv",
}

NEGOTIATION_COLUMNS = [
    "negotiation_id",
    "document_number",
    "negotiation_status_id",
    "negotiation_status_code",
    "negotiation_status_description",
    "negotiation_agreement_type_id",
    "negotiation_agreement_type_code",
    "negotiation_agreement_type_description",
    "negotiation_association_type_id",
    "negotiation_association_type_code",
    "negotiation_association_type_description",
    "negotiator_person_id",
    "negotiator_full_name",
    "negotiation_start_date",
    "negotiation_end_date",
    "anticipated_award_date",
    "document_folder",
    "associated_document_id",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
    "document_update_timestamp",
    "document_update_user",
    "document_ver_nbr",
    "document_obj_id",
]

ACTIVITY_COLUMNS = [
    "negotiation_activity_id",
    "negotiation_id",
    "activity_type_id",
    "activity_type_code",
    "activity_type_description",
    "location_id",
    "location_code",
    "location_description",
    "start_date",
    "end_date",
    "create_date",
    "followup_date",
    "last_modified_user",
    "last_modified_date",
    "description",
    "restricted",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
]

CUSTOM_DATA_COLUMNS = [
    "negotiation_custom_data_id",
    "negotiation_id",
    "negotiation_number",
    "custom_attribute_id",
    "value",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
]

NOTIFICATION_COLUMNS = [
    "notification_id",
    "notification_type_id",
    "document_number",
    "owning_document_id_fk",
    "recipients",
    "subject",
    "message",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
]

UNASSOCIATED_COLUMNS = [
    "negotiation_unassoc_detail_id",
    "negotiation_id",
    "title",
    "pi_person_id",
    "pi_rolodex_id",
    "lead_unit",
    "sponsor_code",
    "pi_name",
    "prime_sponsor_code",
    "sponsor_award_number",
    "contact_admin_person_id",
    "subaward_org",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
]

SOURCE_COLUMN_RENAMES = {
    "update_timestamp": "source_update_timestamp",
    "update_user": "source_update_user",
    "ver_nbr": "source_version_number",
    "obj_id": "source_object_id",
    "document_update_timestamp": "document_source_update_timestamp",
    "document_update_user": "document_source_update_user",
    "document_ver_nbr": "document_source_version_number",
    "document_obj_id": "document_source_object_id",
}


def require_files() -> None:
    required_keys = {
        "negotiations",
        "activities",
        "custom_data",
        "unassociated",
    }

    missing = [
        str(FILES[key])
        for key in sorted(required_keys)
        if not FILES[key].exists()
    ]

    if missing:
        raise RuntimeError(
            "Missing required Negotiation CSV files:\n"
            + "\n".join(missing)
        )


def normalize_column_name(column: str) -> str:
    return (
        column.strip()
        .lower()
        .replace(" ", "_")
        .replace("-", "_")
    )


def empty_dataframe(columns: list[str]) -> pd.DataFrame:
    return pd.DataFrame(columns=columns)


def read_csv(
    path: Path,
    expected_columns: list[str],
    allow_empty: bool = False,
) -> pd.DataFrame:
    logger.info("Reading {}", path)

    if not path.exists():
        if allow_empty:
            logger.info(
                "{} is absent; treating it as a valid empty dataset",
                path.name,
            )
            return empty_dataframe(expected_columns)

        raise RuntimeError(f"Missing required CSV file: {path}")

    try:
        dataframe = pd.read_csv(
            path,
            dtype=str,
            keep_default_na=True,
            na_values=["", "NULL", "null"],
            low_memory=False,
        )
    except EmptyDataError:
        if not allow_empty:
            raise RuntimeError(f"{path.name} is empty") from None

        logger.info(
            "{} contains no header or rows; treating it as a valid empty dataset",
            path.name,
        )
        return empty_dataframe(expected_columns)

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
    required_columns: list[str],
    file_name: str,
) -> None:
    missing = sorted(
        set(required_columns) - set(dataframe.columns)
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
        dataframe[column] = pd.to_numeric(
            dataframe[column],
            errors="coerce",
        )


def convert_dates(
    dataframe: pd.DataFrame,
    columns: list[str],
) -> None:
    for column in columns:
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


def require_unique_primary_key(
    dataframe: pd.DataFrame,
    primary_key: str,
    file_name: str,
) -> None:
    duplicate_rows = dataframe.duplicated(
        subset=[primary_key],
        keep=False,
    )

    if duplicate_rows.any():
        duplicate_count = int(duplicate_rows.sum())
        duplicate_preview = (
            dataframe.loc[duplicate_rows, [primary_key]]
            .head(20)
            .to_string(index=False)
        )

        raise RuntimeError(
            f"{file_name} contains duplicate {primary_key} values. "
            f"Duplicate rows: {duplicate_count}\n"
            + duplicate_preview
        )


def prepare_negotiations(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe,
        NEGOTIATION_COLUMNS,
        "negotiations.csv",
    )

    convert_numeric(
        dataframe,
        [
            "negotiation_id",
            "negotiation_status_id",
            "negotiation_agreement_type_id",
            "negotiation_association_type_id",
            "ver_nbr",
            "document_ver_nbr",
        ],
    )
    convert_dates(
        dataframe,
        [
            "negotiation_start_date",
            "negotiation_end_date",
            "anticipated_award_date",
            "update_timestamp",
            "document_update_timestamp",
        ],
    )
    require_values(dataframe, ["negotiation_id"], "negotiations.csv")
    require_unique_primary_key(
        dataframe,
        "negotiation_id",
        "negotiations.csv",
    )

    return dataframe


def prepare_activities(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe,
        ACTIVITY_COLUMNS,
        "negotiation_activities.csv",
    )

    convert_numeric(
        dataframe,
        [
            "negotiation_activity_id",
            "negotiation_id",
            "activity_type_id",
            "location_id",
            "ver_nbr",
        ],
    )
    convert_dates(
        dataframe,
        [
            "start_date",
            "end_date",
            "create_date",
            "followup_date",
            "last_modified_date",
            "update_timestamp",
        ],
    )
    require_values(
        dataframe,
        ["negotiation_activity_id", "negotiation_id"],
        "negotiation_activities.csv",
    )
    require_unique_primary_key(
        dataframe,
        "negotiation_activity_id",
        "negotiation_activities.csv",
    )

    return dataframe


def prepare_custom_data(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe,
        CUSTOM_DATA_COLUMNS,
        "negotiation_custom_data.csv",
    )

    convert_numeric(
        dataframe,
        [
            "negotiation_custom_data_id",
            "negotiation_id",
            "custom_attribute_id",
            "ver_nbr",
        ],
    )
    convert_dates(dataframe, ["update_timestamp"])
    require_values(
        dataframe,
        ["negotiation_custom_data_id", "negotiation_id"],
        "negotiation_custom_data.csv",
    )
    require_unique_primary_key(
        dataframe,
        "negotiation_custom_data_id",
        "negotiation_custom_data.csv",
    )

    return dataframe


def prepare_notifications(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe,
        NOTIFICATION_COLUMNS,
        "negotiation_notifications.csv",
    )

    convert_numeric(
        dataframe,
        [
            "notification_id",
            "notification_type_id",
            "owning_document_id_fk",
            "ver_nbr",
        ],
    )
    convert_dates(dataframe, ["update_timestamp"])
    require_values(
        dataframe,
        ["notification_id", "owning_document_id_fk"],
        "negotiation_notifications.csv",
    )
    require_unique_primary_key(
        dataframe,
        "notification_id",
        "negotiation_notifications.csv",
    )

    return dataframe


def prepare_unassociated(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(
        dataframe,
        UNASSOCIATED_COLUMNS,
        "negotiation_unassociated.csv",
    )

    convert_numeric(
        dataframe,
        [
            "negotiation_unassoc_detail_id",
            "negotiation_id",
            "ver_nbr",
        ],
    )
    convert_dates(dataframe, ["update_timestamp"])
    require_values(
        dataframe,
        ["negotiation_unassoc_detail_id", "negotiation_id"],
        "negotiation_unassociated.csv",
    )
    require_unique_primary_key(
        dataframe,
        "negotiation_unassoc_detail_id",
        "negotiation_unassociated.csv",
    )

    return dataframe


def validate_parent_ids(
    negotiations: pd.DataFrame,
    child: pd.DataFrame,
    child_column: str,
    file_name: str,
) -> None:
    parent_ids = set(
        negotiations["negotiation_id"]
        .astype("int64")
        .tolist()
    )
    child_ids = set(
        child[child_column]
        .dropna()
        .astype("int64")
        .tolist()
    )
    orphan_ids = sorted(child_ids - parent_ids)

    if orphan_ids:
        preview = ", ".join(
            str(value)
            for value in orphan_ids[:20]
        )
        raise RuntimeError(
            f"{file_name} contains Negotiation IDs that do not "
            f"exist in negotiations.csv: {preview}"
        )


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
                'NEGOTIATION',
                'KUALI',
                'negotiation CSV export set',
                :rows_read,
                'STARTED'
            )
            RETURNING load_id
            """
        ),
        {"rows_read": total_rows},
    ).scalar_one()

    return int(load_id)


def clear_existing_negotiation_data(connection: Connection) -> None:
    logger.info("Clearing existing Negotiation archive data")

    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.negotiation_notification,
                archive.negotiation_unassociated_detail,
                archive.negotiation_custom_data,
                archive.negotiation_activity,
                archive.negotiation;
            """
        )
    )


def load_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    table_name: str,
    columns: list[str],
    load_id: int,
) -> int:
    target = dataframe[columns].copy()
    target = target.rename(columns=SOURCE_COLUMN_RENAMES)
    target["load_id"] = load_id

    logger.info(
        "COPY {:<35} {:,} rows",
        table_name,
        len(target),
    )

    if target.empty:
        logger.info(
            "Skipping COPY for archive.{} because the dataset is empty",
            table_name,
        )
        return 0

    return bulk_copy_dataframe(
        connection=connection,
        dataframe=target,
        schema="archive",
        table=table_name,
    )


def verify_loaded_data(
    connection: Connection,
    expected_counts: dict[str, int],
) -> None:
    table_parent_columns = {
        "negotiation_activity": "negotiation_id",
        "negotiation_custom_data": "negotiation_id",
        "negotiation_notification": "owning_document_id_fk",
        "negotiation_unassociated_detail": "negotiation_id",
    }

    for table_name, expected_count in expected_counts.items():
        actual_count = int(
            connection.execute(
                text(f"SELECT COUNT(*) FROM archive.{table_name}")
            ).scalar_one()
        )

        logger.info(
            "VERIFY {:<33} expected={:,} actual={:,}",
            table_name,
            expected_count,
            actual_count,
        )

        if actual_count != expected_count:
            raise RuntimeError(
                f"archive.{table_name} row-count mismatch: "
                f"expected {expected_count}, found {actual_count}"
            )

    for table_name, parent_column in table_parent_columns.items():
        orphan_count = int(
            connection.execute(
                text(
                    f"""
                    SELECT COUNT(*)
                    FROM archive.{table_name} child
                    LEFT JOIN archive.negotiation parent
                        ON parent.negotiation_id = child.{parent_column}
                    WHERE parent.negotiation_id IS NULL
                    """
                )
            ).scalar_one()
        )

        logger.info(
            "VERIFY {:<33} orphan rows={:,}",
            table_name,
            orphan_count,
        )

        if orphan_count:
            raise RuntimeError(
                f"archive.{table_name} contains "
                f"{orphan_count} orphan rows"
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


def main() -> None:
    require_files()

    negotiations = prepare_negotiations(
        read_csv(FILES["negotiations"], NEGOTIATION_COLUMNS)
    )
    activities = prepare_activities(
        read_csv(FILES["activities"], ACTIVITY_COLUMNS)
    )
    custom_data = prepare_custom_data(
        read_csv(FILES["custom_data"], CUSTOM_DATA_COLUMNS)
    )
    notifications = prepare_notifications(
        read_csv(
            FILES["notifications"],
            NOTIFICATION_COLUMNS,
            allow_empty=True,
        )
    )
    unassociated = prepare_unassociated(
        read_csv(FILES["unassociated"], UNASSOCIATED_COLUMNS)
    )

    validate_parent_ids(
        negotiations,
        activities,
        "negotiation_id",
        "negotiation_activities.csv",
    )
    validate_parent_ids(
        negotiations,
        custom_data,
        "negotiation_id",
        "negotiation_custom_data.csv",
    )
    validate_parent_ids(
        negotiations,
        notifications,
        "owning_document_id_fk",
        "negotiation_notifications.csv",
    )
    validate_parent_ids(
        negotiations,
        unassociated,
        "negotiation_id",
        "negotiation_unassociated.csv",
    )

    expected_counts = {
        "negotiation": len(negotiations),
        "negotiation_activity": len(activities),
        "negotiation_custom_data": len(custom_data),
        "negotiation_notification": len(notifications),
        "negotiation_unassociated_detail": len(unassociated),
    }
    total_rows = sum(expected_counts.values())

    logger.info(
        "Prepared Negotiation rows: negotiations={:,} activities={:,} "
        "custom_data={:,} notifications={:,} unassociated={:,}",
        len(negotiations),
        len(activities),
        len(custom_data),
        len(notifications),
        len(unassociated),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1]
        / "database"
        / "migrations",
    )

    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)
        clear_existing_negotiation_data(connection)

        loaded_counts = {
            "negotiation": load_dataframe(
                connection,
                negotiations,
                "negotiation",
                NEGOTIATION_COLUMNS,
                load_id,
            ),
            "negotiation_activity": load_dataframe(
                connection,
                activities,
                "negotiation_activity",
                ACTIVITY_COLUMNS,
                load_id,
            ),
            "negotiation_custom_data": load_dataframe(
                connection,
                custom_data,
                "negotiation_custom_data",
                CUSTOM_DATA_COLUMNS,
                load_id,
            ),
            "negotiation_notification": load_dataframe(
                connection,
                notifications,
                "negotiation_notification",
                NOTIFICATION_COLUMNS,
                load_id,
            ),
            "negotiation_unassociated_detail": load_dataframe(
                connection,
                unassociated,
                "negotiation_unassociated_detail",
                UNASSOCIATED_COLUMNS,
                load_id,
            ),
        }

        if loaded_counts != expected_counts:
            raise RuntimeError(
                "Negotiation COPY row counts do not match prepared row counts"
            )

        verify_loaded_data(connection, expected_counts)
        mark_load_complete(connection, load_id, total_rows)

    logger.success(
        "Negotiation load completed and verified. "
        "negotiations={} activities={} custom_data={} "
        "notifications={} unassociated={} total={}",
        loaded_counts["negotiation"],
        loaded_counts["negotiation_activity"],
        loaded_counts["negotiation_custom_data"],
        loaded_counts["negotiation_notification"],
        loaded_counts["negotiation_unassociated_detail"],
        sum(loaded_counts.values()),
    )


if __name__ == "__main__":
    main()
