# Security Administration — coordinated DEV release plan

**Status: awaiting approval. Nothing has been deployed and PRs #138 and
#149 are NOT merged.**

Backend and frontend ship as **one controlled release**, because merging
#138 publishes the frontend by itself. Prepared 2026-11-13.

---

## 1. Release contents

| | Detail |
|---|---|
| Backend commit | **`18750ba`** — `Merge pull request #142 from hiteshtara/feat/me-capabilities-endpoint`, now the head of `main` |
| Endpoint introduced by | `76695e9` `feat(secadmin): GET /api/v1/me/capabilities` |
| Frontend | PR **#138** (`cb8da49`) — held, unmerged |
| Documentation | PR **#149** (`a35ee75`) — held, unmerged |

### Verified state of the held PRs

| PR | Head | Mergeable | CI |
|---|---|---|---|
| #138 | `cb8da49` | MERGEABLE / CLEAN | 3/3 pass — UI 866 + 37, API unit 1729, PostgreSQL 530 |
| #149 | `a35ee75` | MERGEABLE / CLEAN | 3/3 pass |

---

## 2. The API image — built and verified, NOT pushed

Built from a **clean detached worktree** at `18750ba`, with the same
commands `ops/deploy-api.sh` runs.

```
tag          research-archive-platform-dev-api:20261113T165328Z-18750ba
image id     sha256:ea3e68e3112245a4e68e2a547d4c231973219b0ad7115ec96ccecf621c69a42e
platform     amd64 / linux
```

**The endpoint was verified in the jar *inside the image*, not the host
jar.** The Dockerfile is multi-stage and runs its own
`mvn clean package` from `src`, so the jar built on this machine is not
what ships. The jar was copied out of the image and inspected:

```
BOOT-INF/classes/.../web/MeCapabilitiesController.class    present
BOOT-INF/classes/.../application/me/CapabilitiesService.class  present
BOOT-INF/classes/.../web/dto/me/CapabilitiesResponse.class     present
route literals  "/api/v1/me" + "/capabilities"                 present
```

> The in-image check matters: a `docker run ... unzip` attempt returned
> "not found", which is a **tool error** — the JRE image ships no `unzip`
> and no `jar` — not evidence the class is absent. Treating that as a
> negative would have been wrong.

**Not pushed to ECR.** The push is step R2 of the release below.

### CORRECTION — the index/child trap applies to this path too

An earlier revision of this section claimed `deploy-api.sh`'s plain
`docker build --platform linux/amd64` produces a **single amd64
manifest**, so the index-versus-child digest trap would not apply.

**That was wrong**, and the release proved it. Modern BuildKit pushes an
OCI image index regardless:

```
tag 20261113T165328Z-18750ba
  -> sha256:ea3e68e3...  application/vnd.oci.image.index.v1+json   <- INDEX
       linux/amd64       sha256:e0e40a46...                        <- the image
       attestation       sha256:adfed8e1...
```

`aws ecr describe-images --image-ids imageTag=...` returns the **index**.
The task definition must pin the **child**, which is what ECS records and
what the rollback guard compares. Revision 102 pins
`sha256:e0e40a46...`, and T1 confirmed the service reports exactly that.

The rule in `docs/runbooks/deployment/IMAGE_PIN_UPDATE.md` was right all
along and applies to **every** build path: **take the pin from the serving
task definition**, never from `describe-images`.

---

## 3. Enforcement and auditing stay OFF — by construction

`AuthorizationProperties.enforcementEnabled` is a plain `boolean` with
**no entry in `application.yml`**, so it defaults to Java's `false`. It
becomes true only if `APP_AUTHORIZATION_ENFORCEMENT_ENABLED` is set on the
task definition. Revision 94 carries no such variable, `deploy-api.sh`
adds none, and the new revision inherits the current definition's
environment.

Enforcement is therefore off **by absence**, not by a setting someone
could flip. Step V1 asserts that absence rather than trusting it.

No migration runs. **V089 stays unapplied** — migrations are applied by
the Python ETL, never by an API deploy.

---

## 4. Why the backend goes first

The order is forced by two facts:

1. Merging #138 triggers an Amplify build immediately (`enableAutoBuild:
   true` on `main`), so the frontend publishes the moment it merges.
2. The capabilities endpoint must be **verified with a real BU SSO
   session before the frontend release is complete**.

So the API is deployed and verified **first**, while the frontend is still
held. Only then are the PRs merged, which publishes the UI into an
environment where the endpoint already works.

### Merge order within the release

**#149 first, then #138** — a deliberate change from the original
#138→#149 ordering.

Each merge fires its own Amplify build. Merging the documentation PR
first means the **final** Amplify job is unambiguously the Security
Administration UI release, and the job immediately before it is the clean
rollback target. The other order leaves the rollback target two jobs back.

---

## 5. Release sequence

### Phase R — prepare (no user-visible change)

| Step | Action | Gate |
|---|---|---|
| R0 | **GO/NO-GO checkpoint** — §7 | all green, or stop |
| R1 | Confirm identity: account **770203350335**, `AWS_PROFILE=bu-nprd` | exact match |
| R2 | Push the image to ECR | tag present in ECR |
| R3 | Record the digest ECS will reference | captured |
| R4 | Register the new task definition revision, pinned **by digest** | new revision number recorded |

Revision **94** is the rollback target throughout and is never
deregistered.

### Phase D — deploy the backend

| Step | Action | Gate |
|---|---|---|
| D1 | Update the service to the new revision | `deployments[0].status = PRIMARY` |
| D2 | Wait for steady state | running = desired, pending = 0 |
| D3 | ALB target health | `healthy` |
| D4 | `/actuator/health` | `{"status":"UP"}`, real JSON |

### Phase V — verify the backend with a real session

**The frontend release does not proceed until every check here passes.**

All calls use the absolute origin `https://api-dev.app-nprd.aws-cloud.bu.edu`
and assert the **body and `content-type`**, never status alone — a
relative `/api/...` on the UI origin returns the SPA's `index.html` with
HTTP 200, which has produced convincing fake results in this project
before.

| Step | Check | Expected |
|---|---|---|
| V1 | `APP_AUTHORIZATION_*` on the new task definition | **absent** — enforcement and auditing off |
| V2 | `GET /api/v1/me/capabilities` with **no** token | **401**, no disclosure |
| V3 | Sign in through **real BU SSO + Duo** | authenticated session |
| V4 | Same endpoint with that session's token | **200**, `{administrator:true, enforcementEnabled:false, administrationAvailable:false}` |
| V5 | Response headers | `Cache-Control: no-store` |
| V6 | Response body shape | exactly three boolean keys |
| V7 | `GET /api/v1/admin/identities?query=x` with the same token | **404** — the admin tree is off while enforcement is off |
| V8 | Archive regression set | dashboard counts, award `212137-00001`, Documents totals, Global Search — identical to the revision-94 baseline |

**V4 is the gate for requirement 6.** If it fails, stop and roll back the
API; the frontend is still unmerged, so nothing user-visible has changed.

### Phase F — publish the frontend

| Step | Action | Gate |
|---|---|---|
| F1 | Merge **#149** | Amplify job fires; record its id |
| F2 | Merge **#138** | Amplify job fires; **record its id — this is the release build** |
| F3 | Wait for that job | `SUCCEED` |
| F4 | Hard-reload the DEV UI | new bundle served |

### Phase U — verify the frontend

| Step | Check | Expected |
|---|---|---|
| U1 | Administrator signs in (real BU SSO + Duo) | **Security Admin** toggle visible |
| U2 | Select **Security Admin** | "Security Administration is unavailable"; enforcement reads **OFF** |
| U3 | Controls on that screen | **none** — no search box, button or form |
| U4 | Devtools network, filtered to `/api/v1/admin` | **zero requests** — screenshot it |
| U5 | Direct URL `/admin/overview` | unavailable screen, no controls |
| U6 | Direct URL `/admin/identities` | same, no search box |
| U7 | Direct URL `/admin` | redirects to `/admin/overview`, still guarded |
| U8 | Browser **back** from an admin URL | returns to the archive, screen gone |
| U9 | Browser **forward** into it | re-evaluates and guards again |
| U10 | Normal archive navigation | every sidebar route renders; counts match baseline |
| U11 | Console and network across the run | no 500; no 401/403/404 beyond V2, V7 and U12 |
| U12 | Non-administrator — see §6 | per §6 |
| U13 | Desktop ~1440px | toggle visible, layout intact |
| U14 | Mobile ~390px | layout intact, no horizontal scroll |
| U15 | Toggle at mobile width | **hidden by design** (`display: {xs:"none", md:"inline-flex"}`) |

### Phase T — Terraform consistency

| Step | Check | Expected |
|---|---|---|
| T1 | Re-read the serving digest from the **service** | matches the deployed image |
| T2 | Update `api_image_digest` in the dev tfvars | per `IMAGE_PIN_UPDATE.md` |
| T3 | `api_image_rollback_guard` | still **unset** — default `true` applies |
| T4 | `terraform plan` with no `-var` overrides | see the correction below |
| T5 | Guard still rejects a stale pin | plan with the old digest → precondition failure |

Baseline before the release: pin `sha256:1d440f251c…`, zero guard override
lines, `terraform plan` → `No changes.` (exit 0), verified today.

### CORRECTION — T4 cannot be clean from a pin update alone

This phase predicted `No changes.` after re-syncing the pin. **That was
wrong.** `deploy-api.sh` registers its revision *outside* Terraform, so
after the release Terraform's state still held revision 101 while the
service ran 102. Updating the pin then makes Terraform plan a
replacement:

```
Plan: 1 to add, 0 to change, 1 to destroy.
module.api_service.aws_ecs_task_definition.api  ["delete","create"]
  revision 101 -> (new)   image 1d440f251c... -> e0e40a46...
  skip_destroy: true   ECS service changes: 0   RDS changes: 0
```

`docs/runbooks/deployment/IMAGE_PIN_UPDATE.md` already documents this as
the expected outcome after a deploy Terraform never saw, and says
applying it is a deliberate, separately approved act. The runbook was
right; this phase's expectation was the error.

Nothing is broken by leaving it pending: the service runs 102, the guard
is armed and correct (T5 passed), and `ignore_changes = [task_definition]`
means the pending replacement cannot move the service.

---

## 6. The non-administrator check

Proving a non-administrator sees no toggle requires signing in **as** one.
The operator is the administrator, so this cannot be self-served.

Explicitly excluded:

- **Revoking the operator's own ARCHIVE_ADMIN membership to observe the
  UI.** This is the "manipulate real administrator membership merely to
  test UI visibility" that is ruled out. It would also risk the
  last-administrator guard and write audit rows for a fiction.
- **Using another person's credentials.** Never.

| Option | Outcome |
|---|---|
| A second authorized tester who holds no membership runs U12 | reported **PASS** or **FAIL** on evidence |
| No such tester available | reported **NOT VERIFIED** |

**NOT VERIFIED is not PASS**, and the release report must say so plainly.
The isolated automated coverage that stands in for it:

- `ModeToggle.test.tsx` — a non-administrator renders an **empty
  container**; so does every unverifiable state including hostile bodies
- `securityAdminPresentation.test.mjs` — all eight boolean combinations
- `CapabilitiesServiceTest` — `denied()` for every non-administrator
  resolution
- `CapabilitiesMembershipIntegrationTest` — no row, revoked row and
  suspended identity, against the real schema

That is real coverage of the logic. It is **not** evidence that a real
person with a real token sees no toggle in a real browser.

---

## 7. GO / NO-GO checkpoint (step R0)

Run immediately before R1. Every line must be green.

| # | Gate |
|---|---|
| 1 | AWS identity = **770203350335**, `AWS_PROFILE=bu-nprd` |
| 2 | `main` head = `18750ba` and contains the capabilities endpoint |
| 3 | #138 `cb8da49` and #149 `a35ee75` still MERGEABLE, CI green, heads unchanged |
| 4 | Image built from a clean worktree at `18750ba`; endpoint verified **in the image's jar** |
| 5 | Serving revision **94**; recorded as rollback target with its digest |
| 6 | Amplify `main` activeJobId recorded as the frontend rollback target |
| 7 | RDS `available`, `deletion_protection: true`, no pending modifications |
| 8 | `terraform plan` → `No changes.` |
| 9 | Revision-94 functional baseline captured as digests |
| 10 | A tester identified for U12, or U12 accepted as NOT VERIFIED |

Any red line → **NO-GO**, stop, report.

---

## 8. Rollback

Two independent procedures. The backend can roll back without touching the
frontend, and vice versa.

### 8.1 API rollback — to revision 94

```
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:94 \
  --force-new-deployment
```

Then confirm: service on `…-dev-api:94`, steady state, ALB `healthy`,
`/actuator/health` UP, baseline functional set restored.

Afterwards, restore the Terraform pin to
`sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981`
and confirm `terraform plan` → `No changes.`

No revision is deregistered. The new revision stays ACTIVE and unused.

### 8.2 Amplify frontend rollback — restore the prior build

The frontend rollback target is the Amplify job **active before F1**:

```
current activeJobId  0000000200   commit 18750ba   SUCCEED
```

Two ways to restore it, in order of preference:

1. **Revert the merge commits** on `main` (`git revert`) and push.
   `enableAutoBuild` then produces a new build of the reverted tree —
   the durable fix, because the next build from `main` stays correct.
2. **Redeploy the prior job** via the Amplify console's
   "Redeploy this version" on job 200. Faster, but `main` still contains
   #138, so the **next** merge to `main` republishes the UI. Only
   appropriate as a stopgap immediately followed by option 1.

`enableAutoBuild` is **not** disabled at any point — per the decision
recorded for this release.

### 8.3 Rollback triggers

Mandatory checks — any failure triggers the matching rollback:

| Failure | Rollback |
|---|---|
| V1, V2, V4, V5, V6, V7, V8 | API only (§8.1). Frontend not yet merged |
| D1–D4 | API only (§8.1) |
| U2, U3, U4, U5, U6 | Frontend (§8.2); API rollback only if the cause is the endpoint |
| U10, U11 | Both, if the archive is affected |
| T4 | No rollback — fix the pin; the deployment itself is sound |

---

## 9. Amplify builds this release will cause

Accounted for rather than discovered afterwards:

| Trigger | Build | User-visible effect |
|---|---|---|
| Merge of #142 (already done) | job **200**, `18750ba`, SUCCEED | none — no UI change in #142 |
| F1, merge of #149 | one build | none — documentation only |
| F2, merge of #138 | one build | **the Security Administration UI goes live** |

Jobs 197–200 today confirm the pattern: every merge to `main` builds, and
the ones with no UI change are behaviourally inert.

---

## 10. Out of scope — not done at any point

- No authorization enforcement enabled
- No auditing enabled
- **No V089**, no migration of any kind
- No grant created, modified or revoked; **grant 2 unchanged**
- No ARCHIVE_ADMIN membership added, revoked or altered
- No RDS resize or configuration change
- No ECS revision deregistered
- `enableAutoBuild` left **enabled**
- **No Production access of any kind**

---

## 11. Readiness

| Area | State |
|---|---|
| Backend code | merged, `18750ba` |
| API image | **built and verified, not pushed** |
| Frontend PR | #138 ready, held unmerged |
| Documentation PR | #149 ready, held unmerged |
| Automated tests | UI 866 + 37, API unit 1750, PostgreSQL 536 — all pass |
| Terraform | pin consistent, guard on by default, plan clean |
| Rollback | API and Amplify procedures defined, both targets recorded |
| Non-administrator live check | **unresolved** — needs a tester or a NOT VERIFIED mark |

**Assessment: ready to execute on approval.**

The one genuine gap is §6, and it is a staffing question rather than a
technical one. Everything else is verified rather than assumed: the image
was checked inside the container, enforcement is off by absence rather
than by configuration, and both rollback targets are recorded before
anything moves.

---

## 12. Execution record — 2026-11-13

**Executed and verified. One gate open (T4), one check NOT VERIFIED (U12).**

| | |
|---|---|
| API revision | **102**, derived from 94 — only the image differs |
| linux/amd64 digest | `sha256:e0e40a468b01673115b792521567a5f82fe967a1fe7e6dd6a7ea03eb44024c7a` |
| Index digest (not pinned) | `sha256:ea3e68e3112245a4e68e2a547d4c231973219b0ad7115ec96ccecf621c69a42e` |
| Source commit | `18750ba` |
| Steady state | 17:04:09Z, 1/1, ALB healthy, no outage |

Merges: #149 → `3a5d0d5`, #138 → `3a04f97`.

| Amplify job | Commit | Status | Meaning |
|---|---|---|---|
| 200 | `18750ba` | SUCCEED | pre-release — **frontend rollback target** |
| 201 | `3a5d0d5` | SUCCEED | #149, documentation only |
| 202 | `3a04f97` | SUCCEED | **#138 — the UI release** |

### Backend gates — all pass

V1 enforcement/auditing env absent · V2 401 without a token · V3 real BU
SSO/Duo session · V4 `{administrator:true, enforcementEnabled:false,
administrationAvailable:false}` · V5 `Cache-Control: no-store` · V6
exactly three boolean keys · V7 three admin endpoints 404
`"administrative API is not available"` · V8 dashboard 40,926/3,511,
award `212137-00001` 200, documents 491,685, Global Search 83 with zero
failed modules.

### Frontend gates — all pass except U12

U1 toggle visible · U2 unavailable screen, enforcement reads OFF · U3 no
controls · **U4 zero `/api/v1/admin` requests** · U5–U7 direct URLs
guarded and `/admin` redirects · U8/U9 back and forward re-evaluate · U10
all seven archive routes render · U11 zero console errors · U13/U14 no
horizontal scroll at 390px · U15 toggle `display:none` at mobile.

`/admin/identities` renders the unavailable screen with **0 buttons, 0
inputs, 0 forms** — the live DEV defect this work existed to fix.

**U12 — NOT VERIFIED.** No authorized non-administrator tester was
available and no membership was altered. Isolated automated tests cover
the logic; that is not evidence a real person sees no toggle.

### Three probe errors that were mine, not defects

During V7/V8 I used paths and parameters that do not exist —
`/api/v1/awards/{number}` (the route is `/by-number/{number}`), `?query=`
on the admin endpoint (it takes `q`), and `?q=` on global search (it
takes `query`). Each returned a 400 or 404 that would have read as a
regression. The controllers were checked rather than the results
recorded as findings. All console errors during verification trace to
those probes; none came from the application.

### Open

- **T4** — Terraform plan not clean; needs a separately approved apply
  (1 add / 1 destroy, service stays on 102, revision 101 stays ACTIVE).
- **U12** — needs a tester, or stands as NOT VERIFIED.

Revisions 94, 101 and 102 all ACTIVE; none deregistered. RDS unchanged.
Enforcement OFF, auditing OFF, V089 unapplied, grant 2 untouched,
Production never accessed.


---

## 13. T4 reconciliation — applied 2026-11-13

Approved separately and executed against a fresh saved plan.

| # | Condition | Result |
|---|---|---|
| 1 | AWS account | **770203350335** ✅ |
| 2 | Serving revision + digest, read from the SERVICE | **102**, `sha256:e0e40a46…` ✅ |
| 3 | Rollback guard enabled | zero override lines; declared `default = true` ✅ |
| 4 | Exactly one task-definition replacement | 1 resource, `["delete","create"]`, **1 add / 0 change / 1 destroy** ✅ |
| 5 | Zero ECS service, zero RDS, no other resource, no prod | 0 / 0 / 0 / 0 ✅ |
| 6 | `skip_destroy = true`, revision 101 not deregistered | true; 101 still **ACTIVE** after apply ✅ |
| 7 | Applied only the saved plan | `Apply complete! Resources: 1 added, 0 changed, 1 destroyed.` ✅ |
| 8 | New revision registered but unused | **103** ACTIVE; service points at **102** ✅ |
| 9 | 102 still serving, healthy, no restart | same task `41c8dc3f…`, same `startedAt` 12:02:00, ALB `healthy` ✅ |
| 10 | Final DEV plan | **`No changes.`**, exit 0 ✅ |

Guard re-verified after the apply: a plan pinning the old revision-94
digest is still refused with `Resource precondition failed`.

Revisions 94, 101, 102 and 103 are all ACTIVE; none deregistered. RDS
unchanged. API `/actuator/health` UP throughout.

### A transient credential failure worth recording

Mid-verification, two `aws ecs` calls failed with
`UnrecognizedClientException: The security token included in the request
is invalid`, while `sts get-caller-identity` succeeded either side of
them. The empty results made a pin comparison print **"PIN MISMATCH"**,
which was an artifact of the failed calls, not a real mismatch — the
retry showed an exact match. Nothing was applied on the strength of that
output. If this recurs, re-read before concluding anything: an AWS error
is not a negative result.

---

## 14. U12 — status NOT VERIFIED

Lalitha (lhuval@bu.edu) is the designated tester; her DEV Cognito
identity is `BUShibboleth_lhuval@bu.edu`, `EXTERNAL_PROVIDER`, enabled.
Instructions: `docs/security/U12_NON_ADMINISTRATOR_TEST.md`.

**The proviso is not yet verified.** Whether her identity holds an ACTIVE
`ARCHIVE_ADMIN` membership is recorded in `authz.admin_role_member` in
the DEV database, which this session cannot read:

- the administrative API 404s while enforcement is off, by design
- the SSM tunnel is **"proposed, not deployed"** — gated behind
  `manage_db_tunnel_access`, default `false`; creating it would be an
  infrastructure change
- no administrator roster is recorded in the repository

The approved read-only route is CloudShell, which **is** enabled
(`manage_cloudshell_analyst_access = true`). The query:

```sql
SELECT institutional_identifier, status, granted_at
FROM authz.admin_role_member
WHERE status = 'ACTIVE'
ORDER BY granted_at;
```

Note the table keys on `institutional_identifier`, never on a login name
or email, so the result needs mapping to a person.

Until that check is done **and** Lalitha completes the test, U12 stays
**NOT VERIFIED** — not PASS. Her instructions also make the
toggle-appeared case interpretable: it would mean she holds the role and
is the wrong tester, not that the product is broken.

**No account, membership or grant was changed.**
