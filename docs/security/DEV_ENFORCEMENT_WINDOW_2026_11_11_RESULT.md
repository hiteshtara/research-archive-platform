# DEV enforcement window, 11 November 2026 — result and defect report

**Outcome: ABORTED by decision, after the enforcement gate PASSED.**
Revision 91 restored and verified. No test case 1–13 was executed.

The window was moved from the approved Thursday slot to Wednesday 11 November
on explicit written authorization, with the notification condition knowingly
overridden (BU was closed for Veterans Day; the eight notified users had been
told Thursday and received no effective re-notice).

## Timeline (ET)

| Time | Event |
|---|---|
| 16:27 | stale session cleared (7 Cognito tokens, Cognito session ended) |
| ~16:30 | fresh BU Web Login + Duo completed by the operator |
| 16:31 | identity verified: **FEDERATED (BUShibboleth)**, `auth_time` age **0.006 h**, pool `us-east-1_VJ4ekQ27c` |
| 16:31 | 40-check pre-flight: **36 PASS, 3 CONDITIONAL, 1 OVERRIDDEN, 0 FAIL** → **GO** |
| 16:32:00 | task definition revision **92** registered, digest `sha256:f0f42ab6…b912f1` |
| 16:32:06 | service updated to revision 92 |
| 16:35:11 | revision 92 stable: 1/1, `COMPLETED`, ALB healthy, 6 authorization variables live, no audit key |
| 16:36 | **paired enforcement gate PASSED** |
| 16:36 | administrator identity check → **HTTP 500** |
| 16:37 | second 500 on `/api/v1/admin/audit`; root cause isolated from CloudWatch |
| 16:39:12 | **ABORT decided; rollback initiated** |
| 16:42:23 | revision 91 stable |
| 16:43–16:45 | restoration verified (below) |

Elapsed with enforcement active: **≈7 minutes** (16:35 → 16:42).

## Evidence preserved: the paired enforcement gate PASSED

This is the window's substantive result and it stands.

| Probe | Before (rev 91) | Under enforcement (rev 92) | Expected | Result |
|---|---|---|---|---|
| `GET /api/awards/212137-00001` — in scope, parent unit `1250000000` | 200 | **200** | 200 | **PASS** |
| `GET /api/awards/114471-00001` — out of scope, child unit `1250400000` | 200 | **404** `NOT_FOUND` "Record not found" | 404 | **PASS** |

Both fixtures returned 200 immediately before activation, so the change is
attributable to enforcement and not to data or availability.

**What this proves, beyond "a status changed":**

1. **Record-level enforcement works against real DEV data.** The 404 is a real
   scope decision, not an outage: the in-scope record stayed visible throughout.
2. **The caller was provisioned.** An unprovisioned identity answers **403
   `ACCESS_NOT_PROVISIONED`** to both; this pair was 200/404.
3. **`include_descendants = false` is honoured.** `114471-00001` sits at child
   unit `1250400000`. Had descendants been included it would have returned 200.
4. **Grant 2 was active at 16:36 ET**, since the 200 is impossible under
   enforcement without an active grant for that identity.
5. **The fail-fast policy guard was satisfied** — the application started with
   enforcement on, which `AuthorizationConfiguration.requireEnforcementSettings()`
   would otherwise have refused.

This reproduces acceptance-matrix **cases 2 and 3** against live data and the
deployed configuration, which no test suite can do.

## Why it was aborted

The administrator identity/grant check was a **mandatory gate**. It could not be
evaluated, so the gate did not pass, and the approved rule was to abort.

Assertion status at the moment of abort — recorded honestly, since none was
shown false:

| # | Assertion | Status | Basis |
|---|---|---|---|
| 1 | correct authenticated administrator | **VERIFIED** | `GET /administrators` → 200: adminMemberId 3, `ARP-FK7QY195-QW5HV7G0`, ACTIVE, bootstrapped 2026-10-29. A 200 means `requireAdministrator(actor)` passed on the live token |
| 2 | exactly one active identity link | **PARTIAL** | `GET /identities?q=` → `hasActiveLink: true`; count and link id not enumerated |
| 3 | grant 2 active and associated | **VERIFIED behaviourally** | the gate's 200 (above) |
| 4 | `UNIT 1250000000`, descendants excluded | **VERIFIED behaviourally** | the gate's 404 on the child unit |
| 5 | no unexpected additional active grants | **SUPPORTED, not proven** | a CENTRAL or descendant-inclusive grant would have made the child-unit award 200. A *narrower* second grant would be invisible to this probe |

Assertion 5 remains unproven and is recorded as such.

---

# DEFECT — BLOCKING: administrator API audit query fails on PostgreSQL

**Severity: blocking for any enforcement window.** Two of the four
administrative read endpoints return HTTP 500 whenever enforcement is on.

## Symptom

| Endpoint | Result |
|---|---|
| `GET /api/v1/admin/identities/{identifier}` | **500** |
| `GET /api/v1/admin/audit` | **500** |
| `GET /api/v1/admin/administrators` | 200 |
| `GET /api/v1/admin/identities?q=…` | 200 |

Generic Spring error body, so no handled problem response:
`{"status":500,"error":"Internal Server Error","path":"/api/v1/admin/audit"}`

## Root cause

From CloudWatch, `/ecs/research-archive-platform-dev-api`:

```
org.springframework.jdbc.BadSqlGrammarException: PreparedStatementCallback;
  bad SQL grammar [SELECT audit_id, occurred_at, actor, action, …]
org.postgresql.util.PSQLException:
  ERROR: could not determine data type of parameter $3   (and $1)
```

In `JdbcAdminAuditRepository`:

```sql
WHERE (:subject IS NULL OR institutional_identifier = :subject)
  AND (:action  IS NULL OR action = :action)
  AND (CAST(:fromTime AS TIMESTAMPTZ) IS NULL OR occurred_at >= CAST(:fromTime AS TIMESTAMPTZ))
  AND (CAST(:toTime   AS TIMESTAMPTZ) IS NULL OR occurred_at <  CAST(:toTime   AS TIMESTAMPTZ))
```

The query uses the `:param IS NULL OR <condition>` idiom so one statement
serves every filter combination. **The two timestamp parameters are explicitly
cast; `:subject` and `:action` are not.** In `$n IS NULL`, `IS NULL` places no
type constraint on either side, so PostgreSQL cannot infer the parameter's type
and refuses to prepare the statement.

The casts on `fromTime`/`toTime` show this was encountered once before and
fixed for those two parameters only.

**The parameter numbers corroborate the diagnosis exactly:**

- `/admin/audit` with no filters → `:subject` is null → fails on **`$1`**
- `describeIdentity` supplies a subject but no action → fails on **`$3`**

Both were observed, in that order.

## Why no test caught it

`AuthorizationSchemaAndAuditIntegrationTest` covers the audit table and its
transaction behaviour, but does not query it through this repository with
**NULL filters**. The failure is server-side type inference, so it reproduces
only against a real PostgreSQL server — never against a mock or an in-memory
database.

It also went undetected because the administrative API is gated on
`enforcement-enabled`, so this code path had **never executed in a deployed
environment** before 16:36 today. `AUTHORIZATION_TESTING_STRATEGY.md` records
that risk; this is it occurring.

## Proposed fix — not implemented

Cast the two text parameters exactly as the timestamps already are:

```sql
WHERE (CAST(:subject AS TEXT) IS NULL OR institutional_identifier = CAST(:subject AS TEXT))
  AND (CAST(:action  AS TEXT) IS NULL OR action = CAST(:action  AS TEXT))
```

## Required regression tests — not implemented

Against Testcontainers PostgreSQL, because a mock cannot reproduce this:

1. **Every filter combination**, including **all four NULL** — the case that
   fails today. Parameterise over the 16 combinations rather than testing the
   happy path.
2. `describeIdentity` end to end, asserting `recentAudit` is populated — this
   is the path the window actually needed.
3. A non-vacuity assertion: with filters supplied, the expected rows come back,
   so an empty result cannot pass as success.
4. Route the suite into the **guarded CI PostgreSQL job**, not the unit job —
   see the routing defect in `AUTHORIZATION_TESTING_STRATEGY.md`.

## Gate on retry

No redeployment of revision 92 and no enforcement retry without new approval.
Revision 92 remains **registered but unused**; the service runs revision 91.

---

## Rollback verification

| Check | Result |
|---|---|
| Service task definition | `research-archive-platform-dev-api:91` |
| Running task | revision **91**, `RUNNING`, digest `…8da9d39ba5b912f1` |
| Count / rollout | **1/1**, `COMPLETED`, 0 deployments in flight |
| ALB target | **healthy** |
| `/actuator/health` | **200** `{"status":"UP"}` |
| `APP_AUTHORIZATION_*` | **none** — enforcement **OFF** |
| Audit variables | **none** — auditing **OFF** |
| Environment variables | 8 (base set restored) |
| Unauthenticated `/api/global-search` | **401** |
| `GET /api/awards/212137-00001` | **200** |
| `GET /api/awards/114471-00001` | **200** — was 404 under enforcement; normal access restored |
| Versions of `212137-00001` | **3**, sequences [3, 2, 1] — full visibility restored |
| `GET /api/global-search?query=NSF` | **200** |
| `GET /api/v1/admin/administrators` | **404** — administrative surface correctly absent again |
| Grant 2 | **active and unchanged, NOT revoked** |
| Production | untouched — no Production resource read or written at any point |
| V089 | unapplied |

### Grant 2 — basis for "unchanged"

Test 13 was never executed, so no revocation ran. Positive evidence:

- **No administrative write endpoint was called at any point.** Only four GETs
  were issued: `/administrators`, `/identities?q=`, `/identities/{id}` and
  `/audit`.
- A CloudWatch filter across the window for revocation, grant and link
  mutations returned **no events**.
- Grant 2 was demonstrably **active at 16:36** via the gate's 200.

### V089 — basis for "unapplied"

Migrations are applied only by the Python ETL loaders. The ETL cluster held
**0 running and 0 stopped tasks**, so no loader ran and no migration could have
been applied. `V089` also exists on no branch: it is the planned SEC-008
`authz.activity_audit` migration, recorded in `SEC_008_ACTIVITY_AUDIT.md` as
"not done anywhere".

## Follow-up

| # | Item |
|---|---|
| 1 | Fix the audit-query casts with the regression tests above — separate code change, needs approval |
| 2 | Decide whether revision 92 stays registered or is deregistered |
| 3 | Re-approve an enforcement window once the defect is fixed and its tests run in CI |
| 4 | Assertion 5 ("no unexpected additional active grants") still has no read path — a `--describe-grant-id` CLI mode or a working `describeIdentity` would close it |
| 5 | The eight notified users were told Thursday 4:00–5:30 pm ET. That window is now **not** being used. They should be told the test ran and was withdrawn, or that Thursday is cancelled |
