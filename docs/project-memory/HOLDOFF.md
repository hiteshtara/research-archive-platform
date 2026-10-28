# Research Archive — handoff, 2026-10-28

State verified against GitHub and live AWS on **2026-10-28**, re-derived rather
than carried forward. `origin/main` = **`c341ed0`** (see the merge log below).

> ### ⚠️ Correction, 2026-10-28
>
> The 2026-10-27 draft of this file, and the QA Status page, said the
> authorization **engine** (PR #56) was "in the running API, inert". **It is
> not.** The deployed image is tagged `20261006T182647Z-b10e4e3`, built on
> **2026-10-06** from commit `b10e4e3`. Ancestry shows it contains **none** of
> #56, #62, #64, #66, #71, #74 or #76 — PR #56 merged on 2026-10-23, ten hours
> *after* revision 81 was deployed.
>
> **Nothing authorization-related is deployed at all.** Three requirements
> (SEC-005, SEC-008, SEC-009) claimed `deployed: yes` on that basis and are
> corrected, along with a guard test that was enforcing the overclaim.

---

## Executive summary

### The one-line version

Every planned authorization module is **built, tested and merged**. **None of
it is running.** The archive behaves today exactly as it always has, and will
until someone deploys the API and turns enforcement on — which is blocked on
two answers we do not have.

### Status at a glance

| Area | Built | Merged | **Deployed** | **Enforced** | Acceptance-verified |
|---|---|---|---|---|---|
| Authentication (Shibboleth + Duo) | ✅ | ✅ | ✅ **live** | n/a | ✅ dev, 2026-10-23 |
| Authorization engine | ✅ | ✅ | ❌ | ❌ off | ❌ |
| Award authorization | ✅ | ✅ | ❌ | ❌ off | ❌ |
| Proposal authorization | ✅ | ✅ | ❌ | ❌ off | ❌ |
| Subaward authorization | ✅ | ✅ | ❌ | ❌ off | ❌ |
| Negotiation authorization | ✅ | ✅ | ❌ | ❌ off | ❌ |
| ARCHIVE_ADMIN API + UI | ✅ | ✅ | ❌ | ❌ off | ❌ |
| Dashboard scoping (D2) | ✅ | ✅ | ❌ | ❌ off | ❌ |
| IRB shared-surface isolation (D9) | ✅ | ✅ `c341ed0` | ❌ | ❌ off | ❌ |
| BU identity → KRIM mapping | 🔍 investigated | ✅ docs | ❌ | n/a | **no longer blocking** — superseded by ARP |
| ARCHIVE_ADMIN bootstrap utility | ✅ | ✅ `542f03c` | ❌ | ❌ off | ❌ |
| Archive-issued identity (ARP) | ✅ | ✅ `1e72a66` | ❌ | ❌ off | ❌ |
| SEC-008 audit scope | ❌ | — | — | — | ❌ **decision open** |

**Read the five columns separately.** They are not stages of one thing: merged
means the code is on `main`; deployed means the ECS API runs it; enforced means
the flag is on; acceptance-verified means a person confirmed the behaviour in a
real environment. **Nothing is acceptance-verified**, because that needs
enforcement on, and it is off everywhere.

### What is live today

Only authentication. Users sign in with BU Shibboleth and Duo through Cognito,
and then **see the entire archive**, exactly as before this programme started.

### What remains before authorization can be switched on

1. **BU IAM must confirm the institutional identifier** — which attribute is
   the stable `eppn → PRNCPL_ID` key. Without it there is nothing safe to key
   a grant on.
2. **Anthony must name the first administrators** — at least two, so the
   last-administrator guard is never a single point of failure.
3. **Deploy an API image** containing this work (separate approval).
4. **Bootstrap the first administrator** through the operator procedure, then
   issue real grants.
5. **Turn enforcement on**, dev first, with the stage-5 validation re-run live.

Steps 1 and 2 are not engineering work. They are the critical path.

### Decisions needed

| From | Decision |
|---|---|
| **BU IAM** | The authoritative `eppn → PRNCPL_ID` mapping (blocks everything) |
| **BU IAM** | Whether that identifier can be released to our Cognito service provider |
| **Anthony** | **SEC-008** — is auditing administrative actions only sufficient, or must record views and searches be logged too? **Still open.** |
| **Anthony** | Who the first ARCHIVE_ADMIN holders are |
| **Anthony** | Approve the targeted KRIM backfill into dev RDS (~5,900 staff records) |
| **Anthony** | Approve deploying an API image containing the admin surface |

### Next engineering tasks

1. Merge **PR #77** (D9) after review.
2. Build the one designed-but-missing admin capability: contact-relationships
   review (`GET /identities/{id}/contact-relationships`).
3. Draft the operator bootstrap procedure, ready to run when (1) and (2) above
   are answered.
4. Everything else waits on the two external answers.

---

## Verified live state

| | |
|---|---|
| `origin/main` | `54a4254` |
| **ECS API** | `research-archive-platform-dev-api:81`, deployed **2026-10-22**, 1/1 running, rollout COMPLETED. Image `20261006T182647Z-b10e4e3` — **built 2026-10-06**, and the newest revision registered, so nothing newer is waiting |
| **What the image contains** | **None** of #56, #62, #64, #66, #71, #74, #76 — verified by `git merge-base --is-ancestor` on 2026-10-28 |
| **Enforcement** | **OFF** — the running task definition carries **no `APP_AUTHORIZATION_*` variable at all**, so the Java default `false` applies. Checked directly, not inferred |
| **Amplify** | builds 148, 149, 150 all SUCCEED; UI returns HTTP 200. **A UI rebuild is not a backend deployment** |
| **Migrations** | **V082–V086 not applied** anywhere. `authz` schema does not exist on dev RDS — verified read-only through the approved SSM tunnel on 2026-10-28 |
| **Administrators** | none bootstrapped; `authz.admin_role_member` does not exist |
| **Grants** | none |
| **SSM tunnel** | dbtunnel cluster 0 tasks; local port 15432 closed |
| AWS account | 770203350335 (`bu-nprd`) |

> **The deployed API predates every authorization merge by about three weeks.**
> The website being demonstrated contains none of this behaviour, the
> ARCHIVE_ADMIN screens are unreachable in every environment, and even the
> Phase 1 engine is absent — not merely inert.

---

## Completed and merged

### The four authorization modules

| Module | PR | Merge | Rule |
|---|---|---|---|
| Award | #62 | `b77f03f` | Lead unit, with descendants where the grant allows; Research Staff contact derivation; IO = SAP account |
| Proposal | #64 | `f1b8115` | Sibling port; 14 endpoints |
| Subaward | #66 | `3559fd5` | **Family-level** funding inheritance — visible when any funding Award is. Per-version reached only **4.06%** of version rows in real data; family-level reaches **98.09%** |
| Negotiation | #71 | `f94617b` | Hybrid: lead unit, or associated Award, or associated Proposal. Association resolution is **gated by explicit association type**, which avoids 6,073 document-number collisions found in real data |

Shared properties: denial is **404, never 403**; scope predicates sit in the
**same `WHERE`** as filters so the page and the count agree; every port has a
`PERMIT_ALL` default plus a startup guard that refuses to run with enforcement
on and a permissive port.

### ARCHIVE_ADMIN management — PR #74, merged `b11efaa`

**12** endpoints and **4** UI screens: find and verify an identity, link it,
grant CENTRAL/UNIT/SAP-account access, suspend, revoke, audit history, and
manage administrators with last-administrator protection under a row lock.

- **Administration is not data access** — structural, not conventional:
  `admin_role_member` is its own table rather than a `grant_type`, so no query
  asking "does this user hold a grant" can answer yes for an administrator.
  The UI warns when any identity, administrator included, holds no grant.
- **No HTTP bootstrap endpoint.** The first administrator cannot be appointed
  by an existing one, so an HTTP route would bypass the model.
- **The whole `/api/v1/admin` tree returns 404 while enforcement is off.**
- ⚠️ **Not built:** capability 4, contact-relationships review. Designed,
  recorded in the design document, and tracked as a follow-up. Nothing is
  unprotected by its absence.

### Dashboard scoping — D2, PR #76, merged `54a4254`

All three Proposal-derived figures scoped together: family count, version-row
count, and the PROPOSAL branch of the document tally. **10 of 15** Dashboard
metrics now scoped. 11 integration tests; **9 fail if the scope is removed**,
verified by reverting it.

### Identity investigation — PR #73, merged `289a5ac` (documentation only)

**The 660 unresolved principal ids are explained, and it is none of the
assumed failure modes.** All 660 are valid KRIM principals (660/660 verified
against Oracle staging). `archive.person` is a point-in-time snapshot loaded
**2026-08-03 and never re-run**, while `award_unit_contact` kept loading until
**2026-08-12** — 660 contacts arrived after the snapshot, 0 before. A missing
ETL dependency, not a failed extraction.

**Unplanned finding that changed the architecture:** **61%** of referenced
principals are inactive in KRIM, including 308 of the 467 already loaded. That
is normal for a decades-deep archive — and it is why historical KRIM status
must never gate current access.

### Test-infrastructure fix — PR #75, merged `91471c3`

A `ConcurrentModificationException` hit the full suite roughly **one run in
three**. The diagnosis written in the tests was wrong; a captured stack trace
showed the race is between the MockMvc filter chain and the async streaming
worker, inside the first `perform(...)`. Fixed test-only; five consecutive
full runs clean.

---

## Open pull requests

| PR | State | Depends on | Summary |
|---|---|---|---|
| **#77** | **draft, open** | — | **D9**: exclude IRB from the Dashboard when enforcement is on. CI green. **Ready for review.** |
| #68 | draft | — | SSM tunnel: `TargetNotConnected` retry and live verification record |
| #70, #57, #44, #32 | open | — | Older documentation/QA PRs, not on the authorization path |
| #45, #46 | draft | each other | QA dashboard card link semantics |
| #12–#16 | draft, stacked | each other | Superseded authorization stack; **candidates for closure** — their content reached `main` through #56/#62 and later |

---

## Decisions

### D9 — IRB shared-surface isolation (approved 2026-10-27, implemented in #77)

IRB is **outside** Research Archive authorization scope and will not get a
record rule. When enforcement is on it is removed from shared surfaces: already
from shared Global Search, and now from the Dashboard — the four IRB figures
are **omitted from the response** (null, not zero: a zero would assert the
archive holds no protocols) and the IRB branch leaves the document tally.
Applies to **every caller, Central included**.

### D-ID-1 — historical KRIM status is not an access control

An administrator-verified link is **not** revoked because KRIM marks the
principal inactive. With 61% inactive, the opposite rule would deny most
legitimate users.

### SEC-008 — audit scope — ⚠️ **STILL OPEN**

Currently **only administrative actions** are audited: grants, revocations,
suspensions, identity links and role changes, in an append-only table enforced
by a database trigger. Record **views and searches are deliberately not
logged**. SEC-008 as written says "all archive activity". **Anthony has not
confirmed the narrowing.** If full view logging is required it is new work, not
a configuration change.

---

## KRIM historical identity backfill — not run

Targeted extraction design is complete: 10 driving columns, **5,917** distinct
principal ids, bind-filtered, never a full KRIM scan. `subaward_contact` is
excluded deliberately — it carries `rolodex_id`/`requisitioner_id`, not
`person_id`, and Rolodex entries are not KRIM principals.

Current coverage is **467 of 5,917 (7.89%)**. Until the backfill runs,
administrator identity search finds a small fraction of people; an identifier
can always be entered manually, and the UI says so.

**Local import to a laptop was planned and then recommended against** — the
local database is three migrations behind, synthetic fixtures serve every
engineering need, and ~5,900 BU staff records on a developer machine needs a
data-handling decision. **No personnel data has been transferred anywhere.**

---

## SSM database tunnel

Working and proven end to end (PRs #67 merged, #68 open for the
`TargetNotConnected` retry). Ephemeral Fargate task, no bastion, RDS stays
private. **Not running now** — 0 tasks, local port closed.

One correction carried forward: the SSM agent's patch status is
**INDETERMINATE, leaning patched** for CVE-2026-89049 — the version string is
below the fix but the binaries postdate it. It was briefly overstated as
"confirmed unpatched"; it is not confirmed either way.

---

## Known gaps, stated plainly

| | |
|---|---|
| Nothing is **acceptance-verified** | requires enforcement on in a real environment |
| **270 unresolved Subaward families** | from the funding-inheritance profiling; not investigated |
| **Contact-relationships review** not built | designed, tracked; nothing unprotected |
| **IRB module endpoints** remain unauthorized | by decision; they are not a shared surface |
| **Upstream change detection** absent | nothing notices a Kuali role change; revocation is administrator-driven, by design (D-ID-1) |
| **PR #12–#16** stale | superseded; should be closed to stop them misleading readers |

---

## Where to look

| | |
|---|---|
| Activation sequence | `docs/runbooks/AUTHORIZATION_ACTIVATION.md` |
| The authorization model | `docs/security/IDENTITY_AUTHORIZATION_ARCHITECTURE.md` |
| Admin API and UI design | `docs/security/ARCHIVE_ADMIN_MANAGEMENT_DESIGN.md` |
| The 660 finding | `docs/security/KRIM_LOCAL_IMPORT_PLAN.md` §2 |
| Requirement traceability | `docs/security/ANTHONY_REQUIREMENTS_TRACEABILITY.md` (read its correction note first) |
| Live QA status | `/qa-status` on the dev site |
