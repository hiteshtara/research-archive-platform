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
| API (`mvn test`, includes the Testcontainers database suites) | **2001 pass**, 0 failures |
| UI (`npm run test`) | **841 pass**, 0 failures |
| UI lint (`oxlint`) | clean, no warnings |
| ETL (`uv run pytest`) | **1345 pass**, 0 failures |

Re-run on 2026-11-10 after the measurement, the current-assessment layer and
the PR #125 reconciliation changed branch content. The earlier figures (API
1990 / UI 826 / ETL 1334) described the tree before those.

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

## The #125 open item: answered

**`archive.sponsor` is populated, and the acronym is exactly what the fix
needs.** Measured read-only against DEV on 2026-11-10 as `archive_api`:

| Check | Result |
|---|---|
| `archive.sponsor` rows | **7,246** (772 carry an acronym) |
| `sponsor_code 301573` | `('301573', 'National Science Foundation', 'NSF', active)` |
| Sponsors whose acronym is exactly `NSF` | **exactly one** — no ambiguity |
| Award `100527-00001` reachable by the acronym path | **yes** |

So #125 delivers the true positive on real data, not only in fixtures.

And the false positives it removes, measured on the real corpus:

| Field | Substring match for "NSF" | Word-start match |
|---|---|---|
| current Award titles | **298** | **107** |
| Negotiation agreement types | **4,953** | **0** |

191 of 298 Award titles (64%) were matching on "TRANSFER" or "TRANSFORMING",
and **every one** of the 4,953 Negotiation matches was "Material Transfer
Agreement". That is the inversion TC-040 reported, quantified.

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
| 1 | ~~Is `archive.sponsor` populated?~~ | **answered** — 7,246 rows, acronym `NSF` present, award reachable |
| 2 | ~~Run the TC-041 measurement~~ | **done** — 15 embedding calls, read-only; results in `TC-041_MEASURED_RESULTS.md` |
| 3 | ~~TC-041's status contradicting the investigation~~ | **resolved** — current assessment is now derived; the snapshot is untouched |
| 4 | Build the corpus-vocabulary relevance gate the measurement supports | closing TC-041. Changes what the API returns, so it is its own change |
| 5 | The six DEV activation approvals | any live authorization testing |

Neither remaining item blocks merging this release.

## Reviewed commits

Recorded so the review attaches to something specific rather than to a branch
name that keeps moving.

| PR | Branch head (remote) | GitHub PR head |
|---|---|---|
| #127 | `14fae95` | `14fae95` ✓ |
| #124 | `863ad72` | `863ad72` ✓ |
| #125 | `7d1b214` | `7d1b214` ✓ |
| #126 | `8b3c904` | `8b3c904` ✓ |
| #123 | `a05bdbc` | `a05bdbc` ✓ |

All five reconciled on 2026-11-10: the remote branch head and the head
GitHub reports for the PR are the same commit in every case.

### The #125 discrepancy, resolved

An earlier report listed #125's reviewed head as `ace32d4` while GitHub
showed `d9d5897`. Diagnosed: **unpushed commits**, not a branch mismatch.
`d9d5897` was the FIRST merge of #124 into #125 and is an *ancestor* of
`ace32d4`; three commits existed only locally — the sponsor-degradation tests
(76 lines), the TC-041 contradiction record arriving via a second merge of
#124, and the route-verification note.

Two consequences, both corrected:

- The only CI run for #125 had been against `d9d5897`, not the content I
  reported as reviewed.
- The first integrated verification merged `origin/` refs, so it also
  described `d9d5897` — which is why the API count has moved.

`ace32d4` was pushed, #124 was merged in again (`7d1b214`), CI re-run, and the
integrated figures refreshed above. **Nothing was reviewed that is not now on
GitHub.**
