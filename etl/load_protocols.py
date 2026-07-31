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


def read_bounded_by_row_count(
    source: OracleDataSource, limit: int
) -> pd.DataFrame:
    """Read at most `limit` rows from an Oracle source, stopping the fetch
    as soon as enough rows have been collected instead of reading the full
    result set. Used only for --limit sampling (protocol versions, the
    root of the sample) - never for a real load."""
    collected: list[pd.DataFrame] = []
    total = 0
    batches = source.read_batches()
    try:
        for batch in batches:
            collected.append(batch)
            total += len(batch)
            if total >= limit:
                break
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True).head(limit)


def read_bounded_by_protocol_number(
    source: OracleDataSource, max_protocol_number: str
) -> pd.DataFrame:
    """Read rows from an Oracle source ordered ascending by protocol_number
    (true of every Protocol extraction query), stopping the fetch as soon
    as a row's protocol_number exceeds max_protocol_number instead of
    reading the full result set. Used to bound the personnel/units reads to
    the protocol_number range covered by a sampled set of protocol
    versions, without taking an independent, potentially incoherent
    head(limit) of each dataset.

    This assumes the source query's ORDER BY and Python's string comparison
    agree on ordering, which holds for the fixed-width numeric protocol
    numbers observed in this dataset. It is only ever used for --limit
    sampling, never for a real load, so a rare ordering disagreement would
    at worst affect a --limit report, not the actual archive data.
    """
    collected: list[pd.DataFrame] = []
    batches = source.read_batches()
    try:
        for batch in batches:
            within_bound = batch["protocol_number"].astype(str) <= max_protocol_number
            if within_bound.all():
                collected.append(batch)
                continue
            collected.append(batch[within_bound])
            break
    finally:
        batches.close()

    if not collected:
        return pd.DataFrame()
    return pd.concat(collected, ignore_index=True)


def _sample_version_keys(versions: pd.DataFrame) -> pd.DataFrame:
    keys = versions[["protocol_number", "sequence_number"]].copy()
    keys["protocol_number"] = keys["protocol_number"].astype(str)
    keys["sequence_number"] = pd.to_numeric(
        keys["sequence_number"], errors="coerce"
    )
    return keys.drop_duplicates()


def _filter_to_sample_keys(
    dataframe: pd.DataFrame, sample_keys: pd.DataFrame
) -> pd.DataFrame:
    """Keep only rows whose (protocol_number, sequence_number) exactly
    matches a sampled protocol version. A protocol_number-boundary bounded
    read can include rows for protocol_number/sequence_number combinations
    that fall within the boundary but were never actually sampled (e.g. a
    different sequence_number of the boundary protocol_number, or a version
    row-count cutoff that split a protocol_number's family) - this exact-key
    filter is what guarantees the retained rows genuinely belong to the
    sampled versions rather than merely sharing a protocol_number range."""
    if dataframe.empty:
        return dataframe
    working = dataframe.copy()
    working["protocol_number"] = working["protocol_number"].astype(str)
    working["sequence_number"] = pd.to_numeric(
        working["sequence_number"], errors="coerce"
    )
    return working.merge(
        sample_keys, on=["protocol_number", "sequence_number"], how="inner"
    )


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
) -> dict[str, int]:
    """Resolve each person's protocol_id in place. Never raises: a row whose
    parent is missing or ambiguous gets protocol_id=None and is counted
    instead, so a caller can see the *full* extent of a resolution problem
    (every affected row) rather than aborting on the first one encountered.
    Used by both the --limit sample path and the full load, which is what
    lets the full load report exact unresolved/ambiguous counts in
    load_run.validation_report before deciding whether to proceed."""
    resolver = NumberSequenceParentResolver(versions.to_dict("records"))

    resolved_protocol_ids: list[int | None] = []
    mismatches = 0
    missing = 0
    ambiguous = 0
    for row in persons.itertuples(index=False):
        try:
            resolved = resolver.resolve(
                protocol_number=str(row.protocol_number),
                sequence_number=int(row.sequence_number),
                source_protocol_id=int(row.source_protocol_id),
            )
        except MissingParentError:
            resolved_protocol_ids.append(None)
            missing += 1
            continue
        except AmbiguousParentError:
            resolved_protocol_ids.append(None)
            ambiguous += 1
            continue
        resolved_protocol_ids.append(resolved.protocol_id)
        mismatches += int(resolved.source_protocol_id_differs)

    persons["protocol_id"] = resolved_protocol_ids

    logger.info(
        "Protocol personnel parent resolution: total={} "
        "source_protocol_id_mismatches={} missing_parents={} "
        "ambiguous_parents={}",
        len(persons),
        mismatches,
        missing,
        ambiguous,
    )
    return {
        "source_protocol_id_mismatches": mismatches,
        "missing_parents": missing,
        "ambiguous_parents": ambiguous,
    }


def resolve_unit_parents(units: pd.DataFrame, persons: pd.DataFrame) -> dict[str, int]:
    """Resolve each unit's protocol_id from its owning person in place. Never
    raises: a unit whose owning person is itself unresolved (protocol_id is
    None) is counted as missing instead of raising - see
    resolve_person_parents for why this is tolerant rather than fail-fast."""
    resolved_persons = persons[persons["protocol_id"].notna()]
    resolver = OwnerChainParentResolver(resolved_persons.to_dict("records"))

    resolved_protocol_ids: list[int | None] = []
    missing = 0
    for row in units.itertuples(index=False):
        try:
            resolved_protocol_ids.append(
                resolver.resolve(protocol_person_id=int(row.protocol_person_id))
            )
        except MissingParentError:
            resolved_protocol_ids.append(None)
            missing += 1

    units["protocol_id"] = resolved_protocol_ids

    logger.info(
        "Protocol unit parent resolution: total={} missing_parents={}",
        len(units),
        missing,
    )
    return {"missing_parents": missing}


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


def mark_load_failed(
    engine: Engine,
    load_id: int,
    error_message: str,
    validation_report: dict[str, int] | None = None,
) -> None:
    # validation_report is optional here (unlike mark_load_complete, where
    # it is required) because a failure can happen before any reconciliation
    # counts exist at all. When it is available - e.g. a parent-resolution
    # failure, which always has full read/unresolved/ambiguous counts by
    # the time it raises - persisting it lets an operator see *why* the
    # load failed from load_run alone, without grepping logs. COALESCE
    # leaves any existing validation_report untouched when none is given.
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                   SET status = 'FAILED',
                       completed_at = CURRENT_TIMESTAMP,
                       error_message = :error_message,
                       validation_report = COALESCE(
                           :validation_report, validation_report
                       )
                 WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "error_message": redact_error_message(error_message),
                "validation_report": (
                    pd.Series(validation_report).to_json()
                    if validation_report is not None
                    else None
                ),
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
            "Sample at most this many protocol versions from Oracle "
            "(stopping the fetch early rather than reading the full "
            "table), then retain only personnel belonging to those "
            "versions and units belonging to those personnel. Runs parent "
            "resolution and validation on this coherent sample and reports "
            "the results, but never connects to, writes to, or migrates "
            "PostgreSQL (a bounded, read-only dry run - not a partial "
            "load)."
        ),
    )
    return parser.parse_args(arguments)


def _run_limited_sample(limit: int) -> dict[str, int]:
    """Bounded, read-only --limit sampling: sample up to `limit` protocol
    versions, then keep only the personnel/units that genuinely belong to
    those versions, run parent resolution/validation on that coherent
    sample, and report the result. Never opens a PostgreSQL connection."""
    logger.info(
        "Sampling up to {} Protocol versions from Oracle (bounded read, "
        "no PostgreSQL connection will be made)",
        limit,
    )

    versions = prepare_versions(
        read_bounded_by_row_count(OracleDataSource(VERSIONS_ORACLE_SQL), limit)
    )

    report = {
        "sampled_versions": len(versions),
        "matching_personnel": 0,
        "matching_units": 0,
        "unresolved_parents": 0,
        "ambiguous_parents": 0,
        "missing_email": 0,
        "missing_role_description": 0,
        "missing_unit_name": 0,
    }

    if versions.empty:
        logger.info("Dry run (--limit {}): no protocol versions found.", limit)
        return report

    sample_keys = _sample_version_keys(versions)
    boundary_protocol_number = str(versions["protocol_number"].astype(str).max())

    persons_raw = read_bounded_by_protocol_number(
        OracleDataSource(PERSONS_ORACLE_SQL), boundary_protocol_number
    )
    persons_matching = _filter_to_sample_keys(persons_raw, sample_keys)
    if not persons_matching.empty:
        persons_matching = prepare_persons(persons_matching)

    units_raw = read_bounded_by_protocol_number(
        OracleDataSource(UNITS_ORACLE_SQL), boundary_protocol_number
    )
    units_matching = _filter_to_sample_keys(units_raw, sample_keys)
    if not units_matching.empty and not persons_matching.empty:
        # "units belonging to those retained personnel" - independent of
        # the (protocol_number, sequence_number) key match above.
        units_matching = units_matching[
            units_matching["protocol_person_id"].isin(
                persons_matching["protocol_person_id"]
            )
        ]
    if not units_matching.empty:
        units_matching = prepare_units(units_matching)

    person_stats = {
        "source_protocol_id_mismatches": 0,
        "missing_parents": 0,
        "ambiguous_parents": 0,
    }
    if not persons_matching.empty:
        person_stats = resolve_person_parents(persons_matching, versions)

    unit_stats = {"missing_parents": 0}
    if not units_matching.empty and not persons_matching.empty:
        unit_stats = resolve_unit_parents(units_matching, persons_matching)

    report.update(
        {
            "matching_personnel": len(persons_matching),
            "matching_units": len(units_matching),
            "unresolved_parents": (
                person_stats["missing_parents"] + unit_stats["missing_parents"]
            ),
            "ambiguous_parents": person_stats["ambiguous_parents"],
            "missing_email": (
                int(persons_matching["email_address"].isna().sum())
                if not persons_matching.empty
                else 0
            ),
            "missing_role_description": (
                int(
                    persons_matching["protocol_person_role_description"]
                    .isna()
                    .sum()
                )
                if not persons_matching.empty
                else 0
            ),
            "missing_unit_name": (
                int(units_matching["unit_name"].isna().sum())
                if not units_matching.empty
                else 0
            ),
        }
    )

    logger.info(
        "Dry run (--limit {}) - bounded, coherent sample, no PostgreSQL "
        "connection made:\n"
        "  sampled protocol versions: {}\n"
        "  matching personnel:        {}\n"
        "  matching units:            {}\n"
        "  unresolved parents:        {}\n"
        "  ambiguous parents:         {}\n"
        "  missing email:             {}\n"
        "  missing role description:  {}\n"
        "  missing unit name:         {}",
        limit,
        report["sampled_versions"],
        report["matching_personnel"],
        report["matching_units"],
        report["unresolved_parents"],
        report["ambiguous_parents"],
        report["missing_email"],
        report["missing_role_description"],
        report["missing_unit_name"],
    )
    return report


def main() -> None:
    arguments = parse_args()

    if arguments.limit is not None:
        _run_limited_sample(arguments.limit)
        return

    logger.info("Reading Protocol versions/personnel/units from Oracle")
    versions = prepare_versions(OracleDataSource(VERSIONS_ORACLE_SQL).read())
    persons = prepare_persons(OracleDataSource(PERSONS_ORACLE_SQL).read())
    units = prepare_units(OracleDataSource(UNITS_ORACLE_SQL).read())

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

    # Built up incrementally so that even a failure raised before the write
    # transaction (e.g. unresolved/ambiguous parents) still has read counts
    # available for mark_load_failed to persist below.
    validation_report: dict[str, int] = {
        "versions_read": len(versions),
        "personnel_read": len(persons),
        "units_read": len(units),
    }

    try:
        person_stats = resolve_person_parents(persons, versions)
        unit_stats = resolve_unit_parents(units, persons)

        unresolved_parents = (
            person_stats["missing_parents"] + unit_stats["missing_parents"]
        )
        ambiguous_parents = person_stats["ambiguous_parents"]

        validation_report.update(
            {
                "unresolved_parents": unresolved_parents,
                "ambiguous_parents": ambiguous_parents,
                "personnel_source_protocol_id_mismatches": person_stats[
                    "source_protocol_id_mismatches"
                ],
                "missing_email": int(persons["email_address"].isna().sum()),
                "missing_role_description": int(
                    persons["protocol_person_role_description"].isna().sum()
                ),
                "missing_unit_name": int(units["unit_name"].isna().sum()),
            }
        )

        if unresolved_parents or ambiguous_parents:
            # Never silently drop the affected rows, and never write a
            # partial archive: abort the entire load before anything is
            # truncated or copied. The full counts are already in
            # validation_report for mark_load_failed to persist below.
            raise RuntimeError(
                "Protocol parent resolution failed: "
                f"{unresolved_parents} unresolved and {ambiguous_parents} "
                "ambiguous parent relationships - aborting load without "
                "writing any rows"
            )

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
            validation_report.update(
                {
                    "versions_loaded": version_rows,
                    "personnel_loaded": person_rows,
                    "units_loaded": unit_rows,
                }
            )
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
        mark_load_failed(engine, load_id, str(error), validation_report)
        logger.exception("Protocol load failed")
        raise


if __name__ == "__main__":
    main()
