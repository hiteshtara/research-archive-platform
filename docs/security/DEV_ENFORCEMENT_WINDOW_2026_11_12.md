# DEV enforcement window — Thursday 12 November 2026, 4:00–5:30 PM ET

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

## 16:00 ET — pre-flight checklist

One consolidated list. It restores the full approved checklist, which an
earlier draft of this runbook had compressed to thirteen items, and keeps the
safeguards added since. Nothing is dropped to preserve a round number.

Binding: **every check must pass before revision 92 is registered**, except
those marked *in-window*, which cannot be answered earlier and are noted as
such with the reason.

Status recorded 2026-11-11, the day before the window. A check verified today
is still re-run at 16:00 — the point of the early pass is to find blockers
with a day in hand, not to skip the check.

### Account and environment

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 1 | AWS account and profile | `770203350335`, profile `bu-nprd` | **PASS** |
| 2 | Production untouched and out of scope | no Production resource read or written at any point | **PASS** — no Production call made |

### DEV usage and unexpected traffic

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 3 | DEV request traffic before the window | no unexpected caller active; traffic attributable to known users | *in-window* — a traffic sample is only meaningful close to the window |
| 4 | Known active users during the window | all covered by the notification | **PASS with a note**: one notified user's auto-reply states she returns **Thursday 12 November**, the window day. She is notified, so she is covered — but she is an actually-active user, not a presumed-inactive one |

### Conflicting ECS tasks, ETL, schedules and Amplify

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 5 | Running tasks, API cluster | exactly 1 — the service task, nothing else | **PASS** — count 1 |
| 6 | Running tasks, ETL cluster | 0 | **PASS** — count 0 |
| 7 | Scheduled ETL during the window | nothing fires 16:00–17:30 ET | **PASS** — `research-archive-platform-dev-subaward-nightly` is `cron(0 2 * * ? *)` America/New_York, i.e. 02:00 ET, targeting the ETL cluster. No overlap |
| 8 | Other scheduled rules touching this service | none | **PASS with a note**: the account-level `shutdown-scheduler` rule runs every 5 minutes against a Lambda. It is a shared BU non-production utility, not ours. Empirically it does not stop this service, which has held 1/1 continuously through this period — but it is not under our control, so it is a residual risk rather than a cleared one |
| 9 | Amplify build activity | no job in flight | **PASS** — last job 192 on `main`, `SUCCEED`, ended 2026-11-11 15:41 UTC, built commit `341ff74` (the #129 merge). Nothing queued |

### Cognito pool membership and new identities

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 10 | Pool membership | unchanged since the notification | **PASS** — 14 users: 8 federated (`EXTERNAL_PROVIDER`) and 6 native (`CONFIRMED`), all enabled, single page |
| 11 | New identities since the notification | none | **PASS** — no account created on or after 2026-11-10; newest creation 2026-11-09 |
| 12 | Accounts modified since the notification | none | **PASS** — no account last-modified on or after 2026-11-10 |
| 13 | The 8 notified users reconcile to the pool | the 8 federated accounts | **PASS** — 8 federated matches the 8 notified |

Read-only `ListUsers` on `us-east-1_VJ4ekQ27c` only, as authorized. No Cognito
account is created, changed, disabled, deleted or reset at any point.

### Notification replies, objections and delivery failures

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 14 | Notification sent and on record | one message to all 8 | **PASS** — sent 2026-11-11 18:23 UTC (13:23 ET), ~27 hours before the window |
| 15 | Objections | none | **PASS** — no objection received |
| 16 | Replies | reviewed, each dispositioned | **PASS** — one automatic out-of-office (BU closed 11 November, returning 12 November). Not an objection; see check 4 |
| 17 | Delivery failures | none | **PASS** — no non-delivery report, bounce or delivery-status notification |

### Revision 91 and rollback readiness

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 18 | Service revision | on revision 91, 1/1, rollout `COMPLETED` | **PASS** |
| 19 | Highest ACTIVE revision | 91 — revision 92 **not** registered | **PASS** — 85 ACTIVE revisions, highest is 91 |
| 20 | Rollback reference | revision 91 ARN recorded and reachable | **PASS** — `…:task-definition/research-archive-platform-dev-api:91` |
| 21 | Rollback procedure | unchanged from approval | **PASS** — unchanged |

### Grant 2, administrator membership and identity link

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 22 | Grant 2 active | `UNIT 1250000000`, descendants excluded, expires 2026-12-31, approval ref DEV-APPROVAL-3 | **INDIRECT only** — see the note below |
| 23 | Exactly one ACTIVE identity link for the tester | one, link id 4 | **INDIRECT only** — see the note below |
| 24 | An active archive administrator exists | exactly one | **INDIRECT only** — see the note below |

**Ordering constraint, stated plainly.** Checks 22–24 read the `authz` tables,
and there is no approved read-only path to them while enforcement is off: the
administrative API 404s by design, no operator CLI has a read mode, RDS IAM
authentication is disabled, and a database tunnel would need a password that
must not be requested. The available evidence is **indirect**: the
non-writing operator dry runs refuse with messages that confirm the state
("an active `ARCHIVE_ADMIN` already exists (1)"; "archive identity … already
has an ACTIVE link (id 4)"). Those dry runs are re-run at pre-flight and write
nothing.

Direct confirmation is only possible **after** revision 92 is registered, via
`GET /api/v1/admin/identities/{tester ARP}` — which is the first thing done
after the paired gate, before any test result is interpreted. If that response
does not show one active link and grant 2 as described, the window aborts and
revision 91 is restored.

A `--describe-grant-id` read mode on the grant CLI would close this gap
properly and is worth doing before any wider window.

### Test fixtures and version boundaries

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 25 | In-scope fixture resolves | `GET /api/awards/212137-00001` → 200 | **PASS** — path confirmed to resolve with enforcement off |
| 26 | Out-of-scope fixture resolves | `GET /api/awards/114471-00001` → 200 with enforcement off | **PASS** — both return 200 now, which is what makes the later 200/404 split meaningful |
| 27 | Version boundary baseline under `PER_VERSION` | the versions of the in-scope family, and each one's lead unit, captured **before** enforcement is enabled | *in-window*, and **must be captured while enforcement is still off** |

Check 27 is sequencing, not paperwork. `PER_VERSION` makes authorization
version-specific, so some versions of an in-scope family are expected to be
invisible. Once enforcement is on, a denied version is indistinguishable from
one that never existed — so the list of versions and their lead units has to
be recorded while they are all still visible, or there is nothing to compare
the enforced result against. It needs an authenticated session, which the
15:55 login provides.

### Service and ALB health

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 28 | Application health | `GET /actuator/health` → 200 `UP` | **PASS** |
| 29 | ALB target health | target healthy | **PASS** |

### Configuration, auditing, migration and image

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 30 | Enforcement off before the window | no `APP_AUTHORIZATION_*` in the running definition | **PASS** — zero present on revision 91 |
| 31 | Auditing OFF | no activity-audit variable, before and after | **PASS** — none present on 91; none added by 92 |
| 32 | V089 not applied | absent | **PASS by construction** — migrations are applied only by the ETL loaders, and no loader task has run (check 6); V089 is not in any registered loader run |
| 33 | Unauthenticated API protection | `/api/global-search` with no token → 401 | **PASS** |
| 34 | Image digest equality | ECR = deployed = staged 92 | **PASS** — see below |
| 35 | Exact six-variable configuration diff | only the six added, nothing removed or altered | **PASS** — see below |

#### Checks 34 and 35 in detail

Revision 92 must differ from 91 in configuration only, never in code:

- ECR digest for `research-archive-platform-dev-api`:
  `sha256:f0f42ab6…b912f1`, tags `20261111T144553Z-458de9c` and `latest`,
  pushed 2026-11-11 14:46 UTC
- running revision 91 pins `@sha256:f0f42ab6…b912f1`
- staged revision 92 pins `@sha256:f0f42ab6…b912f1`

All three agree. The repository is `imageTagMutability=MUTABLE`, so a tag can
be moved after the fact — which is why both revisions pin the digest, and why
this check compares digests and not tags.

A field-by-field diff of 92 against 91 shows **only** the six authorization
variables added and nothing removed: 8 base variables unchanged, both IAM
roles, the five secret references, cpu 512, memory 1024, `awsvpc` networking,
the log group and the family all identical.

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

### Fresh federated authentication

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 36 | Fresh BU Web Login with Duo | completed at 15:55 | *in-window* |
| 37 | `auth_time` age | under 12 hours, read without exposing the token | *in-window* |
| 38 | Genuinely fresh, not a reused IdP session | a silent Shibboleth reuse **stops** the window | *in-window* |
| 39 | Identity is the designated federated test account | confirmed, and not the native control | *in-window* |

The session in use on 2026-11-11 had an `auth_time` 44 hours old. A stale
session would have made both halves of the paired gate answer 404 and produced
a false abort, which is why checks 36–39 run first and on their own.
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
