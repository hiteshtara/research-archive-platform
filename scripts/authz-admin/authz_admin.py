#!/usr/bin/env python3
"""Record-authorization administration for the Research Archive (authz schema, V082 + V083).

Every write runs in ONE transaction together with its authz.access_audit row(s); any
failure rolls the whole command back. --dry-run performs the same work and rolls back.

The database connection string is read ONLY from the AUTHZ_ADMIN_DATABASE_URL
environment variable. It is never accepted as an argument and never printed.

Run with:  uv run --with 'psycopg[binary]' scripts/authz-admin/authz_admin.py <command> ...
See scripts/authz-admin/README.md.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_crosswalk as vc  # noqa: E402

ENV_DSN = "AUTHZ_ADMIN_DATABASE_URL"
GRANT_TYPES = ("CENTRAL", "UNIT", "IO")


class AdminError(Exception):
    """A refused command. The message never contains the DSN."""


# --- pure validation (unit-tested) -----------------------------------------------------------

def _blank(value) -> bool:
    return value is None or not str(value).strip()


def check_grant_args(grant_type, grantee, granted_by, approved_by, reason, unit=None, io=None,
                     include_descendants=False, expires_at=None):
    """Raises AdminError when a grant request must be refused. Returns the normalised grant."""
    grant_type = (grant_type or "").strip().upper()
    if grant_type not in GRANT_TYPES:
        raise AdminError(f"grant type must be one of {', '.join(GRANT_TYPES)}")
    for name, value in (("--grantee", grantee), ("--granted-by", granted_by),
                        ("--approved-by", approved_by), ("--reason", reason)):
        if _blank(value):
            raise AdminError(f"{name} is required")
    grantee, granted_by, approved_by = grantee.strip(), granted_by.strip(), approved_by.strip()
    if granted_by == grantee:
        raise AdminError("refused: self-grant (--granted-by equals --grantee)")
    if approved_by == grantee:
        raise AdminError("refused: a grantee cannot approve their own grant")
    if grant_type == "CENTRAL" and approved_by == granted_by:
        raise AdminError("refused: a CENTRAL grant needs a second person (--approved-by must differ from --granted-by)")
    if grant_type == "UNIT":
        if _blank(unit) or not _blank(io):
            raise AdminError("a UNIT grant needs --unit and no --io")
    elif grant_type == "IO":
        if _blank(io) or not _blank(unit) or include_descendants:
            raise AdminError("an IO grant needs --io, and no --unit or --include-descendants")
    elif not _blank(unit) or not _blank(io) or include_descendants:
        raise AdminError("a CENTRAL grant takes no --unit, --io or --include-descendants")
    expires = None
    if not _blank(expires_at):
        try:
            expires = dt.datetime.fromisoformat(expires_at.strip())
        except ValueError as exc:
            raise AdminError("--expires-at must be an ISO-8601 timestamp") from exc
        if expires.tzinfo is None:
            raise AdminError("--expires-at must include a UTC offset (e.g. 2027-01-01T00:00:00+00:00)")
    return {
        "grant_type": grant_type,
        "grantee": grantee,
        "granted_by": granted_by,
        "approved_by": approved_by,
        "reason": reason.strip(),
        "unit_number": unit.strip() if grant_type == "UNIT" else None,
        "io_value": io.strip() if grant_type == "IO" else None,
        "include_descendants": bool(include_descendants) if grant_type == "UNIT" else False,
        "expires_at": expires,
    }


def require(value, name):
    if _blank(value):
        raise AdminError(f"{name} is required")
    return str(value).strip()


@dataclass
class ImportPlan:
    principals: list          # (prncpl_id, entity_id, actv_ind)
    rows: list                # (attribute_name, value, prncpl_id, evidence_ref, verified_by)
    skipped_inactive: int


def plan_import(principal_rows, crosswalk_rows, attribute, rolodex_ids=frozenset(), allow_shared_entity=False):
    """Validates the files; returns (problems, plan). plan is None when anything failed."""
    problems, inactive = vc.validate(principal_rows, crosswalk_rows, attribute, rolodex_ids, allow_shared_entity)
    if problems:
        return problems, None
    inactive_rows = set(inactive)
    principals = [(p["prncpl_id"].strip(), p["entity_id"].strip(), p["actv_ind"].strip()) for p in principal_rows]
    rows = [(r["attribute_name"].strip(), r["attribute_value"].strip(), r["prncpl_id"].strip(),
             r["evidence_ref"].strip(), r["verified_by"].strip())
            for r in crosswalk_rows if r["_row"] not in inactive_rows]
    return {}, ImportPlan(principals, rows, len(inactive_rows))


def classify_rows(rows, active_by_value, active_by_principal):
    """Compares planned rows with ACTIVE rows already stored.

    active_by_value: {(attribute_name, value): prncpl_id}; active_by_principal: {(attribute_name, prncpl_id): value}.
    Returns (to_insert, unchanged, conflicts). Any conflict refuses the whole import: an existing ACTIVE
    mapping is never silently replaced (revoke it first with crosswalk-revoke).
    """
    to_insert, unchanged, conflicts = [], 0, 0
    for row in rows:
        name, value, principal = row[0], row[1], row[2]
        stored_principal = active_by_value.get((name, value))
        stored_value = active_by_principal.get((name, principal))
        if stored_principal == principal and stored_value == value:
            unchanged += 1
        elif stored_principal is None and stored_value is None:
            to_insert.append(row)
        else:
            conflicts += 1
    return to_insert, unchanged, conflicts


# --- database ---------------------------------------------------------------------------------

def connect():
    dsn = os.environ.get(ENV_DSN)
    if _blank(dsn):
        raise AdminError(f"set {ENV_DSN} (the connection string is read only from the environment)")
    try:
        import psycopg
    except ImportError as exc:
        raise AdminError("psycopg is not installed; run with: uv run --with 'psycopg[binary]' ...") from exc
    try:
        return psycopg.connect(dsn, autocommit=False)
    except Exception as exc:  # never echo the DSN or a server address
        raise AdminError(f"could not connect to the database ({type(exc).__name__})") from None


def audit(cur, actor, action, identifier, detail):
    cur.execute(
        "INSERT INTO authz.access_audit (actor, action, institutional_identifier, detail) "
        "VALUES (%s, %s, %s, %s::jsonb)",
        (actor, action, identifier, json.dumps(detail, default=str, sort_keys=True)))


def cmd_crosswalk_import(cur, a):
    actor = require(a.actor, "--actor")
    load_ref = require(a.load_ref, "--load-ref")
    plan = a.plan
    for prncpl_id, entity_id, actv_ind in plan.principals:
        cur.execute(
            "INSERT INTO authz.kim_principal (prncpl_id, entity_id, actv_ind, loaded_at, load_ref) "
            "VALUES (%s, %s, %s, CURRENT_TIMESTAMP, %s) "
            "ON CONFLICT (prncpl_id) DO UPDATE SET entity_id = EXCLUDED.entity_id, actv_ind = EXCLUDED.actv_ind, "
            "loaded_at = EXCLUDED.loaded_at, load_ref = EXCLUDED.load_ref",
            (prncpl_id, entity_id, actv_ind, load_ref))
    cur.execute("SELECT attribute_name, attribute_value, prncpl_id FROM authz.principal_crosswalk "
                "WHERE status = 'ACTIVE' AND attribute_name = %s", (a.attribute,))
    stored = cur.fetchall()
    to_insert, unchanged, conflicts = classify_rows(
        plan.rows, {(n, v): p for n, v, p in stored}, {(n, p): v for n, v, p in stored})
    if conflicts:
        raise AdminError(f"refused: {conflicts} row(s) conflict with an existing ACTIVE mapping; "
                         "revoke those first (crosswalk-revoke). Nothing imported")
    for name, value, principal, evidence, verified_by in to_insert:
        cur.execute(
            "INSERT INTO authz.principal_crosswalk (attribute_name, attribute_value, prncpl_id, status, "
            "evidence_ref, verified_by, verified_at) VALUES (%s, %s, %s, 'ACTIVE', %s, %s, CURRENT_TIMESTAMP)",
            (name, value, principal, evidence, verified_by))
    counts = {"principals_upserted": len(plan.principals), "crosswalk_inserted": len(to_insert),
              "crosswalk_unchanged": unchanged, "crosswalk_skipped_inactive": plan.skipped_inactive,
              "load_ref": load_ref, "attribute_name": a.attribute}
    audit(cur, actor, "CROSSWALK_IMPORTED", None, counts)
    print(f"principals upserted: {counts['principals_upserted']}")
    print(f"crosswalk rows inserted: {len(to_insert)}, unchanged: {unchanged}, "
          f"skipped (inactive principal): {plan.skipped_inactive}")


def cmd_grant_add(cur, a):
    g = check_grant_args(a.type, a.grantee, a.granted_by, a.approved_by, a.reason, a.unit, a.io,
                         a.include_descendants, a.expires_at)
    cur.execute(
        "INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, include_descendants, "
        "io_value, granted_by, approved_by, reason, expires_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) "
        "RETURNING grant_id",
        (g["grantee"], g["grant_type"], g["unit_number"], g["include_descendants"], g["io_value"],
         g["granted_by"], g["approved_by"], g["reason"], g["expires_at"]))
    grant_id = cur.fetchone()[0]
    audit(cur, g["granted_by"], "GRANT_ADDED", g["grantee"],
          {"grant_id": grant_id, "grant_type": g["grant_type"], "unit_number": g["unit_number"],
           "include_descendants": g["include_descendants"], "io_value": g["io_value"],
           "approved_by": g["approved_by"], "reason": g["reason"], "expires_at": g["expires_at"]})
    print(f"grant added: id {grant_id} ({g['grant_type']})")


def cmd_grant_revoke(cur, a):
    by, reason = require(a.revoked_by, "--revoked-by"), require(a.reason, "--reason")
    cur.execute("UPDATE authz.access_grant SET revoked_by = %s, revoked_at = CURRENT_TIMESTAMP "
                "WHERE grant_id = %s AND revoked_at IS NULL RETURNING institutional_identifier, grant_type",
                (by, a.grant_id))
    row = cur.fetchone()
    if row is None:
        raise AdminError("no unrevoked grant with that id")
    audit(cur, by, "GRANT_REVOKED", row[0], {"grant_id": a.grant_id, "grant_type": row[1], "reason": reason})
    print(f"grant revoked: id {a.grant_id}")


def cmd_link_revoke(cur, a):
    by, reason = require(a.revoked_by, "--revoked-by"), require(a.reason, "--reason")
    if (a.link_id is None) == _blank(a.institutional_id):
        raise AdminError("give exactly one of --link-id or --institutional-id")
    if a.link_id is not None:
        where, arg = "identity_link_id = %s", a.link_id
    else:
        where, arg = "institutional_identifier = %s", a.institutional_id.strip()
    cur.execute(f"UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = %s, "
                f"revoked_at = CURRENT_TIMESTAMP WHERE {where} AND status = 'ACTIVE' "
                f"RETURNING identity_link_id, institutional_identifier", (by, arg))
    rows = cur.fetchall()
    if not rows:
        raise AdminError("no ACTIVE identity link matched")
    for link_id, identifier in rows:
        audit(cur, by, "LINK_REVOKED", identifier, {"identity_link_id": link_id, "reason": reason})
    print(f"identity links revoked: {len(rows)}")


def cmd_crosswalk_revoke(cur, a):
    by, reason = require(a.revoked_by, "--revoked-by"), require(a.reason, "--reason")
    if (a.crosswalk_id is None) == _blank(a.prncpl_id):
        raise AdminError("give exactly one of --crosswalk-id or --prncpl-id (with --attribute)")
    if a.crosswalk_id is not None:
        where, args = "crosswalk_id = %s", (a.crosswalk_id,)
    else:
        where, args = "prncpl_id = %s AND attribute_name = %s", (a.prncpl_id.strip(), require(a.attribute, "--attribute"))
    cur.execute(f"UPDATE authz.principal_crosswalk SET status = 'REVOKED', revoked_by = %s, "
                f"revoked_at = CURRENT_TIMESTAMP WHERE {where} AND status = 'ACTIVE' "
                f"RETURNING crosswalk_id, attribute_value, prncpl_id", (by, *args))
    rows = cur.fetchall()
    if not rows:
        raise AdminError("no ACTIVE crosswalk row matched")
    for crosswalk_id, value, principal in rows:
        audit(cur, by, "CROSSWALK_REVOKED", value,
              {"crosswalk_id": crosswalk_id, "kuali_person_id": principal, "reason": reason})
    print(f"crosswalk rows revoked: {len(rows)} (linked sign-ins are revoked by the API on their next request)")


def cmd_suspend(cur, a, suspended):
    identifier = require(a.institutional_id, "--institutional-id")
    by, reason = require(a.changed_by, "--changed-by"), require(a.reason, "--reason")
    if by == identifier:
        raise AdminError("refused: a person cannot change their own suspension")
    cur.execute("INSERT INTO authz.person_status (institutional_identifier, suspended, changed_by, changed_at, reason) "
                "VALUES (%s, %s, %s, CURRENT_TIMESTAMP, %s) ON CONFLICT (institutional_identifier) DO UPDATE SET "
                "suspended = EXCLUDED.suspended, changed_by = EXCLUDED.changed_by, changed_at = EXCLUDED.changed_at, "
                "reason = EXCLUDED.reason", (identifier, suspended, by, reason))
    audit(cur, by, "PERSON_SUSPENDED" if suspended else "PERSON_UNSUSPENDED", identifier, {"reason": reason})
    print("suspended" if suspended else "unsuspended")


def cmd_list(cur, a):
    if _blank(a.institutional_id):
        queries = [
            ("identity links", "SELECT status || ' ' || method, count(*) FROM authz.identity_link GROUP BY 1 ORDER BY 1"),
            ("active grants", "SELECT grant_type, count(*) FROM authz.access_grant WHERE revoked_at IS NULL "
                              "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) GROUP BY 1 ORDER BY 1"),
            ("crosswalk rows", "SELECT attribute_name || ' ' || status, count(*) FROM authz.principal_crosswalk "
                               "GROUP BY 1 ORDER BY 1"),
            ("KIM principals", "SELECT 'actv_ind=' || actv_ind, count(*) FROM authz.kim_principal GROUP BY 1 ORDER BY 1"),
            ("suspended", "SELECT 'suspended', count(*) FROM authz.person_status WHERE suspended GROUP BY 1"),
            ("audit rows", "SELECT action, count(*) FROM authz.access_audit GROUP BY 1 ORDER BY 1"),
        ]
        for title, sql in queries:
            cur.execute(sql)
            print(f"{title}:")
            for key, count in cur.fetchall():
                print(f"  {key}: {count}")
        return
    identifier = a.institutional_id.strip()
    sections = [
        ("identity links", "SELECT identity_link_id, method, status, kuali_person_id, verified_by, verified_at, "
                           "revoked_by, revoked_at FROM authz.identity_link WHERE institutional_identifier = %s "
                           "ORDER BY identity_link_id"),
        ("grants", "SELECT grant_id, grant_type, unit_number, include_descendants, io_value, granted_by, approved_by, "
                   "valid_from, expires_at, revoked_by, revoked_at FROM authz.access_grant "
                   "WHERE institutional_identifier = %s ORDER BY grant_id"),
        ("crosswalk", "SELECT crosswalk_id, attribute_name, prncpl_id, status, evidence_ref, verified_by, revoked_at "
                      "FROM authz.principal_crosswalk WHERE attribute_value = %s ORDER BY crosswalk_id"),
        ("status", "SELECT suspended, changed_by, changed_at, reason FROM authz.person_status "
                   "WHERE institutional_identifier = %s"),
        ("recent audit", "SELECT occurred_at, actor, action, detail FROM authz.access_audit "
                         "WHERE institutional_identifier = %s ORDER BY audit_id DESC LIMIT 20"),
    ]
    for title, sql in sections:
        cur.execute(sql, (identifier,))
        names = [d.name for d in cur.description]
        rows = cur.fetchall()
        print(f"{title}: {len(rows)}")
        for row in rows:
            print("  " + ", ".join(f"{n}={v}" for n, v in zip(names, row)))


def _read_inputs(a):
    principals = vc.read(a.principals, vc.PRINCIPAL_HEADER)
    crosswalk = vc.read(a.crosswalk, vc.CROSSWALK_HEADER)
    rolodex = frozenset(Path(a.rolodex_ids).read_text().split()) if a.rolodex_ids else frozenset()
    return principals, crosswalk, rolodex


def _validated_plan(a):
    principals, crosswalk, rolodex = _read_inputs(a)
    problems, plan = plan_import(principals, crosswalk, a.attribute, rolodex, a.allow_shared_entity)
    print(f"rows read: principals {len(principals)}, crosswalk {len(crosswalk)}")
    for check, rows in sorted(problems.items()):
        print(f"FAIL  {check}: {len(rows)} row(s) {rows[:20]}")
    if plan is not None and plan.skipped_inactive:
        print(f"SKIP  inactive principal (not imported): {plan.skipped_inactive} row(s)")
    return problems, plan


# --- CLI ---------------------------------------------------------------------------------------

def build_parser():
    ap = argparse.ArgumentParser(prog="authz_admin.py", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="command", required=True)

    def files(p):
        p.add_argument("principals", help="kim_principals.tsv")
        p.add_argument("crosswalk", help="principal_crosswalk.tsv")
        p.add_argument("--attribute", required=True, help="the one approved attribute_name")
        p.add_argument("--rolodex-ids", help="file of non-employee ids to refuse (whitespace separated)")
        p.add_argument("--allow-shared-entity", action="store_true")

    def write(p):
        p.add_argument("--dry-run", action="store_true", help="do everything, then roll back")

    files(sub.add_parser("crosswalk-validate", help="validate crosswalk files (no database)"))

    p = sub.add_parser("crosswalk-import", help="validate, then import principals + crosswalk in one transaction")
    files(p)
    p.add_argument("--load-ref", required=True, help="extract/batch reference (no personal data)")
    p.add_argument("--actor", required=True, help="who is importing (recorded in the audit)")
    write(p)

    p = sub.add_parser("grant-add", help="add a CENTRAL, UNIT or IO grant")
    p.add_argument("--type", required=True, choices=GRANT_TYPES)
    p.add_argument("--grantee", required=True, help="institutional identifier of the grantee")
    p.add_argument("--unit")
    p.add_argument("--include-descendants", action="store_true")
    p.add_argument("--io")
    p.add_argument("--granted-by", required=True)
    p.add_argument("--approved-by", required=True)
    p.add_argument("--reason", required=True)
    p.add_argument("--expires-at")
    write(p)

    p = sub.add_parser("grant-revoke", help="revoke one grant")
    p.add_argument("--grant-id", required=True, type=int)
    p.add_argument("--revoked-by", required=True)
    p.add_argument("--reason", required=True)
    write(p)

    p = sub.add_parser("link-revoke", help="revoke identity link(s)")
    p.add_argument("--link-id", type=int)
    p.add_argument("--institutional-id")
    p.add_argument("--revoked-by", required=True)
    p.add_argument("--reason", required=True)
    write(p)

    p = sub.add_parser("crosswalk-revoke", help="revoke crosswalk row(s)")
    p.add_argument("--crosswalk-id", type=int)
    p.add_argument("--prncpl-id")
    p.add_argument("--attribute")
    p.add_argument("--revoked-by", required=True)
    p.add_argument("--reason", required=True)
    write(p)

    for name in ("suspend", "unsuspend"):
        p = sub.add_parser(name, help=f"{name} an institutional identity (overrides every grant)")
        p.add_argument("--institutional-id", required=True)
        p.add_argument("--changed-by", required=True)
        p.add_argument("--reason", required=True)
        write(p)

    p = sub.add_parser("list", help="counts, or the rows for one institutional identifier")
    p.add_argument("--institutional-id")
    return ap


COMMANDS = {
    "crosswalk-import": cmd_crosswalk_import,
    "grant-add": cmd_grant_add,
    "grant-revoke": cmd_grant_revoke,
    "link-revoke": cmd_link_revoke,
    "crosswalk-revoke": cmd_crosswalk_revoke,
    "suspend": lambda cur, a: cmd_suspend(cur, a, True),
    "unsuspend": lambda cur, a: cmd_suspend(cur, a, False),
    "list": cmd_list,
}


def main(argv=None):
    a = build_parser().parse_args(argv)
    try:
        if a.command == "crosswalk-validate":
            problems, plan = _validated_plan(a)
            print("RESULT:", "REJECTED" if plan is None else f"IMPORTABLE ({len(plan.rows)} rows)")
            return 1 if plan is None else 0
        # Validate files and arguments before touching the database.
        if a.command == "crosswalk-import":
            _, a.plan = _validated_plan(a)
            if a.plan is None:
                print("RESULT: REJECTED (nothing imported)")
                return 1
        if a.command == "grant-add":
            check_grant_args(a.type, a.grantee, a.granted_by, a.approved_by, a.reason, a.unit, a.io,
                             a.include_descendants, a.expires_at)
        conn = connect()
        try:
            with conn.cursor() as cur:
                COMMANDS[a.command](cur, a)
            if getattr(a, "dry_run", False) or a.command == "list":
                conn.rollback()
                if getattr(a, "dry_run", False):
                    print("DRY RUN: rolled back, nothing changed")
            else:
                conn.commit()
        except BaseException:
            conn.rollback()
            raise
        finally:
            conn.close()
        return 0
    except AdminError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    except SystemExit:
        raise
    except Exception as exc:  # database errors: class and primary message only, never the DSN
        diag = getattr(exc, "diag", None)
        detail = getattr(diag, "message_primary", None) if diag is not None else None
        print(f"ERROR: {type(exc).__name__}{': ' + detail if detail else ''} (rolled back)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
