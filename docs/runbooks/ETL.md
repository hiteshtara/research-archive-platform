# ETL Runbook

Order (Oracle-direct, the default — see
[`docs/runbooks/ORACLE.md`](ORACLE.md) for the full operator workflow)

Migration

↓

Oracle Extract + Validate (direct, streamed into Postgres)

↓

Verification

CSV export/upload is an explicit, non-default fallback (`--csv` /
`SOURCE_MODE=csv`), not a required step in the default workflow.

-------------------------------------------------------------------------------

Proposal ETL

proposal_versions.csv

proposal_people.csv

award_proposals.csv

↓

proposal_version

proposal_person

proposal_award

