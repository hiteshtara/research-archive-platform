# Security Administration — combined integration and automated test plan

Covers **PR #142** (`GET /api/v1/me/capabilities`, API) and **PR #138**
(Security Administration UI, toggle, route guard). Neither is merged or
deployed. This plan is what should be true before either is.

Prepared 2026-11-13 against a **real trial merge** of both branches onto
main `7b19533`, not against the branches in isolation.

---

## 1. Integration state

| Check | Result |
|---|---|
| #142 merges onto main | clean, no conflict |
| #138 merges onto main | clean, no conflict |
| #142 + #138 + main together | clean |
| File overlap between the two PRs | **none** — #142 is API-only, #138 is UI-only |
| API clean `test-compile` on the combined tree | pass |
| UI `tsc -b && vite build` on the combined tree | pass |
| Behind main | #142 by 16 commits, #138 by 21 |

The two PRs share no file, so the coupling between them is a **contract**,
not a merge. Both sides name the same three booleans:

| API (`CapabilitiesResponse`) | UI (`readCapabilities`) |
|---|---|
| `administrator` | `raw.administrator === true` |
| `enforcementEnabled` | `raw.enforcementEnabled === true` |
| `administrationAvailable` | `raw.administrationAvailable === true` |

The UI uses strict `=== true` throughout and treats a null, a non-object,
a non-JSON body and a failed request all as "not an administrator", so a
contract drift degrades to **denied**, never to a false grant.

---

## 2. Defect found — must be fixed before #138 merges

**`securityAdminPresentation.test.mjs` is never executed by CI.**

`ui/package.json`'s `test` script enumerates feature directories one by
one, and `securityAdmin` is not among them:

```
"test": "node --test src/features/access/*.test.mjs src/features/admin/*.test.mjs … src/features/subaward/*.test.mjs"
```

CI runs exactly that script (`.github/workflows/ci.yml`, "Unit tests").

Measured on the trial merge:

| Command | Tests |
|---|---|
| `npm run test` (what CI runs) | 852 pass |
| `node --test src/features/securityAdmin/*.test.mjs` | **14 pass — none of them in the 852** |

So PR #138 ships 14 tests, all of them green, that **no pipeline will ever
run**. A later regression in the capability matrix would merge silently.

**Fix** — add one glob to `ui/package.json`:

```diff
-"test": "node --test src/features/access/*.test.mjs src/features/admin/*.test.mjs …
+"test": "node --test src/features/access/*.test.mjs src/features/admin/*.test.mjs src/features/securityAdmin/*.test.mjs …
```

Not applied here: #138 is under review and this plan does not modify it.

> The enumerated list is the underlying hazard — every new feature
> directory must be remembered. A single `src/features/**/*.test.mjs`
> glob would remove the class of defect, but that is a wider change than
> this feature needs and is noted, not proposed.

---

## 3. What is already covered automatically

### API — PR #142: 27 tests, all passing on the combined tree

| Suite | Tests | Covers |
|---|---|---|
| `CapabilitiesServiceTest` | 13 | the decision: identity resolution, ACTIVE membership, enforcement state, three fail-closed paths |
| `MeCapabilitiesControllerTest` | 8 | HTTP: 401 without/with a bad token, reachable with enforcement off, exactly three boolean keys, `Cache-Control: no-store` |
| `CapabilitiesMembershipIntegrationTest` | 6 | the real `archive` schema via Testcontainers: no row, revoked row, suspended identity holding an active row, plus a non-vacuity check |

`CapabilitiesMembershipIntegrationTest` is correctly routed in
`.github/workflows/ci.yml` — added to the database-suites job and excluded
from the unit job, matching how every other Testcontainers suite is handled.

### UI — PR #138: 14 tests, passing but **not run by CI** (§2)

The central one is an eight-row table covering **every** combination of the
three booleans, asserting toggle visibility, usability, and route state for
each.

---

## 4. The three behaviours you asked to confirm

### 4.1 The endpoint works with enforcement OFF — confirmed

`MeCapabilitiesControllerTest.withEnforcementOffTheEndpointIsReachableAndNotFourOhFour`
and `CapabilitiesMembershipIntegrationTest.theRealMembershipTableIsReadableWithEnforcementOff`.

The endpoint is deliberately **not** gated on enforcement — unlike the
administrative API — because the UI must be able to ask "should I render a
toggle?" in exactly the state DEV is in today.

### 4.2 Only ACTIVE administrators see the toggle — confirmed, in two layers

**Server** (`CapabilitiesService`): `administrator` is true only when the
identity resolves to `IdentityResolution.Mapped` **and**
`adminRoles.findActive(...)` returns a row. `Mapped` is matched by
`instanceof` on a sealed interface, so `NotProvisioned`, `Ambiguous`,
`Revoked` and `Suspended` deny *by construction* — a future variant cannot
silently become permitted. Any exception on either path logs a warning and
returns `denied()`.

**Client** (`showsModeToggle`): keys on `administrator === true &&
verified === true`. A non-administrator sees no toggle regardless of what
the other two booleans say — asserted directly.

### 4.3 Administration controls stay unavailable while enforcement is OFF — confirmed, independently on both sides

**Server.** All **12** endpoints on `AdminAuthorizationController` obtain
their actor through one private `actor()` helper whose first statement is
`requireEnforcementEnabled()`, which throws `NoSuchElementException` →
**404**. Verified by reading the wiring, because the class javadoc's claim
("the whole tree 404s while enforcement is off") is not implemented by an
annotation — there is no `@ConditionalOnProperty`. It is a runtime check,
and it holds because every endpoint routes through that one helper.

**Client.** With `administrator: true, enforcementEnabled: false`:
`showsModeToggle` → `true`, `adminRouteState` → `"unavailable"`,
`mayCallAdminApi` → `false`, `rendersInteractiveControls` → `false`.

The toggle stays visible on purpose: an administrator should be able to see
that the area exists and why it is inert, rather than find it missing. The
screen behind it explains the state and renders **no** interactive control —
the test naming the DEV behaviour it prevents: *"/admin/identities renders a
working search box while every admin endpoint answers 404."*

---

## 5. Gaps automated tests do not cover

This repo has **no component-render harness** — no `@testing-library/react`,
no jsdom, no vitest. `*.test.mjs` files are `node:test` unit tests of pure
presentation helpers. So the following are **untested by any suite** and
need manual verification:

| Not covered | File |
|---|---|
| The toggle actually renders, and where | `ModeToggle.tsx` |
| Route guard redirects/blocks as intended | `AdminRouteGuard.tsx` |
| The unavailable screen's rendered content | `SecurityAdminUnavailable.tsx` |
| The hook's fetch/caching/error behaviour | `useCapabilities.ts` |
| Nav entry visibility | `AppLayout.tsx`, `App.tsx` |
| The real endpoint over real Cognito | end-to-end |

Adding a render harness is a larger decision than this feature; the
manual sequence below is the alternative, and it is the one this plan
assumes.

---

## 6. Manual DEV integration sequence — for a later, separately approved deployment

Not authorised now. Preconditions: enforcement **OFF**, auditing **OFF**,
V089 unapplied, grant 2 unchanged, revision 94 the rollback target.

| # | Step | Expected |
|---|---|---|
| 1 | Fix §2, merge #142 then #138, CI green | securityAdmin tests appear in the CI tally |
| 2 | Deploy to DEV, re-pin per `docs/runbooks/deployment/IMAGE_PIN_UPDATE.md` | `terraform plan` → `No changes.` |
| 3 | `GET /api/v1/me/capabilities` with **no** token | **401**, no body disclosure |
| 4 | Same with a valid **non-administrator** BU SSO token | **200**, `{administrator:false, enforcementEnabled:false, administrationAvailable:false}`, `Cache-Control: no-store` |
| 5 | Same with an **administrator** token | **200**, `{true, false, false}` — enforcement is off |
| 6 | Non-administrator loads the UI | **no** toggle, no admin nav entry |
| 7 | Administrator loads the UI | toggle **visible** |
| 8 | Administrator opens `/admin/overview` | "unavailable" screen, **zero** interactive controls |
| 9 | Browser devtools, network tab, while on that screen | **no** request to `/api/v1/admin/*` |
| 10 | Direct `GET /api/v1/admin/identities?query=…` with the admin token | **404** |
| 11 | Revoke that administrator's membership, reload | toggle disappears without a redeploy |
| 12 | Regression | Documents, Global Search, dashboard counts unchanged from revision 94 |

Step 9 is the one worth doing carefully: it is the difference between "the
UI hides the controls" and "the UI does not call the API", and only the
second is a real guarantee.

Steps 3–5 must assert the **body**, not the status. A relative `/api/...`
on the UI origin returns the SPA `index.html` with HTTP 200 — it has
produced convincing-looking fake results in this project before. Use the
absolute API origin and check `content-type: application/json`.

---

## 7. Readiness

| | Status |
|---|---|
| PR #142 | **Ready** pending review. Merges clean, 27 tests pass, CI routing correct |
| PR #138 | **Blocked** on the one-line `package.json` fix in §2 |
| Contract between them | Verified consistent, fails closed |
| Enforcement activation | **Out of scope** — nothing here turns it on |

Neither PR changes enforcement, auditing, migrations, grants, or any
infrastructure. The capabilities endpoint reports state; it grants nothing,
and every administrative endpoint re-checks the actor independently.
