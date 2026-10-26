# Subaward record authorization

Status: **implemented, not enabled.** `app.authorization.enforcement-enabled`
remains unset, which is `false`, in every environment. Nothing in this
document changes what the deployed site does today.

Approved business rule, 2026-10-26:

> A user may access a Subaward when they are authorized to view at least ONE
> of its funding Awards. This is an ANY-match rule, not an ALL-match rule.

## How it works

There is no Subaward authorization rule. There is the Award rule, evaluated
through `archive.subaward_funding`. That reuse is deliberate: department
grants, descendant-unit expansion and Research Staff contact access all behave
on a Subaward exactly as they do on an Award, because they **are** the Award
clauses, rendered against the funded Award row.

Two code paths decide, and they must agree:

| Path | Decides | Where |
| --- | --- | --- |
| SQL predicate | lists, counts, pagination | `RecordScopeSqlBuilder.subawardRow` |
| Per-record check | single-record reads | `RecordAuthorizationService.canSeeSubaward` |

The predicate:

```sql
EXISTS (SELECT 1 FROM archive.subaward_funding az_sf
        JOIN archive.award_version az_sa ON az_sa.award_id = az_sf.award_id
        WHERE az_sf.subaward_id = <alias>.subaward_id
          AND az_sf.award_id IS NOT NULL
          AND <the ordinary Award predicate, applied to az_sa>)
```

Three properties fall out of the shape rather than needing special cases:

- **No funding row** → `EXISTS` is false → denied.
- **A funding row whose `award_id` resolves to nothing** → the INNER join
  drops it, so a dangling reference can never authorise. There is no foreign
  key on `subaward_funding.award_id`, so dangling references are possible.
- **ANY-match** → one qualifying Award is enough and `EXISTS` stops looking.
  An ALL-match rule would need `NOT EXISTS` over the unauthorized ones and was
  not approved.

Under a `FAMILY_WIDE` version policy the Award clause is family-wrapped
**anchored on the funded Award row (`az_sa`)**, not on the outer Subaward row
— a Subaward has no `award_number`. This is not cosmetic: `canSeeAward` is
family-wide under that policy, so a predicate testing only the funded version
would deny in SQL what the evaluator permits in Java.
`familyWideSubawardAnchorsTheFamilyWrapOnTheFundedAwardNotTheSubaward` and
`theSqlPredicateAndThePerRecordDecisionAgreeOnEverySubaward` pin both halves.

Central short-circuits in both paths. Its predicate is `SqlFragment.NONE`, so
every Subaward is listed; deciding the record through funding inheritance
would answer false for an unfunded one, and Central would see a row that 404s
when opened. This suite caught exactly that during development.

## Endpoint inventory

All 14 endpoints on `SubawardArchiveController` (`/api/subawards`). No
controller reaches `SubawardArchiveRepository` directly — the service is the
only consumer, verified by grep.

| # | Endpoint | Gate | Notes |
| --- | --- | --- | --- |
| 1 | `GET /` (search) | SQL predicate on `s`, in the same `WHERE` as the filters | count and page share it |
| 2 | `GET /{id}` | `requireSubaward` | |
| 3 | `GET /{id}/versions` | `requireSubaward` **+ SQL predicate on the version list and its count** | versions of one family may fund different Awards |
| 4 | `GET /{id}/amounts` | `requireSubaward` | |
| 5 | `GET /{id}/contacts` | `requireSubaward` | |
| 6 | `GET /{id}/custom-data` | `requireSubaward` | |
| 7 | `GET /{id}/funding` | `requireSubaward` **+ per-row Award filter** | see "Related-Award disclosure" |
| 8 | `GET /{id}/attachments` | `requireSubaward` | |
| 9 | `GET /{id}/attachments/{aid}/download` | `ArchiveAttachmentViewer` role (controller, unchanged) **+** `requireSubaward` (service) | both must hold |
| 10 | `GET /{id}/template-info` | `requireSubaward` | |
| 11 | `GET /{id}/closeout` | `requireSubaward` | |
| 12 | `GET /{id}/reports` | `requireSubaward` | |
| 13 | `GET /{id}/notepad` | `requireSubaward` | |
| 14 | `GET /{id}/notifications` | `requireSubaward` | |

Coverage is kept by reflection, not by this table:
`everySubawardKeyedServiceMethodRefusesADeniedCaller` fails if a public
`SubawardArchiveService` method taking a `subaward_id` is added without a
gate.

Subaward also appears in three places outside its own controller:

| Surface | Behaviour |
| --- | --- |
| Global Search, keyword leg | goes through `SubawardArchiveService.findPage`, so it inherits the predicate |
| Global Search, semantic leg | `findActiveSummariesForCodes` is scoped in SQL; an unenriched SUBAWARD row is **dropped** for a scoped caller, gated on `!unrestricted()` |
| `GET /api/awards/{awardId}/funding-subawards` | already gated by `requireAward`; consistent with the rule, since a user authorized for an Award is authorized for every Subaward it funds |
| Archived File Finder (`DocumentSearchService`) | fail-closed already — `requireUnrestrictedForCrossModuleSearch` refuses any scoped caller, for every module |
| Dashboard (`DashboardService`) | the Subaward count and the SUBAWARD branch of the document tally are now scoped (closing part of D2's gap); Proposal, Negotiation and IRB figures there remain unscoped |

## Related-Award disclosure

The ANY-match rule's one real risk. Reaching a Subaward needs only ONE
accessible funding Award, but `GET /{id}/funding` carries every funding
Award's number, title, status, sponsor, amount and a navigable link. A user
who inherits access through Award A would otherwise be handed Award B's
metadata through the very record A granted.

Unauthorized funding rows are therefore **omitted entirely**, following
decision D5 (no stubs): a placeholder saying "a funding Award exists here that
you may not see" still discloses the relationship, and the relationship is
most of what the card conveys. The visible consequence is a shorter funding
list and, with it, incomplete funding totals on that card — a partial list is
the correct answer to a partially authorized question.

Gated on `!unrestricted()`, so with enforcement off, or for Central, the list
is byte-for-byte what it was, dangling rows included.

Access to a Subaward grants nothing on its Awards: the Award endpoints keep
their own gates (`scenario17_reachingASubawardGrantsNothingOnItsOtherFundingAwards`).

## Security coverage matrix

| Scenario (Phase 3) | Covered by |
| --- | --- |
| 1. One funding Award, authorized | `scenario1_oneFundingAwardAuthorized` |
| 2. One funding Award, unauthorized | `scenario2_oneFundingAwardUnauthorized` |
| 3. Two funding Awards, one authorized | `scenario3_twoFundingAwardsOneAuthorizedIsEnough` |
| 4. Two funding Awards, neither authorized | `scenario4_twoFundingAwardsNeitherAuthorized` |
| 5. Two funding Awards, both authorized | `scenario5_twoFundingAwardsBothAuthorizedIsStillOneRow` |
| 6. No funding Award | `scenario6_aSubawardWithNoFundingAwardIsDenied` |
| 7. Broken Award reference | `scenario7_aBrokenFundingReferenceIsNotAuthorization` |
| 8. Department and descendant-unit grants | `scenario8_*` (3 tests) |
| 9. Research Staff Award-family grants | `scenario9_*` (3 tests) |
| 10. Immediate revocation | `scenario10_*` (2 tests) |
| 11. No assigned grants | `scenario11_*` (2 tests) |
| 12. Suspended identity | `scenario12_aSuspendedIdentityIsDeniedBeforeAnyQueryRuns` |
| 13. ARCHIVE_ADMIN without data grants | `scenario13_archiveAdminWithoutDataGrantsSeesNoRecords` |
| 14. Direct URL denial | `scenario14_*` (2 tests, all 13 endpoints) |
| 15. Search/count/pagination parity | `scenario15_*` (4 tests) |
| 16. Attachment dual authorization | `scenario16_theRecordGateRefusesTheDownloadBeforeAnyStorageLookup` |
| 17. Related Award information disclosure | `scenario17_*` (3 tests) |
| 18. Global Search and semantic isolation | `scenario18_*` (2 tests) + `GlobalSearchSemanticScopeTest` (3 tests) |
| 19. Enforcement-disabled compatibility | `scenario19_*` (3 tests) |

Plus, not in the requested list but required to trust the above:
`theSqlPredicateAndThePerRecordDecisionAgreeOnEverySubaward`,
`theFactsQueryReturnsOnlyResolvableFundingAwards`,
`centralCanOpenASubawardThatHasNoFundingAwardAtAll`,
`everySubawardKeyedServiceMethodRefusesADeniedCaller`,
`theDashboardSubawardCountIsScopedAndTheStatementIsValidSql`, and three
predicate tests in `RecordScopeSqlBuilderTest`.

`SubawardRecordAuthorizationIntegrationTest`: 40 tests, disposable PostgreSQL
(`pgvector/pgvector:pg17`) with every committed migration applied. Registered
in BOTH `ci.yml` lists (the unit-test exclusion and the database-suite
inclusion), as a new Testcontainers suite must be.

## What is NOT implemented, and why

- **`requisitioner_unit` fallback.** Not implemented. It is not an
  authoritative department key and no business decision authorises it. The
  profiling SQL measures it for evidence only.
- **Contact-based Subaward access.** Not implemented; same reason.
- **A grant on a Subaward itself.** The approved rule mentions "a separately
  approved explicit Subaward grant" for the no-funding case. No such grant
  type exists in the `authz` schema and none was approved, so a Subaward with
  no accessible funding Award is denied to every non-Central caller. If that
  population turns out to be material, it is a business decision, not a code
  change — see below.

## Open: the profiling run

`docs/security/subaward-funding-profile.sql` answers all ten Phase 1
questions and is read-only (every statement a `SELECT`). **It has not been
run.** There is no supported Mac-to-RDS route and no CLI path into the VPC
from this session; it needs a human at an AWS CloudShell VPC environment —
see `docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md`. The counts in Phase 1 of
the request are therefore still unanswered.

The implementation does not depend on those answers — it is correct for every
shape the data can take — but **one of them changes the impact**: Q2/Q3, the
share of Subawards with no resolvable funding Award. Every one of those is
invisible to every non-Central user once enforcement is on. If that share is
large, that is a business conversation before enabling enforcement, not a
reason to invent a fallback rule.
