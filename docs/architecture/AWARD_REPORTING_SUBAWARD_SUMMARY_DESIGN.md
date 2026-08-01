# Award Reporting and Subaward Summary — Design

## Purpose

Design and implementation record for the smallest complete archival of
the three real, currently-unarchived Award Reporting/Subaward Summary
tables: `AWARD_CLOSEOUT`, `AWARD_PAYMENT_SCHEDULE`,
`AWARD_APPROVED_SUBAWARDS`. Companion to
`AWARD_DOMAIN_DECOMPOSITION.md` (which first identified these as Tier
1 "Award Reporting"/"Award Subaward Summary" candidates) and
`KUALI_ARCHIVE_COVERAGE.md` (whose DataDictionary matrix already
tracked `AwardCloseout`/`AwardPaymentSchedule`/`AwardApprovedSubaward`
as NOT YET ARCHIVED before this bundle).

## Scope

Strictly these three tables. Does not touch Award Budget, Time and
Money, SAP transmission, Proposal, Negotiation, Subaward, Protocol, or
any of the other Award subsystems already archived (Custom Data,
People expansion, Terms, Contacts, Notepad). Does not touch the
`archive.subaward_funding` table from the separate Subaward domain -
`AWARD_APPROVED_SUBAWARDS` is a distinct, Award-scoped "approved
subaward line item" record with no foreign key or business
relationship to it.

## Source material used

- DataDictionary: `AwardCloseout.xml`, `AwardPaymentSchedule.xml`,
  `AwardApprovedSubawards.xml` in
  `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`.
- OJB mapping: the `AwardCloseout`, `AwardPaymentSchedule`,
  `AwardApprovedSubaward` `class-descriptor` blocks in
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`.
- Oracle bootstrap DDL: `CREATE TABLE AWARD_CLOSEOUT` /
  `AWARD_PAYMENT_SCHEDULE` / `AWARD_APPROVED_SUBAWARDS` and their
  `PRIMARY KEY`/sequence statements in
  `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`,
  cross-checked against every later `ALTER TABLE`/FK/index migration
  that touches these three tables (see Findings below for the full
  list) - the same double-verification discipline (Java mapping *and*
  real DDL, never one alone) used for every prior Award subsystem.
- Current archive schema/ETL: `database/migrations/V001` through
  `V042`, `etl/load_awards_from_csv.py`.

## Findings

### AwardCloseout

- Java: `org.kuali.kra.award.paymentreports.closeout.AwardCloseout`.
  Business purpose: tracks submission of an award's final closeout
  report (the administrative report closing out a completed award) -
  due date, final submission date, which closeout report type, and
  whether the report covers multiple award numbers together.
- Oracle table: `AWARD_CLOSEOUT`. Columns confirmed directly from
  `V300_107__schema.sql`: `AWARD_CLOSEOUT_ID NUMBER(12)`,
  `AWARD_ID NUMBER(22) NOT NULL`, `AWARD_NUMBER VARCHAR2(12) NOT NULL`,
  `SEQUENCE_NUMBER NUMBER(4) NOT NULL`,
  `CLOSEOUT_REPORT_CODE VARCHAR2(3) NOT NULL`,
  `CLOSEOUT_REPORT_NAME VARCHAR2(100) NOT NULL`, `DUE_DATE DATE`,
  `FINAL_SUBMISSION_DATE DATE`, `MULTIPLE CHAR(1)`,
  `UPDATE_TIMESTAMP DATE NOT NULL`, `UPDATE_USER VARCHAR2(60) NOT NULL`,
  `VER_NBR NUMBER(8) NOT NULL`, `OBJ_ID VARCHAR2(36)`.
- Primary key: `AWARD_CLOSEOUT_ID`, its own dedicated sequence
  `SEQ_AWARD_AWARD_CLOSEOUT` (confirmed in `V300_107__schema.sql`) -
  not the shared `SEQUENCE_AWARD_ID`.
- Foreign key to Award: `AWARD_ID`, enforced at the Oracle level
  (`FK_AWARD_CLOSEOUT`, added in `V300_258__schema-constraints.sql`).
  `AWARD_NUMBER`/`SEQUENCE_NUMBER` are denormalized alongside it, same
  pattern as every other Award child table.
- Belongs to a specific Award version, not the whole family:
  `SEQUENCE_NUMBER` is real and NOT NULL, and the upstream backfill
  migration `V1804_005__fix_sequence_numbers_in_award_tables.sql`
  explicitly re-synchronizes `award_closeout.sequence_number` from its
  owning `AWARD` row - confirming this is a per-version record, unlike
  `AwardNotepad` (which has no `SEQUENCE_NUMBER` column at all).
  `AWARD_ID` is still the correct UPSERT/family-widening key: it
  already identifies the exact version row.
- Nullable relationships: `MULTIPLE` (a real
  `OjbCharBooleanConversion` boolean field per the OJB mapping, but not
  listed in the DataDictionary's own `<attributes>` - a persisted but
  UI-hidden business flag, same treatment as `default_unit_contact`/
  `restricted_view` elsewhere in this schema: stored as a raw
  `VARCHAR(10)`, not converted to a Postgres boolean), `DUE_DATE`,
  `FINAL_SUBMISSION_DATE` are nullable; `AWARD_ID`/`AWARD_NUMBER`/
  `SEQUENCE_NUMBER`/`CLOSEOUT_REPORT_CODE`/`CLOSEOUT_REPORT_NAME`/
  `UPDATE_TIMESTAMP`/`UPDATE_USER` are NOT NULL at the Oracle level.
- Current archive coverage before this bundle: none.
- Deletion/reconciliation: no Oracle-level `ON DELETE` behavior beyond
  the FK's default (`NO ACTION`); deferred here exactly as for every
  other Award child table (recorded, not implemented).

### AwardPaymentSchedule

- Java:
  `org.kuali.kra.award.paymentreports.paymentschedule.AwardPaymentSchedule`.
  Business purpose: tracks a scheduled payment/invoice milestone for
  an award - due date, amount, submission/invoice tracking, and status.
- Oracle table: `AWARD_PAYMENT_SCHEDULE`. Base columns confirmed from
  `V300_107__schema.sql`: `AWARD_PAYMENT_SCHEDULE_ID NUMBER(12)`,
  `AWARD_ID NUMBER(22) NOT NULL`, `AWARD_NUMBER VARCHAR2(12) NOT NULL`,
  `SEQUENCE_NUMBER NUMBER(4) NOT NULL`, `DUE_DATE DATE`,
  `AMOUNT NUMBER(12,2)`, `UPDATE_TIMESTAMP DATE NOT NULL`,
  `UPDATE_USER VARCHAR2(60) NOT NULL`, `SUBMIT_DATE DATE`,
  `SUBMITTED_BY VARCHAR2(9)`, `INVOICE_NUMBER VARCHAR2(10)`,
  `STATUS_DESCRIPTION VARCHAR2(50)`, `STATUS VARCHAR2(5)`,
  `VER_NBR NUMBER(8) NOT NULL`, `OBJ_ID VARCHAR2(36)`. The OJB mapping
  also declares `awardReportTermId`, `lastUpdateUser`/
  `lastUpdateTimestamp`, `overdue`, `reportStatusCode`,
  `submittedByPersonId`, `awardReportTermDescription` - none of these
  are in the base `V300_107` DDL, so each was individually traced to
  the real later `ALTER TABLE` that actually added it (see below); this
  matters because Java/OJB field declarations alone are **not**
  sufficient proof a column physically exists (the same lesson
  `AwardCentralAdminContact` taught in `AWARD_CONTACTS_DESIGN.md`) -
  every one of these did check out against a real migration this time:
  - `V320_123__KC_TBL_AWARD_PAYMENT_SCHEDULE.sql`:
    `LAST_UPDATE_USER VARCHAR2(60) NULL`,
    `LAST_UPDATE_TIMESTAMP DATE NULL`, `OVERDUE NUMBER(15,5) NULL`,
    `REPORT_STATUS_CODE VARCHAR2(3) NULL`,
    `SUBMITTED_BY_PERSON_ID VARCHAR2(40) NULL`.
  - `V1802_013__award_payment_schedule_term_fk.sql`:
    `AWARD_REPORT_TERM_ID DECIMAL(12,0) NULL`, plus
    `FK3_AWARD_PAYMENT_SCHEDULE FOREIGN KEY (award_report_term_id)
    REFERENCES award_report_terms(award_report_terms_id)` - confirming
    the column is genuinely already singular (`AWARD_REPORT_TERM_ID`)
    despite optionally referencing the plural-named
    `AWARD_REPORT_TERMS`/`AWARD_REPORT_TERMS_ID` (the same naming
    inconsistency already fixed at the SQL boundary for
    `10_award_report_terms.sql` in `AWARD_TERMS_DESIGN.md` - here it
    cuts the other way and no alias is needed).
  - `V521_029__KC_TBL_AWARD_PAYMENT_SCHEDULE.sql`:
    `AWARD_REPORT_TERM_DESC VARCHAR2(100)`.
  - `V320_216__KC_FK2_AWARD_PAYMENT_SCHEDULE.sql`:
    `FK2_AWARD_PAYMENT_SCHEDULE FOREIGN KEY (report_status_code)
    REFERENCES report_status(report_status_code)` - a lookup FK, not
    joined here (bare code, consistent with every other lookup code in
    this schema).
- Primary key: `AWARD_PAYMENT_SCHEDULE_ID`, sequence
  `SEQUENCE_AWARD_ID` (the shared sequence, per the OJB mapping) - only
  matters for Oracle-side ID assignment; this ETL never generates new
  IDs, only ingests already-assigned ones.
- Foreign key to Award: `AWARD_ID`, enforced at the Oracle level
  (`FK_AWARD_PAYMENT_SCHEDULE`, `V300_258__schema-constraints.sql`).
- Belongs to a specific Award version: same evidence as
  `AwardCloseout` - real NOT NULL `SEQUENCE_NUMBER`, backfilled from
  the owning `AWARD` row by `V1804_005`.
- Nullable relationships: `AWARD_REPORT_TERM_ID` is a real, nullable,
  Oracle-enforced FK into `AWARD_REPORT_TERMS` (already archived as
  `archive.award_report_term` by the Terms bundle) - **deliberately
  not enforced as a physical FK in this archive**. It is an optional
  cross-reference into a table populated by an earlier, separate
  bundle, not a containment relationship, and every other bare
  cross-reference to another business object in this schema (e.g.
  `award_sponsor_term.sponsor_term_id`,
  `award_report_term_recipient.contact_id`) is likewise stored
  unjoined/unenforced. Keeping it unenforced also avoids introducing a
  new inter-bundle load-ordering requirement for a link that is purely
  informational to this archive. Indexed for lookups
  (`ix_award_payment_schedule_report_term`). Every other new column
  beyond the base six is nullable at the Oracle level.
- Current archive coverage before this bundle: none.
- Deletion/reconciliation: deferred, same as every other Award child
  table.

### AwardApprovedSubaward

- Java: `org.kuali.kra.award.home.approvedsubawards.AwardApprovedSubaward`.
  Business purpose: an approved subaward commitment line recorded
  directly on the Award (organization name/id and committed amount) -
  a summary record, not the detailed Subaward workflow object (that is
  a separate domain, already archived as `archive.subaward_funding`;
  no relationship between the two exists in Kuali).
- Oracle table: `AWARD_APPROVED_SUBAWARDS`. Columns confirmed from
  `V300_107__schema.sql`: `AWARD_APPROVED_SUBAWARD_ID NUMBER(8)`,
  `AWARD_ID NUMBER(22)`, `AWARD_NUMBER VARCHAR2(12)`,
  `SEQUENCE_NUMBER NUMBER(8)`, `ORGANIZATION_NAME VARCHAR2(60)`,
  `AMOUNT NUMBER(12,2)`, `UPDATE_TIMESTAMP DATE`,
  `UPDATE_USER VARCHAR2(60)`, `VER_NBR NUMBER(8)`,
  `ORGANIZATION_ID VARCHAR2(8)`, `OBJ_ID VARCHAR2(36) NOT NULL`.
- Primary key: `AWARD_APPROVED_SUBAWARD_ID`, its own dedicated
  sequence `SEQ_AWARD_APPROVED_SUBAWARD_ID`.
- Foreign key to Award: `AWARD_ID` - **no Oracle-level FK constraint
  exists** for it (only `FK_ORGANIZATION_ID FOREIGN KEY
  (organization_id) REFERENCES ORGANIZATION(organization_id)` was
  found in `V300_258__schema-constraints.sql`; confirmed absent by
  searching every bootstrap migration for a
  `REFERENCES AWARD (AWARD_ID)` constraint naming this table). The Java
  OJB `reference-descriptor` for `award` still declares
  `auto-delete="none"`, matching the "Java/OJB-layer relationship only,
  no physical FK" pattern already seen for `AwardNotepad`.
- Belongs to a specific Award version: same `SEQUENCE_NUMBER` evidence
  as the other two, backfilled by `V1804_005`.
- Nullable relationships: unlike `AwardCloseout`/`AwardPaymentSchedule`,
  **`AWARD_ID`, `AWARD_NUMBER`, and `SEQUENCE_NUMBER` are themselves
  nullable** at the Oracle DDL level for this one table - the only
  Award child table archived so far where that is true. This does not
  weaken this archive: every row this ETL actually extracts is, by
  construction, matched via a `WHERE AWARD_ID IN (...)` bind-variable
  filter against the requested family, so any row that reaches
  `prepare_approved_subaward` already has a non-null `award_id`. A
  hypothetical real row with a genuinely null `award_id` in Oracle
  would simply never be selected by any family-widened load - an
  honest, documented gap, not a silent one. `ORGANIZATION_NAME` is
  required at the DataDictionary/UI level (`required="true"`) but
  nullable at the DB level; `ORGANIZATION_ID` is a bare Oracle-side
  lookup code, kept unjoined.
- Current archive coverage before this bundle: none.
- Deletion/reconciliation: deferred, same as every other Award child
  table.

## Archive mapping

| Oracle table | Archive table | UPSERT key |
|---|---|---|
| `AWARD_CLOSEOUT` | `archive.award_closeout` | `award_closeout_id` |
| `AWARD_PAYMENT_SCHEDULE` | `archive.award_payment_schedule` | `award_payment_schedule_id` |
| `AWARD_APPROVED_SUBAWARDS` | `archive.award_approved_subaward` | `award_approved_subaward_id` |

All three carry `AWARD_ID`/`AWARD_NUMBER`/`SEQUENCE_NUMBER` directly -
no Oracle-side join needed for any of them (same flat shape as
`09_award_sponsor_terms.sql`/`10_award_report_terms.sql`). All three
`*_ID` primary key columns are already singular, matching the archive
column name exactly with no alias needed at the SQL boundary (double-
checked against the `AWARD_REPORT_TERMS_ID` naming bug from
`AWARD_TERMS_DESIGN.md`). Two columns on `award_payment_schedule` *do*
need SQL-side aliasing, both table-specific rather than shared
provenance columns: `AWARD_REPORT_TERM_DESC` →
`award_report_term_description`, and `LAST_UPDATE_TIMESTAMP`/
`LAST_UPDATE_USER` → `source_last_update_timestamp`/
`source_last_update_user` (kept distinct from the standard
`UPDATE_TIMESTAMP`/`UPDATE_USER` → `source_update_timestamp`/
`source_update_user` rename, since both audit-stamp pairs are real,
independently-populated columns on this one table).

## Load order

All three tables have no FK relationship to each other, and no FK
relationship to any table added in a previous bundle that requires
ordering (the one candidate, `award_payment_schedule.award_report_term_id`,
is deliberately unenforced - see Findings above). They are upserted
after `notepad` and before `mark_load_complete` in both
`_run_load_award_id` and `_run_load_award_batch`, in the order
closeout → payment_schedule → approved_subaward (an arbitrary but
stable choice, matching the order the three tables are listed in the
migration and everywhere else in this doc).

## Reconciliation strategy

Deferred, identically to every other Award child table archived so
far: this ETL only ever inserts/updates rows present in Oracle's
current extraction; it never deletes an archive row whose source row
disappeared. Recorded here as an open item, not silently ignored - see
Open questions.

## Open questions

- Whether Kuali ever hard-deletes `AWARD_CLOSEOUT`/
  `AWARD_PAYMENT_SCHEDULE`/`AWARD_APPROVED_SUBAWARDS` rows in practice
  (e.g. correcting a mis-entered payment schedule row) was not
  investigated - if so, this archive would retain a stale row
  indefinitely, same open reconciliation question already recorded for
  every other Award child table.
- Whether `award_payment_schedule.award_report_term_id` should
  eventually become a physical FK to `archive.award_report_term` once
  both bundles have been validated together against real production
  data is left as a future decision, not made here.

## Decisions

- `award_payment_schedule.award_report_term_id` is stored as a bare,
  indexed, unenforced BIGINT column, not a physical FK - see Findings
  above for the full reasoning (optional cross-bundle reference, not a
  containment relationship, avoids a new load-ordering coupling).
- `award_approved_subaward.award_id`/`award_number`/`sequence_number`
  are kept NOT NULL in this archive's schema even though the Oracle
  column itself is nullable, because the extraction path structurally
  guarantees non-null values for every row actually read - narrowing
  what a hypothetical genuinely-null-`award_id` row would mean (it
  would simply never be extracted) rather than accommodating it with a
  nullable archive column.
- `MULTIPLE`/`multiple_flag` is stored as raw text
  (`VARCHAR(10)`), not converted to a Postgres boolean, consistent with
  how `default_unit_contact` and `restricted_view` (both also
  `OjbCharBooleanConversion` fields) are already stored elsewhere in
  this schema.

## Recommended implementation order

1. `V043__create_award_reporting_and_subaward_summary.sql` - all three
   tables in one migration (mirrors the Terms bundle's three-tables-
   one-migration precedent).
2. `sql/extract/award/15_award_closeout.sql`,
   `16_award_payment_schedule.sql`, `17_award_approved_subaward.sql`.
3. `prepare_closeout`/`prepare_payment_schedule`/
   `prepare_approved_subaward`, `upsert_award_closeout`/
   `upsert_award_payment_schedule`/`upsert_award_approved_subaward`.
4. Wire into `_run_load_award_id` and `_run_load_award_batch` (report
   dict counters, reads, upsert loops, docstrings, log lines, CLI help
   text).
5. Tests: SQL column contract, insert/update/unchanged, dry-run
   rollback, unrelated-Award isolation, batch propagation, idempotent
   rerun, full-batch rollback on a bad row, one-Oracle-read-per-table
   assertion.

## Date last updated

2026-07-31 (initial version - Award Reporting/Subaward Summary bundle).
