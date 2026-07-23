# Protocol Archive Implementation Plan

## Protocol child parent resolution

Protocol children do not share a universally safe parent key. Each slice must
select `NUMBER_SEQUENCE`, `DIRECT_PROTOCOL_ID`, or `OWNER_CHAIN` from measured
source evidence before implementation.

Personnel uses `NUMBER_SEQUENCE`: resolve `protocol_id` from the unique
`(PROTOCOL_NUMBER, SEQUENCE_NUMBER)` version, preserve the Oracle child
`PROTOCOL_ID` as `source_protocol_id`, report source-ID differences, and reject
missing or ambiguous tuples. Other child domains retain separate future
decisions; the Personnel rule must not be copied without reviewing their
measured evidence.

## Purpose and constraints

Protocol Archive is the canonical human-subjects archive. The existing flat
IRB implementation is a deprecated compatibility path and receives no new
features. It remains in place until Protocol reaches feature parity, after
which its removal will be a dedicated cleanup milestone.

The implementation must:

- preserve every historical Protocol source row;
- use `PROTOCOL_NUMBER` as the stable business identifier;
- preserve physical `PROTOCOL_ID` and `SEQUENCE_NUMBER`;
- retain protocol-level personnel, funding, submissions and reviews, custom
  data, status, expiration, and lead unit only where the source contract is
  verified;
- use local Oracle extraction and approved CSV files; AWS must not connect to
  BU Oracle;
- leave the generic attachment checkpoint unchanged unless a later Protocol
  integration requires a small backward-compatible change; and
- avoid inferred Oracle columns, relationships, and current-record rules.

This document is an inventory and implementation plan only. It does not
authorize attachment or deployment changes. The Protocol Core vertical slice
described below is now authorized by the verified source contract.

# Canonical Archive Model

## Identity hierarchy

```text
Protocol Family
--------------------
Business Identifier

PROTOCOL_NUMBER

        ↓

Protocol Version
--------------------
Business Version

SEQUENCE_NUMBER

        ↓

Physical Oracle Row
--------------------
Primary Key

PROTOCOL_ID
```

## Rules

1. `PROTOCOL_NUMBER` identifies the protocol family.
2. Every `SEQUENCE_NUMBER` belongs to exactly one `PROTOCOL_NUMBER`.
3. Every Oracle `PROTOCOL` row has a unique `PROTOCOL_ID`.
4. The archive stores every physical Oracle row.
5. Latest-version views are derived from archive data.
6. `protocol_base`, latest-only logic, and `record_id` are not primary archive
   identities.
7. Archive first. Views second. API third. UI fourth.

## Architecture

```text
archive.protocol_version
        ↓
archive.v_protocol_latest
        ↓
archive.v_protocol_family
        ↓
API
        ↓
React
```

## Protocol Core implementation contract

The authoritative sources for this slice are:

- `reference/kc/ojb/ProtocolOJB.xml`, which confirms the Protocol business
  object mappings, physical keys, and the three parent references;
- `KCOEUS.PROTOCOL`, preserving `PROTOCOL_ID`, `PROTOCOL_NUMBER`,
  `SEQUENCE_NUMBER`, `DOCUMENT_NUMBER`, `ACTIVE`, type/status codes, title,
  description, four confirmed business dates, FDA/reference fields, create
  and update audit fields, `VER_NBR`, and `OBJ_ID`;
- `KCOEUS.PROTOCOL_DOCUMENT`, joined by exact `DOCUMENT_NUMBER`, for
  `PROTOCOL_WORKFLOW_TYPE` and `REROUTED_FLAG`;
- `KCOEUS.PROTOCOL_STATUS`, joined by `PROTOCOL_STATUS_CODE`, for its
  description; and
- `KCOEUS.PROTOCOL_TYPE`, joined by `PROTOCOL_TYPE_CODE`, for its description.

All enrichment joins are left joins. The PostgreSQL parent is
`archive.protocol_version`, keyed and idempotently upserted by the exact
physical `protocol_id`. `protocol_number` remains unchanged as the business
identifier; no substring-derived base replaces it. Protocol API and UI work
remain deferred. V004–V010 and the Excel-backed loader, API, views, `/irb`
routes, UI, and global search are deprecated compatibility paths until
Protocol reaches feature parity. Their retirement requires a separate cleanup
milestone.

## KC Protocol Aggregate Model

### Authority and aggregate boundary

`reference/kc/ojb/ProtocolOJB.xml` contains 65 class descriptors.
`Protocol` (`PROTOCOL`) is the aggregate root. `PROTOCOL_ID` is the physical
primary key for one exact version row, `PROTOCOL_NUMBER` is its unchanged
business identifier, and `SEQUENCE_NUMBER` identifies its source sequence.
Children carrying `PROTOCOL_ID` or `PROTOCOL_ID_FK` belong to that exact
Protocol version. Repeated `PROTOCOL_NUMBER` and `SEQUENCE_NUMBER` values on
children are preserved verification fields, not substitutes for the physical
parent key.

`ProtocolDocument`, `ProtocolStatus`, and `ProtocolType` are root references,
not version children. Lookup/configuration descriptors are dependencies, not
Protocol-owned transactional rows. The OJB model establishes expected
relationships; BU Oracle metadata must still verify physical objects and
columns before extraction.

### Hierarchy

```text
Protocol [PROTOCOL]
├── ProtocolDocument [PROTOCOL_DOCUMENT]
│   ├── CustomAttributeDocValue [external descriptor]
│   └── DocumentNextvalue [external infrastructure]
├── ProtocolStatus [PROTOCOL_STATUS]
├── ProtocolType [PROTOCOL_TYPE]
├── ProtocolPersons [PROTOCOL_PERSONS]
│   ├── ProtocolUnits [PROTOCOL_UNITS]
│   └── ProtocolAttachmentPersonnel [PROTOCOL_ATTACHMENT_PERSONNEL]
├── ProtocolFundingSources [PROTOCOL_FUNDING_SOURCE]
├── ProtocolResearchAreas [PROTOCOL_RESEARCH_AREAS]
├── ProtocolReferences [PROTOCOL_REFERENCES]
├── ProtocolRiskLevels [PROTOCOL_RISK_LEVELS]
├── ProtocolParticipants [PROTOCOL_VULNERABLE_SUB]
├── ProtocolLocations [PROTOCOL_LOCATION]
├── ProtocolSpecialReviews [PROTOCOL_SPECIAL_REVIEW]
│   └── ProtocolSpecialReviewExemptions [PROTOCOL_EXEMPT_NUMBER]
├── ProtocolAttachmentProtocol [PROTOCOL_ATTACHMENT_PROTOCOL]
├── ProtocolNotepad [PROTOCOL_NOTEPAD]
├── ProtocolActions [PROTOCOL_ACTIONS]
│   ├── ProtocolCorrespondence [PROTOCOL_CORRESPONDENCE]
│   └── IRBProtocolNotification [PROTOCOL_NOTIFICATION]
├── ProtocolSubmissions [PROTOCOL_SUBMISSION]
│   ├── ProtocolExemptStudiesCheckListItem [PROTOCOL_EXEMPT_CHKLST]
│   ├── ProtocolExpeditedReviewCheckListItem
│   │   [PROTOCOL_EXPIDITED_CHKLST]
│   ├── ProtocolReviewers [PROTOCOL_REVIEWERS]
│   │   └── ProtocolOnlineReviews [PROTOCOL_ONLN_RVWS]
│   │       ├── ProtocolOnlineReviewDocument
│   │       │   [PROTOCOL_ONLN_RVW_DOCUMENT]
│   │       └── ProtocolReviewAttachment [REVIEWER_ATTACHMENTS]
│   ├── ProtocolReviewAttachment [REVIEWER_ATTACHMENTS]
│   └── ProtocolSubmissionDoc [PROTOCOL_SUBMISSION_DOC]
└── ProtocolAmendRenewals [PROTO_AMEND_RENEWAL]
    └── ProtocolAmendRenewModules [PROTO_AMEND_RENEW_MODULES]
```

`PROTOCOL_SUBMISSION_V` is the `ProtocolSubmissionLite` read model. It should
not be archived as another transactional child when `PROTOCOL_SUBMISSION` is
available.

### Root, references, and direct children

`VER_NBR`, `UPDATE_TIMESTAMP`, `UPDATE_USER`, and `OBJ_ID` are version/audit
fields wherever listed below. Create fields and other version fields are
called out separately.

| Entity | Oracle table | Parent | Primary key | Parent/reference FK | Business identifier | Version fields | In sequence |
|---|---|---|---|---|---|---|---|
| Protocol | `PROTOCOL` | Root | `PROTOCOL_ID` | `DOCUMENT_NUMBER`, `PROTOCOL_STATUS_CODE`, `PROTOCOL_TYPE_CODE` | `PROTOCOL_NUMBER` | `SEQUENCE_NUMBER`, common audit, `CREATE_TIMESTAMP`, `CREATE_USER` | Root version |
| ProtocolDocument | `PROTOCOL_DOCUMENT` | Referenced by Protocol | `DOCUMENT_NUMBER` | Protocol carries `DOCUMENT_NUMBER` | `DOCUMENT_NUMBER` | Common audit | No; document-scoped |
| ProtocolStatus | `PROTOCOL_STATUS` | Lookup | `PROTOCOL_STATUS_CODE` | Protocol carries status code | Status code | Common audit | No |
| ProtocolType | `PROTOCOL_TYPE` | Lookup | `PROTOCOL_TYPE_CODE` | Protocol carries type code | Type code | Common audit | No |
| ProtocolPerson | `PROTOCOL_PERSONS` | Protocol | `PROTOCOL_PERSON_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `PERSON_ID`/`ROLODEX_ID` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolFundingSource | `PROTOCOL_FUNDING_SOURCE` | Protocol | `PROTOCOL_FUNDING_SOURCE_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `FUNDING_SOURCE` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolResearchArea | `PROTOCOL_RESEARCH_AREAS` | Protocol | `ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `RESEARCH_AREA_CODE` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolReference | `PROTOCOL_REFERENCES` | Protocol | `PROTOCOL_REFERENCE_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; reference number/key | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolRiskLevel | `PROTOCOL_RISK_LEVELS` | Protocol | `PROTOCOL_RISK_LEVELS_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `RISK_LEVEL_CODE` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolParticipant | `PROTOCOL_VULNERABLE_SUB` | Protocol | `PROTOCOL_VULNERABLE_SUB_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; vulnerable-subject code | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolLocation | `PROTOCOL_LOCATION` | Protocol | `PROTOCOL_LOCATION_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; organization/rolodex ID | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolSpecialReview | `PROTOCOL_SPECIAL_REVIEW` | Protocol | `PROTOCOL_SPECIAL_REVIEW_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `SPECIAL_REVIEW_NUMBER` | Common audit; no sequence column in descriptor | Yes by Protocol ID |
| ProtocolAttachmentProtocol | `PROTOCOL_ATTACHMENT_PROTOCOL` | Protocol | `PA_PROTOCOL_ID` | `PROTOCOL_ID_FK` | `PROTOCOL_NUMBER`; `DOCUMENT_ID` | `SEQUENCE_NUMBER`, `ATTACHMENT_VERSION`, common audit, `CREATE_TIMESTAMP` | Yes |
| ProtocolNotepad | `PROTOCOL_NOTEPAD` | Protocol | `PROTOCOL_NOTEPAD_ID` | `PROTOCOL_ID_FK` | `PROTOCOL_NUMBER`; `ENTRY_NUMBER` | `SEQUENCE_NUMBER`, common audit, create fields | Yes |
| ProtocolAction | `PROTOCOL_ACTIONS` | Protocol | `PROTOCOL_ACTION_ID` | `PROTOCOL_ID`; optional `SUBMISSION_ID_FK` | `PROTOCOL_NUMBER`; `ACTION_ID` | `SEQUENCE_NUMBER`, common audit, create fields | Yes |
| ProtocolSubmission | `PROTOCOL_SUBMISSION` | Protocol | `SUBMISSION_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `SUBMISSION_NUMBER` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolAmendRenewal | `PROTO_AMEND_RENEWAL` | Protocol | `PROTO_AMEND_RENEWAL_ID` | `PROTOCOL_ID` | `PROTOCOL_NUMBER`; `PROTO_AMEND_REN_NUMBER` | `SEQUENCE_NUMBER`, common audit | Yes |

### Nested children

| Entity | Oracle table | Parent | Primary key | Parent/Protocol FK | Business identifier | Version fields | In sequence |
|---|---|---|---|---|---|---|---|
| ProtocolUnit | `PROTOCOL_UNITS` | ProtocolPerson | `PROTOCOL_UNITS_ID` | `PROTOCOL_PERSON_ID`; direct Protocol ID is commented out | `PROTOCOL_NUMBER`; `UNIT_NUMBER`, `PERSON_ID` | `SEQUENCE_NUMBER`, common audit | Yes through person |
| ProtocolAttachmentPersonnel | `PROTOCOL_ATTACHMENT_PERSONNEL` | Protocol/person association | `PA_PERSONNEL_ID` | `PROTOCOL_ID_FK`; person reference uses `PERSON_ID` | `PROTOCOL_NUMBER`; `DOCUMENT_ID`, `PERSON_ID` | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolSpecialReviewExemption | `PROTOCOL_EXEMPT_NUMBER` | ProtocolSpecialReview | `PROTOCOL_EXEMPT_NUMBER_ID` | `PROTOCOL_SPECIAL_REVIEW_ID` | Exemption type code | Common audit | Indirect |
| ProtocolCorrespondence | `PROTOCOL_CORRESPONDENCE` | ProtocolAction and Protocol | `ID` | `ACTION_ID_FK`, `PROTOCOL_ID` | `PROTOCOL_NUMBER`; correspondence type | `SEQUENCE_NUMBER`, common audit, create fields | Yes |
| IRBProtocolNotification | `PROTOCOL_NOTIFICATION` | ProtocolAction collection | `NOTIFICATION_ID` | `OWNING_DOCUMENT_ID_FK`; also `DOCUMENT_NUMBER` | `DOCUMENT_NUMBER` | Common audit, create fields | Action/document-scoped; validate semantics |
| ProtocolExemptStudiesCheckListItem | `PROTOCOL_EXEMPT_CHKLST` | Submission and Protocol | `PROTOCOL_EXEMPT_CHKLST_ID` | `SUBMISSION_ID_FK`, `PROTOCOL_ID` | `PROTOCOL_NUMBER`; submission number | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolExpeditedReviewCheckListItem | `PROTOCOL_EXPIDITED_CHKLST` | Submission and Protocol | `PROTOCOL_EXPEDITED_CHKLST_ID` | `SUBMISSION_ID_FK`, `PROTOCOL_ID` | `PROTOCOL_NUMBER`; submission number | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolReviewer | `PROTOCOL_REVIEWERS` | Submission and Protocol | `PROTOCOL_REVIEWER_ID` | `SUBMISSION_ID_FK`, `PROTOCOL_ID` | `PROTOCOL_NUMBER`; reviewer person/rolodex ID | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolOnlineReview | `PROTOCOL_ONLN_RVWS` | Submission, reviewer, Protocol | `PROTOCOL_ONLN_RVW_ID` | `PROTOCOL_ID`, `SUBMISSION_ID_FK`, `PROTOCOL_REVIEWER_FK`, `DOCUMENT_NUMBER` | Parent identifiers | Common audit | Yes through parent keys |
| ProtocolOnlineReviewDocument | `PROTOCOL_ONLN_RVW_DOCUMENT` | Referenced by online review | `DOCUMENT_NUMBER` | Review carries `DOCUMENT_NUMBER` | `DOCUMENT_NUMBER` | Common audit | No; review-document scoped |
| ProtocolReviewAttachment | `REVIEWER_ATTACHMENTS` | Review/submission/Protocol | `REVIEWER_ATTACHMENT_ID` | `PROTOCOL_ONLN_RVW_FK`, `SUBMISSION_ID_FK`, `PROTOCOL_ID_FK` | `ATTACHMENT_ID`; `FILE_ID` | Common audit, create fields | Yes through parent keys |
| ProtocolSubmissionDoc | `PROTOCOL_SUBMISSION_DOC` | Submission and Protocol | `SUBMISSION_DOC_ID` | `SUBMISSION_ID_FK`, `PROTOCOL_ID` | `PROTOCOL_NUMBER`; submission/document number | `SEQUENCE_NUMBER`, common audit | Yes |
| ProtocolAmendRenewModule | `PROTO_AMEND_RENEW_MODULES` | ProtocolAmendRenewal | `PROTO_AMEND_RENEW_MODULES_ID` | `PROTO_AMEND_RENEWAL_ID` | `PROTOCOL_NUMBER`; renewal number/module code | Common audit | Indirect |

The descriptor also lists abstainers, recusers, and committee schedule minutes
under submissions, but their class descriptors are external to this file.

### Lookup and configuration dependencies

| Area | Lookup/reference tables |
|---|---|
| Personnel | `PROTOCOL_PERSON_ROLES`, `AFFILIATION_TYPE`, `ROLODEX`, `PROTOCOL_PERSON_ROLE_MAPPING`; external Unit/Sponsor references |
| Research, risk, participants | `VULNERABLE_SUBJECT_TYPE`, `PROTOCOL_REFERENCE_TYPE`; external ResearchArea and RiskLevel |
| Location and funding | `PROTOCOL_ORG_TYPE`; external Organization, Rolodex, and FundingSourceType |
| Special review | External SpecialReviewType, SpecialReviewApprovalType, and ExemptionType |
| Submission and review | `SUBMISSION_TYPE`, `SUBMISSION_TYPE_QUALIFIER`, `SUBMISSION_STATUS`, `PROTOCOL_REVIEW_TYPE`, `PROTOCOL_REVIEWER_TYPE`, `EXEMPT_STUDIES_CHECKLIST`, `EXPEDITED_REVIEW_CHECKLIST`, `PROTOCOL_ONLN_RVW_STATUS`, `PROTOCOL_ONLN_RVW_DETERM_RECOM`; external Committee/Schedule/Motion references |
| Actions and correspondence | `PROTOCOL_ACTION_TYPE`, `PROTO_CORRESP_TYPE`, `CORRESPONDENT_TYPE` |
| Attachments | `PROTOCOL_ATTACHMENT_STATUS`, `PROTOCOL_ATTACHMENT_TYPE`, `PROTOCOL_ATTACHMENT_GROUP`, `PROTOCOL_ATTACHMENT_TYPE_GROUP` |
| Amendments | `PROTOCOL_MODULES` |

Configuration-only descriptors include `VALID_PROTO_ACTION_CORESP`,
`VALID_PROTO_ACTION_ACTION`, `VALID_PROTO_SUB_REV_TYPE`,
`VALID_PROTO_SUB_TYPE_QUAL`, `PROTO_CORRESP_TEMPL`,
`BATCH_CORRESPONDENCE`, `BATCH_CORRESPONDENCE_DETAIL`,
`PROTO_NOTIFICATION_TEMPL`, `ORGANIZATION_CORRESPONDENT`, and
`UNIT_CORRESPONDENT`. They are not Protocol transactional rows.

### Relationship and dependency graph

```text
PROTOCOL_DOCUMENT ─┐
PROTOCOL_STATUS ───┼─→ PROTOCOL
PROTOCOL_TYPE ─────┘

PROTOCOL
├─→ PROTOCOL_PERSONS ─→ PROTOCOL_UNITS
├─→ PROTOCOL_FUNDING_SOURCE
├─→ PROTOCOL_RESEARCH_AREAS
├─→ PROTOCOL_REFERENCES
├─→ PROTOCOL_RISK_LEVELS
├─→ PROTOCOL_VULNERABLE_SUB
├─→ PROTOCOL_LOCATION
├─→ PROTOCOL_SPECIAL_REVIEW ─→ PROTOCOL_EXEMPT_NUMBER
├─→ PROTOCOL_ACTIONS
│   ├─→ PROTOCOL_CORRESPONDENCE
│   └─→ PROTOCOL_NOTIFICATION (owning-document semantics to validate)
├─→ PROTOCOL_SUBMISSION
│   ├─→ PROTOCOL_EXEMPT_CHKLST
│   ├─→ PROTOCOL_EXPIDITED_CHKLST
│   ├─→ PROTOCOL_REVIEWERS ─→ PROTOCOL_ONLN_RVWS
│   │                         └─→ REVIEWER_ATTACHMENTS
│   └─→ PROTOCOL_SUBMISSION_DOC
└─→ PROTO_AMEND_RENEWAL ─→ PROTO_AMEND_RENEW_MODULES
```

`ProtocolPerson.attachmentPersonnels` uses inverse `personId`, while the
personnel attachment also has `PROTOCOL_ID_FK`. It must not be joined to
`PROTOCOL_PERSON_ID` without Oracle validation.

### Implementation order and scope

1. **Protocol Core:** `PROTOCOL`, `PROTOCOL_DOCUMENT`, `PROTOCOL_STATUS`, and
   `PROTOCOL_TYPE`.
2. **Personnel and units:** personnel/affiliation/role references,
   `PROTOCOL_PERSONS`, then `PROTOCOL_UNITS`.
3. **Scientific and funding profile:** funding, research areas, references,
   risk levels, vulnerable subjects, locations, special reviews, then special
   review exemptions.
4. **Workflow:** submissions before checklists/reviewers/online reviews/review
   attachments/submission documents; actions before correspondence and
   notifications; amendment/renewal before modules.
5. **Document custom data:** only after the external custom-attribute
   descriptor and BU physical schema are verified.
6. **Attachments:** Protocol and personnel attachments remain under the paused
   attachment checkpoint.

Every phase loads verified lookups first, then the exact Protocol parent row,
then direct children, then nested children. Physical primary keys and exact
Protocol foreign keys are preserved throughout.

Deferred/non-transactional objects include `PROTOCOL_SUBMISSION_V`,
document-next-value infrastructure, correspondence and notification
templates, batch/validation configuration, and organization/unit
correspondents unless a later archive requirement explicitly needs them.

## 1. Existing assets that are usable

### Award reference architecture

The Award implementation provides the preferred end-to-end shape:

- V011 through V014 define a physical version parent and child tables using
  Oracle source identifiers.
- `archive.award_version` preserves `award_id`, `award_number`, and
  `sequence_number`.
- V012 permits multiple physical rows in one business sequence.
- V013 selects one deterministic primary current row without deleting other
  source rows.
- Child tables retain their physical primary key and reference `award_id`.
- `etl/load_awards_from_csv.py` normalizes headers, validates required columns
  and values, converts types, applies migrations, calculates current flags,
  loads in one transaction, and verifies counts and relationships.
- `AwardArchiveRepository` uses `JdbcClient` for family search, current
  workspace data, paginated sequence summaries, physical sequence rows, and
  current-version children.
- `AwardArchiveService` normalizes identifiers, handles not-found behavior,
  and constructs paginated responses.
- `AwardArchiveController` exposes a list, workspace, history, sequence, and
  child-resource endpoints.
- `AwardFamiliesPage` and `AwardHistoryPage` provide a searchable family list,
  lazy child tabs, paginated history, and sequence detail.
- Award API URLs encode the business identifier, React Query keys include the
  business identifier and page/selection, and the backend uses zero-based
  pagination.

These are reusable conventions. Award-specific concepts such as award
sequence status, amount rows, sponsor data, or multiple physical Award rows
per sequence must not be copied unless Protocol source evidence supports them.

### Existing Protocol/IRB database assets

V004 through V010 contain two generations of IRB storage:

- `archive.irb_protocol` and `archive.irb_protocol_stage` represent one
  curated/current row per `protocol_base`, linked to
  `archive.research_record`.
- `archive.load_irb` loads the current-row model and rejects duplicate
  `protocol_base` values.
- `archive.v_irb_search` exposes the current-row model.
- `archive.irb_protocol_version` preserves one row per `protocol_id`, including
  `protocol_number`, `sequence_number`, title, type, status, PI summary,
  workflow dates, expiration, and other composite fields.
- `archive.irb_submission`, `archive.irb_funding_source`, and
  `archive.irb_timeline_event` reference `protocol_id`.
- V008 supplies staging tables for the composite loader.
- V009 and V010 build `archive.v_global_search` from both the current and
  historical models.

The historical tables contain useful archived data and must remain available
until a new load is validated and the API/UI cutover is complete. They are not
an adequate final destination contract.

### Existing Protocol/IRB ETL assets

Two separate ingestion paths exist:

1. `transform/irb.py`, `validate/irb.py`, and `load_from_s3.py` load a
   current-record extract into V004/V005.
2. `run_composite_export.py`, `transform/irb_composite.py`, and
   `load_composite_from_s3.py` transform a `PROTOCOL_COMPOSITE` workbook into
   Protocol, submission, funding, and timeline Parquet datasets, upload them,
   stage them, and replace the historical tables.

The composite transform explicitly preserves one row per historical
`protocol_id`. Its deduplication comment and child references confirm that
`protocol_id` is the physical version key in that source contract.

Useful transformation logic includes header normalization, `YYYYMMDD` date
parsing, numeric conversion, funding-column unpivoting, timeline derivation,
duplicate Protocol ID validation, child-orphan checks, row-count checks, and
transactional replacement.

### Existing API and UI assets

Usable components include:

- `IrbArchiveRepository`, which already uses `JdbcClient` and zero-based
  `PageResponse` pagination for family and historical lists;
- existing Protocol workspace DTO fields for dates, status, type, PI summary,
  submissions, funding, and timeline;
- shared error handling, response conventions, Material UI components,
  React Query, routing, date/null formatting, loading states, and empty states;
- `IrbArchiveTabs`, which provides navigation among the three current IRB
  pages; and
- the existing `/irb` navigation location, which can be preserved during
  route migration or redirected to the new `/protocols` contract if a route
  rename is approved.

## 2. Incomplete or inconsistent areas

### Database

- `archive.irb_protocol` enforces one row per `protocol_base`, while
  `archive.irb_protocol_version` preserves history by `protocol_id`.
- `protocol_base`, `study_id`, `record_id`, and `protocol_number` are used as
  competing identifiers.
- The requested business identifier is `PROTOCOL_NUMBER`, but current family,
  workspace, and global-search SQL group or join primarily by
  `protocol_base`.
- V004 omits fields present in the older pre-migration
  `sql/schema/006_irb.sql`, including review, expiration, and responsible-unit
  concepts. That SQL file is not proof that those omitted fields were loaded.
- `sequence_number` is nullable in the historical table even though complete
  sequence history now requires an explicit validation policy.
- No Protocol personnel or custom-data destination exists.
- Funding is stored as a description derived from fifteen wide composite
  columns, not a verified Oracle funding entity with a preserved physical
  primary key.
- Submission uniqueness is constructed from several descriptive/code fields
  rather than a preserved source submission primary key.
- Timeline events are derived data, not physical Oracle entities.
- No deterministic current-version marker exists for Protocol families.
- Existing tables generally lack Oracle audit fields such as
  `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, and `OBJ_ID`.

### ETL

- The current-record and composite loaders overlap and can disagree.
- Both operational loaders read from S3, while the established Award/Proposal
  pattern is a repeatable local CSV loader into PostgreSQL.
- `run_composite_export.py` uploads automatically after transforming, which
  couples local data preparation to an external mutation.
- The historical loader truncates production tables but does not implement
  the Award-style family/current selection contract.
- The source workbook contains useful confirmed fields, but it is a denormalized
  report and does not establish Oracle primary keys or joins for personnel,
  custom data, lead unit, or a physical review entity.
- Existing validation treats `protocol_base` as the family key and therefore
  does not prove that `protocol_number` is stable across all sequence rows.

### API

Three API paths overlap:

- `IrbController` uses JPA through `IrbPersistenceAdapter` for the V004 current
  model.
- `IrbArchiveController` uses `JdbcClient` directly for historical families
  and rows.
- `IrbWorkspaceController` uses another `JdbcClient` repository and accepts an
  archive `record_id`.

This differs from the Award Repository → Service → Controller convention.
Workspace lookup begins with `record_id`, then joins current and history using
`protocol_base`. Family/history endpoints use `protocol_base` and
`protocol_id`. There are no focused Award-style Protocol repository, service,
or controller tests in `api/src/test`.

### UI

- `/irb`, `/irb/families`, and `/irb/history` expose three overlapping lists.
- Current detail uses `/irb/record/:recordId`; historical detail uses
  `/irb/history/:protocolId`.
- The workspace combines a V004 summary with the latest V007 historical row,
  so a request is not identified by the required business identifier.
- Funding, submissions, and timeline are loaded eagerly inside one workspace
  response rather than as independently testable lazy tabs.
- The Documents tab is a placeholder and is outside this reimplementation
  scope while attachment development is paused.
- Some UI list keys fall back to array indexes, and tab labels contain
  hard-coded archive counts.
- There are no configured UI tests; `package.json` provides lint and build
  commands only.

### Global search

- `archive.v_global_search` is Protocol/IRB-specific despite the generic name.
- It groups history by `protocol_base` and returns both `record_id` and
  `protocol_id`.
- The API DTO is shaped around Protocol fields and does not define a stable
  module-specific navigation identifier contract.
- React routes non-Proposal results to `/irb/history/{protocolId}`, regardless
  of the returned module.
- The current view is also consumed by `InvestigatorRepository`, so replacing
  it requires compatibility testing.

## 3. What should be preserved

- All physical `PROTOCOL_ID` rows and their `SEQUENCE_NUMBER` values.
- `PROTOCOL_NUMBER` as text, including leading zeroes.
- `protocol_base`, `study_id`, `document_number`, and CRC identifiers as
  secondary/source identifiers where present, not as the new primary family
  key.
- Confirmed composite fields and derived timeline semantics while their
  provenance is clearly labeled.
- Existing status/type codes alongside descriptions.
- Approval, expiration, closure, authorization, received, claimed, and
  determination dates already present in the confirmed composite contract.
- Existing historical data during a side-by-side migration and validation
  period.
- Zero-based page responses, request validation, 404 behavior, `JdbcClient`,
  constructor injection, and existing exception conventions.
- Existing `/irb` bookmarks through redirects or compatibility routes during
  cutover.
- Investigator search behavior until its replacement query is verified.
- The completed attachment framework checkpoint without changes.

## 4. What should be replaced

- The split current/JPA, history/JdbcClient, and workspace/JdbcClient API with
  one Protocol repository, service, controller, and DTO package.
- `protocol_base`-centric family selection with `protocol_number`-centric
  family selection.
- V004 current-row dependence and record-ID workspace lookup with a
  deterministic current row selected from the physical Protocol version
  archive.
- Automatic composite transformation/upload as the primary repeatable load
  path with explicit approved CSV exports and a local transactional loader.
- The three overlapping IRB list/detail experiences with one Protocol family
  list and one business-key workspace with history and lazy child tabs.
- The Protocol-shaped global-search DTO/view with a module-neutral identifier
  contract, while retaining compatibility for Investigator queries until they
  are migrated.

Existing V004–V010 objects should not be dropped in the first migration.
Removal or compatibility views should be considered only after data parity,
API tests, UI validation, and deployment cutover.

## 5. Confirmed Oracle and CSV source contracts

### Confirmed by repository evidence

The `PROTOCOL_COMPOSITE` workbook contract currently confirms these exported
fields:

- identifiers: `protocol_id`, `protocol_base`, `protocol_number`,
  `sequence_number`, `document_number`, `crc_protocol_num`;
- core data: `title`, active indicator, Protocol type code/description,
  Protocol status code/description, OHRP categories, summary keywords;
- PI summary: PI ID, email, affiliation code/description;
- organization-like report fields: fund center number and school number;
- workflow owners: IRB analyst ID and IRB advisor ID;
- dates: received, claimed, determination, approval, expiration, closure, and
  authorization;
- expiration/storage and duration fields;
- repeated funding descriptions `funding_src1` through `funding_src15`;
- submission number, type, status, event type, and review type fields; and
- repeated modification-request and PI-response dates used to derive timeline
  rows.

The older current-record CSV contract additionally confirms `study_id`, PI
first/last/full name, PI BUID, and a PI-BUID-missing indicator.

The attachment checkpoint separately confirms Oracle Protocol and Protocol
personnel attachment sources, but attachment integration is intentionally
paused and is not part of the initial Protocol reimplementation.

### Not confirmed by repository evidence

No checked-in Protocol Oracle descriptor, physical column inventory, or
normalized Protocol extraction SQL exists. The repository does not yet prove:

- the physical personnel table, its primary key, roles, or sequence join;
- the physical funding table/key and its relationship to Protocol versions;
- submission and review physical primary keys and whether reviews are separate
  entities;
- the custom-data table, custom-attribute definitions, or keys;
- the lead-unit column/table/join;
- source audit columns for these entities;
- whether `protocol_number` is identical across every row that belongs to one
  sequence family; or
- the authoritative deterministic current-row precedence beyond sequence
  number and physical `protocol_id`.

These contracts must be verified before adding the corresponding columns,
tables, or joins. Fund center and school are preserved report fields but must
not be relabeled as lead unit without source evidence.

## 6. PostgreSQL destination design

Use additive V2 tables so the current archive remains operational during
validation. Recommended logical shape:

1. `archive.protocol_version`
   - one row per physical `protocol_id`;
   - `protocol_id` is the primary key;
   - `protocol_number` is the family/business identifier;
   - `sequence_number` is preserved;
   - confirmed source codes, descriptions, dates, identifiers, and audit
     fields are stored without collapsing history;
   - a deterministic `is_primary_current` marker is calculated only after the
     precedence rule is validated; and
   - indexes support `(protocol_number, sequence_number DESC, protocol_id
     DESC)`, current family lookup, status, expiration, lead unit, and PI
     searches where those fields are confirmed.

2. Child tables, created only after their contracts are verified:
   - `archive.protocol_person`;
   - `archive.protocol_funding_source`;
   - `archive.protocol_submission`;
   - `archive.protocol_review` if review is a distinct source entity;
   - `archive.protocol_custom_data`; and
   - optionally `archive.protocol_timeline_event` as explicitly derived data.

Every physical child table should preserve its Oracle primary key, reference
the owning physical `protocol_id`, and retain `protocol_number` and
`sequence_number` when supplied for verification and efficient querying.
Codes and descriptions should be kept together. Oracle audit/version/object
fields should be retained where verified.

Do not enforce uniqueness on `(protocol_number, sequence_number)` until source
validation proves it is unique. Following Award V012, the design should allow
multiple physical source rows per business sequence and use deterministic
ranking rather than deletion.

## 7. ETL implementation plan

1. Establish read-only Oracle metadata and extraction contracts for the parent
   and each requested child entity. Do not infer missing entities from the
   composite report.
2. Produce explicit CSV contracts with exact filenames, headers, primary keys,
   parent keys, code/description joins, audit fields, nullable fields, and load
   order.
3. Create `etl/load_protocols_from_csv.py` following
   `load_awards_from_csv.py`:
   - normalize headers to lowercase snake case;
   - validate files, columns, required values, and duplicate physical keys;
   - preserve text identifiers and leading zeroes;
   - convert numeric and date/timestamp columns explicitly;
   - validate `protocol_id` uniqueness and child parent existence;
   - verify the `protocol_number`/sequence family relationship;
   - apply migrations before the load transaction;
   - truncate only the new Protocol V2 tables;
   - load parent before children in one transaction;
   - calculate the deterministic current marker from a documented rule;
   - log source and loaded row counts; and
   - verify counts, duplicate keys, orphan rows, family counts, sequence
     coverage, and idempotent reload results.
4. Keep the old S3/current/composite loaders available but mark them legacy
   after parity is proven. Do not silently redirect them into the V2 schema.

Recommended load order is Protocol versions, personnel, funding, submissions,
reviews, custom data, and derived timeline. Empty approved child CSVs should
load successfully using the established Negotiation/Subaward convention.

## 8. API changes

Create one Award-style stack:

- `ProtocolArchiveRepository` using `JdbcClient`;
- `ProtocolArchiveService`;
- `ProtocolArchiveController`; and
- DTOs under a single Protocol DTO package.

Recommended business-key endpoints:

- `GET /api/protocols/families`
- `GET /api/protocols/{protocolNumber}`
- `GET /api/protocols/{protocolNumber}/history`
- `GET /api/protocols/{protocolNumber}/history/{sequenceNumber}`
- `GET /api/protocols/{protocolNumber}/people`
- `GET /api/protocols/{protocolNumber}/funding`
- `GET /api/protocols/{protocolNumber}/submissions`
- `GET /api/protocols/{protocolNumber}/reviews`
- `GET /api/protocols/{protocolNumber}/custom-data`

Only expose child endpoints whose destination/source contracts are verified.
History must paginate sequence summaries and provide physical rows for a
selected sequence. Current child queries must join through the selected
physical `protocol_id`, so older-version children cannot leak into the current
workspace.

Preserve old `/api/irb` endpoints during a compatibility window or replace
them with explicit redirects only where HTTP semantics permit. Do not keep JPA
for the new Protocol archive merely to preserve the old implementation.

## 9. UI changes

Build one coherent Protocol experience:

- `/protocols` for family search/list;
- `/protocols/:protocolNumber` for the workspace.

The workspace should follow Award layout and behavior but use Protocol content:

- General: identifiers, title, type, status, lead unit, dates, expiration, and
  other confirmed parent fields;
- People: current physical version personnel;
- Funding: current physical version funding;
- Submissions/Reviews: current and/or historical context explicitly labeled;
- Custom Data: verified custom attributes;
- Timeline: derived events with source provenance;
- History: server-paginated sequences and selectable physical rows.

Child tabs should load lazily, React Query keys must include
`protocolNumber` and relevant page/sequence values, and route changes must
reset local tab/page/selection state. Identifiers in URLs must use
`encodeURIComponent`. Loading, empty, error, and pagination states should
match Award. Stable source keys must be used for rows.

Keep `/irb` routes as temporary redirects or compatibility pages until the new
workspace is deployed. Remove hard-coded counts from `IrbArchiveTabs`; obtain
counts from backend dashboard APIs or omit them.

## 10. Global search changes

Create a migration that rebuilds global search against the validated Protocol
V2 tables and uses `protocol_number` as the navigation identifier.

The search contract should return a module-neutral stable identifier plus an
optional physical source identifier. For Protocol results:

- module: `PROTOCOL` (or retain `IRB` temporarily with an explicit compatibility
  mapping);
- identifier: `protocol_number`;
- physical identifier: current `protocol_id` when useful;
- searchable fields: Protocol number, legacy secondary identifiers, title,
  status/type, personnel, funding, lead unit, submissions/reviews, and custom
  data only where verified.

React should route Protocol results to
`/protocols/${encodeURIComponent(identifier)}` only for the Protocol module.
`InvestigatorRepository` must be migrated or given a compatibility view in the
same cutover because it currently queries `archive.v_global_search`.

## 11. Migration sequence

Assuming V020 remains the completed attachment checkpoint, use subsequent
versions without modifying V004–V010:

1. **V021 — Protocol V2 parent and verified child tables.**
   Include only fields backed by finalized Oracle/CSV contracts.
2. **V022 — Protocol indexes/current-selection support.**
   This may be folded into V021 if the current precedence and source
   multiplicity are verified before implementation.
3. Load and verify the new archive side by side with V004–V010.
4. **V023 — Protocol/global-search compatibility views.**
   Cut global search and Investigator queries over only after parity tests.
5. API and UI cutover with temporary `/irb` compatibility routes.
6. A later cleanup migration may retire obsolete objects only after production
   validation and explicit approval. It must not be part of the first slice.

Migration numbers must be rechecked immediately before implementation in case
another checkpoint claims V021 or later.

## 12. Test strategy

### Migration and ETL

- Assert parent and child physical primary-key uniqueness.
- Assert no missing required `protocol_id`, `protocol_number`, or
  `sequence_number` values under the approved contract.
- Compare CSV and PostgreSQL row counts for every dataset.
- Assert child `protocol_id` foreign keys have no orphans.
- Prove all source sequence rows are retained, including multiple physical rows
  in one sequence if present.
- Verify deterministic current selection and exactly one primary current row
  per `protocol_number`.
- Test missing/duplicate required values, invalid dates/numbers, approved empty
  datasets, transaction rollback, and two identical reloads.
- Compare V2 family/version counts and representative samples against the
  existing historical archive before cutover.

### Repository and service

- Repository tests for family search, current selection, sequence pagination,
  physical sequence ordering, current-version child isolation, empty child
  collections, and not-found behavior.
- Explicitly prove that personnel/funding/submission rows attached only to an
  older `protocol_id` do not appear as current children.
- Service tests for normalization, zero-based pagination, size caps, response
  construction, and errors.

### Controller

- MockMvc tests for all routes, validation boundaries, encoded Protocol
  numbers, 404 responses, JSON contracts, empty arrays, and route
  non-conflicts.
- Compatibility-route tests while `/api/irb` remains available.

### UI

- Add a test runner before relying on UI component tests; none is currently
  configured.
- Test family search/navigation, encoded identifiers, lazy tabs, stale-state
  resets, query keys, loading/error/empty states, zero-based pagination, and
  stable row keys.
- Run `npm run lint` and `npm run build`.

### Global search

- Repository tests for Protocol-number navigation identifiers and searches
  across current and historical fields.
- Verify no duplicate family results.
- Verify non-Protocol modules are not routed as Protocol.
- Regression-test Investigator queries before replacing the old view.

## Recommended second implementation slice

After Protocol Core has been exported, loaded, and validated, the next slice
is `PROTOCOL_PERSONS` plus `PROTOCOL_UNITS`.

Before implementation, verify their exact Oracle tables, physical primary
keys, `PROTOCOL_ID`/sequence relationships, roles, unit fields, lookup joins,
and audit columns. Then add their extraction SQL, CSV contracts, migration,
transactional ETL, lazy API endpoints, and People/Units tabs. Do not infer PI
or lead-unit values from the current composite report when normalized Oracle
source fields are available.
