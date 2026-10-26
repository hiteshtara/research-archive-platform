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
