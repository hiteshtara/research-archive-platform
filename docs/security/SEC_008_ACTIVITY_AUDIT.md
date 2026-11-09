# SEC-008 — archive activity audit

Status: **implemented in source, not deployed, collection OFF, privilege not
granted.** Three separate gates stand between this code and a single audit row
existing in any environment, and each is approved separately:

1. the migration (`V089`) must be applied — **not done anywhere**;
2. `GRANT INSERT ON authz.activity_audit TO archive_api` must be added to the
   role script — **deliberately not in the migration**;
3. `app.authorization.activity-audit.enabled` must be set — **off**, and
   setting it without also choosing a failure policy is a startup failure.

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
| `actor` | the **institutional identifier** — the same key grants are held on, so a reassigned login name is not mistaken for the same person. Never a token, never a raw login name |
| `action` | one of five, CHECK-constrained: `RECORD_VIEWED`, `RECORD_ACCESS_DENIED`, `ATTACHMENT_DOWNLOADED`, `EXPORT_GENERATED`, `SEARCH_PERFORMED` |
| `resource_type` / `resource_key` | a **business** key (award number, proposal number), never a row id that means nothing outside one database |
| `outcome` | `PERMITTED` / `DENIED` / `ERROR` |
| `reason` | the engine's own vocabulary — an `AccessReason` or a denial code. Never prose |
| `correlation_id` | ties the row to the API request and the application logs |

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

## 3. Retention

**Proposed: 90 days. This is a number for review, not an approved BU retention
policy.**

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

**The routine-read setting has no default.**
`ActivityAuditProperties.validate()` fails at startup if collection is enabled
without an explicit choice, naming both options. A default there would be this
code quietly making a compliance-versus-availability decision on the
business's behalf.

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
| `RECORD_VIEWED` | `RecordAuthorizationService.requireVersion` | **wired** |
| `RECORD_ACCESS_DENIED` | the same place, on the refusal branch | **wired** |
| `ATTACHMENT_DOWNLOADED` | recorder API ready | **not yet wired** |
| `EXPORT_GENERATED` | recorder API ready | **not yet wired** |
| `SEARCH_PERFORMED` | recorder API ready | **not yet wired** |

Record view and denial are raised at the one point every record decision
passes through, so a new endpoint is audited by construction rather than by
remembering to add a call. An `INHERITED` decision is deliberately **not**
recorded as a view: it is an internal question asked on behalf of a Subaward or
Negotiation, and recording it would report a view of an Award the caller never
opened.

`searchPerformed` takes a module and a result **count**. There is no parameter
on any method that could carry a query string — the shape of the API is the
privacy control, not a convention each call site has to remember.

Every constructor that does not take a recorder defaults to
`ArchiveActivityRecorder.disabled(clock)`, so wiring a call site changes
nothing until collection is switched on.

## 6. What is NOT built yet

- **Three call sites remain unwired** — attachments, exports and searches. The
  recorder API and its tests are complete; the remaining work is calling it
  from those services.
- **No retention job.** Operational, and it needs the retention period decided.
- **No read side.** Nobody can query activity yet; who may read an audit of who
  read what is its own access-control question.

## 7. Tests

`ActivityAuditorTest` — 17 tests: the never-grants-access invariant under both
policies, both failure behaviours, the startup failure when no policy is
chosen, and the privacy constraints enforced by the type (including a
reflection assertion that no search-term field exists).

`ArchiveActivityRecorderTest` — 7 tests over the five call sites: every action
is raised correctly, a denial never throws even with both tiers closed, the two
tiers apply (a failed download audit refuses the release while a failed view
audit does not), a search cannot carry its terms, and a disabled recorder is a
safe no-op everywhere.

`ActivityAuditSchemaIntegrationTest` — the V089 schema against a real
PostgreSQL: partitions route by month, the default partition catches the rest,
the append-only trigger fires on UPDATE and DELETE, and both CHECK constraints
reject unknown values.
