"""Validates a KIM principal crosswalk before import (scripts/authz-admin/README.md,
"Crosswalk file format"). Copied from the identity lab's validator; authz_admin.py
crosswalk-validate / crosswalk-import call validate() directly.

Prints counts and row numbers only - never attribute values or principal IDs - so the
report can be shared. Exit 0 = importable, 1 = rejected.

Usage: python validate_crosswalk.py kim_principals.tsv principal_crosswalk.tsv
         --attribute NAME [--rolodex-ids FILE] [--allow-shared-entity]
"""
import argparse
import csv
import re
import sys
from collections import Counter, defaultdict

PRINCIPAL_HEADER = ["prncpl_id", "entity_id", "actv_ind"]
CROSSWALK_HEADER = ["attribute_name", "attribute_value", "prncpl_id", "evidence_ref", "verified_by"]
EMAIL = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")
BAD_CHARS = re.compile(r"[\s\x00-\x1f\x7f]")


def read(path, header):
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f, delimiter="\t"))
    if not rows or rows[0] != header:
        raise SystemExit(f"REJECTED: {path}: header must be exactly {header}")
    return [dict(zip(header, r)) | {"_row": i} for i, r in enumerate(rows[1:], start=2)]


def validate(principals, crosswalk, attribute, rolodex_ids=frozenset(), allow_shared_entity=False):
    problems = defaultdict(list)          # check -> crosswalk row numbers
    by_id = {}
    for p in principals:
        if not all(p.get(k) for k in PRINCIPAL_HEADER) or p["actv_ind"] not in ("Y", "N"):
            problems["principal row malformed"].append(p["_row"])
        by_id[p["prncpl_id"]] = p
    names = {r["attribute_name"] for r in crosswalk}
    if names != {attribute}:
        problems["attribute_name is not exactly the approved attribute"].append(0)
    value_count = Counter(r["attribute_value"].strip() for r in crosswalk)
    principal_count = Counter(r["prncpl_id"] for r in crosswalk)
    entity_count = Counter(by_id[r["prncpl_id"]]["entity_id"] for r in crosswalk if r["prncpl_id"] in by_id)
    inactive = []
    for r in crosswalk:
        n, value = r["_row"], r["attribute_value"].strip()
        if not all(r.get(k) for k in CROSSWALK_HEADER):
            problems["required column empty"].append(n)
        if EMAIL.fullmatch(value) or "@" in value:
            problems["attribute value looks like an email address"].append(n)
        if BAD_CHARS.search(value):
            problems["attribute value has whitespace or control characters"].append(n)
        if value_count[value] > 1:
            problems["attribute value maps to more than one principal"].append(n)
        if principal_count[r["prncpl_id"]] > 1:
            problems["principal mapped from more than one attribute value"].append(n)
        if r["prncpl_id"] in rolodex_ids:
            problems["non-employee (rolodex) id is never a login account"].append(n)
        elif r["prncpl_id"] not in by_id:
            problems["principal not in the KIM principal extract"].append(n)
        elif by_id[r["prncpl_id"]]["actv_ind"] != "Y":
            inactive.append(n)
        elif not allow_shared_entity and entity_count[by_id[r["prncpl_id"]]["entity_id"]] > 1:
            problems["several principals share one KIM entity"].append(n)
    return dict(problems), inactive


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("principals")
    ap.add_argument("crosswalk")
    ap.add_argument("--attribute", required=True)
    ap.add_argument("--rolodex-ids")
    ap.add_argument("--allow-shared-entity", action="store_true")
    a = ap.parse_args()
    rolodex = frozenset(open(a.rolodex_ids).read().split()) if a.rolodex_ids else frozenset()
    principals = read(a.principals, PRINCIPAL_HEADER)
    crosswalk = read(a.crosswalk, CROSSWALK_HEADER)
    problems, inactive = validate(principals, crosswalk, a.attribute, rolodex, a.allow_shared_entity)
    print(f"rows read: principals {len(principals)}, crosswalk {len(crosswalk)}")
    for check, rows in sorted(problems.items()):
        print(f"FAIL  {check}: {len(rows)} row(s) {rows[:20]}")
    if inactive:
        print(f"SKIP  inactive principal (not imported): {len(inactive)} row(s) {inactive[:20]}")
    print("RESULT:", "REJECTED" if problems else f"IMPORTABLE ({len(crosswalk) - len(inactive)} rows)")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
