# Award Custom Data — Design and Implementation Record

## Purpose

Design, then implement, incremental UPSERT support for Award Custom Data
(`archive.award_custom_data`), the first Tier 1 subsystem built after
Phase 4A's Core Award, using the existing generic ETL batch framework —
and record the design decisions and final implementation state so future
sessions don't have to re-derive them.

## Scope

Strictly `archive.award_custom_data`, sourced from Oracle's
`AWARD_CUSTOM_DATA` table, as a child of Core Award
(`archive.award_version(award_id)`). No Award Budget, Time and Money
workflow, Reporting, Contacts, or Terms — see
`AWARD_DOMAIN_DECOMPOSITION.md` for those as separate future milestones.

## Source material used

- Upstream Kuali Coeus source (`/Users/mukadder/kuali-project/kuali-research`,
  read-only, referenced in `AWARD_DOMAIN_STUDY.md`):
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`,
  class `org.kuali.kra.award.customdata.AwardCustomData`, table
  `AWARD_CUSTOM_DATA`, PK `award_custom_data_id` / `AWARD_CUSTOM_DATA_ID`
  with its own sequence, `SEQ_AWARD_CUSTOM_DATA_ID` (not part of the
  shared `SEQUENCE_AWARD_ID` family Core Award's four tables draw from —
  still safe as an UPSERT conflict key, just scoped to this one table).
- The existing Negotiation and Subaward EAV/custom-data implementations,
  used as the direct reference pattern per explicit instruction:
  `database/migrations/V017__create_negotiation_archive_tables.sql`
  (`negotiation_custom_data` — flat, no `sequence_number`, since
  Negotiation itself has no version concept) and
  `database/migrations/V018__create_subaward_archive_tables.sql`
  (`subaward_custom_data` — has `sequence_number`, matching Subaward's
  own versioning), plus `oracle/negotiation/export_negotiation_custom_data.sql`
  and `oracle/subaward/export_subaward_custom_data.sql` (both flat
  `SELECT`s with no join on `custom_attribute_id`).
- `docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md` (Phase 4A's
  family-widening design and UPSERT pattern, reused as-is here).

## Assumptions

- `custom_attribute_id` is a cross-domain shared lookup
  (`KRA_CUSTOM_ATTRIBUTES` or similar) whose own extraction/verification
  is out of scope here — kept as a bare, unjoined Oracle ID, exactly the
  convention already established (and explicitly flagged as
  "not yet verified") for `negotiation_custom_data` and
  `subaward_custom_data`. Not re-litigated in this document.
- Award Custom Data is a child of Award the same way `award_amount_info`/
  `award_person`/`award_funding_proposal` are: it depends only on
  `award_id` existing, with no dependency on any other Tier 1/Tier 2
  subsystem.

## Findings (design)

### Oracle source

`AWARD_CUSTOM_DATA` columns extracted (see
`sql/extract/award/05_award_custom_data.sql`): `AWARD_CUSTOM_DATA_ID`
(PK), `AWARD_ID`, `AWARD_NUMBER`, `SEQUENCE_NUMBER`,
`CUSTOM_ATTRIBUTE_ID`, `VALUE`, `UPDATE_TIMESTAMP`, `UPDATE_USER`,
`VER_NBR`. Mirrors `04_award_proposals.sql`'s style exactly: a flat
`SELECT ... ORDER BY AWARD_ID, AWARD_CUSTOM_DATA_ID`, no joins.

### Award relationship

Direct FK-shaped relationship: `AWARD_CUSTOM_DATA.AWARD_ID` →
`AWARD.AWARD_ID`. Like Award's other three child tables, a single
`award_id` can have zero or more custom-data rows; `award_number` +
`sequence_number` are carried along (matching the Subaward shape) since
Award, unlike Negotiation, has a real version concept.

### Target PostgreSQL schema

`archive.award_custom_data` (see
`database/migrations/V038__create_award_custom_data.sql`):
`award_custom_data_id BIGINT PRIMARY KEY`, `award_id BIGINT NOT NULL
REFERENCES archive.award_version(award_id) ON DELETE CASCADE`,
`award_number VARCHAR(50)`, `sequence_number INTEGER`,
`custom_attribute_id BIGINT`, `value TEXT`, the standard
`source_update_timestamp`/`source_update_user`/`source_version_number`/
`source_object_id` provenance columns, `loaded_at`, and `load_id`
referencing `archive.load_run`. Two indexes: `(award_id,
award_custom_data_id)` for the family-scoped reads this subsystem relies
on, and `(custom_attribute_id)` for cross-award lookups by attribute.

### UPSERT conflict key

`award_custom_data_id` — the table's own surrogate PK, globally unique
via its dedicated Oracle sequence. Same `INSERT ... ON CONFLICT
(award_custom_data_id) DO UPDATE SET ... WHERE ... IS DISTINCT FROM
EXCLUDED.... RETURNING (xmax = 0) AS inserted` three-way
inserted/updated/unchanged pattern as the other four Award UPSERT
functions (`upsert_award_custom_data`,
`etl/load_awards_from_csv.py`).

### Deletion/reconciliation strategy

Deliberately deferred, matching the same open question already recorded
for Phase 4A's four tables in `AWARD_IMPLEMENTATION_ROADMAP.md` — no
hard-delete, no soft-delete marking implemented. Recommended (but not
yet implemented) default: mark rows no longer returned by Oracle for
their `award_id`, rather than silently leaving them orphaned with no
signal.

### Load order and batch behavior

Extends `_run_load_award_id` directly as a 5th child table (alongside
`amount_info`/`person`/`funding_proposal`), scoped to the same
`family_award_ids` already resolved for `award_version` — no new
top-level load function, no new Oracle family-resolution scan. Reuses
`read_award_children_matching_award_ids` as-is (already fully generic).
No new batch domain/entity_type: Custom Data rides along for free on
`--create-batch`/`--load-batch`/`--show-batch`, since
`_run_load_award_batch` already calls `_run_load_award_id` per family.

### Migration plan

`V038__create_award_custom_data.sql`, additive only (`CREATE TABLE IF
NOT EXISTS` + two `CREATE INDEX IF NOT EXISTS`), applied by the Python
ETL's migration runner like every other migration in this repo (see
CLAUDE.md's "Migrations are not run by Spring Boot"). Verified applying
cleanly against a throwaway local Postgres database before being wired
into the loader.

### Tests

Extended `etl/tests/test_award_incremental_upsert.py` in place (not a
new file) — Custom Data is not an independent load path, it's a 5th
column set on the same family-widened load, so its tests belong beside
the other four child tables' tests rather than in a separate suite.
Added: `_custom_data_row` fixture; `custom_data` param on
`_patched_oracle` (dispatches `CUSTOM_DATA_ORACLE_SQL`, defaults to an
empty DataFrame so every pre-existing test keeps passing unmodified);
insert/unchanged assertions folded into the existing
`test_first_load_inserts_all_five_tables` (renamed from `..._four_...`)
and `test_reload_with_no_oracle_changes_is_unchanged`;
`test_custom_data_value_change_produces_an_update`;
`test_custom_data_does_not_touch_unrelated_existing_award`; dry-run
assertion added to `test_dry_run_reports_accurate_counts_but_persists_nothing`;
batch-level assertions added to `test_loads_every_batch_member`.

## Open questions

- Same three open questions already recorded in
  `AWARD_IMPLEMENTATION_ROADMAP.md` apply equally here (child-row
  deletion/reconciliation, whether IDs are ever reused across award_id
  versions, no new ones introduced by Custom Data specifically).
- `custom_attribute_id`'s lookup table remains unverified for Award,
  exactly as already flagged for Negotiation/Subaward — not resolved
  here, not blocking.

## Decisions

- Custom Data reuses Phase 4A's family-widening machinery unchanged
  (`family_award_ids` from the already-resolved `award_number` family)
  rather than introducing any new resolution logic — it is a pure
  addition of one more child table to an existing loop.
- Mirrors `archive.subaward_custom_data`'s shape (has `sequence_number`)
  rather than `archive.negotiation_custom_data`'s (flat), because Award
  has a real version concept like Subaward, not like Negotiation.
- `custom_attribute_id` stays a bare, unjoined Oracle ID — consistent
  with, not a re-litigation of, the existing Negotiation/Subaward
  convention.

## Recommended implementation order

1. ~~Design: Oracle columns/keys, Award relationship, target schema,
   conflict key, deletion strategy, load/batch order, migration plan,
   tests~~ — done.
2. ~~Migration (`V038`), verified against a throwaway database~~ — done.
3. ~~Oracle extraction SQL (`05_award_custom_data.sql`)~~ — done.
4. ~~`prepare_custom_data`, `upsert_award_custom_data`~~ — done.
5. ~~Extend `_run_load_award_id`/`_run_load_award_batch`~~ — done.
6. ~~Tests + full validation (`pytest`, `ruff`, `mypy`)~~ — done.
7. Resolve the deletion/reconciliation open question (shared with
   Phase 4A, not yet implemented for any Award child table).
8. Next Tier 1 subsystem per `AWARD_DOMAIN_DECOMPOSITION.md`.

## 2026-08-13 status update: API/UI built, data already loaded

The 2026-07-31 "implementation complete" above covered ETL/database only
— Award Custom Data had no API endpoint, UI section, or nav entry until
`bb22466` (local commit, not yet pushed/deployed), which added
`GET /api/v1/awards/{awardId}/custom-data` and an `AwardCustomDataSection`
UI component mirroring `ProposalV1Repository.findCustomDataRows`'s
label-resolution pattern (`archive.custom_attribute` LEFT JOIN).

Verified against real Oracle staging and dev RDS (2026-08-13, via the
Keychain-backed staging runner and an ECS Fargate one-off task —
`CLAUDE.md`'s "Authoritative data location" section):

- Oracle staging `AWARD_CUSTOM_DATA`: 6,328,084 rows total; 267,386
  `AWARD` rows / 40,926 Award numbers.
- Dev RDS `archive.award_custom_data`: **6,328,064 rows already loaded**
  — the 20-row gap from staging is exactly the 20 Oracle rows whose
  `AWARD_ID` has no matching `AWARD` row (fails the archive's FK
  constraint, correctly excluded, not a bug).
- Award `204713-00117` (the family this design/investigation used as its
  running example): all 7 versions, all 260 `award_custom_data` rows,
  already present in dev RDS.
- An ECS `--load-award-id 3160098 --dry-run` proved, for real, that all
  48 Award child tables (not just custom_data) are already synchronized
  between Oracle staging and dev RDS for this family — every table
  reported `inserted=0 updated=0`, and pre/post RDS row counts were
  byte-for-byte identical (rollback proof).

**No Award Custom Data load is required.** Dev RDS already has the data;
what's missing is deploying `bb22466`'s API/UI code so the existing data
becomes visible.

## Date last updated

2026-08-13.
