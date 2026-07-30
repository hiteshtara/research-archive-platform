# Architectural Decisions

Proposal is the backbone of the archive.

Award derives from Proposal.

Negotiation links to Proposal.

Use JdbcClient.

Use the custom SQL migration runner and `public.schema_migration`.

Use Hexagonal Architecture.

Never duplicate Award logic.

Mirror existing patterns.

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
