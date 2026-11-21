# DEV RDS upgrade assessment — db.t4g.medium

**Nothing was resized.** RDS is unchanged: `db.t4g.micro`, Performance
Insights still disabled. This is a decision document.

## 1. A correction to my earlier analysis, from the documentation

I previously wrote that `CPUCreditBalance` reaching 0 meant the instance
was "throttled to its baseline CPU allowance". **For RDS T4g that is
wrong.**

> "Amazon RDS T4g instances are configured for **Unlimited mode**, which
> means they can burst beyond the baseline over a 24-hour window **for an
> additional charge**."
> — [RDS Instance Types](https://aws.amazon.com/rds/instance-types/)

So a zero CPU credit balance on this instance does **not** throttle CPU;
it starts billing surplus credits. Two consequences:

- The **CPU half of the confound I reported is overstated.** The
  experiment's confound rests on the **EBS** balance, which has no
  "unlimited" equivalent and genuinely does throttle at 0%. That half
  stands, and it was always the stronger evidence (0.0% across three
  consecutive five-minute periods).
- **We may be accruing surplus-credit charges.** `CPUSurplusCreditBalance`
  and `CPUSurplusCreditsCharged` are the metrics to check. **Not yet
  checked** — see §9.

## 2. Documented specifications (AWS, not from memory)

| Class | vCPU | Memory | Baseline CPU / vCPU | Credits / hr | Max credits | EBS burst |
|---|---:|---:|---:|---:|---:|---|
| **`db.t4g.micro`** (current) | 2 | **1 GiB** | 10% | 12 | 288 | ≤ 2,085 Mbps |
| `db.t4g.small` | 2 | 2 GiB | 20% | 24 | 576 | ≤ 2,085 Mbps |
| **`db.t4g.medium`** | 2 | **4 GiB** | 20% | 24 | 576 | **≤ 2,085 Mbps — same** |
| `db.t4g.large` | 2 | 8 GiB | 30% | 36 | — | **≤ 2,780 Mbps** |
| `db.m7g.large` | 2 | 8 GiB | n/a — not burstable | — | — | higher baseline, no credits |

Our observed `CPUCreditBalance` maximum of **279.7** sits just under the
documented **288** cap for `t4g.micro`, which independently confirms the
class and that the balance really was near-full before the experiment.

## 3. Does t4g.medium "materially improve burst capacity and memory"?

**Memory: yes, substantially.** 1 GiB → 4 GiB. RDS sets
`shared_buffers` at roughly 25% of RAM, so ~256 MB → ~1 GB, and
`FreeableMemory` should move from the observed **95–105 MB** to the
low gigabytes. That is the direct answer to the measured starvation.

**CPU burst capacity: yes, doubled** — 12 → 24 credits/hour, 288 → 576
cap, baseline 10% → 20% per vCPU. Less relevant, since CPU peaked at
33–41% and was never the constraint.

**EBS throughput burst capacity: NO — it is identical.** Both micro and
medium are "Up to 2,085 Mbps". **The resize does not raise the ceiling
on the resource that actually throttled.**

So the mechanism by which medium would help is **indirect**: more memory
→ more of the working set cached → fewer bytes read from storage → the
byte-balance bucket drains more slowly. Given we measured sustained
30–50 MB/s of reads against ~100 MB of cache for a 491,685-row union,
that mechanism is plausible and probably significant — but it is a
second-order effect, not a direct lift of the limit, and it should be
described that way rather than sold as "more burst capacity".

## 4. Comparison with the two alternatives (condition 8)

### gp3 throughput adjustment — likely the WRONG lever

`EBSByteBalance%` is an **instance-level** metric: it tracks the
instance's EBS bandwidth burst bucket, not the volume's. Our evidence
says the volume was not the limit:

| | Value |
|---|---|
| Volume provisioned IOPS | 3,000; peak used **1,341 (45%)** |
| gp3 default throughput at 20 GB | 125 MB/s |
| Peak `ReadThroughput` observed | **82.6 MB/s** — under the volume's 125 MB/s |
| `ReadLatency` | **2 ms** |
| `DiskQueueDepth` | **< 1** |

Peak demand never exceeded the volume's own throughput, and latency and
queue depth show no volume-side pressure. **Raising gp3 throughput would
buy headroom that is not the constraint.** It is the cheapest option on
paper and I am recommending against it on the evidence.

### Non-burstable `db.m7g.large` — the definitive fix

8 GiB, 2 vCPU, **no credit mechanics at all**: no CPU credit balance to
exhaust, no surplus charges, and a materially higher sustained EBS
baseline. It removes both failure modes rather than deferring them.

Cost +$110.96/month against +$35.77 for medium — roughly 3× the increase
for a DEV database.

### `db.t4g.large` — the option I would not skip past

8 GiB (same as m7g.large), **and the first size that raises EBS burst to
2,780 Mbps**, at **$94.17/month (+$82.49)** — cheaper than m6g.large or
m7g.large for the same memory. Still burstable, so credits can still
deplete.

| Option | $/month | Δ | Memory | Raises EBS ceiling? | Removes credit mechanics? |
|---|---:|---:|---:|:--:|:--:|
| stay on `t4g.micro` | $11.68 | — | 1 GiB | — | — |
| gp3 throughput bump | ~$11.68 + storage | small | 1 GiB | **no** (wrong layer) | no |
| **`t4g.medium`** | $47.45 | +$35.77 | 4 GiB | **no** | no |
| `t4g.large` | $94.17 | +$82.49 | 8 GiB | **yes** | no |
| `m7g.large` | $122.64 | +$110.96 | 8 GiB | yes | **yes** |

Instance cost only. Storage is **unchanged** by any of these — 20 GB gp3
at ~$0.08/GB-month ≈ $1.60/month, plus 3,000 IOPS and 125 MB/s are
within gp3's free allocation at this size, so there is **no additional
throughput charge** unless gp3 is deliberately provisioned higher.

## 5. GO / NO-GO

### NO-GO on resizing today. GO on `db.t4g.medium` as the next step, after one prerequisite.

**Why not today:** every performance figure on this database is
contaminated by the EBS throttling we now know occurred. Resizing before
re-establishing a clean baseline would mean comparing a clean "after"
against a throttled "before" and overstating the benefit — the mirror
image of the mistake that spoiled the fan-out experiment.

**Prerequisite (free, no change):** re-measure the baseline with
burst-balance gating — require `EBSByteBalance%` ≥ 80% and record it at
both ends. This also unblocks the fan-out re-run.

**Why medium and not large or m7g:** we have just been shown that the
obvious capacity lever can fail (4× app CPU made concurrency worse).
Medium is the proportionate, cheapest step that addresses the
**measured** problem (memory starvation), and it is fully reversible. If
it does not deliver, `t4g.large` is the next rung and also raises the
EBS ceiling. I would not jump to `m7g.large` at +$111/month for DEV
before knowing whether 4 GiB is enough.

**Stated plainly:** medium does **not** raise the EBS burst ceiling. If
re-measurement shows the byte balance still draining to 0% after the
resize, then the answer is `t4g.large` or `m7g.large`, not more memory.

## 6. Execution plan (DEV only — not authorized to run)

Pre-flight:

1. Confirm account: `AWS_PROFILE=bu-nprd`, expect **770203350335**.
2. Confirm the target: `research-archive-platform-dev-postgres`,
   Single-AZ, `available`.
3. **Record the current configuration verbatim** — class, storage,
   parameter group (`default.postgres17`), backup retention, maintenance
   window — so the rollback target is documented, not remembered.
4. **Check `CPUSurplusCreditBalance` / `CPUSurplusCreditsCharged`** to
   establish whether surplus charges are already accruing (§1).
5. Confirm `EBSByteBalance%` ≥ 80% and capture the clean baseline.
6. Announce the window: **Lalitha is the only other user** and should not
   be testing during it.

Backup and snapshot:

7. Verify automated backups are enabled and note `BackupRetentionPeriod`.
   **If retention is 0, automated backups are off and a manual snapshot
   is mandatory, not optional.**
8. Take an explicit manual snapshot and **wait for `available`**:
   `rds create-db-snapshot --db-snapshot-identifier
   rap-dev-pre-t4g-medium-<date>`. This is the only true point-in-time
   rollback if the resize goes wrong in an unexpected way.

The change:

9. `rds modify-db-instance --db-instance-identifier
   research-archive-platform-dev-postgres --db-instance-class
   db.t4g.medium --apply-immediately`
10. Wait: `rds wait db-instance-available`.
11. Confirm `DBInstanceClass` is `db.t4g.medium` and status `available`.

### Expected downtime

**Single-AZ, so there is no failover and the instance is genuinely
unavailable during the change** — typically **several minutes** (commonly
quoted as ~5–10 for a class change; the actual figure for this instance
is unknown and should be measured, not promised). The API will be up but
every database-backed endpoint will fail for that period. `/actuator/health`
may fail with it, so ECS could cycle the task; that is expected and
self-correcting.

Note also that a class change restarts PostgreSQL, so **the buffer cache
starts empty** — the first measurements after the resize will be cold
and must be discarded, exactly as in the ECS experiments.

## 7. Verification tests after resizing

Correctness before performance, as before:

| # | Check | Expected |
|---|---|---|
| 1 | RDS status / class | `available`, `db.t4g.medium` |
| 2 | ECS service | revision 94, running 1/1, ALB target healthy |
| 3 | No token → 401 | authorization still enforced |
| 4 | Award detail | `212137-00001` |
| 5 | Dashboard | `awards=40926`, `subawards=3511` |
| 6 | Documents `query=NSF` | `totalElements=3382`, ordered digest `8b21c2799ca969abdaf166b6e3bfefa0` |
| 7 | Documents unfiltered | `totalElements=491685` |
| 8 | Global Search `NSF` | 83 results, ordered digest `19b635b0ae26ae5d971805e587e561e5` |
| 9 | Negotiations / Subawards / Proposals | real rows |
| 10 | `FreeableMemory` | **expect low GiB, not ~100 MB** — the direct test of the hypothesis |
| 11 | `EBSByteBalance%` after a measured workload | **expect a materially slower drain** |
| 12 | `CPUCreditBalance` | expect accrual toward the 576 cap |

Then, after a discarded warmup, re-measure the gated baseline set and
compare against the clean pre-resize baseline from §5.

## 8. Rollback procedure

**Fast path — reverse the class change (preferred):**

```
aws rds modify-db-instance --region us-east-1 \
  --db-instance-identifier research-archive-platform-dev-postgres \
  --db-instance-class db.t4g.micro --apply-immediately
aws rds wait db-instance-available --region us-east-1 \
  --db-instance-identifier research-archive-platform-dev-postgres
```

Same downtime characteristics as the forward change. **No data is at
risk** — a class change does not touch storage, so there is nothing to
restore and no data loss window.

**Snapshot path — only if the instance is left unhealthy:**
restore the §8 snapshot to a **new identifier**, then repoint the API's
`POSTGRES_HOST` secret. This is slow (tens of minutes) and changes a
secret, so it is the last resort, not the default.

**Rollback triggers:** any verification check 1–9 failing, the instance
not reaching `available`, or ECS unable to stabilise.

**Unchanged by either path:** enforcement OFF, auditing OFF, V089
unapplied, grant 2 untouched, no schema/index/migration change,
Production untouched. ECS stays on revision 94 throughout; 93 remains
its own rollback target.

## 9. The outstanding checks, now completed (credentials refreshed)

### 9.1 Unlimited mode is active, and it has cost nothing

Both surplus metrics are **published** for this instance, which confirms
Unlimited mode is in force:
`CPUSurplusCreditBalance`, `CPUSurplusCreditsCharged`.

| Metric, 24 h | Value |
|---|---|
| `CPUSurplusCreditsCharged` | **0 across all 288 datapoints** |
| `CPUSurplusCreditBalance` | max 2.26, non-zero in only 7 of 288 periods |
| `CPUCreditBalance` | max **288** — exactly the documented `t4g.micro` cap |

**The live cost question is closed: no surplus charges have been
incurred.** The instance briefly dipped into surplus for ~35 minutes
total but never far enough, or long enough, to be billed. So Unlimited
mode has been protecting performance for free rather than quietly
costing money.

### 9.2 Recovery behaviour — the two resources behave completely differently

**`EBSByteBalance%` recovers almost instantly, and it is a step, not a ramp.**

```
21:07=0.0  21:12=0.0  21:17=0.0   <- throttled during bound-3 measurement
21:20=0.0
21:25=99.5                        <- full within ONE five-minute period
21:30=97.0 ... 22:05=99.5         <- stable thereafter
```

Once load stops it refills inside ~5 minutes. (A naive linear fit gives
"31%/hour", which is meaningless for a step function and should not be
quoted.)

**`CPUCreditBalance` recovers slowly and linearly.**

```
21:55=0.0  22:00=0.2  22:05=0.8  22:10=1.3  22:15=1.9 ...  00:30=17.7
```

Measured **+6.85 credits/hour net** over 155 minutes, against a
documented gross accrual of **12/hour** for `t4g.micro` — the difference
being idle baseline consumption. At that rate, from 17.7:

| Target | Time to reach |
|---|---|
| 200 credits | **≈ 27 hours** |
| 288 (full) | **≈ 39 hours** |

`EBSIOBalance%` never reached zero (minimum 57.25%), so the **I/O**
balance was never the constraint — only the **byte/throughput** balance
was.

### 9.3 This invalidates my own proposed experiment gate

In `BOUNDED_FANOUT_EXPERIMENT_RESULTS.md` §5 I recommended requiring
**`CPUCreditBalance` ≥ 200** before each measurement window. That
recommendation is **wrong and should be dropped**, for two independent
reasons now measured:

1. **It is impractical.** Reaching 200 from the current balance would
   take about **27 hours of idle** per window. A four-configuration
   experiment would take most of a week.
2. **It is unnecessary.** Unlimited mode means a zero CPU credit balance
   does **not** throttle CPU (§1), and §9.1 shows it has not been
   charged either. A depleted CPU credit balance is therefore not a
   confound.

**The corrected gate is `EBSByteBalance%` ≥ 80% only**, which costs
about **five minutes of idle** between windows rather than a day. That
is the resource that actually throttled, and it is cheap to satisfy.

### 9.4 Backup and storage posture (for §6/§8)

| Property | Value |
|---|---|
| `BackupRetentionPeriod` | **7 days** — automated backups **are** enabled |
| `PreferredBackupWindow` | 05:19–05:49 UTC |
| `PreferredMaintenanceWindow` | **Wed 09:54–10:24 UTC** |
| `LatestRestorableTime` | current (within minutes) |
| Automated snapshots retained | 10, most recent 2026-11-12 05:25, `available` |
| `StorageThroughput` | **125 MB/s** (gp3 default at 20 GB) |
| `Iops` | 3,000 |
| `DeletionProtection` | **false** |
| `PendingModifiedValues` | none |
| Parameter group | `default.postgres17`, `in-sync` |

Two consequences for the plan:

- Automated backups at 7-day retention plus point-in-time recovery mean
  the §6 manual snapshot is **belt-and-braces rather than the only
  safety net** — but it should still be taken, because it gives a single
  named artefact to point at.
- `StorageThroughput` is confirmed at **125 MB/s**, and peak observed
  `ReadThroughput` was **82.6 MB/s**. That closes the gp3 argument in §4
  with a measured number rather than an inference: **the volume had 34%
  headroom at peak while the instance-level byte balance went to zero.**
  The limit is the instance, not the volume.
- `DeletionProtection: false` is worth noting independently of this
  work — not a blocker for a resize, but a DEV instance with no deletion
  protection is a separate small risk someone may want to close.

### 9.5 One item still unknown

The **documented EBS baseline throughput** (as opposed to the published
"up to 2,085 Mbps" burst) for `t4g.micro` is still not established. AWS
publishes the burst ceiling for T4g but not a per-size baseline
throughput figure in the pages consulted. Without it the drain rate can
be observed but not predicted. This does not affect the recommendation —
the drain was observed directly — but it means any claim about *how long*
a given workload can run before throttling would be a guess.
