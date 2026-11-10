# Release sequence — search fixes and security-test readiness

Five pull requests, reviewed at release-planning level on 2026-11-10. This
records the order to merge them, why that order, what was tested, how to get
back, and what is still waiting on a decision.

**Nothing in this release deploys anything by itself.** Four of the five PRs
change only code, tests and documents. The fifth (#123) adds operator tooling
that writes nothing until it is run with `--confirm`, which is approval-gated.

## The five

| PR | Branch | What it is | Depends on |
|---|---|---|---|
| **#127** | `fix/declare-reserved-migration-v081` | declares V081 reserved; chain check now runs in CI | nothing |
| **#124** | `qa/global-search-tc040-tc041` | honest result counts; the TC-040/041 investigation | nothing |
| **#125** | `fix/global-search-lexical-relevance` | the NSF relevance fix; evidence-based match labels | **#124** |
| **#126** | `qa/semantic-relevance-measurement` | TC-041 measurement tooling and procedure | nothing |
| **#123** | `feat/security-test-readiness` | six operator-path fixes; eight-role pilot; approvals | nothing |

## Recommended order

```
#127  ->  #124  ->  #125  ->  #126  ->  #123
```

**Verified, not assumed.** All five were test-merged into `origin/main` in
this exact order and every one merged **clean** — no conflicts in any file.
The fully integrated tree was then tested:

| Suite | Result |
|---|---|
| API (`mvn test`, includes the Testcontainers database suites) | **1990 pass**, 0 failures |
| UI (`npm run test`) | **826 pass**, 0 failures |
| UI lint (`oxlint`) | clean, no warnings |
| ETL (`uv run pytest`) | **1334 pass**, 0 failures |

### Why this order

**#127 first.** It is the only PR that fixes a *currently failing* test, and
it makes the migration-chain check run in CI. Merging it first means every
later PR is checked by it. It touches nothing else in the release.

**#124 before #125**, because #125 is stacked on it. #125's base is #124's
branch, so merging #124 first lets #125 retarget cleanly to `main` —
`delete_branch_on_merge` is false on this repository, so the branch survives
the merge and GitHub does **not** auto-retarget. Retarget explicitly:

```
gh pr edit 125 --base main
```

Done the other way round, #125's diff over `main` would include #124's
changes and the two could not be reviewed separately — which was the point of
stacking them.

**#126 and #123 are independent** of everything above and of each other.
Their position is a convenience, not a constraint.

## What changes behaviour, and what does not

| PR | Changes what a user sees? |
|---|---|
| #127 | no — test and tooling only |
| #124 | **yes.** "Showing 50 of 1434 matching records" with a per-type breakdown, instead of a bare count. Adds `moduleTotals` to the API response; `totalResults` keeps its value |
| #125 | **yes.** A short alphabetic search stops matching inside unrelated words. "NSF" no longer returns "Material Transfer Agreement"; it returns NSF-sponsored Awards. Match explanations change, and some disappear where they cannot be evidenced |
| #126 | no — Python tooling, never invoked by the application |
| #123 | no, until an operator runs a utility with `--confirm` |

### The one behaviour change worth briefing QA about

#125 changes **what matches**, not just how it is ranked. Three consequences
to expect:

1. A short alphabetic search returns **fewer** results. That is the fix.
2. Some results are **re-attributed** rather than removed. Award
   `100533-00001` still matches "NSF" — its sponsor really is the National
   Science Foundation — but now because of the sponsor, not because of
   "TRANSFER".
3. `archive.sponsor` must be populated for the acronym path to find anything.
   **This is unverified on DEV** and is the one open item below.

## Open item before #125 is released

**Is `archive.sponsor` populated on DEV?** It cannot be answered from outside
the database: the Explorer exposes no sponsor endpoint, and the Award API
returns the numeric `sponsor_code` (`301573`) and never the acronym.

One read-only query settles it:

```sql
SELECT count(*) AS sponsors,
       count(acronym) AS with_acronym
  FROM archive.sponsor;
SELECT acronym FROM archive.sponsor WHERE sponsor_code = '301573';
```

If it is empty the fix still removes the false positives and simply does not
deliver the true positive — and the search keeps working, which is asserted by
`WithoutSponsorReferenceData` in
`GlobalSearchLexicalRelevanceIntegrationTest`. So an empty table is a
disappointment, not a breakage, and does not block the merge. It does change
what we tell Lalitha.

## Rollback

| PR | How to get back |
|---|---|
| #124, #125 | revert the merge commit, then redeploy the UI (Amplify auto-builds `origin/main`) and the API. Both are code-only; no data is written and no migration is involved |
| #126, #127 | revert the merge commit. Neither is reachable from the running application |
| #123 | revert the merge commit. The operator utilities are behind Spring profiles no deployment sets, so an unreverted merge still runs nothing |

Nothing in this release writes to a database, changes a schema, alters IAM, or
touches Production. The migration set is unchanged: V088 remains the highest
applied version and V089 remains outside the applied directory, asserted by
`MigrationReleaseScopeTest`.

## Deployment, after merging

Merging changes nothing on DEV by itself. Two deployments follow, and they
are separate decisions:

1. **UI** — Amplify auto-builds from `origin` (the personal remote), not
   `bu`. A commit pushed only to `bu` is invisible to Amplify. Confirm the
   target commit exists on `origin` before expecting a build.
2. **API** — `ops/deploy-api.sh` from a clean detached worktree at the merge
   commit. It now prints the required follow-up at the end: run
   `ops/sync-operator-taskdefs.sh` and refresh `securityReadiness.json`, or
   the operator utilities will point at the previous image.

## What is still waiting on a decision

| # | Decision | Blocks |
|---|---|---|
| 1 | Is `archive.sponsor` populated on DEV? (one read-only query) | what we claim about #125's NSF behaviour — not the merge |
| 2 | Read access to `archive.search_embedding` + Bedrock calls, to run the TC-041 measurement | closing TC-041. **No new permission is needed** — see `TC-041_MEASUREMENT_PROCEDURE.md` |
| 3 | TC-041's recorded `passed` status, which now contradicts the investigation | the QA page showing an accurate status. Two options with costs are recorded in the investigation |
| 4 | The six DEV activation approvals | any live authorization testing |

None of the four blocks merging this release.

## Reviewed commits

Recorded so the review attaches to something specific rather than to a branch
name that keeps moving.

| PR | Head commit at review |
|---|---|
| #127 | `14fae95` |
| #124 | `b14adfb` |
| #125 | `ace32d4` |
| #126 | `69e8071` |
| #123 | see the PR; head moves with the approval checklist |
