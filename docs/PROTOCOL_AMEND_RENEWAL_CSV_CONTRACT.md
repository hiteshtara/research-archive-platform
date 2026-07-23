# Protocol Amendment/Renewal CSV Contract

Source: `KCOEUS.PROTO_AMEND_RENEWAL`

CSV filename: `protocol_amend_renewals.csv`

```text
proto_amend_renewal_id,source_protocol_id,protocol_number,sequence_number,proto_amend_ren_number,date_created,summary,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

Primary key: `proto_amend_renewal_id`.

Parent strategy: strictly `NUMBER_SEQUENCE`. The focused BU investigation
confirmed 16,569 unique tuple parents, zero missing parents, and zero
ambiguous parents. All 16,569 direct Protocol IDs differ from the resolved
historical parent.

The loader preserves `source_protocol_id` as audit metadata. It has no direct
ID or owner-chain fallback.

Required fields are `proto_amend_renewal_id`, `source_protocol_id`,
`protocol_number`, and `sequence_number`. Business and audit fields may be
nullable.
