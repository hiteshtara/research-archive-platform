# SEC-008 — archive activity audit

> **D4 revised 2026-11-17: record-view and document-download auditing is
> REQUIRED** (BU user story US-SEC-004: "1) Record views are logged. 2)
> Document downloads are logged. 3) Security changes are logged."). Release
> scope is Award, Proposal, Subaward and Negotiation; **IRB is out of release
> scope** and has no activity call sites. Security changes (US-SEC-004.3) are
> the separate `authz.access_audit` trail and are not covered here.
>
> **Activation in DEV still needs its own, separate approval.** See §8 for the
> remaining steps. Nothing in this document enables anything.

Status: **implemented in source, not deployed, collection OFF, privilege not
granted.** Three separate gates stand between this code and a single audit row
existing in any environment, and each is approved separately:

1. the migration (`V089`) must be applied — **not done anywhere**;
2. `GRANT INSERT ON authz.activity_audit TO archive_api` must be added to the
   role script — **deliberately not in the migration**;
3. `app.authorization.activity-audit.enabled` must be set — **off**.

The privilege is `database/roles/archive_api_activity_audit_grant.sql`:
`GRANT INSERT`, plus an **explicit `REVOKE SELECT`**. That revoke is not
redundant — `archive_api_role.sql` grants `SELECT ON ALL TABLES IN SCHEMA
authz`, so running the role script after V089 silently hands the API read
access to the audit. Verified: without the revoke,
`has_table_privilege(…,'SELECT')` is TRUE. Re-running the role script
re-grants it, so the audit grant must be re-applied afterwards;
`ActivityAuditGrantIntegrationTest` applies them in that order and asserts
SELECT is absent.

## 1. Why a new table, not `authz.access_audit`

`access_audit` records **administration**: who was granted what, by whom. It is
append-only by trigger and effectively undeletable by design, which is right
for a handful of rows a year.

Activity is a different thing: **who read what**, on every page view. Extending
`access_audit` would have produced a table that is simultaneously
undeletable-by-design and high-volume — a combination nobody wants to operate,
and one that makes the retention question unanswerable.

## 2. What is recorded

The six fields the requirement asks for, and nothing else:

| Column | Note |
|---|---|
| `occurred_at` | partition key |
| `actor` | the **institutional identifier** — the same key grants are held on, so a reassigned login name is not mistaken for the same person, resolved exactly as the administrative API resolves its actor (`RequestActivityContext`). An **authenticated caller with no resolvable link** (not provisioned, revoked, suspended, ambiguous) is recorded as `cognito-sub:<subject>` — the opaque Cognito subject, never the token, a login name or an email — so their activity is still attributable. `NULL` **only** for a genuinely unauthenticated request. Never a token, never a raw login name |
| `action` | one of five, CHECK-constrained: `RECORD_VIEWED`, `RECORD_ACCESS_DENIED`, `ATTACHMENT_DOWNLOADED`, `EXPORT_GENERATED`, `SEARCH_PERFORMED` |
| `resource_type` / `resource_key` | the module (`AWARD`, `PROPOSAL`, `SUBAWARD`, `NEGOTIATION`) and its business key: award number, proposal number, `subaward_code`, negotiation id. A view addressed by row id also carries `detail.recordId` (the `award_id` / `proposal_id` / `subaward_id`). **Downloads use the same business key** as a view of that record, plus `detail.recordId` (the row id in the download path) and `detail.attachmentId` — ids only, never a file name, description or content. View and download rows for one record therefore share `resource_key` and, within one request, `correlation_id` |
| `outcome` | `PERMITTED` / `DENIED` / `ERROR` |
| `reason` | the engine's own vocabulary — an `AccessReason` or a denial code. Never prose |
| `correlation_id` | one id per HTTP request, shared by every row that request writes; reuses the Global Search timing `cid` when the request has one, otherwise a random UUID. Never derived from caller input |

### Privacy defaults, enforced by the type rather than by discipline

- **No raw search terms.** A search records *that* it happened and *how many*
  rows it returned. `ActivityAuditEvent` has no field for a term, and a test
  asserts the record components do not contain one — so adding `searchTerms`
  later is a visible, failing change rather than a quiet one.
- **No document content, no credentials.** `detail` accepts **scalars only**
  and refuses strings over 200 characters. A structured payload is the shape a
  captured query or a serialised document arrives in, so the type refuses it
  outright instead of trusting every call site.

These are **defaults pending approval**, not an approved BU privacy position.
If the business decides search terms must be captured, that is a deliberate
change to the type, the column comment and this document.

### Who may read the audit

**There is no API read path, and this change adds none.** The API role
`archive_api` holds `INSERT` on `authz.activity_audit` and nothing else — the
grant script explicitly `REVOKE`s `SELECT`, and
`ActivityAuditGrantIntegrationTest` asserts it. Reading the audit ("who read
what") is an **operator / security-administrator action**, performed directly
against the database as the table owner (`archive_admin`) or a dedicated
read-only audit role created for the purpose — never through the archive UI or
API. Who holds that access is itself a BU decision to record alongside the
retention period.

## 3. Retention

**90 days proposed baseline (online retention), pending BU records-retention
approval; manual operator procedure only; no automatic deletion.** The owner
approved 90 days as the proposed online baseline on 2026-11-17, subject to BU
records-retention approval. Nothing purges, schedules or invokes deletion:
no cron, EventBridge rule, `pg_cron`, ETL loader or CI job references the
procedure (verified by repository search 2026-11-17).

A tested, **non-executed** retention procedure exists:
[`ops/activity-audit/activity_audit_retention.sql`](../../ops/activity-audit/activity_audit_retention.sql).
It takes the period as a **required** parameter (no default, so no number can
silently become policy), is a **dry run unless `execute=true`**, drops only
whole monthly partitions whose range ends before `now() - period`, never
touches the `DEFAULT` partition, and never disables the append-only trigger.
Nothing schedules or calls it. `ActivityAuditRetentionProcedureIntegrationTest`
runs it against V089 in a throwaway container.

Monthly `RANGE` partitions, so the eventual decision is a `DROP PARTITION`
rather than a `DELETE` over a hot table — and a partition drop does not fire
row triggers, so retention does not fight the append-only guarantee.

A `DEFAULT` partition exists so an `INSERT` can never fail for want of one
(clock skew, a missed monthly rollover). Rows landing there are a signal that
partition maintenance has stopped, not a failure to write — which matters when
the write in question is an audit write.

## 4. The failure policy — the part that is a business decision

The requirement splits in two, and the code splits the same way.

### 4.1 An audit failure can never grant access — an invariant, not a policy

Enforced by the shape of `ActivityAuditor`, not by care at call sites:

- auditing happens **after** the decision, and the auditor is *given* the
  outcome rather than asked for one. There is no code path that can turn a
  `DENIED` into a `PERMITTED`;
- `recordDenied` **never throws**, under either policy. If it could, a caller
  catching broadly around the denial path could convert a refusal into some
  other control flow;
- `recordPermitted` **refuses** a `DENIED` event outright, so a denial cannot
  be routed through the only method that can throw;
- the strictest thing a failure can cause is *refusing an access that was
  already permitted*. Refusing more is always safe; the reverse is what must be
  impossible.

Tested under **both** policies, because it is not a policy.

### 4.2 Whether a failed write blocks a read — **two settings, not one**

The tradeoff is not the same for every action, so there are two:

| | **Sensitive release** | **Routine authorized read** |
|---|---|---|
| Actions | `ATTACHMENT_DOWNLOADED`, `EXPORT_GENERATED` | `RECORD_VIEWED`, `SEARCH_PERFORMED` |
| What happens | a **file leaves the system** | information appears on screen |
| Setting | `sensitive-release-failure-policy` | `routine-read-failure-policy` |
| Value | **`FAIL_CLOSED`** — business direction 2026-11-09, for planning and testing | **no default** |

A release that happened with no record of it having happened is the case the
audit exists for, which is why the first tier is closed by default. A record
view is a different question, and the availability tradeoff there is genuinely
open:

|  | `FAIL_OPEN` | `FAIL_CLOSED` |
|---|---|---|
| Prefers | availability | audit completeness |
| On a database hiccup | the archive keeps serving | reads start failing |
| Risk | a read happens with **no record that it did** | an audit outage becomes an **archive outage** |
| Visible as | `ERROR` in the application log, request succeeds | `AUDIT_UNAVAILABLE` to the caller |

**FAIL_OPEN for routine views: proposed, pending BU approval; not a
default.** The routine-read setting has no default. Until 2026-11-17 the code
quietly defaulted it to `FAIL_OPEN` while this document said otherwise; owner
direction removed that default. `ActivityAuditProperties.validate()` now fails
at startup if collection is enabled without
`app.authorization.activity-audit.routine-read-failure-policy`, naming both
options (`ActivityAuditStartupTest#flagOnWithoutRoutinePolicyRefusesToStart`,
`ActivityAuditorTest#enablingWithoutARoutinePolicyRefusesToStart`). Requiring
it was chosen over defaulting to `FAIL_CLOSED` because a default in either
direction would be the code taking the compliance-versus-availability decision
on the business's behalf, and the cost is one property set at the single
moment collection is switched on. With collection off nothing new is required
(`ActivityAuditStartupTest#flagOffNeedsNothing`).

Sensitive releases stay `FAIL_CLOSED` by default: with the audit store
failing, a download or export answers `503 AUDIT_UNAVAILABLE` and releases no
file (`ActivityAuditEndToEndIntegrationTest#aFailedAuditWriteRefusesDownloadsAndExports`,
`ActivityAuditorTest$FailurePolicyBehaviour#failClosedRefusesTheRead`).

Administrative security changes are a separate path and are unchanged by this
work: their `authz.access_audit` row is written in the same transaction as the
change (`AuthorizationSchemaAndAuditIntegrationTest$AuditTransactionality`).

With collection **off**, both are moot and nothing is required.

For what it is worth as input rather than as a decision: this is a **read-only
archive of retired data**, so an outage is an inconvenience rather than a
blocked business process — which argues for `FAIL_CLOSED` on routine reads
being more affordable here than it would be in a transactional system. An
argument to put to the business, not a conclusion.

## 5. Call sites

`ArchiveActivityRecorder` is the single place the five SEC-008 events are
raised from. Call sites do **not** build events: they call one of five methods,
and the recorder decides the action, the outcome and which recording path to
use. A call site therefore cannot route a denial through the path that may
throw, because it never chooses the path.

| Event | Where it is raised | Status |
|---|---|---|
| `RECORD_VIEWED` | `RecordAuthorizationService.audited` / `auditUnenforcedView`, reached only from the public `requireAward`, `requireAwardNumber`, `requireEveryAwardVersion`, `requireProposal`, `requireProposalNumber`, `requireEveryProposalVersion`, `requireSubaward`, `requireNegotiation` | wired with actor + correlation id (2026-11-17) |
| `RECORD_ACCESS_DENIED` | the same place, on the refusal branch (including not-provisioned / identity refusals) | wired with actor + correlation id (2026-11-17) |
| `ATTACHMENT_DOWNLOADED` | `AttachmentAuthorizationService.recordAttachmentDownload(type, recordId, attachmentId, opened)`, called by the Award, Proposal, Subaward and Negotiation download endpoints after the service resolved the download | wired with actor + attachment id (2026-11-17) |
| `EXPORT_GENERATED` | `AwardV1Controller.report` and `reportWithAttachments`, via `recordExport` | wired with actor (2026-11-17) |
| `SEARCH_PERFORMED` | `GlobalSearchService`, `DocumentSearchService`, `AttachmentSearchService`, `AwardEvidenceSearchService` | wired with actor (2026-11-17) |

### Correction (2026-11-17): the earlier "wired" claims were not true

This table previously marked every call site **wired**. That was true of the
call sites and false of what they would have written. Proven against
unmodified `main` (7101d75) by `ActivityAuditEndToEndIntegrationTest`, run with
collection ON — 24 of 24 tests failed:

1. **No actor, no correlation id, on any row.** `AuthorizationConfiguration`
   built the recorder with `() -> Optional.empty()` and `() -> null`. The only
   rows main could write (downloads, exports, searches) had
   `actor = NULL, correlation_id = NULL`.
2. **Record views and denials were never written at all**, even with
   collection on: the `RecordAuthorizationService` bean used a constructor
   that defaults to a *disabled* recorder.
3. **Views were recorded only under enforcement** (the `require*` methods
   returned before reaching the recording point when enforcement was off —
   which is every environment), and **Subaward and Negotiation decisions were
   never recorded** (they went through `canSee*`, which short-circuits for
   Central and uses `INHERITED`, non-reportable decisions).
4. **A download refused at the record level still wrote
   `ATTACHMENT_DOWNLOADED / PERMITTED`**, because the release row was written
   before the record-level check ran. Independent QA (2026-11-17) found the
   same false row for another record's attachment id, a missing attachment and
   a missing object - all of which 404 in the service after the row was
   written. Fixed by recording only after the service has resolved the
   download (below).

All four are fixed; the same test file passes 24/24 on the fix.

### A known limitation: decision, not delivery

Release rows record that the system **authorised a file for release** and
committed the audit before any byte left — **not** that the caller received it.
A client that disconnects mid-stream still leaves the row behind.

This is deliberate. FAIL_CLOSED means "do not release unless the audit is
written", which forces the write to happen *before* the stream starts. An event
recorded after the stream completed could refuse nothing, so it would be a
weaker guarantee wearing a stronger name. Every release row carries
`detail.phase = "release_authorized"` so the limitation is visible in the data
rather than only here.

### Ordering: decide, resolve, audit, then stream

For a **download** the endpoint:

1. applies the attachment-viewer group gate (first, before any repository
   call);
2. calls the archive service, which decides the record (recording that view
   or refusal), confirms the attachment id **belongs to that record**,
   confirms the archived object exists and opens it;
3. writes the `ATTACHMENT_DOWNLOADED` row
   (`AttachmentAuthorizationService.recordAttachmentDownload`);
4. only then creates the response body that streams the bytes.

So a `PERMITTED` release row exists only for a release that is about to
happen: a refused record, a foreign attachment id, a missing attachment or a
missing object each throws out of step 2 and leaves **no** download row. Under
the FAIL_CLOSED tier a failed write in step 3 closes the opened object and
answers `503 AUDIT_UNAVAILABLE`: **no byte is released**, because the body that
would stream it does not exist yet.

For an **export** the recorder is called after the report data (and so the
record decision) is built and before the response body exists - the same
guarantee.

A listing must not be recorded as a download, and a download must not be
served on a listing's weaker guarantee - which is why downloads have their own
recording method rather than a flag on the listing gate.

`AttachmentSearchService.searchAttachments` wraps its three record-type
branches so the search is recorded once, around all of them - a new branch
cannot be added without being audited.

Record view and denial are raised from the public `require*` entry points —
the methods a service calls for the record the request is *about* — so a new
endpoint that calls one is audited by construction. Each is recorded **once
per request** (request-scoped de-duplication), whatever the number of checks a
service makes. **Independent of enforcement**: with enforcement off and
collection on, a view is recorded `PERMITTED` with reason `NOT_ENFORCED`.

Not recorded as views, deliberately: the `INHERITED` Award checks made on
behalf of a Subaward or Negotiation, and the `canSee*` questions asked about
*related* records on someone else's page. Both would otherwise report a view
of a record the caller never opened. A Subaward view records the Subaward; a
Negotiation view records the Negotiation.

With collection **off**, the recording points return before doing anything,
and no extra query (business-key lookup, identity resolution) runs.

`searchPerformed` takes a module and a result **count**. There is no parameter
on any method that could carry a query string — the shape of the API is the
privacy control, not a convention each call site has to remember.

Every constructor that does not take a recorder defaults to
`ArchiveActivityRecorder.disabled(clock)`, so wiring a call site changes
nothing until collection is switched on.

### Known characteristic: view volume (for retention sizing)

A record view is recorded **once per record per HTTP request**, not once per
page. A workspace page load issues a request per section (summary, versions,
people, amounts, terms, attachments, ...) - roughly **20 requests for an Award
workspace** - and each section request that gates on the record writes its own
`RECORD_VIEWED` row, all with the same actor and key but different correlation
ids. Expect on the order of 20 rows per workspace open. This is documented,
not changed: collapsing it would need a page-level view signal the API does not
have. BU retention sizing should use rows-per-page-open, not one row per view.

## 6. What is NOT built yet

- **No scheduled retention job.** A tested procedure exists
  (`ops/activity-audit/activity_audit_retention.sql`) but nothing runs it; it
  needs the retention period decided. The process is documented in
  [`docs/runbooks/ACTIVITY_AUDIT_PARTITIONS.md`](../runbooks/ACTIVITY_AUDIT_PARTITIONS.md).
- **No API read side.** See "Who may read the audit" in §2.
- **No IRB call sites.** IRB is out of release scope.

## 7. Tests

`ActivityAuditorTest` — 18 tests: the never-grants-access invariant under both
policies, both failure behaviours, the startup failure when no policy is
chosen, and the privacy constraints enforced by the type (including a
reflection assertion that no search-term field exists).

`ArchiveActivityRecorderTest` — 13 tests over the five call sites (including that download and export rows can carry no file name, content or document text): every action
is raised correctly, a denial never throws even with both tiers closed, the two
tiers apply (a failed download audit refuses the release while a failed view
audit does not), a search cannot carry its terms, and a disabled recorder is a
safe no-op everywhere.

`ActivityAuditSchemaIntegrationTest` — the V089 schema against a real
PostgreSQL: partitions route by month, the default partition catches the rest,
the append-only trigger fires on UPDATE and DELETE, and both CHECK constraints
reject unknown values.

`ActivityAuditEndToEndIntegrationTest` — 36 tests (24 at the main-branch proof, plus related-list parent-only ×2, no-secrets-in-rows, FAIL_CLOSED-on-audit-outage, and foreign / missing attachment id ×4 modules each), real HTTP through the real
controllers and wiring, V089 applied, collection ON, synthetic identities: for
each of Award, Proposal, Subaward and Negotiation a permitted view writes
exactly one `RECORD_VIEWED` row asserting all five fields (actor, action,
target, timestamp, outcome) plus correlation id; a denied view writes
`RECORD_ACCESS_DENIED`; with enforcement OFF a view is still recorded; a
download writes `ATTACHMENT_DOWNLOADED` with actor, record id and attachment
id; a refused download writes no release row; an Award report export writes
`EXPORT_GENERATED` with the actor; an unlinked caller is attributed by Cognito
subject; inherited Award checks are not recorded as Award views; and separate
requests get separate correlation ids.

`ActivityAuditDisabledIntegrationTest` — collection at its default (off):
views, refusals, downloads and exports in every module write **nothing**,
with enforcement on or off.

`ActivityAuditRetentionProcedureIntegrationTest` — the retention procedure:
dry run drops nothing, execute drops only expired whole months, the period is
a parameter, the DEFAULT partition is untouched, append-only still holds, a
non-positive period is refused.

`RequestActivityContextTest` — actor resolution (institutional id; Cognito
subject fallback; null only when unauthenticated; cached per request; never
throws) and correlation ids (one per request; search timing cid reused).

## 8. Remaining before US-SEC-004.1 / .2 are DONE in DEV

Each step is approved separately; none is done by this change.

1. **Approve and apply V089** — `git mv` it from
   `database/migrations-pending-approval/` into `database/migrations/` in its
   own PR, then apply via the loader's `--migrate-only` from an image that
   descends from that commit.
2. **Apply the privilege** — `database/roles/archive_api_activity_audit_grant.sql`
   (INSERT only, explicit REVOKE SELECT), after the role script.
3. **BU records-retention approval** of the 90-day proposed online baseline,
   plus whether denials are kept longer, export-before-drop, and who may
   authorise a drop and who may read the audit. Retention stays a manual
   operator procedure; no automatic deletion.
4. **BU approval of the routine-read failure policy** (`FAIL_OPEN` proposed;
   it has no default and must be set explicitly when the flag is turned on).
5. **Turn the flag on in DEV** — `app.authorization.activity-audit.enabled=true`
   in the DEV task definition — separate approval.
6. **Create next month's partition** before each month begins (runbook §4).
7. Verify in DEV: a view and a download in each module produce rows with an
   institutional-identifier actor (requires the caller to hold an
   `authz.identity_link`; otherwise the actor is `cognito-sub:…`).
