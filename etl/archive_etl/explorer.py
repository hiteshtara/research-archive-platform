"""Read-only Archive Explorer CLI: `python -m archive_etl explore <resource>`.

Phase 1 (this module): ECS-runnable command-line explorer only, queried
directly against the already-archived PostgreSQL data (never Oracle -
the archive itself is the thing being explored). Every resource uses a
FIXED, predefined SQL query with bound parameters - there is no
arbitrary-SQL code path anywhere in this module, by design. Phase 2
(authenticated Spring Boot endpoints + a React /explorer page) is
intentionally NOT built here - see docs/ARCHIVE_EXPLORER.md.

Resources:
    award                 - current-version summary for an award_number
    award-version         - one specific archive.award_version row by award_id
    workflow               - looks up which award_id/sequence owns a
                              workflow_document_number, across every
                              archived version (not just current)
    unit                   - Unit Details + its Unit Administrators
    unit-administrators     - just the administrators list for a unit
    award-contacts          - Key Personnel / Unit Contacts / Sponsor
                              Contacts / Central Administration Contacts
                              for one award_id - Central Administration
                              Contacts follows the proven Kuali rule
                              exactly: Award.lead_unit_number ->
                              unit_administrator -> unit_administrator_type
                              WHERE default_group_flag = 'C' (see
                              docs/architecture/AWARD_CONTACTS_DESIGN.md)
    person                  - one archive.person row by person_id
    rolodex                 - one archive.rolodex row by rolodex_id
    sponsor                 - just the Sponsor Contacts section for an
                              award_id (subset of award-contacts)
    attachments             - attachment metadata for an award_id
"""

from __future__ import annotations

import argparse
import json
import re
from typing import Any

import boto3
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.startup_validation import validate_aws_identity
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.structured_logging import configure_structured_logging

EXPECTED_ACCOUNT_ID = "770203350335"

DEFAULT_LIST_LIMIT = 50
MAX_LIST_LIMIT = 200

_AWARD_NUMBER_RE = re.compile(r"^[A-Za-z0-9\-]{1,60}$")
_UNIT_NUMBER_RE = re.compile(r"^[A-Za-z0-9]{1,20}$")
_PERSON_ID_RE = re.compile(r"^[A-Za-z0-9]{1,40}$")
_WORKFLOW_DOCUMENT_NUMBER_RE = re.compile(r"^[A-Za-z0-9]{1,40}$")


class ExplorerInputError(ValueError):
    """Raised for a rejected/invalid identifier - never used to build SQL,
    only to validate a value before it is bound as a parameter."""


def _validate(value: str, pattern: re.Pattern[str], label: str) -> str:
    if not pattern.match(value):
        raise ExplorerInputError(
            f"Invalid {label}: {value!r} (rejected - never passed to SQL)"
        )
    return value


def _validate_int(value: int, label: str) -> int:
    if value <= 0:
        raise ExplorerInputError(f"Invalid {label}: {value!r} (must be positive)")
    return value


def _row_to_dict(row: Any) -> dict[str, Any] | None:
    if row is None:
        return None
    return dict(row)


def _rows_to_list(rows: Any, limit: int) -> tuple[list[dict[str, Any]], bool]:
    materialized = [dict(row) for row in rows]
    truncated = len(materialized) > limit
    return materialized[:limit], truncated


# --- award --------------------------------------------------------------

_AWARD_SQL = text(
    """
    SELECT award_id, award_number, sequence_number, status_description,
           workflow_document_number, modification_number,
           lead_unit_number, lead_unit_name, is_primary_current
    FROM archive.award_version
    WHERE award_number = :award_number
      AND is_primary_current = TRUE
    """
)


def fetch_award(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    award_number = _validate(args.award_number, _AWARD_NUMBER_RE, "award-number")
    row = connection.execute(
        _AWARD_SQL, {"award_number": award_number}
    ).mappings().one_or_none()
    return {"award": _row_to_dict(row)}


def render_award(data: dict[str, Any]) -> str:
    award = data.get("award")
    if award is None:
        return "AWARD\n(no current version found for this award_number)"
    lines = ["AWARD"]
    lines.append(f"Award number:       {award['award_number']}")
    lines.append(f"Award ID:           {award['award_id']}")
    lines.append(f"Sequence:           {award['sequence_number']}")
    lines.append(f"Workflow document:  {award['workflow_document_number'] or '-'}")
    lines.append(f"Modification #:     {award['modification_number'] or '-'}")
    lines.append(f"Lead unit:          {award['lead_unit_number'] or '-'}")
    lines.append(f"Unit name:          {award['lead_unit_name'] or '-'}")
    lines.append(f"Status:             {award['status_description'] or '-'}")
    return "\n".join(lines)


# --- award-version --------------------------------------------------------

_AWARD_VERSION_SQL = text(
    """
    SELECT award_id, award_number, sequence_number, status_description,
           workflow_document_number, modification_number,
           lead_unit_number, lead_unit_name, is_primary_current,
           source_update_timestamp
    FROM archive.award_version
    WHERE award_id = :award_id
    """
)


def fetch_award_version(
    connection: Connection, args: argparse.Namespace
) -> dict[str, Any]:
    award_id = _validate_int(args.award_id, "award-id")
    row = connection.execute(
        _AWARD_VERSION_SQL, {"award_id": award_id}
    ).mappings().one_or_none()
    return {"award_version": _row_to_dict(row)}


def render_award_version(data: dict[str, Any]) -> str:
    version = data.get("award_version")
    if version is None:
        return "AWARD VERSION\n(no such award_id)"
    lines = ["AWARD VERSION"]
    for key, label in (
        ("award_id", "Award ID"),
        ("award_number", "Award number"),
        ("sequence_number", "Sequence"),
        ("status_description", "Status"),
        ("workflow_document_number", "Workflow document"),
        ("modification_number", "Modification #"),
        ("lead_unit_number", "Lead unit"),
        ("lead_unit_name", "Unit name"),
        ("is_primary_current", "Current"),
        ("source_update_timestamp", "Updated"),
    ):
        lines.append(f"{label + ':':<20}{version.get(key) or '-'}")
    return "\n".join(lines)


# --- workflow -------------------------------------------------------------

_WORKFLOW_SQL = text(
    """
    SELECT award_id, award_number, sequence_number, status_description,
           is_primary_current
    FROM archive.award_version
    WHERE workflow_document_number = :document_number
    ORDER BY sequence_number DESC
    """
)


def fetch_workflow(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    document_number = _validate(
        args.document_number, _WORKFLOW_DOCUMENT_NUMBER_RE, "document-number"
    )
    rows, truncated = _rows_to_list(
        connection.execute(
            _WORKFLOW_SQL, {"document_number": document_number}
        ).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    return {"matches": rows, "truncated": truncated}


def render_workflow(data: dict[str, Any]) -> str:
    matches = data.get("matches") or []
    if not matches:
        return "WORKFLOW DOCUMENT\n(no archived version has this workflow document number)"
    lines = ["WORKFLOW DOCUMENT"]
    for row in matches:
        lines.append(
            f"{row['award_number']:<20} seq {row['sequence_number']:<5} "
            f"award_id={row['award_id']:<10} "
            f"{'CURRENT' if row['is_primary_current'] else ''}"
        )
    return "\n".join(lines)


# --- unit / unit-administrators -------------------------------------------

_UNIT_SQL = text(
    """
    SELECT u.unit_number, u.unit_name, u.parent_unit_number,
           p.unit_name AS parent_unit_name, u.organization_id, u.active
    FROM archive.unit u
    LEFT JOIN archive.unit p ON p.unit_number = u.parent_unit_number
    WHERE u.unit_number = :unit_number
    """
)

_UNIT_ADMINISTRATORS_SQL = text(
    """
    SELECT ua.unit_number, ua.person_id, ua.unit_administrator_type_code,
           uat.description AS administrator_type_description,
           uat.default_group_flag,
           pe.full_name, pe.email_address, pe.phone_number
    FROM archive.unit_administrator ua
    LEFT JOIN archive.unit_administrator_type uat
           ON uat.unit_administrator_type_code = ua.unit_administrator_type_code
    LEFT JOIN archive.person pe ON pe.person_id = ua.person_id
    WHERE ua.unit_number = :unit_number
    ORDER BY ua.unit_administrator_type_code, ua.person_id
    """
)


def _fetch_unit_administrators(
    connection: Connection, unit_number: str
) -> list[dict[str, Any]]:
    rows, _ = _rows_to_list(
        connection.execute(
            _UNIT_ADMINISTRATORS_SQL, {"unit_number": unit_number}
        ).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    return rows


def fetch_unit(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    unit_number = _validate(args.unit_number, _UNIT_NUMBER_RE, "unit-number")
    row = connection.execute(
        _UNIT_SQL, {"unit_number": unit_number}
    ).mappings().one_or_none()
    unit = _row_to_dict(row)
    administrators = (
        _fetch_unit_administrators(connection, unit_number) if unit else []
    )
    return {"unit": unit, "administrators": administrators}


def fetch_unit_administrators(
    connection: Connection, args: argparse.Namespace
) -> dict[str, Any]:
    unit_number = _validate(args.unit_number, _UNIT_NUMBER_RE, "unit-number")
    return {"administrators": _fetch_unit_administrators(connection, unit_number)}


def _render_administrators(administrators: list[dict[str, Any]]) -> list[str]:
    lines = []
    for admin in administrators:
        name = admin.get("full_name") or admin.get("person_id")
        role = admin.get("administrator_type_description") or "-"
        lines.append(f"{name:<20} {role:<28} {admin.get('email_address') or '-'}")
    return lines


def render_unit(data: dict[str, Any]) -> str:
    unit = data.get("unit")
    if unit is None:
        return "UNIT\n(no such unit_number)"
    lines = ["UNIT"]
    lines.append(f"Unit number:        {unit['unit_number']}")
    lines.append(f"Unit name:          {unit['unit_name'] or '-'}")
    lines.append(f"Parent unit:        {unit['parent_unit_number'] or '-'}")
    lines.append(f"Parent unit name:   {unit['parent_unit_name'] or '-'}")
    lines.append(f"Organization:       {unit['organization_id'] or '-'}")
    lines.append("")
    lines.append("ADMINISTRATORS")
    administrators = data.get("administrators") or []
    if not administrators:
        lines.append("(none)")
    else:
        lines.extend(_render_administrators(administrators))
    return "\n".join(lines)


def render_unit_administrators(data: dict[str, Any]) -> str:
    lines = ["UNIT ADMINISTRATORS"]
    administrators = data.get("administrators") or []
    if not administrators:
        lines.append("(none)")
    else:
        lines.extend(_render_administrators(administrators))
    return "\n".join(lines)


# --- award-contacts / sponsor ----------------------------------------------

_AWARD_LEAD_UNIT_SQL = text(
    """
    SELECT award_id, award_number, sequence_number, lead_unit_number
    FROM archive.award_version
    WHERE award_id = :award_id
    """
)

_KEY_PERSONNEL_SQL = text(
    """
    SELECT full_name, contact_role_code, key_person_project_role
    FROM archive.award_person
    WHERE award_id = :award_id
    ORDER BY award_person_id
    """
)

_UNIT_CONTACTS_SQL = text(
    """
    SELECT auc.full_name, auc.person_id, auc.unit_contact_type,
           auc.unit_administrator_type_code, auc.unit_administrator_unit_number,
           pe.email_address, pe.phone_number
    FROM archive.award_unit_contact auc
    LEFT JOIN archive.person pe ON pe.person_id = auc.person_id
    WHERE auc.award_id = :award_id
    ORDER BY auc.award_unit_contact_id
    """
)

_SPONSOR_CONTACTS_SQL = text(
    """
    SELECT asc_.full_name, asc_.contact_role_code, asc_.rolodex_id,
           r.organization, r.phone_number, r.email_address
    FROM archive.award_sponsor_contact asc_
    LEFT JOIN archive.rolodex r ON r.rolodex_id = asc_.rolodex_id
    WHERE asc_.award_id = :award_id
    ORDER BY asc_.award_sponsor_contact_id
    """
)


def _fetch_central_admin_contacts(
    connection: Connection, lead_unit_number: str | None
) -> list[dict[str, Any]]:
    """Reproduces Award.initCentralAdminContacts() exactly: the Award's
    lead unit's administrators, filtered to default_group_flag='C' - see
    docs/architecture/AWARD_CONTACTS_DESIGN.md for the Java trace this
    mirrors. Never a guessed rule."""
    if not lead_unit_number:
        return []
    rows, _ = _rows_to_list(
        connection.execute(
            text(
                """
                SELECT ua.person_id, uat.description AS administrator_type_description,
                       pe.full_name, pe.email_address, pe.phone_number
                FROM archive.unit_administrator ua
                JOIN archive.unit_administrator_type uat
                     ON uat.unit_administrator_type_code = ua.unit_administrator_type_code
                LEFT JOIN archive.person pe ON pe.person_id = ua.person_id
                WHERE ua.unit_number = :unit_number
                  AND uat.default_group_flag = 'C'
                ORDER BY ua.unit_administrator_type_code, ua.person_id
                """
            ),
            {"unit_number": lead_unit_number},
        ).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    return rows


def fetch_award_contacts(
    connection: Connection, args: argparse.Namespace
) -> dict[str, Any]:
    award_id = _validate_int(args.award_id, "award-id")
    award = connection.execute(
        _AWARD_LEAD_UNIT_SQL, {"award_id": award_id}
    ).mappings().one_or_none()
    if award is None:
        return {"award": None}

    key_personnel, _ = _rows_to_list(
        connection.execute(_KEY_PERSONNEL_SQL, {"award_id": award_id}).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    unit_contacts, _ = _rows_to_list(
        connection.execute(_UNIT_CONTACTS_SQL, {"award_id": award_id}).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    sponsor_contacts, _ = _rows_to_list(
        connection.execute(_SPONSOR_CONTACTS_SQL, {"award_id": award_id}).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    central_admin_contacts = _fetch_central_admin_contacts(
        connection, award["lead_unit_number"]
    )

    return {
        "award": dict(award),
        "key_personnel": key_personnel,
        "unit_contacts": unit_contacts,
        "sponsor_contacts": sponsor_contacts,
        "central_administration_contacts": central_admin_contacts,
    }


def fetch_sponsor(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    award_id = _validate_int(args.award_id, "award-id")
    rows, _ = _rows_to_list(
        connection.execute(_SPONSOR_CONTACTS_SQL, {"award_id": award_id}).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    return {"sponsor_contacts": rows}


def _render_section(title: str, rows: list[dict[str, Any]], columns: list[str]) -> list[str]:
    lines = ["", title]
    if not rows:
        lines.append("(none)")
        return lines
    for row in rows:
        lines.append("  ".join(str(row.get(c) or "-") for c in columns))
    return lines


def render_award_contacts(data: dict[str, Any]) -> str:
    award = data.get("award")
    if award is None:
        return "AWARD CONTACTS\n(no such award_id)"
    lines = ["AWARD CONTACTS"]
    lines.append(f"Award number:  {award['award_number']}  (sequence {award['sequence_number']})")
    lines.append(f"Lead unit:     {award.get('lead_unit_number') or '-'}")

    lines.extend(
        _render_section(
            "KEY PERSONNEL",
            data.get("key_personnel") or [],
            ["full_name", "contact_role_code", "key_person_project_role"],
        )
    )
    lines.extend(
        _render_section(
            "UNIT CONTACTS",
            data.get("unit_contacts") or [],
            ["full_name", "unit_contact_type", "email_address", "phone_number"],
        )
    )
    lines.extend(
        _render_section(
            "SPONSOR CONTACTS",
            data.get("sponsor_contacts") or [],
            ["full_name", "organization", "phone_number", "email_address"],
        )
    )
    admins = data.get("central_administration_contacts") or []
    lines.append("")
    lines.append("CENTRAL ADMINISTRATION CONTACTS")
    if not admins:
        lines.append("(none)")
    else:
        for row in admins:
            name = row.get("full_name") or row.get("person_id")
            lines.append(f"{name:<20}{row.get('administrator_type_description') or '-'}")
    return "\n".join(lines)


def render_sponsor(data: dict[str, Any]) -> str:
    lines = ["SPONSOR CONTACTS"]
    rows = data.get("sponsor_contacts") or []
    if not rows:
        lines.append("(none)")
    else:
        for row in rows:
            lines.append(
                f"{row.get('full_name') or '-':<24} {row.get('organization') or '-':<24} "
                f"{row.get('phone_number') or '-':<16} {row.get('email_address') or '-'}"
            )
    return "\n".join(lines)


# --- person / rolodex -----------------------------------------------------

_PERSON_SQL = text(
    """
    SELECT person_id, first_name, middle_name, last_name, full_name,
           email_address, phone_number
    FROM archive.person
    WHERE person_id = :person_id
    """
)

_ROLODEX_SQL = text(
    """
    SELECT rolodex_id, first_name, last_name, organization, phone_number,
           email_address, city, state, active
    FROM archive.rolodex
    WHERE rolodex_id = :rolodex_id
    """
)


def fetch_person(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    person_id = _validate(args.person_id, _PERSON_ID_RE, "person-id")
    row = connection.execute(
        _PERSON_SQL, {"person_id": person_id}
    ).mappings().one_or_none()
    return {"person": _row_to_dict(row)}


def render_person(data: dict[str, Any]) -> str:
    person = data.get("person")
    if person is None:
        return (
            "PERSON\n(not archived - only person_ids referenced by "
            "unit_administrator/award_unit_contact are archived)"
        )
    lines = ["PERSON"]
    for key, label in (
        ("person_id", "Person ID"),
        ("full_name", "Full name"),
        ("email_address", "Email"),
        ("phone_number", "Phone"),
    ):
        lines.append(f"{label + ':':<12}{person.get(key) or '-'}")
    return "\n".join(lines)


def fetch_rolodex(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    rolodex_id = _validate_int(args.rolodex_id, "rolodex-id")
    row = connection.execute(
        _ROLODEX_SQL, {"rolodex_id": rolodex_id}
    ).mappings().one_or_none()
    return {"rolodex": _row_to_dict(row)}


def render_rolodex(data: dict[str, Any]) -> str:
    rolodex = data.get("rolodex")
    if rolodex is None:
        return "ROLODEX\n(no such rolodex_id)"
    lines = ["ROLODEX"]
    for key, label in (
        ("rolodex_id", "Rolodex ID"),
        ("organization", "Organization"),
        ("phone_number", "Phone"),
        ("email_address", "Email"),
        ("city", "City"),
        ("state", "State"),
        ("active", "Active"),
    ):
        lines.append(f"{label + ':':<14}{rolodex.get(key) or '-'}")
    return "\n".join(lines)


# --- attachments ------------------------------------------------------------

_ATTACHMENTS_SQL = text(
    """
    SELECT aa.award_attachment_id, aa.type_code, aa.description,
           aa.document_status_code, ao.file_name, ao.content_type,
           ao.upload_status
    FROM archive.award_attachment aa
    LEFT JOIN archive.attachment_object ao ON ao.file_id = aa.file_id
    WHERE aa.award_id = :award_id
    ORDER BY aa.award_attachment_id
    """
)


def fetch_attachments(connection: Connection, args: argparse.Namespace) -> dict[str, Any]:
    award_id = _validate_int(args.award_id, "award-id")
    rows, truncated = _rows_to_list(
        connection.execute(_ATTACHMENTS_SQL, {"award_id": award_id}).mappings(),
        DEFAULT_LIST_LIMIT,
    )
    return {"attachments": rows, "truncated": truncated}


def render_attachments(data: dict[str, Any]) -> str:
    lines = ["ATTACHMENTS"]
    rows = data.get("attachments") or []
    if not rows:
        lines.append("(none)")
    else:
        for row in rows:
            lines.append(
                f"{row.get('file_name') or '-':<40} {row.get('upload_status') or '-':<12} "
                f"{row.get('description') or '-'}"
            )
    if data.get("truncated"):
        lines.append(f"... truncated at {DEFAULT_LIST_LIMIT} rows")
    return "\n".join(lines)


# --- resource registry ------------------------------------------------------


class _Resource:
    def __init__(self, name, add_arguments, fetch, render, identifier_arg):
        self.name = name
        self.add_arguments = add_arguments
        self.fetch = fetch
        self.render = render
        self.identifier_arg = identifier_arg


def _add_award_number_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--award-number", required=True)


def _add_award_id_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--award-id", required=True, type=int)


def _add_unit_number_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--unit-number", required=True)


RESOURCES: dict[str, _Resource] = {
    "award": _Resource(
        "award", _add_award_number_arg, fetch_award, render_award, "award_number"
    ),
    "award-version": _Resource(
        "award-version",
        _add_award_id_arg,
        fetch_award_version,
        render_award_version,
        "award_id",
    ),
    "workflow": _Resource(
        "workflow",
        lambda p: p.add_argument("--document-number", required=True),
        fetch_workflow,
        render_workflow,
        "document_number",
    ),
    "unit": _Resource(
        "unit", _add_unit_number_arg, fetch_unit, render_unit, "unit_number"
    ),
    "unit-administrators": _Resource(
        "unit-administrators",
        _add_unit_number_arg,
        fetch_unit_administrators,
        render_unit_administrators,
        "unit_number",
    ),
    "award-contacts": _Resource(
        "award-contacts",
        _add_award_id_arg,
        fetch_award_contacts,
        render_award_contacts,
        "award_id",
    ),
    "person": _Resource(
        "person",
        lambda p: p.add_argument("--person-id", required=True),
        fetch_person,
        render_person,
        "person_id",
    ),
    "rolodex": _Resource(
        "rolodex",
        lambda p: p.add_argument("--rolodex-id", required=True, type=int),
        fetch_rolodex,
        render_rolodex,
        "rolodex_id",
    ),
    "sponsor": _Resource(
        "sponsor", _add_award_id_arg, fetch_sponsor, render_sponsor, "award_id"
    ),
    "attachments": _Resource(
        "attachments",
        _add_award_id_arg,
        fetch_attachments,
        render_attachments,
        "award_id",
    ),
}


def _output_parent_parser() -> argparse.ArgumentParser:
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument("--output", choices=["table", "json"], default="table")
    return parent


def build_parser() -> argparse.ArgumentParser:
    # --output is registered on BOTH the top-level parser (so
    # `explore --output json award ...` works) and each resource
    # subparser via a shared parent (so the more natural
    # `explore award ... --output json` - every example in
    # scripts/run-archive-explorer.sh's own header uses this order -
    # also works, which plain argparse subparsers do not support
    # without this).
    output_parent = _output_parent_parser()
    parser = argparse.ArgumentParser(
        prog="archive_etl explore",
        parents=[output_parent],
        description=(
            "Read-only Archive Explorer - queries already-archived "
            "PostgreSQL data using fixed, predefined SQL only. Never "
            "accepts arbitrary SQL."
        ),
    )
    subparsers = parser.add_subparsers(dest="resource", required=True)
    for name, resource in RESOURCES.items():
        resource_parser = subparsers.add_parser(
            name, parents=[_output_parent_parser()]
        )
        resource.add_arguments(resource_parser)
    return parser


def _identifier_for_log(args: argparse.Namespace, resource: _Resource) -> str:
    return str(getattr(args, resource.identifier_arg, ""))


def run_explore(args: argparse.Namespace, engine: Engine) -> str:
    resource = RESOURCES[args.resource]
    identifier = _identifier_for_log(args, resource)
    logger.bind(stage="explore", resource=resource.name, identifier=identifier).info(
        "Explorer query"
    )

    with engine.connect() as connection:
        try:
            data = resource.fetch(connection, args)
        except ExplorerInputError as error:
            return f"ERROR: {error}"

    if args.output == "json":
        return json.dumps(data, indent=2, default=str)
    return resource.render(data)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    configure_structured_logging("explorer")

    # Fail-closed identity check, mirroring every other --ecs entry
    # point (see load_awards_from_csv.py's _run_ecs_setup) - never
    # proceed against an unverified/unexpected AWS account.
    identity = validate_aws_identity(boto3.client("sts"))
    if identity["account"] != EXPECTED_ACCOUNT_ID:
        print(
            f"ERROR: unexpected AWS account {identity['account']!r} "
            f"(expected {EXPECTED_ACCOUNT_ID!r}) - refusing to proceed"
        )
        return 1

    configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)
    engine = create_postgres_engine()

    print(run_explore(args, engine))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
