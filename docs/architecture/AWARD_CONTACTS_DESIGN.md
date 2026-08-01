# Award Contacts — Design and Implementation Record

## Purpose

Design, then implement, incremental UPSERT support for the Award
Contacts subsystem (`archive.award_sponsor_contact`,
`archive.award_unit_contact`) — including resolving the standing
question of whether `award_unit_contact` (dropped in V033 for lack of a
verified Oracle extraction) can now be safely reintroduced — and record
the decisions so future sessions don't have to re-derive them.

## Scope

`AWARD_SPONSOR_CONTACTS` and `AWARD_UNIT_CONTACTS` — the two real
Award-instance contact tables. Does not touch Award People (already
complete, a structurally unrelated object graph — see Decisions), SAP
transmission (explicitly deferred, not investigated), Reporting,
Notepad, Subaward Summary, Budget, or Time and Money.

## Source material used

- Upstream Kuali Coeus source (`/Users/mukadder/kuali-project/kuali-research`,
  read-only): `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  — the `Award` class-descriptor's `awardUnitContacts`/`sponsorContacts`
  named collections (lines 191–196, both `inverse-foreignkey
  field-ref="awardId"`), and the full class-descriptors for
  `AwardSponsorContact` (`AWARD_SPONSOR_CONTACTS`, lines 752–773) and
  `AwardUnitContact` (`AWARD_UNIT_CONTACTS`, lines 775–800).
- `coeus-impl/src/main/java/org/kuali/kra/award/contacts/AwardCentralAdminContact.java`,
  `AwardCentralAdminContactsBean.java`, `UnitContactTypeConverter.java` —
  read in full to resolve what "Award Central Admin Contacts" actually
  is (see Findings — it is not a table).
- **Real Oracle DDL**, not just the Java OJB mapping:
  `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
  (the original `CREATE TABLE AWARD_SPONSOR_CONTACTS`/
  `CREATE TABLE AWARD_UNIT_CONTACTS` statements, lines 1611–1639 and
  2038–2068) and
  `.../bootstrap/V510_060__KC_TBL_AWARD_UNIT_CONTACTS.sql` (the later
  `ALTER TABLE ... ADD DEFAULT_UNIT_CONTACT` migration). Cross-checking
  the actual bootstrap DDL against the OJB mapping — not done for any
  prior Tier 1 subsystem — was specifically motivated by wanting strong,
  double-sourced verification before reintroducing a previously-removed
  table (see Decisions).
- BU 7.3 reference tree (`reference/kuali/award/repository-award.xml`,
  `Award.xml`): both `awardUnitContacts`/`sponsorContacts` collections
  are present, and `Award.xml` has a specific (hidden-field) DD override
  for `awardUnitContacts.awardContactId` — confirms BU actively
  customizes this feature, not just inherits it unused.
- `docs/DECISIONS.md` (the original V033 removal rationale) and
  `database/migrations/V014__create_award_unit_contact.sql`/
  `V033__drop_award_unit_contact_and_proposal_person.sql` (the
  previously-shipped-then-dropped schema, read in full — see Findings
  for why it cannot simply be restored as-is).

## Assumptions

- `CONTACT_ROLE_CODE` (on `AWARD_SPONSOR_CONTACTS`) and
  `UNIT_ADMINISTRATOR_TYPE_CODE`/`UNIT_CONTACT_TYPE` (on
  `AWARD_UNIT_CONTACTS`) are small code lookups
  (`CONTACT_TYPE`/`UnitAdministratorType`/`UnitContactType`) whose own
  extraction/verification is out of scope here — kept bare, unjoined,
  the same convention already established for `contact_type_code` on
  `archive.award_report_term_recipient` and `contact_role_code` on
  `archive.award_person`.
- `ROLODEX_ID` (`AWARD_SPONSOR_CONTACTS`) and `PERSON_ID`
  (`AWARD_UNIT_CONTACTS`) follow the same bare-value convention already
  used for `archive.award_person`'s own `rolodex_id`/`person_id` — no
  join to a Rolodex/Person directory table.

## Findings

### `AWARD_CENTRAL_ADMIN_CONTACTS` does not exist — it is not a table at all

Direct inspection of `AwardCentralAdminContact.java` (its own doc
comment: *"This class is a minor hack to satisfy the DataDictionary
requirements... the Award Central Admin contact type uses the same BO
as the Unit Contact type"*) shows it is a Java subclass of
`AwardUnitContact` with **zero additional fields**, mapped to the exact
same `AWARD_UNIT_CONTACTS` table. More importantly,
`AwardCentralAdminContactsBean.initCentralAdminContacts()` never reads
`AWARD_UNIT_CONTACTS` at all — it builds **transient, never-persisted**
`AwardUnitContact` objects on the fly from `UnitAdministrator` records
(a Unit-level admin-role roster, filtered by
`unitAdministratorType.getDefaultGroupFlag().equals("C")`), purely for
one UI panel's display. Confirmed via `grep -rl` across the entire
source tree that no `AWARD_CENTRAL_ADMIN_CONTACTS` table, DDL, or OJB
class-descriptor exists anywhere. There is nothing to extract or
archive under this name — it is a computed, read-only rollup of
Unit-administrator data at view time, not Award-instance data.

### Why `award_unit_contact` was dropped, and whether that reason still holds

`docs/DECISIONS.md`: *"Award's unit contacts... had no verified Oracle
extraction query; rather than write unverified Oracle SQL to fill the
gap, [the] feature [was] removed entirely."* Comparing the **previously
shipped** `V014__create_award_unit_contact.sql` schema against the
now-available authoritative sources shows exactly why it was
unverified: V014 included columns with **no basis whatsoever** in the
real `AwardUnitContact` OJB mapping or the real Oracle DDL —
`unit_name`, `parent_unit_number`, `parent_unit_name`, `project_role`,
`primary_title`, `directory_title`, `office_location`, `email_address`,
`office_phone`, `phone_extension`. None of these exist on
`AWARD_UNIT_CONTACTS` in Oracle; they read like a guessed
person-directory shape (the kind of fields a Rolodex/contact-card
record might have), not a verified extraction. The real table is far
narrower: `AWARD_UNIT_CONTACT_ID`, `AWARD_ID`, `AWARD_NUMBER`,
`SEQUENCE_NUMBER`, `PERSON_ID`, `FULL_NAME`,
`UNIT_ADMINISTRATOR_TYPE_CODE`, `UNIT_CONTACT_TYPE`,
`UNIT_ADMINISTRATOR_UNIT_NUMBER`, `DEFAULT_UNIT_CONTACT`, plus standard
provenance columns — confirmed identically by both the Java OJB mapping
**and** the original Oracle bootstrap DDL
(`V300_107__schema.sql`/`V510_060__KC_TBL_AWARD_UNIT_CONTACTS.sql`).

**The original removal reason no longer holds**: a verified extraction
now exists, double-sourced (Java mapping + actual Oracle DDL), for the
narrower, real schema. Its business meaning (which unit(s) administer
an award, and who the designated unit-level contact person is) is a
real, BU-customized feature (per the `Award.xml` DD override), not
dead/unused scaffolding. This document reintroduces `award_unit_contact`
on that basis — with the corrected, verified schema, **not** a
restoration of V014's guessed one. This restores archive-side data
capture only: no API/UI/DTO work is in scope here (mirroring every
other Tier 1 subsystem completed so far, all ETL-only), and reinstating
the removed API/UI layer remains a separate, unrequested decision.

### Complete Award Contacts object graph

```
Award (AWARD)
├── AwardSponsorContact (AWARD_SPONSOR_CONTACTS)   [MISSING - this work]
│   └── roleCode -> ContactType (CONTACT_TYPE)     [unverified lookup, unjoined]
└── AwardUnitContact (AWARD_UNIT_CONTACTS)         [re-added, verified - this work]
    ├── unitAdministratorTypeCode -> UnitAdministratorType  [unverified lookup, unjoined]
    └── unitContactType -> UnitContactType (Java enum, not an Oracle lookup table)

AwardCentralAdminContact (subclass of AwardUnitContact, same table, zero
new fields) - not archived separately; its UI "Central Admin Contacts"
view is a transient rollup of UNIT_ADMINISTRATOR data, never persisted
under this name.
```

Both `AwardSponsorContact` and `AwardUnitContact` carry `AWARD_ID`/
`AWARD_NUMBER`/`SEQUENCE_NUMBER` directly (confirmed in both the OJB
mapping and the Oracle DDL) — flat, no join needed, the same shape
already used for `award_amount_info`/`award_person`/
`award_funding_proposal`/`award_custom_data`/`award_sponsor_term`/
`award_report_term`.

### Oracle tables, PK/FK mappings

| Table | PK column | Sequence | FK column(s) | Parent |
|---|---|---|---|---|
| `AWARD_SPONSOR_CONTACTS` | `AWARD_SPONSOR_CONTACT_ID` | `SEQUENCE_AWARD_ID` (shared) | `AWARD_ID`, `AWARD_NUMBER`, `SEQUENCE_NUMBER` (all direct) | `AWARD` |
| `AWARD_UNIT_CONTACTS` | `AWARD_UNIT_CONTACT_ID` | `SEQUENCE_AWARD_ID` (shared) | `AWARD_ID`, `AWARD_NUMBER`, `SEQUENCE_NUMBER` (all direct) | `AWARD` |

Both PKs draw from the shared `SEQUENCE_AWARD_ID` (confirmed in the OJB
mapping) — no `SEQ_AWARD_SPONSOR_TERM`/`SEQ_AWARD_CUSTOM_DATA_ID`-style
surprise this time. Neither table has a plural-vs-singular PK-column
naming mismatch either (`AWARD_SPONSOR_CONTACT_ID`/
`AWARD_UNIT_CONTACT_ID` are already singular "CONTACT", matching their
archive column names exactly) — the SQL boundary can select these
columns directly with no alias required, unlike
`10_award_report_terms.sql`'s `AWARD_REPORT_TERMS_ID` bug. Both
extraction files are still written with an explicit review of this
exact class of risk (see Test plan).

### Current archive coverage

- `archive.award_sponsor_contact` — missing, this work.
- `archive.award_unit_contact` — previously existed (V014), dropped
  (V033) for lack of verification, reintroduced here with a corrected,
  verified schema.

### Proposed target tables (new migration)

`database/migrations/V041__create_award_contacts.sql` (additive only —
`CREATE TABLE IF NOT EXISTS` + indexes):

```sql
CREATE TABLE IF NOT EXISTS archive.award_sponsor_contact (
    award_sponsor_contact_id  BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    rolodex_id                BIGINT,
    full_name                 VARCHAR(500),
    contact_role_code         VARCHAR(50),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_sponsor_contact_award ON archive.award_sponsor_contact (award_id, award_sponsor_contact_id);
CREATE INDEX ix_award_sponsor_contact_rolodex ON archive.award_sponsor_contact (rolodex_id);

CREATE TABLE IF NOT EXISTS archive.award_unit_contact (
    award_unit_contact_id            BIGINT PRIMARY KEY,
    award_id                         BIGINT NOT NULL
                                         REFERENCES archive.award_version(award_id)
                                         ON DELETE CASCADE,
    award_number                     VARCHAR(50),
    sequence_number                  INTEGER,

    person_id                        VARCHAR(50),
    full_name                        VARCHAR(500),
    unit_contact_type                VARCHAR(50),
    unit_administrator_type_code     VARCHAR(50),
    unit_administrator_unit_number   VARCHAR(30),
    default_unit_contact             VARCHAR(10),

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,

    loaded_at                        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT REFERENCES archive.load_run(load_id)
);
CREATE INDEX ix_award_unit_contact_award ON archive.award_unit_contact (award_id, award_unit_contact_id);
CREATE INDEX ix_award_unit_contact_person ON archive.award_unit_contact (person_id);
CREATE INDEX ix_award_unit_contact_unit ON archive.award_unit_contact (unit_administrator_unit_number);
```

Deliberately excludes every one of V014's unverified columns
(`unit_name`, `parent_unit_number`, `parent_unit_name`, `project_role`,
`primary_title`, `directory_title`, `office_location`, `email_address`,
`office_phone`, `phone_extension`) — none exist on the real table.
`default_unit_contact` is `VARCHAR(10)` (Y/N), matching
`archive.award_person.faculty_flag`'s existing convention, not a native
`BOOLEAN`.

### UPSERT conflict keys

Each table's own surrogate PK: `award_sponsor_contact_id`,
`award_unit_contact_id` — both globally unique via the shared
`SEQUENCE_AWARD_ID`, the same pattern as every flat (non-grandchild)
Award child table so far.

### Load order

Within `_run_load_award_id`'s existing family-widened transaction, after
the existing eleven tables: `award_unit_contact`, then
`award_sponsor_contact` (order between the two is arbitrary — neither
depends on the other or on anything else added in this pass; listed in
the same order as the user's own investigation request).

No new Oracle family-resolution scan, no new top-level load function —
both reuse `read_award_children_matching_award_ids` exactly as
`award_custom_data`/`award_sponsor_term`/`award_report_term` do (all
four carry `award_id` directly).

### Deletion/reconciliation strategy

Deferred, identically to every other Award child table so far — no
hard-delete, no soft-delete marking implemented. Same
recommended-but-unimplemented default already recorded in
`AWARD_IMPLEMENTATION_ROADMAP.md`. Not re-decided here.

### Family-widening behavior

Unchanged from Phase 4A: both tables are scoped to the same
already-resolved `family_award_ids` set. Neither interacts with
`is_primary_current` (exclusively an `archive.award_version` concern).

### Batch behavior

No new batch domain/entity_type. Both tables are children of the
`AWARD`/`AWARD` entity that already exists — they ride along for free on
`--create-batch`/`--load-batch`/`--show-batch`.

### Test plan

Extend `etl/tests/test_award_incremental_upsert.py` in place. New
fixtures: `_sponsor_contact_row`, `_unit_contact_row`; a
`sponsor_contacts`/`unit_contacts` param added to `_patched_oracle`
(defaulting to empty DataFrames, same safe-default pattern already
proven three times over). New/extended tests: insert-all-N-tables
(extending the existing "first load" test from eleven to thirteen
tables), reload-unchanged, a value-change-produces-an-update test for
at least one table, a does-not-touch-unrelated-award isolation test, a
dry-run test, and batch-level assertions. **Also add a
`AwardContactsSqlColumnContractTest`**, the same real-`.sql`-file column
parser introduced in `AWARD_TERMS_DESIGN.md` after the
`10_award_report_terms.sql` aliasing bug, run against both new
extraction files before the real-data smoke test — specifically to
catch this exact class of mistake locally, without needing Oracle
access, given how directly relevant it is here.

### Local real-data smoke-test plan

Same shape as every prior Tier 1 subsystem's, prepared but not run here
(requires BU VPN + a real AWS SSM session, outside this work's
authorization): BU VPN → `buaws` if needed → start the approved SSM
tunnel (`docs/runbooks/LOCAL_SETUP.md`'s exact target) → export
`POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` and
`ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` → pick one real `AWARD_ID`
with at least one row in `AWARD_SPONSOR_CONTACTS` and
`AWARD_UNIT_CONTACTS` → from `etl/`:
`uv run python load_awards_from_csv.py --load-award-id <award_id> --dry-run`,
inspect the report for all thirteen tables and confirm nothing
persisted → `uv run python load_awards_from_csv.py --load-award-id <award_id>`
(real load) → re-run the exact same command immediately → confirm the
second run reports `inserted=0 updated=0` and `unchanged` equal to each
new table's row count for that award (or `unchanged=0` if that award
genuinely has no rows in one of the two tables — legitimate, not a bug)
→ `uv run python scripts/reconcile_load.py --domain AWARD --limit 5` to
confirm no discrepancy.

## Open questions

- Same deletion/reconciliation and ID-reuse open questions already
  recorded in `AWARD_IMPLEMENTATION_ROADMAP.md` apply equally here.
- `CONTACT_TYPE`/`UnitAdministratorType` lookup descriptions are not
  joined in — same deferred-verification status as every other bare
  code in this subsystem family, not resolved here.
- Whether the API/UI layer for Award Contacts (removed alongside
  `award_unit_contact` in the original decision) should ever be
  restored is explicitly **not** decided by this document — this work
  is archive/ETL-only.

## Decisions

- `award_unit_contact` is reintroduced with a corrected, double-verified
  schema (Java OJB mapping + real Oracle bootstrap DDL) — not a
  restoration of V014's schema, which included several columns with no
  basis in the real table. The original removal reason ("no verified
  extraction") is resolved for archive/ETL purposes only; API/UI
  restoration is explicitly out of scope and not decided here.
  `AWARD_CENTRAL_ADMIN_CONTACTS` is excluded because it does not exist
  as a table — it is a transient UI rollup of `UNIT_ADMINISTRATOR` data,
  never persisted under an Award-contact identity.
- Award Contacts is kept structurally and archivally separate from
  Award People: different Kuali package intent (`AwardSponsorContact`/
  `AwardUnitContact` represent *external sponsor* and *internal
  unit-administration* contacts respectively, not project personnel),
  different tables, no FK relationship between the two subsystems'
  tables anywhere in the OJB mapping.
- `sponsor_term_id`/`contact_role_code`/`unit_administrator_type_code`/
  `unit_contact_type` all stay bare, unjoined values — consistent with
  the established unverified-lookup convention, not a re-litigation of
  it.

## Recommended implementation order

1. ~~Design: object graph, Oracle PK/FK mappings, archive coverage,
   V033 removal analysis, migration, UPSERT keys, load order, batch
   behavior, deletion strategy, family-widening behavior, test plan,
   smoke-test plan~~ — done.
2. ~~Migration (`V041`), verified against a throwaway database~~ — done.
3. ~~Oracle extraction SQL (both flat, no join needed)~~ — done.
4. ~~`prepare_sponsor_contacts`/`prepare_unit_contacts`,
   `upsert_award_sponsor_contact`/`upsert_award_unit_contact`~~ — done.
5. ~~Extend `_run_load_award_id`/`_run_load_award_batch`~~ — done.
6. ~~Tests (including the SQL/column contract test) + full validation
   (`pytest` 513 passed, `ruff` clean, `mypy` clean)~~ — done.
7. Local real-data smoke test (dry-run, real load, rerun, verify
   `unchanged` on every new table) — plan prepared, not yet run (no
   Oracle/RDS connectivity available in this session).
8. Next Tier 1 subsystem per `AWARD_DOMAIN_DECOMPOSITION.md` (Award
   Reporting or Award Subaward Summary — Award Attachments/Notepad's
   `AWARD_ATTACHMENT` half is already done; `AWARD_NOTEPAD` remains).

## Date last updated

2026-07-31 (design and implementation complete; local real-data smoke
test not yet run — see step 7 above).
