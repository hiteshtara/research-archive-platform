#!/usr/bin/env python3
"""A8 enforcement window 1 - live authorization probes for DEV.

DRY RUN BY DEFAULT. With no --execute this prints the probe plan and makes
no network call. Nothing in this tool writes anything: every probe is an
HTTP GET (the optional AI evidence probe is a POST to a read-only search).

Modes
  plan                         print every probe (default; no network)
  self-test                    check the assertion logic on canned bodies (no network)
  capture-baseline --execute   enforcement must be OFF: resolve records A/B/C,
                               write the fixture file the probes need
  run --execute                enforcement must be ON: run the probes, write evidence

Tokens are read from environment variables RAP_PROBE_TOKEN_<ROLE>
(DEPT, PI, OAV, NONE, BASELINE). They are never printed, logged or written.
See docs/security/A8_ENFORCEMENT_WINDOW_1.md and A6_SYNTHETIC_IDENTITIES.md.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

RECORDS = {  # BU's own test records (LALITHA_BU_ACCEPTANCE_CHECKLIST.md)
    "A": {"award_number": "212128-00002", "lead_unit": "2573180018"},
    "B": {"award_number": "211144-00007", "lead_unit": "2573180018"},
    "C": {"award_number": "209407-00002", "lead_unit": "1330420000"},
}
ROLES = ("DEPT", "PI", "OAV", "NONE")


# ---------------------------------------------------------------- probes
def probes(fx: dict, include_pi: bool) -> list[dict]:
    """Each probe: role, method, path, expect (status set), must/must_not
    (strings that must / must not appear in the body), criteria, fix."""
    a, b, c = (fx["records"][k] for k in "ABC")
    p: list[dict] = []

    def add(role, path, status, criteria, fix, must=(), must_not=(), method="GET", body=None, code=None):
        p.append(dict(role=role, method=method, path=path, status=set(status), must=list(must),
                      must_not=list(must_not), criteria=criteria, fix=fix, body=body, code=code))

    hidden_c = [c["award_number"]] + ([c["title"]] if c.get("title") else [])
    hidden_b = [b["award_number"]] + ([b["title"]] if b.get("title") else [])

    for role in ROLES:  # capabilities: same switches for everyone
        # enforcementEnabled is always false for a non-administrator (CapabilitiesService.denied()),
        # so only the writes switch is asserted here; enforcement is proven by S4's 403 below.
        add(role, "/api/v1/me/capabilities", {200}, "window guard", "-",
            must=['"administrationWritesAvailable":false'])

    # Department user, UNIT 2573180018 (A and B in scope; C out)
    add("DEPT", f"/api/v1/awards/search?q={a['award_number']}", {200}, "US-DEP-001 #1", "-", must=[a["award_number"]])
    add("DEPT", f"/api/v1/awards/search?q={b['award_number']}", {200}, "US-DEP-001 #1", "-", must=[b["award_number"]])
    add("DEPT", f"/api/v1/awards/search?q={c['award_number']}", {200}, "US-DEP-001 #2, US-DEP-005 #2, US-SEC-003 #2", "-", must_not=hidden_c)
    add("DEPT", f"/api/v1/awards/{c['award_id']}/summary", {404}, "US-DEP-001 #2, US-SEC-003 #1", "-", must_not=hidden_c, code="NOT_FOUND")
    add("DEPT", f"/api/v1/awards/by-number/{c['award_number']}", {404}, "US-SEC-003 #1", "-", must_not=[str(c["award_id"])])
    if fx.get("c_tm_txn_id"):
        add("DEPT", f"/api/v1/awards/{a['award_id']}/time-and-money/transactions/{fx['c_tm_txn_id']}", {404},
            "US-DEP-001 #2, US-SEC-003 #1", "H1 (#164)", must_not=hidden_c)
    if fx.get("c_tm_doc"):
        add("DEPT", f"/api/v1/awards/{a['award_id']}/time-and-money/documents/{fx['c_tm_doc']}", {404},
            "US-DEP-001 #2, US-SEC-003 #1", "H1 (#164)", must_not=hidden_c)
    add("DEPT", f"/api/v1/awards/{a['award_id']}/sap-transmissions", {200}, "US-DEP-001 #2, US-DEP-005 #1", "H3 (#164)", must_not=hidden_c)
    for rel in ("funding-proposals", "funding-subawards", "negotiations"):
        add("DEPT", f"/api/v1/awards/{a['award_id']}/{rel}", {200}, "US-DEP-005 #1", "#159", must_not=hidden_c)
    add("DEPT", f"/api/negotiations?query={urllib.parse.quote(c['award_number'])}", {200}, "US-DEP-005 #1 #2, US-SEC-003 #2", "H2 (#164)", must_not=hidden_c)
    if fx.get("c_negotiation_id"):
        add("DEPT", f"/api/negotiations/{fx['c_negotiation_id']}/associated-record", {404}, "US-DEP-005 #1, US-SEC-003 #2", "H2 (#164)", must_not=hidden_c)
    add("DEPT", f"/api/global-search?query={urllib.parse.quote(c['award_number'])}", {200}, "US-DEP-005 #2, US-SEC-003 #2", "-", must_not=hidden_c)
    add("DEPT", f"/api/ai/awards/{a['award_number']}/evidence-search", {200, 404, 503}, "US-DEP-005 #1", "H5 (#165)",
        method="POST", body={"query": c["award_number"]}, must_not=hidden_c)

    # Research staff (PI of A): A in scope, B not
    if include_pi:
        add("PI", f"/api/v1/awards/search?q={a['award_number']}", {200}, "US-PI-001 #2 (positive control)", "-", must=[a["award_number"]])
        add("PI", f"/api/v1/awards/search?q={b['award_number']}", {200}, "US-PI-001 #2, US-SEC-003 #2", "-", must_not=hidden_b)
        add("PI", f"/api/v1/awards/{b['award_id']}/summary", {404}, "US-PI-001 #2, US-SEC-003 #1", "-", must_not=hidden_b, code="NOT_FOUND")
        add("PI", f"/api/global-search?query={urllib.parse.quote(b['award_number'])}", {200}, "US-PI-001 #2, US-SEC-003 #2", "-", must_not=hidden_b)

    # OAV, IO 9500312705: C in scope, B not
    add("OAV", f"/api/v1/awards/search?q={c['award_number']}", {200}, "US-OAV-001 #2 (positive control)", "-", must=[c["award_number"]])
    add("OAV", f"/api/v1/awards/search?q={b['award_number']}", {200}, "US-OAV-001 #2, US-SEC-003 #2", "-", must_not=hidden_b)
    add("OAV", f"/api/v1/awards/{b['award_id']}/summary", {404}, "US-OAV-001 #2, US-SEC-003 #1", "-", must_not=hidden_b, code="NOT_FOUND")
    if fx.get("b_tm_txn_id"):
        add("OAV", f"/api/v1/awards/{c['award_id']}/time-and-money/transactions/{fx['b_tm_txn_id']}", {404},
            "US-OAV-001 #2, US-SEC-003 #1", "H1 (#164)", must_not=hidden_b)
    add("OAV", f"/api/v1/awards/{c['award_id']}/sap-transmissions", {200}, "US-OAV-001 #2", "H3 (#164)", must_not=hidden_b)

    # No-grant control: explicit "not provisioned", never an empty archive
    add("NONE", "/api/v1/awards/search?q=", {403}, "control (S6.1)", "-", code="ACCESS_NOT_PROVISIONED", must_not=hidden_b + hidden_c)
    add("NONE", f"/api/v1/awards/{a['award_id']}/summary", {403, 404}, "control (S6.2)", "-", must_not=[a["award_number"]])
    return p


# ---------------------------------------------------------------- checks
def judge(probe: dict, status: int, content_type: str, body: str) -> list[str]:
    """Return failure reasons; empty means PASS. Body assertions, not status only."""
    why = []
    # Page identity first: a relative /api on the UI origin returns index.html with 200.
    if "json" not in (content_type or "").lower():
        why.append(f"not a JSON API response (content-type {content_type!r}) - wrong origin or page")
        return why
    if status not in probe["status"]:
        why.append(f"status {status} not in {sorted(probe['status'])}")
    if probe.get("code") and status in (403, 404) and f'"{probe["code"]}"' not in body:
        why.append(f"expected error code {probe['code']}")
    for s in probe["must"]:
        if s not in body:
            why.append(f"expected to see {s!r}")
    for s in probe["must_not"]:
        if s and s.lower() in body.lower():
            why.append(f"LEAK: body contains {s!r}")
    return why


def http(base: str, token: str, method: str, path: str, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base.rstrip("/") + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read().decode("utf-8", "replace")


def find(obj, key):
    if isinstance(obj, dict):
        if obj.get(key) not in (None, ""):
            return obj[key]
        obj = list(obj.values())
    if isinstance(obj, list):
        for v in obj:
            r = find(v, key)
            if r not in (None, ""):
                return r
    return None


def token(role: str) -> str:
    t = os.environ.get(f"RAP_PROBE_TOKEN_{role}", "")
    if not t:
        sys.exit(f"RAP_PROBE_TOKEN_{role} is not set (tokens are never accepted on the command line)")
    return t


def guard_base(base: str) -> None:
    u = urllib.parse.urlparse(base)
    host = (u.hostname or "").lower()
    if u.scheme != "https":
        sys.exit("refusing: base URL must be https")
    if "prod" in host and "nonprod" not in host:
        sys.exit(f"refusing: {host} looks like production; this tool is DEV-only")


# ---------------------------------------------------------------- modes
def capture_baseline(base: str, out: str) -> None:
    t = token("BASELINE")
    s, ct, b = http(base, t, "GET", "/api/v1/me/capabilities")
    if "json" not in ct.lower() or '"enforcementEnabled":false' not in b.replace(" ", ""):
        sys.exit("refusing: capture-baseline needs enforcement OFF (and a JSON API response)")
    fx = {"captured_at": dt.datetime.now(dt.timezone.utc).isoformat(), "base": base, "records": {}}
    for k, r in RECORDS.items():
        s, ct, b = http(base, t, "GET", f"/api/v1/awards/by-number/{r['award_number']}")
        if s != 200:
            sys.exit(f"refusing: record {k} ({r['award_number']}) not resolvable in DEV (status {s})")
        aid = find(json.loads(b), "awardId")
        s, ct, sb = http(base, t, "GET", f"/api/v1/awards/{aid}/summary")
        summ = json.loads(sb) if s == 200 else {}
        unit = find(summ, "leadUnitNumber")
        if unit is not None and str(unit) != r["lead_unit"]:
            sys.exit(f"refusing: record {k} lead unit is {unit}, expected {r['lead_unit']}")
        fx["records"][k] = dict(r, award_id=aid, title=find(summ, "title"), lead_unit_observed=unit)
    for k, key in (("C", "c"), ("B", "b")):
        s, ct, hb = http(base, t, "GET", f"/api/v1/awards/{fx['records'][k]['award_id']}/time-and-money/history")
        if s == 200:
            h = json.loads(hb)
            fx[f"{key}_tm_txn_id"] = find(h, "pendingTransactionId")
            fx[f"{key}_tm_doc"] = find(h, "timeAndMoneyDocumentNumber")
    s, ct, nb = http(base, t, "GET", f"/api/v1/awards/{fx['records']['C']['award_id']}/negotiations")
    if s == 200:
        fx["c_negotiation_id"] = find(json.loads(nb), "negotiationId")
    with open(out, "w") as f:
        json.dump(fx, f, indent=2, default=str)
    print(f"baseline written to {out}: records A/B/C resolved; "
          f"T&M ids C={'yes' if fx.get('c_tm_txn_id') else 'none'} B={'yes' if fx.get('b_tm_txn_id') else 'none'}; "
          f"negotiation for C={'yes' if fx.get('c_negotiation_id') else 'none'}")


def run(base: str, fx: dict, include_pi: bool, out: str) -> int:
    toks = {r: token(r) for r in ROLES if include_pi or r != "PI"}
    rows, failed = [], 0
    for p in probes(fx, include_pi):
        s, ct, b = http(base, toks[p["role"]], p["method"], p["path"], p.get("body"))
        why = judge(p, s, ct, b)
        if p["fix"].startswith("H5") and s in (404, 503) and not why:
            verdict = "NOT TESTABLE (AI off)"
        else:
            verdict = "FAIL" if why else "PASS"
        failed += verdict == "FAIL"
        rows.append(dict(role=p["role"], method=p["method"], path=p["path"], status=s,
                         body_sha256=hashlib.sha256(b.encode()).hexdigest(), verdict=verdict,
                         reasons=why, criteria=p["criteria"], fix=p["fix"]))
        print(f"{verdict:22} {p['role']:5} {p['method']:4} {s} {p['path']}  [{p['criteria']}]" + (f"  {why}" if why else ""))
    with open(out, "w") as f:
        json.dump({"run_at": dt.datetime.now(dt.timezone.utc).isoformat(), "base": base,
                   "fixture_captured_at": fx.get("captured_at"), "probes": rows}, f, indent=2)
    print(f"\n{len(rows) - failed}/{len(rows)} not failed; evidence: {out} (bodies hashed, not stored)")
    return 1 if failed else 0


def self_test() -> int:
    fx = {"records": {k: dict(v, award_id=i + 1, title=f"Title {k}") for i, (k, v) in enumerate(RECORDS.items())}}
    all_p = probes(fx, True)
    pick = lambda role, path: next(x for x in all_p if x["role"] == role and x["path"] == path)
    c_summary = pick("DEPT", "/api/v1/awards/3/summary")
    c_search = pick("DEPT", "/api/v1/awards/search?q=209407-00002")
    none_search = pick("NONE", "/api/v1/awards/search?q=")
    caps = pick("DEPT", "/api/v1/me/capabilities")
    j = "application/json"
    cases = [
        (c_summary, 404, j, '{"code":"NOT_FOUND"}', True),
        (c_summary, 404, j, '{"code":"NOT_FOUND","title":"Title C"}', False),   # detail leak
        (c_summary, 200, "text/html", "<html>", False),                          # wrong page (UI index.html)
        (c_search, 200, j, '{"content":[]}', True),
        (c_search, 200, j, '{"content":[{"awardNumber":"209407-00002"}]}', False),
        (none_search, 403, j, '{"code":"ACCESS_NOT_PROVISIONED"}', True),
        (none_search, 200, j, '{"content":[]}', False),                         # silent empty archive
        (caps, 200, j, '{"enforcementEnabled":true,"administrationWritesAvailable":false}', True),
        (caps, 200, j, '{"enforcementEnabled":false,"administrationWritesAvailable":true}', False),
    ]
    bad = 0
    for probe, s, ct, body, expect_pass in cases:
        ok = not judge(probe, s, ct, body)
        if ok != expect_pass:
            bad += 1
            print("SELF-TEST FAIL:", probe["path"], s, body)
    print("self-test", "PASS" if not bad else f"FAIL ({bad})", f"({len(cases)} cases)")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", default="plan", choices=["plan", "self-test", "capture-baseline", "run"])
    ap.add_argument("--execute", action="store_true", help="actually make HTTP calls (otherwise nothing is sent)")
    ap.add_argument("--base-url", help="DEV API origin, https only")
    ap.add_argument("--fixture", default="a8-baseline.json")
    ap.add_argument("--evidence", default="a8-evidence.json")
    ap.add_argument("--no-pi", action="store_true", help="omit research-staff probes (A6 option (b))")
    a = ap.parse_args()

    if a.mode == "self-test":
        return self_test()
    if a.mode == "plan" or not a.execute:
        fx = {"records": {k: dict(v, award_id=f"<{k}.awardId>") for k, v in RECORDS.items()},
              "c_tm_txn_id": "<C txn>", "c_tm_doc": "<C T&M doc>", "b_tm_txn_id": "<B txn>", "c_negotiation_id": "<C negotiation>"}
        for p in probes(fx, not a.no_pi):
            print(f"{p['role']:5} {p['method']:4} {p['path']:75} expect {sorted(p['status'])} "
                  f"{'code=' + p['code'] if p.get('code') else ''} [{p['criteria']}] {p['fix']}")
        if a.mode != "plan":
            print("\nDRY RUN: nothing was sent. Add --execute to run this mode.")
        return 0
    if not a.base_url:
        sys.exit("--base-url is required with --execute")
    guard_base(a.base_url)
    if a.mode == "capture-baseline":
        capture_baseline(a.base_url, a.fixture)
        return 0
    with open(a.fixture) as f:
        fx = json.load(f)
    return run(a.base_url, fx, not a.no_pi, a.evidence)


if __name__ == "__main__":
    sys.exit(main())
