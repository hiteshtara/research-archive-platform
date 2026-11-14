# Read-only Security Administration — DEV activation plan

**Status: awaiting approval. Nothing enabled, nothing deployed.**

Activates the PR #152 flags in **DEV only**, so a verified active
`ARCHIVE_ADMIN` can review identities, administrators, existing grants and
audit history — while record-level enforcement stays **OFF** and every
write stays refused.

Prepared 2026-11-13.

---

## 1. Target configuration

| Setting | Value | How |
|---|---|---|
| `app.authorization.administration-enabled` | **true** | `APP_AUTHORIZATION_ADMINISTRATION_ENABLED=true` on the task definition |
| `app.authorization.administration-writes-enabled` | **false** | left unset — Java default |
| `app.authorization.enforcement-enabled` | **false** | left unset — Java default |
| Auditing | **unchanged** | no auditing configuration exists to change; see §6 |

One environment variable is added. Nothing is removed, and nothing else on
the task definition changes.

### Why writes stay off by absence

`administrationWritesEnabled` has no YAML entry and defaults to Java's
`false`. `isAdministrationWritesEnabled()` additionally requires
`administrationEnabled`, so a writes-only mistake cannot open anything.
Step V2 asserts the variable's absence rather than trusting it.

---

## 2. Deployment mechanics

PR #152 must be merged and deployed first. The flag lives on the **ECS task
definition**, so activation is a new revision — the API image itself does
not change.

| Step | Action |
|---|---|
| A1 | **GO/NO-GO** (§5) |
| A2 | Confirm account **770203350335** |
| A3 | Record the serving revision as the rollback target |
| A4 | Derive a new revision from the serving one, adding the single variable |
| A5 | Update the service; wait for steady state and ALB `healthy` |
| A6 | Re-sync the Terraform pin and confirm a clean plan |

Note for A6: the Terraform pin tracks the **image**, which is unchanged, but
the task definition revision changes, so Terraform's state will again be one
revision behind. That is the same reconciliation already documented in
`docs/runbooks/deployment/IMAGE_PIN_UPDATE.md` and needs its own approval.

---

## 3. Verification

All API calls use the absolute origin
`https://api-dev.app-nprd.aws-cloud.bu.edu` and assert the **body and
content-type**, never status alone.

### V1-V2 — configuration actually applied

| # | Check | Expected |
|---|---|---|
| V1 | `APP_AUTHORIZATION_ADMINISTRATION_ENABLED` on the serving revision | `true` |
| V2 | `APP_AUTHORIZATION_ENFORCEMENT_ENABLED` and any `*WRITES*` variable | **absent** |
| V3 | `GET /api/v1/me/capabilities` with a real BU SSO token | `administrator:true`, `enforcementEnabled:false`, `administrationAvailable:true`, `administrationWritesAvailable:false` |

V3 is the single most informative check: all four booleans in one response,
showing administration open and both enforcement and writes off.

### V4 — the four read endpoints, real ARCHIVE_ADMIN identity

| # | Endpoint | Expected |
|---|---|---|
| V4a | `GET /api/v1/admin/identities?q=<term>` | **200**, JSON array |
| V4b | `GET /api/v1/admin/identities/{institutionalIdentifier}` | **200**, identity detail incl. existing grants |
| V4c | `GET /api/v1/admin/administrators` | **200**, roster incl. the signed-in administrator |
| V4d | `GET /api/v1/admin/audit` | **200**, audit entries |

Use an institutional identifier obtained from V4a rather than a guessed one
— the table keys on `institutional_identifier`, never an email.

### V5 — the eight write endpoints refuse, and change nothing

Each must return **409** with `"code":"ADMINISTRATION_WRITES_DISABLED"`:

| # | Endpoint |
|---|---|
| V5a | `POST   /identities/{id}/links` |
| V5b | `DELETE /identities/{id}/links/{linkId}` |
| V5c | `PUT    /identities/{id}/suspension` |
| V5d | `POST   /identities/{id}/grants` |
| V5e | `PATCH  /identities/{id}/grants/{grantId}` |
| V5f | `DELETE /identities/{id}/grants/{grantId}` |
| V5g | `POST   /administrators` |
| V5h | `DELETE /administrators/{identifier}` |

**Then prove nothing changed**, which is the point rather than the status
code: re-run V4b, V4c and V4d and confirm the grant list, the administrator
roster and the **audit entry count** are identical to before V5.

> The audit count is the strongest available signal from outside the
> database. `AdministrationReadOnlyIntegrationTest` already proves the
> same property directly against the real schema, counting
> `authz.access_grant`, `authz.access_audit`, `identity_link`,
> `admin_role_member` and `person_status` before and after all eight
> writes — including a non-vacuity case showing the same request does
> insert once writes are enabled. The DEV check corroborates it; it does
> not carry the proof alone.

Use a **non-administrator** institutional identifier as the write target, so
that even a failure of the gate could not alter the reviewing
administrator's own access.

### V6 — ordinary Research Archive access unchanged

Compare against the revision-102 baseline captured before activation:

| # | Check | Baseline |
|---|---|---|
| V6a | Dashboard counts | awards 40,926 · proposals 33,960 · award history 267,386 · proposal history 119,788 · negotiations 10,775 · subawards 3,511 · documents 491,678 |
| V6b | Award `212137-00001` via `/by-number/` | 200, `awardId` 3798004 |
| V6c | Documents total | 491,685 |
| V6d | Global Search `?query=NSF` | 83 results, **0** failed modules |
| V6e | Every sidebar route renders | no blank page, no error |

A change in any of these would mean record scoping had been applied, which
is the failure this whole separation exists to prevent.

### V7 — the Security Admin screens are real, not the unavailable message

| # | Check | Expected |
|---|---|---|
| V7a | `/admin/overview` | the real overview, **not** "Security Administration is unavailable" |
| V7b | `/admin/identities` | a working identity search returning real results |
| V7c | `/admin/administrators` | the real roster |
| V7d | `/admin/audit` | real audit history |
| V7e | Status panel | "Administration: Available", "Write Operations: Disabled (read-only)", "Authorization Enforcement: OFF" |
| V7f | Any write control on those screens | absent or disabled |
| V7g | Devtools network while browsing | admin reads present; **no** write verb to `/api/v1/admin/*` |
| V7h | `/admin/units` | **still the overview** — see §7; no permissions screen exists yet |

### V8 — status codes

No **500** anywhere. The only non-2xx responses should be the 409s from V5
and a 401 from an unauthenticated probe. Any other 4xx is a finding.

---

## 4. Rollback

Configuration-only:

```
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition <the revision recorded at A3> \
  --force-new-deployment
```

The administrative surface returns to **404** with no code change, no data
change and no migration. No revision is deregistered.

Nothing written during a read-only activation needs undoing, because by
construction nothing can be written — which is the main argument for doing
this phase before writes.

---

## 5. GO / NO-GO (step A1)

| # | Gate |
|---|---|
| 1 | PR #152 merged, CI green on the merged commit |
| 2 | API deployed from that commit, serving and healthy |
| 3 | Account **770203350335** |
| 4 | Serving revision recorded as the rollback target |
| 5 | Revision-102 functional baseline captured (V6) |
| 6 | A real BU SSO session available for V3-V5 |
| 7 | An institutional identifier for a **non-administrator** chosen as the write target |
| 8 | RDS healthy, `deletion_protection: true` |

Any red line → NO-GO.

---

## 6. Auditing — unchanged, and already independent

No auditing configuration is altered because **there is none to alter**.
Administrator auditing is not feature-flagged:

- `authz.access_audit` comes from **V082**, applied to DEV
- it is **append-only by database trigger** (`authz.reject_audit_change()`),
  not by convention
- rows are written inside the same transaction as the action
- no audit path reads the enforcement flag

Enabling read-only administration does not change auditing behaviour. It
also produces no new audit rows, because reads are not audited and writes
are refused before any work happens.

**V089 is not applied and not required.** It is `archive_activity_audit`
(SEC-008) — auditing of *record access by ordinary users* — which exists
only on an unmerged branch and is unrelated to administrator auditing.

---

## 7. Follow-up A — the Unit Permissions page

`/admin/units` is a navigation entry labelled "Permissions" that renders the
**overview** screen. No permissions view exists. Reported rather than
quietly built.

Smallest version, reusing existing APIs with **no new endpoint**:

| Need | Existing source |
|---|---|
| Which identities hold grants | `GET /api/v1/admin/identities?q=` |
| A person's grants, type, scope, descendants, expiry | `GET /api/v1/admin/identities/{id}` (already returns them) |
| Unit names and hierarchy | `JdbcUnitHierarchy`, already used by the authorization engine |

So the page is a **re-presentation**: group the grants already returned by
the identity-detail endpoint by unit rather than by person, and render them
read-only. It needs `mayCallAdminApi` to gate its fetches and
`allowsAdminWrites` to withhold any control, both of which PR #152 adds.

Two honest limitations to decide on first:

1. **There is no "grants by unit" endpoint.** Building the view purely from
   existing APIs means fetching identities and reading each one's grants,
   which is N+1 and only acceptable at DEV scale. A proper version wants a
   single read endpoint — new API surface, so a separate decision.
2. **Deciding what the page is for.** "Unit Permissions" could mean *who
   can see this unit* or *what this person can see*. The second already
   exists on the identity screen. Only the first justifies a new page, and
   it is the one that needs the new endpoint.

Recommendation: hold this until read-only administration has been used
once. The identity and administrator screens may already answer the
question, in which case the right change is to **rename or remove the nav
entry** rather than build a screen.

---

## 8. Follow-up B — the stale readiness status

`ui/src/features/qa/securityReadiness.json` is a **committed snapshot**, and
it is now wrong:

| Field | File says | Reality |
|---|---|---|
| `verifiedAt` | 2026-11-11 | — |
| `apiTaskDefinition` | `…-dev-api:91` | **102** serving (103 registered) |
| `taskDefinitionIsNewest` | `true` | **false** — 11 revisions behind |
| `imageCommit` | `458de9c` | `18750ba` |

It should not be presented as current. Two things are needed, and they are
separable.

### B1 — stop asserting a stale fact (small, immediate)

Until a live source exists, the page must not claim a revision number as
current. Either drop the revision row, or label it explicitly as "recorded
on 2026-11-11" so it reads as history rather than status. This is a
few lines and carries no risk.

### B2 — a live source (the real fix)

`/actuator/info` is already exposed and returns `{}` because **no
`build-info` goal is configured** in `api/pom.xml`. Adding it populates
version, build time and — with `git-commit-id` — the commit:

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <executions>
    <execution><goals><goal>build-info</goal></goals></execution>
  </executions>
</plugin>
```

That gives the **commit and build time** live. It does **not** give the ECS
task-definition revision, which the application cannot know about itself.
Options for that, in increasing cost:

| Option | Gives | Cost |
|---|---|---|
| `build-info` only | commit, build time, version | one plugin execution; the page derives "which code is running" |
| Add the revision as an env var on the task definition | exact revision | the deploy script must set it, and it can go stale if someone forgets |
| Read it from the ECS API | authoritative revision | the API would need ECS describe permission — new IAM surface, not worth it |

Recommendation: **`build-info` plus the capabilities endpoint**. Between
them they cover "which code is running" and "what is switched on" from live
sources, which is what the page is actually trying to say. The
task-definition revision is an infrastructure detail that belongs in the
deployment record, not on a status page.

---

## 9. Out of scope

No enforcement activation, no administrator writes, no V089, no migration,
no grant or membership change, no RDS change, no Production access. Phase 3
write safeguards (self-granting, grant expansion, administrator promotion)
remain open and are why writes stay off.

---

## Appendix — the exact activation sequence

Verified against DEV on 2026-11-13. The serving revision is **102** and its
container carries **8** environment variables, **none** authorization-related:

```
APP_EXPLORER_ENABLED            APP_CORS_ALLOWED_ORIGINS
SPRING_PROFILES_ACTIVE          APP_SEARCH_SEMANTIC_ENABLED
COGNITO_ISSUER_URI              ARCHIVE_DOCUMENTS_BUCKET
COGNITO_CLIENT_ID               APP_SEARCH_TIMING_ENABLED
```

That absence is the current proof that record enforcement and
administration are both off. Activation adds exactly **one**.

### A0 — preconditions

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity --query Account --output text    # expect 770203350335
```

PR #152 merged, deployed, serving and healthy. Record the serving revision
as the rollback target.

### A1 — derive a new revision, adding one variable

Derived from the **serving** revision so nothing else can drift:

```bash
SERVING=$(aws ecs describe-services --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text)

aws ecs describe-task-definition --region us-east-1 \
  --task-definition "$SERVING" --query 'taskDefinition' > /tmp/td.json
```

Then, in that JSON: drop the server-assigned fields
(`taskDefinitionArn`, `revision`, `status`, `requiresAttributes`,
`compatibilities`, `registeredAt`, `registeredBy`, `deregisteredAt`) and
append **one** entry to `containerDefinitions[0].environment`:

```json
{ "name": "APP_AUTHORIZATION_ADMINISTRATION_ENABLED", "value": "true" }
```

Before registering, assert the diff is exactly that — the same
object-comparison check used for revision 102, which proved only the image
differed:

- environment count **8 → 9**
- no `*WRITES*` variable present
- no `APP_AUTHORIZATION_ENFORCEMENT_ENABLED` present
- image digest **unchanged**
- cpu, memory, roles, log configuration unchanged

```bash
aws ecs register-task-definition --region us-east-1 \
  --cli-input-json file:///tmp/td.json \
  --query 'taskDefinition.revision' --output text
```

### A2 — deploy and wait

```bash
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:<new> \
  --force-new-deployment

aws ecs wait services-stable --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api
```

Then confirm one deployment, 1/1 running, ALB target `healthy`, and
`/actuator/health` returning `{"status":"UP"}`.

### A3 — the decisive check, in one request

```bash
# with a real BU SSO session's bearer token
curl -s https://api-dev.app-nprd.aws-cloud.bu.edu/api/v1/me/capabilities \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json' -i
```

Required:

```json
{ "administrator": true,
  "enforcementEnabled": false,
  "administrationAvailable": true,
  "administrationWritesAvailable": false }
```

with `Cache-Control: no-store`. All four booleans in one response — this is
the fastest way to see that administration is open while both enforcement
and writes are off. If `enforcementEnabled` is anything but `false`, stop
and roll back.

### A4 — rollback, if needed

```bash
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:102 \
  --force-new-deployment
```

The surface returns to 404. No code change, no data change, no migration,
no revision deregistered. Nothing written during a read-only activation
needs undoing, because nothing can be written.

### Note on Terraform

The image is unchanged, so the `api_image_digest` pin stays correct. But the
task-definition **revision** moves, so Terraform's state goes one revision
behind again and a plan will show the familiar `1 to add, 0 to change, 1 to
destroy` replacement. That is the reconciliation in
`docs/runbooks/deployment/IMAGE_PIN_UPDATE.md` and needs its own approval —
it is not part of activation, and leaving it pending breaks nothing because
`ignore_changes = [task_definition]` keeps the service where it is.
