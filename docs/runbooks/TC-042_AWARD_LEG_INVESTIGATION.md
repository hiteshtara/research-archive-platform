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

**What queue wait can and cannot settle.** It measures the delay before
a worker *starts*. It says nothing about CPU contention *after* it
starts: on a 0.5 vCPU task two running threads stretch each other's
wall-clock without any of that appearing as queue wait. A near-zero
queue wait therefore excludes scheduling delay only — it does not
exonerate contention. That is why the run below captures CPU alongside
the timings.

**What the statement timers are, and are not.** They are the duration of
the repository CALL: connection acquisition, driver work, network, server
execution and row materialisation together — **not** server-side
execution time.

One part of that bundle is now separated. `DB_CONNECTION_ACQUIRE` times
`DataSource.getConnection()` through a thin wrapper
(`TimingDataSourceConfiguration`), under the same correlation id and the
same single flag. Hikari already measures this and exports it through
Micrometer, but `management.endpoints.web.exposure.include` is
`health,info`, so the metrics endpoint is not reachable — and widening
that exposure on a deployed API to read one number during a temporary
investigation is a worse trade than a default-off wrapper that exposes
nothing new.

What remains inside the repository-call number after connection
acquisition is removed is driver, network, server execution and row
materialisation. Splitting *that* needs `EXPLAIN (ANALYZE, BUFFERS)`,
which stays separately gated and should be chosen only once the timings
name a statement.

### A2. The run itself — alternating, both paths

**Alternate the two paths rather than batching them.** Blocks of one
then blocks of the other confound the comparison with anything that
drifts over the run — environment load, pool state, a deploy elsewhere.
Alternating puts both paths under the same conditions minute by minute:

```
for term in [autism, cancer, neuroscience, genomics, imaging, 105698, 100004]:
    for i in 1..10:
        GET /api/v1/awards/search?q=<term>&page=0&size=25     # standalone
        GET /api/global-search?query=<term>&modules=AWARD      # Global Search leg
```

Identical terms and identical parameters on both sides; `page=0&size=25`
matches `PER_DOMAIN_LIMIT`. Sequential, concurrency 1, 5 discarded
warm-up requests per path first.

**Record and COUNT every non-200 — do not silently exclude them.** The
report carries, per path and per term: observations attempted,
observations at 200, and a count of each non-200 status seen. A run
where 15% of Global Search requests failed is a different result from a
clean one, and dropping them without saying so hides that. A session
token expiring mid-run returns 401 in 25–60 ms, indistinguishable from a
fast success if status is not recorded — that happened in the first run
and cost 10 observations before it was caught.

**Associate by returned correlation id, not by timestamp.** Each
response now carries its id in the `X-Search-Timing-Cid` header (set
only while the flag is on). Read it from the response and join directly
to the stage lines with that `cid`.

Timestamp windows are the fallback only, and a poor one: they
misassociate as soon as anything else is talking to the API — a second
tester, an open browser tab, a health probe — and the misassociation is
silent. If the header is ever absent, say so in the report and treat
those observations as windowed rather than joined.

**Capture CPU alongside.** Container Insights is enabled on this
cluster, so no code change is needed. For the run window, pull from
`ECS/ContainerInsights` for `ServiceName=research-archive-platform-dev-api`:
`CpuUtilized` and `CpuReserved` (and `MemoryUtilized`/`MemoryReserved`
for completeness), at the finest period available, plus
`AWS/ECS` `CPUUtilization`. Report them beside the timings.

**How to read it, and how not to.** `CpuUtilized` approaching
`CpuReserved` (512 units) during the Global Search requests but not the
standalone ones shows the task is **saturated**, which is consistent
with contention. It does **not** prove contention caused any individual
request's duration — a 1-minute aggregate cannot speak to one request
inside it. Both figures well below the reservation would weigh against
contention, which is the more useful direction.

Cgroup throttle counters are not exported here, so actual throttling is
not directly observable.

**What the run should answer.** Where the gap sits:

| If the gap is in | Reading |
|---|---|
| `AWARD_COUNT` | the count statement differs between paths |
| `AWARD_PAGE` | the page statement differs |
| `AWARD_EXACT_DOC` | the exact-document lookup differs |
| `DB_CONNECTION_ACQUIRE` | the pool, not the SQL |
| `LEG_AWARD_QUEUE_WAIT` | scheduling delay before the worker starts |
| none of them — `LEG_AWARD` exceeds their sum | the cost is **outside** the repository calls: CPU contention, GC, or the mapping loop |

That last row is a real possible outcome and the reason the whole-leg
figure is kept alongside the parts.

**Reconciling the leg total — do not double-count.**
`DB_CONNECTION_ACQUIRE` is **nested inside** the repository-call timers:
the call acquires its connection and then uses it, so that duration is
already contained in `AWARD_COUNT`, `AWARD_PAGE` or `AWARD_EXACT_DOC`.
When checking whether the parts account for `LEG_AWARD`, add the three
statement timers and the queue wait — **not** connection acquisition on
top. Treat it as a breakdown *within* a statement's number, answering
"how much of this call was waiting for a connection", never as an
additional term in the sum.

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
