# Capabilities endpoint — implementation readiness review (2026-11-12)

Reviewed against DEV revision 94. Nothing here is merged or deployed.

## 1. Headline: the server half does not exist

| Component | Where it lives | State |
|---|---|---|
| `GET /api/v1/me/capabilities` | — | **not implemented anywhere** — not on `main`, not on any branch |
| Contract for it | `docs/security/CAPABILITIES_ENDPOINT_CONTRACT.md` (PR #139) | approved, complete |
| UI that consumes it | `ui/src/features/securityAdmin/` (PR #138) | written, **not on `main`** |
| Security Administration UI | PR #138 | written, not merged |

Verified by searching `origin/main` for any `capabilit*` path and for
`capabilities` in `api/src/main/java`: **no matches**. So the entire
feature is unmerged, and the API side is unstarted.

## 2. Two defects found in the UI, both fixed in this commit

The UI was written against an endpoint that does not exist yet, so
nothing had ever exercised it. Both defects would have failed silently.

### 2.1 It called a relative path (would never reach the API)

`useCapabilities.ts` fetched `/api/v1/me/capabilities` **relative**.
Every other call in the app goes through `request()` in
`ui/src/api/client.ts`, which prefixes `API_BASE_URL`
(`VITE_API_BASE_URL`) — the API is a **different host** from the UI.

On the deployed UI origin a relative `/api/...` is served the
single-page app's own `index.html` with **HTTP 200** and
`content-type: text/html`. So `response.ok` is `true` and the body is
not the contract's JSON. The call could **never** have reached the
endpoint, even after it ships — the toggle would simply never appear,
and the reason would look like "not an administrator".

This is the same trap that produced five bogus sub-200 ms "Documents"
timings earlier today; it is a property of the deployment, not of this
hook, and anything fetching `/api/...` relatively from this UI is wrong.

### 2.2 It sent no `Authorization` header

The bare `fetch` carried only `Accept`. `request()` attaches
`Authorization: Bearer <access token>` from `accessToken()`. Even with
the path corrected, the endpoint would have answered **401** for every
caller, which `readCapabilities` correctly reads as "not an
administrator" — the right failure, for the wrong reason, forever.

### 2.3 The fix

`getCapabilities()` now lives in `ui/src/api/client.ts` alongside every
other call, so it inherits the absolute `API_BASE_URL` and the bearer
token. It is deliberately **not** routed through `request()`, because
`request()` throws on any non-2xx and here 404 (not deployed yet) and
401 (session gone) are ordinary answers meaning "not an administrator",
not failures to surface.

It additionally **asserts the content type is JSON** rather than
trusting the status, so an HTML body can never be parsed as a
capability. A non-2xx, a non-JSON body, a parse failure, a network
error and a missing token all return `null`, which `readCapabilities`
treats as "cannot verify" and **fails closed**.

Verified: `tsc -b && vite build` clean, `oxlint` clean apart from the
pre-existing `securityAdminPreview.tsx` warning that pre-merge checklist
§11a already removes, `npm run test` **852 passing**.

## 3. What the fail-closed behaviour now is, end to end

| Server state | Response | `readCapabilities` | Toggle |
|---|---|---|:--:|
| endpoint not deployed | 404 → `null` | cannot verify | hidden |
| session expired | 401 → `null` | cannot verify | hidden |
| SPA fallback HTML | 200 text/html → `null` | cannot verify | hidden |
| network failure | throw → `null` | cannot verify | hidden |
| authenticated non-administrator | 200 all `false` | not an administrator | hidden |
| administrator, enforcement OFF | 200 `administrator: true` | administrator | **shown**, screen unavailable |
| administrator, enforcement ON | 200 all `true` | administrator | **shown**, screen usable |

The two approved behaviours hold: an administrator sees the toggle even
with enforcement OFF, and a non-administrator never sees it.

## 4. Next implementation steps, in order

**Step 1 — build the endpoint (the only thing blocking everything else).**
Per the approved contract:

- `GET /api/v1/me/capabilities`, exactly three boolean fields:
  `administrator`, `enforcementEnabled`, `administrationAvailable`.
  **No `auditingEnabled`** — explicitly excluded.
- `administrator` resolved by **reading administrator membership
  directly**, never by calling the administrator API, which 404s in
  exactly the state this must work in (enforcement OFF).
- A **suspended** identity holding a membership row is **not** an
  administrator.
- Only one error status: **401**. Every other outcome is 200 with
  `false` values, including identity-resolution and membership-read
  failures, which are logged server-side.
- `Cache-Control: no-store`.

Suggested placement, mirroring the existing concrete-service pattern
rather than the IRB ports pattern: a small `MeCapabilitiesController` in
`adapter/in/web`, calling a service that reads membership through the
existing authorization store. It must not depend on
`ArchiveAdminService`, whose methods are gated on enforcement.

**Step 2 — tests from the contract's own table.** The eight cases in
§7 are already enumerated: no token, expired token, authenticated with
no institutional identity, identity with no membership, active
administrator with enforcement off and on, suspended identity holding a
membership row. These should be a `@WebMvcTest` plus a
Testcontainers-backed membership case. **Register any new database suite
in BOTH CI lists** — excluded from the non-database job, added to the
database job — or it runs in the wrong job and never in the right one.

**Step 3 — merge PR #138's UI**, which is now consistent with the
client and fails closed. Complete pre-merge checklist §11a first:
delete `ui/security-preview.html` and `ui/src/securityAdminPreview.tsx`,
verifying against the **built** bundle, and retire `qaPreview.tsx`
alongside it.

**Step 4 — verify on DEV** with enforcement still OFF: an administrator
sees the toggle and the "unavailable" screen; a non-administrator sees
no toggle; zero administrator API calls are made from the overview
screen.

## 5. Explicitly out of scope here

The **self-grant approval safeguards** remain a separate security
workstream (PR #139's `SELF_GRANT_APPROVAL_DESIGN.md`) and are not a
prerequisite for the capabilities endpoint. The capabilities endpoint
grants nothing: all twelve administrator endpoints independently call
`requireAdministrator(actor)`, so a forged `administrator: true` changes
no authorization outcome.

The one genuinely new disclosure remains `enforcementEnabled` to any
authenticated caller, accepted in the approved contract.
