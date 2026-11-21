# Decoupling Security Administration from record-level enforcement

**Design proposal. Nothing implemented, nothing configured, nothing deployed.**

Goal: let a verified active `ARCHIVE_ADMIN` use the Security Administration
screens while record-level authorization enforcement stays **OFF** for
ordinary Research Archive users.

Investigated against `main` at `3a04f97` on 2026-11-13.

---

## 1. Which endpoints are gated, and where

**All 12 are gated, at exactly one place.**

Every endpoint on `AdminAuthorizationController` obtains its actor through a
single private helper, whose first statement is the gate:

```java
private InstitutionalIdentifier actor() {
    requireEnforcementEnabled();          // <-- the gate
    return identities.current() ... ;
}

private void requireEnforcementEnabled() {
    if (!properties.isEnforcementEnabled()) {
        throw new NoSuchElementException("administrative API is not available");
    }
}
```

`NoSuchElementException` maps to **404** via `GlobalExceptionHandler`.
Verified live on DEV: all three endpoints probed returned
`404 {"code":"NOT_FOUND","message":"administrative API is not available"}`.

| # | Endpoint | Kind |
|---|---|---|
| 1 | `GET /identities` | read |
| 2 | `GET /identities/{id}` | read |
| 3 | `POST /identities/{id}/links` | write |
| 4 | `DELETE /identities/{id}/links/{linkId}` | write |
| 5 | `PUT /identities/{id}/suspension` | write |
| 6 | `POST /identities/{id}/grants` | write |
| 7 | `PATCH /identities/{id}/grants/{grantId}` | write |
| 8 | `DELETE /identities/{id}/grants/{grantId}` | write |
| 9 | `GET /audit` | read |
| 10 | `GET /administrators` | read |
| 11 | `POST /administrators` | write |
| 12 | `DELETE /administrators/{id}` | write |

**4 read, 8 write.** There is no per-endpoint gating to unpick: one helper,
one call site, 12 callers. That is why this change can be small.

---

## 2. Can administrator auth work independently of record enforcement?

**Yes. It already does.** Nothing in the authentication or
`ARCHIVE_ADMIN` authorization path consults the enforcement flag.

```
Cognito JWT  ->  CurrentIdentityProvider      (no flag)
             ->  IdentityResolver             (no flag)
             ->  IdentityResolution.Mapped    (sealed; only Mapped proceeds)
             ->  AdminRoleRepository.findActive(id)   (no flag)
             ->  ArchiveAdminService.requireAdministrator(actor)
```

`requireAdministrator` throws `NotAnArchiveAdministratorException` → **403**,
and is called independently inside **every** service method. It reads
`authz.admin_role_member` directly.

Proven live, with enforcement OFF: `GET /api/v1/me/capabilities` returned
`{"administrator":true,...}`, which required resolving a real identity link
and reading an ACTIVE membership row — both with the flag off.

So the 404 gate is a *policy* decision ("don't offer tools that cannot
work"), not a technical dependency. Removing it exposes machinery that is
already fully functional.

### The 13 flag consumers, split

| Consumer | Category |
|---|---|
| `AuthorizationConfiguration:246` → `AuthorizationEnforcementSettings` | record |
| `RecordAuthorizationService` ×3 | record |
| `RecordAuthorizationGate` ×2 | record |
| `RecordAccess:64` | record |
| `DashboardService:127` | record (scoped counts) |
| `GlobalSearchService:257` | record (scoped search) |
| **`AdminAuthorizationController:257`** | **admin availability — the only one to change** |
| `CapabilitiesService:107` | reporting |
| `ArchiveAdminBootstrapCommand:287` | informational message only |

---

## 3. Can read-only administrator functions be enabled first?

**Yes, and this is the recommended first step.** The 4/8 read/write split is
clean, and the reads are genuinely read-only:

| Endpoint | Reads | Writes |
|---|---|---|
| `GET /identities` | identity search | none |
| `GET /identities/{id}` | identity detail, links, grants | none |
| `GET /audit` | `authz.access_audit` | none |
| `GET /administrators` | `authz.admin_role_member` | none |

None touches research-record data. All four still call
`requireAdministrator`, so a non-administrator gets 403 regardless.

Enabling reads carries **no grant-mutation risk at all**, which matters
because §4 shows the write paths are where the real problems are.

---

## 4. Write operations vulnerable today

Three genuine gaps. All are latent while enforcement is OFF, because grants
are not read — which is exactly what makes them dangerous to enable
casually: **a grant written now becomes live the moment enforcement is
switched on.**

### 4.1 Self-granting — UNGUARDED

`ArchiveAdminService.grantAccess(actor, subject, specification)` performs
`requireAdministrator(actor)` and `requireSubject(subject)` and then
**never compares them**. An administrator can call
`POST /api/v1/admin/identities/{their-own-id}/grants` and grant themselves
record access.

An `actor.equals(subject)` check exists in
`provisioning/IdentityLinkCommand:249` — a **CLI**, not the HTTP path.

### 4.2 Grant expansion — UNGUARDED

```java
public AccessGrant modifyAccess(
        InstitutionalIdentifier actor, long grantId,
        Instant expiresAt, boolean includeDescendants, String reason) {
    requireAdministrator(actor);
    AccessGrant modified = grants.modify(grantId, expiresAt, includeDescendants);
```

Addressed by **`grantId` alone**: no subject, no self-check. An
administrator can modify *any* grant, including their own, to
- extend `expiresAt` indefinitely, and
- flip `includeDescendants` to `true`, widening it to child records.

`includeDescendants` is **outside** V087's
`ux_access_grant_active_scope` key, so widening this way is not seen as a
duplicate by the index.

### 4.3 Administrator promotion — no two-person rule

`assignAdministrator` requires only that the actor is *an* administrator.
Any single administrator can unilaterally appoint another. There is no
approval step — notably inconsistent with the rest of the model:

| Operation | Safeguard |
|---|---|
| Re-link an identity holding grants | **two-person approval** (V086); approver may not be the target |
| Revoke an administrator | last-administrator guard, under a row lock |
| **Appoint an administrator** | **none** |

### 4.4 A migration dependency, unverified

`grantAccess`'s own comment states that V087's index "is what actually
holds *at most one unrevoked grant per grantee and scope*" — the service
only translates the error. Project memory records **V087 as merged but not
applied to DEV**, and this session has no database read path to confirm.

**If V087 is not applied, grant writes have no duplicate protection.**
That is a hard gate on Phase 3 below, not a detail.

---

## 5. Auditing today, and its independence

**Already independent of record enforcement.** No flag check exists on any
audit path.

- Table `authz.access_audit` — created in **V082**, which is provably
  applied to DEV (V084's `admin_role_member` was read successfully live,
  and it builds on V082).
- **Append-only by trigger**: `authz.reject_audit_change()` raises on
  UPDATE or DELETE. Not a convention — the database refuses.
- Written by `ArchiveAdminService` inside the **same `@Transactional`
  boundary** as the action, so a refused write leaves no audit row and an
  audited action cannot be rolled back separately.
- Ordering is deliberate: in `grantAccess` the audit row is written
  **after** the insert, so an index refusal never leaves an
  `ACCESS_GRANTED` row behind.

**No new auditing mechanism is needed.** Enabling administration makes the
existing audit trail start recording real activity.

Distinct and not required here: **V089 `archive_activity_audit`** (SEC-008)
audits *record access* by ordinary users. It exists only on an unmerged
branch, is unrelated to administrator auditing, and stays out of scope.

---

## 6. Capabilities endpoint and React route guards

### Server — a one-line semantic change

```java
// today
return CapabilitiesResponse.administrator(authorization.isEnforcementEnabled());

// proposed
return CapabilitiesResponse.of(
        /* administrator          */ true,
        /* enforcementEnabled    */ authorization.isEnforcementEnabled(),
        /* administrationAvailable */ administration.isEnabled());
```

`administrationAvailable` stops meaning "enforcement is on" and starts
meaning "the administration API will answer". The wire contract — three
booleans — **does not change**.

### UI — no logic change required

This falls out of a decision already made in `readCapabilities`:

```js
// Never recomputed from the other two: the server decides, because a client
// bug must not be able to show a surface the backend would refuse.
const administrationAvailable = raw.administrationAvailable === true;
```

So `showsModeToggle`, `administrationIsUsable`, `adminRouteState`,
`mayCallAdminApi` and `rendersInteractiveControls` all keep working
unchanged, and the 37 component tests plus the eight-row matrix stay valid.

The only UI work is presentational: the status panel currently shows one
row, "Authorization enforcement: ON/OFF". It should show two, so an
administrator can see that administration is available *while* record
enforcement is off — otherwise the screen looks self-contradictory.

---

## 7. Keeping ordinary record access unchanged

The separation is already structural. Record-side decisions read a
**different object** from the one the admin gate would read:

```
record side   AuthorizationProperties.enforcementEnabled
                -> AuthorizationEnforcementSettings  (built once in AuthorizationConfiguration)
                -> RecordAccess / RecordAuthorizationGate / RecordAuthorizationService
                -> DashboardService, GlobalSearchService

admin side    AuthorizationProperties.administrationEnabled   (NEW)
                -> AdminAuthorizationController.requireAdministrationEnabled()
```

The new flag is read in **one place** and reaches nothing on the record
path. Award, Proposal, Negotiation, Subaward and Document queries never
consult it, and `app.authorization.enforcement-enabled` keeps its current
meaning and its `false` default.

Writing or changing a grant while enforcement is OFF still changes nothing
a user sees, because no query reads grants. The §4 concern is not that
visibility changes today — it is that grants accumulate for the day
enforcement is switched on.

---

## 8. Smallest safe implementation

### Configuration

One new property, defaulting to **off**, so merging changes nothing:

```java
// AuthorizationProperties
private boolean administrationEnabled;   // default false
```

`app.authorization.administration-enabled`, env
`APP_AUTHORIZATION_ADMINISTRATION_ENABLED`. Record enforcement keeps its own
flag, unchanged.

### API

| Change | Size |
|---|---|
| Add the property + accessors | ~10 lines |
| `requireEnforcementEnabled()` → `requireAdministrationEnabled()`, reading the new flag | 1 method |
| `CapabilitiesService`: `administrationAvailable` from the new flag | 1 line |
| `CapabilitiesResponse`: add an `of(...)` factory alongside `administrator(...)` | ~6 lines |
| `ArchiveAdminBootstrapCommand`: correct its informational NOTE | 1 message |

No change to `requireAdministrator`, to any service method, to the record
path, or to the wire contract.

### Read-only phase enforcement

For Phase 2, the write endpoints must stay closed while reads open. The
honest way is a second property rather than trusting operators:

```java
private boolean administrationWritesEnabled;   // default false
```

and one guard on the 8 write endpoints. A read-only phase whose writes are
"disabled by not being used" is not a control.

### UI

Status panel shows both flags (≈15 lines). No logic change, no new tests
required for existing behaviour.

### Regression tests

The point of these is to prove the decoupling does not leak:

1. `administrationEnabled=true, enforcementEnabled=false` → admin reads
   answer **200**, and `RecordAccess`/`RecordAuthorizationGate` still report
   enforcement **off**
2. Same configuration → Award, Proposal, Negotiation, Subaward and Document
   endpoints return **identical** payloads to the enforcement-off baseline
3. Same configuration → `DashboardService` and `GlobalSearchService` return
   **unscoped** counts and results
4. `administrationEnabled=true` → a non-administrator gets **403** on all 12
   endpoints (not 404 — the surface now exists)
5. `administrationEnabled=false` → all 12 still **404**
6. `administrationWritesEnabled=false` → the 8 write endpoints **404** or
   **409** while the 4 reads answer 200
7. An audit row is written with enforcement OFF, and is **append-only**
   (UPDATE and DELETE both rejected)
8. A self-grant attempt is **refused** once §4.1 is fixed
9. `modifyAccess` cannot widen a grant the actor is the grantee of, once
   §4.2 is fixed
10. Property-matrix test over all four combinations of the two flags,
    asserting no combination turns record enforcement on

Test 3 is the one that would catch the worst plausible mistake: scoping
silently applied to dashboard counts because a shared settings object was
reused.

### Rollback

Set `APP_AUTHORIZATION_ADMINISTRATION_ENABLED=false` (or remove it) and
redeploy the task definition. The surface returns to 404 with no code
change, no data change, and no migration. Grants already written stay
written — which is why §4 must be fixed **before** writes are enabled, not
after.

---

## 9. Separate flag, or reuse enforcement?

**Introduce a separate flag.** Reusing `enforcement-enabled` cannot express
the state you actually want — administration on, record enforcement off —
because it is one boolean controlling twelve consumers across two unrelated
concerns.

Three alternatives considered and rejected:

| Option | Why not |
|---|---|
| Remove the gate entirely | Administration becomes permanently on with no way to close it; no rollback short of a redeploy of different code |
| Derive availability from "an ACTIVE administrator exists" | Availability would change as data changes, and bootstrapping one administrator would silently open the API |
| Reuse `enforcement-enabled` and accept record enforcement | Directly contradicts the requirement to keep enforcement OFF for ordinary users |

A second flag is also the smallest diff, and the only option with a
configuration-only rollback.

---

## Proposed phased rollout

Each phase is separately approvable and independently reversible.

| Phase | Content | Risk | Gate to proceed |
|---|---|---|---|
| **1** | `administrationEnabled` flag + capabilities + UI status panel + tests 1-5, 7, 10. **Default off; merging changes nothing.** | very low — dead code until enabled | tests green; DEV plan clean |
| **2** | Enable in DEV **reads only** (`administrationWritesEnabled=false`). Verify the 4 reads answer and the 8 writes do not. | low — no mutation possible | Phase 1 merged and deployed; test 6 green |
| **3a** | **Fix §4 before any write.** Self-grant refusal, `modifyAccess` self/expansion guard, two-person administrator promotion. | medium — new refusals could block legitimate work | tests 8, 9 green; **V087/V088 confirmed applied to DEV** |
| **3b** | Enable writes in DEV. | medium | 3a merged and deployed |
| **4** | Record-level enforcement — **entirely separate decision**, not part of this work. | high | out of scope here |

### My recommendation

Do **Phase 1 and Phase 2**. They give you a working Security Administration
screen — identity search, identity detail, the administrator roster and the
audit log — with no ability to change anything, so none of the §4 gaps can
be reached.

Hold Phase 3 until §4 is fixed. The reason is specific rather than
cautious: with enforcement OFF, a self-granted or widened grant produces no
visible effect and no obvious symptom, so a mistake made during Phase 3
would sit undetected until the day enforcement is switched on — the worst
possible moment to discover it.

---

## Out of scope

No enforcement activation, no V089, no migration, no grant change, no
configuration change, no deployment, no Production access. Phase 4 is named
only to show where this work stops.
