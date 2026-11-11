# DEV enforcement window — Thursday 12 November 2026, 4:00–5:30 PM ET

Written for the project's security reviewers and for whoever executes or
audits this window.

DEV only. Production is out of scope and is not touched at any point.

## Authorized changes, and nothing else

| Authorized | Not authorized |
|---|---|
| Register task definition revision 92 | Any new environment variable beyond the six below |
| Enable record enforcement for the window | Any new grant, identity or migration |
| Revoke grant 2 (the one approved administrative write) | Any other administrative write endpoint |
| Read `GET /api/v1/admin/identities/{identifier}` | Enabling AI, auditing, or outbound services |
| Roll back to revision 91 | Any Production change |

## Expected authorization responses

The single reference for every assertion in this window:

| Status | Meaning |
|---|---|
| **200** | authorized record |
| **404** | out-of-scope record belonging to a **provisioned** identity |
| **403** | unprovisioned or suspended identity (`ACCESS_NOT_PROVISIONED` / `ACCESS_DENIED`) |
| **401** | expired or missing sign-in authentication |

**Any status outside this table, or any of these in a place the procedure does
not predict, stops testing and triggers rollback to revision 91.**

## 15:55 ET — fresh authentication gate

The session in use on 2026-11-11 had an `auth_time` 44 hours old. A stale
session would have made both halves of the enforcement gate answer 404 and
produced a false abort, so this gate runs first and on its own.

1. Sign in through BU Web Login with Duo, freshly.
2. Verify `auth_time` is **less than 12 hours** old, read in the browser
   without exposing or persisting the token.
3. Confirm the identity is the designated **federated** test account and that
   grant 2 is still active.
4. **If the login silently reuses the old Shibboleth session, stop.** A
   genuinely fresh authentication is required; do not proceed on a reused one.

## 16:00 ET — pre-flight checks

Binding. Every one must pass before revision 92 is registered. Checks 1–12 are
the previously agreed list, written out here; **13 is new**, added on
2026-11-11.

| # | Check | Expected |
|---|---|---|
| 1 | AWS identity | account `770203350335`, profile `bu-nprd` |
| 2 | Service state | revision 91, 1/1, rollout `COMPLETED`, ALB target healthy |
| 3 | Health | `GET /actuator/health` → 200 `UP` |
| 4 | Enforcement and auditing off | no `APP_AUTHORIZATION_*`, no audit key, in the running definition |
| 5 | Highest ACTIVE revision | 91 — revision 92 not yet registered |
| 6 | Grant 2 | active: `UNIT 1250000000`, no descendants, expires 2026-12-31 |
| 7 | Identity link | exactly one ACTIVE link for the tester's ARP |
| 8 | V089 | not applied |
| 9 | Production | untouched, out of scope |
| 10 | Rollback reference | revision 91 ARN recorded and reachable |
| 11 | Unauthenticated access | `/api/global-search` without a token → 401 |
| 12 | Fresh-login gate | the 15:55 gate above passed |
| **13** | **Image digest** | **ECR = deployed = staged revision 92** |

### Check 13 in detail

Revision 92 must differ from 91 in configuration only, never in code.
Verified 2026-11-11, to be re-run in pre-flight:

- ECR digest for `research-archive-platform-dev-api`:
  `sha256:f0f42ab6…b912f1`, tags `20261111T144553Z-458de9c` and `latest`,
  pushed 2026-11-11 14:46 UTC
- running revision 91 pins `@sha256:f0f42ab6…b912f1`
- staged revision 92 pins `@sha256:f0f42ab6…b912f1`

All three agree. The repository is `imageTagMutability=MUTABLE`, so a tag could
be moved after the fact — which is exactly why both revisions pin the digest
and not the tag, and why this check compares digests rather than tags.

A field-by-field diff of 92 against 91 shows **only** the six authorization
variables added and nothing removed: both IAM roles, the five secret
references, cpu 512, memory 1024, `awsvpc` networking, the log group and the
family are identical.

The six variables, and no others:

```
APP_AUTHORIZATION_ENFORCEMENT_ENABLED   = true
APP_AUTHORIZATION_VERSION_SCOPE         = PER_VERSION
APP_AUTHORIZATION_DEPARTMENT_MATCH      = LEAD_UNIT_WITH_DESCENDANTS
APP_AUTHORIZATION_CONTACT_DERIVATION    = EXPLICIT_GRANT
APP_AUTHORIZATION_RESEARCH_STAFF_ROLES  = PI
APP_AUTHORIZATION_MAX_SIGN_IN_AGE       = 12h
```

`PER_VERSION` is the approved temporary test configuration; business decision
2.1 remains OPEN.

## Paired enforcement gate

A healthy Spring Boot application does not prove enforcement is active, so
enforcement is proved by behaviour before any test runs:

| Probe | Expected |
|---|---|
| `GET /api/awards/212137-00001` (in scope) | **200** |
| `GET /api/awards/114471-00001` (out of scope) | **404** |

Both paths were confirmed to resolve while enforcement was off, where both
returned 200.

The pair is diagnostic, which is why it is run as a pair:

| In scope | Out of scope | Means |
|---|---|---|
| 200 | **404** | enforcement on, grant seen — proceed |
| 200 | 200 | enforcement **off** — abort |
| 403 | 403 | enforcement on, grant **not** seen — abort |
| 401 | 401 | sign-in stale or no `auth_time` — abort |

A 200/404 pair also proves the caller is provisioned, since an unprovisioned
caller answers 403 to both. **If either assertion fails, abort immediately and
restore revision 91.**

## Principal and grant verification

The HTTP status of a granted Award does not by itself prove *which* identity
is being tested, so identity is confirmed from the server's own point of view
before any result is interpreted:

`GET /api/v1/admin/identities/{tester ARP}` returns `administrator`, `links[]`,
`grants[]` and `recentAudit[]`. Because `actor()` resolves the caller from the
live token and `ArchiveAdminService.describeIdentity` calls
`requireAdministrator(actor)`, a 200 proves the server resolved **this token**
to an active archive administrator, and the body states the grant association
directly.

Expected: one ACTIVE link, and grant 2 as the only active grant —
`UNIT 1250000000`, descendants excluded.

This endpoint is read-only. See `ADMIN_API_EXPOSURE_DURING_ENFORCEMENT.md` for
why the whole administrative tree becomes reachable with enforcement, what
protects it, and its test coverage.

## Test order

**Tests 1–10, then test 13, then test 12.** Test 13 revokes grant 2, after
which the tester identity is deliberately unprovisioned — so every scoped test
must already be complete.

### Test 11 — NOT EXECUTED

AI is disabled on DEV and the endpoint 404s. Recorded as NOT EXECUTED, not as
a pass and not as a failure. AI will not be enabled and the six approved
variables will not be changed to accommodate it.

### Test 13 — revocation takes effect

Revoking grant 2 is the single approved administrative write. Afterwards the
tester identity has no active grant, so the gate records flip to **403 /
403** — which is the expected evidence for this test, not an abort condition.

### Test 12 — the no-grant control

A valid, freshly authenticated **native** Cognito identity with no active
grant.

- **Expected: 403 `ACCESS_NOT_PROVISIONED`.**
- Credentials are entered by the user directly in the browser. All browser
  interaction stops at the native sign-in page and resumes only on the user's
  confirmation. No password is requested, captured, stored or logged.
- The authenticated identity is verified to be the intended native control
  account, **not** the federated identity.
- No password, token, cookie or authentication header is printed or persisted.

**If the native token carries no `auth_time` claim**, the freshness check
precedes enrollment and `signInFresh(null, …)` returns `false`, so the
response is **401**. That outcome is recorded separately as an
**authentication compatibility issue** and **does not count as a successful
no-grant authorization test**; test 12 is then UNVERIFIED, not passed.

- **Hard deadline 17:05 ET.** Test 12 must not delay rollback. Incomplete at
  17:05 → recorded UNVERIFIED and rollback begins.

## Rollback

- **By 17:10 ET**, the service returns to revision 91 regardless of outcome.
- Post-rollback verification: revision 91, 1/1, `COMPLETED`, health 200 `UP`,
  and enforcement and auditing absent from the running definition.
- Rollback is also triggered immediately by any unexpected authorization
  status, a failed gate assertion, or a failed pre-flight check.

## Reporting

Test by test: **PASS / FAIL / NOT EXECUTED / UNVERIFIED**, with the status
observed and the evidence. Twelve executable tests are not twelve passing
tests unless all twelve actually pass.

## Operational constraints

- Account-wide ECS enumeration is not attempted; the cluster, service and
  family identifiers (`research-archive-platform-dev-api` for all three) are
  taken from `ops/deploy-api.sh` and are sufficient.
- No sensitive identifier, password, token, cookie or authentication header
  appears in any log, screenshot or repository file.
