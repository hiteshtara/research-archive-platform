# Award Budget — Object Graph and Implementation Record

## Status

Implemented. Research (Phase 1) completed and reviewed before any code
was written (Phase 2), per explicit instruction. This is the deepest,
most interconnected Award bundle in the project — five levels of
parent/child nesting, and every level is a **shared, table-per-subclass
Oracle table also used by Proposal Development budgets**, not an
Award-only table. That sharing is the single most important fact in
this document; see Findings.

## Purpose

Archive the full Award Budget subsystem: the budget document itself,
its periods, line items, personnel detail, and every calculated-amount
table beneath them, plus the two small Award-specific budget lookups
and the budget limit table.

## Scope

`AWARD_BUDGET_EXT`, `BUDGET` (the two merged into one archive table —
see Findings), `AWARD_BUDGET_PERIOD_EXT`/`BUDGET_PERIODS`,
`AWARD_BUDGET_DETAILS_EXT`/`BUDGET_DETAILS`,
`AWD_BGT_DET_CAL_AMTS_EXT`/`BUDGET_DETAILS_CAL_AMTS`,
`AWD_BUDGET_PER_DET_EXT`/`BUDGET_PERSONNEL_DETAILS`,
`AWD_BUDGET_PER_CAL_AMTS_EXT`/`BUDGET_PERSONNEL_CAL_AMTS`,
`AWD_BGT_PER_SUM_CALC_AMT`, `AWARD_BUDGET_LIMIT`,
`AWARD_BUDGET_TYPE`, `AWARD_BUDGET_STATUS`. Does not touch
`BUDGET_DOCUMENT` (pure KEW envelope, no business content — see
Findings), `BUDGET_PERSONS`/`BUDGET_PERSON_SALARY_DETAILS` (a real,
confirmed-Award-specific personnel roster discovered during this
research pass but not part of the requested scope — see Open
Questions), Proposal Development's own budget classes/tables, or SAP.

## Source material used

- DataDictionary: `AwardBudgetDocument.xml`, `AwardBudgetExt.xml`,
  `AwardBudgetLimit.xml`, `AwardBudgetLineItemCalculatedAmountExt.xml`,
  `AwardBudgetLineItemExt.xml`, `AwardBudgetPeriodExt.xml`,
  `AwardBudgetPeriodSummaryCalculatedAmount.xml`,
  `AwardBudgetPersonnelCalculatedLineitemExt.xml`,
  `AwardBudgetPersonnelDetailsExt.xml`, `AwardBudgetStatus.xml`,
  `AwardBudgetType.xml`.
- OJB mapping: `coeus-impl/src/main/resources/org/kuali/coeus/common/budget/impl/repository-budget.xml`
  (1348 lines — read in full). The Award-specific class-descriptors
  live at lines 1029–1252; the generic/shared classes they extend live
  at lines 24–890. `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  line 256 (`Award.currentVersionBudgets` → `AwardBudgetExt`, inverse
  FK `awardId` — confirms the direct Award relationship).
- Generic Kuali Coeus bootstrap DDL
  (`coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V300_107__schema.sql`)
  for every table's base `CREATE TABLE`, plus later incremental
  migrations for every column/constraint added afterward
  (`V310_2_031`, `V310_2_036`, `V300_258__schema-constraints.sql`,
  `V600_046`, `V600_049`, `V1601_003`, `V311_042`, `V310_3_033`,
  `V310_3_036`, `V310_3_066`).
- `bu-db/` (every file): no BU-specific customization found for any
  Award Budget table — confirmed via a case-insensitive grep across
  the entire directory for every table name in scope.
- Current archive state: `docs/architecture/KUALI_ARCHIVE_COVERAGE.md`
  and `docs/architecture/AWARD_DOMAIN_DECOMPOSITION.md` (both already
  tracked this as an 8-table Tier 2 deferral before this bundle).

## Findings

### The central fact: every table here is shared with Proposal Development, via table-per-subclass inheritance

`BUDGET`, `BUDGET_PERIODS`, `BUDGET_DETAILS`, `BUDGET_DETAILS_CAL_AMTS`,
`BUDGET_PERSONNEL_DETAILS`, and `BUDGET_PERSONNEL_CAL_AMTS` are **not**
Award-only tables — they are the generic budget tables Kuali's
`BudgetParentDocument` OJB factory explicitly extends for **both**
`ProposalDevelopmentDocument` and `AwardDocument`
(repository-budget.xml line 18–21). `AWARD_BUDGET_EXT`,
`AWARD_BUDGET_PERIOD_EXT`, `AWARD_BUDGET_DETAILS_EXT`,
`AWD_BGT_DET_CAL_AMTS_EXT`, `AWD_BUDGET_PER_DET_EXT`, and
`AWD_BUDGET_PER_CAL_AMTS_EXT` are Award-specific **extension** tables
in the strict OJB "super" sense (each declares a `<reference-descriptor
name="super" class-ref="...">`) — a real, Oracle-enforced 1:1
relationship sharing the exact same PK value as its generic parent
(confirmed via `V300_258__schema-constraints.sql`'s six real FK
constraints, one per level — `FK_AWARD_BUDGET_EXT`,
`AWARD_BUDGET_PERIOD_EXT`, `FK_AWARD_BUDGET_DETAILS_EXT`,
`FK_AWD_BGT_DET_CAL_AMTS_EXT`, `FK_AWD_BUDGET_PER_DET_EXT`,
`FK_AWD_BGT_PER_CAL_AMTS_EXT`). **This is the first confirmed case in
the whole Award domain of a real, Oracle-enforced FK between two
archived-in-this-project tables** — every other cross-table
relationship found so far (Comment, Extension/CGB, Time and Money) has
been Java/OJB-layer only.

Two consequences follow directly:
1. **The archive merges each generic/Ext pair into one flattened
   table**, keyed by the shared PK, exactly the same "smallest complete
   change" reasoning already used for `archive.award_extension`/
   `archive.award_cgb` (1:1 extension, shared PK) — just applied five
   times in a nested chain instead of once. The generic table holds
   almost all the substantive business content (dates, costs,
   comments/justification, name); the Ext table contributes only the
   Award discriminator and a handful of Award-specific fields
   (`obligated_amount` at every level, plus `award_id`/status/type
   codes on the root).
2. **Filtering to "Award budgets only" is a plain INNER JOIN, not a
   WHERE clause**: every extraction query joins the generic table to
   its Ext counterpart, and that join itself is what excludes Proposal
   Development's own budget rows (which have no matching Ext row at
   all). No column on the generic tables themselves distinguishes
   "which kind of budget this is" — confirmed by `BUDGET`'s own
   `PROPOSAL_NUMBER`/`VERSION_NUMBER` columns and its
   `PK_BUDGET_KRA` index on exactly those two columns, a purely
   Proposal-oriented natural key that is `NULL` for every Award budget
   row.

### `AwardBudgetPeriodSummaryCalculatedAmount` is one table serving two logical roles

`AwardBudgetPeriodExt` declares **two** collection-descriptors against
the exact same class/table (`AWD_BGT_PER_SUM_CALC_AMT`):
`awardBudgetPeriodFringeAmounts` (query-customizer filters
`rateClassType='E'`) and `awardBudgetPeriodFnAAmounts` (filters
`rateClassType='O'`). This is not two tables — it is one table whose
`rate_class_type` column (`'E'` or `'O'`) distinguishes fringe/employee-
benefit amounts from F&A/overhead amounts at query time. The archive
keeps it as one table with `rate_class_type` intact, the same
distinguishing approach Kuali itself uses, rather than splitting it in
two.

### `AwardBudgetVersionOverviewExt` is not an 8th table

A second Java class, `AwardBudgetVersionOverviewExt`, is also mapped to
`AWARD_BUDGET_EXT` (repository-budget.xml line 1090) — a lighter-weight
projection used for version-listing screens. It is the same physical
table as `AwardBudgetExt`, not a separate one; no separate archive
table is needed for it.

### `BUDGET_DOCUMENT` stays NOT APPLICABLE — genuinely different from `TIME_AND_MONEY_DOCUMENT`

`BUDGET_DOCUMENT`'s own OJB mapping has no business fields at all —
only `documentNumber` (PK), `updateTimestamp`/`updateUser`/`objectId`/
`versionNumber`, and the `budgets` collection. Confirmed against DDL:
its real columns are `DOCUMENT_NUMBER`, `PARENT_DOCUMENT_KEY`,
`PARENT_DOCUMENT_TYPE_CODE`, plus the standard audit columns — pure KEW
routing/parent-linkage metadata, no award linkage of its own (the real
Award linkage lives directly on `AWARD_BUDGET_EXT.AWARD_ID`). This is
genuinely different from `TIME_AND_MONEY_DOCUMENT`, which had real
business fields (`rootAwardNumber`, `documentStatus`) justifying
archival — the two are not treated inconsistently, they are actually
different in kind. `document_number` is still captured as a bare
reference column on `archive.award_budget` (matching the existing
`archive.award_version.document_number`-style precedent), just without
its own archive table.

### `Award.currentVersionBudgets` confirms the direct Award relationship

`AWARD_BUDGET_EXT.AWARD_ID` (added later — see Traps) is Award's own
declared collection FK (`Award.currentVersionBudgets`, inverse FK
`awardId`), not an inferred join. `archive.award_budget.award_id`
carries a real Postgres FK to `archive.award_version(award_id)`,
mirroring Oracle's own real constraint.

## The complete object graph

```
Award (already archived)
└── currentVersionBudgets (1:many, FK award_id) -> AwardBudgetExt + Budget [MERGED as archive.award_budget]
    ├── budgetPeriods (1:many, FK budget_id) -> AwardBudgetPeriodExt + BudgetPeriod [MERGED as archive.award_budget_period]
    │   ├── budgetLineItems (1:many, FK budget_period_id) -> AwardBudgetLineItemExt + BudgetLineItem [MERGED as archive.award_budget_line_item]
    │   │   ├── budgetLineItemCalculatedAmounts (1:many, FK budget_line_item_id) -> AwardBudgetLineItemCalculatedAmountExt + BudgetLineItemCalculatedAmount [MERGED as archive.award_budget_line_item_calculated_amount]
    │   │   └── budgetPersonnelDetailsList (1:many, FK budget_line_item_id) -> AwardBudgetPersonnelDetailsExt + BudgetPersonnelDetails [MERGED as archive.award_budget_personnel_detail]
    │   │       │     (bare reference: person_sequence_number -> BUDGET_PERSONS, NOT archived - see Open Questions)
    │   │       └── budgetPersonnelCalculatedAmounts (1:many, FK budget_personnel_line_item_id) -> AwardBudgetPersonnelCalculatedAmountExt + BudgetPersonnelCalculatedAmount [MERGED as archive.award_budget_personnel_calculated_amount]
    │   └── awardBudgetPeriodFringeAmounts + awardBudgetPeriodFnAAmounts (1:many, FK budget_period_id, same table, split by rate_class_type) -> AwardBudgetPeriodSummaryCalculatedAmount [archive.award_budget_period_summary_calculated_amount]
    └── awardBudgetLimits (1:many, FK budget_id; also FK award_id directly) -> AwardBudgetLimit [archive.award_budget_limit]

AwardBudgetStatus (AWARD_BUDGET_STATUS) - lookup, denormalized as award_budget_status_description
AwardBudgetType (AWARD_BUDGET_TYPE) - lookup, denormalized as award_budget_type_description
BudgetDocument (BUDGET_DOCUMENT) - pure KEW envelope, NOT APPLICABLE - document_number kept as a bare column on archive.award_budget only
```

## Final table graph and archive names

| Archive table | Merged from | PK (shared value) | Real Oracle FK |
|---|---|---|---|
| `archive.award_budget` | `AWARD_BUDGET_EXT` + `BUDGET` | `budget_id` | `award_id` → `AWARD`; `award_budget_status_code` → `AWARD_BUDGET_STATUS`; `award_budget_type_code` → `AWARD_BUDGET_TYPE` |
| `archive.award_budget_period` | `AWARD_BUDGET_PERIOD_EXT` + `BUDGET_PERIODS` | `budget_period_id` (Oracle column `BUDGET_PERIOD_NUMBER`) | none declared to `BUDGET.BUDGET_ID` (Java/OJB-layer only) |
| `archive.award_budget_line_item` | `AWARD_BUDGET_DETAILS_EXT` + `BUDGET_DETAILS` | `budget_line_item_id` (Oracle column `BUDGET_DETAILS_ID`) | none declared to `BUDGET_PERIODS` (Java/OJB-layer only) |
| `archive.award_budget_line_item_calculated_amount` | `AWD_BGT_DET_CAL_AMTS_EXT` + `BUDGET_DETAILS_CAL_AMTS` | `budget_line_item_calculated_amount_id` | none declared to `BUDGET_DETAILS` (Java/OJB-layer only) |
| `archive.award_budget_personnel_detail` | `AWD_BUDGET_PER_DET_EXT` + `BUDGET_PERSONNEL_DETAILS` | `budget_personnel_line_item_id` | none declared to `BUDGET_DETAILS` (Java/OJB-layer only) |
| `archive.award_budget_personnel_calculated_amount` | `AWD_BUDGET_PER_CAL_AMTS_EXT` + `BUDGET_PERSONNEL_CAL_AMTS` | `budget_personnel_calculated_amount_id` | none declared to `BUDGET_PERSONNEL_DETAILS` (Java/OJB-layer only) |
| `archive.award_budget_period_summary_calculated_amount` | `AWD_BGT_PER_SUM_CALC_AMT` (standalone) | `award_budget_period_summary_calculated_amount_id` | none declared (Java/OJB-layer only) |
| `archive.award_budget_limit` | `AWARD_BUDGET_LIMIT` (standalone) | `budget_limit_id` | real: `award_id` → `AWARD`, `budget_id` → `AWARD_BUDGET_EXT` |

Only the six generic/Ext pairs themselves have real, Oracle-enforced FK
constraints (confirmed above) — every *other* relationship in this
hierarchy (period→line item, line item→calculated amount/personnel
detail, personnel detail→personnel calculated amount,
period→period-summary, budget→limit's `budget_id`) is Java/OJB-layer
only, exactly the pattern already established everywhere else in the
Award domain. `archive.award_budget_limit`'s two FKs (`award_id`,
`budget_id`) **are** real Oracle constraints and are mirrored as real
Postgres FKs.

## Nullable relationships

- `award_budget.award_id`: NOT NULL in real Oracle (added by `V600_046`,
  backfilled, then `MODIFY ... NOT NULL`) — archived as `NOT NULL` with
  a real FK.
- `award_budget_period.budget_id`, `award_budget_line_item.budget_period_id`
  /`.budget_id`, `award_budget_personnel_detail.budget_line_item_id`
  /`.budget_period_id`/`.budget_id`, and every other cross-level
  reference: nullable in the real Oracle DDL (no `NOT NULL` found for
  any of them) even though functionally always populated. Archived as
  nullable, matching Oracle exactly rather than asserting a stronger
  constraint than the source system itself does.
- `award_budget_limit.award_id`/`.budget_id`: nullable in Oracle despite
  having real FK constraints — a row can apparently exist with either
  reference unset. Archived as nullable with the FK still enforced
  (Postgres FKs permit `NULL` by default).

## Status and version behavior

- `award_budget_status_code`/`award_budget_type_code` are genuine,
  small Award-specific lookups (3 rows each expected, `VARCHAR2(3)` PK)
  — denormalized via `LEFT JOIN`, the same convention used for
  `AWARD_STATUS`/`AWARD_TRANSACTION_TYPE` in `01_award_versions.sql`
  and `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` in the Basis/
  Method of Payment bundle.
- `budget_version_number` (from generic `BUDGET.VERSION_NUMBER`) is the
  **budget's own** version counter — completely independent of
  `archive.award_version.sequence_number` (the Award's own version).
  A single Award version can have multiple budget versions over time
  (each a full new `AWARD_BUDGET_EXT`/`BUDGET` row pair); do not
  conflate the two version concepts.
- No workflow/document-status column exists directly on
  `AWARD_BUDGET_EXT`/`BUDGET` beyond `award_budget_status_code` itself
  (e.g. `'A'` active, `'P'` pending, whatever the three
  `AWARD_BUDGET_STATUS` rows turn out to be — not enumerated here,
  kept as data, not hardcoded).

## Budget document identity

`archive.award_budget.document_number` is a bare reference to the KEW
document number of the `BUDGET_DOCUMENT` row that owns this budget —
kept as a plain column (no archive table, no FK), the same treatment
already given to other pure-envelope document numbers referenced
in-passing elsewhere in this project.

## Hierarchy depth and load order

Five levels deep — the deepest hierarchy in the Award domain, exactly
as `AWARD_DOMAIN_DECOMPOSITION.md` anticipated:

1. `award_budget` (root; depends only on the already-archived
   `award_version`)
2. `award_budget_limit` (depends on `award_budget` and `award_version`
   directly — can load immediately after step 1, does not depend on
   any deeper level)
3. `award_budget_period` (depends on `award_budget`)
4. `award_budget_line_item` and `award_budget_period_summary_calculated_amount`
   (both depend on `award_budget_period` — siblings, either order)
5. `award_budget_line_item_calculated_amount` and
   `award_budget_personnel_detail` (both depend on
   `award_budget_line_item` — siblings, either order)
6. `award_budget_personnel_calculated_amount` (depends on
   `award_budget_personnel_detail`)

## Code/lookup fields

Kept as bare, unjoined codes (consistent with every other
not-independently-verified lookup in this project):
`oh_rate_class_code`/`oh_rate_type_code`/`ur_rate_class_code` (rate
classification), `budget_category_code`, `cost_element`,
`rate_class_code`/`rate_type_code` (plus the denormalized
`rate_type_description`, which Oracle **already** denormalizes onto
`BUDGET_DETAILS_CAL_AMTS`/`BUDGET_PERSONNEL_CAL_AMTS` themselves — not
something this project computed), `job_code`, `period_type_code`,
`limit_type_code`. Denormalized via `LEFT JOIN` (genuinely small,
Award-specific, already-proven-pattern lookups):
`award_budget_status_code`/`award_budget_type_code`.

## Oracle sequences

- `SEQ_BUDGET_ID` (generic `BUDGET`) — `award_budget.budget_id`
- `SEQ_BUDGET_PERIOD_NUMBER` (generic `BUDGET_PERIODS`) —
  `award_budget_period.budget_period_id`
- `SEQ_BUDGET_DETAILS_ID` (generic `BUDGET_DETAILS`) —
  `award_budget_line_item.budget_line_item_id`
- `SEQ_BUDGET_DETAILS_CAL_AMTS_ID` (generic
  `BUDGET_DETAILS_CAL_AMTS`) —
  `award_budget_line_item_calculated_amount.budget_line_item_calculated_amount_id`
- `SEQ_BUDGET_PER_DET_ID` (generic `BUDGET_PERSONNEL_DETAILS`) —
  `award_budget_personnel_detail.budget_personnel_line_item_id`
- `SEQ_BUDGET_PER_CAL_AMTS_ID` (generic `BUDGET_PERSONNEL_CAL_AMTS`) —
  `award_budget_personnel_calculated_amount.budget_personnel_calculated_amount_id`
- `SEQ_BGT_SUM_PER_CALC_AMT_ID` — `award_budget_period_summary_calculated_amount`'s
  own id
- `SEQ_AWRD_BDGT_LMT_ID` — `award_budget_limit.budget_limit_id`

None of the six `_EXT` tables have their own sequence — a new
`AWARD_BUDGET_EXT`/etc. row always reuses the `BUDGET_ID`/etc. value
already generated by the generic-table insert, confirming the 1:1
shared-PK relationship is real at the data-generation level, not just
declared in OJB.

## BU-specific extensions

None found. Every table in this bundle is generic Kuali Coeus; `bu-db/`
has no file touching any of them.

## Traps for implementation (read before writing code)

1. **Every generic table is shared with Proposal Development.** Every
   extraction query in this bundle must `INNER JOIN` the generic table
   to its `_EXT` counterpart — that join is the only thing that
   excludes Proposal budget rows. A query against `BUDGET_PERIODS`/
   `BUDGET_DETAILS`/etc. alone, without the join, would silently pull
   in Proposal Development data.
2. **`AWARD_BUDGET_EXT.AWARD_ID` was added later** (`V600_046`) and is
   `NOT NULL` only after a one-time backfill — a real, confirmed,
   NOT-NULL column today, not an assumption.
3. **`previous_obligated_total` is excluded** — `AwardBudgetExt.previousObligatedTotal`
   is a real OJB field-descriptor, but **no DDL evidence for a
   `PREVIOUS_OBLIGATED_TOTAL` column was found anywhere in this
   checkout** (not the bootstrap, not any later migration) — the
   inverse of the Cost Share `FISCAL_YEAR` mistake (there the column
   existed in generic DDL but not real BU Oracle; here it doesn't
   exist in any DDL found at all). Not implemented. Flagged, not
   guessed.
4. **`Budget.finalVersionFlag`/`BUDGET.FINAL_VERSION_FLAG` is excluded**
   — a real DDL column with **no corresponding OJB field-descriptor at
   all** — the mirror-image gap of `AwardCgb.BILL_FREQ_CD` (there a
   real OJB-absent column was included anyway with a flag; here it is
   excluded entirely since Java apparently never reads it and its
   business meaning is unconfirmed). Not implemented.
5. **`BUDGET_DETAILS_CAL_AMTS.BUDGET_PERIOD_NUMBER` is OJB-disabled**
   — the field-descriptor for it exists in
   `repository-budget.xml` but is XML-commented-out (line 199). The
   physical column is real (confirmed in DDL) and harmless to capture;
   archived as `award_budget_line_item_calculated_amount.budget_period_id`
   anyway since it costs nothing and may aid reconciliation, but noted
   as a column Kuali's own Java layer does not actually read.
6. **Codes are numeric-typed in Oracle but kept as text everywhere in
   this bundle** — e.g. `BUDGET_DETAILS.BUDGET_CATEGORY_CODE` is
   `NUMBER(3) NOT NULL` in real DDL despite the OJB mapping declaring
   `budgetCategoryCode` as `VARCHAR` — the same "OJB type wins, keep as
   text, never numeric-convert" treatment already established for
   `Award.transaction_type_code`-style fields, applied here for
   consistency even where OJB and DDL disagree on type.
7. **`documentNumber` on both `Budget` and `AwardBudgetExt` was
   `NUMBER(10)` in the oldest bootstrap DDL, then corrected to
   `VARCHAR2(40)`** by later migrations (`V310_2_036`, `V310_2_031`) —
   confirmed real schema evolution, not a discrepancy to chase further.

## Reconciliation strategy

Same as every other Award child table: whatever Oracle returns on the
next load overwrites the archive row via UPSERT, keyed by each table's
own PK. Duplicate/legitimately-repeated calculated-amount rows (e.g.
two `award_budget_period_summary_calculated_amount` rows for the same
period with different `rate_class_type` values, or several
`award_budget_line_item_calculated_amount` rows for one line item
across different `rate_class_code`/`rate_type_code` combinations) are
expected and normal — no uniqueness constraint beyond each table's own
surrogate PK is added anywhere in this bundle.

## Deletion strategy

No physical `DELETE` investigated or found for any table in this
bundle (consistent with every other Award subsystem) — not
independently re-verified here beyond that general pattern holding.

## Open questions

- `BUDGET_PERSONS`/`BUDGET_PERSON_SALARY_DETAILS` (the personnel
  *roster* at the budget level — name, job code, rolodex, appointment
  type, effective date — distinct from `BUDGET_PERSONNEL_DETAILS`,
  which is personnel *cost* charged to a specific budget line item) is
  a real, confirmed table — its own OJB mapping carries the comment
  "ojb mapping for BudgetPerson should only be used by award" — but was
  not part of this bundle's requested scope and is not implemented.
  Flagged as a genuine, scoped-out gap for a future follow-on, not
  silently dropped.
- `previous_obligated_total` and `BUDGET.final_version_flag`: excluded
  per Traps 3–4 above; would need direct BU Oracle confirmation before
  ever being added.
- `AWARD_BUDGET_STATUS`/`AWARD_BUDGET_TYPE`'s actual row values (what
  the 3-or-so codes in each mean) were not enumerated — out of scope,
  the archive stores whatever Oracle returns.

## Decisions

- Merge each `_EXT`/generic pair into one flattened archive table,
  keyed by the shared PK — the same reasoning as
  `archive.award_extension`/`archive.award_cgb`, applied at every one
  of the six inheritance levels found in this bundle.
- Exclude `previous_obligated_total` and `BUDGET.final_version_flag` —
  both are one-sided (OJB-only or DDL-only) with no corroborating
  evidence, flagged rather than guessed either way.
- Exclude `BUDGET_PERSONS`/`BUDGET_PERSON_SALARY_DETAILS` — real and
  Award-specific, but outside this bundle's explicit scope; documented
  as a deliberate, reviewable gap.
- Real Postgres FK constraints added only where Oracle itself declares
  one (`award_budget.award_id`, `award_budget_limit.award_id`/
  `.budget_id`) — every other relationship stays a bare, unenforced
  column, matching Oracle's own choice not to constrain it.
- `award_budget_period_summary_calculated_amount` stays one table with
  `rate_class_type` intact, not split into "fringe" and "F&A" tables —
  matching Kuali's own single-table, query-filtered design.

## Files changed

- Migration: `V050__create_award_budget.sql` (eight tables, verified
  against a throwaway database).
- Extraction SQL: `sql/extract/award/37_award_budget.sql` through
  `44_award_budget_limit.sql` (eight new files).
- ETL: `etl/load_awards_from_csv.py` (path constants, required-column
  sets, eight `prepare_*` functions, eight `_*_COLUMNS` lists, eight
  `upsert_*` functions, wiring into both `_run_load_award_id` and
  `_run_load_award_batch`, report counters, docstrings, CLI help text).
- Tests: `etl/tests/test_award_incremental_upsert.py` (eight fixture
  builders, `AwardBudgetSqlColumnContractTest`, `_patched_oracle`/
  `_oracle_batches_stub` extended, first-load/unchanged/dry-run tests
  extended, per-table value-change/isolation tests, parent-before-child
  load-order tests, a duplicate-calculated-amount-rows test, batch
  propagation/one-read-per-table/dry-run/idempotent/rollback tests
  extended).
- Docs: this file, `KUALI_ARCHIVE_COVERAGE.md`,
  `AWARD_DOMAIN_DECOMPOSITION.md`, `AWARD_IMPLEMENTATION_ROADMAP.md`.

## Real-data smoke-test plan (not run — no Oracle access in this environment)

1. `SELECT COUNT(*) FROM AWARD_BUDGET_EXT;` and
   `SELECT ab.BUDGET_ID, ab.AWARD_ID, b.BUDGET_NAME, b.START_DATE, b.END_DATE FROM AWARD_BUDGET_EXT ab JOIN BUDGET b ON b.BUDGET_ID = ab.BUDGET_ID FETCH FIRST 10 ROWS ONLY;`
   — confirm the merge join actually returns rows and real budget
   names/dates.
2. Find an Award with a deep budget (multiple periods, line items,
   personnel):
   `SELECT ab.AWARD_ID, COUNT(DISTINCT abp.BUDGET_PERIOD_NUMBER) AS periods, COUNT(DISTINCT abd.BUDGET_DETAILS_ID) AS line_items FROM AWARD_BUDGET_EXT ab JOIN AWARD_BUDGET_PERIOD_EXT abp ON abp.BUDGET_ID = ab.BUDGET_ID JOIN BUDGET_DETAILS abd ON abd.BUDGET_PERIOD_NUMBER = abp.BUDGET_PERIOD_NUMBER GROUP BY ab.AWARD_ID ORDER BY line_items DESC FETCH FIRST 10 ROWS ONLY;`
3. For one returned award, run `--load-award-id <id> --dry-run`,
   inspect all eight tables' reported counts, then a real load, then an
   immediate rerun to confirm `unchanged` across every level.
4. Verify the INNER JOIN correctly excludes Proposal Development
   budgets: confirm `SELECT COUNT(*) FROM BUDGET` is larger than
   `SELECT COUNT(*) FROM BUDGET b JOIN AWARD_BUDGET_EXT ab ON ab.BUDGET_ID = b.BUDGET_ID` for a real database with both Proposal
   and Award budgets present.
5. Verify at least one `award_budget_period_summary_calculated_amount`
   pair exists with both `rate_class_type='E'` and `='O'` for the same
   `budget_period_id`, confirming the single-table-two-roles finding.

## Date last updated

2026-07-31 (research pass completed and reviewed; implementation
completed the same day).
