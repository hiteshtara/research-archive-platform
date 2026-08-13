# Script reference

All paths are relative to the repository root.

## Inventory

| Script | Purpose | Important inputs | Side effects |
|---|---|---|---|
| `scripts/run-local.sh` | Run local API and UI | `ui/.env.local`; fixed local DB/ports | Starts Homebrew PostgreSQL and child processes |
| `scripts/setup-local.sh` | Create local attachment demo | `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_DB` | Generates local fixtures; inserts synthetic rows |
| `scripts/seed-local-subaward-attachments.sql` | Seed demo metadata | Local `archive` schema | Deletes obsolete seed rows; inserts IDs 9000000001–9000000004 |
| `scripts/get-access-token.sh` | Cognito admin password authentication | username; Cognito/AWS identifier overrides | Calls Cognito; creates and removes a 0600 temp payload |
| `scripts/deploy-api.sh` | Deploy API to dev ECS | `--check-only`, `EXPECTED_ACCOUNT_ID` | Builds/pushes images; registers task revision; updates ECS service |
| `scripts/dev-deploy.sh` | Full developer deploy pipeline | `--check-only`, `--skip-backend`, `--skip-frontend`, `--no-push`, `--full` | Tests/builds; may push Git and deploy ECS; observes Amplify |
| `scripts/run-award-loader.sh` | Run core Award loader in Fargate | ECS/network/secret identifiers; one loader verb | May push image, register/run task, migrate/write PostgreSQL |
| `scripts/run-award-attachment-loader.sh` | Run attachment metadata/upload loader | ECS/network/secret identifiers; loader flags | May push image, register/run task, write PostgreSQL/S3 |
| `scripts/run-archive-explorer.sh` | Query predefined archive views in Fargate | resource flags; ECS/network/PostgreSQL identifiers | May push/register/run ECS; archive query is read-only |
| `scripts/filter_award_child_export.py` | Enforce parent-child Award export membership | `--parent`, `--child` | Creates `.original`; replaces child CSV |
| `scripts/tests/test-bulk-load-reconciliation.sh` | Regression-test bulk resume accounting | `jq`; sourced attachment wrapper | Uses only a temporary directory; AWS/Docker calls are faked |

**Removed 2026-08-13:** `scripts/start-db-tunnel.sh` (SSM port forwarding
to dev RDS) and `api/scripts/dev.sh`. This project has no EC2 bastion, so
the tunnel was never actually usable. Dev RDS investigation/ETL goes
through an ECS Fargate one-off task instead - see `CLAUDE.md`'s
"Authoritative data location" section.

## Tool dependencies

The scripts collectively use Bash, AWS CLI, Docker, Git, Maven, npm, Python 3,
`jq`, `curl`, `lsof`, PostgreSQL client tools, Terraform, and `uv`. Local startup assumes Homebrew's
Apple Silicon PostgreSQL path `/opt/homebrew/opt/postgresql@17/bin/pg_isready`.
No single script needs every tool; its preamble and failure messages are the
authoritative per-command requirements.

## Shared ECS loader configuration

`run-award-loader.sh`, `run-award-attachment-loader.sh`, and
`run-archive-explorer.sh` share these inputs:

| Variable | Required/default | Meaning |
|---|---|---|
| `SUBNET_IDS` | required | Comma-separated private subnet IDs |
| `SECURITY_GROUP_ID` | required | Fargate task security group |
| `POSTGRES_SECRET_ID` | required | PostgreSQL Secrets Manager identifier |
| `ECR_REPOSITORY_URI` | required unless `--image-uri` | Loader ECR repository, without tag |
| `AWS_REGION` | `us-east-1` | AWS region |
| `PROJECT_NAME` | `research-archive-platform` | Naming prefix |
| `ENVIRONMENT` | `dev` | Naming suffix |
| `CLUSTER_NAME` | derived | ECS cluster override |
| `TASK_FAMILY` | derived | ECS task family override |
| `POLL_INTERVAL_SECONDS` | `15` where supported | Task polling interval |

Award write/load modes also require `ORACLE_SECRET_ID`; PostgreSQL-only
diagnostics do not. The wrappers accept secret identifiers, never raw database
passwords. `POSTGRES_HOST`, `POSTGRES_PORT`, and `POSTGRES_DB` are optional
routing fallbacks when secret JSON omits those fields.

## Award loader verbs

- `--migrate-only`: apply/validate schema without Oracle.
- `--load-award-id ID`: idempotent load of one Award version family.
- `--create-batch N`, `--load-batch ID`, `--show-batch ID`: create, load, or inspect a bounded batch.
- `--diff-award-versions AWARD_NUMBER`: compare Oracle and archive versions.
- `--investigate-workflow-document-number NUMBER`: targeted workflow diagnostic.
- `--dry-run`: roll back compatible data writes.
- `--image-uri URI`: skip local Docker build and ECR push.

Only one primary operation is accepted at a time. The script validates numeric
IDs and incompatible combinations before launching Fargate.

## Attachment loader flags

The attachment wrapper supports bounded metadata loads (`--load-file-id`,
`--load-file-ids`, `--load-batch`), upload selection (`--upload`, `--file-id`,
`--batch-id`, `--limit`, `--retry-failed`, `--bucket`, `--prefix`), batch
management (`--create-batch`, `--include-already-uploaded`, `--show-batch`),
read-only reports (`--show-upload-status`, `--list-awards-with-attachments`,
`--diff-award-attachments`), migration, and resumable bulk orchestration
(`--bulk-load`, `--bulk-batch-size`, `--state-file`).

Consult the extensive header in the script for the precise compatibility rules.
The key distinction is that `--dry-run` protects database writes, while S3
upload requires explicit `--upload`; infrastructure actions such as image push,
task-definition registration, and task launch still occur unless avoided by
`--image-uri` where applicable.

## Exit behavior

Most wrappers use `set -euo pipefail` and return nonzero on local validation,
AWS API, task, or verification failure. The ECS runners wait for the container,
tail CloudWatch logs, and propagate a known nonzero container exit code.
`dev-deploy.sh` uses a result table and EXIT trap to preserve a multi-step
summary. Treat a missing/unknown ECS exit code or failed log tail as an
incomplete operator signal and confirm the task in AWS.
