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
- **Still open after D6 and after Proposal.** Proposal and Subaward now have
  approved rules and scoped endpoints, but the **Dashboard counts for them
  were not changed** by that work and remain unscoped. This is a real
  remaining gap, named here rather than quietly closed: a scoped caller's
  Dashboard would report archive-wide Proposal and Subaward figures. It must
  be closed before enforcement is enabled.

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
