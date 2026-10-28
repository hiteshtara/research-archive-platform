# Pinning the SSM tunnel's container image

**Status (2026-10-28): the ECR image is protected; the Terraform apply has
NOT been run.** The tunnel still runs task definition revision 1 from its
mutable tag.

## The problem

Deploying a new ETL loader revision replaces the DB tunnel's task
definition. The two have nothing to do with each other.

`db_tunnel.tf` reads the tunnel's image from the **live loader task
definition**:

```hcl
data "aws_ecs_task_definition"      "loader_live" { task_definition = module.loader_ecs.task_definition_family }
data "aws_ecs_container_definition" "loader_live" { ... }
image = data.aws_ecs_container_definition.loader_live[0].image
```

That was a deliberate, well-reasoned choice, and the comment above it
explains why: the image must already be in this account's ECR (the VPC has
no route to public ECR), and the obvious alternative — composing
`<loader repo>:${var.loader_image_tag}` — resolves to `latest`, which does
not exist in that repository. A plan would succeed and the first
`run-task` would fail with `CannotPullContainerError`. Reading the live
definition "can only ever name an image that is actually deployed."

The cost is coupling. Registering loader revision 254 on 2026-10-28 (the
`linux/amd64` rebuild) immediately made the tunnel's task definition show
as needing replacement, in a plan that had nothing to do with the tunnel.

## The thing that makes a naive pin worse than no pin

The loader ECR repository has a lifecycle policy:

| Rule | Selection | Action |
|---|---|---|
| 1 | tagged `protected-*` | expire after 36500 days — i.e. never |
| 2 | **any**, more than 20 images | expire |

Rule 1 exists precisely for images referenced by a pinned task definition,
and **it was already in use**: `20260817T011034Z-4a82821` also carries
`protected-20260817T011034Z-4a82821`. So this is the established local
pattern, not one invented here.

The image the tunnel runs (`20260923T163946Z-443e507`, digest
`sha256:19972492…`) was **not** protected, so rule 2 would eventually have
swept it and the tunnel would have failed with `CannotPullContainerError`.

> **Correction.** An earlier revision of this document estimated "the
> oldest 6 are due to expire" from the image count. That arithmetic was
> wrong — ECR's own `get-lifecycle-policy-preview` is authoritative and
> reports **3**, apparently because untagged manifests referenced by an
> image index are not counted independently. The risk was real but less
> imminent than stated. Use the preview, not arithmetic.

So this is not only a tidiness problem. **The tunnel's current image is on
a path to being deleted**, pinned or not; pinning to it without protecting
it would simply freeze the failure in place.

## The change

A new `var.db_tunnel_image`, digest-only, defaulting to `null`:

- **set** → that image is used and the two `loader_live` data sources drop
  to `count = 0`, so they are not even read. The coupling is gone
  structurally, not by convention.
- **null** → the historical behaviour, unchanged.

Validation rejects a tag, because tags in this repository are `MUTABLE`
and therefore not a stable reference.

## Step 1 — protect the image: **DONE 2026-10-28**

```
tag:    protected-20260923T163946Z-443e507
digest: sha256:19972492b07aae991577e52295694babd9792aa1cf69a79b547ba2987111bc53
```

Both tags now sit on **one** image entry — a tag was added, no image was
pushed, no digest changed, and the repository still holds 27 entries. The
pre-existing `protected-20260817T011034Z-4a82821` was not touched.

Confirmed by `aws ecr get-lifecycle-policy-preview`: 3 images would expire,
all under rule 2, and **the tunnel image is not among them**.

### The trap in `put-image`, which caught me once

The obvious command is wrong:

```sh
# WRONG - creates a NEW image with a DIFFERENT digest
aws ecr batch-get-image ... --query 'images[0].imageManifest' --output text > manifest.json
aws ecr put-image ... --image-manifest file://manifest.json
```

A manifest's digest is the SHA-256 of its **exact bytes**. `--output text`
appends a newline, so the pushed manifest is 857 bytes where the original
is 856, and ECR stores a *second* image — same layers, different digest —
and puts your `protected-` tag on the wrong one. I did this, saw digest
`sha256:9e85ff3b…` come back instead of `…19972492…`, deleted the stray
manifest, and redid it byte-exactly.

The reliable form verifies the bytes reproduce the digest **before**
pushing:

```python
import json, subprocess, hashlib
raw = subprocess.run(["aws","ecr","batch-get-image",
    "--repository-name","research-archive-platform-dev-loader",
    "--image-ids","imageTag=<tag>","--output","json"],
    capture_output=True, text=True, check=True).stdout
img = json.loads(raw)["images"][0]
b = img["imageManifest"].encode()
assert "sha256:"+hashlib.sha256(b).hexdigest() == img["imageId"]["imageDigest"]
open("manifest.exact","wb").write(b)
```

then `put-image --image-manifest file://manifest.exact
--image-manifest-media-type application/vnd.oci.image.index.v1+json`.

## Step 2 — tfvars: **DONE 2026-10-28**

In the untracked `terraform/environments/dev/terraform.tfvars`:

```hcl
db_tunnel_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader@sha256:19972492b07aae991577e52295694babd9792aa1cf69a79b547ba2987111bc53"
```

## Step 3 — apply: **NOT RUN, awaiting approval**

```sh
cd terraform/environments/dev
export AWS_PROFILE=bu-nprd
terraform plan  -target=aws_ecs_task_definition.db_tunnel -out=tunnel-pin.tfplan
terraform apply tunnel-pin.tfplan
```

Expect: **1 added, 0 changed, 1 destroyed** — the tunnel task definition
only, going from **revision 1 to revision 2**, converting the same image
from tag to digest:

```
before: …dev-loader:20260923T163946Z-443e507
after:  …dev-loader@sha256:19972492…
```

No running task is disturbed: the tunnel has no ECS *service*, and
`start-db-tunnel.sh` launches a one-off task against the family's latest
revision each time. Zero tunnel tasks were running when this was prepared.

### Verify after applying

```sh
aws ecs describe-task-definition --task-definition research-archive-platform-dev-dbtunnel \
  --query 'taskDefinition.{rev:revision,image:containerDefinitions[0].image}'
scripts/start-db-tunnel.sh      # must still reach dev RDS
scripts/stop-db-tunnel.sh
```

A plan run afterwards must report **no changes** for this resource, and
must keep reporting none when a new loader revision is registered.

## Rollback

The pin is one tfvars line, so rolling back is removing it:

```sh
# delete the db_tunnel_image line from terraform.tfvars, then
terraform apply -target=aws_ecs_task_definition.db_tunnel
```

That registers revision 3, identical in substance to revision 1 (it
resumes following the live loader image — which is now loader 254's
`…-amd64` image, not the Sept 23 one).

To return to the *exact* previous state instead, revision 1 remains ACTIVE
and `start-db-tunnel.sh` can be pointed at it; and because the ECR tag is
protected, revision 1's image will still pull.

Leave `protected-20260923T163946Z-443e507` in place either way — it costs
nothing and removing it would re-expose the image to rule 2.

## Why this image

It is the one the tunnel runs today and has been used successfully,
including for the `archive_api` verification on 2026-10-28 — "known-good"
in the literal sense. It is an OCI image **index**, so it resolves per
architecture and does not depend on the task definition declaring a
`runtimePlatform` (it declares none, so Fargate defaults to LINUX/X86_64).

The container runs `sleep` and nothing else; the ETL code in the image is
never invoked. Freezing it carries no functional debt.
