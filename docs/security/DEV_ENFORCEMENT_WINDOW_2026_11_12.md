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

| Status | Code | Meaning |
|---|---|---|
| **200** | — | authorized record |
| **404** | — | out-of-scope record belonging to a **provisioned** identity |
| **403** | `ACCESS_NOT_PROVISIONED` | no identity link, or no active grant |
| **403** | `ACCESS_DENIED` | suspended, ambiguous, revoked, or evaluation failure |
| **403** | `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` | the caller may open the Award but not **every** version of its family, and the response spans the whole family |
| **401** | — | expired or missing sign-in authentication |

**Three different 403s, so a 403 is never interpreted by its status alone —
the `code` is read every time.** `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` is an
*expected* response in this window (see the PER_VERSION discriminator) and
must not be mistaken for `ACCESS_NOT_PROVISIONED`, which would mean the grant
is not being seen and would abort the window.

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

One consolidated list of **40** checks. It restores the full approved
checklist, which an earlier draft of this runbook had compressed to thirteen
items, and keeps every safeguard added since. Nothing is dropped to preserve a
round number — check 40 was added on 2026-11-11 rather than folded into an
existing one.

Binding: **every check must pass before revision 92 is registered**, except
those marked *in-window*, which cannot be answered earlier and are noted as
such with the reason.

Every check is reported as **PASS**, **FAIL**, **CONDITIONAL** or **NOT YET
EXECUTED**. Indirect evidence is **CONDITIONAL**, never PASS.

Status recorded 2026-11-11, the day before the window. A check that passes
today is still re-run at 16:00 — the early pass exists to surface blockers
with a day in hand, not to skip the check.

### Account and environment

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 1 | AWS account and profile | `770203350335`, profile `bu-nprd` | **PASS** |
| 2 | Production untouched and out of scope | no Production resource read or written at any point | **PASS** — no Production call made |

### DEV usage and unexpected traffic

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 3 | DEV request traffic before the window | no unexpected caller active; traffic attributable to known users | **NOT YET EXECUTED** — a traffic sample is only meaningful close to the window |
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
| 22 | Grant 2 active | `UNIT 1250000000`, descendants excluded, expires 2026-12-31, approval ref DEV-APPROVAL-3 | **CONDITIONAL** — indirect evidence only; see below |
| 23 | Exactly one ACTIVE identity link for the tester | one, link id 4 | **CONDITIONAL** — indirect evidence only; see below |
| 24 | An active archive administrator exists | exactly one | **CONDITIONAL** — indirect evidence only; see below |

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
| 27 | Version-boundary baseline under `PER_VERSION` | Award IDs, sequence numbers, lead units and expected decisions, captured **before** enforcement is enabled | **PASS** — captured 2026-11-11 with enforcement off; see the baseline section below |

Check 27 was sequencing, not paperwork, and is now done: `PER_VERSION` makes
authorization version-specific, so some versions of an in-scope family are
expected to be invisible, and once enforcement is on a denied version is
indistinguishable from one that never existed. The baseline was therefore
captured on 2026-11-11 while all ten versions were still visible — see the
baseline section below.

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
| 36 | Fresh BU Web Login with Duo | completed at 15:55 | **NOT YET EXECUTED** |
| 37 | `auth_time` age | under 12 hours, read without exposing the token | **NOT YET EXECUTED** |
| 38 | Genuinely fresh, not a reused IdP session | a silent Shibboleth reuse **stops** the window | **NOT YET EXECUTED** |
| 39 | Identity is the designated federated test account | confirmed, and not the native control | **NOT YET EXECUTED** |

### Discriminator identity binding

| # | Check | Expected | Status 2026-11-11 |
|---|---|---|---|
| 40 | D1 and D2 bound to the fresh session | both probes execute as the identity signed in at 15:55 holding grant 2 — not a pre-existing, cached or stale session | **NOT YET EXECUTED** — confirmed at 16:00, before activation |

The session in use on 2026-11-11 had an `auth_time` 44 hours old. A stale
session would have made both halves of the paired gate answer 404 and produced
a false abort, which is why checks 36–39 run first and on their own.
## Version-boundary baseline — captured 2026-11-11 with enforcement OFF

Mandatory, and captured a day early because it can only be taken while
enforcement is off: once it is on, a denied version is indistinguishable from
one that never existed, so there would be nothing to compare the enforced
result against.

Taken from the live DEV API as the federated test account via
`GET /api/v1/awards/versions/search`. Access-decision fields only — no
investigator name or title was read or recorded.

### The unit hierarchy, and why the field names mislead

The API exposes `leadUnit` as a display **name**; the authorization engine
matches on `lead_unit_number` (`{row}.lead_unit_number IN (:az_units)`), a
column the API never returns. A first pass at this baseline compared the name
against the granted number and therefore scored **every** version as a denial,
including the Award the gate expects to allow. That output was wrong and is
recorded here as the reason the mapping is stated explicitly rather than
assumed.

The mapping, established from two independent sources that agree — the live
data above, and the committed acceptance matrix's hierarchy
`1250000000 → 1250400000 → 1252060000` with its cases 2 and 3:

| Unit number | Display name | Position |
|---|---|---|
| `1250000000` | COLLEGE OF GENERAL STUDIES (CGS) | **parent — the granted unit** |
| `1250400000` | CGS ADMINISTRATION | child (excluded: grant 2 has descendants OFF) |

### The baseline

Grant 2: `UNIT 1250000000`, **descendants excluded**, `PER_VERSION`.

| Award | Seq | `awardId` | Lead unit | Unit no. | Status | Current | Expected |
|---|---|---|---|---|---|---|---|
| `212137-00001` | 3 | 3798004 | COLLEGE OF GENERAL STUDIES (CGS) | `1250000000` | Approved Award | yes | **200** |
| `212137-00001` | 2 | 3787291 | COLLEGE OF GENERAL STUDIES (CGS) | `1250000000` | Approved Award | no | **200** |
| `212137-00001` | 1 | 3785806 | CGS ADMINISTRATION | `1250400000` | Approved Award | no | **404** |
| `114471-00001` | 7 | 1014735 | CGS ADMINISTRATION | `1250400000` | Closed | yes | **404** |
| `114471-00001` | 6 | 1014724 | CGS ADMINISTRATION | `1250400000` | Closed | no | **404** |
| `114471-00001` | 5 | 994529 | CGS ADMINISTRATION | `1250400000` | PAFO/OSP (Closing) | no | **404** |
| `114471-00001` | 4 | 826786 | CGS ADMINISTRATION | `1250400000` | PAFO/OSP (Closing) | no | **404** |
| `114471-00001` | 3 | 254250 | CGS ADMINISTRATION | `1250400000` | Approved Award | no | **404** |
| `114471-00001` | 2 | 254159 | CGS ADMINISTRATION | `1250400000` | Approved Award | no | **404** |
| `114471-00001` | 1 | 7885 | CGS ADMINISTRATION | `1250400000` | Approved Award | no | **404** |

Totals: the in-scope family has **3** versions, the out-of-scope family **7**.
All ten are visible now, with enforcement off.

### The one assertion that actually tests PER_VERSION

**`212137-00001` sequence 1 (`awardId` 3785806) must return 404.**

It belongs to the same Award family as the version the gate expects to allow,
but it sits at the **child** unit. A family-wide implementation would return
200 for it; a per-version implementation returns 404. It is therefore the
single discriminator between the two, and the live evidence for Anthony's
`PER_VERSION` requirement. Business decision 2.1 remains OPEN, so this is
evidence for that decision rather than a closure of it.

The acceptance matrix's own cases 2 and 3 are exactly the paired gate, which
is why the gate assertions and this baseline agree by construction rather than
by coincidence.


## Paired enforcement gate

A healthy Spring Boot application does not prove enforcement is active, so
enforcement is proved by behaviour before any test runs:

| Probe | Expected |
|---|---|
| `GET /api/awards/212137-00001` (in scope) | **200** |
| `GET /api/awards/114471-00001` (out of scope) | **404** |

Both paths were confirmed to resolve while enforcement was off, where both
returned 200. Both are served by `findWorkspace`, which authorises the
**current version only** (`requireAwardNumber`) and does not apply the
family-completeness rule — so the gate is unaffected by the correction
recorded in the PER_VERSION discriminator section below.

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

## PER_VERSION discriminator — corrected instrumentation

> **What these tests do and do not settle.** D1 and D2 verify that the
> **temporary** `PER_VERSION` test configuration behaves as implemented. They
> are not business approval of version-specific scoping. Business decision 2.1
> remains **OPEN**, and a passing result here is evidence for that decision,
> never a closure of it. SEC-003 is closed and is independent: contact-derived
> access is version-specific unconditionally, whatever `versionScope` says.


Verified explicitly alongside the paired gate, and **authorization decisions
are read from unit numbers, never display names** (see the baseline section
for the mapping and for the first-pass error that motivated saying so).

### The correction

The discriminator was specified as three probes of the per-version endpoint:
sequence 3 → 200, sequence 2 → 200, sequence 1 → 404. Checked against the
implementation on 2026-11-11, **that endpoint cannot produce those results**,
and running it as specified would have reported three FAILs and triggered an
unnecessary rollback.

`GET /api/awards/{awardNumber}/history/{sequenceNumber}` is served by
`AwardArchiveService.findSequence`, which applies the **family-completeness
rule**:

```java
recordAccess.requireEveryAwardVersion(normalizedAwardNumber);
```

Its own comment states the intent:

> "the whole family must be visible, decided per version. Under FAMILY_WIDE
> version scope that is satisfied whenever any version is in scope, so this
> costs nothing today; **under PER_VERSION it denies, which is the point.**"

The response spans every version of the family, so a caller cleared for the
current version but not an earlier one must not read it. With grant 2,
sequence 1 sits at the child unit `1250400000` and is out of scope, so
`requireEveryAwardVersion` throws `PartialFamilyAccessException` → **403**
`AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS`, "This response needs access to every
version of the Award." All three sequences answer 403, including sequence 1 —
which is 403 rather than 404 because `requireAwardNumber` passes first and the
family check throws after it.

The paired gate is unaffected: `GET /api/awards/{awardNumber}` is served by
`findWorkspace`, which calls only `requireAwardNumber` — the current version,
no family rule.

### The approved assertions

Approved 2026-11-11. These **replace** the superseded sequence-specific
assertions (sequence 3 → 200, sequence 2 → 200, sequence 1 → 404), which the
implementation cannot produce.

Each has a recorded enforcement-off baseline taken on 2026-11-11, so the change
under enforcement is unambiguous rather than inferred.

**D1 — version filtering**

`GET /api/v1/awards/versions/search?awardNumber=212137-00001`

| | Expected |
|---|---|
| Status | **200** |
| `totalElements` | **2** |
| Visible sequences | **[3, 2]** |
| Sequence 1 | **absent** |

Baseline with enforcement off: 200, `totalElements` **3**, sequences
**[3, 2, 1]**. `searchVersions` applies `awardScopeSql("av")`, a per-row
predicate on `award_version`, so each version is decided on its own.
FAMILY_WIDE would return all three.

**D2 — family completeness**

`GET /api/awards/212137-00001/history/3`

| | Expected |
|---|---|
| Status | **403** |
| Error code | **`AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS`** |

Baseline with enforcement off: 200. FAMILY_WIDE would return 200 under
enforcement too, per the implementation comment above.

**Sequences 2 and 1** are probed and must return **the same
family-completeness denial** — 403 `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` —
for the same reason: the response spans the whole family, and sequence 1 is
out of scope. Sequence 1 is 403 and **not** 404 because `requireAwardNumber`
passes first and the family check throws after it.

### Every 403 is classified by its error code, never by status

There are three distinct 403s in this system (see the status reference).
`AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` is **expected** here;
`ACCESS_NOT_PROVISIONED` in the same place would mean the grant is not being
seen and aborts the window. The response body's `code` is read and recorded
for **every** 403 observed, and no result is classified by HTTP status alone.

### Executed as the freshly signed-in test identity

D1 and D2 run **after** activation, but their binding is confirmed **before**
activation as pre-flight check 40: both probes execute against the
authenticated session created by the 15:55 fresh BU login and Duo — the
designated federated test identity holding grant 2 — and not against any
pre-existing, cached or stale session.

This is not a formality. A stale token fails the `max-sign-in-age` check
before enrollment is even read, so it would answer **401** — which falls under
"an unexpected error" below and would abort the window for a reason that has
nothing to do with version scoping. The session identity is re-confirmed from
the administrator identity response before either result is interpreted.

### Abort conditions for this step

Any of the following stops testing immediately and follows the approved
rollback procedure to revision 91:

| Condition | What it would mean |
|---|---|
| **D1 exposes sequence 1** | authorization is family-wide, not per version — restricted data reachable |
| **D1 returns no accessible versions** | the grant is not being seen at all |
| **D1 returns an unexpected error** | any status other than 200, including 401 (stale sign-in) or a 403 of any code |
| **D2 returns 200, or an unexpected error code** | the family-completeness rule is not firing, or is failing for the wrong reason |
| **Any restricted data is exposed** | see below |

"Restricted data exposed" means, concretely: any field of
`212137-00001` sequence 1 appearing in a D1 response; any content of
`114471-00001` at any sequence appearing in any response; or any denial
message naming a record, unit, department or person the identity is not
cleared for. Any of these is a containment failure, not a test failure, and
ends the window at once.

## Principal and grant verification — immediately after the gate

Runs after the paired gate and **before any test result is interpreted**. The
HTTP status of a granted Award does not by itself prove which identity is
being tested.

`GET /api/v1/admin/identities/{tester ARP}` — read-only. Because `actor()`
resolves the caller from the live token and
`ArchiveAdminService.describeIdentity` calls `requireAdministrator(actor)`, a
200 proves the server resolved **this token** to an active archive
administrator, and the body states the links and grants directly.

Five assertions, all of which must hold:

| # | Assertion |
|---|---|
| 1 | the authenticated administrator is the correct, expected identity |
| 2 | **exactly one** active identity link, and it is the expected one |
| 3 | grant 2 is active and associated with that identity |
| 4 | scope is `UNIT 1250000000` with **descendants excluded** |
| 5 | **no unexpected additional active grants** |

**If any assertion fails: restore revision 91 immediately and do not proceed
with tests 1–10.**

This is the point at which the indirect pre-flight evidence for checks 22–24
is replaced by direct verification. Until this response is read, that evidence
remains indirect and is not treated as direct verification. No new read-only
operator command is added during this window.

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
- Post-rollback verification, all four: revision 91 at 1/1 with rollout
  `COMPLETED`; health 200 `UP`; enforcement **and** auditing absent from the
  running definition; and **grant 2 revoked** (test 13's revocation confirmed
  to have persisted).
- Rollback is also triggered immediately by any unexpected authorization
  status, a failed gate assertion, or a failed pre-flight check.

## Reporting

**Every one of the 40 pre-flight checks** is reported as **PASS**, **FAIL**,
**CONDITIONAL** or **NOT YET EXECUTED**. Indirect evidence is reported as
CONDITIONAL, never as PASS — checks 22–24 are CONDITIONAL until the
administrator identity response is read.

**The GO/NO-GO result is returned at the beginning of the window, before any
activation.** If any mandatory pre-flight check reports FAIL, revision 92 is
not registered.

Test by test: **PASS / FAIL / NOT EXECUTED / UNVERIFIED**, with the status
observed and the evidence. Twelve executable tests are not twelve passing
tests unless all twelve actually pass.

## Operational constraints

- Account-wide ECS enumeration is not attempted; the cluster, service and
  family identifiers (`research-archive-platform-dev-api` for all three) are
  taken from `ops/deploy-api.sh` and are sufficient.
- No sensitive identifier, password, token, cookie or authentication header
  appears in any log, screenshot or repository file.
- **External dependency (accepted residual risk).** The account-level
  `shutdown-scheduler` rule fires every 5 minutes against a Lambda. It is a
  shared BU non-production utility, outside this project's control and **not
  to be modified**. Service stability is monitored throughout the window; if
  the service is disrupted from outside, the window ends and revision 91 is
  restored.
- **No Amplify deployment is triggered during the window.** PRs #131, #132 and
  #133 stay unmerged until after the test: Amplify auto-builds `main` from
  `origin`, so merging any of them would move the deployed UI mid-window.
  None of the three affects the API image used on Thursday — revision 92 pins
  the existing `sha256:f0f42ab6…b912f1` built from commit `458de9c`.
- No additional deployment, database change, identity provisioning or grant
  modification outside the approved test.
