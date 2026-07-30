from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.pipeline.protocol_parent_resolution import (
    AmbiguousParentError,
    MissingParentError,
    NumberSequenceParentResolver,
    OwnerChainParentResolver,
)
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

PROJECT_ROOT = Path(__file__).resolve().parents[1]

# All three datasets have a verified Oracle extraction query. There is no
# CSV path for this loader at all.
_ORACLE_DIR = PROJECT_ROOT / "oracle" / "protocol"
VERSIONS_ORACLE_SQL = _ORACLE_DIR / "export_protocol_versions.sql"
PERSONS_ORACLE_SQL = _ORACLE_DIR / "export_protocol_persons.sql"
UNITS_ORACLE_SQL = _ORACLE_DIR / "export_protocol_units.sql"

VERSION_REQUIRED_COLUMNS = {
    "protocol_id",
    "protocol_number",
    "sequence_number",
}

PERSON_REQUIRED_COLUMNS = {
    "protocol_person_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
}

UNIT_REQUIRED_COLUMNS = {
    "protocol_units_id",
    "protocol_person_id",
    "protocol_number",
    "sequence_number",
}

VERSION_COLUMNS = [
    "protocol_id",
    "protocol_number",
    "sequence_number",
    "document_number",
    "active",
    "protocol_type_code",
    "protocol_type_description",
    "protocol_status_code",
    "protocol_status_description",
    "title",
    "description",
    "initial_submission_date",
    "approval_date",
    "expiration_date",
    "last_approval_date",
    "fda_application_number",
    "reference_number_1",
    "reference_number_2",
    "protocol_workflow_type",
    "rerouted_flag",
    "source_create_timestamp",
    "source_create_user",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]

PERSON_COLUMNS = [
    "protocol_person_id",
    "protocol_id",
    "source_protocol_id",
    "protocol_number",
    "sequence_number",
    "person_id",
    "full_name",
    "protocol_person_role_id",
    "protocol_person_role_description",
    "is_pi",
    "email_address",
    "email_source",
    "rolodex_id",
    "affiliation_type_code",
    "comments",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]

UNIT_COLUMNS = [
    "protocol_units_id",
    "protocol_person_id",
    "protocol_id",
    "protocol_number",
    "sequence_number",
    "unit_number",
    "unit_name",
    "lead_unit_flag",
    "person_id",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
    "source_object_id",
]

# The exact BU Oracle role code/description denoting Principal Investigator
# has not been verified against live PROTOCOL_PERSON_ROLES data. This
# pattern is deliberately loose (case-insensitive substring match) so it is
# easy to confirm or correct against real --limit output before trusting
# is_pi in reconciliation - see docs/DECISIONS.md.
_PI_DESCRIPTION_PATTERN = "principal investigator"


def require_columns(
    dataframe: pd.DataFrame,
    required_columns: set[str],
    source_name: str,
) -> None:
    missing = sorted(required_columns - set(dataframe.columns))
    if missing:
        raise RuntimeError(
            f"{source_name} is missing columns: " + ", ".join(missing)
        )


def convert_numeric(dataframe: pd.DataFrame, columns: list[str]) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_numeric(
                dataframe[column], errors="coerce"
            )


def convert_dates(dataframe: pd.DataFrame, columns: list[str]) -> None:
    for column in columns:
        if column in dataframe.columns:
            dataframe[column] = pd.to_datetime(
                dataframe[column], errors="coerce"
            )


def require_values(
    dataframe: pd.DataFrame,
    columns: list[str],
    source_name: str,
) -> None:
    invalid = dataframe[dataframe[columns].isna().any(axis=1)]
    if not invalid.empty:
        raise RuntimeError(
            f"{source_name} contains {len(invalid)} rows missing required "
            "values"
        )


def prepare_versions(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(dataframe, VERSION_REQUIRED_COLUMNS, "protocol versions")

    convert_numeric(dataframe, ["protocol_id", "sequence_number"])
    convert_dates(
        dataframe,
        [
            "initial_submission_date",
            "approval_date",
            "expiration_date",
            "last_approval_date",
            "source_create_timestamp",
            "source_update_timestamp",
        ],
    )

    require_values(
        dataframe,
        ["protocol_id", "protocol_number", "sequence_number"],
        "protocol versions",
    )

    duplicate_ids = dataframe.duplicated(subset=["protocol_id"], keep=False)
    if duplicate_ids.any():
        raise RuntimeError(
            "protocol versions contains duplicate protocol_id values: "
            f"{int(duplicate_ids.sum())}"
        )

    # A (protocol_number, sequence_number) pair should identify exactly one
    # protocol_id. If Oracle ever returns more than one, NumberSequenceParentResolver
    # would raise AmbiguousParentError for every child resolved against it -
    # log this loudly and early rather than let that surface as a confusing
    # downstream failure.
    repeated = dataframe.duplicated(
        subset=["protocol_number", "sequence_number"], keep=False
    )
    if repeated.any():
        logger.warning(
            "{} protocol version rows share a (protocol_number, "
            "sequence_number) pair with a different protocol_id - "
            "personnel/unit parent resolution will treat these as "
            "ambiguous parents",
            int(repeated.sum()),
        )

    return dataframe


def prepare_persons(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(dataframe, PERSON_REQUIRED_COLUMNS, "protocol persons")

    convert_numeric(
        dataframe,
        ["protocol_person_id", "source_protocol_id", "sequence_number", "rolodex_id"],
    )
    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        ["protocol_person_id", "source_protocol_id", "protocol_number", "sequence_number"],
        "protocol persons",
    )

    duplicate_ids = dataframe.duplicated(
        subset=["protocol_person_id"], keep=False
    )
    if duplicate_ids.any():
        raise RuntimeError(
            "protocol persons contains duplicate protocol_person_id "
            f"values: {int(duplicate_ids.sum())}"
        )

    # full_name: PROTOCOL_PERSONS carries both FULL_NAME and the older
    # PERSON_NAME; prefer FULL_NAME, fall back to PERSON_NAME. The Oracle
    # extraction only selects full_name today - if person_name is ever
    # added back, extend this coalesce.
    if "full_name" not in dataframe.columns:
        dataframe["full_name"] = None

    # Authoritative email selection: person_email_address (a direct column
    # on PROTOCOL_PERSONS) is primary; rolodex_email_address (via
    # ROLODEX_ID) is only used when the primary is null. Record which one
    # was used - or that neither was - so reconciliation can report this
    # rather than hide it.
    person_email = dataframe.get("person_email_address")
    rolodex_email = dataframe.get("rolodex_email_address")

    def _resolve_email(row: pd.Series) -> tuple[str | None, str | None]:
        person_value = row.get("person_email_address")
        rolodex_value = row.get("rolodex_email_address")
        if person_value and not pd.isna(person_value):
            return str(person_value), "PERSON"
        if rolodex_value and not pd.isna(rolodex_value):
            return str(rolodex_value), "ROLODEX"
        return None, None

    if person_email is not None or rolodex_email is not None:
        resolved = dataframe.apply(_resolve_email, axis=1, result_type="expand")
        dataframe["email_address"] = resolved[0]
        dataframe["email_source"] = resolved[1]
    else:
        dataframe["email_address"] = None
        dataframe["email_source"] = None

    missing_email = int(dataframe["email_address"].isna().sum())
    if missing_email:
        logger.warning(
            "{} protocol person rows have no email address from either "
            "PROTOCOL_PERSONS or ROLODEX",
            missing_email,
        )

    # is_pi: derived from protocol_person_role_description - see the
    # _PI_DESCRIPTION_PATTERN comment above. Rows with no role description
    # at all cannot be classified and are logged, not silently defaulted.
    role_description = dataframe.get("protocol_person_role_description")
    if role_description is not None:
        missing_role_description = int(role_description.isna().sum())
        if missing_role_description:
            logger.warning(
                "{} protocol person rows have no protocol_person_role_description "
                "- is_pi cannot be verified for these rows and will be False",
                missing_role_description,
            )
        dataframe["is_pi"] = (
            role_description.fillna("")
            .astype(str)
            .str.lower()
            .str.contains(_PI_DESCRIPTION_PATTERN, regex=False)
        )
    else:
        dataframe["is_pi"] = False

    return dataframe


def prepare_units(dataframe: pd.DataFrame) -> pd.DataFrame:
    require_columns(dataframe, UNIT_REQUIRED_COLUMNS, "protocol units")

    convert_numeric(
        dataframe,
        ["protocol_units_id", "protocol_person_id", "sequence_number"],
    )
    convert_dates(dataframe, ["source_update_timestamp"])

    require_values(
        dataframe,
        ["protocol_units_id", "protocol_person_id", "protocol_number", "sequence_number"],
        "protocol units",
    )

    duplicate_ids = dataframe.duplicated(
        subset=["protocol_units_id"], keep=False
    )
    if duplicate_ids.any():
        raise RuntimeError(
            "protocol units contains duplicate protocol_units_id values: "
            f"{int(duplicate_ids.sum())}"
        )

    return dataframe


def resolve_person_parents(
    persons: pd.DataFrame,
    versions: pd.DataFrame,
) -> int:
    """Resolve each person's protocol_id in place. Returns the count of rows
    whose resolved protocol_id differs from their raw source_protocol_id."""
    resolver = NumberSequenceParentResolver(versions.to_dict("records"))

    resolved_protocol_ids: list[int] = []
    mismatches = 0
    for row in persons.itertuples(index=False):
        try:
            resolved = resolver.resolve(
                protocol_number=str(row.protocol_number),
                sequence_number=int(row.sequence_number),
                source_protocol_id=int(row.source_protocol_id),
            )
        except (MissingParentError, AmbiguousParentError) as error:
            raise RuntimeError(
                "protocol persons parent resolution failed: "
                f"{redact_error_message(error)}"
            ) from error
        resolved_protocol_ids.append(resolved.protocol_id)
        mismatches += int(resolved.source_protocol_id_differs)

    persons["protocol_id"] = resolved_protocol_ids

    logger.info(
        "Protocol personnel parent resolution: total={} "
        "source_protocol_id_mismatches={}",
        len(persons),
        mismatches,
    )
    return mismatches


def resolve_unit_parents(units: pd.DataFrame, persons: pd.DataFrame) -> None:
    resolver = OwnerChainParentResolver(persons.to_dict("records"))

    resolved_protocol_ids: list[int] = []
    for row in units.itertuples(index=False):
        try:
            resolved_protocol_ids.append(
                resolver.resolve(protocol_person_id=int(row.protocol_person_id))
            )
        except MissingParentError as error:
            raise RuntimeError(
                "protocol units parent resolution failed: "
                f"{redact_error_message(error)}"
            ) from error

    units["protocol_id"] = resolved_protocol_ids


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
                'PROTOCOL',
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
    validation_report: dict[str, int],
) -> None:
    connection.execute(
        text(
            """
            UPDATE archive.load_run
               SET status = 'LOADED',
                   rows_staged = :rows_loaded,
                   rows_loaded = :rows_loaded,
                   rows_rejected = 0,
                   validation_report = :validation_report,
                   completed_at = CURRENT_TIMESTAMP
             WHERE load_id = :load_id
            """
        ),
        {
            "load_id": load_id,
            "rows_loaded": rows_loaded,
            "validation_report": pd.Series(validation_report).to_json(),
        },
    )


def mark_load_failed(engine: Engine, load_id: int, error_message: str) -> None:
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


def clear_existing_protocol_data(connection: Connection) -> None:
    logger.info("Clearing existing Protocol archive data")
    connection.execute(
        text(
            """
            TRUNCATE TABLE
                archive.protocol_unit,
                archive.protocol_person,
                archive.protocol_version
            RESTART IDENTITY;
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
    available_columns = [c for c in columns if c in dataframe.columns]
    target = dataframe[available_columns].copy()
    target["load_id"] = load_id

    logger.info("COPY {:<30} {:,} rows", table_name, len(target))

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
    for table_name, expected_count in expected_counts.items():
        actual_count = int(
            connection.execute(
                text(f"SELECT COUNT(*) FROM archive.{table_name}")
            ).scalar_one()
        )
        logger.info(
            "VERIFY {:<20} expected={:,} actual={:,}",
            table_name,
            expected_count,
            actual_count,
        )
        if actual_count != expected_count:
            raise RuntimeError(
                f"archive.{table_name} row-count mismatch: expected "
                f"{expected_count}, found {actual_count}"
            )

    orphan_persons = int(
        connection.execute(
            text(
                """
                SELECT COUNT(*)
                FROM archive.protocol_person child
                LEFT JOIN archive.protocol_version parent
                    ON parent.protocol_id = child.protocol_id
                WHERE parent.protocol_id IS NULL
                """
            )
        ).scalar_one()
    )
    if orphan_persons:
        raise RuntimeError(
            f"archive.protocol_person contains {orphan_persons} orphan rows"
        )

    orphan_units = int(
        connection.execute(
            text(
                """
                SELECT COUNT(*)
                FROM archive.protocol_unit child
                LEFT JOIN archive.protocol_person parent
                    ON parent.protocol_person_id = child.protocol_person_id
                WHERE parent.protocol_person_id IS NULL
                """
            )
        ).scalar_one()
    )
    if orphan_units:
        raise RuntimeError(
            f"archive.protocol_unit contains {orphan_units} orphan rows"
        )


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load Protocol versions/personnel/units from Oracle."
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help=(
            "Truncate every dataset to at most this many rows after "
            "reading, skip parent resolution/validation, and skip the "
            "database write entirely (a bounded dry run for testing "
            "connectivity/transform logic - not a partial load)."
        ),
    )
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()

    logger.info("Reading Protocol versions/personnel/units from Oracle")
    versions = prepare_versions(OracleDataSource(VERSIONS_ORACLE_SQL).read())
    persons = prepare_persons(OracleDataSource(PERSONS_ORACLE_SQL).read())
    units = prepare_units(OracleDataSource(UNITS_ORACLE_SQL).read())

    if arguments.limit is not None:
        versions = versions.head(arguments.limit)
        persons = persons.head(arguments.limit)
        units = units.head(arguments.limit)
        logger.info(
            "Dry run (--limit {}): read versions={} persons={} units={} - "
            "skipping parent resolution, validation, and database write.",
            arguments.limit,
            len(versions),
            len(persons),
            len(units),
        )
        return

    mismatches = resolve_person_parents(persons, versions)
    resolve_unit_parents(units, persons)

    missing_email = int(persons["email_address"].isna().sum())
    missing_role_description = int(
        persons["protocol_person_role_description"].isna().sum()
    )
    missing_unit_name = int(units["unit_name"].isna().sum())

    validation_report = {
        "personnel_source_protocol_id_mismatches": mismatches,
        "personnel_missing_email": missing_email,
        "personnel_missing_role_description": missing_role_description,
        "unit_missing_unit_name": missing_unit_name,
    }

    total_rows = len(versions) + len(persons) + len(units)

    logger.info(
        "Prepared Protocol rows: versions={:,} persons={:,} units={:,}",
        len(versions),
        len(persons),
        len(units),
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        Path(__file__).resolve().parents[1] / "database" / "migrations",
    )

    # The STARTED load_run row is committed in its own transaction, before
    # the risky work below begins - otherwise a failure would roll back the
    # STARTED row along with everything else, and mark_load_failed would
    # silently update zero rows, leaving no trace of the failure.
    with engine.begin() as connection:
        load_id = create_load_run(connection, total_rows)

    try:
        with engine.begin() as connection:
            clear_existing_protocol_data(connection)

            version_rows = load_dataframe(
                connection, versions, "protocol_version", VERSION_COLUMNS, load_id
            )
            person_rows = load_dataframe(
                connection, persons, "protocol_person", PERSON_COLUMNS, load_id
            )
            unit_rows = load_dataframe(
                connection, units, "protocol_unit", UNIT_COLUMNS, load_id
            )

            verify_loaded_data(
                connection,
                {
                    "protocol_version": len(versions),
                    "protocol_person": len(persons),
                    "protocol_unit": len(units),
                },
            )

            rows_loaded = version_rows + person_rows + unit_rows
            mark_load_complete(connection, load_id, rows_loaded, validation_report)

        logger.success(
            "Protocol load completed and verified. "
            "load_id={} versions={} persons={} units={} total={}",
            load_id,
            version_rows,
            person_rows,
            unit_rows,
            rows_loaded,
        )
    except Exception as error:
        mark_load_failed(engine, load_id, str(error))
        logger.exception("Protocol load failed")
        raise


if __name__ == "__main__":
    main()
