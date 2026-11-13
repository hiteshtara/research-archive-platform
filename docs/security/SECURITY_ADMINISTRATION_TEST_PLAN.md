# Security Administration — combined integration and automated test plan

Covers **PR #142** (`GET /api/v1/me/capabilities`, API) and **PR #138**
(Security Administration UI, toggle, route guard). Neither is merged or
deployed. This plan is what should be true before either is.

Prepared 2026-11-13 against a **real trial merge** of both branches onto
main `7b19533`, not against the branches in isolation.

---

## 1. Integration state

Verified against a **real trial merge** of both branches onto main
`7b19533`, re-checked after the test work landed.

| Check | Result |
|---|---|
| #142 merges onto main | clean |
| #138 merges onto main | clean |
| #142 + #138 + main together | clean |
| File overlap between the two PRs | **none** — #142 API-only, #138 UI-only |
| API clean `test-compile` on the combined tree | pass |
| UI `tsc -b && vite build` | pass |
| `oxlint` | clean |
| #138 brought current with main | yes (`5ace547`) |

The two PRs share no file, so the coupling is a **contract**, not a merge.
Both sides name the same three booleans, and the UI uses strict `=== true`
throughout, so contract drift degrades to **denied**, never to a false grant.

| API (`CapabilitiesResponse`) | UI (`readCapabilities`) |
|---|---|
| `administrator` | `raw.administrator === true` |
| `enforcementEnabled` | `raw.enforcementEnabled === true` |
| `administrationAvailable` | `raw.administrationAvailable === true` |

---

## 2. The CI-execution defect — RESOLVED

`securityAdminPresentation.test.mjs` was never executed by CI: the `test`
script in `ui/package.json` enumerates feature directories individually and
`securityAdmin` was not among them.

Fixed in `cb8da49`. `src/features/securityAdmin/*.test.mjs` is now in the
glob, and CI's own log for that head prints the full command including it.

| | Before | After |
|---|---|---|
| `npm run test` helper tests | 852 | **866** (+14) |

The enumerated list remains the underlying hazard — a new feature directory
must be remembered — but the list is now correct and the gap is visible in
CI output rather than silent.

---

## 3. Automated coverage, as measured

Everything below was run on a trial merge of **#142 + #138 onto main
`7b19533`**, and separately by CI on #138 head `cb8da49`.

### 3.1 UI — now two suites, both executed by CI

`npm run test` runs `test:helpers` (node:test) then `test:components`
(vitest), so a new suite is executed rather than silently ignored.

| Suite | Tests | Result |
|---|---|---|
| Presentation helpers (`node --test`, 17 directories) | **866** | pass |
| Component tests (`vitest run`, 5 files) | **37** | pass |

CI log, head `cb8da49`:

```
> npm run test:helpers && npm run test:components
# tests 866
# pass 866
✓ src/features/securityAdmin/useCapabilities.test.tsx          (8 tests)
✓ src/features/securityAdmin/AdminRouteGuard.test.tsx          (9 tests)
✓ src/features/securityAdmin/ModeToggle.test.tsx               (6 tests)
✓ src/features/securityAdmin/SecurityAdminUnavailable.test.tsx (5 tests)
✓ src/features/securityAdmin/securityAdminRouting.test.tsx     (9 tests)
 Test Files  5 passed (5)
      Tests  37 passed (37)
```

### 3.2 The component-test harness

This repo had none, so one was added: **vitest + jsdom +
@testing-library/react**. vitest reuses the existing Vite pipeline, so TSX
needs no separate transform and no second build configuration appears.

vitest deliberately does **not** collect the `*.test.mjs` helper tests —
they import `node:test` and keep running under `node --test`.

The production bundle is unaffected: same byte size and the same content
hash as before the change, no `vitest`/`@testing-library` strings in
`dist/`, and the repo's own `verify:bundle` passes with 13 routes present.

### 3.3 API — unchanged by this work, re-verified

| Job | Tests | Result |
|---|---|---|
| API unit (CI, #138) | **1729** | pass |
| API unit (local, #142 + #138 + main) | **1750** | pass |
| PostgreSQL integration (CI, #138) | **530** | pass |
| PostgreSQL integration (local, #142 + #138 + main) | **536** | pass |

The differences are #142's own suites, absent from #138 alone: 21 unit
tests, and the 6 of `CapabilitiesMembershipIntegrationTest`, which runs
against a disposable PostgreSQL container and covers no row, a revoked
row, a suspended identity holding an active row, enforcement on and off,
plus a non-vacuity check on its fixture.

---

## 3A. What the component tests actually assert

### Requirement 4 — non-administrators never see the toggle

`ModeToggle` renders **nothing** — asserted as an empty container, not as a
hidden or disabled control — for a non-administrator and for every
unverifiable state: loading, errored, `null`, `undefined`, a non-object, and
hostile bodies such as `{administrator: "true"}` or `{administrator: 1}`.

### Requirement 5 — enforcement OFF shows the unavailable screen and calls nothing

`AdminRouteGuard` renders `SecurityAdminUnavailable` and the child route
**never mounts**. Zero administrator API calls is asserted against the
fetches the tree actually issued — every `fetch` is captured and the test
asserts no URL contains `/api/v1/admin`, after letting the tree settle.

`SecurityAdminUnavailable` renders no button, textbox, searchbox, link,
form, input, select or textarea. It reports the enforcement state it was
given and, for an unverified capability, reads **OFF** rather than ON.

### Requirement 3 — identity states and enforcement

| State | Rendered | Admin API calls |
|---|---|---|
| Active administrator, enforcement ON | real screen | permitted |
| Active administrator, enforcement OFF | unavailable screen | **0** |
| Non-administrator | access-denied | **0** |
| Suspended identity | access-denied | **0** |
| Revoked membership | access-denied | **0** |
| Expired session (401 → `null`) | access-denied | **0** |
| Request rejected (network) | access-denied | **0** |
| Still in flight | nothing administrative | **0** |

Suspended, revoked, unprovisioned, ambiguous and plain non-administrator are
**one response on the wire** — `CapabilitiesResponse.denied()`, all three
false. The endpoint does not say which, because that would disclose
membership state. The distinctions are server-side, in
`CapabilitiesMembershipIntegrationTest`, against the real schema.

### Requirement 6 — direct URL, back/forward, archive preserved

Driven through the **real `App` route table**, not a rebuilt imitation, so a
regression in `App.tsx`'s wiring cannot pass these.

- Direct entry to `/admin/overview` and `/admin/identities` — denied, zero admin calls
- `/admin` redirects to `/admin/overview` and is still guarded
- Back out of a denied admin URL returns to `/` and the denial is gone
- Forward back into it re-evaluates and denies again — the guard is not bypassable by history
- An administrator returning from the unavailable screen finds the archive intact, toggle still offered
- A non-administrator on `/` sees the ordinary archive header with no toggle and no admin nav entry

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

## 5. What remains manual

The component gap is closed — `ModeToggle`, `AdminRouteGuard`,
`SecurityAdminUnavailable` and `useCapabilities` all have automated tests
that CI runs. What jsdom cannot establish still needs a person:

| Not covered by any suite | Why |
|---|---|
| Real Cognito session, real token, real 401 | every test mocks the client boundary |
| The live endpoint answering over HTTPS | #142 is not deployed; revision 94 predates it |
| Visual layout, MUI breakpoints, the toggle's responsive `display` | jsdom computes no real styles |
| Keyboard and screen-reader behaviour | not asserted |
| A membership revoked *in the real database* mid-session | tests mock the response, not the row |
| Genuine browser history across a page reload | the router's history is in memory |

These are what §6 exists for.

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
| PR #142 | **Ready** pending review — merges clean, 27 tests pass, CI routing correct |
| PR #138 | **Ready** pending review — CI-execution defect fixed, 37 component tests added and executed |
| Contract between them | verified consistent, fails closed |
| Enforcement activation | **out of scope** — nothing here turns it on |

Both remain **drafts**, unmerged and undeployed.

Neither changes enforcement, auditing, migrations, grants, or infrastructure.
The capabilities endpoint reports state and grants nothing; all 12
administrative endpoints re-check the actor independently and 404 while
enforcement is off.

### One caveat worth stating

Every automated test here mocks the capabilities response. They prove the UI
behaves correctly **given** what the server says. That the server says the
right thing is proved separately, server-side, by #142's 27 tests — including
six against the real schema. What no suite covers is the two halves talking
to each other over a real network with a real Cognito token, and that is
exactly §6.
