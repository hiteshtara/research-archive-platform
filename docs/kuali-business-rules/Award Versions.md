# Award Versions

## The rule

An Award's identity has three layers that must not be collapsed into
one another:

- **`award_number`** — the family. A stable business identifier shared
  by every version of the same Award.
- **`award_id`** — the surrogate primary key of one specific archived
  version-row. Multiple rows can share both `award_number` *and*
  `sequence_number` when `award_id` differs — never assume
  `(award_number, sequence_number)` uniquely identifies a row.
- **`sequence_number`** — a business version ordinal within the family.
  Critically, **the highest `sequence_number` is not always the
  "current" one** — `is_primary_current` is the real, authoritative flag
  Kuali uses to mark which version is current, independent of sequence
  ordering.

Getting this wrong is exactly the class of bug behind the Time & Money
scoping fix (see [Time and Money](Time%20and%20Money.md)): a table with
its own real `sequence_number` column is version-scoped and should be
queried by `award_id`; a table with no `sequence_number` column at all
(`award_notepad`, `award_amount_transaction`) is family-scoped from the
Oracle schema itself and must be queried by `award_number` — this is a
verifiable fact about the table's own DDL, not a judgment call to be
guessed per feature.

## Hierarchy is a separate axis from versioning

A related, easy-to-conflate fact: **Award Hierarchy and Award
versioning are orthogonal.** Each hierarchy node (parent/child Award) has
its own distinct `award_number` and starts its own new sequence at 1 —
"Create New Child Award" is a wholly separate Kuali UI action that
initiates a brand-new KEW workflow document, not a later
`sequence_number` of the parent's family. An Award's version history
(sequence 1, 2, 3, ... within one `award_number`) never spans a
parent/child hierarchy boundary. See [Award
Hierarchy](Award%20Hierarchy.md) for the full financial-independence
consequence of this.

## Why this matters

Every new Award-adjacent feature in this project restates the same
question — "should this query scope to the exact `award_id`, or to the
whole `award_number` family?" — and the answer has been gotten wrong
first, then corrected after live-data investigation, more than once
(Time and Money, Award Comments). The check that actually answers it:
does the real Oracle/archive table for this feature have its own
`sequence_number` column? If yes, it's version-scoped. If no, it's
family-scoped. Intuition about what "should" be version-scoped is not a
reliable substitute for checking the schema.

## Evidence

- `V055__add_award_workflow_document_number.sql` (identifier model).
- `AwardHierarchyServiceImpl.java` (`createNewChildAward`, lines
  208-221), `Award.java` (`initializeAwardWithDefaultValues`) — confirm
  new hierarchy nodes start a new `award_number`/sequence-1, not a
  continuation of the parent's sequence.
- Real example: sibling Awards `100004-00002` (award_id 1207597,
  sequence 5) and `100004-00003` (award_id 3, sequence 1) confirmed
  fully financially and historically independent.

## See also

[`docs/architecture/AWARD_IDENTIFIER_MODEL.md`](../architecture/AWARD_IDENTIFIER_MODEL.md)
for the complete identifier model including `workflow_document_number`
and `transaction_id` (see [Workflow
Documents](Workflow%20Documents.md)), and
[`docs/architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md`](../architecture/AWARD_HIERARCHY_FINANCIAL_SEMANTICS.md).
