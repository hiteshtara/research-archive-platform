# Downstream bottleneck — metrics and analysis (2026-11-12)

Read-only. No database write, no schema or index change, no configuration
change, nothing enabled. DEV revision 94 serving throughout.

**I am not calling RDS the root cause.** The existing metrics do not show
a saturated database, and §4 says plainly what is and is not established.

## 1. The instance, and what monitoring exists

| Property | Value |
|---|---|
| Identifier | `research-archive-platform-dev-postgres` |
| Class | **`db.t4g.micro`** — 2 vCPU, **1 GB RAM**, burstable |
| Engine | PostgreSQL 17.9 |
| Storage | gp3, 20 GB, 3,000 provisioned IOPS, autoscale to 100 GB |
| Multi-AZ | no |
| Parameter group | `default.postgres17` (defaults) |

Condition 2, answered:

| Monitoring | State |
|---|---|
| **Performance Insights** | **disabled** (`performanceInsightsEnabled: false`) |
| Database Insights | not enabled (no PI, which it builds on) |
| Enhanced Monitoring | **off** (`monitoringInterval: 0`, no resource ARN) |
| CloudWatch Logs exports | none |

Nothing was enabled. Standard CloudWatch RDS metrics are therefore the
only observability available, and that materially limits what can be
concluded — see §4.

## 2. Measured RDS metrics across the capacity experiment

Window 18:43–20:23 UTC, 1-minute periods, covering configurations A, B
and C.

| Metric | Max | Min | Headroom |
|---|---:|---:|---|
| `CPUUtilization` | **33.3%** | 3.3% | two thirds idle at peak |
| `CPUCreditBalance` | 279.7 | **270.8** | barely touched; **not** credit-starved |
| `CPUCreditUsage` | 2.96 | 0.39 | well under accrual |
| `DatabaseConnections` | **10** | 2 | see §3 — not what it looks like |
| `ReadLatency` | **0.002 s** | 0 | 2 ms; storage is fast |
| `WriteLatency` | 0.011 s | 0 | read-only workload |
| `ReadIOPS` | **1,341** | 0.5 | 45% of 3,000 provisioned |
| `WriteIOPS` | 43.9 | 0.1 | negligible |
| `DiskQueueDepth` | **0.96** | 0 | essentially no queuing |
| `FreeableMemory` | 142 MB | **95.6 MB** | of 1,024 MB — see §3 |
| `ReadThroughput` | **46.8 MB/s** | 8.9 kB/s | sustained 30–50 MB/s under load |
| `EBSIOBalance%` | 100% | **61%** | declining |
| `EBSByteBalance%` | 100% | **12%** | declining, **has not recovered** |

## 3. Two readings corrected, and one that survives

### 3.1 The connection pool is NOT saturated — my first reading was wrong

`DatabaseConnections` peaked at exactly **10**, which is also Hikari's
`maximum-pool-size: 10`. That coincidence looked like pool exhaustion.

It is not. The per-minute distribution over 95 minutes:

| Connections | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Minutes | 19 | 13 | 7 | 4 | 11 | 20 | 17 | 3 | **1** |

The pool reached its limit for **one minute out of ninety-five**, and
was at 9 or more for only four. For the overwhelming majority of the
experiment it sat at 2–8 of 10. **Pool exhaustion is not the
explanation**, and the maximum alone would have misled me.

### 3.2 `EBSByteBalance%` is genuinely depleting — but was not exhausted

It fell monotonically from **100% to 12%** over six hours and has not
recovered (12% at 20:14 and still 12% at 20:19), driven by sustained
30–50 MB/s of reads.

**Important limit on this:** EBS throughput throttling begins at **0%**.
At 12% the volume was *approaching* its burst limit, not being throttled
by it. So this explains a **real risk under sustained load** and does not
by itself explain today's latency. Saying otherwise would overstate it.

It does, though, corroborate §3.3: 30–50 MB/s of reads is what a
cache-starved database looks like.

### 3.3 The reading that survives: the database cannot cache this workload

`FreeableMemory` sat at **95–105 MB of 1,024 MB** all day — not a spike,
a standing condition. On a 1 GB instance PostgreSQL's `shared_buffers`
is small, and the working set here is not small: `archive.award_version`
alone is 267,386 rows, and the Documents union is 491,685.

So every large scan reads from storage rather than cache, which is
exactly the 30–50 MB/s observed, which is what consumes the EBS byte
balance.

## 4. Analysis — measured, hypothesised, and unknown

### Measured

- The application is **not** CPU-bound: 4× app CPU left Global Search
  flat and made the concurrent case 31% worse, while `CpuUtilized` fell
  to 26% of reserved (`DEV_CAPACITY_EXPERIMENT_RESULTS.md`).
- RDS is **not** CPU-saturated (33% peak), **not** credit-starved,
  **not** IOPS-bound (45%), **not** latency-bound (2 ms), **not**
  queue-bound (<1), and **not** connection-pool-bound (§3.1).
- RDS memory is effectively exhausted as cache (95–105 MB free of 1 GB).
- Read throughput is sustained at 30–50 MB/s and is consuming the
  instance's EBS byte-burst balance (100% → 12%).

### Leading hypothesis — explicitly NOT a finding

**The binding constraint is per-query execution on a small,
cache-starved database, and concurrency oversubscribes it.**

The reasoning, offered as reasoning: PostgreSQL executes one query on
one core (no parallel query configured here). 33% of a 2-vCPU instance
is roughly 0.66 of a core — about what one heavy sequential scan looks
like. Six concurrent legs each wanting a large scan must share 2 vCPUs
and ~100 MB of cache, where they evict each other's pages and re-read
from storage. More application CPU makes that worse by delivering those
queries sooner and more simultaneously, which is the measured direction.

This is consistent with every number above, but "consistent with" is
not "demonstrated". **It is a hypothesis.**

### Not established, and why

| Question | Blocked by |
|---|---|
| Per-query server-side execution time | no `EXPLAIN (ANALYZE)` access; no psql route from this machine |
| PostgreSQL **wait events** | needs Performance Insights (disabled) or `pg_stat_activity` sampling (no DB connection) |
| `pg_stat_statements` totals | extension state unknown; no DB connection to query it |
| Hikari **acquisition wait** | `management.endpoints.web.exposure.include: health,info` — `/actuator/metrics` is **not exposed**, so `hikaricp_connections_acquire_seconds` and `hikaricp_connections_pending` are unreachable without a config change and redeploy |
| Whether EBS throttling ever actually engaged | balance never reached 0% in the observed window |

Condition 3 asked about acquisition waits and wait events specifically.
Both require observability that does not currently exist; the honest
answer is that they could not be measured, and the closest available
substitute — `DatabaseConnections` — argues *against* pool saturation
rather than for it.

## 5. Why bounded fan-out is still the right next move

It does not depend on which downstream resource is binding. Whatever the
constraint is — DB CPU, cache, or storage throughput — **all of them are
consumed per concurrent query**, and all the measured evidence points at
simultaneity rather than at any single query being slow:

- one Award search alone: **1,443 ms**
- the same query with five concurrent companions: **14,848 ms**
- the same query with 4× app CPU and five companions: **22,102 ms**

Reducing how many queries are in flight at once is the one lever that
addresses simultaneity directly, costs nothing, and needs no capacity
decision. If it fails to help, that is itself strong evidence that the
constraint is *not* concurrency, which would redirect the investigation
usefully.

## 6. Smallest safe correction (implemented, draft PR)

`app.search.fan-out-concurrency`, default **0 = unbounded = exactly
today's behaviour**, so merging changes nothing until the property is
set.

When set, each request takes a **per-request `Semaphore`** of that many
permits, and every one of the six legs — including semantic, which also
queries the database — acquires a permit before doing work.

Design choices and why:

- **A semaphore, not a shared executor.** A shared bounded pool would
  couple concurrent users and introduce head-of-line blocking between
  them. A per-request permit set bounds each request's own database load
  and nothing else.
- **Every future is still created and joined in the same order.** The
  bound decides only how many *run* at once, so results, ordering,
  counts and `failedModules` cannot move.
- **Release in a `finally`**, so a throwing leg cannot strand a permit
  and deadlock the remaining legs.
- **An interrupt while waiting restores the flag and runs the work
  anyway**, rather than returning an empty list. A missing leg would
  surface as "found nothing", and "could not run" is the truth —
  `joinOrRecordFailure` still records it in `failedModules`.
- **`LEG_<module>_PERMIT_WAIT`** is recorded separately from the work,
  so a bounded run can be read as waiting-for-a-permit versus
  executing. Recorded after the existing `LEG_*_QUEUE_WAIT` so that
  metric keeps its original meaning.

### Automated regression tests — `GlobalSearchBoundedFanOutTest`, 4 passing

Correctness is asserted as **equivalence against the unbounded run**,
not against hand-written expectations:

| Test | Asserts |
|---|---|
| `theResponseIsIdenticalAtEveryBound` | at bounds 3, 2 and 1 the response shape — total, `failedModules`, and every row's module, identifier and `matchType` **in order** — is identical to the unbounded run |
| `aFailedLegStillAppearsInFailedModulesAtEveryBound` | the Award leg throws in every configuration; `AWARD` appears in `failedModules` at bounds 0, 3, 2 and 1. A bound must never turn "failed" into "found nothing" |
| `theBoundIsActuallyEnforced` | peak observed concurrent legs never exceeds the bound, and is at least 1 |
| `theUnboundedDefaultStillOverlapsSoTheProbeIsMeaningful` | **non-vacuity guard** — unbounded fan-out genuinely overlaps (peak > 1), without which the enforcement test would pass for free |

The legs sleep and record live concurrency through an `AtomicInteger`
peak tracker, so the overlap measured is real rather than simulated.

`mvn clean test`: **2,082 tests, 0 failures.**

## 7. Bounded fan-out experiment design (not executed)

### Configurations

One task definition revision per bound, derived from **94**, changing
**only** the `APP_SEARCH_FAN_OUT_CONCURRENCY` environment variable —
same image digest, same CPU (512), same memory (1024), same roles, same
secrets. `onlyThisEnvChanged` asserted programmatically, as for 95/96.

| # | Bound | Meaning |
|---|---|---|
| D0 | unset / 0 | control — six concurrent legs, today |
| D3 | 3 | half the fan-out |
| D2 | 2 | pairs |
| D1 | 1 | fully sequential |

This requires **no image rebuild** — the code change must be deployed
once, then only the environment variable varies.

### Method, applying what the CPU experiment taught

1. **Warm every revision before measuring.** The CPU experiment's first
   result was wrong because a fresh task was compared against one warm
   for an hour. Five discarded requests per revision, as before.
2. **Re-measure the control immediately before each bound**, in one
   authenticated session, because host variance reaches 26% within a
   single five-sample series.
3. Verify health, ALB target, 401-without-token and real record content
   at each revision before measuring.
4. **Restore revision 94 at the end regardless of outcome.**

### Workloads — both sequential and concurrent, per condition 6

| Workload | Shape | n |
|---|---|---:|
| W1 Global Search, single user | one request at a time | 7 |
| W2 Global Search, non-matching | one at a time | 5 |
| W3 Global Search, identifier | one at a time | 5 |
| W4 Global Search × 3 **concurrent users** | 3 simultaneous Global Searches | 7 rounds |
| W5 Award search solo | one at a time | 7 |
| W6 Award search + 5 companions | the contention probe | 7 rounds |
| W7 Documents text query | one at a time | 5 |
| W8 Record detail | one at a time | 10 |

W4 matters most and is new: bounding *within* a request could simply
push contention across requests. Measuring three simultaneous users
tests whether the bound helps or merely relocates the queue.

### Acceptance — correctness first, per condition 5

Before any timing is compared, at **every** bound:

| Gate | Requirement |
|---|---|
| Result count | `NSF` returns exactly **83**; identifier **1**; non-matching **5** |
| Row identity and order | SHA-256 digest over the ordered `module\|identifier\|matchType` list is **identical** to the control |
| `failedModules` | identical to the control (empty in the healthy case) |
| `moduleTotals` | identical to the control |
| HTTP | every accepted sample 200 + `application/json` + content check |

A digest-stability control is run against the unchanged revision first,
as was done for the Documents fix, so the comparator is proven before it
is used to judge a change.

### Performance acceptance

| Gate | Threshold |
|---|---|
| W1 Global Search single user | median must not regress by more than 10% vs control |
| W6 Award contended | median **improves by ≥ 20%**, else bounding is not the answer |
| W4 three concurrent users | median must not regress vs control |
| W8 record detail | median ≤ 500 ms |

**W6 is the decisive gate.** If bounding the fan-out does not improve
the contended case, the hypothesis in §4 is wrong and the investigation
should turn to RDS instrumentation instead — which would mean asking for
Performance Insights or a temporary revision with
`app.search.timing.enabled=true` and `/actuator/metrics` exposed.

## 8. Recommendation

1. **Do not change CPU.** Already decided; the measurements support it.
2. **Review the RDS instance class as a separate question.** A
   `db.t4g.micro` with **1 GB RAM** against a 491,685-row union is the
   most under-provisioned component measured today, and the
   `FreeableMemory` and `EBSByteBalance%` figures are the evidence. This
   is a capacity decision with its own cost, not something to fold into
   a code change, and it should be measured the way CPU was rather than
   assumed. **Noted, not recommended** — the CPU experiment is a fresh
   reminder that the obvious capacity lever can fail to help.
3. **Run the bounded fan-out experiment in §7** once approved. It is
   free, reversible, and informative whichever way it comes out.
4. **F3 — drop the Award leg's redundant `COUNT(*)`** remains worth
   doing independently (~0.7–1 s, the pattern already proven on
   Documents).
