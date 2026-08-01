# Award Domain — Full Kuali Source Study

## Purpose

Build the complete Award object graph and Oracle dependency map — every
table Award connects to across Budget, Time-and-Money, Proposal,
Negotiation, Subaward, Attachments, Contacts, and Reports — from the real
upstream Kuali Research source tree, to fully understand the Kuali data
model before designing or building the next generation of Award ETL work.

## Scope

Award and everything it references or is referenced by, at the Oracle/OJB
level. Four parallel research passes covered: (1) Award core +
`AwardAmountInfo`/`AwardPerson`/`AwardFundingProposal`/`AwardUnitContact`;
(2) Award Budget + Time-and-Money; (3) Award↔Proposal +
Award↔Negotiation; (4) Award↔Subaward + Attachments/Contacts/Reports.
Does not cover Proposal, Negotiation, Subaward, or Protocol's own
internal object graphs beyond their point of connection to Award.

## Source material used

- Full upstream Kuali Research source tree:
  `/Users/mukadder/kuali-project/kuali-research` (`coeus-impl/src/main/resources`
  and `coeus-impl/src/main/java`, primarily
  `org/kuali/kra/award/repository-award.xml`,
  `org/kuali/coeus/common/budget/impl/repository-budget.xml`,
  `org/kuali/kra/timeandmoney/repository-timeandmoney.xml`,
  `org/kuali/kra/institutionalproposal/repository-institutionalproposal.xml`,
  `org/kuali/kra/negotiations/repository-negotiation.xml`,
  `org/kuali/kra/subaward/repository-subAward.xml`)
- BU 7.3's own OJB mapping: `reference/kuali/award/repository-award.xml`
  (395 lines) and supporting files (`Award.xml`, `AwardBudgetDocument.xml`,
  `AwardPersonUnit.xml`, `AwardReportTerm.xml`, `AwardSpringBeans.xml`,
  `AwardDocument.xml`)
- This platform's current schema/loaders: `database/migrations/`,
  `etl/load_awards_from_csv.py`, `etl/load_award_attachments.py`

## Assumptions

- BU 7.3's `reference/kuali/award/` files are authoritative for what
  actually runs against BU's real Oracle instance; the upstream tree may
  reflect a newer/different KC version BU never upgraded to. Every
  divergence between the two is flagged, not silently resolved in favor
  of the upstream (newer) version.
- "Archived" means a Postgres table + loader exists; it does not imply
  full column coverage (see the Award-core field-by-field diff below for
  how large that gap can be even on an already-archived table).

## Findings

### Key corrections

**Budget and Time-and-Money are NOT already represented in
`award_amount_info`.** This directly contradicted the working assumption
carried into this study. Verified against `repository-budget.xml` and
`repository-timeandmoney.xml`:

- **Award Budget is fully disjoint** from `AWARD_AMOUNT_INFO` — `BUDGET`
  → `AWARD_BUDGET_EXT` → `BUDGET_PERIODS` → `BUDGET_DETAILS`
  (object-code-level line items) → `BUDGET_PERSONNEL_DETAILS`
  (salary/effort) → calculated-amount tables, plus `AWARD_BUDGET_LIMIT` —
  with **no FK to or from `AWARD_AMOUNT_INFO` anywhere**.
- **Time-and-Money partially overlaps** (the committed T&M action's
  resulting snapshot lands in `AWARD_AMOUNT_INFO`, joined via
  `TNM_DOCUMENT_NUMBER`) but the workflow/transaction layer around it —
  `TIME_AND_MONEY_DOCUMENT`, `PENDING_TRANSACTIONS`,
  `AWARD_AMOUNT_TRANSACTION`, `TRANSACTION_DETAILS` — is separate,
  unarchived, and not derivable from `award_amount_info` alone.

This does not change Phase 4's scope (the user explicitly kept Phase 4 at
the four already-implemented tables) — it changes what "already covered"
means for any future Budget/Time-and-Money work.

**A real BU-vs-upstream schema divergence**: BU 7.3's `Award` has
`nsfCode`/`NSF_CODE` (VARCHAR). The upstream tree's newer `Award` mapping
has `nsfSequenceNumber`/`NSF_SEQUENCE_NUMBER` (INTEGER) instead — different
name *and* different type. Flagged, not resolved — must be checked
against BU's real Oracle `AWARD` table before this field is ever added to
the archive. Other upstream-only fields not present in BU's mapping:
`fainId`, `fedAwardYear`, `fedAwardDate`; `cfdaNumber` is a direct scalar
field in BU's mapping but a full child collection (`awardCfdas` →
`AWARD_CFDA`) in the upstream tree.

**A genuine design gift**: `award_id`, `award_amount_info_id`,
`award_person_id` (Oracle column `AWARD_PERSON_ID`, Java field
`awardContactId`), `award_funding_proposal_id`, and — per the wider
study — `award_unit_contact_id`, `award_sponsor_contact_id`,
`award_report_terms_id`, `award_payment_schedule_id`,
`award_notepad_id`, and `award_custom_data_id` (separate sequence,
`SEQ_AWARD_CUSTOM_DATA_ID`, does **not** share the others' sequence) are
mostly drawn from the same Oracle sequence, `SEQUENCE_AWARD_ID` — their
surrogate PKs are globally unique across those tables, a real, verified
safety property for UPSERT conflict-key design.

**`AWARD_PERSONS` has no DB-level uniqueness constraint** — only
`PRIMARY KEY (AWARD_PERSON_ID)` plus two FKs. The only "one PI" rule is an
application-level audit warning (`AwardProjectPersonsAuditRule`), not
enforced at save time or DB level. No effort-percentage ceiling exists
anywhere in the Java source. Confirms duplicate person rows per `award_id`
are legitimate; the UPSERT conflict key must be the surrogate
`award_person_id` alone.

### Award object graph

```
Award (AWARD, PK award_id, business key award_number+sequence_number)
├── AwardExtension (AWARD_EXTENSION, 1:1, PK = award_id)                    [not archived]
├── AwardAmountInfo (AWARD_AMOUNT_INFO)                                     [archived, Phase 4A UPSERT]
├── AwardPerson (AWARD_PERSONS)                                             [archived, Phase 4A UPSERT]
├── AwardFundingProposal (AWARD_FUNDING_PROPOSALS) → InstitutionalProposal  [archived, Phase 4A UPSERT]
├── AwardUnitContact (AWARD_UNIT_CONTACTS)                                  [removed, V033]
├── AwardAttachment (AWARD_ATTACHMENT)                                     [archived, UPSERT]
├── AwardNotepad (AWARD_NOTEPAD)                                           [not archived]
├── AwardSponsorContact (AWARD_SPONSOR_CONTACTS)                           [not archived]
├── AwardReportTerm (AWARD_REPORT_TERMS) → AwardReportTermRecipient        [not archived]
├── AwardCloseout (AWARD_CLOSEOUT)                                         [not archived]
├── AwardPaymentSchedule (AWARD_PAYMENT_SCHEDULE) -.-> AwardReportTerm     [not archived]
├── AwardApprovedSubaward (AWARD_APPROVED_SUBAWARDS)                       [not archived, no real link to SUBAWARD]
├── AwardCustomData (AWARD_CUSTOM_DATA)                                    [not archived]
├── AwardBudgetExt/Budget → Periods → LineItems → Personnel → CalcAmounts  [not archived, deep hierarchy]
│   └── AwardBudgetLimit
└── TimeAndMoneyDocument → PendingTransactions, AwardAmountTransaction     [not archived, shares AwardAmountInfo]
    └── TransactionDetails

Negotiation ⇢ Award   (business-key string only, no FK — see below)
Subaward → SubAwardFundingSource → Award   (real FK, already archived as archive.subaward_funding)
```

### Oracle table inventory (27 Award-owned tables + 3 cross-domain links)

See the companion decomposition document
(`AWARD_DOMAIN_DECOMPOSITION.md`) for the full table-by-table PK/parent-key
breakdown grouped into subsystems — not repeated here to avoid drift
between the two documents.

### Cross-domain relationships

- **Award ↔ Proposal**: `AWARD_FUNDING_PROPOSALS.PROPOSAL_ID` → `PROPOSAL`
  (the `InstitutionalProposal` table — Kuali names the class
  `InstitutionalProposal` but the underlying Oracle table is literally
  `PROPOSAL`). InstitutionalProposal has the **same**
  `proposalNumber + sequenceNumber` versioning pattern as Award —
  `proposalId` is only the OJB surrogate PK. `PROPOSAL.CURRENT_AWARD_NUMBER`
  is a second, denormalized Proposal→Award pointer independent of
  `AWARD_FUNDING_PROPOSALS` — not confirmed whether
  `archive.proposal_version` currently captures this column.
- **Award ↔ Negotiation**: **no direct Oracle FK column exists.**
  `NEGOTIATION.ASSOCIATED_DOCUMENT_ID` (VARCHAR) + `NEGOTIATION_ASSC_TYPE_ID`
  resolve to one of 5 polymorphic target types (`NO`/`PL`/`IP`/`AWD`/`SWD`),
  resolved only in Java (`NegotiationServiceImpl.getAssociatedObject`),
  never an Oracle FK. When association type is `AWD`,
  `ASSOCIATED_DOCUMENT_ID` holds the Award's **business key**
  (`award_number`), not the surrogate `AWARD_ID`.
- **Award ↔ Subaward**: **not** first-class through `SUBAWARD` itself
  (no `AWARD_ID`/`AWARD_NUMBER` column on `SUBAWARD`, ever), nor through
  `AwardApprovedSubaward` (a separate, unlinked informational summary
  table — no `SUBAWARD_ID` column, no reference to the `SubAward` class
  anywhere). The real linkage is the bridge table
  `SUBAWARD_FUNDING_SOURCE.AWARD_ID`/`.SUBAWARD_ID` — **already archived**
  as `archive.subaward_funding`.

## Open questions

- Is `PROPOSAL.CURRENT_AWARD_NUMBER` currently captured by
  `archive.proposal_version`? Not verified in this study.
- Should `AwardExtension` (a real, fully-mapped 1:1 Kuali child — ARRA
  code, clinical trial flags, SPUDS record number) be archived? No
  archive table exists for it today; not decided either way.
- The upstream-vs-BU-7.3 NSF field divergence (and the `cfdaNumber`
  scalar-vs-child-table restructuring) needs checking against BU's real
  Oracle `AWARD` table before any of those fields are added anywhere.

## Decisions

- Treat Budget and Time-and-Money as their own future initiatives, not
  Phase 4 line items — they are real, disjoint, and each individually
  larger than the entire current Award-core scope.
- Do not model Negotiation↔Award as a foreign key if it's ever
  represented in the archive — Kuali itself doesn't; mirror the
  business-key-plus-association-type resolution instead.
- Re-verify the upstream-vs-BU-7.3 diff against BU's real Oracle `AWARD`
  table before any of the flagged fields are added to
  `archive.award_version`.

## Recommended implementation order

See `AWARD_DOMAIN_DECOMPOSITION.md` for the full subsystem-by-subsystem
milestone order — this study feeds that document directly and does not
duplicate its ordering here.

## Date last updated

2026-07-31.
