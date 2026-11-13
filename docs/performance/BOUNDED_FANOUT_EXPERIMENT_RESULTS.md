# Bounded fan-out experiment — ABORTED as confounded (2026-11-12)

**Verdict: the comparison is not valid and I am not reporting a
recommended concurrency setting from it.** The database exhausted both
its storage-throughput burst and its CPU credits part-way through, so
the control and the bound were measured under materially different
conditions. Revision 94 is restored and verified (§6).

The experiment infrastructure is sound and reusable; only the
measurement window was spoiled. §5 says how to re-run it safely.

## 1. What ran

Merge commit **`f214001`** (PR #143 merged). Image built from a clean
detached worktree at exactly that commit, `--platform linux/amd64`, and
the **image's own jar** verified to contain `fanOutConcurrency`,
`fanOutPermits`, `PERMIT_WAIT`, `app.search.fan-out-concurrency` and
`gated` — necessary because the Dockerfile builds its own jar via
`COPY --from=build`, so a host-built jar proves nothing.

Pushed to ECR as `fanout-f214001`; the **linux/amd64 child manifest**
`sha256:df3dcfe2d4badb5fdbd1c7e62907aad64bde610954e45c5c99aa6c7fd3ce3ffd`
was pinned, not the buildx index (which also carries an
`os=unknown` attestation manifest).

| Revision | `APP_SEARCH_FAN_OUT_CONCURRENCY` | CPU | Memory | Measured |
|---|---|---|---|---|
| 97 | **unset** (control) | 512 | 1024 | yes |
| 98 | **3** | 512 | 1024 | yes, **confounded** |
| 99 | 2 | 512 | 1024 | **not reached** |
| 100 | 1 | 512 | 1024 | **not reached** |

All four were asserted programmatically to be digest-pinned, CPU 512,
memory 1024, with **every other environment variable, both IAM roles and
all five secrets identical to revision 94**.

## 2. The confound, with the evidence

RDS burst metrics across the measurement window
(`research-archive-platform-dev-postgres`, `db.t4g.micro`):

| Time (UTC) | `EBSByteBalance%` | `CPUCreditBalance` | What was running |
|---|---:|---:|---|
| 20:55–21:05 | 2–12% | ~272 | **control (rev 97) measured** |
| 21:07 | **0.0%** | ~272 | bound 3 measured |
| 21:12 | **0.0%** | ~273 | bound 3 measured |
| 21:17 | **0.0%** | ~273 | bound 3 measured |
| 21:22 | 99.5% | **0.0** | after |
| 21:27 | 97.0% | **0.0** | after |

Two separate resources bottomed out:

- **`EBSByteBalance%` reached 0.0%** for at least three consecutive
  five-minute periods during the bound-3 measurement. At 0% the volume
  is throttled to the instance's baseline throughput. The control was
  measured while it still had 2–12%.
- **`CPUCreditBalance` reached 0.0.** The `db.t4g.micro` is burstable;
  with no credits it is throttled to its baseline CPU allowance.

So the control ran on a database with credits; bound 3 ran on one that
was being throttled. **Any latency difference between them is
uninterpretable.**

### The internal tell that caught it

`W5_awardSolo` measures `/api/v1/awards/search` — a single request
through the Award controller, which **does not go through
`GlobalSearchService` and therefore cannot be affected by the
semaphore at all**.

| Row | Control | Bound 3 | Change |
|---|---:|---:|---|
| W5 Award search **solo** | 2,096 ms | **10,140 ms** | **4.8× worse** |

A metric the change cannot touch moved by 4.8×. That is the signature of
the environment degrading underneath the experiment, and it is why the
other rows cannot be trusted either.

## 3. The numbers, recorded but NOT to be acted on

Reported for completeness and because the re-run should reproduce or
refute them. **Every one of these is confounded per §2.** All were
HTTP 200 + `application/json` + content-checked.

| Row | n | Control (rev 97) | Bound 3 (rev 98) | Apparent |
|---|---:|---:|---:|---|
| W1 Global Search, single user | 5 | 18,163 | 11,169 | −38% |
| W4 Global Search, **3 concurrent users** | 9 / 6 | 38,958 | 13,018 | −67% |
| W6 Award **contended** | 5 | 14,196 | 11,319 | −20% |
| W8 Record detail | 7 / 5 | 172 | 81 | −53% |
| W5 Award **solo** | 5 / 3 | 2,096 | **10,140** | **+384%** ← the tell |

Min/max and indicative p90/p95 (n≤9; **not** a service level):

| Row | Control min–max (p90) | Bound 3 min–max (p90) |
|---|---|---|
| W1 | 12,917–20,474 (20,474) | 10,932–11,401 (11,401) |
| W4 | 33,589–40,691 (40,691) | 12,807–13,213 (13,213) |
| W6 | 9,393–20,702 (20,702) | 10,502–11,394 (11,394) |

The bound-3 **spread is strikingly tight** (W1 10,932–11,401; W4
12,807–13,213) against the control's wide spread. That is interesting
and consistent with bounding removing contention jitter — but it is
equally consistent with a throttled database serialising everything to a
uniform rate. **The experiment cannot distinguish those.**

### Correctness gates — what passed and what could not be read

| Gate | Control | Bound 3 |
|---|---|---|
| No token → 401 | PASS | **PASS** |
| Identifier query, ordered digest vs control | baseline `b53df917…` | **PASS, identical** |
| Non-matching query, ordered digest vs control | baseline `cb8be452…` | **PASS, identical** |
| `NSF` count 83 and ordered digest | baseline 83 / `19b635b0…` | **NOT READ** — the gate request hit a transient network error |

The `NSF` gate is recorded as **not read**, not as a failure. Its
dependent checks (`nsfDigestMatchesControl`, `failedModulesEmpty`)
reported FAIL only because the digest was `undefined`, which is a
measurement gap and not a correctness finding. Two of the three
digests did match exactly, and 83 results were returned on every W1 and
W4 sample at bound 3 (the validator required it), so there is **no
evidence of a correctness problem** — but the NSF digest gate must be
re-run before anyone relies on it.

## 4. What this experiment DID establish

Even aborted, it produced the strongest evidence yet about the
downstream constraint, and it is **measured**, not inferred:

**The `db.t4g.micro` exhausts both of its burst allowances under this
workload.** `EBSByteBalance%` reached **0%** and `CPUCreditBalance`
reached **0** within roughly 90 minutes of search activity by a single
tester. Earlier today the byte balance had already fallen 100% → 12%
without recovering.

That is no longer a hypothesis about cache pressure — it is a recorded
depletion of two distinct burst budgets. It also means **every
performance number taken on this DEV database today carries an unknown
amount of throttling**, including some of the earlier Documents and CPU
figures, and that caveat should attach to them.

## 5. How to re-run it validly

1. **Gate on `EBSByteBalance%` ≥ 80% before and after every
   configuration**, and record it at both ends. Abort the window if it
   falls below 20% mid-measurement.

   **Corrected 2026-11-13:** this originally also required
   `CPUCreditBalance` ≥ 200. That is dropped. Measurement showed the
   credit balance recovers at only **+6.85/hour net**, so reaching 200
   would take **~27 hours per window**; and RDS T4g runs in **Unlimited
   mode**, so a zero credit balance does not throttle CPU and
   `CPUSurplusCreditsCharged` has been **0 for 24 hours**. The CPU
   credit balance is therefore neither a confound nor a cost here. See
   `DEV_RDS_UPGRADE_ASSESSMENT.md` §9.3.

   `EBSByteBalance%` by contrast refills from 0% to 99.5% within a
   single five-minute period of idle, so this gate costs ~5 minutes
   between windows.
2. **Wait for recovery between configurations** rather than measuring
   back-to-back. The balance recovered to ~99% within about 5 minutes of
   idle, so this is cheap.
3. **Interleave rather than sequence** — control, bound, control, bound —
   so a monotonic environmental drift cannot masquerade as an effect.
4. **Keep `W5_awardSolo` as the control variable.** It cannot be
   affected by the semaphore, so any movement in it invalidates the
   window. It worked exactly as intended here.
5. Re-run the NSF digest gate, which was not read.
6. Reach bounds 2 and 1, which were never measured.

## 6. Restoration evidence

Revision 94 restored as soon as the confound was identified, before any
analysis.

```
service taskDefinition : research-archive-platform-dev-api:94
running / desired      : 1 / 1,  pending 0
deployments            : 1,  rolloutState COMPLETED
service event          : "has reached a steady state."
running task           : cpu 512, memory 1024, lastStatus RUNNING
running task digest    : sha256:1d440f251c6d725b4e1d34907461f747a0367080a6a7bfbab395412ec1927981
ALB rap-dev-api-tg     : 10.30.2.54 healthy
```

Post-restoration functional verification — **9 of 9 PASS**, each HTTP
200 + `application/json` + verified content:

| Check | Result |
|---|---|
| No token → 401 | PASS (auth enforced) |
| Award detail | PASS `212137-00001` |
| Dashboard | PASS `awards=40926`, `subawards=3511` |
| Documents `query=NSF` | PASS `totalElements=3382` |
| Documents unfiltered | PASS `totalElements=491685` |
| Global Search `NSF` | PASS 83 results |
| Negotiations / Subawards / Proposals | PASS, real rows |

Enforcement OFF, auditing OFF, V089 unapplied, grant 2 untouched. No
database write at any point. Production untouched.

Revisions 95–100 remain registered but **inactive**. 97–100 are reusable
for the re-run without a rebuild.

## 7. Recommended concurrency setting

**None yet.** The ≥20% improvement gate on W6 was nominally met
(14,196 → 11,319 ms, −20.3%), but it was met under a throttled database
alongside a 4.8× regression in a metric the change cannot influence.
Acting on that would be acting on noise.

The code is merged and **default-off**, so nothing is live and nothing
needs reverting. The setting should be chosen from the re-run in §5.
