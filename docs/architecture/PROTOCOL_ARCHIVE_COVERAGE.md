# Kuali Protocol (IRB) Archive Coverage — Master Checklist

## Purpose

The authoritative checklist for declaring the human-subjects domain
complete, built the same way as
[`KUALI_ARCHIVE_COVERAGE.md`](KUALI_ARCHIVE_COVERAGE.md) (Award): from
Kuali's own **DataDictionary** definitions
(`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/Protocol*.xml`),
not from counting Oracle tables. Every `Protocol*.xml` file is a business
object *Kuali itself* considers part of its Protocol module's functional
surface.

**A naming trap this document deliberately avoids** — three different
things share the word "Protocol"/"IRB" across this codebase's history, and
they are not interchangeable:

1. **Kuali's real source-code business object is "Protocol."** In the
   actual Kuali source tree, human-subjects/IRB review is implemented as
   `org.kuali.kra.irb.Protocol` (table `PROTOCOL`) and its ~69 sibling
   `Protocol*` classes — the `org.kuali.kra.protocol` package also exists
   as a shared abstract-base layer (`ProtocolAssociateBase`,
   `ProtocolAttachmentBase`) but the concrete, persisted human-subjects
   implementation lives under `org.kuali.kra.irb.*`. There is no Kuali
   business object literally named "IRB."
2. **This project calls that same data "IRB."** `archive.irb_*` (five
   tables: `irb_protocol`, `irb_protocol_stage`, `irb_protocol_version`,
   `irb_protocol_version_stage`, `irb_submission`/`irb_submission_stage`,
   `irb_funding_source`/`irb_funding_source_stage`,
   `irb_timeline_event`/`irb_timeline_event_stage`) is this project's name
   for Kuali's Protocol business object family. This is the **only**
   currently-shipping human-subjects domain in this repository — it has
   its own hexagonal ports/use-case layer
   (`application/port/in/IrbQueryUseCase` etc., see `CLAUDE.md`) and is
   what this document evaluates coverage against.
3. **A separate, now fully-removed "Protocol Archive" domain existed
   independently of IRB and is not part of this evaluation.** At an
   earlier point in this project's history, a second, independent
   human-subjects archive (`archive.protocol_version` /
   `protocol_person` / `protocol_unit`, its own API/UI/ETL) was built with
   the intent of eventually replacing legacy IRB. That plan was reversed:
   **Protocol Archive was removed in full** — API, UI, ETL loaders/Oracle
   SQL, and a forward-only schema-removal migration
   (`V032__drop_protocol_archive.sql`) — and legacy IRB was kept as the
   sole surviving domain. See `docs/DECISIONS.md`'s "Superseded: Protocol
   Archive (removed)" section for the full history. **This document does
   not propose resurrecting that domain.** Its one lasting contribution is
   `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` (retained, deprecated), a
   real Oracle data-quality finding — `PROTOCOL_ID` does not reliably
   identify a child row's business version; `PROTOCOL_NUMBER` +
   `SEQUENCE_NUMBER` must be used instead — that is cited below wherever
   relevant to a specific table.

So: **Kuali's "Protocol"** = **this project's "IRB"** = the thing being
measured here. **This project's (removed) "Protocol Archive"** is a
different, defunct thing and is out of scope.

**This document also supersedes any narrative that assumed most of the
Protocol/IRB surface would already be COMPLETE.** It is not. The
`archive.irb_*` schema was not built the way Award/Proposal/Negotiation/
Subaward were (a verified, column-checked Oracle-direct OJB extraction).
It was built from **a legacy administrative Excel/Parquet export**
(`etl/archive_etl/extract/excel.py`,
`etl/archive_etl/transform/irb.py`/`irb_composite.py`) — see "Decisions"
below for what that means for every row's Status.

## Scope

Every one of the 70 `Protocol*.xml` files under
`coeus-impl/src/main/resources/org/kuali/kra/datadictionary/` (the
`docs/` maintenance-document subfolder is out of scope, exactly as it was
for Award's 68 `Award*.xml` files). For each: the Java business object
class, the underlying Oracle table (if any), whether it is a **persisted
business entity**, a **lookup/reference**, **UI-only**, **template/
reference metadata**, a **workflow envelope**, or **transient**, the
archive mapping, and a status of **COMPLETE**, **PARTIALLY ARCHIVED**,
**NOT YET ARCHIVED**, or **NOT APPLICABLE**.

## Source material used

Direct enumeration of every `Protocol*.xml` file in
`/Users/mukadder/kuali-project/kuali-research/coeus-impl/src/main/resources/org/kuali/kra/datadictionary/`,
cross-referenced against each file's `businessObjectClass` declaration,
then against the real OJB mapping that backs each class —
`coeus-impl/src/main/resources/org/kuali/kra/irb/repository-irb.xml`
(already mirrored into this repo as
`reference/kc/ojb/ProtocolOJB.xml`, read in full), plus
`org/kuali/kra/committee/repository-committee.xml` (for the `meeting`
package's `ProtocolContingency`/`ProtocolVoteAbstainee`/
`ProtocolVoteRecused` classes) and
`org/kuali/kra/personmasschange/repository-personmasschange.xml` (for
`ProtocolPersonMassChange` → `PMC_PROTOCOL`) — to confirm each one's real
Oracle table (or absence of one, for transient/UI-only/abstract-base
classes). Cross-checked against the Oracle bootstrap DDL
(`coeus-db/coeus-db-sql/src/main/resources/co/kuali/coeus/data/migration/sql/oracle/kc/bootstrap/V300_107__schema.sql`,
plus the dedicated later-migration files for the two tables not in the
base schema, `V311_050__KC_TBL_REVIEWER_ATTACHMENTS.sql` and
`V400_194__KC_TBL_PMC_PROTOCOL.sql`) for every persisted table's real
columns, nullability, and primary key — the same double-verification
discipline as the Award pass, never trusting the Java/OJB mapping alone.
Cross-checked against this project's own current schema
(`database/migrations/V004__create_irb_tables.sql`,
`V005__create_irb_load_procedure.sql`, `V006__create_archive_views.sql`,
`V007__create_irb_composite_history.sql`,
`V008__create_irb_composite_stage.sql`,
`V009__create_global_search_view.sql`,
`V010__expand_global_search_to_history.sql`,
`V030__add_archive_list_performance_indexes.sql`) and the IRB ETL code
(`etl/archive_etl/extract/excel.py`,
`etl/archive_etl/transform/irb.py`, `transform/irb_composite.py`,
`validate/irb.py`) to determine real current coverage — not the removed
Protocol Archive's schema (`V021`–`V029`, `V031`, dropped by `V032`),
which is out of scope per the Purpose section above.
`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` was read for its
`PROTOCOL_ID`-vs-`PROTOCOL_NUMBER`+`SEQUENCE_NUMBER` finding and is cited
below wherever a table's parent-resolution shape is relevant to its
Status; it is historical/deprecated context, not re-litigated here.

## Protocol (IRB) Feature Coverage Matrix

### Core Protocol record and workflow envelope

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `Protocol.xml` | `org.kuali.kra.irb.Protocol` | `PROTOCOL` | Persisted entity | `archive.irb_protocol_version` (+ `archive.irb_protocol` for the current row) | **PARTIALLY ARCHIVED** | PK/business-versioning shape confirmed identical to Award's (`PROTOCOL_ID` surrogate row, `PROTOCOL_NUMBER`+`SEQUENCE_NUMBER` business version, confirmed via bootstrap DDL's `UQ_PROTOCOL` index). But `archive.irb_protocol_version` is sourced from a legacy administrative Excel "composite" export (`etl/archive_etl/transform/irb_composite.py`), not an Oracle-direct extraction — `DESCRIPTION`, `LAST_APPROVAL_DATE`, `REFERENCE_NUMBER_1`/`REFERENCE_NUMBER_2`, `FDA_APPLICATION_NUMBER`, and all seven business-indicator flags (`SPECIAL_REVIEW_INDICATOR`, `VULNERABLE_SUBJECT_INDICATOR`, `KEY_STUDY_PERSON_INDICATOR`, `FUNDING_SOURCE_INDICATOR`, `CORRESPONDENT_INDICATOR`, `REFERENCE_INDICATOR`, `RELATED_PROJECTS_INDICATOR`) are absent from the archive schema. Conversely, the archive carries several fields (`irb_analyst_id`, `irb_advisor_id`, `working_days`/`calendar_days`/`irb_days`/`pi_days`, `record_storage_box`, `ohrp_categories`) with **no corresponding column on the OJB-mapped `PROTOCOL` table at all** — likely a downstream BU IRB-office reporting layer, not the base KC business object; provenance unconfirmed (see Open questions) |
| `ProtocolDocument.xml` | `org.kuali.kra.irb.ProtocolDocument` (workflow doc) | `PROTOCOL_DOCUMENT` | Workflow envelope | — | **NOT APPLICABLE** | KEW routing/document-header metadata, not business content — DD entry uses `documentTypeName`, not `businessObjectClass` |
| `ProtocolStatus.xml` | `org.kuali.kra.irb.actions.ProtocolStatus` | `PROTOCOL_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.irb_protocol_version.protocol_status`/`protocol_status_code` |
| `ProtocolType.xml` | `org.kuali.kra.irb.protocol.ProtocolType` | `PROTOCOL_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.irb_protocol_version.protocol_type`/`protocol_type_code` |

### Personnel and unit affiliation

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolPerson.xml` | `org.kuali.kra.irb.personnel.ProtocolPerson` | `PROTOCOL_PERSONS` | Persisted entity | `archive.irb_protocol_version` (PI scalar fields only) | **PARTIALLY ARCHIVED** | This is the single most important finding in this pass: **the full personnel roster is not archived at all.** `PROTOCOL_PERSONS` is a ~60-column table covering every role (PI, co-investigator, key personnel, student, etc.) with demographics, affiliation, visa/citizenship, and contact detail per person per protocol version. The archive captures only the PI, and only as flat scalar columns on the protocol row itself (`pi_id`/`pi_buid`, `pi_first_name`, `pi_last_name`, `pi_full_name`, `pi_email`, `pi_affiliation_code`/`pi_affiliation`) — there is no `archive.irb_person`-style child table, no `protocol_person_role_id`, no co-investigators. This is a direct parallel to the exact problem the now-removed Protocol Archive project set out to solve (`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`'s ~15% `PROTOCOL_ID`/`PROTOCOL_NUMBER`+`SEQUENCE_NUMBER` mismatch for `PROTOCOL_PERSONS`) — that fix was built, then the whole domain it was built for was removed, leaving the surviving IRB domain with the original gap (no full personnel table) never addressed |
| `ProtocolPersonRole.xml` | `org.kuali.kra.irb.personnel.ProtocolPersonRole` | `PROTOCOL_PERSON_ROLES` | Lookup/reference | — | **NOT APPLICABLE** | Role taxonomy for the unarchived personnel roster above |
| `ProtocolPersonMassChange.xml` | `org.kuali.kra.personmasschange.bo.ProtocolPersonMassChange` | `PMC_PROTOCOL` | Persisted entity, different feature | — | **NOT APPLICABLE** | Administrative bulk-personnel-change utility/audit table — exact same treatment as Award's `AwardPersonMassChange`/`PMC_AWARD` |
| `ProtocolUnit.xml` | `org.kuali.kra.irb.personnel.ProtocolUnit` | `PROTOCOL_UNITS` | Persisted entity | — | **NOT YET ARCHIVED** | No archive table at all. Per bootstrap DDL, `PROTOCOL_UNITS` has no `PROTOCOL_ID` column — only `PROTOCOL_PERSON_ID` (real FK) plus `PROTOCOL_NUMBER`/`SEQUENCE_NUMBER` as audit-only fields, confirming `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`'s `OWNER_CHAIN` resolution requirement (unit → its owning person → that person's protocol) if ever archived |
| `ProtocolAssociate.xml` | `org.kuali.kra.protocol.ProtocolAssociateBase` | none (abstract base) | Abstract base class | — | **NOT APPLICABLE** | `public abstract class ProtocolAssociateBase extends KcPersistableBusinessObjectBase` — shared superclass for Protocol-family associate objects (e.g. `ProtocolPerson`), not itself persisted |

### Funding sources

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolFundingSource.xml` | `org.kuali.kra.irb.protocol.funding.ProtocolFundingSource` | `PROTOCOL_FUNDING_SOURCE` | Persisted entity | `archive.irb_funding_source` | **PARTIALLY ARCHIVED** | `funding_source` name and a derived `funding_sequence` are captured (denormalized out of 15 `FUNDING_SRC1`..`FUNDING_SRC15` composite-export columns via `build_funding()` in `transform/irb_composite.py`, not a 1:1 row mirror of `PROTOCOL_FUNDING_SOURCE`). `FUNDING_SOURCE_TYPE_CODE` (the lookup categorizing federal/industry/foundation/etc.) is not captured at all |

### Research areas, locations, and vulnerable subjects

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolResearchAreas.xml` | `org.kuali.kra.irb.protocol.research.ProtocolResearchArea` | `PROTOCOL_RESEARCH_AREAS` | Persisted entity | — | **NOT YET ARCHIVED** | A many-to-many bridge to the shared `RESEARCH_AREA` lookup, same shape as Award's `AwardScienceKeyword` — no archive table exists for it |
| `ProtocolLocation.xml` | `org.kuali.kra.irb.protocol.location.ProtocolLocation` | `PROTOCOL_LOCATION` | Persisted entity | — | **NOT YET ARCHIVED** | Study-site/organization location per protocol version; no archive table |
| `ProtocolOrganizationType.xml` | `org.kuali.kra.irb.protocol.location.ProtocolOrganizationType` | `PROTOCOL_ORG_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Supports the unarchived `ProtocolLocation` above |
| `ProtocolParticipant.xml` | `org.kuali.kra.irb.protocol.participant.ProtocolParticipant` | `PROTOCOL_VULNERABLE_SUB` | Persisted entity | — | **NOT YET ARCHIVED** | Vulnerable-subject-population counts by type per protocol version; no archive table |

### Submissions, committee review, and voting

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolSubmission.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmission` | `PROTOCOL_SUBMISSION` | Persisted entity | `archive.irb_submission` | **PARTIALLY ARCHIVED** | `submission_number`, `submission_type`/`code`, `submission_status`/`code`, `event_type`/`code`, `review_type`/`code` are captured. `committee_id`/`schedule_id`, vote tallies (`yes`/`no`/`abstainer`/`recused` counts), `comments`, `voting_comments`, `is_billable`, and the committee decision motion type are not |
| `ProtocolSubmissionDoc.xml` | `org.kuali.kra.irb.actions.ProtocolSubmissionDoc` | `PROTOCOL_SUBMISSION_DOC` | Persisted entity | — | **NOT YET ARCHIVED** | BLOB document attached to a submission; no archive table (and no attachment-binary path for IRB submissions the way Award/Subaward attachments have one) |
| `ProtocolSubmissionLite.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmissionLite` | `PROTOCOL_SUBMISSION_V` | Read-only DB view | — | **NOT APPLICABLE** | A view over `ProtocolSubmission` plus denormalized protocol/PI fields — `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` explicitly notes this "is a read view, not an additional transactional child... do not count it as another archive entity"; honored here |
| `ProtocolSubmissionType.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmissionType` | `SUBMISSION_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.irb_submission.submission_type` |
| `ProtocolSubmissionStatus.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmissionStatus` | `SUBMISSION_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.irb_submission.submission_status` |
| `ProtocolSubmissionQualifierType.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmissionQualifierType` | `SUBMISSION_TYPE_QUALIFIER` | Lookup/reference | — | **NOT APPLICABLE** | Qualifier code on `ProtocolSubmission`, not itself captured or needed independently |
| `ProtocolReviewType.xml` | `org.kuali.kra.irb.actions.submit.ProtocolReviewType` | `PROTOCOL_REVIEW_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Denormalized as `archive.irb_submission.review_type` |
| `ProtocolReviewerType.xml` | `org.kuali.kra.irb.actions.submit.ProtocolReviewerType` | `PROTOCOL_REVIEWER_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Supports the unarchived reviewer/online-review tables below |
| `ProtocolOnlineReview.xml` | `org.kuali.kra.irb.onlinereview.ProtocolOnlineReview` | `PROTOCOL_ONLN_RVWS` | Persisted entity | — | **NOT YET ARCHIVED** | Individual committee member's online review of a submission; no archive table (note: `PROTOCOL_REVIEWERS`/`ProtocolReviewer` itself has no own `Protocol*.xml` DD entry — it is reachable only via `ProtocolOnlineReview`'s and `ProtocolReviewAttachment`'s reference descriptors — but remains equally un-archived) |
| `ProtocolOnlineReviewStatus.xml` | `org.kuali.kra.irb.onlinereview.ProtocolOnlineReviewStatus` | `PROTOCOL_ONLN_RVW_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolOnlineReviewDeterminationRecommendation.xml` | `org.kuali.kra.irb.onlinereview.ProtocolOnlineReviewDeterminationRecommendation` | `PROTOCOL_ONLN_RVW_DETERM_RECOM` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolOnlineReviewDocument.xml` | `org.kuali.kra.irb.ProtocolOnlineReviewDocument` (workflow doc) | `PROTOCOL_ONLN_RVW_DOCUMENT` | Workflow envelope | — | **NOT APPLICABLE** | KEW routing metadata, `documentTypeName`-based DD entry |
| `ProtocolReviewAttachment.xml` | `org.kuali.kra.irb.onlinereview.ProtocolReviewAttachment` | `REVIEWER_ATTACHMENTS` | Persisted entity | — | **NOT YET ARCHIVED** | Added by dedicated migration `V311_050__KC_TBL_REVIEWER_ATTACHMENTS.sql`, not the base bootstrap schema; no archive table |
| `ProtocolVoteAbstainee.xml` | `org.kuali.kra.meeting.ProtocolVoteAbstainee` | `PROTOCOL_VOTE_ABSTAINEES` | Persisted entity | — | **NOT YET ARCHIVED** | Mapped in `org/kuali/kra/committee/repository-committee.xml`, not `repository-irb.xml` — a committee-meeting vote-abstention record, no archive table |
| `ProtocolVoteRecused.xml` | `org.kuali.kra.meeting.ProtocolVoteRecused` | `PROTOCOL_VOTE_RECUSED` | Persisted entity | — | **NOT YET ARCHIVED** | Same package/file as above; no archive table |
| `ProtocolContingency.xml` | `org.kuali.kra.meeting.ProtocolContingency` | `PROTOCOL_CONTINGENCY` | Lookup/reference | — | **NOT APPLICABLE** | Committee-decision contingency-reason code, referenced from committee-meeting minutes, not a per-protocol business record |
| `ProtocolSubmittedBean.xml` | `org.kuali.kra.meeting.ProtocolSubmittedBean` | none (no OJB mapping) | Transient | — | **NOT APPLICABLE** | `implements Serializable` plain UI helper bean (agenda/committee-schedule submitted-protocol list), not persisted |

### Actions, amendments/renewals, and risk level

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolAction.xml` | `org.kuali.kra.irb.actions.ProtocolAction` | `PROTOCOL_ACTIONS` | Persisted entity | `archive.irb_timeline_event` (approximate) | **PARTIALLY ARCHIVED** | `archive.irb_timeline_event` is **not** sourced from `PROTOCOL_ACTIONS` at all — it is synthesized in `build_timeline()` (`transform/irb_composite.py`) directly from ~19 date columns in the composite Excel export (received/claimed/determination/approval/expiration/closure/authorization dates, plus up to 6 rounds of modification-request/response dates). It approximates the *timing* of some of the same real-world events `PROTOCOL_ACTIONS` records, but carries none of that table's actual content: no `protocol_action_type_code`, no `comments`, no actor, no submission linkage, and per `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` this table's `PROTOCOL_ID` mismatches its `PROTOCOL_NUMBER`+`SEQUENCE_NUMBER` parent at ~85.6% in raw Oracle data — a rate this document's synthesized substitute sidesteps entirely by never touching the table |
| `ProtocolActionType.xml` | `org.kuali.kra.irb.actions.ProtocolActionType` | `PROTOCOL_ACTION_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Supports the un-archived `ProtocolAction` above |
| `ProtocolAmendRenewal.xml` | `org.kuali.kra.irb.actions.amendrenew.ProtocolAmendRenewal` | `PROTO_AMEND_RENEWAL` | Persisted entity | — | **NOT YET ARCHIVED** | Amendment/renewal request record (date, summary, protocol linkage); no archive table. Per `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`, this table's `PROTOCOL_ID` never matches its `PROTOCOL_NUMBER`+`SEQUENCE_NUMBER` parent (100% mismatch measured) — `NUMBER_SEQUENCE` resolution would be mandatory if ever archived. Note: `ProtocolAmendRenewModule` (`PROTO_AMEND_RENEW_MODULES`, the child linking an amendment to the protocol module(s) it touches) has no own `Protocol*.xml` DD entry, but remains equally un-archived |
| `ProtocolRiskLevel.xml` | `org.kuali.kra.irb.actions.risklevel.ProtocolRiskLevel` | `PROTOCOL_RISK_LEVELS` | Persisted entity | — | **NOT YET ARCHIVED** | Assigned risk-level record with effective/inactivation dates; no archive table |

### Special review and external references

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolSpecialReview.xml` | `org.kuali.kra.irb.specialreview.ProtocolSpecialReview` | `PROTOCOL_SPECIAL_REVIEW` | Persisted entity | — | **NOT YET ARCHIVED** | The Protocol/IRB domain's own special-review tracking (e.g. IBC, radiation-safety approvals recorded on a protocol) — analogous in shape to Award's `AwardSpecialReview`, but this is IRB's own instance, not the same table Award's `AwardSpecialReview.PROTOCOL_NUMBER` soft-references. No archive table |
| `ProtocolSpecialReviewExemption.xml` | `org.kuali.kra.irb.specialreview.ProtocolSpecialReviewExemption` | `PROTOCOL_EXEMPT_NUMBER` | Persisted entity | — | **NOT YET ARCHIVED** | True child of `ProtocolSpecialReview` above (`PROTOCOL_SPECIAL_REVIEW_ID` FK, no `PROTOCOL_ID` of its own) — same "no direct protocol FK" shape as Award's `AwardSpecialReviewExemption` |
| `ProtocolReference.xml` | `org.kuali.kra.irb.protocol.reference.ProtocolReference` | `PROTOCOL_REFERENCES` | Persisted entity | — | **NOT YET ARCHIVED** | External reference numbers (e.g. registry IDs) per protocol version; no archive table |
| `ProtocolReferenceType.xml` | `org.kuali.kra.irb.protocol.reference.ProtocolReferenceType` | `PROTOCOL_REFERENCE_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | Supports the un-archived `ProtocolReference` above |
| `ProtocolReferenceBean.xml` | `org.kuali.kra.irb.protocol.reference.ProtocolReferenceBean` | none (no OJB mapping) | Transient | — | **NOT APPLICABLE** | UI add/edit-reference form helper |

### Correspondence and notification

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolCorrespondence.xml` | `org.kuali.kra.irb.correspondence.ProtocolCorrespondence` | `PROTOCOL_CORRESPONDENCE` | Persisted entity | — | **NOT YET ARCHIVED** | Generated correspondence document (BLOB) per protocol action; no archive table |
| `ProtocolCorrespondenceTemplate.xml` | `org.kuali.kra.irb.correspondence.ProtocolCorrespondenceTemplate` | `PROTO_CORRESP_TEMPL` | Template/reference metadata | — | **NOT APPLICABLE** | Keyed by committee + correspondence-type code, not a real protocol instance — same category as Award's `AwardTemplate*` rows |
| `ProtocolCorrespondenceType.xml` | `org.kuali.kra.irb.correspondence.ProtocolCorrespondenceType` | `PROTO_CORRESP_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolNotificationTemplate.xml` | `org.kuali.kra.irb.actions.notification.ProtocolNotificationTemplate` | `PROTO_NOTIFICATION_TEMPL` | Template/reference metadata | — | **NOT APPLICABLE** | BLOB notification template keyed by action-type code, not a real protocol instance |

### Attachments and notepad

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolAttachmentBase.xml` | `org.kuali.kra.protocol.noteattachment.ProtocolAttachmentBase` | none (abstract base) | Abstract base class | — | **NOT APPLICABLE** | `public abstract class ProtocolAttachmentBase extends ProtocolAssociateBase implements TypedAttachment` — shared superclass for the two concrete attachment classes below, not itself persisted |
| `ProtocolAttachmentProtocol.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentProtocol` | `PROTOCOL_ATTACHMENT_PROTOCOL` | Persisted entity | — | **NOT YET ARCHIVED** | Protocol-level attachment metadata (file, type, status, contact); no archive table for IRB attachment metadata at all — unlike Award/Subaward, which both have their own archived attachment tables |
| `ProtocolAttachmentPersonnel.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentPersonnel` | `PROTOCOL_ATTACHMENT_PERSONNEL` | Persisted entity | — | **NOT YET ARCHIVED** | Personnel-scoped attachment metadata; no archive table |
| `ProtocolAttachmentGroup.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentGroup` | `PROTOCOL_ATTACHMENT_GROUP` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolAttachmentStatus.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentStatus` | `PROTOCOL_ATTACHMENT_STATUS` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolAttachmentType.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentType` | `PROTOCOL_ATTACHMENT_TYPE` | Lookup/reference | — | **NOT APPLICABLE** | |
| `ProtocolAttachmentTypeGroup.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentTypeGroup` | `PROTOCOL_ATTACHMENT_TYPE_GROUP` | Lookup/reference (bridge) | — | **NOT APPLICABLE** | Type-to-group bridge, not a real protocol business record |
| `ProtocolAttachmentFilter.xml` | `org.kuali.kra.irb.noteattachment.ProtocolAttachmentFilter` | none (no OJB mapping) | Transient | — | **NOT APPLICABLE** | UI attachment-list filter-criteria bean |
| `ProtocolNotepad.xml` | `org.kuali.kra.irb.noteattachment.ProtocolNotepad` | `PROTOCOL_NOTEPAD` | Persisted entity | — | **NOT YET ARCHIVED** | Free-text notes per protocol; no `archive.irb_notepad` table, unlike Award's archived `AwardNotepad` |

### Workflow action UI beans (transient, no persisted business object)

Every one of these is a plain form-helper/action-parameter class backing a
single IRB workflow action (approve, withdraw, delete, submit, request
review, assign to committee agenda, notify committee/IRB office, grant
exemption, modify a submission, undo the last action, generic multi-purpose
action, admin correction). None has an OJB mapping anywhere in this Kuali
source tree — confirmed by cross-referencing every class name against
`repository-irb.xml`, `repository-committee.xml`, and
`repository-personmasschange.xml` with no match. This is the same category
Award's `AwardTransactionSelectorBean`/`AwardPrintNotice` fall into, just a
much larger group here because IRB's workflow has many more distinct
actions than Award's.

| DataDictionary XML | Business object | Oracle table | Type | Archive table | Status | Notes |
|---|---|---|---|---|---|---|
| `ProtocolAdminCorrectionActionBean.xml` | `org.kuali.kra.irb.actions.correction.AdminCorrectionBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolAmendmentBean.xml` | `org.kuali.kra.irb.actions.amendrenew.ProtocolAmendmentBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolApproveBean.xml` | `org.kuali.kra.irb.actions.approve.ProtocolApproveBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolAssignCmtSchedBean.xml` | `org.kuali.kra.irb.actions.assigncmtsched.ProtocolAssignCmtSchedBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolAssignToAgendaBean.xml` | `org.kuali.kra.irb.actions.assignagenda.ProtocolAssignToAgendaBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolDeleteBean.xml` | `org.kuali.kra.irb.actions.delete.ProtocolDeleteBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolGenericActionBean.xml` | `org.kuali.kra.irb.actions.genericactions.ProtocolGenericActionBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolGrantExemptionBean.xml` | `org.kuali.kra.irb.actions.grantexemption.ProtocolGrantExemptionBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolModifySubmissionBean.xml` | `org.kuali.kra.irb.actions.modifysubmission.ProtocolModifySubmissionBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolNotifyCommitteeBean.xml` | `org.kuali.kra.irb.actions.notifycommittee.ProtocolNotifyCommitteeBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolNotifyIrbBean.xml` | `org.kuali.kra.irb.actions.notifyirb.ProtocolNotifyIrbBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolRequestBean.xml` | `org.kuali.kra.irb.actions.request.ProtocolRequestBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolReviewNotRequiredBean.xml` | `org.kuali.kra.irb.actions.noreview.ProtocolReviewNotRequiredBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolReviewerBean.xml` | `org.kuali.kra.irb.actions.submit.ProtocolReviewerBean` | none | Transient | — | **NOT APPLICABLE** | UI helper for adding a reviewer to a submission — distinct from the persisted `ProtocolReviewer`/`PROTOCOL_REVIEWERS` it helps populate (itself un-archived, see Submissions group) |
| `ProtocolSubmitAction.xml` | `org.kuali.kra.irb.actions.submit.ProtocolSubmitAction` | none | Transient | — | **NOT APPLICABLE** | `extends ProtocolActionBean` |
| `ProtocolUndoLastActionBean.xml` | `org.kuali.kra.irb.actions.undo.UndoLastActionBean` | none | Transient | — | **NOT APPLICABLE** | |
| `ProtocolWithdrawBean.xml` | `org.kuali.kra.irb.actions.withdraw.ProtocolWithdrawBean` | none | Transient | — | **NOT APPLICABLE** | |

## Totals

- **COMPLETE**: 0.
- **PARTIALLY ARCHIVED**: 5 — `Protocol` (core fields, missing several
  scalars/flags), `ProtocolPerson` (PI only, no personnel roster at all),
  `ProtocolFundingSource` (name only, no type code), `ProtocolSubmission`
  (type/status only, no vote tallies/committee linkage), `ProtocolAction`
  (approximated by a synthesized timeline, not the real action records).
- **NOT YET ARCHIVED**: 18 — `ProtocolUnit`, `ProtocolResearchAreas`,
  `ProtocolLocation`, `ProtocolParticipant`, `ProtocolSubmissionDoc`,
  `ProtocolOnlineReview`, `ProtocolReviewAttachment`,
  `ProtocolVoteAbstainee`, `ProtocolVoteRecused`, `ProtocolAmendRenewal`,
  `ProtocolRiskLevel`, `ProtocolSpecialReview`,
  `ProtocolSpecialReviewExemption`, `ProtocolReference`,
  `ProtocolCorrespondence`, `ProtocolAttachmentProtocol`,
  `ProtocolAttachmentPersonnel`, `ProtocolNotepad`.
- **NOT APPLICABLE**: 47 — lookups/reference tables, template/reference
  metadata, workflow envelopes, the two abstract base classes
  (`ProtocolAssociate`, `ProtocolAttachmentBase`), the administrative
  `ProtocolPersonMassChange` utility table, the `ProtocolSubmissionLite`
  read view, and 17 transient workflow-action UI beans.

70 `Protocol*.xml` files total (4+5+1+4+17+4+5+4+9+17 across the ten
functional groups above = 70).

**This is a materially different outcome than Award's.** Award's pass
found 26 of 68 entries COMPLETE. Protocol/IRB's pass finds **zero**
entries fully COMPLETE, because the entire `archive.irb_*` schema is
sourced from a legacy administrative Excel export rather than a verified
Oracle-direct extraction — every persisted-entity row that has *any*
archive coverage is capped at PARTIALLY ARCHIVED, and the large majority
of Kuali's real persisted Protocol child tables (personnel roster,
locations, research areas, risk level, special review, amendments,
correspondence, attachments, notepad, online review, voting) have no
archive table at all.

## Open questions

- **Provenance of the composite-export-only fields on
  `archive.irb_protocol_version`** (`irb_analyst_id`, `irb_advisor_id`,
  `working_days`/`calendar_days`/`irb_days`/`pi_days`,
  `record_storage_box`, `maximum_expiration_ind`, `expiration_status`,
  `ohrp_categories`, `fund_center_number`, `school_number`): none of these
  correspond to a column on the OJB-mapped `PROTOCOL` table. They likely
  originate from a BU IRB-office administrative reporting layer built on
  top of Kuali data (a Crystal Reports/Business Objects-style export, or a
  local Access/Excel tracking sheet) rather than the base KC business
  object — not confirmed, no such layer's source was found in this Kuali
  checkout. Not resolved here.
- **Whether the personnel-roster gap (`ProtocolPerson`) is worth closing**
  given Oracle's own `PROTOCOL_ID` unreliability for that exact table
  (`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`'s ~15% mismatch) — any
  future attempt would need `NUMBER_SEQUENCE` parent resolution from day
  one, exactly as that deprecated analysis specifies, and would need a
  fresh, currently-nonexistent Oracle-direct extraction path for IRB
  (today's IRB ETL has no Oracle connectivity at all — see Decisions).
  Not decided.
- Whether `ProtocolAction`'s real content (action type, comments, actor,
  submission linkage) is worth archiving as a genuine `archive.irb_action`
  table, given `archive.irb_timeline_event` already covers the *dates* of
  the same underlying events via a different, synthesized path. Not
  decided.
- No dedicated Oracle bootstrap block was found for `PROTOCOL_REVIEWERS`
  in the same read pass that covered every other table in this document —
  it is mapped in `repository-irb.xml` and referenced from
  `ProtocolOnlineReview`'s and `ProtocolReviewAttachment`'s OJB
  reference-descriptors, but has no own `Protocol*.xml` DataDictionary
  entry, so it is noted here rather than given its own matrix row.

## Decisions

- The DataDictionary-driven matrix above is the same style of primary,
  authoritative checklist established for Award, extended to Protocol/IRB
  for the first time in this pass — a functional feature list is a
  stronger definition of "done" than an Oracle table count.
- **`archive.irb_*` is not, and has never been, an Oracle-direct extraction
  the way Award/Proposal/Negotiation/Subaward are.** It is built entirely
  from a legacy IRB-office Excel/Parquet export
  (`etl/archive_etl/extract/excel.py` reads the workbook;
  `transform/irb.py` maps the "current protocol" sheet;
  `transform/irb_composite.py` derives `irb_protocol_version`/
  `irb_submission`/`irb_funding_source`/`irb_timeline_event` from a single
  wide "composite" sheet via column selection, melting, and
  date-column-to-event-row synthesis). `CLAUDE.md`'s note that "S3 is
  retained... for the legacy IRB Excel/Parquet export pipeline" is this
  same fact stated from the infrastructure side. This is why no row in
  this matrix reaches COMPLETE even where real coverage exists: fidelity
  can only be judged against what the export sheet actually carried, not
  against a verified Oracle column list, and several real Oracle columns
  are confirmed absent from the export-derived schema.
- The removed Protocol Archive domain (`archive.protocol_*`,
  `V021`–`V029`/`V031`, dropped by `V032`) is not re-evaluated and not
  resurrected by this document. Its one durable artifact,
  `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`'s parent-resolution
  findings, is cited above only where it bears on a specific Oracle
  table's shape (`PROTOCOL_PERSONS`, `PROTOCOL_ACTIONS`,
  `PROTO_AMEND_RENEWAL`, `PROTOCOL_UNITS`), not as a plan for future work.
- Lookup/reference tables, template/reference metadata, workflow
  envelopes, abstract base classes, the administrative
  `ProtocolPersonMassChange` table, and transient/UI-only workflow-action
  beans are NOT APPLICABLE regardless of having a DataDictionary entry —
  identical rule to the Award document: a DD entry confirms Kuali
  considers something part of the module surface, not that it is a
  business record worth archiving.
- `ProtocolSubmissionLite`/`PROTOCOL_SUBMISSION_V` is NOT APPLICABLE as a
  read view over `ProtocolSubmission`, per the explicit instruction
  already recorded in `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` not to
  count it as a separate entity.

## Recommended implementation order

Unlike Award (which had a clear "special approvals and compliance" bundle
ready to ship), Protocol/IRB's gap is dominated by one structural problem
— **there is no Oracle-direct ETL path for IRB at all today** — so the
real prerequisite work is different in kind, not just in table count:

1. Decide whether IRB should ever get an Oracle-direct extraction path
   (mirroring Award/Proposal/Negotiation/Subaward's `etl/` pattern) or
   whether the Excel/Parquet pipeline remains permanent. Every row below
   this line assumes the former; none of it is buildable against the
   current Excel-only pipeline without a real Oracle connection and a
   verified extraction query, per this repo's standing rule against
   inventing Oracle table/column names.
2. If an Oracle-direct path is approved: `ProtocolPerson` (full personnel
   roster, `NUMBER_SEQUENCE` resolution per the deprecated analysis) is
   the highest-value gap — it is the one gap severe enough to affect
   correctness of a currently-shipping page (today's PI-only fields are
   presented as if they were the whole personnel picture).
3. `ProtocolLocation`, `ProtocolResearchAreas`, `ProtocolSpecialReview`/
   `ProtocolSpecialReviewExemption`, `ProtocolRiskLevel`,
   `ProtocolParticipant` — straightforward child tables with clear
   `PROTOCOL_ID` FKs (no `OWNER_CHAIN`/`NUMBER_SEQUENCE` complications per
   the parent-resolution measurements, aside from `ProtocolLocation`'s own
   ~5.3% measured mismatch, which would need re-verification against
   current data before trusting a direct-ID join).
4. `ProtocolAmendRenewal`, `ProtocolNotepad`, `ProtocolCorrespondence`,
   `ProtocolAttachmentProtocol`/`ProtocolAttachmentPersonnel` — each is a
   real feature with no home today; `ProtocolAmendRenewal` specifically
   needs `NUMBER_SEQUENCE` resolution (100% measured `PROTOCOL_ID`
   mismatch).
5. `ProtocolOnlineReview`/`ProtocolReviewAttachment`/vote tables
   (`ProtocolVoteAbstainee`/`ProtocolVoteRecused`) — committee-review
   detail, lowest priority given it is meeting-administration detail
   rather than protocol-content detail.
6. Revisit whether `Protocol`'s own missing scalar fields (`DESCRIPTION`,
   `REFERENCE_NUMBER_1`/`REFERENCE_NUMBER_2`, the seven business-indicator
   flags) and `ProtocolFundingSource.FUNDING_SOURCE_TYPE_CODE` are worth a
   schema change to the existing Excel-sourced tables, independent of
   whether an Oracle-direct path is ever built.

## Date last updated

2026-07-31 (initial DataDictionary-driven pass for the Protocol/IRB
domain, built to mirror `KUALI_ARCHIVE_COVERAGE.md`'s Award methodology;
first documented confirmation that `archive.irb_*` is Excel/Parquet-sourced
rather than Oracle-direct, and that the full `ProtocolPerson` roster —
not just the PI — is currently unarchived).
