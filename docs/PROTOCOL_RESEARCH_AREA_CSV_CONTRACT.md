# Protocol Research Area CSV Contract

Source: `KCOEUS.PROTOCOL_RESEARCH_AREAS`

CSV filename: `protocol_research_areas.csv`

Header:

```text
protocol_research_area_id,source_protocol_id,protocol_number,sequence_number,research_area_code,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

The OJB contract confirms only `RESEARCH_AREA_CODE` on this physical table.
No description column is selected or invented, and this slice does not join
the external Research Area lookup.

Primary key: `protocol_research_area_id` from Oracle `ID`.

Parent strategy: `NUMBER_SEQUENCE`. Existing BU evidence measured 35,331
rows, 35,142 direct-ID matches, and 189 sequence mismatches (0.5349%).
The focused local rerun query is
`oracle/protocol/validate_protocol_research_area_parent.sql`.

The resolved archive parent is stored as `protocol_id`; the Oracle value is
preserved as `source_protocol_id`. Missing or ambiguous tuple parents fail
without a direct-ID fallback.
