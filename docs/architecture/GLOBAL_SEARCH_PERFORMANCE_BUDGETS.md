# Global Search Performance Budgets

## Purpose

Records the reproducible baseline for Global Search's five-domain fan-out
(`GlobalSearchService.java`), the performance budgets each part of it is
held to, and the exact tooling to re-measure against those budgets as the
archive grows. This is a living record, not a one-time measurement — the
baseline in it will go stale as more data loads; the tooling to refresh it
does not.

## How the baseline was captured

`etl/run_search_diagnostics.py` (a read-only diagnostics script) run via
`scripts/run-search-diagnostics.sh` as a one-off ECS Fargate task on the
existing `research-archive-platform-dev-loader` task family — no bastion,
no public RDS, no new long-lived infrastructure, reusing the loader's own
VPC/subnet/security-group/Secrets-Manager wiring exactly. Two runs on
2026-08-07: `--suite global-search-baseline` (row counts, extension/index
inventory, EXPLAIN (ANALYZE, BUFFERS) for representative searches per
domain) and `--suite isolate-anomaly` (a targeted re-run that confirmed an
apparent 855ms/379ms slowdown on the first run was a one-time warmup
artifact, not a real cost — both queries landed at 7-10ms on isolated
re-run).

Rerun either suite the same way after any major archive population
increase:

```bash
export AWS_PROFILE=bu-nprd
export ECR_REPOSITORY_URI=770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader
export SUBNET_IDS=subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed
export SECURITY_GROUP_ID=sg-0817befcc5b4affc9
scripts/run-search-diagnostics.sh --suite global-search-baseline
```

## Baseline (2026-08-07)

| Domain | Rows / current records | Trigram indexed? | Exact identifier | Lexical (keyword/PI/sponsor) | No match |
|---|---|---|---|---|---|
| Award | 47,939 / 8,596 current | Yes (title, sponsor_name, lead_unit_name, modification_number, PI full_name) | 7.9-9.9ms | 10.4-21.4ms | 13.2ms |
| Proposal | 17,739 / 5,159 families | No | 14.1ms | 16.7ms | 18.8ms |
| Negotiation | 10,775 (no version concept) | No | 7.5-8.8ms | 11.0ms | 11.0ms |
| Subaward | 513 / 27 families | No | 0.43ms | 1.21ms | 0.58ms |
| IRB | **0 rows in dev** | One irrelevant index (`pi_full_name`) — real path is an unindexable view | Unmeasurable | Unmeasurable | 0.24ms (empty-table short-circuit) |

Every non-IRB domain is well inside budget today, indexed or not — see
Decisions for why that's not a reason to add indexes preemptively.

## Performance budgets

| Tier | Budget | Applies to |
|---|---|---|
| Exact identifier lookup | **< 50ms** | A single domain's exact business-number/document-number match |
| Lexical domain search | **< 100ms** | A single domain's keyword/PI/sponsor substring search |
| Complete Global Search | **< 250ms** | The full five-domain fan-out, merged/ranked/deduplicated, as returned by `GET /api/global-search` |

**If a domain exceeds its budget**: re-run the relevant EXPLAIN (ANALYZE,
BUFFERS) via `run_search_diagnostics.py`, read the actual plan, and add
*only* the index the plan justifies — never a speculative index added
because a column "seems searchable." This is the same discipline that kept
Step 2 of the Performance Sprint from adding trigram indexes to Negotiation/
Proposal/Subaward when the live data showed no current need for them
(see Decisions).

## IRB — separately flagged, not yet evaluable

`archive.irb_protocol_version` and `archive.v_global_search` both have
**zero rows** in the dev database as of this baseline. The architectural
concern already on record — `v_global_search` is a plain `CREATE VIEW`
(not materialized) that recomputes `DISTINCT ON` + four `STRING_AGG` CTEs
on every single query, with no index possible on the resulting
`search_text` column — is real, but literally unmeasurable against empty
tables. **This baseline must be re-captured once real IRB data is loaded**,
and the materialized-view-vs-dedicated-table decision (Global Search v2
audit, Phase 3) should not be made on the current empty-table numbers.

## Decisions

- **No speculative trigram indexes added.** The original Performance
  Sprint plan treated adding pg_trgm indexes to Negotiation/Proposal/
  Subaward as the default next step; the live baseline showed every query
  on all three already lands well under budget via sequential scan, because
  their tables are small (10,775 / 17,739 / 513 rows). Indexing them now
  would be optimizing for a problem the data says doesn't exist yet — the
  budgets above exist so this becomes a data-driven decision every time,
  not a one-time judgment call.
- **`run_search_diagnostics.py` and `scripts/run-search-diagnostics.sh`
  are the permanent benchmark tooling**, not a one-off artifact — kept in
  the repo specifically to be re-run, not rewritten, the next time this
  question comes up.
- **pg_trgm is already `CREATE EXTENSION`'d** (v1.6) — Award's own V053
  migration required it. No extension-setup step is needed the day a real
  index becomes justified.
