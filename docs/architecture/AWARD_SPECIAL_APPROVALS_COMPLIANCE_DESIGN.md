# Award Special Approvals and Compliance — Design

## Purpose

Design and implementation record for the nine real, currently-
unarchived Award "special approvals and compliance" tables surfaced by
the DataDictionary-driven pass in `KUALI_ARCHIVE_COVERAGE.md`:
`AWARD_CFDA`, `AWARD_COST_SHARE`, `AWARD_IDC_RATE` (business object
`AwardFandaRate`), `AWARD_SCIENCE_KEYWORD`, `AWARD_SPECIAL_REVIEW`,
`AWARD_EXEMPT_NUMBER` (business object `AwardSpecialReviewExemption`),
`AWARD_APPROVED_EQUIPMENT`, `AWARD_APPROVED_FOREIGN_TRAVEL`, and
`SUBCONTRACTING_BUD` (business object
`AwardSubcontractingBudgetedGoals`).

## Scope

Strictly these nine tables. Does not touch Award Budget, Time and
Money, SAP transmission, `AwardComment`, `AwardExtension`/`AwardCgb`,
Proposal, Negotiation, Subaward, Protocol, or any Award subsystem
already archived.

## Source material used

- DataDictionary: `AwardCfda.xml`, `AwardCostShare.xml`,
  `AwardFandaRate.xml`, `AwardScienceKeyword.xml`,
  `AwardSpecialReview.xml`, `AwardSpecialReviewExemption.xml`,
  `AwardApprovedEquipment.xml`, `AwardApprovedForeignTravel.xml`,
  `AwardSubcontractingBudgetedGoals.xml`, plus the separate bare
  `CFDA.xml` lookup file, in
  `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`.
- OJB mapping: every relevant `class-descriptor` in
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`,
  plus `repository.xml` for the `SpecialReviewType`/
  `SpecialReviewApprovalType` lookup tables.
- Oracle bootstrap DDL and later migrations:
  `coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
  (base schema), `V300_258__schema-constraints.sql` (FK constraints),
  `V1807_003__multi_cfda.sql` (the migration that actually created
  `AWARD_CFDA`, added well after the base schema), and
  `V510_093__KC_TBL_SUBCONTRACTING_BUD.sql` (created `SUBCONTRACTING_BUD`
  after the base schema) - every table/sequence/FK claim below was
  checked against these files directly, not inferred from Java.
- Current archive schema/ETL: `database/migrations/V001` through
  `V043`, `etl/load_awards_from_csv.py`.

## Findings

### AWARD_CFDA (special attention: real child table, not enrichment)

**Confirmed a real, physical child table** via `V1807_003__multi_cfda.sql`
- not inferred from the class name `AwardCfda`, per the explicit
verification request. That migration is titled "multi_cfda" and its
own backfill logic proves the business meaning directly: it created
`AWARD_CFDA` (along with three sibling tables for Proposal/EPS/S2S)
specifically to let an Award carry *multiple* CFDA numbers, migrating
the single scalar `AWARD.CFDA_NUMBER`/`CFDA_DESCRIPTION` columns that
existed before into this new one-to-many child table (backfilling one
row per Award that had a non-null `CFDA_NUMBER`). This is unambiguous
proof of "real child table representing a one-to-many relationship,"
not a read-only enrichment/reference view.

- Java: `org.kuali.kra.award.home.AwardCfda`.
- Oracle table: `AWARD_CFDA` - confirmed the literal, real name (not a
  differently-named table that merely backs a same-named Java class).
  Columns: `AWARD_CFDA_ID NUMBER(12,0) NOT NULL`,
  `AWARD_ID NUMBER(22,0) NOT NULL`, `AWARD_NUMBER VARCHAR2(12) NOT NULL`,
  `SEQUENCE_NUMBER NUMBER(8,0) NOT NULL`, `CFDA_NUMBER VARCHAR2(7) NOT NULL`,
  `CFDA_DESCRIPTION VARCHAR2(255)`, plus standard audit columns.
- Primary key: `AWARD_CFDA_ID`, own dedicated sequence `SEQ_AWARD_CFDA_ID`.
- Foreign key to Award: `AWARD_ID`, Oracle-enforced (`FK_AWARD_CFDA`).
- Belongs to a specific Award version: yes - real, NOT NULL
  `SEQUENCE_NUMBER`.
- `CFDA_NUMBER` is a bare lookup code into the separate `CFDA` table
  (Java class `org.kuali.kra.award.home.CFDA`, table `CFDA`, keyed by
  `CFDA_NBR`) - `CFDA_DESCRIPTION` is denormalized directly onto
  `AWARD_CFDA` itself (no join needed to read it), consistent with
  every other bare lookup code in this schema. The `CFDA` lookup table
  itself is NOT copied into the archive, same treatment as
  `ReportStatus`/`CloseoutReportType`/etc.
- Current archive coverage: none.

### AWARD_COST_SHARE

- Java: `org.kuali.kra.award.commitments.AwardCostShare`.
- Oracle table: `AWARD_COST_SHARE`. Columns:
  `AWARD_COST_SHARE_ID NUMBER(8)` (PK), `AWARD_ID NUMBER(22)`,
  `AWARD_NUMBER VARCHAR2(12)`, `SEQUENCE_NUMBER NUMBER(8)` (**all three
  nullable at the Oracle DDL level** - the second table found so far
  with this property, after `AWARD_APPROVED_SUBAWARDS`),
  `COST_SHARE_PERCENTAGE NUMBER(5,2)`, `COST_SHARE_TYPE_CODE NUMBER(3)`,
  `SOURCE VARCHAR2(32)`, `DESTINATION VARCHAR2(32)`,
  `COMMITMENT_AMOUNT NUMBER(12,2)`, `COST_SHARE_MET NUMBER(12,2)`,
  `VERIFICATION_DATE DATE`, plus audit columns. **The generic Kuali
  Coeus source tree's bootstrap DDL also shows a `FISCAL_YEAR
  VARCHAR2(4)` column here - real BU Oracle does not have one**,
  confirmed against the actual BU schema directly rather than the
  generic source tree; corrected after initial implementation
  (see Decisions and Open Questions).
- Primary key: `AWARD_COST_SHARE_ID`, own dedicated sequence
  `SEQ_AWARD_COST_SHARE_ID`.
- Foreign key to Award: `AWARD_ID`, Oracle-enforced
  (`FK_AWARD_COST_SHARE_AWARD_ID`).
- Belongs to a specific Award version: yes (real `SEQUENCE_NUMBER`
  column), though nullable at the DB level - handled the same way as
  `AWARD_APPROVED_SUBAWARDS`: kept NOT NULL in the archive since the
  extraction path structurally guarantees non-null values for any row
  actually read.
- **Special attention answered**: carries dates (`VERIFICATION_DATE`),
  a sequence number, and `SOURCE`/`DESTINATION` fields (both plain free
  text, NOT foreign keys - no `reference-descriptor` for either in the
  OJB mapping, and no matching lookup table exists). **No hierarchical
  child rows** - no `collection-descriptor` anywhere in the OJB
  mapping; this is a flat, single-level table.
- `unitNumber` (bare lookup to `Unit`) and `costShareTypeCode` (bare
  lookup to `CostShareType`, table `COST_SHARE_TYPE`) are both kept
  unjoined, consistent with every other lookup code in this schema.
- **Correction (post-implementation)**: the initial implementation of
  this table included `FISCAL_YEAR`, following the generic Kuali Coeus
  source tree's bootstrap DDL (which also has no corresponding OJB
  `field-descriptor` for it - itself already a yellow flag). Real BU
  Oracle was subsequently confirmed to have no `FISCAL_YEAR` column on
  `AWARD_COST_SHARE` at all - the extraction SQL, prepare function, and
  UPSERT were corrected to stop selecting/requiring/writing it.
  `archive.award_cost_share.fiscal_year` (added by the original `V044`
  migration) is deliberately left in place as a harmless, always-null
  column rather than rewriting an already-shipped migration - see
  Decisions. This is the first case in the whole Award domain where
  the generic Kuali Coeus source tree's own bootstrap DDL disagreed
  with real BU Oracle - every prior "trust the DDL" precedent in this
  project assumed the generic source tree's DDL accurately reflects
  BU's real schema, which held until now.
- Current archive coverage: none.

### AWARD_IDC_RATE (business object `AwardFandaRate`)

- Java: `org.kuali.kra.award.commitments.AwardFandaRate`. The business
  object was renamed from "IDC Rate" to "F&A Rate" at some point
  without renaming the underlying Oracle table/columns - every Java
  field name uses "fanda" terminology (`awardFandaRateId`,
  `applicableFandaRate`, `fandaRateTypeCode`) while every Oracle column
  still says "IDC" (`AWARD_IDC_RATE_ID`, `APPLICABLE_IDC_RATE`,
  `IDC_RATE_TYPE_CODE`). This is a deliberate historical rename, not a
  bug - handled the same way `AWARD_REPORT_TERMS_ID` (Oracle) vs.
  `awardReportTermId` (Java) was handled in `AWARD_TERMS_DESIGN.md`:
  the archive adopts the Java/business terminology (`award_fanda_rate_id`,
  `applicable_fanda_rate`, `fanda_rate_type_code`, etc.), aliased at
  the SQL extraction boundary.
- Oracle table: `AWARD_IDC_RATE`. Columns:
  `AWARD_IDC_RATE_ID NUMBER(12)` (PK), `AWARD_ID NUMBER(22) NOT NULL`,
  `AWARD_NUMBER VARCHAR2(12) NOT NULL`, `SEQUENCE_NUMBER NUMBER(8) NOT NULL`,
  `APPLICABLE_IDC_RATE NUMBER(5,2) NOT NULL`,
  `IDC_RATE_TYPE_CODE NUMBER(3) NOT NULL`, `FISCAL_YEAR VARCHAR2(4) NOT NULL`,
  `ON_CAMPUS_FLAG VARCHAR2(1) NOT NULL`, `UNDERRECOVERY_OF_IDC NUMBER(12,2)`,
  `SOURCE_ACCOUNT VARCHAR2(32)`, `DESTINATION_ACCOUNT VARCHAR2(32)`,
  `START_DATE DATE NOT NULL`, `END_DATE DATE`, plus audit columns.
- Primary key: `AWARD_IDC_RATE_ID`, sequence `SEQUENCE_AWARD_ID` (the
  shared sequence - only matters Oracle-side, this ETL never generates
  new IDs).
- Foreign key to Award: `AWARD_ID`, Oracle-enforced (`FK_AWARD_IDC_RATE`).
- Belongs to a specific Award version: yes, real NOT NULL
  `SEQUENCE_NUMBER`.
- Carries a real date range (`START_DATE` NOT NULL, `END_DATE`
  nullable) and `SOURCE_ACCOUNT`/`DESTINATION_ACCOUNT` free-text
  fields (no reference-descriptor, not foreign keys). No child rows.
- `fandaRateTypeCode` is a bare lookup into `FandaRateType`
  (table `IDC_RATE_TYPE`) - kept unjoined.
- Current archive coverage: none.

### AWARD_SCIENCE_KEYWORD (special attention: bridge table, confirmed)

- Java: `org.kuali.kra.award.home.keywords.AwardScienceKeyword`.
- Oracle table: `AWARD_SCIENCE_KEYWORD`. Columns:
  `AWARD_SCIENCE_KEYWORD_ID NUMBER(12)` (PK), `AWARD_ID NUMBER(22) NOT NULL`,
  `SCIENCE_KEYWORD_CODE VARCHAR2(15) NOT NULL`, plus audit columns.
  **No `AWARD_NUMBER`/`SEQUENCE_NUMBER` columns exist at all** - the
  only table in this bundle (and, so far, in the whole archived Award
  domain besides science keyword's own siblings below) with just
  `AWARD_ID` and nothing else identifying.
- Primary key: `AWARD_SCIENCE_KEYWORD_ID`, own dedicated sequence
  `SEQ_AWARD_SCIENCE_KEYWORD_ID`.
- Foreign key to Award: `AWARD_ID`, Oracle-enforced
  (`FK_AWARD_SCIENCE_KEYWORD`).
- **Special attention answered**: this is a genuine many-to-many
  *bridge* table between an Award version and the shared
  `SCIENCE_KEYWORD` lookup table (`FK_AWARD_SCIENCE_KEYWORD2` FK to
  `SCIENCE_KEYWORD(SCIENCE_KEYWORD_CODE)`) - one Award version can have
  many keyword rows, and one keyword code is shared across many awards.
  `SCIENCE_KEYWORD` itself (`SCIENCE_KEYWORD_CODE`/`DESCRIPTION`) is a
  genuine shared lookup and is **not** copied into the archive -
  `science_keyword_code` is stored as a bare code, same treatment as
  every other lookup code in this schema.
- Belongs to a specific Award version: yes, via `AWARD_ID` (no
  `SEQUENCE_NUMBER` column exists to confirm this independently, but
  the direct `AWARD_ID`-only FK, with no family-wide analog anywhere
  else in the schema, means it is definitionally tied to one exact
  version row).
- Because there is no denormalized `AWARD_NUMBER`/`SEQUENCE_NUMBER` on
  this table, the extraction SQL joins back to `AWARD` to populate
  them for schema consistency with every other archived table (the
  same join-to-denormalize approach already used for
  `award_person_unit_credit_split` and `award_report_term_recipient`) -
  see Decisions.
- Current archive coverage: none.

### AWARD_SPECIAL_REVIEW

- Java: `org.kuali.kra.award.specialreview.AwardSpecialReview`.
- Oracle table: `AWARD_SPECIAL_REVIEW`. Columns:
  `AWARD_SPECIAL_REVIEW_ID NUMBER(12)` (PK), `AWARD_ID NUMBER(22) NOT NULL`,
  `SPECIAL_REVIEW_NUMBER NUMBER(3) NOT NULL`,
  `SPECIAL_REVIEW_CODE NUMBER(3) NOT NULL`,
  `APPROVAL_TYPE_CODE NUMBER(3) NOT NULL`, `PROTOCOL_NUMBER VARCHAR2(20)`,
  `APPLICATION_DATE DATE`, `APPROVAL_DATE DATE`, `EXPIRATION_DATE DATE`,
  `COMMENTS CLOB`, plus audit columns. **No `AWARD_NUMBER`/
  `SEQUENCE_NUMBER` columns** - same shape as Science Keyword.
- Primary key: `AWARD_SPECIAL_REVIEW_ID`, own dedicated sequence
  `SEQ_AWARD_SPECIAL_REVIEW_ID`.
- Foreign key to Award: `AWARD_ID`, Oracle-enforced
  (`FK_AWARD_SPECIAL_REVIEW`).
- `SPECIAL_REVIEW_NUMBER` is the review's **own** per-award ordinal
  (assigned by the application, not this Oracle schema) - a distinct
  concept from an Award version's `SEQUENCE_NUMBER`, kept as its own
  archive column (`special_review_number`), never conflated with the
  version-level `sequence_number` this design also denormalizes in via
  the same `AWARD` join used for Science Keyword.
- `PROTOCOL_NUMBER` is free text, and is a **soft, non-enforced**
  cross-reference to Kuali's separate Protocol/IRB world by business
  key - it is not joined to `archive.irb_*` here (no Oracle-level FK
  exists to any protocol table, and IRB's own `PROTOCOL_NUMBER` scheme
  is tracked entirely independently). Kept as a bare text column - see
  Decisions.
- `specialReviewTypeCode`/`approvalTypeCode` are bare lookup codes
  (`SPECIAL_REVIEW_TYPE` and `SP_REV_APPROVAL_TYPE` real table names,
  confirmed via `repository.xml`, not `repository-award.xml` - both
  are cross-domain shared lookups, not copied in). Oracle declares both
  columns `NUMBER(3)` while the OJB mapping declares them `VARCHAR` -
  stored as text in the archive either way, consistent with how every
  other "numeric-looking code" column in this schema (e.g.
  `award_version.status_code`) is already treated as an opaque code,
  not a quantity.
- Has a real one-to-many child: `AWARD_EXEMPT_NUMBER` (see next
  section) - `AwardSpecialReview`'s own OJB `collection-descriptor`
  declares this explicitly.
- Current archive coverage: none.

### AWARD_EXEMPT_NUMBER (business object `AwardSpecialReviewExemption`; special attention: relationship to Special Review)

- Java: `org.kuali.kra.award.specialreview.AwardSpecialReviewExemption`.
  Java field name `awardSpecialReviewExemptionId` vs. Oracle column
  `AWARD_EXEMPT_NUMBER_ID` - the same kind of deliberate historical
  naming divergence as `AwardFandaRate`, handled the same way (archive
  adopts the Java-side name, aliased at the SQL boundary).
- Oracle table: `AWARD_EXEMPT_NUMBER`. Columns:
  `AWARD_EXEMPT_NUMBER_ID NUMBER(12)` (PK),
  `AWARD_SPECIAL_REVIEW_ID NUMBER(12) NOT NULL`,
  `EXEMPTION_TYPE_CODE VARCHAR2(3) NOT NULL`, plus audit columns.
- **Special attention answered - this is the key finding of the whole
  bundle**: `AWARD_EXEMPT_NUMBER` has **no `AWARD_ID` column at all** -
  confirmed directly from the `CREATE TABLE` statement, not assumed
  from the OJB mapping. Its only foreign key is
  `FK_AWARD_SPECIAL_REVIEW_ID FOREIGN KEY (AWARD_SPECIAL_REVIEW_ID)
  REFERENCES AWARD_SPECIAL_REVIEW (AWARD_SPECIAL_REVIEW_ID)` - a
  genuine parent/child relationship to `AwardSpecialReview`, not a
  direct relationship to `Award` at all. This mirrors
  `award_report_term_recipient`'s relationship to `award_report_term`
  exactly: **Special Review Exemptions relate to Special Reviews as
  true children, and only reach the owning Award by first resolving
  their parent Special Review.**
- Primary key: `AWARD_EXEMPT_NUMBER_ID`, own dedicated sequence
  `SEQ_AWARD_EXEMPT_NUMBER_ID`.
- Because there is no `AWARD_ID` on this table, the extraction SQL
  joins to `AWARD_SPECIAL_REVIEW` (on `AWARD_SPECIAL_REVIEW_ID`) to
  denormalize `AWARD_ID` (and, transitively through that table's own
  `AWARD` join, `AWARD_NUMBER`/`SEQUENCE_NUMBER`) - the same
  join-through-parent-then-grandparent shape already used for
  `award_person_unit_credit_split` (via `award_person_unit` →
  `award_persons`).
- **Load order requirement**: `AwardSpecialReview` rows MUST be
  upserted before `AwardSpecialReviewExemption` rows in the same
  transaction, exactly like `report_term` before
  `report_term_recipient` and `person_unit` before
  `person_unit_credit_split` - both `_run_load_award_id` and
  `_run_load_award_batch` order the exemption upsert loop after the
  special review upsert loop.
- `exemptionTypeCode` is a bare lookup into `EXEMPTION_TYPE` - kept
  unjoined.
- Current archive coverage: none.

### AWARD_APPROVED_EQUIPMENT

- Java:
  `org.kuali.kra.award.paymentreports.specialapproval.approvedequipment.AwardApprovedEquipment`.
- Oracle table: `AWARD_APPROVED_EQUIPMENT`. Columns:
  `AWARD_APPROVED_EQUIPMENT_ID NUMBER(22)` (PK),
  `AWARD_ID NUMBER(22) NOT NULL`, `AWARD_NUMBER VARCHAR2(12) NOT NULL`,
  `SEQUENCE_NUMBER NUMBER(4) NOT NULL`, `ITEM VARCHAR2(100) NOT NULL`,
  `VENDOR VARCHAR2(50)`, `MODEL VARCHAR2(50)`,
  `AMOUNT NUMBER(12,2) NOT NULL`, plus audit columns.
- Primary key: `AWARD_APPROVED_EQUIPMENT_ID`, sequence
  `SEQUENCE_AWARD_ID` (shared).
- Foreign key to Award: `AWARD_ID`, Oracle-enforced
  (`FK_AWARD_AWARD_APPROVED_EQUIP`).
- Belongs to a specific Award version: yes, real NOT NULL
  `SEQUENCE_NUMBER`.
- No lookups, no reference-descriptors at all in the OJB mapping - a
  plain, flat data table (item/vendor/model are all free text). No
  child rows.
- Current archive coverage: none.

### AWARD_APPROVED_FOREIGN_TRAVEL

- Java:
  `org.kuali.kra.award.paymentreports.specialapproval.foreigntravel.AwardApprovedForeignTravel`.
- Oracle table: `AWARD_APPROVED_FOREIGN_TRAVEL`. Columns:
  `AWARD_APPR_FORN_TRAVEL_ID NUMBER(22)` (PK), `AWARD_ID NUMBER(22) NOT NULL`,
  `AWARD_NUMBER VARCHAR2(12) NOT NULL`, `SEQUENCE_NUMBER NUMBER(4) NOT NULL`,
  `PERSON_ID VARCHAR2(40)`, `ROLODEX_ID NUMBER(6)`,
  `TRAVELER_NAME VARCHAR2(90)`, `DESTINATION VARCHAR2(30) NOT NULL`,
  `START_DATE DATE NOT NULL`, `END_DATE DATE`, `AMOUNT NUMBER(12,2)`
  (nullable at the DB level, despite the OJB mapping declaring
  `nullable="false"` - the DDL is treated as authoritative for
  persistence, same discipline used throughout this session), plus
  audit columns.
- Primary key: `AWARD_APPR_FORN_TRAVEL_ID`, sequence `SEQUENCE_AWARD_ID`
  (shared). Renamed to `award_approved_foreign_travel_id` in the
  archive for readability, aliased at the SQL boundary.
- Foreign key to Award: **no Oracle-level FK constraint exists** -
  confirmed absent from every migration touching this table (only its
  own `PRIMARY KEY` constraint was found). Java/OJB-layer relationship
  only, the same "no physical FK" pattern already seen for
  `AWARD_NOTEPAD` and `AWARD_APPROVED_SUBAWARDS`.
- Belongs to a specific Award version: yes, real NOT NULL
  `SEQUENCE_NUMBER`.
- `personId`/`rolodexId`/`travelerName` are bare, unjoined person
  references (same pattern as every other person-identifying column
  in this schema). `destination` is free text, not a lookup.
- No child rows.
- Current archive coverage: none.

### SUBCONTRACTING_BUD (business object `AwardSubcontractingBudgetedGoals`; the one genuine structural exception in this bundle)

- Java: `org.kuali.kra.award.subcontracting.goalsAndExpenditures.AwardSubcontractingBudgetedGoals`.
- Oracle table: `SUBCONTRACTING_BUD`. Columns:
  **`AWARD_NUMBER VARCHAR2(12) NOT NULL` is the table's own primary
  key** - confirmed via `ADD CONSTRAINT PK_SUBCONTRACTING_BUD PRIMARY
  KEY (AWARD_NUMBER)` in `V510_093__KC_TBL_SUBCONTRACTING_BUD.sql`.
  There is **no surrogate ID column, no `AWARD_ID` column, and no
  `SEQUENCE_NUMBER` column at all** - this table has exactly one row
  per `award_number`, full stop, with no tie to any specific Award
  version. The remaining columns are eight goal-amount fields
  (`LARGE_BUSINESS_GOAL`, `SMALL_BUSINESS_GOAL`, `WOMAN_OWNED_GOAL`,
  `SDB_GOAL`, `HUB_ZONE_GOAL`, `VETERAN_OWNED_GOAL`, `SDV_GOAL`,
  `HBCU_GOAL`, all `NUMBER(12,2)`), `COMMENTS VARCHAR2(2000)`, plus
  audit columns.
- **No foreign key to `AWARD` at all** - there is nothing to
  foreign-key against a specific version anyway, since this table
  isn't version-scoped.
- **This is the one table in the whole bundle where "UPSERT using an
  authoritative surrogate PK" does not apply** - there is no surrogate
  PK to use. The archive's UPSERT conflict key is `award_number`
  itself (a natural key), matching Oracle's own real schema exactly
  rather than inventing a surrogate ID Oracle doesn't have.
- Because this table has no `AWARD_ID` column, it cannot be read via
  the shared `read_award_children_matching_award_ids` bounded reader
  (which filters `WHERE AWARD_ID IN (...)`). A new bounded reader,
  `read_award_children_matching_award_numbers`, filters
  `WHERE AWARD_NUMBER IN (...)` instead - the same bind-variable/
  chunking discipline (`OracleDataSource.read_filtered`), just a
  different filter column. Reused by nothing else in this bundle (it
  is the only award_number-only-keyed table), but written as a general
  helper in case a future subsystem needs the same shape.
- Current archive coverage: none.

## Object graph summary

```
Award (award_id, specific version)
 |-- AwardCfda (award_id, award_number, sequence_number)
 |-- AwardCostShare (award_id, award_number, sequence_number - nullable in Oracle)
 |-- AwardFandaRate / AWARD_IDC_RATE (award_id, award_number, sequence_number)
 |-- AwardScienceKeyword (award_id only - award_number/sequence_number denormalized via join)
 |-- AwardSpecialReview (award_id only - award_number/sequence_number denormalized via join)
 |    `-- AwardSpecialReviewExemption / AWARD_EXEMPT_NUMBER
 |         (FK to AwardSpecialReview, NOT to Award directly -
 |          award_id/award_number/sequence_number denormalized via
 |          join through AwardSpecialReview then Award)
 |-- AwardApprovedEquipment (award_id, award_number, sequence_number)
 `-- AwardApprovedForeignTravel (award_id, award_number, sequence_number - no Oracle FK)

Award (award_number, the whole family - NOT version-scoped)
 `-- AwardSubcontractingBudgetedGoals / SUBCONTRACTING_BUD
      (PK = award_number itself, no award_id, no sequence_number)
```

## Archive mapping

| Oracle table | Archive table | UPSERT key |
|---|---|---|
| `AWARD_CFDA` | `archive.award_cfda` | `award_cfda_id` |
| `AWARD_COST_SHARE` | `archive.award_cost_share` | `award_cost_share_id` |
| `AWARD_IDC_RATE` | `archive.award_fanda_rate` | `award_fanda_rate_id` (aliased) |
| `AWARD_SCIENCE_KEYWORD` | `archive.award_science_keyword` | `award_science_keyword_id` |
| `AWARD_SPECIAL_REVIEW` | `archive.award_special_review` | `award_special_review_id` |
| `AWARD_EXEMPT_NUMBER` | `archive.award_special_review_exemption` | `award_special_review_exemption_id` (aliased) |
| `AWARD_APPROVED_EQUIPMENT` | `archive.award_approved_equipment` | `award_approved_equipment_id` |
| `AWARD_APPROVED_FOREIGN_TRAVEL` | `archive.award_approved_foreign_travel` | `award_approved_foreign_travel_id` (aliased) |
| `SUBCONTRACTING_BUD` | `archive.award_subcontracting_budgeted_goals` | `award_number` (natural key, no surrogate PK exists) |

## Load order

Within both `_run_load_award_id` and `_run_load_award_batch`, in this
order (after the existing fourteen-table sequence, before
`mark_load_complete`): `award_cfda`, `award_cost_share`,
`award_fanda_rate`, `award_science_keyword`, `award_special_review`,
`award_special_review_exemption` (**must** follow
`award_special_review` - its FK parent), `award_approved_equipment`,
`award_approved_foreign_travel`, `award_subcontracting_budgeted_goals`.
The first five and the last three have no FK relationship to each
other or to any prior table beyond `award_version`/(for the exemption)
`award_special_review`, so their relative order among themselves is
arbitrary but stable.

## Reconciliation strategy

Deferred, identically to every other Award child table archived so
far: insert/update only, no delete-on-disappearance.

## Open questions

- ~~`AWARD_COST_SHARE.FISCAL_YEAR`~~: resolved - confirmed absent from
  real BU Oracle (the generic Kuali Coeus source tree's own bootstrap
  DDL shows this column, but BU's real schema does not); the pipeline
  no longer selects, requires, or writes it. See the AWARD_COST_SHARE
  section above and Decisions.
- Whether `AWARD_IDC_RATE.FISCAL_YEAR` (a separate table, a separate
  column, genuinely OJB-mapped unlike Cost Share's) matches real BU
  Oracle has NOT been verified independently and must not be assumed
  correct just because Cost Share's turned out to be wrong - open,
  not yet investigated.
- `AWARD_SPECIAL_REVIEW.PROTOCOL_NUMBER`: a soft, non-enforced
  cross-reference to Kuali's Protocol/IRB world. Not joined to
  `archive.irb_*` in this pass - whether a future cross-domain view
  linking Award Special Reviews to their referenced IRB protocol is
  worth building is left open, not scoped here.
- Whether Kuali ever hard-deletes rows in any of these nine tables was
  not investigated - same open reconciliation question recorded for
  every prior Award child table.

## Decisions

- `AWARD_COST_SHARE.FISCAL_YEAR` was removed from the extraction SQL
  (`19_award_cost_share.sql`), the required-columns set, the
  `_AWARD_COST_SHARE_COLUMNS` list, and every clause of
  `upsert_award_cost_share` (INSERT column list, bind values, UPDATE
  SET, and `IS DISTINCT FROM` comparisons) after real BU Oracle
  confirmed the column does not exist there. The already-shipped `V044`
  migration's `archive.award_cost_share.fiscal_year` column is
  deliberately **not** removed via a schema rewrite - since it may
  already be applied against a real database, silently rewriting
  deployed migration history is avoided; the nullable, now-always-null
  column is left in place as harmless, with removal deferred to a
  future corrective migration if ever warranted, rather than assuming
  the migration was never applied anywhere.
- `AWARD_CFDA` confirmed a real child table via its own creating
  migration's backfill logic, not inferred from the class name, per
  the explicit verification request for this bundle.
- `AWARD_IDC_RATE`/`AWARD_EXEMPT_NUMBER`'s PK columns and
  `AWARD_IDC_RATE`'s rate-related columns are renamed in the archive to
  match their authoritative Java field names (`award_fanda_rate_id`,
  `applicable_fanda_rate`, `fanda_rate_type_code`,
  `award_special_review_exemption_id`) rather than their literal
  Oracle names - a deliberate historical business-terminology rename
  on Kuali's side, not a bug, handled with the same "Java field name is
  authoritative when it and Oracle diverge" precedent established for
  `AWARD_REPORT_TERMS_ID` in `AWARD_TERMS_DESIGN.md`.
  `AWARD_APPR_FORN_TRAVEL_ID` is likewise renamed to
  `award_approved_foreign_travel_id` purely for readability (an
  Oracle-identifier-length abbreviation, not a business-terminology
  divergence).
- `AWARD_SCIENCE_KEYWORD` and `AWARD_SPECIAL_REVIEW` both lack
  `AWARD_NUMBER`/`SEQUENCE_NUMBER` columns in Oracle; both are
  denormalized via an Oracle-side `JOIN` back to `AWARD` at extraction
  time so every archived table keeps the same `award_number`/
  `sequence_number` shape, consistent with the project's established
  join-to-denormalize pattern.
- `AWARD_EXEMPT_NUMBER` has no `AWARD_ID` at all; its extraction SQL
  joins through its true parent, `AWARD_SPECIAL_REVIEW`, then through
  that table's own join to `AWARD`, to denormalize `award_id`/
  `award_number`/`sequence_number` - and `award_special_review` MUST be
  loaded first in the same transaction.
- `SUBCONTRACTING_BUD` is the one table in this bundle without any
  surrogate PK or `AWARD_ID`/version tie at all - its archive table
  uses `award_number` as a natural-key UPSERT conflict key, and reads
  via a new `read_award_children_matching_award_numbers` bounded
  reader rather than the shared award_id-based one.
- Lookup tables referenced by this bundle (`CFDA`, `COST_SHARE_TYPE`,
  `IDC_RATE_TYPE`, `SCIENCE_KEYWORD`, `SPECIAL_REVIEW_TYPE` /
  `SP_REV_APPROVAL_TYPE`, `EXEMPTION_TYPE`, `Unit`) all remain
  unarchived lookups - every code column referencing them is stored as
  a bare code in the archive, never joined/copied in.

## Recommended implementation order

1. `V044__create_award_special_approvals_and_compliance.sql` - all
   nine tables in one migration.
2. `sql/extract/award/18_award_cfda.sql` through
   `26_award_subcontracting_budgeted_goals.sql`.
3. `prepare_*`/`upsert_award_*` for all nine, plus the new
   `read_award_children_matching_award_numbers` bounded reader.
4. Wire into `_run_load_award_id` and `_run_load_award_batch` (report
   dict counters, reads, upsert loops in FK-safe order, docstrings, log
   lines, CLI help text).
5. Tests: SQL column contract (all nine), insert/update/unchanged, dry-
   run rollback, unrelated-Award isolation, parent/child ordering
   (special review → exemption), one-Oracle-read-per-table batch
   assertion, idempotent rerun, full-batch rollback.

## Date last updated

2026-07-31 (initial version - Award Special Approvals and Compliance
bundle; corrected same day - `AWARD_COST_SHARE.FISCAL_YEAR` removed
from the pipeline after real BU Oracle disproved the generic Kuali
source tree's bootstrap DDL for this one column).
