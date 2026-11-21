# RDS instance class — read-only cost/performance assessment

**Nothing was resized, modified or enabled.** This is an assessment for
a decision, not a proposal to act today.

## 1. Current state and why it is being questioned

`research-archive-platform-dev-postgres` — **`db.t4g.micro`**, 2 vCPU,
**1 GiB RAM**, PostgreSQL 17.9, gp3 20 GB / 3,000 IOPS, Single-AZ.

Measured today, not assumed:

| Observation | Value |
|---|---|
| `FreeableMemory` all day | **95–105 MB of 1,024 MB** |
| `ReadThroughput` under search load | sustained **30–50 MB/s**, peak 82.6 MB/s |
| `EBSByteBalance%` | 100% → 12% over six hours, then **0.0%** |
| `CPUCreditBalance` | ~273 → **0.0** |
| `CPUUtilization` peak | 33–41% |
| `ReadLatency` | 2 ms |
| `DiskQueueDepth` | < 1 |
| Documents union size | **491,685 rows** (`award_version` 267,386) |

The instance is **not** CPU-, IOPS-, latency- or queue-bound. It is
**memory-starved**, and that drives storage throughput high enough to
exhaust two separate burst budgets — both of which hit zero today under
a single tester.

With ~100 MB of freeable memory, PostgreSQL cannot cache a 491,685-row
union, so every large scan re-reads from storage.

## 2. Candidate classes — list price, us-east-1, PostgreSQL, Single-AZ

From the AWS Pricing API (`GetProducts`, `AmazonRDS`), 730 hours.
**Instance cost only** — storage, backups, I/O and data transfer are
unchanged by a class change and excluded.

| Class | vCPU | RAM | $/hour | $/month | Δ vs now | Burstable? |
|---|---:|---:|---:|---:|---:|---|
| **`db.t4g.micro`** (current) | 2 | 1 GiB | 0.016 | **$11.68** | — | **yes** |
| `db.t4g.small` | 2 | 2 GiB | 0.032 | $23.36 | +$11.68 | yes |
| `db.t4g.medium` | 2 | 4 GiB | 0.065 | $47.45 | +$35.77 | yes |
| `db.t4g.large` | 2 | 8 GiB | 0.129 | $94.17 | +$82.49 | yes |
| `db.m6g.large` | 2 | 8 GiB | 0.159 | $116.07 | +$104.39 | **no** |
| `db.m7g.large` | 2 | 8 GiB | 0.168 | $122.64 | +$110.96 | **no** |
| `db.r7g.large` | 2 | 16 GiB | 0.239 | $174.47 | +$162.79 | **no** |

Every candidate has the **same 2 vCPU** as today, except that the `m`
and `r` families are not burstable, so they have no credit budget to
exhaust. **What changes with price here is memory and the removal of
throttling, not core count** — which matters, because CPU has already
been measured not to be the constraint (both in the app and on the DB).

## 3. What each class would and would not fix

| Class | Addresses memory starvation? | Removes burst throttling? | Honest expectation |
|---|---|---|---|
| `t4g.small` (2 GiB) | marginally — still far below the working set | no, still burstable | likely insufficient; cheapest, but may not move the needle |
| `t4g.medium` (4 GiB) | meaningfully — `shared_buffers` roughly 4× today | no | plausible sweet spot on cost; still has a credit budget that can deplete |
| `t4g.large` (8 GiB) | substantially | no | more memory than `m6g.large` buys at lower cost, but still burstable |
| `m6g/m7g.large` (8 GiB) | substantially | **yes** — no credits to run out | the first option where today's two zero-balance events become impossible |
| `r7g.large` (16 GiB) | fully, for this working set | **yes** | most likely to cache the union outright; highest cost |

## 4. Recommendation

**Do not resize yet, and do not treat this as the obvious fix.** Two
reasons, both earned today:

1. **The CPU experiment is a fresh warning.** Buying capacity was the
   obvious answer for Global Search and it did not work — 4× app CPU
   left search flat and made the concurrent case 31% worse. The same
   could be true here, and the only way to know is to measure.
2. **Today's measurements are themselves contaminated.** Both burst
   balances hit zero, so an unknown amount of throttling is baked into
   every figure taken on this database today. A resize decision should
   not be made from numbers that may partly be measuring throttling.

**Suggested order, if you want to pursue it:**

1. **First re-establish a clean baseline** on the current class, with
   burst-balance gating (per `BOUNDED_FANOUT_EXPERIMENT_RESULTS.md` §5).
   Free, and it also unblocks the bounded fan-out re-run.
2. **If the clean baseline still shows search is slow**, the single most
   informative and cheapest step is **`db.t4g.medium`** (+$35.77/month,
   4× the memory) as a temporary, reversible change, measured the same
   way the CPU experiment was. An instance-class change on
   Single-AZ RDS requires a restart, so it is **not** zero-impact the
   way an ECS task definition swap is — it needs a window.
3. **Only if memory demonstrably helps**, consider whether the
   non-burstable `m7g.large` is warranted to remove throttling
   permanently, at +$111/month.

**What I would not do:** jump to `r7g.large`. It is 15× the current cost
for a DEV database, and nothing measured yet shows that 16 GiB is
required rather than 4.

## 5. Notes that belong with any decision

- These are **DEV** figures. Production sizing is a separate question
  with different data volumes, concurrency and availability
  requirements, and Multi-AZ roughly doubles instance cost.
- **Storage is not the issue.** 3,000 provisioned IOPS are 45% used and
  read latency is 2 ms. The `EBSByteBalance%` depletion is an
  *instance-level* throughput allowance, which is a property of the
  instance class, not of the volume.
- `gp3` volumes can be given higher throughput independently, which may
  be a cheaper partial mitigation than a class change. **Not costed
  here** and worth pricing before committing to a resize.
- No change to indexes, schema, migrations, parameter groups or
  Production is implied or recommended by this document.
