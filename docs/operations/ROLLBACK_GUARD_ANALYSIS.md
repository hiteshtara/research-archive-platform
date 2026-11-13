# API image rollback guard — analysis, correction, tests

Reviewed against merged `main` **`0068db9`**. DEV only; Production not
inspected. No Terraform applied; every plan read-only (`-lock=false`,
output discarded), account **770203350335** verified first.

## 1. How the guard works

| Piece | Location |
|---|---|
| Live-image lookup | `environments/dev/main.tf:17-27` (data sources) |
| Desired image | `local.api_image_desired`, `main.tf:30-40` — digest wins over tag |
| Wiring | `expected_live_image` passed to `module.api_service`, `main.tf:518` |
| **The check** | precondition on `aws_ecs_task_definition.api`, `modules/api_service/main.tf:359-363` |

```hcl
condition = var.expected_live_image == null || var.expected_live_image == var.api_image
```

It deliberately lives on the **task definition**, not in the root
module: applies here are run with `-target=module.api_service`, and
`-target` skips preconditions outside the target set. A root-module
precondition was verified on 2026-10-22 *not* to fire for a 209-commit
rollback; this one does. **The correction keeps it exactly where it
is.**

## 2. Finding: the reference image was the wrong revision

`data "aws_ecs_task_definition"` resolved by **family name** returns the
**newest ACTIVE revision**, not the one the service runs. Measured
2026-11-13:

| | Revision | Image |
|---|---|---|
| Family resolution (what the guard used) | **100** | `sha256:df3dcfe2d4ba…` ← fan-out experiment |
| **Service actually running** | **94** | `sha256:1d440f251c…` |

Two distinct defects follow.

**2a. Unused revisions poison the reference (task 3).** Revisions 95–100
were registered for the CPU and bounded fan-out experiments and
deliberately retained. They are ACTIVE in the family but were never
deployed, so the guard compared the pin against an image nobody ran.

**2b. The guard could disarm itself.** If Terraform ever registered a
revision carrying a stale image, that revision became the newest ACTIVE
one — and every later plan would compare the stale pin against the
equally stale reference and **pass**. The guard would silently stop
guarding. Reading the service is immune: the service changes only when a
deployment changes it.

### Correction (task 2): compare against the SERVING revision

```diff
-data "aws_ecs_task_definition" "api_live" {
-  task_definition = "${var.project_name}-${var.environment}-api"
-}
+data "aws_ecs_service" "api_live" {
+  cluster_arn  = "arn:aws:ecs:${var.aws_region}:${…account…}:cluster/${…}-api"
+  service_name = "${var.project_name}-${var.environment}-api"
+}

 data "aws_ecs_container_definition" "api_live" {
-  task_definition = data.aws_ecs_task_definition.api_live[0].id
+  task_definition = data.aws_ecs_service.api_live[0].task_definition
 }
```

**Verified live.** With the correction, the guard now reports the
serving image:

```
var.expected_live_image is "…@sha256:1d440f251c…"   ← revision 94, serving
```

(previously `…df3dcfe2…`, revision 100, never deployed).

Unchanged: an environment whose service does not exist yet must still
set `api_image_rollback_guard = false`, exactly as before.

## 3. Task 4 — does it block legitimate forward deployments?

**No, once the ownership model is stated correctly.** Images are
deployed by `ops/deploy-api.sh`, **not** by Terraform. So:

| Situation | Guard | Correct? |
|---|---|---|
| Pin matches the serving image | passes | ✅ steady state |
| Deployment happened, pin not yet updated | **fails** | ✅ it is telling you to re-pin |
| Pin moved ahead of the service | **fails** | ✅ Terraform must not drive deploys, and digests carry no ordering so "newer" is unknowable |
| Deliberate rollback | `guard = false` | ✅ documented escape hatch |

The guard's **logic** was never wrong. What was wrong was the
**reference** (§2) and the **message**.

### The message was factually incorrect, and that is why it was disabled

The old text said *"api_image would roll the API back"* on **any**
mismatch. The tfvars comment that disabled the guard says exactly this:
*"it fires on any image change, with a message that wrongly says 'would
roll back'."* A misleading message got a working safety control switched
off. Corrected to:

> *"api_image does not match what the ECS service is running… Images are
> deployed by ops/deploy-api.sh, not by Terraform, so this almost always
> means the pin in terraform.tfvars is stale: re-pin api_image_digest …
> then re-plan."*

## 4. Task 5 — is the guard still meaningful after PR #145?

**Yes, but its meaning changed and that must be understood.**

| | Before #145 | After #145 |
|---|---|---|
| Can Terraform repoint the service? | **yes** | **no** (`ignore_changes`) |
| A stale pin therefore causes… | an actual **rollback** of the running API | a revision **registered but never adopted** |
| Guard still needed? | yes | **yes — for different reasons** |

It remains worth having because it:

1. **Detects pin staleness at plan time**, which is the operator-facing
   signal that Terraform's declared intent has diverged from reality.
2. **Prevents the self-disarming failure in §2b**, which `ignore_changes`
   does nothing about.
3. **Protects against `ignore_changes` being removed later** — the two
   controls are independent and complementary.

They are not redundant: `ignore_changes` stops the **symptom** (the
service being repointed); the guard stops the **cause** (a stale pin
silently persisting).

## 5. Task 6 — automated tests

`terraform/modules/api_service/tests/rollback_guard.tftest.hcl`, run
with:

```
terraform -chdir=terraform/modules/api_service test
```

The precondition is a pure function of two inputs, so the suite needs no
AWS calls — the provider is mocked. Digests are the real DEV values, not
invented.

| Scenario | Expectation | Result |
|---|---|---|
| **Serving revision 94**, pin matches | plan succeeds | ✅ pass |
| **Unused ACTIVE revisions 95–100** as the reference | **fails** — proves a correct pin is wrongly rejected when the reference comes from an undeployed revision, which is the regression the fix prevents | ✅ pass |
| **Genuinely stale desired image** (rev 84's) | **fails** — the guard's core job | ✅ pass |
| **Legitimate forward deployment**, pin updated to the new serving image | plan succeeds | ✅ pass |
| Terraform tries to drive a deployment (pin ahead of service) | **fails** | ✅ pass |
| **Unrelated change** (the RDS deletion-protection case) with a correct pin | not blocked | ✅ pass |
| `expected_live_image = null` | check disabled | ✅ pass |

**`Success! 7 passed, 0 failed.`**

Two defects in the tests themselves were found and fixed while writing
them: a `locals` block (not permitted in `.tftest.hcl`) and a mistyped
account id that would have failed scenario 1 for the wrong reason.

## 6. Task 8 — the final plan, and an honest problem with the expected result

The requested outcome was **1 RDS modification, 0 ECS changes, 0
destroys, with the corrected guard enabled**. That combination is **not
achievable**, and here is exactly why.

| Option | Guard | Pin | Plan | Meets the expected result? |
|---|---|---|---|---|
| **A** | **off** (current) | stale, unchanged | `0 add, 1 change, 0 destroy` | ✅ **yes** |
| **B** | **on** | stale | **plan fails** (correctly) | ❌ |
| **C** | **on** | corrected to serving | `1 add, 1 change, 1 destroy` | ❌ |

Option C's add/destroy is **the task definition being replaced**:
changing the pinned digest changes the container definition, which
forces a new revision.

**That destroy is benign.** `skip_destroy = true` on
`aws_ecs_task_definition.api`, so the "destroy" is **state-only** — AWS
revision 84 stays ACTIVE and nothing is deregistered. Combined with
#145's `ignore_changes`, the **service stays on revision 94**: the newly
registered revision is not adopted. Net real-world effect of option C:

- RDS deletion protection enabled ✅
- one new task-definition revision registered and unused
- no revision deregistered, no service change, no downtime

So option C is safe — but it is not "zero destroys", and it adds one
more unused revision to the 95–100 pile.

### Recommendation

**Apply option A now.** It is exactly the plan specified, it touches the
image not at all, and the guard's state is unchanged from today.

**Then treat option C as a separate, deliberate re-sync.** Applying it
once would leave Terraform's task definition matching what is serving,
the pin correct, and the guard **permanently enabled** — after which
plans stay clean with the guard on. That is the better end state, but it
is a decision with a visible (if benign) replacement, not something to
slip into a deletion-protection apply.

**Not done, pending approval:** no revisions deregistered (task 7), the
tfvars pin not edited, the guard not re-enabled.

## 7. Scope and controls

DEV only. Revision 94 serving, revision 93 available, revisions 95–100
retained and inactive. Enforcement OFF, auditing OFF, V089 unapplied,
grant 2 unchanged. No application code deployed, no RDS resize, no
Production access.
