# Protocol Action CSV Contract

Source: `KCOEUS.PROTOCOL_ACTIONS`

CSV filename: `protocol_actions.csv`

```text
protocol_action_id,action_id,source_protocol_id,protocol_number,sequence_number,submission_number,submission_id_fk,protocol_action_type_code,comments,prev_submission_status_code,submission_type_code,prev_protocol_status_code,source_create_timestamp,source_create_user,source_update_timestamp,source_update_user,action_date,actual_action_date,source_version_number,source_object_id,followup_action_code
```

Primary key: `protocol_action_id`, sourced from
`PROTOCOL_ACTION_ID`.

Parent strategy: exclusively `NUMBER_SEQUENCE`. The validated source has
903,796 rows, 903,796 unique tuple parents, zero missing tuple parents, and
zero ambiguous tuple parents. Direct Protocol IDs differ from the resolved
historical version for 773,324 rows.

The loader preserves `source_protocol_id` and `submission_id_fk` as source
metadata. It does not use either value for parent resolution and has no
fallback path.

Required fields are `protocol_action_id`, `source_protocol_id`,
`protocol_number`, and `sequence_number`. All remaining business and audit
fields are nullable according to the descriptor.
