from __future__ import annotations

import argparse
from pathlib import Path

import boto3
import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.reference_data import _upsert_rows
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

def _resolve_project_root() -> Path:
    """Locate the directory containing oracle/negotiation/ and
    database/migrations/ relative to this file - mirrors
    load_proposals_from_csv.py's own _resolve_project_root() exactly
    (same technique, kept local to each loader): the local repo checkout
    (this file at <repo>/etl/load_negotiations_from_csv.py, project root
    one level up) vs. the ECS loader container image (this file copied
    flatly to /app/load_negotiations_from_csv.py alongside oracle/ and
    database/migrations/ copied directly under /app - see
    etl/Dockerfile.loader), where the project root is this file's own
    parent directory. This loader had never been run in a container
    before this fix - the naive parents[1] resolution silently worked
    locally and only broke inside the ECS image."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

# All five datasets have a verified, column-matching Oracle extraction query.
_ORACLE_DIR = PROJECT_ROOT / "oracle" / "negotiation"
ORACLE_SQL = {
    "negotiations": _ORACLE_DIR / "export_negotiations.sql",
    "activities": _ORACLE_DIR / "export_negotiation_activities.sql",
    "custom_data": _ORACLE_DIR / "export_negotiation_custom_data.sql",
    "notifications": _ORACLE_DIR / "export_negotiation_notifications.sql",
    "unassociated": _ORACLE_DIR / "export_negotiation_unassociated.sql",
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

# (archive.<table> primary key columns, full archive.<table> column list
# post-SOURCE_COLUMN_RENAMES) - used only by the targeted/bounded UPSERT
# path (--negotiation-id / --max-negotiations), which loads_dataframe's
# TRUNCATE-and-reload path never needs. Column lists mirror
# V017__create_negotiation_archive_tables.sql exactly.
ARCHIVE_TABLES: dict[str, tuple[list[str], list[str]]] = {
    "negotiation": (
        ["negotiation_id"],
        [
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
            "source_update_timestamp",
            "source_update_user",
            "source_version_number",
            "source_object_id",
            "document_source_update_timestamp",
            "document_source_update_user",
            "document_source_version_number",
            "document_source_object_id",
        ],
    ),
    "negotiation_activity": (
        ["negotiation_activity_id"],
        [
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
            "source_update_timestamp",
            "source_update_user",
            "source_version_number",
            "source_object_id",
        ],
    ),
    "negotiation_custom_data": (
        ["negotiation_custom_data_id"],
        [
            "negotiation_custom_data_id",
            "negotiation_id",
            "negotiation_number",
            "custom_attribute_id",
            "value",
            "source_update_timestamp",
            "source_update_user",
            "source_version_number",
            "source_object_id",
        ],
    ),
    "negotiation_notification": (
        ["notification_id"],
        [
            "notification_id",
            "notification_type_id",
            "document_number",
            "owning_document_id_fk",
            "recipients",
            "subject",
            "message",
            "source_update_timestamp",
            "source_update_user",
            "source_version_number",
            "source_object_id",
        ],
    ),
    "negotiation_unassociated_detail": (
        ["negotiation_unassoc_detail_id"],
        [
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
            "source_update_timestamp",
            "source_update_user",
            "source_version_number",
            "source_object_id",
        ],
    ),
}


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load Negotiation data from Oracle."
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
        "--load-negotiation-id",
        dest="negotiation_id",
        type=int,
        action="append",
        default=None,
        help=(
            "Load only this Negotiation ID (repeatable). Uses an "
            "idempotent per-row UPSERT, never TRUNCATE - safe to "
            "re-run and safe to combine with an already-loaded "
            "database. Mutually exclusive with --max-negotiations."
        ),
    )
    parser.add_argument(
        "--max-negotiations",
        type=int,
        default=None,
        help=(
            "Load only the first N Negotiations, ordered ascending by "
            "negotiation_id. Same idempotent UPSERT as --negotiation-id. "
            "Mutually exclusive with --negotiation-id."
        ),
    )
    parser.add_argument(
        "--load-all",
        action="store_true",
        help=(
            "Load every Negotiation in Oracle, one negotiation_id at a "
            "time, each in its own transaction - a bad row on one "
            "Negotiation is logged and skipped, not a whole-run abort. "
            "Mutually exclusive with --load-negotiation-id/"
            "--max-negotiations."
        ),
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Resolve PostgreSQL/Oracle credentials from Secrets Manager "
            "via the ECS task's environment instead of requiring local "
            "exports - see archive_etl.config.ecs."
        ),
    )
    arguments = parser.parse_args(arguments)

    if arguments.negotiation_id and arguments.max_negotiations is not None:
        parser.error(
            "--negotiation-id and --max-negotiations are mutually "
            "exclusive"
        )

    if arguments.load_all and (
        arguments.negotiation_id or arguments.max_negotiations is not None
    ):
        parser.error(
            "--load-all cannot be combined with --load-negotiation-id "
            "or --max-negotiations"
        )

    return arguments


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


def _run_ecs_setup() -> None:
    """Minimal --ecs credential setup: resolves PostgreSQL/Oracle
    credentials from Secrets Manager into os.environ so
    create_postgres_engine()/OracleDataSource keep working unchanged -
    see load_proposals_from_csv.py's own (larger) _run_ecs_setup for the
    fuller startup-validation ceremony this intentionally skips, per the
    "do not build a full batch framework yet" scope for this loader."""
    configure_ecs_environment(boto3.client("secretsmanager"))


def resolve_target_negotiation_ids(
    arguments: argparse.Namespace,
) -> list[int] | None:
    """Returns the explicit list of Negotiation IDs to load for
    --negotiation-id/--max-negotiations, or None if neither flag was
    given (the untargeted full-refresh path)."""
    if arguments.negotiation_id:
        return sorted(set(arguments.negotiation_id))

    if arguments.max_negotiations is not None:
        all_ids = (
            OracleDataSource(ORACLE_SQL["negotiations"])
            .read()["negotiation_id"]
            .dropna()
            .astype("int64")
            .sort_values()
            .tolist()
        )
        return all_ids[: arguments.max_negotiations]

    return None


def run_targeted_load(target_ids: list[int]) -> dict[str, dict[str, int]]:
    """Loads exactly the given Negotiation IDs (and their children) via
    an idempotent per-row UPSERT (archive_etl.reference_data._upsert_rows)
    instead of clear_existing_negotiation_data's TRUNCATE - safe to
    re-run, and safe to run repeatedly against a database that already
    has other Negotiations loaded. Returns
    {table_name: {"inserted": n, "updated": n, "unchanged": n}}."""
    logger.info(
        "Reading {} targeted Negotiation ID(s) from Oracle",
        len(target_ids),
    )

    negotiations = prepare_negotiations(
        OracleDataSource(ORACLE_SQL["negotiations"]).read_filtered(
            column="negotiation_id",
            values=target_ids,
        )
    )
    activities = prepare_activities(
        OracleDataSource(ORACLE_SQL["activities"]).read_filtered(
            column="negotiation_id",
            values=target_ids,
        )
    )
    custom_data = prepare_custom_data(
        OracleDataSource(ORACLE_SQL["custom_data"]).read_filtered(
            column="negotiation_id",
            values=target_ids,
        )
    )
    notifications = prepare_notifications(
        OracleDataSource(ORACLE_SQL["notifications"]).read_filtered(
            column="owning_document_id_fk",
            values=target_ids,
        )
    )
    unassociated = prepare_unassociated(
        OracleDataSource(ORACLE_SQL["unassociated"]).read_filtered(
            column="negotiation_id",
            values=target_ids,
        )
    )

    validate_parent_ids(
        negotiations, activities, "negotiation_id", "negotiation_activities.csv"
    )
    validate_parent_ids(
        negotiations, custom_data, "negotiation_id", "negotiation_custom_data.csv"
    )
    validate_parent_ids(
        negotiations,
        notifications,
        "owning_document_id_fk",
        "negotiation_notifications.csv",
    )
    validate_parent_ids(
        negotiations, unassociated, "negotiation_id", "negotiation_unassociated.csv"
    )

    datasets = {
        "negotiation": negotiations,
        "negotiation_activity": activities,
        "negotiation_custom_data": custom_data,
        "negotiation_notification": notifications,
        "negotiation_unassociated_detail": unassociated,
    }
    total_rows = sum(len(dataframe) for dataframe in datasets.values())

    logger.info(
        "Prepared targeted Negotiation rows: negotiations={:,} "
        "activities={:,} custom_data={:,} notifications={:,} "
        "unassociated={:,}",
        len(negotiations),
        len(activities),
        len(custom_data),
        len(notifications),
        len(unassociated),
    )

    engine = create_postgres_engine()
    apply_migrations(
        engine, PROJECT_ROOT / "database" / "migrations"
    )

    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    results: dict[str, dict[str, int]] = {}
    try:
        with engine.begin() as connection:
            for table_name, dataframe in datasets.items():
                pk_columns, columns = ARCHIVE_TABLES[table_name]
                renamed = dataframe.rename(columns=SOURCE_COLUMN_RENAMES)
                results[table_name] = _upsert_rows(
                    connection,
                    table=table_name,
                    pk_columns=pk_columns,
                    columns=columns,
                    rows=renamed,
                    load_id=load_id,
                )
                logger.info(
                    "UPSERT {:<33} inserted={:,} updated={:,} unchanged={:,}",
                    table_name,
                    results[table_name]["inserted"],
                    results[table_name]["updated"],
                    results[table_name]["unchanged"],
                )

            mark_load_complete(connection, load_id, total_rows)

        logger.success(
            "Targeted Negotiation load completed: {} Negotiation ID(s)",
            len(target_ids),
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Targeted Negotiation load failed")
        raise

    return results


def run_full_load() -> dict[str, object]:
    """Loads every Negotiation in Oracle, one negotiation_id at a time,
    each in its own Postgres transaction - unlike run_targeted_load's
    single whole-batch transaction (fine for a bounded/proving load, not
    safe at ~11K negotiations: one bad row would roll back everything).
    A failure on one negotiation_id is logged and skipped; every other
    negotiation_id still loads. Reads each Oracle table once
    (unfiltered), then groups children by their parent id in memory -
    much cheaper than read_filtered's chunked round trips at this scale.
    Returns a summary dict for reporting."""
    logger.info("Reading the full Negotiation population from Oracle")

    negotiations = prepare_negotiations(
        OracleDataSource(ORACLE_SQL["negotiations"]).read()
    )
    activities = prepare_activities(
        OracleDataSource(ORACLE_SQL["activities"]).read()
    )
    custom_data = prepare_custom_data(
        OracleDataSource(ORACLE_SQL["custom_data"]).read()
    )
    notifications = prepare_notifications(
        OracleDataSource(ORACLE_SQL["notifications"]).read()
    )
    unassociated = prepare_unassociated(
        OracleDataSource(ORACLE_SQL["unassociated"]).read()
    )

    logger.info(
        "Read full Negotiation population: negotiations={:,} "
        "activities={:,} custom_data={:,} notifications={:,} "
        "unassociated={:,}",
        len(negotiations),
        len(activities),
        len(custom_data),
        len(notifications),
        len(unassociated),
    )

    activities_by_id = {
        key: group
        for key, group in activities.groupby("negotiation_id")
    }
    custom_data_by_id = {
        key: group
        for key, group in custom_data.groupby("negotiation_id")
    }
    notifications_by_id = {
        key: group
        for key, group in notifications.groupby("owning_document_id_fk")
    }
    unassociated_by_id = {
        key: group
        for key, group in unassociated.groupby("negotiation_id")
    }

    engine = create_postgres_engine()
    apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    empty_activities = activities.iloc[0:0]
    empty_custom_data = custom_data.iloc[0:0]
    empty_notifications = notifications.iloc[0:0]
    empty_unassociated = unassociated.iloc[0:0]

    totals = {
        table: {"inserted": 0, "updated": 0, "unchanged": 0}
        for table in ARCHIVE_TABLES
    }
    completed = 0
    failed_ids: list[int] = []

    negotiation_ids = (
        negotiations["negotiation_id"].astype("int64").sort_values().tolist()
    )

    for index, negotiation_id in enumerate(negotiation_ids, start=1):
        datasets = {
            "negotiation": negotiations[
                negotiations["negotiation_id"] == negotiation_id
            ],
            "negotiation_activity": activities_by_id.get(
                negotiation_id, empty_activities
            ),
            "negotiation_custom_data": custom_data_by_id.get(
                negotiation_id, empty_custom_data
            ),
            "negotiation_notification": notifications_by_id.get(
                negotiation_id, empty_notifications
            ),
            "negotiation_unassociated_detail": unassociated_by_id.get(
                negotiation_id, empty_unassociated
            ),
        }

        try:
            with engine.begin() as connection:
                load_id = create_load_run(connection, sum(
                    len(dataframe) for dataframe in datasets.values()
                ))
                for table_name, dataframe in datasets.items():
                    pk_columns, columns = ARCHIVE_TABLES[table_name]
                    renamed = dataframe.rename(columns=SOURCE_COLUMN_RENAMES)
                    result = _upsert_rows(
                        connection,
                        table=table_name,
                        pk_columns=pk_columns,
                        columns=columns,
                        rows=renamed,
                        load_id=load_id,
                    )
                    for key in ("inserted", "updated", "unchanged"):
                        totals[table_name][key] += result[key]
                mark_load_complete(connection, load_id, sum(
                    len(dataframe) for dataframe in datasets.values()
                ))
            completed += 1
        except Exception as error:
            failed_ids.append(negotiation_id)
            logger.error(
                "Negotiation {} failed to load: {}",
                negotiation_id,
                redact_error_message(str(error)),
            )

        if index % 500 == 0 or index == len(negotiation_ids):
            logger.info(
                "Progress: {:,}/{:,} Negotiations processed "
                "({:,} completed, {:,} failed)",
                index,
                len(negotiation_ids),
                completed,
                len(failed_ids),
            )

    logger.success(
        "Full Negotiation load finished: {:,} requested, {:,} completed, "
        "{:,} failed",
        len(negotiation_ids),
        completed,
        len(failed_ids),
    )
    for table_name, result in totals.items():
        logger.info(
            "TOTAL {:<33} inserted={:,} updated={:,} unchanged={:,}",
            table_name,
            result["inserted"],
            result["updated"],
            result["unchanged"],
        )
    if failed_ids:
        logger.warning(
            "Failed Negotiation IDs ({:,}): {}",
            len(failed_ids),
            failed_ids[:50],
        )

    return {
        "requested": len(negotiation_ids),
        "completed": completed,
        "failed": len(failed_ids),
        "failed_ids": failed_ids,
        "totals": totals,
    }


def main() -> None:
    arguments = parse_args()

    if arguments.ecs:
        _run_ecs_setup()

    if arguments.load_all:
        run_full_load()
        return

    target_ids = resolve_target_negotiation_ids(arguments)
    if target_ids is not None:
        if not target_ids:
            logger.warning("No target Negotiation IDs resolved - nothing to load")
            return
        run_targeted_load(target_ids)
        return

    logger.info("Reading Negotiation data from Oracle")
    negotiations = prepare_negotiations(
        OracleDataSource(ORACLE_SQL["negotiations"]).read()
    )
    activities = prepare_activities(
        OracleDataSource(ORACLE_SQL["activities"]).read()
    )
    custom_data = prepare_custom_data(
        OracleDataSource(ORACLE_SQL["custom_data"]).read()
    )
    notifications = prepare_notifications(
        OracleDataSource(ORACLE_SQL["notifications"]).read()
    )
    unassociated = prepare_unassociated(
        OracleDataSource(ORACLE_SQL["unassociated"]).read()
    )

    if arguments.limit is not None:
        negotiations = negotiations.head(arguments.limit)
        activities = activities.head(arguments.limit)
        custom_data = custom_data.head(arguments.limit)
        notifications = notifications.head(arguments.limit)
        unassociated = unassociated.head(arguments.limit)
        logger.info(
            "Dry run (--limit {}): read negotiations={} activities={} "
            "custom_data={} notifications={} unassociated={} - skipping "
            "validation and database write.",
            arguments.limit,
            len(negotiations),
            len(activities),
            len(custom_data),
            len(notifications),
            len(unassociated),
        )
        return

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
        PROJECT_ROOT / "database" / "migrations",
    )

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins - otherwise a failure would roll back
    # the STARTED row along with everything else, and mark_load_failed
    # would silently update zero rows, leaving no trace of the failure.
    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
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
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Negotiation load failed")
        raise


if __name__ == "__main__":
    main()
