# Phase 0 — correction specification for `securityRequirements.json`

Recorded 2026-11-11. **Specification only — not applied.**

`ui/src/features/qa/securityRequirements.json` is UI source that ships in the
deployed bundle, so editing it is a UI change. Instruction: *"Do not implement
API, database, UI, CI, or deployment changes yet"* and *"Record these decisions
in draft PR #135 only."* So this document holds the exact replacement text,
ready to apply as phase 0 on a one-line go-ahead. **Nothing in `ui/src` has
been touched.**

## Decisions recorded

| # | Decision |
|---|---|
| 1 | Replace outdated statements with currently verified facts |
| 2 | Add a dated correction note citing tests, CI evidence and documentation |
| 3 | Preserve original claims **in git history**, not by repeating them as current |
| 4 | Correct the inconsistent `testsPassed` flags |
| 5 | Extend consistency checks to catch status-versus-narrative contradictions |
| 6 | **SEC-008 unchanged** — its open business decision is still valid |
| 7 | Phases preserved: documentation accuracy → authenticated API → schema and dashboard |
| 8 | `V089` reserved for SEC-008, `V090` proposed for QA. Neither created nor applied |
| 9 | Table-by-table privileges, never blanket schema grants |
| 10 | Deployed-bundle audit uses **positive and negative** assertions |

---

## 1. SEC-004 — `requirements[3].tests`

**Replace with:**

> `IoAccessAgreementIntegrationTest` pins the two halves of IO access together —
> `AwardAccountNumberIoSql`, the list predicate a search or dashboard count
> runs, and `AwardAccountNumberIoResolver`, the single-record path opening a
> record by URL — exercising the **real** resolver and **real** SQL strategy
> against a throwaway PostgreSQL with the committed migrations applied. Six
> tests, including one asserting a live IO scope does reach rows, so the
> empty-scope case cannot pass vacuously. Acceptance-matrix case 14
> (`SecurityAcceptancePackIntegrationTest`) additionally drives a
> `GrantType.IO` grant on an account number through to a visibility decision.
> **Caveat: neither suite is currently routed in CI** — see the routing defect
> in `AUTHORIZATION_TESTING_STRATEGY.md` — so the coverage exists and passes
> but is not continuously verified. Deployment and enforcement remain off, so
> acceptance is still unverified.

**`status`: no change.** `testsPassed` is already `"yes"`; correcting the prose
removes the contradiction.

**One judgement for you.** `testsPassed: "yes"` is defensible — the tests pass
— but they are not in CI, so nothing re-proves it on each change. If you would
rather that read as `"partial"`, say so; I have left it alone because the
statement "tests passed" is true and the CI gap is now stated explicitly in the
prose beside it.

---

## 2. SEC-009 — `requirements[8].tests`

**Replace with:**

> `ArchiveReadOnlyPrivilegeIntegrationTest` (12 tests) and
> `ArchiveApiRoleIntegrationTest` (17 tests) assert the absence of a write path
> across the `archive` schema, and **both run in the CI PostgreSQL integration
> job**. `docs/security/ARCHIVE_API_DATABASE_ROLE.md` records table by table
> the privileges the `archive_api` role actually holds — `SELECT` on `archive`
> and nothing more, with writes confined to six `authz` tables and no `DELETE`
> anywhere. The read-only claim therefore rests on executed tests and a written
> privilege record, not on design, charter and code review alone.

## 3. SEC-009 — `requirements[8].acceptance`

**Replace with:**

> Evidence now exists: catalog-wide privilege assertions running in CI, plus a
> written record of the grants the API role holds. What remains outstanding is
> an agreed **acceptance criterion** — the threshold at which the business
> accepts SEC-009 as verified — rather than the evidence itself.

## 4. SEC-009 — `requirements[8].status`

| Field | From | To | Why |
|---|---|---|---|
| `testsPassed` | `"no"` | **`"yes"`** | 29 tests exist across two suites, both in the CI database job |

All other fields unchanged: `deployed: "no"`, `enforcementEnabled: "off"`,
`acceptanceVerified: "no"` remain correct — tests passing is not acceptance.

---

## 5. The dated correction note (requirement 2)

A new append-only array per requirement, rather than overloading `evidence`:

```json
"corrections": [
  {
    "correctedOn": "2026-11-11",
    "fields": ["tests", "acceptance", "status.testsPassed"],
    "whatChanged": "The tests and acceptance narratives understated the coverage that now exists, and status.testsPassed disagreed with the narrative beside it. Both now describe the verified position.",
    "citedEvidence": [
      "api/src/test/java/.../ArchiveReadOnlyPrivilegeIntegrationTest.java (12 tests)",
      "api/src/test/java/.../ArchiveApiRoleIntegrationTest.java (17 tests)",
      "both named in the .github/workflows/ci.yml PostgreSQL integration job",
      "docs/security/ARCHIVE_API_DATABASE_ROLE.md"
    ],
    "priorText": "see git history for this file; the superseded wording is not restated here"
  }
]
```

**Requirement 3 is honoured by that last field.** The note says *what* changed
and points at git history; it does not quote the inaccurate claim, which would
reproduce it in current status — the thing requirement 3 forbids. `git log -p`
on this file remains the record of what was believed and when.

---

## 6. Consistency checks (requirement 5)

Extend the existing UI-side consistency suite — `securityReadinessConsistency.test.mjs`
is the precedent — with rules comparing each requirement's **status flags
against its own narrative**. The existing suite compares *files*, which is why
it never caught this.

| Rule | Fails when |
|---|---|
| **R1** | `tests` asserts absence (`no automated coverage`, `no test`, `not covered`, `no coverage`) while `status.testsPassed == "yes"` |
| **R2** | `tests` names at least one `*Test`/`*IntegrationTest` class while `status.testsPassed == "no"` |
| **R3** | `acceptance` says no test case was supplied while `status.acceptanceVerified == "yes"` |
| **R4** | `status.codeImplemented == "no"` while `tests` names a test class |
| **R5** | `status.testsPassed == "yes"` without any identifiable test class named in `tests` |

R1 and R2 are the two that would have caught SEC-004 and SEC-009 respectively.
R5 is the stronger form: it requires a claim of passing tests to name them.

**Note:** this is distinct from the Q7 parity check, which is API-side and
compares the catalog JSON against the `V090` migration seed. Different files,
different job, both needed.

---

## 7. SEC-008 unchanged (requirement 6)

`requirements[7].acceptance` — *"No test case was supplied in the source
document. Acceptance cannot be agreed until the scope question below is
answered"* — stays exactly as written. `decisionApproved: "open"` is accurate;
the open business decision is real, and the statement is not stale.

Under R3 above this remains consistent, because `acceptanceVerified` is `"no"`.

---

## 8. Phases, migrations, privileges (requirements 7–9)

Unchanged from `QA_WORKSPACE_IMPLEMENTATION_PLAN.md` and restated for the
record: **phase 0** documentation accuracy (this spec), **phase 1**
authenticated API hardening with build-time imports removed, **phase 2+** the
`qa` schema and dashboard.

`V089` stays reserved for SEC-008's `authz.activity_audit` — specified in
`SEC_008_ACTIVITY_AUDIT.md`, which records it as *"not done anywhere"*, and
present on no branch. `V090` is proposed for the QA workspace. **Neither is
created nor applied.**

Privileges remain **table by table**. The blanket form is the trap
`SEC_008_ACTIVITY_AUDIT.md` already documents for `authz`: a role-script re-run
silently re-grants what a migration revoked.

---

## 9. Bundle audit — positive and negative assertions (requirement 10)

The audit must be unable to pass against the wrong artifact. Four parts, run in
order against the **deployed** bundle:

**Part A — baseline self-test, run BEFORE the change.** Assert the sampled
strings from all four JSON files **are** present. If this fails, the audit is
pointed at the wrong artifact or the sampling is broken, and the run is void.
This is the step that makes the later negative assertions meaningful.

**Part B — positive assertions, after the change.** The fetched artifact must
be a real application bundle: a known application string is present (a route
name, a component identifier), the response is JavaScript of plausible size,
and `index.html` resolved to an asset path rather than a 404 page.

**Part C — negative assertions, after the change.** Zero occurrences of: the
sampled long string literals from each of the four JSON files; `sha256:`; ECS
revision strings; and each of the four coverage-gap sentences.

**Part D — the data still works.** The authenticated endpoints return the
catalog and findings, and `/qa-status` renders from fetched data. Bundle size
recorded before and after.

Part A is the point of the design. A negative assertion that never ran reads
identically to a clean result — the same failure mode as a surefire run that
matched no tests and reported success, which happened once in this engagement
and was caught only by diffing declared against executed names.

---

## Not applied

No change to `ui/src`, the API, the database, CI or deployment. No migration
created. No Cognito group, grant or role. Thursday's authorization test frozen;
revision 91 and staged revision 92 untouched. PRs #131–#135 unmerged.
Production untouched, auditing OFF, V089 unapplied.

**Ready to apply as phase 0 on your go-ahead.**
