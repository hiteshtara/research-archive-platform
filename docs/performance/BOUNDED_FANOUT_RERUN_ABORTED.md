# Bounded fan-out re-run — ABORTED at window 1 under A2 (2026-11-13)

**The experiment stopped at the first window, before any candidate was
measured.** The independent control tripped abort threshold A2 in the
**baseline** itself, which means the environment was unfit to measure in
— not that a candidate failed.

Revision 94 is restored and verified (§5). No permanent concurrency
change was made; revision 94 does not contain the fan-out implementation
at all.

## 1. Pre-flight — all conditions met

| Condition | Result |
|---|---|
| 1 — account | `AWS_PROFILE=bu-nprd` → **770203350335** verified before every mutation |
| 2 — revision 94 recorded | cpu 512, memory 1024, `sha256:1d440f251c…`, `ACTIVE`, serving, ALB healthy |
| 3 — revisions 97–100 | previously verified; `ALL_VERIFIED: true` |
| 4 — CPU/memory/security | unchanged throughout |
| 5 — burst gate | `EBSByteBalance%` **100%** (datapoint 00:40 UTC) |
| 6 — publication lag | latest datapoint was ~5.7 min old at gate time; noted, not treated as instantaneous |
| 11 — session | expired token found and **refused**; probe returned 401 and was never recorded. Re-authenticated to a fresh 59.9-minute token, verified 200 + JSON + real content before proceeding |

## 2. Window B₁ (revision 97, bound unset) — correctness passed, control failed

All correctness gates **PASSED** before any timing was accepted:

| Gate | Result |
|---|---|
| No token → 401 | PASS |
| `NSF` count 83 | PASS |
| `NSF` ordered digest `19b635b0ae26ae5d971805e587e561e5` | PASS |
| identifier digest `b53df917a01c94e33417948a0815db87` | PASS |
| non-matching digest `cb8be4528561905601c7e768cf5550ab` | PASS |
| `failedModules` empty | PASS |
| `moduleTotals` | AWARD 3286 / SUBAWARD 162 / PROPOSAL null / NEGOTIATION 3 |

Measurements (all HTTP 200 + `application/json` + content-checked, zero
discards):

| Row | n | Median | Min | Max | p90 |
|---|---:|---:|---:|---:|---:|
| Global Search, 1 user | 5 | 11,479 | 11,007 | 11,630 | 11,630 |
| Global Search, 3 concurrent users | 6 | 13,305 | 13,220 | 13,377 | 13,377 |
| **Award-solo control** | 3 | **9,968** | 9,923 | 10,026 | 10,026 |

**A2 threshold is 8,000 ms. The control read 9,968 ms in the baseline.**

## 3. Two diagnostics before accepting the abort

A tripped threshold in the *baseline* could mean the threshold was
miscalibrated rather than the environment being unfit, so both
possibilities were tested rather than assumed.

### 3.1 Is the control position-dependent? — NO

The control runs last in a window, after ~2.5 minutes of load, so it
might have been measuring the window's own load rather than the
environment. Tested on revision 97, unchanged between phases:

| Award-solo | n | Median |
|---|---:|---:|
| **Before** any load in the window | 3 | **10,218** |
| **After** the window's full load | 3 | **9,903** |
| Ratio | | **0.97** |

Essentially identical. **The control is not position-dependent**, so the
9,968 ms was not an artefact of where it sits. That hypothesis is
refuted.

### 3.2 Did the fan-out image cause it? — NO, the image is exonerated

Revision 97 carries the fan-out image; revision 94 does not. Award
search does not pass through `GlobalSearchService` at all, so the
semaphore should be irrelevant — but that needed proving, because a
regression in a merged change would be far more serious than a spoiled
experiment.

| Configuration | Image | n | Award-solo median |
|---|---|---:|---:|
| revision 97 | `sha256:df3dcfe2d4ba…` (with fan-out) | 3 | 10,218 |
| **revision 94** | `sha256:1d440f251c…` (**without** fan-out) | 5 | **9,733** |
| revision 94, **earlier the same day** | identical digest | 5 | **2,096** |

**Revision 94 is now just as slow as revision 97**, on the identical
image digest that measured 2,096 ms hours earlier. The fan-out image is
exonerated: PR #143 did not regress Award search.

### 3.3 And it is not burst throttling

At the time of these readings:

| Metric | Value |
|---|---|
| `EBSByteBalance%` | **99–100%** |
| RDS `CPUUtilization` | **22%** |
| `CPUCreditBalance` | recovering, non-zero |

So this is **not** the confound that spoiled the previous run. The
database is healthy by every metric available and Award search is still
~4.6× slower than it was this morning.

## 4. Conclusion: the environment, not the change

Award search moved from ~2,100 ms to ~9,900 ms on an unchanged image,
with no throttling signal, within one working day. The cause is **not
established**. The most plausible explanation consistent with everything
measured is the `db.t4g.micro`'s tiny cache: with `FreeableMemory` at
95–105 MB against a 491,685-row corpus, *which* query is fast depends on
what happens to be cached, and hours of Documents and Global Search
activity will have evicted whatever `award_version` pages were resident
this morning. **Stated as the leading explanation, not a finding** — no
cache-hit metric is available without Performance Insights.

**The control did exactly its job.** Last time, environmental drift was
discovered *after* a full run produced attractive-looking results. This
time it was caught in the first window, before a single candidate was
measured, and no misleading numbers were produced. That is the
difference between the two runs, and it is the argument for keeping the
control.

**A2 was not miscalibrated.** It was set from this morning's clean
observations (2,096–4,820 ms) and it correctly identified that the
system is no longer in that state. Re-calibrating it mid-experiment to
let the run continue would have been moving the goalposts to get a
result.

## 5. Restoration evidence (conditions 13, 14)

```
service taskDefinition : research-archive-platform-dev-api:94
running / desired      : 1 / 1,  pending 0
deployments            : 1,  rolloutState COMPLETED
running task           : cpu 512, memory 1024, lastStatus RUNNING
running task digest    : sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981
ALB rap-dev-api-tg     : 10.30.2.111 healthy
```

Post-restoration verification — **8 of 8 PASS**, each HTTP 200 +
`application/json` + verified content:

| Check | Result |
|---|---|
| No token → 401 | PASS (161 ms) |
| Award detail | PASS `212137-00001` (134 ms) |
| Dashboard | PASS `awards=40926`, `subawards=3511` |
| Documents `query=NSF` | PASS `totalElements=3382` |
| Global Search `NSF` | PASS 83 results |
| Negotiations / Subawards / Proposals | PASS, real rows |

Enforcement OFF, auditing OFF, V089 unapplied, grant 2 untouched, no
database write, Production untouched. Revisions 95–100 remain registered
and inactive.

## 6. Recommendation

**No concurrency setting is recommended.** Two attempts have now been
defeated by the same underlying problem from two different directions —
burst throttling the first time, unexplained 4.6× environmental drift
the second. The bounded fan-out code remains merged and **default-off**,
so nothing is live.

**Stop trying to measure this on the current database.** The DEV
`db.t4g.micro` is not a stable measurement platform: its ~100 MB of
cache means baseline performance for any given query swings several-fold
depending on recent activity, and that is larger than the effect the
experiment is trying to detect. Any further A/B work here will keep
failing its own controls.

The order should therefore be:

1. **Address the database first** —
   `DEV_RDS_UPGRADE_ASSESSMENT.md` already recommends `db.t4g.medium`
   (4× memory) as the proportionate, reversible step. A stable baseline
   is a *prerequisite* for the fan-out question, not a follow-up to it.
2. **Then re-run the fan-out experiment**, with the control retained and
   A2 re-calibrated against the new platform's own clean values.
3. **F3** (drop the Award leg's redundant `COUNT(*)`) is still worth
   doing independently and does not need a measurement platform to
   justify — it removes a provably redundant query.
