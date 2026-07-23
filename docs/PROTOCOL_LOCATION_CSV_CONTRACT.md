# Protocol Location CSV Contract

Source: `KCOEUS.PROTOCOL_LOCATION`

CSV filename: `protocol_locations.csv`

```text
protocol_location_id,source_protocol_id,protocol_number,sequence_number,protocol_org_type_code,organization_id,rolodex_id,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

The descriptor verifies exactly these physical fields. This slice does not
join Organization, Rolodex, or Protocol Organization Type lookups.

Primary key: `protocol_location_id`.

Parent strategy: `NUMBER_SEQUENCE`. Existing BU evidence measured 40,336
rows, 38,202 direct-ID matches, 1,524 number mismatches, 610 sequence
mismatches, and 1,524 unresolved number/sequence tuples. The direct-ID
mismatch rate was 5.2906%.

The source evidence rules out unrestricted `DIRECT_PROTOCOL_ID`. The loader
preserves `source_protocol_id` and normally resolves the exact tuple with
method `NUMBER_SEQUENCE`.

All 1,524 missing tuples were confirmed as the literal source placeholder
`(PROTOCOL_NUMBER='0', SEQUENCE_NUMBER=0)`. They contain 1,518 distinct
source Protocol IDs, all with valid sequence-zero direct parents present in
Protocol Core. Only these rows use `DIRECT_ID_PLACEHOLDER`. Missing
non-placeholder tuples, ambiguous tuples, or placeholder rows without an
archive direct parent are rejected.
