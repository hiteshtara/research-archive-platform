# Security Administration — DEV deployment and live verification plan

**Status: awaiting approval. Nothing in this document has been executed.**

Covers deploying the merged Security Administration work to **DEV only** and
verifying it against real BU SSO. Prepared 2026-11-13.

---

## 0. The blocker to settle first

**Merging PR #138 publishes the UI to DEV automatically.** This is not a
choice the merge makes explicitly — it is how the environment is wired:

```
aws amplify get-app   --app-id d288p9gmoteftb  -> repository: github.com/hiteshtara/research-archive-platform  (origin)
aws amplify get-branch --branch-name main      -> enableAutoBuild: true
```

Every merge to `origin/main` today already triggered a build and deploy:

| Amplify job | Commit | PR |
|---|---|---|
| 197 | `0068db9` | #145 |
| 198 | `0e2c3c1` | #146 |
| 199 | `7b19533` | #147 |

Those were documentation and Terraform only, so the rebuilt UI was
behaviourally identical and nothing changed for a user. **#138 changes the
UI**, so merging it deploys the Security Administration interface to DEV
with no further action.

What would actually appear, given the API is **not** deployed at that point:
`/api/v1/me/capabilities` returns 404 → `getCapabilities()` returns `null` →
`readCapabilities` fails closed → **no toggle for anyone**. The `/admin/*`
routes would exist and render "Administrator access required" instead of
a 404.

So the exposure is small, but it is still an unapproved deployment. Three
ways forward, for a decision before #138 merges:

| | Action | Consequence |
|---|---|---|
| **A** | Set `enableAutoBuild: false` on `main`, merge #138 and #149, deploy deliberately later, restore the setting afterwards | No automatic deploy. One temporary infrastructure change, reversible, DEV-only |
| **B** | Merge #138 and accept the automatic UI deploy | UI ships to DEV now, inert until the API deploys. No approval for that deploy |
| **C** | Hold #138 and #149 until the deployment itself is approved, then merge and deploy together | Nothing changes now; the merge and deploy become one approved event |

**Recommended: C**, then A's safeguard if the merge must happen earlier
than the deployment. C keeps merge and deploy as one reviewed act, which
is how every other change in this sequence has been handled.

---

## 1. What gets deployed

| Component | Source | Mechanism |
|---|---|---|
| API | merged `main` (contains #142, `18750ba`) | `ops/deploy-api.sh` from a clean detached worktree |
| UI | merged `main` (contains #138) | Amplify build on `main` |

**Deployment path** (the only supported one): clean committed source → local
Docker build (`--platform linux/amd64`) → BU non-production ECR → ECS
Fargate. No CodeBuild, no GitHub Actions deployment.

### Enforcement and auditing stay OFF — by construction

`AuthorizationProperties.enforcementEnabled` is a plain `boolean` field with
**no entry in `application.yml`**, so it defaults to Java's `false`. It
becomes true only if `APP_AUTHORIZATION_ENFORCEMENT_ENABLED` is set on the
task definition. Revision 94 carries no such variable, and neither
`deploy-api.sh` nor the Terraform task definition adds one.

So enforcement is off **by absence**, not by a setting someone could
mistakenly flip. The verification below asserts the absence directly rather
than trusting it.

No migration is run. **V089 stays unapplied** — migrations are applied by
the Python ETL, never by an API deploy, and none is invoked here.

---

## 2. Pre-deployment gates

| # | Gate | Required |
|---|---|---|
| 1 | AWS identity | account **770203350335**, `AWS_PROFILE=bu-nprd` |
| 2 | §0 decided and the merges done | #142 ✅ merged `18750ba`; #138, #149 per §0 |
| 3 | CI green on the exact merged commit | all three jobs |
| 4 | Revision **94** recorded as the rollback target | `…-dev-api:94`, image `sha256:1d440f251c…` |
| 5 | RDS healthy | `available`, `deletion_protection: true`, no pending modifications |
| 6 | Build from a **clean detached worktree** at the release commit | never the live working directory |
| 7 | Image platform | `linux/amd64` child manifest confirmed from the ECR index |
| 8 | Baseline functional capture on revision 94 | so "unchanged" can be proved, not assumed |

Abort if any gate fails.

---

## 3. Deployment sequence

1. **Capture the baseline** on revision 94: award `212137-00001`, dashboard
   counts, Documents totals, Global Search — recorded as digests, not
   impressions.
2. **Build and push** the API image from a clean worktree at the release
   commit.
3. **Register and deploy** the new task definition, pinned by the
   **linux/amd64 child digest**.
4. **Wait for steady state**: `deployments[0].status = PRIMARY`,
   running = desired, ALB target `healthy`.
5. **Re-sync the Terraform pin** per
   `docs/runbooks/deployment/IMAGE_PIN_UPDATE.md`, then confirm
   `terraform plan` → `No changes.`
6. **Record the new revision number** and keep **94** as the rollback target.

The API deploy does **not** restart the database, change RDS, or touch any
grant.

---

## 4. Live verification

All checks use the **absolute API origin** `https://api-dev.app-nprd.aws-cloud.bu.edu`
and assert the **response body and `content-type`**, never the status alone.
A relative `/api/...` on the UI origin returns the SPA's `index.html` with
HTTP 200 — that has produced convincing fake results in this project before.

### 4.1 Authentication — real BU SSO/Duo

| # | Check | Expected |
|---|---|---|
| 1 | Sign in at the DEV UI through BU SSO, completing **Duo** | lands authenticated |
| 2 | Session carries a usable token | an authenticated API call returns JSON |

If the session expires mid-run, **stop and re-authenticate** rather than
interpreting the resulting 401s as findings.

### 4.2 Capabilities endpoint with enforcement OFF

| # | Check | Expected |
|---|---|---|
| 3 | `GET /api/v1/me/capabilities` **without** a token | **401**, no body disclosure |
| 4 | With an **administrator** token | **200**, `{administrator:true, enforcementEnabled:false, administrationAvailable:false}` |
| 5 | Response headers | `Cache-Control: no-store` |
| 6 | Response shape | exactly three boolean keys, nothing else |
| 7 | Enforcement state on the task definition | `APP_AUTHORIZATION_*` **absent** |

Check 4 is the one that proves the endpoint is reachable with enforcement
off — the behaviour that distinguishes it from the administrative API.

### 4.3 Administrator sees the toggle

| # | Check | Expected |
|---|---|---|
| 8 | Signed in as a **verified active administrator** | **Security Admin** toggle visible in the header |
| 9 | Toggle selection state on an archive route | "Research Archive" selected |

### 4.4 Non-administrator does **not** see the toggle

This is the one check that **cannot be completed by the operator alone**, and
the plan says so rather than quietly skipping it. See §6.

| # | Check | Expected |
|---|---|---|
| 10 | A second authorized tester who is **not** an administrator signs in | **no** toggle, **no** admin nav entry |
| 11 | Their `GET /api/v1/me/capabilities` | **200**, all three `false` |

### 4.5 Unavailable screen, and zero administrator API calls

| # | Check | Expected |
|---|---|---|
| 12 | Administrator opens `/admin/overview` | "Security Administration is unavailable"; enforcement reads **OFF** |
| 13 | Interactive controls on that screen | **none** — no search box, no button, no form |
| 14 | Browser devtools network tab, filtered to `/api/v1/admin` | **zero requests** |
| 15 | Direct `GET /api/v1/admin/identities?query=x` with the admin token | **404** |

Check 14 is the decisive one: it is the difference between "the UI hides the
controls" and "the UI does not call the API". Capture it as a screenshot of
the filtered network tab.

### 4.6 Direct URL access exposes no controls

| # | Check | Expected |
|---|---|---|
| 16 | Type `/admin/overview` directly | unavailable screen (admin) / access-denied (non-admin) |
| 17 | Type `/admin/identities` directly | same, no search box |
| 18 | Type `/admin` directly | redirects to `/admin/overview`, still guarded |
| 19 | Browser **back** from an admin URL | returns to the archive, denial gone |
| 20 | Browser **forward** into it | re-evaluates and guards again |

### 4.7 Normal Research Archive navigation

| # | Check | Expected |
|---|---|---|
| 21 | Dashboard counts | identical to the revision-94 baseline |
| 22 | Award `212137-00001` | identical content digest |
| 23 | Documents search | identical totals |
| 24 | Global Search "NSF" | identical result shape |
| 25 | Every sidebar route | renders, no blank page |

### 4.8 No unexpected status codes

| # | Check | Expected |
|---|---|---|
| 26 | Browser console across the whole run | no uncaught errors |
| 27 | Network log across the whole run | no **500**; no **401/403/404** other than the ones asserted above (checks 3, 11, 15) |
| 28 | API logs for the window | no new error-level entries |

Expected-by-design codes are enumerated so an unexpected one is actually
visible, rather than lost in a crowd of normal denials.

### 4.9 Desktop and mobile

| # | Check | Expected |
|---|---|---|
| 29 | Desktop ~1440px | toggle visible, layout intact |
| 30 | Mobile ~390px | layout intact, no horizontal scroll, navigation usable |
| 31 | Toggle at mobile width | **hidden by design** — `display: {xs:"none", md:"inline-flex"}` |

Check 31 is a deliberate design decision, not a defect. Worth confirming the
mobile experience is coherent without it — an administrator on a phone has no
route into the admin area, which is acceptable only if intended.

---

## 5. Abort and rollback

**Mandatory checks** — any failure triggers rollback: 3, 4, 7, 12, 13, 14,
15, 21, 22, 27.

Rollback:

```
aws ecs update-service --region us-east-1 \
  --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:94 \
  --force-new-deployment
```

Then confirm steady state on 94, ALB `healthy`, and the baseline functional
set restored. The UI rolls back by redeploying the previous Amplify job.

Revision 94 is **not** deregistered at any point, and neither is any other
revision.

---

## 6. The one check the operator cannot do alone

**Check 10 requires a second person.** Verifying that a non-administrator
sees no toggle means signing in **as** a non-administrator, and the operator
account is the administrator.

The two wrong ways to get around this, both excluded:

- **Revoking the operator's own ARCHIVE_ADMIN membership to observe the UI.**
  This is exactly the "manipulate real administrator membership merely to
  test UI visibility" that is ruled out. It also risks the
  last-administrator guard and writes audit rows for a fiction.
- **Using someone else's credentials.** Never.

The legitimate options:

1. **A second authorized tester** who already holds no membership signs in
   and reports checks 10–11. Preferred.
2. **Treat it as covered by isolated tests.** The automated suite already
   proves it at both layers: `ModeToggle` renders an empty container for a
   non-administrator (6 tests), and `CapabilitiesService` returns
   `denied()` for every non-administrator resolution (13 tests), including
   against the real schema (6 more).

If no second tester is available, option 2 is honest coverage — but the
report must say check 10 was **not** verified live, not imply it was.

---

## 7. What this plan does NOT do

- No authorization enforcement, and none enabled
- No auditing enabled
- **No V089**, and no migration of any kind
- No grant created, modified or revoked; **grant 2 unchanged**
- No ARCHIVE_ADMIN membership added, revoked or altered
- No RDS resize, parameter or configuration change
- No ECS revision deregistered
- **No Production access of any kind**

---

## 8. GO / NO-GO

**NO-GO until §0 is decided.** The deployment itself is ready and low risk —
the API change is additive and reports state without granting anything, and
the UI fails closed when the endpoint is absent.

What is not ready is the **sequencing**: merging #138 deploys the UI by
itself, which is the one thing the approval for this work explicitly
excluded. Settle §0, and this plan is executable as written.
