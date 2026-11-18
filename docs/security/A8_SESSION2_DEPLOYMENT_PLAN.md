# DEV API deployment for A8 session 2 (option 2a): plan for approval

Status: **prepared 2026-11-18, nothing pushed or deployed.** Shared DEV is on
`research-archive-platform-dev-api:106` (image `c85be89a…`, source `1c3f0c8`), 1/1,
with enforcement OFF. Each block below runs only after the owner approves it.

## 1. What is being deployed

| Item | Value |
|---|---|
| Source | `main` **`f6addd4098cc13fe5585ece89c4ad85af799f537`** = merge of #172 (`da71993a`) onto `b5aa32b1` (merge of #171, `499fdb40`) |
| Code since rev 106 (`1c3f0c8`) | #171: IO grant authorizes the Award only (`RecordAccessEvaluator`, `RecordScopeSqlBuilder`, `RecordAuthorizationService` comments). #172: Global Search `requireProvisioned()`, `AccessControlFailures` in the three AI services, `RecordVisibility.requireProvisioned()` default, UI `searchErrorMessage` |
| Config, `application*.yml`, migrations | **no change** |
| Local image | `research-archive-platform-dev-api:f6addd4`, linux/amd64, image ID `sha256:7257b72f00b6…`. Built from a clean detached worktree at `f6addd40` with the repo Dockerfile, a multi-stage build that compiles inside the image |
| Image content check | `/app/app.jar` contains `AccessControlFailures.class`; `GlobalSearchService` and `RecordVisibility` contain `requireProvisioned`; the three AI services contain `rethrowIfAccessControl` (byte search in Python) |
| CI on these commits | #171 run 44380317392 and #172 run 44389278454: unit, UI and PostgreSQL all green. Amplify job 219 SUCCEED for `f6addd40` |

## 2. Revision 107 = revision 106 with only the image changed

Built from the live `:106` JSON (`rev107-input-template.json`, sha256 `2e7cd558…`).
The field-by-field diff against live `:106` has **exactly one difference**:

```
.containerDefinitions[0].image
  :106  …/research-archive-platform-dev-api@sha256:c85be89ade227565dcb561d0df98557aa30be37a0f95d7c96ed3fab8e3725b90
  :107  …/research-archive-platform-dev-api@sha256:<ECR digest of tag f6addd4, filled after push>
```

Everything else is identical:
- 9 environment variables. The only authorization ones are `APP_AUTHORIZATION_ADMINISTRATION_ENABLED=true` and `APP_EXPLORER_ENABLED=false`. There is **no** enforcement, administrator-writes, audit or IRB setting, so all keep their defaults (OFF / IRB unchanged).
- 5 secrets (`POSTGRES_DB/HOST/PASSWORD/PORT/USER`, Secrets Manager references).
- Task role `research-archive-platform-dev-api-task-role` and execution role `research-archive-platform-dev-api-execution-role`.
- 512 CPU / 1024 MB, port 8080, logging, tags.

The image is pinned **by digest**. The ECR repository's tag mutability is
`MUTABLE`, so the commit-SHA tag `f6addd4` is a unique, never-reused label, but
the digest is what makes `:107` immutable.

## 3. Migration safety (V089 / V090 cannot be applied)

- **No migration changes:** none since `1c3f0c8`, and the newest in `database/migrations/` on `f6addd40` is **V088**.
- **Live DEV database (read-only check, 2026-11-18):** 86 applied migrations, latest **88**; **no 089, no 090**; none of their tables exist.
- **Where V089/V090 live:** only in `database/migrations-pending-approval/`, with rollbacks in `database/rollback/`. The ETL migrator reads `database/migrations/` only.
- **The API cannot migrate:** `spring.flyway.enabled: false` and `ddl-auto: none`, so the API never migrates on start.
- **This deployment runs no loader:** so nothing can apply a migration.

## 4. Commands for approval (AWS_PROFILE=bu-nprd, account 770203350335)

```
R=research-archive-platform-dev-api
ECR=770203350335.dkr.ecr.us-east-1.amazonaws.com
# P0 pre-checks: account; service :106 1/1 one deployment; listener default only; no a8 task running

# P1 protect the rollback image (rule 2 keeps only the latest 20 images)
M=$(aws ecr batch-get-image --repository-name $R --image-ids imageDigest=sha256:c85be89ade227565dcb561d0df98557aa30be37a0f95d7c96ed3fab8e3725b90 --query 'images[0].imageManifest' --output text)
aws ecr put-image --repository-name $R --image-tag protected-rev106-1c3f0c8 --image-manifest "$M"

# P2 push the built image (tag must not already exist; verified free 2026-11-18)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR
docker tag $R:f6addd4 $ECR/$R:f6addd4 && docker push $ECR/$R:f6addd4
D=$(aws ecr describe-images --repository-name $R --image-ids imageTag=f6addd4 --query 'imageDetails[0].imageDigest' --output text)
#    verify: manifest platform linux/amd64; scan result has no new CRITICAL; record D
aws ecr put-image --repository-name $R --image-tag protected-rev107-f6addd4 --image-manifest "$(aws ecr batch-get-image --repository-name $R --image-ids imageDigest=$D --query 'images[0].imageManifest' --output text)"

# P3 register :107 from the template with D filled in; re-diff the REGISTERED :107 against :106 (expect image only)
aws ecs register-task-definition --cli-input-json file://rev107-input.json

# P4 deploy
aws ecs update-service --cluster $R --service $R --task-definition $R:107
aws ecs wait services-stable --cluster $R --services $R
```

## 5. Smoke test (read-only, about 10 min)

| Check | Pass |
|---|---|
| Service | `:107`, running 1 / desired 1, one deployment COMPLETED; the old task drained |
| ALB | `rap-dev-api-tg` target healthy; `GET /actuator/health` 200 |
| Startup log | profile `aws`; no ERROR; no Flyway or migration lines |
| Unauthenticated | `GET /api/dashboard` → 401 (auth still required) |
| Enforcement still OFF (owner's existing browser session, read-only, as on 2026-11-18) | Dashboard counts equal the shared baseline 40,926 / 267,386 / 33,960 / 119,788 / 10,775 / 3,511 / 491,678; Award search `212137` returns both; Global Search and an Award page load; Security Administration shows enforcement OFF, writes disabled |
| Database | read-only check: still 86 migrations, latest 88; `authz` write counters unchanged |

## 6. Rollback (any failed smoke check, or on the owner's word)

```
aws ecs update-service --cluster research-archive-platform-dev-api --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:106
aws ecs wait services-stable --cluster research-archive-platform-dev-api --services research-archive-platform-dev-api
```

Then repeat the §5 checks against `:106`. The image `c85be89a` is protected by P1.
No database change was made, so there is nothing to revert in the data. The UI
(Amplify, `f6addd40`) is backward compatible with `:106`: F5 only changes the
message shown for 401 and 403 responses.

## 7. Then A8 session 2 (separate approval, `A8_SESSION2_PLAN.md`)

- The private task definition `-a8:2` = **registered `:107`** plus the same six enforcement settings, re-diffed (expect six).
- The rest is unchanged from session 1: re-enable S1/S3/S4, then grants, the attachment group, baseline on shared, C1–C4, isolation proof both ways, matrix, cross-check, manual checks, cleanup.
- Target: **655 PASS, 0 FAIL, 0 LEAK, 11 NOT TESTED**.
- The operator task definitions (`dev-grant-admin:6`, `dev-link-identity:2`) stay on the `:106` image, because the provisioning code did not change.
