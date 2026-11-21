# DEV capacity experiment — results (2026-11-12)

**Headline: more CPU did not fix Global Search, and made the contended
case worse. Recommendation is to keep 0.5 vCPU.** The leading hypothesis
— that the cost is app-CPU contention — is **not supported** by the
experiment, and the metrics say why.

Revision 94 has been restored and verified (§7).

## 1. What was run

| Config | Task definition | CPU | Memory | Image digest |
|---|---|---|---|---|
| A | `…-dev-api:94` | 512 (0.5 vCPU) | 1,024 MB | `sha256:1d440f251c…` |
| B | `…-dev-api:95` | 1024 (1 vCPU) | 2,048 MB | identical |
| C | `…-dev-api:96` | 2048 (2 vCPU) | 4,096 MB | identical |

95 and 96 were derived from 94 with **only** `cpu` and `memory`
changed, asserted programmatically: `onlyCpuAndMemoryChanged: true`,
`imageMatchesRev94: true`, `envIdenticalToRev94: true`,
`secretsIdentical: true`, both IAM roles identical. AWS account
`770203350335` verified via `AWS_PROFILE=bu-nprd` before every mutation.

2 vCPU **is** supported — `RegisterTaskDefinition` accepted 2048/4096
and the running task reported `cpu: 2048, memory: 4096`.

Every accepted timing required HTTP 200 + `application/json` + a content
check (text-query rows verified NSF in a searched field; `totalElements`
3,382 / 491,685; Global Search result count 83; award detail
`212137-00001`; dashboard `awards=40926`). **One sample discarded in the
whole experiment** (a single network failure at B, before warmup).

## 2. A methodology correction made mid-experiment

The first B measurement showed Global Search **slower** at 1 vCPU
(21,073 ms) than at 0.5 vCPU (18,429 ms). That was a confound, not a
result: **configuration A had been serving for over an hour and was
fully warm, while revision 95 had started two minutes earlier** — cold
JIT, cold Hikari pool, cold Bedrock connection.

A five-request warmup (discarded by construction) was added and B
re-measured:

| Row | B cold | B warm |
|---|---:|---:|
| Global Search `NSF` | 21,073 ms | **14,177 ms** |
| Global Search identifier | 7,799 ms | **3,774 ms** |

Cold start was worth ~33%. The same warmup was then applied to C, and
all figures below are warm. Recording this because the cold numbers
would have supported the opposite conclusion.

## 3. Results — all warm, medians (ms)

| Row | n | A 0.5 vCPU | B 1 vCPU | C 2 vCPU | A→C |
|---|---:|---:|---:|---:|---|
| Global Search `NSF` | 7 | 18,429 | **14,177** | 17,870 | −3% |
| Global Search identifier | 3 | 5,429 | **3,774** | 6,092 | +12% |
| Award search **solo** | 5 | 4,820 | 4,561 | **2,298** | **−52%** |
| Award search **contended** | 7 | **16,892** | 18,057 | 22,102 | **+31% WORSE** |
| Documents, text query | 5 | 11,473 | 12,847 | **10,748** | −6% |
| Documents, unfiltered | 3 | 6,185 | **5,615** | 6,025 | −3% |
| Record detail | 7 | 174 | 116 | **102** | **−41%** |
| Dashboard | 3 | 4,385 | **4,145** | 4,331 | −1% |

Min/max and indicative p90/p95 (p90/p95 from n=7 and n=3 are **not** a
service level — they are the top sample, labelled as the plan said):

| Row | A min–max (p90/p95) | B min–max (p90/p95) | C min–max (p90/p95) |
|---|---|---|---|
| Global Search `NSF` | 12,962–28,835 (28,835) | 12,912–18,882 (18,882) | 15,383–22,545 (22,545) |
| Award solo | 2,520–8,621 (8,621) | 3,245–6,548 (6,548) | 1,387–2,970 (2,970) |
| Award contended | 12,729–22,250 (22,250) | 13,966–23,885 (23,885) | 18,007–23,353 (23,353) |
| Documents text query | 10,469–16,692 (16,692) | 9,905–23,199 (23,199) | 9,534–14,808 (14,808) |
| Record detail | 86–309 (309) | 60–224 (224) | 53–253 (253) |

### What improved, and what did not

**Only single-request work improved.** Award search *solo* halved
(4,820 → 2,298) and record detail fell 41%. Those are the cases where
one request has the whole task to itself.

**Everything concurrent did not.** Global Search is flat within noise
across a 4× CPU range, and **Award search under concurrent load got
monotonically worse: 16,892 → 18,057 → 22,102.** Four times the CPU,
31% slower.

## 4. The metrics explain it: the task was never CPU-bound

| Config | `CPUUtilization` max | avg of minute-maxima | `CpuUtilized` peak | of reserved | `MemoryUtilized` peak | of reserved |
|---|---:|---:|---:|---:|---:|---:|
| A 0.5 vCPU | 10.6%* | 1.6%* | 21.9* | 4%* | 340 MB | 1,024 MB |
| B 1 vCPU | **100%** | 8.3% | 360 | **35%** | 347 MB | 2,048 MB |
| C 2 vCPU | **100%** | 15.5% | 524 | **26%** | 375 MB | 4,096 MB |

\* A's window spans 45 minutes including long idle periods either side of
its ~8 minutes of measurement, so its maxima are diluted and **not
comparable** to B and C. Stated rather than quietly presented as a
like-for-like row.

The decisive numbers are B and C. `CpuUtilized` peaked at **35% of
reserved at 1 vCPU and 26% at 2 vCPU** — the share *falls* as capacity
rises, which is what "not CPU-bound" looks like. The 100% figures are
1-minute maxima of a sampled metric and reflect brief spikes, not
sustained saturation; the average of minute-maxima stayed at 8–16%.

**Memory never exceeded 375 MB at any capacity**, against 1,024 /
2,048 / 4,096 MB reserved. The memory that Fargate's task-size table
forces alongside a CPU increase is entirely wasted — at C, 91% of
reserved memory was unused.

## 5. What this means — the hypothesis is refuted

Earlier evidence stands on its own terms: the identical Award request
measured 1,443 ms solo and 14,848 ms alongside five concurrent reads.
That 10.3× is real and reproducible.

**But it is not app-CPU contention.** If it were, 4× the CPU would have
relieved it. Instead the contended case got worse while the task used a
quarter of its allocation.

The remaining explanation is a **downstream shared constraint**, and the
obvious candidate is **PostgreSQL/RDS**: all six concurrent requests
query the same database instance. Giving the application more CPU lets
it issue those queries *faster and more concurrently*, pushing more
simultaneous load onto RDS — which would explain why the contended case
degrades as app CPU rises.

Stated as the **leading hypothesis, not a finding.** It has not been
measured: no RDS metrics were collected in this experiment, and
`app.search.timing.enabled` is still false so
`LEG_<module>_QUEUE_WAIT` and the Hikari connection-acquire metric
remain unexamined.

## 6. Cost, and the recommendation

us-east-1 list price (AWS Pricing API): $0.04048/vCPU-hour,
$0.004445/GB-hour, 730 hours, one task.

| Config | $/month | Δ vs current | Buys |
|---|---:|---:|---|
| **A 0.5 vCPU + 1 GB** | **$18.02** | — | — |
| B 1 vCPU + 2 GB | $36.04 | +$18.02 | nothing consistent; Global Search −3% within noise |
| C 2 vCPU + 4 GB | $72.08 | +$54.06 | Award solo −52%, record detail −41%, **Award contended +31% worse** |

### Recommended configuration: **keep 0.5 vCPU (revision 94)**

Paying +$18 or +$54 a month for no improvement in the surfaces that
actually hurt — and a regression in the concurrent case — is not worth
it. The single-request gains at C are real but those paths are already
acceptable: record detail is 174 ms at 0.5 vCPU.

If a capacity change is wanted later for headroom rather than for this
bottleneck, **1 vCPU is the better value than 2** — it captured most of
the record-detail gain at a third of the extra cost — but it should not
be justified on Global Search.

### Where the next effort should go instead

1. **Measure RDS under the concurrent workload** — connections, CPU,
   read IOPS, `pg_stat_activity` wait events. This is where the
   evidence now points. Read-only, no deployment.
2. **Enable `app.search.timing.enabled` on a temporary revision** to get
   `LEG_*_QUEUE_WAIT` and Hikari's connection-acquire metric, which
   separates connection acquisition from execution — the open question
   §8 of the root-cause document named.
3. **Bounded fan-out (F1/F2) is now MORE attractive, not less.** If the
   constraint is downstream, reducing simultaneous database load is the
   lever, and that is exactly what bounding the fan-out does. It is no
   longer an alternative to buying CPU; buying CPU is off the table and
   bounding concurrency is the remaining candidate.
4. **F3 — drop the Award leg's redundant `COUNT(*)`** remains worth
   doing independently (~0.7–1 s, the same win already proven on
   Documents).

Note that §5 of the experiment plan predicted fan-out and CPU would
interact, with real parallelism starting to pay at 2 vCPU. The
measurement shows the opposite: at 2 vCPU the concurrent case is worse,
so bounding the fan-out does not become the wrong choice at higher CPU —
it becomes more clearly the right one.

## 7. Restoration evidence

Revision 94 restored immediately after configuration C, per condition
12, before any analysis.

```
service taskDefinition : research-archive-platform-dev-api:94
running / desired       : 1 / 1,  pending 0
deployments             : 1,  rolloutState COMPLETED
service event           : "has reached a steady state."
running task            : cpu 512, memory 1024, lastStatus RUNNING
running task digest     : sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981
ALB rap-dev-api-tg      : 10.30.1.167 healthy
```

Post-restoration functional verification — **9 of 9 PASS**, every one
HTTP 200 + `application/json` + verified content:

| Check | Result |
|---|---|
| No token → 401 | PASS (auth still enforced) |
| Award detail | PASS `212137-00001` (452 ms) |
| Dashboard | PASS `awards=40926`, `subawards=3511` |
| Documents `query=NSF` | PASS `totalElements=3382` |
| Documents unfiltered | PASS `totalElements=491685` |
| Global Search `NSF` | PASS 83 results |
| Negotiations / Subawards / Proposals | PASS, real rows |

Enforcement OFF, auditing OFF, V089 unapplied, grant 2 untouched — no
task definition in this experiment altered any environment variable, and
no database write was made at any point. Production untouched.

Revisions 95 and 96 remain registered but **inactive**. They can be
deregistered on request; they are left in place so this experiment is
reproducible without rebuilding.
