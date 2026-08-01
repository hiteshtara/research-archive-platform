# Kuali Subaward Archive Coverage — Master Checklist

## Purpose

The authoritative checklist for declaring the Subaward domain complete,
built the same way as `KUALI_ARCHIVE_COVERAGE.md` (the Award domain's
equivalent document): from Kuali's own **DataDictionary** definitions
(`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/Subaward*.xml`
and `SubAward*.xml`), not from counting Oracle tables or from assuming this
project's existing `archive.subaward_*` schema is itself the ground truth
for "what Subaward is." Every `SubAward*.xml` file is a business object
Kuali itself considers part of the Subaward module's functional surface —
the same stronger, more honest definition of "what makes up Subaward" used
for Award.

**This supersedes any informal claim like "Subaward is basically done."**
The correct claim is: every `SubAward*.xml` entry that is a real persisted
business entity is marked COMPLETE, PARTIALLY ARCHIVED, or NOT YET ARCHIVED
below; the domain is functionally complete once none say NOT YET ARCHIVED.
Unlike Award at the point its own checklist was first built, Subaward
turns out to already be substantially archived — 10 of 27 in-scope entries
are COMPLETE and only 4 are NOT YET ARCHIVED, with no Tier-2-sized deferred
subsystem (no Subaward analogue of Award's Budget/Time-and-Money tables).

## Scope

Every one of the 27 `Subaward*.xml`/`SubAward*.xml` files under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/` (root level
only — the same non-recursive scope the Award checklist used, which
excludes the `docs/` subdirectory's `*MaintenanceDocument.xml` wrappers
around the lookups already listed here). For each: the Java business
object class, the underlying Oracle table (if any), whether it is a
**persisted business entity**, a **lookup/reference**, **template/reference
metadata**, **UI-only**, or **transient**, the archive mapping, and a
status of **COMPLETE**, **PARTIALLY ARCHIVED**, **NOT YET ARCHIVED**, or
**NOT APPLICABLE**.

One file matching the naming pattern, `AwardApprovedSubawards.xml`, is
**excluded** from the 27 — it is `Award`-prefixed, not `Subaward`/`SubAward`-
prefixed (`AwardApprovedSubaward` / `AWARD_APPROVED_SUBAWARDS`), and is
already fully covered in `KUALI_ARCHIVE_COVERAGE.md`'s own "Subaward
summary" section (`archive.award_approved_subaward`, **COMPLETE**, and
explicitly *not* linked to the real `SUBAWARD` table covered here).

## Source material used

Direct enumeration of every `Subaward*.xml`/`SubAward*.xml` file in
`/Users/mukadder/kuali-project/kuali-research/coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`,
cross-referenced against each file's `businessObjectClass` declaration,
then against the real OJB mapping that backs nearly every one of them in a
single file,
`coeus-impl/src/main/resources/org/kuali/kra/subaward/repository-subAward.xml`
(with `SubawardPersonMassChange` confirmed instead via
`.../kra/personmasschange/repository-personmasschange.xml`), to get each
one's literal, physical Oracle table (or confirmed absence of one, for
transient/UI-only classes). Cross-checked against the Oracle bootstrap DDL
and every later `ALTER TABLE` migration touching those tables under
`coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/`
(notably `V521_039__KC_TBL_SUBAWARD.sql`,
`V521_033__KC_TBL_SUBAWARD_AMOUNT_INFO.sql`,
`V1609_008__ffata_reporting.sql`,
`V2001_011__subaward_notes_att_comments.sql` — which creates both
`SUBAWARD_COMMENT` and `SUBAWARD_TEMPLATE_ATTACHMENTS` —
`V1907_002__add_subaward_notifications.sql`,
`V1905_006__subaward_financial_migration.sql`, and the FSRS/FFATA/PO-related
column-add migrations), plus the BU-specific customization file
`bu-db/BUKR-0026:Subaward.sql`, which is the only place `SUBAWARD_EXTENSION`
(`edu.bu.kuali.kra.subaward.bo.SubAwardExtension`, BU's own 1:1 extension of
`SubAward`) is defined — it never appears anywhere under `coeus-db-sql`'s
Kuali-authored bootstrap tree. Cross-checked against this project's current
archive schema (`database/migrations/V018__create_subaward_archive_tables.sql`,
`V019__create_subaward_attachment_archive.sql`) and ETL
(`etl/load_subawards_from_csv.py`, whose `DATASETS` tuple is a second,
independent confirmation of exactly which of the 11 `archive.subaward_*`
tables are actively loaded from Oracle — despite its legacy `_from_csv`
filename it reads via `OracleDataSource`/`oracle_sql_file`, consistent with
this repo's CSV-retirement decision in `docs/DECISIONS.md`; the referenced
Oracle extraction `.sql` files themselves are not checked into this repo,
which is true uniformly for every domain's loader, not a Subaward-specific
gap) to confirm actual current coverage.

## Subaward Feature Coverage Matrix

### Core Subaward record and its 1:1 extension

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAward.xml` | `SubAward` | `SUBAWARD` | Persisted entity | `archive.subaward` | **COMPLETE** | Every physical column found across the bootstrap DDL and its `ALTER TABLE` history (`fsrs_subaward_number`, `de_minimus`, `ffata_required`, `f_and_a_rate`, `fed_award_proj_desc`, `cost_type`, `date_of_fully_executed`/`requisition_number`, `subaward_sequence_status`, `award_prime_sponsor_name`/`award_sponsor_name`) is present in `archive.subaward` — `V018__create_subaward_archive_tables.sql` |
| `SubAwardExtension.xml` | `edu.bu.kuali.kra.subaward.bo.SubAwardExtension` | `SUBAWARD_EXTENSION` (BU-specific, `bu-db/BUKR-0026:Subaward.sql`) | Persisted entity (1:1 with SubAward, BU-specific) | `archive.subaward.extension_date_received` | **COMPLETE** | Unlike Award's still-open `AwardExtension`/`AwardCgb`, this BU 1:1 extension has exactly one real column beyond its PK (`DATE_RECEIVED`) — it was folded directly into `archive.subaward` as `extension_date_received` rather than given its own table. Confirmed via the BU customization SQL, since this table is absent from Kuali's own bootstrap tree entirely |
| `SubAwardDocument.xml` | `SubAwardDocument` (workflow doc) | `SUBAWARD_DOCUMENT` | Workflow envelope | — | **NOT APPLICABLE** | KEW routing/document-header metadata, not business content — mirrors `AwardDocument.xml` |

### Financial: amounts, released amounts, and FFATA reporting

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardAmountInfo.xml` | `SubAwardAmountInfo` | `SUBAWARD_AMOUNT_INFO` | Persisted entity | `archive.subaward_amount` | **COMPLETE** | Includes the direct/indirect obligated & anticipated change columns added by `V1703_019__subaward_idc_fields.sql` and the modification/rate/purchase-order columns added by `V1905_006__subaward_financial_migration.sql` |
| `SubAwardAmountReleased.xml` | `SubAwardAmountReleased` | `SUBAWARD_AMT_RELEASED` | Persisted entity (Kuali's own `objectLabel` is "Subaward Invoice") | — | **NOT YET ARCHIVED** | Real invoice/payment-release tracking against a subaward — amount released, invoice number, effective/start/end dates, and an actual invoice document blob (`DOCUMENT`/`FILE_NAME`/`MIME_TYPE`). No archive table exists yet; not previously tracked |
| `SubAwardFfataReporting.xml` | `SubAwardFfataReporting` | `SUBAWARD_FFATA_REPORTING` | Persisted entity | — | **NOT YET ARCHIVED** | FFATA (Federal Funding Accountability and Transparency Act) sub-recipient reporting submissions — real child of both `SubAward` and `SubAwardAmountInfo` (`V1609_008__ffata_reporting.sql`), with its own attached file. No archive table exists yet |
| `SubAwardModificationType.xml` | `org.kuali.kra.subaward.fdp.SubAwardModificationType` | `SUBAWARD_MODIFICATION_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Already denormalized as `archive.subaward_amount.modification_type_description` |

### Contacts and notifications

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardContact.xml` | `SubAwardContact` | `SUBAWARD_CONTACT` | Persisted entity | `archive.subaward_contact` | **COMPLETE** | `contact_type_code`/`rolodex_id`/`requisitioner_id` all captured |
| `SubAwardApprovalType.xml` | `SubAwardApprovalType` | `SUBAWARD_APPROVAL_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Not referenced from `SubAward`'s own OJB mapping as a live FK target in the reviewed repository file; a standalone code table regardless |

`SubAwardNotification` (business object
`org.kuali.kra.subaward.notification.SubAwardNotification`, table
`SUBAWARD_NOTIFICATION`) has **no root-level `SubAward*.xml` DataDictionary
entry at all** — confirmed as a real, persisted, already-archived business
object purely via its OJB mapping (`repository-subAward.xml`) and its
creating migration (`V1907_002__add_subaward_notifications.sql`), which is
why it isn't one of the 27 rows counted in this document's totals but is
recorded here for completeness:

| Business object (no DD entry) | Oracle table | Archive table | Status | Notes |
|---|---|---|---|---|
| `SubAwardNotification` | `SUBAWARD_NOTIFICATION` | `archive.subaward_notification` | **COMPLETE** | FK'd to `SUBAWARD.SUBAWARD_ID` via `OWNING_DOCUMENT_ID_FK`; mirrors the Award checklist's own note about `TimeAndMoneyDocument` lacking a dedicated DD entry |

### Attachments and template attachments

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardAttachments.xml` | `SubAwardAttachment` | `SUBAWARD_ATTACHMENTS` | Persisted entity | `archive.subaward_attachment` (+ `archive.subaward_attachment_archive` for the S3 binary manifest) | **COMPLETE** | `V019__create_subaward_attachment_archive.sql` adds the binary-manifest table, mirroring `award_attachment_archive`'s role for the Award domain — not itself a DD entity, so it gets no row of its own here, consistent with how the Award checklist treats it |
| `SubAwardAttachmentType.xml` | `SubAwardAttachmentType` | `SUBAWARD_ATTACHMENT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.subaward_attachment.attachment_type_description` |
| `SubAwardTemplateAttachments.xml` | `SubAwardTemplateAttachment` | `SUBAWARD_TEMPLATE_ATTACHMENTS` | Persisted entity | — | **NOT YET ARCHIVED** | A genuinely distinct, real per-subaward attachment collection (`V2001_011__subaward_notes_att_comments.sql`) — same shape as `SubAwardAttachment` (own `FILE_DATA_ID`/`FILE_NAME`/`MIME_TYPE`, own FK to `SUBAWARD`), not template *metadata*; Kuali's own object label distinguishes it as a separate "Template Attachment" feature. Newly surfaced by this pass; not previously tracked |
| `SubAwardTemplateAttachmentType.xml` | `SubAwardTemplateAttachmentType` | `SUBAWARD_TMPL_ATTACH_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Lookup for the not-yet-archived `SubAwardTemplateAttachment` above |

### Notes, comments, and custom data

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardNotepad.xml` | `SubAwardNotepad` | `SUBAWARD_NOTEPAD` | Persisted entity | `archive.subaward_notepad` | **COMPLETE** | |
| `SubAwardComment.xml` | `SubAwardComment` | `SUBAWARD_COMMENT` | Persisted entity | — | **NOT YET ARCHIVED** | **Distinct from `SubAwardNotepad`**, exactly as `AwardComment` is distinct from `AwardNotepad` in the Award domain — a separate "Comments" feature (`comment_type_code`, `checklist_print_flag`) created by `V2001_011__subaward_notes_att_comments.sql`. Newly surfaced by this pass |
| `SubAwardCustomData.xml` | `org.kuali.kra.subaward.customdata.SubAwardCustomData` | `SUBAWARD_CUSTOM_DATA` | Persisted entity | `archive.subaward_custom_data` | **COMPLETE** | |

### Closeout and reporting

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardCloseout.xml` | `SubAwardCloseout` | `SUBAWARD_CLOSEOUT` | Persisted entity | `archive.subaward_closeout` | **COMPLETE** | `closeout_type_code` FKs to the small `CLOSEOUT_TYPE` lookup, which has no `SubAward*.xml` DD entry of its own and is out of this document's enumeration scope |
| `SubAwardReports.xml` | `SubAwardReports` | `SUBAWARD_REPORTS` | Persisted entity | `archive.subaward_report` | **COMPLETE** | |
| `SubAwardReportType.xml` | `SubAwardReportType` | `SUBAWARD_REPORT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.subaward_report.report_type_description` |

### Funding source

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardFundingSource.xml` | `SubAwardFundingSource` | `SUBAWARD_FUNDING_SOURCE` | Persisted entity | `archive.subaward_funding` | **COMPLETE** | Carries the real `AWARD_ID` link back into the Award domain (`award_id` column, indexed) — the actual Proposal→Award→Subaward chain link point, distinct from the Award-side `archive.award_approved_subaward` rollup |

### Reference data and reusable templates (not real Subaward instances)

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubAwardStatus.xml` | `SubAwardStatus` | `SUBAWARD_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Already denormalized as `archive.subaward.status_description` |
| `SubAwardCostType.xml` | `SubAwardCostType` | `SUBCONTRACT_COST_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `SubAwardCopyRightsType.xml` | `SubAwardCopyRightsType` | `SUBCONTRACT_COPYRIGHT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Referenced by `archive.subaward_template_info.copyright_type`, which stores the raw code, not a denormalized description |
| `SubawardTemplateType.xml` | `SubawardTemplateType` | `SUBAWARD_TEMPLATE_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Drives which reusable form (`SubAwardForms`, below) applies |
| `SubAwardForms.xml` | `SubAwardForms` | `SUBAWARD_FORMS` | Template/reference metadata | — | **NOT APPLICABLE** | Keyed by `FORM_ID`, holds the reusable printable-form template content (a CLOB) itself, never a specific subaward's `SUBAWARD_ID` — mirrors the Award domain's Templates group treatment |
| `SubAwardPrintAgreement.xml` | `SubAwardPrintAgreement` | none (no OJB mapping found in `repository-subAward.xml`) | Transient | — | **NOT APPLICABLE** | Print/report-generation parameter holder for the printed subaward agreement document — mirrors `AwardPrintNotice.xml` |

### Workflow-internal and administrative utilities

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `SubawardPersonMassChange.xml` | `org.kuali.kra.personmasschange.bo.SubawardPersonMassChange` | `PMC_SUBAWARD` | Persisted entity, different feature | — | **NOT APPLICABLE** | Administrative bulk-personnel-change utility/audit table, not Subaward business content — mirrors `AwardPersonMassChange.xml` exactly, confirmed via `repository-personmasschange.xml` (not `repository-subAward.xml`) |

## Totals

- **COMPLETE**: 10 — `SubAward`, `SubAwardExtension`, `SubAwardAmountInfo`,
  `SubAwardContact`, `SubAwardAttachments`, `SubAwardNotepad`,
  `SubAwardCustomData`, `SubAwardCloseout`, `SubAwardReports`,
  `SubAwardFundingSource`.
- **PARTIALLY ARCHIVED**: 0.
- **NOT YET ARCHIVED**: 4 (all real, persisted, previously untracked; no
  Tier-2-sized subsystem exists in this domain the way Award has Budget/
  Time-and-Money) — `SubAwardAmountReleased` (subaward invoice/payment
  releases), `SubAwardFfataReporting` (federal sub-recipient reporting),
  `SubAwardComment` (distinct from `SubAwardNotepad`),
  `SubAwardTemplateAttachments` (distinct from `SubAwardAttachments`).
- **NOT APPLICABLE**: 13 — lookups (`SubAwardApprovalType`,
  `SubAwardModificationType`, `SubAwardAttachmentType`,
  `SubAwardTemplateAttachmentType`, `SubAwardReportType`, `SubAwardStatus`,
  `SubAwardCostType`, `SubAwardCopyRightsType`, `SubawardTemplateType`),
  template/reference metadata (`SubAwardForms`), transient UI helper
  (`SubAwardPrintAgreement`), workflow envelope (`SubAwardDocument`), and
  an administrative bulk-change utility (`SubawardPersonMassChange`).

27 `Subaward*.xml`/`SubAward*.xml` files total (root
`datadictionary/` level, excluding `docs/`).

Outside the 27-file count, but confirmed real and already archived via OJB
mapping + DDL rather than a DataDictionary entry: `SubAwardNotification` →
`archive.subaward_notification`, **COMPLETE**. Also outside the count,
already fully tracked in the Award domain's own checklist:
`AwardApprovedSubawards.xml` → `archive.award_approved_subaward`,
**COMPLETE** (per `KUALI_ARCHIVE_COVERAGE.md`).

## Open questions

- `SubAwardAmountReleased`, `SubAwardFfataReporting`,
  `SubAwardTemplateAttachments`, and `SubAwardComment` are all real,
  un-designed Subaward business data newly surfaced by this
  DataDictionary-driven pass — none have a design doc analogous to
  `AWARD_CUSTOM_DATA_DESIGN.md`/`AWARD_TERMS_DESIGN.md` yet. No sequencing
  decision has been made among the four.
- `SUBAWARD.PURCHASE_ORDER_NUM`: a real physical column (added by
  `V1907_003__RESKC-3452_subaward_po_changes.sql`, and already captured in
  `archive.subaward.purchase_order_num`) that has **no corresponding
  field-descriptor on the `SubAward` class** in `repository-subAward.xml` —
  only `SubAwardAmountInfo.purchaseOrderNum` is OJB-mapped. The DD's own
  `SubAward-purchaseOrderId`/`SubAward-subAwardAmountInfoList.purchaseOrderId`
  attribute references suggest the UI actually surfaces this value from the
  amount-info child, not the parent column directly — the exact
  relationship between the two `PURCHASE_ORDER_NUM` columns (parent vs.
  child) has not been investigated further. Doesn't block archive
  completeness since both are already captured (`archive.subaward.purchase_order_num`
  and `archive.subaward_amount.purchase_order_num`), but the *meaning* of
  having both is unresolved — same category of open question as the Award
  checklist's `AwardCostShare.FISCAL_YEAR` note.
- `SUBAWARD_AMOUNT_INFO_EXTENSION` (BU-specific, `bu-db/BUKR-0026:Subaward.sql`,
  columns `SUBAWARD_AMOUNT_INFO_ID`/`MODIFICATION_TYPE`): a real BU-created
  table with **zero references anywhere** in `coeus-impl`'s Java or XML —
  no OJB mapping, no DataDictionary entry, no business-object class. Appears
  to be an orphaned/abandoned BU customization, not a live business object;
  not given a row in this matrix because there is nothing to point a DD
  entry at, but flagged here since a raw Oracle-schema pass (the method this
  document explicitly avoids) could easily have mistaken it for an
  unarchived Subaward feature.
- `SubAwardComment` vs. `SubAwardNotepad`, and `SubAwardTemplateAttachments`
  vs. `SubAwardAttachments`: confirmed distinct classes and distinct tables
  in both pairs, exactly mirroring the Award checklist's own
  `AwardComment`-vs-`AwardNotepad` finding — the functional reason Kuali
  has two of each has not been investigated.

## Decisions

- The DataDictionary-driven matrix above is the primary authoritative
  checklist for Subaward-domain completeness, for the same reason it was
  adopted for Award: a functional feature list is a stronger definition of
  "done" than an Oracle table count.
- Unlike Award, Subaward has **no Tier-2-sized deferred subsystem** — there
  is no Subaward analogue of Award's Budget or Time-and-Money tables. All 4
  NOT YET ARCHIVED entries here are small, independent, Tier-1-sized
  additions that could each ship on their own.
- `SubAwardExtension` (BU-specific 1:1 extension) is treated as already
  resolved rather than left as an open "worth archiving?" question the way
  Award's `AwardExtension`/`AwardCgb` were — it has exactly one real column,
  and that column is already captured directly on `archive.subaward`.
- Lookup/reference tables, reusable form-template metadata, workflow-
  internal envelopes, transient UI beans, and the `SubawardPersonMassChange`
  administrative utility are NOT APPLICABLE regardless of having a
  DataDictionary entry, per the same two-part test the Award checklist
  uses: a DD entry confirms module-surface membership, not that the
  underlying object is a persisted, non-lookup, non-template business
  entity worth archiving. Both checks are required for NOT YET ARCHIVED /
  COMPLETE status.
- `SubAwardNotification` is recorded as COMPLETE despite having no DD entry
  at all, on the same basis the Award checklist uses for objects it
  encounters this way: real OJB mapping + real creating migration is
  sufficient proof of "real business object," a DD entry is not required
  to reach that conclusion, only to be *counted* in this document's 27-file
  total.

## Recommended implementation order

1. `SubAwardComment` — smallest of the four (four extra columns beyond the
   parent FK), most directly parallel to the already-solved `AwardComment`
   gap and to the already-complete `SubAwardNotepad` pattern this domain
   already has a working archive shape for.
2. `SubAwardTemplateAttachments` — same shape as the already-archived
   `SubAwardAttachments`/`archive.subaward_attachment_archive` pair; the
   S3-manifest pattern is proven and could be reused directly.
3. `SubAwardFfataReporting` — real federal compliance reporting data with
   its own attached file; child of both `SubAward` and the already-archived
   `SubAwardAmountInfo`, so no new parent-linkage design is required.
4. `SubAwardAmountReleased` — largest of the four (invoice document blob,
   invoice status/number, multiple date fields); do last since it is the
   least similar in shape to anything already archived in this domain.

Once all four ship, the Subaward domain has no known remaining gap and no
deferred Tier-2 subsystem — it would be the first of this project's five
domains (Award, Negotiation, Proposal, Subaward, IRB) to reach that state
under the DataDictionary-driven definition of "done."

## Date last updated

2026-07-31 (initial version, built from the `Subaward*.xml`/`SubAward*.xml`
DataDictionary listing using the same method as
`KUALI_ARCHIVE_COVERAGE.md`; found the domain already substantially
archived — 10 of 27 in-scope entries COMPLETE, 4 NOT YET ARCHIVED, 13 NOT
APPLICABLE, plus `SubAwardNotification` confirmed COMPLETE outside the
27-file DD-enumeration scope).
