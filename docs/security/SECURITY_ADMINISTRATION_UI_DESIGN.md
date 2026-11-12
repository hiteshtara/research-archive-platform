# Security Administration UI — design for approval

**Design only. Nothing implemented.** No endpoint, component, route, test or
configuration change. Returned for approval before any code is written.

Everything in §1 was read in the tree at `9729eff`.

---

## 1. What already exists — reuse, do not rebuild

| Component | State |
|---|---|
| `AdminIdentitySearchPage` (4.3 KB) | **exists**, routed at `/admin/identities` |
| `AdminIdentityWorkspacePage` (10.3 KB) | **exists**, routed at `/admin/identities/:identifier` — *"Manage one identity: grants, suspension, links and its audit trail"*, with `createGrant`, `revokeGrant`, suspension and link mutations already wired |
| `AdministratorsPage` (4.9 KB) | **exists**, routed at `/admin/administrators` |
| `AdminAuditPage` (2.9 KB) | **exists**, routed at `/admin/audit` |
| `features/admin/adminPresentation.mjs` (7.5 KB) + tests (7.9 KB) | **exists** |
| 12 administrator API endpoints | **exist**, authorization proven on all 12 |

**Nothing working is rebuilt.** The new work is a shell, a capabilities
endpoint, two new pages, and tests.

### Three gaps between the mockup and the code

**Gap A — "Access Grants" has no standalone page.** Grants are managed
*per identity* inside `AdminIdentityWorkspacePage`. There is no cross-identity
grant list, and no API that returns all grants: `GET /identities/{id}` returns
one identity's grants, and nothing enumerates them. A true "Access Grants"
landing page would need a new endpoint.

**Recommendation:** make "Access Grants" a **search-first** page that reuses
identity search and routes to the existing workspace, rather than inventing a
grant-listing endpoint. It satisfies the mockup's navigation without new
backend surface. If a genuine cross-identity grant list is wanted later, that
is its own scoped change.

**Gap B — `AdministratorsPage` is not in the mockup's sidebar.** The mockup
lists five items; the page exists but has no home. **Recommendation:** surface
it under **Users & Identities** as a secondary tab or link, not as a sixth
nav item — administrators are a kind of identity, and the mockup's five-item
nav is the approved shape.

**Gap C — Unit Permissions has no authorization-side API.**
`UnitHierarchy` is backed by `archive.unit` and exposes exactly one method,
`isSameOrDescendant(unitNumber, ancestorUnitNumber)` — a predicate, not a tree
read. The tree itself is reachable through the **existing Explorer endpoint
`/api/v1/explorer/units`**, which reads the same table.

So the hierarchy is buildable with no new backend. **Access inheritance** —
which grants reach which units — is not: it requires combining the tree with
grants, and grants are readable only per identity. See §5.

---

## 2. `GET /api/v1/me/capabilities` — contract

The one new backend surface. It exists because the UI currently has **no way
to know whether the signed-in user is an administrator**: there is no `/api/me`,
no whoami, and no `isAdministrator` state anywhere in the UI.

### Why it cannot live under `/api/v1/admin`

That whole tree is gated on `enforcement-enabled` and returns **404** when
enforcement is off. A UI asking "am I an admin?" would get three
indistinguishable answers — 404 (feature off), 403 (not an admin), 200 (admin)
— and could not tell "enforcement is off" from "you are not an admin". The
endpoint must therefore sit **outside** the admin tree and **must not** be
gated on enforcement.

### Request and response

```
GET /api/v1/me/capabilities
Authorization: Bearer <Cognito access token>     (required)
```

```json
{
  "administrator": false,
  "enforcementEnabled": false,
  "administrationAvailable": false
}
```

| Field | Meaning |
|---|---|
| `administrator` | the caller resolves to an **active** ARCHIVE_ADMIN membership |
| `enforcementEnabled` | `app.authorization.enforcement-enabled` as the running process sees it |
| `administrationAvailable` | `administrator && enforcementEnabled && any further server-side prerequisites` — gates whether the admin screens **function** |

**Revised 2026-11-12.** The toggle's **visibility** keys on **`administrator`
alone**. `administrationAvailable` no longer hides the toggle; it decides
whether the Security Administration screens work or render the unavailable
state. The two were previously conflated, which would have hidden the whole
surface from administrators on DEV, where enforcement is off.

`administrationAvailable` is computed **server-side** on purpose. If the client
derived it, a client bug could show the toggle when the backend would refuse
every call.

### Authentication and identity resolution

- **Authentication required.** Unauthenticated → **401**. It is not public.
- Identity resolved exactly as the admin tree resolves it: validated token →
  `IdentityResolver` → `IdentityResolution.Mapped` → institutional identifier
  → active-membership check. **The same code path**, so the two cannot drift.
- An authenticated caller who resolves to no institutional identity, or to a
  suspended identity, gets `administrator: false` — **200, not an error**.
  "You are not an administrator" is a normal answer.

### Error handling

| Condition | Response |
|---|---|
| No/invalid/expired token | **401** |
| Authenticated, not an admin | **200**, `administrator: false` |
| Authenticated, admin, enforcement off | **200**, `administrator: true`, `administrationAvailable: false` |
| Identity resolution fails internally | **200**, all `false`, failure logged server-side |

That last row is deliberate: this endpoint **fails closed to "no capabilities"**
rather than erroring, so a resolution fault hides the toggle instead of
breaking the archive for every user.

### Caching

- `Cache-Control: no-store`. Capability is security state; a stale `true`
  after a revoked membership is exactly the wrong thing to cache.
- Client: fetched once per session on mount, held in React Query with
  `staleTime: 0` and refetch on window focus. Cheap — it is three booleans.
- **No server-side cache.** The membership read is a single indexed lookup.

### Why it cannot be used to bypass authorization

This is the central security question, and the answer is structural:

1. **It returns no data.** Three booleans. No identity, no grant, no unit, no
   record, no audit row.
2. **It grants nothing.** Every one of the 12 admin endpoints independently
   calls `actor()` and `requireAdministrator(actor)`. A forged or replayed
   `administrator: true` changes nothing about what those endpoints do.
3. **It is read-only** and has no write path.
4. **It cannot confirm a record exists**, so it is not an existence
   side-channel — the problem identified in the earlier authorization audit.
5. **It leaks nothing to a non-admin**: `administrator: false` is what a
   non-admin already knows about themselves.

The one thing it reveals that is new is `enforcementEnabled` — a deployment
configuration flag, not data. That is already inferable by any authenticated
user from whether records are visible, and is published today in the UI bundle
(see the hardening issue in `QA_WORKSPACE_IMPLEMENTATION_PLAN.md`).

---

## 3. Route map

| Route | Page | Status |
|---|---|---|
| `/admin` | redirect → `/admin/overview` | change: today redirects to `/admin/identities` |
| `/admin/overview` | **NEW** Security Administration dashboard | new |
| `/admin/identities` | `AdminIdentitySearchPage` | **exists** |
| `/admin/identities/:identifier` | `AdminIdentityWorkspacePage` | **exists** |
| `/admin/administrators` | `AdministratorsPage` | **exists**, linked from Users & Identities |
| `/admin/grants` | search-first page reusing identity search (Gap A) | new, thin |
| `/admin/units` | **NEW** Unit Permissions | new |
| `/admin/audit` | `AdminAuditPage` | **exists** |
| `/admin/configuration` | **NEW** read-only Configuration | new |

Archive routes are **untouched**.

## 4. Component architecture

```
AppLayout                                   (modified: toggle slot in header)
├── useCapabilities()                       NEW hook -> /api/v1/me/capabilities
├── ModeToggle                              NEW, rendered only when administrationAvailable
├── navigationPresentation.mjs              (unchanged)
├── adminNavigationPresentation.mjs         NEW pure module + tests
└── <Outlet/>

features/admin/
├── adminPresentation.mjs                   (exists, unchanged)
├── adminModePresentation.mjs               NEW: mode state, nav items, status derivation
└── adminModePresentation.test.mjs          NEW
```

Following this repository's convention, **all decision logic lives in pure
`.mjs` presentation modules with `node:test` coverage**; the `.tsx` components
render what those modules decide. No logic in JSX.

### Header toggle

Inserted into the existing right-hand `Stack` in `AppLayout.tsx`, between the
signed-in user block and Sign out — matching the mockup. BU logo, title,
**Development** chip, user and Sign out are **unchanged**.

**Visibility rule (revised 2026-11-12):**

| `administrator` | `administrationAvailable` | Toggle | Selecting Security Admin |
|---|---|---|---|
| `false` | — | **absent** | n/a — route denied |
| unverifiable / request failed | — | **absent (fail closed)** | n/a |
| `true` | `true` | **shown** | full administration |
| `true` | `false` | **shown** | **explicit unavailable screen** |

Absent — not disabled — for non-administrators, so a non-admin sees exactly
today's header with no hint the mode exists.

**Fail closed:** if the capabilities call 401s, 5xxs, times out, or returns
anything unparseable, the UI treats `administrator` as `false` and hides the
toggle. There is no default-visible path.

## 5. Unit Permissions (new page)

Built on `GET /api/v1/explorer/units`, which reads `archive.unit` — **the same
table `JdbcUnitHierarchy` uses**, so the tree shown is the tree authorization
applies.

**Shows:** the unit tree with numbers and names, expandable; and for a selected
unit, which ancestors would reach it under `LEAD_UNIT_WITH_DESCENDANTS`.

**Does not show — and this is a deliberate limit:** "which identities hold a
grant reaching this unit." No API can answer it. Grants are readable only per
identity, and nothing enumerates them (Gap A). Inventing a client-side
cross-product over every identity would be slow and would read the whole grant
table into the browser.

**Recommendation:** ship the hierarchy and inheritance *rules* view now, and
treat "who can reach this unit" as a separate, scoped change with its own
endpoint. Shipping a half-answer labelled as access inheritance would be worse
than shipping the rules clearly.

## 6. Configuration (new page, read-only)

Renders **only** `enforcementEnabled` and `auditingEnabled` from verified API
data, plus a count of active grants **when obtainable**.

**No toggle, no mutation, no form.** Displays state; never changes it.

**The honest constraint, which the mockup cannot satisfy as drawn:** the
mockup's System Status bar shows `Active Grants: 2` while
`Authorization Enforcement: OFF`. **That number is unobtainable in that state** —
every admin endpoint 404s when enforcement is off, and your own requirement
says never hardcode grant counts or security states.

So Configuration renders:

- enforcement and auditing from `/api/v1/me/capabilities` (always available)
- active grant count **only when `administrationAvailable`**; otherwise an
  explicit **"Unavailable while enforcement is disabled"**, not a number

`auditingEnabled` needs adding to the capabilities response, or a second field;
it is configuration state of the same kind.

## 7. States (requirement 6)

| State | Trigger | Render |
|---|---|---|
| Loading | capabilities in flight | skeleton in the toggle slot; **no toggle** until resolved |
| Normal archive | `administrator: false` | today's UI exactly |
| Admin available | `administrationAvailable: true` | toggle shown |
| **Enforcement disabled** | `administrator: true`, `enforcementEnabled: false` | toggle **SHOWN**; selecting it renders the unavailable screen (§7a). **Zero admin API calls are issued.** Explanatory, not an error |
| Unauthorized (401) | token expired | existing session-expired path; re-authenticate |
| Forbidden (403) | non-admin hits an admin API | "You do not have administrator access", no retry |
| Not found (404) | admin API with enforcement off | treated as **enforcement-disabled**, not as an error |
| API failure (5xx) | server fault | "Administration is temporarily unavailable", retry offered |

The 404-means-disabled mapping matters: without it the UI would show "not
found" for a feature that exists and is merely switched off.

### 7a. The unavailable screen (requirements 2, 3, 7)

Rendered whenever `administrationAvailable === false` and the user is an
administrator — at `/admin/overview` and at **every** `/admin/*` route.

**What it shows:** that Security Administration exists, that it is unavailable
because record-level enforcement is currently disabled, that this is a
configuration state rather than a fault or a permissions problem, and that no
action is available from this screen.

**What it must not do — three hard rules:**

1. **Zero admin API calls.** The capability state is known *before* any admin
   request is attempted, so the screens must not call and then interpret a
   404. Implementation: the admin route element checks capabilities first and
   short-circuits; the React Query hooks on the four existing pages are
   `enabled: administrationAvailable`, so they never fire.
2. **No functional search form** (requirement 7). `AdminIdentitySearchPage`
   today renders *"Find an identity"* with a working search box. Under this
   state that form must **not** render — a box that cannot work is worse than
   an honest message. This is a guard added to an existing page, not a rewrite.
3. **No fabricated status.** No grant counts, no "0 users", no placeholder
   figures. Unobtainable values are absent or labelled unavailable.

**Verified against the live system 2026-11-12:** at `/admin/identities` on
revision 93 with enforcement off, the page renders its "Find an identity"
search form, issues **no** API call on load, and all three admin endpoints
answer `404 NOT_FOUND`. So today a tester can type a query and press Search
into a dead end. Requirement 7 is fixing an observed behaviour, not a
hypothetical one.

## 7b. Self-service administrator workflows

The six workflows, each mapped to the endpoint that already serves it. **All
six are supported by existing APIs — no new permission API is required.**

| # | Workflow | Existing endpoint | State |
|---|---|---|---|
| 1 | Search and identify users | `GET /identities?q=` | exists |
| 2 | View current roles and access | `GET /identities/{id}` — links, grants, administrator flag, recent audit | exists |
| 3 | Assign and revoke permissions | `POST /identities/{id}/grants`, `PATCH .../grants/{id}`, `DELETE .../grants/{id}` | exists |
| 4 | Manage authorized administrators | `GET`/`POST /administrators`, `DELETE /administrators/{id}` | exists |
| 5 | Link authenticated to historical Kuali identities | `POST /identities/{id}/links`, `DELETE .../links/{linkId}` | exists |
| 6 | Review the audit trail | `GET /audit` | exists — **and was broken until revision 93** (the untyped-parameter defect) |

### RAP's actual permission model — not Kuali's

Grants are **`CENTRAL`, `UNIT`, `IO`, `CONTACT_DERIVATION`, `PARENT_AWARD`**,
each optionally with `includeDescendants` and an expiry. There are no Kuali
roles, no permission strings and no role hierarchy. The UI must present these
five and nothing else.

**`ARCHIVE_ADMIN` membership is separate from and confers no record access** —
administering access does not grant it (acceptance cases 20 and 20b). The
interface must not imply otherwise by showing administrators as a kind of
privileged data access.

### Confirmation before sensitive changes

Revoking a grant, unlinking an identity, suspending an identity, and
adding or removing an administrator each require explicit confirmation naming
the subject and the effect. Grant creation requires review of scope, unit and
descendants before submission — the mistake to prevent is a wrong *scope*, not
a mis-click.

### Escalation controls — what the backend does and does not enforce

Verified by reading `ArchiveAdminService`:

| Control | State |
|---|---|
| Every operation requires an active ARCHIVE_ADMIN | **enforced** — `requireAdministrator(actor)` on all 12 |
| The last administrator cannot be removed | **enforced** — 409 |
| Relink four-eyes: the requester cannot approve their own relink | **enforced** |
| Relink four-eyes: the approver cannot be the identity being linked | **enforced** — *"that would put one person on both sides of the decision"* |
| Every security change is audited | **enforced** — actor, action, subject, timestamp |
| Duplicate active grant for one scope | **enforced** — V087 unique index, surfaced as a controlled 409 |
| **An administrator granting themselves a grant** | **NOT PREVENTED** |

**The self-granting gap, stated plainly.** `grantAccess(actor, subject, …)`
calls `requireAdministrator(actor)` and `requireSubject(subject)` and then
creates the grant. **Nothing compares `actor` to `subject`.** An ARCHIVE_ADMIN
can therefore grant themselves any scope, including `CENTRAL`, which reaches
every record.

It is **audited but not prevented** — the audit row names the actor and the
subject, so it is detectable after the fact and attributable, but the operation
succeeds.

This matters directly to the requirement to "prevent unauthorized privilege
escalation and self-granting": **the backend does not currently do so.** The
relink path has genuine four-eyes; the grant path does not.

**This design does not fix it, deliberately** — a self-grant guard is a backend
authorization change, outside the approved UI scope and requiring its own
approval and tests. Options, for a separate decision: refuse `actor == subject`
outright; require a second administrator to approve a self-grant, mirroring the
relink control that already exists; or accept it and rely on audit, documenting
that choice.

**The UI must not imply a control that does not exist.** It will not describe
self-granting as blocked, and an administrator viewing their own identity will
see the same grant controls as for anyone else, because that is what the
backend permits.

## 8. Security review

| # | Requirement | How it holds |
|---|---|---|
| 1 | Toggle only for active ARCHIVE_ADMIN | server-computed `administrationAvailable`; client never derives it |
| 2 | Never rely on hiding UI as access control | **hiding is cosmetic only.** All 12 endpoints call `requireAdministrator(actor)` independently; typing `/admin/identities` loads a page whose every call is refused |
| 3 | No impersonation or bypass | no act-as, no identity override; identity comes from the validated token alone, as the earlier audit confirmed |
| 4 | No sensitive detail in browser logs | no console logging of identifiers, tokens or grant detail; capabilities returns booleans only |
| 5 | Enforcement OFF: do not expose disabled routes | toggle **shown to administrators**; `/admin/*` renders the explanatory state and issues **no** admin API calls. "Expose" means exposing a *non-functional surface*, not hiding the mode's existence from an administrator |
| 8 | Unverifiable admin status | **fails closed** — toggle hidden. No default-visible path |
| 9 | Backend independent of UI visibility | unchanged by this revision: showing the toggle while enforcement is off grants nothing, because all 12 endpoints still 404 and, when enabled, still call `requireAdministrator(actor)` |
| 6 | Status only from verified API data | no hardcoded counts or states; unobtainable values render as "Unavailable" |
| 7 | Mode switch changes no enforcement | pure client-side routing; no request alters configuration |

**Residual risk, stated:** `enforcementEnabled` becomes visible to any
authenticated user. Judged acceptable — it is a deployment flag, already
inferable from behaviour, and already public in the UI bundle today.

## 9. Implementation phases

| Phase | Content | Gate |
|---|---|---|
| **0** | Security review of this document | your approval |
| **1** | `/api/v1/me/capabilities` + tests. Backend only, no UI | approval |
| **2** | `useCapabilities`, `ModeToggle`, admin shell, nav, `/admin/overview` | — |
| **3** | Wire existing four pages into the shell; Users & Identities hosts Administrators | — |
| **4** | Configuration (read-only) | — |
| **5** | Unit Permissions (hierarchy + rules) | — |
| **6** | Browser automation, mobile layouts, QA/UAT cases | — |

Phase 1 is separately gated because it is the only new backend surface.

## 10. Test plan

**Pure module tests** (`node:test`, this repo's convention): mode derivation
for all eight combinations of the three capability booleans; nav item sets;
404→enforcement-disabled mapping; status rendering with and without an
obtainable grant count.

**Component tests:** toggle absent when `administrator: false`; absent when
`enforcementEnabled: false`; present only when `administrationAvailable`;
loading shows no toggle; archive header unchanged in archive mode.

**Authorization tests:** direct `/admin/*` navigation as a non-admin renders
access-denied and issues no admin API call; 401 → session-expired; 403 →
forbidden; 404 → enforcement-disabled; 5xx → failure with retry.

**Browser automation:** toggle switches without a page reload; archive
navigation unchanged; desktop and mobile (~400 px) layouts; **no sensitive
identifier in console output**.

**Backend tests for capabilities:** 401 unauthenticated; `false` for a
resolvable non-admin; `false` for suspended; `true`/`administrationAvailable:
false` when enforcement off; resolution failure fails closed to all-false.

## 11. QA/UAT additions (requirement 9)

New administrator cases go in the **UAT guide**, not the QA snapshot.
`qaSnapshot.json` is SHA-pinned by two guard tests and `qaOpenFindings.json`
can only make a case *more open* — neither can express a new passing case. New
IDs continue from **TC-064** (TC-056–TC-063 are taken).

Proposed: TC-064 toggle hidden for non-admin · TC-065 toggle shown for admin ·
TC-066 direct URL denied for non-admin · TC-067 enforcement-disabled state ·
TC-068 session-expired · TC-069 admin navigation reaches all five areas ·
TC-070 archive navigation unchanged · TC-071 mobile layout.

## 11a. Pre-merge checklist

**Must be completed before PR #138 merges. Not before — the scaffolding is
what renders the screens for design review.**

- [ ] Delete `ui/security-preview.html`
- [ ] Delete `ui/src/securityAdminPreview.tsx`
- [ ] Confirm nothing imports either: `grep -rn "securityAdminPreview\|security-preview" ui/` returns nothing
- [ ] Confirm the production bundle contains neither — check the **built** asset, not the source tree, since a stale build can hide a surviving import
- [ ] Re-run the UI suite and confirm the count returns to its pre-scaffolding baseline with zero failures
- [ ] Confirm `npm run build` and `npm run lint` are clean after removal

**Why it must be deleted.** The harness injects capabilities as plain props to
render the administrator screens without a session. It bypasses no
authorization — the real application reads capabilities from the server and
every administrator endpoint enforces independently — but a component that
renders administration UI from caller-supplied capability values has no place
in a production bundle, and leaving it would invite exactly the confusion this
design exists to prevent.

It mirrors `qaPreview.tsx`, which carries its own removal checklist in
`docs/QA_STATUS_PAGE.md`. **Both should be removed together**; shipping one
while deleting the other would leave the pattern half-retired.

## 12. Not authorized by this document

No implementation, endpoint, component, route, migration, test, deployment or
enforcement change. Revision 93 serving with enforcement OFF; revision 91
fallback; revision 92 unused; grant 2 unchanged; auditing OFF; V089 unapplied;
Production untouched.

## Appendix — DEF-5 preserved

Confirmed performance defect, authenticated and content-verified on
2026-11-12 against revision 93: cold Documents search with a query
**44,908 ms**; cold Global Search non-matching **18,110 ms**; matching
**13,027 ms**; Documents unfiltered **12,410 ms**; dashboard **5,276 ms**;
control award detail **286 ms**. Profiling resumes after this design review.
