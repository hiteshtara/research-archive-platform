# ETL Runbook

Order (Oracle is the only supported source — see
[`docs/runbooks/ORACLE.md`](ORACLE.md) for the full operator workflow)

Migration

↓

Oracle Extract + Validate (direct, streamed into Postgres)

↓

Verification

There is no CSV export/upload step — CSV ingestion for structured data has
been retired entirely (see [`docs/DECISIONS.md`](../DECISIONS.md)).

-------------------------------------------------------------------------------

Proposal ETL

proposal_versions (Oracle)

award_proposals (Oracle)

↓

proposal_version

proposal_award

proposal_person is not loaded - no verified Oracle extraction query exists,
and this dataset was dropped rather than kept on a CSV fallback. Existing
proposal_person rows are left untouched, not truncated.

