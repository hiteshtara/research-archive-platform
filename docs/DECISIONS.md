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
step in the development order, no CSV contract docs. Award's unit contacts
and Proposal's people had no verified Oracle extraction query; rather than
write unverified Oracle SQL to fill the gap, both features were removed
entirely (API endpoints, repository/service/controller methods, DTOs, UI
tabs/client functions/types, and the `archive.award_unit_contact`/
`archive.proposal_person` tables via `V033__drop_award_unit_contact_and_proposal_person.sql`).
This project is a new build with no production data to preserve, so this
is a straightforward schema/feature removal, not a data-migration concern.
See the removed features' history in this file's version control log if
ever revisiting Award unit contacts or Proposal people with a verified
Oracle extraction query. S3 is retained only for document/attachment binary
storage and legacy IRB's separate Excel/Parquet export pipeline — neither
is affected by this decision. The S3 "data" bucket's `processed/`/
`rejected/` prefixes and the `processed/` lifecycle rule were removed from
Terraform as dead infrastructure (zero
code references anywhere); `landing/`/`validation/` were kept because IRB's
export pipeline actively uses those prefix namespaces.

## Award Comments: archive COMMENT_TYPE as a shared reference table

`AWARD_COMMENT.COMMENT_TYPE_CODE` is a real, Oracle-enforced FK into
`COMMENT_TYPE`. The first Award Comments implementation planned to
denormalize `comment_type_description`/`award_comment_screen_flag`
directly onto every `archive.award_comment` row. That plan was reversed
before implementation: `COMMENT_TYPE` is instead archived once as its
own shared reference entity (`archive.comment_type`,
`V057__create_comment_type.sql`), mirroring the existing
`archive.unit`/`archive.unit_administrator`/
`archive.unit_administrator_type` precedent, with the repository
joining to it at query time. Real BU Oracle data (23 rows) confirmed
only 2 of 23 comment types have `award_comment_screen_flag='Y'`
("General Comments", "Fiscal Report Comments") - everything else is
real archived data but excluded from the Award Comments screen, matching
Kuali's own behavior. See
[`docs/kuali-business-rules/Award Comments.md`](kuali-business-rules/Award%20Comments.md)
and [`docs/architecture/AWARD_COMMENT_DESIGN.md`](architecture/AWARD_COMMENT_DESIGN.md).

A second, independently significant finding from this same feature: the
history-collapsing algorithm for repeated comment text must keep the
**oldest** occurrence of a run of identical values, not the newest - see
[`docs/kuali-business-rules/Comment History.md`](kuali-business-rules/Comment%20History.md)
for why, with a real example spanning 2014-2021.

## Award identifiers: workflow document number vs. modification number

`AWARD.MODIFICATION_NUMBER` is not the Kuali workflow document number —
an earlier investigation this project made that exact mistake and wired
the API's `documentNumber` field to it. The real workflow document
identifier is `AWARD.DOCUMENT_NUMBER`, the foreign key into
`KREW_DOC_HDR_T.DOC_HDR_ID` (both `VARCHAR2(40)` on BU's real schema).
Both fields are now archived separately (`workflow_document_number` and
`modification_number` on `archive.award_version`) and both exposed
separately by the API (`documentNumber` re-sourced to the real value,
`modificationNumber` added for the old data) — see
[`docs/architecture/AWARD_IDENTIFIER_MODEL.md`](architecture/AWARD_IDENTIFIER_MODEL.md)
for the full identifier model (Award Number, Award ID, Sequence Number,
Workflow Document Number, Modification Number, and the Time and Money
`TRANSACTION_ID` naming collision) and migration
`V055__add_award_workflow_document_number.sql`.

## Protocol Archive (rebuilt, Oracle-direct)

The "not license to rebuild" note in the superseded section below has since
been acted on deliberately: a new, independent Protocol Archive
(`archive.protocol_version` / `protocol_person` / `protocol_unit`) was
built on `feature/protocol-oracle-loader`, ETL-only so far (no API/UI). It
is **not** a restoration of the schema below — smaller scope (no derived
views, no unit-administrator table pending a verified Oracle source) — and
it is **additive alongside legacy IRB**, not a replacement for it. See
[`docs/PROTOCOL_ORACLE_LOADER.md`](PROTOCOL_ORACLE_LOADER.md) for the full
architecture, and migration `V034__create_protocol_archive.sql`. The
parent-resolution strategies below (`NUMBER_SEQUENCE`, `OWNER_CHAIN`) carry
forward unchanged into this rebuild — they were re-verified, not
re-derived.

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

## Global Search: minimal semantic search as a strictly-supplemental 6th input

Added `archive.search_embedding` (`V070`) and a 6th `GlobalSearchService`
fan-out branch (pgvector cosine search over Bedrock
`amazon.titan-embed-text-v2:0` embeddings), behind `app.search.semantic.enabled`
(default off everywhere). This followed a PoC (`archive.search_embedding_poc`,
`V069`, kept permanently as the regression benchmark — never reused or
repurposed for production) and a threshold experiment that found no single
global similarity cutoff works across heterogeneous queries, but a hard
Top-5 cutoff gives zero irrelevant results.

Structured search remains strictly authoritative: a semantic result can
never outrank a structured one (`RANK_SEMANTIC` sorts after every
structured tier), and a semantic hit for the same canonical record a
structured branch already returned is dropped, never duplicated.
`GlobalSearchService.deduplicate()`'s key was simplified from
`module:id:sequenceNumber` to `module:id` to make this collapse work —
the embedding table has no live "current sequence number" to report, so
requiring an exact match would silently defeat deduplication whenever the
index lags a reload. Verified safe against the existing dedup tests: within
one domain's own dual lookup paths, both occurrences already resolved to
the same sequence number, so dropping it changed no prior behavior.

An identifier-shape heuristic (`LikelyIdentifierDetector`: pure-numeric or
Award-number-shaped queries) skips the Bedrock call entirely for obvious
exact-identifier searches, since structured search alone is already
sufficient for those. Population (`etl/build_search_embedding.py`) runs
asynchronously as a one-off ECS task, never synchronously with a user's
search request. The API's own ECS task role needed a separate
`bedrock:InvokeModel` IAM grant (`terraform/modules/api_service/main.tf`) —
the loader's existing grant only covers the loader's task role, though the
VPC-wide `bedrock-runtime` interface endpoint added for the PoC already
covers the network path for both.

Explicitly not built: reranking, attachment/comment/custom-data embeddings,
chatbot/RAG. Production population has not been run as of this decision —
it needs a real cost/time estimate from the PoC's own measured Bedrock
throughput before it's run for real (~24,557 records across Award,
Proposal, Negotiation, Subaward, vs. the PoC's 777-row sample).
