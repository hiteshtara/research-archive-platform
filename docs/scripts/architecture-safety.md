# Script architecture and safety

The directory is an operator interface, not a reusable application library.
Most scripts resolve the repository root from their own path and delegate real
work to Maven/npm, Terraform outputs, AWS services, or the Python ETL package.

## Execution boundaries

```text
local developer
├── run-local / setup-local ─────> local PostgreSQL, API, UI, fixtures
├── get-access-token ────────────> Cognito
├── start-db-tunnel ─────────────> SSM session ──> private dev RDS
├── deploy-api / dev-deploy ─────> ECR, ECS service, Amplify checks
└── ETL wrappers ────────────────> ECR + ECS one-off task
                                      ├── Secrets Manager identifiers
                                      ├── Oracle source
                                      ├── PostgreSQL archive
                                      └── documents S3 bucket
```

The ECS wrappers clone the current loader task definition, transform its image
and command, register a new revision, launch it in private subnets, poll until
STOPPED, and tail CloudWatch logs. They do not update Terraform state. This
means a read-only data command can still create an ECR image and ECS task
definition revision; supply a trusted `--image-uri` to avoid the build/push
portion.

## Credential and secret handling

- AWS access comes from the caller's profile or environment. Deployment and
  tunnel scripts resolve STS identity before mutation and compare it with an
  expected account.
- ETL wrappers pass Secrets Manager identifiers to the container. They do not
  accept or forward raw Oracle/PostgreSQL passwords.
- `get-access-token.sh` requires a TTY, disables password echo, uses a private
  temporary payload file, and prints only the access token to stdout.
- `dev-deploy.sh` accepts optional Cognito test credentials from environment
  variables. Environment variables can leak through process inspection,
  diagnostics, or shell history; use only an approved local secret-injection
  method and keep tracing disabled.

## Guardrails and their limits

Account checks reduce wrong-account risk, but several defaults are explicitly
dev-specific. Changing `EXPECTED_ACCOUNT_ID` does not rewrite ECR/ECS names,
Cognito IDs, Terraform directories, database endpoints, or fallback VPC IDs.
Review all resolved context before treating a wrapper as account-portable.

`start-db-tunnel.sh` prefers Terraform output, then Secrets Manager, then a
literal RDS fallback. A supplied cross-VPC SSM instance is allowed with a
warning. Its `--check-only` mode is the safest first check.

`--dry-run` describes the ETL application's transaction behavior; it does not
make the surrounding wrapper free of AWS mutations. Without `--image-uri`, the
wrapper can build and push an image, and it registers/runs an ECS task in either
case. Likewise, Archive Explorer queries are read-only but its orchestration is
not infrastructure-neutral.

## Data recovery points

- The CSV filter creates `<child>.original` once before atomically replacing
  the child with the filtered file. Preserve that backup until counts reconcile.
- Attachment bulk loads checkpoint JSON after batches. The state file is part
  of the recovery protocol; preserve it across credential expiry or task failure.
- Local attachment seed rows use reserved synthetic IDs and `ON CONFLICT DO
  NOTHING`. Removal SQL is documented, but commented out, at the bottom of the
  seed file.
- ECS image tags include UTC time and Git SHA. Prefer the immutable tag over
  `latest` when diagnosing or repeating a deployment.

## Safe operating sequence

1. Inspect `git status` and the exact script version.
2. Confirm AWS profile, STS account, region, and target environment.
3. Use `--check-only`, a read-only report, or a small `--dry-run` where available.
4. Pin a previously validated `--image-uri` for repeatable ECS work.
5. Choose the smallest data scope: one ID, then one batch, then bulk.
6. Capture task ARN, immutable image tag, batch ID/state file, and reconciliation counts.
7. Verify PostgreSQL/S3 results independently before expanding scope.

For ETL grain guarantees and incident-derived procedures, continue with the
[ETL architecture](../etl/architecture.md) and [ETL operations guide](../etl/operations.md).
