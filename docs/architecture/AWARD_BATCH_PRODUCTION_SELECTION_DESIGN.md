# Award Batch Production Selection — Design and Implementation Record

## Status

Implemented. `--create-batch` now defaults to a production selection mode
that advances through the Award population across repeated calls,
excluding award_ids already successfully completed or currently claimed
by an active batch. The original always-smallest-N behavior is preserved
unchanged, opt-in only, via `--validation-overlap`.

## Purpose

Before this change, `_select_award_ids_ascending` (the only selection
path `--create-batch` had) always returned the N globally-smallest
award_ids, every single call, regardless of any prior batch's outcome —
confirmed and analyzed in detail in a research pass immediately before
this implementation (see "Prior analysis" below). This was a deliberate,
documented tradeoff for the specific 10→100→1000 validation-scale test
plan in `AWARD_IMPLEMENTATION_ROADMAP.md`, where the overlap was itself a
useful repeat-idempotency check — but it was never designed for, or
efficient for, ongoing production batch creation: every repeated call
re-scanned the entire Oracle Award population and reprocessed every
lower-numbered award_id all over again. This bundle adds the missing
production mode, without touching the validation behavior at all.

## Prior analysis (verbatim summary of the research pass this implements)

- `--create-batch N` always creates a batch from the beginning of the
  Award population — confirmed via `_select_award_ids_ascending`'s
  `sorted(all_award_ids)[:requested_size]`.
- No exclusion of Awards already assigned to previous batches existed at
  all — no query against `archive.etl_batch_item` anywhere in the old
  code path.
- `entity_key` in `archive.etl_batch_item` is the real, global `award_id`
  value directly — not a row number, not an internal ID.
- Repeating `--create-batch 5000` selected the same first 5,000 award_ids
  every time, not the next unprocessed 5,000.
- The actual N-selection logic was Python-side (`sorted(...)[:N]`), not
  SQL-side — `01_award_versions.sql` (the only source the old path read)
  has no `WHERE`/`LIMIT` and is `ORDER BY AWARD_NUMBER, SEQUENCE_NUMBER`,
  unrelated to `award_id` order.
- Verdict: intentional for the validation-scale test plan it was written
  for, but a real gap for ongoing production loading — not efficiently
  designed for repeated incremental use the way Award Attachment's own
  `_run_create_batch` (which excludes already-`UPLOADED` file_ids by
  default) already is.

## Scope

Only `--create-batch`'s own selection logic (`_run_create_award_batch`,
`_select_award_ids_ascending`, and the new
`_excluded_completed_and_active_award_ids`), the new
`award_ids_ascending.sql` extraction query, and CLI/unified-CLI wiring
for the new `--validation-overlap` flag. Does not touch
`--load-award-id`, `--load-batch`, `--show-batch`, any UPSERT logic, or
any of the 48 Award table load functions — all unchanged, per explicit
instruction.

## Selection modes

### Production (default, `validation_overlap=False`)

Excludes every award_id that either:

1. is already `COMPLETED` as an `etl_batch_item` — regardless of that
   item's own batch's overall status, since a batch can finish
   `PARTIAL`/`FAILED` overall while still containing individually-
   `COMPLETED` items, and those specific award_ids must never be
   reselected; or
2. belongs to a batch that is still active (`READY` or `PROCESSING`) —
   so two batches can never concurrently claim the same award_id,
   regardless of that item's own individual status within the active
   batch.

Deliberately does **not** exclude `FAILED` or `PENDING` items belonging
to an already-resolved batch (`COMPLETED`/`FAILED`/`PARTIAL`/
`ABANDONED`) — the whole point of production selection is that an
award_id which never successfully completed remains eligible for a later
batch to pick up again, not permanently skipped. A batch that is merely
`CREATED` or `METADATA_LOADING` (not yet confirmed `READY`) also does not
lock out its own items — only `READY`/`PROCESSING` count as "active."

Implementation: `_excluded_completed_and_active_award_ids(engine)` builds
this exclusion set with one read-only PostgreSQL query (`archive.etl_batch_item`
joined to `archive.etl_batch`, scoped to `domain='AWARD'`/`entity_type='AWARD'`).
That set is passed as `excluded=` into the already-existing, shared
`batch_framework.select_distinct_ascending_from_oracle_batches` — the
same generic, early-stopping helper Award Attachment's own
`_run_create_batch` already uses for `FILE_ID`. It scans a **new**,
narrowly-scoped Oracle query, `sql/extract/award/award_ids_ascending.sql`
(`SELECT AWARD_ID FROM AWARD ORDER BY AWARD_ID` — no new/invented
columns, a strict subset of the already-verified `01_award_versions.sql`),
stopping as soon as `requested_size` non-excluded distinct award_ids are
found. Because this source is genuinely `ORDER BY AWARD_ID` (unlike
`01_award_versions.sql`), the early-stop optimization is valid here and
never requires loading the entire Oracle Award population into memory —
directly satisfying "avoid loading the entire population into memory
when a bounded ordered source query can do the selection safely."

### Validation/testing (`validation_overlap=True`, opt-in via `--validation-overlap`)

Unchanged: `_select_award_ids_ascending` still does a full scan of
`01_award_versions.sql`, collects every distinct award_id in Python, and
returns `sorted(...)[:requested_size]` — always the smallest N, every
time, with no exclusion of anything. Documented, as before, as
intentionally overlapping and useful for repeat-idempotency checks at
increasing scale (`AWARD_IMPLEMENTATION_ROADMAP.md`). `--validation-overlap`
is rejected by `parse_args()` unless `--create-batch` is also given.

## CLI

```
--create-batch N                       # production mode (default)
--create-batch N --validation-overlap  # old, always-smallest-N behavior
```

Also forwarded through the unified CLI: `python -m archive_etl award
--create-batch N [--validation-overlap]`. Not wired into
`scripts/run-award-loader.sh`/`etl/scripts/build_award_ecs_overrides.py`
in this pass — `--validation-overlap` is a testing-only flag not expected
to be needed from a real ECS production run; can be added trivially later
if that changes.

## Files changed

- `sql/extract/award/award_ids_ascending.sql` (new).
- `etl/load_awards_from_csv.py`: `AWARD_IDS_ASCENDING_ORACLE_SQL` path
  constant; `_excluded_completed_and_active_award_ids` (new);
  `_run_create_award_batch` (rewritten with `validation_overlap`
  parameter and mode branch); `parse_args()` (`--validation-overlap`
  flag + validation); `main()` (threads `validation_overlap` through to
  `_run_create_award_batch`).
- `etl/archive_etl/__main__.py`: `--validation-overlap` added to the
  `award` domain's forwarded flags.
- `etl/tests/test_award_incremental_upsert.py`: `_patched_oracle` gained
  an `award_ids=` kwarg wired to `AWARD_IDS_ASCENDING_ORACLE_SQL`; two
  pre-existing tests updated to pass `validation_overlap=True` explicitly
  (they test the old always-smallest-N behavior by name and intent, now
  reachable only that way); new `CreateAwardBatchProductionSelectionTest`
  class (12 tests).
- `database/migrations/`: none — `archive.etl_batch.selection_strategy`
  is `VARCHAR(50)`; the new strategy label
  (`ORACLE_SCAN_ASCENDING_AWARD_ID_EXCL_COMPLETED`, 45 chars) and the
  validation one (`ORACLE_SCAN_ASCENDING_AWARD_ID_VALIDATION_OVERLAP`,
  49 chars) both fit without widening the column.

## Tests

`CreateAwardBatchProductionSelectionTest` (12 tests, real Postgres via
`_AwardPostgresTestCase`):

- first production batch selects IDs 1–5000
- next production batch selects 5001–10000 after the first is fully
  `COMPLETED`
- `COMPLETED` items excluded even when their own batch is only `PARTIAL`
  overall
- `FAILED` items remain eligible once their batch is resolved
- `PENDING` items remain eligible once their batch is resolved
  (`ABANDONED` batch)
- `READY` batch items are not selected twice
- `PROCESSING` batch items are not selected twice
- `CREATED`/`METADATA_LOADING`-status batches do not exclude their own
  items (only `READY`/`PROCESSING` count as active)
- `--validation-overlap` still selects the smallest N every time,
  ignoring completion state entirely
- deterministic rerun: two production calls with no state change in
  between select identically
- both selection-strategy labels are recorded correctly on
  `archive.etl_batch`

Two pre-existing `RunCreateAwardBatchTest` tests (`test_selects_exactly_n_distinct_award_ids_ascending`,
`test_persists_membership_with_generic_batch_domain`) were testing the
old always-smallest-N behavior specifically — updated to pass
`validation_overlap=True` explicitly, preserving their original intent
and assertions unchanged. One additional pre-existing test
(`ShowAwardBatchTest::test_generic_show_batch_works_for_award_domain`)
updated to patch `award_ids=` instead of `versions=`, matching the new
default selection mode's own Oracle source.

## Validation

`cd etl && uv run pytest` (748 passed), `uv run ruff check .` (clean),
`uv run mypy .` (clean).

## Decisions

- Production mode is the new **default** for `--create-batch` (no flag
  needed) — per explicit instruction ("make the normal production
  behavior select the next Award IDs not already completed"); the old
  behavior moves behind an explicit, clearly-named opt-in flag rather
  than the reverse, since production loading is the common case going
  forward and validation-scale testing is the rare one.
- Exclusion is item-status-based (`COMPLETED`) plus batch-status-based
  (`READY`/`PROCESSING`), not a single combined check — this is what
  correctly satisfies both "exclude only successfully completed items"
  and "prevent duplicate membership across active batches" as two
  genuinely distinct conditions (a `FAILED` item in an active `READY`
  batch must still be excluded, even though it isn't itself
  `COMPLETED`).
- A new, narrowly-scoped Oracle query (`award_ids_ascending.sql`) was
  added rather than trying to reuse `01_award_versions.sql` for
  selection — reusing it would have kept the "must fully scan, cannot
  early-stop" problem the original `_select_award_ids_ascending` docstring
  already explains; a dedicated `ORDER BY AWARD_ID` query is what makes
  the shared early-stop framework helper valid and efficient here,
  mirroring exactly how Award Attachment's own physical-file scan is
  already `ORDER BY FILE_ID` for the same reason.
- `--validation-overlap` was not wired into the ECS wrapper script/
  override-builder in this pass — out of scope for a testing-only flag
  not expected to be needed from a real ECS production run.

## Open questions

- None specific to this change. The general question of whether Oracle
  ever hard-deletes/renumbers `AWARD_ID` values is the same one already
  open for every other Award table in this project — unaffected by this
  change.

## Date last updated

2026-08-01.
