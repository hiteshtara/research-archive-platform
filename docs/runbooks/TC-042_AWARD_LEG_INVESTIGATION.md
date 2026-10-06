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

### 3. The other four lexical legs are not what stretches it

`modules=AWARD` leaves the Award leg **and the semantic leg** running —
`modules` does not gate semantic. That request still takes **10,072 ms
p50**, indistinguishable from the full six-leg request at 10,764 ms.

**This reduces contention; it does not eliminate it.** Two legs still run
concurrently on a 0.5 vCPU task, so contention between the Award and
semantic legs remains a live hypothesis. What is excluded is the other
four lexical legs as the explanation.

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
document.** Timing alone cannot tell us which statement to change.

On the leading-wildcard `ILIKE` theory: the identical statement running
fast through the other path shows that **wildcard matching alone cannot
explain the gap** — something else differs between the paths. It does
**not** rule the wildcard out as a contributor. The Awards endpoint's own
893 ms for a 226-result word query is itself not fast, and a scan could
well be part of both numbers with a second factor multiplying it on one
path.

## Static comparison of the two paths

Asked for before any further deployment. Same term, same parameters.

| | `AwardV1Controller` | Global Search `searchAward` |
|---|---|---|
| Method | `search(q, filters, page, size)` | `search(query, 0, 25)` → delegates to the same 4-arg method |
| Bound filters | `new AwardSearchFilters(null ×6)` | `AwardSearchFilters.none()` — **literally the same all-null record** |
| Page / size | `page=0`, `size=25` | `page=0`, `size=PER_DOMAIN_LIMIT=25` |
| Statements reached | count, page, exact-doc | the same three |
| Transaction | none — no `@Transactional` on controller, service or repository | same |
| Statement timeout | none configured | same |
| Pool | Hikari, max 10, min-idle 2, 30 s connection timeout | same |
| **Thread** | Tomcat worker, inline (`nio-N-exec-N`) | **a new `Thread-N` per leg** via `supplyAsync` fallback |
| **Concurrent work in flight** | none | the semantic leg at minimum (~2.5 s), on 0.5 vCPU |

The bound values, transaction context, timeouts and pool settings are
**identical**. The two differences that survive are the executor and what
else is running alongside — which is what the new instrumentation
targets.

## Next diagnostic step

Two measurements, in this order.

### A. Finer timing — PREPARED in this branch

Added to the existing default-off `SearchTimingLog`:

| Stage | Covers |
|---|---|
| `AWARD_COUNT` | `repository.countSearchAwards(...)` |
| `AWARD_PAGE` | `repository.searchAwards(...)`, with row count |
| `AWARD_EXACT_DOC` | `repository.findExactWorkflowDocumentMatch(...)` |
| `LEG_<MODULE>_QUEUE_WAIT` | scheduling to worker start, per leg |

**Queue wait matters here specifically.** The legs go to
`CompletableFuture.supplyAsync` with no executor. That uses the common
ForkJoinPool *unless* its parallelism is 1, in which case the JDK falls
back to a thread-per-task executor. The measurement run's own log lines
came from threads named `Thread-N`, not
`ForkJoinPool.commonPool-worker-N` — the fallback's signature, consistent
with a 0.5 vCPU task reporting one processor. So the Award leg runs on a
freshly created thread while the same search through `AwardV1Controller`
runs inline on the Tomcat worker (`nio-N-exec-N`). That is a real
difference between the paths, and the queue-wait line measures it instead
of arguing about it.

**What the statement timers are, and are not.** They are the duration of
the repository CALL: connection acquisition, driver work, network, server
execution and row materialisation together. They are **not** server-side
execution time. Separating that further needs either Hikari's
connection-acquire metric (already exported by the actuator, no code
change) or `EXPLAIN (ANALYZE, BUFFERS)`, which is separately gated.

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
