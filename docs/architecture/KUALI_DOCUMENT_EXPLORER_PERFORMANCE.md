# Kuali Document Explorer — Performance Investigation and Fix (2026-08-12)

## Symptom

After the Award archive was expanded from a partial sample (49,827 rows)
to the full available Kuali staging population (267,386 rows,
2026-08-12), the Kuali Documents page began spinning indefinitely on its
initial, unfiltered load. Filtered searches (e.g. an exact document
number) still returned quickly.

## Root cause

`GET /api/v1/documents` with no filters set (`DocumentExplorerController` →
`DocumentExplorerService.search()` → `DocumentExplorerRepository`) issued
**three separate full evaluations** of the same fixed four-module
`UNION ALL` CTE (`count()`, `search()`, `moduleFacets()`) on every request,
regardless of whether any filter was actually applied. Each evaluation
independently scanned and aggregated:

- `archive.award_person` (325,468 rows) via a `DISTINCT ON` per-`award_id`
  PI resolution (`award_pi`)
- `archive.award_person_unit` (334,371 rows) via a `GROUP BY award_id`
  unit count (`award_unit_counts`)
- `archive.award_person` again via a second `GROUP BY award_id` person
  count (`award_person_counts`)
- `archive.award_version` (267,386 rows) itself

— for **every** Award row, even though the default page only displays 25.
`search()`'s own `ORDER BY ... LIMIT 25 OFFSET 0` could not push the limit
down through the `UNION ALL`: Postgres had to materialize and sort the
full ~296,413-row unioned result (267,386 Award + 17,739 Proposal +
10,775 Negotiation + 513 Subaward) before taking the top 25.

Measured (dev Postgres, `EXPLAIN (ANALYZE, BUFFERS)`, default unfiltered
request, page=0, size=25, sort=documentNumber):

| Query | Wall time |
|---|---:|
| `count()` | 13.4s |
| `search()` | 12.9s |
| `moduleFacets()` | 12.3s |
| **Total per page load** | **≈38.6s** |

Isolated by module: **AWARD alone = 12.0s** (essentially the entire cost);
PROPOSAL=0.56s, NEGOTIATION=1.4s, SUBAWARD=0.1s. Filtered exact
`documentNumber` search: 0.10s/0.07s (index-backed, unaffected). The
`search()` plan's outermost node was a `Sort` processing an estimated
296,455 rows to return 25 (12.7s of the 12.9s total). The dominant cost
nodes were full, **unfiltered but correctly-indexed** scans of
`award_person_unit`, `award_person`, and `award_version` feeding Merge
Joins — not missing indexes, and no row-multiplying fan-out (the
pre-aggregating CTEs correctly held the Award branch at exactly 267,386
rows throughout).

One separate, non-performance finding from this investigation: exactly
one `(module, document_number)` pair — `AWARD` / `141590` — has **8**
archived rows sharing one workflow document number, not 1. Pre-existing
Kuali data characteristic, not caused by the load or this fix — see the
Identity section below for how the fix preserves this.

Full investigation detail (checklist verdicts, plan evidence) is preserved
in this session's transcript; this document focuses on the implemented
fix and its measured results.

## Implemented fix

Scope: the default, fully-unfiltered request only. Any filter at all
(including a bare module filter) continues to use the original,
byte-for-byte unchanged `count()`/`search()`/`moduleFacets()` — this
investigation did not find those paths to be the reported problem, and
changing filtered-search correctness was explicitly out of scope.

### New code paths (`DocumentExplorerRepository`)

- `countDefault()` — sums four lightweight, independent
  `SELECT COUNT(*) FROM <table> WHERE <same base predicate as the CTE
  branch>` queries (one per module table), never touching
  `award_person`/`award_person_unit`.
- `moduleFacetsDefault()` — the same four counts, returned as
  `(module, n)` rows instead of summed.
- `searchDefaultPage(limit, offset, sort)` — a two-stage query:
  1. **`documents_light`**: the same four-module union, but with only
     directly-available columns per row (no `award_pi`/
     `award_unit_counts`/`award_person_counts`). Award's primary-person/
     unit-count/person-count columns are `NULL`/`0` placeholders here.
     Negotiation's `lead_unit` and Subaward's `sponsor`/contact-count
     joins are kept (they're bounded by Negotiation's/Subaward's own
     small row counts, not by Award's).
  2. **`paginated`**: `SELECT * FROM documents_light ORDER BY ... LIMIT
     :limit OFFSET :offset` — sorted and paginated down to the requested
     page *before* anything Award-specific is computed.
  3. **Award-only enrichment**, scoped to `WHERE award_id IN (SELECT
     award_id FROM paginated_award_ids)` — at most `size` (≤100) Award
     ids, resolved via the existing indexes
     (`ix_award_person_award`/`ix_award_person_unit_award`) instead of a
     267,386-row scan. Proposal/Negotiation/Subaward never needed this
     step — their "primary person" is already a single denormalized
     column on the row, not a `DISTINCT ON` pick across many rows.

### Identity and pagination determinism

`document_number` is not globally unique (the AWARD/141590 case above).
The light path adds `source_record_id` — the true per-row identity
(`award_id` / `proposal_id:version_number` / `negotiation_id` /
`subaward_id`) — as a final `ORDER BY` tie-breaker after `document_number`
for every supported sort, so `LIMIT`/`OFFSET` pagination is deterministic
across requests and never silently drops or merges rows that share a
document number. This is a light-path-only addition; the original
filtered path's sort (`document_number` alone as the final tie-breaker)
is unchanged, since touching its behavior was out of scope — it has the
same theoretical non-determinism for a duplicate-document-number filtered
result, unaddressed here.

### Routing (`DocumentExplorerService`)

`isUnfiltered(filter)` — true only when every filter field is at its
default (empty string / `false` / `null`; sort/page/size are not filters
and don't affect this check, since all four approved sorts are available
directly on the light union without the expensive joins). `search()`
branches on this once and uses it consistently for the count, facets, and
result-page calls within a single request.

## Before / after (dev Postgres, live)

| Query | Before | After |
|---|---:|---:|
| Default `count()` | 13.4s | **0.88s** |
| Default `moduleFacets()` | 12.3s | **0.046s** |
| Default `search()` page 0 | 12.9s | **2.5s** (cold) / <1s (warm) |
| **Total, default page load** | **≈38.6s** | **≈3.5s** (cold) |

Both hard requirements met: default database work is under 5 seconds
(≈3.5s cold, well under 2s warm); exact `documentNumber` search is
unaffected (still ~0.1s, code path untouched).

`EXPLAIN (ANALYZE, BUFFERS)` on the optimized default `search()`
confirms the goal: Award person/unit aggregation is now scoped to
`WHERE award_id IN (<= 25 ids)` — resolved via existing indexes in
microseconds — while the dominant remaining cost (≈532ms) is a single
`Seq Scan` of `archive.award_version` itself (267,386 rows), which the
light union's own `UNION ALL` + cross-table sort cannot avoid touching
(no index can satisfy a sort spanning four physically distinct tables).
This is a fundamentally cheaper operation than the eliminated
`award_person`/`award_person_unit` scans+aggregations it replaced, and
matches the "no migration/index unless measurements prove one is
required" boundary — measurements show the current approach already
meets the target without one.

## Known, accepted limitation

Filtered requests are unchanged and were not the reported problem, but
are worth stating precisely since some remain slow: an exact
`documentNumber` filter is fast (~0.1s, index-backed). A `personName`
filter (~12-13.5s), a `unitNumber` filter (~1.3-6.1s), or a bare `module`
filter (`module=AWARD` alone: ~12s; `PROPOSAL`/`NEGOTIATION`/`SUBAWARD`:
0.1-1.4s) all still use the original full CTE, since any filter at all
routes away from the new fast path. This matches the explicit scope of
this fix ("optimized page-first path only when no filters are present")
and was not a regression introduced by the Award load — these filtered
cases were already this slow beforehand. Extending the fast path to
person/unit-filtered requests would require a materially different
design (e.g. per-module targeted queries chosen by which filter is set,
or a summary table) and is not implemented here.

## Files changed

- `api/src/main/java/edu/bu/archive/adapter/out/persistence/DocumentExplorerRepository.java`
  — added `countDefault()`, `moduleFacetsDefault()`, `searchDefaultPage()`
  and their supporting SQL constants/helper; `search()`/`count()`/
  `moduleFacets()`/`EXPLORER_CTE`/`FILTER_WHERE`/`SELECT_LIST` untouched.
- `api/src/main/java/edu/bu/archive/application/document/DocumentExplorerService.java`
  — added `isUnfiltered()` and branched `search()` on it.
- `api/src/test/java/edu/bu/archive/adapter/out/persistence/DocumentExplorerRepositoryTest.java`
  — added SQL-structure tests for the new methods (paginate-before-enrich
  ordering, scoped enrichment, tie-breaker, no deduplication by document
  number, all four sorts).
- `api/src/test/java/edu/bu/archive/application/document/DocumentExplorerServiceTest.java`
  — updated the shared `stub()` helper to cover both paths; adjusted two
  tests whose original premise (a no-op filter routed to the old methods)
  no longer holds, preserving their original intent by giving them a real
  filter instead; added explicit routing tests for both branches.

No migration, index, Terraform, AWS configuration, attachment/RAG/ETL/
embedding code, or authentication was touched.
