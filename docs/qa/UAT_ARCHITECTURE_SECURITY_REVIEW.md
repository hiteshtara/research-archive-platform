# QA workspace — architecture and security review

Pre-implementation review, 2026-11-11. **Nothing implemented.** No migration,
schema, endpoint, UI change, IAM policy or role. Thursday's authorization test
is frozen and unchanged; Production untouched, auditing OFF, V089 unapplied.

**Verdict: the direction is sound and the data model holds.** Six findings were
raised; **Q1–Q7 were decided on 2026-11-11** and are recorded in §Decisions
resolved. The F1 audit requested with Q1 is complete and its result is in
§F1a — it **inverts the priority**: the catalog is the least sensitive of the
four exposed files.

## Decisions folded into the design

| Decision | How it lands |
|---|---|
| Screenshots by reference only | `screenshot_ref` is text; no S3 write path, no upload endpoint. Controls in **F2** |
| Separate `QA_LEAD`, not `ARCHIVE_ADMIN` | **F6** — Cognito group, following the `ArchiveAttachmentViewer` precedent |
| `/qa-status` permanent and authenticated | **F1** must be resolved for "authenticated" to be true |
| Retention 12 months after cycle closure, provisional | **F5** — not computable without a test-cycle concept; no deletion job designed |
| DEV-only first phase | Production needs separate approval. **F3** is the production blocker to carry forward |

John's IDs immutable, catalog in version control and results in `qa`, identity
from Cognito only, immutable history including superseded rows, CI parity
checks, untested modules including zero-case modules, and discovery separated
from attachment viewing — all unchanged from the approved design.

---

## F1 — "Authenticated workspace" is not currently true. **Blocker for decision 3**

**Severity: low for data, high for the claim.**

Verified 2026-11-11 with no credentials:

```
GET https://nonprod.kuali-archive.bu.edu/                    → 200
GET https://nonprod.kuali-archive.bu.edu/assets/index-*.js   → 200, 1,112,223 bytes
```

That bundle contains the **entire QA catalog** (TC-001…TC-052), the open
findings including TC-041, the catalog attribution, per-case environment
strings naming **ECS revisions and the deployed image digest
`sha256:f0f42ab6…`**, the internal schema vocabulary (`authz.`,
`access_grant`, `enforcement`), and the **`ArchiveAttachmentViewer` group
name**.

The *route* `/qa-status` is gated. The *data* is not: it is compiled into a
public artifact, so anyone who fetches the bundle reads it without a BU login.

**What is not exposed**, checked explicitly: no email address, no real
archive-issued identity (only the `ARP-XXXX…` placeholder pattern), no unit
number, no personal name, no secret. The Cognito pool ID is present and is
*necessarily* client-side for sign-in — not a leak.

So this is not a breach. It is a mismatch between what decision 3 asserts and
what the build does, plus unnecessary operational disclosure.

**Options:**

| | Approach | Cost |
|---|---|---|
| **A** | Serve the catalog from `GET /api/v1/qa/cases` behind authentication; remove the JSON from the bundle | the honest fix; makes "authenticated workspace" true. More work: the page becomes data-driven for the catalog as well as results |
| **B** | Keep the catalog in the bundle; drop digests and ECS revisions from the published `environment` strings | cheap, reduces disclosure, but the workspace is still not genuinely authenticated |
| **C** | Accept it; document that the catalog is public | no work; decision 3 must then be reworded |

**Recommendation: A**, since results are already moving server-side — the
catalog should follow rather than leave the page half-static. **B as an interim
regardless of choice**, because the image digest and ECS revision serve no
purpose in a public file.

---

## F1a — The Q1 audit: all four build-time imports are public, and the catalog is the least sensitive

Completed 2026-11-11 as required by Q1. Four JSON files are imported at build
time in `ui/src`, totalling about 118 KB, and **every one ships in full** in
the publicly downloadable bundle. Verified by sampling long string literals
from each file and finding them in the fetched bundle — 12 of 12 for the large
files, 2 of 2 for the small one.

| File | Size | Publicly readable | What it discloses |
|---|---|---|---|
| `qaSnapshot.json` | 66.5 KB | **yes, in full** | the test catalog: steps, expected results, statuses, PR numbers, merge SHAs, per-case environment strings with ECS revisions and image digests |
| `securityRequirements.json` | 25.5 KB | **yes, in full** | the SEC-001…009 control inventory — **including where security coverage is absent** |
| `securityReadiness.json` | 23.3 KB | **yes, in full** | the readiness record: verification narrative, image digests, revision numbers |
| `qaOpenFindings.json` | 3.4 KB | **yes, in full** | the open TC-041 finding and a path to an internal runbook |

### The finding that changes the ordering

`securityRequirements.json` contains **five statements admitting absent or
partial security assurance, and all five are readable without authentication.**
For example:

> "No test asserts the absence of a write path, and no record exists of the
> database grants the API role actually holds."

> "No automated coverage yet. The resolver and the SQL clause exist; no test
> drives an end-to-end SAP-account grant."

This is a publicly readable inventory of where this system's security
assurance is weakest. It is not an exploitable vulnerability and it leaks no
credential or personal data — but it is materially more sensitive than the
image digests that prompted F1, and it is the one an outside reader would find
most useful.

It may also be **stale**, which makes it worse rather than better: at least one
of those statements has since been addressed by work recorded elsewhere in this
repository. A public, out-of-date claim that a control is untested is both a
disclosure and a misrepresentation.

### Consequence for Q1

Q1 approved moving **the catalog** to an authenticated API. The audit shows the
catalog is the *least* sensitive of the four. **A new decision is therefore
needed** — recorded as **U1** below — on whether
`securityRequirements.json` and `securityReadiness.json` also move behind
authentication, are trimmed, or stay public. Moving only the catalog would
leave the more sensitive material exposed.

Recommendation: move all four behind the authenticated endpoint. They are
internal assurance records with no external audience, and `/qa-status` is
becoming an authenticated workspace in any case.

---

## F2 — Making references non-bypassing: three vectors

Decision 1 requires that references cannot bypass the original storage
permissions. Three distinct ways they could.

**V1 — anonymous sharing links.** A SharePoint "Anyone with the link" URL *is*
a bypass: it carries its own access. Controls: a **BU-tenant host allowlist**
at submit time (`bu.sharepoint.com`, `bu-my.sharepoint.com`, `teams.microsoft.com`
and the like), rejecting everything else. **Residual risk, stated plainly:** the
API cannot tell a tenant-scoped link from an anonymous one without calling
Microsoft Graph, which this phase does not do. So V1 is mitigated by allowlist
plus a submit-time attestation ("this link is restricted to BU staff"), not
eliminated. Graph validation is a later option if the residual risk is
unacceptable.

**V2 — RAP becoming the proxy.** If the app fetches, embeds, thumbnails or
prefetches the reference, RAP serves the content and *its* permissions apply
instead of SharePoint's. Controls: the reference is stored and rendered as
**text and a plain external link only** — no server-side fetch, no `iframe`,
no `img`, no link preview, no prefetch; `rel="noreferrer noopener"` and a new
tab. The viewer's browser authenticates to BU storage directly, so SharePoint
enforces permissions and RAP never holds the content. This is what satisfies
"a tester cannot view evidence beyond their existing BU storage permissions" —
by construction, not by policy.

**V3 — the URL itself disclosing.** A path like
`…/Award-212137-restricted-financials.pdf` discloses through its filename to
anyone who can read the result row. Controls: testers are told the URL is
visible to everyone who can view the result, and **F3** governs who that is.

---

## F3 — Result viewing is a potential existence side-channel. **Production blocker**

**Severity: none in DEV, high in production.**

A result row names a `record_number`. Anyone who can read results therefore
learns that record exists — which is exactly what record authorization is built
to conceal, since an out-of-scope record returns 404 precisely so its existence
stays hidden.

In DEV this is moot: enforcement is off and every tester can already see every
record. In production, with enforcement on, "result viewing" would become a
side channel around the 404 — the same existence-disclosure problem the View
Access As audit identified.

**Not a reason to change phase 1.** It is the reason production needs its own
decision, and the options should be recorded now rather than rediscovered:
either restrict result viewing to the submitting tester plus `QA_LEAD`, or
filter result rows through the viewer's own record scope, or keep the QA
workspace DEV-only permanently.

Phase 1 recommendation: **results readable by any authenticated tester** —
simple, and harmless while enforcement is off — with this finding carried
forward as a gate on production deployment.

---

## F4 — "On behalf of" must record two identities, or it is impersonation

The requirement to prevent submitting for others "unless explicitly
authorized" implies a delegated path — John recording what a tester reported by
phone, for instance.

Identity derived from Cognito means self-submission is already guaranteed. A
delegated submission is the exception, and if it overwrites
`tester_identifier` it becomes impersonation with no trace.

**Design:** add `submitted_by_identifier` alongside `tester_identifier`. The
submitter always comes from the token; the subject may differ **only** for
`QA_LEAD`, and the row is then visibly marked "recorded by" in the UI. Both
identities persist in the immutable history. Where the two differ and the
submitter is not `QA_LEAD`, the write is refused.

This mirrors the conclusion of the impersonation audit: the safeguard is
recording both parties, not forbidding the action.

---

## F5 — Retention is not yet computable

"12 months after test-cycle closure" needs a test cycle, and the model has
none — so *closure* is undefined and no retention rule can be evaluated, even
manually.

**Design, no deletion job:** a `qa.test_cycle` table (`id`, `name`,
`opened_on`, `closed_on`) with `qa.test_result.cycle_id`. Retention then
becomes a computable property — `closed_on + 12 months` — reportable on the
dashboard and enforceable later by a separately approved job. Adding the column
now costs nothing; adding it after results exist means a backfill with no
correct answer.

No automatic deletion is designed or implemented, per decision 4.

---

## F6 — `QA_LEAD` placement

**Recommendation: a Cognito group, `ArchiveQaLead`.** It follows the
`ArchiveAttachmentViewer` precedent already in production use, needs no schema,
and makes membership an explicitly approved act — satisfying "QA triage
assignments must be explicitly approved" without building a provisioning
surface.

Rejected: a `qa.qa_lead` table, which would create a second provisioning path
needing its own audit and approval flow for no benefit at this scale.

**Two constraints.** `QA_LEAD` must confer **no record access** — the same rule
as `ARCHIVE_ADMIN`, where matrix cases 20 and 20b assert that administering
access does not grant it. And it must be checked **independently** of
`ARCHIVE_ADMIN`: not a superset, not implied by it. A `QA_LEAD` who is not an
administrator must work, and an administrator who is not `QA_LEAD` must be
refused triage.

---

## Decisions resolved 2026-11-11

| # | Decision taken |
|---|---|
| **Q1** | Catalog moves to an authenticated API. ECS revisions, image digests and unnecessary operational detail are removed from publicly downloadable bundles. Audit of other build-time imports **complete — see F1a**, which raises **U1** |
| **Q2** | Ordinary testers see **only their assigned tests and their own results**; QA leads see all DEV results. Enforced **server-side** |
| **Q3** | Delegated submission **by QA leads only**, persisting both the authenticated submitter and the subject, shown as "Recorded by", immutable history preserved |
| **Q4** | `qa.test_cycle` added with lifecycle status and closure timestamp. 12-month retention **provisional** pending BU policy. **No deletion jobs** |
| **Q5** | `ArchiveQaLead` accepted as the name. **Not created, no members assigned** without separate approval. Confers **no** archive-record access; independent of `ARCHIVE_ADMIN` in both directions |
| **Q6** | Phase 1: BU-approved host allowlisting plus user attestation. **No proxying, fetching, embedding, previewing or caching** of evidence through RAP. Reject known anonymous-sharing URL patterns where feasible. Documented that these controls **do not independently prove SharePoint permissions** |
| **Q7** | Catalog/migration parity verification in the **API CI suite**, with exact test-ID matching, failing on missing, duplicate or unexpected IDs |

## Remaining unresolved decisions

| # | Question | Status |
|---|---|---|
| **U1** | Do `securityRequirements.json` and `securityReadiness.json` also move behind authentication, get trimmed, or stay public? The audit shows they are **more** sensitive than the catalog | **new, from F1a.** Recommendation: move all four |
| **U2** | Production record-existence disclosure (**F3**) — result rows name record numbers, which in production would become a side channel around the 404 | **recorded as a separate blocker** requiring its own security decision, per requirement 4. Deliberately unresolved |
| **U3** | In production, does the **catalog itself** need scoping? Test steps name record numbers (TC-056 names subaward `95015`), so an authenticated-but-unauthorized tester reading the catalog learns those records exist | **new.** Follows from requirement 2 combined with Q1. No effect in DEV, where enforcement is off. Belongs with U2 |
| **U4** | Initial `ArchiveQaLead` members | awaiting separate approval (Q5) |
| **U5** | Retention period and whether results survive into production | provisional at 12 months, pending BU records-retention and security approval (Q4) |
| **U6** | Does the QA workspace ever deploy to production at all? | DEV-only for phase 1; production needs separate approval |

### Why U3 is listed separately from U2

U2 is about **results**; U3 is about the **catalog**. They need the same
decision but have different blast radii: results accumulate record numbers as
testing proceeds, whereas the catalog's record numbers are fixed, few, and
chosen by us. U3 may therefore be acceptable where U2 is not — for instance by
selecting catalog fixtures that every tester is authorized to see. Deciding
them together risks applying the stricter answer to both unnecessarily.

## Not authorized by this review

No migration, schema, endpoint, UI change, IAM policy, role or Cognito group
created. No invitation sent. No identity, grant or group membership
provisioned. No deletion or retention job. DEV unchanged. PR #135 stays draft.
