# Subaward Nightly Sync

How the Subaward archive's business data (`archive.subaward` and its nine
child tables — amounts, contacts, custom data, funding, attachments,
closeout, reports, notepad, notifications, template info) stays
up to date with Kuali Oracle staging on an ongoing basis, unattended.

## Summary

- **What runs**: `etl/load_subawards_from_csv.py --ecs --sync-all`, as a
  detached, one-off ECS Fargate task (never a persistent service).
- **When**: 2:00 AM America/New_York, every night, via an EventBridge
  Scheduler schedule (`research-archive-platform-dev-subaward-nightly`,
  Terraform: `terraform/modules/subaward_sync_schedule/`).
- **What it does**: reads every `SUBAWARD_CODE` family from Oracle and
  UPSERTs it into Postgres — inserts new families/versions, updates
  changed source metadata, **never truncates, never deletes archive rows
  merely because they disappeared from Oracle**, and never touches
  `archive.subaward_attachment_archive` (binaries are a separate pipeline
  — see [ATTACHMENT_ARCHITECTURE.md](../ATTACHMENT_ARCHITECTURE.md)).
- **Where it must never depend on**: a Mac, CloudShell, VS Code session,
  terminal, EC2 instance, or local PostgreSQL. Oracle staging is reached
  from the ECS task's own VPC peering path
  ([ORACLE_STAGING_CONNECTIVITY.md](../ORACLE_STAGING_CONNECTIVITY.md)) —
  entirely independent of whether any particular operator's Mac has BU
  VPN connected.

## Terminology

`SUBAWARD.SUBAWARD_CODE` is the Subaward business identifier (a family —
every version/row sharing a code is the same real-world Subaward across
its history). There is no "Subaward number" business field anywhere in
Kuali Subaward; never use that phrase in code, docs, or UI text.
`SUBAWARD_ID` identifies one exact physical version/row and must never be
confused with `SUBAWARD_CODE`. See
[Archived File Finder Phase 3](../architecture/RESEARCH_OBJECT_MODEL.md)
(when written) for how this distinction surfaces in the UI/API layer —
this document covers only the data-sync layer.

## CLI operations (`etl/load_subawards_from_csv.py`)

There is **no default/no-verb behavior** — every invocation requires one
explicit operation flag, enforced by `parse_args()`:

| Flag | What it does | Writes? |
|---|---|---|
| `--sync-all` | Every Oracle family, idempotent UPSERT, advisory-lock guarded | Yes (UPSERT only) |
| `--reconcile-only` | Oracle-vs-`archive.subaward` `SUBAWARD_CODE` comparison | No |
| `--load-subaward-code CODE` (repeatable) | One or more specific families | Yes (UPSERT only) |
| `--load-subaward-id ID` (repeatable) | Resolves to family, then as above | Yes (UPSERT only) |
| `--max-families N` | First N families, ascending by code | Yes (UPSERT only) |
| `--limit N` (alone) | Bounded read/validate dry run, no selector | No |
| `--full-refresh` | **Destructive**: `TRUNCATE` + full reload | Yes (destructive) |

`--full-refresh` must be given explicitly — there is no way to reach the
`TRUNCATE` path by omission. It is never used by the nightly schedule.

### `--sync-all` safety properties

- **Never truncates.** Reuses the exact same per-family transaction/UPSERT
  machinery as `--load-subaward-code` (`run_targeted_load`), just applied
  to every family Oracle has.
- **Advisory-lock guarded.** Acquires `pg_try_advisory_lock` on a fixed
  key (`SUBAWARD_SYNC_ADVISORY_LOCK_KEY` in the loader source) before
  starting; a second concurrent invocation (e.g. a manual run overlapping
  the nightly schedule) sees the lock held, logs a warning, and exits 0
  having done no work at all — it does not queue, retry, or race.
- **Per-family isolation.** One family's row failing at the database level
  (e.g. a genuine constraint violation) fails only that family's own
  transaction; every other family still loads. Family failures are logged
  (`Subaward family {code} failed to load: ...`) and counted.
- **RDS-only rows are a warning, never a deletion.** If a `SUBAWARD_CODE`
  exists in `archive.subaward` but no longer appears in Oracle (removed
  upstream, or Oracle staging reset), `--sync-all` logs it under
  `rds_only` and leaves it untouched.
- **Exit code is the source of truth for automation.** Exits nonzero if
  any family failed, or if the post-sync reconciliation still finds
  Oracle codes missing from the archive. A clean exit 0 means every
  family loaded and reconciliation found zero `oracle_only` gaps.

### `--reconcile-only`

Pure read comparison — computes the Oracle `SUBAWARD_CODE` set and the
`archive.subaward` `SUBAWARD_CODE` set, reports both directions
(`oracle_only`, `rds_only`), and exits nonzero only if `oracle_only` is
non-empty. Use this to check sync health without touching data, from
either a manual ECS run (see below) or ad hoc.

## Running manually

Always from the established ECS one-off pattern — never a local Mac
Postgres/Oracle connection (see the main
[`CLAUDE.md`](../../CLAUDE.md) "Authoritative data location" section).

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity   # confirm account 770203350335 first

export ECR_REPOSITORY_URI=770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader
export SUBNET_IDS=subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed
export SECURITY_GROUP_ID=sg-0817befcc5b4affc9

# Read-only health check - safe to run any time
scripts/run-subaward-loader.sh --reconcile-only

# Full unattended sync - same thing the nightly schedule runs
scripts/run-subaward-loader.sh --sync-all
```

`scripts/run-subaward-loader.sh` builds/pushes a fresh loader image from
the current worktree by default; pass `--image-uri <uri>` to reuse an
already-pushed image instead (faster, and guarantees you're running
exactly what's already deployed rather than an uncommitted local change).

## Inspecting a run

```bash
# Task status and exit code
aws ecs describe-tasks --cluster research-archive-platform-dev-etl \
  --tasks <task-arn> --region us-east-1 \
  --query 'tasks[0].{status:lastStatus,exitCode:containers[0].exitCode}'

# Logs (loader/loader/<task-id> is the stream name convention)
aws logs tail /ecs/research-archive-platform-dev-loader \
  --log-stream-names loader/loader/<task-id> --region us-east-1 --since 1d
```

Look for `--sync-all: N Subaward families in Oracle` (run started),
`TOTAL <table> inserted=... updated=... unchanged=...` (per-table result),
`Targeted Subaward load finished: ... failed` (family-level summary), and
`Reconciliation: oracle=... rds=... oracle_only=... rds_only=...` (final
health check). A run that "did not fully converge" logs that exact phrase
before exiting nonzero.

## Monitoring

Terraform (`terraform/modules/subaward_sync_schedule/`) creates:

- **SNS topic** `research-archive-platform-dev-subaward-sync-alerts` — has
  no subscription by default. Subscribe an operator email/Slack endpoint
  out of band (console: SNS → this topic → Create subscription), since
  Terraform has no way to know the right destination.
- **CloudWatch alarms**, all publishing to that topic:
  - `*-subaward-sync-family-failures` — any individual family failed
  - `*-subaward-sync-task-failures` — `--sync-all` exited nonzero
  - `*-subaward-sync-stale` — no `--sync-all` attempt observed in the logs
    for 2 days (catches a scheduler misconfiguration or ECS launch
    failure that the other two alarms can't see, since those require the
    loader to have actually run and logged something)

## Disabling the schedule safely

```bash
aws scheduler update-schedule \
  --name research-archive-platform-dev-subaward-nightly \
  --group-name default \
  --state DISABLED \
  --region us-east-1 --profile bu-nprd \
  --schedule-expression "cron(0 2 * * ? *)" \
  --flexible-time-window '{"Mode":"OFF"}' \
  --target '<current target JSON - fetch via get-schedule first>'
```

`update-schedule` requires the full schedule definition, not just the
changed field — fetch the current definition with `aws scheduler
get-schedule` first, change only `state`, and pass the rest back
unmodified. (Simpler in practice: toggle "Enabled"/"Disabled" via the
EventBridge Scheduler console.) Disabling the schedule does not delete
it, does not affect any in-flight task, and does not require a Terraform
apply — re-enable the same way.

## What this does *not* do

- Does not load or touch `archive.subaward_attachment_archive` (binaries)
  at all — that table isn't even in the loader's `DATASETS` tuple. See
  [ATTACHMENT_ARCHITECTURE.md](../ATTACHMENT_ARCHITECTURE.md) for the
  separate attachment-binary pipeline.
- Does not reload S3 objects, checksums, or archive statuses.
- Does not require, use, or benefit from a local SSM tunnel, CloudShell
  session, or any Mac-side BU VPN connection — only the *manual*
  `kc_staging_query.py`-style ad hoc diagnostic queries from an operator's
  own Mac need BU VPN; the nightly ECS task's Oracle access is a
  completely separate, already-provisioned VPC peering path.
- Does not run migrations beyond what `apply_migrations` already applies
  idempotently as part of every loader invocation (schema changes still
  ship via `database/migrations/`, applied by the ETL, never by Spring
  Boot — see the main `CLAUDE.md`).
