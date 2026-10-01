"""Endpoint-by-endpoint authorization coverage for Award-related API paths (local SAML lab).

Real Shibboleth sign-in (fictional accounts), simulated Cognito, real API. For every endpoint:
  - permitted:  lab-pat (Research Staff, NO attachment group) on Award A (in scope)
  - forbidden:  lab-pat on Award B (out of scope)
  - central:    lab-central on Award A
Categories:
  VERIFIED     permitted works with real content AND forbidden is denied without disclosure
  UNAVAILABLE  refused to non-Central users by design (403 NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION);
               Central works. NOT counted as completed functionality.
  FAIL         anything else
  NOT TESTED   no meaningful request could be made here (listed with the reason)
"""
import json
import sys
from pathlib import Path

import requests

import lab_client as c

HERE = Path(__file__).parent
A_ID, A_NUM, B_ID, B_NUM = "9000102", "990001-00001", "9000201", "990002-00001"
A_ATT, B_ATT = "9300001", "9300002"
B_MARKERS = ("Award B", "990002-00001", "SYNTHETIC-award-B")
NOT_SCOPED = "NOT_AVAILABLE_UNDER_RECORD_AUTHORIZATION"

# What a non-Central user loses in the UI when a path is unavailable (from the UI code).
LOSS = {
    "/api/awards": "nothing in the UI (no UI caller; legacy routes)",
    "/api/v1/documents": "the Document Explorer page",
    "/api/documents/search": "the document search used by Document Explorer / Global document lookup",
    "/api/v1/explorer/workflows": "Explorer: workflow lookup tab",
    "/api/v1/explorer/units": "Explorer: unit lookup tab",
    "/api/v1/explorer/unit-administrators": "Explorer: unit administrators tab",
    "/api/v1/explorer/award-contacts": "Explorer: award contacts tab",
    "/api/v1/explorer/persons": "Explorer: person lookup tab",
    "/api/v1/explorer/rolodex": "Explorer: rolodex tab",
    "/api/v1/explorer/sponsors": "Explorer: sponsor lookup tab",
    "/api/v1/explorer/attachments": "Explorer: cross-record attachment lookup tab",
    "/api/v1/explorer/proposals": "Explorer: Proposal discovery page",
}

# Endpoints whose feature is not available in the lab at all (not an authorization result).
FEATURE_OFF = {
    "/api/ai/awards/{awardNumber}/evidence-search":
        "needs semantic search (an embedding provider); off in the lab - covered by the API integration tests",
}

QUERY = {
    "/api/v1/awards/search": "?q=SYNTHETIC&size=100",
    "/api/v1/awards/versions/search": "?q=SYNTHETIC&size=100",
    "/api/v1/explorer/awards": "?awardNumber={num}",
    "/api/v1/explorer/award-versions": "?awardId={id}",
    "/api/v1/attachments/search": "?recordType=AWARD&recordNumber={num}&versionFilter=all",
    "/api/v1/documents": "?q=SYNTHETIC",
    "/api/documents/search": "?q=SYNTHETIC",
    "/api/global-search": "?query={num}",
    "/api/v1/explorer/workflows": "?q=SYNTHETIC", "/api/v1/explorer/units": "?q=SYN",
    "/api/v1/explorer/unit-administrators": "?unitNumber=SYN-U-100", "/api/v1/explorer/award-contacts": "?awardNumber={num}",
    "/api/v1/explorer/persons": "?q=PAT", "/api/v1/explorer/rolodex": "?q=ROBIN", "/api/v1/explorer/sponsors": "?q=SYN",
    "/api/v1/explorer/attachments": "?recordNumber={num}", "/api/v1/explorer/proposals": "?q=SYN",
}


def fill(path, which):
    aid, num, att = (A_ID, A_NUM, A_ATT) if which == "A" else (B_ID, B_NUM, B_ATT)
    p = (path.replace("{awardId}", aid).replace("{awardNumber}", num).replace("{attachmentId}", att)
         .replace("{sequenceNumber}", "1").replace("{pendingTransactionId}", "9710001" if which == "A" else "9710003")
         .replace("{timeAndMoneyDocumentNumber}", "SYN-TNM-A1" if which == "A" else "SYN-TNM-B1"))
    return p + QUERY.get(path, "").replace("{num}", num).replace("{id}", aid)


def call(token, method, path):
    h = {"Authorization": f"Bearer {token}"}
    if method == "POST":
        return requests.post(c.API + path, headers=h, timeout=60,
                             json={"question": "What is the status?"} if path.endswith("/questions")
                             else ({"query": "budget"} if path.endswith("/evidence-search") else None))
    return requests.get(c.API + path, headers=h, timeout=60)


def code(r):
    try:
        return r.json().get("code")
    except Exception:
        return None


def discloses_b(r):
    """Protected content about Award B, ignoring the caller's own request echoed back
    (an error body's "path", a search response's "query")."""
    raw = r.content.decode("latin-1")
    if raw.startswith("%PDF"):
        return True
    try:
        body = r.json()
        if isinstance(body, dict):
            body = {k: v for k, v in body.items() if k not in ("path", "query")}
        raw = json.dumps(body)
    except Exception:
        pass
    return any(m in raw for m in B_MARKERS)


def main(out):
    pat = c.login("lab-pat", "Lab-Pat-2026")[0]["access_token"]
    central = c.login("lab-central", "Lab-Central-2026")[0]["access_token"]
    rows = []
    for method, template, ui in json.load(open(HERE / "endpoints.json")):
        a, b = fill(template, "A"), fill(template, "B")
        rp, rc_ = call(pat, method, a), call(central, method, a)
        rf = call(pat, method, b) if "{" in template or template in QUERY else None
        if template in FEATURE_OFF and rc_.status_code == 404:
            cat, detail = "NOT TESTED", FEATURE_OFF[template]
        elif rp.status_code == 403 and code(rp) == "AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS":
            ok_forbidden = rf is None or (rf.status_code in (403, 404) and not discloses_b(rf))
            cat = "POLICY (P3)" if ok_forbidden else "FAIL"
            detail = (f"refused: Award A has a version Pat may not see (AI context spans the family); "
                      f"forbidden {rf.status_code if rf is not None else '-'}; Central {rc_.status_code}")
        elif rp.status_code == 403 and code(rp) == NOT_SCOPED:
            cat = "UNAVAILABLE"
            prefix = next((k for k in LOSS if template.startswith(k)), None)
            detail = f"Central {rc_.status_code}; users lose: {LOSS.get(prefix, 'see notes')}" if ui else \
                f"Central {rc_.status_code}; no UI caller"
        elif rp.status_code == 200 and (rf is None or (rf.status_code in (200, 403, 404) and not discloses_b(rf))):
            cat = "VERIFIED"
            detail = f"permitted 200; forbidden {rf.status_code if rf is not None else 'n/a (no record parameter)'}" \
                     f"{' (empty/scoped)' if rf is not None and rf.status_code == 200 else ''}; Central {rc_.status_code}"
        elif rp.status_code in (400, 405) and rc_.status_code in (400, 405):
            cat = "NOT TESTED"
            detail = f"request shape not reproduced here (HTTP {rp.status_code} for Central too)"
        else:
            cat = "FAIL"
            detail = (f"permitted {rp.status_code} {code(rp)}; forbidden {rf.status_code if rf is not None else '-'}"
                      f"{' DISCLOSES B' if rf is not None and discloses_b(rf) else ''}; Central {rc_.status_code}")
        rows.append({"method": method, "endpoint": template, "ui": ui, "category": cat, "detail": detail})
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "endpoint-matrix.json").write_text(json.dumps(rows, indent=1))
    lines = ["# Award-related endpoint coverage (local SAML lab, real login, real API)", "",
             "Restricted user: lab-pat (Research Staff, PI on Award A, **no** ArchiveAttachmentViewer group). "
             "Permitted = Award A; forbidden = Award B. UNAVAILABLE is refusal by design, **not** completed functionality.", "",
             "| Category | Method | Endpoint | UI uses it | Result |", "|---|---|---|---|---|"]
    order = {"FAIL": 0, "NOT TESTED": 1, "POLICY (P3)": 2, "UNAVAILABLE": 3, "VERIFIED": 4}
    for r in sorted(rows, key=lambda r: (order[r["category"]], r["endpoint"])):
        lines.append(f"| {r['category']} | {r['method']} | `{r['endpoint']}` | {'yes' if r['ui'] else 'no'} | {r['detail']} |")
    from collections import Counter
    counts = Counter(r["category"] for r in rows)
    lines[3:3] = ["Counts: " + ", ".join(f"{k} {counts[k]}" for k in order if counts.get(k)), ""]
    (out / "ENDPOINT_COVERAGE.md").write_text("\n".join(lines) + "\n")
    print(json.dumps(dict(counts)))
    for r in rows:
        if r["category"] in ("FAIL", "NOT TESTED"):
            print(r["category"], r["method"], r["endpoint"], r["detail"])


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).resolve().parents[3] / ".identity-lab" / "acceptance"))
