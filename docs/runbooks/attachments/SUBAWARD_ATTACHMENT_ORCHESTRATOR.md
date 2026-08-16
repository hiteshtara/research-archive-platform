# Subaward Attachment Orchestrator — pilot-scope operator runbook

This runbook covers `etl/attachment_orchestrator.py`'s Subaward stage,
scoped by Subaward Code via `--subaward-code`, launched through
[`scripts/run-subaward-attachment-loader.sh`](../../../scripts/run-subaward-attachment-loader.sh).
It does **not** cover the older, separate local-exporter pipeline
(`fetch_subaward_attachment_metadata.py`/`archive_attachments.py`,
run from a BU-VPN-connected machine) — see
[`docs/SUBAWARD_ATTACHMENT_ARCHIVE.md`](../../SUBAWARD_ATTACHMENT_ARCHIVE.md)
for that one. No ECS-orchestrator-specific runbook existed before this
document.

## Why scoped, not full-population, first

`archive.subaward`'s own core-record population is itself only a small
fraction of Oracle's full `KCOEUS.SUBAWARD` population (see
`etl/attachment_orchestrator.py`'s own module docstring and
`docs/architecture/ARCHIVE_ATTACHMENT_LOAD_INVENTORY.md` for the exact,
point-in-time counts) — the Subaward attachment stage can only ever
surface metadata for `subaward_id`s that already have a core
`archive.subaward` row. Running the full, unscoped population before a
single pilot has ever executed against real infrastructure is
unnecessary risk for no benefit; the `--subaward-code` pilot-scope
feature (`etl/attachment_orchestrator.py`, see
`etl/tests/test_subaward_attachment_pilot_scope.py`) exists specifically
so a first real run can be bounded to one or a few known Subaward
families before ever being pointed at everything.

## The approved dev pilot fixture: Subaward Code 3595

**Subaward Code `3595` is the approved dev pilot fixture** — this is a
real, already-documented dev-environment identifier (see
`docs/project-memory/CURRENT_STATE.md`'s Subaward section), not a value
invented for this runbook. As of that document's own 2026-08-15
reconciliation: 150 metadata rows on both Oracle and Postgres (exact
match), 13 distinct physical files, 11 of those 13 files referenced by
more than one attachment row (up to 24 references for one file), and
**0 of the 150 rows have any archive row yet** — the UI correctly shows
"Not archived" for it today, which is expected, not a bug.

This fixture is specifically useful as a first real pilot because its
own physical-file-sharing pattern (11/13 files multiply-referenced) is
exactly the shape `subaward_binary_stage`'s per-physical-file S3 keying
depends on getting right (see the module's own docstring: "a file
referenced by many proposal/subaward versions is still only streamed
from Oracle and PUT to S3 once").

**Schema prerequisite, already satisfied on this branch:** `V077`
(`database/migrations/V077__widen_subaward_attachment_archive_status.sql`,
already applied at commit `51748dd`, the base of this branch) drops
`ux_subaward_attachment_archive_object` — the `UNIQUE (s3_bucket,
s3_key)` constraint that would otherwise make the orchestrator's very
first shared-`file_data_id` bulk `UPDATE` fail with `duplicate key value
violates unique constraint` (see `V077`'s own migration comment for the
full incident analysis, which used the 3595 population itself as its
worked example). **Verify `V077` has actually been applied to the
target database (`--migrate-only`, see
`docs/AWARD_ATTACHMENT_ECS_EXECUTION.md`'s pattern) before running a
real `--run` pilot against it** — per `CLAUDE.md`'s standing rule,
committing a migration is not the same as it having been applied to a
specific database.

Every example below that names a real Subaward Code uses `3595` for
exactly this reason. Test-only code (unit tests, the Docker image-layout
smoke test) uses obviously synthetic values instead (e.g.
`SYNTHETIC-SUBAWARD-A`) — never this real fixture code — so that no
test's pass/fail status is ever coupled to real dev-environment data.

## Prerequisites

- `AWS_PROFILE` set to a profile that resolves to BU account
  `770203350335` (or your target BU environment's account, via
  `EXPECTED_ACCOUNT_ID`).
- `POSTGRES_SECRET_ID` / `ORACLE_SECRET_ID` — Secrets Manager
  identifiers (not credentials), same convention as
  `scripts/run-award-attachment-loader.sh`.
- Either `--image-uri <already-pushed image>` or `--build-image`
  (requires a local Docker daemon and `ECR_REPOSITORY_URI`, or a
  resolvable `terraform output loader_ecr_repository_url`).
- Bucket/subnet/security-group: left to resolve automatically from this
  project's own Terraform outputs
  (`terraform/environments/dev`) unless you override them explicitly
  (`BUCKET_NAME` / `SUBNET_IDS` / `SECURITY_GROUP_ID`). See the
  launcher script's own header comment for the full list.

See the launcher script's header comment for the complete, current flag
and environment-variable reference — this runbook shows the operational
sequence, not a full option reference.

## Scoped dry-run (read-only preview)

Always run this before a real pilot. No PostgreSQL write, no S3 write,
no Oracle BLOB read — only the candidate scan, code resolution, and the
cross-scope safety check (see `attachment_orchestrator.plan_subaward_batch`).

```bash
AWS_PROFILE=bu-nprd \
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-XXXXXX \
ORACLE_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/oracle-XXXXXX \
  scripts/run-subaward-attachment-loader.sh --dry-run --subaward-code 3595 --image-uri <already-pushed-image-uri>
```

Review the printed plan (candidate `file_data_id` count, unresolved
codes, destination-key shape, and — critically — `cross_scope_violation`)
in the streamed CloudWatch logs before proceeding. A non-null
`cross_scope_violation` means a candidate physical file is also
referenced by a Subaward Code outside the requested scope; the launcher
exits non-zero in that case and no real run should follow until that's
understood.

## Scoped real pilot run

Only after a clean dry-run:

```bash
AWS_PROFILE=bu-nprd \
POSTGRES_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-XXXXXX \
ORACLE_SECRET_ID=arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/oracle-XXXXXX \
  scripts/run-subaward-attachment-loader.sh --run --subaward-code 3595 --image-uri <already-pushed-image-uri>
```

This runs the real orchestration (metadata load, then S3 upload) scoped
to exactly Subaward Code `3595`'s resolved `subaward_id` version(s).
Multiple codes can be piloted together in one run:

```bash
  scripts/run-subaward-attachment-loader.sh --run \
    --subaward-code 3595 --subaward-code <next-approved-code> \
    --image-uri <already-pushed-image-uri>
```

## Idempotency rerun

Re-running the **exact same** `--run --subaward-code 3595 ...` command
again (e.g. after an interruption, or simply to confirm nothing was
missed) is safe and expected to be a near-no-op:

- `attachment_orchestrator.py`'s own durable-state-first exclusion
  (`_subaward_excluded_file_data_ids`) means a `file_data_id` already
  present in `archive.subaward_attachment` is never re-selected.
- If a prior run left an incomplete batch (`archive.etl_batch`
  `status=CREATED`) for this exact `--subaward-code` scope, the same
  batch is resumed in place, not duplicated
  (`subaward_metadata_stage`'s resume path).
- If a prior run left an incomplete batch for a **different** scope,
  this launcher's own scope guard
  (`SubawardCodeScopeMismatch`) refuses to resume it and refuses to
  silently create a conflicting parallel batch — it fails loudly instead,
  naming both the requested and the existing scope, so the operator can
  resolve the existing batch first.
- Binary-stage reconciliation (`reconcile_batch`) re-verifies S3
  existence/size/checksum for everything a batch just uploaded before
  that batch is ever marked done — a rerun after a partial failure
  re-verifies rather than blindly re-uploading.

## Future explicitly approved full backfill

The full, unscoped Subaward population is **not** approved for a run
against real infrastructure as of this writing — treat the command
below as documentation of the mechanism only, not a standing
authorization:

```bash
  scripts/run-subaward-attachment-loader.sh --run --all-subawards --image-uri <already-pushed-image-uri>
```

`--all-subawards` is the only way to invoke
`attachment_orchestrator.py` without any `--subaward-code` — it cannot
be combined with `--subaward-code`, and omitting both flags is refused
outright (see the launcher's own validation and
`scripts/tests/test-subaward-attachment-loader.sh`'s "no mutation before
validation completes" tests). Before ever running this for real: confirm
the scoped pilot(s) above completed cleanly and were reconciled, and get
explicit sign-off the same way the `3595` pilot itself was approved
(see `docs/project-memory/CURRENT_STATE.md`) — this is a materially
larger operation (Oracle's full `KCOEUS.SUBAWARD_ATTACHMENTS`
population, not one Subaward family's 150 rows), and this launcher
deliberately does not make that decision easy to reach by accident.
