# Pinning the SSM tunnel's container image

**Status: prepared, not applied.**

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

Rule 1 exists precisely for images referenced by a pinned task definition.

Measured 2026-10-28: the repository holds **27** entries, so the oldest 7
are already due for expiry, and the image the tunnel runs today
(`20260923T163946Z-443e507`, digest `sha256:19972492…`) sits at **#21 of
27** — roughly **four more loader builds** of headroom before rule 2 sweeps
it. Each build pushes about three entries.

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

## Applying it, when approved — in this order

1. **Protect the image first**, so rule 1 shields it from rule 2:

   ```sh
   export AWS_PROFILE=bu-nprd
   MANIFEST="$(aws ecr batch-get-image \
     --repository-name research-archive-platform-dev-loader \
     --image-ids imageTag=20260923T163946Z-443e507 \
     --query 'images[0].imageManifest' --output text)"
   aws ecr put-image \
     --repository-name research-archive-platform-dev-loader \
     --image-tag protected-dbtunnel-base \
     --image-manifest "$MANIFEST"
   ```

   This adds a tag to an existing manifest. It pushes no new image and
   changes no digest.

2. Add to the untracked `terraform.tfvars`:

   ```hcl
   db_tunnel_image = "770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader@sha256:19972492b07aae991577e52295694babd9792aa1cf69a79b547ba2987111bc53"
   ```

3. `terraform plan -target=aws_ecs_task_definition.db_tunnel`

   **Expect exactly one replacement**, converting the same image from tag
   to digest:

   ```
   before: …dev-loader:20260923T163946Z-443e507
   after:  …dev-loader@sha256:19972492…
   ```

   One deliberate replacement to establish the pin; after it, loader
   deploys cannot touch this resource.

## Why this image

It is the one the tunnel runs today and has been used successfully,
including for the `archive_api` verification on 2026-10-28 — "known-good"
in the literal sense. It is an OCI image **index**, so it resolves per
architecture and does not depend on the task definition declaring a
`runtimePlatform` (it declares none, so Fargate defaults to LINUX/X86_64).

The container runs `sleep` and nothing else; the ETL code in the image is
never invoked. Freezing it carries no functional debt.
