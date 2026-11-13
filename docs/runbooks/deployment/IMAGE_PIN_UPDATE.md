# Updating the API image pin after a deployment

**Audience:** whoever runs `ops/deploy-api.sh`.
**When:** immediately after every API deployment to an environment, before
anyone runs `terraform plan` there.
**Time:** about two minutes.

---

## Why this exists

API images are deployed by `ops/deploy-api.sh`, which registers a new ECS
task-definition revision and points the service at it. **Terraform does
not do this and does not observe it.** Terraform has its own idea of the
image, held in `api_image_digest` in the environment's gitignored
`terraform.tfvars`.

So every deployment makes that pin stale. A stale pin is not cosmetic: a
`terraform apply` for an unrelated reason would register a task
definition carrying the *old* image. On its own that does not deploy
anything — `lifecycle { ignore_changes = [task_definition] }` keeps the
service where it is — but it leaves a loaded gun in the family, and any
later operation that does move the service would move it backwards.

`api_image_rollback_guard` exists to stop that. It compares the pin
against the image **the ECS service is actually running** and refuses to
plan when they differ. The guard is on by default; if you see it fire,
the fix is almost always this runbook, not disabling the guard.

> This procedure is what closes the loop. Between 2026-10-28 and
> 2026-11-13 the DEV pin sat ~5 weeks stale while the guard was switched
> off in tfvars so plans would still run. Both halves of that are now
> corrected; this runbook is the thing that keeps it from recurring.

---

## Procedure

Run from the environment directory, e.g. `terraform/environments/dev`.

### 1. Read what the service is running

```bash
export AWS_PROFILE=bu-nprd        # account 770203350335
aws sts get-caller-identity --query Account --output text   # expect 770203350335

CLUSTER=research-archive-platform-dev-api
SERVICE=research-archive-platform-dev-api

TD=$(aws ecs describe-services --region us-east-1 \
      --cluster "$CLUSTER" --services "$SERVICE" \
      --query 'services[0].taskDefinition' --output text)
echo "serving: $TD"

aws ecs describe-task-definition --region us-east-1 --task-definition "$TD" \
  --query 'taskDefinition.containerDefinitions[?name==`api`].image' --output text
```

The image comes back as `<repo>@sha256:<digest>`. **That digest is the
value to pin** — it is what ECS recorded and what the guard compares.

Read the **service**, not `list-task-definitions --sort DESC`. The
family's newest ACTIVE revision is routinely one the service never
adopted.

### 2. Update the pin

In the environment's `terraform.tfvars`:

```hcl
api_image_digest = "sha256:<the digest from step 1>"
```

Leave `api_image_rollback_guard` **unset**. It defaults to `true`.

### 3. Verify

```bash
terraform plan -detailed-exitcode
```

Expected: **`No changes.`**, exit code `0`.

| Outcome | Meaning | Do |
|---|---|---|
| `No changes.` | Pin matches; nothing else drifted | Done |
| `Resource precondition failed` | Pin still does not match the serving image | Re-read step 1 — don't disable the guard |
| `1 to add, 0 to change, 1 to destroy` on `aws_ecs_task_definition.api` | Pin now differs from what Terraform has in **state** (normal the first time after a deploy Terraform never saw) | See "Replacement" below |

---

## Replacement: what `1 add / 1 destroy` means here

A task definition is immutable, so changing the image forces a new
revision. Terraform expresses that as `["delete","create"]`, which counts
in **both** columns — one resource, not two.

Quote Terraform's own `Plan:` line. Don't recompute it: a replacement is
easy to miscount as `0 to add` if you look only for actions equal to
exactly `["create"]`.

Applying it is safe but not free:

- `skip_destroy = true` means the destroy half is **state-only**. The old
  revision is released from Terraform's state and is **never**
  deregistered — it stays `ACTIVE` and remains available for rollback.
- `ignore_changes = [task_definition]` means the service is **not**
  repointed. Nothing deploys, nothing restarts.
- The cost is one more unused ACTIVE revision on the family.

Because of that cost, applying is a deliberate, separately approved act
— not something to fold into an unrelated apply.

---

## Multi-arch images: pin the child, not the index

`aws ecr describe-images` will often report **no tags** for the digest you
just read from ECS. That is expected and is not a sign you read the wrong
thing.

`docker buildx` pushes an **OCI image index** (plus an attestation
manifest). The tag is attached to the *index*; ECS records the
**linux/amd64 child manifest**. They are different digests.

Worked example, verified on DEV 2026-11-13:

| | Digest | Tagged? |
|---|---|---|
| Index (`application/vnd.oci.image.index.v1+json`) | `sha256:6c0efb2e…` | yes — `def5-543ab22` |
| linux/amd64 child — **what revision 94 records** | `sha256:1d440f251c…` | no |

So `aws ecr describe-images --image-ids imageTag=def5-543ab22` returns
`6c0efb2e…`, which is **not** the value to pin.

**Pin the child digest — the one ECS reports.** To find the tag for human
reference:

```bash
CHILD=sha256:<the digest from step 1>
for d in $(aws ecr describe-images --region us-east-1 \
     --repository-name research-archive-platform-dev-api \
     --query 'sort_by(imageDetails,&imagePushedAt)[-8:].imageDigest' --output text); do
  aws ecr batch-get-image --region us-east-1 \
    --repository-name research-archive-platform-dev-api \
    --image-ids imageDigest=$d --query 'images[0].imageManifest' --output text \
  | grep -q "${CHILD#sha256:}" && \
  aws ecr describe-images --region us-east-1 \
    --repository-name research-archive-platform-dev-api \
    --image-ids imageDigest=$d --query 'imageDetails[0].imageTags' --output json
done
```

Always build with `--platform linux/amd64`.

---

## Deliberate rollback

To pin an image *older* than what is serving, the guard must be bypassed
for that one command:

```bash
terraform plan -var 'api_image_rollback_guard=false' -var 'api_image_digest=sha256:<older>'
```

Use `-var`. **Do not** set `api_image_rollback_guard = false` in
`terraform.tfvars`: that file is gitignored, so a disabled guard is
invisible to review and tends to stay disabled. That is exactly how it
went unnoticed for two weeks.

Record any such bypass in `docs/development/CHANGELOG_ARCHITECTURE.md`.

---

## Related

- `docs/operations/ECS_TASK_DEFINITION_OWNERSHIP.md` — who owns the task
  definition, and why Terraform and `deploy-api.sh` both touch it
- `docs/operations/ROLLBACK_GUARD_ANALYSIS.md` — the guard's design and
  the newest-ACTIVE defect corrected in PR #146
- `docs/operations/OPTION_C_PIN_RESYNC_PROPOSAL.md` — the 2026-11-13
  re-sync, worked through end to end
