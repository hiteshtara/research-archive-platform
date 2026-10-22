# Terraform drift reconciliation proposal — `environments/dev`

**Status: RESOLVED in configuration, 2026-10-22.** The restoration shipped in
**#53** (merge commit `27bc22e`). This document is kept as the investigation
record and the rationale behind what #53 did — read the *Historical* framing
below, not as a live warning.

Everything in the investigation was read-only. No resource was destroyed,
replaced, disabled, or removed from state, and **no `terraform apply` was run**
at any point.

**Investigated 2026-10-22** against account `770203350335`, `us-east-1`,
originally from `origin/main` at `f33f4ab`, with the real `terraform.tfvars`.

### Then and now

| | Unrestricted `terraform plan` in `environments/dev` |
|---|---|
| Before #53 (`f33f4ab`) | `Plan: 2 to add, 1 to change, 7 to destroy.` |
| **After #53 (`27bc22e`)** | **`Plan: 1 to add, 0 to change, 0 to destroy.`** |

All seven resources below are now `no-op`. There is no `delete` action
anywhere in the plan, no replacement, and the nightly Subaward schedule is a
`no-op` still pinned to the known-good revision **237**. The single remaining
add is `terraform_data.explorer_flags_match`, a local feature-flag precondition
that creates no AWS resource.

**`-target` is still the right habit**, but it is no longer load-bearing for
these seven: an unrestricted plan no longer proposes destroying them.

---

## The seven destroys are not seven orphans

Six are genuine orphans — present in state, absent from configuration. The
seventh is a **replacement**, which is a different problem with a different
fix, and it is the most dangerous item here.

| # | Address | Action | Root cause |
|---|---|---|---|
| 1 | `aws_security_group.cloudshell_analyst[0]` | delete | never committed |
| 2 | `aws_vpc_security_group_egress_rule.cloudshell_analyst_to_database[0]` | delete | never committed |
| 3 | `aws_vpc_security_group_ingress_rule.database_from_cloudshell_analyst[0]` | delete | never committed |
| 4 | `module.subaward_sync_schedule.aws_cloudwatch_event_rule.loader_task_failed` | delete | never committed |
| 5 | `module.subaward_sync_schedule.aws_cloudwatch_event_target.loader_task_failed_sns` | delete | never committed |
| 6 | `module.subaward_sync_schedule.aws_sns_topic_policy.subaward_sync_alerts_events` | delete | never committed |
| 7 | `module.loader_ecr.aws_ecr_lifecycle_policy.loader` | **delete + create** | **config is wrong, not missing** |

Plus two non-destructive changes, covered below:
`terraform_data.explorer_flags_match` (create) and
`module.subaward_sync_schedule.aws_scheduler_schedule.subaward_nightly`
(update in place).

---

## Single root cause for orphans 1–6

All six exist in exactly one commit in the entire repository:

```
27f4dd5  WIP(preserved): uncommitted working tree of
         feat/negotiation-multi-filter-search as of 20260929T093115
```

on branch `preserve/wip-negotiation-multi-filter-search-20260929T093115`.
They appear in **no other ref**, and in particular never on `main`:

```bash
git log --oneline --all -S'cloudshell_analyst' -- terraform/environments/dev/main.tf
# -> 27f4dd5 only

git grep -l 'cloudshell_analyst' origin/main -- terraform/
# -> no match
```

So the sequence was: the declarations were written in a working tree, applied
to AWS (creating the live resources and their state entries), and then the
working tree was preserved onto a branch during the 2026-09-29 save-then-switch
rather than merged. `main` never received them, so from `main`'s point of view
the resources are unmanaged, and Terraform offers to delete them.

This is **not** drift caused by someone editing AWS by hand. The resources are
legitimate and documented; only their declaration is missing. That matters,
because it points at *restore the declaration* rather than *forget the
resource*.

---

## Item-by-item findings and recommendation

### 1–3. CloudShell analyst security group and its two rules

- **What they are:** a security group intended to be attached to a CloudShell
  VPC environment ENI, plus an egress rule to RDS on 5432 and the matching
  ingress rule on the database security group. This is the only approved
  read-only PostgreSQL analysis path — `docs/runbooks/CLOUDSHELL_ANALYSIS.md`.
- **Still referenced:** `terraform.tfvars` sets
  `manage_cloudshell_analyst_access = true`, and `terraform init` warns the
  variable is **undeclared** — the tfvars line and the live resources are the
  only surviving evidence of the feature.
- **Declaration recoverable from:** `27f4dd5`, both
  `terraform/environments/dev/main.tf` (the three resources, `count` gated on
  the variable) and `variables.tf` (the variable, `default = false`).
- **Cost of the resources:** none — a security group and two rules, no EC2,
  no endpoints, no NAT.
- **Recommendation (implemented by #53): RESTORE the declaration.** Re-apply the
  three resources and the variable from `27f4dd5`, then confirm a targeted
  plan reports **no changes** (the resources already exist at index `[0]`,
  which is what `count = 1` produces, so a correct restoration is a no-op).
  Removing them from state instead would leave an undeclared variable, an
  undocumented live security group, and a runbook pointing at infrastructure
  Terraform does not know about.

### 4–6. Subaward nightly-sync failure alerting

- **What they are:** an EventBridge **rule** matching failed loader task-state
  changes, an EventBridge **target** forwarding those events to the alert SNS
  topic, and the **SNS topic policy** permitting EventBridge to publish to it.
  Together they are the push-notification path that tells a human the nightly
  Subaward sync failed.
- **Not duplicated by what is in config.** The module *does* declare
  `aws_sns_topic.subaward_sync_alerts`,
  `aws_cloudwatch_log_metric_filter.family_failures` and
  `aws_cloudwatch_metric_alarm.family_failures`. That is a *log-metric* alarm
  path. The three orphans are a *separate, event-driven* path keyed on ECS
  task-state change. Deleting them removes a real alerting channel, and
  removes the SNS topic policy that authorises EventBridge to publish —
  so the alarm path's topic could also stop accepting event-sourced messages.
- **Why this is worse than it looks:** the nightly sync is recorded as healthy
  on the strength of observed clean runs. If its alerting is deleted, a later
  failure becomes silent, and the next person to look will see "healthy"
  because nothing told them otherwise.
- **Recommendation (implemented by #53): RESTORE the declaration** from `27f4dd5`
  (`terraform/modules/subaward_sync_schedule/`), then confirm a targeted plan
  reports no changes. Do not delete alerting to tidy a plan.

### 7. ECR lifecycle policy — the dangerous one

This is a **replace**, not an orphan: the resource is in configuration, but
the configuration disagrees with what is live, and `policy` forces
replacement (`replace_paths: [["policy"]]`).

**Live policy (would be destroyed):**

| Priority | Rule | Selection |
|---|---|---|
| 1 | "Never sweep protected-* images (referenced by a pinned task definition)" | tagged, `tagPrefixList: ["protected-"]`, expire after 36500 days |
| 2 | "Keep the latest 20 loader images" | any tag, `imageCountMoreThan: 20` |

**Configured policy (would replace it):** rule 2 only. The protection rule is
**gone**.

Why that breaks something concrete:

- The loader repository currently holds **24 images**, so a bare
  "keep the latest 20" rule expires the oldest **4**.
- The image tagged `protected-20260817T011034Z-4a82821` is the **3rd oldest**
  of the 24 — squarely inside that set.
- The nightly sync's EventBridge Scheduler target currently pins task
  definition `research-archive-platform-dev-loader:237`, and **`loader:237`
  runs exactly that image** (`…dev-loader:20260817T011034Z-4a82821`).

So applying the configured policy makes the image behind the running nightly
sync eligible for expiry. Combined with items 4–6, an unrestricted apply would
delete the image protection, delete the alerting that would report the
resulting failure, and (see below) retarget the schedule — all in one run.

> **Resolved by #53.** The priority-1 `protected-*` rule is back in
> configuration with the keep-20 sweep renumbered to priority 2, matching live
> exactly, so this resource now plans as `no-op`. The expiry risk described
> above is **historical** — it describes what an unrestricted apply *would*
> have done before #53, and what in fact already happened once in August.

- **Recommendation (implemented by #53): RESTORE the protection rule in configuration.** The policy
  is declared at `terraform/modules/ecr/main.tf:19`
  (`aws_ecr_lifecycle_policy.loader`), reached through
  `module "loader_ecr" { source = "../../modules/ecr" }` in
  `environments/dev/main.tf:198`. Add the `protected-` rule back at priority 1
  and renumber the existing "keep the latest 20" rule to priority 2, so the
  configured policy matches the live one and the plan goes quiet. The API
  repository has its own separate policy in `modules/api_ecr`, which shows no
  drift.

  **`modules/ecr` is shared, so this edit is NOT contained to dev.** It is
  instantiated by `environments/dev/main.tf:198`,
  `environments/test/main.tf:114` **and** `environments/prod/main.tf:114`.
  Editing the module therefore changes the desired policy for test and
  production too. That is almost certainly the *right* outcome — a rule that
  protects pinned images is not dev-specific — but it means:
  - the change must be reviewed as touching all three environments, and
  - it must not be applied to test or prod as a side effect. Applying it to
    dev does not touch them (each environment has its own state), but whoever
    next applies test or prod will pick it up, and should expect a policy
    replacement there. Check first whether test/prod live policies already
    carry a `protected-` rule; if they differ, the module may need the rule to
    be variable-driven rather than hardcoded. Do **not** "fix" this by applying the configured
  policy, and do **not** remove the policy from state — the policy itself is
  legitimately Terraform-managed; only its content is wrong.
- **Interim safeguard:** nothing is expiring right now, because the live
  policy still protects the image. The urgency is "do not apply untargeted",
  not "act within the hour".

---

## The two non-destructive changes

### `terraform_data.explorer_flags_match` — create

A guard resource that asserts the UI and API Explorer feature flags agree. It
is declared in configuration and simply has never been applied, because every
apply in this directory has been `-target`-scoped and it was never in a target
set. Harmless: it creates no AWS resource and only evaluates a precondition.

- **Recommendation:** let it be created the next time a plan legitimately
  includes it. No action needed, no risk either way.

### `aws_scheduler_schedule.subaward_nightly` — update in place

The only differing field is the pinned task definition:

```
target.ecs_parameters[0].task_definition_arn
  before (live)   : …/research-archive-platform-dev-loader:237   image 20260817T011034Z-4a82821
  after  (config) : …/research-archive-platform-dev-loader:252   image 20260923T163946Z-443e507
```

Live is pinned to revision **237**; configuration wants **252**. Revision 237
is the revision the sync's observed clean runs were made on — it is the
known-good. Revision 252 is a month newer and, as far as this investigation
can tell, has never run the nightly sync.

**Correction to this document's first draft.** It suggested "update
configuration to pin 237" as if that were a config tweak. It is not. The module
derives the ARN from `data "aws_ecs_task_definition" "loader_latest"`
*deliberately* — the comment there explains that re-applying should re-point
the schedule at whatever was most recently deployed "rather than silently
keeping a stale revision pinned forever." Hard-pinning would reverse a design
decision.

`27f4dd5` had already solved this properly, and **#53 restored that solution**:
an optional `loader_task_definition_revision` variable (default `null`, which
keeps the family-latest behaviour) plus a `protected_image_tag`, with the dev
root passing revision **237** and its protected tag. Family-latest remains the
module's default for any other caller.

- **Resolved for now:** the schedule plans as `no-op` at revision 237, so an
  unrestricted plan no longer proposes moving it.
- **Still an open decision (current, not historical):** whether 237 should
  remain the pin indefinitely, or the sync should move to a newer, reviewed
  revision whose image is also `protected-` tagged. That is a deliberate
  change deserving its own review and a verified overnight run. Deferred.
- Note the interaction: staying on 237 keeps the dependency on the
  `protected-` image, which is exactly why item 7's rule matters.

---

## What #53 actually did, and one correction it had to make

The order of work this document proposed was carried out in a single PR (#53)
rather than four, because each piece was a no-op once correct and the plan
could be verified as a whole.

Restored, using `27f4dd5` as reference and reconciled against live AWS:

- `modules/ecr` — the priority-1 `protected-*` rule, keep-20 renumbered to 2.
- `modules/subaward_sync_schedule` — the EventBridge failure rule, SNS target
  and SNS topic policy, plus the `aws_ecr_image` detective control and the
  pinned-revision local.
- `environments/dev` — the CloudShell security group and its two 5432 rules,
  the `manage_cloudshell_analyst_access` variable (which also silences an
  undeclared-variable warning at `init`), and the two module arguments pinning
  revision 237 with its protected image tag.

**One deliberate deviation from `27f4dd5`, which this document had not
anticipated.** `27f4dd5` set `egress = []` on the CloudShell security group to
force Terraform to revoke the implicit AWS-default allow-all-outbound rule a
new security group receives. That conflicts with the separately-managed
`aws_vpc_security_group_egress_rule`: AWS reports that rule back through the
security group's own `egress` attribute, so `egress = []` made the plan propose
**revoking the single 5432-to-RDS rule the group exists to provide**. Observed
directly — restoring `27f4dd5` verbatim produced `1 to change` on exactly that
revocation, and applying it would have broken the CloudShell path and re-added
it on the next apply, indefinitely.

So the inline `egress` is omitted and the separate rule resource owns it. The
implicit allow-all rule is already revoked on the live group
(`sg-002be83bf1cf249fa`), so this changes nothing today. **If that group is
ever destroyed and recreated, re-check that the default allow-all-outbound rule
is gone** — Terraform will no longer revoke it automatically.

Values verified independently before the preserved ones were trusted:

- `4a82821` is an ancestor of `origin/main`.
- `20260817T011034Z-4a82821` and `protected-20260817T011034Z-4a82821` resolve
  to the same manifest `sha256:5339636d…`, which is what `loader:237` runs.
- Revision 245 is the non-commit-traceable `evidence-idx-20260817T144632Z`
  build the preserved comments warn against.
- `modules/subaward_sync_schedule` is instantiated only by dev, so the newly
  required `protected_image_tag` cannot break test or prod.
- The `aws_ecr_image` guard resolves to that digest, confirming it reads the
  real image and would fail `terraform plan` loudly if it disappeared.

---

## Current risks (as of 2026-10-22, after #53)

Distinct from the historical risks above, which #53 closed.

1. **`modules/ecr` is shared with test and prod.** It is instantiated by
   `environments/{dev,test,prod}/main.tf`. Each environment has its own state,
   so applying dev does not touch them — but **whoever next applies test or
   prod will see a lifecycle-policy replacement there**. Check first whether
   those environments' live policies already carry a `protected-` rule; if they
   differ, the rule may need to be variable-driven rather than hardcoded.
   Neither account was readable during this investigation.
2. **The scheduler pin (237) is a deferred decision**, not a settled one. See
   the scheduler section above.
3. **`terraform_data.explorer_flags_match` is still unapplied.** Harmless — it
   creates no AWS resource — but it means the Explorer flag agreement between
   UI and API has never actually been asserted by an apply.
4. **Nothing here has been applied.** Configuration and live state now agree,
   which is why the plan is quiet; that agreement has not been *exercised* by
   an apply. The first apply that includes these resources should still be
   `-target`-scoped and read carefully.

## Why "restore", not "remove from state", everywhere here

`terraform state rm` is the right tool when a resource should stop being
Terraform-managed. None of these qualify:

- Items 1–6 are documented, intentional infrastructure whose declarations were
  lost to a process accident, not resources someone decided Terraform should
  not own. Removing them from state would preserve the live resources but
  leave them permanently undeclared — the same latent trap, just quieter, and
  it would strand `manage_cloudshell_analyst_access` and a runbook.
- Item 7 is already declared; its content is simply wrong. State removal would
  make the policy unmanaged and leave a drifted policy nobody owns.

`state rm` would, however, be the correct choice if a deliberate decision were
made to retire the CloudShell analyst path or the event-driven alerting. That
is a product decision, not a cleanup, and it should be taken explicitly rather
than by letting an untargeted apply make it.

## Verification performed

Read-only throughout, both for the investigation and for confirming #53:
`terraform fmt -check`, `terraform validate`, `terraform plan` (with
`-lock=false`, full and `-target`-scoped), `terraform show -json`,
`git log`/`git grep`/`git show`, and AWS describe/list calls
(`ecs describe-task-definition`, `ecs describe-services`,
`ecr describe-images`, `scheduler get-schedule`,
`ec2 describe-security-groups`, `amplify get-domain-association`,
`route53 list-resource-record-sets`). **No apply, no state mutation, no
resource change at any point.**

Post-merge confirmation on `origin/main` at `27bc22e`: `terraform validate`
Success; full unrestricted plan `1 to add, 0 to change, 0 to destroy`; all seven
resources `no-op`; zero delete actions; zero replacements; schedule `no-op` at
revision 237. Live state unchanged throughout — schedule still 237, protected
image still present (`sha256:5339636d…`), API service still ECS revision 81.
