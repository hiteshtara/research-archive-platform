# Option C — re-sync the image pin and re-enable the rollback guard

**Not executed. Not approved.** This is a proposal with its exact plan
attached. No revisions deregistered (nothing in this proposal
deregisters anything).

## 1. What Option C is for

After the 2026-11-13 deletion-protection apply, two things are still
true and both are undesirable:

| | State | Consequence |
|---|---|---|
| `api_image_digest` | `sha256:c9f3bc99…` — **revision 84's image, from 2026-10-06** | Terraform's declared intent disagrees with what runs |
| `api_image_rollback_guard` | **`false`** since ~2026-10-28 | the safety control that exists for exactly this is switched off |

Option C fixes both in one apply: re-pin to the serving image, and turn
the (now corrected) guard back on. After it, plans are clean **with the
guard enabled**, which is the stable end state.

## 2. The exact plan

Read-only, from the PR #146 branch (corrected guard), account
**770203350335** verified, `terraform/environments/dev`:

```
terraform plan \
  -var 'api_image_rollback_guard=true' \
  -var 'api_image_digest=sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981'
```

```
module.api_service.aws_ecs_task_definition.api
    actions          : ["delete", "create"]
    revision before  : 84
    skip_destroy     : true
    replacement cause: container_definitions changed (image digest)

TOTALS: 0 add, 0 change, 1 destroy
ECS SERVICE changes: 0
```

**No RDS change appears** — deletion protection is already applied, so
Option C's remaining scope is purely the task definition.

> Terraform counts a `["delete","create"]` replacement as one destroy
> plus one create; the create is reported inside the replacement rather
> than as a separate add, which is why the totals read `0 add … 1
> destroy`.

## 3. Confirming the ECS service remains on revision 94

Three independent reasons, two of them visible in the plan itself:

1. **`ECS SERVICE changes: 0`** — `aws_ecs_service.api` does not appear
   in the plan at all.
2. **PR #145's `lifecycle { ignore_changes = [task_definition] }`** means
   Terraform will not repoint the service even when a newer revision
   exists. That is the whole point of #145.
3. **`skip_destroy = true`** on the task definition means the "destroy"
   is **state-only**: AWS revision 84 is **not deregistered** and stays
   ACTIVE, as do 93–100.

So the real-world effect of applying Option C is:

| | Before | After |
|---|---|---|
| Service runs | revision **94** | revision **94** — unchanged |
| Running task | started 2026-11-12 19:58 | **same task, not restarted** |
| New revision registered | — | one new revision (**101**), **unused** |
| Revisions deregistered | — | **none** |
| Downtime | — | **none** |
| RDS | `deletion_protection: true` | unchanged |

**Nothing is deployed and nothing restarts.** Terraform registers a task
definition that the service does not adopt, which is exactly the
behaviour #145's warning describes.

## 4. The cost, stated plainly

**It adds one more unused ACTIVE revision** (101) to the 95–100 pile.
That is a real, if minor, downside: the family accumulates revisions
nobody runs.

It is now **harmless to the guard**, because the corrected guard reads
the **service**, not the family — the very defect that made unused
revisions dangerous is fixed in PR #146. Before that fix, registering
revision 101 with any image would have made it the guard's reference and
could have disarmed it.

## 5. Verification to run after applying

| # | Check | Expected |
|---|---|---|
| 1 | `describe-services … taskDefinition` | **`…-dev-api:94`** — unchanged |
| 2 | Running task `startedAt` | unchanged from before the apply (no restart) |
| 3 | Running task `imageDigest` | `sha256:1d440f251c…` |
| 4 | ALB target health | `healthy` |
| 5 | Revision 84 status | **`ACTIVE`** (proves `skip_destroy` worked) |
| 6 | Revisions 93–100 status | all **`ACTIVE`** — nothing deregistered |
| 7 | Re-plan with guard on | **`No changes`** — the end state this exists for |
| 8 | Functional set | 401 without token; award `212137-00001`; dashboard `40926`/`3511`; Documents `3382` and `491685`; Global Search `83` |
| 9 | RDS | `deletion_protection: true`, `PendingModifiedValues` empty |

## 6. Rollback

Simple, because nothing is deployed:

- **To undo the registered revision:** nothing needs undoing — revision
  101 is unused and costs nothing. It can be left, or deregistered
  later **with approval**.
- **To undo the pin change:** set `api_image_digest` back to
  `sha256:c9f3bc99…` and `api_image_rollback_guard = false`. The next
  apply would register yet another revision, so the cleaner reversal is
  simply to leave it.
- **The service never moved**, so there is no service rollback to
  perform. Revision 93 remains available as the standing rollback target
  for unrelated reasons.

## 7. Prerequisites

1. **PR #146 must be merged first.** Option C depends on the corrected
   guard reading the serving revision; running it against the old
   family-resolution guard would compare the pin against revision 100
   (an experiment image) and fail.
2. The pin value must be re-read at execution time, not copied from this
   document — if a deployment happens in between, the serving image
   changes and this digest goes stale.
3. The change belongs in the **gitignored** `terraform.tfvars`, so it is
   operational rather than code-reviewed. Worth considering whether
   `api_image_rollback_guard` should instead be removed from tfvars
   entirely so the declared default of `true` applies — that would make
   the safety control's state visible in version control, which is how
   it came to be silently disabled for two weeks.

## 8. Recommendation

**Approve Option C after PR #146 merges**, as a deliberate, separately
reviewed re-sync. It is low risk — no deployment, no restart, no
deregistration, service provably unchanged — and it closes the open
exception recorded in the architecture changelog.

**Do not bundle it with anything else.** The entire reason this is a
separate proposal is that a task-definition replacement should be seen
and approved on its own, not discovered inside an unrelated apply.
