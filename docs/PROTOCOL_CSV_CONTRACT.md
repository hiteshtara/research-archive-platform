# Protocol Core CSV Contract

## Export

- Filename: `protocol_versions.csv`
- SQL: `oracle/protocol/export_protocol_versions.sql`
- Parent source: `KCOEUS.PROTOCOL`
- Physical primary key: `protocol_id`
- Business identifier: `protocol_number`
- Version field: `sequence_number`

Exact header:

```text
protocol_id,protocol_number,sequence_number,document_number,active,protocol_type_code,protocol_type_description,protocol_status_code,protocol_status_description,title,description,initial_submission_date,approval_date,expiration_date,last_approval_date,fda_application_number,reference_number_1,reference_number_2,protocol_workflow_type,rerouted_flag,source_create_timestamp,source_create_user,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

## Joins

- `PROTOCOL.DOCUMENT_NUMBER = PROTOCOL_DOCUMENT.DOCUMENT_NUMBER`
- `PROTOCOL.PROTOCOL_STATUS_CODE =
  PROTOCOL_STATUS.PROTOCOL_STATUS_CODE`
- `PROTOCOL.PROTOCOL_TYPE_CODE = PROTOCOL_TYPE.PROTOCOL_TYPE_CODE`

All enrichment joins are `LEFT JOIN`. A missing document, status, or type
lookup must not remove a Protocol row.

## Required values

- `protocol_id`
- `protocol_number`
- `sequence_number`

`protocol_number` must be loaded as text to preserve its exact value and any
leading zeroes. The loader rejects duplicate `protocol_id` rows. It reports,
but does not reject, repeated `(protocol_number, sequence_number)` pairs
because the source contract does not establish that pair as unique.

## Dates and timestamps

Dates:

- `initial_submission_date`
- `approval_date`
- `expiration_date`
- `last_approval_date`

Timestamps:

- `source_create_timestamp`
- `source_update_timestamp`

## Deferred collections

Personnel, units, funding, submissions, actions, reviews, custom data,
attachments, correspondence, locations, risk levels, research areas,
vulnerable subjects, references, and special reviews are not part of this
contract.
