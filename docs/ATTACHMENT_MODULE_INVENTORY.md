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
3. verified module-specific PostgreSQL archive destination.

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
| Proposal | Verified | Direct `FILE_DATA_ID` verified | Missing | Blocked only on destination |
| Award | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified | Missing | Oracle-to-S3 implemented |
| Negotiation | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified | Missing | Oracle-to-S3 implemented |
| IRB | Protocol and personnel sources verified | Direct `ATTACHMENT_FILE.FILE_ID` verified for both | Missing | Protocol implemented; personnel blocked on destination |

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
Only the repository destination contract is blocked.

The Proposal plugin is not registered because the repository has no
module-specific PostgreSQL attachment metadata/archive table or synchronization
contract. The CLI rejection must not describe the Oracle table or BLOB join as
unverified.

Remaining work:

1. Define and review a Proposal attachment CSV export contract.
2. Add a module-specific PostgreSQL metadata/archive migration.
3. Add repeatable metadata ETL and verification.
4. Implement the plugin against that confirmed destination.

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
`CONTENT_TYPE`. An Award-specific PostgreSQL destination remains missing, so
`--sync-postgres` is disabled.

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
A Negotiation-specific PostgreSQL destination remains missing.

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
verified. The existing IRB protocol plugin reads
`ATTACHMENT_FILE.FILE_DATA`; filename and MIME type come from `FILE_NAME` and
`CONTENT_TYPE`.

The personnel source is not implemented because no IRB-specific PostgreSQL
attachment metadata/archive destination exists. Implementing it before that
contract is defined would require inventing its destination schema. The
existing protocol plugin remains unchanged and its `--sync-postgres` path is
also unavailable for the same destination reason.

## CLI behavior

Subaward, Award, Negotiation, and IRB are registered. Proposal remains blocked
only by its missing PostgreSQL destination contract.

Example:

```bash
uv run --project etl python etl/archive_attachments.py --module proposal
```

Expected reason: the Proposal Oracle source and direct `FILE_DATA_ID` join are
verified, but the module-specific PostgreSQL destination is missing.

No dry-run or upload commands are provided for blocked modules.

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
