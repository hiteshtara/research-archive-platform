# ETL Batch Framework

## Purpose

Provide one generic, domain-agnostic mechanism for "select exactly N
entities, then load/process exactly that membership" ETL workflows —
deterministic, resumable, and reusable across domains — instead of a
bespoke batch-table pair per domain.

## Scope

The framework itself (`archive.etl_batch`/`archive.etl_batch_item`,
`etl/archive_etl/batch/framework.py`) and its two current consumers:
Award Attachment (`AWARD_ATTACHMENT`/`PHYSICAL_FILE`) and Award
(`AWARD`/`AWARD`, added in Phase 4A). Does not cover Proposal,
Negotiation, Subaward, or Protocol batching — none of those domains are
wired onto this framework yet.

## Source material used

- `database/migrations/V037__create_etl_batch_framework.sql`
- `etl/archive_etl/batch/framework.py`
- `etl/load_award_attachments.py` (first consumer, Award Attachment)
- `etl/load_awards_from_csv.py` (second consumer, Award — Phase 4A)
- `etl/tests/test_batch_framework.py`, `etl/tests/test_batch_workflow.py`,
  `etl/tests/test_award_incremental_upsert.py`
- Session design discussion that produced the original
  Award-attachment-only design, then generalized it once Award
  parent-batching made a second bespoke batch-table pair look like the
  wrong direction

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

## Assumptions

- Every domain wired onto this framework has a real, numeric surrogate
  primary key for its `entity_key` (true for both current consumers:
  `file_id`, `award_id`). A domain without one would need a
  domain-specific membership table alongside this one, not a weakened
  free-form `entity_key`.
- A generic batch framework existing does **not** imply a domain is
  batch-ready — each domain still needs its own safe, idempotent
  per-entity load primitive (UPSERT functions) before it can use this
  framework correctly. This was the central lesson from the Award
  domain-decomposition work: see `AWARD_DOMAIN_DECOMPOSITION.md`.

## Findings

### Schema

`database/migrations/V037__create_etl_batch_framework.sql` creates two
tables:

- **`archive.etl_batch`** — the parent manifest. Tagged with `domain`
  (e.g. `AWARD_ATTACHMENT`, `AWARD`) and `entity_type` (e.g.
  `PHYSICAL_FILE`, `AWARD`), both plain `VARCHAR` discriminators —
  matching this schema's existing convention (`archive.load_run.domain`
  is the same shape, not a foreign-keyed lookup table). Also carries
  `requested_size`, `selection_strategy` (a short label describing how
  membership was chosen), `selection_parameters` (`JSONB`,
  domain-specific selection options), and the usual
  `status`/`created_at`/`started_at`/`completed_at`/`created_by_run_id`/
  `notes`.
- **`archive.etl_batch_item`** — batch membership. One row per selected
  entity, keyed by `(batch_id, entity_key)`, with a stable `ordinal`
  (unique per batch) recording selection order.

`entity_key` is a plain `BIGINT`, not a foreign key to any domain table.
Batch creation persists membership *before* any load has upserted the
corresponding domain row — a FK here would make batch creation itself
impossible.

### Status ownership — the rule that matters most

`etl_batch_item.status` tracks **exactly one thing**: whether this
batch's own load/process step has run for this `entity_key`
(`PENDING → PROCESSING → COMPLETED / FAILED / MISSING_SOURCE / SKIPPED`).

It is **never** used to duplicate a domain's own downstream state. For
Award attachments, upload progress (`PENDING`/`UPLOADING`/`UPLOADED`/
`FAILED`/`MISSING_SOURCE_CONTENT`) remains solely owned by
`archive.attachment_object.upload_status`. For Award, there is no
downstream state at all beyond the load itself — Award has no
"upload"-style second phase, so `etl_batch_item.status` alone is the
complete picture for that domain. Domain code scopes further processing
to batch membership by joining `etl_batch_item` to its own table on
`entity_key`, never by mirroring that table's status into
`etl_batch_item`.

`etl_batch.status` is a coarser, batch-level rollup:

```
CREATED → METADATA_LOADING → READY → PROCESSING → PARTIAL / COMPLETED / FAILED / ABANDONED
```

`METADATA_LOADING`/`PARTIAL`/`FAILED`/`ABANDONED` are reserved for future
use — neither current consumer's workflow uses them yet. Award Attachment
moves through `CREATED → READY → PROCESSING → COMPLETED`; Award (no
upload phase) moves through `CREATED → READY` only, via `--load-batch`.

### The framework module

`etl/archive_etl/batch/framework.py` has no knowledge of any domain's own
tables. It provides:

- `select_distinct_ascending_from_oracle_batches(batches, *, id_column, requested_size, excluded)`
  — scans an `OracleDataSource.read_batches()`-shaped lazy iterator,
  keeping the first N distinct, non-excluded values, stopping early once
  enough are found. Always closes the iterator itself (`try`/`finally`),
  matching `OracleDataSource`'s own resource-lifecycle contract. **Only
  correct when the underlying Oracle source is already sorted ascending
  by the same column being selected** — true for Award Attachment's
  physical-file scan (`ORDER BY FILE_ID`), false for Award's own version
  scan (`ORDER BY AWARD_NUMBER, SEQUENCE_NUMBER`, unrelated to
  `award_id`). Award's `_run_create_award_batch` therefore does **not**
  reuse this function — see `AWARD_IMPLEMENTATION_ROADMAP.md`.
- `create_batch(engine, *, domain, entity_type, requested_size, selection_strategy, selected_keys, selection_parameters=None, run_id=None)`
  — persists a new batch and its already-decided membership
  transactionally. Raises `ValueError` for a non-positive
  `requested_size`.
- `load_batch_membership(connection, batch_id, *, domain, entity_type)` /
  `assert_batch_matches(...)` — returns membership in ordinal order;
  raises `RuntimeError` if the batch doesn't exist, or exists but was
  created for a *different* domain/entity_type. This domain check is the
  guard against accidentally scoping one domain's load step to another
  domain's batch, now that `batch_id` is no longer namespaced per domain
  by a separate table.
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
  `attachment_object` or any other domain table. Award has no such
  augmentation to add, so its CLI calls this function directly.

### How Award Attachment consumes it

`AWARD_ATTACHMENT_BATCH_DOMAIN = "AWARD_ATTACHMENT"`,
`AWARD_ATTACHMENT_BATCH_ENTITY_TYPE = "PHYSICAL_FILE"`.

- `--create-batch N` (`_run_create_batch`): builds the excluded-file-ids
  set (already-`UPLOADED` file_ids, unless `--include-already-uploaded`),
  scans `OracleDataSource(FILES_ORACLE_SQL).read_batches()` via
  `select_distinct_ascending_from_oracle_batches`, then calls
  `create_batch(...)`.
- `--load-batch <id>` (`_run_load_batch`): calls `load_batch_membership`,
  reads exactly those file_ids' metadata/references from Oracle, upserts
  them, and calls `set_item_status` per file (`COMPLETED` or
  `MISSING_SOURCE`) and `set_batch_status` (`READY`) on success — all
  inside the same transaction, so `--dry-run` rolls every one of those
  back too.
- `--upload --batch-id <id>`: `select_upload_candidates` joins
  `etl_batch_item` to `attachment_object` on `entity_key = file_id`,
  scoped to `batch_id`; `_run_upload` calls `begin_batch_processing`
  (`PROCESSING`) before uploading and `finish_batch_processing`
  (`COMPLETED`) after.
- `--show-batch <id>` (`_run_show_batch`): calls the generic `show_batch`
  for the batch-shape fields, then augments the report with the
  attachment-specific upload-status breakdown via its own query.

### How Award consumes it (Phase 4A)

`AWARD_BATCH_DOMAIN = "AWARD"`, `AWARD_BATCH_ENTITY_TYPE = "AWARD"`,
`entity_key = award_id`. See `AWARD_IMPLEMENTATION_ROADMAP.md` for the
full design (why Award's load widens each `award_id` to its whole
`award_number` version family, and why batch creation can't reuse the
framework's early-stop selection helper).

- `--create-batch N` (`_run_create_award_batch`): full Oracle scan (not
  early-stop), sorts distinct `award_id`s in Python, then calls
  `create_batch(...)`.
- `--load-batch <id>` (`_run_load_award_batch`): resolves each batch
  member's `award_number`, deduplicates award_ids that share one (loading
  the family once), and calls `_run_load_award_id` per unique family.
- `--show-batch <id>`: calls the generic `show_batch` directly — no
  domain-specific augmentation, since Award has no second phase/status
  beyond the load itself.

### CLI surfaces

The batch commands are exposed identically everywhere each domain's
loader is invoked from — flag names never change between layers:

- Each domain's own `parse_args` (the source of truth for validation).
- `python -m archive_etl award-attachment ...` /
  `python -m archive_etl award ...` (`etl/archive_etl/__main__.py`) — a
  thin forwarder; it does not re-validate combinations itself.
- For Award Attachment only (Phase 3 CLI convergence, not yet done for
  Award): `etl/scripts/build_award_attachment_ecs_overrides.py` and
  `scripts/run-award-attachment-loader.sh` mirror the same validation in
  bash so a bad combination fails before an image build/push or a
  task-definition round trip.

### Validation rules (Award Attachment; Award mirrors the batch-verb subset)

- `--create-batch`/`--load-batch`/`--show-batch` are mutually exclusive
  with each other (one batch operation at a time).
- `--create-batch` must be a positive integer.
- None of the three batch verbs may combine with a domain's own
  single-entity verb (`--upload`/`--load-file-id`/`--file-id` for Award
  Attachment; `--load-award-id` for Award) — each dispatches to its own
  complete code path and returns immediately, so combining would silently
  discard whichever flag lost dispatch priority.
- Award Attachment additionally: `--include-already-uploaded` requires
  `--create-batch`; `--batch-id` is only valid together with `--upload`
  and cannot combine with `--file-id`/`--load-file-id`/any batch verb;
  `--show-batch` needs no `ORACLE_SECRET_ID` (PostgreSQL-only, like
  `--migrate-only`/`--show-upload-status`), `--create-batch`/`--load-batch`
  both read Oracle and are not exempt.

## Open questions

- Should `--show-batch` become a single, truly domain-agnostic top-level
  command (e.g. `etl --show-batch <ID>`) instead of being repeated under
  each domain's own subcommand? Deferred — not a technical blocker,
  just unresolved ergonomics.
- Award Attachment has full CLI convergence (unified CLI, ECS override
  builder, deployment shell script all wired for its batch flags, plus
  early bash-side validation). Award (Phase 4A) only has the Python
  `parse_args`/`main()` dispatch — it has not been wired into the unified
  CLI, any ECS override builder, or a deployment shell script, because
  Award has no ECS execution path at all yet (`load_awards_from_csv.py`
  has never had a `--ecs` mode). This is a real gap if Award batching is
  ever run in production, not yet addressed.

## Decisions

- Generalize immediately once a second domain needed batching, rather
  than let Award attachment's bespoke tables become "the pattern" by
  default.
- `entity_key` stays a plain `BIGINT`, not a composite/string key, until a
  domain without a numeric surrogate key actually needs this framework.
- Status ownership: `etl_batch_item.status` is generic-only; any
  domain-specific downstream state is the domain's own responsibility to
  track and report, never mirrored into the generic table.

## Recommended implementation order

1. ~~Generic schema + framework module~~ — done.
2. ~~Award Attachment as first consumer~~ — done.
3. ~~Award Attachment CLI convergence (unified CLI, ECS override builder,
   deployment script, validation)~~ — done.
4. ~~Award as second consumer (Phase 4A: UPSERT primitives +
   `--create-batch`/`--load-batch`/`--show-batch`)~~ — done, this session.
5. Award CLI convergence (unified CLI wiring, and — only if/when Award
   gets an ECS execution path — an override builder and deployment
   script) — not started.
6. A third domain (Proposal is the next natural candidate, since it
   shares Award's `business_number + sequence_number` versioning shape)
   — not started; requires that domain's own UPSERT primitives first,
   exactly as Award's did.

## Date last updated

2026-07-31 (Phase 4A: Award added as the framework's second consumer).
