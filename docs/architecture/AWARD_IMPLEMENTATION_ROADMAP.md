# Award Implementation Roadmap — Incremental UPSERT (Phase 4)

## Purpose

Design, then implement, incremental UPSERT support for Award's four
existing archive tables, using the generic ETL batch framework — and
record the design decisions, real bugs found, and final implementation
state so future sessions don't have to re-derive them.

## Scope

Strictly `archive.award_version`, `archive.award_amount_info`,
`archive.award_person`, `archive.award_funding_proposal`. No Award
Budget, Award Custom Data, Award Reporting, Award Contacts, Award Terms,
or Time and Money workflow table — see `AWARD_DOMAIN_DECOMPOSITION.md`
for those as separate future milestones. No `award_unit_contact`
reintroduction (removed in V033; not revisited here).

## Source material used

- BU 7.3's OJB mapping: `reference/kuali/award/repository-award.xml`
  (authoritative persistence source per explicit instruction), `Award.xml`,
  `AwardBudgetDocument.xml`, `AwardPersonUnit.xml`, `AwardReportTerm.xml`,
  `AwardSpringBeans.xml`, `AwardDocument.xml`
- Comparison against `reference/kuali/negotiation-ojb.xml`,
  `reference/kuali/subaward-ojb.xml`, `reference/kc/ojb/ProtocolOJB.xml`
- `database/migrations/V011__create_award_archive_tables.sql`,
  `V012__allow_multiple_award_rows_per_sequence.sql`,
  `V013__add_award_primary_current_flag.sql`
- `etl/load_awards_from_csv.py` (existing full-load implementation, and
  now the Phase 4A incremental additions)
- `etl/load_award_attachments.py` (the UPSERT pattern this mirrors)
- `etl/tests/test_award_incremental_upsert.py`

## Assumptions

- The generic ETL batch framework (`ETL_BATCH_FRAMEWORK.md`) is available
  and stable — no framework changes were needed for Award to consume it.
- Award's full-load path (`load_awards_from_csv.py`'s `TRUNCATE`-based
  main flow) continues to exist unchanged and is not being replaced —
  the incremental path is additive.

## Findings (design phase)

### Kuali object graph → archive mapping

| Table | Kuali class | Oracle table | PK | Parent key | Versioning |
|---|---|---|---|---|---|
| `archive.award_version` | `org.kuali.kra.award.home.Award` | `AWARD` | `award_id` | — (root) | `award_number` + `sequence_number`; `VER_NBR` (OJB lock, `locking="false"`, not archived) |
| `archive.award_amount_info` | `AwardAmountInfo` (referenced only in BU's file) | `AWARD_AMOUNT_INFO` | `award_amount_info_id` | `award_id` | none of its own |
| `archive.award_person` | `AwardPerson` (referenced only in BU's file) | `AWARD_PERSONS` | `award_person_id` | `award_id` | none of its own |
| `archive.award_funding_proposal` | `AwardFundingProposal` (referenced only in BU's file) | `AWARD_FUNDING_PROPOSALS` | `award_funding_proposal_id` | `award_id` (+ `proposal_id`) | none of its own |

BU's `repository-award.xml` only contains class-descriptors for
`AwardExtension`, `Award`, `AwardTransmission`(`Child`) — the three child
classes above are only *referenced* from `Award`'s own
`collection-descriptor`s. Their own column-level OJB mappings were
confirmed later, from the full upstream Kuali Research source tree (see
`AWARD_DOMAIN_STUDY.md`), not from BU's file alone.

### UPSERT conflict keys

Each child table's own surrogate PK (`award_amount_info_id`,
`award_person_id`, `award_funding_proposal_id`) is the UPSERT conflict
key — confirmed safe because these IDs (along with `award_id` itself) are
drawn from a shared Oracle sequence (`SEQUENCE_AWARD_ID`, per the full
Kuali source study), globally unique across all of them, and because
`AWARD_PERSONS` specifically has no DB-level uniqueness constraint beyond
its own PK (duplicate person/role rows per `award_id` are legitimate — a
natural composite key would be unsafe).

### Parent/child load order

None required between the three children — each depends only on
`award_id` existing, not on each other. The real ordering constraint
turned out to be **within** `award_version` itself: see "is_primary_current
family-widening" below.

### Deletion / reconciliation strategy

**Not implemented in Phase 4A, by deliberate scope decision.** A child row
already archived for an `award_id` is never deleted or marked if Oracle no
longer returns it on a later incremental load. This mirrors the fact that
neither the full `TRUNCATE`+reload loader nor this incremental loader can
currently distinguish "legitimately removed in Kuali" from "transient
Oracle read anomaly" without a reconciliation-strategy decision. Recorded
as an open question below, not silently glossed over.

## Findings (implementation phase — real issues caught by tests)

### `is_primary_current` requires family-widening, not per-award_id UPSERT

`archive.award_version.is_primary_current` is enforced by a **partial
unique index** (`V013`'s `ux_award_one_primary_current`: "at most one
`TRUE` row per `award_number`"). Correctly maintaining that invariant for
a single `award_id` in isolation is impossible — deciding which one row
in a version family is primary requires comparing it against every
sibling row for the same `award_number`. `_run_load_award_id` therefore:

1. Resolves the requested `award_id`'s `award_number` (a bounded,
   early-stop-capable Oracle scan — `award_id` is unique per row).
2. Re-reads that **entire family** fresh from Oracle (a full-scan,
   no-early-stop read — `award_number` is not unique per row).
3. Re-upserts every family member together, in one transaction.

`is_current_version` needed no such widening: it's computed by Oracle's
own window function (`PARTITION BY AWARD_NUMBER`) in
`sql/extract/award/01_award_versions.sql`, server-side, before any
client-side filtering — already correct per-row regardless of how the
result set is later narrowed.

### The "clear-then-set" ordering bug (caught by
`test_reload_with_no_oracle_changes_is_unchanged`)

First implementation cleared the whole family's `is_primary_current` to
`FALSE` unconditionally before the per-row UPSERT loop, to avoid a
transient unique-index violation when a *different* row becomes primary.
This caused every reload to report `"updated"` instead of `"unchanged"`,
even with no real change — because the UPSERT's `IS DISTINCT FROM` check
compared against the just-cleared `FALSE`, not the value before the whole
operation started. Fixed by excluding the freshly-computed winning
`award_id` from the clear statement, so a row that stays primary is never
touched by the clear step and correctly reports `"unchanged"` when nothing
changed.

### Batch creation cannot reuse the generic framework's early-stop selection
(caught by `test_selects_exactly_n_distinct_award_ids_ascending`)

`batch_framework.select_distinct_ascending_from_oracle_batches`'s
early-stop optimization is only correct when the Oracle source is already
sorted ascending by the same column being selected — true for Award
Attachment (`FILES_ORACLE_SQL` is `ORDER BY FILE_ID`), **false for
Award** (`01_award_versions.sql` is `ORDER BY AWARD_NUMBER,
SEQUENCE_NUMBER`, unrelated to `award_id`). `_run_create_award_batch`
therefore uses a dedicated `_select_award_ids_ascending` helper: a full
scan collecting every distinct `award_id`, sorted in Python, with the
first N taken — no early stop, correctness over speed.

### Batch loads deduplicate award_ids sharing one award_number

`_run_load_award_batch` resolves every batch member's `award_number`
before loading; if two different `award_id`s in the same batch belong to
the same family, the family is only scanned/upserted once (the second
`award_id`'s data was already written as a side effect of the first's
family-widened load) — both batch items are still marked `COMPLETED`
correctly.

## Implementation state

**Done, this session:**
- `read_award_number_for_award_id`, `read_award_versions_matching_award_numbers`,
  `read_award_children_matching_award_ids`, `_select_award_ids_ascending`
  (bounded/full-scan Oracle readers, `etl/load_awards_from_csv.py`)
- `upsert_award_version`, `upsert_award_amount_info`, `upsert_award_person`,
  `upsert_award_funding_proposal` (idempotent UPSERT, same
  `INSERT ... ON CONFLICT ... WHERE IS DISTINCT FROM ... RETURNING (xmax=0)`
  pattern as Award Attachment)
- `_run_load_award_id` (bounded, family-widening single-award proof,
  `--load-award-id`)
- `_run_create_award_batch` / `_run_load_award_batch` (generic batch
  framework consumer: `domain="AWARD"`, `entity_type="AWARD"`,
  `entity_key=award_id`; `--show-batch` calls the framework's generic
  `show_batch` directly, no domain augmentation needed since Award has no
  second phase/status beyond the load itself)
- CLI: `--load-award-id`, `--create-batch`, `--load-batch`, `--show-batch`,
  `--dry-run`, with validation mirroring Award Attachment's mutual-exclusion
  rules
- `etl/tests/test_award_incremental_upsert.py` — 33 tests: `parse_args`
  validation, bounded-reader unit tests, real-Postgres UPSERT
  insert/update/unchanged tests, the family-widening/primary-current
  test, batch create/load/show tests, and `main()` dispatch tests
- Full validation: `uv run pytest` (497 passed), `uv run ruff check .`
  (clean), `uv run mypy .` (clean)

**Not done (explicitly out of scope for Phase 4A):**
- Deletion/reconciliation strategy for child rows no longer returned by
  Oracle (see open questions)
- Award's own ECS execution path, unified CLI wiring, or any deployment
  script convergence (`load_awards_from_csv.py` has no `--ecs` mode at
  all — Award Attachment's full CLI convergence, `ETL_BATCH_FRAMEWORK.md`
  §"CLI surfaces", has no Award counterpart yet)
- Any of the Tier 1/Tier 2 subsystems from `AWARD_DOMAIN_DECOMPOSITION.md`
  — **except Award Custom Data, now done**; see
  `AWARD_CUSTOM_DATA_DESIGN.md` for its own design record. It was added
  directly to this same `_run_load_award_id`/`_run_load_award_batch`
  incremental path as a 5th child table, with no new top-level load
  function, no new batch domain/entity_type, and no changes to Phase
  4A's four original tables.

## Open questions

- **Child-row deletion/reconciliation.** Recommended default (not yet
  implemented or decided): never hard-delete; mark rows no longer
  returned by Oracle for their `award_id` instead (matching the
  precedent already set for attachments' `MISSING_SOURCE`/
  `MISSING_IN_ORACLE` pattern), rather than silently leaving them
  orphaned forever with no signal.
- **The ~29 unarchived `AWARD` columns** (`cfda_number`, `account_type_code`,
  `pre_award_authorized_amount`, etc. — see `AWARD_DOMAIN_STUDY.md`'s
  field-by-field diff). No evidence this was a deliberate, reviewed scope
  decision the way `award_unit_contact`/`proposal_person` was (V033).
- **Is `award_amount_info_id`/`award_person_id`/`award_funding_proposal_id`
  ever reused across award_id versions?** Design assumes not (backed by
  the shared-sequence finding), but not spot-checked against real Oracle
  data.
- **Should `AwardExtension` be archived?** A real, fully-mapped 1:1 Kuali
  child with no archive table today — not decided either way.

## Decisions

- Family-widening (not per-`award_id` isolation) is required for
  `award_version` UPSERTs, to preserve the `is_primary_current` invariant
  — this is the single most important design decision in Phase 4A.
- Batch creation for Award uses a dedicated full-scan-then-sort helper,
  not the generic framework's early-stop selection, because Award's
  Oracle source isn't sorted by the selection column.
- Deletion/reconciliation is deliberately deferred, not silently ignored
  — recorded as an open question, with a recommended default.

## Recommended implementation order

1. ~~Design: object graph, UPSERT keys, deletion-strategy question~~ —
   done.
2. ~~Bounded Oracle readers~~ — done.
3. ~~Four UPSERT functions~~ — done.
4. ~~Bounded single-award proof (`--load-award-id`)~~ — done.
5. ~~Generic batch framework integration (`--create-batch`/`--load-batch`/
   `--show-batch`)~~ — done.
6. ~~Test suite + full validation~~ — done.
7. Resolve the deletion/reconciliation open question.
8. Award CLI convergence (unified CLI, and — only if Award ever gets an
   ECS execution path — an override builder/deployment script), per
   `ETL_BATCH_FRAMEWORK.md`'s open questions.
9. ~~Tier 1: Award Custom Data~~ — done, see `AWARD_CUSTOM_DATA_DESIGN.md`.
10. Remaining Tier 1 subsystems from `AWARD_DOMAIN_DECOMPOSITION.md`.

## Date last updated

2026-07-31 (Phase 4A implementation complete; Award Custom Data —
Tier 1 — also done, see `AWARD_CUSTOM_DATA_DESIGN.md`).
