# ARCHIVE_ADMIN management — API and UI design

Design only, 2026-10-27. **No code written, no migration applied, no
administrator bootstrapped, no grant created.** Prepared for review
before implementation, per the approved architecture in
[`IDENTITY_AUTHORIZATION_ARCHITECTURE.md`](IDENTITY_AUTHORIZATION_ARCHITECTURE.md).

---

## 1. What already exists — and the one thing that does not

This is not a new subsystem. Phase 1 built the schema and most of the
service layer; what is missing is the way in.

| Layer | State |
|---|---|
| Schema `authz.identity_link`, `access_grant`, `person_status`, `access_audit` | ✅ [`V082`](../../database/migrations/V082__create_authorization_identity_and_grants.sql) |
| Schema `authz.admin_role_member` (+ last-admin uniqueness index) | ✅ [`V084`](../../database/migrations/V084__create_authz_archive_admin_role.sql) |
| `ArchiveAdminService` — assign/revoke/list admins, bootstrap, audit | ✅ implemented and tested |
| `JdbcAdminRoleRepository` — last-administrator lock | ✅ implemented |
| `AdminAuditSink` → append-only `access_audit` (DB trigger enforced) | ✅ implemented |
| `IdentityResolver`, `AccessScopeResolver`, `RecordAuthorizationService` | ✅ implemented, four modules |
| **Grant write operations** (create / modify / revoke) | ❌ `AccessGrantRepository` is **read-only** — one method, `findForGrantee` |
| **Suspend / unsuspend** | ❌ `person_status` is read via `isSuspended`; nothing writes it |
| **Identity search, audit read** | ❌ no read path |
| **Any HTTP surface** | ❌ **no controller exists** — none of the 21 controllers touches admin |
| **Any UI** | ❌ nothing |

> **The build is therefore: 3 repository write paths, ~4 service
> methods, 1 controller, 1 UI feature.** Everything else is reuse. There
> is no second role-management system here and none is proposed —
> `ArchiveAdminService` stays the single place administrative changes
> happen.

---

## 2. Non-negotiable properties the design must preserve

| | Property | How this design keeps it |
|---|---|---|
| R7 | Shibboleth authentication alone grants nothing | the admin API creates links and grants only on an explicit administrator action; signing in still yields `NotProvisioned` |
| R8 | ARCHIVE_ADMIN alone grants no data access | the admin API **never** writes `access_grant` rows for the caller implicitly; `admin_role_member` stays a separate table, never a `grant_type` |
| R6 | Revocation effective on the next request | unchanged — resolution is per request, nothing memoised, no Cognito group claim consulted |
| R2 | Historical `ACTV_IND` never gates current access | the admin API does not read `kim_principal.actv_ind` for any decision; it may **display** it as historical context, clearly labelled |
| D4 | Every administrative change is audited in the same transaction | all writes route through `ArchiveAdminService`, which already enforces this |

R8 deserves one extra guard: an administrator granting themselves
archive access is a legitimate action, but it must be **explicit and
audited like any other**, never a side effect of holding the role. The
design has no code path that grants on role assignment.

---

## 3. API surface

Base path `/api/v1/admin`. A new controller
`adapter/in/web/AdminAuthorizationController`, following the concrete
`*ArchiveService` pattern used by the four record domains (not the IRB
ports pattern).

### 3.1 Endpoints, mapped to the eight required capabilities

| # | Capability | Method | Path |
|---|---|---|---|
| 1 | Find and verify a BU-authenticated identity | `GET` | `/identities?q=` |
| 1 | Identity detail: links, grants, suspension, admin role | `GET` | `/identities/{institutionalIdentifier}` |
| 2 | Link an identity to an archive principal | `POST` | `/identities/{id}/links` |
| 2 | Remove a link | `DELETE` | `/identities/{id}/links/{linkId}` |
| 3 | Grant CENTRAL / UNIT / **SAP account (IO)** access | `POST` | `/identities/{id}/grants` |
| 3 | Modify a grant (expiry, descendants) | `PATCH` | `/identities/{id}/grants/{grantId}` |
| 6 | **Revoke a grant immediately** | `DELETE` | `/identities/{id}/grants/{grantId}` |
| 4 | Review Research Staff access relationships | `GET` | `/identities/{id}/contact-relationships` |
| 5 | Suspend / restore archive access | `PUT` | `/identities/{id}/suspension` |
| 7 | Administrative audit history | `GET` | `/audit?identity=&action=&from=&to=` |
| 8 | List administrators | `GET` | `/administrators` |
| 8 | Assign ARCHIVE_ADMIN | `POST` | `/administrators` |
| 8 | Revoke ARCHIVE_ADMIN (last-admin protected) | `DELETE` | `/administrators/{id}` |

Bootstrap is **deliberately absent from the API**. The first
administrator cannot be granted by an existing administrator, so it stays
an operator-run path that records who ran it — exposing it over HTTP
would create a privilege-escalation route that bypasses the whole model.

### 3.2 "SAP account access" is the existing IO grant

`GrantType.IO` matches an Award version's `account_number` exactly after
trimming ([`AwardAccountNumberIoResolver`](../../api/src/main/java/edu/bu/archive/adapter/out/persistence/authorization/AwardAccountNumberIoResolver.java),
decision D-A). The UI should **label it "SAP Account"** because that is
what administrators call it, while the stored `grant_type` stays `IO`.

One behaviour the UI must state plainly, because it will otherwise be
read as a bug: an IO grant reaches **Awards only** and never travels to
Proposals, Negotiations or Subawards through relationships (design 4.6).

### 3.3 Finding an identity — what is and is not possible

There is **no BU directory lookup**. The archive cannot enumerate BU
staff, and this design does not add that capability. Search therefore
covers three sources, and the UI must be honest about which is which:

| Source | Finds | Caveat |
|---|---|---|
| `authz.identity_link` | anyone already linked | the common case |
| `authz.access_audit` | anyone who has **attempted** sign-in (enrollment records refusals) | proves they authenticated via Shibboleth at least once |
| `archive.person` | historical KRIM identity, for linking to an archive principal | **historical**; says nothing about current employment |

Plus manual entry of an institutional identifier, for granting access
before a first sign-in.

> **The `archive.person` route is exactly why KRIM completeness matters.**
> Only 467 of 5,917 referenced principals are loaded (7.89%), so this
> search will miss most people until the backfill runs (D-ID-4). That is
> a usability limit, not a security one — and it is the concrete business
> cost of the load-ordering gap.

### 3.4 Status codes

| Case | Status | Why |
|---|---|---|
| caller is not an active ARCHIVE_ADMIN | **403** | contrast with record endpoints, which return **404** — see note |
| enforcement disabled | **404** on the whole `/admin` tree | the surface does not exist when authorization is off |
| last-administrator revoke refused | **409** | a conflict with system state, not a bad request |
| subject identity unknown | **404** | |
| grant violates a `CHECK` (e.g. IO + `include_descendants`) | **400** | |

**The 403/404 difference is deliberate and worth the review's
attention.** Record endpoints return 404 so the archive never confirms a
record exists to someone not entitled to it. The admin API has no such
secret: that an admin API exists is not sensitive, and returning 404
would make "am I an administrator?" undiagnosable for a legitimate
administrator with a broken link. Flagged as **D-ADM-1** (§7) rather
than decided.

---

## 4. Service and repository work

### 4.1 New methods on `AccessGrantRepository`

```java
AccessGrant create(InstitutionalIdentifier grantee, GrantType type,
                   String unitNumber, boolean includeDescendants, String ioValue,
                   String grantedBy, String approvedBy, String reason,
                   Instant expiresAt);
AccessGrant modify(long grantId, Instant expiresAt, boolean includeDescendants);
void        revoke(long grantId, String revokedBy, String reason);
List<AccessGrant> findAllForGrantee(InstitutionalIdentifier grantee);  // incl. revoked
```

Revocation is a **status change, never a delete** — the grant history is
the audit trail's backing evidence.

### 4.2 New service methods on `ArchiveAdminService`

Each follows the established shape: `requireAdministrator(actor)` →
mutate → `audit.record(...)`, all `@Transactional`, so the audit row and
the change commit together or not at all.

```java
AccessGrant grantAccess(actor, subject, GrantSpecification spec);   // ACCESS_GRANTED
AccessGrant modifyAccess(actor, subject, long grantId, …);          // ACCESS_MODIFIED
void        revokeAccess(actor, subject, long grantId, reason);     // ACCESS_REVOKED
void        setSuspended(actor, subject, boolean suspended, reason);
IdentityLink linkIdentity(actor, subject, KualiPersonId principal, reason);
void        unlinkIdentity(actor, long linkId, reason);
List<AuditEntry> auditHistory(actor, AuditQuery query);
IdentityOverview describeIdentity(actor, subject);
```

Two gaps in `AdminAuditAction` must be filled, because suspension and
linking are currently unauditable:

```java
IDENTITY_SUSPENDED, IDENTITY_RESTORED, IDENTITY_LINKED, IDENTITY_UNLINKED
```

`recordGrantChange`'s whitelist (`ACCESS_*` only) must be widened to
accept these, or they will throw. Adding enum values requires **no
migration** — `access_audit.action` is `VARCHAR(40)`, unconstrained.

### 4.3 Suspension semantics

`authz.person_status` is a per-identity override: `suspended = true`
denies **everything** regardless of grants, and `IdentityResolver`
already honours it (`IdentityResolution.Suspended`). So suspension needs
a writer and nothing else. It is the right tool for "stop this person
now, decide about their grants later" — grants survive, so restoring is
one action rather than a re-grant.

---

## 5. UI design

New feature `ui/src/features/admin/` with pages under `ui/src/pages/admin/`,
routed in [`App.tsx`](../../ui/src/App.tsx) under `/admin`, matching the
existing workspace page shape (Header → Relationships → History →
Supporting Data).

| Route | Page | Purpose |
|---|---|---|
| `/admin` | `AdminHomePage` | administrator count, recent audit activity, search entry |
| `/admin/identities` | `IdentitySearchPage` | capability 1 — search, with the source of each result labelled |
| `/admin/identities/:id` | `IdentityWorkspacePage` | capabilities 2–6 — the main screen |
| `/admin/administrators` | `AdministratorsPage` | capability 8 |
| `/admin/audit` | `AuditHistoryPage` | capability 7 |

### 5.1 `IdentityWorkspacePage` — the screen that matters

```
┌─ Identity ────────────────────────────────────────────────┐
│ <institutional identifier>          [Suspend] [Admin role]│
│ Status: Active · Linked to archive principal ########     │
│ ⚠ No archive access  ← shown whenever grants are empty    │
├─ Archive access (grants) ─────────────────────────────────┤
│ CENTRAL    granted 2026-10-27 by …   [Modify] [Revoke]    │
│ UNIT 1234 (+ sub-units)              [Modify] [Revoke]    │
│ SAP Account 9500012345  — Awards only [Modify] [Revoke]   │
│                                        [+ Grant access]   │
├─ Research staff relationships (read-only) ────────────────┤
│ Listed as PI on 14 Awards, 3 Proposals                    │
│ ℹ Historical. Confers no access unless granted below.     │
├─ Identity links ──────────────────────────────────────────┤
│ Cognito <issuer>/<subject> → principal ########  [Unlink] │
├─ Audit history for this identity ─────────────────────────┤
│ 2026-10-27  ACCESS_GRANTED  CENTRAL   by …                │
└───────────────────────────────────────────────────────────┘
```

Three UI rules that carry architectural weight:

1. **"⚠ No archive access"** must appear for any identity with no active
   grant — including an ARCHIVE_ADMIN. It makes rule 8 visible rather
   than merely true, and prevents the most likely support ticket ("I'm
   an admin, why can't I see anything?").
2. **Research staff relationships are read-only and captioned
   "Confers no access unless granted below."** This is the UI expression
   of *historical visibility ≠ active authorization*. A `CONTACT_DERIVATION`
   grant is a deliberate, separate action.
3. **Any KRIM `ACTV_IND` shown is captioned "Historical — as at
   `<extracted_at>`"** and never styled as an access state.

### 5.2 Destructive actions

Revoke, suspend and admin-revoke each require a typed **reason** — the
`reason` column exists on all three tables and an unexplained audit row
is a poor audit row. Admin-revoke additionally shows the live
administrator count and disables itself at one, so the 409 is a
last-resort guard rather than the primary UX.

### 5.3 Discoverability

The `/admin` nav entry renders only when `GET /api/v1/admin/administrators`
succeeds for the current user. Hiding it is convenience, **not**
security — the server-side check in `ArchiveAdminService` is the control,
and it is enforced regardless of what the UI renders.

---

## 6. Proposed tests

API (`@SpringBootTest` + Testcontainers, mirroring the four record
suites; any new suite must be named in **both** lists in `ci.yml`):

| # | Test | Proves |
|---|---|---|
| T1 | `nonAdministratorCannotReachAnyAdminEndpoint` (parameterised over all 13) | the gate has no gap |
| T2 | `administratorWithNoGrantSeesNoRecords` | **rule 8**, end to end |
| T3 | `grantedAccessIsVisibleOnTheVeryNextRequest` | no caching |
| T4 | `revokedGrantDeniesOnTheVeryNextRequest` | **rule 6** |
| T5 | `suspensionOverridesEveryGrant` | suspension semantics |
| ~~T6~~ | `revokingTheLastAdministratorIsRefused` | last-admin guard — **already exists** in `ArchiveAdminConcurrencyIntegrationTest` |
| ~~T7~~ | `concurrentRevocationOfBothRemainingAdministratorsLeavesOne` | the `FOR UPDATE` lock serialises — **already exists** |
| T8 | `everyAdminMutationWritesExactlyOneAuditRow` (parameterised) | D4 |
| T9 | `auditRowAndChangeRollBackTogether` | same-transaction guarantee |
| T10 | `inactiveKrimPrincipalDoesNotAffectAnyAdminDecision` | **rule 2** |
| T11 | `authenticationAloneYieldsNoAccess` | **rule 7** |
| T12 | `adminTreeReturns404WhenEnforcementDisabled` | off-by-default preserved |
| T13 | `bootstrapIsNotReachableOverHttp` | no escalation route |

UI (`node:test` on presentation helpers, the only test style the UI has):

| # | Test |
|---|---|
| U1 | `noAccessWarningShownWhenGrantsEmpty` — including for administrators |
| U2 | `ioGrantIsLabelledSapAccountAndScopedToAwards` |
| U3 | `contactRelationshipsRenderWithTheNoAccessCaption` |
| U4 | `adminRevokeDisabledAtOneAdministrator` |

T2, T10 and T11 are the three that encode the corrected architecture.

T6 and T7 are struck through because
[`ArchiveAdminConcurrencyIntegrationTest`](../../api/src/test/java/edu/bu/archive/adapter/out/persistence/authorization/ArchiveAdminConcurrencyIntegrationTest.java)
already covers them, including the concurrent case and the bootstrap
refusal. No new work there — listed so the review can see the guard is
tested rather than merely designed.

---

## 7. Implementation sequence

Each step is independently reviewable, and authorization stays **off**
throughout.

| Step | Work | Gate |
|---|---|---|
| 1 | `AdminAuditAction` additions + widen `recordGrantChange` | — |
| 2 | `AccessGrantRepository` write paths + `person_status` writer | — |
| 3 | `ArchiveAdminService` grant/suspend/link/audit methods (T5–T10) | — |
| 4 | `AdminAuthorizationController` + DTOs (T1–T4, T11–T13) | D-ADM-1 |
| 5 | UI feature and pages (U1–U4) | — |
| 6 | Bootstrap runbook — documented, **not run** | D-ID-2 |

Steps 1–5 add no behaviour while enforcement is off: the admin tree 404s
and no grant is read by anything. Nothing in this sequence enables
authorization, applies a migration, or creates a real grant.

---

## 8. Open decisions

| # | Decision | Blocks |
|---|---|---|
| **D-ADM-1** | 403 vs 404 for a non-administrator on the admin API (§3.4). Recommendation: **403** | step 4 |
| **D-ADM-2** | Must a grant require a second approver (`access_grant.approved_by` exists and is nullable)? Recommendation: **not for CENTRAL-only in dev**, decide before production | step 3 |
| **D-ADM-3** | Default grant expiry — none, or a required review date? | step 3 |
| **D-ADM-4** | Should administrators be able to search `archive.person`, given it holds BU personnel data? Recommendation: **yes**, restricted to administrators, since it is the only way to link a person who has never signed in | step 4 |
| **D-ID-2** | First administrators and who runs the bootstrap | step 6 |
| **D-ID-3** | Which BU attribute is the institutional identifier | enabling authorization |
| **D-ID-4** | KRIM backfill into dev RDS — without it, identity search finds 7.89% of people | usability of step 4 |
