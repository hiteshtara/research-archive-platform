# DEV RDS deletion protection — Terraform plan for review

**Not applied.** The plan was run read-only (`-lock=false`, output to
`/dev/null`). RDS is unchanged: `DeletionProtection: false`.

**Headline: the plan is NOT clean. It shows 2 changes, not 1, and the
second one would revert the DEV API from task definition revision 94 to
revision 84.** The deletion-protection change itself is correct; it
cannot be applied until that is resolved. See §3.

## 1. Terraform ownership — confirmed against the real files

Per CLAUDE.md's rule about never trusting a README on this point,
verified against `.tf` files and `git ls-files`:

| Question | Answer |
|---|---|
| Is RDS Terraform-managed? | **Yes** — `terraform/modules/rds/{main,variables,outputs}.tf`, all tracked |
| Does dev wire the flag? | **Yes** — `terraform/environments/dev/main.tf`: `deletion_protection = var.database_deletion_protection` |
| Does the module support it? | **Yes** — `modules/rds/main.tf:85` → `var.deletion_protection` |
| Where does `false` come from? | `environments/dev/variables.tf:161`, `default = false` |
| Does the untracked tfvars override it? | **No** — `terraform.tfvars` (gitignored, present locally) does not set `database_deletion_protection`, so the default applies |
| Is there drift? | **No** — Terraform's desired state (`false`) matches the live instance (`false`) |

A correction worth recording: my first grep for `deletion_protection`
appeared to show the setting missing from dev. It was not missing — the
output was truncated by `head` and hid the dev line. Verified properly
by reading the whole module block.

## 2. The proposed change — one line, in tracked code

`terraform/environments/dev/variables.tf`:

```diff
 variable "database_deletion_protection" {
+  # Enabled for dev on 2026-11-13. This dev database is NOT disposable …
+  # [rationale comment, see the file]
   description = "Protect the RDS instance from accidental deletion. Should be true in test/prod."
   type        = bool
-  default     = false
+  default     = true
 }
```

**Set as a tracked default rather than in `terraform.tfvars`**, which is
gitignored. A change made only in the untracked tfvars would be
invisible to review and could silently revert — the exact class of
drift this project has been bitten by before.

Branch: `ops/dev-rds-deletion-protection`. One file, 17 insertions, 1
deletion, no other changes.

## 3. The plan output — and why it must not be applied yet

Run from the branch, with the untracked `backend.hcl` and
`terraform.tfvars` supplied, account **770203350335** verified first:

```
  # module.api_service.aws_ecs_service.api will be updated in-place
  ~ resource "aws_ecs_service" "api" {
      ~ task_definition = "…research-archive-platform-dev-api:94"
                       -> "…research-archive-platform-dev-api:84"
    }

  # module.rds.aws_db_instance.this will be updated in-place
  ~ resource "aws_db_instance" "this" {
      ~ deletion_protection = false -> true
    }

Plan: 0 to add, 2 to change, 0 to destroy.
```

**Zero destroys — good. But two changes, and the ECS one is harmful.**

### Why the ECS change is there

`terraform/modules/api_service/main.tf`:

```hcl
resource "aws_ecs_service" "api" {
  task_definition = aws_ecs_task_definition.api.arn
  ...
}
```

There is **no `lifecycle { ignore_changes = [task_definition] }`** in
that module — confirmed, the module contains no `lifecycle` block at
all. So Terraform owns the service's task definition and will always
reset it to whatever `aws_ecs_task_definition.api` resolves to in
state, which is **revision 84**.

Meanwhile the documented and only supported deployment path
(`ops/deploy-api.sh`, then `aws ecs update-service`) registers new
revisions and repoints the service **outside Terraform**. Every
deployment since revision 84 has therefore widened this gap.

### What applying it today would do

Revert the DEV API to **revision 84**, silently undoing everything
deployed since — including the merged DEF-5 Documents fix now serving as
revision 94 (3.25× faster filtered search, 14/14 correctness gates).
Nothing in the plan output announces that as a rollback; it reads as a
routine in-place update.

**This is a pre-existing landmine, not something this change
introduced.** Any `terraform apply` against dev today would do it. The
deletion-protection change merely surfaced it.

It also qualifies CLAUDE.md's statement that deployments "never touch
Terraform-managed state": the ECS **service's** `task_definition`
attribute *is* Terraform-managed, and out-of-band deployment guarantees
drift in it.

## 4. Recommendation

**NO-GO on applying this plan as it stands.** It does not meet the
"one intended modification" bar, and the second modification is a
regression.

Three options, in order of preference:

1. **Fix the module first, then apply both together (recommended).**
   Add to `aws_ecs_service.api`:
   ```hcl
   lifecycle {
     ignore_changes = [task_definition]
   }
   ```
   This is the standard pattern when deployments are external, and it
   matches how this project actually deploys. After it, the plan should
   show **1 change, 0 destroys** — exactly the deletion-protection
   update. That module change deserves its own review, because it
   permanently cedes task-definition management to the deploy script.

2. **Targeted apply** — `terraform apply -target=module.rds` to change
   only the database. Avoids the ECS revert, but `-target` is explicitly
   a break-glass tool and leaves the landmine armed for the next person.

3. **Enable the flag out-of-band** via `aws rds modify-db-instance`
   while leaving Terraform at `false`. **Not recommended** — it creates
   exactly the console-versus-code drift that argues against option 3 in
   the first place, and the next apply would turn it back off.

**Either way, the ECS drift should be reported and fixed on its own
merits**, independently of deletion protection. It is a standing risk
that the next Terraform apply on dev — for any reason — will revert the
API.

## 5. What was and was not touched

- `terraform plan` only: read-only, `-lock=false`, output discarded.
- **No apply, no RDS change, no ECS change.**
- **Production was not inspected or planned.** Only
  `terraform/environments/dev` was initialised and planned. A grep for
  `deletion_protection` across `terraform/` incidentally listed
  filenames and line numbers under `environments/prod/`; those files
  were **not opened or read**, and no Production plan was run.
- `backend.hcl` and `terraform.tfvars` were copied into a scratch
  worktree to run the plan and are untracked there as they are in the
  main tree.
