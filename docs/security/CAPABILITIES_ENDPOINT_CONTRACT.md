# `GET /api/v1/me/capabilities` — implementation contract

**Contract for review. Not implemented.** Phase 1 of the Security
Administration UI, separately gated because it is the only new backend surface.

---

## 1. Why it exists

The UI has **no way to know whether the signed-in user is an administrator**.
There is no `/api/me`, no whoami, and no `isAdministrator` state anywhere
outside the administrator pages. `App.tsx` says so: route gating is
*"convenience only; the server-side ARCHIVE_ADMIN check in ArchiveAdminService
is the control."*

It cannot live under `/api/v1/admin`, because that tree is gated on
`enforcement-enabled` and returns **404** when enforcement is off. A UI asking
"am I an admin?" would then receive 404 (feature off), 403 (not an admin) or
200 (admin) — and **cannot distinguish the first two**, which is precisely the
distinction the toggle depends on.

## 2. Request and response

```
GET /api/v1/me/capabilities
Authorization: Bearer <Cognito access token>      required
Cache-Control: no-store                           on the response
```

```json
{ "administrator": true, "enforcementEnabled": false, "administrationAvailable": false }
```

| Field | Meaning |
|---|---|
| `administrator` | the caller resolves to an **active** ARCHIVE_ADMIN membership |
| `enforcementEnabled` | `app.authorization.enforcement-enabled`, as the running process sees it |
| `administrationAvailable` | `administrator && enforcementEnabled` **and any further prerequisite** |

Exactly three booleans. No identity, no identifier, no grant, no count, no
name. The response is the same shape for everyone.

## 3. Independent verification, including when enforcement is OFF

**The requirement that shapes the implementation.** `administrator` must be
determined by **reading administrator membership directly**, not by asking the
administrator API, which is unavailable in exactly the state this must work in.

The resolution chain, identical to `actor()`'s so the two cannot drift:

1. validated Cognito token → `CurrentIdentityProvider`
2. → `IdentityResolver` → `IdentityResolution.Mapped`
3. → canonical `InstitutionalIdentifier`
4. → `AdminRoleRepository.findActive(identifier)` → present **and** active

Step 4 is the independent check: a direct membership read that **does not pass
through `requireEnforcementEnabled()`**. The same repository the admin service
uses, so one source of truth, consulted without the enforcement gate.

**Suspended identities are not administrators**, even with a membership row —
the suspension check applies as elsewhere.

## 4. Status codes

| Condition | Status | Body |
|---|---|---|
| No, invalid or expired token | **401** | standard problem body |
| Authenticated, not an administrator | **200** | all three `false` |
| Authenticated administrator, enforcement **off** | **200** | `administrator: true`, other two `false` |
| Authenticated administrator, enforcement **on** | **200** | all three `true` |
| Identity resolution throws | **200** | all three `false`, failure logged server-side |
| Membership read throws | **200** | all three `false`, failure logged server-side |

Only one error status: **401**. "You are not an administrator" is a normal
answer, not an error, and **a fault fails closed to no capabilities** rather
than erroring — so a resolution problem hides the toggle instead of breaking
the archive for every signed-in user. The server logs the fault; the client
sees an ordinary negative answer.

## 5. Caching

`Cache-Control: no-store`, and **no server-side cache**. Capability is security
state: a stale `true` after a revoked membership is exactly the wrong thing to
keep. The cost is one indexed membership lookup.

Client: React Query, `staleTime: 0`, `retry: false`, refetch on window focus.

## 6. Why it cannot be used to bypass authorization

1. **It returns no data** — three booleans.
2. **It grants nothing.** All 12 administrator endpoints independently call
   `actor()` and `requireAdministrator(actor)`. A forged `administrator: true`
   in a client changes nothing about what they do.
3. **Read-only.** No write path, no state change.
4. **Not an existence side-channel.** It cannot confirm any record, identity or
   grant exists — the concern raised in the authorization audit.
5. **Nothing leaks to a non-administrator.** `false` is what they already know.

**The one genuinely new disclosure** is `enforcementEnabled` to any
authenticated user. Judged acceptable: it is a deployment flag, already
inferable from whether records are visible, and already published in the UI
bundle today (see `QA_WORKSPACE_IMPLEMENTATION_PLAN.md`). Recorded rather than
waved past.

## 7. Tests

| Case | Expected |
|---|---|
| No token | 401 |
| Expired token | 401 |
| Authenticated, no institutional identity | 200, all false |
| Authenticated, identity but no membership | 200, all false |
| Active administrator, enforcement **off** | 200, `administrator: true`, `administrationAvailable: false` |
| Active administrator, enforcement **on** | 200, all true |
| **Suspended** identity holding a membership row | 200, `administrator: false` |
| Membership **revoked** between calls | second call `administrator: false` — no caching |
| Identity resolution throws | 200, all false, fault logged |
| Response body shape | exactly three boolean keys, nothing else |
| Endpoint reachable with enforcement **off** | **not 404** — the property the admin tree cannot satisfy |

That last test is the one that matters most: it is the reason the endpoint
exists, and a regression there would silently return the UI to being unable to
tell "disabled" from "not an administrator".

## 8. Decisions taken 2026-11-12

| # | Decision |
|---|---|
| C-1 | **`auditingEnabled` is NOT added.** The initial response is exactly `administrator`, `enforcementEnabled`, `administrationAvailable`. The Configuration page shows auditing as **Unavailable** rather than inventing it |
| C-2 | `administrationAvailable` stays `administrator && enforcementEnabled`. No further prerequisites for now |
| C-3 | Path **approved** as `GET /api/v1/me/capabilities` |

Confirmed unchanged: available with enforcement **OFF**, `no-store`, and
**fails closed** on identity-resolution errors.

## 9. Not authorized

No implementation. Reviewed before any code is written.
