# Award Extension and Award CGB — Design

## Purpose

Design and implementation record for the two real, persisted, 1:1-
with-Award BU-specific extension tables: `AwardExtension`/
`AWARD_EXTENSION` and `AwardCgb`/`AWARD_CGB`. Both were previously
tracked in `KUALI_ARCHIVE_COVERAGE.md` as an open "worth archiving at
all?" question; this bundle resolves that question for both by
confirming (not assuming) they are real persisted business data, then
archiving them.

## Scope

Strictly these two tables. Does not touch Time and Money, Budget, SAP
transmission (including `AWARD_TRANSMISSION`, a real child of
`AwardExtension` - see Findings), Proposal, Negotiation, Subaward, or
Protocol.

## Source material used

- DataDictionary: `AwardExtension.xml`, `AwardCgb.xml` in
  `coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`.
- OJB mapping: the `AwardExtension` and `AwardCgb` `class-descriptor`
  blocks in
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`.
- Oracle DDL: **neither table is created by the generic Kuali Coeus
  bootstrap schema** (`V300_107__schema.sql`) - both are BU-specific
  customizations, found instead in `bu-db/BUKR-0002: award_extension.sql`
  (the original `AWARD_EXTENSION` creation script) and
  `coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V600_047__KC_TBL_AWARD_CGB.sql`
  (the `AWARD_CGB` creation script, plus a follow-on
  `V601_007__KRACOEUS-8814.sql`). Also checked
  `V1511_001__FAIN.sql`/`V1603_999__bu_drop_fain_extension.sql` (a real
  schema-evolution event on `AWARD_EXTENSION` - see Findings) and every
  other file in `bu-db/` (`BUKR-0001`, `BUKR-0003`, `BUKR-0009`,
  `BUKR-0026`) to rule out any other undiscovered `AWARD_EXTENSION`/
  `AWARD_CGB` alteration.
- Current archive schema/ETL: `database/migrations/V001` through
  `V045`, `etl/load_awards_from_csv.py`.

## Findings

### AWARD_EXTENSION

- Java: `edu.bu.kuali.kra.award.home.AwardExtension` - note the
  `edu.bu.kuali` package (a BU-specific package, distinct from every
  other Award class in `org.kuali.kra.award.*`), itself a strong signal
  this is a genuine BU customization, not a generic Kuali feature.
- Oracle table: `AWARD_EXTENSION`. Original creation script
  (`bu-db/BUKR-0002: award_extension.sql`) declares: `AWARD_ID
  NUMBER(22)`, `PROPOSED_INDICATOR VARCHAR2(1)`, `LAST_TRANS_DATE DATE`,
  `CHILD_TYPE VARCHAR2(25)`, `CHILD_DESCRIPTION VARCHAR2(30)`,
  `MAJOR_PROJECT VARCHAR2(25)`, `ARRA_CODE VARCHAR2(25)`,
  `AVC_INDICATOR VARCHAR2(25)`, `A133_CLUSTER VARCHAR2(25)`,
  `FRINGE_NOT_ALLOWED_INDICATOR VARCHAR2(1)`,
  `INTEREST_EARNED VARCHAR2(25)`,
  `INTEREST_EARNED_ACCOUNT_NUMBER VARCHAR2(10)`,
  `FEDERAL_RATE_DATE VARCHAR2(25)`, `BU_BMC_FA_SPLIT VARCHAR2(5)`,
  `CONFERENCE_GRANT VARCHAR2(25)`, `PROGRAM_INCOME VARCHAR2(25)`,
  `STOCK_AWARD VARCHAR2(25)`, `FOREIGN_CURRENCY_AWARD VARCHAR2(25)`,
  `NCE_NOTIFICATION_DATE DATE`, `CLINICAL_TRIAL_INITIATED_BY VARCHAR2(25)`,
  `IND_IDE_RESPONSIBILITY VARCHAR2(25)`,
  `CLINICAL_TRIAL_REG_DATE DATE`, `SPUDS_RECORD_NUMBER VARCHAR2(25)`,
  `WALKER_SOURCE_NUMBER VARCHAR2(25)`,
  `PRIME_SPONSOR_AWARD_ID VARCHAR2(40)`, `GRANT_NUMBER VARCHAR2(10)`,
  `FEDERAL_CLINICAL_TRIAL VARCHAR2(1)`. **No `NOT NULL` or `DEFAULT`
  clause anywhere in this script** - every column, including
  `AWARD_ID`, is nullable with no default.
- **Real schema evolution confirmed**: the original script also had a
  `FAIN` column - `V1511_001__FAIN.sql` shows BU copying
  `AWARD_EXTENSION.FAIN` data into a new `AWARD.FAIN_ID` column, and
  the immediately following `V1603_999__bu_drop_fain_extension.sql`
  then drops `AWARD_EXTENSION.FAIN` entirely. The OJB mapping (below)
  correctly has no `fain` field at all - consistent with this
  timeline. This proves the table's schema has changed since its
  original creation script, which matters for judging the next point.
- Primary key / FK: the OJB mapping declares
  `<field-descriptor name="awardId" column="AWARD_ID" jdbc-type="BIGINT" primarykey="true"/>`
  - but **no `ALTER TABLE ... ADD CONSTRAINT ... PRIMARY KEY` statement
  for `AWARD_EXTENSION` was found anywhere in this checkout**, and
  **no Oracle-level FK constraint to `AWARD` was found either** (unlike
  `AwardCgb`, which has a confirmed physical PK - see below). Given the
  confirmed FAIN schema evolution above, it is plausible a PK-adding
  script exists outside what this checkout retains, but it cannot be
  confirmed one way or the other from available material. Archived
  anyway with `award_id` as the Postgres primary key - the same
  business-key choice OJB itself makes, and the only sensible key for
  a genuine 1:1 extension row - but this specific gap (no direct DDL
  proof of a physical Oracle PK/FK) is recorded honestly in Open
  Questions rather than silently assumed away.
- Belongs to a specific Award version: implicitly yes - `AWARD_ID` is
  the sole key, and `AWARD_ID` is Award's own per-version surrogate
  key. **No `AWARD_NUMBER`/`SEQUENCE_NUMBER` columns exist on this
  table at all** (neither OJB-mapped nor physically present) - the
  same shape as `AWARD_SCIENCE_KEYWORD`/`AWARD_SPECIAL_REVIEW` from the
  prior bundle. Denormalized via an Oracle-side `JOIN` back to `AWARD`
  at extraction time for schema consistency with every other archived
  table.
- All BU-specific extension fields: 27 real business columns (listed
  above), covering SAP-transmission eligibility flags
  (`proposedForTransmissionIndicator`/`lastTransmissionDate`),
  compliance/reporting classifications (`arraCode`, `avcIndicator`,
  `a133Cluster`, `majorProject`, `childType`/`childDescription`),
  financial/administrative flags (`fringeNotAllowedIndicator`,
  `interestEarned`/`interestEarnedAccountNumber`, `buBmcFaSplit`,
  `programIncome`, `stockAward`, `foreignCurrencyAward`,
  `conferenceGrant`), and clinical-trial-specific fields
  (`clinicalTrialInitiatedBy`, `INDIDEResponsibility`,
  `clinicalTrialRegistrationDate`, `federalClinicalTrial`), plus a
  handful of BU-internal tracking identifiers (`spudsRecordNumber`,
  `walkerSourceNumber`, `primeSponsorAwardId`, `grantNumber`).
- Nullable/default-value behavior: every column is nullable with no
  default, per the creation script - no exceptions.
- Computed/transient/lookup-derived: none - no `reference-descriptor`
  anywhere in the OJB mapping; every field is a bare, directly-stored
  value (several look code-like, e.g. `childType`/`avcIndicator`, but
  none are joined to a lookup table in the mapping). One naming oddity
  worth flagging, not resolving: the Java field `steppedUpRate` maps
  to Oracle column `FEDERAL_RATE_DATE` (a `VARCHAR2`, not a date,
  despite the column name) - archived under the Java field's name
  (`stepped_up_rate`), consistent with this project's "Java field name
  is authoritative when it and Oracle's literal name diverge"
  precedent, not further investigated.
- Child records: **yes, a real one** - `<collection-descriptor
  name="awardTransmissions" element-class-ref=
  "edu.bu.kuali.kra.bo.AwardTransmission" ...>` with
  `<inverse-foreignkey field-ref="awardId"/>`, backed by a real
  `AWARD_TRANSMISSION` table (confirmed in
  `bu-db/BUKR-0009: SAP_interface_implementation.sql`, alongside a
  sibling `AWARD_TRANSMISSION_CHILD` table). **This is SAP transmission
  tracking** - explicitly out of scope per this bundle's own
  instructions and every prior Award design doc's standing SAP
  exclusion. Documented here because the question was explicitly
  asked ("whether either table has child records"), not implemented.
- Current archive coverage: none.

### AWARD_CGB

- Java: `org.kuali.kra.award.cgb.AwardCgb`.
- Oracle table: `AWARD_CGB`, created by
  `V600_047__KC_TBL_AWARD_CGB.sql` (a later Kuali migration, not the
  base schema): `AWARD_ID NUMBER(22,0) NOT NULL`,
  `AWARD_NUMBER VARCHAR2(12) NOT NULL`, `SEQUENCE_NUMBER NUMBER(4,0)
  NOT NULL`, `ADDITIONAL_FORMS_REQ CHAR(1)`, `MIN_INVOICE_AMT
  NUMBER(19,2)`, `AUTO_APPROVE_INVOICE CHAR(1)`,
  `INVOICING_OPTION VARCHAR2(120)`, `STOP_WORK CHAR(1)`,
  `DUNNING_CAMPAIGN_ID VARCHAR2(4)`, `LAST_BILLED_DATE DATE`,
  `PREV_LAST_BILLED_DATE DATE`,
  `FINAL_BILL CHAR(1) DEFAULT 'N' NOT NULL`,
  `AMT_TO_DRAW NUMBER(19,2)`,
  `LETTER_OF_CREDIT_REVIEW CHAR(1) DEFAULT 'N' NOT NULL`,
  `INVOICE_DOCUMENT_STATUS VARCHAR2(45)`,
  `LOC_CREATION_TYPE VARCHAR2(45)`,
  `SUSPEND_INVOICING CHAR(1) DEFAULT 'N' NOT NULL`, plus audit columns
  (`UPDATE_TIMESTAMP`/`UPDATE_USER NOT NULL`,
  `VER_NBR DEFAULT 1 NOT NULL`, `OBJ_ID NOT NULL`). A later migration,
  `V601_007__KRACOEUS-8814.sql`, adds `BILL_FREQ_CD VARCHAR2(4)`
  (nullable, no default).
- Primary key: `AWARD_ID` - **Oracle-enforced**
  (`ALTER TABLE AWARD_CGB ADD CONSTRAINT AWARD_CGBP1 PRIMARY KEY
  (AWARD_ID)`, confirmed directly in `V600_047`). No surrogate ID -
  `AWARD_ID` is both the sole key and, functionally, the FK to `AWARD`.
- Foreign key to Award: **no Oracle-level FK constraint was found**
  anywhere referencing `AWARD(AWARD_ID)` from `AWARD_CGB` - Java/OJB-
  layer relationship only, the same "PK enforced, FK not" pattern
  already seen for a few other Award child tables this session.
- Belongs to a specific Award version: yes, unambiguously -
  `AWARD_NUMBER`/`SEQUENCE_NUMBER` are real, physically NOT NULL
  columns (unlike `AWARD_EXTENSION`, which has neither).
- All BU-specific extension fields: 15 real business columns covering
  invoicing configuration (`invoicingOption`, `autoApproveInvoice`,
  `minInvoiceAmount`, `suspendInvoicing`, `additionalFormsRequired`),
  billing state (`lastBilledDate`/`previousLastBilledDate`,
  `finalBill`, `amountToDraw`, `dunningCampaignId`), and letter-of-
  credit/CGB-specific tracking (`letterOfCreditReviewIndicator`,
  `locCreationType`, `invoiceDocumentStatus`, `stopWork`), plus the
  later `billFreqCd`/`BILL_FREQ_CD` addition (see below).
- Nullable/default-value behavior: `AWARD_ID`/`AWARD_NUMBER`/
  `SEQUENCE_NUMBER` NOT NULL (no defaults); `FINAL_BILL`/
  `LETTER_OF_CREDIT_REVIEW`/`SUSPEND_INVOICING` NOT NULL with a `'N'`
  default; every other business column nullable, no default.
- Computed/transient/lookup-derived: none - no `reference-descriptor`
  anywhere in the OJB mapping. **`BILL_FREQ_CD` has no OJB
  `field-descriptor` at all** despite being a real, physically-added
  column (`V601_007`) - the identical risk shape to
  `AWARD_COST_SHARE.FISCAL_YEAR`, which this same session already
  found to be a real column in the generic Kuali source tree's DDL
  that does **not** exist in real BU Oracle. `BILL_FREQ_CD` is
  included in this design and implementation as the best available
  evidence, but is flagged prominently in Open Questions as unverified
  against real BU Oracle and carrying the exact same risk profile - if
  it turns out not to exist in BU's real schema, the fix is identical
  to the Cost Share correction (stop selecting/requiring/writing it;
  leave the harmless archive column in place).
- Child records: none - no `collection-descriptor` anywhere in the
  OJB mapping.
- Current archive coverage: none.

## Archive mapping

| Oracle table | Archive table | UPSERT key |
|---|---|---|
| `AWARD_EXTENSION` | `archive.award_extension` | `award_id` |
| `AWARD_CGB` | `archive.award_cgb` | `award_id` |

Both use `award_id` itself as the UPSERT conflict key - a true 1:1
extension shape (one row per Award version, keyed by the same surrogate
id as `archive.award_version` itself), not a surrogate sequence id and
not a family-wide natural key like `award_subcontracting_budgeted_goals`
from the prior bundle.

## Load order

No FK relationship to each other or to any table in this or any prior
bundle beyond `award_version` itself. Upserted after `award_comment`
(the prior bundle's last table) and before `mark_load_complete`, in
both `_run_load_award_id` and `_run_load_award_batch` - Extension then
CGB, an arbitrary but stable choice.

## Reconciliation strategy

Deferred, identically to every other Award child table archived so far.

## Open questions

- **No direct DDL evidence of a physical Oracle PK or FK constraint on
  `AWARD_EXTENSION`** was found in this checkout, despite confirmed
  real schema evolution (the FAIN column add/copy/drop) proving the
  table's history extends beyond the one creation script available.
  Archived using `award_id` as the Postgres PK regardless (the same
  choice OJB's own mapping makes), but this specific gap in available
  evidence is recorded honestly rather than silently assumed away.
- **`AWARD_CGB.BILL_FREQ_CD` is unverified against real BU Oracle** -
  a real column in the generic Kuali source tree's DDL with no OJB
  mapping, the same risk shape as the `AWARD_COST_SHARE.FISCAL_YEAR`
  column this session already found to be fictional in real BU Oracle.
  Needs the same kind of direct verification before being trusted.
- `AwardExtension.steppedUpRate`/`FEDERAL_RATE_DATE` naming mismatch
  (a "rate" field mapped to a column literally named "date", stored as
  `VARCHAR2` rather than `DATE`) was not investigated further.
- `AwardExtension`'s real `awardTransmissions`/`AWARD_TRANSMISSION`
  child relationship (SAP transmission tracking) remains entirely out
  of scope, consistent with every prior Award design doc's standing
  SAP exclusion - not a gap, a deliberate boundary.
- Whether Kuali ever hard-deletes rows in either table was not
  investigated - same open reconciliation question recorded for every
  prior Award child table.

## Decisions

- Both tables are archived as real, confirmed persisted business data
  - the "worth archiving at all?" open question from
  `KUALI_ARCHIVE_COVERAGE.md` is resolved to **yes** for both,
  resolving the two remaining Tier 1 "1:1 BU-specific extension table"
  entries.
- `award_id` is used directly as the archive table's primary key for
  both tables (not a surrogate sequence id) - the correct, minimal
  choice for a true 1:1 extension shape, and the same key OJB itself
  declares.
- `AWARD_EXTENSION.AWARD_NUMBER`/`SEQUENCE_NUMBER` are denormalized via
  an Oracle-side `JOIN` back to `AWARD` at extraction time, since
  neither column exists on the table itself - the same
  join-to-denormalize pattern already used for `AWARD_SCIENCE_KEYWORD`/
  `AWARD_SPECIAL_REVIEW`.
- `AWARD_CGB.BILL_FREQ_CD` is included despite having no OJB mapping,
  on the same "real physical column, include it" precedent used
  elsewhere in this project - but is explicitly flagged (see Open
  Questions) as carrying the same risk the Cost Share `FISCAL_YEAR`
  column already turned out to embody this session, rather than
  silently trusted.
- `AwardExtension`'s real `AWARD_TRANSMISSION` child collection is
  documented but not archived, per the standing SAP exclusion.

## Recommended implementation order

1. `V046__create_award_extension_and_cgb.sql`.
2. `sql/extract/award/28_award_extension.sql`,
   `29_award_cgb.sql`.
3. `prepare_award_extension`/`prepare_award_cgb`,
   `upsert_award_extension`/`upsert_award_cgb`.
4. Wire into `_run_load_award_id` and `_run_load_award_batch` (report
   dict counters, reads, upsert loops, docstrings, log lines, CLI help
   text).
5. Tests: SQL column contract, insert/update/unchanged, dry-run
   rollback, unrelated-Award isolation, batch propagation, idempotent
   rerun, one-Oracle-read-per-table batch assertion, full-batch
   rollback.

## Date last updated

2026-07-31 (initial version - Award Extension and Award CGB bundle).
