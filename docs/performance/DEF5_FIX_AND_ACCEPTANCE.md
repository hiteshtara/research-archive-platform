# DEF-5 — measured bottleneck, the fix, and its acceptance criteria

Status: proposed. Not merged, not deployed. DEV revision 93 is
unchanged by this branch.

The measurement record this document cites lives on the investigation
branch (`perf/def5-investigation`, PR #140) as
`docs/performance/DEF5_MEASURED_RESULTS.md`. This branch carries only
the code change and its gates.

## 1. The measured bottleneck

`GET /api/v1/documents` with any filter set evaluates the four-module
`EXPLORER_CTE` **three times per request** — once for `count()`, once
for `search()`, once for `moduleFacets()`. That CTE resolves Award's
primary person, unit count and person count for **all 267,386**
`archive.award_version` rows before any filter or `LIMIT` can apply:

- `award_pi` — `DISTINCT ON (award_id)` over `archive.award_person`
  (325,468 rows)
- `award_unit_counts` — `GROUP BY award_id` over
  `archive.award_person_unit` (334,371 rows)
- `award_person_counts` — a second `GROUP BY` over `award_person`
- then a four-way `UNION ALL` (~296,413 rows) which must be fully
  materialized and sorted before `LIMIT 25` applies

The filter is applied **after** the union, so no index on a base table
can serve it. `ix_award_version_title_trgm` already exists and is
exactly the index this predicate would want, but it is unreachable
from a predicate applied to a CTE's output.

## 2. Evidence

Measured on authenticated DEV revision 93, every timing recorded only
for an HTTP 200 whose body also passed a content check:

| Request | Result |
|---|---:|
| `query=NSF`, all modules, 5 warm repeats | median **43,406 ms** (40,326–45,757) |
| `query=NSF&module=AWARD` | **19,763 ms** |
| `query=NSF&module=PROPOSAL` | **1,801 ms** |
| no text query (light path already in place) | ~6,700 ms |
| `module=PROPOSAL` only | 462 ms |
| `size=1` vs `size=25` with `query=NSF` | 40,909 / 41,629 ms |
| unrelated authenticated endpoint | 32 ms |

Independently, the 2026-08-12 investigation
(`docs/architecture/KUALI_DOCUMENT_EXPLORER_PERFORMANCE.md`) measured
the same three queries with `EXPLAIN (ANALYZE, BUFFERS)` at 13.4 s /
12.9 s / 12.3 s ≈ 38.6 s total. Three evaluations at ~14.5 s each is
43.5 s, which is the black-box median above. The two methods agree.

That investigation **fixed only the unfiltered path** and recorded the
filtered cases as a known-accepted limitation, naming `module=AWARD`
alone at ~12 s. DEF-5 is that limitation coming due.

### Measured, not assumed

- Not cold start: five consecutive warm repeats all exceeded 40 s.
- Not payload size: `size=1` ≈ `size=25`.
- Not application overhead: an unrelated endpoint answered in 32 ms.
- Not a missing index: the 2026-08-12 plans showed the dominant nodes
  were full but **correctly indexed** scans feeding merge joins, with
  no row-multiplying fan-out.

### One correction to the earlier reading

All-modules (43,406 ms) is substantially **more** than the sum of the
per-module timings (19,763 + 1,801 + Negotiation + Subaward ≈ 23 s).
So the Award branch is the largest single contributor but **not** the
entire cost in the filtered case — the cross-module union and its sort
carry their own large cost. The 2026-08-12 note that "AWARD alone =
essentially the entire cost" held for the unfiltered default page; it
does not transfer unchanged to a filtered text search.

## 3. The fix (smallest change that addresses the measured cause)

No migration. No new index. No change to the heavy CTE's SQL. Two
changes, both in the request path:

**(a) Stop computing the same total twice.** The response's
`totalElements` is now summed from the per-module facet counts instead
of a separate `COUNT(*)` over the same union under the same `WHERE`.
`COUNT(*)` over a predicate equals the sum of that predicate's
per-group counts, so the number is unchanged — but a filtered request
evaluates the union **twice instead of three times**.

**(b) Reuse the existing two-stage light path for filtered requests
that do not need the Award person aggregation.** The 2026-08-12 fix
already built `documents_light` (the same union without the
`award_person`/`award_person_unit` aggregation) plus Award enrichment
scoped to `WHERE award_id IN (<= size ids)`. This routes filtered
requests through that same shape.

A request keeps using the heavy CTE when a filter actually reads a
column the light union does not carry:

| Filter | Reads | Path |
|---|---|---|
| `personId`, `personName`, `personRole` | `primary_person_*` | heavy |
| `piOnly` | `primary_person_role` | heavy |
| `unitNumber` **with** `includeAnyUnit` | `award_any_units` | heavy |
| everything else, incl. `query`, `module`, dates, `sponsorCode`, `unitNumber` alone | light-union columns | **light** |

`unitNumber` without `includeAnyUnit` stays eligible: it reduces to
`lead_unit_number` equality, which the light union does carry for all
four modules.

### Why this is safe

The light path is an optimization only, so the regression test asserts
**equivalence against the heavy path** rather than hand-written
expectations — identical rows and identical per-module counts across 19
light-eligible filters, on real PostgreSQL with every committed
migration applied. If the two unions ever diverge, that test fails even
if each path looks reasonable on its own.

## 4. Expected impact — a projection, not a measurement

This has **not** yet been measured against DEV. The projection:

- Change (a) alone removes one of three evaluations: expect ~43.4 s →
  ~29 s.
- Change (b) moves the remaining two onto the light union. In **this**
  environment today the light union's own cost is already visible: the
  unfiltered default page measured ~6,700 ms, and `size=1` 10,522 ms.
  The filtered light path cannot be faster than that floor.

So the honest expectation for `query=NSF` across all modules is
**roughly 8–12 s, i.e. a 3.5–5× reduction, not an order of
magnitude** — and explicitly **not** under a 5-second target. The
residual cost is the light union's own scan of `award_version` plus a
sort spanning four physically distinct tables, which no index can
satisfy.

If sub-second filtered text search is the real requirement, that needs
a different design (a maintained document-index table, or a search
service), which this change deliberately does not attempt.

## 5. Acceptance criteria

### Already satisfied (automated, in this branch)

1. A filtered request issues **at most two** database evaluations, and
   **no** separate `COUNT(*)` on either path. *(service routing tests)*
2. The light filtered SQL filters and paginates **before** any Award
   aggregation, and scopes all three Award aggregations to the page's
   ids. *(repository SQL-structure tests)*
3. The light filtered `WHERE` never references `personId`,
   `personName`, `personRole`, `piOnly` or `award_any_units`.
   *(repository SQL-structure test)*
4. The heavy path's SQL is unchanged. *(repository SQL-structure test)*
5. Light and heavy paths return identical rows and identical
   per-module counts for all 19 light-eligible filters; summed facets
   equal `count()`; paging covers every row exactly once; the
   duplicate-document-number Award pair keeps both rows; Award
   enrichment values are real, not placeholders; all four sorts agree.
   *(Testcontainers equivalence suite, 8 tests, run and passing)*
6. The equivalence suite cannot pass vacuously — a guard test asserts
   the fixture yields 4 rows across 3 modules, all four modules are
   reachable, and the no-document-number Award is excluded on both
   paths.
7. The new suite is registered in **both** CI test lists, so it runs in
   the database job rather than starting a container in the
   non-database job.
8. `mvn clean test`: **2,076 tests, 0 failures**.

### Still to satisfy (requires an authenticated DEV deployment)

Every measurement below must be an HTTP 200 whose body also passes a
content check, from the absolute API origin. A 401 is never recorded as
a timing. Five warm repeats each; report median, min, max and sample
count.

| # | Gate | Before | Threshold |
|---|---|---:|---:|
| 9 | `query=NSF`, all modules | 43,406 ms | median **≤ 15,000 ms** |
| 10 | `query=NSF&module=AWARD` | 19,763 ms | median **≤ 9,000 ms** |
| 11 | `query=NSF&module=PROPOSAL` | 1,801 ms | median **≤ 2,200 ms** (no regression) |
| 12 | unfiltered default page | ~6,700 ms | median **≤ 8,000 ms** (no regression) |
| 13 | a `personName`-filtered request | to be baselined first | within **+10%** of its own before-value (stays on the heavy path) |
| 14 | unrelated endpoint baseline | 32 ms | **≤ 100 ms** |

Thresholds 9 and 10 are deliberately looser than the projection in §4:
they gate "the defect is fixed", not "the projection was exact".

**Correctness gates on live data, not only in tests:**

| # | Gate |
|---|---|
| 15 | For `query=NSF`: `totalElements` is **identical** before and after. |
| 16 | For `query=NSF`: the 25 returned rows are the **same rows** before and after (same module + document number + target id multiset). |
| 17 | For a `personName` filter: results identical before and after (it must still take the heavy path). |
| 18 | `moduleFacets` identical before and after for `query=NSF`. |

Gates 15–18 require capturing the before-state from the currently
deployed revision 93 **before** any deployment of this change. That
capture has not been taken yet.

## 6. Not addressed here

**Global Search is a separate finding.** Measured on revision 93:
`NSF` median 17,312 ms, a non-matching query 18,110 ms, an identifier
query 10,571 ms, with the Award leg at 11,776 ms against
Proposal 1,042 / Negotiation 722 / Subaward 2,533 ms. That a
**non-matching** query costs as much as a matching one points to the
same shape of cause — work proportional to the corpus rather than to
the matches — but `GlobalSearchService`'s own SQL has not been
investigated here and no fix is proposed for it. Stated as a
hypothesis, not a finding.
