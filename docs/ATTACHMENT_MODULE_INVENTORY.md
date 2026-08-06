# Attachment Module Inventory

## Scope and evidence levels

This inventory covers BU-supported archive modules with an attachment
contract:

- Subaward
- Award
- Proposal
- Negotiation

It excludes IACUC, S2S, templates, lookup tables, and unused KC modules.
Oracle extraction remains local to a BU-managed computer on the VPN.

**Protocol (removed).** A "Protocol" attachment module previously existed
here, covering `KCOEUS.PROTOCOL_ATTACHMENT_PROTOCOL`/
`PROTOCOL_ATTACHMENT_PERSONNEL` via the `etl/archive_etl/attachments/plugins/irb.py`
plugin (module codes `IRB_PROTOCOL`/`IRB_PERSONNEL` — named after Kuali's IRB
functional module, not this repo's separate legacy IRB domain). It has been
removed along with the rest of Protocol Archive; see `docs/DECISIONS.md`.
**The already-archived `archive.archived_attachment` rows with module codes
`IRB_PROTOCOL`/`IRB_PERSONNEL` are kept in place** for historical
compatibility — they are not purged, and the shared `module_code` CHECK
constraint from V020 is not narrowed to exclude them. No new ingestion into
those module codes is possible since the plugin is gone; this is a closed,
historical data set, not an active module. See the "Protocol (historical)"
section below for the archived contract detail.

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

Award and Negotiation read `ATTACHMENT_FILE.FILE_DATA` directly through
`FILE_ID`; they do not look up `KCOEUS.FILE_DATA`. The removed Protocol
plugins did the same (see "Protocol (historical)" below).

## Summary

| Module | Oracle source | Binary relationship | PostgreSQL destination | Plugin status |
|---|---|---|---|---|
| Subaward | Verified | Direct `FILE_DATA_ID` verified | Verified | Implemented |
| Proposal | Verified | Direct `FILE_DATA_ID` verified | Generic V020 destination | Implemented |
| Award | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified | Generic V020 destination | Implemented |
| Negotiation | Verified | Direct `ATTACHMENT_FILE.FILE_ID` verified — but see "BLOB retention gap" below: only 8.1% of rows have retrievable content | Generic V020 destination | Implemented, full population loaded 2026-08-06 |
| Protocol (historical) | Protocol and personnel sources verified | Direct `ATTACHMENT_FILE.FILE_ID` verified for both | Generic V020 destination | Removed — rows retained, no new ingestion |

V020 adds `archive.archived_attachment` for Award, Proposal, and Negotiation
(plus historical Protocol/Protocol-personnel rows under module codes
`IRB_PROTOCOL`/`IRB_PERSONNEL` — see above). Its typed columns hold the
common archive contract, while `source_metadata` preserves source-specific
identifiers and attributes. The uniqueness key is
`(module_code, source_attachment_id)`. Subaward continues to use its V019
destination and existing API/UI contract.

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

- The OJB descriptor also identifies `NEGOTIATION_ATTACHMENT` (nested under
  `NegotiationActivity`, not `Negotiation` itself — see
  `repository-negotiation.xml`), confirmed physically present.
- `oracle/negotiation/export_negotiation_attachments.sql` (added 2026-08-06)
  is the extraction query, joined through `NEGOTIATION_ACTIVITY` to resolve
  the owning `negotiation_id` (the attachment row has no `negotiation_id` of
  its own). `etl/fetch_negotiation_attachment_metadata.py` runs it into the
  CSV shape the generic plugin expects — Negotiation never had a manual
  SQL*Plus export step; this automates it.
- Negotiation ETL, repository (`NegotiationArchiveRepository.findAttachments`),
  DTO (`NegotiationAttachmentResponse`), and UI (attachments grouped by
  Activity in `NegotiationWorkspacePage`) all have a working attachment
  contract as of 2026-08-06.

### Status

The Negotiation plugin uses the verified activity relationship to resolve
the owning Negotiation and reads `ATTACHMENT_FILE.FILE_DATA` through
`FILE_ID`. Filename and MIME type come from `FILE_NAME` and `CONTENT_TYPE`.
Manifest synchronization uses the generic V020 destination. The activity ID,
restriction flag, and `UPDATE_USER` remain in `source_metadata` (added
`UPDATE_USER`/`sourceUpdateUser` 2026-08-06 — it exists in Oracle but had
never been extracted before).

### BLOB retention gap (found during the 2026-08-06 full-population load)

A full load of all 28,923 `NEGOTIATION_ATTACHMENT` rows found that **only
2,342 (8.1%) have retrievable binary content** — the other 26,581 have a
real `ATTACHMENT_FILE` row (confirmed: 0 dangling `FILE_ID` references) but
`FILE_DATA IS NULL`. This is a source-side Oracle fact, not an ETL bug:
verified with `SELECT COUNT(*) FROM NEGOTIATION_ATTACHMENT att JOIN
ATTACHMENT_FILE af ON af.FILE_ID = att.FILE_ID WHERE af.FILE_DATA IS NOT
NULL` (2,342) vs. `WHERE af.FILE_DATA IS NULL` (26,581), and by confirming
zero rows have no `ATTACHMENT_FILE` row at all. The 2,342 rows with real
content cluster at the high end of the `FILE_ID` range (24046–39280,
i.e. the most recent negotiations) — consistent with an Oracle-side
retention/cleanup policy that strips old BLOB content while keeping the
metadata row. `archive.archived_attachment` reflects this honestly:
`archive_status = 'ARCHIVED'` for the 2,342 with content, `'MISSING'` for
the other 26,581 (0 `'FAILED'` — every upload attempt that had real content
succeeded, 0 checksum mismatches). The API's `downloadable` flag on
`NegotiationAttachmentResponse` is derived from `archive_status = 'ARCHIVED'`,
so the UI correctly shows "Not available" rather than a broken download link
for the 92% with no content.

A related orchestration bug was found and fixed in the same load:
`scripts/run-negotiation-loader.sh` originally chained the upload and
`--sync-postgres` steps with `&&`, and the upload step's exit code is
non-zero whenever any attachment has a missing/failed blob (true for
this domain by design, not just at the margins) — so `--sync-postgres`
never ran, silently discarding metadata for every attachment that *did*
upload successfully. Fixed to chain with `;` instead, so sync always runs
regardless of how many blobs were missing.

## Protocol (historical)

**This module is removed. The plugin code
(`etl/archive_etl/attachments/plugins/irb.py`) and its two runner
registrations (legacy plugin IDs `irb`, `irb-personnel`) no longer exist in
this repository.** The contract below is preserved only to explain the
`IRB_PROTOCOL`/`IRB_PERSONNEL` module codes still present on already-archived
`archive.archived_attachment` rows, which are kept for historical
compatibility and are not purged (see `docs/DECISIONS.md`). No new rows can
be ingested under these module codes.

### Confirmed Oracle contract (as it existed before removal)

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

### Confirmed Protocol personnel attachment contract

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

- Current Protocol ingestion does not contain a verified attachment CSV.
- Existing migrations contain no Protocol-specific attachment
  metadata/archive destination.
- `source_file_name` in legacy IRB staging describes an ETL input file, not a
  protocol attachment.
- The older `sql/schema/008_documents.sql` generic document concept is not an
  Protocol-specific migration destination and has no confirmed mapping here.
- Protocol repositories, DTOs, and UI have no attachment contract.

### Status (before removal)

Both Protocol source tables and their direct `ATTACHMENT_FILE.FILE_ID` joins
were verified. The Protocol and personnel plugins read
`ATTACHMENT_FILE.FILE_DATA`; filename and MIME type came from `FILE_NAME` and
`CONTENT_TYPE`.

Both plugins synchronized into V020 with separate module codes:
`IRB_PROTOCOL` and `IRB_PERSONNEL`. Protocol attachment version and status
fields, and personnel `PERSON_ID` and `TYPE_CD`, remain in `source_metadata`
on the rows that were already archived — those rows and fields are
unaffected by the plugin's removal.

## CLI behavior

Subaward, Award, Proposal, and Negotiation are registered. Each generic
module supports `--sync-postgres`; this applies migrations and idempotently
upserts its local manifest without contacting Oracle or S3. The Protocol
(`irb`) and Protocol personnel (`irb-personnel`) plugin IDs are no longer
registered and will fail with an unknown-module error if invoked.

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
