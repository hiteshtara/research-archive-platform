# DEV RDS capacity experiment — proposal (not executed)

**Nothing resized.** RDS is `db.t4g.micro`, `DeletionProtection: false`,
no pending modifications. This proposes an experiment; it does not run
one.

Compares the current **`db.t4g.micro`** with **`db.t4g.medium`**.

## 1. Why this comes before any further fan-out work

Two bounded-concurrency attempts have now failed, from two different
directions, both tracing to the same platform:

| Attempt | Failure |
|---|---|
| 2026-11-12 | `EBSByteBalance%` hit **0%** mid-run; control moved 4.8× |
| 2026-11-13 | Control read **9,968 ms** in the *baseline*, vs 2,096 ms that morning on the identical image digest, with the database showing **99–100%** balance and **22%** CPU |

The second is the more damning: no throttling signal at all, yet Award
search was **~4.6× slower** than hours earlier on unchanged code.

**The platform's own baseline moves by more than the effect we are
trying to measure.** That makes A/B comparison impossible here, and it
is why further fan-out experiments are suspended rather than retried.

The leading explanation — stated as explanation, not finding — is the
instance's cache: `FreeableMemory` sits at **95–105 MB of 1,024 MB**
against a 491,685-row corpus, so which query is fast depends on what
happens to be resident, and that changes with whatever ran recently.

**This experiment's real purpose is therefore not "make search faster".
It is "make the platform measurable".** A speed improvement would be
welcome; measurement stability is the requirement.

## 2. What `db.t4g.medium` does and does not change

Documented AWS specifications:

| | `db.t4g.micro` | `db.t4g.medium` | Change |
|---|---|---|---|
| vCPU | 2 | 2 | **none** |
| Memory | **1 GiB** | **4 GiB** | **4×** |
| Baseline CPU / vCPU | 10% | 20% | 2× |
| CPU credits / hour | 12 | 24 | 2× |
| Max accrued credits | 288 | 576 | 2× |
| **EBS burst bandwidth** | ≤ 2,085 Mbps | **≤ 2,085 Mbps** | **none** |

**Stated plainly: this does not raise the EBS throughput ceiling.** The
expected mechanism is indirect — more memory → more of the working set
cached → fewer bytes read → the byte balance drains more slowly and
baselines become less dependent on cache contents.

That indirection is why this is proposed as an **experiment with
objective acceptance criteria**, not as a fix.

## 3. Cost

AWS Pricing API, us-east-1, PostgreSQL, Single-AZ, 730 h, instance only:

| | $/hour | $/month | Δ |
|---|---:|---:|---:|
| `db.t4g.micro` | 0.016 | **$11.68** | — |
| `db.t4g.medium` | 0.065 | **$47.45** | **+$35.77** |

**Storage cost is unchanged.** 20 GB gp3 ≈ $1.60/month; its 3,000 IOPS
and 125 MB/s are within the free allocation at this size, and nothing
here provisions more. No additional throughput charges.

Surplus CPU credits remain possible under Unlimited mode, but
`CPUSurplusCreditsCharged` has been **0 across 24 hours**, and medium's
doubled accrual makes surplus *less* likely, not more.

## 4. Downtime

A class change on a **Single-AZ** instance has no failover: the instance
is genuinely unavailable while it happens.

| | Expectation |
|---|---|
| Duration | **several minutes** — commonly ~5–10 for a class change. **This is not a promise**; the actual figure should be measured and recorded |
| Effect | every database-backed endpoint fails; `/actuator/health` likely fails with it, so ECS may cycle the task — expected and self-correcting |
| After | PostgreSQL restarts with an **empty buffer cache**, so the first measurements are cold and must be discarded |
| Avoid | the maintenance window, **Wed 09:54–10:24 UTC** |
| Notify | Lalitha, the only other user, should not be testing during it |

## 5. Snapshot and rollback

**Backups already in place:** `BackupRetentionPeriod` **7 days**,
point-in-time recovery current, 10 automated snapshots retained.

**Still take a manual snapshot first** — belt-and-braces, and it gives a
single named artefact:

```
aws rds create-db-snapshot --region us-east-1 \
  --db-instance-identifier research-archive-platform-dev-postgres \
  --db-snapshot-identifier rap-dev-pre-t4g-medium-20261113
aws rds wait db-snapshot-available --region us-east-1 \
  --db-snapshot-identifier rap-dev-pre-t4g-medium-20261113
```

**Rollback — reverse the class change (preferred):**

```
aws rds modify-db-instance --region us-east-1 \
  --db-instance-identifier research-archive-platform-dev-postgres \
  --db-instance-class db.t4g.micro --apply-immediately
aws rds wait db-instance-available --region us-east-1 \
  --db-instance-identifier research-archive-platform-dev-postgres
```

**No data is at risk.** A class change does not touch storage — there is
nothing to restore and no data-loss window. The snapshot path (restore
to a *new* identifier, then repoint the `POSTGRES_HOST` secret) is the
last resort only, and is slow.

**Rollback triggers:** any correctness check failing, the instance not
reaching `available`, ECS unable to stabilise, or acceptance criteria
not met **and** no diagnostic value in staying.

### Terraform interaction

`instance_class` is Terraform-managed
(`var.database_instance_class`). A CLI resize creates drift that the
next plan would revert. **Either** make the change in Terraform, **or**
accept it as a temporary experiment and revert before any apply. Given
PRs #144/#145 are pending, the cleanest sequence is: land those, then do
the resize through Terraform. **Flagged rather than decided** — it is a
real coupling and should not be discovered mid-experiment.

## 6. Objective acceptance criteria

Measured **before** the resize and again after, with a discarded warmup
and the `EBSByteBalance%` ≥ 80% gate at both ends.

### Primary — stability (the actual goal)

| # | Criterion | Threshold |
|---|---|---|
| **S1** | **Award-solo repeatability across 3 runs separated by ≥10 min of mixed load** | **max/min ≤ 1.5×**. Today this ratio is ~4.6× (2,096 → 9,733 ms). This is the criterion that decides success |
| S2 | Within-run spread, Award-solo n=5 | max/min ≤ 1.3× |
| S3 | `FreeableMemory` after warmup | **> 1,000 MB** (today: 95–105 MB) |
| S4 | `EBSByteBalance%` drain over a fixed 10-min load | **< half** the current 1.10 pp/min |

### Secondary — speed (welcome, not decisive)

| # | Criterion | Threshold |
|---|---|---|
| P1 | Documents `query=NSF` median | no regression vs the pre-resize measurement |
| P2 | Global Search `NSF` median | no regression |
| P3 | Award-solo median | no regression |
| P4 | Record detail median | ≤ 500 ms |

### Correctness — must all pass before any timing is compared

| # | Criterion |
|---|---|
| C1 | Documents `query=NSF` → `totalElements` **3382**, digest `8b21c2799ca969abdaf166b6e3bfefa0` |
| C2 | Documents unfiltered → **491685** |
| C3 | Global Search `NSF` → **83** results, digest `19b635b0ae26ae5d971805e587e561e5` |
| C4 | Global Search identifier → **1**, digest `b53df917a01c94e33417948a0815db87` |
| C5 | `failedModules` empty; `moduleTotals` unchanged |
| C6 | No-token request → **401** |
| C7 | Dashboard → `awards=40926`, `subawards=3511` |

### Decision rule

- **S1 met** → the platform is measurable; keep medium, resume the
  fan-out experiment on it.
- **S1 not met** → memory was not the instability driver. **Roll back to
  micro** and do not spend more on capacity until the cause is
  identified; the next candidates are `t4g.large` (first size that
  raises the EBS ceiling) or non-burstable `m7g.large`.
- **Any correctness criterion fails** → roll back immediately,
  regardless of performance.

## 7. Estimated duration

| Phase | Time |
|---|---|
| Pre-resize baseline (3 runs for S1, spaced) | ~35 min |
| Snapshot + wait | ~10 min |
| Resize + wait available | ~10 min |
| Post-resize warmup (discarded) | ~5 min |
| Post-resize measurement (3 runs for S1, spaced) | ~35 min |
| **Total** | **≈ 95 min** |

S1 requires spaced runs with intervening load, which dominates the
schedule. Compressing it would destroy the only criterion that matters.

Roughly 50 minutes needs an authenticated session, so **expect two
re-authentications**, taken between runs.

## 8. Controls held throughout

DEV API stays on **revision 94**; revision 93 preserved; revisions
95–100 inactive. Enforcement OFF, auditing OFF, V089 unapplied, grant 2
unchanged, no schema/index/migration change, no database write,
Production untouched.

## 9. What is NOT proposed

- No instance class beyond `t4g.medium`.
- No gp3 throughput change — the volume was measured to have **34%
  headroom at peak** (82.6 MB/s against 125 MB/s), so it is the wrong
  layer.
- No Performance Insights or Enhanced Monitoring (paid monitoring).
- No parameter-group change — `shared_buffers` scales with instance
  memory by default, which is the mechanism being tested; tuning it
  manually would confound the result.
