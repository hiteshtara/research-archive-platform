# Attachment Module Inventory

## Scope and evidence levels

This inventory covers only BU-supported archive modules:

- Subaward
- Award
- Proposal
- Negotiation
- IRB

It excludes IACUC, S2S, templates, lookup tables, and unused KC modules.
Oracle extraction remains local to a BU-managed computer on the VPN.

Each module is evaluated independently for:

1. verified physical Oracle attachment source;
2. verified relationship to the binary BLOB source;
3. verified PostgreSQL archive destination.

A verified Oracle table does not imply that its `FILE_ID` points directly to
`KCOEUS.FILE_DATA`. No such relationship is assumed without direct evidence.

Legacy attachment storage is now confirmed:

```text
KCOEUS.ATTACHMENT_FILE.FILE_ID
KCOEUS.ATTACHMENT_FILE.FILE_NAME
KCOEUS.ATTACHMENT_FILE.CONTENT_TYPE
KCOEUS.ATTACHMENT_FILE.FILE_DATA
KCOEUS.ATTACHMENT_FILE.FILE_DATA_ID
```

Award, Negotiation, and IRB read `ATTACHMENT_FILE.FILE_DATA` directly through
`FILE_ID`; they do not look up `KCOEUS.FILE_DATA`.

## Summary

| Module | Oracle source | Binary relationship | PostgreSQL destination | Plugin status |
|---|---|---|---|---|
| Subaward | Verified | Direct `FILE_DATA_ID` verified | Verified | Implemented |
| Proposal | Verified | Direct `FILE_DATA_ID` verified | Generic V020 destination | Implemented |
| Award | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified | Generic V020 destination | Implemented |
| Negotiation | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified | Generic V020 destination | Implemented |
| IRB | Protocol and personnel sources verified | Direct `ATTACHMENT_FILE.FILE_ID` verified for both | Generic V020 destination | Both implemented |

V020 adds `archive.archived_attachment` for Award, Proposal, Negotiation,
IRB protocol, and IRB personnel. Its typed columns hold the common archive
contract, while `source_metadata` preserves source-specific identifiers and
attributes. The uniqueness key is `(module_code, source_attachment_id)`.
Subaward continues to use its V019 destination and existing API/UI contract.

## Subaward

### Confirmed Oracle contract

- Attachment table: `KCOEUS.SUBAWARD_ATTACHMENTS`
- Parent table: `KCOEUS.SUBAWARD`
- Binary source:
  `SUBAWARD_ATTACHMENTS.FILE_DATA_ID = FILE_DATA.ID`
- BLOB column: `KCOEUS.FILE_DATA.DATA`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `ATTACHMENT_ID` |
| Record ID | `SUBAWARD_ID` |
| Business key | `SUBAWARD_CODE` |
| Sequence number | `SEQUENCE_NUMBER` |
| Document ID | `DOCUMENT_ID` |
| Filename | `FILE_NAME` |
| File data ID | `FILE_DATA_ID` |
| MIME type | `MIME_TYPE` |
| Source update timestamp | `UPDATE_TIMESTAMP` |
| Last update timestamp | `LAST_UPDATE_TIMESTAMP` |
| Status | `DOCUMENT_STATUS_CODE` |

### Repository contracts

- Extraction: `oracle/subaward/export_subaward_attachments.sql`
- CSV: `subaward_attachments.csv`
- Metadata table: `archive.subaward_attachment` from V018
- Archived-file table: `archive.subaward_attachment_archive` from V019
- ETL: `etl/load_subawards_from_csv.py`
- Binary plugin:
  `etl/archive_etl/attachments/plugins/subaward.py`
- API and UI expose attachment metadata and download availability.

Subaward remains implemented and unchanged.

## Proposal

### Confirmed Oracle contract

- Attachment table: `KCOEUS.PROPOSAL_ATTACHMENTS`
- Parent table: `KCOEUS.PROPOSAL`
- Binary source:
  `PROPOSAL_ATTACHMENTS.FILE_DATA_ID = FILE_DATA.ID`
- BLOB column: `KCOEUS.FILE_DATA.DATA`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `PROPOSAL_ATTACHMENTS_ID` |
| Record ID | `PROPOSAL_ID` |
| Business key | `PROPOSAL_NUMBER` |
| Sequence number | `SEQUENCE_NUMBER` |
| Document ID | `ATTACHMENT_NUMBER` |
| Title | `ATTACHMENT_TITLE` |
| Filename | `FILE_NAME` |
| File data ID | `FILE_DATA_ID` |
| MIME type | `CONTENT_TYPE` |
| Source update timestamp | `UPDATE_TIMESTAMP` |
| Last update timestamp | `LAST_UPDATE_TIMESTAMP` |
| Status | `DOCUMENT_STATUS_CODE` |

### Repository evidence

- Existing Proposal extraction covers versions and people, but has no
  attachment CSV export.
- V015 and V016 contain no Proposal attachment metadata or archived-file
  destination table.
- Proposal ETL, repository, DTOs, and UI have no attachment contract.

### Status

The Oracle source and direct `FILE_DATA_ID` relationship are fully verified.
The Proposal plugin is registered and synchronizes archived manifests into
the generic V020 destination. It reads `KCOEUS.FILE_DATA.DATA` through
`FILE_DATA_ID`.

Remaining work:

1. Define and review a Proposal attachment CSV export contract.
2. Add repeatable metadata ETL and verification.
3. Add API/UI attachment metadata and download behavior when approved.

## Award

### Confirmed Oracle contract

- Attachment table: `KCOEUS.AWARD_ATTACHMENT`
- Parent table: `KCOEUS.AWARD`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `AWARD_ATTACHMENT_ID` |
| Record ID | `AWARD_ID` |
| Business key | `AWARD_NUMBER` |
| Sequence number | `SEQUENCE_NUMBER` |
| Document ID | `DOCUMENT_ID` |
| File reference | `FILE_ID` |
| Description | `DESCRIPTION` |
| Source update timestamp | `UPDATE_TIMESTAMP` |
| Last update timestamp | `LAST_UPDATE_TIMESTAMP` |
| Status | `DOCUMENT_STATUS_CODE` |

### Repository evidence

- Existing Award extraction covers versions, amounts, people, and proposals,
  but has no attachment export.
- V011 through V014 contain no Award attachment metadata/archive table.
- Award ETL, repository, DTOs, and UI have no attachment contract.

### Status and missing information

The Award plugin is implemented using `AWARD_ATTACHMENT.FILE_ID` to read
`ATTACHMENT_FILE.FILE_DATA`, with filename and MIME type from `FILE_NAME` and
`CONTENT_TYPE`. Manifest synchronization uses the generic V020 destination.

## Negotiation

### Confirmed Oracle contract

- Attachment table: `KCOEUS.NEGOTIATION_ATTACHMENT`
- Candidate parent table: `KCOEUS.NEGOTIATION_ACTIVITY`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `ATTACHMENT_ID` |
| Parent activity ID | `ACTIVITY_ID` |
| Description | `DESCRIPTION` |
| Restricted flag | `RESTRICTED` |
| File reference | `FILE_ID` |
| Source update timestamp | `UPDATE_TIMESTAMP` |

### Repository evidence

- The OJB descriptor also identifies `NEGOTIATION_ATTACHMENT`, but direct BU
  `DESCRIBE` output now confirms its physical existence.
- Existing Negotiation extraction and V017 omit attachments.
- Negotiation ETL, repository, DTOs, and UI have no attachment contract.

### Status and missing information

The Negotiation plugin uses the existing verified activity relationship to
resolve the owning Negotiation and reads `ATTACHMENT_FILE.FILE_DATA` through
`FILE_ID`. Filename and MIME type come from `FILE_NAME` and `CONTENT_TYPE`.
Manifest synchronization uses the generic V020 destination. The activity and
restriction identifiers remain in `source_metadata`.

The inventory must not describe `NEGOTIATION_ATTACHMENT` as physically
unverified.

## IRB

### Confirmed Oracle contract

- Protocol attachment table:
  `KCOEUS.PROTOCOL_ATTACHMENT_PROTOCOL`
- Parent record: protocol through `PROTOCOL_ID_FK`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `PA_PROTOCOL_ID` |
| Record ID | `PROTOCOL_ID_FK` |
| Business key | `PROTOCOL_NUMBER` |
| Sequence number | `SEQUENCE_NUMBER` |
| Document ID | `DOCUMENT_ID` |
| File reference | `FILE_ID` |
| Description | `DESCRIPTION` |
| Status | `STATUS_CD` |
| Source update timestamp | `UPDATE_TIMESTAMP` |
| Created timestamp | `CREATE_TIMESTAMP` |
| Attachment version | `ATTACHMENT_VERSION` |
| Document status | `DOCUMENT_STATUS_CODE` |

### Confirmed IRB personnel attachment contract

- Personnel attachment table:
  `KCOEUS.PROTOCOL_ATTACHMENT_PERSONNEL`
- Parent record: protocol through `PROTOCOL_ID_FK`
- Binary source:
  `PROTOCOL_ATTACHMENT_PERSONNEL.FILE_ID = ATTACHMENT_FILE.FILE_ID`

| Archive field | Oracle column |
|---|---|
| Attachment ID | `PA_PERSONNEL_ID` |
| Record ID | `PROTOCOL_ID_FK` |
| Business key | `PROTOCOL_NUMBER` |
| Sequence number | `SEQUENCE_NUMBER` |
| Type code | `TYPE_CD` |
| Document ID | `DOCUMENT_ID` |
| File reference | `FILE_ID` |
| Description | `DESCRIPTION` |
| Person ID | `PERSON_ID` |
| Source update timestamp | `UPDATE_TIMESTAMP` |

The verified `ATTACHMENT_FILE` enrichment and payload fields are:

- `FILE_NAME`
- `CONTENT_TYPE`
- `FILE_DATA`
- `FILE_DATA_ID`
- `SEQUENCE_NUMBER`
- `UPDATE_TIMESTAMP`

### Repository evidence

- Current IRB ingestion does not contain a verified attachment CSV.
- IRB Flyway migrations contain no IRB-specific attachment metadata/archive
  destination.
- `source_file_name` in IRB staging describes an ETL input file, not a
  protocol attachment.
- The older `sql/schema/008_documents.sql` generic document concept is not an
  IRB-specific Flyway destination and has no confirmed mapping here.
- IRB repositories, DTOs, and UI have no attachment contract.

### Status and missing information

Both IRB source tables and their direct `ATTACHMENT_FILE.FILE_ID` joins are
verified. The IRB protocol and personnel plugins read
`ATTACHMENT_FILE.FILE_DATA`; filename and MIME type come from `FILE_NAME` and
`CONTENT_TYPE`.

Both plugins synchronize into V020 with separate module codes:
`IRB_PROTOCOL` and `IRB_PERSONNEL`. Protocol attachment version and status
fields, and personnel `PERSON_ID` and `TYPE_CD`, remain in `source_metadata`.

## CLI behavior

Subaward, Award, Proposal, Negotiation, IRB protocol (`irb`), and IRB
personnel (`irb-personnel`) are registered. Each generic module supports
`--sync-postgres`; this applies migrations and idempotently upserts its local
manifest without contacting Oracle or S3.

Confirmed Subaward dry run:

```bash
uv run --project etl python etl/archive_attachments.py \
  --module subaward \
  --subaward-id 94202 \
  --limit 10 \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix test/subawards \
  --dry-run
```
