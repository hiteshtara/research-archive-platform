"""Renders the Award acceptance runs as a requirement-to-test matrix (Markdown).

Input: award-acceptance-default.json (+ award-acceptance-alternative.json if present).
Output: AWARD_ACCEPTANCE_MATRIX.md in the same directory.
"""
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

d = Path(sys.argv[1])
default = json.loads((d / "award-acceptance-default.json").read_text())
alt_path = d / "award-acceptance-alternative.json"
alternative = {r["case"]: r for r in json.loads(alt_path.read_text())} if alt_path.exists() else {}

REQS = {
    "1": "Central: all objects and functionality",
    "2": "Department: assigned department(s) only",
    "3": "Research Staff: directly listed contact only",
    "4": "Other Authorized Viewer: authorized IO(s) only",
    "5": "Add access (role, department, object, IO)",
    "6": "Remove access, and it is revoked",
    "7": "Unauthorized attempts are denied",
}
ORDER = ["FAIL", "NOT IMPLEMENTED", "POLICY DECISION NEEDED", "PASS"]


def primary_req(row):
    return row["req"].split(",")[0].strip()


counts = Counter(r["status"] for r in default)
lines = ["# Award authorization acceptance matrix (local SAML lab)", "",
         "Real Shibboleth login with fictional accounts, simulated Cognito, real archive enforcement.",
         "Requirement IDs 1-7 are from the Security Requirements tab; T-numbers from design rev 3, section 9.", "",
         "| Status | Rows |", "|---|---|"]
lines += [f"| {s} | {counts.get(s, 0)} |" for s in ORDER]
lines += ["", "Policy rows show the demo setting's result; the **Alternative** column is the same check with",
          "FAMILY_WIDE / LEAD_UNIT_WITH_DESCENDANTS / roles incl. KP (separate API run)." if alternative
          else "(alternative-policy run not present)", ""]

by_req = defaultdict(list)
for r in default:
    by_req[primary_req(r)].append(r)
for req in sorted(by_req, key=lambda x: (len(x), x)):
    rows = sorted(by_req[req], key=lambda r: (ORDER.index(r["status"]), r["case"]))
    c = Counter(r["status"] for r in rows)
    lines += [f"## Requirement {req}: {REQS.get(req, '')}", "",
              " · ".join(f"{s}: {c[s]}" for s in ORDER if c.get(s)), "",
              "| Status | Case | Design | User | Check | Expected | Actual | Alternative | Note |",
              "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        a = alternative.get(r["case"])
        alt = f"{a['status']}: {a['actual']}" if a and r["status"] == "POLICY DECISION NEEDED" else ""
        cells = [r["status"], r["case"], r["design"], r["user"], r["action"], r["expected"], r["actual"], alt, r["note"]]
        lines.append("| " + " | ".join(str(x).replace("|", "/")[:160] for x in cells) + " |")
    lines.append("")
(d / "AWARD_ACCEPTANCE_MATRIX.md").write_text("\n".join(lines))
print(f"matrix: {d / 'AWARD_ACCEPTANCE_MATRIX.md'}  {dict(counts)}")
