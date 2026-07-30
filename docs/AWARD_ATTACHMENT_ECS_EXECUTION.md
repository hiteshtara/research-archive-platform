# Award Attachment Loader: ECS Production Execution

Documents the Award Attachment loader's production execution model
(`etl/load_award_attachments.py --ecs`), built on branch
`feature/award-attachment-s3-loader`. Covers local development, ECS
execution, one-file validation, batch execution, recovery after
interruption, rollback, Secrets Manager requirements, and CloudWatch logs.

## Why `--ecs` exists

There is **no EC2 bastion or SSM jump host in the project VPC**, and the
private RDS instance
(`research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com`)
is not reachable from a laptop. The only supported way to run a real load
or upload against BU RDS is **inside the existing ECS loader task**
(`research-archive-platform-dev-loader`, family defined in
`terraform/modules/ecs/main.tf` — not modified by this branch):

```text
Oracle (BU VPN-only, read-only)
    │  Award Attachment Loader, running inside the ECS loader task
    ▼
PostgreSQL RDS (private subnet, reachable only from inside the VPC)
    │
    ▼
S3 documents bucket (research-archive-platform-dev-documents-770203350335)
```

Do not build (or expect) a `localhost:15432` tunnel workflow for this
loader — that pattern applies to the *unrelated* local-Postgres-vs-BU-RDS
tunnel described in `docs/runbooks/LOCAL_SETUP.md` for other domains, not
to Award Attachment uploads, which must run where the RDS instance is
actually reachable: inside ECS.

## Local development

Local development still works exactly as it did in Sprints 1–2 —
`--ecs` is opt-in, never implied:

```bash
cd etl
uv sync
export ORACLE_USER=... ORACLE_PASSWORD=... ORACLE_DSN=...
export POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5432 POSTGRES_DB=... \
       POSTGRES_USER=... POSTGRES_PASSWORD=...

uv run python load_award_attachments.py --limit 10 --dry-run   # metadata, read-only
uv run python load_award_attachments.py --file-id 9001 --dry-run  # one-file lookup
uv run python load_award_attachments.py --upload --limit 5 --bucket my-dev-bucket
```

None of this requires `--ecs`, Secrets Manager, or an ECS task — it is
identical to Sprint 1/2's local workflow. `--ecs` is specifically for
running *inside* the ECS loader task (or an environment that otherwise
provides ECS-shaped credentials/config), not for local development.

Unit tests never require live Oracle/Postgres/AWS credentials — every
collaborator is mocked:

```bash
uv run pytest
uv run ruff check .
uv run mypy .
```

## ECS execution

`--ecs` changes three things, and only three:

1. **Credential resolution** (`archive_etl/config/ecs.py`) — never
   requires a local export. PostgreSQL: plain `POSTGRES_*` environment
   variables first (already true in the current, unmodified ECS task
   definition — its `secrets` block resolves the RDS-managed Secrets
   Manager secret into plain env vars before the container's process ever
   starts), falling back to a direct Secrets Manager lookup via
   `POSTGRES_SECRET_ARN` if the plain vars aren't populated. Oracle:
   Secrets Manager via `ORACLE_SECRET_ARN` if set, otherwise plain
   `ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` environment variables.
   Fails with a clear `ConfigurationError` if neither path resolves —
   never silently falls through to "nothing configured."
2. **Structured logging** (`archive_etl/utils/structured_logging.py`) —
   replaces the default human-readable stderr handler with one JSON
   object per line on stdout, which ECS's `awslogs` log driver (already
   configured for the loader task) forwards to CloudWatch Logs verbatim.
3. **Startup validation** (`archive_etl/config/startup_validation.py`) —
   read-only checks, run once, before touching any file (see "Startup
   validation" below). Aborts immediately with a clear
   `StartupValidationError` on the first failure.

It also applies one **production default**: `--bucket` defaults to the
`DATA_BUCKET_NAME` environment variable (already injected as a plain
variable by the existing task definition) when not explicitly given.

AWS credentials themselves need no special handling — running inside a
real ECS task, `boto3`'s default credential chain already picks up the
task role's temporary credentials automatically via the ECS container
credentials endpoint. `--ecs` mode's startup validation and
`validate_aws_identity()` (an `sts:GetCallerIdentity` call) simply confirm
this resolved correctly; there is no bespoke credential-fetching code for
this, and there should not be.

```bash
# Inside the ECS loader task (or an environment providing equivalent
# ECS-shaped config):
uv run python load_award_attachments.py --ecs --upload --limit 500
```

### Secrets Manager requirements

| Credential | Source | Shape (verified against `terraform/modules/rds/main.tf`) |
| --- | --- | --- |
| PostgreSQL | `<project>/<environment>/postgres` secret (already provisioned; ARN available as `POSTGRES_SECRET_ARN` if you want the loader to fetch it directly instead of relying on the task definition's `secrets` block) | `{"engine", "host", "port", "dbname", "username", "password"}` |
| Oracle | `ORACLE_SECRET_ARN`, if you choose to provision one | **Proposed, not verified** — no Oracle secret currently exists in this repo's Terraform. Assumed shape: `{"username", "password", "dsn"}`. Verify (or provision) before relying on this path; plain `ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` environment variables remain fully supported and require no secret at all. |

The execution role's IAM policy (`terraform/modules/ecs/main.tf`'s
`execution_secrets` policy) already grants `secretsmanager:GetSecretValue`
on the Postgres secret. If you provision an Oracle secret, its ARN needs
the same grant added to that policy — a Terraform change, out of scope
for this branch.

### CloudWatch logs

Log group: `/ecs/research-archive-platform-dev-loader` (from
`aws_cloudwatch_log_group.loader`). Log stream naming follows ECS's
`awslogs-stream-prefix` convention: `loader/loader/<task-id>` (stream
prefix `loader`, container name `loader`).

Every line is a single JSON object:

```json
{"timestamp": "...", "level": "INFO", "message": "file_id=9001 upload succeeded (424242 bytes)", "run_id": "...", "stage": "upload", "file_id": 9001, "status": "uploaded", "elapsed_ms": 812.4}
```

Fields: `timestamp`, `level`, `message`, `run_id` (bound once for the
whole process), and whichever of `stage`/`file_id`/`status`/`elapsed_ms`
the call site bound. Never SQL text, never BLOB content, never
credentials — `last_error` always goes through the same
`redact_error_message()` used everywhere else in this loader before it is
ever logged or persisted.

Example CloudWatch Logs Insights query for one run's outcome breakdown:

```text
fields @timestamp, file_id, status, elapsed_ms
| filter run_id = "<run-id>"
| stats count() by status
```

## Startup validation

Runs once, at the start of any `--ecs` invocation, entirely read-only (no
writes — always safe under `--dry-run` too):

1. PostgreSQL reachable (`SELECT 1`)
2. Oracle reachable (`SELECT 1 FROM DUAL`)
3. S3 bucket exists (`HEAD` — skipped, not failed, if no bucket is
   configured at all, e.g. a metadata-only `--ecs` run with no
   `--bucket`/`DATA_BUCKET_NAME`)
4. `archive.attachment_object` and `archive.award_attachment` tables exist
5. `archive.attachment_object.upload_status`'s CHECK constraint
   (`ck_attachment_object_upload_status`) matches the migration V036
   contract (`PENDING`, `UPLOADING`, `UPLOADED`, `FAILED`,
   `MISSING_SOURCE_CONTENT`)

Each check raises a clear `StartupValidationError` identifying exactly
what failed; the loader aborts immediately rather than proceeding with a
partially-verified environment.

## One-file validation

Use `--file-id` with `--dry-run` (works locally or with `--ecs`) to
confirm a specific physical file's metadata before trusting a batch run:

```bash
uv run python load_award_attachments.py --ecs --file-id 9001 --dry-run
```

This is an exact, targeted lookup (never an arbitrary `--limit` sample —
see the "Sprint 2.1" fix), reports filename/content type/source
location/size, and never connects to PostgreSQL or reads/logs BLOB
content.

## Batch execution

```bash
# Upload up to 500 pending/in-progress physical files:
uv run python load_award_attachments.py --ecs --upload --limit 500

# Via the deployment helper (builds/pushes the image, registers a task
# revision, launches the one-off task, waits, streams logs):
scripts/run-award-attachment-loader.sh --upload --limit 500
```

`--limit` bounds how many candidate rows are selected per invocation —
run it repeatedly (or drop `--limit` entirely) to work through the full
backlog; already-`UPLOADED` rows with a matching bucket/key are always
skipped (see "Recovery after interruption" below), so repeated batch runs
never re-upload the same physical file.

## Recovery after interruption

Every file's status transition (`UPLOADING` → `UPLOADED`/`FAILED`) is its
own immediately-committed transaction, deliberately not part of one big
batch transaction — a crash or task kill mid-run leaves durable, resumable
progress rather than rolling everything back. To resume:

```bash
# Re-run the same command - PENDING and UPLOADING (left mid-attempt by
# the interrupted run) rows are picked up again automatically:
uv run python load_award_attachments.py --ecs --upload

# Also retry rows a previous run marked FAILED:
uv run python load_award_attachments.py --ecs --upload --retry-failed
```

No manual cleanup is required. `MISSING_SOURCE_CONTENT` rows are never
retried automatically (there is structurally nothing to upload for them).

## Rollback procedure

There is no destructive "rollback" for the upload step, by design — a
successful upload's `UPLOADED` row is never overwritten by a later run
unless the target bucket/key changes, and a failed or interrupted upload
never partially corrupts `archive.attachment_object` (each row's own
transaction either commits a clean status transition or doesn't commit at
all). To undo a bad batch:

1. Identify the affected rows via `archive.attachment_object` (filter by
   `uploaded_at`/`s3_bucket`/`s3_key`).
2. If the S3 objects themselves need to be removed, do so directly via
   `aws s3 rm` (out of scope for this loader, which never deletes from
   S3).
3. Reset the affected rows' `upload_status` back to `PENDING` (a manual
   `UPDATE`, since there is no CLI flag for this — a deliberately narrow,
   rare operation) and rerun `--upload` to re-stream and re-verify them.

The metadata load (`load_award_attachments.py` without `--upload`)
retains its Sprint 1 behavior: one `TRUNCATE`-then-reload transaction, so
a failed metadata (re)load simply leaves the previous successful metadata
in place — rerun the same command after fixing the underlying problem.

## Deployment helper

`scripts/run-award-attachment-loader.sh` builds the loader image, pushes
it to ECR, registers a new task-definition revision from the existing
`research-archive-platform-dev-loader` family with that image, runs it as
a one-off Fargate task, waits for completion, streams its CloudWatch
logs, and exits with the task container's own exit code. It supports
`--file-id`, `--limit`, `--retry-failed`, `--dry-run`, `--upload`,
`--bucket`, and `--prefix` — the same flags the loader itself accepts —
translating them into the ECS `run-task --overrides` JSON via
`etl/scripts/build_award_attachment_ecs_overrides.py` (a small, pure,
independently-tested function — see `etl/tests/test_build_award_attachment_ecs_overrides.py`).

Required environment (no safe defaults — the script refuses to guess
them): `ECR_REPOSITORY_URI`, `SUBNET_IDS`, `SECURITY_GROUP_ID`. See the
script's own header comment for the full list of optional overrides
(`AWS_REGION`, `PROJECT_NAME`, `ENVIRONMENT`, `CLUSTER_NAME`,
`TASK_FAMILY`).

**This script performs real AWS actions the moment it is invoked** (image
build/push, task-definition registration, and — with `--upload` and
without `--dry-run` — a real upload run). It was authored and reviewed on
this branch but has not been executed.

## Scope confirmation

- No API, UI, presigned URLs, or download endpoints — unchanged from
  Sprint 1/2.
- Terraform is unmodified. `terraform/modules/ecs/main.tf` and
  `terraform/modules/rds/main.tf` are read as the source of truth for
  cluster/task-family/secret naming, not changed.
- `etl/Dockerfile.loader` **was** updated (not Terraform) — it previously
  only copied `load_from_s3.py`/`load_composite_from_s3.py` into the
  image, not `load_award_attachments.py` or the `oracle/` SQL directory
  it needs. Without this fix, `--ecs` execution would fail immediately
  inside the container regardless of any other Sprint 3 work.
