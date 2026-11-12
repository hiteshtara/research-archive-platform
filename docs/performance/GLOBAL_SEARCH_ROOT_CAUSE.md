# Global Search — root cause (2026-11-12)

Investigated independently of Documents search, against DEV revision 94.
No fix proposed. No index, migration, or configuration changed.

**This supersedes the attribution in
`DEF5_GLOBAL_SEARCH_SEPARATE_CAUSE.md`, which blamed the Award lexical
OR-chain predicate. Measurement does not support that.** The predicate
costs ~1.4–2.3 s. The bottleneck is CPU contention.

## 1. The finding

The API task has **0.5 vCPU** (`taskCpu` 512, verified live). Global
Search fans out **six concurrent legs** — IRB, Award, Negotiation,
Subaward, Proposal, and semantic. On half a core those six
database-backed requests compete for CPU, and the slowest leg inflates
by roughly **10×**.

The Award lexical SQL is not the problem. It is the victim.

## 2. The decisive measurement

The **identical** Award search request (`/api/v1/awards/search?q=NSF`,
the controller path, no Global Search involved), measured solo and then
alongside five other ordinary reads:

| Condition | n | Median | Samples |
|---|---:|---:|---|
| Solo | 3 | **1,443 ms** | 1,613 / 1,443 / 1,225 |
| Concurrent with 5 other reads | 3 | **14,848 ms** | 10,381 / 14,848 / 20,543 |
| | | **10.3× inflation** | |

Global Search's own AWARD leg measures **10,966 ms** — inside the
contended range.

This matters because it reproduces the anomaly **without Global Search**.
`AwardArchiveService.search` carries a TC-042 comment recording that
"the same three statements run on two paths that differ by about 11x on
a word query — ~893ms through AwardV1Controller, ~9,961ms as Global
Search's AWARD leg, with identical bound parameters … and no
transaction on either."

That was read as a difference between the two **paths**. It is not. It
is a difference between **running alone and running concurrently**. The
controller path reproduces the full ~10× when something else is running
beside it. Global Search is simply the thing that reliably puts five
competitors next to its own slowest leg.

## 3. What the Award lexical SQL actually costs

Measured directly through the controller, revision 94, all content-verified:

| Query | Branch taken | n | Median | Matches |
|---|---|---:|---:|---:|
| `NSF` | regex `~* \mNSF` | 4 | **2,257 ms** | 3,286 |
| `*NSF*` | ILIKE, **same** `%NSF%` pattern | 4 | **1,787 ms** | 3,456 |
| `NSF*` | ILIKE, prefix `NSF%` | 3 | 1,604 ms | 3,282 |
| `ZZQQXX` | regex, matches nothing | 3 | 1,941 ms | 0 |
| `*ZZQQXX*` | ILIKE, matches nothing | 3 | 1,523 ms | 0 |

`*NSF*` was chosen deliberately: `LexicalMatch.anchorsToWordStart`
returns false when the term contains `*`, so that query takes the
**ILIKE** branch with the **identical** `%NSF%` pattern the regex query
uses. It isolates regex-vs-ILIKE with selectivity held nearly constant
(ILIKE actually matches *more* rows — 3,456 vs 3,286 — the known
"TRANSFER contains NSF" effect, so it is the conservative direction).

**Regex costs about 25% more than ILIKE, not 4–10×.** A hypothesis that
POSIX `~*` evaluation was the bottleneck is therefore refuted. So is
"a non-matching query is pathological": `ZZQQXX` (1,941 ms) is *cheaper*
than `NSF` (2,257 ms) at the SQL level. The earlier observation that a
non-matching Global Search costs as much as a matching one is real, but
it is contention, not the predicate.

## 4. Expensive SQL operations, stated precisely

The Award leg issues **three** statements per request
(`GlobalSearchService.searchAward`):

1. `countSearchAwards(...)` — `COUNT(*)` over `award_version` under
   `AWARD_FAMILY_SEARCH_WHERE`
2. `searchAwards(...)` — the same predicate, one page of 25
3. `explainAwardMatches(...)` — explanation for rows whose match is not
   visible in their own displayed fields

Statement 3 is already well scoped: `WHERE av.award_id IN (:awardIds)`
with at most 25 ids, a `CROSS JOIN LATERAL` with `LIMIT 1`. Not a
concern.

Statements 1 and 2 share one predicate: `av.is_primary_current = TRUE`
(≈40,926 rows) AND a single large `OR` chain.

### Index coverage of each disjunct

| Disjunct | Index | Usable for this predicate |
|---|---|:--:|
| `UPPER(award_number) = UPPER(:rawQuery)` | `ix_award_version_number` (plain) | **no** — no expression index on `UPPER(...)` |
| `award_number ILIKE :pattern` | btree only | **no** — leading wildcard, no trigram |
| `sponsor_code ILIKE :pattern` | `ix_award_version_sponsor` (btree) | **no** |
| `lead_unit_number ILIKE :pattern` | `ix_award_version_lead_unit_number` (btree) | **no** |
| `modification_number ILIKE :pattern` | `ix_award_version_modification_number_trgm` | yes |
| `title` ILIKE / `~*` | `ix_award_version_title_trgm` | yes |
| `sponsor_name` ILIKE / `~*` | `ix_award_version_sponsor_name_trgm` | yes |
| `lead_unit_name` ILIKE / `~*` | `ix_award_version_lead_unit_name_trgm` | yes |
| `EXISTS award_person.full_name` | `ix_award_person_full_name_trgm` | yes (inner) |
| `EXISTS sponsor.acronym` | **none** | **no** |
| correlated `EXISTS` on `award_extension.grant_number` | `award_extension.award_id` is the PRIMARY KEY; `grant_number` has **none** | correlated — cannot join a BitmapOr on the outer table |

A disjunction can only be served by an index when **every** disjunct
has a usable index, so the planner can build a complete `BitmapOr`.
Here at least five disjuncts cannot, and one is correlated. So a
sequential scan of the `is_primary_current = TRUE` rows is the expected
and correct plan — and at ~1.4–2.3 s for ~40,926 rows it is not
unreasonable.

**This is why adding indexes is not the opportunity it looks like.**
Covering the gaps would need expression and trigram indexes on
`award_number`, `sponsor_code`, `lead_unit_number`,
`sponsor.acronym` and `award_extension.grant_number` — five or more new
indexes, with write amplification on the ETL load path — to maybe
remove 1–2 s from a 17–20 s request whose cost is ~85% contention.

## 5. Performance baseline (revision 94, content-verified)

| Row | n | Median | Min | Max | Results |
|---|---:|---:|---:|---:|---:|
| Global Search `NSF` | 3 | 17,116 | 15,830 | 28,839 | 83 |
| Global Search identifier | 3 | 12,384 | 4,385 | 18,480 | 1 |
| Global Search `NSF` AWARD leg | 3 | 10,966 | 3,855 | 11,004 | 25 |
| Award endpoint `NSF`, solo | 3 | **1,443** | 1,225 | 1,613 | 3,286 |
| Award endpoint `NSF`, contended | 3 | **14,848** | 10,381 | 20,543 | 3,286 |

Earlier, revision 93: `NSF` 20,401 · non-matching 28,097 · identifier
4,527 · Award leg 11,316 · Proposal 365 · Negotiation 718 ·
Subaward 1,876.

## 6. Correctness baseline

| Query | Results | Notes |
|---|---:|---|
| `NSF` | **83** | unchanged across revisions 93 → 94 |
| identifier `212137-00001` | **1** | unchanged |
| `NSF` AWARD leg | **25** | per-domain cap is 25 |
| `NSF` PROPOSAL leg | 30 | |
| `NSF` NEGOTIATION leg | 3 | |
| `NSF` SUBAWARD leg | 25 | |
| non-matching word | **5**, all `matchType: RELATED` | semantic only |
| identifier-shaped non-match | **0** | semantic skipped |

`failedModules` was empty on every sample. Semantic search **is**
enabled (`APP_SEARCH_SEMANTIC_ENABLED=true` on the task definition) and
is **cheap**: it is a Bedrock embedding round trip plus a pgvector
query, and `modules=PROPOSAL` — which pays the semantic leg, since
eligibility does not depend on `modules` — answered in **353 ms** warm.
The 1,571 ms first sample is the cold Bedrock connection.

## 7. Safe optimization opportunities, ranked by measured leverage

Nothing here is implemented or approved; each needs its own baseline.

1. **Raise the task CPU above 0.5 vCPU.** Highest leverage by a wide
   margin — it addresses ~85% of the measured cost. Not a code change;
   a cost and capacity decision. A 1 or 2 vCPU task would also improve
   Documents, the dashboard, and every other endpoint. This should be
   measured on a temporary revision before being adopted.
2. **Stop fanning out concurrently, or bound the fan-out.** Counter-
   intuitive but worth measuring: on 0.5 vCPU, wall-clock already tends
   toward the *sum* of the legs, so concurrency buys little while adding
   contention and context switching. Running legs sequentially, or
   limiting concurrency to 2, may be no slower and more predictable.
   A code change, low risk, measurable.
3. **Remove the redundant count from the Award leg.** Global Search
   displays per-module totals; the `COUNT(*)` is a second full
   evaluation of the predicate. This is the same saving that worked on
   Documents, worth ~0.7–1 s here. Small, safe, modest.
4. **Index the uncovered disjuncts.** Lowest leverage for the cost:
   five or more new indexes with ETL write amplification, for ~1–2 s of
   a 17–20 s request. **Not recommended** on current evidence, and it
   would require migration approval.

## 8. What is NOT established

- No `EXPLAIN (ANALYZE, BUFFERS)` was run. The split between server-side
  execution, connection acquisition and row materialisation inside the
  measured call durations is **not** separated. The code comment in
  `AwardArchiveService` names the two ways to separate it — Hikari's
  connection-acquire metric (already exported) or EXPLAIN — and both
  remain ungated work.
- `SearchTimingLog` would settle per-leg attribution directly, including
  `LEG_<module>_QUEUE_WAIT`. It is **off**:
  `app.search.timing.enabled` defaults to `false`. Turning it on is a
  configuration change and a redeployment, which was not authorized.
- The Hikari pool is `maximum-pool-size: 10`, `minimum-idle: 2`. With at
  most six concurrent legs, pool exhaustion is **unlikely** but has not
  been ruled out by metric. `minimum-idle: 2` does mean four
  connections must be created on a cold fan-out.
