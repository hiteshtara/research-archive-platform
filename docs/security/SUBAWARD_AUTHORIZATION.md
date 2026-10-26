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
| Global Search, semantic leg | `findActiveSummariesForCodes` is scoped in SQL; an unenriched SUBAWARD row is **dropped** for a scoped caller, gated on `!unrestricted()`. The leg is now wrapped in `visibility.propagate(...)` — see below |
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
most of what the card conveys.

Gated on `!unrestricted()`, so with enforcement off, or for Central, the list
is byte-for-byte what it was, dangling rows included.

### D7: the omission must leave no trace (APPROVED 2026-10-26)

> Display only authorized funding Awards. Never reveal the existence or
> number of restricted funding Awards.

The same rule as D5, applied to a different relationship. Review found that
omitting the rows was not sufficient on its own: the Funding tab heading
counted the rows it received and stated them as the record's Associated
Awards, so a Subaward funded by three Awards with one visible rendered
**"Associated Award", singular** — a false statement about the record.

A `restricted` flag ("1 shown; others restricted") was considered and
**rejected**: true, but it discloses that more exist. The approved answer is
to drop the count:

- `GET /{id}/funding` returns the authorized rows and **nothing else** — no
  count of what was removed, no flag, no placeholder row, no header.
- The heading is the constant `Associated Awards` for every caller.
  `resolveAssociatedAwardsSectionLabel` takes no arguments, so there is
  nothing to count; its implementation carries a do-not-reintroduce note.
- The empty-state message is phrased about the caller's view ("are
  available"), never about the archive, so it reads the same whether the
  archive holds none or the rows were withheld.
- The real number stays visible to anyone entitled to it: one card per
  authorized funding row.

**The property, stated properly.** Not "the restricted Award's fields are
absent" but *indistinguishability*: two Subawards whose authorized funding
is identical must be observably identical, even when one has further funding
Awards the caller cannot see. `SUB-TWINA` (funded by CHEM) and `SUB-TWINB`
(CHEM + PHYS) are compared as **whole serialized JSON** with only their own
identifiers neutralised — so a leak through a field nobody thought to assert
on still fails the test — across the funding list, workspace, amounts,
contacts, attachments, custom data, closeout, the version list, and the
paged search row. Both indistinguishability tests were verified to fail with
the filter removed.

### Financial totals

Checked, and no misleading total exists. Each Associated Award card shows its
**own** Award's `awardAmount`; there is no cross-card sum. The Amendment
History tab's `sumAmendmentTotals` aggregates `archive.subaward_amount`, which
is Subaward-owned data and is never filtered, so that total stays complete and
correct. The only misstatement was the count, fixed above.

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
| 17. Related Award information disclosure | `scenario17_*` (4) + `d7_*` (13, incl. twin indistinguishability and 404-body equality) + 3 heading tests in `subawardFundingPresentation.test.mjs` |
| 18. Global Search and semantic isolation | `scenario18_*` (2 tests) + `GlobalSearchSemanticScopeTest` (4 tests, incl. thread propagation) |
| 19. Enforcement-disabled compatibility | `scenario19_*` (3 tests) |

Plus, not in the requested list but required to trust the above:
`theSqlPredicateAndThePerRecordDecisionAgreeOnEverySubaward`,
`theFactsQueryReturnsOnlyResolvableFundingAwards`,
`centralCanOpenASubawardThatHasNoFundingAwardAtAll`,
`everySubawardKeyedServiceMethodRefusesADeniedCaller`,
`theDashboardSubawardCountIsScopedAndTheStatementIsValidSql`, and three
predicate tests in `RecordScopeSqlBuilderTest`.

`SubawardRecordAuthorizationIntegrationTest`: 56 tests, disposable PostgreSQL
(`pgvector/pgvector:pg17`) with every committed migration applied. Registered
in BOTH `ci.yml` lists (the unit-test exclusion and the database-suite
inclusion), as a new Testcontainers suite must be.

## Found in review: the semantic leg had no access decision

Every module leg of Global Search goes through `supplyLeg`, which wraps it in
`visibility.propagate(...)` so the request's access decision is reinstated on
the worker thread. The **semantic** leg has its own timing instrumentation and
calls `CompletableFuture.supplyAsync` directly — so it was the one leg running
with nothing propagated.

Off the request thread `RecordAuthorizationService.outcome()` fails closed to
`Denied(EVALUATION_FAILED)`, and the enrichment lookups call
`requireProvisioned()`, which throws `IdentityAccessDeniedException` on that
outcome. Once enforcement were enabled, **every semantic Global Search by a
scoped caller would have thrown** and been reported as a failed `SEMANTIC`
module.

No disclosure — it fails closed in the safe direction — but a silently broken
feature is its own kind of wrong answer, and "search returned nothing" is
indistinguishable from "the archive has nothing". The leg is now propagated
like the other five. With enforcement off `propagate()` returns the supplier
unchanged, so nothing about today's behaviour moves.

This predates the Subaward work (it arrived with the Award and Proposal
wiring) and affected AWARD and PROPOSAL enrichment too; it is fixed here
because this is where it was found.
`theSemanticLegRunsWithTheRequestsAccessDecisionReinstated` reproduces the
original failure — verified to fail with the fix reverted.

## Metadata-leak audit (2026-10-26)

Every Subaward response surface inspected for indirect disclosure of a
restricted funding Award. The decisive structural fact:

> **`findFunding` is the only query in `SubawardArchiveRepository` that
> touches an Award table at all.** Lines 625–634 join `archive.award_version`
> and `archive.award_amount_info`; no other Subaward query reads Award data.

So restricted Award metadata cannot reach any other Subaward response,
because no other response ever loads it.

| Surface | Finding |
| --- | --- |
| Funding Award list | filtered; the only Award-reading query |
| Subaward details (`SubawardRowResponse`) | carries `awardPrimeSponsorName`, `awardSponsorName`, `fedAwardProjDesc` — **not a leak**: `oracle/subaward/export_subawards.sql` selects all three from the SubAward row itself (`s.`), so they are Subaward-owned attributes that merely read "award_*", not per-funding-Award data |
| Template info | `mpiAward` comes from `sti.mpi_award`, the Subaward Template Info table — Subaward-owned |
| Version history | scoped per version, page and count together |
| Search results, counts, pagination totals | one predicate in one `WHERE`; `totalElements` always equals what paging can reach |
| Global Search (keyword) | inherits the predicate via `findPage` |
| Global Search (semantic) | enrichment scoped in SQL; unenriched SUBAWARD rows dropped for scoped callers |
| Dashboard aggregates | Subaward count and SUBAWARD document branch scoped |
| Attachment metadata | no Award fields in `SubawardAttachmentResponse` |
| Attachment downloads | dual gate; `Content-Disposition` carries the Subaward's own filename |
| Reports / closeout / notepad / notifications / custom data | Subaward-owned tables, no Award join |
| Related Award links | `navigableCurrentAwardId` only ever present on an authorized funding row |
| **Error messages** | checked specifically, see below |

### Error-message indistinguishability

The 404 body echoes `exception.getMessage()`, so matching status codes are
not enough. A denied record raises `RecordNotAccessibleException` ("Record
not found") while a missing one raises `NoSuchElementException` ("Subaward
not found: &lt;id&gt;") — different strings, which would let a caller
enumerate ids and learn which Subawards exist but are denied.

**It does not happen, and not by luck.** For a scoped caller the
authorization gate runs first and answers "Record not found" in both cases,
because `canSeeSubaward` finds no funding rows either way; the detailed
message sits behind that gate and is unreachable while scoped. This depends
on `archive.subaward_funding.subaward_id` having a **foreign key** to
`archive.subaward` (V018) — without it a funding row could name a
nonexistent Subaward, authorization could pass, and the detailed message
would surface. `d7_aDeniedSubawardAndANonexistentOneProduceTheIDENTICAL404Body`
pins the invariant and names that constraint as its dependency.

### Residual, accepted

- **Timing.** The predicate runs a correlated `EXISTS`; a denied lookup and a
  missing one do different amounts of work. Not addressed — the archive has
  no timing-attack threat model, and addressing it would mean constant-time
  query execution.
- **Enforcement OFF.** None of the above applies today. Every statement here
  describes behaviour once the flag is turned on.

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

## Open: the profiling run (attempted 2026-10-26, BLOCKED)

`docs/security/subaward-funding-profile.sql` answers all ten Phase 1
questions and is read-only — every statement a `SELECT`, output limited to
counts and de-identified shapes, identifiers hashed. **It has still not been
run.** Reported rather than estimated: there are no profiling figures in this
document, and none should be inferred from anything in it.

What was checked, so the blocker is evidence and not an assumption:

| Check | Result |
| --- | --- |
| `aws sts get-caller-identity` | works — account `770203350335`, role `Shibboleth-InfraMgt` |
| SSM analyst credential | present: `/research-archive-platform/dev/postgres-analyst` |
| `scripts/mac-show-analyst-password.sh` | present and executable |
| CloudShell security group `sg-002be83bf1cf249fa` | intact — 0 ingress, 1 egress |
| RDS instance | `available`, `PubliclyAccessible: false` |
| EC2 instances in `vpc-0590614d7cfcdedf6` | **0** — no bastion, consistent with CLAUDE.md's hard stop |
| ECS cluster `…-dev-etl` | 0 services, 0 running tasks |
| ETL CLI | no arbitrary-SQL flag; `--migrate-only` and per-loader flags only |
| `aws cloudshell …` | not a valid CLI command — CloudShell has no API/CLI execution surface |

So the approved path is **fully intact and ready** — nothing is broken or
missing. The blocker is that its final hop is browser-interactive by design:
steps 2–5 of `docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md` require opening the
AWS console, selecting the `research-archive-analysis` VPC environment, and
pasting a clipboard-held password into `psql`. There is no non-interactive
route into that VPC that does not mean provisioning a bastion or injecting a
command into an ECS task definition — both outside the approved path and both
explicitly out of scope.

**A human needs to run it.** There is now a step-by-step for exactly this
task: [`SUBAWARD_PROFILING_PROCEDURE.md`](SUBAWARD_PROFILING_PROCEDURE.md).
It accounts for the constraint that trips people up — the CloudShell security
group allows egress only to RDS:5432, so that shell has no internet and
cannot clone this repo; the SQL goes in by paste.

### Why one of the answers matters before enforcement is enabled

The implementation does not depend on the profiling results — it is correct
for every shape the data can take. But **Q2/Q3 size the blast radius**: the
share of Subawards with no resolvable funding Award. Every one of those
becomes invisible to every non-Central user the moment enforcement is turned
on, because no explicit Subaward grant type exists to fall back to.

If that share is small, enabling enforcement is uncontroversial. If it is
large, it is a business conversation — and the honest options are to create a
Subaward grant type, or to accept the loss deliberately, not to invent a
`requisitioner_unit` fallback.

Q6 (dangling references) and Q10 (funding varying across versions) are
already handled correctly whatever the counts turn out to be — the INNER join
and the per-version scoping respectively — so they are informational.
