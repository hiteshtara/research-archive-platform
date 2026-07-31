# Award Domain Decomposition — Roadmap

## Purpose

Group the 27 Award-owned Oracle tables identified in `AWARD_DOMAIN_STUDY.md`
into functional subsystems, with dependencies, effort estimates, and an
independence assessment for each — turning the remaining Award domain work
into a series of small, independently-shippable milestones rather than one
large implementation.

## Scope

All 27 Award-owned Oracle tables (excludes the 3 cross-domain link tables
— `PROPOSAL`, the Negotiation business-key link, `SUBAWARD_FUNDING_SOURCE`
— which belong to other domains' own object graphs, not Award's).

## Source material used

`AWARD_DOMAIN_STUDY.md` (this decomposition performs no new source-tree
exploration — it is a synthesis pass), plus one direct verification of
`AwardCustomData`'s Oracle mapping
(`coeus-impl/src/main/resources/org/kuali/kra/award/repository-award.xml`,
class `org.kuali.kra.award.customdata.AwardCustomData`, table
`AWARD_CUSTOM_DATA`) performed while preparing this document, since the
four parallel research passes referenced but didn't individually deep-dive
that table.

## Assumptions

- Effort sizing (Small/Medium/Large) is a qualitative estimate based on
  table count, hierarchy depth, and whether calculated/derived fields are
  involved — not a time estimate.
- "Independent" means buildable and shippable without any other
  not-yet-built subsystem existing first (Core Award excepted, which
  everything depends on).

## Findings

### Dependency structure

Every satellite subsystem depends **only** on Core Award (an `award_id`
row existing) — there is no subsystem-to-subsystem dependency except one
soft FK: Award Reporting's `AwardPaymentSchedule.AWARD_REPORT_TERM_ID`
optionally references Award Terms. This means 7 of the 9 non-core
subsystems can be built and shipped **in any order, or in parallel**,
once Core Award's UPSERT primitives exist.

### Subsystems

**Tier 0 — Core Award** *(done, Phase 4A)*
- Tables (4): `AWARD`, `AWARD_EXTENSION`, `AWARD_AMOUNT_INFO`,
  `AWARD_FUNDING_PROPOSALS`
- Depends on: nothing — the root every other subsystem needs
- Effort: Medium — `AwardExtension` (1:1, no own sequence) is the one
  piece not yet built; the other three shipped in Phase 4A
- Independent: N/A — everything else waits on this

**Tier 1 — Award People**
- Tables (2): `AWARD_PERSONS`, `AWARD_UNIT_CONTACTS`
- Depends on: Core Award only
- Effort: Small — flat, single-level; `AWARD_PERSONS` has no DB
  uniqueness constraint, so the UPSERT conflict key is simply its own
  surrogate PK
- Independent: yes — but `AWARD_UNIT_CONTACTS` specifically requires
  re-opening the V033 removal decision before it can ship; `AWARD_PERSONS`
  alone can proceed without that blocker

**Tier 1 — Award Contacts**
- Tables (1): `AWARD_SPONSOR_CONTACTS`
- Depends on: Core Award only
- Effort: Small — flat, single table, same shared-sequence shape as
  Award People
- Independent: yes, fully

**Tier 1 — Award Attachments**
- Tables (2): `AWARD_ATTACHMENT` (already archived + UPSERT-ready),
  `AWARD_NOTEPAD`
- Depends on: Core Award only
- Effort: Small — only `AWARD_NOTEPAD` remains, simpler than the
  attachment work already done (flat, no binary content, no S3 concern)
- Independent: yes, fully — this subsystem is already half-shipped

**Tier 1 — Award Terms**
- Tables (2): `AWARD_REPORT_TERMS`, `AWARD_REP_TERMS_RECNT`
- Depends on: Core Award only
- Effort: Small-Medium — one real parent/child hop, otherwise flat
- Independent: yes — should ship before or alongside Award Reporting,
  since Reporting's `AwardPaymentSchedule` optionally references it

**Tier 1 — Award Reporting**
- Tables (2): `AWARD_CLOSEOUT`, `AWARD_PAYMENT_SCHEDULE`
- Depends on: Core Award; `AWARD_PAYMENT_SCHEDULE` optionally references
  Award Terms' `AWARD_REPORT_TERMS_ID`
- Effort: Small — both flat, single level
- Independent: mostly — can ship without Award Terms present (the FK is
  nullable/optional), but the reference is only meaningful once Award
  Terms exists

**Tier 1 — Award Custom Data**
- Tables (1): `AWARD_CUSTOM_DATA`
- Depends on: Core Award only (plus a shared, cross-domain
  `CustomAttribute` lookup table already dealt with for
  Negotiation/Subaward)
- Effort: Small — generic key/value (EAV) shape, **identical pattern to
  `archive.negotiation_custom_data` and `archive.subaward_custom_data`**,
  both already implemented and in production. Lowest-risk subsystem in
  this entire decomposition — closer to a copy than a new design.
- Independent: yes, fully

**Tier 1 — Award Subaward Summary**
- Tables (1): `AWARD_APPROVED_SUBAWARDS`
- Depends on: Core Award only — **not** the real `SUBAWARD` table (no
  Oracle-level link between them; this is a standalone "planned
  subaward" summary)
- Effort: Trivial — smallest table in the whole domain
- Independent: yes, fully

**Tier 2 — Award Budget**
- Tables (8): `BUDGET`/`AWARD_BUDGET_EXT` → `BUDGET_PERIODS` →
  `BUDGET_DETAILS` → {`BUDGET_DETAILS_CAL_AMTS`,
  `BUDGET_PERSONNEL_DETAILS` → `BUDGET_PERSONNEL_CAL_AMTS`} →
  `AWD_BGT_PER_SUM_CALC_AMT`, plus `AWARD_BUDGET_LIMIT`
- Depends on: Core Award only, externally — but internally a genuine
  5-level hierarchy that must load parent-before-child within itself
  (unlike every subsystem above, which is flat or 2-level)
- Effort: Large — deepest hierarchy in the domain, multiple
  calculated-amount tables, its own workflow document layer. Deserves its
  own dedicated design pass.
- Independent: yes as a whole subsystem, but should be scheduled last
  among the tier-1 work given its size

**Tier 2 — Award Time and Money**
- Tables (4): `TIME_AND_MONEY_DOCUMENT`,
  `PENDING_TRANSACTIONS`(`_EXTENSION`), `AWARD_AMOUNT_TRANSACTION`,
  `TRANSACTION_DETAILS`
- Depends on: Core Award's `AWARD_AMOUNT_INFO` specifically (already
  archived) — its "anchor" table already exists, a head start no other
  tier-2 subsystem has
- Effort: Medium — 4 tables, one real parent/child hop,
  workflow-document-status semantics to model, nowhere near Budget's
  depth
- Independent: yes — arguably the better tier-2 candidate to tackle
  before Budget, precisely because its anchor table is already live

## Open questions

- Should Award Terms and Award Reporting ship in the same milestone
  (given the soft dependency) or genuinely separately? Leaning toward
  "Terms first, Reporting second" but not decided.
- Is there a real product need for `AwardExtension` (Tier 0's one
  remaining piece) at all, or is it low-value BU-specific metadata not
  worth archiving? Not decided.

## Decisions

- Tier 1 subsystems may be built in any order; no artificial sequencing
  imposed beyond the Terms-before-Reporting soft preference.
- Tier 2 (Budget, Time and Money) are deliberately not scheduled until
  Tier 1 is complete, to prove the UPSERT+batch pattern repeatedly on
  simpler shapes first.
- Award Custom Data is flagged as the recommended **first** Tier 1
  subsystem to build, precisely because it reuses an already-proven
  pattern from Negotiation/Subaward.

## Recommended implementation order

1. ~~Tier 0: Core Award (Phase 4A)~~ — done.
2. Tier 1, in any order (suggested starting point: Award Custom Data,
   for its near-zero design risk): Award People, Award Contacts, Award
   Attachments, Award Terms, Award Reporting, Award Custom Data, Award
   Subaward Summary.
3. Tier 2: Award Time and Money before Award Budget.

## Date last updated

2026-07-31.
