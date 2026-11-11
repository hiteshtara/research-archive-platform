# UAT feedback workflow and QA dashboard — implementation design

**Design for review. Nothing is implemented.** Updated 2026-11-11 with
decisions Q1–Q7; see `UAT_ARCHITECTURE_SECURITY_REVIEW.md` for the findings
behind them and for the six decisions still open (U1–U6). No endpoint, no migration, no
schema, no IAM change, no DEV change, no invitation. Thursday's security test,
the frozen runbook, revision 91 and the staged revision 92 configuration are
untouched.

Everything in §1 was read in the tree at `341ff74`, not assumed.

---

## 0. Decisions being implemented

| # | Decision |
|---|---|
| 1 | **Option A** — extend the existing `/qa-status` page; no external QA portal |
| 2 | Integrated feedback: PASS / FAIL / BLOCKED / NOT TESTED, screenshots, comments, BU-approved storage and authentication |
| 3 | Preserve John's catalog and reuse its IDs; add only the verified Subaward and Document gaps |
| 4 | Warren unconfirmed — question drafted in §9 |
| 5 | Lalitha / John / Shari / Dean as proposed, **subject to their confirmation** |
| 6 | Document *discovery* and attachment *viewing* kept explicitly separate |
| 7 | TC-023 / TC-027 negative evidence recorded separately; cases stay open |
| 8 | Dashboard panels per §7 |
| 9 | **No tester receives an authorization grant to complete UAT** |
| 10 | Design and data model only — no implementation |

---

## 1. The constraint that shapes this entire design

Three facts, each verified, and together they mean this is **not** a small
change:

**1. The archive is read-only at the database role, not merely by convention.**
`archive_api` holds `SELECT` on the `archive` schema and nothing more. A source
scan found zero `INSERT`/`UPDATE`/`DELETE` against any `archive.*` object.
Feedback must therefore never touch the `archive` schema.

**2. The `/qa-status` page is a static build artifact.** `QaTestingPage.tsx`
imports `qaSnapshot.json` and `qaOpenFindings.json` at build time and makes
**no network call at all**. Today a QA result is published by committing JSON
and redeploying the UI through Amplify. Accepting live submissions turns a
static report into a write-backed feature.

**3. Nothing in this application persists user-submitted content.** The only
write endpoints are the eight administrative authorization endpoints, which
write to `authz`. The three AI `POST`s carry a payload but store nothing
(`store=false`). A QA result endpoint would be **the first user-content write
surface in the platform.**

### What follows: split the catalog from the results

| | Lives in | Source of truth | Changes by |
|---|---|---|---|
| **Test catalog** — John's TC-001…TC-052 plus the new TC-056…TC-063 | version control (`qaSnapshot.json`) | the repository | pull request |
| **Test results** — who tested what, when, with what outcome | a new write-backed `qa` schema | the database | tester submission |

This satisfies decision 3 structurally: the catalog cannot be edited by a
tester, John's IDs are preserved because nothing renumbers them, and the
dashboard is a **join** of the two rather than a second copy of either.

### Precedent to follow, not invent

`authz` is already a write-capable schema sitting beside the read-only
`archive`, with narrow grants, **no `DELETE` at all**, and an append-only
trigger (`authz.reject_audit_change()`) protecting the audit table. The `qa`
schema should be built the same way. Where this design makes a choice, it
copies `authz` rather than inventing a new pattern.

---

## 2. Proposed data model

New schema `qa`. Forward-only migration, applied by the ETL loaders like every
other migration in this project — **not** by Spring, since
`spring.flyway.enabled: false`.

### `qa.test_cycle` — what "closure" means (Q4)

```sql
CREATE TABLE qa.test_cycle (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    status      VARCHAR(16)  NOT NULL,   -- planned | open | closed
    opened_on   DATE,
    closed_at   TIMESTAMPTZ              -- set once, when status becomes closed
);
```

Retention is expressed as *12 months after test-cycle closure*, so without a
cycle the rule is not evaluable even by hand. `closed_at` makes
`closed_at + 12 months` a computable, reportable property.

**Provisional**, pending BU records-retention and security approval, and **no
deletion or retention job is designed or implemented** (Q4). The schema only
makes the question answerable; it does not answer it.

### `qa.test_case` — the catalog, mirrored for referential integrity

```sql
CREATE TABLE qa.test_case (
    case_id         VARCHAR(16) PRIMARY KEY,   -- 'TC-001' … 'TC-063'
    category        VARCHAR(64)  NOT NULL,     -- 'Awards Search', 'Documents', …
    module          VARCHAR(32)  NOT NULL,     -- the seven UAT modules
    title           VARCHAR(500) NOT NULL,
    priority        VARCHAR(16),
    catalog_status  VARCHAR(16)  NOT NULL,     -- passed | blocked | decision | evidence
    source          VARCHAR(64)  NOT NULL      -- 'John QA matrix' | 'UAT gap 2026-11'
);
```

Seeded **from `qaSnapshot.json` by the migration**, so the file stays the
source of truth and the table is a projection of it.

**Drift is the obvious risk, and it has a precedent cure. Parity lives in the
API CI suite (Q7).** The check must read the migration's seed SQL as well as
the catalog JSON, which puts it on the API side — `ReleaseMigrationRehearsalIntegrationTest`
is the precedent for a test that reads migrations. It asserts **exact test-ID
matching** and fails on **missing, duplicate or unexpected** IDs, so a case
added to the JSON without a migration, a case seeded without a catalog entry,
or a duplicated ID all break the build rather than silently producing results
that reference nothing.

The UI-side `securityReadinessConsistency.test.mjs` remains the precedent for
consistency *between QA data files* and is unaffected.

### `qa.test_result` — append-only

```sql
CREATE TYPE qa.result_value AS ENUM ('PASS','FAIL','BLOCKED','NOT_TESTED','QUESTION');

CREATE TABLE qa.test_result (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             VARCHAR(16) NOT NULL REFERENCES qa.test_case(case_id),
    tester_identifier        VARCHAR(64) NOT NULL,  -- the subject: whose result this is
    submitted_by_identifier  VARCHAR(64) NOT NULL,  -- the authenticated caller; from the token, always
    cycle_id                 BIGINT NOT NULL REFERENCES qa.test_cycle(id),
    result              qa.result_value NOT NULL,
    record_number       VARCHAR(64),           -- the award/proposal/… actually opened
    comment             TEXT,
    screenshot_ref      TEXT,                  -- see §5; a reference, not bytes
    observed_at         TIMESTAMPTZ NOT NULL,
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ui_version          VARCHAR(64),           -- Amplify job / commit
    api_revision        VARCHAR(64),           -- ECS task definition revision
    superseded_by       BIGINT REFERENCES qa.test_result(id)
);
```

**Append-only, following `authz.access_audit`.** A trigger rejects `UPDATE` and
`DELETE`. A tester correcting a result inserts a new row and the old one is
marked `superseded_by`; the dashboard reads the latest non-superseded row per
(case, tester). The reason is the same one that governs the authorization audit
trail: *the row is the evidence*. It also means "this passed last week and fails
today" is answerable, which a mutable table would lose.

`ui_version` and `api_revision` exist because the current catalog already
records an `environment` string per case. A result without the build it was
observed on becomes unusable within weeks.

**Two identities, never one (Q3).** `submitted_by_identifier` is always taken
from the validated token. `tester_identifier` equals it for an ordinary
submission, and may differ **only** when the caller holds `ArchiveQaLead` — a
delegated submission, rendered in the UI as "Recorded by <submitter>". Where
the two differ and the caller is not a QA lead, the write is **refused**. Both
identities persist in the immutable history, including on superseded rows, so a
delegated entry can never be mistaken for a self-submission or quietly
rewritten into one. This is the impersonation safeguard applied here: record
both parties rather than forbid the action.

### `qa.assignment` — who is asked to test what

```sql
CREATE TABLE qa.assignment (
    id                  BIGSERIAL PRIMARY KEY,
    assignee_identifier VARCHAR(64) NOT NULL,
    module              VARCHAR(32) NOT NULL,
    basis               TEXT,          -- the documented reason, e.g. 'filed TC-040/TC-041'
    proposed_on         DATE NOT NULL,
    confirmed_on        DATE,          -- NULL until the tester confirms (decision 5)
    declined_on         DATE
);
```

`confirmed_on` being nullable is decision 5 made structural: an assignment is
*proposed* until the tester confirms. Warren has **no row at all** until he
answers §9 — absence rather than a placeholder, so no assignment is ever
inferred from a blank.

### `qa.defect`

```sql
CREATE TABLE qa.defect (
    id            BIGSERIAL PRIMARY KEY,
    result_id     BIGINT NOT NULL REFERENCES qa.test_result(id),
    severity      VARCHAR(24) NOT NULL,  -- blocks work | wrong information | confusing | cosmetic
    summary       VARCHAR(500) NOT NULL,
    status        VARCHAR(24) NOT NULL,  -- open | triaged | fixed | not a defect | duplicate
    duplicate_of  VARCHAR(16),           -- an existing TC id, for John's triage
    opened_on     DATE NOT NULL,
    closed_on     DATE
);
```

Every defect hangs off a result, which hangs off a case — so a defect can never
arrive without a test case, which is the discipline the guide requires.
`duplicate_of` is what lets John close a report against an existing case rather
than accumulating duplicates.

### Business decisions stay in version control

The six unresolved questions are **business records, not tester data**, and
they already live in `qaSnapshot.json` as `status: "decision"`. They stay
there. The dashboard derives that panel from the catalog. No table.

### Database role

Mirroring the `authz` grant design exactly:

```sql
GRANT USAGE ON SCHEMA qa TO archive_api;
-- Table by table, never ON ALL TABLES: archive_api_role.sql grants
-- SELECT ON ALL TABLES IN SCHEMA authz, so a re-run of the role script
-- silently re-grants what a migration revoked (SEC-008 records this).
-- A blanket grant here would also make any future qa table auto-readable.
GRANT SELECT ON qa.test_case, qa.test_cycle, qa.test_result,
                qa.assignment, qa.defect TO archive_api;
GRANT INSERT ON qa.test_result, qa.defect, qa.assignment TO archive_api;
GRANT INSERT ON qa.test_cycle TO archive_api;
GRANT UPDATE (status, closed_at) ON qa.test_cycle TO archive_api;
GRANT UPDATE (superseded_by) ON qa.test_result TO archive_api;
GRANT UPDATE (status, closed_on, duplicate_of) ON qa.defect TO archive_api;
GRANT UPDATE (confirmed_on, declined_on) ON qa.assignment TO archive_api;
GRANT USAGE ON SEQUENCE qa.test_result_id_seq, qa.defect_id_seq,
                        qa.assignment_id_seq, qa.test_cycle_id_seq TO archive_api;
-- No DELETE on anything. No privilege on qa.test_case beyond SELECT.
```

Two deliberate details: **no `DELETE` anywhere**, as in `authz`; and
column-scoped `UPDATE`, so the API can supersede a result but cannot rewrite
what was observed. Note the `authz` lesson recorded in
`ARCHIVE_API_DATABASE_ROLE.md` — `INSERT` on a `BIGSERIAL` needs `USAGE` on its
sequence, which a naive grant omits and which fails only at runtime.

---

## 3. Proposed API surface

All under `/api/v1/qa`, behind a flag.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/qa/cases` | **the catalog itself**, served from the database and joined with results — see Q1 below |
| `POST` | `/qa/results` | submit one result (append-only) |
| `GET` | `/qa/dashboard` | the aggregates in §7 |
| `GET` | `/qa/assignments` | assignments, with confirmation state |
| `POST` | `/qa/assignments/{id}/confirm` | a tester confirms or declines their own |
| `POST` | `/qa/defects` | raise a defect against a result |
| `PATCH` | `/qa/defects/{id}` | triage (John) |

### The catalog is served, not shipped (Q1)

The catalog moves out of the UI bundle and is returned by
`GET /api/v1/qa/cases`, which requires authentication. Requirement 5 is the
point: it must be **loadable only after authentication**, not merely hidden
behind a protected route. Today the opposite is true — the JSON is compiled
into a publicly fetchable artifact, so the route is gated while the data is
not.

Removing the import is therefore part of the change, not a follow-up: leaving
`qaSnapshot.json` in `ui/src` while also serving it would keep the public copy
alive. The file remains in version control as the **source of truth** and is
read by the migration that seeds `qa.test_case`; it simply stops being an
input to the UI build.

ECS revisions, image digests and other operational detail are dropped from
whatever is published, per Q1. The audit of the other three build-time imports
is complete and raised **U1** — see the security review; that decision is still
open.

### Removable by construction

`/qa-status` is already documented as temporary scaffolding with a removal
checklist in `docs/QA_STATUS_PAGE.md`. This feature inherits that, and the same
discipline the View Access As design used:

- `app.qa.feedback-enabled`, **default false**, so the surface does not exist
  unless switched on
- no DEV or production configuration change is part of this design
- a removal checklist extending the existing one: drop the `qa` schema, revoke
  the grants, delete the controller and the flag, and confirm the test count
  returns to its baseline

### Authorization — and the reason this is safe

**QA endpoints must sit outside record authorization entirely.** A test result
is not an archive record, so it has no lead unit, no grant and no scope.

That is exactly what makes decision 9 achievable: because submitting a result
requires **no** record grant, a tester never needs additional authorization to
complete UAT. Had the QA surface been placed behind `requireProvisioned()`,
every tester would have needed a grant just to file a result — which is the
trap decision 9 forbids.

Three controls apply instead:

1. **Authentication required** — the existing Cognito JWT resource server. No
   new authentication mechanism, and no new account.
2. **Attribution comes from the token** — `tester_identifier` is derived
   server-side from the validated token, never accepted from the request body,
   so a tester cannot submit as someone else.
3. **A tester may write only their own rows** — confirm and supersede operate
   on rows whose `tester_identifier` matches the caller, except for a QA lead's
   delegated submission above.
4. **Reading is scoped server-side (Q2).** An ordinary tester may read **only
   their assigned test cases and their own submitted results**. A holder of
   `ArchiveQaLead` may read all DEV QA results. This is enforced in the query,
   not in the UI: the row filter is applied server-side so a crafted request
   cannot widen it, and the dashboard's aggregate counts are computed under the
   same filter.
5. **`ArchiveQaLead` is a Cognito group (Q5)**, following the
   `ArchiveAttachmentViewer` precedent. It confers **no** archive-record access,
   exactly as `ARCHIVE_ADMIN` confers none, and it is checked **independently**
   of `ARCHIVE_ADMIN` in both directions: a QA lead who is not an administrator
   must be able to triage, and an administrator who is not a QA lead must be
   refused. The group is **not created and no member is assigned** without
   separate approval.

---

## 4. Authentication and storage: what "BU-approved" resolves to

| Concern | Resolution |
|---|---|
| Authentication | **Already solved.** Cognito via BU Shibboleth and Duo. Testers are signed in; nothing new is needed |
| Result data | AWS RDS PostgreSQL in BU's non-production account `770203350335`, inside this project's VPC — the same store already holding `archive` and `authz` |
| Screenshots | **The genuinely unsolved part — §5** |

No data leaves BU infrastructure under this design. That was the deciding
factor for Option A and it holds throughout, with the single exception
discussed next.

---

## 5. Screenshots: the one part I recommend deferring

Decision 2 asks for screenshot attachment. It is the riskiest element in this
design, for two reasons that are worth separating:

**A new write surface.** The API reads from `ARCHIVE_DOCUMENTS_BUCKET` and
writes to no bucket at all. Accepting uploads means a new S3 write path,
presigned-upload handling, content-type and size validation, and malware
considerations for files that other people will later open.

**A data-exposure path pointing the wrong way.** The UAT guide already tells
testers *not* to screenshot data they believe they should not see. A screenshot
upload invites exactly the material most likely to contain it, and stores it
where the archive's own authorization rules do not apply — a screenshot of a
restricted record is not itself a record, so no grant governs who can view it.
That would create a store of archive content outside the access-control model
built to protect it.

### Recommendation — two phases

**Phase 1, approved (Q6): a reference, not bytes.** `screenshot_ref` holds a
link to BU-approved storage the tester already has. Zero new write surface, and
evidence stays under BU's existing document controls and retention.

The controls, as approved:

- **Host allowlisting** against BU-approved evidence storage; anything else is
  rejected at submit time.
- **Known anonymous-sharing URL patterns rejected where feasible** — a
  best-effort pattern check, not a guarantee.
- **RAP never proxies, fetches, embeds, previews or caches** an evidence file.
  The reference is stored as text and rendered as a plain external link with
  `rel="noreferrer noopener"` in a new tab. The viewer's own browser
  authenticates to BU storage, so permissions are enforced **there** and RAP
  never holds the content. This is what makes "a tester cannot view evidence
  beyond their existing BU storage permissions" true by construction rather
  than by policy.
- **Documented limitation, stated rather than implied:** these controls do
  **not** independently prove SharePoint permissions. The API cannot
  distinguish a tenant-scoped link from an anonymous sharing link without
  calling Microsoft Graph, which phase 1 does not do. Allowlisting plus
  attestation reduces the risk; it does not eliminate it.

**Phase 2 (only if phase 1 proves insufficient): native upload.** Then it needs
its own design covering bucket policy, encryption, lifecycle and retention, who
may view an attachment, and whether screenshots inherit the record's
authorization. That is a security review in its own right, not a field on a
form.

Phase 1 satisfies "attach screenshots" as testers experience it — they paste a
link — while deferring the part that needs a separate approval. **Your call; I
have implemented neither.**

---

## 6. Document discovery is not attachment viewing

Decision 6, made explicit in both the data model and the UI.

| | Document **discovery** | Attachment **viewing** |
|---|---|---|
| What it does | finds *that* a document exists and which record it belongs to | opens the *file itself* |
| Verified reach | 491,685 documents matched a search for `NSF` on 2026-11-11 | refused for the designated test account |
| Gate | none beyond authentication (and record authorization once enforcement is on) | **`ArchiveAttachmentViewer` Cognito group**, active today regardless of enforcement |
| On refusal | — | `403 ATTACHMENT_ACCESS_DENIED`, "requires membership in the ArchiveAttachmentViewer group" |
| UAT cases | **TC-060…TC-063** | scenario A-5, and TC-023 / TC-027 |

**The `ArchiveAttachmentViewer` restriction is preserved exactly as it is.** No
tester is added to that group by this design, and group membership is not
provisioned — it is access, and access requires separate approval under
decision 9.

The distinction has to be visible in the dashboard too: a Documents case
failing because discovery is broken and one "failing" because the tester lacked
attachment permission are different outcomes. The second is **BLOCKED**, not
FAIL, which is why `BLOCKED` is a first-class result value and not a comment.

---

## 7. Dashboard panels

Extending `/qa-status`. Each panel and how it is computed:

| Panel | Content | Computed from |
|---|---|---|
| **Test assignments** | tester × module, with basis and confirmation state | `qa.assignment`; unconfirmed shown as *proposed*, never as assigned |
| **Progress** | cases attempted / total, per tester and per module | `qa.test_result` ∪ `qa.test_case` |
| **Passed / failed / blocked** | counts per module and per tester | latest non-superseded result per (case, tester) |
| **Untested modules** | cases never attempted, **and modules with no cases at all** | `qa.test_case` left-joined to results, plus the §3 gap table from the guide |
| **Defects** | open defects, severity, owner, duplicate-of | `qa.defect` joined to results |
| **Unresolved business decisions** | the six open questions | `qaSnapshot.json`, `status: "decision"` — version control, not the database |

**The untested panel is the one that must not be quietly dropped.** It is the
panel the current page lacks, and its absence is why Documents had zero
coverage without anyone noticing: a module with no cases renders as nothing
wrong rather than as nobody looked. It must count *modules with no cases*, not
only unattempted cases — otherwise it would have shown Documents as fully
tested.

**Counts must state their denominator.** The existing page already carries a
hard-won lesson about this (QA TC-040: a page size displayed as a total). "12
passed" is meaningless without "of 63". Every count on this dashboard names
what it is out of.

---

## 8. Catalog additions

Preserving John's catalog unchanged and adding only the verified gaps:

| Case | Module | Gap it closes |
|---|---|---|
| TC-056 | Subawards | open a known subaward (`95015`, code `4092`) |
| TC-057 | Subawards | status values human-readable |
| TC-058 | Subawards | search by subaward code |
| TC-059 | Subawards | subaward relates to its funding award |
| TC-060 | Documents | document search returns attributable results |
| TC-061 | Documents | a document row opens its record |
| TC-062 | Documents | the result count is honest |
| TC-063 | Documents | document number not conflated with record number |

TC-001…TC-052 are untouched: same IDs, same titles, same recorded statuses and
environments. Numbering starts at **TC-056** because TC-053…TC-055 exist on an
unmerged branch and reuse would collide.

---

## 9. Warren — the question to send

Short, specific, and answerable in one reply. Not sent.

> Subject: Research Archive testing — which area fits your work?
>
> Hi Warren,
>
> We're organising structured testing of the Research Archive and assigning
> each tester the area they know best, rather than asking everyone to check
> everything.
>
> I don't want to guess yours. Which of these do you work with closely enough
> to judge whether the archived information is *correct* — not just whether the
> page loads?
>
> - **Negotiations** — agreement records and their document numbers
> - **Subawards** — money passed through to other organisations
> - **Documents** — finding an archived document across the whole archive
>
> Any one is plenty, and "none of these, but I know X" is a useful answer. If
> you'd rather not take part, that's completely fine — just say so.
>
> It's about an hour, on the development site, with a short checklist of
> specific records to open.

Until he answers, Warren has **no `qa.assignment` row**.

---

## 10. Assignments pending confirmation

| Tester | Proposed module | Documented basis | State |
|---|---|---|---|
| Lalitha Sudhireddy | Global Search | filed TC-040 / TC-041 | **proposed** |
| John Nickerson | regression + triage | authored the catalog | **proposed** |
| Shari Huval | data accuracy (Awards, Proposals) | BU data migration correspondence | **proposed** |
| Dean Haywood | cross-module validation | authored the Grants & Agreements architecture proposal | **proposed** |
| Warren Hennemuth | — | not on record | **unconfirmed; no row** |

Each becomes *assigned* only when `confirmed_on` is set by the tester.

---

## 11. TC-023 and TC-027 — negative evidence recorded separately

Per decision 7. **Neither case is closed.**

| | TC-023 / TC-027 |
|---|---|
| Negative scenario — a tester **without** attachment permission | **Verified 2026-11-11**: the designated test account receives `403 ATTACHMENT_ACCESS_DENIED`, "requires membership in the ArchiveAttachmentViewer group". No record detail disclosed |
| Positive scenario — a tester **with** permission opens an attachment | **NOT VERIFIED.** Requires `ArchiveAttachmentViewer` membership, which is access and needs separate approval |
| Case status | **remains open** |

The negative evidence is recorded as its own observation against each case
rather than flipping either to passed. In the model, that is a
`qa.test_result` row with result `BLOCKED` for the positive half and `PASS` for
the negative half, both referencing the same `case_id` — which is precisely why
results are per-submission rows rather than a single status column on the case.

---

## 12. What this design does not authorize

No implementation, deployment, migration, schema, IAM or bucket change. No
invitation sent. No identity, grant or group membership provisioned — including
`ArchiveAttachmentViewer`. No tester receives an authorization grant to
complete UAT; any access genuinely required is a separate approval. No change
to Thursday's security test, the frozen runbook, revision 91 or the staged
revision 92 configuration. No CI change. Production untouched, auditing OFF,
V089 unapplied.

### Decisions taken, and what is still open

The questions this document originally raised were **decided on 2026-11-11**
and are applied above: screenshots by reference (Q6), `ArchiveQaLead` rather
than `ARCHIVE_ADMIN` (Q5), the catalog served from an authenticated endpoint
(Q1), `qa.test_cycle` with provisional 12-month retention and no deletion job
(Q4), server-side read scoping (Q2), QA-lead-only delegated submission (Q3),
and API-side parity verification (Q7).

Two of those earlier questions were answered by decision rather than by this
design:

- **`/qa-status` is now a permanent, authenticated QA workspace.** Its
  temporary status is removed only as part of a separately approved
  implementation, so the removal checklist in `docs/QA_STATUS_PAGE.md` stands
  until then.
- **Phase 1 is DEV-only.** Production deployment requires separate approval.

**Six decisions remain open — U1 to U6, recorded in
`UAT_ARCHITECTURE_SECURITY_REVIEW.md`.** Two of them were raised by the Q1
audit and by requirement 2 rather than existing beforehand: whether
`securityRequirements.json` and `securityReadiness.json` also move behind
authentication (U1), and whether the catalog itself needs scoping in
production, since its test steps name record numbers (U3). The production
record-existence disclosure risk is U2 and is recorded as its own blocker
requiring its own security decision.
