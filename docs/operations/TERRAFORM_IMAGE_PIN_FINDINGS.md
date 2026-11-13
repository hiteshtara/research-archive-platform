# Terraform API image pinning — correction and findings

**Nothing changed.** No variable removed, renamed, repurposed or
re-valued. This documents a correction to two claims I made, and the
real finding that replaced them.

## 1. Correction: my two "hygiene findings" were both WRONG

In PR #145 I reported, as incidental findings:

| Claim | Status |
|---|---|
| "`api_image_digest` is set but consumed by no `.tf` file" | ❌ **WRONG** |
| "`api_image_rollback_guard` is declared nowhere and is silently inert" | ❌ **WRONG** |

Both are false. On `main`, both variables are declared, consumed and
working:

- **`api_image_digest`** — declared at
  `environments/dev/variables.tf:473` *with a format validation block*,
  and consumed at `environments/dev/main.tf:36-40` via
  `local.api_image_desired`, which prefers the digest when set and falls
  back to the tag. The comment there says exactly why: *"a digest names
  content, a tag names a pointer, and tags in this account are
  MUTABLE."*
- **`api_image_rollback_guard`** — declared at
  `environments/dev/variables.tf:484` with **`default = true`**, and
  implemented through two count-gated data sources
  (`main.tf:17,23`), `local.expected_live_image` (`main.tf:518`), and a
  resource precondition in `modules/api_service/main.tf:360`.

### Why I got it wrong

I ran `grep -rn … terraform/` from
`/Users/mukadder/projects/research-archive-platform`, which is checked
out on branch **`qa/version-table-and-hierarchy-link-semantics` at
`53a5855`** — not `main`. That branch predates this work. The code I
was looking for exists on `main` and did not exist in the tree I
searched.

The repository's own guidance warns about precisely this: verify
against the actual file and the actual ref, never a convenient working
tree. I checked the file contents but not **which commit the tree was
on**, which made the check worthless. Re-verified here from a clean
worktree at merged `main` (`0068db9`).

## 2. The real finding: the guard exists, and it is switched off

`terraform/environments/dev/terraform.tfvars` (gitignored) contains:

```
# Disabled only for this reviewed FORWARD deployment. The guard compares
# image references as strings, so a digest never equals a tag and it fires
# on any image change, with a message that wrongly says "would roll back".
api_image_rollback_guard = false
```

So the guard's default is **`true`**, dev overrides it to **`false`**,
and that override — described in its own comment as being for one
specific deployment — has persisted since around **2026-10-28**.

### The guard was built for exactly the hazard PR #145 addresses

From `environments/dev/main.tf:4-15`:

> *"ops/deploy-api.sh builds and deploys API images directly to ECR/ECS,
> so var.api_image_tag in terraform.tfvars drifts behind reality. On
> 2026-10-22 the pin was 209 commits behind the live image, and applying
> an unrelated CORS change would have reverted the API to the 2026-08-10
> build."*

That is the same failure mode as the `94 → 84` revert found on
2026-11-13 — independently rediscovered because the guard protecting
against it was off.

**With the guard enabled, that plan would have failed loudly instead of
quietly proposing a rollback.**

## 3. Measured: re-enabling the guard today FAILS the plan

Read-only test from merged `main`, `-var api_image_rollback_guard=true`:

```
Error: Resource precondition failed
  on ../../modules/api_service/main.tf line 360

api_image would roll the API back. Requested
'…@sha256:c9f3bc99…' but the newest ACTIVE revision of this task family
runs '…@sha256:df3dcfe2…'.
```

The pin is stale. `api_image_digest` points at `sha256:c9f3bc99…`
(revision 84's image, from 2026-10-06).

**Consequence for the pending deletion-protection apply:** with the
guard off, the plan is clean (`0 add, 1 change, 0 destroy`). **If the
guard is re-enabled first, that apply will be blocked** until the pin is
corrected. The two decisions are therefore coupled and should be taken
together.

## 4. A complication created by the capacity experiments

The guard compares against **the newest ACTIVE revision of the task
family**, not the revision actually serving.

| | Revision | Image |
|---|---|---|
| Currently **serving** | **94** | `sha256:1d440f251c…` |
| Newest **ACTIVE** in the family | **100** | `sha256:df3dcfe2d4ba…` |

Revisions 95–100 were registered for the CPU and bounded fan-out
experiments and are deliberately retained but inactive. They now sit
above 94 in the family, so the guard's reference image is an
**experimental** revision rather than the serving one.

So "re-pin to what is deployed" is not a single obvious value:

- Pinning to `sha256:1d440f251c…` (what is **serving**) would **not**
  satisfy the guard, because revision 100 is newer.
- Pinning to `sha256:df3dcfe2d4ba…` (newest ACTIVE) would satisfy the
  guard but names the **fan-out experiment image**, which is not what
  runs and not what anyone intends Terraform to deploy.

**No action taken.** Resolving this is a decision, not a cleanup:
deregister the experiment revisions so the family's newest ACTIVE is 94
again, or keep them and accept that the guard needs a different
reference. The instruction has been to preserve 97–100, so nothing was
deregistered.

## 5. Does PR #145 make the guard redundant?

**No — they are complementary, and both are worth having.**

| Protection | What it stops |
|---|---|
| `ignore_changes = [task_definition]` (PR #145) | Terraform **repointing the service** to an older revision |
| `api_image_rollback_guard` | Terraform **registering a task definition** whose image is older than what is deployed |

PR #145 stops the symptom I found. The guard stops the cause — a stale
pin — and does so at plan time with an explanatory error rather than a
silent in-place update. With `ignore_changes` in place the service
pointer is safe either way, but a stale pin would still have Terraform
create revisions nobody wants.

## 6. Recommendation (no change made)

1. **Do not remove, rename or repurpose either variable.** Both are
   real, both work, and the digest-over-tag preference is a deliberate
   immutability safeguard.
2. **Decide the revision-family question first** (§4) — it determines
   what a correct pin even is.
3. **Then re-pin `api_image_digest` to the agreed image and re-enable
   `api_image_rollback_guard`**, restoring the declared default of
   `true`. Both live in the gitignored tfvars, so the change is
   operational rather than reviewable in code — which is itself worth
   considering, given this override silently outlived its stated
   one-deployment purpose.
4. **Sequence against the pending apply:** either apply deletion
   protection first while the guard is off, or fix the pin first and
   apply with the guard on. **Do not re-enable the guard and then be
   surprised the apply fails.**

## 7. Scope

DEV only. Production was not inspected, planned or read. No Terraform
apply was run; every plan in this document was read-only (`-lock=false`,
output discarded), with account **770203350335** verified first.
