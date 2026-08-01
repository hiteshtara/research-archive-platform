# Award Full-Load Reset — `clear_existing_award_data()`

## Status

Fixed. `clear_existing_award_data()` (in `etl/load_awards_from_csv.py`) now
clears every one of the 48 Award-owned archive tables through migration
V052, not just the original four. `--load-award-id` and `--load-batch` are
unaffected — both UPSERT and never call this function at all.

## Purpose

The legacy full load (`uv run python load_awards_from_csv.py`, no flags —
distinct from `--load-award-id`/`--load-batch`) has always started by
clearing `archive.award_version` and its three original children
(`award_amount_info`/`award_person`/`award_funding_proposal`) before
reloading them from Oracle. That function was never updated as 44 more
Award child tables were added across this project's later bundles
(Custom Data through SAP Award Transmission History) — those tables all
carry either a real Postgres foreign key back to one of the original four
(directly or transitively) or a bare, unenforced Award-number reference.
Once any of them held even one row (from a prior `--load-award-id`/
`--load-batch` run, which do populate them), the old 4-table `TRUNCATE`
would fail with a foreign-key violation, since Postgres requires every
table with an FK into a truncated table to also be named in the same
`TRUNCATE` statement (or `CASCADE`d). This was the last piece of technical
debt blocking the full-load path from running cleanly.

## Scope

Only `clear_existing_award_data()`. Does not touch `_run_load_award_id`,
`_run_load_award_batch`, or any other incremental/batch loading behavior —
neither of those two paths ever calls this function.

## How the table list was built

Not hand-maintained from memory or grep alone. Built in two steps, cross-
checked against each other:

1. Every `INSERT INTO archive.*` target across every `upsert_award_*`/
   `load_dataframe` call in `load_awards_from_csv.py` — this is the
   authoritative "what does this loader actually write to" list, and
   produced exactly 48 tables (excluding `archive.load_run`, a shared
   provenance table written by every domain's loader, not Award-owned).
2. A throwaway Postgres database with every migration through V052
   applied, queried for the real foreign-key graph (a recursive CTE
   walking `information_schema.table_constraints`/
   `constraint_column_usage`) rooted at the four original tables. This
   surfaced two things:
   - 37 of the 44 non-root tables have a real Postgres FK, direct or
     transitive, into one of the four roots.
   - 7 tables (`award_amount_transaction`, `award_hierarchy`,
     `award_subcontracting_budgeted_goals`, `pending_transaction`,
     `pending_transaction_extension`, `time_and_money_document`,
     `transaction_detail`) reference Award only via a bare, unenforced
     `award_id`/`award_number` column — the same cross-`award_number`-
     family pattern already documented on
     `AWARD_TIME_AND_MONEY_DESIGN.md` and
     `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` — and have **no** Postgres
     FK to any of the four roots (only to `archive.load_run`, and, for
     `pending_transaction_extension`, to `pending_transaction`). These
     would never be caught by a naive `CASCADE` from the four original
     tables, which is itself a reason to prefer an explicit list.
3. Confirmed, using the same throwaway database, that **no table outside
   this 48-table list has any FK into any table inside it** — in
   particular no Proposal, Negotiation, Protocol, Subaward, or Attachment
   table. This is what makes a single combined `TRUNCATE` naming exactly
   these 48 tables both correct (nothing referencing them is left
   unlisted) and safely scoped (nothing outside the list can be reached).

`archive.award_attachment`/`archive.attachment_object`/
`archive.archived_attachment` are deliberately excluded even though
`award_attachment` is genuinely Award-domain data: that table is owned and
loaded exclusively by the separate `etl/load_award_attachments.py` loader,
never by this module's full load, and was never part of the original
`clear_existing_award_data()` scope either.

## The fix

`_AWARD_OWNED_TABLES` is now a module-level tuple of all 48 table names,
grouped by the bundle that introduced them (Budget, SAP Transmission,
Extension/CGB, Comment, Special Approvals/Compliance, Reporting/Subaward
Summary, Notepad, Contacts, Terms, People hierarchy, Custom Data, Time and
Money, and finally the four original core tables), in leaf-to-root order.
`clear_existing_award_data()` builds one combined
`TRUNCATE TABLE archive.t1, archive.t2, ..., archive.t48 RESTART IDENTITY;`
from that tuple.

A single combined `TRUNCATE` — not `TRUNCATE ... CASCADE` on just the four
original tables — is the explicit, ordered strategy this fix uses in place
of `CASCADE`: Postgres requires every table with an FK into any table
named in the statement to also appear in that same statement, so this
list is simultaneously the mechanism and the safety boundary. If a future
bundle adds a 49th Award table and whoever adds it forgets to update this
list, the next full-load run raises a real Postgres foreign-key-violation
error (the same failure mode this fix itself corrects) rather than
`CASCADE` silently reaching into that table anyway — or, worse, `CASCADE`
reaching into some future table this function was never meant to touch.
Intra-list order has no effect on correctness for a single combined
statement (Postgres processes it atomically); it is kept in leaf-to-root
order purely for human readability and in case a future maintainer ever
needs to split it into sequential statements.

`RESTART IDENTITY` is preserved from the original implementation, though
it is a no-op for every one of these 48 tables: every Award table's
primary key is populated directly from Oracle's own real business/
surrogate key (`award_id`, `transmission_id`, etc.), never from a Postgres
`SERIAL`/`GENERATED ALWAYS AS IDENTITY` column, so there is no sequence
for any of them to restart. Kept for consistency with the original code
and in case a future Award table ever does introduce a Postgres-generated
identity column.

## Tests

`ClearExistingAwardDataTest` in `etl/tests/test_award_incremental_upsert.py`:

- Populates representative rows across the full Award hierarchy via the
  real incremental loader (`_run_load_award_id`, not hand-written
  `INSERT`s) — every one of the 48 tables, including the deepest Budget
  bundle and both SAP transmission tables.
- Plants one row directly in a representative table from every domain
  this function must never touch: `archive.negotiation`,
  `archive.proposal_version`, `archive.protocol_version`,
  `archive.subaward`, `archive.attachment_object`,
  `archive.award_attachment`.
- Calls `clear_existing_award_data()` and asserts it does not raise (no
  FK violation despite 48 interrelated tables and no `CASCADE`).
- Asserts every one of the 48 tables is empty afterward.
- Asserts every planted non-Award row, and `archive.load_run` itself
  (shared provenance, not Award-owned), is still present and untouched.
- A second test confirms `RESTART IDENTITY` is a harmless no-op here (no
  `archive.award_*` sequence exists to reset).
- A third test confirms the full clear-then-reload sequence a real full
  load performs (see `main()`) works end to end: the same `award_id` can
  be re-inserted immediately after clearing, with no leftover row or
  constraint blocking it.

## Validation

`cd etl && uv run pytest` (668 passed), `uv run ruff check .` (clean),
`uv run mypy .` (clean).

## Decisions

- An explicit, ordered, combined `TRUNCATE` naming all 48 tables, rather
  than `TRUNCATE ... CASCADE` on the four original tables — per explicit
  instruction, and because `CASCADE` would have an unbounded, implicit
  blast radius (anything with an FK into the truncated set, whether or
  not it was ever verified to be Award-only), whereas this list is a
  closed, explicitly-verified boundary.
- The table list is generated from a `tuple` constant
  (`_AWARD_OWNED_TABLES`) rather than inlined directly into the SQL
  string, so the same authoritative list can be asserted against directly
  in the regression test (proving the test and the implementation can
  never silently drift apart).
- `archive.award_attachment`/`archive.attachment_object` excluded, per
  explicit instruction and consistent with those tables never having
  been part of this function's original scope.

## Open questions

- None specific to this fix. The general "does Kuali ever hard-delete
  Award rows that this reset should also reconcile against" question is
  the same one already recorded as open for every incremental Award
  child table in this project — unaffected by this change, since the
  full load's own reset-then-reload behavior was already how that
  question was handled here before this fix (this fix only makes that
  existing reset-then-reload behavior work again for all 48 tables
  instead of erroring out on the first FK violation).

## Date last updated

2026-08-01.
