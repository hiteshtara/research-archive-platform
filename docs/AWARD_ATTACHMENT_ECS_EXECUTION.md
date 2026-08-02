# Award Attachment Loader: ECS Production Execution

Documents the Award Attachment loader's production execution model
(`etl/load_award_attachments.py --ecs`), built on branch
`feature/award-attachment-s3-loader`. Covers local development, ECS
execution, one-file validation, batch execution (both plain `--limit` and
the deterministic `--create-batch`/`--load-batch`/`--show-batch`/
`--upload --batch-id` workflow built on the generic ETL batch framework —
see [`docs/architecture/ETL_BATCH_FRAMEWORK.md`](architecture/ETL_BATCH_FRAMEWORK.md)), recovery
after interruption, rollback, Secrets Manager requirements, and
CloudWatch logs.

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
   `ORACLE_SECRET_ID` secret (skipped entirely for `--migrate-only` and
   `--show-upload-status` — see below). PostgreSQL host/port/dbname come
   from the secret when present,
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

### Inspecting PostgreSQL upload state: `--show-upload-status`

There is no SSM tunnel or bastion host for the private RDS instance (see
"Why `--ecs` exists" above) — the only way to inspect a specific file's
upload state today is from inside the ECS task itself. `--show-upload-status`
(requires `--file-id`) is a read-only diagnostic for exactly that:

```bash
uv run python load_award_attachments.py --ecs --show-upload-status --file-id 1
```

This resolves AWS identity and the PostgreSQL secret (skipping the
Oracle secret entirely, exactly like `--migrate-only`), verifies
PostgreSQL connectivity, runs a single `SELECT` against
`archive.attachment_object` for the exact given `file_id`, logs
`file_id`, `file_name`, `blob_source`, `upload_status`,
`upload_attempts`, `s3_bucket`, `s3_key`, `uploaded_at`, and
`last_error` (redacted, same as everywhere else in this loader), then
**exits successfully (0)** — including when no row exists for that
`file_id`, which is logged clearly as "metadata has not been loaded for
this file_id" rather than treated as an error. Never reads a BLOB
(`archive.attachment_object` has no BLOB column at all — source content
lives only in Oracle), never writes to PostgreSQL, never touches S3.
`--show-upload-status` is rejected at the argument-parsing level if
`--ecs` isn't also given, or if `--file-id` isn't given.

### Finding Awards with attachments: `--list-awards-with-attachments`

Same motivation as `--show-upload-status` above — no bastion host exists
for the private RDS instance, so this is how you find a real `award_id`
worth opening in the UI's Attachments tab without one. Developer aid,
not a production feature:

```bash
uv run python load_award_attachments.py --ecs --list-awards-with-attachments --limit 25
```

or, as a one-off ECS task using the existing loader task definition:

```bash
POSTGRES_SECRET_ID=arn:...:postgres \
  scripts/run-award-attachment-loader.sh --list-awards-with-attachments --limit 25
```

Resolves AWS identity and the PostgreSQL secret only (skipping the
Oracle secret entirely, exactly like `--show-upload-status`/
`--show-batch`), verifies PostgreSQL connectivity, prints every Award
version (`award_number`, `award_id`, `title`) with at least one
`archive.award_attachment` row and its attachment count, sorted
highest-count first, then **exits successfully (0)**. Never reads a
BLOB, never writes to PostgreSQL, never touches S3.

### Bounded single-file metadata load: `--load-file-id`

**The bug this fixes**: Oracle can contain `FILE_ID=1` while
`archive.attachment_object` has no row for it at all (a fresh database,
or a file added to Oracle after the last full load). In that state,
`--upload --file-id 1` correctly selects **zero** candidates — there is
nothing in PostgreSQL to select, since `--upload` only ever picks from
rows that already exist. `--load-file-id` closes that gap: a bounded,
idempotent UPSERT for exactly one physical `file_id` (and its
`award_attachment` reference rows only), so a subsequent `--upload
--file-id` has something to find.

```bash
uv run python load_award_attachments.py --ecs --load-file-id 1
```

Unlike the full metadata load (which `TRUNCATE`s both tables and bulk
`COPY`s everything), `--load-file-id`:
- **Never truncates or replaces the full tables** — every other file's
  row is left completely untouched.
- **UPSERTs**, not inserts — safe to run against a database that
  already has other rows loaded, and safe to re-run against the same
  `file_id` repeatedly.
- **Preserves an existing row's upload state** — `upload_status`,
  `upload_attempts`, `last_error`, `sha256`, `s3_bucket`, `s3_key`,
  `s3_etag`, and `uploaded_at` are never touched by the `UPDATE` branch
  of the UPSERT if the row already exists (whatever a prior `--upload`
  run recorded stays exactly as it was). Only a brand-new row gets those
  columns' normal defaults (`PENDING`/`MISSING_SOURCE_CONTENT`, zero
  attempts, no S3 state yet — exactly what `prepare_files()` already
  computes for a fresh load).
- **Never reads a BLOB** — the same physical-file and reference Oracle
  queries the full load already uses, neither of which selects a blob
  column.
- **Never uploads to S3** — `--load-file-id` takes priority over
  `--upload`/`--file-id`/`--limit` in `main()`'s dispatch if more than
  one is given, so this guarantee holds even if they're combined by
  mistake.
- **Also reconciles reference rows for that file_id only** — every
  `archive.award_attachment` row Oracle currently has for this
  `file_id` is UPSERTed too (every column refreshed on conflict, since
  that table carries no loader-owned mutable state the way
  `attachment_object` does).

Logs `inserted`/`updated`/`unchanged`/`missing` counts (aggregated
across the file row and its reference rows): `missing=1` means the
`file_id` wasn't found in Oracle at all (nothing else is attempted in
that case); otherwise `missing=0` and the file row plus each reference
row are each counted as exactly one of inserted/updated/unchanged.

Combine with `--dry-run` to see accurate counts without persisting
anything — the UPSERT actually runs (so the insert/update/unchanged
classification is genuine, not simulated), but the transaction is
rolled back instead of committed, including its own `load_run` audit
row.

`--load-file-id` does **not** require `--ecs` — like `--upload` and the
ordinary metadata load, it works in local dev too (against a local
Postgres tunnel and Oracle VPN access). In `--ecs` mode it relies on
`_run_ecs_setup`'s normal startup validation (both PostgreSQL and
Oracle connectivity, tables already existing) exactly like `--upload`
does; outside `--ecs`, it applies pending migrations first, exactly
like the ordinary metadata load does.

### Secrets Manager requirements

| Credential | Environment variable | Shape | Precedence |
| --- | --- | --- | --- |
| PostgreSQL | `POSTGRES_SECRET_ID` — required for every `--ecs` invocation, including `--migrate-only` and `--show-upload-status` | Verified against `terraform/modules/rds/main.tf`'s `aws_secretsmanager_secret_version.database`: `{"engine", "host", "port", "dbname", "username", "password"}`. `database` is also accepted as a synonym for `dbname`. | `username`/`password` **always** come from the secret — no environment-variable fallback, ever. `host`/`port`/`dbname` come from the secret when present; if the secret omits one, it falls back to the plain `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` environment variable; if neither source has it, resolution fails clearly. |
| Oracle | `ORACLE_SECRET_ID` — required for every `--ecs` invocation **except** `--migrate-only` and `--show-upload-status` | **Container provisioned** — `aws_secretsmanager_secret.oracle` (`terraform/environments/dev/main.tf`) has been applied; the live loader task definition's `ORACLE_SECRET_ID` resolves to a real ARN (`research-archive-platform/dev/oracle-ECgann`). Terraform never creates an `aws_secretsmanager_secret_version`, so the *value* is not necessarily populated yet — confirm with an authorized operator before relying on it. Contract: `{"username": "...", "password": "...", "dsn": "..."}`, all three required. | All three fields always come from the secret — no environment-variable fallback in `--ecs` mode at all (unlike PostgreSQL's host/port/dbname, there is no non-sensitive subset of Oracle's contract). |

Distinct, catchable exception types (`archive_etl/config/ecs.py`, all
subclasses of `ConfigurationError`) distinguish *why* a secret failed to
resolve, without ever including the secret's content in the message:
`SecretNotFoundError`, `SecretAccessDeniedError`, `SecretInvalidJsonError`,
`SecretMissingKeyError`, `SecretEmptyValueError`.

**Populating the Oracle secret's value** — Terraform has already created
the empty secret container (`aws_secretsmanager_secret.oracle`); an
authorized operator still needs to populate its value. Since the
container already exists, use `put-secret-value`, **not**
`create-secret` (which would fail against an existing secret name). This
avoids the password ever appearing as a literal command-line argument,
so it is not captured in shell history or visible via `ps`:

```bash
# read -s disables terminal echo; the value only ever lives in the
# ORACLE_PASSWORD shell variable, never as a literal argument.
read -r -s -p "Oracle (KCOEUS) password: " ORACLE_PASSWORD
echo

aws secretsmanager put-secret-value \
  --secret-id research-archive-platform/dev/oracle \
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
work, and doing so is explicitly out of scope for this repo's automation.

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
5. Load the Oracle secret (**skipped** for `--migrate-only` and
   `--show-upload-status`)
6. Verify PostgreSQL connectivity (`SELECT 1`)
7. **If `--migrate-only`:** apply migrations, validate the resulting
   schema (steps 10–11, below, run here instead), then exit successfully
   — steps 8–9 and any file/upload processing never run
7a. **If `--show-upload-status`:** run the read-only diagnostic `SELECT`
    against `archive.attachment_object` for the given `--file-id`, then
    exit successfully — same as `--migrate-only`, steps 8–9 and any
    file/upload processing never run
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

### Why plain `--limit` is not sufficient for a controlled run

`--limit` is **not a persisted selection** on either side of this loader:

- On the metadata-load side, `--limit` bounds an Oracle scan re-evaluated
  fresh on every invocation.
- On the upload side, `--limit`/`select_upload_candidates` is a live,
  unpersisted `WHERE upload_status = ANY(...) ORDER BY file_id LIMIT`
  query, also re-evaluated fresh on every invocation.

These are two different data sources with no relationship to each other,
so there is no guarantee the same N files are used for metadata loading
and upload — and no guarantee two separate `--upload --limit N`
invocations even select the same N files as each other, if any row's
`upload_status` changed in between. For a controlled, auditable run (e.g.
validating exactly 10 files end to end) you need a **batch**: a durable,
immutable manifest of exactly which physical files are in scope, built on
the generic ETL batch framework (`archive.etl_batch`/`etl_batch_item` —
see [`docs/architecture/ETL_BATCH_FRAMEWORK.md`](architecture/ETL_BATCH_FRAMEWORK.md)).

### Deterministic batch workflow

Four new `--ecs` subcommands, all under the same
`load_award_attachments.py` / `python -m archive_etl award-attachment`
entrypoint used by everything else in this document:

| Command | Requires Oracle? | Requires S3? | Writes? |
|---|---|---|---|
| `--create-batch N [--include-already-uploaded]` | yes | no | PostgreSQL only |
| `--load-batch BATCH_ID` | yes | no | PostgreSQL only |
| `--show-batch BATCH_ID` | **no** (PostgreSQL-only, like `--migrate-only`/`--show-upload-status`) | no | none (read-only) |
| `--upload --batch-id BATCH_ID` | yes | yes | PostgreSQL + S3 |

`--create-batch`/`--load-batch`/`--show-batch` are mutually exclusive
with each other and with `--upload`/`--file-id`/`--load-file-id`;
`--batch-id` is only valid together with `--upload`, and is itself
mutually exclusive with `--file-id`/`--load-file-id`/any of the three
batch verbs. Get any of these combinations wrong and both the loader's
own `parse_args` and `scripts/run-award-attachment-loader.sh` reject it
immediately, before touching Oracle, PostgreSQL, S3, or (via the
deployment helper) Docker/ECS at all.

**The exact controlled 10-file sequence:**

```bash
# 1. Create a deterministic batch of exactly 10 distinct physical files.
#    Selects in stable ascending file_id order, excludes already-UPLOADED
#    files by default, and persists membership immediately - this is the
#    only step where "which 10 files" is decided; every later step
#    operates on exactly this membership, never a fresh sample.
scripts/run-award-attachment-loader.sh --create-batch 10
#    -> logs "Created batch_id=<N> requested_size=10 selected=10 file_ids=[...]"
#    Record <N> - every later command needs it.

# 2. Show the freshly created batch - confirms membership was persisted
#    and nothing has happened to it yet (status=CREATED, all counts zero).
scripts/run-award-attachment-loader.sh --show-batch <N>

# 3. Load metadata for exactly this batch's 10 file_ids (and their
#    award_attachment reference rows) - never touches the other ~138k
#    rows, never reads a BLOB, never touches S3.
scripts/run-award-attachment-loader.sh --load-batch <N>
#    -> logs inserted/updated/unchanged/missing_in_oracle counts.

# 4. Show the batch again - status is now READY, metadata_loaded=10 (or
#    fewer, if any file_id wasn't found in Oracle - reported as
#    missing_metadata, not silently dropped).
scripts/run-award-attachment-loader.sh --show-batch <N>

# 5. Upload exactly this batch's 10 files to S3.
scripts/run-award-attachment-loader.sh --upload --batch-id <N> \
  --bucket research-archive-platform-dev-documents-770203350335

# 6. Show the batch again - status is now COMPLETED, with an
#    uploaded/failed/missing_source_content breakdown for exactly these
#    10 files.
scripts/run-award-attachment-loader.sh --show-batch <N>

# 7. Verify the S3 objects directly (out of scope for the loader itself,
#    which never reads back what it wrote):
aws s3 ls s3://research-archive-platform-dev-documents-770203350335/award-files/by-file-id/ --recursive | grep -F "<file_id>"

# 8. Rerun the exact same upload command to prove idempotency - every
#    file is already UPLOADED with a matching bucket/key, so all 10 are
#    reported as skipped_already_uploaded, none are re-uploaded, no
#    duplicate S3 objects are created.
scripts/run-award-attachment-loader.sh --upload --batch-id <N> \
  --bucket research-archive-platform-dev-documents-770203350335

# 9. Show the final batch status one more time to confirm it is stable
#    across the rerun (same status, same counts as step 6).
scripts/run-award-attachment-loader.sh --show-batch <N>
```

**How resume works:** if step 5 is interrupted partway through (task
killed, crash, timeout), each file's status transition
(`UPLOADING → UPLOADED`/`FAILED`) is its own immediately-committed
transaction — re-running the exact same `--upload --batch-id <N>`
command picks up only the files still `PENDING`/`UPLOADING` in that
batch; already-`UPLOADED` files are skipped automatically.

**How failed rows are retried:** add `--retry-failed` to the same
`--upload --batch-id <N>` command — only that batch's `FAILED` rows
(plus any still-`PENDING`) are retried; files outside the batch are never
touched.

**How already-uploaded rows behave:** a batch member whose
`upload_status` is already `UPLOADED` with a matching bucket/key is
reported as `skipped_already_uploaded` and never re-streamed from
Oracle — this is what makes step 8 idempotent.

**How missing Oracle files are reported:** `--show-batch` reports
`missing_metadata` (batch members with no `attachment_object` row at
all — Oracle didn't return them at `--load-batch` time) separately from
`missing_source_content` (members that *were* loaded, but have no BLOB
in either `ATTACHMENT_FILE` or `FILE_DATA` to upload). Neither is
silently dropped from the count.

**How to inspect a batch safely:** `--show-batch BATCH_ID` is read-only
(a single `SELECT`, no transaction) and — uniquely among the four batch
commands — needs no `ORACLE_SECRET_ID` at all, so it's safe to run
repeatedly at any point in the workflow, including while an upload is
still in progress from another invocation.

### Long-running bulk backfills: `--bulk-load`

Running the 10-file sequence above by hand doesn't scale to backfilling
tens or hundreds of thousands of files - `scripts/run-award-attachment-loader.sh
--bulk-load TOTAL_FILES` automates repeated
`--create-batch`/`--load-batch`/`--show-batch` (and `--upload --batch-id`,
with `--upload`) cycles of `--bulk-batch-size` files each (default 5,000)
until `TOTAL_FILES` have been processed or Oracle's candidate pool (file_ids
not already excluded as already-UPLOADED) runs out first, whichever comes
first:

```bash
# Bulk-backfill up to 200,000 files, 5,000 per batch, metadata-load only:
scripts/run-award-attachment-loader.sh --bulk-load 200000

# Add --upload to also upload each batch to S3 immediately after loading it:
scripts/run-award-attachment-loader.sh --bulk-load 200000 --upload \
  --bucket research-archive-platform-dev-documents-770203350335
```

**The image is built and the task definition registered exactly once per
invocation** - never once per batch. This fixes a real failure mode: an
earlier ad-hoc way of doing this (calling the script once per batch in a
shell loop) rebuilt and re-pushed the Docker image on every single batch,
which is both wasteful and a real point of failure (a transient
`docker build`/registry network error aborts the whole run instead of
just one batch). `--bulk-load` builds/pushes/registers once, then every
batch's `--create-batch`/`--load-batch`/`--show-batch`/`--upload` task
reuses that same task-definition revision.

**Progress is persisted after every batch** to `--state-file` (default
`/tmp/${PROJECT_NAME}-${ENVIRONMENT}-bulk-load-state.json`) - `files
processed so far`, each batch's `batch_id`/`load_status`/`upload_status`,
and the already-registered `image_uri`/`task_definition_arn`. **Stops
immediately on the first failed batch** (exit 1), after saving state.
Re-running the identical `--bulk-load TOTAL_FILES --state-file PATH`
command resumes from that point: it reuses the recorded task definition
(skipping the build/push/register step entirely, unless `--image-uri` is
also given) and retries only the specific batch that failed - the
in-progress step (create/load/upload), never a whole-run restart, and
never a brand new batch that would lose the original selection. A state
file only resumes a run for the exact `TOTAL_FILES` it was created with;
mismatches are rejected rather than silently reinterpreted.

**Never reloads already-uploaded files**, for the same reason the 10-file
sequence above doesn't: every batch is created via plain `--create-batch`
(no `--include-already-uploaded`), which excludes already-`UPLOADED`
file_ids from selection by default.

`--bulk-load` cannot be combined with `--create-batch`/`--load-batch`/
`--show-batch`/`--file-id`/`--load-file-id`/`--load-file-ids`/`--batch-id`/
`--diff-award-attachments`/`--migrate-only`/`--show-upload-status`/
`--list-awards-with-attachments` - it owns the whole create/load/(upload)
cycle itself. Like `--create-batch`/`--load-batch`, it requires
`ORACLE_SECRET_ID`.

**How to abandon or clean up a test batch:** there is no CLI flag for
this, deliberately — batch membership is meant to be immutable once
created. If a test batch needs to be discarded, do it directly in
PostgreSQL:

```sql
DELETE FROM archive.etl_batch_item WHERE batch_id = <N>;
DELETE FROM archive.etl_batch WHERE batch_id = <N>;
```

**What not to delete manually:** never delete rows from
`archive.attachment_object`/`archive.award_attachment` to "undo" a
batch's metadata load — those tables are shared with every other loading
path (`--load-file-id`, the full metadata load, other batches), and a
row there may be relied on by data outside the batch you're cleaning up.
If a batch's *uploaded* files need to be un-uploaded, follow the
"Rollback procedure" below instead — it applies identically whether the
rows were uploaded via `--batch-id` or any other path.

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
`--file-id`, `--load-file-id`, `--limit`, `--retry-failed`, `--dry-run`,
`--upload`, `--migrate-only`, `--show-upload-status`, `--bucket`,
`--prefix`, `--create-batch`, `--include-already-uploaded`,
`--load-batch`, `--show-batch`, and `--batch-id` — the same flags the
loader itself accepts, including the same early validation for
conflicting combinations (see "Deterministic batch workflow" above) —
translating them into the ECS `run-task --overrides`
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
`--migrate-only`, `--show-upload-status`, or `--show-batch` is passed
(`--show-upload-status` also requires `--file-id`) — `--create-batch` and
`--load-batch` both read Oracle and so are **not** exempt. See the
script's own header comment for the full list of optional overrides
(`AWS_REGION`, `PROJECT_NAME`, `ENVIRONMENT`, `CLUSTER_NAME`,
`TASK_FAMILY`, `POSTGRES_HOST`/`PORT`/`DB`, `AWARD_ATTACHMENT_BUCKET_NAME`).

Example — bootstrap a fresh database:
```bash
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz \
  scripts/run-award-attachment-loader.sh --migrate-only
```
which generates the container command
`["python", "-m", "archive_etl", "award-attachment", "--ecs", "--migrate-only"]`
- the unified module CLI, never a bare `load_award_attachments.py`
filename. An ECS containerOverrides `command` replaces the container's
CMD entirely (no shell, no `uv run` wrapper to fall back on), so element
0 must already be a real executable on the image's PATH; a bare script
filename is neither executable nor found via PATH lookup, which is
exactly how an earlier version of this command failed in production
(`exec: "load_award_attachments.py": executable file not found in
$PATH`).

Example — inspect PostgreSQL upload state for one file, read-only:
```bash
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz \
  scripts/run-award-attachment-loader.sh --show-upload-status --file-id 1
```
which generates the container command
`["python", "-m", "archive_etl", "award-attachment", "--ecs", "--show-upload-status", "--file-id", "1"]`.

Example — load metadata for exactly one physical file, so a subsequent
`--upload --file-id` has something to find:
```bash
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-4k6Ngz \
ORACLE_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/oracle-ECgann \
  scripts/run-award-attachment-loader.sh --load-file-id 1
```
which generates the container command
`["python", "-m", "archive_etl", "award-attachment", "--ecs", "--load-file-id", "1"]`.

**This script performs real AWS actions the moment it is invoked** (image
build/push, task-definition registration, and — with `--upload` and
without `--dry-run` — a real upload run). It was authored and reviewed on
this branch but has not been executed.

## IAM permissions (implemented in Terraform - applied)

The application code inside the loader container runs as the **task
role** (`aws_iam_role.task` in `terraform/modules/ecs/main.tf`), not the
execution role — every `boto3` call this loader makes (Secrets Manager,
S3, STS) is authorized by the task role's policy. The **execution role**
(`aws_iam_role.execution`) is used only by the ECS agent itself, for
pulling the container image and delivering logs — it holds no
Secrets Manager or S3 permission of any kind (see "Execution role"
below).

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
        "arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/oracle-ECgann"
      ]
    },
    {
      "Sid": "ListDocumentsBucketForAwardAttachments",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::research-archive-platform-dev-documents-770203350335",
      "Condition": {
        "StringLike": {
          "s3:prefix": ["award-files/by-file-id", "award-files/by-file-id/*"]
        }
      }
    },
    {
      "Sid": "ListDocumentsBucketMultipartUploads",
      "Effect": "Allow",
      "Action": "s3:ListBucketMultipartUploads",
      "Resource": "arn:aws:s3:::research-archive-platform-dev-documents-770203350335"
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
  constrains what a `ListBucket` call is allowed to *return*, not the
  bucket-level resource ARN itself (S3's bucket-level actions are
  inherently whole-bucket in `Resource`; the condition is the mechanism
  for narrowing what they can see).
- `s3:GetObject`/`s3:HeadObject` are **not** granted — this loader never
  reads back an uploaded object today. Add them, scoped the same way,
  only if a future verification step requires it.
- `s3:ListBucketMultipartUploads` is its **own** statement, deliberately
  separate from `s3:ListBucket` above: it has no object-key equivalent
  (it lists in-progress multipart uploads for the whole bucket) and does
  not evaluate an `s3:prefix` condition the way `ListBucket`'s
  key-listing does — sharing a statement with a prefix condition would
  have made that condition a silent no-op for this action. Still
  restricted to this one bucket, never `*`.
- The upload key prefix (`award-files/by-file-id/*`, plus the bare
  `award-files/by-file-id` prefix itself) matches
  `DEFAULT_S3_KEY_PREFIX` in `load_award_attachments.py`; if `--prefix` is
  ever overridden to something outside that path, this policy's resource
  scope needs widening accordingly.
- The pre-existing `task_s3` policy (IRB's data bucket:
  `s3:ListBucket`/`s3:GetObject`/`s3:PutObject`) is completely unchanged.

Execution role — retains only its `AmazonECSTaskExecutionRolePolicy`
attachment (ECR pull + CloudWatch Logs `CreateLogStream`/
`PutLogEvents`/`CreateLogGroup`). It previously also had a
`secretsmanager:GetSecretValue` grant on the Postgres secret
(`aws_iam_role_policy.execution_secrets`), which existed only to resolve
the container's now-removed `secrets` block (see "Task-definition
environment" below) — that grant has been removed along with the block
it supported, so the execution role now has **no** Secrets Manager
access at all. Every application-level Secrets Manager/S3 call is
authorized on the task role only.

`sts:GetCallerIdentity` (used by `validate_aws_identity()`) requires
**no IAM policy grant at all** — it's usable by any valid AWS identity
with zero prior permissions, by design.

Task-definition environment (`terraform/modules/ecs/main.tf`'s
`environment` block). The container's `secrets` block — which used to
resolve `POSTGRES_HOST/PORT/DB/USER/PASSWORD` as plain environment
variables from the Postgres secret via ECS-native injection, before the
container even started — has been **removed entirely**. That mechanism
directly undermined the hardened PostgreSQL secret resolution this
loader performs itself (`resolve_postgres_credentials()`): it would have
handed the container the same credentials as plaintext env vars
regardless of what the loader's own startup validation required. The
container now carries only secret *identifiers*, never credential
values, anywhere in its environment:

| Variable | Source | Purpose |
| --- | --- | --- |
| `POSTGRES_SECRET_ID` | `var.database_secret_arn` (existing) | ARN of the PostgreSQL secret, for this loader's own direct Secrets Manager call |
| `ORACLE_SECRET_ID` | `var.oracle_secret_arn` (`aws_secretsmanager_secret.oracle.arn`) | ARN of the Oracle secret |
| `AWARD_ATTACHMENT_BUCKET_NAME` | `var.documents_bucket_name` | Documents bucket name — deliberately not `DATA_BUCKET_NAME` (see "ECS execution" above) |
| `DATA_BUCKET_NAME` | Unchanged | Still the IRB-only data bucket, for `load_from_s3.py` |
| `IRB_S3_PREFIX` | Unchanged | IRB-only, unrelated to this loader |
| `AWS_REGION` | Unchanged | The SDK's own region resolution applies regardless |

There is no `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB`/`POSTGRES_USER`/
`POSTGRES_PASSWORD` anywhere in the container definition, via either
`environment` or `secrets`. The loader's plain-variable fallback for
host/port/dbname (see the Secrets Manager requirements table above)
exists for local, non-`--ecs` development — in `--ecs` mode on this task
definition it is simply unused, since the Postgres secret always carries
`host`/`port`/`dbname` itself.

## Scope confirmation

- No API, UI, presigned URLs, or download endpoints — unchanged from
  Sprint 1/2.
- Terraform (`terraform/modules/ecs/`,
  `terraform/environments/dev/main.tf`/`variables.tf`/`outputs.tf`) has
  been modified across this branch's history to create the Oracle secret
  container, grant the task role the IAM permissions above (including
  the later split of the S3 multipart-list statement), remove the
  container's `secrets` block and the execution role's now-unneeded
  Secrets Manager grant, and add the new task-definition environment
  variables. **This has since been applied, live, outside of this
  session's own work**: the loader task definition's current revision
  (confirmed via `aws ecs describe-task-definition`) already shows
  `AWARD_ATTACHMENT_BUCKET_NAME`/`POSTGRES_SECRET_ID`/`ORACLE_SECRET_ID`
  in its environment, `secrets: null`, and a real `ORACLE_SECRET_ID` ARN
  (`research-archive-platform/dev/oracle-ECgann`), confirming the Oracle
  secret container itself now exists. Nothing in this repo's automation
  ran that apply. The Oracle secret's *value* is never created by
  Terraform (no `aws_secretsmanager_secret_version` resource exists) —
  confirm with an authorized operator whether it has been populated
  before relying on `--ecs` execution beyond `--migrate-only`.
- `etl/Dockerfile.loader` was also updated (separately, earlier) — it
  previously only copied `load_from_s3.py`/`load_composite_from_s3.py`
  into the image, not `load_award_attachments.py` or the `oracle/` SQL
  directory it needs. Without that fix, `--ecs` execution would fail
  immediately inside the container regardless of any other work here.
- No secret value, migration run, image build/push, or task launch has
  been performed as part of this repo's own scripts/automation. Whether
  any of those has happened via the same out-of-band channel that
  applied Terraform is not something this document can confirm from
  code alone.
