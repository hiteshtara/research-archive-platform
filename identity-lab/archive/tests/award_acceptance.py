"""Award authorization acceptance pass for the local SAML lab.

Every check signs in through the real Shibboleth IdP (fictional LDAP accounts),
gets tokens from the simulated Cognito, and calls the archive API with its real
enforcement. No identity selector and no mocked responses.

Each row is traced to Anthony's requirement IDs (1-7, Security Requirements tab)
and to the design's test IDs (T1-T33, authorization design rev 3 section 9), and
graded:
  PASS                    behaves as required, permitted actions with real content
  FAIL                    does not behave as required
  NOT IMPLEMENTED         path still closed for non-Central users (refused, not covered)
  POLICY DECISION NEEDED  depends on an unapproved choice; the demo setting is
                          reported and the alternative is tested separately

Usage: python award_acceptance.py [--policy default|alternative] [--out DIR]
"""
import argparse
import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

import lab_client as c

ROOT = Path(__file__).resolve().parents[3]
ADMIN = str(ROOT / "scripts" / "identity-lab" / "admin.sh")

PASSWORDS = {
    "lab-central": "Lab-Central-2026", "lab-dept": "Lab-Dept-2026", "lab-pat": "Lab-Pat-2026",
    "lab-io": "Lab-Io-2026", "lab-multi": "Lab-Multi-2026", "lab-nogrants": "Lab-Nogrants-2026",
    "lab-suspended": "Lab-Suspended-2026", "lab-stranger": "Lab-Stranger-2026", "lab-noattr": "Lab-Noattr-2026",
    "lab-kim-pi": "Lab-Kim-Pi-2026", "lab-kim-only": "Lab-Kim-Only-2026", "lab-kim-inactive": "Lab-Kim-Inactive-2026",
    "lab-kim-ambiguous": "Lab-Kim-Ambiguous-2026", "lab-rolodex": "Lab-Rolodex-2026",
}

# Synthetic fixture facts (api/src/test/resources/authz/synthetic-seed.sql + award-acceptance-fixtures.sql).
VERSIONS = {  # award_id: (award_number, seq, unit, title fragment)
    9000101: ("990001-00001", 1, "SYN-U-100", "Award A - original"),
    9000102: ("990001-00001", 2, "SYN-U-100", "Award A - PI is Pat"),
    9000103: ("990001-00001", 2, "SYN-U-200", "Award A - other-unit"),
    9000111: ("990001-00002", 1, "SYN-U-100", "Award A child"),
    9000201: ("990002-00001", 1, "SYN-U-200", "Award B"),
    9000301: ("990003-00001", 1, "SYN-U-300", "Award C - Pat"),
    9000302: ("990003-00001", 1, "SYN-U-200", "Award C - other-unit"),
    9000401: ("990004-00001", 1, "SYN-U-300", "Award D"),
    9000501: ("990005-00001", 1, "SYN-U-300", "Award E"),
    9000601: ("990006-00001", 1, "SYN-U-400", "Award F"),
    9000701: ("990007-00001", 1, "SYN-U-110", "Award G"),
    9000801: ("990008-00001", 1, "SYN-U-200", "Award H"),
    9000901: ("990009-00001", 1, "SYN-U-200", "Award I - seq 1"),
    9000902: ("990009-00001", 2, "SYN-U-100", "Award I - seq 2"),
    9001001: ("990010-00001", 1, "SYN-U-300", "Award J"),
}
ATTACHMENTS = {9000101: 9301003, 9000102: 9300001, 9000111: 9301004, 9000201: 9300002, 9000902: 9301009,
               9000103: 9300003}
ALL = set(VERSIONS)

# Visible award_ids per user under the APPROVED policy (Hitesh, 2026-10-01): P3 FAMILY_WIDE within the
# same Award number (never children or related records), P6 sub-units only when the grant's flag is set,
# P4 PI/MPI/COI (Key Person excluded).
DEMO_VISIBLE = {
    "lab-central": ALL,
    "lab-dept": {9000101, 9000102, 9000103, 9000111, 9000701, 9000901, 9000902},   # A family, A child (own unit), G (sub-unit, flag set), I family
    "lab-pat": {9000101, 9000102, 9000103, 9000301, 9000302, 9000401, 9000901, 9000902},   # A, C, D, I families; not E (KP), not A child
    "lab-io": {9000601, 9000901, 9000902},                                  # account SYN-IO-7001: F, I family
    "lab-multi": {9000301, 9000302, 9000401, 9000501, 9000801, 9001001},    # UNIT SYN-U-300 (no sub-unit flag) + IO SYN-IO-7002
    "lab-kim-pi": {9001001},                                                # KIM principal is J's PI; NO grant rows
}
# Visible under the PREVIOUS demo settings (PER_VERSION, EXACT_LEAD_UNIT, PI/MPI/COI), run for comparison.
ALT_VISIBLE = {
    "lab-central": ALL,
    "lab-dept": {9000101, 9000102, 9000111, 9000902},
    "lab-pat": {9000101, 9000102, 9000301, 9000401, 9000902},
    "lab-io": {9000601, 9000902},
    "lab-multi": {9000301, 9000401, 9000501, 9000801, 9001001},
    "lab-kim-pi": {9001001},
}
ATTACHMENT_GROUP = {"lab-central", "lab-dept", "lab-io", "lab-multi", "lab-pat", "lab-kim-pi"}
WITHOUT_GROUP = {"lab-pat", "lab-kim-pi"}
GRANT_KIND = {"lab-central": "CENTRAL", "lab-dept": "DEPARTMENT", "lab-pat": "RESEARCH_STAFF",
              "lab-io": "OTHER_AUTHORIZED_VIEWER", "lab-multi": "DEPARTMENT + OTHER_AUTHORIZED_VIEWER"}
REQ = {"lab-central": "1", "lab-dept": "2", "lab-pat": "3", "lab-io": "4", "lab-multi": "2, 4 (union)",
       "lab-kim-pi": "3"}
NOT_SCOPED = "NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION"


@dataclass
class Row:
    case: str
    req: str
    design: str
    user: str
    action: str
    expected: str
    actual: str
    status: str
    note: str = ""


@dataclass
class Run:
    policy: str
    rows: list = field(default_factory=list)
    tokens: dict = field(default_factory=dict)

    def token(self, user, fresh=False):
        if fresh or user not in self.tokens:
            self.tokens[user] = c.login(user, PASSWORDS[user])[0]["access_token"]
        return self.tokens[user]

    def add(self, *args, **kw):
        self.rows.append(Row(*args, **kw))


def get(token, path):
    return c.api(token, path)


def post(token, path, body):
    import requests
    return requests.post(c.API + path, json=body, timeout=60, headers={"Authorization": f"Bearer {token}"})


def admin(*args):
    subprocess.run([ADMIN, *args], check=True, capture_output=True)


def code_of(r):
    try:
        return r.json().get("code")
    except Exception:
        return None


def leaks(r, award_ids):
    """True when a refused response discloses any protected fixture content."""
    text = r.content.decode("latin-1")
    if text.startswith("%PDF"):
        return True
    for aid in award_ids:
        number, seq, unit, title = VERSIONS[aid]
        if title in text:
            return True
    return False


def grade_permitted(r, ok, closed_is_not_implemented=True):
    if r.status_code == 403 and code_of(r) == NOT_SCOPED and closed_is_not_implemented:
        return "NOT IMPLEMENTED", "closed for non-Central (403 NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION)"
    if r.status_code == 200 and ok:
        return "PASS", "200 with expected synthetic content"
    return "FAIL", f"HTTP {r.status_code} {r.text[:120]!r}"


def grade_denied(r, protected):
    if r.status_code in (403, 404) and not leaks(r, protected):
        return "PASS", f"HTTP {r.status_code}, no protected content"
    return "FAIL", f"HTTP {r.status_code}; leak={leaks(r, protected)}"


def families(ids):
    return {VERSIONS[i][0] for i in ids}


# --------------------------------------------------------------- checks

def check_search(run, user, visible):
    t = run.token(user)
    r = get(t, "/api/v1/awards/search?q=SYNTHETIC&size=100")
    page = r.json()["results"]
    shown = {x["awardNumber"] for x in page["content"]}
    want = families({i for i in visible if VERSIONS[i][0] in families(visible) and _is_current_or_visible(i, visible)})
    want = _current_families(visible)
    status = "PASS" if shown == want and page["totalElements"] == len(want) else "FAIL"
    run.add(f"S1-{user}", REQ[user] + ", 7", "T1/T3/T26", user, "Award search: rows and totalElements",
            ", ".join(sorted(want)), f"{', '.join(sorted(shown))} (total {page['totalElements']})", status)

    # combined filters: q + leadUnit narrows inside the scope, never widens it
    r = get(t, "/api/v1/awards/search?q=SYNTHETIC&leadUnit=SYN-U-200&size=100")
    shown_u2 = {x["awardNumber"] for x in r.json()["results"]["content"]}
    want_u2 = {VERSIONS[i][0] for i in visible if VERSIONS[i][2] == "SYN-U-200" and _current_of(i)}
    want_u2 &= want
    run.add(f"S2-{user}", REQ[user] + ", 7", "T31", user, "Combined filters q + leadUnit=SYN-U-200 (tampering)",
            ", ".join(sorted(want_u2)) or "(none)", ", ".join(sorted(shown_u2)) or "(none)",
            "PASS" if shown_u2 == want_u2 else "FAIL")

    # pagination: size=1 pages agree with the total and never repeat or leak
    seen, total, page_no = [], None, 0
    while True:
        r = get(t, f"/api/v1/awards/search?q=SYNTHETIC&size=1&page={page_no}")
        res = r.json()["results"]
        total = res["totalElements"]
        seen += [x["awardNumber"] for x in res["content"]]
        if res.get("last", True) or not res["content"] or page_no > 20:
            break
        page_no += 1
    ok = len(seen) == total == len(set(seen)) and set(seen) == want
    run.add(f"S3-{user}", REQ[user] + ", 7", "T3", user, "Pagination size=1 over all pages",
            f"{len(want)} distinct", f"{len(seen)} rows, total {total}", "PASS" if ok else "FAIL")

    # historical (version) search
    r = get(t, "/api/v1/awards/versions/search?q=SYNTHETIC&size=100")
    ids = {x["awardId"] for x in r.json()["content"]}
    run.add(f"S4-{user}", REQ[user] + ", 7", "T6/T8/T14", user, "Historical Award (version) search",
            ", ".join(map(str, sorted(visible))), ", ".join(map(str, sorted(ids))),
            "PASS" if ids == visible else "FAIL",
            "per-version rows; POLICY P3 decides other versions" if user != "lab-central" else "")

    # Global Search and dashboard counts
    g = get(t, "/api/global-search?query=SYNTHETIC").json()
    text = json.dumps(g.get("results", []))
    hidden = families(ALL) - families(visible)
    leaked = sorted(n for n in hidden if n in text)
    run.add(f"S5-{user}", REQ[user] + ", 7", "T26", user, "Global Search: no out-of-scope Award",
            "none of " + (", ".join(sorted(hidden)) or "(n/a)"), ", ".join(leaked) or "none",
            "PASS" if not leaked else "FAIL")
    d = get(t, "/api/dashboard").json()
    run.add(f"S6-{user}", REQ[user] + ", 7", "T1/T23", user, "Dashboard Award count",
            str(len(want)), str(d.get("awards")), "PASS" if d.get("awards") == len(want) else "FAIL")


# is_current_version = TRUE in the fixtures (A' 9000103 shares A's sequence 2 but is NOT current).
CURRENT = {9000102, 9000111, 9000201, 9000301, 9000401, 9000501, 9000601, 9000701, 9000801, 9000902, 9001001}


def _current_of(aid):
    return aid in CURRENT


def _current_families(visible):
    # Award search shows one current row per family, among the versions the user may see.
    out = set()
    for number in families(visible):
        out.add(number)
    return out


def _is_current_or_visible(aid, visible):
    return True


def check_records(run, user, visible):
    t = run.token(user)
    group = user in ATTACHMENT_GROUP
    for aid, (number, seq, unit, title) in VERSIONS.items():
        allowed = aid in visible
        r = get(t, f"/api/v1/awards/{aid}/summary")
        if allowed:
            st, note = grade_permitted(r, title.split(" - ")[0] in r.text or number in r.text)
        else:
            st, note = grade_denied(r, {aid})
        run.add(f"R1-{user}-{aid}", REQ[user] + ", 7", "T4/T8/T13/T15", user,
                f"Direct URL /awards/{aid} ({number} seq {seq})", "allowed" if allowed else "denied", note, st,
                _policy_note(user, aid))
    # by-number and hierarchy
    for number in sorted(families(ALL)):
        allowed = any(VERSIONS[i][0] == number for i in visible)
        r = get(t, f"/api/v1/awards/by-number/{number}")
        st, note = (grade_permitted(r, number in r.text) if allowed
                    else grade_denied(r, {i for i in ALL if VERSIONS[i][0] == number}))
        run.add(f"R2-{user}-{number}", REQ[user] + ", 7", "T4", user, f"By-number URL {number}",
                "allowed" if allowed else "denied", note, st)
    r = get(t, "/api/v1/awards/990001-00001/hierarchy")
    if any(VERSIONS[i][0] == "990001-00001" for i in visible):
        child_visible = 9000111 in visible
        children = r.json()["root"]["children"] if r.status_code == 200 else None
        ok = r.status_code == 200 and (len(children) == 1) == child_visible
        run.add(f"H1-{user}", REQ[user] + ", 7", "T30", user, "Hierarchy of A: child node only if in scope",
                "child shown" if child_visible else "child omitted", f"{r.status_code}, children={children and len(children)}",
                "PASS" if ok else "FAIL")
    # related records from A seq 2 (if visible)
    if 9000102 in visible:
        for sub, central_count in (("funding-proposals", 1), ("negotiations", 1), ("funding-subawards", 1)):
            r = get(t, f"/api/v1/awards/9000102/{sub}")
            n = len(r.json()) if r.status_code == 200 else None
            want = central_count if user == "lab-central" else 0
            run.add(f"L1-{user}-{sub}", REQ[user] + ", 7", "T11", user, f"Related {sub} of A (not separately in scope)",
                    str(want), str(n), "PASS" if n == want else "FAIL",
                    "" if user == "lab-central" else "relationship never authorizes (ID 7, R8)")
        r = get(t, "/api/v1/awards/9000102/versions")
        ids = {x["awardId"] for x in r.json()["content"]} if r.status_code == 200 else set()
        want = {i for i in visible if VERSIONS[i][0] == "990001-00001"}
        run.add(f"V1-{user}", REQ[user] + ", 7", "T6/T8", user, "Version list of A", str(sorted(want)), str(sorted(ids)),
                "PASS" if ids == want else "FAIL")
    # Award I: seq 2 may be visible while seq 1 is not (P3). Sections of seq 2 must not
    # carry seq 1 rows unless seq 1 is visible too.
    if 9000902 in visible:
        hide = 9000901 not in visible
        for sub, marker in (("comments", "award_id 9000901"), ("amounts", '"awardId":9000901'),
                            ("time-and-money/history", '"awardId":9000901')):
            r = get(t, f"/api/v1/awards/9000902/{sub}")
            shown = marker in r.text.replace(" ", "")
            ok = r.status_code == 200 and (not shown if hide else True)
            run.add(f"C1-{user}-{sub}", REQ[user] + ", 7", "T6/T8/T14", user,
                    f"I seq 2 {sub}: rows of I seq 1", "omitted" if hide else "may show (seq 1 visible)",
                    f"{r.status_code}, seq 1 rows {'shown' if shown else 'absent'}", "PASS" if ok else "FAIL",
                    "approved P3: family-wide" if user != "lab-central" else "")
    # Time and Money lookups through a permitted Award must not reach another Award (proven leak, fixed).
    if 9000102 in visible and 9000201 not in visible:
        for sub in ("time-and-money/documents/SYN-TNM-90002-00001", "time-and-money/transactions/9700002"):
            r = get(t, f"/api/v1/awards/9000102/{sub}")
            leaked = "990002-00001" in r.text
            st = "PASS" if r.status_code in (403, 404) and not leaked else "FAIL"
            run.add(f"T1-{user}-{sub.split('/')[1]}", "3, 7" if user == "lab-pat" else REQ[user] + ", 7", "T31", user,
                    f"Award B's T&M {sub.split('/')[1][:-1]} via Award A's URL", "denied, nothing of B",
                    f"{r.status_code}{', B disclosed' if leaked else ''}", st)


def _policy_note(user, aid):
    if aid in (9000901, 9000103, 9000302) and user in ("lab-pat", "lab-dept", "lab-io"):
        return "approved P3: family-wide within the Award number"
    if aid == 9000701 and user == "lab-dept":
        return "approved P6: sub-unit because the grant's include-sub-units flag is set"
    if aid == 9000501 and user == "lab-pat":
        return "approved P4: Key Person excluded"
    return ""


def check_reports_and_attachments(run, user, visible):
    t = run.token(user)
    group = user in ATTACHMENT_GROUP
    for aid in sorted(ATTACHMENTS):
        allowed = aid in visible
        att = ATTACHMENTS[aid]
        r = get(t, f"/api/v1/awards/{aid}/report.pdf")
        st, note = (grade_permitted(r, r.content.startswith(b"%PDF") and len(r.content) > 5000) if allowed
                    else grade_denied(r, {aid}))
        run.add(f"P1-{user}-{aid}", REQ[user] + ", 7", "T1/T29", user, f"Report PDF for {aid}",
                "allowed" if allowed else "denied", note, st)
        r = get(t, f"/api/v1/awards/{aid}/report-with-attachments.pdf")
        exp_ok = allowed and group
        if allowed and not group:
            st, note = (("PASS", f"HTTP {r.status_code}: attachment permission required")
                        if r.status_code == 403 and code_of(r) != NOT_SCOPED and not leaks(r, {aid})
                        else grade_permitted(r, False))
        elif exp_ok:
            st, note = grade_permitted(r, r.content.startswith(b"%PDF") and len(r.content) > 5000)
        else:
            st, note = grade_denied(r, {aid})
        run.add(f"P2-{user}-{aid}", REQ[user] + ", 7", "T27/T28/T29", user, f"Report with attachments for {aid}",
                "allowed" if exp_ok else ("403 needs ArchiveAttachmentViewer" if allowed else "denied"), note, st)
        r = get(t, f"/api/v1/awards/{aid}/attachments")
        if allowed and group:
            ids = [x["attachmentId"] if "attachmentId" in x else x.get("awardAttachmentId") for x in r.json()["content"]]
            st, note = ("PASS", f"lists {ids}") if r.status_code == 200 and ids == [att] else ("FAIL", f"{r.status_code} {ids}")
        elif allowed:
            st, note = ("PASS", "403 attachment permission") if r.status_code == 403 else ("FAIL", str(r.status_code))
        else:
            st, note = grade_denied(r, {aid})
        run.add(f"A1-{user}-{aid}", REQ[user] + ", 7", "T27/T28", user, f"Attachment list for {aid}",
                "list" if allowed and group else ("403" if allowed else "denied"), note, st)
        r = get(t, f"/api/v1/awards/{aid}/attachments/{att}/download")
        if allowed and group:
            st, note = grade_permitted(r, r.content.startswith(b"%PDF") and b"FICTIONAL" in r.content)
        elif allowed:
            st, note = ("PASS", "403 attachment permission") if r.status_code == 403 and not r.content.startswith(b"%PDF") else ("FAIL", str(r.status_code))
        else:
            st, note = grade_denied(r, {aid})
        run.add(f"A2-{user}-{aid}", REQ[user] + ", 7", "T27/T28", user, f"Attachment view/download {att} on {aid}",
                "PDF" if allowed and group else ("403" if allowed else "denied"), note, st)


def check_other_surfaces(run, user, visible):
    t = run.token(user)
    group = user in ATTACHMENT_GROUP
    hidden_numbers = families(ALL) - families(visible)
    # Archived File Finder (/api/v1/attachments/search) by Award number
    for number in ("990001-00001", "990002-00001"):
        allowed = number in families(visible)
        r = get(t, f"/api/v1/attachments/search?recordType=AWARD&recordNumber={number}&versionFilter=all&size=50")
        protected = {i for i in ALL if VERSIONS[i][0] == number}
        want_ids = sorted(ATTACHMENTS[i] for i in protected if i in visible and i in ATTACHMENTS)
        if allowed and group:
            got = sorted(int(x.get("attachmentId") or x.get("awardAttachmentId") or 0) for x in r.json().get("content", [])) \
                if r.status_code == 200 else None
            st, note = (("PASS", f"attachments {got}") if got == want_ids
                        else grade_permitted(r, False) if r.status_code != 200 else ("FAIL", f"got {got}, want {want_ids}"))
        elif allowed:
            st, note = ("PASS", "403 attachment permission") if r.status_code == 403 and code_of(r) != NOT_SCOPED \
                else grade_permitted(r, False)
        else:
            empty = r.status_code == 200 and not r.json().get("content")
            st, note = (("PASS", "no results") if empty and not leaks(r, protected) else grade_denied(r, protected))
        run.add(f"F1-{user}-{number}", REQ[user] + ", 7", "T28", user, f"Archived File Finder for {number}",
                f"attachments {want_ids}" if allowed and group else ("403" if allowed else "nothing"), note, st)
    # Explorer
    for number in ("990001-00001", "990002-00001"):
        allowed = number in families(visible)
        r = get(t, f"/api/v1/explorer/awards?awardNumber={number}")
        if allowed and group:
            st, note = grade_permitted(r, number in r.text)
        elif allowed:
            st, note = ("PASS", "403 attachment permission") if r.status_code == 403 and code_of(r) != NOT_SCOPED else grade_permitted(r, False)
        else:
            st, note = grade_denied(r, {i for i in ALL if VERSIONS[i][0] == number})
        run.add(f"X1-{user}-{number}", REQ[user] + ", 7", "T28", user, f"Explorer for {number}",
                "allowed" if allowed and group else ("403" if allowed else "denied"), note, st)
    # AI summary (deterministic stub provider). The AI context spans the whole family, so it is
    # allowed only when EVERY version is visible (P3-dependent); otherwise 403 partial access.
    for number in ("990001-00001", "990002-00001", "990006-00001"):
        family = {i for i in ALL if VERSIONS[i][0] == number}
        current_visible = number in families(visible) and any(
            i in visible and _current_of(i) for i in family)
        every = family <= visible
        r = post(t, f"/api/ai/awards/{number}/summary", None)
        if every:
            st, note = grade_permitted(r, r.status_code == 200)
            exp = "allowed"
        elif current_visible:
            exp = "403 AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS"
            ok = r.status_code == 403 and code_of(r) == "AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS" and not leaks(r, family - visible)
            st, note = ("PASS", exp) if ok else ("FAIL", f"HTTP {r.status_code} {code_of(r)}")
        else:
            exp = "denied"
            st, note = grade_denied(r, family)
        run.add(f"I1-{user}-{number}", REQ[user] + ", 7", "T25", user, f"AI summary for {number}",
                exp, note, st, "POLICY P3 (AI only with every version visible)" if current_visible and not every else "")


def check_denied_identities(run):
    cases = [("lab-nogrants", "6, 7", "T23", "no grants", "ACCESS_NOT_PROVISIONED"),
             ("lab-stranger", "7", "T23", "unknown mapping", "ACCESS_NOT_PROVISIONED"),
             ("lab-suspended", "6, 7", "T22", "suspended", "ACCESS_DENIED")]
    for user, req, design, label, want in cases:
        t = run.token(user)
        results = []
        for path in ("/api/v1/awards/search?q=SYNTHETIC", "/api/v1/awards/9000102/summary",
                     "/api/v1/awards/9000102/report.pdf", "/api/v1/awards/9000102/attachments/9300001/download",
                     "/api/global-search?query=SYNTHETIC", "/api/dashboard"):
            r = get(t, path)
            results.append(r.status_code == 403 and code_of(r) == want and not leaks(r, ALL))
        run.add(f"N1-{user}", req, design, user, f"{label}: search, record, report, download, global, dashboard",
                f"403 {want} everywhere", f"{sum(results)}/{len(results)} refused", "PASS" if all(results) else "FAIL")
    try:
        c.login("lab-noattr", PASSWORDS["lab-noattr"])
        run.add("N2-lab-noattr", "7", "T23", "lab-noattr", "Missing identifier attribute", "sign-in refused",
                "signed in", "FAIL")
    except c.SignInFailed:
        run.add("N2-lab-noattr", "7", "T23", "lab-noattr", "Missing identifier attribute", "sign-in refused",
                "IdP InvalidNameIDPolicy; no token", "PASS")
    # revoked mapping (identity link revoked by an administrator)
    t = run.token("lab-central", fresh=True)
    admin("revoke-link", "SYN-INST-0001")
    try:
        r = get(t, "/api/v1/awards/9000102/summary")
        t2 = run.token("lab-central", fresh=True)
        r2 = get(t2, "/api/v1/awards/9000102/summary")
        ok = r.status_code == 403 and r2.status_code == 403 and not leaks(r, ALL)
        run.add("N3-revoked", "6, 7", "T21", "lab-central", "Revoked identity link: existing session and fresh sign-in",
                "403 both", f"{r.status_code} {code_of(r)} / {r2.status_code} {code_of(r2)}", "PASS" if ok else "FAIL")
    finally:
        admin("restore-link", "SYN-INST-0001")
        run.tokens.pop("lab-central", None)


def check_grant_changes(run):
    # ID 5 add, ID 6 remove; the user keeps the SAME access token throughout.
    t = run.token("lab-nogrants", fresh=True)
    before = get(t, "/api/v1/awards/9000201/summary").status_code
    admin("add-grant", "SYN-INST-0007", "UNIT", "SYN-U-200")
    try:
        added = get(t, "/api/v1/awards/9000201/summary")
        unrelated = get(t, "/api/v1/awards/9000701/summary").status_code       # G: SYN-U-110, not granted
        via_old_version = get(t, "/api/v1/awards/9000102/summary").status_code  # A: an OLD version (A') was in SYN-U-200
        run.add("G1-add", "5", "T19/T17", "lab-nogrants", "Administrator adds UNIT SYN-U-200; same session",
                "B allowed, G denied", f"B {added.status_code}, G {unrelated} (before: {before})",
                "PASS" if before == 403 and added.status_code == 200 and "Award B" in added.text and unrelated == 404
                else "FAIL")
        run.add("G1b-family-wide-unit-history", "2", "T6", "lab-nogrants",
                "Same grant: Award A, whose OLD version A' was in SYN-U-200",
                "allowed (approved P3 family-wide: any version opens the Award number)", f"A {via_old_version}",
                "PASS" if via_old_version == 200 else "FAIL",
                "approved P3 consequence: a unit that ever led any version sees the whole Award (not its children)")
    finally:
        admin("remove-grant", "SYN-INST-0007", "UNIT")
    after = get(t, "/api/v1/awards/9000201/summary")
    run.add("G2-remove", "6", "T18/T21", "lab-nogrants", "Administrator removes that grant; same session",
            "denied again", f"{after.status_code} {code_of(after)}",
            "PASS" if after.status_code == 403 and not leaks(after, ALL) else "FAIL")
    # overlapping grants: removing the IO keeps the unit (T17)
    t = run.token("lab-multi", fresh=True)
    admin("revoke-grant", "SYN-INST-0006", "IO")
    try:
        h = get(t, "/api/v1/awards/9000801/summary").status_code
        cc = get(t, "/api/v1/awards/9000301/summary").status_code
        run.add("G3-overlap", "6", "T17", "lab-multi", "Remove IO grant of a multi-grant user; same session",
                "H denied, C (unit) still allowed", f"H {h}, C {cc}", "PASS" if h in (403, 404) and cc == 200 else "FAIL")
    finally:
        admin("restore-grant", "SYN-INST-0006", "IO")


def sql(query):
    out = subprocess.run(["docker", "exec", "lab-archive-db", "psql", "-U", "lab_archive", "-d", "identity_lab",
                          "-Atc", query], check=True, capture_output=True, text=True)
    return out.stdout.strip()


def enrollment_outcome(inst):
    """Latest enrollment decision audited by the API; inst=None for refusals that record no identifier."""
    where = f"AND institutional_identifier = '{inst}'" if inst else ""
    return sql(f"SELECT detail->>'outcome' FROM authz.access_audit WHERE action LIKE 'ENROLLMENT_%' {where} "
               "ORDER BY audit_id DESC LIMIT 1")


def check_kim_chain(run):
    """Verified sign-in -> unique KIM principal -> contact PERSON_ID -> rule 3, with no grant rows."""
    grants = sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier IN ('SYN-INST-0011', 'SYN-INST-0003') "
                 "AND revoked_at IS NULL AND grant_type = 'CONTACT_DERIVATION'")
    pat_grants = sql("SELECT count(*) FROM authz.access_grant WHERE institutional_identifier = 'SYN-INST-0011'")
    run.add("K1-no-grant-rows", "3", "T8/T9", "lab-kim-pi, lab-pat", "Contact access with NO access_grant rows",
            "0 grant rows for lab-kim-pi; no CONTACT_DERIVATION rows", f"{pat_grants} / {grants}",
            "PASS" if pat_grants == "0" and grants == "0" else "FAIL",
            "access derives from the verified KIM mapping (contact-derivation VERIFIED_PRINCIPAL)")
    link = sql("SELECT kuali_person_id || ' ' || method FROM authz.identity_link WHERE institutional_identifier = "
               "'SYN-INST-0011' AND status = 'ACTIVE' AND cognito_issuer LIKE 'https://localhost:9443/%'")
    run.add("K2-link", "3", "T8", "lab-kim-pi", "Identity link carries the KIM principal",
            "SYNP-KIM-11 AUTO_VERIFIED", link, "PASS" if link == "SYNP-KIM-11 AUTO_VERIFIED" else "FAIL")
    for user, inst, outcome, label in (
            ("lab-kim-only", "SYN-INST-0012", "LINKED", "KIM account that is nobody's contact"),
            ("lab-kim-inactive", "SYN-INST-0013", "REFUSED_INACTIVE_PRINCIPAL", "principal departed after import"),
            ("lab-kim-ambiguous", None, "REFUSED_UNKNOWN_PERSON", "ambiguous mapping (rejected at import)"),
            ("lab-rolodex", None, "REFUSED_UNKNOWN_PERSON", "rolodex id (rejected at import)"),
            ("lab-stranger", None, "REFUSED_UNKNOWN_PERSON", "no crosswalk row")):
        t = run.token(user, fresh=True)
        r = get(t, "/api/v1/awards/search?q=SYNTHETIC")      # the first request enrolls
        got = enrollment_outcome(inst)
        ok = got in outcome.split("|") and r.status_code == 403 and code_of(r) == "ACCESS_NOT_PROVISIONED" and not leaks(r, ALL)
        run.add(f"K3-{user}", "3, 7", "T23", user, f"{label}: no archive access",
                f"enrollment {outcome}; 403 ACCESS_NOT_PROVISIONED", f"enrollment {got}; {r.status_code} {code_of(r)}",
                "PASS" if ok else "FAIL")
    # The import validator refuses the ambiguous and rolodex rows (all-or-nothing).
    fx = ROOT / "identity-lab" / "archive" / "fixtures"
    v = subprocess.run(["python3", str(ROOT / "scripts" / "authz-admin" / "validate_crosswalk.py"),
                        str(fx / "kim_principals.tsv"), str(fx / "principal_crosswalk_rejected.tsv"),
                        "--attribute", "labInstitutionalId", "--rolodex-ids", str(fx / "rolodex_ids.txt")],
                       capture_output=True, text=True)
    ok = (v.returncode == 1 and "maps to more than one principal" in v.stdout
          and "never a login account" in v.stdout)
    run.add("K5-import-validation", "3, 7", "T20", "(admin)", "Crosswalk import rejects ambiguous and rolodex rows",
            "REJECTED (ambiguous, rolodex)", "REJECTED" if ok else v.stdout[-120:], "PASS" if ok else "FAIL")
    # The KIM mapping is revoked (crosswalk row): the next request revokes the link; access denied.
    admin("revoke-crosswalk", "SYN-INST-0011")
    try:
        t = run.token("lab-kim-pi", fresh=True)
        r = get(t, "/api/v1/awards/9001001/summary")
        got = enrollment_outcome("SYN-INST-0011")
        ok = got == "REVOKED_MAPPING_NO_LONGER_VALID" and r.status_code == 403 and code_of(r) == "ACCESS_DENIED"
        run.add("K4-revoked-mapping", "6, 7", "T21", "lab-kim-pi", "Crosswalk row revoked: next sign-in",
                "link revoked; 403 ACCESS_DENIED", f"enrollment {got}; {r.status_code} {code_of(r)}", "PASS" if ok else "FAIL")
    finally:
        admin("restore-crosswalk", "SYN-INST-0011")
        admin("restore-link", "SYN-INST-0011")
        sql("UPDATE authz.identity_link SET status = 'ACTIVE', revoked_by = NULL, revoked_at = NULL "
            "WHERE institutional_identifier = 'SYN-INST-0011' AND revoked_by LIKE 'lab-enrollment%'")
        run.tokens.pop("lab-kim-pi", None)


def check_files_without_group(run):
    """Approved policy: an authorized user WITHOUT the attachment group gets the record's files."""
    for user in sorted(WITHOUT_GROUP):
        t = run.token(user, fresh=True)
        import base64 as _b
        claims = json.loads(_b.urlsafe_b64decode(t.split(".")[1] + "=="))
        no_group = "ArchiveAttachmentViewer" not in claims.get("cognito:groups", [])
        run.add(f"Z1-{user}", "3, 7", "T27 (revised)", user, "Token carries no ArchiveAttachmentViewer group",
                "no group", "no group" if no_group else "group present", "PASS" if no_group else "FAIL",
                "approved 2026-10-01: record access covers its files")


def check_approved_boundaries(run):
    """Approved policy boundaries: family-wide never reaches children or related records;
    sub-units only by the grant's flag; revocation removes access unless another basis remains."""
    t = run.token("lab-pat", fresh=True)
    for label, path, ok in (
            ("A's child Award (not inherited)", "/api/v1/awards/9000111/summary", lambda r: r.status_code == 404),
            ("A's related Proposal SYN-PRP-0001", "/api/proposals/SYN-PRP-0001", lambda r: r.status_code == 404),
            ("A's Negotiations list", "/api/v1/awards/9000102/negotiations", lambda r: r.status_code == 200 and r.json() == []),
            ("A's Subawards list", "/api/v1/awards/9000102/funding-subawards", lambda r: r.status_code == 200 and r.json() == []),
            ("A's funding Proposals list", "/api/v1/awards/9000102/funding-proposals", lambda r: r.status_code == 200 and r.json() == []),
            ("parent Award B of D", "/api/v1/awards/9000201/summary", lambda r: r.status_code == 404),
            ("D's hierarchy does not reveal parent B", "/api/v1/awards/990004-00001/hierarchy",
             lambda r: r.status_code == 200 and "990002-00001" not in json.dumps({k: v for k, v in r.json().items() if k != "path"}))):
        r = get(t, path)
        run.add(f"B1-{label[:24].replace(' ', '-')}", "3, 7", "T11/T30", "lab-pat",
                f"Family-wide on A/D: {label}", "denied / omitted", f"{r.status_code}", "PASS" if ok(r) else "FAIL",
                "approved: family-wide never extends to children or related records")
    # A grant WITHOUT the include-sub-units flag stays exact (P6).
    t = run.token("lab-dept", fresh=True)
    sql("UPDATE authz.access_grant SET include_descendants = FALSE WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'")
    try:
        off = get(t, "/api/v1/awards/9000701/summary").status_code
    finally:
        sql("UPDATE authz.access_grant SET include_descendants = TRUE WHERE institutional_identifier = 'SYN-INST-0002' AND grant_type = 'UNIT'")
    on = get(t, "/api/v1/awards/9000701/summary").status_code
    run.add("B2-subunit-flag", "2", "T7", "lab-dept", "Sub-unit Award G with the grant flag off, then on",
            "404 then 200", f"{off} then {on}", "PASS" if (off, on) == (404, 200) else "FAIL",
            "approved P6: sub-units only by explicit grant flag")
    # Revocation removes access unless another valid basis remains.
    t = run.token("lab-pat", fresh=True)
    admin("add-grant", "SYN-INST-0003", "UNIT", "SYN-U-300")
    try:
        added = {a: get(t, f"/api/v1/awards/{a}/summary").status_code for a in (9000501, 9001001, 9000301, 9000401)}
    finally:
        admin("remove-grant", "SYN-INST-0003", "UNIT")
    after = {a: get(t, f"/api/v1/awards/{a}/summary").status_code for a in (9000501, 9001001, 9000301, 9000401)}
    ok = (added == {9000501: 200, 9001001: 200, 9000301: 200, 9000401: 200}
          and after == {9000501: 404, 9001001: 404, 9000301: 200, 9000401: 200})
    run.add("G4-revoke-keeps-contact-basis", "5, 6", "T17/T18", "lab-pat",
            "Add UNIT SYN-U-300, then revoke it (same session)",
            "E,J,C,D open; after: E,J closed, C,D stay (contact basis)", f"{added} -> {after}", "PASS" if ok else "FAIL")
    t = run.token("lab-dept", fresh=True)
    admin("add-grant", "SYN-INST-0002", "IO", "SYN-IO-7001")
    admin("revoke-grant", "SYN-INST-0002", "UNIT")
    try:
        result = {a: get(t, f"/api/v1/awards/{a}/summary").status_code for a in (9000102, 9000601, 9000901, 9000902)}
    finally:
        admin("restore-grant", "SYN-INST-0002", "UNIT")
        admin("remove-grant", "SYN-INST-0002", "IO")
    ok = result == {9000102: 404, 9000601: 200, 9000901: 200, 9000902: 200}
    run.add("G5-revoke-unit-keeps-io-basis", "5, 6", "T17/T18", "lab-dept",
            "Add IO SYN-IO-7001, revoke the UNIT grant (same session)",
            "A closed; F and I (both versions) stay via IO", f"{result}", "PASS" if ok else "FAIL")


def check_unauthenticated(run):
    import requests
    r = requests.get(c.API + "/api/v1/awards/search?q=SYNTHETIC", timeout=30)
    run.add("U1-anonymous", "7", "T24", "(none)", "No token", "401", str(r.status_code),
            "PASS" if r.status_code == 401 else "FAIL")


def mark_policy(run):
    for row in run.rows:
        if row.note.startswith("POLICY") and row.status == "PASS":
            row.status = "POLICY DECISION NEEDED"
            row.note += f" - demo setting result shown; run with --policy alternative for the other choice"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--policy", choices=["default", "alternative"], default="default")
    ap.add_argument("--out", default=str(ROOT / ".identity-lab" / "acceptance"))
    args = ap.parse_args()
    visible = DEMO_VISIBLE if args.policy == "default" else ALT_VISIBLE
    run = Run(args.policy)
    for user in ("lab-central", "lab-dept", "lab-pat", "lab-io", "lab-multi", "lab-kim-pi"):
        check_search(run, user, visible[user])
        check_records(run, user, visible[user])
        check_reports_and_attachments(run, user, visible[user])
        check_other_surfaces(run, user, visible[user])
    if args.policy == "default":
        check_denied_identities(run)
        check_kim_chain(run)
        check_files_without_group(run)
        check_approved_boundaries(run)
        check_grant_changes(run)
        check_unauthenticated(run)
        mark_policy(run)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"award-acceptance-{args.policy}.json").write_text(json.dumps([r.__dict__ for r in run.rows], indent=1))
    counts = {}
    for r in run.rows:
        counts[r.status] = counts.get(r.status, 0) + 1
    print(json.dumps({"policy": args.policy, "rows": len(run.rows), **counts}))
    for r in run.rows:
        if r.status in ("FAIL", "NOT IMPLEMENTED"):
            print(f"{r.status:16} {r.case:28} {r.action[:58]:58} {r.actual[:70]}")


if __name__ == "__main__":
    main()
