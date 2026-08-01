# Award Time and Money — Object Graph and Implementation Record

## Status

**Implemented.** The research pass below (object graph, relationship
summary, findings, traps) was completed and reviewed first, per
explicit instruction — Time and Money is far more interconnected than
every prior Award bundle (Comment, Extension/CGB, Basis/Method of
Payment), each of which touched one or two flat, mostly-independent
tables. Time and Money touches seven tables (six of its own, plus
`AWARD_HIERARCHY`, reclassified — see Findings and
`KUALI_ARCHIVE_COVERAGE.md`), all cross-referencing each other and
`AWARD_AMOUNT_INFO` through several different keys, two of which are
literally named the same column (`TRANSACTION_ID`) while meaning
completely different things in different tables. The "Implementation"
section near the end of this document records what was actually built,
including one correction to the research pass below (`creation_date`
**is** a real, OJB-mapped column — the original text below calling it
"Java field only" was wrong; see Implementation).

## Purpose

Build one complete, cross-verified object graph for the Time and Money
subsystem: every DataDictionary object, every Java business object,
every Oracle table and sequence, and every relationship to
`AWARD_AMOUNT_INFO`, to Award Version, and to Budget — so that the
eventual implementation bundle has no internal contradictions to
discover mid-build.

## Scope

Research only, `archive.award_version`/`archive.award_amount_info`
read for cross-reference but not modified. No other Award subsystem,
Proposal, Negotiation, Subaward, or Protocol touched.

## Source material used

- DataDictionary: `AwardAmountTransaction.xml`, `AwardTransactionType.xml`,
  `AwardTransactionSelectorBean.xml`, `PendingTransaction.xml`,
  `PendingTransactionExtension.xml`, `TimeAndMoneyDocument.xml`,
  `TransactionDetail.xml`, plus (adjacent, see Findings)
  `AwardHierarchy.xml`, `AwardHierarchyNode.xml`.
- OJB mapping: `coeus-impl/src/main/resources/org/kuali/kra/timeandmoney/repository-timeandmoney.xml`
  (the dedicated Time and Money mapping file) and
  `coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`
  (`AWARD_AMOUNT_INFO`'s own class-descriptor at lines 1329–1363 —
  including its two Time-and-Money-only columns,
  `TRANSACTION_ID`/`TNM_DOCUMENT_NUMBER`; `AWARD_HIERARCHY` at lines
  1448–1459; `AWARD_AMT_FNA_DISTRIBUTION` at lines 1305–1327).
- Java: the full `org.kuali.kra.timeandmoney` package tree (`document/`,
  `transactions/`, `history/`, `service/`, `service/impl/`, `rules/`),
  `edu.bu.kuali.kra.timeandmoney.transactions.PendingTransactionExtension`
  (BU-specific, confirmed below), `org.kuali.kra.award.awardhierarchy`
  (the real, persisted parent/child Award relationship this subsystem
  depends on), `org.kuali.kra.award.timeandmoney.AwardDirectFandADistribution`.
  Read in full or in large part:
  `ActivePendingTransactionsServiceImpl.java` (the core money-movement
  algorithm), `TimeAndMoneyHistoryServiceImpl.java` (the read-side
  history/reconciliation logic), `AwardAmountInfoServiceImpl.java`
  (confirms how "current" `AwardAmountInfo` state is resolved),
  `AwardHierarchyNode.java`, `AwardHierarchy.java`,
  `TransactionDetailType.java`, `TransactionType.java` (both enums).
- Generic Kuali Coeus bootstrap DDL
  (`coeus-db/coeus-db-sql/.../oracle/kc/bootstrap/V300_107__schema.sql`)
  for every table's base `CREATE TABLE`/`PRIMARY KEY` constraint, plus
  the later incremental `ALTER TABLE` migrations that added columns not
  present in the base schema (`V310_1_044`, `V310_1_054`, `V310_3_042`,
  `V510_082`, `V1507_016`, `V310_4_030`).
- `bu-db/BUKR-0020: add_budget_period_to_tm.sql` — confirms
  `PENDING_TRANSACTIONS_EXTENSION` is a genuine BU-specific
  customization (file literally named "add budget period to T&M"), not
  a generic Kuali table.
- Current archive state: `database/migrations/V011__create_award_archive_tables.sql`
  (`archive.award_amount_info`'s existing columns),
  `sql/extract/award/02_award_amounts.sql` (existing extraction),
  `docs/architecture/KUALI_ARCHIVE_COVERAGE.md` (existing
  classification of every Time-and-Money-adjacent DataDictionary
  entry, including the `AwardHierarchy` misclassification flagged
  below).

## The complete object graph

```
TimeAndMoneyDocument (TIME_AND_MONEY_DOCUMENT)                      [KEW workflow document, like AwardDocument]
  PK: documentNumber -> DOCUMENT_NUMBER VARCHAR2(10)                [no sequence - KEW-assigned doc number, same shape as AWARD_DOCUMENT]
  rootAwardNumber -> AWARD_NUMBER VARCHAR2(12) NOT NULL             [the Award family this T&M action was raised against]
  documentStatus -> TIME_AND_MONEY_DOC_STATUS VARCHAR2(10)          [added later, V1507_016__RESKC-561; default 'PENDING']
  creationDate -> CREATION_DATE TIMESTAMP (real OJB-mapped column, BU-specific - added by a later migration, "bu_add_create_date_tm.sql" ["BU: Add Creation Date to T&M Document. Required for sorting purposes."], backfilled from UPDATE_TIMESTAMP when added - archived as archive.time_and_money_document.creation_date)
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR
  |
  ├── pendingTransactions (1:many, FK documentNumber)  -> PendingTransaction (PENDING_TRANSACTIONS)   [transient/in-flight - see below]
  ├── awardAmountTransactions (1:many, FK documentNumber) -> AwardAmountTransaction (AWARD_AMOUNT_TRANSACTION)
  └── awardAmountInfos (1:many, FK AWARD_AMOUNT_INFO.TNM_DOCUMENT_NUMBER) -> AwardAmountInfo (AWARD_AMOUNT_INFO, already archived)

PendingTransaction (PENDING_TRANSACTIONS)                            [IN-FLIGHT ONLY - see "Pending vs. history" below]
  PK: transactionId -> TRANSACTION_ID NUMBER(10), sequence SEQ_TRANSACTION_ID
  documentNumber -> DOCUMENT_NUMBER VARCHAR2(10) NOT NULL            [FK to TimeAndMoneyDocument]
  sourceAwardNumber -> SOURCE_AWARD_NUMBER VARCHAR2(12) NOT NULL     [award_number, NOT award_id - see Traps]
  destinationAwardNumber -> DESTINATION_AWARD_NUMBER VARCHAR2(12) NOT NULL
  obligatedAmount/obligatedDirectAmount/obligatedIndirectAmount -> OBLIGATED_AMOUNT/_DIRECT_AMOUNT/_INDIRECT_AMOUNT NUMBER(12,2)
    (_DIRECT_AMOUNT/_INDIRECT_AMOUNT added later, V310_1_044)
  anticipatedAmount/anticipatedDirectAmount/anticipatedIndirectAmount -> ANTICIPATED_* NUMBER(12,2) (same later-add history)
  comments -> COMMENTS VARCHAR2(2000)
  processedFlag -> PROCESSED_FLAG CHAR(1) (added V310_3_042, default 'Y' NOT NULL - Y/N via OjbCharBooleanConversion)
  singleNodeTransaction -> SINGLE_NODE_TRANS CHAR(1) (added V510_082, nullable)
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR
  |
  └── extension (1:1, FK transactionId) -> PendingTransactionExtension (PENDING_TRANSACTIONS_EXTENSION, BU-SPECIFIC)
        PK/FK: transactionId -> TRANSACTION_ID NUMBER(10)            [no Oracle PK/FK constraint found - Java-declared PK only]
        budgetPeriod -> BUDGET_PERIOD VARCHAR2(30)                   [bare string - relationship to Budget, see below]

TransactionDetail (TRANSACTION_DETAILS)                              [PERMANENT - the actual history ledger]
  PK: transactionDetailId -> TRANSACTION_DETAIL_ID, sequence SEQ_TRANSACTION_DETAIL_ID
  awardNumber -> AWARD_NUMBER VARCHAR2(12) NOT NULL                  [the "current"/root award in context at this hop, not necessarily source or destination]
  sequenceNumber -> SEQUENCE_NUMBER NUMBER(4) NOT NULL               [Award VERSION relationship - see below, NOT the same award as awardNumber's row]
  transactionId -> TRANSACTION_ID NUMBER(10) NOT NULL                [= the originating PendingTransaction.transactionId - a real, confirmed-by-Java soft FK, no Oracle constraint]
  timeAndMoneyDocumentNumber -> TNM_DOCUMENT_NUMBER VARCHAR2(10) NOT NULL [FK to TimeAndMoneyDocument.documentNumber]
  sourceAwardNumber/destinationAwardNumber -> SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER VARCHAR2(12) NOT NULL
  obligatedAmount/obligatedDirectAmount/obligatedIndirectAmount, anticipatedAmount/anticipatedDirectAmount/anticipatedIndirectAmount
    -> same NUMBER(12,2) shape as PendingTransaction (direct/indirect added later, V310_1_054)
  comments -> COMMENTS VARCHAR2(200)                                  [note: shorter than PendingTransaction's 2000 - copied verbatim at creation time, can truncate]
  transactionDetailType -> TRANSACTION_DETAIL_TYPE VARCHAR2(12)       [plain text, values from the Java enum TransactionDetailType: PRIMARY, INTERMEDIATE, DATE - no lookup table]
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR

AwardAmountTransaction (AWARD_AMOUNT_TRANSACTION)                     [one row per (T&M document, affected award) pair - see Java comment, confirmed]
  PK: awardAmountTransactionId -> AWARD_AMOUNT_TRANSACTION_ID, sequence SEQ_AWARD_AMOUNT_TRANS_ID
  awardNumber -> AWARD_NUMBER VARCHAR2(12) NOT NULL
  documentNumber -> TRANSACTION_ID VARCHAR2(10) NOT NULL             [TRAP: this "TRANSACTION_ID" column stores the T&M DOCUMENT NUMBER, not a PendingTransaction id - see Traps]
  transactionTypeCode -> TRANSACTION_TYPE_CODE NUMBER(3)             [FK to AwardTransactionType - the SAME lookup table Award.transactionTypeCode already denormalizes in 01_award_versions.sql]
  noticeDate -> NOTICE_DATE DATE
  comments -> COMMENTS VARCHAR2(2000)
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR
  unique index UQ_AWARD_AMOUNT_TRANSACTIONS (AWARD_NUMBER, TRANSACTION_ID) - not a PK constraint, an index only
  |
  └── awardTransactionType (many:1) -> AwardTransactionType (AWARD_TRANSACTION_TYPE, ALREADY reachable via Award's own transaction_type_code join)

AwardAmountInfo (AWARD_AMOUNT_INFO, ALREADY ARCHIVED as archive.award_amount_info) - Time-and-Money-relevant columns NOT currently captured:
  transactionId -> TRANSACTION_ID BIGINT                             [FK to PendingTransaction.transactionId / TransactionDetail.transactionId - THE anchor linking a funding-state snapshot to the transaction that produced it]
  timeAndMoneyDocumentNumber -> TNM_DOCUMENT_NUMBER VARCHAR(100)      [ALREADY CAPTURED in archive.award_amount_info today - confirmed via V011]
  originatingAwardVersion -> ORIGINATING_AWARD_VERSION INTEGER        [the Award VERSION (sequence_number) this snapshot row was created against - relationship to Award Version, see below]
  entryType -> ENTRY_TYPE CHAR (Y/N converted)                        [not investigated further this pass - flagged open]
  eomProcessFlag -> EOM_PROCESS_FLAG CHAR (Y/N converted)              [not investigated further this pass - flagged open]
  anticipatedChange/obligatedChange -> ANTICIPATED_CHANGE/OBLIGATED_CHANGE NUMBER  [bare net-change totals, distinct from the already-archived _DIRECT/_INDIRECT split]
  antDistributableAmount/obliDistributableAmount -> ANT_DISTRIBUTABLE_AMOUNT/OBLI_DISTRIBUTABLE_AMOUNT NUMBER
  finalExpirationDate/currentFundEffectiveDate/obligationExpirationDate/amountObligatedToDate -> dates/amounts NOT currently archived

AwardHierarchy (AWARD_HIERARCHY) - real, persisted, Oracle-PK-enforced. CURRENTLY MISCLASSIFIED - see Findings.
  PK: awardHierarchyId -> AWARD_HIERARCHY_ID, sequence SEQUENCE_AWARD_ID (shared with core Award/AwardAmountInfo)
  rootAwardNumber/awardNumber/parentAwardNumber/originatingAwardNumber -> all VARCHAR2(12) NOT NULL
  active -> ACTIVE CHAR(1) (added later, V310_4_030, default 'Y' NOT NULL - Y/N converted)
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR
  This table is THE physical parent/child Award relationship that
  ActivePendingTransactionsServiceImpl walks (via the in-memory
  AwardHierarchyNode wrapper) to decide whether a PendingTransaction is
  a direct parent-child move, a child-parent move, or an indirect move
  requiring a common-parent walk. Time and Money cannot be correctly
  understood or archived without this table.

AwardHierarchyNode (no OJB mapping - confirmed transient)
  Extends AwardHierarchy, adds in-memory-only fields (current fund
  effective date, distributable amounts, lead unit name, PI name,
  title, awardId, hasChildren, etc.) assembled at runtime for display
  and for the money-routing algorithm. Nothing here is persisted beyond
  what AwardHierarchy itself already stores - confirmed NOT APPLICABLE,
  current classification is correct.

AwardDirectFandADistribution (AWARD_AMT_FNA_DISTRIBUTION) - real, child of BOTH Award and AwardAmountInfo
  PK: awardDirectFandADistributionId -> AWARD_AMT_FNA_DISTRIBUTION_ID, sequence SEQ_AWARD_AMT_FNA_DSTRBTN_ID
  awardId -> AWARD_ID NUMBER(22) (nullable in base DDL)
  awardNumber/sequenceNumber -> AWARD_NUMBER/SEQUENCE_NUMBER
  amountSequenceNumber -> AMOUNT_SEQUENCE_NUMBER NUMBER(4)
  awardAmountInfoId -> AWARD_AMOUNT_INFO_ID NUMBER(8)                 [explicit OJB reference-descriptor FK to AwardAmountInfo - a REAL, confirmed child of the anchor table]
  budgetPeriod -> BUDGET_PERIOD NUMBER(3)                             [relationship to Budget - bare integer period number, NOT a physical FK to any AWARD_BUDGET_EXT/BUDGET_PERIOD_EXT row - see Traps re: type mismatch against PendingTransactionExtension.budgetPeriod]
  startDate/endDate -> START_DATE/END_DATE DATE
  directCost/indirectCost -> DIRECT_COST/INDIRECT_COST NUMBER(12,2)
  standard UPDATE_TIMESTAMP/UPDATE_USER/OBJ_ID/VER_NBR
```

## Relationship summary (as explicitly requested)

**Every relationship to `AWARD_AMOUNT_INFO`:**
- `AWARD_AMOUNT_INFO.TRANSACTION_ID` (BIGINT, not currently archived) →
  soft FK to `PENDING_TRANSACTIONS.TRANSACTION_ID` /
  `TRANSACTION_DETAILS.TRANSACTION_ID`. No Oracle constraint declares
  this; it is proven only by Java (`ActivePendingTransactionsServiceImpl`
  sets `newAwardAmountInfo.setTransactionId(pendingTransaction.getTransactionId())`
  at every money-movement call site).
- `AWARD_AMOUNT_INFO.TNM_DOCUMENT_NUMBER` (VARCHAR, **already archived**
  today) → FK to `TIME_AND_MONEY_DOCUMENT.DOCUMENT_NUMBER`.
- `AWARD_AMT_FNA_DISTRIBUTION.AWARD_AMOUNT_INFO_ID` → explicit,
  OJB-reference-descriptor-declared FK to `AWARD_AMOUNT_INFO.AWARD_AMOUNT_INFO_ID`
  (still no Oracle-level constraint, consistent with the rest of the
  Award domain's pattern of Java/OJB-only FKs).
- "Current" `AwardAmountInfo` state for a given `award_id` is **not**
  determined by `MAX(TRANSACTION_ID)` despite the service method's
  name (`fetchAwardAmountInfoWithHighestTransactionId`) — it is
  determined by the **last row in `award_amount_info_id ASC` order**
  (the collection's declared OJB `<orderby>`), i.e. effectively
  `MAX(award_amount_info_id)` per award. Any reconciliation/"current
  state" logic built for the archive must use the surrogate PK, not
  the transaction_id column, to match Kuali's own behavior exactly.

**Every relationship to Award Version:**
- `AWARD_AMOUNT_INFO.ORIGINATING_AWARD_VERSION` (not currently
  archived) records the `sequence_number` of the specific Award version
  a Time-and-Money-created snapshot row belongs to. Only rows where
  both `ORIGINATING_AWARD_VERSION` and `TNM_DOCUMENT_NUMBER` are
  non-null are "Time-and-Money-created" rows, as opposed to the
  original row created when the Award was first entered (which has
  neither set) — confirmed directly from
  `TimeAndMoneyHistoryServiceImpl.getValidAwardAmountInfosAssociatedWithAwardVersion`.
- `TRANSACTION_DETAILS.SEQUENCE_NUMBER` (real, NOT NULL column) is set
  from `doc.getAward().getSequenceNumber()` at the moment the T&M
  document is approved — the version of the **root/current** award
  being viewed, not necessarily the version of the specific
  source/destination award named in that detail row.
- A single approved `PendingTransaction` can generate `AwardAmountInfo`
  rows against **more than one Award version simultaneously** — if the
  `ALLOW_TM_WHEN_PENDING_AWARD_PARAM` parameter is on,
  `handleTransaction()` updates both the "pending" and the "active"
  Award version for the same `awardNumber` in a single pass
  (`awardVersionService.getPendingAwardVersion`/`getActiveAwardVersion`),
  not just the single "working" version used when that parameter is
  off. This is a genuine, non-obvious multi-version fan-out that any
  extraction SQL must account for (it cannot assume one
  `AwardAmountInfo` row per document per award).
- `AWARD_HIERARCHY.ORIGINATING_AWARD_NUMBER` and `AWARD_AMT_FNA_DISTRIBUTION`'s
  `awardNumber`/`sequenceNumber` are further, separate version-scoped
  relationships (see object graph above).

**Every relationship to Budget:**
- `PENDING_TRANSACTIONS_EXTENSION.BUDGET_PERIOD` (VARCHAR2(30),
  BU-specific — added by `bu-db/BUKR-0020`) — a bare string budget
  period identifier, no Oracle-level FK to any Budget table.
- `AWARD_AMT_FNA_DISTRIBUTION.BUDGET_PERIOD` (NUMBER(3)) — a bare
  integer budget period number, also no Oracle-level FK. **Type
  mismatch worth flagging**: this is the same conceptual field
  (`BUDGET_PERIOD`) as `PENDING_TRANSACTIONS_EXTENSION.BUDGET_PERIOD`,
  but one is stored as text and the other as a number, in two
  physically distinct tables — do not assume they can be joined or
  compared directly without normalizing type first.
- Neither relationship is Oracle-enforced; both are conceptual-only
  cross-references to whatever Budget subsystem eventually gets
  archived (Tier 2, still deferred, unaffected by this research pass).

## Findings

### Pending vs. history — the core lifecycle

`PendingTransaction` is **transient/in-flight only**: a row created
while a Time and Money document is being edited, representing one
proposed money move between a source and destination award (by
`award_number`, not `award_id`). When the document is approved
(`ActivePendingTransactionsServiceImpl.approveTransactions`), each
unprocessed `PendingTransaction` is walked through the Award hierarchy
tree (parent/child/indirect relationship resolution against
`AWARD_HIERARCHY`) and, for every award touched along that path:
- one new `AwardAmountInfo` row is appended (never updated in place —
  this table is an append-only ledger; the "current" state is always
  the latest row by `award_amount_info_id`), with computed running
  totals/distributable amounts and change amounts, and
- one or more `TransactionDetail` rows are written, classified
  `PRIMARY` (the actual requested source→destination move) or
  `INTERMEDIATE` (every hop along the tree the money conceptually
  passes through to get there) — `DATE`-type detail rows are also
  possible (see `TimeAndMoneyHistoryServiceImpl.captureDateInfos`,
  triggered by a different rule flow, `TimeAndMoneyAwardDateSaveRule`,
  not investigated in depth this pass since it does not create
  `AwardAmountInfo`/`PendingTransaction` rows itself).
- One `AwardAmountTransaction` row is created per (document, affected
  award) pair — confirmed directly from the implementation's own
  comment: "AwardAmountTransaction table is going to have one entry
  per document, per affected award."

This means: `PendingTransaction`/`PENDING_TRANSACTIONS` itself is
**not** durable business history in the way every other Tier 1 table
archived so far has been — it is Kuali's own working/scratch state for
an in-progress document. Once approved, its content is copied forward
into `TransactionDetail` (`transaction_id` preserved as the link) and
the row's `processedFlag` is set true; the row is not deleted. **Open
question, not resolved this pass**: does BU's real Oracle retain
processed `PENDING_TRANSACTIONS` rows indefinitely (making them a
legitimate, if redundant with `TRANSACTION_DETAILS`, archival target),
or are they purged/reused? This must be checked against real BU Oracle
before deciding whether `PendingTransaction` needs its own archive
table or whether `TransactionDetail` alone is sufficient business
history (the current best guess, based on the code path, is that
`TransactionDetail` is the durable, complete historical record and
`PendingTransaction` is redundant once `processedFlag = 'Y', but this
is not yet proven against real data).

### `AwardHierarchy` is very likely misclassified in `KUALI_ARCHIVE_COVERAGE.md`

The current coverage matrix classifies `AwardHierarchy`/`AWARD_HIERARCHY`
as **NOT APPLICABLE**, grouped under "Multi-campus hierarchy and sync
(workflow-internal)" alongside `AwardSyncChange`/`AwardSyncLog`/
`AwardSyncStatus` (genuine multi-campus workflow bookkeeping tables,
unrelated to money). That grouping undersold `AwardHierarchy`: it is
the literal, real, Oracle-PK-enforced parent/child Award relationship
that Time and Money's own core money-routing algorithm reads and walks
on every single transaction approval — not incidental sync machinery.
**This is flagged here, not corrected here** — reclassifying it is an
editorial change that belongs to the Time and Money implementation
bundle itself (this pass is research-only, per instruction), but
whoever implements Time and Money should revisit that NOT APPLICABLE
status rather than accept it at face value.

### `AwardAmountInfoHistory`/`AwardVersionHistory`/`TimeAndMoneyDocumentHistory` are confirmed transient

None of the three appear anywhere in `repository-timeandmoney.xml` or
`repository-award.xml` — no OJB mapping exists for any of them. They
are pure in-memory view-assembly classes built at read time from
already-covered data (`AwardAmountInfo` + `TransactionDetail` +
`TimeAndMoneyDocument`), used only to render the Time and Money history
UI screen. Nothing to archive here beyond what the object graph above
already covers.

### `AWARD_AMOUNT_TRANSACTION`'s own transaction type vs. the in-memory `TransactionType` enum

Two completely unrelated concepts share the phrase "transaction type,"
and conflating them would be a real mistake:
- `AwardAmountTransaction.transactionTypeCode` → real, Oracle-persisted
  FK to `AWARD_TRANSACTION_TYPE` (`NEW`, `SUPPLEMENT`, etc.) — the
  **same lookup table** `Award.transactionTypeCode` already denormalizes
  in `01_award_versions.sql`. No new lookup table needed if Time and
  Money is implemented; the description can be joined the same way.
- `org.kuali.kra.timeandmoney.history.TransactionType` (Java enum:
  `DATE`, `MONEY`, `INITIAL`, `SINGLENODEMONEYTRANSACTION`) — a
  purely in-memory classification label used only by
  `TimeAndMoneyHistoryServiceImpl` to group `AwardAmountInfoHistory`
  entries for display. Never persisted anywhere, not the same value
  domain as `AWARD_TRANSACTION_TYPE`, and not to be confused with
  `TransactionDetailType` (`PRIMARY`/`INTERMEDIATE`/`DATE`, which *is*
  a real, persisted plain-text column on `TRANSACTION_DETAILS`).

## Traps for implementation (read before writing any code)

1. **`TRANSACTION_ID` means two different things across these tables.**
   On `PENDING_TRANSACTIONS`/`TRANSACTION_DETAILS`/`AWARD_AMOUNT_INFO`
   it is a `NUMBER`/`BIGINT` surrogate key tracing back to a specific
   `PendingTransaction` row. On `AWARD_AMOUNT_TRANSACTION` it is a
   `VARCHAR2(10)` column that actually stores the **Time and Money
   document number** (confirmed by both the OJB field name —
   `documentNumber` — and the Oracle DDL's `VARCHAR2(10) NOT NULL`
   width matching `TIME_AND_MONEY_DOCUMENT.DOCUMENT_NUMBER` exactly).
   Do not extract this column as a numeric transaction id.
2. **`BUDGET_PERIOD` has two different physical types** across
   `PENDING_TRANSACTIONS_EXTENSION` (VARCHAR2(30)) and
   `AWARD_AMT_FNA_DISTRIBUTION` (NUMBER(3)) — do not assume they can be
   compared or joined without normalizing type first, and do not
   assume either one is a real FK to a Budget table (neither is).
3. **A single approved `PendingTransaction` can produce more than one
   `AwardAmountInfo` row per award** (pending version + active version,
   when `ALLOW_TM_WHEN_PENDING_AWARD_PARAM` is on) and **can produce
   `AwardAmountInfo` rows for multiple different awards** (every hop
   from source to destination along the hierarchy tree, not just the
   two named endpoints). Do not design extraction SQL that assumes a
   1:1 relationship between a `PendingTransaction`/`TransactionDetail`
   and a single `AwardAmountInfo` row.
4. **`TimeAndMoneyDocument` is a real KEW workflow document**, exactly
   like `AwardDocument` — its lifecycle (routing, approval, cancellation)
   is governed by workflow state, not just the bare `TIME_AND_MONEY_DOC_STATUS`
   column. `TimeAndMoneyHistoryServiceImpl.removeCanceledDocs` explicitly
   filters out canceled documents by checking the KEW workflow document,
   not the status column — a purely SQL-based extraction cannot
   replicate that check without also reading (or at minimum being aware
   of) workflow-cancellation state, the same open question already
   flagged but unresolved for every other Award workflow document in
   this project.
5. **`AwardHierarchy` must be understood, and likely reclassified,
   before Time and Money can be correctly built** — see Findings above.
   Building Time and Money's extraction without first resolving how
   (or whether) `AWARD_HIERARCHY` gets archived would either silently
   omit the parent/child relationship data needed to interpret
   `TransactionDetail.transactionDetailType = INTERMEDIATE` rows
   correctly, or require re-deriving it later as a corrective migration.
6. **`AWARD_AMOUNT_INFO`'s two Time-and-Money columns are asymmetric
   today**: `TNM_DOCUMENT_NUMBER` is already archived (`archive.award_amount_info.tnm_document_number`,
   shipped in Phase 4A, apparently anticipating this exact follow-on);
   `TRANSACTION_ID` and `ORIGINATING_AWARD_VERSION` are not. Any
   corrective migration for `AWARD_AMOUNT_INFO` needs to add the latter
   two (plus the other not-yet-captured columns listed in the object
   graph above — `ANT_DISTRIBUTABLE_AMOUNT`, `OBLI_DISTRIBUTABLE_AMOUNT`,
   `AMOUNT_OBLIGATED_TO_DATE`, `FINAL_EXPIRATION_DATE`,
   `CURRENT_FUND_EFFECTIVE_DATE`, `OBLIGATION_EXPIRATION_DATE`,
   `ENTRY_TYPE`, `EOM_PROCESS_FLAG`, bare `ANTICIPATED_CHANGE`/
   `OBLIGATED_CHANGE`) — none of which are Time-and-Money-exclusive,
   all of which are read by the money-routing algorithm regardless.

## Open questions (updated after implementation)

- ~~Does BU's real Oracle retain `PENDING_TRANSACTIONS` rows after
  `processedFlag = 'Y'`, or are they purged/reused?~~ Not resolved by
  this bundle either (still no BU Oracle access in this environment —
  see Implementation's smoke-test plan) — decided to implement
  `archive.pending_transaction`/`archive.pending_transaction_extension`
  regardless, per explicit scope, rather than wait on the answer.
  Real-data verification remains a follow-up.
- ~~Should `AwardHierarchy` be reclassified out of NOT APPLICABLE and
  archived as part of (or immediately before) Time and Money?~~
  Resolved: yes — reclassified to NOT YET ARCHIVED (then archived as
  part of this same bundle) in `KUALI_ARCHIVE_COVERAGE.md`, per
  explicit instruction. See Implementation.
- `TransactionDetail`'s `DATE`-type rows come from a separate rule flow
  (`TimeAndMoneyAwardDateSaveRule`/`TimeAndMoneyAwardDateSaveRuleImpl`)
  not read in depth this pass — worth a closer look before
  implementation to confirm no additional tables are involved in that
  path.
- `AwardAmountInfo.entryType`/`eomProcessFlag` — real columns, not
  investigated beyond confirming their existence and boolean-conversion
  type this pass.
- No BU-specific override was found for `TIME_AND_MONEY_DOCUMENT`,
  `PENDING_TRANSACTIONS` itself (only its extension table),
  `TRANSACTION_DETAILS`, `AWARD_AMOUNT_TRANSACTION`,
  `AWARD_TRANSACTION_TYPE`, `AWARD_HIERARCHY`, or
  `AWARD_AMT_FNA_DISTRIBUTION` — all confirmed as generic Kuali Coeus
  tables (with real, later-migration schema evolution, all verified
  against the bootstrap DDL and subsequent `ALTER TABLE`s above) except
  `PENDING_TRANSACTIONS_EXTENSION`, which is BU's own addition.

## Decisions

- The research pass produced a record first, reviewed before any code
  was written, per explicit instruction.
- The object graph above is treated as internally consistent — every
  relationship claimed is backed by either an OJB
  `reference-descriptor`/`collection-descriptor`, a matching Oracle DDL
  column, or a direct Java code reference (cited inline), not inferred
  from naming alone.
- The `TRANSACTION_ID` naming collision (Trap 1) and the multi-version/
  multi-award fan-out (Trap 3) were treated as the two highest-risk
  facts for implementation to get wrong, and were the two the
  implementation's tests were built to prove directly (see
  Implementation).
- `AwardHierarchy` was reclassified (NOT APPLICABLE → NOT YET ARCHIVED
  → COMPLETE, all in this same pass) and archived alongside Time and
  Money's own tables, not separately — see
  `KUALI_ARCHIVE_COVERAGE.md`.
- `archive.award_amount_info` was extended in place (two new columns:
  `transaction_id`, `originating_award_version`) via a corrective
  migration rather than a new table, reusing the already-archived
  anchor per explicit instruction. The other not-yet-captured
  `AWARD_AMOUNT_INFO` columns flagged in Trap 6
  (`ant_distributable_amount`, `obli_distributable_amount`,
  `amount_obligated_to_date`, `final_expiration_date`,
  `current_fund_effective_date`, `obligation_expiration_date`,
  `entry_type`, `eom_process_flag`, bare `anticipated_change`/
  `obligated_change`) were deliberately **not** added — they are not
  Time-and-Money-exclusive and were not named in this bundle's explicit
  scope; they remain a clearly-documented, open completeness gap on
  `archive.award_amount_info`, not a silent omission.

## Implementation

### Final table graph and archive names

| Oracle table | Archive table | PK | Keying |
|---|---|---|---|
| `AWARD_HIERARCHY` | `archive.award_hierarchy` | `award_hierarchy_id` (Oracle's own surrogate) | `award_number` (version-agnostic, no `sequence_number`) |
| `TIME_AND_MONEY_DOCUMENT` | `archive.time_and_money_document` | `document_number` (KEW string, no sequence) | `root_award_number` |
| `PENDING_TRANSACTIONS` | `archive.pending_transaction` | `transaction_id` | `source_award_number`/`destination_award_number` |
| `PENDING_TRANSACTIONS_EXTENSION` | `archive.pending_transaction_extension` | `transaction_id` (FK to `pending_transaction`, real archive-side constraint) | 1:1 with `pending_transaction` |
| `TRANSACTION_DETAILS` | `archive.transaction_detail` | `transaction_detail_id` | `award_number`/`sequence_number` |
| `AWARD_AMOUNT_TRANSACTION` | `archive.award_amount_transaction` | `award_amount_transaction_id` | `award_number` |
| `AWARD_AMT_FNA_DISTRIBUTION` | `archive.award_direct_fanda_distribution` | `award_direct_fanda_distribution_id` (renamed from `AWARD_AMT_FNA_DISTRIBUTION_ID`, the authoritative Java field name) | `award_id` (real FK to `archive.award_version` and to `archive.award_amount_info`) |
| `AWARD_AMOUNT_INFO` (already archived) | `archive.award_amount_info` | unchanged | two new columns only: `transaction_id`, `originating_award_version` |

### Renamed fields (critical mapping rules)

- `AWARD_AMOUNT_TRANSACTION.TRANSACTION_ID` (`VARCHAR2(10)`, actually
  the Time and Money document number) → archived as
  `archive.award_amount_transaction.document_number`. Never exposed as
  `transaction_id` — proved by
  `AwardTimeAndMoneySqlColumnContractTest::test_award_amount_transaction_sql_transaction_id_becomes_document_number`
  and a dedicated end-to-end row test.
- Every other `TRANSACTION_ID` in this bundle
  (`archive.pending_transaction`, `archive.transaction_detail`,
  `archive.award_amount_info`) is the real numeric surrogate key and
  keeps that name unchanged.
- `AWARD_AMT_FNA_DISTRIBUTION_ID` → `award_direct_fanda_distribution_id`
  (the authoritative Java field name — same historical-naming-rename
  treatment as `AWARD_EXEMPT_NUMBER_ID`/`20_award_fanda_rate.sql`).
- `TIME_AND_MONEY_DOCUMENT.AWARD_NUMBER` → `root_award_number` — done in
  Python (`prepare_time_and_money_document`), not SQL, so the table can
  still be read via the shared
  `read_award_children_matching_award_numbers` bounded reader, which
  filters on a literal `AWARD_NUMBER` column.

### Load order

Within one family/batch transaction: `award_hierarchy` and
`time_and_money_document` first (no dependents), then
`pending_transaction` before `pending_transaction_extension` (real FK),
then `transaction_detail` and `award_amount_transaction` (no
dependents), then `award_direct_fanda_distribution` last (real FK to
the already-upserted `award_amount_info` row from earlier in the same
transaction). No ordering requirement exists between the groups
themselves.

### Nullable relationships

Every cross-table reference in this bundle except two is a bare,
unenforced column (no Oracle-level FK ever existed for any of them,
confirmed via `repository-timeandmoney.xml`/`repository-award.xml`):
`pending_transaction.document_number`,
`transaction_detail.transaction_id`,
`transaction_detail.time_and_money_document_number`,
`award_amount_transaction.document_number`,
`award_amount_info.transaction_id`. The two **real** archive-side FKs
are `pending_transaction_extension.transaction_id` (→
`pending_transaction`, safe: always upserted first) and
`award_direct_fanda_distribution.award_id`/`award_amount_info_id` (→
`award_version`/`award_amount_info`, both already-archived anchors,
always upserted earlier in the same transaction).

### Hierarchy traversal implications

`archive.award_hierarchy` stores each Award's own
`parent_award_number`/`root_award_number`/`originating_award_number`
as bare `award_number` references — the archive does not attempt to
resolve or validate the referenced parent/root/originating award
against this batch's own membership (that award may not be loaded in
the same batch at all). Reconstructing the full hierarchy tree for a
given root is a query-time join across `archive.award_hierarchy` rows
by `award_number = parent_award_number`, not something this ETL
resolves at load time.

### Budget-period type mismatch handling

Not normalized. `archive.pending_transaction_extension.budget_period`
stays `VARCHAR(50)` (Oracle: `VARCHAR2(30)`) and
`archive.award_direct_fanda_distribution.budget_period` stays
`INTEGER` (Oracle: `NUMBER(3)`) — both columns are archived verbatim
in their own native type, with the type mismatch documented in both
the migration and the extraction SQL comments so a future consumer
does not attempt to join them directly without casting.

### Reconciliation strategy

Same as every other Award child table in this project: whatever Oracle
returns on the next load (full, `--load-award-id`, or `--load-batch`)
overwrites the archive row via UPSERT, keyed by each table's own PK
above. No new reconciliation logic was introduced.

### Deletion strategy

No physical `DELETE` was found anywhere in the Kuali/BU source for any
of these seven tables. `archive.award_hierarchy.active` is preserved as
the one real soft-delete signal in this bundle (kept as raw text, per
this project's `OjbCharBooleanConversion` convention) — a row whose
`ACTIVE` flips to `'N'` in Oracle is still archived, with its new
`active = 'N'` value, not removed.

### Files changed

- Migrations: `V048__add_time_and_money_columns_to_award_amount_info.sql`,
  `V049__create_award_time_and_money.sql` (both verified against a
  throwaway database).
- Extraction SQL: `sql/extract/award/30_award_hierarchy.sql` through
  `36_award_direct_fanda_distribution.sql` (seven new files), plus
  `02_award_amounts.sql` (two new columns).
- ETL: `etl/load_awards_from_csv.py` (path constants, required-column
  sets, seven `prepare_*` functions, seven `_*_COLUMNS` lists, seven
  `upsert_*` functions, `upsert_award_amount_info` extended, wiring
  into both `_run_load_award_id` and `_run_load_award_batch`, report
  counters, docstrings, CLI help text — table count now
  "thirty-six"); `etl/archive_etl/pipeline/sources.py` (new
  `OracleDataSource.read_filtered_any_column` method, for
  `pending_transaction`/`pending_transaction_extension`'s
  source-or-destination-award-number filtering).
- Tests: `etl/tests/test_award_incremental_upsert.py` (seven fixture
  builders, `_amount_row` extended, `AwardTimeAndMoneySqlColumnContractTest`,
  `_patched_oracle`/`_oracle_batches_stub` extended, first-load/
  unchanged/dry-run tests extended, per-table value-change/isolation
  tests, hierarchy version-agnostic test, the
  `TRANSACTION_ID`-becomes-`document_number` test, the
  one-PendingTransaction-to-many-`AwardAmountInfo`-rows test, batch
  propagation/one-read-per-table/dry-run/idempotent/rollback tests
  extended).
- Docs: this file, `KUALI_ARCHIVE_COVERAGE.md`,
  `AWARD_DOMAIN_DECOMPOSITION.md`, `AWARD_IMPLEMENTATION_ROADMAP.md`.

### Real-data smoke-test plan (not run — no Oracle access in this environment)

1. `SELECT AWARD_HIERARCHY_ID, AWARD_NUMBER, PARENT_AWARD_NUMBER FROM AWARD_HIERARCHY WHERE ROWNUM <= 10;`
   — confirm real hierarchy data exists and shapes match.
2. Find an award family with real Time and Money activity:
   `SELECT tmd.AWARD_NUMBER, COUNT(*) FROM TIME_AND_MONEY_DOCUMENT tmd GROUP BY tmd.AWARD_NUMBER ORDER BY COUNT(*) DESC FETCH FIRST 10 ROWS ONLY;`
3. For one returned `award_number`, resolve an `award_id` via
   `archive.award_version` (or Oracle directly), then run
   `--load-award-id <id> --dry-run`, inspect the reported counts,
   then a real load, then an immediate rerun to confirm
   `unchanged` counts across all seven tables.
4. Explicitly verify `archive.award_amount_info` rows for that award
   include more than one row sharing the same `transaction_id` (the
   1:many fan-out) and that `archive.award_amount_transaction.document_number`
   holds a real Time and Money document number string, never a bare
   integer.
5. Verify against real BU Oracle whether any `PENDING_TRANSACTIONS`
   row for that document still exists with `PROCESSED_FLAG = 'Y'` (the
   still-open retention question above).

## Date last updated

2026-07-31 (research pass completed and reviewed; implementation
completed the same day — all seven tables, `AWARD_AMOUNT_INFO`
corrective migration, tests, `AwardHierarchy` reclassification).
