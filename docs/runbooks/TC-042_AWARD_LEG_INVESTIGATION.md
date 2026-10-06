# TC-042 — the Award leg: what is established, and what is not

Follow-on from the stage measurement (see
`TC-042_SEARCH_TIMING_MEASUREMENT.md`). Status: **the cost is localised,
the cause is not identified.** No fix is proposed here, because the
evidence does not yet support one.

Environment for everything below: dev, API task definition rev 77
(image `20261006T161658Z-f7acec7`), Amplify #119 at `f7acec7`,
sequential requests at concurrency 1, signed-in session.

## Established

### 1. The Award leg is the whole request, on word queries

Stage timings, 7 terms × 10 requests, nearest-rank percentiles
(`ceil(p/100 × n)`, 1-based, no interpolation). Representative term
(`neuroscience`, n=10); the other three word terms agree within ~1%:

| Stage | p50 (ms) |
|---|---|
| MVC_REQUEST_TOTAL | 10,878 |
| SERVICE_TOTAL | 10,875 |
| **LEG_AWARD** | **10,874** |
| LEG_SUBAWARD | 2,507 |
| SEMANTIC_TOTAL | 2,529 |
| · SEMANTIC_VECTOR_QUERY | 2,344 |
| · SEMANTIC_EMBED | 139 |
| · SEMANTIC_ENRICH_TOTAL | 16 |
| LEG_NEGOTIATION | 398 |
| LEG_PROPOSAL | 61 |
| LEG_IRB | 7 |

The legs run concurrently, so the request is its slowest leg. That leg is
AWARD, and it is ~4× the next slowest.

### 2. The same search is ~11× faster through the Awards endpoint

Same term, same paging, same deployed build, 5 requests each:

| Path | p50 (ms) |
|---|---|
| `/api/v1/awards/search?q=autism&page=0&size=25` | **893** (totalElements 226) |
| `/api/global-search?query=autism&modules=AWARD` | **9,961** |
| `/api/v1/awards/search?q=105698&page=0&size=25` | 1,275 (totalElements 2) |
| `/api/global-search?query=105698&modules=AWARD` | 1,131 |

An identifier query is the control: both paths agree at ~1.1–1.3 s. The
divergence appears only on a word query.

### 3. It is not contention between the legs

`modules=AWARD` leaves only the Award leg and the semantic leg running
(semantic is not gated by `modules`). That request still takes **10,072 ms
p50** — indistinguishable from the full six-leg request at 10,764 ms. So
the other four legs are not what stretches it.

### 4. It is not an accumulation effect

Back-to-back: 1,531 / 10,259 / 9,979 / 10,116 ms. With a **20-second gap**
between each: 9,835 / 10,131 / 9,934 ms. Spacing does not help, so this is
not pool exhaustion, cache churn, or anything that builds up across
requests. The single fast 1,531 ms reading is an outlier, not a warm/cold
pattern — the spaced run has no fast first request.

Meanwhile the Awards endpoint back-to-back: 2,255 / 882 / 911 / 856 ms —
stable and fast.

### 5. The two paths run the same service method

`GlobalSearchService.searchAward` calls
`awardArchiveService.search(query, 0, PER_DOMAIN_LIMIT)` where
`PER_DOMAIN_LIMIT = 25`. The 3-argument overload delegates straight to
`search(query, AwardSearchFilters.none(), page, size)`.
`AwardV1Controller` calls the 4-argument overload with an
all-null `AwardSearchFilters`. Both therefore reach the same three
statements: `countSearchAwards`, `searchAwards`, and
`findExactWorkflowDocumentMatch`.

`toGlobalSearchItem(AwardSearchResultResponse, query)` is pure in-memory
mapping — `awardMatchedField` and `awardMatchedValue` are string
comparisons, no database access. It is not an N+1.

## Not established — and not to be guessed

**Which statement is slow, and why it is slow only on this path.** The
instrumentation added for TC-042 times the leg as a whole
(`LEG_AWARD`); it does not time the three statements inside
`AwardArchiveService.search` separately. Two paths reaching the same
three statements with an 11× difference is a real finding and a real
puzzle, and the next step is to measure, not to theorise.

Hypotheses explicitly **not** acted on, because nothing here distinguishes
them:

- the count query planning differently when invoked from a worker thread
- connection acquisition behaving differently off the request thread
- some difference in how the two paths bind or reuse the statement
- the 0.5 vCPU task shaping scheduling in a way the single-leg test did
  not isolate

**No index, schema migration or query rewrite should follow from this
document.** Timing alone cannot tell us which statement to change, and a
leading-wildcard `ILIKE` theory is not supported: the identical statement
is fast through the other path.

## Next diagnostic step

Two measurements, in this order.

### A. Time the three statements separately

A small addition to the existing `SearchTimingLog`, in
`AwardArchiveService.search`:

| Stage | Covers |
|---|---|
| `AWARD_COUNT` | `repository.countSearchAwards(...)` |
| `AWARD_PAGE` | `repository.searchAwards(...)` |
| `AWARD_EXACT_DOC` | `repository.findExactWorkflowDocumentMatch(...)` |
| `AWARD_SEARCH_TOTAL` | the whole method |

The same correlation id ties them to the enclosing `LEG_AWARD` and
`MVC_REQUEST_TOTAL`, so the same request can be read on both paths. Run
the identical comparison from section 2 and read which statement differs.

This answers "which statement" without any database access, and reuses
instrumentation that is already reviewed, already default-off, and
already proven not to log anything identifying.

### B. Then, and only then, plans and buffers

Once a statement is named, capture for **that statement**, with the same
term and parameters:

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE) <the statement>;
```

on dev, through the documented ECS Fargate route (see `CLAUDE.md` —
there is no supported direct Mac-to-RDS path), comparing the plan when
run as the Awards endpoint runs it against the plan when run as Global
Search runs it. Record shared/local hit and read counts, not only
timings.

Database access of this kind needs its own approval; it is not covered by
the deployment authorisation that produced this document.

## Reporting corrections carried forward

- **Enrichment.** `SEMANTIC_ENRICH_TOTAL` at 16–35 ms is a small measured
  contribution **at this result size** (`count=5`). It is not proof that
  TC-041 introduced no regression: that would need a before/after
  comparison on the same path, and the cost plausibly scales with the
  number of enriched rows.
- **Sample count.** The first aggregation covered 60 observations, not
  the planned 70. The `autism` block ran before an access-token expiry
  forced a session refresh, and its window fell outside the CloudWatch
  query range used for the first aggregation. Those 10 observations were
  recovered afterwards and agree with the rest (`LEG_AWARD` p50
  10,530 ms), giving the planned 7 terms × 10 = 70.
- **Discarded observations.** A separate set of requests returned HTTP
  401 in 25–60 ms after the token expired mid-run. They were discarded,
  not counted as fast successes, and every reported observation is
  status 200.
