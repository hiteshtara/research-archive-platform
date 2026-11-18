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
REFUSED_FOR_SCOPED = ("/api/documents/search", "/api/v1/documents?", "/api/v1/attachments/search")
# Recorded, never judged: Explorer is off on DEV; IRB is excluded from release scope and its
# API stays on (app.irb.api.enabled defaults true) because IRB exclusion is not activated in A8.
DISABLED_PATHS = ["/api/v1/explorer/awards", "/api/irb"]
EXCLUDED = [("/api/investigators?email=<a real person>", "looks up a named BU person by email; excluded: the synthetic test never queries a real identity")]
WORKERS = 8
ADMIN_PATHS = ["/api/v1/admin/identities?q=ARP", "/api/v1/admin/administrators", "/api/v1/admin/audit"]


# ------------------------------------------------------------------ http
def http(base, token, method, path, body=None, timeout=90):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json, application/pdf")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
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


# ------------------------------------------------------------------ fixtures
def discover(base, t):
    s, caps = jget(base, t, "/api/v1/me/capabilities")
    if not caps or caps.get("enforcementEnabled") is not False:
        sys.exit("refusing: baseline needs enforcement OFF (and a JSON API response)")
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
    # Proposal funding the CGS award: out of scope for both synthetic roles.
    s, pl = jget(base, t, f"/api/v1/awards/{g['award_id']}/funding-proposals")
    fx["prop_out_id"] = (find_all(pl, "proposalId") or [None])[0]
    fx["prop_out_number"] = (find_all(pl, "proposalNumber") or [None])[0]
    # A proposal led by unit 2573180018: in scope for DEPT.
    s, pin = jget(base, t, f"/api/proposals/search?leadUnit={DEPT_UNIT}&size=5")
    fx["prop_in_number"] = (find_all(pin, "proposalNumber") or [None])[0]
    s, plist = jget(base, t, f"/api/proposals/search?query={Q(str(fx['prop_in_number']))}") if fx["prop_in_number"] else (0, None)
    fx["prop_in_id"] = (find_all(plist, "proposalId") or [None])[0]
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
    return t


IN_SCOPE = {"DEPT": ["A", "B", "PROP_IN"], "OAV": ["C"], "NONE": []}
OUT_SCOPE = {"DEPT": ["C", "G", "SUB_C", "PROP_OUT"], "OAV": ["A", "B", "G", "SUB_C", "PROP_OUT", "PROP_IN"],
             "NONE": ["A", "B", "C", "G", "SUB_C", "PROP_OUT", "PROP_IN"]}


# ------------------------------------------------------------------ probe plan
def plan(fx):
    """Every probe: dict(role, kind, method, path, body, label)."""
    aw = fx["awards"]
    p = []

    def add(role, kind, path, label, method="GET", body=None):
        p.append(dict(role=role, kind=kind, method=method, path=path, body=body, label=label))

    def award_paths(k):
        r = aw[k]
        out = [(f"/api/v1/awards/{r['award_id']}/{x}", f"award {k} {x}") for x in AWARD_ID_PATHS]
        out += [(t.format(n=r["award_number"]), f"award {k} {t.split('{n}')[-1] or 'detail'}") for t in AWARD_NUM_PATHS]
        return out

    for role in ROLES:
        add(role, "caps", "/api/v1/me/capabilities", "window guard")
        for path, why in EXCLUDED:
            add(role, "excluded", path, why)
        for path in ADMIN_PATHS:
            add(role, "none" if role == "NONE" else "admin", path, "admin API, non-administrator")
        for path in DISABLED_PATHS:
            add(role, "disabled", path, "out of A8 scope: Explorer off / IRB excluded (recorded, not judged)")

    allow = {"DEPT": ["A", "B"], "OAV": ["C"]}
    deny = {"DEPT": ["C", "G"], "OAV": ["A", "B", "G"]}
    for role in ("DEPT", "OAV"):
        for k in allow[role]:
            for path, label in award_paths(k):
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

    # Negotiations (H2): A's negotiation is DEPT's; IO never reaches Negotiations.
    if fx.get("neg_in"):
        for x in NEG_PATHS:
            add("DEPT", "allow", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
            add("OAV", "deny", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
            add("NONE", "none", f"/api/negotiations/{fx['neg_in']}{x}", f"negotiation(A){x or ' detail'}")
    if fx.get("neg_out"):
        for x in NEG_PATHS:
            add("DEPT", "deny", f"/api/negotiations/{fx['neg_out']}{x}", f"negotiation(out){x or ' detail'}")

    # Subawards: C's subaward is outside DEPT's unit, and IO never reaches Subawards.
    if fx.get("sub_out"):
        for x in SUB_PATHS:
            for role in ("DEPT", "OAV"):
                add(role, "deny", f"/api/subawards/{fx['sub_out']}{x}", f"subaward(C){x or ' detail'}")
            add("NONE", "none", f"/api/subawards/{fx['sub_out']}{x}", f"subaward(C){x or ' detail'}")

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
        add(role, kind, "/api/negotiations?leadUnit=1330420000&size=25", "negotiation list, unit of C")
        add(role, kind, f"/api/negotiations?leadUnit={DEPT_UNIT}&size=25", "negotiation list, unit of A/B")
        for code in (fx.get("sub_out_codes") or [])[:2]:
            add(role, kind, f"/api/subawards?query={Q(code)}", "subaward list by C's subaward code")
            add(role, kind, f"/api/global-search?query={Q(code)}", "global search by C's subaward code")
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


def judge(probe, base_row, status, ctype, text, tok, ambiguous):
    """Return (verdict, reasons). verdict in PASS, FAIL, LEAK, REVIEW, NOT TESTED."""
    role, kind = probe["role"], probe["kind"]
    is_json = "json" in (ctype or "").lower()
    out_t = [t for k in OUT_SCOPE[role] for t in tok.get(k, [])]
    in_t = [t for k in IN_SCOPE[role] for t in tok.get(k, [])]
    hits = present(text, out_t)
    hard = [h for h in hits if h not in ambiguous.get(role, set())]
    soft = [h for h in hits if h in ambiguous.get(role, set())]
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
        ok = status == 200 and '"enforcementEnabled":true' in compact and '"administrationWritesAvailable":false' in compact
        return ("PASS" if ok else "FAIL"), ([] if ok else ["enforcement not on, or writes available"])
    if not is_json and kind != "allow":
        if status in (403, 404):
            return "FAIL", [f"denial not a JSON API response (content-type {ctype!r})"]
    if hard:
        return "LEAK", [f"out-of-scope identifier in body: {hard}"]
    bs = base_row.get("status") if base_row else None
    if status == 403 and '"ATTACHMENT_ACCESS_DENIED"' in text:
        # The Cognito-group gate runs before record scope, so this proves nothing about scope.
        return "NOT TESTED", ["attachment group gate (account not in ArchiveAttachmentViewer)"]
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
            now = int(json.loads(text).get("awards", json.loads(text).get("awardCount", -1)))
        except Exception:
            now = -1
        then = (base_row or {}).get("dashboard_awards", -1)
        if now < 0 or then < 0:
            return "NOT TESTED", ["dashboard award count not found"]
        return ("PASS" if now < then else "FAIL"), [f"awards {now} vs unscoped {then}"]
    if bs in (None,):
        return "NOT TESTED", ["no baseline row"]
    if kind == "deny":
        if bs != 200:
            return "NOT TESTED", [f"baseline {bs}: no data to hide, denial not provable"]
        if status not in (403, 404):
            return "FAIL", [f"status {status}, expected 403/404"]
        return ("REVIEW" if soft else "PASS"), ([f"ambiguous identifiers {soft}"] if soft else [])
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
def do_baseline(base, fixture_path):
    t = token("BASELINE")
    fx = discover(base, t)
    tok = scope_tokens(fx)
    uniq = {}
    for pr in plan(fx):
        if pr["kind"] != "excluded":
            uniq.setdefault(f"{pr['method']} {pr['path']}", pr)

    def one(pr):
        s, ct, b = http(base, t, pr["method"], pr["path"], pr.get("body"))
        text = b.decode("utf-8", "replace")
        row = {"status": s, "ctype": ct, "sha256": hashlib.sha256(b).hexdigest(),
               "in_seen": {r: present(text, [x for k in IN_SCOPE[r] for x in tok.get(k, [])]) for r in ROLES},
               "out_seen": {r: present(text, [x for k in OUT_SCOPE[r] for x in tok.get(k, [])]) for r in ROLES}}
        if pr["path"] == "/api/dashboard" and "json" in ct:
            try:
                row["dashboard_awards"] = int(json.loads(text).get("awards", -1))
            except Exception:
                row["dashboard_awards"] = -1
        return row

    with cf.ThreadPoolExecutor(WORKERS) as ex:
        rows = dict(zip(uniq, ex.map(one, uniq.values())))
    # An out-of-scope identifier that appears in an in-scope body for the same role is ambiguous.
    ambiguous = {}
    for pr in plan(fx):
        if pr["kind"] == "allow":
            seen = rows.get(f"{pr['method']} {pr['path']}", {}).get("out_seen", {}).get(pr["role"], [])
            ambiguous.setdefault(pr["role"], set()).update(seen)
    fx["baseline"] = rows
    fx["ambiguous"] = {k: sorted(v) for k, v in ambiguous.items()}
    with open(fixture_path, "w") as f:
        json.dump(fx, f, indent=1, default=str)
    st = {}
    for r in rows.values():
        st[r["status"]] = st.get(r["status"], 0) + 1
    print(f"baseline: {len(rows)} distinct requests, status mix {dict(sorted(st.items()))}")
    print("fixtures:", {k: v["award_number"] for k, v in fx["awards"].items()},
          "neg_in", fx.get("neg_in"), "neg_out", fx.get("neg_out"), "sub_out", fx.get("sub_out"),
          "prop_in", fx.get("prop_in_number"), "prop_out", fx.get("prop_out_number"))
    print("ambiguous identifiers per role:", fx["ambiguous"])
    print(f"written to {fixture_path} (statuses, hashes and identifier presence only; no bodies)")


def do_run(base, fx, out, stop_on_leak):
    toks = {r: token(r) for r in ROLES}
    tok = scope_tokens(fx)
    amb = {k: set(v) for k, v in fx.get("ambiguous", {}).items()}
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
        verdict, why = judge(pr, base_row, s, ct, text, tok, amb)
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
    return 2 if (tally.get("LEAK") or tally.get("ABORTED")) else (1 if tally.get("FAIL") else 0)


def self_test():
    fx = {"awards": {k: dict(v, award_id=i + 1, title=f"Title of record {k} long", tm_txn=10 + i, tm_doc=f"D{i}")
                     for i, (k, v) in enumerate(FIXED.items())},
          "neg_in": 7, "neg_out": 8, "sub_out": 9, "sub_out_codes": ["3259"], "sub_out_titles": ["Subaward title of C long"],
          "prop_out_id": 5, "prop_out_number": "00099001", "prop_in_id": 6, "prop_in_number": "00099002"}
    tok = scope_tokens(fx)
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
        (P("DEPT", "caps"), None, 200, j, '{"enforcementEnabled":true,"administrationWritesAvailable":false}', "PASS"),
        (P("DEPT", "caps"), None, 200, j, '{"enforcementEnabled":false,"administrationWritesAvailable":false}', "FAIL"),
        (P("DEPT", "refused"), base200, 403, j, '{"code":"ACCESS_DENIED"}', "PASS"),
        (P("OAV", "refused"), base200, 200, j, '{"content":[]}', "FAIL"),
        (P("DEPT", "refused"), base200, 403, j, '{"code":"ACCESS_DENIED","x":"209407-00002"}', "LEAK"),
        (P("NONE", "none"), base200, 403, j, '{"code":"ATTACHMENT_ACCESS_DENIED"}', "NOT TESTED"),
        (P("DEPT", "deny"), base200, 403, j, '{"code":"ATTACHMENT_ACCESS_DENIED"}', "NOT TESTED"),
        (P("DEPT", "admin"), None, 403, j, '{}', "PASS"),
        (P("DEPT", "admin"), None, 200, j, '{}', "FAIL"),
    ]
    amb_case = (P("DEPT", "deny"), base200, 404, j, '{"x":"209407-00002"}', "REVIEW")
    bad = 0
    for pr, br, s, ct, body, want in cases:
        got, why = judge(pr, br, s, ct, body, tok, {})
        if got != want:
            bad += 1
            print("SELF-TEST FAIL:", pr["role"], pr["kind"], s, body, "->", got, why, "wanted", want)
    got, _ = judge(*amb_case[:5], tok, {"DEPT": {"209407-00002"}})
    if got != amb_case[5]:
        bad += 1
        print("SELF-TEST FAIL: ambiguous identifier ->", got)
    n = len(plan(fx))
    print("self-test", "PASS" if not bad else f"FAIL ({bad})", f"({len(cases) + 1} cases); plan has {n} probes")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", default="plan", choices=["plan", "self-test", "baseline", "run"])
    ap.add_argument("--execute", action="store_true")
    ap.add_argument("--base-url")
    ap.add_argument("--fixture", default="a8-matrix-baseline.json")
    ap.add_argument("--evidence", default="a8-matrix-evidence.json")
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
        do_baseline(a.base_url, a.fixture)
        return 0
    return do_run(a.base_url, json.load(open(a.fixture)), a.evidence, not a.no_stop_on_leak)


if __name__ == "__main__":
    sys.exit(main())
