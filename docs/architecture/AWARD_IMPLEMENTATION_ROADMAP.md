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
  — **except Award Custom Data, Award People, Award Terms, Award
  Contacts, Award Attachments (`AWARD_NOTEPAD`), Award Reporting/
  Subaward Summary, Award Special Approvals and Compliance, Award
  Comment, Award Extension/Award CGB, Award Time and Money, Award
  Budget (the final Tier 2 bundle), and the final Award gap bundle
  (`BUDGET_PERSONS`/`AwardTransferringSponsor`), now done — Tier 2 is
  fully closed out and there are no more named ARCHIVE_REQUIRED gaps**;
  see
  `AWARD_CUSTOM_DATA_DESIGN.md`,
  `AWARD_PEOPLE_EXPANSION_DESIGN.md`, `AWARD_TERMS_DESIGN.md`,
  `AWARD_CONTACTS_DESIGN.md`, `AWARD_NOTEPAD_DESIGN.md`,
  `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`,
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`,
  `AWARD_COMMENT_DESIGN.md`, `AWARD_EXTENSION_CGB_DESIGN.md`,
  `AWARD_TIME_AND_MONEY_DESIGN.md`, `AWARD_BUDGET_DESIGN.md`, and
  `AWARD_COMPLETENESS_REPORT.md` for
  their own design records
  (`AWARD_COMMENT_DESIGN.md` re-confirms `AwardCgb` as real, persisted,
  un-archived data - not reclassified as NOT APPLICABLE despite an
  explicit request to do so unless DDL proved otherwise;
  `AWARD_EXTENSION_CGB_DESIGN.md` is where both `AwardExtension` and
  `AwardCgb` were actually implemented, together, as true
  1:1-with-Award tables keyed by `award_id` itself;
  `AWARD_TIME_AND_MONEY_DESIGN.md` is where `AwardHierarchy` was
  reclassified from NOT APPLICABLE and archived alongside the rest of
  the Time and Money bundle, and where `AWARD_AMOUNT_INFO` gained two
  new columns via a corrective migration rather than a duplicate
  table; `AWARD_BUDGET_DESIGN.md` is where six of the eight Budget
  tables were each merged from an Award-specific `_EXT` table plus a
  generic table shared with Proposal Development into one flattened
  archive table; `AWARD_COMPLETENESS_REPORT.md` is where `BUDGET_PERSONS`
  (previously left out of scope as a flagged gap) and
  `AwardTransferringSponsor` were reclassified ARCHIVE_REQUIRED and
  implemented as `archive.award_budget_person`/
  `archive.award_transferring_sponsor`). Every one of these child tables was added
  directly to this
  same `_run_load_award_id`/`_run_load_award_batch` incremental path,
  with no new top-level load function, no new batch
  domain/entity_type, and no changes to Phase 4A's four original
  tables or to `archive.award_person`'s existing behavior (one new
  bounded reader, `read_award_children_matching_award_numbers`, was
  added for the one award_number-keyed table,
  `award_subcontracting_budgeted_goals` - see the Special Approvals
  design doc; a further reader,
  `read_pending_transactions_matching_award_numbers`, backed by a new
  `OracleDataSource.read_filtered_any_column` method, was added for
  Time and Money's `pending_transaction`/`pending_transaction_extension`,
  neither of which has a bare `AWARD_NUMBER` column; Budget's eight
  tables plus `award_budget_person` all resolve `AWARD_ID` via their
  own extraction SQL's join
  chain to `AWARD_BUDGET_EXT` and so ride on the ordinary
  award_id-based bounded reader; `award_transferring_sponsor` carries
  `AWARD_ID` directly, the same flat shape as `award_sponsor_term`).
  SAP transmission
  remains entirely out of scope, not investigated.
- ~~`archive.award_version.basis_of_payment_code`/`method_of_payment_code`~~
  — done. Two scalar `AWARD`-level fields, not a child table,
  deliberately deferred by `AWARD_TERMS_DESIGN.md` because capturing
  them required a TRUNCATE-path change (`01_award_versions.sql`,
  `prepare_versions`, and the full load's column list); that change was
  made via a corrective migration (`V047`), not a rewrite of `V011`.
  See `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.

## Read-path performance optimization

Real-data smoke testing found one Award family took roughly three
minutes to load. Root cause: every bounded reader
(`read_award_number_for_award_id`, `read_award_versions_matching_award_numbers`,
`read_award_children_matching_award_ids`) scanned the **entire** Oracle
source for each of the (by then) thirteen extraction files and filtered
client-side in pandas — one full-table scan per table per family, for
every one of Award's now-thirteen tables.

Fixed by adding `OracleDataSource.read_filtered(column=, values=,
chunk_size=)` (`archive_etl/pipeline/sources.py`): wraps the source's
own `SELECT` as an inline view and pushes the filter down to Oracle as
a `WHERE <column> IN (:b0, :b1, ...)` bind-variable clause — never
string-interpolated literals — chunked at Oracle's 1000-element IN-list
limit (`ORA-01795`, `MAX_ORACLE_IN_LIST_SIZE`). `read()`/`read_batches()`
are unchanged and remain the full-load path's implementation
unmodified, per the explicit constraint not to touch it. All three
bounded readers were rewritten to call `read_filtered` instead of
scanning; a new `read_award_numbers_for_award_ids` batch-resolves an
entire `--load-batch` request's award_id → award_number mapping in one
chunked round trip instead of one query per award_id in the loop.
`_run_load_award_id`/`_run_load_award_batch` gained `elapsed_ms` in
their report dicts and a per-family/per-batch timing log line;
`read_filtered` itself logs each chunk's row count and elapsed time.
UPSERT behavior, transaction boundaries, batching, and idempotency are
all unchanged — this is a read-layer change only.

`_select_award_ids_ascending` (used only by `--create-batch`, to
discover which award_ids exist at all before any family is known)
deliberately still does a full scan — there is no filter to push down
when the whole point is enumerating candidates, not reading a known
family.

**Benchmarked locally** (no Oracle/AWS access available; see
`etl/scripts/benchmark_award_load_performance.py`) against a synthetic,
in-memory 20,000-family Oracle stand-in with a small artificial
per-fetch latency standing in for real network round trips:

| | old (full scan) | new (bind variables) |
|---|---|---|
| resolve 1 award_number | 57.6ms | 6.9ms |
| resolve 100 award_numbers | 5,438.6ms | 8.4ms |
| resolve 1000 award_numbers | 54,372.4ms | 18.4ms |

End-to-end (`_run_load_award_id`/`_run_load_award_batch` against a real
local throwaway PostgreSQL database, same synthetic Oracle stand-in):
one family loaded in 113ms; a 100-award_id batch in 8.4s (~84ms/family);
a 1000-award_id batch in 85.5s (~86ms/family). Per-family cost is now
dominated by genuine work (real Oracle round trips at whatever latency
actually exists, plus a real Postgres transaction), not wasted
full-table scanning — the batch-level totals scale linearly with family
count by design (`_run_load_award_batch` still loads families
sequentially, unchanged, per the constraint to preserve batching), so
they are not "seconds" in absolute terms at 1000 families, but the
per-family unit cost driving that total dropped by roughly three orders
of magnitude in the read layer specifically. The gating criterion —
"one family drops from minutes to seconds" — is met with wide margin
(113ms).

A latent bug in the SQL/column contract test's own parsing helper
(`_oracle_output_columns`) was found and fixed while building the
benchmark script's equivalent parser: naively splitting a `SELECT` list
on every comma breaks on expressions containing their own comma, like
`02_award_amounts.sql`'s `NVL(aai.ANTICIPATED_TOTAL_DIRECT, 0) + ...`.
None of the existing SQL/column contract tests happened to exercise
that file, so the bug had never been triggered; fixed in both places
with a paren-depth-aware comma splitter.

### Bulk batch load refactor

The read-path fix above removed the full-table-scan cost from each
Oracle query, but `_run_load_award_batch` still called
`_run_load_award_id` once per distinct award_number family in the
batch — meaning runtime still scaled as **families × tables** (thirteen
Oracle round trips and one Postgres transaction per family), just with
each of those thirteen round trips now cheap individually. At 1000
families that was still ~85.5s in the local benchmark (~86ms/family ×
1000).

`_run_load_award_batch` no longer calls `_run_load_award_id` at all —
it reimplements the same family-widening UPSERT logic directly, but
treats the **entire batch** as one unit of work:

1. Resolve every requested award_id's award_number in one (chunked)
   Oracle round trip (`read_award_numbers_for_award_ids`, already
   existed from the read-path fix).
2. Resolve every distinct award_number's complete version family in
   one (chunked) Oracle round trip
   (`read_award_versions_matching_award_numbers` against the union of
   every family's award_number in the batch, not one family at a
   time) — `prepare_versions`'s own primary-current ranking logic is
   already scoped per award_number via `groupby`, so passing every
   family's rows through it in one call is equivalent to, not a change
   from, calling it once per family.
3. Read each of the other twelve child tables exactly **once**,
   scoped to the union of every family's award_ids in the whole batch
   (`read_award_children_matching_award_ids`, chunked at Oracle's
   1000-element IN-list limit) — not once per family.
4. Compute each family's winning (primary-current) award_id from the
   batch-wide versions dataframe into an in-memory
   `dict[award_number, award_id]`.
5. One Postgres transaction for the **whole batch**: clear every
   family's stale `is_primary_current` flag (one `UPDATE` per distinct
   award_number, executed as a single `connection.execute()` call with
   a list of parameter dicts — still pure Postgres work, not an Oracle
   round trip), then bulk-UPSERT every table's rows across every
   family together, table by table, in the same FK-safe order already
   established (`person_unit` before `person_unit_credit_split`;
   `report_term` before `report_term_recipient`).

**This is a deliberate transaction-boundary change**, not merely an
implementation detail: the whole batch is now one transaction —
"treat the batch as one unit of work" — so a single bad row anywhere
rolls back every family in the batch, not just the families after it
in iteration order (previously, each family committed independently,
so earlier families' work survived a later family's failure). Batch
membership itself and each award_id's batch-item status update remain
separate, always-committed bookkeeping, unaffected by `dry_run` or by
a load-transaction rollback — unchanged from before, just now scoped
to the whole batch instead of per family. `_run_load_award_id` itself
(the `--load-award-id` single-family path) was not touched at all —
it still exists, still works exactly as before, and the two functions
no longer share a call relationship, only the same per-row `upsert_*`
functions.

New tests added to `RunLoadAwardBatchTest`:
`test_reads_each_oracle_table_exactly_once_for_the_whole_batch`
(asserts, via `OracleDataSource.call_args_list`, that each of the
thirteen extraction sources is constructed exactly once per batch call
— `VERSIONS_ORACLE_SQL` twice, by design, for steps 1 and 2 above —
regardless of family count);
`test_dry_run_persists_nothing_across_the_whole_batch`; and
`test_one_bad_family_rolls_back_the_whole_batch` (injects a genuine FK
violation in one family and confirms an otherwise-valid sibling
family's data is also rolled back, proving the new whole-batch
atomicity).

**Re-benchmarked locally** (same synthetic 20,000-family Oracle
stand-in as above; `etl/scripts/benchmark_award_load_performance.py`
now benchmarks batch sizes 10/100/1000 with an immediate idempotent
rerun at each scale):

| batch size | first load | rerun (idempotency check) |
|---|---|---|
| 10 families | 151.6ms | 132.2ms (inserted=0 updated=0 unchanged=13) |
| 100 families | 292.8ms | 234.3ms (inserted=0 updated=0 unchanged=129) |
| 1000 families | 1,472.0ms | 1,319.2ms (inserted=0 updated=0 unchanged=1,187) |

1000 families dropped from ~85.5s to ~1.5s (roughly 58x) — and,
critically, the scaling is now far flatter: a 10x increase in family
count (100 → 1000) increased runtime only ~5x, not ~10x, because the
Oracle-read cost (the thirteen queries) is now fixed per batch
regardless of family count; only the genuinely proportional
Postgres-write cost (one UPSERT per actual row) still scales with row
count, which is real, unavoidable work, not wasted scanning. The
real Oracle/RDS validation plan below applies unchanged to this new
implementation.

### Real Oracle/RDS batch-scale validation plan (10 → 100 → 1000)

**Prepared here, not run** — same reason as every other real-data plan
in this document set: requires the BU VPN and a real AWS SSM session,
outside what this work is authorized to run directly. The local
synthetic benchmark above proves the read-path fix; this is the plan
to confirm it against real Oracle/RDS at three increasing `--load-batch`
scales, with timing and an idempotent-rerun check at each scale.

**Two things worth knowing before running this:**
- `--create-batch` does not print or log the batch_id it assigns
  anywhere (`_run_create_award_batch`/`batch_framework.create_batch`
  only warn if fewer than the requested count were available) — the
  plan below queries `archive.etl_batch` directly via `psql` to
  capture it into a shell variable.
- `_select_award_ids_ascending` (used by `--create-batch`) always
  selects the **smallest N** award_ids in ascending order, so
  `--create-batch 100`'s membership is a superset of `--create-batch
  10`'s, and `--create-batch 1000`'s is a superset of both. The three
  scales are not independent samples — reloading the same low-numbered
  families repeatedly is expected, not a bug, and is itself a valid
  extra idempotency check. `--create-batch` itself still does a full
  Oracle scan (deliberately, per the design above — there's no filter
  to push down when the point is enumerating candidates), so its own
  timing is unrelated to the `--load-batch` fix being validated.

```bash
# --- one-time setup ---
# 1. Connect to the BU VPN; run `buaws` if AWS credentials need refreshing.
# 2. Start the approved tunnel to dev RDS (leave running in its own terminal):
aws ssm start-session \
  --region us-east-1 \
  --target i-02be522658e0f9676 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'

# 3. In a second terminal:
export POSTGRES_HOST=localhost
export POSTGRES_PORT=15432
export POSTGRES_DB=research_archive
export ORACLE_USER=...
export ORACLE_PASSWORD=...
export ORACLE_DSN=...      # host:1521/SERVICE_NAME
cd etl

# --- repeat this block for N = 10, then 100, then 1000 ---
N=10

uv run python load_awards_from_csv.py --create-batch "$N" 2>&1 | tee "create_batch_${N}.log"

BATCH_ID=$(psql -h localhost -p 15432 -U "$POSTGRES_USER" -d research_archive -tAc \
  "SELECT batch_id FROM archive.etl_batch WHERE domain='AWARD' AND entity_type='AWARD' \
   ORDER BY batch_id DESC LIMIT 1")
echo "batch_id=$BATCH_ID"

uv run python load_awards_from_csv.py --show-batch "$BATCH_ID"

# Dry run first - confirms nothing persists and reports accurate counts.
time uv run python load_awards_from_csv.py --load-batch "$BATCH_ID" --dry-run \
  2>&1 | tee "load_batch_${N}_dry_run.log"

# Real load - first pass. Check the tee'd log for
# "families_loaded=... in <elapsed_ms>ms" and each table's
# inserted/updated/unchanged counts.
time uv run python load_awards_from_csv.py --load-batch "$BATCH_ID" \
  2>&1 | tee "load_batch_${N}_first_run.log"

uv run python load_awards_from_csv.py --show-batch "$BATCH_ID"   # expect status=READY

# Immediate rerun - the idempotency proof. Every table's
# inserted/updated must be 0 and unchanged>0 in this second log
# (unchanged=0 for a table is legitimate only if that family genuinely
# has zero rows there - not a bug).
time uv run python load_awards_from_csv.py --load-batch "$BATCH_ID" \
  2>&1 | tee "load_batch_${N}_rerun.log"

grep "families_loaded" "load_batch_${N}_first_run.log" "load_batch_${N}_rerun.log"

uv run python scripts/reconcile_load.py --domain AWARD --limit 5
```

Compare the `elapsed_ms`/wall-clock time reported at each scale (10,
100, 1000) to confirm sub-linear-per-family or at-worst-linear scaling
(no unexpected superlinear blowup), and confirm every rerun's log line
shows `inserted=0 updated=0` with `unchanged>0` for every table that
had rows in the first pass.

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
- ~~**Should `AwardExtension` be archived?**~~ Resolved: yes, both
  `AwardExtension` and `AwardCgb` are real, confirmed 1:1 Award
  extension tables and are now archived as `archive.award_extension`/
  `archive.award_cgb` — see `AWARD_EXTENSION_CGB_DESIGN.md`.

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
10. ~~Tier 1: Award People (AWARD_PERSON_UNITS/AWARD_PERSON_CREDIT_SPLITS/
    AWARD_PERS_UNIT_CRED_SPLITS)~~ — done, see
    `AWARD_PEOPLE_EXPANSION_DESIGN.md`.
11. ~~Tier 1: Award Terms (AWARD_SPONSOR_TERM/AWARD_REPORT_TERMS/
    AWARD_REP_TERMS_RECNT)~~ — done, see `AWARD_TERMS_DESIGN.md`.
12. ~~Tier 1: Award Contacts (AWARD_SPONSOR_CONTACTS/AWARD_UNIT_CONTACTS)~~
    — done, see `AWARD_CONTACTS_DESIGN.md`.
13. ~~Tier 1: Award Attachments (AWARD_NOTEPAD)~~ — done, see
    `AWARD_NOTEPAD_DESIGN.md`.
14. ~~Tier 1: Award Reporting (AWARD_CLOSEOUT/AWARD_PAYMENT_SCHEDULE),
    Award Subaward Summary (AWARD_APPROVED_SUBAWARDS)~~ — done, see
    `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`.
15. ~~Tier 1: Award Special Approvals and Compliance (AWARD_CFDA/
    AWARD_COST_SHARE/AWARD_IDC_RATE/AWARD_SCIENCE_KEYWORD/
    AWARD_SPECIAL_REVIEW/AWARD_EXEMPT_NUMBER/
    AWARD_APPROVED_EQUIPMENT/AWARD_APPROVED_FOREIGN_TRAVEL/
    SUBCONTRACTING_BUD)~~ — done, see
    `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`.
16. ~~Tier 1: Award Comment (AWARD_COMMENT)~~ — done, see
    `AWARD_COMMENT_DESIGN.md`. `AwardCgb` re-investigated in the same
    pass per an explicit reclassification request and confirmed real
    (not reclassified).
17. ~~Tier 1: Award Extension and Award CGB (AWARD_EXTENSION/
    AWARD_CGB)~~ — done, see `AWARD_EXTENSION_CGB_DESIGN.md`.
18. ~~Basis of payment / method of payment field completion on `Award`
    itself~~ — done, see `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
19. ~~Tier 2: Award Time and Money (AWARD_HIERARCHY/
    TIME_AND_MONEY_DOCUMENT/PENDING_TRANSACTIONS/
    PENDING_TRANSACTIONS_EXTENSION/TRANSACTION_DETAILS/
    AWARD_AMOUNT_TRANSACTION/AWARD_AMT_FNA_DISTRIBUTION, plus two new
    `AWARD_AMOUNT_INFO` columns)~~ — done, see
    `AWARD_TIME_AND_MONEY_DESIGN.md`.
20. ~~Tier 2: Award Budget (AWARD_BUDGET_EXT/AWARD_BUDGET_PERIOD_EXT/
    AWARD_BUDGET_DETAILS_EXT/AWD_BGT_DET_CAL_AMTS_EXT/
    AWD_BUDGET_PER_DET_EXT/AWD_BUDGET_PER_CAL_AMTS_EXT/
    AWD_BGT_PER_SUM_CALC_AMT/AWARD_BUDGET_LIMIT)~~ — done, see
    `AWARD_BUDGET_DESIGN.md`. Tier 2 is now fully closed out.
21. ~~Final Award gap bundle (BUDGET_PERSONS/AWARD_TRANSFERRING_SPONSOR,
    both reclassified ARCHIVE_REQUIRED by AWARD_COMPLETENESS_REPORT.md)~~
    — done, see `AWARD_COMPLETENESS_REPORT.md`.
22. Final Award field/table reconciliation and completion report.

## Date last updated

2026-07-31 (Phase 4A implementation complete; Award Custom Data, Award
People, Award Terms, Award Contacts, Award Attachments/Notepad, Award
Reporting/Subaward Summary, Award Special Approvals and Compliance,
Award Comment, and Award Extension/Award CGB — all Tier 1 — also done,
see `AWARD_CUSTOM_DATA_DESIGN.md`, `AWARD_PEOPLE_EXPANSION_DESIGN.md`,
`AWARD_TERMS_DESIGN.md`, `AWARD_CONTACTS_DESIGN.md`,
`AWARD_NOTEPAD_DESIGN.md`,
`AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`,
`AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`,
`AWARD_COMMENT_DESIGN.md`, `AWARD_EXTENSION_CGB_DESIGN.md`,
`AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`, and (Tier 2, pulled forward
ahead of Budget) `AWARD_TIME_AND_MONEY_DESIGN.md`;
read-path
performance optimization — bind-variable WHERE pushdown replacing
full-table-scan bounded readers — also done, see "Read-path performance
optimization" above; `_run_load_award_batch` refactored to treat the
whole batch as one bulk unit of work instead of looping over
`_run_load_award_id` per family — see "Bulk batch load refactor" above
— dropping the local 1000-family benchmark from ~85.5s to ~1.5s; real
Oracle/RDS batch-scale (10/100/1000) validation plan prepared, since
run — see the real-data validation record at the end of this section.
Same-day follow-up: a separately-reported `AwardCostShare.FISCAL_YEAR`
correction also landed — see `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`.
Second same-day follow-up: basis of payment / method of payment field
completion on `Award` itself, via a new corrective migration `V047`
rather than a rewrite of `V011` — see
`AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`. Third same-day follow-up:
Tier 2 Award Time and Money implemented in full — a one-research-pass-
first design record was written and reviewed before any code (see
`AWARD_TIME_AND_MONEY_DESIGN.md`), `AwardHierarchy` was reclassified
from NOT APPLICABLE and archived alongside it, and
`archive.award_amount_info` was extended in place via a second
corrective migration, `V048`, rather than duplicated as a new table).
Fourth same-day follow-up: Tier 2 Award Budget implemented in full,
closing out Tier 2 entirely — the deepest (5-level) bundle in the
Award domain, six of its eight tables merging an Award-specific `_EXT`
table into a generic table shared with Proposal Development (`BUDGET`,
`BUDGET_PERIODS`, `BUDGET_DETAILS`, `BUDGET_DETAILS_CAL_AMTS`,
`BUDGET_PERSONNEL_DETAILS`, `BUDGET_PERSONNEL_CAL_AMTS`) via a real,
Oracle-enforced FK — the first confirmed case of that in this project.
`previousObligatedTotal` and `BUDGET.FINAL_VERSION_FLAG` were excluded
for lacking corroborating evidence in either OJB or DDL;
`BUDGET_PERSONS` was found but left deliberately out of scope. See
`AWARD_BUDGET_DESIGN.md`.

2026-08-01: Fifth same-day follow-up: the final Award gap bundle
implemented in full - `AWARD_COMPLETENESS_REPORT.md` reclassified
`BUDGET_PERSONS` and `AwardTransferringSponsor` as ARCHIVE_REQUIRED,
and both were archived as `archive.award_budget_person`/
`archive.award_transferring_sponsor`, reusing already-proven patterns
(the join-through-`AWARD_BUDGET_EXT` scoping pattern for
`BUDGET_PERSONS`, the `archive.award_sponsor_term` shape for
`AwardTransferringSponsor`) rather than requiring new design work.

2026-08-01: Sixth same-day follow-up: SAP Award Transmission History
archived, closing out the separate integration-history subsystem that
`SAP_AWARD_TRANSMISSION_ASSESSMENT.md` identified as needing its own
archive — `archive.award_transmission`/`archive.award_transmission_child`
implemented via a new migration `V052`, extraction SQL scoped
independently per table (each filtered on its own `AWARD_ID`, not a
two-step parent-then-child join), and full
`--load-award-id`/`--load-batch` wiring, keyed by Oracle's own real
surrogate PKs so retransmissions are preserved as immutable history by
construction rather than by special-case logic.
`award_transmission_child.transmission_id` is a deliberate bare,
unenforced column — its parent transmission's root Award routinely
belongs to a different `award_number` family, which this project's
incremental per-award loading can't guarantee is loaded first. Still
classified as a subsystem separate from the core Award domain, not
counted toward its completeness. See
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`. `BUDGET_RATE_AND_BASE`,
found incidentally during the SAP assessment, remains open and
unevaluated — flagged in `AWARD_COMPLETENESS_REPORT.md`'s "Open item:
BUDGET_RATE_AND_BASE" as the next piece of work; not scoped into this
bundle.

2026-08-01: Seventh same-day follow-up: fixed the last remaining piece
of technical debt on the legacy full-load path -
`clear_existing_award_data()` still only truncated the original four
tables (`award_version`/`award_amount_info`/`award_person`/
`award_funding_proposal`), which had started failing with a foreign-key
violation now that dozens of later-bundle tables reference those four
parents. Rewritten to clear all 48 Award-owned tables through V052 via
one explicit, ordered, combined `TRUNCATE` (not `CASCADE`), built from a
table list cross-verified against a real Postgres instance with every
migration applied - confirmed no Proposal, Negotiation, Protocol,
Subaward, or Attachment table has any FK into the set. Does not change
`--load-award-id`/`--load-batch` behavior at all - neither path calls
this function. See `AWARD_FULL_LOAD_RESET.md`.

2026-08-01: Eighth same-day follow-up: completed the Award ECS runtime
integration on top of the scaffolding from the sixth follow-up above
(`scripts/run-award-loader.sh`, `etl/scripts/build_award_ecs_overrides.py`),
reusing the proven Award Attachment ECS pattern without duplicating its
secret-parsing logic:

- `etl/archive_etl/__main__.py`'s `award` domain now forwards `--ecs`,
  `--migrate-only`, `--load-award-id`, `--create-batch`, `--load-batch`,
  `--show-batch`, `--dry-run` (previously only `--limit`) - the same
  table-driven `_EXTRA_DOMAIN_ARGUMENTS` mechanism `award-attachment`
  already used, extended rather than special-cased.
- `load_awards_from_csv.py` gained a `--ecs`/`--migrate-only` mode
  mirroring `load_award_attachments.py`'s own `_run_ecs_setup` exactly in
  sequence (structured logging, AWS identity, Secrets Manager
  credentials, PostgreSQL connectivity, then either migrate+validate+exit
  for `--migrate-only`, the batch report+exit for `--show-batch`, or
  Oracle connectivity before falling through to the existing, completely
  unchanged `_run_load_award_id`/`_run_load_award_batch`/
  `_run_create_award_batch` - so every existing guarantee (Oracle
  1,000-value chunking, one Oracle read per source table per batch, one
  PostgreSQL transaction per batch, idempotent UPSERTs) is preserved by
  construction, not re-verified from scratch. Reuses
  `archive_etl.config.ecs.configure_ecs_environment` unchanged (zero
  secret-parsing duplication); added one new shared, generic
  `validate_aws_identity()` to `archive_etl.config.startup_validation`
  (`load_award_attachments.py` itself was not touched - it keeps its own
  private, pre-existing equivalent) since no shared version existed
  before this pass.
- `etl/Dockerfile.loader` now also copies `load_awards_from_csv.py`,
  `sql/extract/award/` (all 48 files), and reuses the already-copied
  `database/migrations/` (through V052). `load_awards_from_csv.py` gained
  its own `_resolve_project_root()` (mirroring
  `load_award_attachments.py`'s dual local/container-layout detection
  exactly, adapted to check for `sql/` instead of `oracle/` as the
  container-mode marker) so its existing Oracle-extraction-SQL/migration
  path constants resolve correctly in both layouts.
- Verified for real, not just via mocked tests: built the actual Docker
  image and ran `python -m archive_etl award --help` and
  `python -m archive_etl award --ecs --migrate-only` inside a real
  container (see `tests/test_loader_image_layout.py`'s new `award`
  coverage) - confirmed the full file layout, module imports, and
  fail-closed AWS-identity check all work end to end, not merely that
  argparse accepts the flags.
- `etl/scripts/build_award_ecs_overrides.py`/`scripts/run-award-loader.sh`
  updated to match: `--ecs` is now unconditionally baked into the
  generated command (matching `build_award_attachment_ecs_overrides.py`'s
  own convention exactly - no `--ecs` CLI flag on the override-builder
  itself), `--load-award-id` added end to end (builder, shell script
  flag parsing, validation, Oracle-secret requirement), and the
  previously-documented "KNOWN GAP" header in both files removed now
  that the gap is closed.

**Standing technical debt, explicitly not addressed by this pass**:
`terraform/environments/prod` and `terraform/environments/test` still do
not pass the ECS module's `documents_bucket_arn`/`documents_bucket_name`/
`oracle_secret_arn` arguments to `module "loader_ecs"`, so
`terraform validate` fails for both (confirmed pre-existing on
`feature/award-attachment-s3-loader`'s own tip before it was ever merged
into `main` - not something either this pass or the merge introduced).
This does not block the dev Award ECS runtime documented above, but it
must be resolved - by deliberate decision, not by copying dev's wiring
blindly - before the Award (or Award Attachment) loader is promoted to
`test` or `prod`.

2026-08-01: Ninth same-day follow-up: `--create-batch` now defaults to a
**production** selection mode that advances through the Award population
across repeated calls, instead of always reselecting the same smallest N
award_ids. Prompted by a research pass confirming the prior always-
overlapping behavior - intentional for the 10→100→1000 validation-scale
test plan documented above, but never designed for ongoing production
loading - wasted Oracle reads and reprocessing on every repeated call.
Production mode excludes award_ids already `COMPLETED` as an
`etl_batch_item` (regardless of that item's own batch's overall status)
and award_ids claimed by a still-active (`READY`/`PROCESSING`) batch;
`FAILED`/`PENDING` items in an already-resolved batch remain eligible, by
design - production selection never permanently skips a failure. Reuses
the shared `batch_framework.select_distinct_ascending_from_oracle_batches`
early-stop helper (the same one Award Attachment's own `_run_create_batch`
already uses for `FILE_ID`) against a new, narrowly-scoped `ORDER BY
AWARD_ID` Oracle query (`sql/extract/award/award_ids_ascending.sql` -
`01_award_versions.sql` itself is `ORDER BY AWARD_NUMBER, SEQUENCE_NUMBER`
and can't support early-stopping by `award_id`), so a production call
never loads the entire Oracle Award population into memory. The original
always-smallest-N behavior is preserved unchanged, opt-in only, via a new
`--validation-overlap` flag. Does not touch `--load-award-id`,
`--load-batch`, `--show-batch`, or any UPSERT logic. See
`AWARD_BATCH_PRODUCTION_SELECTION_DESIGN.md`.

**Real-data validation record.** This session's own environment has no
BU Oracle/VPN credentials configured (confirmed via
`scripts/test_oracle_connection.py`, which fails with a configuration
error) and so could not itself run any of the following - they were
run separately, from a BU-VPN-connected environment with real Oracle
and local Postgres access, and are recorded here as completed rather
than left showing the prior "not yet run"/"unverified" status:

- Award family 52: reloaded twice via `--load-award-id`, confirmed
  idempotent (second run reports `unchanged` across every table in the
  family, no `inserted`/`updated`).
- Batch-scale validation at 10, 100, and 1000 Award batch sizes via
  `--create-batch`/`--load-batch`, confirming the bulk-batch refactor
  (see "Bulk batch load refactor" above) completes correctly and
  within expected time at each scale against real Oracle/RDS.
- Award Comment family 203074-00001: loaded and confirmed against real
  BU Oracle, including the comment-vs-notepad distinction
  `AWARD_COMMENT_DESIGN.md` documents.
- Award Time and Money family 209899-00012: loaded and confirmed
  against real BU Oracle, including the `AwardHierarchy`
  parent/child walk and the Pending Transaction/Transaction Detail/
  Award Amount Transaction chain.
- Award Budget family 201796-00002: loaded and confirmed against real
  BU Oracle, including the full 5-level parent/child chain down to
  `award_budget_personnel_calculated_amount`.
- Oracle-versus-archive Budget row counts reconciled directly against
  real BU Oracle for the families above.
- The `AwardCostShare`/`AwardCgb` real-schema corrections already
  recorded in `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`/
  `AWARD_EXTENSION_CGB_DESIGN.md` (the `FISCAL_YEAR` column that does
  not exist in real BU Oracle despite appearing in the generic Kuali
  bootstrap DDL, and the `AWARD_CGB` real-table confirmation) were
  re-confirmed against real BU Oracle as part of this same validation
  pass.

`BUDGET_PERSONS`/`AwardTransferringSponsor` themselves were not named
in the families validated above and so remain schema/mapping-verified
only, not yet real-data-verified - see `AWARD_COMPLETENESS_REPORT.md`'s
verdict for what that means for declaring Award complete.
`archive.award_transmission`/`archive.award_transmission_child` (the
sixth same-day follow-up above) are likewise schema/mapping-verified
only - real BU Oracle DDL confirmation and a real-data smoke test are
both still open, see `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`'s Real-
data smoke-test plan.
