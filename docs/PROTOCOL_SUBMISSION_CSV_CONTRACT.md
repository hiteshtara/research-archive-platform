# Protocol Submission CSV Contract

Source: `KCOEUS.PROTOCOL_SUBMISSION`

CSV filename: `protocol_submissions.csv`

```text
submission_id,source_protocol_id,protocol_number,sequence_number,submission_number,schedule_id,committee_id,submission_type_code,submission_type_qual_code,submission_status_code,schedule_id_fk,committee_id_fk,protocol_review_type_code,submission_date,comments,comm_decision_motion_type_code,yes_vote_count,no_vote_count,abstainer_count,recused_count,voting_comments,is_billable,source_update_timestamp,source_update_user,source_version_number,source_object_id
```

Primary key: `submission_id`, sourced from Oracle `SUBMISSION_ID`.

Parent strategy: `DIRECT_PROTOCOL_ID`. The completed aggregate analysis
measured 221,519 rows with zero direct-ID mismatches. The focused validation
query must be rerun locally against the exact export before promotion.

The loader preserves the original parent as `source_protocol_id`, resolves
the archive parent with the same identifier, and rejects missing archive
parents. Protocol number and sequence are preserved as source audit fields.

Nullable business fields include submission number, schedule and committee
identifiers, type/qualifier/review/status codes, date, comments, decision
motion, vote counts, voting comments, and billable flag. Audit fields are
nullable except for the source identity and parent fields.
