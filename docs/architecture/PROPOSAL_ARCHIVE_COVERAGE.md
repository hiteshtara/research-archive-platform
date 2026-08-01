# Kuali Proposal Archive Coverage — Master Checklist

## Purpose

The authoritative checklist for declaring the Proposal domain complete,
built the same way as
[`KUALI_ARCHIVE_COVERAGE.md`](KUALI_ARCHIVE_COVERAGE.md) (the Award
domain's equivalent document): from Kuali's own **DataDictionary**
definitions, not from an Oracle table count. Every DataDictionary file
enumerated below is a business object *Kuali itself* considers part of
the Proposal module's functional surface — a raw table count can't
distinguish a real business feature (`InstitutionalProposalCfda.xml` —
CFDA codes are part of the Proposal model, exactly as they are for
Award) from a lookup table, a UI helper bean, or an administrative
utility. The DataDictionary listing makes that distinction explicit,
file by file, the same way it did for Award.

**This supersedes counting/percentage claims.** The correct claim is:
every DataDictionary entry that is a real persisted Proposal business
entity is marked COMPLETE, PARTIALLY ARCHIVED, or NOT YET ARCHIVED
below.

## Scope

Kuali models two related but distinct pre-award/post-conversion
concepts: **ProposalDevelopment** (the pre-submission proposal-authoring
module, package `org.kuali.coeus.propdev.*`) and **InstitutionalProposal**
(the converted, submitted/awarded proposal record, package
`org.kuali.kra.institutionalproposal.*`, physical table `PROPOSAL`).
This document verified which one this project's Proposal domain
actually targets (see Decisions) — it is **InstitutionalProposal**, not
ProposalDevelopment. Every `InstitutionalProposal*.xml` file plus
`ProposalStatus.xml` (a lookup referenced directly by
`InstitutionalProposal.statusCode`) under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/` — 24 files
total — is in scope. For each: the Java business object class, the
underlying Oracle table (if any), whether it is a **persisted business
entity**, a **lookup/reference**, **UI-only**, **workflow envelope**, or
**transient**, the archive mapping, and a status of **COMPLETE**,
**PARTIALLY ARCHIVED**, **NOT YET ARCHIVED**, or **NOT APPLICABLE**.

Two adjacent, verified-out-of-scope groups are also documented (but not
counted in the 24-file total or the Totals section below), exactly as
Award's own doc carved out Tier 2 as "deferred, not re-litigated" rather
than silently omitting it:

- **Award linkage** (`AwardFundingProposal.xml`,
  `AwardFundingProposalBean.xml`) — these are `Award*.xml` files, already
  fully tracked as COMPLETE/NOT APPLICABLE in
  `KUALI_ARCHIVE_COVERAGE.md`. They are cross-referenced here only
  because the Proposal loader also populates `archive.proposal_award`
  from the same Oracle table.
- **Proposal Log** (`ProposalLog.xml`, `ProposalLogExtension.xml`,
  `ProposalLogStatus.xml`, `ProposalLogType.xml`,
  `ProposalLogPersonMassChange.xml`) — a verified **separate** Kuali
  feature (a lightweight pre-submission deadline-tracking log, table
  `PROPOSAL_LOG`), not part of this project's Proposal domain. See
  Decisions.

## Source material used

Direct enumeration of every `InstitutionalProposal*.xml` and
`ProposalStatus.xml` file in
`/Users/mukadder/kuali-project/kuali-research/coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`,
cross-referenced against each file's `businessObjectClass` declaration,
then against the real OJB mapping that backs each class
(`coeus-impl/src/main/resources/org/kuali/kra/institutionalproposal/repository-institutionalproposal.xml`
for the InstitutionalProposal family, and
`.../kra/personmasschange/repository-personmasschange.xml` for the
mass-change utility classes) to confirm each one's real Oracle table (or
absence of one, for transient/UI-only/orphaned classes) — the same
double-verification discipline established by `KUALI_ARCHIVE_COVERAGE.md`
(Java mapping *and* real DDL, never one alone).

Oracle bootstrap DDL was read directly to confirm the `PROPOSAL` and
`PROPOSAL_PERSONS` table definitions (columns, nullability, primary
keys) —
`coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`
(lines ~8124–8190 for `PROPOSAL`, ~8698–8724 for `PROPOSAL_PERSONS`) —
plus later `ALTER TABLE PROPOSAL` migrations
(`V1612_003__nsf_references.sql`, `V1807_001__SponsorCodeLength.sql`,
`V1809_004__multi_cfda.sql`, `V1902_004__RESKC-3515.sql`,
`V1907_001__increase_mail_description_length.sql`) to confirm which
scalar columns are still physically present today versus which were
dropped/renamed over time (notably: `PROPOSAL.CFDA_NUMBER` and
`PROPOSAL.NSF_CODE` were both **dropped** from the live schema, not
merely un-archived — see the `InstitutionalProposal` row below).

Cross-checked against this project's own
`database/migrations/V015__create_proposal_archive_tables.sql`,
`V016__expand_proposal_person.sql`,
`V033__drop_award_unit_contact_and_proposal_person.sql`,
`V030__add_archive_list_performance_indexes.sql`, `docs/DECISIONS.md`,
and the ETL Proposal loader
(`etl/load_proposals_from_csv.py`, despite its legacy filename it reads
Oracle directly — see `docs/DECISIONS.md`) together with its Oracle
extraction SQL
(`sql/extract/proposal/01_proposal_versions.sql`,
`sql/extract/award/04_award_proposals.sql`) to confirm archived status
against what is actually shipped.

## Proposal Feature Coverage Matrix

### Core Proposal record

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposal.xml` | `InstitutionalProposal` | `PROPOSAL` | Persisted entity | `archive.proposal_version` | **PARTIALLY ARCHIVED** | `sql/extract/proposal/01_proposal_versions.sql` captures title, sequence status, type/activity type codes+descriptions, sponsor, lead unit, PI id/name (derived from `proposal_persons`), initial/total dates and costs, and `update_timestamp`. Not captured: `document_number`, `sponsor_proposal_number`, `current_account_number`, `rolodex_id`, `notice_of_opportunity_code`, grad-student headcount/person-months, `type_of_account`, `number_of_copies`, deadline date/type, mail-by/type/account/description, `subcontract_flag`, `cost_sharing_indicator`, `idc_rate_indicator`, `special_review_indicator`, `status_code`/description, `science_code_indicator`, `prime_sponsor_code`, `initial_contract_admin`, `ip_review_activity_indicator`, `current_award_number`, `opportunity`, `award_type_code`, `nsf_sequence_number`. `CFDA_NUMBER` and `NSF_CODE` were **dropped from Oracle itself** (`V1809_004__multi_cfda.sql`, `V1612_003__nsf_references.sql`), replaced by `PROPOSAL_CFDA` and `nsf_sequence_number` respectively — not "missing", genuinely gone from the source |
| `InstitutionalProposalExtension.xml` | `InstitutionalProposalExtension` (`edu.bu.kuali...`) | `PROPOSAL_EXTENSION` | Persisted entity (1:1 with Proposal, BU-specific) | — | **NOT YET ARCHIVED** | Same open "worth archiving at all" shape as Award's `AwardExtension`/`AwardCgb` — 2 scalar F&A-rate fields plus 3 flags, not decided |
| `InstitutionalProposalDocument.xml` | `InstitutionalProposalDocument` (workflow doc) | `INSTITUTE_PROPOSAL_DOCUMENT` | Workflow envelope | — | **NOT APPLICABLE** | KEW routing/document-header metadata (`TransactionalDocumentEntry`), not business content — mirrors `AwardDocument.xml` |

### Financial

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposalCostShare.xml` | `InstitutionalProposalCostShare` | `PROPOSAL_COST_SHARING` | Persisted entity | — | **NOT YET ARCHIVED** | Award's counterpart (`AwardCostShare`) is COMPLETE; this one has no Proposal-side design doc yet |
| `InstitutionalProposalFandA.xml` | `InstitutionalProposalFandA` | `PROPOSAL_FNA_RATE` | Persisted entity | — | **NOT YET ARCHIVED** | Applicable/institute F&A rate by rate class/type and fiscal year |
| `InstitutionalProposalUnrecoveredFandA.xml` | `InstitutionalProposalUnrecoveredFandA` | `PROPOSAL_UNRECOVERED_FNA_RATE` | Persisted entity | — | **NOT YET ARCHIVED** | Distinct from `InstitutionalProposalFandA` — under-recovery tracking, not the applicable rate itself |

### Compliance, keywords, and special review

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposalCfda.xml` | `InstitutionalProposalCfda` | `PROPOSAL_CFDA` | Persisted entity | — | **NOT YET ARCHIVED** | Replaced the single scalar `PROPOSAL.CFDA_NUMBER` column (dropped in `V1809_004__multi_cfda.sql`) — a real child table now, mirrors `AwardCfda` (already COMPLETE on the Award side) |
| `InstitutionalProposalScienceKeyword.xml` | `InstitutionalProposalScienceKeyword` | `PROPOSAL_SCIENCE_KEYWORD` | Persisted entity | — | **NOT YET ARCHIVED** | Many-to-many bridge to the shared `SCIENCE_KEYWORD` lookup, same shape as the already-COMPLETE `AwardScienceKeyword` |
| `InstitutionalProposalSpecialReview.xml` | `InstitutionalProposalSpecialReview` | `PROPOSAL_SPECIAL_REVIEW` | Persisted entity | — | **NOT YET ARCHIVED** | Carries a soft, non-enforced `PROTOCOL_NUMBER` cross-reference to IRB/Protocol, same open-question shape Award's own `AwardSpecialReview.PROTOCOL_NUMBER` has |
| `InstitutionalProposalSpecialReviewExemption.xml` | `InstitutionalProposalSpecialReviewExemption` | `PROPOSAL_EXEMPT_NUMBER` | Persisted entity, true child of `InstitutionalProposalSpecialReview` | — | **NOT YET ARCHIVED** | No `PROPOSAL_ID` column at all (keyed via `PROPOSAL_SPECIAL_REVIEW_ID`) — same shape as Award's `AwardSpecialReviewExemption` |

### Attachments, notes, and custom data

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposalAttachment.xml` | `InstitutionalProposalAttachment` | `PROPOSAL_ATTACHMENTS` | Persisted entity | — | **NOT YET ARCHIVED** | Award's `AwardAttachment` counterpart is COMPLETE |
| `InstitutionalProposalAttachmentType.xml` | `InstitutionalProposalAttachmentType` | `PROPOSAL_ATTACHMENT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `InstitutionalProposalNotepad.xml` | `InstitutionalProposalNotepad` | `PROPOSAL_NOTEPAD` | Persisted entity | — | **NOT YET ARCHIVED** | Award's `AwardNotepad` counterpart is COMPLETE (34 real rows confirmed in BU Oracle per `KUALI_ARCHIVE_COVERAGE.md`) — Proposal's has no archive equivalent at all yet |
| `InstitutionalProposalComment.xml` | `InstitutionalProposalComment` | `PROPOSAL_COMMENTS` | Persisted entity | — | **NOT YET ARCHIVED** | A second Java class, `org.kuali.kra.institutionalproposal.ProposalComment`, maps to the same `PROPOSAL_COMMENTS` table with no DataDictionary entry of its own — a Java/OJB-level duplicate, not a second archivable feature. Distinct concept from `InstitutionalProposalNotepad`, same "Comments vs Notes" duality Award's `AwardComment`/`AwardNotepad` pair has |
| `InstitutionalProposalCustomData.xml` | `InstitutionalProposalCustomData` | `PROPOSAL_CUSTOM_DATA` | Persisted entity | — | **NOT YET ARCHIVED** | Award's `AwardCustomData` counterpart is COMPLETE |

### People and contacts

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposalContact.xml` | `InstitutionalProposalContact` (abstract) | none (abstract base) | Abstract base class | — | **NOT APPLICABLE** | Confirmed `public abstract class` in `InstitutionalProposalContact.java`; shared base for `InstitutionalProposalPerson` and `InstitutionalProposalUnitContact` below, not itself persisted — mirrors `AwardContact.xml` |
| `InstitutionalProposalPerson.xml` | `InstitutionalProposalPerson` | `PROPOSAL_PERSONS` | Persisted entity | — (removed) | **NOT APPLICABLE** | Deliberately removed by explicit decision, not merely un-built: `archive.proposal_person` was created (`V015`, expanded in `V016`) then dropped (`V033__drop_award_unit_contact_and_proposal_person.sql`) because no verified Oracle extraction query existed for the full person/role/effort/credit-split shape — see `docs/DECISIONS.md`. The PI's `person_id`/`full_name` alone survive, folded into `archive.proposal_version.principal_investigator_id`/`principal_investigator_name` via a windowed join in `01_proposal_versions.sql` — that is not a substitute for the removed feature |
| `InstitutionalProposalPersonCreditSplit.xml` | `InstitutionalProposalPersonCreditSplit` | `PROPOSAL_PER_CREDIT_SPLIT` | Persisted entity, child of Person | — | **NOT APPLICABLE** | Child of the removed Person feature above; no independent archival value without its parent |
| `InstitutionalProposalPersonUnit.xml` | `InstitutionalProposalPersonUnit` | `PROPOSAL_PERSON_UNITS` | Persisted entity, child of Person | — | **NOT APPLICABLE** | Same reasoning as above |
| `InstitutionalProposalPersonUnitCreditSplit.xml` | `InstitutionalProposalPersonUnitCreditSplit` | `PROPOSAL_PERS_UNIT_CRED_SPLITS` | Persisted entity, child of PersonUnit | — | **NOT APPLICABLE** | Same reasoning as above |
| `InstitutionalProposalUnitContact.xml` | `InstitutionalProposalUnitContact` | `PROPOSAL_UNIT_CONTACTS` | Persisted entity, concrete `InstitutionalProposalContact` subclass | — | **NOT YET ARCHIVED** | **Not covered by the Person removal decision above** — a separate table/feature (unit-level administrative contacts, not project personnel) that was simply never built; `archive.proposal_unit_contact` has never existed in any migration. Distinct from Award's own `AwardUnitContact` (COMPLETE on the Award side) |

### Administrative utilities, lookups, and vestigial entries

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `InstitutionalProposalPersonMassChange.xml` | `InstitutionalProposalPersonMassChange` | `PMC_PROPOSAL` | Persisted entity, different feature | — | **NOT APPLICABLE** | Administrative bulk-personnel-change utility/audit table (`repository-personmasschange.xml`), not Proposal business content — mirrors `AwardPersonMassChange.xml` |
| `ProposalStatus.xml` | `ProposalStatus` | `PROPOSAL_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | The real data point is `InstitutionalProposal.statusCode` (see `InstitutionalProposal.xml` row — not currently archived) |
| `InstitutionalProposalUnit.xml` | `InstitutionalProposalUnit` | none — **no backing Java class found anywhere in the codebase** | Orphaned/vestigial DD entry | — | **NOT APPLICABLE** | Verified: no `.java` source file, no compiled `.class` file, and no `repository-institutionalproposal.xml` mapping exist for `org.kuali.kra.institutionalproposal.home.InstitutionalProposalUnit` anywhere in the checkout — a dead DataDictionary definition with nothing behind it. Not to be confused with the real `InstitutionalProposalUnitContact` above |

## Award linkage (cross-reference only — authoritative status lives in `KUALI_ARCHIVE_COVERAGE.md`)

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `AwardFundingProposal.xml` | `AwardFundingProposal` | `AWARD_FUNDING_PROPOSALS` | Persisted entity | `archive.award_funding_proposal` (Award) **and** `archive.proposal_award` (Proposal) | **COMPLETE** (Award doc) | The Proposal loader (`load_proposals_from_csv.py`, via `sql/extract/award/04_award_proposals.sql`) independently reads the same Oracle table to populate `archive.proposal_award` — a second, differently-shaped mirror of the same relationship, one row per (`proposal_id`, `award_id`) pair with `award_number` resolved by joining `archive.award_version`. Not necessarily wrong, but confirm this duplication is intentional (see Open questions) |
| `AwardFundingProposalBean.xml` | `AwardFundingProposalBean` | — (no OJB mapping) | UI-only | — | **NOT APPLICABLE** (Award doc) | Form-helper wrapper, not itself persisted |

## Proposal Log (verified separate feature — not part of this project's Proposal domain)

Every file below has "Proposal" in its name and lives in or near the
same DataDictionary tree, which is exactly why this pass checked it
rather than assuming it was the same concept as `InstitutionalProposal`.
It verifiably is not: `ProposalLog`'s physical table is `PROPOSAL_LOG`,
a lightweight, pre-submission deadline/PI-tracking log with its own
identity (`PROPOSAL_NUMBER` as primary key, sequence
`SEQ_PROPOSAL_NUMBER` — no `PROPOSAL_ID`, no `SEQUENCE_NUMBER` version
axis). It has no foreign-key relationship to `PROPOSAL`/`archive.proposal_version`
other than an optional, denormalized `INST_PROPOSAL_NUMBER` text field.
None of this project's Oracle extraction SQL or archive schema
references `PROPOSAL_LOG` in any way.

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProposalLog.xml` | `ProposalLog` | `PROPOSAL_LOG` | Persisted entity, separate feature | — | **NOT APPLICABLE** | Pre-submission deadline-tracking log, not the InstitutionalProposal record; out of this project's Proposal domain |
| `ProposalLogExtension.xml` | `ProposalLogExtension` (`edu.bu.kuali...`) | `PROPOSAL_LOG_EXTENSION` | Persisted entity, 1:1 with ProposalLog | — | **NOT APPLICABLE** | Same reasoning |
| `ProposalLogStatus.xml` | `ProposalLogStatus` | `PROPOSAL_LOG_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProposalLogType.xml` | `ProposalLogType` | `PROPOSAL_LOG_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProposalLogPersonMassChange.xml` | `ProposalLogPersonMassChange` | `PMC_PROPOSAL_LOG` | Persisted entity, bulk-change utility | — | **NOT APPLICABLE** | Same administrative-utility shape as `InstitutionalProposalPersonMassChange`, but for `ProposalLog` |

## Totals

Totals below cover only the 24 files in scope (the "Proposal Feature
Coverage Matrix" section) — the Award linkage cross-reference (2 files,
tracked in `KUALI_ARCHIVE_COVERAGE.md`) and Proposal Log (5 files,
verified out of scope) are excluded, exactly as Award's own Tier
groupings are broken out but separately labeled.

- **COMPLETE**: 0.
- **PARTIALLY ARCHIVED**: 1 — `InstitutionalProposal` (core version row;
  missing ~20 scalar fields, see its row above).
- **NOT YET ARCHIVED**: 13 — `InstitutionalProposalExtension`,
  `InstitutionalProposalCostShare`, `InstitutionalProposalFandA`,
  `InstitutionalProposalUnrecoveredFandA`, `InstitutionalProposalCfda`,
  `InstitutionalProposalScienceKeyword`,
  `InstitutionalProposalSpecialReview`,
  `InstitutionalProposalSpecialReviewExemption`,
  `InstitutionalProposalAttachment`, `InstitutionalProposalNotepad`,
  `InstitutionalProposalComment`, `InstitutionalProposalCustomData`,
  `InstitutionalProposalUnitContact`.
- **NOT APPLICABLE**: 10 — `InstitutionalProposalDocument`,
  `InstitutionalProposalAttachmentType`, `InstitutionalProposalContact`,
  `InstitutionalProposalPerson`, `InstitutionalProposalPersonCreditSplit`,
  `InstitutionalProposalPersonUnit`,
  `InstitutionalProposalPersonUnitCreditSplit`,
  `InstitutionalProposalPersonMassChange`, `ProposalStatus`,
  `InstitutionalProposalUnit`.

24 `InstitutionalProposal*.xml`/`ProposalStatus.xml` files total. Unlike
Award (26 of 68 COMPLETE, only 15 NOT YET ARCHIVED, most of them Tier
2), Proposal has **zero** COMPLETE entries: today only the core version
row exists at all, and even that is partial. Every child/detail table
that mirrors an already-shipped Award feature (attachments, notepad,
comments, custom data, CFDA, cost share, science keyword, special
review/exemption) remains entirely unbuilt on the Proposal side.

## Open questions

- `InstitutionalProposalExtension`: same open "worth archiving at all"
  question as Award's `AwardExtension`/`AwardCgb`. Not decided.
- `InstitutionalProposalComment` vs. the unmapped-in-DD
  `org.kuali.kra.institutionalproposal.ProposalComment` Java class (same
  `PROPOSAL_COMMENTS` table, no DataDictionary entry): appears to be
  dead/duplicate code in the Kuali source rather than two real features.
  Not investigated further; treat `InstitutionalProposalComment` as the
  authoritative one.
- `InstitutionalProposalSpecialReview.PROTOCOL_NUMBER`: a soft,
  non-enforced cross-reference to Kuali's Protocol/IRB world, same
  open-question shape as Award's own
  `AwardSpecialReview.PROTOCOL_NUMBER` (see `KUALI_ARCHIVE_COVERAGE.md`).
  Not resolved here.
- The `IntellectualPropertyReview` family (`IP_REVIEW`,
  `PROPOSAL_IP_REV_ACTIVITY`, `PROPOSAL_IP_REVIEW_JOIN`,
  `IP_REVIEW_ACTIVITY_TYPE`, `IP_REVIEW_REQ_TYPE`,
  `IP_REVIEW_RESULT_TYPE`) is mapped in the same
  `repository-institutionalproposal.xml` file and linked to
  `InstitutionalProposal` via `ProposalIpReviewJoin`/
  `PROPOSAL_IP_REVIEW_JOIN`, but its DataDictionary files are named
  `IntellectualPropertyReview*.xml`, not `Proposal*`/
  `InstitutionalProposal*` — out of this document's strict
  filename-based enumeration, the same kind of boundary Award's own doc
  drew around `TimeAndMoneyDocument`/`PendingTransaction`/
  `TransactionDetail`. Flagged, not resolved: a real, persisted,
  Proposal-linked feature with no DataDictionary-naming match and no
  archive coverage.
- `archive.proposal_award` vs. `archive.award_funding_proposal`: both
  are independently populated from Oracle's `AWARD_FUNDING_PROPOSALS`
  by two different loaders (Award's and Proposal's), producing two
  differently-shaped mirrors of the same relationship. Plausibly
  intentional (each domain needs the join from its own side), but not
  explicitly documented as such anywhere; worth a deliberate decision
  rather than leaving it implicit.
- `InstitutionalProposalUnit.xml`: confirmed orphaned (no Java class, no
  OJB mapping, anywhere in the checkout). No action needed beyond this
  record — flagged so a future pass doesn't mistake it for an
  unarchived real feature.

## Decisions

- This Proposal domain matrix is scoped by literal DataDictionary
  filename convention (`InstitutionalProposal*.xml` +
  `ProposalStatus.xml`), exactly mirroring how
  `KUALI_ARCHIVE_COVERAGE.md` scoped Award by `Award*.xml` — a
  functional feature list is a stronger definition of "done" than an
  Oracle table count.
- **Verified, not assumed**: this project's "Proposal" domain
  corresponds to Kuali's **InstitutionalProposal** (table `PROPOSAL`),
  not **ProposalDevelopment** (the pre-award authoring module, package
  `org.kuali.coeus.propdev.*`). Two independent pieces of evidence
  confirm this: (1) `org.kuali.coeus.propdev.*` has no Java source, no
  DataDictionary files, and no module directory anywhere in this
  checkout of `kuali-research` — BU's Kuali deployment did not carry
  full Proposal Development into this codebase at all; (2) the actual
  Oracle extraction SQL this project runs
  (`sql/extract/proposal/01_proposal_versions.sql`) selects from table
  `proposal` with columns (`proposal_number`, `sequence_number`,
  `proposal_sequence_status`, `requested_start_date_initial`,
  `total_direct_cost_initial`, …) that match `InstitutionalProposal`'s
  OJB mapping field-for-field, and joins `proposal_persons` — the
  `InstitutionalProposal.projectPersons` collection — for PI derivation.
- Award-domain DD files that reference Proposal
  (`AwardFundingProposal.xml`, `AwardFundingProposalBean.xml`) are
  cross-referenced here for completeness but their status is
  authoritative in `KUALI_ARCHIVE_COVERAGE.md`, not duplicated into this
  file's Totals.
- `ProposalLog` and its four satellite DD files are a **verified
  separate** Kuali feature (deadline-tracking log, not the
  InstitutionalProposal record) — documented here as explicitly
  out-of-scope rather than silently skipped, but excluded from the
  24-file total and the Totals section.
- Proposal's People feature (`InstitutionalProposalPerson` and its three
  child tables) is marked **NOT APPLICABLE**, not **NOT YET ARCHIVED** —
  reflecting the deliberate removal decision already recorded in
  `docs/DECISIONS.md` (no verified Oracle extraction query for the full
  person/role/effort/credit-split shape; `archive.proposal_person` was
  built in `V015`/`V016` and then dropped in `V033`). This is a
  different situation from `InstitutionalProposalUnitContact`
  (`PROPOSAL_UNIT_CONTACTS`), a separate table/feature that was simply
  never built and remains **NOT YET ARCHIVED** — the two must not be
  conflated, since V033 never touched a `proposal_unit_contact` table
  (it never existed).
- Lookup/reference tables, workflow envelopes, abstract base classes,
  administrative bulk-change utilities, and the one orphaned/vestigial
  DD entry (`InstitutionalProposalUnit`) are NOT APPLICABLE regardless
  of having a DataDictionary entry — a DD entry confirms something is
  part of the Proposal *module surface*, not that it is a *business
  record* worth archiving. Both checks (DD entry exists, and the
  underlying object is a persisted, non-lookup, non-template business
  entity) are required for NOT YET ARCHIVED / COMPLETE status, exactly
  as established for Award.

## Recommended implementation order

Proposal has no COMPLETE rows yet, so this order prioritizes the
child/detail tables that already have a proven, shipped Award-side
analog (same OJB shape, same UPSERT+batch pattern to reuse) before the
two open-question/no-clear-precedent items.

1. Proposal Attachments, Notes, Comments, and Custom Data
   (`InstitutionalProposalAttachment`, `InstitutionalProposalNotepad`,
   `InstitutionalProposalComment`, `InstitutionalProposalCustomData`) —
   standalone child tables, same shape as Award's already-shipped
   `AwardAttachment`/`AwardNotepad`/`AwardCustomData`
   (`AWARD_NOTEPAD_DESIGN.md`, `AWARD_CUSTOM_DATA_DESIGN.md`).
2. Compliance and special review (`InstitutionalProposalCfda`,
   `InstitutionalProposalScienceKeyword`,
   `InstitutionalProposalSpecialReview`/
   `InstitutionalProposalSpecialReviewExemption`) — mirrors Award's
   already-shipped Special Approvals and Compliance bundle
   (`AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`) almost table for
   table.
3. Financial (`InstitutionalProposalCostShare`,
   `InstitutionalProposalFandA`, `InstitutionalProposalUnrecoveredFandA`).
4. `InstitutionalProposalUnitContact` — the one still-open
   People/contacts table (`InstitutionalProposalPerson` itself stays
   closed by the removal decision above; don't reopen it without a
   verified Oracle extraction query for the full shape).
5. `InstitutionalProposal` core row's missing scalar fields (see the
   PARTIALLY ARCHIVED row) — a TRUNCATE-and-reload-style follow-on, same
   shape as Award's still-open `basis_of_payment_code`/
   `method_of_payment_code` gap.
6. `InstitutionalProposalExtension` — same open "worth archiving at
   all" question as Award's `AwardExtension`/`AwardCgb`; resolve
   alongside those, not separately.

Once steps 1–4 are done, only the core row's missing scalar fields, the
open-question 1:1 extension table, and the flagged-but-out-of-pattern
`IntellectualPropertyReview` family remain before the Proposal domain
could be declared functionally complete on the same terms Award was.

## Date last updated

2026-07-31 (initial DataDictionary-driven Proposal coverage matrix,
built to mirror `KUALI_ARCHIVE_COVERAGE.md`'s structure and rigor;
verified this project's Proposal domain targets Kuali's
InstitutionalProposal, not ProposalDevelopment; verified
`InstitutionalProposalUnit.xml` is an orphaned DataDictionary entry with
no backing class anywhere in the codebase; verified `ProposalLog` is a
separate, out-of-scope feature; verified `InstitutionalProposalPerson`'s
NOT APPLICABLE status reflects a deliberate removal decision, not an
oversight, and is distinct from the still-open
`InstitutionalProposalUnitContact`).
