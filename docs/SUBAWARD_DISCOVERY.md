# Subaward Discovery

## Purpose and evidence

This document defines the discovery baseline for a future Subaward archive. It is based on the official Kuali OJB class descriptors in `reference/kuali/subaward-ojb.xml` and the BU-specific extension included in that descriptor. The descriptor expresses the Kuali business-object model; it does not prove that every object, column, constraint, or data type is present unchanged in BU Oracle.

No separate, checked-in BU Oracle Subaward table-and-column inventory was found during this review. Therefore, the tables and columns below are **expected Oracle objects**, not yet extraction-ready source contracts. Their owner, physical columns, constraints, data types, row counts, and lookup coverage must be checked against the verified BU Oracle inventory before extraction SQL is written.

The descriptor contains the same descriptor repository twice. The inventory below de-duplicates those repeated definitions.

## Object hierarchy

```text
SUBAWARD_DOCUMENT (document/workflow parent)
└── SUBAWARD (versioned Subaward business object)
    ├── SUBAWARD_EXTENSION (BU one-to-one extension)
    ├── SUBAWARD_FUNDING_SOURCE
    ├── SUBAWARD_AMOUNT_INFO
    │   └── SUBAWARD_FFATA_REPORTING
    ├── SUBAWARD_CONTACT
    ├── SUBAWARD_CLOSEOUT
    ├── SUBAWARD_CUSTOM_DATA
    ├── SUBAWARD_ATTACHMENTS
    ├── SUBAWARD_TEMPLATE_ATTACHMENTS
    ├── SUBAWARD_REPORTS
    ├── SUBAWARD_TEMPLATE_INFO
    └── SUBAWARD_NOTIFICATION

Additional Subaward records whose parent relationship is represented by key
columns but is not declared as a root collection in this descriptor:

SUBAWARD_AMT_RELEASED
SUBAWARD_NOTEPAD
SUBAWARD_COMMENT

Module-level form content:

SUBAWARD_FORMS
```

`SUBAWARD` is versioned. `SUBAWARD_ID` identifies a physical version row, while `SUBAWARD_CODE` groups the business-object family and `SEQUENCE_NUMBER` orders its versions. `DOCUMENT_NUMBER` connects a version row to `SUBAWARD_DOCUMENT`. The descriptor also maps `(SUBAWARD_CODE, SEQUENCE_NUMBER)` to Kuali's external version-history business object.

## Parent and child entities

| Entity | Descriptor primary key | Parent foreign key or relationship | Sequence/version relationship |
|---|---|---|---|
| `SUBAWARD_DOCUMENT` | `DOCUMENT_NUMBER` | None; document root | `VER_NBR` is optimistic-lock/audit version |
| `SUBAWARD` | `SUBAWARD_ID` (`SUBAWARD_ID_S`) | `DOCUMENT_NUMBER` → `SUBAWARD_DOCUMENT` | `SUBAWARD_CODE` + `SEQUENCE_NUMBER` identify and order a Subaward family; also references external version history |
| `SUBAWARD_EXTENSION` | `SUBAWARD_ID` | One-to-one `SUBAWARD_ID` → `SUBAWARD` | Same physical version as its parent; only `DATE_RECEIVED` is declared in addition to the key |
| `SUBAWARD_FUNDING_SOURCE` | `SUBAWARD_FUNDING_SOURCE_ID` (`SUBAWARD_FUNDING_SOURCE_ID_S`) | `SUBAWARD_ID` → `SUBAWARD`; `AWARD_ID` → external `AWARD` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_AMOUNT_INFO` | `SUBAWARD_AMOUNT_INFO_ID` (`SUBAWARD_AMT_INFO_ID_S`) | `SUBAWARD_ID` → `SUBAWARD` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_FFATA_REPORTING` | `SUBAWARD_FFATA_REPORTING_ID` (`SUBAWARD_FFATA_REPORTING_ID_S`) | Collected from `SUBAWARD` by `SUBAWARD_ID`; explicit `SUBAWARD_AMOUNT_INFO_ID` → `SUBAWARD_AMOUNT_INFO` | Carries `VER_NBR`; association to a specific amount row must be preserved |
| `SUBAWARD_AMT_RELEASED` | `SUBAWARD_AMT_RELEASED_ID` (`SUBAWARD_AMT_REL_ID_S`) | Expected `SUBAWARD_ID` → `SUBAWARD`, but no OJB reference is declared | Carries `DOCUMENT_NUMBER`, `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_CONTACT` | `SUBAWARD_CONTACT_ID` (`SUBAWARD_CONTACT_ID_S`) | Collected by `SUBAWARD_ID`; lookup references to contact type and Rolodex | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_CLOSEOUT` | `SUBAWARD_CLOSEOUT_ID` (`SUBAWARD_CLOSEOUT_ID_S`) | Collected by `SUBAWARD_ID`; expected `CLOSEOUT_TYPE_CODE` lookup | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `CLOSEOUT_NUMBER`, `VER_NBR` |
| `SUBAWARD_CUSTOM_DATA` | `SUBAWARD_CUSTOM_DATA_ID` (`SUBAWARD_CUST_ID_S`) | Collected by `SUBAWARD_ID`; `CUSTOM_ATTRIBUTE_ID` → external custom attribute | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_NOTEPAD` | `SUBAWARD_NOTEPAD_ID` (`SEQ_SUBAWARD_NOTEPAD_ID`) | Explicit `SUBAWARD_ID` → `SUBAWARD` | Carries `SUBAWARD_CODE`, `ENTRY_NUMBER`, `VER_NBR`; no `SEQUENCE_NUMBER` is declared |
| `SUBAWARD_COMMENT` | `SUBAWARD_COMMENT_ID` (`SEQ_SUBAWARD_COMMENT_ID`) | Expected `SUBAWARD_ID` → `SUBAWARD`; `COMMENT_TYPE_CODE` → external comment type | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_ATTACHMENTS` | `ATTACHMENT_ID` (`SEQ_SUBAWARD_ATTACHMENT_ID`) | Collected by `SUBAWARD_ID`; `ATTACHMENT_TYPE_CODE` → `SUBAWARD_ATTACHMENT_TYPE` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_TEMPLATE_ATTACHMENTS` | `ATTACHMENT_ID` (`SEQ_SUBAWARD_TMPL_ATT_ID`) | Collected by `SUBAWARD_ID`; `ATTACHMENT_TYPE_CODE` → `SUBAWARD_TMPL_ATTACH_TYPE` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_REPORTS` | `SUBAWARD_REPORT_ID` (`SEQ_SUBAWARD_REPORT_ID`) | Collected by `SUBAWARD_ID`; `REPORT_TYPE_CODE` → `SUBAWARD_REPORT_TYPE` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, `VER_NBR` |
| `SUBAWARD_TEMPLATE_INFO` | `SUBAWARD_ID` | One row per parent version by `SUBAWARD_ID` | Carries `SUBAWARD_CODE`, `SEQUENCE_NUMBER`; descriptor declares no `VER_NBR` or `OBJ_ID` |
| `SUBAWARD_NOTIFICATION` | `NOTIFICATION_ID` (`SEQ_NOTIFICATION_ID`) | Collected from `SUBAWARD` through `OWNING_DOCUMENT_ID_FK`; `NOTIFICATION_TYPE_ID` → external notification type | Also carries `DOCUMENT_NUMBER`, `SUBAWARD_CODE`, `VER_NBR` |
| `SUBAWARD_FORMS` | `FORM_ID` | Module-level form/template record; no `SUBAWARD_ID` | `TEMPLATE_TYPE_CODE` → `SUBAWARD_TEMPLATE_TYPE`; carries `VER_NBR` |

Unless noted otherwise, descriptor entities also declare `UPDATE_TIMESTAMP`, `UPDATE_USER`, `VER_NBR`, and `OBJ_ID`. These audit fields are not substitutes for `SEQUENCE_NUMBER`: `SEQUENCE_NUMBER` represents a Subaward business version, while `VER_NBR` supports row-level optimistic locking.

## Lookup references

### Subaward-owned lookup tables

| Table | Descriptor primary key | Referenced from |
|---|---|---|
| `SUBAWARD_STATUS` | `SUBAWARD_STATUS_CODE` | `SUBAWARD.STATUS_CODE` |
| `SUBAWARD_APPROVAL_TYPE` | `SUBAWARD_APPROVAL_TYPE_CODE` | Descriptor defines the lookup but declares no reference from the reviewed Subaward objects |
| `CLOSEOUT_TYPE` | `CLOSEOUT_TYPE_CODE` | Expected from `SUBAWARD_CLOSEOUT.CLOSEOUT_TYPE_CODE`; the descriptor does not declare an OJB reference |
| `SUBAWARD_TEMPLATE_TYPE` | `SUBAWARD_TEMPLATE_TYPE_CODE` | `SUBAWARD_FORMS.TEMPLATE_TYPE_CODE` |
| `SUBCONTRACT_COST_TYPE` | `COST_TYPE_CODE` | `SUBAWARD.COST_TYPE` |
| `SUBCONTRACT_COPYRIGHT_TYPE` | `COPYRIGHT_TYPE_CODE` | Likely `SUBAWARD_TEMPLATE_INFO.COPYRIGHT_TYPE`, but no OJB reference is declared |
| `SUBAWARD_REPORT_TYPE` | `REPORT_TYPE_CODE` | `SUBAWARD_REPORTS.REPORT_TYPE_CODE` |
| `SUBAWARD_ATTACHMENT_TYPE` | `ATTACHMENT_TYPE_CODE` | `SUBAWARD_ATTACHMENTS.ATTACHMENT_TYPE_CODE` |
| `SUBAWARD_TMPL_ATTACH_TYPE` | `ATTACHMENT_TYPE_CODE` | `SUBAWARD_TEMPLATE_ATTACHMENTS.ATTACHMENT_TYPE_CODE` |
| `SUBAWARD_MODIFICATION_TYPE` | descriptor column `code` | `SUBAWARD_AMOUNT_INFO.MODIFICATION_TYPE_CODE` |

### External lookup and reference objects

- Organization from `SUBAWARD.ORGANIZATION_ID`.
- Unit from `SUBAWARD.REQUISITIONER_UNIT`.
- Rolodex from `SUBAWARD.SITE_INVESTIGATOR` and `SUBAWARD_CONTACT.ROLODEX_ID`.
- Award type from `SUBAWARD.SUBAWARD_TYPE_CODE`.
- Award from `SUBAWARD_FUNDING_SOURCE.AWARD_ID`.
- Award contact type from `SUBAWARD_CONTACT.CONTACT_TYPE_CODE`.
- Custom attribute from `SUBAWARD_CUSTOM_DATA.CUSTOM_ATTRIBUTE_ID`.
- Comment type from `SUBAWARD_COMMENT.COMMENT_TYPE_CODE`.
- Notification type from `SUBAWARD_NOTIFICATION.NOTIFICATION_TYPE_ID`.
- Kuali version history from `SUBAWARD_CODE` plus `SEQUENCE_NUMBER`.

The physical table names and keys for external class references are outside this descriptor and must be resolved from the BU Oracle inventory before joins are designed.

## Attachment and file relationships

There are five distinct file-bearing patterns:

1. `SUBAWARD_ATTACHMENTS` stores attachment metadata, including `DOCUMENT_ID`, `FILE_DATA_ID`, `FILE_NAME`, `MIME_TYPE`, status, description, and last-update audit fields. The descriptor does not map a binary payload column or a file-data object.
2. `SUBAWARD_TEMPLATE_ATTACHMENTS` has the same metadata pattern and a separate attachment-type lookup. It also does not map a payload object.
3. `SUBAWARD_AMOUNT_INFO` and `SUBAWARD_FFATA_REPORTING` carry `FILE_DATA_ID`, `FILE_NAME`, and `MIME_TYPE`, but no binary column is declared.
4. `SUBAWARD_AMT_RELEASED` declares an inline binary `DOCUMENT` plus `FILE_NAME` and `MIME_TYPE`.
5. `SUBAWARD_FORMS` declares form content in `FORM` as a CLOB through an OJB blob/CLOB conversion, with `FILE_NAME` and `CONTENT_TYPE`.

Metadata and binary/content extraction must be designed separately. `FILE_DATA_ID` and `DOCUMENT_ID` must be preserved as source identifiers even if payload archival is deferred. The Oracle object that owns external file payloads, the encoding of `FORM`, maximum sizes, inactive/deleted-file behavior, and permitted treatment of sensitive attachments remain unresolved.

## Recommended CSV exports

The names below are planning recommendations, not approved contracts. Each export should retain its source primary key, parent key, `SUBAWARD_CODE`, `SEQUENCE_NUMBER`, audit fields, raw lookup codes, and lookup descriptions where available.

### Version 1: core searchable archive

- `subaward_documents.csv` — `SUBAWARD_DOCUMENT`.
- `subaward_versions.csv` — every `SUBAWARD` version, not only the current row.
- `subaward_extensions.csv` — BU extension data.
- `subaward_funding_sources.csv` — Award associations by source `AWARD_ID`, without inventing archive links.
- `subaward_amount_info.csv` — obligations, anticipated amounts, modifications, and performance periods.
- `subaward_amount_released.csv` — invoice/release metadata; inline binary content should be a separately approved export.
- `subaward_contacts.csv` — contact identifiers and resolved contact-type/Rolodex display values where verified.
- `subaward_closeouts.csv` — closeout milestones and types.
- `subaward_custom_data.csv` — custom attribute IDs, names where verified, and values.
- `subaward_reports.csv` — report assignments and report-type descriptions.
- `subaward_attachments.csv` — attachment metadata only.
- `subaward_template_info.csv` — FDP/template terms and compliance fields.
- Lookup exports for every locally owned lookup table listed above.

### Version 2: secondary content and file payloads

- `subaward_ffata_reporting.csv`.
- `subaward_notepad.csv`, subject to restricted-note authorization.
- `subaward_comments.csv`.
- `subaward_notifications.csv`, including CLOB message content only if approved.
- `subaward_template_attachments.csv`.
- `subaward_forms.csv`.
- Separately packaged attachment, invoice, FFATA, template-attachment, and form payloads after the physical file source and security requirements are validated.

V1 prioritizes the historical Subaward workspace, version history, funding, contacts, closeout, and searchable metadata. V2 contains lower-priority operational/template records and content with greater privacy, size, or binary-handling risk. This split is a discovery recommendation and must be confirmed with archive owners before migration design.

## Recommended load order

1. Subaward-owned lookup tables and required external lookup snapshots.
2. `SUBAWARD_DOCUMENT`.
3. `SUBAWARD`, retaining every sequence and source `SUBAWARD_ID`.
4. `SUBAWARD_EXTENSION` and direct children: funding sources, amount info, amount released, contacts, closeouts, custom data, notepad, comments, reports, attachment metadata, template attachment metadata, template info, and notifications.
5. `SUBAWARD_FFATA_REPORTING`, after both its Subaward parent and amount-info parent exist.
6. `SUBAWARD_FORMS`, which is template-type-dependent but not Subaward-row-dependent.
7. Approved file/content payloads, keyed back to already-loaded metadata.

Child rows must attach to the physical `SUBAWARD_ID` version they came from. They must not be reassigned to the current version merely because they share a `SUBAWARD_CODE`.

## Unresolved Oracle validation items

Before Oracle extraction SQL or an archive schema is created, validate all of the following against BU Oracle:

- Confirm the owner, existence, columns, nullability, lengths, precision/scale, primary keys, indexes, sequences, and physical foreign-key constraints for all 28 de-duplicated tables.
- Confirm whether all descriptor objects contain production rows and whether any BU-only Subaward tables are absent from the descriptor/inventory.
- Confirm that `SUBAWARD_CODE` is the stable family identifier and determine whether `(SUBAWARD_CODE, SEQUENCE_NUMBER)` is unique. Establish deterministic current-version rules using sequence status and audit timestamps.
- Confirm `DOCUMENT_NUMBER` cardinality across `SUBAWARD_DOCUMENT`, `SUBAWARD`, `SUBAWARD_AMT_RELEASED`, and `SUBAWARD_NOTIFICATION`.
- Confirm the physical FK for notifications. The descriptor connects `OWNING_DOCUMENT_ID_FK` to `SUBAWARD_ID`, despite also storing `DOCUMENT_NUMBER`.
- Confirm parent constraints for children whose OJB references are absent or only implied by root collections, especially amount released, closeout, contacts, custom data, comments, attachments, reports, and template info.
- Confirm whether `SUBAWARD_AMT_RELEASED` belongs to every Subaward version and whether its inline `DOCUMENT` should be archived.
- Resolve descriptor type discrepancies: `SUBAWARD.COST_TYPE` is declared `INTEGER` while `SUBCONTRACT_COST_TYPE.COST_TYPE_CODE` is `VARCHAR`; `SUBAWARD_REPORTS.REPORT_TYPE_CODE` is `VARCHAR` while `SUBAWARD_REPORT_TYPE.REPORT_TYPE_CODE` is `INTEGER`; and `SUBAWARD_REPORT_ID` is a sequence-backed `VARCHAR`.
- Verify the literal descriptor column `SUBAWARD_MODIFICATION_TYPE.code` and its actual Oracle case/name. Also validate the stray quote after the `SUBAWARD_AMOUNT_INFO.MODIFICATION_TYPE_CODE` field descriptor.
- Confirm whether `CLOSEOUT_TYPE_CODE`, `COPYRIGHT_TYPE`, and approval-type values have physical lookup constraints even though OJB references are absent.
- Resolve the external physical tables and join keys for Award, Award Type, Organization, Unit, Rolodex, Contact Type, Custom Attribute, Comment Type, Notification Type, and version history.
- Determine whether `AWARD_ID` in funding source should preserve a version-row association or be normalized to an Award family only after source behavior is verified.
- Resolve the semantics of the integer contact fields in `SUBAWARD_TEMPLATE_INFO`; no reference descriptors are declared for them.
- Locate the backing file-data objects for every `FILE_DATA_ID` and `DOCUMENT_ID`; validate inline BLOB/CLOB sizes, encodings, checksums, filenames, MIME types, and access restrictions.
- Determine how inactive, deleted, superseded, restricted, and incomplete records are represented. In particular, define handling for `SUBAWARD_SEQUENCE_STATUS`, attachment document status, modification-type `ACTIVE`, and notepad `RESTRICTED_VIEW`.
- Confirm whether all historical sequences and all audit fields are in archive scope, and whether any records require privacy or records-retention filtering before local CSV export.

Migrations, extraction SQL, ETL, backend, and UI work must wait until these Oracle metadata and scope questions are validated.
