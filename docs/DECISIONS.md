# Architectural Decisions

Proposal is the backbone of the archive.

Award derives from Proposal.

Negotiation links to Proposal.

Use JdbcClient.

Use the custom SQL migration runner and `public.schema_migration`.

Use Hexagonal Architecture.

Never duplicate Award logic.

Mirror existing patterns.

Oracle is the only supported source of structured data. CSV ingestion for
Award/Negotiation/Subaward/Proposal has been retired entirely: no
`SOURCE_MODE`, no `--csv`/`--csv-dir` flags on any loader, no CSV export
step in the development order, no CSV contract docs. Two datasets have no
verified Oracle extraction query and were dropped rather than kept on a CSV
fallback: Award's unit contacts (`archive.award_unit_contact` is truncated
on every load, since it has a FK to `award_version`, but never repopulated
— it goes and stays empty) and Proposal's people (`archive.proposal_person`
has no FK to `proposal_version` and is simply never touched again — existing
rows are frozen at their last-loaded state). Writing unverified Oracle
extraction SQL to fill either gap was explicitly rejected in favor of
leaving the gap visible; see the module docstring comments in
`load_awards_from_csv.py`/`load_proposals_from_csv.py`. S3 is retained only
for document/attachment binary storage and legacy IRB's separate Excel/
Parquet export pipeline — neither is affected by this decision. The S3
"data" bucket's `processed/`/`rejected/` prefixes and the `processed/`
lifecycle rule were removed from Terraform as dead infrastructure (zero
code references anywhere); `landing/`/`validation/` were kept because IRB's
export pipeline actively uses those prefix namespaces.

## Superseded: Protocol Archive (removed)

The decisions below were made while building a "Protocol Archive" module — a
second, independent human-subjects archive intended to eventually replace
legacy IRB. That plan was reversed: **Protocol Archive was removed in full**
(API, UI, ETL loaders/Oracle SQL, and forward-only migration
`V032__drop_protocol_archive.sql`), and **legacy IRB was kept** as the sole
surviving human-subjects/protocol domain. These entries are kept only as a
historical record of the parent-resolution investigation — they do not
describe current architecture, and nothing here should be read as license to
rebuild a second Protocol domain without a fresh decision to do so.

- Protocol Archive was the canonical human-subjects archive. Its identity was
  `PROTOCOL_NUMBER` (family), `SEQUENCE_NUMBER` (business version), and
  `PROTOCOL_ID` (physical Oracle row).
- The legacy flat IRB implementation was considered deprecated, to be
  preserved without new features until Protocol reached feature parity, then
  retired in a dedicated cleanup milestone. (This did not happen — IRB was
  kept and Protocol Archive was removed instead.)
- Protocol child `PROTOCOL_ID` values were found not to be universally
  reliable version parents (see `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`,
  retained as a deprecated but evidence-backed reference — the same
  `PROTOCOL_ID`/`PROTOCOL_NUMBER`/`SEQUENCE_NUMBER` disagreement could
  resurface in any future Oracle extraction touching these tables, including
  IRB's). The measured strategy per child was `NUMBER_SEQUENCE`,
  `DIRECT_PROTOCOL_ID`, or `OWNER_CHAIN`. Personnel used `NUMBER_SEQUENCE`:
  `protocol_id` resolved from `(PROTOCOL_NUMBER, SEQUENCE_NUMBER)`, with the
  original Oracle value retained as `source_protocol_id`.
- Protocol Units used `OWNER_CHAIN`: `PROTOCOL_PERSON_ID` resolved the
  archived person, and the unit inherited that person's resolved
  `protocol_id`. Unit protocol number and sequence were audit evidence, not
  independent parent keys.
