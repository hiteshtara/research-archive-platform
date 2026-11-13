# ECS task definition — who owns it, and why Terraform ignores it

Applies to `module.api_service.aws_ecs_service.api` in DEV. Production
was **not** inspected and nothing here is asserted about it.

## 1. The problem this fixes

`aws_ecs_service.api` set `task_definition = aws_ecs_task_definition.api.arn`
with no `lifecycle` block, so Terraform owned the attribute and would
reset it to whatever the task definition resource resolves to in state.

Meanwhile the **only supported deployment path** —
`clean committed source → local Docker build → BU non-production ECR →
ops/deploy-api.sh` — registers a new revision and repoints the service
with `aws ecs update-service`, entirely outside Terraform. Every
deployment since revision 84 widened the gap.

On 2026-11-13 a dev plan made the consequence concrete:

```
~ module.api_service.aws_ecs_service.api
  ~ task_definition = "…dev-api:94" -> "…dev-api:84"
Plan: 0 to add, 2 to change, 0 to destroy.
```

Applying that would have reverted the DEV API to revision 84, silently
undoing every deployment since — including the merged DEF-5 Documents
fix then serving as revision 94. **Zero destroys, no warning; it reads
as a routine in-place update.** It would have been triggered by *any*
apply against dev, for any reason.

## 2. Requirement 1 — is ignoring `task_definition` appropriate here?

**Yes.** The test is whether the attribute is genuinely owned elsewhere,
and it is:

| Evidence | Finding |
|---|---|
| Documented deployment path | `ops/deploy-api.sh`, run against a clean detached worktree; CLAUDE.md states this is "the real, established, and only API deployment path" |
| Who repoints the service | `aws ecs update-service`, outside Terraform |
| Does Terraform ever deploy? | No. "GitHub Actions never deploys"; no CodeBuild pipeline exists for this repo |
| Is the drift one-off or structural? | **Structural** — it recurs on every deployment by design |

`ignore_changes` is the standard pattern for exactly this: an attribute
Terraform creates but another system owns thereafter. The alternative —
making Terraform the deployer — would be a far larger change and
contradicts the documented architecture.

**Also verified: the task definition resource itself is not drifting.**
Terraform's config pins the image and state's revision 84 carries that
same image. The drift was purely in the service's *pointer*, which is
precisely what this ignores — and nothing more.

## 3. Requirement 2 — what Terraform still manages

`ignore_changes` is scoped to the single `task_definition` attribute.
Everything else on the service remains Terraform-managed and will still
show drift:

| Attribute | Still managed? |
|---|:--:|
| `desired_count` | ✅ |
| `launch_type` (FARGATE) | ✅ |
| `platform_version` | ✅ |
| `network_configuration` — subnets, security groups, public IP | ✅ |
| `load_balancer` — target group, container name, container port | ✅ |
| `health_check_grace_period_seconds` | ✅ |
| `cluster`, `name` | ✅ |
| IAM roles and policy attachments | ✅ |
| **`task_definition`** | ❌ **owned by the deploy script** |

Confirmed empirically: after the change, a dev plan reports **"No
changes. Your infrastructure matches the configuration."** — so nothing
else was silently suppressed. Had the block been too broad, other
attributes would have stopped being compared and drift elsewhere would
have been masked.

## 4. Requirement 3 — does this conceal task-definition drift?

**It does not hide drift from Terraform, but it does change whether a
Terraform-made change ever takes effect. That distinction matters and is
the main thing to understand before merging.**

The `aws_ecs_task_definition.api` **resource remains fully
Terraform-managed**. Terraform still owns and still diffs:

- `cpu` (512) and `memory` (1024)
- `execution_role_arn`, `task_role_arn`
- `runtime_platform`
- the container definition: **image**, `environment`, `secrets`,
  `portMappings`, `logConfiguration`

A change to any of those **will still appear in a plan**. Nothing about
task-definition drift becomes invisible.

**What changes is adoption.** Because the service's pointer is ignored,
a revision Terraform creates is *registered but not adopted*. So:

> A Terraform-side change to CPU, memory, image, environment or secrets
> will apply cleanly, report success, and have **no runtime effect**
> until a deployment repoints the service.

That is a real foot-gun and it is the price of this fix. It is also not
new in substance — Terraform's task definition already diverged from
what runs — but it is now *permanent by design* rather than accidental,
so it must be documented rather than discovered.

### Mitigation, i.e. the operating rule

| Change you want | Correct route |
|---|---|
| New application image | `ops/deploy-api.sh` — the normal path |
| Change CPU/memory, env or secrets | Edit the Terraform task definition, apply, **then run a deployment** so the service adopts the new revision |
| Emergency pin to a specific revision | `aws ecs update-service --task-definition …:N`; Terraform will not fight it |

A Terraform apply that changes the task definition and is **not**
followed by a deployment leaves the intended change dormant. That is the
single thing to remember.

## 5. Requirement 6 — operational responsibility

| Concern | Owner |
|---|---|
| Building and pushing the API image | `ops/deploy-api.sh`, from a clean detached worktree at the exact released commit |
| Registering new task definition revisions **for a release** | the deployment process |
| Which revision the service runs | **the deployment process, not Terraform** |
| Task definition *shape* — CPU, memory, roles, env, secrets, logging | **Terraform**, with a deployment required afterwards for it to take effect |
| Service shape — count, networking, load balancer, health grace | **Terraform** |
| Rollback to a previous revision | `aws ecs update-service --task-definition …:N`, then record it |

The current rollback target, revision 93, and the serving revision, 94,
are both managed this way and are unaffected by Terraform.

## 6. Plan evidence

Before (without this change):

```
~ aws_ecs_service.api   task_definition = ":94" -> ":84"
~ aws_db_instance.this  deletion_protection = false -> true
Plan: 0 to add, 2 to change, 0 to destroy.
```

After (this change alone, dev):

```
No changes. Your infrastructure matches the configuration.
```

After (this change **plus** the deletion-protection change from PR #144):

```
~ module.rds.aws_db_instance.this
  ~ deletion_protection = false -> true
Plan: 0 to add, 1 to change, 0 to destroy.
```

Exactly one RDS modification, zero ECS service changes, zero destroys.

All plans were read-only (`-lock=false`, output discarded), account
**770203350335** verified first, `environments/dev` only. **Production
was not initialised, planned or read.**

## 7. Two unrelated hygiene findings (not fixed here)

Noticed while tracing how the image is composed. Both live in the
**gitignored** `terraform/environments/dev/terraform.tfvars`, so neither
is fixed in this PR:

- **`api_image_digest` is set but consumed by no `.tf` file.** The
  service image is composed from `api_image_tag` instead
  (`main.tf:414`). The digest pin in tfvars is therefore decorative —
  worth knowing, since a comment there describes it as the immutable
  pin.
- **`api_image_rollback_guard` is set but declared nowhere.** Terraform
  warns about undeclared variables rather than failing, so it has been
  silently inert.

Neither affects this change. Both are worth a separate look, since the
first in particular could give false confidence that deployments are
digest-pinned through Terraform.
