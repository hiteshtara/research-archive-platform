"""Shared reference-data entities backing the Award Contacts feature
(Unit Details / Central Administration Contacts / Unit Contacts /
Sponsor Contacts) - Unit, UnitAdministrator, UnitAdministratorType,
Rolodex, Person. Deliberately NOT Award-owned data: Award references
these by natural key (lead_unit_number, unit_administrator.person_id,
award_sponsor_contact.rolodex_id, award_unit_contact.person_id) rather
than duplicating them. See database/migrations/V056 and
docs/architecture/AWARD_CONTACTS_DESIGN.md.

unit_administrator_type / unit / unit_administrator / rolodex are full
reference-data loads (each individually small and bounded - verified
live against BU Oracle: 11 / ~5.1K / ~1K / ~12.5K rows respectively).
person is a TARGETED load only, via OracleDataSource.read_filtered - a
full scan of Rice KIM's KRIM_PRNCPL_T would cover every BU affiliate
and is never appropriate here.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.pipeline.validation import normalize_columns


def _resolve_project_root() -> Path:
    """Mirrors load_awards_from_csv.py's own _resolve_project_root()
    exactly: this file lives at <repo>/etl/archive_etl/reference_data.py
    locally (project root two levels up), but is copied to
    /app/archive_etl/reference_data.py in the ECS loader container
    image, where sql/ is directly under /app (one level up) - see
    etl/Dockerfile.loader."""
    container_root = Path(__file__).resolve().parents[1]
    if (container_root / "sql").is_dir():
        return container_root
    return Path(__file__).resolve().parents[2]


REFERENCE_SQL_DIR = _resolve_project_root() / "sql" / "extract" / "reference"

UNIT_ADMINISTRATOR_TYPE_SQL = REFERENCE_SQL_DIR / "01_unit_administrator_type.sql"
UNIT_SQL = REFERENCE_SQL_DIR / "02_unit.sql"
UNIT_ADMINISTRATOR_SQL = REFERENCE_SQL_DIR / "03_unit_administrator.sql"
ROLODEX_SQL = REFERENCE_SQL_DIR / "04_rolodex.sql"
PERSON_SQL = REFERENCE_SQL_DIR / "05_person.sql"
COMMENT_TYPE_SQL = REFERENCE_SQL_DIR / "06_comment_type.sql"


def _sql_value(value: Any) -> Any:
    if pd.isna(value):
        return None
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def _upsert_rows(
    connection: Connection,
    *,
    table: str,
    pk_columns: list[str],
    columns: list[str],
    rows: pd.DataFrame,
    load_id: int,
) -> dict[str, int]:
    """Generic natural-key UPSERT for a small reference table - every
    column (besides the primary key) is compared with IS DISTINCT FROM
    so an unchanged row is never rewritten, mirroring
    upsert_award_sponsor_contact/upsert_award_unit_contact's own
    per-row IS DISTINCT FROM convention in load_awards_from_csv.py."""
    counts = {"inserted": 0, "updated": 0, "unchanged": 0}
    if rows.empty:
        return counts

    non_pk_columns = [c for c in columns if c not in pk_columns]
    all_columns = pk_columns + non_pk_columns

    insert_columns = ", ".join(all_columns + ["load_id"])
    insert_values = ", ".join(f":{c}" for c in all_columns) + ", :load_id"
    conflict_columns = ", ".join(pk_columns)
    update_set = ",\n                ".join(
        f"{c} = EXCLUDED.{c}" for c in non_pk_columns + ["load_id"]
    )
    distinct_where = " OR\n                ".join(
        f"archive.{table}.{c} IS DISTINCT FROM EXCLUDED.{c}"
        for c in non_pk_columns
    )

    sql = text(
        f"""
        INSERT INTO archive.{table} ({insert_columns})
        VALUES ({insert_values})
        ON CONFLICT ({conflict_columns}) DO UPDATE SET
                {update_set}
        WHERE
                {distinct_where}
        RETURNING (xmax = 0) AS inserted
        """
    )

    for _, row in rows.iterrows():
        params = {c: _sql_value(row.get(c)) for c in all_columns}
        params["load_id"] = load_id
        result = connection.execute(sql, params).mappings().one_or_none()
        if result is None:
            counts["unchanged"] += 1
        elif result["inserted"]:
            counts["inserted"] += 1
        else:
            counts["updated"] += 1

    return counts


_UNIT_ADMINISTRATOR_TYPE_COLUMNS = [
    "unit_administrator_type_code",
    "description",
    "default_group_flag",
    "multiples_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_UNIT_COLUMNS = [
    "unit_number",
    "unit_name",
    "parent_unit_number",
    "organization_id",
    "active",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_UNIT_ADMINISTRATOR_COLUMNS = [
    "unit_number",
    "person_id",
    "unit_administrator_type_code",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_ROLODEX_COLUMNS = [
    "rolodex_id",
    "last_name",
    "first_name",
    "middle_name",
    "suffix",
    "prefix",
    "title",
    "organization",
    "phone_number",
    "email_address",
    "address_line_1",
    "address_line_2",
    "address_line_3",
    "city",
    "county",
    "state",
    "postal_code",
    "country_code",
    "owned_by_unit",
    "active",
    "delete_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]

_PERSON_COLUMNS = [
    "person_id",
    "first_name",
    "middle_name",
    "last_name",
    "full_name",
    "email_address",
    "phone_number",
]

_COMMENT_TYPE_COLUMNS = [
    "comment_type_code",
    "description",
    "template_flag",
    "checklist_flag",
    "award_comment_screen_flag",
    "source_update_timestamp",
    "source_update_user",
    "source_version_number",
]


def _rename(dataframe: pd.DataFrame, mapping: dict[str, str]) -> pd.DataFrame:
    return dataframe.rename(columns=mapping)


def load_unit_administrator_types(
    connection: Connection, load_id: int
) -> dict[str, int]:
    frame = OracleDataSource(UNIT_ADMINISTRATOR_TYPE_SQL).read()
    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
        },
    )
    return _upsert_rows(
        connection,
        table="unit_administrator_type",
        pk_columns=["unit_administrator_type_code"],
        columns=_UNIT_ADMINISTRATOR_TYPE_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def load_units(connection: Connection, load_id: int) -> dict[str, int]:
    frame = OracleDataSource(UNIT_SQL).read()
    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
        },
    )
    if "active_flag" in frame.columns:
        frame["active"] = frame["active_flag"] == "Y"
    return _upsert_rows(
        connection,
        table="unit",
        pk_columns=["unit_number"],
        columns=_UNIT_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def load_unit_administrators(
    connection: Connection, load_id: int
) -> dict[str, int]:
    frame = OracleDataSource(UNIT_ADMINISTRATOR_SQL).read()
    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
        },
    )
    return _upsert_rows(
        connection,
        table="unit_administrator",
        pk_columns=["unit_number", "person_id", "unit_administrator_type_code"],
        columns=_UNIT_ADMINISTRATOR_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def load_rolodex(connection: Connection, load_id: int) -> dict[str, int]:
    frame = OracleDataSource(ROLODEX_SQL).read()
    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
        },
    )
    if "actv_ind" in frame.columns:
        frame["active"] = frame["actv_ind"] == "Y"
    return _upsert_rows(
        connection,
        table="rolodex",
        pk_columns=["rolodex_id"],
        columns=_ROLODEX_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def _referenced_person_ids(connection: Connection) -> list[str]:
    """Every person_id this phase's Award Contacts feature actually
    needs to resolve a name/email/phone for - archive.unit_administrator
    (Central Administration Contacts) union archive.award_unit_contact
    (Unit Contacts). Never a full scan of Rice KIM's identity tables."""
    rows = connection.execute(
        text(
            """
            SELECT DISTINCT person_id FROM archive.unit_administrator
            UNION
            SELECT DISTINCT person_id FROM archive.award_unit_contact
             WHERE person_id IS NOT NULL
            """
        )
    ).scalars()
    return [str(value) for value in rows]


def load_person_reference_data(
    connection: Connection, load_id: int
) -> dict[str, int]:
    person_ids = _referenced_person_ids(connection)
    if not person_ids:
        return {"inserted": 0, "updated": 0, "unchanged": 0}

    frame = OracleDataSource(PERSON_SQL).read_filtered(
        column="PERSON_ID", values=person_ids
    )
    if frame.empty:
        return {"inserted": 0, "updated": 0, "unchanged": 0}

    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "first_nm": "first_name",
            "middle_nm": "middle_name",
            "last_nm": "last_name",
            "email_addr": "email_address",
            "phone_nbr": "phone_number",
        },
    )
    frame["full_name"] = (
        frame[["first_name", "middle_name", "last_name"]]
        .fillna("")
        .agg(" ".join, axis=1)
        .str.replace(r"\s+", " ", regex=True)
        .str.strip()
    )
    return _upsert_rows(
        connection,
        table="person",
        pk_columns=["person_id"],
        columns=_PERSON_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def load_comment_types(connection: Connection, load_id: int) -> dict[str, int]:
    frame = OracleDataSource(COMMENT_TYPE_SQL).read()
    normalize_columns(frame)
    frame = _rename(
        frame,
        {
            "update_timestamp": "source_update_timestamp",
            "update_user": "source_update_user",
            "ver_nbr": "source_version_number",
        },
    )
    return _upsert_rows(
        connection,
        table="comment_type",
        pk_columns=["comment_type_code"],
        columns=_COMMENT_TYPE_COLUMNS,
        rows=frame,
        load_id=load_id,
    )


def run_load_comment_type_reference_data(
    engine: Engine, *, dry_run: bool = False
) -> dict[str, Any]:
    """Loads archive.comment_type - independent of the Unit/Person
    reference bundle above (no FK relationship to it), so it gets its
    own top-level entry point and CLI flag rather than being folded
    into run_load_unit_reference_data's FK-ordered chain. See
    database/migrations/V057 and
    docs/architecture/AWARD_COMMENT_DESIGN.md."""
    started = time.perf_counter()
    report: dict[str, Any] = {}

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = connection.execute(
                text(
                    """
                    INSERT INTO archive.load_run (
                        domain, source_system, source_file_name,
                        rows_read, status
                    ) VALUES (
                        'COMMENT_TYPE_REFERENCE_DATA', 'KUALI',
                        'Oracle KCOEUS export', 0, 'STARTED'
                    )
                    RETURNING load_id
                    """
                )
            ).scalar_one()

            report["comment_type"] = load_comment_types(connection, load_id)

            connection.execute(
                text(
                    """
                    UPDATE archive.load_run
                       SET status = 'LOADED', completed_at = CURRENT_TIMESTAMP
                     WHERE load_id = :load_id
                    """
                ),
                {"load_id": load_id},
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    report["elapsed_ms"] = (time.perf_counter() - started) * 1000
    logger.bind(stage="load_comment_type_reference_data").info(
        "Comment type reference data load{}: {}",
        " [DRY RUN - not persisted]" if dry_run else "",
        report,
    )
    return report


def run_load_unit_reference_data(
    engine: Engine, *, dry_run: bool = False
) -> dict[str, Any]:
    """Loads unit_administrator_type -> unit -> unit_administrator ->
    rolodex -> person, in that FK-safe order, in one transaction.
    person is loaded last because it depends on unit_administrator (and
    archive.award_unit_contact, loaded independently by the Award
    loader) already being current."""
    started = time.perf_counter()
    report: dict[str, Any] = {}

    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            load_id = connection.execute(
                text(
                    """
                    INSERT INTO archive.load_run (
                        domain, source_system, source_file_name,
                        rows_read, status
                    ) VALUES (
                        'UNIT_REFERENCE_DATA', 'KUALI',
                        'Oracle KCOEUS/Rice KIM export', 0, 'STARTED'
                    )
                    RETURNING load_id
                    """
                )
            ).scalar_one()

            report["unit_administrator_type"] = load_unit_administrator_types(
                connection, load_id
            )
            report["unit"] = load_units(connection, load_id)
            report["unit_administrator"] = load_unit_administrators(
                connection, load_id
            )
            report["rolodex"] = load_rolodex(connection, load_id)
            report["person"] = load_person_reference_data(connection, load_id)

            connection.execute(
                text(
                    """
                    UPDATE archive.load_run
                       SET status = 'LOADED', completed_at = CURRENT_TIMESTAMP
                     WHERE load_id = :load_id
                    """
                ),
                {"load_id": load_id},
            )
        except Exception:
            transaction.rollback()
            raise
        else:
            if dry_run:
                transaction.rollback()
            else:
                transaction.commit()

    report["elapsed_ms"] = (time.perf_counter() - started) * 1000
    logger.bind(stage="load_unit_reference_data").info(
        "Unit reference data load{}: {}",
        " [DRY RUN - not persisted]" if dry_run else "",
        report,
    )
    return report
