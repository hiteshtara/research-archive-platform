# Award Comment — Design

## Purpose

Design and implementation record for archiving `AwardComment` /
`AWARD_COMMENT` — historical Award-level comments, confirmed distinct
from `AwardNotepad`/`AWARD_NOTEPAD` (see Findings). Also records the
outcome of re-investigating `AwardCgb` per an explicit request to
reclassify it as NOT APPLICABLE unless real DDL proves otherwise (see
"AwardCgb reclassification finding" below) — the DDL proves otherwise,
so it is **not** reclassified.

## Scope

Strictly `AwardComment`/`AWARD_COMMENT`. Does not touch Time and Money,
Budget, SAP, Proposal, Negotiation, Subaward, Protocol,
`AwardExtension`, or `AwardCgb`.

## Source material used

- DataDictionary: `AwardComment.xml` in
  `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`.
- OJB mapping: the `AwardComment` `class-descriptor` in
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  (already located in a prior session while researching the Special
  Approvals bundle, re-verified here).
- Oracle bootstrap DDL:
  `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
  (`CREATE TABLE AWARD_COMMENT`, `SEQ_AWARD_COMMENT_ID`),
  `V300_258__schema-constraints.sql` (FK constraints),
  `V1804_005__fix_sequence_numbers_in_award_tables.sql` (confirms
  version-scoping), `V510_133__KC_IX_KRACOEUS-6157.sql` (index) —
  confirmed no other migration touches `AWARD_COMMENT`'s columns; the
  base schema is the complete, current physical shape.
- Current archive schema/ETL: `database/migrations/V001` through
  `V044`, `etl/load_awards_from_csv.py`.

## Findings

- Java: `org.kuali.kra.award.home.AwardComment`.
- Oracle table: `AWARD_COMMENT`. Columns confirmed directly from
  `V300_107__schema.sql`: `AWARD_COMMENT_ID NUMBER(8)`,
  `AWARD_ID NUMBER(22)`, `AWARD_NUMBER VARCHAR2(12)`,
  `SEQUENCE_NUMBER NUMBER(8)` (all three nullable at the Oracle DDL
  level - the third table found with this property, after
  `AWARD_APPROVED_SUBAWARDS` and `AWARD_COST_SHARE`),
  `COMMENT_TYPE_CODE VARCHAR2(3)`, `CHECKLIST_PRINT_FLAG VARCHAR2(1)`,
  `COMMENTS CLOB`, `UPDATE_TIMESTAMP DATE`, `UPDATE_USER VARCHAR2(60)`,
  `VER_NBR NUMBER(8) default 1`, `OBJ_ID VARCHAR2(36) NOT NULL`.
- Primary key: `AWARD_COMMENT_ID`, its own dedicated sequence
  `SEQ_AWARD_COMMENT_ID` - not the shared `SEQUENCE_AWARD_ID`.
- Foreign key to Award: `AWARD_ID`, **Oracle-enforced**
  (`FK_AWARD_COMMENT_AWARD_ID FOREIGN KEY (AWARD_ID) REFERENCES
  AWARD (AWARD_ID)`, confirmed in `V300_258__schema-constraints.sql`) -
  unlike several siblings in the prior Special Approvals bundle
  (`AWARD_APPROVED_FOREIGN_TRAVEL`, `AWARD_APPROVED_SUBAWARDS`), this
  one has a real physical FK.
- Belongs to a specific Award version, not the whole family: real
  `SEQUENCE_NUMBER` column, and
  `V1804_005__fix_sequence_numbers_in_award_tables.sql` explicitly
  backfills `award_comment.sequence_number` from the owning `AWARD`
  row (`update award_comment set award_comment.sequence_number =
  (select sequence_number from award a where a.award_id =
  award_comment.award_id)`) - confirming per-version scoping, the
  opposite of `AwardNotepad`'s whole-family scoping.
- Comment type/category: `COMMENT_TYPE_CODE` is a real, Oracle-enforced
  bare lookup code (`FK_AWARD_COMMENT_COMMENT_TYPE FOREIGN KEY
  (COMMENT_TYPE_CODE) REFERENCES COMMENT_TYPE (COMMENT_TYPE_CODE)`)
  into the small `COMMENT_TYPE` lookup table
  (`COMMENT_TYPE_CODE`/`DESCRIPTION`/`TEMPLATE_FLAG`/`CHECKLIST_FLAG`/
  `AWARD_COMMENT_SCREEN_FLAG`) - kept unjoined, same treatment as every
  other lookup code in this schema; `COMMENT_TYPE` itself is not
  archived. Notably, `commentTypeCode` is a real, OJB-mapped, Oracle-
  enforced field that is **not** exposed in the DataDictionary's own
  `<attributes>` list at all (a persisted-but-UI-hidden field, the same
  pattern already seen for `AwardCloseout.MULTIPLE` and
  `AwardPaymentSchedule`'s `LAST_UPDATE_*` pair) - captured anyway,
  since a DD omission does not mean the column isn't real business
  data.
- Text column length and nullability: `COMMENTS` is an unbounded
  `CLOB` (the DataDictionary's own `maxLength` is `999999999`,
  effectively "no limit"), nullable at the Oracle level. Archived as
  Postgres `TEXT`.
- `CHECKLIST_PRINT_FLAG` is a real `OjbCharBooleanConversion` field
  (genuine Java boolean semantics) but is stored as raw text
  (`VARCHAR(10)`), not converted to a Postgres boolean - consistent
  with every other `OjbCharBooleanConversion` column already archived
  in this schema (`default_unit_contact`, `restricted_view`,
  `multiple_flag`).
- Update timestamp and update user: standard `UPDATE_TIMESTAMP`/
  `UPDATE_USER` provenance pair, nullable at the Oracle level like the
  rest of the row - renamed to `source_update_timestamp`/
  `source_update_user` via the existing shared `_CHILD_COLUMN_RENAMES`
  mapping, no new rename entries needed.
- Derived/transient/stored-elsewhere: none - every field on
  `AwardComment` is a real, directly-persisted column on
  `AWARD_COMMENT` itself. No child records: the OJB mapping has no
  `collection-descriptor` for this class, and no other table's OJB
  mapping declares a foreign key back to `AWARD_COMMENT_ID`.
- Deletion/reconciliation: no Oracle-level `ON DELETE` behavior beyond
  the FK's default (`NO ACTION`); deferred here exactly as for every
  other Award child table (recorded, not implemented).
- **Confirmed distinct from `AwardNotepad`**: different Java class
  (`org.kuali.kra.award.home.AwardComment` vs.
  `org.kuali.kra.award.home.notepad.AwardNotepad` — different
  package), different table (`AWARD_COMMENT` vs. `AWARD_NOTEPAD`),
  different scoping (per-version vs. whole-family), different shape
  (`AwardComment` has a `commentTypeCode`/`checklistPrintFlag` and no
  `entryNumber`; `AwardNotepad` has an `entryNumber`/`noteTopic`/
  `restrictedView` and no comment-type concept at all). Two genuinely
  separate historical-record features that happen to have overlapping
  English names ("Comments" and "Notes"), preserved as two separate
  archive tables per the explicit instruction.

## AwardCgb reclassification finding

Explicitly asked to reclassify `AwardCgb` as NOT APPLICABLE "unless
real DDL proves it stores independent persisted business data." It
does: `V600_047__KC_TBL_AWARD_CGB.sql` (a later migration, not in the
base `V300_107` schema - explaining why an earlier, shallower pass
might have missed or doubted it) contains a real
`CREATE TABLE AWARD_CGB (...)` with substantial genuine business
columns - invoicing configuration (`INVOICING_OPTION`,
`AUTO_APPROVE_INVOICE`, `MIN_INVOICE_AMT`, `SUSPEND_INVOICING`),
billing state (`LAST_BILLED_DATE`, `PREV_LAST_BILLED_DATE`,
`FINAL_BILL`, `AMT_TO_DRAW`), and letter-of-credit review flags
(`LETTER_OF_CREDIT_REVIEW`, `LOC_CREATION_TYPE`) - plus a later
`V601_007__KRACOEUS-8814.sql` migration (`ALTER TABLE AWARD_CGB ADD
BILL_FREQ_CD varchar2(4)`) adding yet another real column. Its primary
key is `AWARD_ID` itself (no surrogate ID) - the same 1:1-with-a-
specific-Award-version shape as `AwardExtension`, not a lookup, not a
UI helper, not transient.

**Conclusion: `AwardCgb` is NOT reclassified as NOT APPLICABLE.** It
remains **NOT YET ARCHIVED** - real, persisted, un-designed Award
business data (BU's Commercial and Government Billing extension to
Award), grouped with `AwardExtension` as the two open "1:1 BU-specific
extension tables - worth archiving?" questions, exactly as before. The
instruction's own conditional ("unless real DDL proves...") is
satisfied by the DDL evidence above, so the classification stands
unchanged from `KUALI_ARCHIVE_COVERAGE.md`'s prior revision - this is
reported as a correction to the premise of the request, not silently
ignored.

## Archive mapping

| Oracle table | Archive table | UPSERT key |
|---|---|---|
| `AWARD_COMMENT` | `archive.award_comment` | `award_comment_id` |

## Load order

No FK relationship to any other table added in this or any prior
bundle beyond `award_version` itself - upserted after
`award_subcontracting_budgeted_goals` (the prior bundle's last table)
and before `mark_load_complete`, in both `_run_load_award_id` and
`_run_load_award_batch`.

## Reconciliation strategy

Deferred, identically to every other Award child table archived so
far.

## Open questions

- Whether Kuali ever hard-deletes `AWARD_COMMENT` rows in practice was
  not investigated - same open reconciliation question recorded for
  every other Award child table.
- `COMMENT_TYPE.AWARD_COMMENT_SCREEN_FLAG` suggests some comment types
  are specifically flagged as relevant to the Award Comment screen
  (as opposed to other comment-bearing screens elsewhere in Kuali that
  may share the same `COMMENT_TYPE` lookup) - not investigated further
  since `COMMENT_TYPE` itself remains an unarchived lookup either way.

## Decisions

- `award_id`/`award_number`/`sequence_number` are kept NOT NULL in
  this archive's schema even though nullable in Oracle, same
  precedent as `AWARD_APPROVED_SUBAWARDS`/`AWARD_COST_SHARE` - the
  extraction path structurally guarantees non-null values for every
  row actually read.
- `checklist_print_flag` is stored as raw text
  (`VARCHAR(10)`), not converted to a Postgres boolean, consistent with
  every other `OjbCharBooleanConversion` column already in this
  schema.
- `comment_type_code` is kept as a bare, unjoined lookup code;
  `COMMENT_TYPE` is not archived.
- `AwardCgb` is explicitly NOT reclassified - see the dedicated
  section above.

## Recommended implementation order

1. `V045__create_award_comment.sql`.
2. `sql/extract/award/27_award_comment.sql`.
3. `prepare_award_comments`, `upsert_award_comments`.
4. Wire into `_run_load_award_id` and `_run_load_award_batch` (report
   dict counters, reads, upsert loop, docstrings, log lines, CLI help
   text).
5. Tests: SQL column contract, insert/update/unchanged, dry-run
   rollback, unrelated-Award isolation, batch propagation, idempotent
   rerun, one-Oracle-read-per-table batch assertion, full-batch
   rollback on a bad row.

## Date last updated

2026-07-31 (initial version - Award Comment; `AwardCgb`
re-investigated and confirmed NOT reclassified).
