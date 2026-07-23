# Protocol Funding CSV Contract

## Source and evidence

Source: `KCOEUS.PROTOCOL_FUNDING_SOURCE`

CSV filename: `protocol_funding.csv`

The completed BU parent-resolution analysis measured 43,405 rows:

- 43,213 direct-ID parents matched the child number and sequence;
- 192 direct-ID sequence mismatches were found;
- mismatch rate: 0.4423%;
- no unresolved number/sequence parent was found in the measured result.

Because the mismatch rate is not zero, Protocol Funding uses
`NUMBER_SEQUENCE`. The loader resolves `protocol_id` from the unique
`(protocol_number, sequence_number)` archive version and preserves the Oracle
`PROTOCOL_ID` as `source_protocol_id`. It never falls back to the source ID.

## Header

```text
protocol_funding_source_id,source_protocol_id,protocol_number,sequence_number,funding_source_type_code,funding_source_number,funding_source_name,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

Primary key: `protocol_funding_source_id`

Resolved parent: `protocol_id` →
`archive.protocol_version.protocol_id`

Required source values:

- `protocol_funding_source_id`
- `source_protocol_id`
- `protocol_number`
- `sequence_number`

Nullable business fields:

- `funding_source_type_code`
- `funding_source_number`
- `funding_source_name`

Audit fields:

- `source_update_timestamp`
- `source_update_user`
- `source_version_number`
- `source_object_id`

No lookup table is joined in this slice because its scope is limited to
`PROTOCOL_FUNDING_SOURCE`.

## Load order

1. `archive.protocol_version`
2. `archive.protocol_funding`

Missing or ambiguous number/sequence parents reject the load. A differing
`source_protocol_id` is counted and reported but does not reject the row.
