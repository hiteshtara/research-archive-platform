# DEV capacity experiment — plan (awaiting approval)

**Nothing in this document has been executed.** DEV revision 94 is
serving on 0.5 vCPU; revision 93 remains the rollback target. No ECS
capacity has been changed. Approval is requested before step 1.

## 1. Why, in one line

The identical Award search request measures **1,443 ms solo and
14,848 ms alongside five ordinary reads — 10.3×** on a 0.5 vCPU task,
and Global Search fans out six concurrent legs. See
`GLOBAL_SEARCH_ROOT_CAUSE.md`.

### Independent confirmation from CloudWatch

Container Insights is **enabled** on the cluster, and the metrics
already recorded during today's measurement session show the task
saturating:

| Metric | Value |
|---|---|
| `AWS/ECS CPUUtilization` (max, 1-min) | **100%** at 18:46 UTC |
| `ECS/ContainerInsights CpuReserved` | 512 (0.5 vCPU), constant |
| `ECS/ContainerInsights CpuUtilized` (max) | 340.5 at 18:45 UTC |
| `MemoryUtilized` idle / peak | 328 MB / **360 MB** of 1,024 MB |

So CPU reaches its ceiling under exactly this workload, while **memory
is nowhere near its limit**. That matters for cost: raising CPU forces
a memory increase that buys nothing (§4).

The two CPU figures differ because they normalise differently —
`CPUUtilization` is a percentage of reserved CPU and hit 100%, while
`CpuUtilized` is a 1-minute aggregate that smooths sub-minute spikes.
The 100% figure is the saturation evidence; the 340.5 is not a
contradiction.

## 2. Candidate configurations

Fargate task sizes are constrained: a CPU value admits only certain
memory values, so memory cannot be held at 1 GB while CPU rises.

| # | CPU | Memory | Notes |
|---|---|---|---|
| A | 512 (0.5 vCPU) | 1,024 MB | **current**, the control |
| B | 1024 (1 vCPU) | 2,048 MB | 2,048 MB is the **minimum** Fargate allows at 1 vCPU |
| C | 2048 (2 vCPU) | 4,096 MB | 4,096 MB is the **minimum** at 2 vCPU |

**2 vCPU is supported.** The FARGATE launch type accepts 2048 CPU units
with 4,096–16,384 MB. Step 1 verifies this at registration rather than
on my assertion — a rejected `RegisterTaskDefinition` would end the
question immediately and costs nothing.

## 3. Method

Each configuration is a **new task definition revision derived from
revision 94, changing only `cpu` and `memory`** — same image digest
(`sha256:1d440f251c…`), same environment, same roles, same secrets. The
`onlyTheseFieldsChanged` assertion used for revision 94 is reused so the
claim is proven, not asserted.

For each of A, B, C:

1. Deploy, `aws ecs wait services-stable`, confirm ALB target healthy
   and the running task's `imageDigest` is unchanged.
2. Confirm authentication still enforced (no token → 401) and ordinary
   record reads return real content.
3. Run the measurement set below in **one authenticated session**, with
   A re-measured immediately before B and before C — because this host's
   run-to-run variance reaches 26% within a single five-sample series,
   so a cross-session comparison is not reliable.
4. Collect CloudWatch for the window.
5. Return to revision 94 (0.5 vCPU) at the end, regardless of outcome.

**Every timing requires HTTP 200 + `application/json` + a per-row
content check, from the absolute API origin. A 401 is never recorded.**
The existing `scripts/performance/def5-baseline-capture.mjs` already
enforces all of that and is the harness.

### Measurement set

| Surface | Request | n |
|---|---|---:|
| Global Search, word query | `/api/global-search?query=NSF` | 7 |
| Global Search, non-matching | `query=zzzqqqxyz123` | 5 |
| Global Search, identifier | `query=212137-00001` | 5 |
| Award search **solo** | `/api/v1/awards/search?q=NSF` | 7 |
| Award search **contended** | same, with 5 concurrent companion reads | 7 |
| Documents, text query | `/api/v1/documents?query=NSF` | 7 |
| Documents, unfiltered | `/api/v1/documents` | 5 |
| Ordinary record read | `/api/awards/212137-00001` | 10 |
| Dashboard | `/api/dashboard` | 5 |

n is raised from 5 to 7 on the headline rows because the effect being
measured (CPU scaling) is expected to be smaller than the 3.25× the
Documents fix produced, and the host is noisy.

### Reported statistics

Median, **p50/p90/p95**, min, max, sample count and discard count per
row. p90/p95 from 7 samples are weak and will be labelled as
indicative, not as a service-level measurement — stating that up front
rather than presenting a percentile the sample size cannot support.

### CloudWatch per window

`CPUUtilization` (max, avg), `CpuUtilized` vs `CpuReserved`,
`MemoryUtilized` vs `MemoryReserved`, `NetworkRx/TxBytes`, plus
Hikari's `hikaricp_connections_acquire_seconds` if the actuator metrics
endpoint is reachable — that is the one measurement that would separate
connection-acquisition from execution, which §8 of the root-cause
document lists as unresolved.

### Throttling evidence — stated honestly

**Fargate does not publish a CPU-throttling metric.** There is no
`CpuThrottled` in `AWS/ECS` or `ECS/ContainerInsights`; cgroup
`nr_throttled` is not surfaced. The honest substitute is
`CPUUtilization` pinning at 100% while latency rises, which is already
observed (§1). I will not present a throttling number I cannot source.

## 4. Estimated monthly cost

us-east-1 list price from the AWS Pricing API (`GetProducts`,
`AmazonECS`), Linux/x86: **$0.04048 per vCPU-hour**, **$0.004445 per
GB-hour**. One task, `desiredCount` 1, 730 hours.

| Configuration | x86 / month | Δ vs current | Multiple | ARM / month |
|---|---:|---:|---:|---:|
| 0.5 vCPU + 1 GB (current) | $18.02 | baseline | 1.0x | $14.42 |
| 1 vCPU + 2 GB | $36.04 | +$18.02 | 2.0x | $28.84 |
| 2 vCPU + 4 GB | $72.08 | +$54.06 | 4.0x | $57.67 |

Figures are **DEV only** and exclude ALB, RDS, data transfer and ECR,
none of which this change touches.

Two things worth seeing in that table:

- The absolute amounts are small. Going to 1 vCPU costs about **$18 a
  month more** on DEV. If the experiment shows a worthwhile latency
  gain, the decision is not really a cost decision at this scale — but
  Production sizing is a separate conversation with different volumes.
- **Graviton/ARM is ~20% cheaper** at every size. It is *not* part of
  this experiment, because it needs an `arm64` image rebuild and is a
  platform change rather than a capacity change. Flagged because it is
  the cheapest lever available and the local build machine is already
  arm64. It should be its own proposal.

Roughly $12 of the current $18 is memory and CPU the task does not use —
memory peaks at 360 MB of 1,024 MB.

## 5. Bounded fan-out, evaluated as the alternative

The code-only alternative to buying CPU. `GlobalSearchService` hands
each leg to `CompletableFuture.supplyAsync` **with no executor**, and
the existing in-code note records that when common-pool parallelism is
1 the JDK falls back to a thread-per-task executor — so six legs run
concurrently on half a core.

| Option | Change | Expected | Risk |
|---|---|---|---|
| **F1** Run legs sequentially | remove the fan-out | On 0.5 vCPU wall-clock already tends toward the **sum** of the legs, so this may be **no slower** while removing contention and context switching, and making latency predictable | Low. Pure ordering change; no SQL, no authorization. Would regress on a larger task, where real parallelism pays |
| **F2** Bound concurrency to 2 | a fixed-size executor | Keeps some overlap without six-way contention. Probably the best shape for 0.5 vCPU | Low, but one more knob to size |
| **F3** Drop the Award leg's redundant `COUNT(*)` | derive the total, as the Documents fix did | ~0.7–1 s, independent of CPU | Low. Same pattern already proven |

**F1/F2 and the CPU change are not alternatives in the strict sense —
they interact.** If CPU rises to 2 vCPU, true parallelism starts paying
and sequential execution would become the wrong choice. So the
experiment must measure fan-out behaviour **at each CPU size**, not
pick one and then tune the other. Concretely: at configuration A, the
contended-vs-solo Award ratio is the fan-out penalty; if that ratio
collapses toward 1.0 at B or C, bounded fan-out stops being needed.

F3 is worth doing regardless of the capacity outcome and is independent
of this experiment.

## 6. Guardrails

- DEV only. Production untouched.
- Revision 94 is the end state and the rollback target throughout;
  revision 93 stays registered.
- Enforcement OFF, auditing OFF, V089 unapplied, grant 2 unchanged. No
  task definition in this experiment changes any environment variable.
- No index, no migration, no Award SQL rewrite.
- Image digest identical across A, B and C, so the only variable is
  capacity.
- Abort and return to revision 94 if ALB health fails, authentication
  behaviour changes, or ordinary record reads stop returning real
  content.
- The whole experiment is ~45–60 minutes of measurement across three
  deployments; each deployment is ~3–4 minutes to stabilise.

## 7. What approval is being requested for

1. Register three task definition revisions differing from 94 **only**
   in `cpu`/`memory`.
2. Deploy each to DEV in turn, measure, and **return to revision 94**.
3. Report the comparison, CloudWatch evidence, cost table and a
   recommendation.

This does **not** request a permanent capacity change. That would be a
separate decision taken on the experiment's results, and would need its
own Production assessment, since Production volumes and sizing differ
from DEV's.
