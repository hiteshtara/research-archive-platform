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
- Tables (4): `AWARD`, `AWARD_AMOUNT_INFO`, `AWARD_FUNDING_PROPOSALS`,
  and (originally listed here, actually built as its own Tier 1 bundle
  below alongside `AwardCgb`) `AWARD_EXTENSION`
- Depends on: nothing — the root every other subsystem needs
- Effort: Medium — the first three shipped in Phase 4A;
  `AwardExtension` shipped later, paired with `AwardCgb`, once both
  were confirmed as real 1:1 extension tables — see "Tier 1 — Award
  Extension and Award CGB" below
- Independent: N/A — everything else waits on this

**Tier 1 — Award People** *(done)*
- Tables (4): `AWARD_PERSONS` (archived, Phase 4A), `AWARD_PERSON_UNITS`,
  `AWARD_PERSON_CREDIT_SPLITS`, `AWARD_PERS_UNIT_CRED_SPLITS` — a real,
  3-level-deep sub-hierarchy under `AwardPerson`
  (`AwardPerson → AwardPersonUnit → AwardPersonUnitCreditSplit`, plus the
  sibling `AwardPerson → AwardPersonCreditSplit`), corrected from this
  entry's prior `AWARD_PERSONS`/`AWARD_UNIT_CONTACTS` pairing — see
  `AWARD_PEOPLE_EXPANSION_DESIGN.md`'s Decisions section for why
  `AWARD_UNIT_CONTACTS` was never actually part of this object graph.
  `AWARD_UNIT_CONTACTS` remains fully out of scope (V033 removal,
  unrevisited).
- Depends on: Core Award only
- Effort: Small — flat/2-level, all four surrogate PKs share
  `SEQUENCE_AWARD_ID`; `AWARD_PERSONS` has no DB uniqueness constraint,
  so every UPSERT conflict key is simply its own surrogate PK
- Independent: yes, fully — shipped without needing
  `AWARD_UNIT_CONTACTS`'s V033 blocker at all, since that table was never
  actually part of this subsystem

**Tier 1 — Award Contacts** *(done)*
- Tables (2): `AWARD_SPONSOR_CONTACTS`, `AWARD_UNIT_CONTACTS` —
  corrected from this entry's prior single-table listing, which omitted
  `AWARD_UNIT_CONTACTS` (dropped in V033 at the time, now reintroduced
  with a corrected, double-verified schema; see
  `AWARD_CONTACTS_DESIGN.md`). `AWARD_CENTRAL_ADMIN_CONTACTS` does not
  exist as a table at all — it is a transient UI rollup of
  `UNIT_ADMINISTRATOR` data built from `AwardUnitContact` objects that
  are never persisted under that identity; see the design doc's
  Findings.
- Depends on: Core Award only
- Effort: Small — both flat, single-level, same shared-sequence shape
  as Award People
- Independent: yes, fully

**Tier 1 — Award Attachments** *(done)*
- Tables (2): `AWARD_ATTACHMENT` (already archived + UPSERT-ready),
  `AWARD_NOTEPAD` — 34 real rows confirmed in BU Oracle; see
  `AWARD_NOTEPAD_DESIGN.md`
- Depends on: Core Award only
- Effort: Small — flat, no binary content, no S3 concern; the one
  genuine schema surprise was that `AWARD_NOTEPAD` has no
  `sequence_number` at all (notes are scoped to the whole
  `award_number` family, not a version) and is the first Award child
  table with both `CREATE_*` and `UPDATE_*` provenance columns
- Independent: yes, fully

**Tier 1 — Award Terms** *(done)*
- Tables (3): `AWARD_SPONSOR_TERM`, `AWARD_REPORT_TERMS`,
  `AWARD_REP_TERMS_RECNT` — corrected from this entry's prior
  `AWARD_REPORT_TERMS`/`AWARD_REP_TERMS_RECNT` pairing, which omitted
  `AWARD_SPONSOR_TERM` entirely; see `AWARD_TERMS_DESIGN.md`.
  `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` are **not** part of
  this subsystem at all — they are pure code/description lookup tables
  for two scalar fields directly on `AWARD` itself
  (`basisOfPaymentCode`/`methodOfPaymentCode`), architecturally identical
  to `STATUS_CODE`/`SPONSOR_CODE`. Capturing those two fields is a
  separate, small, deliberately deferred follow-on (an `award_version`
  column addition, not a new child table) — see `AWARD_TERMS_DESIGN.md`'s
  Open Questions.
- Depends on: Core Award only
- Effort: Small-Medium — one real parent/child hop
  (`AWARD_REPORT_TERMS` → `AWARD_REP_TERMS_RECNT`), otherwise flat
- Independent: yes — should ship before or alongside Award Reporting,
  since Reporting's `AwardPaymentSchedule` optionally references
  `AWARD_REPORT_TERMS`

**Tier 1 — Award Reporting** *(done)*
- Tables (2): `AWARD_CLOSEOUT`, `AWARD_PAYMENT_SCHEDULE`
- Depends on: Core Award; `AWARD_PAYMENT_SCHEDULE` optionally references
  Award Terms' `AWARD_REPORT_TERMS_ID` (stored as a bare, unenforced
  column in the archive, not a physical FK - see
  `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`)
- Effort: Small — both flat, single level. `AWARD_PAYMENT_SCHEDULE`
  turned out to carry two columns added by later upstream migrations
  beyond the OJB mapping's base fields (`AWARD_REPORT_TERM_DESC`,
  `LAST_UPDATE_TIMESTAMP`/`LAST_UPDATE_USER`), each individually traced
  to a real `ALTER TABLE` rather than assumed from the Java mapping
  alone
- Independent: mostly — can ship without Award Terms present (the FK is
  nullable/optional), but the reference is only meaningful once Award
  Terms exists

**Tier 1 — Award Custom Data** *(done)*
- Tables (1): `AWARD_CUSTOM_DATA`
- Depends on: Core Award only (plus a shared, cross-domain
  `CustomAttribute` lookup table already dealt with for
  Negotiation/Subaward)
- Effort: Small — generic key/value (EAV) shape, **identical pattern to
  `archive.negotiation_custom_data` and `archive.subaward_custom_data`**,
  both already implemented and in production. Lowest-risk subsystem in
  this entire decomposition — closer to a copy than a new design.
- Independent: yes, fully

**Tier 1 — Award Subaward Summary** *(done)*
- Tables (1): `AWARD_APPROVED_SUBAWARDS`
- Depends on: Core Award only — **not** the real `SUBAWARD` table (no
  Oracle-level link between them; this is a standalone "planned
  subaward" summary)
- Effort: Trivial — smallest table in the whole domain. Turned out to
  be the only Award child table found so far where `AWARD_ID`/
  `AWARD_NUMBER`/`SEQUENCE_NUMBER` are themselves nullable at the
  Oracle DDL level - handled by keeping the archive columns NOT NULL,
  since the extraction path structurally guarantees non-null values
  for every row actually read - see
  `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`
- Independent: yes, fully

**Tier 1 — Award Special Approvals and Compliance** *(done)*
- Tables (9): `AWARD_CFDA`, `AWARD_COST_SHARE`, `AWARD_IDC_RATE`
  (`AwardFandaRate`), `AWARD_SCIENCE_KEYWORD`, `AWARD_SPECIAL_REVIEW`,
  `AWARD_EXEMPT_NUMBER` (`AwardSpecialReviewExemption`),
  `AWARD_APPROVED_EQUIPMENT`, `AWARD_APPROVED_FOREIGN_TRAVEL`,
  `SUBCONTRACTING_BUD` (`AwardSubcontractingBudgetedGoals`) - not part
  of this document's original table-counting decomposition at all;
  surfaced entirely by `KUALI_ARCHIVE_COVERAGE.md`'s later
  DataDictionary-driven pass, which is exactly why that pass is now the
  primary checklist, not this document.
- Depends on: Core Award only, with two real internal wrinkles:
  `AWARD_EXEMPT_NUMBER`'s only FK is to `AWARD_SPECIAL_REVIEW` (not
  `AWARD` directly - a true parent/child hop), and
  `SUBCONTRACTING_BUD` has no `AWARD_ID`/version tie at all (keyed
  directly by `award_number`, the one such table in the whole domain)
- Effort: Medium — nine tables, but each individually simple; the real
  cost was verification (two tables with no `AWARD_NUMBER`/
  `SEQUENCE_NUMBER` columns requiring a join-to-denormalize, one
  requiring a brand-new award_number-keyed bounded reader, and two
  columns/PKs renamed from Oracle's literal legacy naming to their
  authoritative Java-side names)
- Independent: yes, fully - see `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`

**Tier 1 — Award Comment** *(done)*
- Tables (1): `AWARD_COMMENT` - also not part of this document's
  original table list; surfaced by `KUALI_ARCHIVE_COVERAGE.md`.
  Confirmed distinct from `AwardNotepad`/`AWARD_NOTEPAD` - different
  Java class/package, different table, and different scoping (this
  table belongs to a specific Award *version* via a real, backfilled
  `SEQUENCE_NUMBER`, whereas `AwardNotepad` has no `SEQUENCE_NUMBER`
  column at all and is scoped to the whole family).
- Depends on: Core Award only - Oracle-enforced FK to `AWARD`, unlike
  several Special Approvals siblings that lack one.
- Effort: Trivial - single flat table, no children, no lookup joins
  beyond a bare `comment_type_code`.
- Independent: yes, fully - see `AWARD_COMMENT_DESIGN.md`, which also
  records the outcome of re-investigating `AwardCgb` (explicitly
  requested to be reclassified as NOT APPLICABLE unless DDL proved
  otherwise - it did, so `AwardCgb` was NOT reclassified).

**Tier 1 — Award Extension and Award CGB** *(done)*
- Tables (2): `AWARD_EXTENSION` (originally listed under Tier 0, never
  actually built there), `AWARD_CGB` (re-investigated in the Award
  Comment bundle above and confirmed real, not reclassified). Both are
  true 1:1-with-Award, BU-specific extension tables — `award_id` itself
  is the primary key, not a surrogate sequence id, the shape that
  distinguishes this pair from every other Award child table built so
  far.
- Depends on: Core Award only. Neither table has an Oracle-enforced FK
  to `AWARD` in the available checkout (Java/OJB-layer relationship
  only, consistent with several other Award child tables).
  `AWARD_EXTENSION` has no native `AWARD_NUMBER`/`SEQUENCE_NUMBER`
  columns and is denormalized via a join to `AWARD` at extraction time
  (the same join-to-denormalize pattern used for
  `AWARD_SCIENCE_KEYWORD`/`AWARD_SPECIAL_REVIEW`); `AWARD_CGB` carries
  both columns natively.
- Effort: Small — both flat, single-table, no children;
  `AWARD_CGB.BILL_FREQ_CD` is flagged as unverified against real BU
  Oracle (no OJB mapping — the same risk shape as the
  `AwardCostShare.FISCAL_YEAR` mistake caught this session).
- Independent: yes, fully - see `AWARD_EXTENSION_CGB_DESIGN.md`.

**Tier 2 — Award Budget** *(done)*
- Tables (8): `BUDGET`/`AWARD_BUDGET_EXT` → `BUDGET_PERIODS` →
  `BUDGET_DETAILS` → {`BUDGET_DETAILS_CAL_AMTS`,
  `BUDGET_PERSONNEL_DETAILS` → `BUDGET_PERSONNEL_CAL_AMTS`} →
  `AWD_BGT_PER_SUM_CALC_AMT`, plus `AWARD_BUDGET_LIMIT`
- Depends on: Core Award only, externally — but internally a genuine
  5-level hierarchy that must load parent-before-child within itself
  (unlike every subsystem above, which is flat or 2-level)
- Effort: turned out Large as predicted — deepest hierarchy in the
  domain (5 levels), six of the eight tables are OJB table-per-subclass
  merges of an Award-specific `_EXT` table with a generic table shared
  with Proposal Development (the first confirmed real, Oracle-enforced
  FK between two tables archived in this project), one table
  (`AWD_BGT_PER_SUM_CALC_AMT`) serves two logical roles from a single
  physical table via `rate_class_type`. See `AWARD_BUDGET_DESIGN.md`.
- Independent: yes as a whole subsystem — was scheduled last among the
  tier-1/tier-2 work given its size, exactly as planned.

**Tier 2 — Award Time and Money** *(done — pulled forward and
implemented ahead of Budget, see below)*
- Tables (7, not 4 as originally estimated): `AWARD_HIERARCHY`
  (reclassified from NOT APPLICABLE — a required dependency, not
  optional), `TIME_AND_MONEY_DOCUMENT`, `PENDING_TRANSACTIONS`,
  `PENDING_TRANSACTIONS_EXTENSION`, `TRANSACTION_DETAILS`,
  `AWARD_AMOUNT_TRANSACTION`, `AWARD_AMT_FNA_DISTRIBUTION` — plus two
  new columns (`transaction_id`, `originating_award_version`) on the
  already-archived `AWARD_AMOUNT_INFO`, not a new table.
- Depends on: Core Award's `AWARD_AMOUNT_INFO` specifically (already
  archived) — its "anchor" table already existed, a head start no other
  tier-2 subsystem has. `AWARD_HIERARCHY` turned out to be a genuine,
  load-bearing dependency too: `TransactionDetail`'s `INTERMEDIATE` rows
  cannot be correctly interpreted without it.
- Effort: turned out Large, not Medium — the two "TRANSACTION_ID"
  columns across different tables mean two different things
  (`archive.award_amount_transaction.document_number` is a renamed
  VARCHAR2 document number, not a numeric transaction id), and one
  `PendingTransaction` fans out into multiple `AwardAmountInfo` rows
  (never a 1:1 mapping) — see `AWARD_TIME_AND_MONEY_DESIGN.md`.
- Independent: yes — was the better tier-2 candidate to tackle before
  Budget, precisely because its anchor table was already live; Budget
  has since also shipped, closing out Tier 2 entirely.

**Final Award gap bundle** *(done)*
- Tables (2): `BUDGET_PERSONS` (no Award-specific `_EXT` table -
  standalone, scoped to Award by joining `BUDGET_PERSONS` → `BUDGET` →
  `AWARD_BUDGET_EXT`), `AWARD_TRANSFERRING_SPONSOR` (a simple,
  per-version child table with no children of its own)
- Depends on: `AWARD_BUDGET_EXT`/`archive.award_budget` (Tier 2 Budget,
  already archived) for `BUDGET_PERSONS`; Core Award only for
  `AwardTransferringSponsor`
- Effort: Small on both, exactly as `AWARD_COMPLETENESS_REPORT.md`
  predicted — `BUDGET_PERSONS` reuses the join-through-`AWARD_BUDGET_EXT`
  scoping pattern already proven twice in Tier 2 Budget (no new design
  decision required), and `AwardTransferringSponsor` is a near-exact
  copy of the already-shipped `archive.award_sponsor_term` shape (one
  lookup-code FK, one row per Award version). Two DDL-only columns
  with no OJB field-descriptor - `BUDGET_PERSONS.PROPOSAL_NUMBER` and
  `.VERSION_NUMBER` (distinct from `VER_NBR`) - were found and
  excluded for the same "no corroborating evidence" reason as
  `BUDGET.FINAL_VERSION_FLAG` in Tier 2 Budget.
- Independent: yes, fully - see `AWARD_COMPLETENESS_REPORT.md`, which
  is where both objects were reclassified from their prior
  "flagged gap"/"not yet evaluated" status to ARCHIVE_REQUIRED.

## Open questions

- ~~Should Award Terms and Award Reporting ship in the same milestone
  (given the soft dependency) or genuinely separately?~~ Resolved:
  shipped separately, Terms first - see Decisions.
- ~~Is there a real product need for `AwardExtension` (Tier 0's one
  remaining piece) at all, or is it low-value BU-specific metadata not
  worth archiving?~~ Resolved: both `AwardExtension` and `AwardCgb` are
  real, confirmed 1:1 Award extension tables and have been archived —
  see "Tier 1 — Award Extension and Award CGB" and
  `AWARD_EXTENSION_CGB_DESIGN.md`.
- `AWARD_EXTENSION`'s Oracle-level PK/FK and `AWARD_CGB.BILL_FREQ_CD`'s
  real-BU-Oracle verification remain open — see
  `AWARD_EXTENSION_CGB_DESIGN.md`'s Open Questions.

## Decisions

- Tier 1 subsystems may be built in any order; no artificial sequencing
  imposed beyond the Terms-before-Reporting soft preference.
- Tier 2 (Budget, Time and Money) were deliberately not scheduled until
  Tier 1 was complete, to prove the UPSERT+batch pattern repeatedly on
  simpler shapes first. Time and Money was then pulled forward and
  implemented once Tier 1 finished, ahead of Budget; Budget followed as
  the final Tier 2 bundle, exactly as originally planned — see below.
- Award Custom Data was built first among Tier 1 subsystems, precisely
  because it reused an already-proven pattern from
  Negotiation/Subaward — confirmed low-risk, shipped with no design
  surprises, riding along on the existing `--load-award-id`/batch
  framework as a 5th child table with no new top-level load function or
  new batch domain/entity_type.
- Award People was built second, revealing that this entry's original
  table list (`AWARD_PERSONS`/`AWARD_UNIT_CONTACTS`) was itself
  incomplete — a fresh read of the upstream OJB mapping's
  `org.kuali.kra.award.contacts` package found three real child/
  grandchild tables (`AWARD_PERSON_UNITS`,
  `AWARD_PERSON_CREDIT_SPLITS`, `AWARD_PERS_UNIT_CRED_SPLITS`) neither
  this document nor `AWARD_DOMAIN_STUDY.md` had previously surfaced.
  Shipped the same way as Custom Data — no new batch domain/entity_type,
  riding along on the same family-widened load — with one added
  wrinkle: none of the three carry `AWARD_ID` directly, so
  `AWARD_ID`/`AWARD_NUMBER`/`SEQUENCE_NUMBER` are denormalized through
  an Oracle-side `JOIN` back to `AWARD_PERSONS` (and, for the
  grandchild, through `AWARD_PERSON_UNITS` as well) purely to keep
  reusing the existing generic bounded reader unmodified.
- Award Terms was built third, revealing the same kind of gap again:
  this entry's original table list omitted `AWARD_SPONSOR_TERM`
  entirely, and two of the six tables originally asked about
  (`AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT`) turned out not to
  be child tables at all — see `AWARD_TERMS_DESIGN.md`. Also refined the
  "everything shares `SEQUENCE_AWARD_ID`" finding: `AWARD_SPONSOR_TERM`
  and `AWARD_REP_TERMS_RECNT` each draw from their own dedicated
  sequence, the same way `award_custom_data_id` already did — still
  safe as table-scoped UPSERT conflict keys, just no longer a default
  assumption for a not-yet-investigated table.
- Award Contacts was built fourth. `AWARD_UNIT_CONTACTS` was
  successfully reintroduced (dropped in V033 for lack of a verified
  extraction) after double-verifying its real schema against both the
  Kuali OJB mapping and the actual Oracle bootstrap DDL — the
  previously-shipped V014 schema had included several columns
  (`unit_name`, `parent_unit_number`/`name`, `project_role`,
  `primary_title`, `directory_title`, `office_location`,
  `email_address`, `office_phone`, `phone_extension`) with no basis in
  the real table at all, explaining exactly why it had been unverified.
  `AWARD_CENTRAL_ADMIN_CONTACTS` turned out not to be a table at all —
  a transient UI rollup, never persisted. See `AWARD_CONTACTS_DESIGN.md`.
- Award Attachments (`AWARD_NOTEPAD`) was built fifth, completing that
  subsystem. The real Oracle bootstrap DDL again proved essential (same
  discipline as Award Contacts): it revealed `AWARD_NOTEPAD` has no
  `sequence_number` column at all — the only Award child table found so
  far scoped to the whole `award_number` family rather than a version —
  and that its `AWARD_NOTEPAD_ID` sequence (`SEQ_AWARD_NOTEPAD_ID`) is
  yet a fourth table not sharing `SEQUENCE_AWARD_ID`. See
  `AWARD_NOTEPAD_DESIGN.md`.
- Award Reporting and Award Subaward Summary were built together
  sixth, as one bundle (the two Reporting tables plus the one Subaward
  Summary table). Confirmed the "Terms first, Reporting second" soft
  ordering from Open Questions was correct in practice:
  `AWARD_PAYMENT_SCHEDULE.AWARD_REPORT_TERM_ID` optionally references
  `archive.award_report_term`, already archived by Award Terms - kept
  as a bare, unenforced column rather than a physical FK, since it is
  an optional cross-bundle reference, not a containment relationship.
  See `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`.
- Award Special Approvals and Compliance was built seventh - the first
  Tier 1 bundle not drawn from this document's own original table
  list at all, but from `KUALI_ARCHIVE_COVERAGE.md`'s later
  DataDictionary-driven pass. Surfaced two genuinely new schema shapes
  not seen anywhere else in the domain: `AWARD_EXEMPT_NUMBER` (a true
  child of `AWARD_SPECIAL_REVIEW`, not `AWARD` directly - no `AWARD_ID`
  column exists on it at all) and `SUBCONTRACTING_BUD` (keyed by
  `award_number` itself, no surrogate PK, no version tie, requiring a
  new `read_award_children_matching_award_numbers` bounded reader).
  Also confirmed `AWARD_CFDA` is a real child table, not an enrichment
  view, via its own creating migration's backfill logic. See
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`.
- Award Comment was built eighth, alongside a re-investigation of
  `AwardCgb` (explicitly asked to be reclassified as NOT APPLICABLE
  unless real DDL proved otherwise). The DDL
  (`V600_047__KC_TBL_AWARD_CGB.sql`, a later migration not in the base
  schema) proves `AwardCgb` is real, substantial, persisted billing/
  invoicing data with `AWARD_ID` as its own PK - the same 1:1 shape as
  `AwardExtension` - so it was **not** reclassified, correcting the
  premise of that request rather than following it blindly. See
  `AWARD_COMMENT_DESIGN.md`.
- Award Extension and Award CGB were built ninth, together as one
  bundle (both are direct, structurally simple 1:1 Award extensions).
  Confirmed both use `award_id` itself as the primary key (no surrogate
  sequence id, the only Award tables shaped this way). Found no
  Oracle-level PK/FK constraint for `AWARD_EXTENSION` in the available
  checkout despite confirmed real schema evolution (an added-then-
  dropped FAIN column); flagged `AWARD_CGB.BILL_FREQ_CD` (a real
  column with no OJB mapping, added by a later Kuali migration) as the
  same risk shape as the `AwardCostShare.FISCAL_YEAR` mistake caught
  earlier this session, rather than repeating it. See
  `AWARD_EXTENSION_CGB_DESIGN.md`.
- A separately-reported, unrelated correction landed the same day:
  `AwardCostShare.FISCAL_YEAR` was removed from the Cost Share
  extraction/prepare/upsert pipeline after direct BU Oracle evidence
  proved the column does not exist in production despite appearing in
  the generic Kuali source tree's bootstrap DDL — the first confirmed
  case of that discrepancy in the whole Award domain. See
  `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`'s own Decisions
  section.
- Basis of payment / method of payment field completion on `Award`
  itself was built tenth, closing the TRUNCATE-path follow-on
  `AWARD_TERMS_DESIGN.md` had deliberately deferred. Confirmed both
  codes are plain scalar `VARCHAR2(3)` columns directly on `AWARD`
  (not `INTEGER` like `status_code`/`transaction_type_code`, so neither
  is numeric-converted - a leading zero is meaningful). Added via a new
  corrective migration (`V047`), not a rewrite of the already-shipped
  `V011`, the same precedent `V013` set for `is_primary_current`.
  `AWARD_BASIS_OF_PAYMENT`/`AWARD_METHOD_OF_PAYMENT` descriptions are
  denormalized via `LEFT JOIN` snapshot at extraction time, the same
  convention already used for `status_description`/`transaction_type`.
  This closes `Award`'s last PARTIALLY ARCHIVED gap. See
  `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
- Award Time and Money was built eleventh, pulled forward ahead of
  Budget as originally planned. A one-research-pass-first discipline
  was followed explicitly: the full object graph, relationship
  summary, and "traps for implementation" were written and reviewed
  before any migration or code. `AwardHierarchy` was reclassified from
  NOT APPLICABLE to COMPLETE in the same pass — it had been
  miscategorized as multi-campus sync bookkeeping, but is the real
  parent/child Award relationship the money-routing algorithm depends
  on directly. `AWARD_AMOUNT_INFO` was extended in place (two new
  columns) rather than duplicated as a new table, per explicit
  instruction. The two distinct "TRANSACTION_ID" concepts across
  different tables (a real numeric surrogate vs.
  `AWARD_AMOUNT_TRANSACTION`'s own VARCHAR2 document number) were kept
  under different archive field names by design, and the
  one-`PendingTransaction`-to-many-`AwardAmountInfo` relationship was
  deliberately left unconstrained (no unique/1:1 assumption anywhere).
  See `AWARD_TIME_AND_MONEY_DESIGN.md`.
- Award Budget was built twelfth, the final Tier 2 bundle and the
  deepest hierarchy in the whole Award domain (5 levels). The same
  one-research-pass-first discipline was followed. The central design
  question - how to represent six OJB-inheritance pairs where the
  generic side is shared with Proposal Development, not Award-only -
  was resolved by merging each pair into one flattened archive table
  keyed by the shared PK, using the INNER JOIN to the `_EXT` table
  itself as the Proposal-exclusion filter (the generic tables carry no
  discriminator column). `AWD_BGT_PER_SUM_CALC_AMT`'s two logical roles
  (fringe/F&A, via `rate_class_type`) were kept as one table, matching
  Kuali's own design, rather than split into two. `previousObligatedTotal`
  and `BUDGET.FINAL_VERSION_FLAG` were excluded for lacking corroborating
  evidence in either OJB or DDL, the same discipline the Cost Share
  `FISCAL_YEAR` correction established. `BUDGET_PERSONS` was found but
  deliberately left out of scope, flagged rather than silently added or
  dropped. See `AWARD_BUDGET_DESIGN.md`.
- The final Award gap bundle was built thirteenth: `AWARD_COMPLETENESS_REPORT.md`
  reassessed both `BUDGET_PERSONS` (previously flagged out of scope in
  the Budget bundle above) and `AwardTransferringSponsor` (previously
  "not yet evaluated"), reclassified both ARCHIVE_REQUIRED with real
  Java/OJB/DDL evidence, and both were implemented reusing
  already-proven patterns rather than new design work -
  `archive.award_budget_person` extends the join-through-`AWARD_BUDGET_EXT`
  scoping pattern to a table with no `_EXT` counterpart, and
  `archive.award_transferring_sponsor` mirrors
  `archive.award_sponsor_term`. Two more DDL-only, no-OJB-mapping
  columns were found on `BUDGET_PERSONS` itself
  (`PROPOSAL_NUMBER`/`VERSION_NUMBER`, distinct from `VER_NBR`) and
  excluded for the same reason as `BUDGET.FINAL_VERSION_FLAG`. See
  `AWARD_COMPLETENESS_REPORT.md`.

## Recommended implementation order

1. ~~Tier 0: Core Award (Phase 4A)~~ — done.
2. ~~Tier 1: Award Custom Data~~ — done.
3. ~~Tier 1: Award People~~ — done, see
   `AWARD_PEOPLE_EXPANSION_DESIGN.md`.
4. ~~Tier 1: Award Terms~~ — done, see `AWARD_TERMS_DESIGN.md`.
5. ~~Tier 1: Award Contacts~~ — done, see `AWARD_CONTACTS_DESIGN.md`.
6. ~~Tier 1: Award Attachments (`AWARD_NOTEPAD`)~~ — done, see
   `AWARD_NOTEPAD_DESIGN.md`.
7. ~~Tier 1: Award Reporting, Award Subaward Summary~~ — done, see
   `AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md`.
8. ~~Tier 1: Award Special Approvals and Compliance~~ — done, see
   `AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md`.
9. ~~Tier 1: Award Comment~~ — done, see `AWARD_COMMENT_DESIGN.md`.
10. ~~Tier 1: Award Extension and Award CGB~~ — done, see
    `AWARD_EXTENSION_CGB_DESIGN.md`.
11. ~~Basis of payment / method of payment field completion on `Award`
    itself~~ — done, see `AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`.
12. ~~Tier 2: Award Time and Money~~ — done, see
    `AWARD_TIME_AND_MONEY_DESIGN.md`.
13. ~~Tier 2: Award Budget~~ — done, see `AWARD_BUDGET_DESIGN.md`.
14. ~~Final Award gap bundle (`BUDGET_PERSONS`/`AwardTransferringSponsor`)~~
    — done, see `AWARD_COMPLETENESS_REPORT.md`.
15. Final Award field/table reconciliation and completion report.

## Date last updated

2026-07-31 (Award Custom Data, Award People, Award Terms, Award
Contacts, Award Attachments/Notepad, Award Reporting/Subaward Summary,
Award Special Approvals and Compliance, and Award Comment marked done;
AwardCgb re-investigated and confirmed not reclassifiable. Same-day
follow-up: Award Extension and Award CGB built together as their own
Tier 1 bundle, both marked done; Tier 0's Core Award entry corrected to
stop listing `AWARD_EXTENSION` as still-unbuilt. Second same-day
follow-up: basis of payment / method of payment field completion on
`Award` itself marked done — see
`AWARD_BASIS_METHOD_OF_PAYMENT_DESIGN.md`. Third same-day follow-up:
Tier 2 Award Time and Money marked done, pulled forward ahead of
Budget; `AwardHierarchy` reclassified NOT APPLICABLE → COMPLETE and
archived alongside it — see `AWARD_TIME_AND_MONEY_DESIGN.md`. Fourth
same-day follow-up: Tier 2 Award Budget marked done, closing out Tier 2
entirely — the deepest (5-level) bundle in the domain, six of its eight
tables merging an Award-specific `_EXT` table into a generic table
shared with Proposal Development; see `AWARD_BUDGET_DESIGN.md`).

2026-08-01: Final Award gap bundle marked done -
`AWARD_COMPLETENESS_REPORT.md` reclassified `BUDGET_PERSONS` and
`AwardTransferringSponsor` as ARCHIVE_REQUIRED and both were
implemented as `archive.award_budget_person`/
`archive.award_transferring_sponsor`, reusing already-proven patterns
rather than new design work. Only the final Award field/table
reconciliation and completion report remains.
