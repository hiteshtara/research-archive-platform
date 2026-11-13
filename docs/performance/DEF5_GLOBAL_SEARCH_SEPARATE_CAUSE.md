# Global Search — a separate cause from Documents

Investigated on the explicit instruction not to assume the Documents
optimization fixes Global Search. **It does not.** Different code,
different tables, different cause, and draft PR #141 touches none of it.

Source-verified against `perf/def5-investigation` (= `main` at 9729eff).

## 1. The two are structurally unrelated

| | Documents (DEF-5 fix) | Global Search |
|---|---|---|
| Entry | `DocumentExplorerService` | `GlobalSearchService` |
| Shape | one four-module `UNION ALL` CTE | six independent legs, fanned out |
| Cost centre | `award_pi`/`award_unit_counts`/`award_person_counts` over all 267,386 Award rows | `AWARD_FAMILY_SEARCH_WHERE`, an OR-chain over `is_primary_current = TRUE` rows |
| Evaluations per request | 3 (now 2, and light) | 2 per leg + a third Award query |
| Touched by PR #141 | yes | **no** |

`GlobalSearchService` does not call `DocumentExplorerRepository` at all.

## 2. Measured (authenticated DEV revision 93, content-verified)

| Leg / query | Median |
|---|---:|
| `query=NSF`, all modules | 17,312 ms |
| non-matching query | 18,110 ms |
| identifier query | 10,571 ms |
| Award leg alone | 11,776 ms |
| Proposal leg alone | 1,042 ms |
| Negotiation leg alone | 722 ms |
| Subaward leg alone | 2,533 ms |

## 3. What the Award leg actually does

`GlobalSearchService.searchAward()` issues, per request:

1. optionally `findAwardById` when the query is all digits
2. `awardArchiveService.search(query, 0, 25)` — which internally runs
   **both** `searchAwards(...)` and `countSearchAwards(...)`, each a full
   evaluation of `AWARD_FAMILY_SEARCH_WHERE`
3. `explainAwards(unexplained, query)` — a third query, for the rows
   whose match cannot be explained from their own displayed fields

So the dominant leg evaluates its expensive predicate at least twice
and issues a third query on top.

`AWARD_FAMILY_SEARCH_WHERE` is a single large `OR` chain over
`archive.award_version` restricted to `is_primary_current = TRUE`:

- five identifier `ILIKE :pattern` disjuncts (`award_number`,
  `sponsor_code`, `lead_unit_number`, `modification_number`, plus an
  exact `UPPER()` compare)
- a text branch that switches on the bound constant `:anchorWords`:
  substring `ILIKE` when the term does not qualify for anchoring,
  otherwise POSIX regex `~* :wordRegex` on `title`, `sponsor_name`,
  `lead_unit_name`
- `EXISTS` on `archive.award_person` (`full_name`)
- `EXISTS` on `archive.sponsor` (`acronym`)
- a **correlated** `EXISTS` joining `archive.award_version av2` to
  `archive.award_extension ext2` on `av2.award_number = av.award_number`,
  matching `grant_number` family-wide

For `NSF` specifically: `LexicalMatch.anchorsToWordStart("NSF")` is
**true** (all letters, no `*`, long enough), so the anchored regex
branch is the live one and the substring branch is discarded.

## 4. Why a non-matching query costs the same

Measured: non-matching 18,110 ms vs matching 17,312 ms — the
non-matching query is marginally **more** expensive.

A disjunction can stop early only when something matches. For a query
that matches nothing, **every** disjunct must be evaluated to false for
**every** candidate row, including the correlated `EXISTS`. There is
nothing to short-circuit. Cost is therefore proportional to the
**corpus**, not to the result set — which is exactly what the two
numbers show.

This also retires the earlier reading in PR #137 that "the semantic leg
runs hardest when nothing matches". The gap is ~800 ms, not a
step-change, and the simpler explanation above accounts for it.

## 5. Index coverage, stated precisely

| Column used by the OR chain | Index |
|---|---|
| `award_version.title` | `ix_award_version_title_trgm` (GIN trigram) |
| `award_version.sponsor_name` | `ix_award_version_sponsor_name_trgm` |
| `award_version.lead_unit_name` | `ix_award_version_lead_unit_name_trgm` |
| `award_version.modification_number` | `ix_award_version_modification_number_trgm` |
| `award_version.award_number` | `ix_award_version_number` |
| `award_version.sponsor_code` | `ix_award_version_sponsor` |
| `award_version.lead_unit_number` | `ix_award_version_lead_unit_number` |
| `award_person.full_name` | `ix_award_person_full_name_trgm` |
| `award_extension.award_id` | **implicit unique index** (it is the `PRIMARY KEY`, V046) |
| `award_extension.grant_number` | **none** |
| `sponsor.acronym` | **none** |

Two corrections worth stating, because the first draft of this analysis
got them wrong:

1. `award_extension` is **not** unindexed. `award_id` is its `PRIMARY
   KEY`, so the correlated join has an index to use. An earlier
   `grep` for `CREATE INDEX .*award_extension` returned nothing and
   that was misread as "no index".
2. So the correlated `EXISTS` is not necessarily a per-row table scan.
   Its cost is bounded by index lookups per candidate row — large
   because of the row count, not because of a missing index.

## 6. The 0.5 vCPU amplifier — verified live

The legs are handed to `CompletableFuture.supplyAsync` **with no
executor**. The code already documents the consequence, and it checks
out: when common-ForkJoinPool parallelism is 1 the JDK silently falls
back to a thread-per-task executor, so the legs do run concurrently
rather than serially.

But concurrency buys little here. Confirmed from the live task
definition (`ecs:DescribeTaskDefinition`,
`research-archive-platform-dev-api:93`):

```
taskCpu    = "512"   (0.5 vCPU)
taskMemory = "1024"  (1 GB)
```

With half a core, six concurrent legs contend, and wall-clock tends
toward the **sum** of their work rather than the maximum. The measured
numbers fit that: the legs sum to roughly 16 s
(11,776 + 1,042 + 722 + 2,533 + IRB + semantic) against a measured
total of 17,312 ms, while the slowest single leg is only 11,776 ms.

The service already has the instrumentation to settle this directly —
`SearchTimingLog` records `LEG_<module>_QUEUE_WAIT` separately from the
work, precisely to distinguish scheduling from execution. It is
**off**: `app.search.timing.enabled` defaults to `false`, so no per-leg
timings are being emitted on DEV. Turning it on is a configuration
change and a redeployment, which is out of scope here and would change
what revision 93 is serving.

## 7. Status: cause identified, no fix proposed

Stated as a **finding** (sections 3–6 are read from source and from the
live task definition) with one **hypothesis** clearly marked: the
precise split between predicate evaluation and CPU starvation cannot be
settled without either `EXPLAIN (ANALYZE)` on dev RDS or the timing log
enabled. Neither is available without a deployment or database access
that this work is not authorized to take.

No fix is proposed. For the record, a fix would **not** resemble the
Documents one — there is no union CTE to bypass. The candidate
directions, none investigated or costed:

- stop evaluating the predicate twice per leg (the `count` could be
  derived or dropped where the UI does not need an exact total)
- replace the single OR-chain with per-column indexed probes combined
  by `UNION`, which a planner can serve from the trigram indexes
- raise the task CPU above 0.5 vCPU, which is a cost and capacity
  decision, not a code change
