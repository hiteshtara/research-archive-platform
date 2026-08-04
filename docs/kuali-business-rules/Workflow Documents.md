# Workflow Documents

## The rule

`AWARD.MODIFICATION_NUMBER` is **not** the Kuali workflow document
number, despite the name resemblance and despite an earlier version of
this project wiring the API's `documentNumber` field to it. The real
workflow document identifier is `AWARD.DOCUMENT_NUMBER` — a foreign key
into `KREW_DOC_HDR_T.DOC_HDR_ID`, both `VARCHAR2(40)` on BU's real
schema. `modification_number` is a separate, ordinary Award business
field with no relationship to the workflow engine at all, and is
frequently `NULL` (confirmed: `NULL` across every sequence of a real
sampled Award, `100567-00001`).

`workflow_document_number` is confirmed globally unique across every
archived Award version — 26,930 of 26,930 rows, no collisions — and is
never synthesized: it stays `NULL` if Oracle genuinely has no matching
value, rather than being backfilled or guessed.

Every other domain with its own workflow document concept (Budget, Time
& Money, Proposal, Subaward, Negotiation) has been confirmed to carry
its own separate `DOCUMENT_NUMBER` column following this exact same
pattern — this is a repeating shape across the whole Kuali schema, not
an Award-specific quirk.

## Why this matters

This identifier space has six distinct concepts that are easy to
conflate: `award_number` (family), `award_id` (version-row surrogate
key), `sequence_number` (business version ordinal),
`workflow_document_number` (real KEW engine ID), `modification_number`
(unrelated Award field), and `transaction_id` (which means two different
things across two different Time & Money tables — see [Time and
Money](Time%20and%20Money.md)). Wiring the wrong one to a user-facing
field is a silent, plausible-looking bug: both `modification_number` and
`workflow_document_number` are strings that look like document
identifiers, and only one of them actually is.

## Evidence

- `V055__add_award_workflow_document_number.sql`,
  `V048__add_time_and_money_columns_to_award_amount_info.sql`.
- Real Award `100567-00001`: `modification_number` confirmed `NULL`
  across every sequence, `workflow_document_number` populated and
  unique.

## See also

[`docs/architecture/AWARD_IDENTIFIER_MODEL.md`](../architecture/AWARD_IDENTIFIER_MODEL.md)
for the complete six-identifier model and the API rename this finding
drove (`documentNumber` re-sourced to the real value,
`modificationNumber` added separately for the old, unrelated data). See
also [Award Versions](Award%20Versions.md) for the `award_number`/
`award_id`/`sequence_number` half of this same identifier space.
