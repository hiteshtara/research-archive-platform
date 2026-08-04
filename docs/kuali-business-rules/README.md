# Kuali Business Rules

This directory records **behavioral facts about how Kuali actually works**,
discovered by reading real Kuali Coeus source
(`/Users/mukadder/kuali_Home/kuali-research/`) and cross-checking against
real archived/live BU data — as distinct from the
`docs/architecture/*_DESIGN.md` files, which document this project's own
Oracle-to-Postgres schema mapping and ETL implementation decisions.

The distinction matters: a `*_DESIGN.md` file answers "how did we archive
this table?" A file here answers "what does Kuali actually do, and why does
that matter for anyone building on top of this archive?" Several files here
cite a `*_DESIGN.md` file as their schema-level source of record and should
be read together with it.

Every rule below is sourced — either to a real Kuali `.java`/`.xml`/SQL
migration file, or to real data (an Award number, `award_id`, or comment
type code) confirmed against the archive or a live BU environment. Where a
rule was checked only against generic Kuali Coeus seed data and not
verified live against BU's actual Oracle instance, that caveat is stated
explicitly — the project has been burned by this exact gap before (see
`UNIT_ADMINISTRATOR_TYPE` and `COMMENT_TYPE` in `docs/DECISIONS.md`).

## Index

- [Award Comments](Award%20Comments.md) — comment-type screen filtering,
  family-wide history scoping, real BU comment-type codes.
- [Comment History](Comment%20History.md) — the oldest-to-newest,
  keep-first-of-run deduplication rule behind every "collapse consecutive
  identical values across versions" feature (Award Comments today; the
  same shape recurs elsewhere).
- [Time and Money](Time%20and%20Money.md) — pending vs. permanent
  transaction state, one-transaction-to-many-rows fan-out, family-wide vs.
  version-scoped totals.
- [Central Administration Contacts](Central%20Administration%20Contacts.md)
  — a UI-only derived view with no backing Oracle table.
- [Workflow Documents](Workflow%20Documents.md) — the real KEW workflow
  document identifier vs. the unrelated modification number.
- [Award Versions](Award%20Versions.md) — `award_number`/`award_id`/
  `sequence_number`/`is_primary_current`, and why the highest sequence
  isn't always "current."
- [Award Hierarchy](Award%20Hierarchy.md) — why hierarchy nodes never
  financially aggregate, and how money actually moves between them.
- [Budget](Budget.md) — the bounded-family scoping rule (proven a third
  time, after Comments and Time & Money), why Kuali's own
  `getCurrentBudget()` doesn't transfer to a closed archive, and why
  Budget and SAP transmission share a real data dependency but no
  foreign key.
- [Institutional Proposal](InstitutionalProposal.md) — the
  `AWARD_FUNDING_PROPOSALS` many-to-many relationship resolved
  family-wide/active/non-Cancelled on both sides (a fourth proof of the
  bounded-family pattern), the `PROPOSAL_LOG.INST_PROPOSAL_NUMBER`
  naming trap (it holds a `PROPOSAL_ID`, not a `PROPOSAL_NUMBER`, and
  is unreliably populated), and why the Proposal archive currently
  holds zero live rows despite its schema existing since V015.
