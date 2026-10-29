# SEC-008 — audit coverage decision matrix

Inventory of what the deployed code (API revision 84) actually writes to
`authz.access_audit`, what it does not, and what needs a business
decision. **Nothing is implemented here.**

## What exists

`authz.access_audit` is append-only, enforced twice: the
`tr_access_audit_append_only` trigger, and `archive_api` holding INSERT
and nothing else. Columns: `audit_id`, `occurred_at`, `actor`, `action`
(VARCHAR 40, no CHECK), `institutional_identifier`, `detail` (**JSONB**,
written as `{"note": "..."}`).

`AdminAuditAction` declares **14** values, and every one is written by
exactly one or two call sites — no declared-but-unused actions:

`ADMIN_ROLE_ASSIGNED`, `ADMIN_ROLE_REVOKED`, `ADMIN_ROLE_BOOTSTRAPPED`,
`ACCESS_GRANTED`, `ACCESS_MODIFIED`, `ACCESS_REVOKED`, `IDENTITY_LINKED`,
`IDENTITY_RELINKED`, `IDENTITY_UNLINKED`, `IDENTITY_SUSPENDED`,
`IDENTITY_RESTORED`, `RELINK_APPROVAL_REQUESTED`,
`RELINK_APPROVAL_GRANTED`, `RELINK_APPROVAL_REVOKED`.

**Every one is an administrative change. Nothing on the read side is
audited at all** — `RecordAuthorizationService` has no logging,
`GlobalExceptionHandler` has no audit reference, and `AwardArchiveService`
has none.

## Decision matrix

| Event | Implemented | Gap | Decision needed |
|---|---|---|---|
| Administrator assigned / revoked | **Yes** | — | — |
| First administrator bootstrapped | **Yes** — verified live, audit_id 3 | — | — |
| Grant created / modified / revoked | **Yes** | — | — |
| Identity linked / relinked / unlinked | **Yes** | — | — |
| Identity suspended / restored | **Yes** | — | — |
| Relink approval requested / granted / revoked | **Yes** | — | — |
| **Record view** (an authorized read) | **No** | every allowed read is invisible | **Anthony:** is "who looked at what" required, or only "who changed access"? Volume is the whole question — 267k award versions |
| **Search executed** | **No** | query terms never recorded | **Anthony:** search terms can themselves be sensitive. Record terms, or only that a search happened? |
| **Attachment download** | **No** | file access leaves no trace | **Anthony:** likeliest genuine requirement — a download removes data from the system |
| **Denied request** | **No** | nothing distinguishes probing from a typo | **Anthony:** denials are low-volume and high-signal. My recommendation: start here |
| Authentication success / failure | **No** (Cognito-side only) | not in the archive's own trail | **Anthony:** is Cognito's own logging sufficient? |

## What I would weigh

**Denied requests are the cheapest high-value addition.** Low volume,
high signal, and they answer "did someone try to reach records they
should not?" — which is the question an audit of an access-control system
is usually asked. One insert in the 404 path.

**Record views are the expensive one and should not be defaulted to.**
With 267,386 award versions and list endpoints returning 50 at a time, a
row per viewed record would outgrow every other table here. If it is
required, the grain needs deciding first: per request, or per record
returned? Those differ by orders of magnitude.

**Attachment downloads are the strongest candidate for "must have"** —
they are low volume, and unlike a view they remove a copy of the data.

**`archive_api` already has the privilege it would need** (INSERT on
`access_audit`), so none of this requires a database-role change. The
append-only trigger means nothing can later be tidied away.

## Not decided here

Retention. `access_audit` has no retention policy and the trigger forbids
deletion, so whatever is logged is permanent by construction. If view
auditing is adopted, retention has to be designed at the same time — or
the decision is made by default.
