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

1. **Credential resolution** (`archive_etl/config/ecs.py`) — **never**
   requires a local export, and **never** accepts a plaintext
   `POSTGRES_USER`/`POSTGRES_PASSWORD`/`ORACLE_USER`/`ORACLE_PASSWORD`/
   `ORACLE_DSN` environment variable as the source of a credential.
   PostgreSQL username/password always come from the `POSTGRES_SECRET_ID`
   secret; Oracle username/password/dsn always come from the
   `ORACLE_SECRET_ID` secret (skipped entirely for `--migrate-only` — see
   below). PostgreSQL host/port/dbname come from the secret when present,
   otherwise from the plain (non-secret) `POSTGRES_HOST`/`POSTGRES_PORT`/
   `POSTGRES_DB` environment variables — those are connection routing
   info, not credentials, so a plain variable is an acceptable source for
   them specifically. A resolved host/port that points at loopback
   (`localhost`/`127.0.0.1`/`::1`, or port `15432`) is always rejected —
   `--ecs` mode connecting to a local tunnel would silently defeat the
   entire reason this mode exists. Fails with a clear `ConfigurationError`
   (or a more specific subclass — see "Secrets Manager requirements"
   below) on any failure — never silently falls through to "nothing
   configured."
2. **Structured logging** (`archive_etl/utils/structured_logging.py`) —
   replaces the default human-readable stderr handler with one JSON
   object per line on stdout, which ECS's `awslogs` log driver (already
   configured for the loader task) forwards to CloudWatch Logs verbatim.
3. **Startup validation** — read-only checks, run once, in a specific
   order, before touching any file or Oracle BLOB (see "Startup
   validation" below). Aborts immediately with a clear error on the first
   failure.

It also applies one **production default**: `--bucket` defaults to the
`AWARD_ATTACHMENT_BUCKET_NAME` environment variable when not explicitly
given. This is deliberately **not** `DATA_BUCKET_NAME` - that variable
points at a different, IRB-only bucket
(`research-archive-platform-dev-data-<account-id>`, used only by
`load_from_s3.py`/`load_composite_from_s3.py`'s Excel/Parquet export
pipeline - see `terraform/modules/s3/main.tf`'s "DATA BUCKET" comment).
Reusing `DATA_BUCKET_NAME` for the Award Attachment loader would have
silently repointed IRB's existing, working bucket resolution at the
wrong bucket, since both loaders share the same ECS task family/
container environment.

AWS credentials themselves need no special handling — running inside a
real ECS task, `boto3`'s default credential chain already picks up the
**task role's** temporary credentials automatically via the ECS container
credentials endpoint. `--ecs` mode explicitly resolves this identity via
`validate_aws_identity()` (an `sts:GetCallerIdentity` call) as the very
first startup step, before any secret is touched; there is no bespoke
credential-fetching code, and `AWS_PROFILE`/local `~/.aws` credentials are
never required or read.

```bash
# Inside the ECS loader task (or an environment providing equivalent
# ECS-shaped config):
uv run python load_award_attachments.py --ecs --upload --limit 500
```

### Bootstrapping a fresh database: `--migrate-only`

Ordinary `--ecs` execution **requires** `archive.attachment_object`/
`archive.award_attachment` and the V036 `upload_status` schema to already
exist — it never applies a migration itself, so it never silently
mutates schema during what's supposed to be an upload run. On a
brand-new RDS database (migrations V035/V036 never applied), use
`--migrate-only` once first:

```bash
uv run python load_award_attachments.py --ecs --migrate-only
```

This resolves AWS identity and the PostgreSQL secret (skipping the
Oracle secret entirely), verifies PostgreSQL connectivity, applies
pending migrations, validates the resulting schema (the same table-
existence and `upload_status` constraint checks ordinary `--ecs` runs
use), then **exits successfully without ever touching Oracle, S3, or any
attachment data**. `--migrate-only` is rejected at the argument-parsing
level if `--ecs` isn't also given.

### Secrets Manager requirements

| Credential | Environment variable | Shape | Precedence |
| --- | --- | --- | --- |
| PostgreSQL | `POSTGRES_SECRET_ID` — required for every `--ecs` invocation, including `--migrate-only` | Verified against `terraform/modules/rds/main.tf`'s `aws_secretsmanager_secret_version.database`: `{"engine", "host", "port", "dbname", "username", "password"}`. `database` is also accepted as a synonym for `dbname`. | `username`/`password` **always** come from the secret — no environment-variable fallback, ever. `host`/`port`/`dbname` come from the secret when present; if the secret omits one, it falls back to the plain `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` environment variable; if neither source has it, resolution fails clearly. |
| Oracle | `ORACLE_SECRET_ID` — required for every `--ecs` invocation **except** `--migrate-only` | **Proposed, not yet provisioned** — no Oracle secret currently exists in this repo's Terraform. Contract: `{"username": "...", "password": "...", "dsn": "..."}`, all three required. | All three fields always come from the secret — no environment-variable fallback in `--ecs` mode at all (unlike PostgreSQL's host/port/dbname, there is no non-sensitive subset of Oracle's contract). |

Distinct, catchable exception types (`archive_etl/config/ecs.py`, all
subclasses of `ConfigurationError`) distinguish *why* a secret failed to
resolve, without ever including the secret's content in the message:
`SecretNotFoundError`, `SecretAccessDeniedError`, `SecretInvalidJsonError`,
`SecretMissingKeyError`, `SecretEmptyValueError`.

**Creating the Oracle secret** (not done by this branch — for an
authorized operator to run later, once IAM permissions are in place; see
"IAM permissions" below). This avoids the password ever appearing as a
literal command-line argument, so it is not captured in shell history or
visible via `ps`:

```bash
# read -s disables terminal echo; the value only ever lives in the
# ORACLE_PASSWORD shell variable, never as a literal argument.
read -r -s -p "Oracle (KCOEUS) password: " ORACLE_PASSWORD
echo

aws secretsmanager create-secret \
  --name research-archive-platform/dev/oracle \
  --description "Oracle KCOEUS credentials for the Award Attachment loader" \
  --secret-string "$(jq -n \
      --arg username "<kcoeus-username>" \
      --arg password "$ORACLE_PASSWORD" \
      --arg dsn "<host>:1521/<service-name>" \
      '{username: $username, password: $password, dsn: $dsn}')" \
  --region us-east-1

unset ORACLE_PASSWORD
```

`jq -n` builds the JSON body without ever writing a plaintext file to
disk; `unset` clears the variable from the current shell afterward. This
command is **documentation only** — it has not been run as part of this
work, and doing so is explicitly out of scope for this commit.

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

Runs once, at the start of every `--ecs` invocation, entirely read-only
(no writes anywhere — always safe under `--dry-run` too), in this exact
order (`load_award_attachments.py`'s `_run_ecs_setup()`):

1. Configure structured logging
2. Resolve ECS task-role identity via STS (`validate_aws_identity()`)
3. Create **one** Secrets Manager client for the whole startup
4. Load the PostgreSQL secret (always)
5. Load the Oracle secret (**skipped** for `--migrate-only`)
6. Verify PostgreSQL connectivity (`SELECT 1`)
7. **If `--migrate-only`:** apply migrations, validate the resulting
   schema (steps 10–11, below, run here instead), then exit successfully
   — steps 8–9 and any file/upload processing never run
8. Verify Oracle connectivity (`SELECT 1 FROM DUAL`)
9. Verify S3 bucket access (`HEAD` — skipped, not failed, if no bucket is
   configured at all, e.g. a metadata-only `--ecs` run with no
   `--bucket`/`AWARD_ATTACHMENT_BUCKET_NAME`)
10. Verify `archive.attachment_object` and `archive.award_attachment`
    tables exist
11. Verify `archive.attachment_object.upload_status`'s CHECK constraint
    (`ck_attachment_object_upload_status`) matches the migration V036
    contract (`PENDING`, `UPLOADING`, `UPLOADED`, `FAILED`,
    `MISSING_SOURCE_CONTENT`)
12. Only then does `main()` proceed to metadata reading, BLOB reading, or
    upload

Each check raises a clear `StartupValidationError` (or `ConfigurationError`
for credential-resolution failures) identifying exactly what failed; the
loader aborts immediately — no upload or BLOB read can begin before every
required check for the requested mode passes. Ordinary `--ecs` execution
(without `--migrate-only`) still requires migrations to already be
applied; it never applies them itself, so an upload run never silently
mutates schema.

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
`--migrate-only`, `--bucket`, and `--prefix` — the same flags the loader
itself accepts — translating them into the ECS `run-task --overrides`
JSON via `etl/scripts/build_award_attachment_ecs_overrides.py` (a small,
pure, independently-tested function — see
`etl/tests/test_build_award_attachment_ecs_overrides.py`).

The script also passes `POSTGRES_SECRET_ID`/`ORACLE_SECRET_ID` (and,
optionally, `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB`/
`AWARD_ATTACHMENT_BUCKET_NAME`/`AWS_REGION`) through as **non-secret**
container environment overrides — identifiers and connection routing info
only. There is no flag, environment variable, or code path anywhere in
the script or the override builder that accepts a password, a DSN, or a
secret's JSON content; the loader resolves those itself, at runtime, from
Secrets Manager.

Required environment (no safe defaults — the script refuses to guess
them): `ECR_REPOSITORY_URI`, `SUBNET_IDS`, `SECURITY_GROUP_ID`,
`POSTGRES_SECRET_ID`. `ORACLE_SECRET_ID` is required unless
`--migrate-only` is passed. See the script's own header comment for the
full list of optional overrides (`AWS_REGION`, `PROJECT_NAME`,
`ENVIRONMENT`, `CLUSTER_NAME`, `TASK_FAMILY`, `POSTGRES_HOST`/`PORT`/`DB`,
`AWARD_ATTACHMENT_BUCKET_NAME`).

Example — bootstrap a fresh database:
```bash
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz \
  scripts/run-award-attachment-loader.sh --migrate-only
```
which generates the container command
`load_award_attachments.py --ecs --migrate-only`.

**This script performs real AWS actions the moment it is invoked** (image
build/push, task-definition registration, and — with `--upload` and
without `--dry-run` — a real upload run). It was authored and reviewed on
this branch but has not been executed.

## IAM permissions (implemented in Terraform - not yet applied)

The application code inside the loader container runs as the **task
role** (`aws_iam_role.task` in `terraform/modules/ecs/main.tf`), not the
execution role — every `boto3` call this loader makes (Secrets Manager,
S3, STS) is authorized by the task role's policy. The **execution role**
(`aws_iam_role.execution`) is used only by the ECS agent itself: pulling
the container image and (for the *existing* IRB loader path only)
resolving its own `secrets` block before the container starts. No
application-level Secrets Manager or S3 call depends on the execution
role's permissions.

The task role previously had **no** `secretsmanager:GetSecretValue`
permission at all, and lacked the S3 multipart-upload actions Sprint 2's
large-file path needs (confirmed by the implementation audit). Both are
now defined in `terraform/modules/ecs/main.tf` — `aws_iam_role_policy.
task_secrets` and `aws_iam_role_policy.task_documents_s3` — as two new,
separate policies (kept apart from the pre-existing `task_s3` policy,
which is untouched and still covers the unrelated, IRB-only data
bucket):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadRequiredSecrets",
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": [
        "arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz",
        "<research-archive-platform/dev/oracle secret ARN - known only after Terraform creates it>"
      ]
    },
    {
      "Sid": "ListDocumentsBucketForAwardAttachments",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:ListBucketMultipartUploads"
      ],
      "Resource": "arn:aws:s3:::research-archive-platform-dev-documents-770203350335",
      "Condition": {
        "StringLike": { "s3:prefix": "award-files/by-file-id/*" }
      }
    },
    {
      "Sid": "UploadAwardAttachmentObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": "arn:aws:s3:::research-archive-platform-dev-documents-770203350335/award-files/by-file-id/*"
    }
  ]
}
```

Notes:
- Exactly two named secret ARNs — never `secretsmanager:*` or a wildcard
  resource. Both policies are scoped to the task role only.
- `s3:HeadBucket` (this loader's bucket-exists check) is, per AWS's own
  API reference, authorized via the `s3:ListBucket` action — there is no
  separate `s3:HeadBucket` IAM action to grant. The `s3:prefix` condition
  constrains what a `ListBucket`/`ListBucketMultipartUploads` call is
  allowed to *return*, not the bucket-level resource ARN itself (S3's
  bucket-level actions are inherently whole-bucket in `Resource`; the
  condition is the mechanism for narrowing what they can see).
- `s3:GetObject`/`s3:HeadObject` are **not** granted — this loader never
  reads back an uploaded object today. Add them, scoped the same way,
  only if a future verification step requires it.
- `s3:ListBucketMultipartUploads` is inherently bucket-level (no
  object-level equivalent) — the one genuinely unavoidable
  whole-bucket-ARN scope, still restricted to this one bucket, never `*`,
  and still narrowed by the same prefix condition.
- The upload key prefix (`award-files/by-file-id/*`) matches
  `DEFAULT_S3_KEY_PREFIX` in `load_award_attachments.py`; if `--prefix` is
  ever overridden to something outside that path, this policy's resource
  scope needs widening accordingly.
- The pre-existing `task_s3` policy (IRB's data bucket:
  `s3:ListBucket`/`s3:GetObject`/`s3:PutObject`) is completely unchanged.

Execution role — **no change made or needed**: retains its existing
`AmazonECSTaskExecutionRolePolicy` (ECR pull + CloudWatch Logs
`CreateLogStream`/`PutLogEvents`/`CreateLogGroup`) and its existing
`secretsmanager:GetSecretValue` grant on the Postgres secret (used only
for the *existing* IRB loader path's `secrets`-block resolution, not by
this loader's own direct Secrets Manager calls).

`sts:GetCallerIdentity` (used by `validate_aws_identity()`) requires
**no IAM policy grant at all** — it's usable by any valid AWS identity
with zero prior permissions, by design.

Task-definition environment additions (`terraform/modules/ecs/main.tf`'s
`environment` block; the pre-existing `secrets` block, which already
resolves `POSTGRES_HOST/PORT/DB/USER/PASSWORD` as plain environment
variables from the Postgres secret before the container starts, is
completely unchanged — adding the same variable names again via
`environment` would be an invalid/undefined duplicate):

| Variable | Source | Purpose |
| --- | --- | --- |
| `POSTGRES_SECRET_ID` | `var.database_secret_arn` (existing) | ARN of the PostgreSQL secret, for this loader's own direct Secrets Manager call |
| `ORACLE_SECRET_ID` | `var.oracle_secret_arn` (new — `aws_secretsmanager_secret.oracle.arn`) | ARN of the Oracle secret |
| `AWARD_ATTACHMENT_BUCKET_NAME` | `var.documents_bucket_name` (new) | Documents bucket name — deliberately not `DATA_BUCKET_NAME` (see "ECS execution" above) |
| `DATA_BUCKET_NAME` | Unchanged | Still the IRB-only data bucket, for `load_from_s3.py` |
| `AWS_REGION` | Unchanged | The SDK's own region resolution applies regardless |

`POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` were **not** added as new
plain `environment` entries — they're already provided as plain
environment variables via the existing `secrets` block above, and the
loader's `resolve_postgres_credentials()` already falls back to them
(under those exact names) if the secret itself ever lacked host/port/
dbname, which it doesn't today.

## Scope confirmation

- No API, UI, presigned URLs, or download endpoints — unchanged from
  Sprint 1/2.
- Terraform **has now been modified** (`terraform/modules/ecs/`,
  `terraform/environments/dev/main.tf`/`variables.tf`/`outputs.tf`) to
  create the Oracle secret container, grant the task role the IAM
  permissions above, and add the new task-definition environment
  variables — but `terraform apply` has not been run. `terraform plan`
  was run and reviewed (see the branch's commit history for the exact
  plan summary); the Oracle secret's value is never in Terraform state
  or source code, only its container/ARN.
- `etl/Dockerfile.loader` was also updated (separately, earlier) — it
  previously only copied `load_from_s3.py`/`load_composite_from_s3.py`
  into the image, not `load_award_attachments.py` or the `oracle/` SQL
  directory it needs. Without that fix, `--ecs` execution would fail
  immediately inside the container regardless of any other work here.
- No secret value was created, no live migration was applied, and no ECS
  task was built, pushed, registered, or launched as part of this work.
