# UAT feedback workflow and QA dashboard — implementation design

**Design for review. Nothing is implemented.** No endpoint, no migration, no
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

**Drift is the obvious risk, and it has a precedent cure.** The repository
already has `securityReadinessConsistency.test.mjs`, which asserts consistency
*across* QA data files. A sibling test must assert that the migration's seeded
case IDs exactly equal the IDs in `qaSnapshot.json` — same count, same set — so
a new case added to the JSON without a migration fails CI rather than silently
producing results that reference nothing.

### `qa.test_result` — append-only

```sql
CREATE TYPE qa.result_value AS ENUM ('PASS','FAIL','BLOCKED','NOT_TESTED','QUESTION');

CREATE TABLE qa.test_result (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             VARCHAR(16) NOT NULL REFERENCES qa.test_case(case_id),
    tester_identifier   VARCHAR(64) NOT NULL,  -- institutional identifier, never an email
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
GRANT SELECT ON ALL TABLES IN SCHEMA qa TO archive_api;
GRANT INSERT ON qa.test_result, qa.defect, qa.assignment TO archive_api;
GRANT UPDATE (superseded_by) ON qa.test_result TO archive_api;
GRANT UPDATE (status, closed_on, duplicate_of) ON qa.defect TO archive_api;
GRANT UPDATE (confirmed_on, declined_on) ON qa.assignment TO archive_api;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA qa TO archive_api;
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
| `GET` | `/qa/cases` | catalog joined with the latest result per case |
| `POST` | `/qa/results` | submit one result (append-only) |
| `GET` | `/qa/dashboard` | the aggregates in §7 |
| `GET` | `/qa/assignments` | assignments, with confirmation state |
| `POST` | `/qa/assignments/{id}/confirm` | a tester confirms or declines their own |
| `POST` | `/qa/defects` | raise a defect against a result |
| `PATCH` | `/qa/defects/{id}` | triage (John) |

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
   on rows whose `tester_identifier` matches the caller. John's triage
   (`PATCH /qa/defects`) is the one elevated action and needs a named QA-lead
   check, which should **not** reuse `ARCHIVE_ADMIN` — QA triage and
   authorization administration are different jobs and should not be collapsed.

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

**Phase 1 (recommended now): a reference, not bytes.** `screenshot_ref` holds a
link to BU-internal storage the tester already has — SharePoint, Teams, or an
email thread. Zero new write surface, no new exposure path, and screenshots
stay under BU's existing document controls and retention.

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

### Open questions for your decision

| # | Question |
|---|---|
| Q1 | Screenshots — phase 1 (a link to BU storage) or native upload now? **Phase 1 recommended** |
| Q2 | Who is the QA lead for defect triage, and confirm it is **not** `ARCHIVE_ADMIN` |
| Q3 | `/qa-status` is temporary scaffolding — does a write-backed feature change that, or does it inherit the removal checklist? |
| Q4 | Retention: how long do results and defects live, and do they survive into production? |
| Q5 | Does the `qa` schema belong in DEV only, or eventually in production too? |
