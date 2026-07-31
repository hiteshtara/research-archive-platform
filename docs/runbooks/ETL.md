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

Proposal people (`archive.proposal_person`) had no verified Oracle
extraction query and has been removed entirely (API, UI, ETL, and schema —
see [`docs/DECISIONS.md`](../DECISIONS.md)).

-------------------------------------------------------------------------------

Protocol ETL

`KCOEUS.PROTOCOL` / `PROTOCOL_PERSONS` / `PROTOCOL_UNITS` (Oracle)

↓

`protocol_version` / `protocol_person` / `protocol_unit`

Independent of and additive to legacy IRB — not a replacement, and not the
same schema as the removed Protocol Archive above. ETL only so far; no API
or UI. `protocol_unit_administrator` is out of scope pending a verified
Oracle source. See
[`docs/PROTOCOL_ORACLE_LOADER.md`](../PROTOCOL_ORACLE_LOADER.md) for the
full architecture, reconciliation metrics, and deployment procedure.

