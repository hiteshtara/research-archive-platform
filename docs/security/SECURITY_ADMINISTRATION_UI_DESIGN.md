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
| `administrationAvailable` | `administrator && enforcementEnabled` — the only field the toggle reads |

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

Rendered **only** when `administrationAvailable === true`. Absent — not
disabled — otherwise, so a non-admin sees exactly today's header.

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
| **Enforcement disabled** | `administrator: true`, `enforcementEnabled: false` | toggle **hidden**; direct `/admin/*` shows "Security Administration is unavailable while record enforcement is disabled" — explanatory, not an error |
| Unauthorized (401) | token expired | existing session-expired path; re-authenticate |
| Forbidden (403) | non-admin hits an admin API | "You do not have administrator access", no retry |
| Not found (404) | admin API with enforcement off | treated as **enforcement-disabled**, not as an error |
| API failure (5xx) | server fault | "Administration is temporarily unavailable", retry offered |

The 404-means-disabled mapping matters: without it the UI would show "not
found" for a feature that exists and is merely switched off.

## 8. Security review

| # | Requirement | How it holds |
|---|---|---|
| 1 | Toggle only for active ARCHIVE_ADMIN | server-computed `administrationAvailable`; client never derives it |
| 2 | Never rely on hiding UI as access control | **hiding is cosmetic only.** All 12 endpoints call `requireAdministrator(actor)` independently; typing `/admin/identities` loads a page whose every call is refused |
| 3 | No impersonation or bypass | no act-as, no identity override; identity comes from the validated token alone, as the earlier audit confirmed |
| 4 | No sensitive detail in browser logs | no console logging of identifiers, tokens or grant detail; capabilities returns booleans only |
| 5 | Enforcement OFF: do not expose disabled routes | toggle hidden; `/admin/*` renders the explanatory state and issues **no** admin API calls |
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
