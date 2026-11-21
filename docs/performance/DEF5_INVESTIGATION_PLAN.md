# DEF-5 — performance investigation plan

**Executable now, with no database access.** Two tasks, both black-box, both
runnable against the live DEV application by an authenticated user. Execution
plans are an **optional follow-up** requiring separately approved access, not
a prerequisite.

Harness: `scripts/performance/def5-blackbox.mjs` — paste into the browser
console of an authenticated session.

---

## The measurement rule, and why it is first

**A timing is recorded only when the response is HTTP 200 and its content
validates.** Everything else is reported as a discarded sample and never
averaged in.

This is not pedantry. During the first profiling attempt the session token
expired mid-run, and a batch of **401 responses was recorded as ~50 ms "fast"
results** — very nearly producing the conclusion that DEF-5 was cold-start
warmup and closing a real defect. The harness refuses to start on a token with
under a minute left, and prints `counted` beside `runs` so a short count is
visible rather than silently averaged.

## Confirmed evidence so far

Authenticated, content-verified, revision 93, 2026-11-12:

| Measurement | Result |
|---|---|
| Documents + `query=NSF`, five warm repeats | 40,326 / 42,207 / 43,406 / 44,130 / 45,757 ms — **median 43,406** |
| Documents, no query | ~6,700 ms median |
| Documents + query + `module=PROPOSAL` | **1,801 ms** |
| Documents, `module=PROPOSAL` only | 462 ms |
| Documents + query at `size=1` vs `size=25` | 40,909 vs 41,629 ms |
| Global Search `NSF` | 12,203–23,677 ms, median 17,312 |
| Global Search non-matching | 18,110 ms |
| Global Search by identifier | 10,571 ms |
| Award leg alone | 11,776 ms |
| Proposal / Negotiation / Subaward legs | 1,042 / 722 / 2,533 ms |
| Baseline award detail | **32 ms** median |

**Not cold start** — five consecutive warm repeats stayed above 40 s.
**Not payload** — `size=1` costs the same as `size=25`.
**Not application overhead** — baselines are ~30 ms, so on a 43-second request
roughly 43,370 ms is query time.

---

## Task A — Documents cross-module text search

**Question:** why does adding a text term cost ~36.7 s, while adding
`module=` makes the same query 23× faster?

**Hypothesis:** the text predicate scans the combined ~491,685-row document set
across all modules with no usable index, and `module=` lets the planner use a
module-scoped path.

**Black-box steps** (harness rows A1–A6):

1. A1 vs A2 isolates the text term's cost.
2. A2 vs A3 tests payload — same cost at `size=1` means it is not transfer.
3. A4 vs A2 measures how much `module=` recovers.
4. A5 is the floor with no text term at all.
5. A6 uses a novel random term each run, so a result cache cannot flatter it.

**Evidence to record:** cold and warm figures per row, counted vs discarded,
and the A2→A4 ratio, which is the single most diagnostic number.

**Conclusion it supports:** if A4 is fast and A2 is slow at every page size,
the cost is in scanning the unfiltered document set, not in the term itself.

## Task B — Global Search Award leg and semantic overhead

**Question:** which leg dominates, and what does the semantic leg add?

**Hypothesis:** two separable costs — the Award leg (11.8 s alone) and a
semantic leg of roughly 6 s, inferred because the combined search (22.3 s)
exceeded the sum of its legs (~16.1 s).

**Black-box steps** (rows B1–B7): B4–B7 time each module's own endpoint for the
same term; B1 times the combined search; B2 uses a non-matching term, where
the semantic path should work hardest; B3 uses an identifier, which
`LikelyIdentifierDetector` short-circuits past the semantic leg.

**Evidence to record:** each leg, their sum, the combined total, and
`combined − Σlegs` as the semantic estimate. B3 vs B1 bounds the semantic
contribution from the other direction.

**A caveat this cannot resolve:** the legs are measured through separate
endpoints that may not be identical to the work Global Search does internally.
The inference is sound for locating the dominant cost; it is not a profiler.

---

## Optional follow-up — execution plans

**Not required for Tasks A and B**, which stand on their own.

If the black-box results justify going further, the plans would answer which
index is missing and whether the scan is sequential. That needs
`EXPLAIN (ANALYZE, BUFFERS)` against `archive` tables — read-only, but
requiring database access this engagement does not have: the tunnel forwards a
port but psql needs the `archive_analyst` password, which is outside standing
constraints to request or handle.

**Not proposed:** a SQL execution endpoint, or any request for a password.

**Route, if approved separately:** an operator runs prepared read-only
statements and returns the plans. The statements would be supplied with the
request, cover the combined document query with and without a module
predicate, and be accompanied by `pg_indexes` output for the tables involved.
Recorded as a known gap rather than a blocker.

## What this plan does not claim

These are **single-host, single-session** measurements on a shared DEV
environment with other activity possible. They establish magnitude and
locate the dominant cost. They are not a benchmark, and they do not establish
a production SLA — which is open decision **TC-042**, where the business has
yet to agree an acceptable response time.
