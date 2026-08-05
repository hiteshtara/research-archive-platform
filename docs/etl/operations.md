# How to operate the ETL

This guide covers the normal sequence for preflight checks, migrations, full
loads, reconciliation, recovery, batch loads, and ECS execution.

## Prerequisites

- Complete [getting started](getting-started.md).
- Run commands from `etl/` unless a command says otherwise.
- Confirm the target database and AWS account before any write operation.
- Know the business grain of the domain being loaded. A raw row count is not
  automatically a business-object count.

## How to run a safe full structured-data load

1. Export the correct Oracle and PostgreSQL environment variables.

2. Check connectivity and the domain's extraction path:

   ```bash
   uv run python -m archive_etl check award
   ```

3. Run the loader without `--limit`:

   ```bash
   uv run python -m archive_etl award
   ```

   Substitute `negotiation`, `subaward`, `proposal`, or `protocol` as
   required. These commands can write PostgreSQL and apply unapplied
   migrations.

4. Reconcile the latest load:

   ```bash
   uv run python scripts/reconcile_load.py --latest
   ```

5. Verify domain-specific business counts with the exact SQL appropriate to
   that domain. Do not compare unlike grains, such as Award version rows and
   distinct award numbers.

### What a full load changes

The active structured loaders use a transaction around their destructive
reload. They truncate their owned archive tables and bulk-load the new
snapshot atomically. If the transaction fails, PostgreSQL retains the
previous successful data.

The audit row in `archive.load_run` is created and committed before risky
work begins. A failed attempt therefore remains visible even when the load
transaction rolls back.

## How to apply migrations

Spring Boot does not apply migrations. ETL loaders call the migration runner
in `archive_etl/upload/migrations.py` before loading.

For the Award ECS loader, bootstrap a database without running a data load:

```bash
uv run python -m archive_etl award --ecs --migrate-only
```

Migration files must follow `VNNN__description.sql` and live in
`database/migrations/`. Applied versions are recorded in
`public.schema_migration`. Never renumber or edit an already-applied
migration; add a new migration instead.

## How to inspect and recover a failed load

List failures and their recommended rerun command:

```bash
uv run python scripts/resume_failed_load.py
uv run python scripts/resume_failed_load.py --domain AWARD
```

Inspect counts and status:

```bash
uv run python scripts/reconcile_load.py --load-id 42
uv run python scripts/reconcile_load.py --domain AWARD --limit 5
```

Fix the external cause, then rerun the same loader. There is no destructive
rollback command because the failed transaction leaves the prior successful
snapshot in place.

Common causes include expired credentials, VPN loss, source-data validation
failures, missing migrations, RDS TLS configuration, and insufficient disk
or network capacity.

## How to use a bounded diagnostic run

```bash
uv run python -m archive_etl proposal --limit 25
```

Use this to test source access and transformation. Do not use it to populate
a subset of production data: the command intentionally skips PostgreSQL.

## How to run an Award incremental load

Load one Award version family by Oracle `award_id`:

```bash
uv run python -m archive_etl award --load-award-id 123456 --dry-run
uv run python -m archive_etl award --load-award-id 123456
```

The loader resolves the selected row's `award_number` and upserts the whole
version family and its owned child rows. The dry run executes the SQL and
reports counts, then rolls the transaction back.

For a persisted selection of multiple Award IDs:

```bash
uv run python -m archive_etl award --create-batch 100
uv run python -m archive_etl award --show-batch 7
uv run python -m archive_etl award --load-batch 7 --dry-run
uv run python -m archive_etl award --load-batch 7
```

A batch stores exact membership in `archive.etl_batch` and
`archive.etl_batch_item`. See the [batch framework](../architecture/ETL_BATCH_FRAMEWORK.md)
before using `--validation-overlap` or changing status handling.

## How to run in ECS

The Award and Award Attachment loaders support `--ecs`. This mode:

- obtains database credentials from AWS Secrets Manager;
- rejects plaintext Oracle/PostgreSQL username and password fallbacks;
- validates AWS identity and downstream connectivity before processing;
- emits structured JSON logs for CloudWatch;
- rejects loopback PostgreSQL endpoints and the local tunnel port.

Generate container overrides with the provided builders instead of manually
assembling secret JSON:

```bash
uv run python scripts/build_award_ecs_overrides.py --migrate-only
uv run python scripts/build_award_ecs_overrides.py --create-batch 100
uv run python scripts/build_award_attachment_ecs_overrides.py --show-upload-status
```

The builders print task overrides; the repository's deployment scripts use
the same argument contract. Follow the
[Award attachment ECS runbook](../AWARD_ATTACHMENT_ECS_EXECUTION.md) for IAM,
secret shapes, task execution, and CloudWatch verification.

## How to archive attachment binaries

Attachment archival is separate from structured metadata loading. It streams
Oracle BLOBs through a temporary file, computes SHA-256 while streaming,
uploads to private S3, verifies object size and checksum metadata, and can
sync archive status to PostgreSQL.

Use the module-specific runbook rather than guessing source joins or S3 key
formats:

- [Subaward attachment archive](../SUBAWARD_ATTACHMENT_ARCHIVE.md)
- [Attachment module inventory](../ATTACHMENT_MODULE_INVENTORY.md)
- [Award attachment ECS execution](../AWARD_ATTACHMENT_ECS_EXECUTION.md)

Always start with a bounded or verification-only operation. Never assume
that Award, Proposal, Subaward, Negotiation, and Protocol use the same Oracle
BLOB table or identifier.

## Verification checklist

- The command exited successfully.
- `archive.load_run.status` is `LOADED` for the expected domain.
- `rows_read`, `rows_loaded`, and `rows_rejected` reconcile.
- Domain-specific validation reports have no unexplained failures.
- Business-grain counts use the documented identifier, not `COUNT(*)` by
  default.
- Attachment loads verify S3 size and SHA-256 and report manifest orphans.
- The API remains read-only and receives no Oracle credentials.

