# Kuali Award Archive Coverage — Master Checklist

## Purpose

The authoritative checklist for declaring the Award domain complete —
now built from Kuali's own **DataDictionary** definitions
(`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/Award*.xml`),
not from counting Oracle tables. Every `Award*.xml` file is a business
object *Kuali itself* considers part of the Award module's functional
surface — that is a stronger, more honest definition of "what makes up
Award" than an Oracle-schema table count ever was. A raw table count
can't distinguish a real business feature (`AwardCfda.xml` — CFDA codes
are part of the Award model) from a lookup table, a UI helper bean, or
a workflow-internal sync log — the DataDictionary listing makes that
distinction explicit, file by file.

**This supersedes counting/percentage claims** like "we're 80% done
with Award." The correct claim is: every `Award*.xml` entry that is a
real persisted business entity is marked COMPLETE, PARTIALLY ARCHIVED,
or NOT YET ARCHIVED below; the domain is functionally complete once
none say NOT YET ARCHIVED (Tier 2 aside — see Decisions).

## Scope

Every one of the 68 `Award*.xml` files under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`. For
each: the Java business object class, the underlying Oracle table (if
any), whether it is a **persisted business entity**, a **lookup/
reference**, **UI-only**, or **transient**, the archive mapping, and a
status of **COMPLETE**, **PARTIALLY ARCHIVED**, **NOT YET ARCHIVED**, or
**NOT APPLICABLE**.

## Source material used

Direct enumeration of every `Award*.xml` file in
`/Users/mukadder/kuali-project/kuali-research/coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`,
cross-referenced against each file's `businessObjectClass` declaration,
then against the real OJB mappings that back each class
(`coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`,
`.../coeus/common/budget/impl/repository-budget.xml`,
`.../kra/timeandmoney/repository-timeandmoney.xml`,
`.../kra/personmasschange/repository-personmasschange.xml`) to confirm
each one's real Oracle table (or absence of one, for transient/UI-only
classes) — the same double-verification discipline (Java mapping *and*
real DDL/repository file, never one alone) established across every
Tier 1 design doc. Cross-checked against
`AWARD_DOMAIN_DECOMPOSITION.md`, `AWARD_CUSTOM_DATA_DESIGN.md`,
`AWARD_PEOPLE_EXPANSION_DESIGN.md`, `AWARD_TERMS_DESIGN.md`,
`AWARD_CONTACTS_DESIGN.md`, `AWARD_NOTEPAD_DESIGN.md`, and every
`database/migrations/V0*.sql` that creates an `archive.award_*` table,
to confirm archived status against what is actually shipped.

## Award Feature Coverage Matrix

### Core Award, financial, and funding

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `Award.xml` | `Award` | `AWARD` | Persisted entity | `archive.award_version` | **COMPLETE** | `basisOfPaymentCode`/`methodOfPaymentCode` scalar fields (plus their `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` denormalized descriptions) captured via `V047__add_award_basis_and_method_of_payment.sql` — see `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md` |
| `AwardExtension.xml` | `AwardExtension` | `AWARD_EXTENSION` | Persisted entity (1:1 with Award) | `archive.award_extension` | **COMPLETE** | Confirmed real BU-specific 1:1 extension table (`bu-db/BUKR-0002`); no Oracle-level PK/FK found in the available checkout despite confirmed schema evolution — see `AWARD_EXTENSION_CGB_DESIGN.md` |
| `AwardAmountInfo.xml` | `AwardAmountInfo` | `AWARD_AMOUNT_INFO` | Persisted entity | `archive.award_amount_info` | **COMPLETE** | Phase 4A |
| `AwardFundingProposal.xml` | `AwardFundingProposal` | `AWARD_FUNDING_PROPOSALS` | Persisted entity | `archive.award_funding_proposal` | **COMPLETE** | Phase 4A |
| `AwardFundingProposalBean.xml` | `AwardFundingProposalBean` | — (no OJB mapping) | UI-only | — | **NOT APPLICABLE** | Form-helper wrapper around `AwardFundingProposal`, not itself persisted |
| `AwardStatus.xml` | `AwardStatus` | `AWARD_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Already denormalized as `archive.award_version.status_description` |
| `AwardType.xml` | `AwardType` | `AWARD_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `AwardTransactionType.xml` | `AwardTransactionType` | `AWARD_TRANSACTION_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Already denormalized as `archive.award_version.transaction_type` |
| `AwardTransferringSponsor.xml` | `AwardTransferringSponsor` | `AWARD_TRANSFERRING_SPONSOR` | Persisted entity | `archive.award_transferring_sponsor` | **COMPLETE** | Final Award gap bundle, classified ARCHIVE_REQUIRED by `AWARD_COMPLETENESS_REPORT.md`. Structurally identical to `archive.award_sponsor_term` (one lookup-code FK, one per-version row). `sponsor_name` denormalized via `LEFT JOIN SPONSOR`, matching `archive.award_version`'s own convention. |
| `AwardDocument.xml` | `AwardDocument` (workflow doc) | `AWARD_DOCUMENT` | Workflow envelope | — | **NOT APPLICABLE** | KEW routing/document-header metadata, not business content |

### People and contacts

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardPerson.xml` | `AwardPerson` | `AWARD_PERSONS` | Persisted entity | `archive.award_person` | **COMPLETE** | Phase 4A |
| `AwardPersonUnit.xml` | `AwardPersonUnit` | `AWARD_PERSON_UNITS` | Persisted entity | `archive.award_person_unit` | **COMPLETE** | `AWARD_PEOPLE_EXPANSION_DESIGN.md` |
| `AwardPersonCreditSplit.xml` | `AwardPersonCreditSplit` | `AWARD_PERSON_CREDIT_SPLITS` | Persisted entity | `archive.award_person_credit_split` | **COMPLETE** | `AWARD_PEOPLE_EXPANSION_DESIGN.md` |
| `AwardPersonUnitCreditSplit.xml` | `AwardPersonUnitCreditSplit` | `AWARD_PERS_UNIT_CRED_SPLITS` | Persisted entity | `archive.award_person_unit_credit_split` | **COMPLETE** | `AWARD_PEOPLE_EXPANSION_DESIGN.md` |
| `AwardPersonMassChange.xml` | `AwardPersonMassChange` | `PMC_AWARD` | Persisted entity, different feature | — | **NOT APPLICABLE** | Administrative bulk-personnel-change utility/audit table, not Award business content |
| `AwardContact.xml` | `AwardContact` (abstract) | none (abstract base) | Abstract base class | — | **NOT APPLICABLE** | Shared base for the three concrete contact classes below; not itself persisted |
| `AwardSponsorContact.xml` | `AwardSponsorContact` | `AWARD_SPONSOR_CONTACTS` | Persisted entity | `archive.award_sponsor_contact` | **COMPLETE** | `AWARD_CONTACTS_DESIGN.md` |
| `AwardUnitContact.xml` | `AwardUnitContact` | `AWARD_UNIT_CONTACTS` | Persisted entity | `archive.award_unit_contact` | **COMPLETE** | Dropped V033 (unverified), reintroduced with a corrected, double-verified schema — `AWARD_CONTACTS_DESIGN.md` |
| `AwardCentralAdminContact.xml` | `AwardCentralAdminContact` | none — same table as `AwardUnitContact`, never persisted under this identity | Transient | — | **NOT APPLICABLE** | Zero-field subclass; UI bean builds transient, never-persisted objects from `UNIT_ADMINISTRATOR` data — `AWARD_CONTACTS_DESIGN.md` |

### Terms, payment, and reporting

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardSponsorTerm.xml` | `AwardSponsorTerm` | `AWARD_SPONSOR_TERM` | Persisted entity | `archive.award_sponsor_term` | **COMPLETE** | `AWARD_TERMS_DESIGN.md` |
| `AwardReportTerm.xml` | `AwardReportTerm` | `AWARD_REPORT_TERMS` | Persisted entity | `archive.award_report_term` | **COMPLETE** | `AWARD_TERMS_DESIGN.md` |
| `AwardReportTermRecipient.xml` | `AwardReportTermRecipient` | `AWARD_REP_TERMS_RECNT` | Persisted entity | `archive.award_report_term_recipient` | **COMPLETE** | `AWARD_TERMS_DESIGN.md` |
| `AwardBasisOfPayment.xml` | `AwardBasisOfPayment` | `AWARD_BASIS_OF_PAYMENT` | Lookup/reference | — | **NOT APPLICABLE** | The real data point is `Award.basisOfPaymentCode` (see Award.xml row) |
| `AwardMethodOfPayment.xml` | `AwardMethodOfPayment` | `AWARD_METHOD_OF_PAYMENT` | Lookup/reference | — | **NOT APPLICABLE** | The real data point is `Award.methodOfPaymentCode` (see Award.xml row) |
| `AwardCloseout.xml` | `AwardCloseout` | `AWARD_CLOSEOUT` | Persisted entity | `archive.award_closeout` | **COMPLETE** | `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md` |
| `AwardPaymentSchedule.xml` | `AwardPaymentSchedule` | `AWARD_PAYMENT_SCHEDULE` | Persisted entity | `archive.award_payment_schedule` | **COMPLETE** | `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`; carries an optional, deliberately-unenforced reference to `archive.award_report_term` |

### Attachments, notes, and custom data

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardAttachment.xml` | `AwardAttachment` | `AWARD_ATTACHMENT` | Persisted entity | `archive.award_attachment` | **COMPLETE** | Own batch-framework track, predates the Tier 1 design-doc series |
| `AwardAttachmentType.xml` | `AwardAttachmentType` | `AWARD_ATTACHMENT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `AwardNotepad.xml` | `AwardNotepad` | `AWARD_NOTEPAD` | Persisted entity | `archive.award_notepad` | **COMPLETE** | 34 real rows confirmed in BU Oracle — `AWARD_NOTEPAD_DESIGN.md` |
| `AwardComment.xml` | `AwardComment` | `AWARD_COMMENT` | Persisted entity | `archive.award_comment` | **COMPLETE** | `AWARD_COMMENT_DESIGN.md`; **confirmed distinct from `AwardNotepad`** — a separate "Comments" feature, per-version-scoped (real backfilled sequence_number), not the same table, class, or scoping |
| `AwardCustomData.xml` | `AwardCustomData` | `AWARD_CUSTOM_DATA` | Persisted entity | `archive.award_custom_data` | **COMPLETE** | `AWARD_CUSTOM_DATA_DESIGN.md` |
| `AwardPrintNotice.xml` | `AwardPrintNotice` | none (no OJB mapping) | Transient | — | **NOT APPLICABLE** | Print/report-generation parameter holder |
| `AwardTransactionSelectorBean.xml` | `AwardTransactionSelectorBean` | none (no OJB mapping) | Transient | — | **NOT APPLICABLE** | UI print-selection helper |

### Special approvals and compliance (newly surfaced as a functional group by this pass)

Every one of these has its own `Award*.xml` DataDictionary entry —
Kuali's own UI treats them as part of the Award module, even though the
previous Oracle-table-centric pass had bucketed most of them as
"a different, unrelated feature area." That framing undersold them:
they are real, persisted, per-Award business records with no home
anywhere else in this project's scope. All ten are now archived — nine
per `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`, `AwardCgb` per
`AWARD_EXTENSION_CGB_DESIGN.md`.

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardCfda.xml` | `AwardCfda` | `AWARD_CFDA` | Persisted entity | `archive.award_cfda` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; confirmed a real child table (not enrichment) via its own creating migration |
| `AwardCostShare.xml` | `AwardCostShare` | `AWARD_COST_SHARE` | Persisted entity | `archive.award_cost_share` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md` |
| `AwardFandaRate.xml` | `AwardFandaRate` | `AWARD_IDC_RATE` | Persisted entity | `archive.award_fanda_rate` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; PK/columns renamed from Oracle's literal "IDC" naming to the authoritative Java "F&A" naming |
| `AwardScienceKeyword.xml` | `AwardScienceKeyword` | `AWARD_SCIENCE_KEYWORD` | Persisted entity | `archive.award_science_keyword` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; a genuine many-to-many bridge to the shared `SCIENCE_KEYWORD` lookup (not copied in) |
| `AwardSpecialReview.xml` | `AwardSpecialReview` | `AWARD_SPECIAL_REVIEW` | Persisted entity | `archive.award_special_review` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; Award's own special-review tracking (e.g. IRB/IACUC flags on the award record) — **not** the IRB domain itself, no overlap with `archive.irb_*` |
| `AwardSpecialReviewExemption.xml` | `AwardSpecialReviewExemption` | `AWARD_EXEMPT_NUMBER` | Persisted entity | `archive.award_special_review_exemption` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; a true child of `AwardSpecialReview`, not `Award` directly — the one table in the whole Award domain with no `AWARD_ID` column at all |
| `AwardApprovedEquipment.xml` | `AwardApprovedEquipment` | `AWARD_APPROVED_EQUIPMENT` | Persisted entity | `archive.award_approved_equipment` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md` |
| `AwardApprovedForeignTravel.xml` | `AwardApprovedForeignTravel` | `AWARD_APPROVED_FOREIGN_TRAVEL` | Persisted entity | `archive.award_approved_foreign_travel` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; no Oracle-level FK to Award exists (Java/OJB-layer only) |
| `AwardCgb.xml` | `AwardCgb` | `AWARD_CGB` | Persisted entity (1:1 with Award, BU-specific) | `archive.award_cgb` | **COMPLETE** | **Re-investigated per an explicit request to reclassify as NOT APPLICABLE unless DDL proves real persisted data — it does**: `V600_047__KC_TBL_AWARD_CGB.sql` creates a real table with substantial billing/invoicing/letter-of-credit columns, PK=`AWARD_ID` (same 1:1 shape as `AwardExtension`). NOT reclassified, now archived; `bill_freq_cd` has no OJB mapping and remains unverified against real BU Oracle — see `AWARD_EXTENSION_CGB_DESIGN.md` |
| `AwardSubcontractingBudgetedGoals.xml` | `AwardSubcontractingBudgetedGoals` | `SUBCONTRACTING_BUD` | Persisted entity | `archive.award_subcontracting_budgeted_goals` | **COMPLETE** | `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`; the one table in the whole Award domain keyed by `award_number` itself, no surrogate PK, no `AWARD_ID`, no version tie |

### Subaward summary

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardApprovedSubawards.xml` | `AwardApprovedSubaward` | `AWARD_APPROVED_SUBAWARDS` | Persisted entity | `archive.award_approved_subaward` | **COMPLETE** | `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`; not linked to the real `SUBAWARD` table (already archived separately as `archive.subaward_funding`) |

### Multi-campus hierarchy and sync (workflow-internal)

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardHierarchy.xml` | `AwardHierarchy` | `AWARD_HIERARCHY` | Persisted entity, real business structure | `archive.award_hierarchy` | **COMPLETE** | **Reclassified** (was NOT APPLICABLE): this is not sync bookkeeping. It is the real, Oracle-PK-enforced parent/child Award relationship (`PARENT_AWARD_NUMBER`/`AWARD_NUMBER`, both award-number-keyed, not award-id-keyed) that `ActivePendingTransactionsServiceImpl` reads and walks directly on every Time and Money transaction approval to resolve parent-child/child-parent/indirect money routing. Version-agnostic (no `sequence_number` column — scoped to the whole award_number family, per its own Java class's documented contract). Soft-delete only, via `ACTIVE` (default `'Y'`, flipped to `'N'` only when an award's first (`sequence_number=1`) document is cancelled) — no physical `DELETE` found anywhere. Cycles are structurally impossible: every child hierarchy row is created with a freshly-generated `award_number` that cannot already exist as an ancestor. Archived as part of the Award Time and Money bundle — see `AWARD_TIME_AND_MONEY_DESIGN.md`. |
| `AwardHierarchyNode.xml` | `AwardHierarchyNode` | none (no OJB mapping found) | Transient | — | **NOT APPLICABLE** | In-memory tree-node wrapper for UI hierarchy display and for Time and Money's routing algorithm — confirmed to add no persisted fields beyond what `AwardHierarchy` itself already stores |
| `AwardSyncChange.xml` | `AwardSyncChange` | `AWARD_SYNC_CHANGE` | Persisted entity, workflow-internal | — | **NOT APPLICABLE** | Genuine multi-campus sync bookkeeping, unrelated to Time and Money — not reclassified |
| `AwardSyncLog.xml` | `AwardSyncLog` | `AWARD_SYNC_LOG` | Persisted entity, workflow-internal | — | **NOT APPLICABLE** | Genuine multi-campus sync bookkeeping, unrelated to Time and Money — not reclassified |
| `AwardSyncStatus.xml` | `AwardSyncStatus` | `AWARD_SYNC_STATUS` | Persisted entity, workflow-internal | — | **NOT APPLICABLE** | Genuine multi-campus sync bookkeeping, unrelated to Time and Money — not reclassified |

### Templates (reusable defaults, not real Award instances)

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardTemplate.xml` | `AwardTemplate` | `AWARD_TEMPLATE` | Template/reference metadata | — | **NOT APPLICABLE** | Keyed by `AWARD_TEMPLATE_CODE`, never a real award's `AWARD_ID`/`AWARD_NUMBER` |
| `AwardTemplateComment.xml` | `AwardTemplateComment` | `AWARD_TEMPLATE_COMMENTS` | Template/reference metadata | — | **NOT APPLICABLE** | |
| `AwardTemplateContact.xml` | `AwardTemplateContact` | `AWARD_TEMPLATE_CONTACT` | Template/reference metadata | — | **NOT APPLICABLE** | |
| `AwardTemplateTerm.xml` | `AwardTemplateTerm` | `AWARD_TEMPLATE_TERMS` | Template/reference metadata | — | **NOT APPLICABLE** | `AWARD_TERMS_DESIGN.md` |
| `AwardTemplateReportTerm.xml` | `AwardTemplateReportTerm` | `AWARD_TEMPLATE_REPORT_TERMS` | Template/reference metadata | — | **NOT APPLICABLE** | `AWARD_TERMS_DESIGN.md` |
| `AwardTemplateReportTermRecipient.xml` | `AwardTemplateReportTermRecipient` | `AWARD_TEMPL_REP_TERMS_RECNT` | Template/reference metadata | — | **NOT APPLICABLE** | `AWARD_TERMS_DESIGN.md` |

### Award Budget (no longer deferred — implemented as its own bundle)

Previously tracked here as deferred Tier 2. The DataDictionary pass
refined the Budget table list: these are Award-specific `_EXT`
extension tables (confirmed via `repository-budget.xml`), more precise
than the generic `BUDGET_PERIODS`/`BUDGET_DETAILS`-style names
`AWARD_DOMAIN_DECOMPOSITION.md` originally used for this tier. Budget
was pulled forward and implemented as its own bundle
(`AWARD_BUDGET_DESIGN.md`), alongside Time and Money above — Tier 2 is
now fully closed out.

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardBudgetDocument.xml` | (workflow doc) | — | Workflow envelope | — | **NOT APPLICABLE** | Budget maintenance-document routing metadata |
| `AwardBudgetExt.xml` | `AwardBudgetExt` | `AWARD_BUDGET_EXT` | Persisted entity | `archive.award_budget` | **COMPLETE** | Merges `AWARD_BUDGET_EXT` with the generic, Proposal-shared `BUDGET` table (real Oracle FK, `V300_258`). See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetType.xml` | `AwardBudgetType` | `AWARD_BUDGET_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized into `archive.award_budget.award_budget_type_description` via LEFT JOIN. |
| `AwardBudgetStatus.xml` | `AwardBudgetStatus` | `AWARD_BUDGET_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized into `archive.award_budget.award_budget_status_description` via LEFT JOIN. |
| `AwardBudgetPeriodExt.xml` | `AwardBudgetPeriodExt` | `AWARD_BUDGET_PERIOD_EXT` | Persisted entity | `archive.award_budget_period` | **COMPLETE** | Merges with generic `BUDGET_PERIODS`. `BUDGET_PERIOD_NUMBER` aliased to `budget_period_id`. See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetLineItemExt.xml` | `AwardBudgetLineItemExt` | `AWARD_BUDGET_DETAILS_EXT` | Persisted entity | `archive.award_budget_line_item` | **COMPLETE** | Merges with generic `BUDGET_DETAILS`. See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetLineItemCalculatedAmountExt.xml` | `AwardBudgetLineItemCalculatedAmountExt` | `AWD_BGT_DET_CAL_AMTS_EXT` | Persisted entity | `archive.award_budget_line_item_calculated_amount` | **COMPLETE** | Merges with generic `BUDGET_DETAILS_CAL_AMTS`. See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetPersonnelDetailsExt.xml` | `AwardBudgetPersonnelDetailsExt` | `AWD_BUDGET_PER_DET_EXT` | Persisted entity | `archive.award_budget_personnel_detail` | **COMPLETE** | Merges with generic `BUDGET_PERSONNEL_DETAILS`. `person_sequence_number` is a bare reference to `BUDGET_PERSONS` — now archived as `archive.award_budget_person`, see the row below and `AWARD_COMPLETENESS_REPORT.md`. |
| `AwardBudgetPersonnelCalculatedLineitemExt.xml` | `AwardBudgetPersonnelCalculatedAmountExt` | `AWD_BUDGET_PER_CAL_AMTS_EXT` | Persisted entity | `archive.award_budget_personnel_calculated_amount` | **COMPLETE** | Merges with generic `BUDGET_PERSONNEL_CAL_AMTS`. See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetPeriodSummaryCalculatedAmount.xml` | `AwardBudgetPeriodSummaryCalculatedAmount` | `AWD_BGT_PER_SUM_CALC_AMT` | Persisted entity | `archive.award_budget_period_summary_calculated_amount` | **COMPLETE** | Standalone (no generic counterpart). Serves two logical roles via `rate_class_type` ('E'/'O'), kept as one table matching Kuali's own design. See `AWARD_BUDGET_DESIGN.md`. |
| `AwardBudgetLimit.xml` | `AwardBudgetLimit` | `AWARD_BUDGET_LIMIT` | Persisted entity | `archive.award_budget_limit` | **COMPLETE** | Standalone, Award-specific (not shared with Proposal). Both `award_id`/`budget_id` are real Oracle-enforced FKs (`V310_3_066`). See `AWARD_BUDGET_DESIGN.md`. |
| *(no `Award*.xml` DD entry — see note)* | `BudgetPerson` | `BUDGET_PERSONS` | Persisted entity | `archive.award_budget_person` | **COMPLETE** | Final Award gap bundle, classified ARCHIVE_REQUIRED by `AWARD_COMPLETENESS_REPORT.md`. Shared with Proposal Development like the rest of Budget, but has **no** Award-specific `_EXT` table — scoped to Award by joining `BUDGET_PERSONS` → `BUDGET` → `AWARD_BUDGET_EXT`. Keyed by Oracle's own real composite PK (`budget_id`, `person_sequence_number`), no surrogate id. DD file is `BudgetPerson.xml` (lives under the Budget package's own DD, not `Award*.xml` — same situation as the four Time and Money tables below), so it is not one of the 68 files counted in Totals. |

### Award Time and Money (no longer deferred — implemented as its own bundle)

Previously tracked here as deferred Tier 2. Time and Money was pulled
forward and implemented as its own bundle (`AWARD_TIME_AND_MONEY_DESIGN.md`),
alongside the `AwardHierarchy` reclassification above — Tier 2 now
refers only to Award Budget, still fully deferred.

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardAmountTransaction.xml` | `AwardAmountTransaction` | `AWARD_AMOUNT_TRANSACTION` | Persisted entity | `archive.award_amount_transaction` | **COMPLETE** | One row per (Time and Money document, affected Award) pair. Oracle's own `TRANSACTION_ID` column here is a `VARCHAR2(10)` that actually stores the Time and Money document number — renamed to `document_number` at the archive boundary, never exposed under the same field name as the numeric `transaction_id` used elsewhere in this bundle. No dedicated DD entry found for `TimeAndMoneyDocument`/`PendingTransaction`/`TransactionDetail`/`PendingTransactionExtension` under `Award*.xml` (they live under the `timeandmoney` package's own DD, out of this file's `Award*.xml` enumeration) — archived anyway as `archive.time_and_money_document`/`archive.pending_transaction`/`archive.transaction_detail`/`archive.pending_transaction_extension`. See `AWARD_TIME_AND_MONEY_DESIGN.md`. |
| `AwardDirectFandADistribution.xml` | `AwardDirectFandADistribution` | `AWARD_AMT_FNA_DISTRIBUTION` | Persisted entity | `archive.award_direct_fanda_distribution` | **COMPLETE** | F&A distribution calculation, a real child of both `Award` and the already-archived `AwardAmountInfo` (explicit OJB FK to `AWARD_AMOUNT_INFO_ID`). `AWARD_AMT_FNA_DISTRIBUTION_ID` renamed to `award_direct_fanda_distribution_id`, the authoritative Java field name. See `AWARD_TIME_AND_MONEY_DESIGN.md`. |

### SAP Award and Budget Transmission (separate integration-history subsystem — not part of this checklist's completeness count)

Researched in a dedicated pass — see
`AWARD_COMPLETENESS_REPORT.md`'s Verdict and the full
`SAP_AWARD_TRANSMISSION_ASSESSMENT.md` — and subsequently implemented;
see `SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md` for the implementation
record. `AwardTransmission`/`AwardTransmissionChild` (Oracle
`AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD`, both real, OJB-mapped,
`KcPersistableBusinessObjectBase` tables — no bootstrap DDL found
anywhere in the available checkout, the same situation as
`AwardExtension`'s own unconfirmed Oracle-level constraints) have **no
`Award*.xml` DataDictionary entry at all** — they are still not counted
in this file's 68-file/42-COMPLETE inventory, the same treatment already
given to `BudgetPerson`/`TimeAndMoneyDocument`/etc. before them. **They
are now archived** as `archive.award_transmission`/
`archive.award_transmission_child`, wired into both
`--load-award-id`/`--load-batch` — but SAP transmission history remains
classified as a separate, standalone integration-history subsystem, not
folded into the core Award domain's own completeness count. Partially
reconstructable is why archiving was needed at all: every
Award/Budget/Cost Share/People/Terms/Contacts/Extension field the SAP
integration reads as an *input* is already archived; the actual
transmitted payload, response, status, timestamp, transmitting user, and
(for hierarchy children) the F&A rate basis genuinely used were **not**
reconstructable from that already-archived data — see the assessment's
Findings for why — and are now preserved directly via these two tables
instead. Does not block, and is not counted toward, the core Award
domain's own completeness declaration.

**Still open**: `BUDGET_RATE_AND_BASE`, a real Budget table feeding this
same F&A rate calculation, remains unevaluated and unarchived — see
`AWARD_COMPLETENESS_REPORT.md`'s Open Questions for why this may turn out
to be `ARCHIVE_REQUIRED` rather than merely reconstructable.

## Totals

- **COMPLETE**: 42 — `Award` (now fully, not just PARTIALLY, archived —
  see below), `AwardAmountInfo`, `AwardFundingProposal`,
  `AwardPerson`, `AwardPersonUnit`, `AwardPersonCreditSplit`,
  `AwardPersonUnitCreditSplit`, `AwardSponsorContact`,
  `AwardUnitContact`, `AwardSponsorTerm`, `AwardReportTerm`,
  `AwardReportTermRecipient`, `AwardAttachment`, `AwardNotepad`,
  `AwardComment`, `AwardCustomData`, `AwardCloseout`,
  `AwardPaymentSchedule`, `AwardApprovedSubaward`, `AwardCfda`,
  `AwardCostShare`, `AwardFandaRate`, `AwardScienceKeyword`,
  `AwardSpecialReview`, `AwardSpecialReviewExemption`,
  `AwardApprovedEquipment`, `AwardApprovedForeignTravel`,
  `AwardSubcontractingBudgetedGoals`, `AwardCgb`, `AwardExtension`,
  `AwardHierarchy`, `AwardAmountTransaction`,
  `AwardDirectFandADistribution`, `AwardBudgetExt`,
  `AwardBudgetPeriodExt`, `AwardBudgetLineItemExt`,
  `AwardBudgetLineItemCalculatedAmountExt`,
  `AwardBudgetPersonnelDetailsExt`,
  `AwardBudgetPersonnelCalculatedAmountExt`,
  `AwardBudgetPeriodSummaryCalculatedAmount`, `AwardBudgetLimit`,
  `AwardTransferringSponsor` (42). `BudgetPerson` is also now archived
  (`archive.award_budget_person`) but is not one of the 68
  `Award*.xml` files, so it is not part of this count — see the note
  under Award Budget above.
- **PARTIALLY ARCHIVED**: 0 (`Award`'s last gap —
  `basisOfPaymentCode`/`methodOfPaymentCode` — closed by
  `V047__add_award_basis_and_method_of_payment.sql`; see
  `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`).
- **NOT YET ARCHIVED**: 0. `AwardTransferringSponsor` (the last entry
  in this category) and `BudgetPerson`/`BUDGET_PERSONS` (the last
  named item outside the 68-file DD enumeration) were both classified
  ARCHIVE_REQUIRED and implemented as the final Award gap bundle — see
  `AWARD_COMPLETENESS_REPORT.md`. This category is now empty.
- **NOT APPLICABLE**: 26 (lookups, templates, workflow-internal sync,
  transient UI beans, workflow envelopes, and the abstract
  `AwardContact` base class — `AwardHierarchy` moved out of this count,
  reclassified and archived). Corrected from a prior miscount of 25
  during the `AWARD_COMPLETENESS_REPORT.md` reconciliation pass — a
  programmatic recount of every row's Status cell confirms 41 + 26 + 1
  = 68, matching the file count exactly.

68 `Award*.xml` files total.

## Open questions

- `AwardExtension`'s Oracle-level PK/FK: no `ALTER TABLE ... ADD
  CONSTRAINT` was found for `AWARD_EXTENSION` in the available BU
  checkout despite confirmed real schema evolution (an added-then-
  dropped FAIN column) proving its history extends beyond the one
  creation script found — see `AWARD_EXTENSION_CGB_DESIGN.md`.
- `AwardCgb.BILL_FREQ_CD`: a real column (added by a later Kuali
  migration, `V601_007`) with no corresponding OJB field-descriptor —
  the same risk shape as `AwardCostShare.FISCAL_YEAR`, which real BU
  Oracle already proved does not exist despite appearing in the
  generic Kuali source tree's DDL. Included as the best available
  evidence, but flagged as unverified against real BU Oracle — see
  `AWARD_EXTENSION_CGB_DESIGN.md`. **Still unverified**: this session's
  environment has no BU Oracle/VPN access (verification requires a
  BU-VPN-connected machine per this repo's own README), so this
  column's real-Oracle status could not be checked before this session
  ended, the same limitation that already applied to the Award Comment
  smoke test. Whoever next has Oracle access should run
  `SELECT BILL_FREQ_CD FROM AWARD_CGB WHERE ROWNUM <= 1;` (or an
  equivalent `information_schema`/`DESC AWARD_CGB` check) before
  trusting this column the way the Cost Share `FISCAL_YEAR` column
  once was.
- ~~`basis_of_payment_code`/`method_of_payment_code` on `Award`
  itself~~: resolved - implemented via
  `V047__add_award_basis_and_method_of_payment.sql`; see
  `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
- ~~`AwardCostShare.FISCAL_YEAR`~~: resolved - real BU Oracle has no
  such column despite the generic Kuali source tree's bootstrap DDL
  showing one; the pipeline was corrected to stop
  selecting/requiring/writing it (`archive.award_cost_share.fiscal_year`
  itself is left in place, harmless and always null - see
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`). Whether
  `AwardFandaRate`/`AWARD_IDC_RATE`'s own, separately-mapped
  `FISCAL_YEAR` matches real BU Oracle has NOT been verified and
  remains open.
  `AwardSpecialReview.PROTOCOL_NUMBER` (a soft, non-enforced
  cross-reference to Kuali's Protocol/IRB world) is recorded as an
  open question in `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`, not
  resolved here.
- `AwardComment` vs. `AwardNotepad`: confirmed distinct classes, distinct
  tables (`AWARD_COMMENT` vs. `AWARD_NOTEPAD`), and distinct scoping
  (per-version vs. whole-family - see `AWARD_COMMENT_DESIGN.md`) — the
  functional difference in *why* Kuali has both a "Comments" and a
  "Notes" concept (rather than just the schema-level difference) has
  not been investigated further.
- Award Time and Money: whether BU's real Oracle retains
  `PENDING_TRANSACTIONS` rows after `PROCESSED_FLAG='Y'` (determines
  whether `archive.pending_transaction` is durable history or
  redundant with `archive.transaction_detail`) is unverified — same
  BU-VPN/Oracle-access limitation as `AwardCgb.BILL_FREQ_CD` above. See
  `AWARD_TIME_AND_MONEY_DESIGN.md`'s Open Questions and smoke-test plan.
- ~~Award Budget: `BUDGET_PERSONS` was found during research but was
  not named in that bundle's explicit scope~~: resolved - reassessed
  by `AWARD_COMPLETENESS_REPORT.md`, classified ARCHIVE_REQUIRED (real
  base-salary/appointment/salary-anniversary data distinct from both
  `AwardPerson` and `archive.award_budget_personnel_detail`, confirmed
  via Java/OJB/DDL), and archived as `archive.award_budget_person`.
  Two DDL-only columns with no OJB field-descriptor -
  `BUDGET_PERSONS.PROPOSAL_NUMBER` and `.VERSION_NUMBER` (distinct from
  `VER_NBR`) - were excluded for the same "no corroborating evidence"
  reason as `previousObligatedTotal`/`BUDGET.FINAL_VERSION_FLAG` below.
  `previousObligatedTotal` (OJB-only, no DDL evidence) and
  `BUDGET.FINAL_VERSION_FLAG` (DDL-only, no OJB evidence) remain
  excluded for the same "no corroborating evidence in either direction"
  reason as `AwardCostShare.FISCAL_YEAR`. See
  `AWARD_BUDGET_DESIGN.md`'s Open Questions and Traps and
  `AWARD_COMPLETENESS_REPORT.md`.

## Decisions

- The DataDictionary-driven matrix above is now the **primary**
  authoritative checklist for Award-domain completeness, per explicit
  direction: a functional feature list ("what does the Award module's
  own UI/maintenance surface consider part of Award") is a stronger
  definition of "done" than an Oracle table count, because it can't
  silently misclassify a real feature as "someone else's domain."
  `AWARD_DOMAIN_DECOMPOSITION.md`'s Tier 0/1/2 structure remains the
  detailed *why*/sequencing record and is not being replaced, only
  cross-checked and corrected where the two disagree.
- The "Special approvals and compliance" group was previously bucketed
  as "out of scope / different feature area" in this document's first
  (Oracle-table-only) revision. That was wrong: every one of those
  tables has its own `Award*.xml` DataDictionary entry, meaning Kuali's
  own UI treats them as part of the Award module. Corrected here to
  NOT YET ARCHIVED, then to COMPLETE (9 of the 10) once
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md` shipped -
  `AwardCgb` was the sole exception at that point, grouped instead with
  `AwardExtension`'s open "worth archiving at all" question. Both have
  since shipped as their own bundle (see below) and are now COMPLETE.
- `AwardCgb` was explicitly re-investigated per a request to
  reclassify it as NOT APPLICABLE unless real DDL proves it stores
  independent persisted business data. It does (`V600_047__KC_TBL_AWARD_CGB.sql`
  creates a real table with substantial billing/invoicing columns) -
  **not reclassified**. See `AWARD_COMMENT_DESIGN.md`'s dedicated
  section for the original evidence.
- `AwardExtension` and `AwardCgb` were confirmed as real, persisted,
  1:1-with-Award tables (settling the prior "worth archiving at all"
  open question) and implemented together as the next Tier 1 bundle:
  `archive.award_extension` and `archive.award_cgb`
  (`V046__create_award_extension_and_cgb.sql`), both keyed by
  `award_id` itself (no surrogate id — the correct shape for a true 1:1
  extension row). See `AWARD_EXTENSION_CGB_DESIGN.md`.
- `Award.basisOfPaymentCode`/`Award.methodOfPaymentCode` — the last
  gap keeping `Award` itself at PARTIALLY ARCHIVED — were captured via
  a corrective migration (`V047`) adding four columns to the existing
  `archive.award_version` row (the two codes plus their
  `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` denormalized
  descriptions, joined the same way `status_description`/
  `transaction_type` already were). `Award` is now COMPLETE. See
  `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
- Tier 2 (Budget, Time and Money) was originally deferred as a whole
  subsystem per `AWARD_DOMAIN_DECOMPOSITION.md`'s decision to prove the
  UPSERT+batch pattern on simpler shapes first — both have since been
  pulled forward and implemented as their own bundles (Time and Money,
  then Budget), closing out Tier 2 entirely.
- Lookup/reference tables, template metadata, workflow-internal sync
  tables, and transient/UI-only beans are NOT APPLICABLE regardless of
  having a DataDictionary entry — a DD entry confirms something is part
  of the Award *module surface*, not that it is a *business record*
  worth archiving. Both checks (DD entry exists, and the underlying
  object is a persisted, non-lookup, non-template business entity) are
  required for NOT YET ARCHIVED / COMPLETE status.
- Award Budget's six generic/`_EXT` pairs are each merged into one
  flattened archive table keyed by the shared PK, the same 1:1-extension
  reasoning already used for `archive.award_extension`/`archive.award_cgb`,
  applied six times in a nested chain — the first confirmed case in the
  whole Award domain of a real, Oracle-enforced FK between two tables
  archived in this project (`V300_258__schema-constraints.sql`). The
  INNER JOIN to each `_EXT` table is itself what excludes Proposal
  Development's own budget rows, since the generic tables carry no
  discriminator column. See `AWARD_BUDGET_DESIGN.md`.
- The final Award gap bundle (`BUDGET_PERSONS`/`AwardTransferringSponsor`)
  reversed the informal "flagged gap" framing `AWARD_BUDGET_DESIGN.md`
  had given `BUDGET_PERSONS` and confirmed `AwardTransferringSponsor`
  was a real, un-evaluated milestone rather than a soft deferral - both
  classified ARCHIVE_REQUIRED and implemented as one small bundle,
  reusing already-proven patterns rather than inventing new ones:
  `archive.award_budget_person` extends the join-through-`AWARD_BUDGET_EXT`
  scoping pattern to a table with no `_EXT` counterpart at all (same
  shape as `archive.award_budget_limit`/
  `archive.award_budget_period_summary_calculated_amount`), and
  `archive.award_transferring_sponsor` mirrors
  `archive.award_sponsor_term` almost exactly. See
  `AWARD_COMPLETENESS_REPORT.md`.

## Recommended implementation order

1. ~~Award Attachments (`AwardNotepad`)~~ — done.
2. ~~Award Reporting (`AwardCloseout`, `AwardPaymentSchedule`) and Award
   Subaward Summary (`AwardApprovedSubaward`)~~ — done, see
   `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`.
3. ~~Special approvals and compliance (`AwardCfda`, `AwardCostShare`,
   `AwardFandaRate`, `AwardScienceKeyword`, `AwardSpecialReview`/
   `AwardSpecialReviewExemption`, `AwardApprovedEquipment`,
   `AwardApprovedForeignTravel`, `AwardSubcontractingBudgetedGoals`)~~ —
   done, see `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`. `AwardCgb`
   remains grouped with `AwardExtension`'s open "1:1 BU-specific
   extension tables" question, not archived here.
4. ~~`AwardComment`~~ — done, see `AWARD_COMMENT_DESIGN.md`.
   `AwardCgb` was re-investigated in the same pass and confirmed real
   (not reclassified as NOT APPLICABLE).
5. ~~`AwardExtension` and `AwardCgb`~~ — done, see
   `AWARD_EXTENSION_CGB_DESIGN.md`.
6. ~~Basis of payment / method of payment field completion on `Award`
   itself~~ — done, see `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
7. ~~Award Time and Money (`AwardHierarchy` reclassified and archived,
   `TimeAndMoneyDocument`, `PendingTransaction`,
   `PendingTransactionExtension`, `TransactionDetail`,
   `AwardAmountTransaction`, `AwardDirectFandADistribution`, plus two
   new columns on the already-archived `AwardAmountInfo`)~~ — done, see
   `AWARD_TIME_AND_MONEY_DESIGN.md`.
8. ~~Award Budget (`AwardBudgetExt`, `AwardBudgetPeriodExt`,
   `AwardBudgetLineItemExt`, `AwardBudgetLineItemCalculatedAmountExt`,
   `AwardBudgetPersonnelDetailsExt`,
   `AwardBudgetPersonnelCalculatedAmountExt`,
   `AwardBudgetPeriodSummaryCalculatedAmount`, `AwardBudgetLimit`)~~ —
   done, see `AWARD_BUDGET_DESIGN.md`.
9. ~~Final Award gap bundle (`BUDGET_PERSONS`/`AwardTransferringSponsor`,
   both reclassified ARCHIVE_REQUIRED by `AWARD_COMPLETENESS_REPORT.md`)~~
   — done, archived as `archive.award_budget_person`/
   `archive.award_transferring_sponsor`.
10. Final Award field/table reconciliation and completion report.

Now that step 9 is done, only the final reconciliation report (step 10)
remains before the Award domain can be declared functionally complete
— see `AWARD_COMPLETENESS_REPORT.md` for that report and its verdict.

## Date last updated

2026-07-31 (rebuilt from the `Award*.xml` DataDictionary listing,
superseding the original Oracle-table-count-only revision; Award
Notepad marked complete; the "Special approvals and compliance" group
and `AwardComment` newly surfaced as real, un-designed Award business
data; Award Reporting/Subaward Summary — `AwardCloseout`,
`AwardPaymentSchedule`, `AwardApprovedSubaward` — marked complete;
Special approvals and compliance — `AwardCfda`, `AwardCostShare`,
`AwardFandaRate`, `AwardScienceKeyword`, `AwardSpecialReview`,
`AwardSpecialReviewExemption`, `AwardApprovedEquipment`,
`AwardApprovedForeignTravel`, `AwardSubcontractingBudgetedGoals` — also
marked complete, `AwardCgb` excluded; `AwardComment` marked complete,
`AwardCgb` re-investigated per an explicit request and confirmed real
via DDL - not reclassified as NOT APPLICABLE. Same-day follow-up:
`AwardExtension` and `AwardCgb` both marked COMPLETE after being
implemented together as their own Tier 1 bundle; `AwardCostShare`'s
Notes corrected to reflect the `FISCAL_YEAR` fix — see
`AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`'s own Decisions section
for that correction's full detail. Second same-day follow-up: `Award`
itself marked COMPLETE after `basisOfPaymentCode`/`methodOfPaymentCode`
were captured via `V047`, closing the domain's last PARTIALLY ARCHIVED
row — see `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`. `AwardCgb.BILL_FREQ_CD`
remains unverified against real BU Oracle — this session's environment
has no Oracle/VPN access to check it. Third same-day follow-up:
`AwardHierarchy` reclassified from NOT APPLICABLE to COMPLETE
(previously miscategorized as multi-campus sync bookkeeping — it is
the real parent/child Award relationship Time and Money's own
money-routing algorithm depends on) and archived together with the
rest of the Award Time and Money bundle —
`AwardAmountTransaction`/`AwardDirectFandADistribution` also marked
COMPLETE, and `AwardAmountInfo` gained two new columns
(`transaction_id`, `originating_award_version`) via a corrective
migration rather than a new table; see
`AWARD_TIME_AND_MONEY_DESIGN.md`. `PendingTransaction`'s real-Oracle
retention behavior after processing remains unverified, same
Oracle-access limitation as `AwardCgb.BILL_FREQ_CD`). Fourth same-day
follow-up: Award Budget implemented as its own bundle, closing out
Tier 2 entirely — `AwardBudgetExt`, `AwardBudgetPeriodExt`,
`AwardBudgetLineItemExt`, `AwardBudgetLineItemCalculatedAmountExt`,
`AwardBudgetPersonnelDetailsExt`,
`AwardBudgetPersonnelCalculatedAmountExt`,
`AwardBudgetPeriodSummaryCalculatedAmount`, and `AwardBudgetLimit` all
marked COMPLETE; see `AWARD_BUDGET_DESIGN.md`. `BUDGET_PERSONS`
(a real, confirmed Award-specific table not named in this bundle's
scope) remains a flagged, deliberately un-archived gap.

2026-08-01: Fifth follow-up, the final Award gap bundle -
`AWARD_COMPLETENESS_REPORT.md` reassessed `BUDGET_PERSONS` and
`AwardTransferringSponsor`, classified both ARCHIVE_REQUIRED, and both
were implemented and marked COMPLETE: `BUDGET_PERSONS` as
`archive.award_budget_person` (scoped to Award by joining
`BUDGET_PERSONS` → `BUDGET` → `AWARD_BUDGET_EXT`, keyed by Oracle's
real composite PK; two DDL-only columns with no OJB mapping,
`PROPOSAL_NUMBER`/`VERSION_NUMBER`, excluded) and
`AwardTransferringSponsor` as `archive.award_transferring_sponsor`
(structurally identical to `archive.award_sponsor_term`, `sponsor_name`
denormalized via `LEFT JOIN SPONSOR`). NOT YET ARCHIVED is now empty.
Totals recounted programmatically against every row's Status cell:
42 COMPLETE + 26 NOT APPLICABLE + 0 NOT YET ARCHIVED = 68, matching
the file count exactly.

2026-08-01: Sixth follow-up, SAP Award and Budget Transmission
assessment - `SAP_AWARD_TRANSMISSION_ASSESSMENT.md` researched
`edu.bu.kuali.kra.award.sapintegration.SapIntegrationServiceImpl` and
confirmed `AwardTransmission`/`AwardTransmissionChild`
(`AWARD_TRANSMISSION`/`AWARD_TRANSMISSION_CHILD`) as real, persisted,
OJB-mapped tables with no `Award*.xml` DD entry and no bootstrap DDL
found anywhere in the available checkout. Recorded as a separate,
standalone integration-history subsystem (new section above), not
part of this file's 68-file/42-COMPLETE Award-domain count and not
implemented - partially reconstructable at best, per the assessment's
verdict. Does not change any status in the matrix above.

2026-08-01: Seventh follow-up, SAP Award Transmission History archive
implemented - `archive.award_transmission`/`archive.award_transmission_child`
built in full (migration, extraction SQL, loader wiring for both
`--load-award-id`/`--load-batch`, tests) per
`SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md`, keyed by Oracle's own real
surrogate PKs so retransmissions are preserved as immutable history by
construction. `transmission_id` on the child table is a deliberate bare,
unenforced column (no Postgres FK), since a transmission's hierarchy
children routinely belong to a different `award_number` family than the
parent transmission's own root Award - the two are read and loaded
independently. Still recorded as a separate, standalone integration-
history subsystem (section above updated), not part of this file's
68-file/42-COMPLETE Award-domain count. `BUDGET_RATE_AND_BASE` remains
open and unevaluated - flagged for the next pass, do not drop it.
