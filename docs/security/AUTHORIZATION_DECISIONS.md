# Archive authorization — recorded business decisions

Decisions taken 2026-10-26, binding on Phase 2 implementation. Each names
where it is enforced and the test that proves it, so a later reader can tell
whether the code still matches the decision.

Enforcement remains OFF by default; these describe behaviour once it is on.

## D1 — No permissions means Access Not Provisioned, not an empty archive

> An authenticated user with no assigned archive permissions receives Access
> Not Provisioned and no research data.

A scope predicate alone renders an empty list. That is "no research data",
but it is indistinguishable from an archive that happens to contain nothing,
and it tells the user nothing about what to do. List endpoints therefore call
`requireProvisioned()` **before** building a predicate, so the caller gets
`ACCESS_NOT_PROVISIONED` (403) instead.

- **Enforced:** `AwardRecordAccess.requireProvisioned()`, called at the top of
  `search`, `findFamilies`, `searchVersions`, `searchAttachments` and
  `DashboardService.counts()`. Single-record reads reach it through
  `RecordAuthorizationService.requireAward*`.
- **Tests:** `decision1_anUnprovisionedCallerGetsAnExplicitProblemNotAnEmptyArchive`,
  `decision1_doesNotFireWhenEnforcementIsOff`.

## D2 — Dashboard counts are scoped

> Dashboard counts and statistics must be scoped to the records the user is
> authorized to see.

- **Enforced:** `DashboardService`, which replaces the raw `JdbcClient` the
  controller used to hold. The Award count CTE and the `AWARD` branch of the
  document tally both take the scope predicate.
- **Known gap, deliberate:** IRB, Proposal, Negotiation and Subaward counts
  are **not** scoped, because no approved record rule existed for those
  modules when D2 was taken. Scoping Award alone makes the Award figures
  correct and leaves the others as they were; it does not make the dashboard
  as a whole authorized.
- **Partly closed by D6.** The Subaward count and the SUBAWARD branch of the
  document tally are now scoped, because Subaward has an approved rule.
- **STILL OPEN: Proposal Dashboard counts.** Proposal has an approved record
  rule and its endpoints are scoped, but `proposal_counts` (both
  `proposals` and `proposal_history_records`) and the `PROPOSAL` branch of
  the document tally in `DashboardService` were **not** changed when that
  landed and remain archive-wide. A scoped caller's Dashboard therefore
  reports Proposal figures covering records they cannot open. This is a real
  remaining gap, recorded rather than quietly closed, and it must be closed
  before enforcement is enabled. Negotiation and IRB stay unscoped because
  neither has an approved rule yet.

## D3 — Central sees archive-wide totals

> Central Users may see archive-wide totals.

Falls out of the design rather than needing a special case: a Central scope
renders `SqlFragment.NONE`, so the statement is the unscoped one.

- **Test:** `decision3_centralSeesArchiveWideTotals`.

## D4 — Department users see their departments and descendants

> Department users see only authorized department and descendant records.

- **Enforced:** `RecordScopeSqlBuilder` on `lead_unit_number`, with
  descendants expanded by `RecordAuthorizationService` only when the policy is
  `LEAD_UNIT_WITH_DESCENDANTS` **and** the grant sets `includeDescendants`.
- **Tests:** `decision4_departmentTotalsDescribeOnlyTheirOwnRecords`,
  `parentUnitWithDescendantsReachesTheChildUnit`,
  `parentUnitWithoutDescendantsReachesNothing`.

## D5 — Unauthorized hierarchy relatives are omitted entirely

> Unauthorized Award hierarchy relatives must be omitted entirely. Do not
> expose titles, identifiers or metadata as stubs.

Filtering happens on the edge list, **before** the edge map is built and
before the summary-card lookup runs, so nothing about an unauthorized
relative is read from the database at all.

A stub node was considered and rejected: "an Award exists here that you may
not see" discloses the family's shape, and the shape is most of what a
hierarchy conveys. The visible consequence is that a tree may have gaps and a
child whose parent is omitted is re-parented or dropped by the existing
dangling-reference handling. A partial tree is the correct answer to a
partially authorized question.

- **Test:** `decision5_unauthorizedHierarchyRelativesAreOmittedNotStubbed`.

## Derived rule — family completeness

Not one of the five, but required to honour them. A response spanning every
version of a family (`findFamily`, `findSequencePage`, `findSequence`, and so
the three AI endpoints built on them) requires **every** version to be
visible, decided per version. Under `FAMILY_WIDE` version scope this is
satisfied whenever any version is in scope and costs nothing; under
`PER_VERSION` it denies with `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS`.

Otherwise a caller cleared for the current version could be handed an AI
summary synthesised from versions they may not read, presented as the whole
Award — a wrong answer dressed as a complete one.

- **Tests:** `aFamilySpanningResponseRequiresEveryVersionToBeVisible`,
  `familyCompletenessIsSatisfiedWhenEveryVersionIsVisible`.

## Derived rule — cross-module indexes fail closed

The document and attachment search indexes `UNION` Award rows with Proposal,
Negotiation, Subaward and IRB rows in one statement. Scoping only the Award
branch would still disclose the other four. Until those modules have approved
rules, these endpoints are **refused** for any caller who is not unrestricted,
rather than silently returning Award rows outside their scope.

Refused loudly, not emptied: an empty page would look like an empty index.

- **Enforced:** `requireUnrestrictedForCrossModuleSearch` in
  `AttachmentSearchService`, `DocumentSearchService`, `DocumentExplorerService`.
- **Tests:** `crossModuleSearchIsRefusedForAScopedCallerRatherThanLeakingAwardRows`,
  `crossModuleSearchStillWorksForCentralAndWhenEnforcementIsOff`.

## D6 — Subaward access is inherited from its funding Awards (2026-10-26)

> A user may access a Subaward when they are authorized to view at least ONE
> of its funding Awards. ANY-match, not ALL-match.

No new rule: the Award rule, evaluated through `archive.subaward_funding`.
Department grants, descendant units and Research Staff contact access need no
Subaward equivalent because they are the Award clauses, applied to the funded
Award row.

Three consequences were accepted as part of the decision:

- A Subaward with **no** funding Award is denied to every non-Central caller.
  No explicit Subaward grant type exists in the `authz` schema, so there is
  nothing to fall back to. The size of that population is unmeasured — see
  the open profiling run in `SUBAWARD_AUTHORIZATION.md`.
- A **broken** funding reference (`award_id` resolving to no Award) is not
  authorization. `subaward_funding.award_id` has no foreign key, so this is a
  real shape in the data.
- Reaching a Subaward grants **nothing** on its Awards. The funding list
  omits unauthorized Awards entirely, following D5 — a shorter list, not a
  stubbed one, and therefore incomplete funding totals on that card.

`requisitioner_unit` and contact-based fallbacks were explicitly excluded
pending a separate business decision. The profiling SQL measures
`requisitioner_unit` for evidence only; nothing reads it.

- **Enforced:** `RecordScopeSqlBuilder.subawardRow` (lists, counts, version
  lists, semantic enrichment), `RecordAuthorizationService.canSeeSubaward`
  (single records), `SubawardArchiveService` (14 endpoints),
  `DashboardService` (the Subaward count and the SUBAWARD document branch).
- **Tests:** `SubawardRecordAuthorizationIntegrationTest` (40), three
  predicate tests in `RecordScopeSqlBuilderTest`,
  `GlobalSearchSemanticScopeTest` (3). The 19 named scenarios map to tests in
  `docs/security/SUBAWARD_AUTHORIZATION.md`.

## D6a — Funding inheritance is evaluated at FAMILY level (APPROVED, supersedes part of D6)

> A user may view all versions of a Subaward family when they are authorized
> to view at least one valid funding Award linked to any version of that
> family. ANY-match semantics unchanged.

Approved 2026-10-26 **after profiling the real dev archive**, which is the
only reason the defect was found. D6 as first implemented evaluated funding
per `subaward_id`. On real data that was unusable:

| | Per version (D6) | Family (D6a) |
|---|---|---|
| Subaward version rows reachable | 3,807 / 93,735 — **4.06%** | 91,945 / 93,735 — **98.09%** |
| Families reachable | 3,241 / 3,511 — 92.31% | 3,241 / 3,511 — 92.31% |
| Of a reachable family's own history | **4.14%** | 100% |

The cause: only **4,033 of 93,735** version rows carry any
`subaward_funding` row at all. Funding is attached almost entirely to a
family's newest version, so every older version authorised against nothing.
The archive's historical record — the point of the archive — would have been
invisible.

**`subaward_code` was verified as a family key before being relied on**, not
assumed:

- never null, never blank, 3,511 distinct values over 93,735 rows;
- no `subaward_id` spans more than one code, so a version belongs to exactly
  one family;
- families are plausible objects: 1–484 versions, mean 26.7.

Two caveats the verification surfaced, both load-bearing:

1. **`(subaward_code, sequence_number)` is NOT unique** — 2,577 pairs repeat,
   up to 5 rows deep. It is a family key, never a version key; `subaward_id`
   remains the only version identifier.
2. **`subaward_funding.subaward_code` disagrees with its parent row for 8
   rows**, naming genuinely different families (3046 vs 1970, 3203 vs 4094).
   The family is therefore derived by joining `archive.subaward` on
   `subaward_id`; reading the denormalised column would grant the wrong
   family. Pinned by
   `familyRule_isDerivedThroughSubawardIdNotTheDenormalisedFundingCode`.

Unchanged by this revision: ANY-match, D7's omission rule, no access to
unrelated Awards (the funding list still filters per authorized Award),
immediate revocation, deny for families with no resolvable funding Award
except Central.

Performance measured, not assumed: the family-wide `EXISTS` runs in **6.4 ms**
across all 93,735 rows, using the existing `ix_subaward_code_sequence`
index. No migration required.

- **Enforced:** `RecordScopeSqlBuilder.subawardRow` (correlates on
  `subaward_code`), `JdbcRecordFactsRepository.subawardFamilyFundingAwardIds`,
  `RecordAuthorizationService.canSeeSubaward`.
- **Tests:** `familyRule_*` (4) plus the revised
  `scenario15_theVersionListShowsTheWHOLEFamilyOnceAnyVersionIsFunded`, which
  previously asserted the opposite.

## D7 — Restricted funding Awards are omitted, and leave no trace (APPROVED)

> Display only authorized funding Awards. Never reveal the existence or
> number of restricted funding Awards.

Approved 2026-10-26. This is the same rule as D5, applied to a different
relationship: unauthorized records are omitted **entirely**, with no stub, no
count, no flag and no placeholder. Where D5 omits Award hierarchy relatives,
D7 omits funding Awards from a Subaward's funding list.

Review of PR #66 found that omission alone was not sufficient, because the
Funding tab heading counted the rows it received and stated them as the
record's Associated Awards. After filtering, a Subaward funded by three
Awards with one visible rendered **"Associated Award", singular** — a false
statement about the record.

Two candidate fixes were considered:

1. Return a `restricted` flag so the heading could say "1 shown; others
   restricted". True, but it discloses that more exist. **Rejected** — that
   is precisely the existence disclosure D7 forbids.
2. Drop the count from the heading entirely. Neither false nor disclosing.
   **Approved and implemented.**

The heading is now the constant string `Associated Awards` for every caller,
and `resolveAssociatedAwardsSectionLabel` takes no arguments so there is
nothing to count. The empty-state message is phrased about the caller's view
("are available"), never about the archive, so it reads identically whether
the archive holds none or the rows were withheld. The real number remains
visible to anyone entitled to it: one card per authorized funding row.

**The property this buys** is indistinguishability, which is stronger than
"the restricted Award's fields are absent": two Subawards whose authorized
funding is identical must be observably identical, even when one has further
funding Awards the caller cannot see. That is tested by comparing whole
serialized responses, not field by field, so a leak through a field nobody
thought to assert on still fails.

ANY-match is unchanged — one authorized funding Award still permits the
Subaward — and reaching a Subaward still grants nothing on its other funding
Awards.

- **Enforced:** `SubawardArchiveService.findFunding` (returns the authorized
  rows and nothing else); `resolveAssociatedAwardsSectionLabel`.
- **Tests:** eleven `d7_*` tests in
  `SubawardRecordAuthorizationIntegrationTest`, including the twin-Subaward
  indistinguishability pair and the whole-response-surface comparison, plus
  three heading tests in `subawardFundingPresentation.test.mjs`. The
  indistinguishability tests were verified to fail with the filter removed.

## Derived rule — every async search leg carries the access decision

Found in the same review. Global Search fans out onto worker threads, and
the per-request access decision does not follow them: off the request thread
`outcome()` fails closed to `Denied(EVALUATION_FAILED)`.

The five module legs go through `supplyLeg`, which wraps them in
`visibility.propagate(...)`. The **semantic** leg did not — it has its own
timing instrumentation and called `supplyAsync` directly — so once
enforcement were enabled every semantic search by a scoped caller would have
thrown `IdentityAccessDeniedException` and been reported as a failed module.
Fail-closed, never a leak, but silently broken, and an empty result is
indistinguishable from an empty archive.

Any new asynchronous leg must be wrapped the same way.

- **Enforced:** `GlobalSearchService.supplyLeg` and the semantic future.
- **Test:** `theSemanticLegRunsWithTheRequestsAccessDecisionReinstated`,
  verified to fail with the fix reverted.

## D8 — Negotiation access is HYBRID: lead unit OR a type-gated association (APPROVED)

> A user may view a Negotiation assigned to their authorized lead unit
> (including descendants where the grant permits), **or** inherited from an
> associated Award when the association type is explicitly Award, **or**
> from an associated Proposal when the type is explicitly Institutional
> Proposal. These are alternative paths.

Approved 2026-10-27, after profiling. Negotiation is **flat** — 10,775 rows,
10,775 distinct document numbers, no version family — so unlike every other
module there is no family question.

### Why hybrid, and not inheritance alone

| Basis | Covered | % |
|---|---|---|
| Award + Proposal inheritance only | 2,226 | **20.7%** |
| Lead unit only | 10,718 | 99.47% |
| **Both (implemented)** | **10,721** | **99.50%** |
| Central-only | 54 | 0.50% |

Inheritance alone would have hidden 79% of Negotiations from every
non-Central user. The two paths do not conflict: for all **2,223**
Award-associated Negotiations the lead unit equals the Award's lead unit,
0 differ.

### The association-type gate is a security control

`associated_document_id` is free TEXT and is populated on **every**
Negotiation, including all 8,533 typed `None`, where it holds the
Negotiation's own KC document number. Matched without the type gate, those
collide with an Award **workflow** document number **7,246** times and with
a `proposal_number` **6,980** times; **6,073 of 10,775** identifiers match
more than one record kind.

So the predicate and the evaluator both resolve associations **only** inside
a gate on `negotiation_association_type_description`, and `'Award'` resolves
against `award_number` — never `workflow_document_number`, which type-gated
matches nothing and could only add false hits.

`'Subaward'` (16 rows) grants **nothing**: its identifiers resolve
ambiguously and no reliable key was found. Those records reach the lead-unit
path like any other. **Unresolved — see below.**

### Lead-unit source, and its limitation

`archive.negotiation_search_attribute`, verified before use: strict 1:1 with
`archive.negotiation` (PRIMARY KEY + FOREIGN KEY ON DELETE CASCADE), zero
orphans in either direction, 10,718 of 10,775 carry a lead unit and **100%
of those match `archive.unit`**.

**It is an ETL-denormalised projection, not the system of record**, and for
the 8,533 unassociated rows the unit is self-asserted by the negotiator
rather than derived from an Award. That was accepted knowingly as the price
of 99.5% coverage.

### Preserved

D7 applies to the associated record: if a Negotiation is reachable by lead
unit but its Award or Proposal is not, the association is **omitted
entirely** — the response degrades to exactly the shape an unassociated
Negotiation produces, so a restricted association is indistinguishable from
none. No counts, no markers. `negotiator_person_id` (only 16 distinct) and
`contact_admin_person_id` are **not** authorization inputs.

- **Enforced:** `RecordScopeSqlBuilder.negotiationRow`,
  `RecordAuthorizationService.canSeeNegotiation`,
  `JdbcRecordFactsRepository.negotiationFacts`,
  `NegotiationArchiveService` (9 endpoints), `DashboardService`.
- **Tests:** `NegotiationRecordAuthorizationIntegrationTest` (30), including
  three collision regressions verified to fail with the type gate removed.
- **Real data:** SQL predicate vs Java evaluator, **0 disagreements across
  all 10,775 rows**. Predicate cost 19.9 ms, no new index.

### Open

- The **16 Subaward-typed** Negotiations have no reliable association key.
- The **54 with no basis** are Central-only.
### RESTRICTED attachment flag — investigated 2026-10-27, NOT changed

Asked to require "the existing RESTRICTED attachment policy" as a third
control, and to fail closed for flagged attachments **if no approved
authorization semantics exist**. Investigated before touching code, and that
condition does **not** hold: approved semantics exist and say the opposite.

`docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md` records a final
product decision in two parts, and states what survived both:

> the legacy `RESTRICTED` flag itself is never an access-control signal, in
> either version of the policy … **Do not gate any endpoint, query, or UI
> element on this flag's value** — the group membership is the only gate.

It was written, in its own words, so a later session would not "reintroduce
access blocking based on RESTRICTED … since a naive reading of
`RESTRICTED='Y'` looks like an access-control field even though this project
has deliberately decided it is not one." Migration V076 says the same:
*"Informational only: this column is never used for access control."*
Verified in code: the column is selected and displayed, never filtered.

**Functionality impact of failing closed, measured:** 20,406 of 28,923
Negotiation attachments carry `Y` — **70.55%**. Gating on the flag would
remove roughly seven of every ten Negotiation attachments from the archive.

So the three controls stack as approved, with the flag contributing nothing:

| Control | Where | Status |
|---|---|---|
| `ArchiveAttachmentViewer` group | controller, first | unchanged |
| Negotiation record authorization | service | **added by this PR** |
| RESTRICTED flag | — | informational by approved decision |

Nothing is bypassed: the record gate is additive to the group gate.
`recordAuthorizationDoesNotReadTheLegacyRestrictedFlag` pins the decision so
a future "fix" fails loudly.

**Reversing it needs a business decision**, with the 70.55% impact in view.
