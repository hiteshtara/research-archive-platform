# Award People Expansion — Design and Implementation Record

## Purpose

Design, then implement, incremental UPSERT support for the Award People
subsystem's three still-missing child tables
(`archive.award_person_unit`, `archive.award_person_credit_split`,
`archive.award_person_unit_credit_split`), extending
`archive.award_person` (already archived and incrementally UPSERTed
since Phase 4A) rather than replacing it — and record the design
decisions and final implementation state so future sessions don't have
to re-derive them.

## Scope

Strictly the four Oracle tables that make up Kuali's
`org.kuali.kra.award.contacts.AwardPerson` object and its two direct
child collections (`units`, `creditSplits`) plus the grandchild
collection (`AwardPersonUnit.creditSplits`):
`AWARD_PERSONS` (already archived), `AWARD_PERSON_UNITS`,
`AWARD_PERSON_CREDIT_SPLITS`, `AWARD_PERS_UNIT_CRED_SPLITS`. Does not
touch `AWARD_SPONSOR_CONTACTS` (Tier 1 Award Contacts — a different
Kuali class, `AwardSponsorContact`, with no relationship to
`AwardPerson`) or `AWARD_UNIT_CONTACTS` (removed in V033, out of scope
per explicit instruction unless a verified extraction and business-rule
decision says otherwise — none is being made here). No changes to
`archive.award_person`'s existing schema, extraction SQL, or UPSERT
behavior.

## Source material used

- Upstream Kuali Coeus source (`/Users/mukadder/kuali-project/kuali-research`,
  read-only), `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`,
  lines 378–491: the full `org.kuali.kra.award.contacts` package —
  `AwardPerson` (table `AWARD_PERSONS`), `AwardPersonBoLite` (a
  read-optimized subset mapped to the *same* `AWARD_PERSONS` table, not
  a distinct physical table — no separate archive concern),
  `AwardPersonCreditSplit` (table `AWARD_PERSON_CREDIT_SPLITS`),
  `AwardPersonUnit` (table `AWARD_PERSON_UNITS`),
  `AwardPersonUnitCreditSplit` (table `AWARD_PERS_UNIT_CRED_SPLITS`).
  Grepping the full class-descriptor list in this file (55 classes)
  confirms these five are the entire `contacts` package for Award —
  `AwardSponsorContact` and `AwardUnitContact` are the only other two
  `contacts`-package classes, both already out of scope for this work.
- BU 7.3 reference tree (`reference/kuali/award/`): `AwardPersonUnit.xml`
  — a Struts/data-dictionary attribute-override bean (field
  `awardPersonUnitId` maxLength 10, `unitNumber` maxLength 10) —
  confirms `AwardPersonUnit` is a real, BU-customized business object,
  not an upstream-only artifact. No BU-specific override file exists for
  `AwardPersonCreditSplit`/`AwardPersonUnitCreditSplit` in the provided
  reference tree; their absence doesn't indicate BU doesn't use them
  (Award.xml and AwardPersonUnit.xml are themselves override-only files,
  not full data dictionaries — most upstream business objects have no
  BU override file at all because none was needed).
- `AWARD_DOMAIN_STUDY.md`'s existing object graph, which lists
  `AwardPerson (AWARD_PERSONS) [archived, Phase 4A UPSERT]` as a single
  leaf node with no children shown — this expansion's own investigation
  found that graph incomplete at the person level (see Findings below);
  not a contradiction of that document's own scope (it explicitly
  deferred to `AWARD_DOMAIN_DECOMPOSITION.md` for full table detail),
  but a genuine gap neither document previously surfaced.
- `database/migrations/V011__create_award_archive_tables.sql` (current
  `archive.award_person` schema), `V033__drop_award_unit_contact_and_proposal_person.sql`
  (confirms why `AWARD_UNIT_CONTACTS` stays out of scope),
  `sql/extract/award/01_award_versions.sql` (established `LEFT JOIN`
  denormalization style, reused here as a plain inner `JOIN` since these
  parent links are `NOT NULL`), `04_award_proposals.sql`/
  `05_award_custom_data.sql` (flat-extraction style, reused where no
  join is needed), `etl/load_awards_from_csv.py` (existing
  `read_award_children_matching_award_ids`, `_CHILD_COLUMN_RENAMES`,
  `_sql_value`, the family-widening `_run_load_award_id` this extends).

## Assumptions

- `AwardPersonBoLite` requires no archive work of its own — it is a
  lighter-weight Java view over the same `AWARD_PERSONS` table already
  archived, not a second table.
- `INV_CREDIT_TYPE_CODE` (on both credit-split tables) is a cross-domain
  lookup (`InvestigatorCreditType`) whose own extraction/verification is
  out of scope here — kept as a bare, unjoined code, the same convention
  already established for `custom_attribute_id` on
  `archive.award_custom_data`.
- `UNIT_NUMBER` has no archive-side unit dimension table to join against
  (confirmed: no `archive.unit` table exists anywhere in
  `database/migrations/`; `archive.award_version.lead_unit_number` is
  itself a bare, unjoined `VARCHAR(30)` with a separately denormalized
  `lead_unit_name` pulled via Oracle-side `LEFT JOIN UNIT`, not an
  archive-side FK) — `award_person_unit.unit_number` is modeled the same
  way, as a bare code.

## Findings

### Complete Award People object graph (corrects the prior single-node view)

```
AwardPerson (AWARD_PERSONS, PK award_person_id)                [archived, Phase 4A]
├── AwardPersonUnit (AWARD_PERSON_UNITS)                       [MISSING — this expansion]
│   └── AwardPersonUnitCreditSplit (AWARD_PERS_UNIT_CRED_SPLITS) [MISSING — this expansion]
└── AwardPersonCreditSplit (AWARD_PERSON_CREDIT_SPLITS)        [MISSING — this expansion]
```

`AwardPersonUnit` records which unit(s) a person is credited under on
this award (with a `lead_unit_flag`), independent of
`AwardPersonCreditSplit`, which records the person's own overall
investigator-credit split by credit type
(`INV_CREDIT_TYPE_CODE`/`CREDIT`) with **no unit dimension** —
`AwardPersonUnitCreditSplit` is the **per-unit** version of the same
credit-split concept, one level deeper, keyed off
`AWARD_PERSON_UNIT_ID` rather than `AWARD_PERSON_ID` directly. All three
are real, live Kuali features (`AwardPerson.ADD_CREDIT_SPLIT`/
`includeInCreditAllocation` is the flag controlling whether a person
participates in credit-split calculations at all) — not legacy/unused
scaffolding as far as static source alone can show; actual usage volume
at BU is unverified until the local smoke test runs against real data
(see Open questions).

### Oracle tables, PK/FK mappings

| Table | PK column | FK column(s) | Parent |
|---|---|---|---|
| `AWARD_PERSONS` | `AWARD_PERSON_ID` | `AWARD_ID` | `AWARD` (already archived) |
| `AWARD_PERSON_UNITS` | `AWARD_PERSON_UNIT_ID` | `AWARD_PERSON_ID` | `AWARD_PERSONS` — **no `AWARD_ID` column of its own** |
| `AWARD_PERSON_CREDIT_SPLITS` | `AWARD_PERSON_CREDIT_SPLIT_ID` | `AWARD_PERSON_ID` | `AWARD_PERSONS` — **no `AWARD_ID` column of its own** |
| `AWARD_PERS_UNIT_CRED_SPLITS` | `APU_CREDIT_SPLIT_ID` | `AWARD_PERSON_UNIT_ID` | `AWARD_PERSON_UNITS` — **two hops from Award, no `AWARD_ID`/`AWARD_PERSON_ID` column of its own** |

All four PKs (`AWARD_PERSON_ID`, `AWARD_PERSON_UNIT_ID`,
`AWARD_PERSON_CREDIT_SPLIT_ID`, `APU_CREDIT_SPLIT_ID`) draw from the
same shared `SEQUENCE_AWARD_ID` Oracle sequence already confirmed safe
for cross-table-unique UPSERT conflict keys (`AWARD_DOMAIN_STUDY.md`) —
verified directly in each class-descriptor's
`sequence-name="SEQUENCE_AWARD_ID"` attribute for this work, not merely
assumed by extension.

**Critical structural finding**: unlike `award_amount_info`/
`award_person`/`award_funding_proposal`/`award_custom_data` (all of
which carry `AWARD_ID` directly), none of these three new tables carry
`AWARD_ID` at all — only `AWARD_PERSON_ID` (or, for the grandchild,
only `AWARD_PERSON_UNIT_ID`). `read_award_children_matching_award_ids`
filters strictly by an `award_id` column already present in the
extracted rows, so it cannot be reused unmodified unless `AWARD_ID` (and
`AWARD_NUMBER`/`SEQUENCE_NUMBER`, for consistency with every other child
table) is denormalized through via a Oracle-side `JOIN` back up to
`AWARD_PERSONS` (and, for the grandchild, up through `AWARD_PERSON_UNITS`
as well) — exactly the join-and-denormalize style already established
by `01_award_versions.sql`. Doing this in the extraction SQL means the
existing reader needs **zero changes**.

### Current archive coverage

- `archive.award_person` — archived, Phase 4A, unaffected by this work.
- `archive.award_person_unit` — missing, this expansion.
- `archive.award_person_credit_split` — missing, this expansion.
- `archive.award_person_unit_credit_split` — missing, this expansion.

### Missing target tables (new migration)

`database/migrations/V039__create_award_person_units_and_credit_splits.sql`
(additive only — `CREATE TABLE IF NOT EXISTS` + indexes):

```sql
CREATE TABLE IF NOT EXISTS archive.award_person_unit (
    award_person_unit_id     BIGINT PRIMARY KEY,
    award_person_id          BIGINT NOT NULL
                                 REFERENCES archive.award_person(award_person_id)
                                 ON DELETE CASCADE,
    award_id                 BIGINT NOT NULL
                                 REFERENCES archive.award_version(award_id)
                                 ON DELETE CASCADE,
    award_number             VARCHAR(50),
    sequence_number          INTEGER,

    unit_number               VARCHAR(30),
    lead_unit_flag            VARCHAR(10),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_person_unit_award ON archive.award_person_unit (award_id, award_person_unit_id);
CREATE INDEX ix_award_person_unit_person ON archive.award_person_unit (award_person_id);
CREATE INDEX ix_award_person_unit_number ON archive.award_person_unit (unit_number);

CREATE TABLE IF NOT EXISTS archive.award_person_credit_split (
    award_person_credit_split_id  BIGINT PRIMARY KEY,
    award_person_id               BIGINT NOT NULL
                                      REFERENCES archive.award_person(award_person_id)
                                      ON DELETE CASCADE,
    award_id                      BIGINT NOT NULL
                                      REFERENCES archive.award_version(award_id)
                                      ON DELETE CASCADE,
    award_number                  VARCHAR(50),
    sequence_number               INTEGER,

    inv_credit_type_code          VARCHAR(50),
    credit                        NUMERIC(10,2),

    source_update_timestamp       TIMESTAMP,
    source_update_user            VARCHAR(100),
    source_version_number         BIGINT,

    loaded_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                       BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_person_credit_split_award ON archive.award_person_credit_split (award_id, award_person_credit_split_id);
CREATE INDEX ix_award_person_credit_split_person ON archive.award_person_credit_split (award_person_id);

CREATE TABLE IF NOT EXISTS archive.award_person_unit_credit_split (
    award_person_unit_credit_split_id  BIGINT PRIMARY KEY,
    award_person_unit_id               BIGINT NOT NULL
                                           REFERENCES archive.award_person_unit(award_person_unit_id)
                                           ON DELETE CASCADE,
    award_id                           BIGINT NOT NULL
                                           REFERENCES archive.award_version(award_id)
                                           ON DELETE CASCADE,
    award_number                       VARCHAR(50),
    sequence_number                    INTEGER,

    inv_credit_type_code               VARCHAR(50),
    credit                             NUMERIC(10,2),

    source_update_timestamp            TIMESTAMP,
    source_update_user                 VARCHAR(100),
    source_version_number              BIGINT,

    loaded_at                          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                            BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_person_unit_credit_split_award ON archive.award_person_unit_credit_split (award_id, award_person_unit_credit_split_id);
CREATE INDEX ix_award_person_unit_credit_split_unit ON archive.award_person_unit_credit_split (award_person_unit_id);
```

`lead_unit_flag` is modeled as `VARCHAR(10)` (Y/N), matching
`award_person.faculty_flag`'s existing convention, not a native
`BOOLEAN` — OJB's char-to-boolean conversion is a Java-layer concern,
the Oracle column itself is `CHAR(1)`. `credit` uses `NUMERIC(10,2)`
(the OJB mapping specifies `OjbScaleTwoDecimalFieldConversion`, i.e.
exactly two decimal places), distinct from `award_person`'s own effort
columns which are `NUMERIC(10,4)`. `award_id`/`award_number`/
`sequence_number` are denormalized onto all three tables via the Oracle
extraction join (see below) so every child table keeps the same
family-scoped-query shape as `award_amount_info`/`award_person`/
`award_custom_data` — consistent with the project convention of
preserving both the business grain and enough context to query
independently. `OBJ_ID` is deliberately **not** extracted or added as a
`source_object_id` column here, matching the same not-yet-wired gap
already present for `archive.award_custom_data` — not a new omission
introduced by this work, an existing one left consistent.

### UPSERT conflict keys

Each table's own surrogate PK: `award_person_unit_id`,
`award_person_credit_split_id`, `award_person_unit_credit_split_id` —
all globally unique via the shared `SEQUENCE_AWARD_ID`, same pattern as
every other Award child table.

### Deletion/reconciliation strategy

Deferred, identically to every other Award child table so far (Phase 4A
and Award Custom Data) — no hard-delete, no soft-delete marking
implemented. Same recommended-but-unimplemented default: mark rows no
longer returned by Oracle for their parent, rather than silently
leaving them orphaned with no signal. Not re-decided here.

### Load order

Within `_run_load_award_id`'s existing family-widened transaction, after
the existing `version` → `amount_info` → `person` → `funding_proposal` →
`custom_data` sequence:

6. `award_person_unit` (FK to `award_person_id`, already upserted in
   step 3 within the same transaction — visible before commit)
7. `award_person_credit_split` (FK to `award_person_id`, same
   requirement as step 6)
8. `award_person_unit_credit_split` (FK to `award_person_unit_id` —
   **must follow step 6**, since it depends on `award_person_unit` rows
   existing first; ordered directly after step 6's insert loop, before
   step 7, to keep the FK dependency visually adjacent to its parent)

No new Oracle family-resolution scan, no new top-level load function —
this is a pure extension of the same loop shape `award_custom_data`
established: three more child tables riding along on the same
`family_award_ids` already resolved for `award_version`.

### Batch behavior

No new batch domain/entity_type. These three tables are children of the
`AWARD`/`AWARD` entity that already exists — they ride along for free on
`--create-batch`/`--load-batch`/`--show-batch`, exactly as
`award_custom_data` does, since `_run_load_award_batch` already calls
`_run_load_award_id` per family.

### Test plan

Extend `etl/tests/test_award_incremental_upsert.py` in place (not a new
file — same rationale as Custom Data: these are not independent load
paths, they're three more column sets on the same family-widened load).
New fixtures: `_person_unit_row`, `_person_credit_split_row`,
`_person_unit_credit_split_row`; a `person_units`/`person_credit_splits`/
`person_unit_credit_splits` param added to `_patched_oracle`
(dispatching the three new Oracle SQL path constants, defaulting to
empty DataFrames so every pre-existing test keeps passing unmodified —
same pattern proven safe for Custom Data). New/extended tests:
insert-all-N-tables (folded into the existing "first load" test,
extending it from five to eight tables), reload-unchanged, a
value-change-produces-an-update test for at least one of the three new
tables, an FK-ordering test proving `award_person_unit_credit_split`
loads correctly even though its parent `award_person_unit` row is newly
inserted in the very same transaction, a does-not-touch-unrelated-award
isolation test, a dry-run test, and batch-level assertions that the
three new tables' counts propagate through `_run_load_award_batch`'s
report dict.

### Local real-data smoke-test plan

**Prepared here, not run** — executing it requires the BU VPN, a real
AWS SSM session, and the approved dev RDS tunnel, all outside what this
work is authorized to run directly (no ECS involved either way — Award
has no ECS execution mode, per `AWARD_IMPLEMENTATION_ROADMAP.md`'s prior
finding; the real path is local + BU VPN + SSM tunnel, per
`docs/runbooks/ORACLE.md`/`LOCAL_SETUP.md`).

1. Connect to the BU VPN; run `buaws` if AWS credentials need
   refreshing.
2. Start the approved tunnel to dev RDS (leave running in its own
   terminal — exact target per `docs/runbooks/LOCAL_SETUP.md`):
   ```bash
   aws ssm start-session \
     --region us-east-1 \
     --target i-02be522658e0f9676 \
     --document-name AWS-StartPortForwardingSessionToRemoteHost \
     --parameters '{"host":["research-archive-platform-dev-postgres.cs3i6a24sthk.us-east-1.rds.amazonaws.com"],"portNumber":["5432"],"localPortNumber":["15432"]}'
   ```
3. In a second terminal, export Postgres and Oracle connection
   variables:
   ```bash
   export POSTGRES_HOST=localhost
   export POSTGRES_PORT=15432
   export POSTGRES_DB=research_archive
   export ORACLE_USER=...
   export ORACLE_PASSWORD=...
   export ORACLE_DSN=...      # host:1521/SERVICE_NAME
   ```
4. Pick one real `AWARD_ID` from Oracle that has at least one row in
   `AWARD_PERSON_UNITS`/`AWARD_PERSON_CREDIT_SPLITS` (a quick read-only
   `SELECT` against Oracle, no archive writes) — this exercises all
   eight tables rather than just the four Phase 4A already proved.
5. Dry run first, from `etl/`:
   ```bash
   uv run python load_awards_from_csv.py --load-award-id <award_id> --dry-run
   ```
   Inspect the logged report for all eight tables
   (`version`/`amount_info`/`person`/`funding_proposal`/`custom_data`/
   `person_unit`/`person_credit_split`/`person_unit_credit_split`);
   confirm `archive.award_person_unit` etc. still have 0 rows for this
   `award_id` afterward (dry run must persist nothing).
6. Real load (no `--dry-run`):
   ```bash
   uv run python load_awards_from_csv.py --load-award-id <award_id>
   ```
7. Immediately re-run the exact same command a second time.
8. Verify the second run's report shows, for every one of the three new
   tables, `inserted=0 updated=0` and `unchanged` equal to that table's
   row count from step 6 (or `unchanged=0` if that `award_id`
   genuinely has zero rows in one of the three — a legitimate outcome
   for these optional per-person features, not a bug) — proving
   idempotency the same way Phase 4A's and Award Custom Data's own
   smoke tests already did.
9. Run `uv run python scripts/reconcile_load.py --domain AWARD --limit 5`
   and confirm no discrepancy is introduced for the affected award.

## Open questions

- **Real BU usage volume.** Whether `AwardPersonCreditSplit`/
  `AwardPersonUnitCreditSplit` are actually populated at meaningful scale
  in BU's live data (versus a rarely-used optional feature) is not
  determinable from static source alone — the local smoke test against
  real Oracle data will observe this empirically.
- **`award_person.ADD_CREDIT_SPLIT`/`OPT_IN_UNIT_STATUS` are not
  currently archived** on `archive.award_person` itself (confirmed via
  `03_award_people.sql`/`prepare_people`) — these two flags are exactly
  what determines whether a person's credit-split/unit-opt-in rows are
  meaningful. Not fixed here (out of scope per "preserve the existing
  `award_person` UPSERT behavior"), but worth flagging as a follow-on
  gap: without them, the archive records credit-split rows without the
  flag explaining why they exist.
- Same three open questions already recorded in
  `AWARD_IMPLEMENTATION_ROADMAP.md` apply equally here (child-row
  deletion/reconciliation, whether surrogate IDs are ever reused, no new
  ones introduced by this work specifically).

## Decisions

- Denormalize `award_id`/`award_number`/`sequence_number` onto all three
  new tables via an Oracle-side `JOIN` (not a Python-side resolution
  step), so the existing generic
  `read_award_children_matching_award_ids` reader needs zero changes —
  the same reuse-over-invention principle already applied to
  `award_custom_data`.
  All three joins are plain (inner) `JOIN`s, not `LEFT JOIN`s, because
  the parent FK is `NOT NULL` on every one of these tables in the OJB
  mapping — an orphaned child row would indicate real data corruption,
  not a legitimate optional relationship.
- `award_person_unit_credit_split` upserts strictly after
  `award_person_unit` within the same family-load transaction, to
  satisfy its FK even when the parent unit row is being inserted for the
  first time in this very same load.
- `AWARD_DOMAIN_DECOMPOSITION.md`'s "Tier 1 — Award People" entry is
  corrected by this document to list all four real Oracle tables
  (`AWARD_PERSONS`, `AWARD_PERSON_UNITS`, `AWARD_PERSON_CREDIT_SPLITS`,
  `AWARD_PERS_UNIT_CRED_SPLITS`) instead of its prior
  `AWARD_PERSONS`/`AWARD_UNIT_CONTACTS` pairing — `AWARD_UNIT_CONTACTS`
  was never actually part of the `AwardPerson` object graph; it belongs
  conceptually next to `AwardSponsorContact`/`AwardUnitContact` (both
  `contacts`-package classes with no relationship to `AwardPerson`), and
  remains out of scope for the reason already recorded (V033 removal).

## Recommended implementation order

1. ~~Design: object graph, Oracle PK/FK mappings, archive coverage,
   migration, UPSERT keys, deletion strategy, load order, batch
   behavior, test plan, smoke-test plan~~ — done.
2. ~~Migration (`V039`), verified against a throwaway database~~ — done.
3. ~~Oracle extraction SQL (three new files, `award_id` denormalized via
   `JOIN`)~~ — done.
4. ~~`prepare_person_units`/`prepare_person_credit_splits`/
   `prepare_person_unit_credit_splits`,
   `upsert_award_person_unit`/`upsert_award_person_credit_split`/
   `upsert_award_person_unit_credit_split`~~ — done.
5. ~~Extend `_run_load_award_id`/`_run_load_award_batch`~~ — done.
6. ~~Tests + full validation (`pytest` 502 passed, `ruff` clean, `mypy`
   clean)~~ — done.
7. Local real-data smoke test (dry-run, real load, rerun, verify
   `unchanged` on every new table) — plan prepared, not yet run; see
   below.
8. Next Tier 1 subsystem per `AWARD_DOMAIN_DECOMPOSITION.md` (Award
   Contacts, Award Attachments/Notepad, Award Terms, Award Reporting, or
   Award Subaward Summary).

## Date last updated

2026-07-31 (design and implementation complete; local real-data smoke
test against real Oracle/RDS not yet run — see step 7 above).
