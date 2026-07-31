# ETL Batch Framework

A generic, domain-agnostic framework for "select exactly N entities, then
load/process exactly that membership" ETL workflows — deterministic,
resumable, and reusable across domains, instead of one bespoke batch-table
pair per domain.

## Why this exists

Neither `--limit` (bounded Oracle sampling for a metadata load) nor
`--limit`/a live candidate-selection query (for upload/processing) is a
*persisted* selection — both are re-evaluated fresh on every invocation,
over data sources with no relationship to each other. There is no
guarantee the same N entities are used across two separate invocations of
the same command, let alone across two different commands (e.g. a
metadata-load run and a later upload run). A **batch** is a durable
manifest: once created, its membership never changes, so every later
command scoped to that batch operates on the exact same entity set, and
the whole workflow (create → load → process) can be paused and resumed at
any point.

This was originally built as an Award-attachment-only pair of tables
(`attachment_load_batch`/`attachment_load_batch_file`). It was generalized
before ever being applied anywhere (no environment had it) once a second
candidate consumer — Award parent-batching — made clear that a second
bespoke batch-table pair per domain would produce two incompatible
operational models instead of one shared one.

## Schema

`database/migrations/V037__create_etl_batch_framework.sql` creates two
tables:

- **`archive.etl_batch`** — the parent manifest. Tagged with `domain`
  (e.g. `AWARD_ATTACHMENT`) and `entity_type` (e.g. `PHYSICAL_FILE`), both
  plain `VARCHAR` discriminators — matching this schema's existing
  convention (`archive.load_run.domain` is the same shape, not a
  foreign-keyed lookup table). Also carries `requested_size`,
  `selection_strategy` (a short label describing how membership was
  chosen), `selection_parameters` (`JSONB`, domain-specific selection
  options), and the usual `status`/`created_at`/`started_at`/
  `completed_at`/`created_by_run_id`/`notes`.
- **`archive.etl_batch_item`** — batch membership. One row per selected
  entity, keyed by `(batch_id, entity_key)`, with a stable `ordinal`
  (unique per batch) recording selection order.

`entity_key` is a plain `BIGINT`, not a foreign key to any domain table.
Batch creation persists membership *before* any load has upserted the
corresponding domain row — a FK here would make batch creation itself
impossible. Both domains in scope today (`award_id` for a future Award
batch, `file_id` for Award-attachment physical files) already have real
numeric surrogate primary keys, so no composite-key representation is
needed. A future domain with no numeric surrogate key is the trigger for
a domain-specific membership table alongside this one, not for weakening
`entity_key` into a fragile free-form string.

## Status ownership — the rule that matters most

`etl_batch_item.status` tracks **exactly one thing**: whether this
batch's own load/process step has run for this `entity_key`
(`PENDING → PROCESSING → COMPLETED / FAILED / MISSING_SOURCE / SKIPPED`).

It is **never** used to duplicate a domain's own downstream state. For
Award attachments, upload progress (`PENDING`/`UPLOADING`/`UPLOADED`/
`FAILED`/`MISSING_SOURCE_CONTENT`) remains solely owned by
`archive.attachment_object.upload_status`, exactly as it was before this
framework existed. Domain code scopes further processing to batch
membership by joining `etl_batch_item` to its own table on `entity_key`
(see `select_upload_candidates` in `etl/load_award_attachments.py`), never
by mirroring that table's status into `etl_batch_item`.

`etl_batch.status` is a coarser, batch-level rollup:

```
CREATED → METADATA_LOADING → READY → PROCESSING → PARTIAL / COMPLETED / FAILED / ABANDONED
```

`METADATA_LOADING`/`PARTIAL`/`FAILED`/`ABANDONED` are reserved for future
use — Award-attachment's own workflow today only ever moves through
`CREATED → READY → PROCESSING → COMPLETED`.

## The framework module

`etl/archive_etl/batch/framework.py` has no knowledge of any domain's own
tables. It provides:

- `select_distinct_ascending_from_oracle_batches(batches, *, id_column, requested_size, excluded)`
  — scans an `OracleDataSource.read_batches()`-shaped lazy iterator,
  keeping the first N distinct, non-excluded values, stopping early once
  enough are found. Always closes the iterator itself (`try`/`finally`),
  matching `OracleDataSource`'s own resource-lifecycle contract.
- `create_batch(engine, *, domain, entity_type, requested_size, selection_strategy, selected_keys, selection_parameters=None, run_id=None)`
  — persists a new batch and its already-decided membership
  transactionally. Raises `ValueError` for a non-positive
  `requested_size`.
- `load_batch_membership(connection, batch_id, *, domain, entity_type)` /
  `assert_batch_matches(...)` — returns membership in ordinal order;
  raises `RuntimeError` if the batch doesn't exist, or exists but was
  created for a *different* domain/entity_type. This domain check is new
  relative to the original attachment-only design: now that `batch_id` is
  no longer namespaced per domain by a separate table, it's the guard
  against accidentally scoping one domain's load/upload step to another
  domain's batch.
- `set_item_status`/`set_batch_status` — plain status updates, taking a
  `Connection` (not an `Engine`) so they participate in the caller's own
  transaction (e.g. a `--dry-run` metadata load's rollback must roll these
  back too).
- `begin_batch_processing`/`finish_batch_processing` — batch-level
  lifecycle transitions with `started_at`/`completed_at` timestamps.
  `started_at` is set only the first time (`COALESCE`), so a resumed run
  doesn't reset the original start time.
- `show_batch(engine, batch_id, *, domain, entity_type)` — read-only
  report: batch metadata plus the *generic* item-status breakdown
  (`pending`/`processing`/`completed`/`failed`/`missing_source`/
  `skipped`). Any domain-specific downstream breakdown (e.g. Award
  attachment's upload-status counts) is the caller's own responsibility to
  compute and merge — this function has no knowledge of
  `attachment_object` or any other domain table.

## How Award-attachment consumes it today

`etl/load_award_attachments.py` is domain glue on top of the framework:
`AWARD_ATTACHMENT_BATCH_DOMAIN = "AWARD_ATTACHMENT"`,
`AWARD_ATTACHMENT_BATCH_ENTITY_TYPE = "PHYSICAL_FILE"`.

- `--create-batch N` (`_run_create_batch`): builds the excluded-file-ids
  set (already-`UPLOADED` file_ids, unless `--include-already-uploaded`),
  scans `OracleDataSource(FILES_ORACLE_SQL).read_batches()` via
  `select_distinct_ascending_from_oracle_batches`, then calls
  `create_batch(...)`.
- `--load-batch <id>` (`_run_load_batch`): calls `load_batch_membership`,
  reads exactly those file_ids' metadata/references from Oracle, upserts
  them (unchanged from before this refactor), and calls `set_item_status`
  per file (`COMPLETED` or `MISSING_SOURCE`) and `set_batch_status`
  (`READY`) on success — all inside the same transaction, so `--dry-run`
  rolls every one of those back too.
- `--upload --batch-id <id>`: `select_upload_candidates` joins
  `etl_batch_item` to `attachment_object` on `entity_key = file_id`,
  scoped to `batch_id`; `_run_upload` calls `begin_batch_processing`
  (`PROCESSING`) before uploading and `finish_batch_processing`
  (`COMPLETED`) after, exactly like the original design, just via the
  shared framework.
- `--show-batch <id>` (`_run_show_batch`): calls the generic `show_batch`
  for the batch-shape fields, then augments the report with the
  attachment-specific upload-status breakdown via its own query.

## CLI surfaces

The four batch commands are exposed identically everywhere the loader
itself is invoked from — the flag names never change between layers:

- `etl/load_award_attachments.py`'s own `parse_args` (the source of
  truth for validation — see below).
- `python -m archive_etl award-attachment ...`
  (`etl/archive_etl/__main__.py`) — a thin forwarder; it does not
  re-validate combinations itself, since `_run_domain` always calls
  through to the real `load_award_attachments.main()`/`parse_args()`.
- `etl/scripts/build_award_attachment_ecs_overrides.py` — translates the
  same flags into the ECS `run-task --overrides` container command, e.g.
  `--create-batch 10` → `["python", "-m", "archive_etl",
  "award-attachment", "--ecs", "--create-batch", "10"]`.
- `scripts/run-award-attachment-loader.sh` — the deployment helper.
  Mirrors `parse_args`'s own validation in bash (see below) so a bad
  combination fails in milliseconds, before an image build/push or a
  task-definition round trip.

See [`docs/AWARD_ATTACHMENT_ECS_EXECUTION.md`](AWARD_ATTACHMENT_ECS_EXECUTION.md)'s
"Deterministic batch workflow" section for the exact 10-file operational
sequence across all four commands.

### Validation rules (enforced in both `parse_args` and the shell script)

- `--create-batch`/`--load-batch`/`--show-batch` are mutually exclusive
  with each other (one batch operation at a time).
- `--create-batch` must be a positive integer.
- `--include-already-uploaded` requires `--create-batch`.
- None of `--create-batch`/`--load-batch`/`--show-batch` may combine with
  `--upload`, `--load-file-id`, or `--file-id` — each dispatches to its
  own complete code path and returns immediately, so combining would
  silently discard whichever flag lost dispatch priority.
- `--batch-id` is only valid together with `--upload`, and cannot combine
  with `--file-id`, `--load-file-id`, or any of the three batch verbs.
- `--show-batch` needs no `ORACLE_SECRET_ID` (PostgreSQL-only, like
  `--migrate-only`/`--show-upload-status`); `--create-batch` and
  `--load-batch` both read Oracle and are not exempt.

## What this does *not* include yet

**Award parent-batching is not implemented.** Award's structured loader
(`load_awards_from_csv.py`) still truncates and reloads all four Award
tables (`award_version`/`award_amount_info`/`award_person`/
`award_funding_proposal`) on every run — there is no per-award UPSERT
primitive to restrict to batch membership. Building `AWARD`/`AWARD`
batching on this framework is a separate, explicitly-gated follow-on that
starts with writing those UPSERT functions, not something this framework
provides on its own. See the design discussion that produced this
framework (session history) for the full reasoning, including the
corrected entity-key choice (`award_id`, not `award_number` +
`sequence_number`, which `V012` proves is not unique) and the corrected
child-table list (`award_amount_info` already covers "time and money";
"Award units/contacts" was removed in `V033` and should not be
reintroduced without a fresh decision).

## Tests

- `etl/tests/test_batch_framework.py` — pure framework tests, using a
  made-up `TEST_DOMAIN`/`TEST_ENTITY` pair throughout to prove the
  framework has no domain-specific knowledge baked in.
- `etl/tests/test_batch_workflow.py` — Award-attachment's integration
  with the framework (CLI parsing, create/load/show/upload behavior,
  resume/retry, idempotency), against a real throwaway PostgreSQL
  database with Oracle/S3/AWS boundaries mocked.
