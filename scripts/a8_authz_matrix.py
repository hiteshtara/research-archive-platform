#!/usr/bin/env python3
"""A8 window 1 - full synthetic authorization matrix, baseline-differential.

DRY RUN BY DEFAULT: with no --execute nothing is sent. Every request is a GET,
except the AI evidence/summary/question POSTs, which are read-only and are only
sent where the expected answer is a denial (summary, questions) or a
retrieval (evidence search). Nothing in this tool writes data.

How a verdict is reached
  baseline --execute   enforcement OFF. Resolves the fixture records, then
                       requests EVERY probe path once and stores, per path,
                       the status, content type, body hash and which fixture
                       identifiers appeared. Nothing about a body is stored
                       except that.
  run --execute        enforcement ON. Repeats every probe as its role and
                       compares with the baseline:
    allow  status must equal the baseline status, and every in-scope
           identifier the baseline body carried must still be there.
    deny   status 403/404, and no out-of-scope identifier in the body.
    list   status equals baseline; in-scope identifiers the baseline showed
           must remain; out-of-scope identifiers it showed must be gone.
    none   403 ACCESS_NOT_PROVISIONED and no fixture identifier at all.
    refused 403 ACCESS_DENIED: a cross-module index refuses a scoped caller (never filtered rows).
    admin  403/404 for a non-administrator, no identifier.
    count  dashboard total for the role is below the unscoped baseline.
  A probe whose baseline cannot discriminate (the record had no data, the
  endpoint is disabled or group-gated) is NOT TESTED, never PASS.
  An out-of-scope identifier found in a body is FAIL + LEAK (an abort
  condition). If that identifier also appears in an in-scope baseline body for
  the same role, the hit is REVIEW instead: it may be a legitimate mention.

Tokens: environment variables RAP_PROBE_TOKEN_{DEPT,OAV,NONE,BASELINE}; never
printed, logged or written. DEV only (https, refuses production hostnames).
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import threading
import datetime as dt
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

Q = urllib.parse.quote
FIXED = {  # BU checklist records + the CGS family used for the owner's manual test
    "A": {"award_number": "212128-00002", "lead_unit": "2573180018"},
    "B": {"award_number": "211144-00007", "lead_unit": "2573180018"},
    "C": {"award_number": "209407-00002", "lead_unit": "1330420000"},
    "G": {"award_number": "212137-00001", "lead_unit": "1250000000"},
}
DEPT_UNIT, IO = "2573180018", "9500312705"
ROLES = ("DEPT", "OAV", "NONE")
# H3 (SAP) live fixtures, found by read-only discovery on DEV 2026-11-19 (sap-discovery.py, sap-detail.py).
# A transmission is stored on the PARENT award version; its children are other award numbers. No award
# version that carries a SAP transmission has an account number (0 of 14,761), so an IO grant can never
# open SAP data directly; the IO route is the PARENT of the IO award, which must stay closed.
SAP_DEPT_ROOTS = [  # transmitting award_id -> children split by S1's UNIT 2573180018 (no descendants)
    {"award_id": 1022767, "award_number": "110224-00001", "visible": ["110224-00002"], "hidden": ["110224-00003"]},
    {"award_id": 3816385, "award_number": "208096-00001", "visible": ["208096-00002", "208096-00004"],
     "hidden": ["208096-00003"]},
]
SAP_IO_PARENT = {"award_id": 3868241, "award_number": "209407-00001", "child": "209407-00002"}
AWARD_NUMBER_IN_TEXT = re.compile(r"(?<![\d-])\d{6}-\d{5}(?![\d-])")
# Fixture ids the plan cannot do without. A missing one refuses the baseline instead of silently
# dropping its probes (session 1 and 2 ran with null proposal ids and nobody noticed).
REQUIRED_FIXTURES = ("neg_in", "neg_out", "sub_out", "prop_out_id", "prop_out_number", "prop_in_id",
                     "prop_in_number")
MAX_BODY = 5_000_000

AWARD_ID_PATHS = [
    "summary", "versions", "people", "unit-details", "unit-contacts", "sponsor-contacts",
    "central-administration-contacts", "amounts", "time-and-money/summary", "time-and-money/actions",
    "time-and-money/history", "terms", "custom-data", "comments", "sap-transmissions", "attachments",
    "budget/summary", "budget/versions", "budget/periods", "budget/line-items", "budget/personnel",
    "funding-proposals", "funding-subawards", "negotiations", "report.pdf", "report-with-attachments.pdf",
]
AWARD_NUM_PATHS = ["/api/v1/awards/by-number/{n}", "/api/v1/awards/{n}/hierarchy", "/api/awards/{n}",
                   "/api/awards/{n}/history", "/api/awards/{n}/people", "/api/awards/{n}/amounts",
                   "/api/awards/{n}/proposals", "/api/awards/{n}/funding", "/api/awards/history/{n}"]
NEG_PATHS = ["", "/activities", "/custom-data", "/notifications", "/unassociated-details", "/attachments",
             "/associated-record"]
SUB_PATHS = ["", "/versions", "/amounts", "/contacts", "/custom-data", "/funding", "/attachments",
             "/template-info", "/closeout", "/reports", "/notepad", "/notifications"]
PROP_V1_PATHS = ["", "/versions", "/people", "/units", "/attachments", "/comments", "/funded-awards", "/custom-data"]
PROP_NUM_PATHS = ["", "/history", "/awards"]
LIST_PATHS = [  # {n} is an award number, {t} a short title fragment
    "/api/v1/awards/search?q={n}", "/api/v1/awards/versions/search?q={n}", "/api/global-search?query={n}",
    "/api/global-search?query={t}", "/api/v1/documents?query={n}", "/api/v1/documents?businessRecordNumber={n}",
    "/api/documents/search?businessRecordNumber={n}", "/api/awards/families?query={n}",
    "/api/v1/attachments/search?awardNumber={n}",
]
# Cross-module indexes that UNION Award/Proposal/Negotiation/Subaward/IRB rows. With enforcement on
# they REFUSE every scoped caller (AwardRecordAccess.requireUnrestrictedForCrossModuleSearch ->
# 403 ACCESS_DENIED) rather than filter, because four of the five modules in the UNION have no
# Award-style rule there. Expected: refusal, never rows. Judged as kind "refused".
# Endpoints that return attachment FILE CONTENT. Never requested at baseline (enforcement OFF), so no
# attachment content is opened while records are unrestricted (owner condition, 2026-11-18). Judged
# under enforcement on their own: allow = 200 application/pdf; deny = 403/404 JSON, no identifier.
NO_BASELINE_CONTENT = ("report-with-attachments.pdf",)
REFUSED_FOR_SCOPED = ("/api/documents/search", "/api/v1/documents?", "/api/v1/attachments/search")
# Recorded, never judged: Explorer is off on DEV; IRB is excluded from release scope and its
# API stays on (app.irb.api.enabled defaults true) because IRB exclusion is not activated in A8.
DISABLED_PATHS = ["/api/v1/explorer/awards", "/api/irb"]
EXCLUDED = [("/api/investigators?email=<a real person>", "looks up a named BU person by email; excluded: the synthetic test never queries a real identity")]
WORKERS = 8
ADMIN_PATHS = ["/api/v1/admin/identities?q=ARP", "/api/v1/admin/administrators", "/api/v1/admin/audit"]


# ------------------------------------------------------------------ http
# Optional isolation-by-host mode (2026-11-18): with A8_HOST_HEADER set, requests go to the DEV ALB's own
# DNS name carrying that Host header, which matches only the private-task rule (and only from the
# operator's IP). TLS hostname checking is relaxed ONLY in that mode and ONLY for the DEV ALB name,
# because the ALB certificate names api-dev.*, not the ALB.
_A8_HOST = os.environ.get("A8_HOST_HEADER", "")
_ALB = "rap-dev-api-alb-727962412.us-east-1.elb.amazonaws.com"
def _ctx(url):
    if _A8_HOST and _ALB in url:
        import ssl
        c = ssl.create_default_context(); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
        return c
    if _A8_HOST:
        raise SystemExit("refusing: A8_HOST_HEADER is set but the base URL is not the DEV ALB")
    return None


def http(base, token, method, path, body=None, timeout=90):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if _A8_HOST:
        req.add_header("Host", _A8_HOST)
    req.add_header("Accept", "application/json, application/pdf")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_ctx(req.full_url)) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read(MAX_BODY)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read(MAX_BODY)


def token(role):
    t = os.environ.get(f"RAP_PROBE_TOKEN_{role}", "")
    if not t:
        sys.exit(f"RAP_PROBE_TOKEN_{role} is not set (tokens are never accepted on the command line)")
    return t


def guard_base(base):
    u = urllib.parse.urlparse(base)
    host = (u.hostname or "").lower()
    if u.scheme != "https":
        sys.exit("refusing: base URL must be https")
    if "prod" in host and "nonprod" not in host:
        sys.exit(f"refusing: {host} looks like production; this tool is DEV-only")


def find_all(obj, key, out=None):
    out = [] if out is None else out
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == key and v not in (None, ""):
                out.append(v)
            find_all(v, key, out)
    elif isinstance(obj, list):
        for v in obj:
            find_all(v, key, out)
    return out


def jget(base, t, path):
    s, ct, b = http(base, t, "GET", path)
    try:
        return s, json.loads(b) if "json" in ct else None
    except ValueError:
        return s, None


def first(*vals):
    for v in vals:
        if v not in (None, "", []):
            return v[0] if isinstance(v, list) else v
    return None


def current_proposal_id(search_body, number):
    """currentProposalId of the family row whose proposalNumber equals number (Proposal search)."""
    for row in (search_body or {}).get("content", []) if isinstance(search_body, dict) else []:
        if str(row.get("proposalNumber")) == str(number) and row.get("currentProposalId") is not None:
            return row["currentProposalId"]
    return None


def records(body, path, module):
    """Records of one module in a response body: Global Search items carry 'module'; every other
    list endpoint is a JSON array or a page with 'content'."""
    if path.startswith("/api/global-search"):
        return [r for r in (body or {}).get("results", []) or [] if isinstance(r, dict) and r.get("module") == module]
    if isinstance(body, list):
        return body
    if isinstance(body, dict) and isinstance(body.get("content"), list):
        return body["content"]
    return []


def record_keys(rec):
    """Identifiers a record exposes: every *subawardId / *negotiationId / recordId value and any code."""
    ids, codes = set(), set()
    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                kl = k.lower()
                if v is not None and not isinstance(v, (dict, list)) and (
                        kl.endswith("subawardid") or kl.endswith("negotiationid") or kl == "recordid"):
                    ids.add(str(v))
                if v is not None and kl in ("subawardcode", "identifier"):
                    codes.add(str(v))
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(rec)
    return ids, codes


def award_numbers(obj, at="$"):
    """(json path, value) for every string value shaped like an award number, anywhere in the body."""
    out = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            out += award_numbers(v, f"{at}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            out += award_numbers(v, f"{at}[{i}]")
    elif isinstance(obj, str):
        # Whole values and award numbers embedded in text (messages, titles, payload fragments).
        out += [(at, m) for m in AWARD_NUMBER_IN_TEXT.findall(obj)]
    return out


# ------------------------------------------------------------------ fixtures
def discover(base, t, scope_sets=None):
    # /api/v1/me/capabilities reports enforcementEnabled=false for EVERY non-administrator
    # (CapabilitiesService returns denied()), so it cannot tell the modes apart. The unscoped
    # dashboard can: with enforcement OFF the baseline account sees the whole archive.
    s, dash = jget(base, t, "/api/dashboard")
    if s != 200 or not dash or int(dash.get("awards", -1)) < 10000:
        sys.exit(f"refusing: baseline needs enforcement OFF (dashboard {s}, awards {dash and dash.get('awards')})")
    fx = {"captured_at": dt.datetime.now(dt.timezone.utc).isoformat(), "base": base, "awards": {}}
    for k, r in FIXED.items():
        s, a = jget(base, t, f"/api/v1/awards/by-number/{r['award_number']}")
        if s != 200:
            sys.exit(f"refusing: award {k} {r['award_number']} not resolvable (status {s})")
        aid = find_all(a, "awardId")[0]
        s, summ = jget(base, t, f"/api/v1/awards/{aid}/summary")
        units = {str(u) for u in find_all(summ, "leadUnitNumber")}
        if units and r["lead_unit"] not in units:
            sys.exit(f"refusing: award {k} lead unit {units} != {r['lead_unit']}")
        title = (find_all(summ, "title") or [None])[0]
        fx["awards"][k] = dict(r, award_id=aid, title=title)
        s, h = jget(base, t, f"/api/v1/awards/{aid}/time-and-money/history")
        fx["awards"][k]["tm_txn"] = (find_all(h, "pendingTransactionId") or [None])[0]
        fx["awards"][k]["tm_doc"] = (find_all(h, "timeAndMoneyDocumentNumber") or [None])[0]
    a, c, g = (fx["awards"][k] for k in "ACG")
    # Negotiation linked to A: in scope for DEPT (lead unit / Award association), never for OAV.
    s, n = jget(base, t, f"/api/v1/awards/{a['award_id']}/negotiations")
    fx["neg_in"] = (find_all(n, "negotiationId") or [None])[0]
    # A negotiation outside unit 2573180018 and not tied to A/B: out of scope for DEPT and OAV.
    s, nl = jget(base, t, "/api/negotiations?leadUnit=1330420000&size=5")
    fx["neg_out"] = (find_all(nl, "negotiationId") or [None])[0]
    # C's subawards: an IO grant never reaches Subawards; C is out of DEPT's unit.
    s, sl = jget(base, t, f"/api/v1/awards/{c['award_id']}/funding-subawards")
    fx["sub_out"] = (find_all(sl, "navigableCurrentSubawardId") or find_all(sl, "subawardId") or [None])[0]
    fx["sub_out_codes"] = sorted({str(x) for x in find_all(sl, "subawardCode")})[:8]  # query terms only
    fx["sub_out_titles"] = []
    if fx["sub_out"]:
        s, sd = jget(base, t, f"/api/subawards/{fx['sub_out']}")
        fx["sub_out_titles"] = sorted({str(x) for x in find_all(sd, "title") if len(str(x)) >= 12})
    # Every version id of every subaward Award C funds (read-only DB scope sets, cross-checked here
    # against the API's own ids): the IO account must see none of them.
    c_subs = (scope_sets or {}).get("C_subawards") or []
    fx["sub_out_ids"] = sorted({str(r["subaward_id"]) for r in c_subs}
                               | {str(x) for k in ("subawardId", "navigableCurrentSubawardId") for x in find_all(sl, k)})
    by_code = {}
    for r in c_subs:
        by_code[str(r["subaward_code"])] = max(by_code.get(str(r["subaward_code"]), 0), int(r["subaward_id"]))
    fx["sub_out_by_code"] = {c: i for c, i in sorted(by_code.items()) if str(i) != str(fx["sub_out"])}
    if fx["sub_out"] and str(fx["sub_out"]) not in fx["sub_out_ids"]:
        sys.exit(f"refusing: API subaward {fx['sub_out']} is not among the DB scope set's Award C subawards")
    # Proposal funding the CGS award: out of scope for both synthetic roles. Funding links carry
    # exactLinkedProposalId / navigableActiveProposalId, not 'proposalId' (the session 1-2 defect).
    s, pl = jget(base, t, f"/api/v1/awards/{g['award_id']}/funding-proposals")
    fx["prop_out_number"] = first(find_all(pl, "proposalNumber"))
    # A proposal led by unit 2573180018: in scope for DEPT. Proposal search rows carry currentProposalId.
    s, pin = jget(base, t, f"/api/proposals/search?leadUnit={DEPT_UNIT}&size=5")
    fx["prop_in_number"] = first(find_all(pin, "proposalNumber"))
    for side in ("out", "in"):
        num = fx[f"prop_{side}_number"]
        s, ps = jget(base, t, f"/api/proposals/search?query={Q(str(num))}") if num else (0, None)
        fx[f"prop_{side}_id"] = first(current_proposal_id(ps, num),
                                     *(find_all(pl, k) for k in ("navigableActiveProposalId", "exactLinkedProposalId")
                                       if side == "out"))
        if fx[f"prop_{side}_id"]:
            s, pv = jget(base, t, f"/api/v1/proposals/{fx[f'prop_{side}_id']}")
            if s != 200 or str(first(find_all(pv, "proposalNumber"))) != str(num):
                sys.exit(f"refusing: v1 proposal {fx[f'prop_{side}_id']} does not resolve to {num} (status {s})")
    # H3 fixtures: confirm each transmitting award id is the expected award number.
    fx["sap_dept_roots"], fx["sap_io_parent"] = [], None
    for r in SAP_DEPT_ROOTS + [SAP_IO_PARENT]:
        s, sm = jget(base, t, f"/api/v1/awards/{r['award_id']}/summary")
        if s != 200 or r["award_number"] not in {str(x) for x in find_all(sm, "awardNumber")}:
            sys.exit(f"refusing: SAP fixture award {r['award_id']} is not {r['award_number']} (status {s})")
    fx["sap_dept_roots"], fx["sap_io_parent"] = [dict(r) for r in SAP_DEPT_ROOTS], dict(SAP_IO_PARENT)
    fx["scope_sets"] = {"DEPT": sorted((scope_sets or {}).get("DEPT_awards") or []),
                        "OAV": sorted((scope_sets or {}).get("OAV_awards") or []),
                        "DEPT_versions": (scope_sets or {}).get("DEPT_versions"),
                        "OAV_versions": (scope_sets or {}).get("OAV_versions"),
                        "computed_at": (scope_sets or {}).get("at")}
    missing = [k for k in REQUIRED_FIXTURES if not fx.get(k)]
    if not fx["scope_sets"]["DEPT"] or not fx["scope_sets"]["OAV"] or not fx["sub_out_ids"]:
        missing.append("scope_sets (--scope-sets file from the read-only DB query)")
    if missing:
        sys.exit(f"refusing: required fixtures unresolved: {missing}")
    return fx


def scope_tokens(fx):
    """Identifier strings per fixture record, used to detect presence/leaks."""
    aw = fx["awards"]
    def toks(k):
        r = aw[k]
        return [r["award_number"]] + ([r["title"]] if r.get("title") and len(r["title"]) >= 12 else [])
    t = {k: toks(k) for k in aw}
    # Subaward codes are short numbers that collide with ordinary text, so only titles mark presence.
    t["SUB_C"] = [x for x in (fx.get("sub_out_titles") or []) if x not in t["C"]]
    t["PROP_OUT"] = [str(fx["prop_out_number"])] if fx.get("prop_out_number") else []
    t["PROP_IN"] = [str(fx["prop_in_number"])] if fx.get("prop_in_number") else []
    roots = fx.get("sap_dept_roots") or []
    t["SAP_ROOTS"] = [r["award_number"] for r in roots] + [x for r in roots for x in r["visible"]]
    t["SAP_HIDDEN"] = [x for r in roots for x in r["hidden"]]
    t["C_PARENT"] = [fx["sap_io_parent"]["award_number"]] if fx.get("sap_io_parent") else []
    t["_C_SUB_IDS"] = list(fx.get("sub_out_ids") or [])  # structural use only, never a text token
    return t


IN_SCOPE = {"DEPT": ["A", "B", "PROP_IN", "SAP_ROOTS"], "OAV": ["C"], "NONE": []}
OUT_SCOPE = {"DEPT": ["C", "G", "SUB_C", "PROP_OUT", "SAP_HIDDEN", "C_PARENT"],
             "OAV": ["A", "B", "G", "SUB_C", "PROP_OUT", "PROP_IN", "SAP_ROOTS", "SAP_HIDDEN", "C_PARENT"],
             "NONE": ["A", "B", "C", "G", "SUB_C", "PROP_OUT", "PROP_IN", "SAP_ROOTS", "SAP_HIDDEN", "C_PARENT"]}
# Kinds whose 200 bodies are scanned for award numbers outside the role's full allowed set
# (read-only DB scope sets), not only the fixture tokens.
SCANNED_KINDS = ("allow", "list", "sap_scoped", "no_records")


# ------------------------------------------------------------------ probe plan
def plan(fx):
    """Every probe: dict(role, kind, method, path, body, label)."""
    aw = fx["awards"]
    p = []

    def add(role, kind, path, label, method="GET", body=None, **extra):
        p.append(dict(role=role, kind=kind, method=method, path=path, body=body, label=label, **extra))

    def award_paths(k):
        r = aw[k]
        out = [(f"/api/v1/awards/{r['award_id']}/{x}", f"award {k} {x}") for x in AWARD_ID_PATHS]
        out += [(t.format(n=r["award_number"]), f"award {k} {t.split('{n}')[-1] or 'detail'}") for t in AWARD_NUM_PATHS]
        return out

    for role in ROLES:
        # Window guard. S4 proves enforcement is ON (403 ACCESS_NOT_PROVISIONED on the dashboard);
        # S1/S3 prove administrator writes are unavailable. capabilities.enforcementEnabled is
        # always false for a non-administrator, so it is not used as the guard.
        if role == "NONE":
            add(role, "caps", "/api/dashboard", "window guard: enforcement on")
        else:
            add(role, "caps", "/api/v1/me/capabilities", "window guard: writes unavailable")
        for path, why in EXCLUDED:
            add(role, "excluded", path, why)
        for path in ADMIN_PATHS:
            # The admin API refuses every non-administrator with its own 403 before any provisioning
            # check, so S4 is judged as a non-administrator here, not as ACCESS_NOT_PROVISIONED.
            add(role, "admin", path, "admin API, non-administrator")
        for path in DISABLED_PATHS:
            add(role, "disabled", path, "out of A8 scope: Explorer off / IRB excluded (recorded, not judged)")

    allow = {"DEPT": ["A", "B"], "OAV": ["C"]}
    deny = {"DEPT": ["C", "G"], "OAV": ["A", "B", "G"]}
    for role in ("DEPT", "OAV"):
        for k in allow[role]:
            for path, label in award_paths(k):
                # F1 (#171): the IO account still opens Award C, but an IO grant never reaches
                # Subawards or Negotiations, so C's related-record tabs must list NO record.
                # Judged structurally (record count), not by text: subaward C's title equals C's.
                if role == "OAV" and k == "C" and path.endswith("/funding-subawards"):
                    add(role, "no_records", path, label + " (F1: no subaward)", module="SUBAWARD")
                elif role == "OAV" and k == "C" and path.endswith("/negotiations"):
                    add(role, "no_records", path, label + " (F1: no negotiation)", module="NEGOTIATION")
                else:
                    add(role, "allow", path, label)
        for k in deny[role]:
            for path, label in award_paths(k):
                add(role, "deny", path, label)
        add(role, "count", "/api/dashboard", "dashboard counts are scoped")
    for k in "ABCG":
        for path, label in award_paths(k):
            add("NONE", "none", path, label)

    # Cross-record Time & Money (H1): another award's transaction through an in-scope award id.
    for role, via, other in (("DEPT", "A", "C"), ("OAV", "C", "B")):
        if aw[other].get("tm_txn"):
            add(role, "structural", f"/api/v1/awards/{aw[via]['award_id']}/time-and-money/transactions/{aw[other]['tm_txn']}",
                f"H1 {other}'s T&M transaction via {via}")
            add(role, "deny", f"/api/v1/awards/{aw[other]['award_id']}/time-and-money/transactions/{aw[other]['tm_txn']}",
                f"H1 {other}'s T&M transaction direct")
        if aw[other].get("tm_doc"):
            add(role, "structural", f"/api/v1/awards/{aw[via]['award_id']}/time-and-money/documents/{aw[other]['tm_doc']}",
                f"H1 {other}'s T&M document via {via}")
            add(role, "deny", f"/api/v1/awards/{aw[other]['award_id']}/time-and-money/documents/{aw[other]['tm_doc']}",
                f"H1 {other}'s T&M document direct")
        if aw[via].get("tm_txn"):
            add(role, "allow", f"/api/v1/awards/{aw[via]['award_id']}/time-and-money/transactions/{aw[via]['tm_txn']}",
                f"H1 own T&M transaction {via}")

    # SAP (H3), live. DEPT opens two transmitting awards in its unit: every transmission must still be
    # listed (authorized SAP stays accessible), the in-unit child must remain, the other-unit child
    # must be gone and the raw SOAP payloads withheld. The hierarchy page must omit the hidden child
    # too ("strict": its baseline necessarily shows the child, so that is no reason to downgrade a hit).
    for r in fx.get("sap_dept_roots") or []:
        aid = r["award_id"]
        add("DEPT", "sap_scoped", f"/api/v1/awards/{aid}/sap-transmissions?size=100", f"H3 SAP {r['award_number']}",
            sap_visible=r["visible"], sap_hidden=r["hidden"])
        add("DEPT", "allow", f"/api/v1/awards/{aid}/summary", f"H3 SAP root {r['award_number']} summary")
        add("DEPT", "allow", f"/api/v1/awards/{r['award_number']}/hierarchy",
            f"H3 SAP root {r['award_number']} hierarchy", strict=True)
        for role in ("OAV",):
            add(role, "deny", f"/api/v1/awards/{aid}/sap-transmissions?size=100", f"H3 SAP {r['award_number']}")
        add("NONE", "none", f"/api/v1/awards/{aid}/sap-transmissions?size=100", f"H3 SAP {r['award_number']}")
        for h in r["hidden"]:
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/v1/awards/by-number/{h}", f"H3 hidden SAP child {h}")
    # IO route: C's parent holds the 9 transmissions that list C. IO is DIRECT only (F1), so the
    # parent and its SAP data stay closed to the IO account; DEPT has no claim on it either.
    if fx.get("sap_io_parent"):
        par = fx["sap_io_parent"]
        for x in ("sap-transmissions?size=100", "summary", "versions", "time-and-money/history", "attachments"):
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/v1/awards/{par['award_id']}/{x}", f"H3 IO parent {par['award_number']} {x}")
            add("NONE", "none", f"/api/v1/awards/{par['award_id']}/{x}", f"H3 IO parent {par['award_number']} {x}")
        for role in ("DEPT", "OAV"):
            add(role, "deny", f"/api/v1/awards/by-number/{par['award_number']}", f"H3 IO parent {par['award_number']} by number")

    # Negotiations (H2): A's negotiation is DEPT's; IO never reaches Negotiations.
    if fx.get("neg_in"):
        for x in NEG_PATHS:
            add("DEPT", "allow", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
            add("OAV", "deny", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
            add("NONE", "none", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
    if fx.get("neg_out"):
        for x in NEG_PATHS:
            for role in ("DEPT", "OAV"):  # OAV added after session-2 QA (12511 was DEPT-only)
                add(role, "deny", f"/api/negotiations/{fx['neg_out']}{x}", f"negotiation(out){x or ' detail'}")
            add("NONE", "none", f"/api/negotiations/{fx['neg_out']}{x}", f"negotiation(out){x or ' detail'}")

    # Subawards: C's subaward is outside DEPT's unit, and IO never reaches Subawards.
    if fx.get("sub_out"):
        for x in SUB_PATHS:
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/subawards/{fx['sub_out']}{x}", f"subaward(C){x or ' detail'}")
            add("NONE", "none", f"/api/subawards/{fx['sub_out']}{x}", f"subaward(C){x or ' detail'}")
    # C funds five subawards, not one: the newest version of each other code, three endpoints each.
    for code, sid in (fx.get("sub_out_by_code") or {}).items():
        for x in ("", "/versions", "/amounts"):
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/subawards/{sid}{x}", f"subaward(C code {code}){x or ' detail'}")
            add("NONE", "none", f"/api/subawards/{sid}{x}", f"subaward(C code {code}){x or ' detail'}")

    # Proposals: the CGS proposal is out for both; the 2573180018 proposal is DEPT's only.
    if fx.get("prop_out_id"):
        for x in PROP_V1_PATHS:
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/v1/proposals/{fx['prop_out_id']}{x}", f"proposal(out) v1{x or ' detail'}")
    if fx.get("prop_out_number"):
        for x in PROP_NUM_PATHS:
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/proposals/{Q(str(fx['prop_out_number']))}{x}", f"proposal(out){x or ' detail'}")
    if fx.get("prop_in_id"):
        for x in PROP_V1_PATHS:
            add("DEPT", "allow", f"/api/v1/proposals/{fx['prop_in_id']}{x}", f"proposal(in) v1{x or ' detail'}")
            add("OAV", "deny", f"/api/v1/proposals/{fx['prop_in_id']}{x}", f"proposal(in) v1{x or ' detail'}")
            add("NONE", "none", f"/api/v1/proposals/{fx['prop_in_id']}{x}", f"proposal(in) v1{x or ' detail'}")

    # Search, documents, related lists, global search: by every fixture's number and title.
    for k in "ABCG":
        r = aw[k]
        frag = " ".join((r.get("title") or r["award_number"]).split()[:4])
        for t in LIST_PATHS:
            path = t.format(n=Q(r["award_number"]), t=Q(frag))
            for role in ROLES:
                kind = "none" if role == "NONE" else (
                    "refused" if path.startswith(REFUSED_FOR_SCOPED) else "list")
                add(role, kind, path, f"list/search for {k}")
    for role in ROLES:
        kind = "none" if role == "NONE" else "list"
        # The IO account must get NO negotiation from any negotiation list or search (judged by record
        # count, so a list without fixture identifiers is still decisive - the session-2 gap).
        nkind = "no_records" if role == "OAV" else kind
        neg_lists = [("/api/negotiations?leadUnit=1330420000&size=25", "negotiation list, unit of C"),
                     (f"/api/negotiations?leadUnit={DEPT_UNIT}&size=25", "negotiation list, unit of A/B")]
        for k in "ABCG":  # added after session-2 QA: ?query= and ?associationId= were never probed
            n = Q(aw[k]["award_number"])
            neg_lists += [(f"/api/negotiations?query={n}&size=25", f"negotiation search by {k}'s number"),
                          (f"/api/negotiations?associationId={n}&size=25", f"negotiation filter by {k} association")]
        for path_, label_ in neg_lists:
            add(role, nkind, path_, label_, **({"module": "NEGOTIATION"} if nkind == "no_records" else {}))
        for code in (fx.get("sub_out_codes") or [])[:5]:
            # F1 (#171): the IO account must get no subaward at all; Global Search is judged on its
            # SUBAWARD items (module + recordId), not on 'subawardId' keys it never carries.
            k2 = "no_records" if role == "OAV" else kind
            for path_, label_ in ((f"/api/subawards?query={Q(code)}", f"subaward list by C's subaward code {code}"),
                                  (f"/api/global-search?query={Q(code)}", f"global search by C's subaward code {code}")):
                add(role, k2, path_, label_, **({"module": "SUBAWARD"} if k2 == "no_records" else {}))
        for num in [fx.get("prop_out_number"), fx.get("prop_in_number")]:
            if num:
                add(role, kind, f"/api/proposals/search?query={Q(str(num))}", "proposal search")
                add(role, kind, f"/api/global-search?query={Q(str(num))}", "global search by proposal number")

    # AI (H5): evidence search both ways. It is gated by app.search.semantic.enabled,
    # which is true on DEV, so it is testable. AI summary and questions (app.ai.enabled,
    # unset on DEV) are deliberately NOT probed: were they ever on, the enforcement-OFF
    # baseline would make a real model call on archive data.
    for role, ok, no in (("DEPT", "A", "C"), ("OAV", "C", "A")):
        add(role, "allow", f"/api/ai/awards/{aw[ok]['award_number']}/evidence-search", f"AI evidence {ok}",
            method="POST", body={"query": "budget"})
        add(role, "deny", f"/api/ai/awards/{aw[no]['award_number']}/evidence-search", f"AI evidence {no}",
            method="POST", body={"query": "budget"})
    add("NONE", "none", f"/api/ai/awards/{aw['A']['award_number']}/evidence-search", "AI evidence A",
        method="POST", body={"query": "budget"})
    return p


# ------------------------------------------------------------------ judging
def present(body_text, toks):
    low = body_text.lower()
    return [t for t in toks if t and t.lower() in low]


IO_FORBIDDEN_MODULES = ("SUBAWARD", "NEGOTIATION", "PROPOSAL")  # decision D-A: IO reaches Awards only
RECORD_LEVEL = re.compile(r"^\$(\.(results|content))?\[\d+\]\.(awardNumber|identifier)$")
IRB_DASHBOARD_FIELDS = ("irb", "submissions", "fundingRecords", "timelineEvents")


def judge(probe, base_row, status, ctype, text, tok, ambiguous, sets=None):
    """Return (verdict, reasons). verdict in PASS, FAIL, LEAK, REVIEW, NOT TESTED.

    sets: the role's full allowed award-number sets and version counts (read-only DB scope sets).
    With sets, 200 bodies of SCANNED_KINDS are also checked for ANY award number outside the role's
    scope - not only the fixture tokens - and the dashboard is judged on exact expected counts."""
    verdict, why = _judge(probe, base_row, status, ctype, text, tok, ambiguous, sets)
    role = probe["role"]
    if not sets or role not in sets or status != 200 or "json" not in (ctype or "").lower():
        return verdict, why
    if verdict in ("FAIL", "LEAK") or probe["kind"] not in SCANNED_KINDS:
        return verdict, why
    try:
        body = json.loads(text)
    except ValueError:
        return verdict, why
    if isinstance(body, dict):  # the caller's own echoed request is not disclosure
        body = {k: v for k, v in body.items() if k not in ("path", "query")}
    if role == "OAV" and probe["path"].startswith("/api/global-search"):
        found = {m: len(records(body, probe["path"], m)) for m in IO_FORBIDDEN_MODULES}
        if any(found.values()):
            return "LEAK", why + [f"IO account given non-Award records {found} (decision D-A)"]
    allowed = set(sets[role])
    stray = sorted({(p, v) for p, v in award_numbers(body) if v not in allowed})
    if not stray:
        return verdict, why
    listed = [x for x in stray if RECORD_LEVEL.match(x[0])]
    if listed:
        return "LEAK", why + [f"record outside the role's {len(allowed)} allowed awards listed: {listed[:8]}"]
    return "REVIEW", why + [f"award numbers outside the role's allowed set (adjudicate each): {stray[:8]}"]


def _judge(probe, base_row, status, ctype, text, tok, ambiguous, sets=None):
    role, kind = probe["role"], probe["kind"]
    is_json = "json" in (ctype or "").lower()
    out_t = [t for k in OUT_SCOPE[role] for t in tok.get(k, [])]
    in_t = [t for k in IN_SCOPE[role] for t in tok.get(k, [])]
    # Error bodies echo the request URI in "path". An identifier the CALLER typed into the URL is
    # not disclosure, so the echoed path is removed before looking for out-of-scope identifiers.
    # (Found live on 2026-11-18: two false LEAKs on /by-number/{C} and /{C}/hierarchy.)
    try:
        _j = json.loads(text) if is_json and text[:1] == "{" else None
    except Exception:
        _j = None
    # Drop ONLY the top-level echo fields ("path" from error bodies, "query" from Global Search; both
    # found live as false LEAKs) and scan everything else - the same value inside results still counts.
    text_scan = json.dumps({k: v for k, v in _j.items() if k not in ("path", "query")}) if isinstance(_j, dict) else text
    hits = present(text_scan, out_t)
    amb = set() if probe.get("strict") else ambiguous.get(role, set())
    hard = [h for h in hits if h not in amb]
    soft = [h for h in hits if h in amb]
    if kind == "excluded":
        return "NOT TESTED", [probe["label"]]
    if kind == "structural":
        bs0 = (base_row or {}).get("status")
        if status in (403, 404) and bs0 in (403, 404):
            return "PASS", ["refused with enforcement off and on: blocked structurally (H1)"]
        return "FAIL", [f"status {status} (baseline {bs0}); a cross-record id must never resolve"]
    if kind == "disabled":
        return "NOT TESTED", [f"feature disabled/out of scope (status {status})"]
    if kind == "caps":
        compact = text.replace(" ", "")
        if probe["path"].endswith("/api/dashboard"):
            ok = status == 403 and '"ACCESS_NOT_PROVISIONED"' in text
            return ("PASS" if ok else "FAIL"), ([] if ok else [f"enforcement not on: No-Grant dashboard {status}"])
        ok = status == 200 and '"administrationWritesAvailable":false' in compact
        return ("PASS" if ok else "FAIL"), ([] if ok else ["administrator writes available"])
    if not is_json and kind != "allow":
        if status in (403, 404):
            return "FAIL", [f"denial not a JSON API response (content-type {ctype!r})"]
    if hard:
        return "LEAK", [f"out-of-scope identifier in body: {hard}"]
    bs = base_row.get("status") if base_row else None
    if status == 403 and '"ATTACHMENT_ACCESS_DENIED"' in text:
        # The Cognito-group gate runs before record scope, so this proves nothing about scope.
        return "NOT TESTED", ["attachment group gate (account not in ArchiveAttachmentViewer)"]
    if base_row and base_row.get("skipped_baseline") and kind in ("allow", "deny"):
        if kind == "allow":
            ok = status == 200 and "pdf" in (ctype or "").lower()
            return ("PASS" if ok else "FAIL"), ([] if ok else [f"status {status} {ctype!r}, expected 200 PDF"])
        ok = status in (403, 404)
        return ("PASS" if ok else "FAIL"), ([] if ok else [f"status {status}, expected 403/404"])
    try:
        body = json.loads(text) if is_json and text[:1] in "{[" else None
    except Exception:
        body = None
    if kind == "no_records":
        # Structural: the role must receive NO record of this module (IO -> Awards only, D-A).
        module = probe["module"]
        if status in (403, 404):
            return "PASS", [f"refused (no {module.lower()} record)"]
        if status != 200 or body is None:
            return "FAIL", [f"status {status}, expected 200 JSON with no {module.lower()} record"]
        recs = records(body, probe["path"], module)
        if recs:
            keys = [sorted(record_keys(r)[0] | record_keys(r)[1])[:4] for r in recs[:5]]
            c_ids = set(map(str, tok.get("_C_SUB_IDS", [])))
            mark = " incl. Award C's subaward" if any(set(k) & c_ids for k in keys) else ""
            return "LEAK", [f"{len(recs)} {module.lower()} record(s) returned{mark}: {keys}"]
        if not ((base_row or {}).get("rec_counts") or {}).get(module):
            return "NOT TESTED", [f"baseline listed no {module.lower()} record: absence not provable"]
        return "PASS", []
    if kind == "sap_scoped":
        # H3: authorized SAP still accessible, other-family children and raw payloads withheld.
        b = base_row or {}
        if not (b.get("status") == 200 and b.get("sap_tx") and b.get("sap_payload")
                and set(probe["sap_hidden"]) & set(b.get("sap_children") or [])):
            return "NOT TESTED", [f"baseline did not show transmissions, a hidden child and payloads ({b.get('status')})"]
        if status != 200 or not isinstance(body, dict):
            return "FAIL", [f"status {status}: authorized SAP data no longer accessible"]
        content = body.get("content") or []
        kids = {str(c.get("awardNumber")) for tx in content for c in (tx.get("children") or [])}
        leaked = sorted(kids & set(probe["sap_hidden"]))
        if leaked:
            return "LEAK", [f"other-unit SAP child(ren) listed: {leaked}"]
        if any(tx.get("sentData") is not None or tx.get("returnedData") is not None for tx in content):
            return "LEAK", ["raw SOAP payload returned to a scoped caller"]
        total = body.get("totalElements", len(content))
        if total != b["sap_tx"]:
            return "FAIL", [f"{total} transmissions vs {b['sap_tx']} at baseline: authorized SAP rows lost"]
        if not kids & set(probe["sap_visible"]):
            return "FAIL", [f"in-unit child(ren) {probe['sap_visible']} missing: authorized SAP data lost"]
        return "PASS", [f"{total} transmissions; children {sorted(kids)}; payloads withheld"]
    if kind == "refused":
        ok = status == 403 and '"ACCESS_DENIED"' in text
        return ("PASS" if ok else "FAIL"), ([] if ok else [
            f"expected 403 ACCESS_DENIED (cross-module search refused for a scoped caller), got {status}"])
    if kind == "none":
        ok = status == 403 and '"ACCESS_NOT_PROVISIONED"' in text
        return ("PASS" if ok else "FAIL"), ([] if ok else [f"expected 403 ACCESS_NOT_PROVISIONED, got {status}"])
    if kind == "admin":
        return ("PASS" if status in (403, 404) else "FAIL"), ([] if status in (403, 404) else [f"status {status}"])
    if kind == "count":
        try:
            d = json.loads(text)
            now = int(d.get("awards", d.get("awardCount", -1)))
        except Exception:
            d, now = {}, -1
        then = (base_row or {}).get("dashboard_awards", -1)
        if now < 0 or then < 0:
            return "NOT TESTED", ["dashboard award count not found"]
        if not sets or role not in sets:
            return ("PASS" if now < then else "FAIL"), [f"awards {now} vs unscoped {then}"]
        # Exact: awards and award history equal the role's DB scope set; IRB figures absent (D9);
        # IO gets zero Proposals/Negotiations/Subawards (D-A); nothing exceeds the unscoped baseline.
        exp = {"awards": len(sets[role]), "awardHistoryRecords": sets.get(f"{role}_versions")}
        if role == "OAV":
            exp.update(proposals=0, proposalHistoryRecords=0, negotiations=0, subawards=0)
        over, under = [], []
        for f, want in exp.items():
            got = d.get(f)
            if want is None or got is None:
                under.append(f"{f} missing (expected {want})")
            elif int(got) > int(want):
                over.append(f"{f} {got} > expected {want}")
            elif int(got) < int(want):
                under.append(f"{f} {got} < expected {want}")
        unscoped = (base_row or {}).get("dashboard") or {}
        over += [f"{f} {d[f]} > unscoped {unscoped[f]}" for f in d
                 if isinstance(d.get(f), (int, float)) and isinstance(unscoped.get(f), (int, float)) and d[f] > unscoped[f]]
        irb = [f for f in IRB_DASHBOARD_FIELDS if f in d]
        if over:
            return "LEAK", over
        if under or irb:
            return "FAIL", under + ([f"IRB figures present under enforcement: {irb}"] if irb else [])
        return "PASS", [f"exact: {exp}"]
    if bs in (None,):
        return "NOT TESTED", ["no baseline row"]
    if kind == "deny":
        if bs != 200:
            return "NOT TESTED", [f"baseline {bs}: no data to hide, denial not provable"]
        if status not in (403, 404):
            return "FAIL", [f"status {status}, expected 403/404"]
        notes = [f"ambiguous identifiers {soft}"] if soft else []
        if status == 404 and '"Record not found"' in text:
            # A hidden record says "Record not found"; a non-existent one says "<Type> not found: <id>".
            notes.append("FINDING existence-oracle: hidden-record 404 message differs from a true not-found")
        return ("REVIEW" if soft else "PASS"), notes
    if kind == "allow":
        if bs != 200:
            return "NOT TESTED", [f"baseline {bs} (no data, gated or disabled): allow not provable"]
        if status != 200:
            return "FAIL", [f"status {status}, baseline 200 - in-scope access lost"]
        lost = [t for t in base_row.get("in_seen", {}).get(role, []) if t.lower() not in text.lower()]
        if lost:
            return "FAIL", [f"in-scope identifiers missing: {lost}"]
        return ("REVIEW" if soft else "PASS"), ([f"ambiguous identifiers {soft}"] if soft else [])
    if kind == "list":
        if status != bs:
            return "FAIL", [f"status {status}, baseline {bs}"]
        lost = [t for t in base_row.get("in_seen", {}).get(role, []) if t.lower() not in text.lower()]
        if lost:
            return "FAIL", [f"in-scope identifiers missing: {lost}"]
        shown = base_row.get("out_seen", {}).get(role, [])
        if not shown and not base_row.get("in_seen", {}).get(role):
            return "NOT TESTED", ["baseline showed no fixture identifier: list not discriminating"]
        return ("REVIEW" if soft else "PASS"), ([f"ambiguous identifiers {soft}"] if soft else [])
    return "FAIL", [f"unknown kind {kind}"]


# ------------------------------------------------------------------ modes
def baseline_facts(path, ct, text):
    """Shape facts kept from a baseline body (never the body): record counts, SAP shape, dashboard."""
    row = {}
    if "json" not in (ct or ""):
        return row
    try:
        body = json.loads(text)
    except ValueError:
        return row
    row["rec_counts"] = {m: len(records(body, path, m)) for m in IO_FORBIDDEN_MODULES}
    if "/sap-transmissions" in path and isinstance(body, dict):
        content = body.get("content") or []
        row["sap_tx"] = body.get("totalElements", len(content))
        row["sap_children"] = sorted({str(c.get("awardNumber")) for tx in content for c in (tx.get("children") or [])})
        # Presence only: payload text is never kept (archived payloads have carried credentials).
        row["sap_payload"] = any(tx.get("sentData") or tx.get("returnedData") for tx in content)
    if path == "/api/dashboard" and isinstance(body, dict):
        row["dashboard_awards"] = int(body.get("awards", -1))
        row["dashboard"] = {k: v for k, v in body.items() if isinstance(v, (int, float))}
    return row


def predict_not_tested(fx):
    """Probes that the baseline alone already makes NOT TESTED, whatever the enforced response.
    ('list' may still turn REVIEW/LEAK on the enforced body through the full-scope scan.)"""
    out = []
    for pr in plan(fx):
        b = fx["baseline"].get(f"{pr['method']} {pr['path']}") or {}
        k, why = pr["kind"], None
        if k == "excluded":
            why = pr["label"]
        elif k == "disabled":
            why = f"feature disabled/out of scope (baseline {b.get('status')})"
        elif k in ("allow", "deny") and not b.get("skipped_baseline") and b.get("status") != 200:
            why = f"baseline {b.get('status')}: nothing to {'show' if k == 'allow' else 'hide'}"
        elif k == "list" and not b.get("in_seen", {}).get(pr["role"]) and not b.get("out_seen", {}).get(pr["role"]):
            why = "baseline showed no fixture identifier (full-scope scan still applies)"
        elif k == "no_records" and not (b.get("rec_counts") or {}).get(pr["module"]):
            why = f"baseline listed no {pr['module'].lower()} record"
        elif k == "sap_scoped" and not (b.get("status") == 200 and b.get("sap_tx") and b.get("sap_payload")
                                        and set(pr["sap_hidden"]) & set(b.get("sap_children") or [])):
            why = "baseline SAP shape incomplete"
        if why:
            out.append({"role": pr["role"], "kind": k, "method": pr["method"], "path": pr["path"], "reason": why})
    return out


def scope_sets_for_judge(fx):
    s = fx.get("scope_sets") or {}
    if not s.get("DEPT") or not s.get("OAV"):
        return None
    return {"DEPT": set(s["DEPT"]), "OAV": set(s["OAV"]),
            "DEPT_versions": s.get("DEPT_versions"), "OAV_versions": s.get("OAV_versions")}


def do_baseline(base, fixture_path, scope_sets_path):
    t = token("BASELINE")
    if not scope_sets_path or not os.path.exists(scope_sets_path):
        sys.exit("refusing: --scope-sets <file> (read-only DB scope sets) is required for the baseline")
    fx = discover(base, t, json.load(open(scope_sets_path)))
    tok = scope_tokens(fx)
    uniq = {}
    for pr in plan(fx):
        if pr["kind"] != "excluded":
            uniq.setdefault(f"{pr['method']} {pr['path']}", pr)

    def one(pr):
        if pr["path"].split("?")[0].endswith(NO_BASELINE_CONTENT):
            return {"status": None, "skipped_baseline": "attachment content not opened with enforcement off",
                    "in_seen": {}, "out_seen": {}}
        s, ct, b = http(base, t, pr["method"], pr["path"], pr.get("body"))
        text = b.decode("utf-8", "replace")
        row = {"status": s, "ctype": ct, "sha256": hashlib.sha256(b).hexdigest(),
               "in_seen": {r: present(text, [x for k in IN_SCOPE[r] for x in tok.get(k, [])]) for r in ROLES},
               "out_seen": {r: present(text, [x for k in OUT_SCOPE[r] for x in tok.get(k, [])]) for r in ROLES}}
        row.update(baseline_facts(pr["path"], ct, text))
        return row

    with cf.ThreadPoolExecutor(WORKERS) as ex:
        rows = dict(zip(uniq, ex.map(one, uniq.values())))
    # An out-of-scope identifier that appears in an in-scope body for the same role is ambiguous.
    ambiguous = {}
    for pr in plan(fx):
        if pr["kind"] == "allow" and not pr.get("strict"):
            seen = rows.get(f"{pr['method']} {pr['path']}", {}).get("out_seen", {}).get(pr["role"], [])
            ambiguous.setdefault(pr["role"], set()).update(seen)
    fx["baseline"] = rows
    fx["ambiguous"] = {k: sorted(v) for k, v in ambiguous.items()}
    fx["predicted_not_tested"] = predict_not_tested(fx)
    with open(fixture_path, "w") as f:
        json.dump(fx, f, indent=1, default=str)
    st = {}
    for r in rows.values():
        st[r["status"]] = st.get(r["status"], 0) + 1
    print(f"baseline: {len(rows)} distinct requests, status mix {dict(sorted(st.items(), key=lambda kv: str(kv[0])))}")
    print("fixtures:", {k: v["award_number"] for k, v in fx["awards"].items()},
          "neg_in", fx.get("neg_in"), "neg_out", fx.get("neg_out"), "sub_out", fx.get("sub_out"),
          "prop_in", fx.get("prop_in_number"), fx.get("prop_in_id"), "prop_out", fx.get("prop_out_number"),
          fx.get("prop_out_id"), "sub_out_ids", len(fx.get("sub_out_ids") or []),
          "scope", {k: len(fx["scope_sets"][k]) for k in ("DEPT", "OAV")})
    sap = {k: {f: v.get(f) for f in ("status", "sap_tx", "sap_children", "sap_payload")}
           for k, v in rows.items() if "/sap-transmissions" in k}
    print("SAP baseline shape:", json.dumps(sap))
    print("ambiguous identifiers per role:", fx["ambiguous"])
    print(f"predicted NOT TESTED (fixed before enforcement; the acceptance bar): {len(fx['predicted_not_tested'])}")
    for x in fx["predicted_not_tested"]:
        print(f"  {x['role']:4} {x['kind']:10} {x['path']}  {x['reason']}")
    print(f"written to {fixture_path} (statuses, hashes and identifier presence only; no bodies)")


def do_run(base, fx, out, stop_on_leak):
    toks = {r: token(r) for r in ROLES}
    tok = scope_tokens(fx)
    amb = {k: set(v) for k, v in fx.get("ambiguous", {}).items()}
    sets = scope_sets_for_judge(fx)
    if not sets:
        sys.exit("refusing: fixture has no scope sets (re-run the baseline with --scope-sets)")
    rows, tally, lock, stop = [], {}, threading.Lock(), threading.Event()
    # Window guards first: if enforcement is not on, nothing else is meaningful.
    ps = plan(fx)
    ps.sort(key=lambda x: x["kind"] != "caps")

    def one(pr):
        if stop.is_set():
            return
        if pr["kind"] == "excluded":
            s, ct, b = 0, "", b""
        else:
            s, ct, b = http(base, toks[pr["role"]], pr["method"], pr["path"], pr.get("body"))
        text = b.decode("utf-8", "replace")
        base_row = fx["baseline"].get(f"{pr['method']} {pr['path']}")
        verdict, why = judge(pr, base_row, s, ct, text, tok, amb, sets)
        with lock:
            tally[verdict] = tally.get(verdict, 0) + 1
            rows.append(dict(pr, body=None, status=s, baseline_status=(base_row or {}).get("status"),
                             body_sha256=hashlib.sha256(b).hexdigest(), verdict=verdict, reasons=why))
            if verdict != "PASS":
                print(f"{verdict:10} {pr['role']:4} {pr['method']:4} {s} {pr['path']}  [{pr['label']}] {why}", flush=True)
            if (verdict == "LEAK" or (pr["kind"] == "caps" and verdict == "FAIL")) and stop_on_leak:
                print("ABORT: " + ("unauthorized disclosure" if verdict == "LEAK" else "enforcement not confirmed")
                      + " - stopping the run (roll back now)", flush=True)
                stop.set()

    caps = [x for x in ps if x["kind"] == "caps"]
    for pr in caps:
        one(pr)
    with cf.ThreadPoolExecutor(WORKERS) as ex:
        list(ex.map(one, [x for x in ps if x["kind"] != "caps"]))
    if stop.is_set():
        tally["ABORTED"] = 1
    with open(out, "w") as f:
        json.dump({"run_at": dt.datetime.now(dt.timezone.utc).isoformat(), "base": base,
                   "fixture_captured_at": fx.get("captured_at"), "tally": tally, "probes": rows}, f, indent=1)
    print(f"\n{len(rows)} probes: {dict(sorted(tally.items()))}; evidence {out} (bodies hashed, not stored)")
    pred = {(x["role"], x["method"], x["path"]) for x in fx.get("predicted_not_tested") or []}
    got = {(r["role"], r["method"], r["path"]) for r in rows if r["verdict"] == "NOT TESTED"}
    if got - pred:
        print(f"UNPREDICTED NOT TESTED ({len(got - pred)}): investigate before any go/no-go", sorted(got - pred)[:20])
    return 2 if (tally.get("LEAK") or tally.get("ABORTED")) else (1 if tally.get("FAIL") else 0)


def self_test():
    fx = {"awards": {k: dict(v, award_id=i + 1, title=f"Title of record {k} long", tm_txn=10 + i, tm_doc=f"D{i}")
                     for i, (k, v) in enumerate(FIXED.items())},
          "neg_in": 7, "neg_out": 8, "sub_out": 9, "sub_out_codes": ["3259"], "sub_out_titles": ["Subaward title of C long"],
          "sub_out_ids": ["9", "19"], "sub_out_by_code": {"3260": 19},
          "prop_out_id": 5, "prop_out_number": "00099001", "prop_in_id": 6, "prop_in_number": "00099002",
          "sap_dept_roots": [dict(r) for r in SAP_DEPT_ROOTS], "sap_io_parent": dict(SAP_IO_PARENT),
          "scope_sets": {"DEPT": ["212128-00002", "211144-00007", "110224-00001", "110224-00002"],
                         "OAV": ["209407-00002"], "DEPT_versions": 9, "OAV_versions": 14}}
    tok = scope_tokens(fx)
    sets = scope_sets_for_judge(fx)
    j = "application/json"
    P = lambda role, kind: {"role": role, "kind": kind, "path": "/x", "method": "GET"}
    base200 = {"status": 200, "in_seen": {"DEPT": ["212128-00002"], "OAV": ["209407-00002"], "NONE": []},
               "out_seen": {"DEPT": ["209407-00002"], "OAV": ["212128-00002"], "NONE": []}}
    base404 = {"status": 404, "in_seen": {}, "out_seen": {}}
    cases = [
        (P("DEPT", "deny"), base200, 404, j, '{"code":"NOT_FOUND"}', "PASS"),
        (P("DEPT", "deny"), base200, 404, j, '{"code":"NOT_FOUND","x":"209407-00002"}', "LEAK"),
        (P("DEPT", "deny"), base200, 200, j, '{"x":1}', "FAIL"),
        (P("DEPT", "deny"), base404, 404, j, '{}', "NOT TESTED"),
        (P("DEPT", "deny"), base200, 404, "text/html", "<html>", "FAIL"),
        (P("DEPT", "allow"), base200, 200, j, '{"n":"212128-00002"}', "PASS"),
        (P("DEPT", "allow"), base200, 404, j, '{}', "FAIL"),
        (P("DEPT", "allow"), base200, 200, j, '{"n":"other"}', "FAIL"),
        (P("DEPT", "allow"), base404, 404, j, '{}', "NOT TESTED"),
        (P("DEPT", "list"), base200, 200, j, '{"c":["212128-00002"]}', "PASS"),
        (P("DEPT", "list"), base200, 200, j, '{"c":["212128-00002","209407-00002"]}', "LEAK"),
        (P("OAV", "list"), base200, 200, j, '{"c":["209407-00002","Subaward title of C long"]}', "LEAK"),
        (P("OAV", "list"), base200, 200, j, '{"c":["209407-00002","code 3259"]}', "PASS"),  # short code is not a marker
        (P("NONE", "none"), base200, 403, j, '{"code":"ACCESS_NOT_PROVISIONED"}', "PASS"),
        (P("NONE", "none"), base200, 200, j, '{"content":[]}', "FAIL"),
        (P("DEPT", "caps"), None, 200, j, '{"enforcementEnabled":false,"administrationWritesAvailable":false}', "PASS"),
        (P("DEPT", "caps"), None, 200, j, '{"administrationWritesAvailable":true}', "FAIL"),
        ({"role": "NONE", "kind": "caps", "path": "/api/dashboard", "method": "GET"}, None, 403, j, '{"code":"ACCESS_NOT_PROVISIONED"}', "PASS"),
        ({"role": "NONE", "kind": "caps", "path": "/api/dashboard", "method": "GET"}, None, 200, j, '{"awards":40926}', "FAIL"),
        (P("DEPT", "refused"), base200, 403, j, '{"code":"ACCESS_DENIED"}', "PASS"),
        (P("OAV", "refused"), base200, 200, j, '{"content":[]}', "FAIL"),
        (P("DEPT", "refused"), base200, 403, j, '{"code":"ACCESS_DENIED","x":"209407-00002"}', "LEAK"),
        (P("NONE", "none"), base200, 403, j, '{"code":"ATTACHMENT_ACCESS_DENIED"}', "NOT TESTED"),
        (P("DEPT", "deny"), base200, 403, j, '{"code":"ATTACHMENT_ACCESS_DENIED"}', "NOT TESTED"),
        (P("DEPT", "allow"), {"status": None, "skipped_baseline": "x"}, 200, "application/pdf", "%PDF", "PASS"),
        (P("DEPT", "deny"), {"status": None, "skipped_baseline": "x"}, 404, j, '{"code":"NOT_FOUND"}', "PASS"),
        (P("DEPT", "deny"), {"status": None, "skipped_baseline": "x"}, 200, "application/pdf", "%PDF", "FAIL"),
        ({"role": "DEPT", "kind": "deny", "path": "/api/v1/awards/by-number/209407-00002", "method": "GET"}, base200, 404, j,
         '{"code":"NOT_FOUND","message":"Record not found","path":"/api/v1/awards/by-number/209407-00002"}', "PASS"),
        ({"role": "DEPT", "kind": "deny", "path": "/api/v1/awards/by-number/209407-00002", "method": "GET"}, base200, 404, j,
         '{"code":"NOT_FOUND","message":"209407-00002 exists","path":"/api/v1/awards/by-number/209407-00002"}', "LEAK"),
        ({"role": "OAV", "kind": "list", "path": "/api/global-search?query=212128-00002", "method": "GET"}, base200, 200, j,
         '{"query":"212128-00002","totalResults":0,"results":["209407-00002 in scope"]}', "PASS"),
        ({"role": "OAV", "kind": "list", "path": "/api/global-search?query=212128-00002", "method": "GET"}, base200, 200, j,
         '{"query":"212128-00002","results":[{"awardNumber":"212128-00002"}]}', "LEAK"),
        (P("DEPT", "admin"), None, 403, j, '{}', "PASS"),
        (P("DEPT", "admin"), None, 200, j, '{}', "FAIL"),
    ]
    # ---- cases judged WITH the full scope sets (session-3 strengthening)
    NR = lambda path, module: {"role": "OAV", "kind": "no_records", "path": path, "method": "GET", "module": module}
    recb = {"status": 200, "in_seen": {}, "out_seen": {}, "rec_counts": {"SUBAWARD": 3, "NEGOTIATION": 2, "PROPOSAL": 3}}
    rec0 = {"status": 200, "in_seen": {}, "out_seen": {}, "rec_counts": {"SUBAWARD": 0, "NEGOTIATION": 0, "PROPOSAL": 0}}
    sapP = {"role": "DEPT", "kind": "sap_scoped", "path": "/api/v1/awards/1022767/sap-transmissions?size=100",
            "method": "GET", "sap_visible": ["110224-00002"], "sap_hidden": ["110224-00003"]}
    sapB = {"status": 200, "in_seen": {}, "out_seen": {}, "sap_tx": 2, "sap_payload": True,
            "sap_children": ["110224-00002", "110224-00003"]}
    kid = lambda n: {"awardNumber": n, "leadUnitNumber": "x"}
    sap_ok = json.dumps({"content": [{"awardNumber": "110224-00001", "sentData": None, "returnedData": None,
                                      "children": [kid("110224-00002")]},
                                     {"awardNumber": "110224-00001", "sentData": None, "returnedData": None,
                                      "children": []}], "totalElements": 2})
    dashB = {"status": 200, "in_seen": {}, "out_seen": {}, "dashboard_awards": 40926,
             "dashboard": {"awards": 40926, "awardHistoryRecords": 267386, "proposals": 33960,
                           "proposalHistoryRecords": 119788, "negotiations": 10775, "subawards": 3511, "documents": 491678}}
    CNT = lambda role: {"role": role, "kind": "count", "path": "/api/dashboard", "method": "GET"}
    oav_dash = {"awards": 1, "awardHistoryRecords": 14, "proposals": 0, "proposalHistoryRecords": 0,
                "negotiations": 0, "subawards": 0, "documents": 30}
    AL = lambda role, path="/x": {"role": role, "kind": "allow", "path": path, "method": "GET"}
    scoped_cases = [
        # no_records: structural, decisive even when no fixture identifier is in the body
        (NR("/api/subawards?query=3259", "SUBAWARD"), recb, 200, j, '{"content":[],"totalElements":0}', "PASS"),
        (NR("/api/subawards?query=3259", "SUBAWARD"), recb, 200, j, '{"content":[{"subawardId":9,"subawardCode":"3259"}]}', "LEAK"),
        (NR("/api/subawards?query=3259", "SUBAWARD"), recb, 200, j, '{"content":[{"subawardId":555}]}', "LEAK"),
        (NR("/api/subawards?query=3259", "SUBAWARD"), rec0, 200, j, '{"content":[]}', "NOT TESTED"),
        (NR("/api/v1/awards/3/funding-subawards", "SUBAWARD"), recb, 404, j, '{"code":"NOT_FOUND"}', "PASS"),
        (NR("/api/v1/awards/3/funding-subawards", "SUBAWARD"), recb, 200, j, '[]', "PASS"),
        (NR("/api/v1/awards/3/funding-subawards", "SUBAWARD"), recb, 200, j, '[{"navigableCurrentSubawardId":19}]', "LEAK"),
        (NR("/api/negotiations?leadUnit=1&size=25", "NEGOTIATION"), recb, 200, j, '{"content":[{"negotiationId":4}]}', "LEAK"),
        (NR("/api/negotiations?leadUnit=1&size=25", "NEGOTIATION"), recb, 200, j, '{"content":[],"totalElements":0}', "PASS"),
        # Global Search: judged on module + recordId; an Award item for C is fine, a SUBAWARD item is not
        (NR("/api/global-search?query=3259", "SUBAWARD"), recb, 200, j,
         '{"query":"3259","results":[{"module":"AWARD","recordId":3476042,"identifier":"209407-00002"}]}', "PASS"),
        (NR("/api/global-search?query=3259", "SUBAWARD"), recb, 200, j,
         '{"query":"3259","results":[{"module":"SUBAWARD","recordId":92899,"identifier":"3259"}]}', "LEAK"),
        # ...and any non-Award record in ANY IO Global Search is a leak (decision D-A)
        ({"role": "OAV", "kind": "list", "path": "/api/global-search?query=x", "method": "GET"}, base200, 200, j,
         '{"query":"x","results":[{"module":"AWARD","identifier":"209407-00002"},{"module":"PROPOSAL","recordId":1}]}', "LEAK"),
        # sap_scoped (H3)
        (sapP, sapB, 200, j, sap_ok, "PASS"),
        (sapP, sapB, 200, j, sap_ok.replace('"children": []', '"children": [{"awardNumber": "110224-00003"}]'), "LEAK"),
        (sapP, sapB, 200, j, sap_ok.replace('"sentData": null', '"sentData": "<soap/>"', 1), "LEAK"),
        (sapP, sapB, 200, j, sap_ok.replace('"totalElements": 2', '"totalElements": 1'), "FAIL"),
        (sapP, sapB, 200, j, sap_ok.replace('"110224-00002"', '"110224-00001"'), "FAIL"),
        (sapP, sapB, 404, j, '{"code":"NOT_FOUND"}', "FAIL"),
        (sapP, dict(sapB, sap_payload=False), 200, j, sap_ok, "NOT TESTED"),
        (sapP, dict(sapB, sap_children=["110224-00002"]), 200, j, sap_ok, "NOT TESTED"),
        # allowed-page scan against the role's FULL allowed set, not only fixture tokens
        (AL("DEPT"), base200, 200, j, '{"n":"212128-00002","rel":"110224-00001"}', "PASS"),
        (AL("DEPT"), base200, 200, j, '{"n":"212128-00002","note":"moved from 999999-00001"}', "REVIEW"),
        ({"role": "DEPT", "kind": "list", "path": "/api/v1/awards/search?q=x", "method": "GET"}, base200, 200, j,
         '{"content":[{"awardNumber":"212128-00002"},{"awardNumber":"999999-00001"}]}', "LEAK"),
        ({"role": "DEPT", "kind": "list", "path": "/api/global-search?query=x", "method": "GET"}, base200, 200, j,
         '{"query":"999999-00001","results":[{"module":"AWARD","identifier":"212128-00002"}]}', "PASS"),
        (AL("OAV"), base200, 200, j, '{"n":"209407-00002"}', "PASS"),
        (AL("OAV"), base200, 200, j, '{"n":"209407-00002","x":[{"awardNumber":"209407-00001"}]}', "LEAK"),  # C's parent: fixture token
        (AL("OAV"), base200, 200, j, '{"n":"209407-00002","x":[{"awardNumber":"300000-00001"}]}', "REVIEW"),
        # strict probes do not downgrade a hit to REVIEW because the baseline also showed it
        ({"role": "DEPT", "kind": "allow", "path": "/h", "method": "GET", "strict": True},
         dict(base200, in_seen={"DEPT": []}), 200, j, '{"n":"110224-00003"}', "LEAK"),
        # exact dashboard
        (CNT("OAV"), dashB, 200, j, json.dumps(oav_dash), "PASS"),
        (CNT("OAV"), dashB, 200, j, json.dumps(dict(oav_dash, awards=2)), "LEAK"),
        (CNT("OAV"), dashB, 200, j, json.dumps(dict(oav_dash, proposals=1)), "LEAK"),
        (CNT("OAV"), dashB, 200, j, json.dumps(dict(oav_dash, irb=5)), "FAIL"),
        (CNT("OAV"), dashB, 200, j, json.dumps(dict(oav_dash, awardHistoryRecords=13)), "FAIL"),
        (CNT("DEPT"), dashB, 200, j, json.dumps({"awards": 4, "awardHistoryRecords": 9, "proposals": 5, "negotiations": 3,
                                                  "subawards": 1, "documents": 9}), "PASS"),
        (CNT("DEPT"), dashB, 200, j, json.dumps({"awards": 3, "awardHistoryRecords": 9, "documents": 9}), "FAIL"),
        (CNT("DEPT"), dashB, 200, j, json.dumps({"awards": 4, "awardHistoryRecords": 9, "documents": 500000}), "LEAK"),
    ]
    amb_case = (P("DEPT", "deny"), base200, 404, j, '{"x":"209407-00002"}', "REVIEW")
    bad = 0
    for pr, br, s, ct, body, want in cases:
        got, why = judge(pr, br, s, ct, body, tok, {})
        if got != want:
            bad += 1
            print("SELF-TEST FAIL:", pr["role"], pr["kind"], s, body, "->", got, why, "wanted", want)
    for pr, br, s, ct, body, want in scoped_cases:
        got, why = judge(pr, br, s, ct, body, tok, {"DEPT": {"110224-00003"}}, sets)
        if got != want:
            bad += 1
            print("SELF-TEST FAIL (scoped):", pr["role"], pr["kind"], s, body[:90], "->", got, why, "wanted", want)
    got, _ = judge(*amb_case[:5], tok, {"DEPT": {"209407-00002"}})
    if got != amb_case[5]:
        bad += 1
        print("SELF-TEST FAIL: ambiguous identifier ->", got)
    bad += _self_test_discovery()
    # Prediction: a fully populated baseline predicts only the excluded/disabled probes; an empty one
    # predicts every baseline-dependent probe.
    full = {"status": 200, "in_seen": {r: ["x"] for r in ROLES}, "out_seen": {}, "sap_tx": 2, "sap_payload": True,
            "sap_children": [x for r in SAP_DEPT_ROOTS for x in r["hidden"]],
            "rec_counts": {m: 1 for m in IO_FORBIDDEN_MODULES}}
    keys = {f"{x['method']} {x['path']}" for x in plan(fx)}
    pf = predict_not_tested(dict(fx, baseline={k: full for k in keys}))
    pe = predict_not_tested(dict(fx, baseline={}))
    fixed = sum(1 for x in plan(fx) if x["kind"] in ("excluded", "disabled"))
    if len(pf) != fixed or len(pe) <= fixed:
        bad += 1
        print("SELF-TEST FAIL (prediction):", len(pf), "vs", fixed, "| empty baseline", len(pe))
    ps = plan(fx)
    n, kinds = len(ps), {}
    for x in ps:
        kinds[x["kind"]] = kinds.get(x["kind"], 0) + 1
    v1 = sum(1 for x in ps if x["path"].startswith("/api/v1/proposals/"))
    for need, why in ((v1 >= 40, f"v1 proposal probes {v1} < 40"),
                      (kinds.get("sap_scoped") == 2, f"sap_scoped {kinds.get('sap_scoped')}"),
                      (not any(x["kind"] == "list" and x["role"] == "OAV" and "/api/negotiations?" in x["path"] for x in ps),
                       "an OAV negotiation list is still judged by text"),
                      (any(x["role"] == "OAV" and "/api/negotiations/8" in x["path"] for x in ps), "no OAV probe on neg_out")):
        if not need:
            bad += 1
            print("SELF-TEST FAIL (plan):", why)
    total = len(cases) + len(scoped_cases) + 1 + 3 + 1
    print("self-test", "PASS" if not bad else f"FAIL ({bad})", f"({total} cases); plan has {n} probes; kinds {dict(sorted(kinds.items()))}")
    return 1 if bad else 0


def _self_test_discovery():
    """discover() against a fake API: real proposal ids resolve; a missing required fixture refuses."""
    aw = {"212128-00002": 1, "211144-00007": 2, "209407-00002": 3, "212137-00001": 4}
    lu = {1: "2573180018", 2: "2573180018", 3: "1330420000", 4: "1250000000"}
    sap = {r["award_id"]: r["award_number"] for r in SAP_DEPT_ROOTS + [SAP_IO_PARENT]}

    def fake(drop_prop=False):
        def jget_(base, t, path):
            if path == "/api/dashboard":
                return 200, {"awards": 40926}
            if path.startswith("/api/v1/awards/by-number/"):
                return 200, {"awardId": aw[path.rsplit("/", 1)[1]]}
            m = re.match(r"^/api/v1/awards/(\d+)/(.*)$", path)
            if m:
                aid, rest = int(m.group(1)), m.group(2)
                if rest == "summary":
                    if aid in sap:
                        return 200, {"awardNumber": sap[aid]}
                    return 200, {"leadUnitNumber": lu[aid], "title": f"Title {aid} long enough"}
                if rest == "funding-subawards":
                    return 200, [{"subawardId": 92899, "navigableCurrentSubawardId": 92899, "subawardCode": "3259"}]
                if rest == "funding-proposals":
                    return 200, [{"proposalNumber": "01444988", "exactLinkedProposalId": 1452400,
                                  "navigableActiveProposalId": 1452409}]
                if rest == "negotiations":
                    return 200, [{"negotiationId": 12373}]
                return 200, {}
            if path.startswith("/api/negotiations?"):
                return 200, {"content": [{"negotiationId": 12511}]}
            if path.startswith("/api/subawards/"):
                return 200, {"title": "National Center on Improving Literacy"}
            if path.startswith("/api/proposals/search?leadUnit="):
                return 200, {"content": [{"proposalNumber": "01092706", "currentProposalId": 1092805}]}
            if path.startswith("/api/proposals/search?query="):
                num = urllib.parse.unquote(path.split("=", 1)[1])
                ids = {"01092706": 1092805, "01444988": 1452409}
                if drop_prop and num == "01092706":
                    return 200, {"content": []}
                return 200, {"content": [{"proposalNumber": num, "currentProposalId": ids[num]}]}
            if path.startswith("/api/v1/proposals/"):
                pid = int(path.rsplit("/", 1)[1])
                return 200, {"proposalNumber": {1092805: "01092706", 1452409: "01444988"}.get(pid)}
            return 404, None
        return jget_

    scope = {"DEPT_awards": ["212128-00002"], "OAV_awards": ["209407-00002"], "DEPT_versions": 1, "OAV_versions": 1,
             "C_subawards": [{"subaward_id": 92899, "subaward_code": "3259"}, {"subaward_id": 93912, "subaward_code": "3260"}]}
    real, bad = globals()["jget"], 0
    try:
        globals()["jget"] = fake()
        fx = discover("https://x", "t", scope)
        if (fx["prop_in_id"], fx["prop_out_id"], fx["sub_out_by_code"]) != (1092805, 1452409, {"3260": 93912}):
            bad += 1
            print("SELF-TEST FAIL (discovery): ids", fx["prop_in_id"], fx["prop_out_id"], fx["sub_out_by_code"])
        globals()["jget"] = fake(drop_prop=True)
        try:
            discover("https://x", "t", scope)
            bad += 1
            print("SELF-TEST FAIL (discovery): unresolved proposal id did not refuse")
        except SystemExit as e:
            if "prop_in_id" not in str(e):
                bad += 1
                print("SELF-TEST FAIL (discovery): wrong refusal", e)
        globals()["jget"] = fake()
        try:
            discover("https://x", "t", None)
            bad += 1
            print("SELF-TEST FAIL (discovery): missing scope sets did not refuse")
        except SystemExit:
            pass
    finally:
        globals()["jget"] = real
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", default="plan", choices=["plan", "self-test", "baseline", "run"])
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--base-url")
    ap.add_argument("--fixture", default="a8-matrix-baseline.json")
    ap.add_argument("--evidence", default="a8-matrix-evidence.json")
    ap.add_argument("--scope-sets", help="read-only DB scope sets JSON (required for baseline)")
    ap.add_argument("--no-stop-on-leak", action="store_true", help="finish the run after a LEAK (default: stop)")
    a = ap.parse_args()
    if a.mode == "self-test":
        return self_test()
    if a.mode == "plan" or not a.execute:
        fx = json.load(open(a.fixture)) if os.path.exists(a.fixture) else None
        if not fx:
            print("no fixture yet: run 'baseline --execute' first to resolve ids; showing counts from self-test shape")
            return self_test()
        ps = plan(fx)
        by = {}
        for p in ps:
            by[(p["role"], p["kind"])] = by.get((p["role"], p["kind"]), 0) + 1
        for p in ps:
            print(f"{p['role']:4} {p['kind']:8} {p['method']:4} {p['path']}  [{p['label']}]")
        print(f"\n{len(ps)} probes; by role/kind {dict(sorted(by.items()))}")
        if a.mode != "plan":
            print("DRY RUN: nothing was sent. Add --execute.")
        return 0
    if not a.base_url:
        sys.exit("--base-url is required with --execute")
    guard_base(a.base_url)
    if a.mode == "baseline":
        do_baseline(a.base_url, a.fixture, a.scope_sets)
        return 0
    return do_run(a.base_url, json.load(open(a.fixture)), a.evidence, not a.no_stop_on_leak)


if __name__ == "__main__":
    sys.exit(main())
