# Bounded fan-out re-run — final plan (awaiting approval)

**Not executed.** DEV is on revision 94; RDS is `db.t4g.micro`,
unchanged, no pending modifications. Nothing here changes RDS or makes
any permanent concurrency change.

## 1. Pre-flight already done: revisions 97–100 verified

Requirement 4, completed. All four checked against revision 94:

| Rev | Bound | CPU | Mem | Image digest correct | Fan-out env | Only fan-out env + image differ | Secrets | Roles |
|---|---|---|---|:--:|---|:--:|:--:|:--:|
| 97 | **unset** | 512 | 1024 | ✅ | `<unset>` | ✅ | ✅ | ✅ |
| 98 | **3** | 512 | 1024 | ✅ | `3` | ✅ | ✅ | ✅ |
| 99 | **2** | 512 | 1024 | ✅ | `2` | ✅ | ✅ | ✅ |
| 100 | **1** | 512 | 1024 | ✅ | `1` | ✅ | ✅ | ✅ |

All four `ACTIVE`, all pinned to
`sha256:df3dcfe2d4badb5fdbd1c7e62907aad64bde610954e45c5c99aa6c7fd3ce3ffd`
(the fan-out image, correctly **different** from revision 94's
`sha256:1d440f251c…`, which lacks the implementation). Verified
programmatically by normalised whole-task-definition comparison, not by
eye: `ALL_VERIFIED: true`.

## 2. Can the experiment finish without exhausting the byte balance?

**Yes, with large margin — and the arithmetic holds even assuming zero
recovery between windows.**

### Measured depletion rate

From the 2026-11-12 session, during continuous search load:

```
19:44=45%  19:49=44%  19:54=35%  19:59=28%  20:04=24%  20:09=18%  20:14=12%
```

**33 percentage points over 30 minutes = 1.10 pp per minute of
continuous load.**

### Load budget per window

| Step | Requests | ~Load time |
|---|---:|---:|
| Correctness digests (NSF, identifier, non-matching) | 3 | 36 s |
| Warmup (discarded) | 3 | 35 s |
| W-single: Global Search, single user, n=5 | 5 | 75 s |
| W-conc: Global Search, 3 concurrent users, 2 rounds | 6 | 50 s |
| W-ctrl: Award solo control, n=3 | 3 | 12 s |
| **Total per window** | **20** | **≈ 3.5 min** |

### Budget

| | Value |
|---|---|
| Per window | 3.5 min × 1.10 = **3.9 pp** |
| 7 windows | **27 pp total** |
| Starting at 100% → worst case | ends **~73%** |
| Starting at the 80% gate → worst case | ends **~53%** |
| Abort threshold | **20%** |

**Worst case lands 33 pp above the abort threshold and 53 pp above
throttling.** And that assumes *no* recovery at all, which is
pessimistic: the balance refills from 0% to 99.5% within a single
five-minute period of idle, and each inter-window deployment provides
~3.5 minutes of zero load. Realistically the balance should re-enter
each window at ≥95%.

This is the single biggest change from the aborted run: that run used
**~8 minutes of load per window** and ran windows back to back. This
plan halves the load per window and inserts a deployment-length recovery
gap between every one.

Current balance: **99.5–100%**. The gate is satisfied now.

## 3. Final test sequence — 7 windows, fully interleaved

Baseline either side of every candidate, so a monotonic environmental
drift cannot be mistaken for an effect:

| # | Window | Revision | Bound | Role |
|---|---|---|---|---|
| 1 | **B₁** | 97 | unset | baseline |
| 2 | **C₃** | 98 | 3 | candidate |
| 3 | **B₂** | 97 | unset | baseline |
| 4 | **C₂** | 99 | 2 | candidate |
| 5 | **B₃** | 97 | unset | baseline |
| 6 | **C₁** | 100 | 1 | candidate |
| 7 | **B₄** | 97 | unset | baseline |
| — | restore | **94** | n/a | final state |

Each candidate is compared against the **mean of its two adjacent
baselines** — C₃ vs (B₁,B₂), C₂ vs (B₂,B₃), C₁ vs (B₃,B₄). Requirement 6
is met for every candidate including the last, which the 6-window
variant would not have done.

### Per-window procedure

1. `AWS_PROFILE=bu-nprd`, confirm account **770203350335**.
2. Deploy the revision; `aws ecs wait services-stable`; confirm
   `rolloutState COMPLETED`, running 1/1, ALB target `healthy`, and the
   running task's image digest.
3. **Gate: read `EBSByteBalance%`. Require ≥ 80%.** If below, idle until
   it recovers (minutes) rather than proceeding.
4. Record pre-window metrics: `EBSByteBalance%`, `EBSIOBalance%`,
   `CPUCreditBalance`, RDS `CPUUtilization`, `FreeableMemory`,
   `ReadLatency`, `ReadThroughput`, `DatabaseConnections`; and ECS
   `CpuUtilized`/`CpuReserved`, `MemoryUtilized`.
5. **Correctness gates, before any timing** — compare against the
   recorded control digests:
   - `NSF` → **83** results, ordered digest `19b635b0ae26ae5d971805e587e561e5`
   - identifier → **1** result, digest `b53df917a01c94e33417948a0815db87`
   - non-matching → **5** results, digest `cb8be4528561905601c7e768cf5550ab`
   - `moduleTotals` identical; `failedModules` **empty**
   - no-token request → **401**
6. Warmup: 3 requests, **discarded by construction**.
7. Measure: W-single (n=5), W-conc (3 users × 2 rounds), W-ctrl (n=3).
8. Record post-window metrics, same list as step 4.
9. Proceed to the next window (its deployment is the recovery gap).

Every accepted sample requires **HTTP 200 + `application/json` + a
content check**. A 401 is never recorded as a timing.

## 4. Estimated duration

| Phase | Per window | ×7 |
|---|---:|---:|
| Deploy + wait stable + health | 3.5 min | 24.5 min |
| Gate + pre/post metrics | 0.7 min | 4.9 min |
| Correctness gates | 0.6 min | 4.2 min |
| Warmup | 0.6 min | 4.2 min |
| Measurement | 3.5 min | 24.5 min |
| **Subtotal** | **8.9 min** | **62 min** |
| Restore revision 94 + verify | — | 5 min |
| **Total** | | **≈ 67 min**, call it **60–75 min** |

Of that, roughly **33 minutes requires an authenticated session**. A
60-minute token will not cover the whole run end to end, so **one
mid-experiment re-authentication should be expected** — between windows,
never mid-measurement. If a token lapses mid-window, that window is
discarded and repeated rather than partially recorded.

**Load on the database is ~25 minutes of the ~67**, spread across seven
separated windows. This is not a sustained load test.

## 5. Abort thresholds

Any of these stops the experiment and restores revision 94 immediately:

| # | Threshold | Rationale |
|---|---|---|
| A1 | `EBSByteBalance%` **< 20%** at a pre-window gate or post-window read | throttling begins at 0%; 20% is the margin that protects the remaining windows |
| A2 | Award-solo control median **> 8,000 ms** in any window | across every clean window the control measured 2,096 / 2,298 / 4,561 / 4,820 ms; the confounded run measured 10,140. 8,000 sits above all clean observations and below the known-bad one |
| A3 | Award-solo control median **> 2× the B₁ value** | relative drift guard, in case absolute conditions differ from 2026-11-12 |
| A4 | Any correctness digest, result count, `moduleTotals` or `failedModules` mismatch | a correctness regression ends the experiment regardless of performance |
| A5 | No-token request returns anything but **401** | authorization behaviour must not change |
| A6 | ALB target not `healthy`, or ECS not reaching `COMPLETED` / stable | health failure |
| A7 | **> 20% of a window's samples discarded** (non-200, non-JSON, network) | the window is unreliable; repeat it rather than report it |
| A8 | Session token < 90 s headroom | never record a 401 as a timing |

A1 and A2 are the two that the aborted run would have tripped — A1 at
0.0%, A2 at 10,140 ms. Both are now explicit and checked **every**
window rather than discovered afterwards.

### Decision rule

The hypothesis is supported only if a candidate improves the **Award
contended** or **3-concurrent-user** median by **≥ 20%** against the
mean of its adjacent baselines, **with the Award-solo control stable**
(A2/A3 not tripped) in all three windows. Otherwise the result is
reported as "no supported improvement" and the default stays off.

## 6. What this plan does not do

- No RDS change of any kind: class, storage, parameter group, Performance
  Insights, deletion protection — all untouched.
- CPU Unlimited mode unchanged (requirement 3); no `CPUCreditBalance`
  gate (requirement 2), for the reasons in
  `DEV_RDS_UPGRADE_ASSESSMENT.md` §9.3.
- No permanent concurrency change. Revision 94 — which does **not**
  contain the fan-out implementation — is the final state, so the
  feature is not merely defaulted off but absent from what serves DEV.
- No enforcement, auditing, migration or Production change. V089 stays
  unapplied; grant 2 untouched.
- Revisions 95–100 stay registered and inactive afterwards.
