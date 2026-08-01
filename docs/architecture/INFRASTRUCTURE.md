# Infrastructure Reference (Terraform)

A map of what each Terraform module actually provisions and how the pieces
wire together. This is the architecture reference — for the *how to deploy*
runbook (bootstrap, `init`/`plan`/`apply`, account safety, destroy
procedure, migration/cutover notes), see
[`terraform/README.md`](../../terraform/README.md); that doc is
authoritative for operational steps and isn't duplicated here.

```
terraform/
  bootstrap/        one-time: S3 state bucket (local state, not a module)
  environments/
    dev/  test/  prod/     one main.tf per environment, wiring modules together
  modules/           9 reusable modules
```

## Module inventory

| Module | Creates |
|---|---|
| `vpc` | VPC, public/private subnets, Internet Gateway, NAT Gateway + EIP (optional), route tables, VPC endpoints (ECR API/DKR, CloudWatch Logs, Secrets Manager, STS, S3), and optionally a VPC peering connection + routes to an existing Oracle staging VPC |
| `rds` | PostgreSQL instance, DB subnet group, security group, a random master password, and a Secrets Manager secret holding the DB credentials |
| `s3` | Two buckets — `data` (ETL landing/validation prefixes) and `documents` (attachment storage, with per-domain prefixes: IRB, Awards, Proposals, Negotiations, Subawards, plus a lifecycle rule) — each with versioning, SSE, public-access block, and ownership controls |
| `secrets` | An empty Secrets Manager container for the OpenAI API key (no value — populated out-of-band) |
| `api_ecr` / `ecr` | ECR repository + lifecycle policy for, respectively, the API image and the ETL loader image (two separate repos, two separate modules with near-identical bodies) |
| `ecs` | The **ETL loader's** ECS cluster, CloudWatch log group, security group (with ingress rules for reaching the database and, when peered, Oracle), IAM execution/task roles (S3, Secrets Manager, documents-bucket access), and the loader's task definition. No service/ALB — the loader runs as a one-off/scheduled task, not a long-running service. |
| `api_service` | The **API's** own ECS cluster, ALB + target group + HTTP/HTTPS listeners, security groups (ALB and API, plus an ingress rule opening the database to the API's SG), IAM execution/task roles (Secrets Manager, documents-bucket access), CloudWatch log group, and the ECS service + task definition |
| `cognito` | User Pool, app client, and optionally a Hosted UI domain — only created when `manage_cognito = true` |
| `amplify` | Amplify app, branch, and optional custom-domain association — only created when `manage_amplify = true` |

Note the API and the ETL loader each get their **own ECS cluster** (`api_service.aws_ecs_cluster.api` vs `ecs.aws_ecs_cluster.this`) and their own ECR repo — they are not sub-services of one shared cluster.

## How an environment wires them together

`environments/{dev,test,prod}/main.tf` is the composition root; all three
follow the same shape (only variable values differ — see
[`terraform/README.md`](../../terraform/README.md#environment-separation)
for why they're directories, not workspaces). Dependency order, following
the actual `module.x.output` references in `dev/main.tf`:

```
module.vpc
  ├─▶ module.rds                (vpc_id, private_subnet_ids)
  ├─▶ module.loader_ecs          (vpc_id, private_subnet_ids)
  └─▶ module.api_service          (vpc_id, public/private_subnet_ids)

module.archive_s3 ─────────────▶ module.loader_ecs (data + documents bucket ARNs)
                    └───────────▶ module.api_service (documents bucket ARN)

module.rds ─────────────────────▶ module.loader_ecs (database_secret_arn, database_security_group_id)
            └───────────────────▶ module.api_service (same)

module.loader_ecr ──────────────▶ module.loader_ecs (repository_url, combined with loader_image_tag)
module.api_ecr ──────────────────▶ module.api_service (repository_url, combined with api_image_tag)

aws_secretsmanager_secret.oracle ▶ module.loader_ecs (oracle_secret_arn)
module.openai_secret (count=0/1) ▶ module.api_service (additional_secrets, only if enable_openai_secret)

module.cognito (count=0/1) ──┐
                              ├─▶ locals.cognito_* (created pool vs. bring-your-own vars)
                              ├─▶ module.amplify (env vars for the UI build)
                              └─▶ module.api_service (cognito_issuer_uri/client_id, for JWT validation)

module.api_service ──────────▶ aws_route53_record.api (alias to the ALB, only if api_domain_name is set)
```

Two things worth calling out that aren't obvious from the module list
alone:

- **The Oracle credentials secret is not a module.** Unlike the OpenAI
  secret (wrapped in `module.secrets`), the Oracle (KCOEUS) secret for the
  Award Attachment loader is a bare `aws_secretsmanager_secret` resource
  directly in each environment's `main.tf`. This is deliberate — reusing
  `module.secrets`'s OpenAI-specific output names
  (`openai_secret_arn`/`openai_secret_name`) for an unrelated secret would
  be confusing, so it was left inline instead. Like the OpenAI secret,
  Terraform only creates the empty container; the value is set out-of-band
  (see `docs/AWARD_ATTACHMENT_ECS_EXECUTION.md`).
- **Cognito and Amplify are both optional, toggled independently** via
  `manage_cognito`/`manage_amplify` (`count = var.x ? 1 : 0`), so either can
  be "bring your own" while the other is Terraform-managed. `locals.cognito_*`
  in each environment's `main.tf` is what resolves "use the pool this
  config just created" vs. "use the existing pool's IDs from variables"
  into a single set of values consumed by both `module.amplify` and
  `module.api_service`.

## Networking specifics

`module.vpc` also provisions VPC endpoints (ECR API/DKR, CloudWatch Logs,
Secrets Manager, STS, S3) so ECS tasks in private subnets can reach those
AWS services without needing a NAT Gateway for them specifically — NAT is
still needed for genuinely external egress (e.g. the live OpenAI provider
reaching `api.openai.com`), which is why `enable_nat_gateway` and
`use_private_subnets_for_api` are separate, and why the API defaults to
running in public subnets with a public IP rather than requiring NAT (see
`terraform/README.md`'s "NAT Gateway / private subnets" section).

Oracle connectivity is handled by the same module: `enable_oracle_peering`
creates a VPC peering connection plus routes to an existing Oracle staging
VPC (`oracle_vpc_id`, `oracle_subnet_cidrs`, `oracle_route_table_ids`),
consumed by `module.loader_ecs`'s security group. Full setup and validation
steps live in
[`docs/ORACLE_STAGING_CONNECTIVITY.md`](../ORACLE_STAGING_CONNECTIVITY.md) —
this doc only covers which module owns the resources.

Each environment uses a distinct VPC CIDR (dev `10.30.0.0/16`, test
`10.31.0.0/16`, prod `10.32.0.0/16`) so future peering/Transit Gateway
between them doesn't hit a collision.

## Where to look next

- **Deploying or changing an environment**: [`terraform/README.md`](../../terraform/README.md) — prerequisites, bootstrap, account safety, outputs, destroy procedure, and the current dev-environment drift/migration notes.
- **Day-to-day AWS operations** (ECS deploys, ECR pushes, log access): [`ops/AWS_OPERATIONS.md`](../../ops/AWS_OPERATIONS.md).
- **Oracle peering setup/validation**: [`docs/ORACLE_STAGING_CONNECTIVITY.md`](../ORACLE_STAGING_CONNECTIVITY.md).
- **AI feature deployment** (OpenAI secret, `APP_AI_*` env vars on the API task): [`docs/runbooks/ecs-ai-deployment.md`](../runbooks/ecs-ai-deployment.md).
- **Database schema that RDS ends up holding**: [`docs/architecture/DATABASE_SCHEMA.md`](DATABASE_SCHEMA.md).
