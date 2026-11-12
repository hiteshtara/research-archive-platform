# DEF-5 — revision 93 baseline (captured 2026-11-12)

Captured against the **deployed** DEV API task definition
`research-archive-platform-dev-api:93` (`taskCpu` 512 = **0.5 vCPU**,
1 GB, verified via `ecs:DescribeTaskDefinition`), from an authenticated
session whose `auth_time` was 1.3 minutes old at the start.

**Every number below is from an HTTP 200 with `content-type:
application/json` that also passed a per-row content check.
Zero samples were discarded across the entire capture.** No 401 and no
`text/html` SPA response is recorded anywhere.

Measured from the **absolute API origin**
`https://api-dev.app-nprd.aws-cloud.bu.edu`.

## 1. Documents (`/api/v1/documents`) — timing gates

`page=0&size=25&includeAnyUnit=false&piOnly=false` unless stated.

| Gate | Request | n | Median | Min | Max | totalElements |
|---|---|---:|---:|---:|---:|---:|
| 9 | `query=NSF`, all modules | 5 | **38,014** | 36,086 | 45,362 | 3,382 |
| 10 | `query=NSF&module=AWARD` | 5 | **12,860** | 10,131 | 16,780 | 1,734 |
| 11 | `query=NSF&module=PROPOSAL` | 5 | **1,587** | 1,435 | 3,295 | 1,361 |
| 12 | unfiltered default page | 5 | **6,405** | 5,285 | 10,598 | 491,685 |
| 13 | `personName=SMITH` (heavy-path control) | 3 | **35,499** | 35,090 | 42,065 | 1,040 |
| 14 | `/api/awards/212137-00001` (baseline) | 5 | **99** | 59 | 387 | — |
| A5 | `query=NSF&module=NEGOTIATION` | 3 | 355 | 235 | 1,161 | **0** |
| A6 | `query=NSF&module=SUBAWARD` | 3 | 4,761 | 4,501 | 4,939 | 287 |

### Internal consistency (a real check, not a restatement)

The per-module runs independently reproduce the all-modules facets:

```
AWARD 1,734 + PROPOSAL 1,361 + SUBAWARD 287 + NEGOTIATION 0 = 3,382
                                       = gate 9 totalElements ✓
```

And the unfiltered facets sum to the unfiltered total:

```
267,386 + 119,788 + 93,736 + 10,775 = 491,685 ✓
```

Gate 13's content check additionally verified that **every returned row's
`primaryPersonName` contains SMITH**, so the person filter demonstrably
applies rather than being silently ignored.

A5 returns **zero** rows: `NSF` matches no Negotiation, which is why
Negotiation is absent from gate 9's facets. Its content check is
therefore vacuous and the row is labelled as such rather than counted as
positive evidence.

### The union has grown 66% since the last analysis

The 2026-08-12 investigation worked with ~296,413 unioned rows
(267,386 Award + 17,739 Proposal + 10,775 Negotiation + 513 Subaward).
It is now **491,685** — Proposal 17,739 → **119,788** and Subaward
513 → **93,736**. Any figure carried over from that document
understates today's cost.

## 2. Global Search (`/api/global-search`) — separate cause

| Row | Query | n | Median | Min | Max | Results |
|---|---|---:|---:|---:|---:|---:|
| B1 | `NSF` | 3 | **20,401** | 14,429 | 21,597 | 83 |
| B2 | non-matching word | 3 | **28,097** | 23,914 | 29,584 | 5 |
| B3 | identifier `212137-00001` | 3 | **4,527** | 3,856 | 9,091 | 1 |
| B4 | `NSF`, `modules=AWARD` | 3 | 11,316 | 3,272 | 11,474 | 25 |
| B5 | `NSF`, `modules=PROPOSAL` | 3 | 365 | 353 | 1,571 | 30 |
| B6 | `NSF`, `modules=NEGOTIATION` | 3 | 718 | 408 | 739 | 3 |
| B7 | `NSF`, `modules=SUBAWARD` | 3 | 1,876 | 1,871 | 1,986 | 25 |

No `failedModules` on any sample.

### The semantic leg: enabled, and NOT the bottleneck

A discriminator was run because an early reading nearly attributed
~11 s to the semantic leg. Three queries that all match (almost)
nothing lexically:

| Row | Query shape | n | Median | RELATED rows |
|---|---|---:|---:|---:|
| D1 | word-shaped (`zzzqqqxyz123`) | 3 | 15,000 | **5** |
| D2 | identifier-shaped (`999999-99999`) | 3 | 3,832 | 0 |
| D3 | digits-only (`99999999`) | 3 | 3,987 | 0 |

Semantic search **is enabled**: `zzzqqqxyz123` returns 5 rows, all
`matchType: "RELATED"` with `matchedField: null` (AWARD ×4,
PROPOSAL ×1) — the semantic signature, read from the response rather
than inferred.

But semantic is **cheap**, and the earlier attribution was wrong.
`semanticEligible` depends only on the feature flag and
`LikelyIdentifierDetector`, **not** on `modules`, so B5
(`modules=PROPOSAL`, a non-identifier query) pays the semantic leg too —
and B5 completed in **353–1,571 ms**. A leg costing 11 s cannot hide
inside a 353 ms response.

So the dominant Global Search cost is the **Award lexical leg**
(11,316 ms median alone; 13,906 ms observed), and the D1-vs-D2/D3
difference is the cost of a *word-shaped* query versus an
*identifier-shaped* one: an identifier can be served by the exact and
`ILIKE` predicates on indexed identifier columns, while a word runs the
anchored-regex branch across the corpus.

This is consistent with `DEF5_GLOBAL_SEARCH_SEPARATE_CAUSE.md`: a
non-matching query costs as much or more because a disjunction cannot
short-circuit when nothing matches.

## 3. Correctness baseline (gates 15–18)

Identity key per row:
`module | documentNumber | businessRecordNumber | versionOrSequence | targetRoute`.
Digests are the first 32 hex characters of SHA-256 over the newline-joined keys.

| Subject | totalElements | Returned | Sorted-key digest | Ordered-key digest |
|---|---:|---:|---|---|
| `query=NSF` page 0, size 100 | 3,382 | 100 | `8b21c2799ca969abdaf166b6e3bfefa0` | `9700e4c4924a4974187084b4beeac754` |
| `query=NSF&module=SUBAWARD`, **all 287 rows** | 287 | 287 | `df2c20ac5a4851f8d7e0f2a5f7b1d2ce` | — |
| unfiltered page 0, size 100 | 491,685 | 100 | `524857d9be9f0643224b0c85769371dd` | `cdedee3c27032483665eb5da1343bcac` |
| `personName=SMITH` page 0, size 100 | 1,040 | 100 | `85bd5c31faf4235be1b062883ef57bfe` | `0782bd9224c20742ea42f84b40bfaa60` |

Module facets, to be compared unchanged:

- `query=NSF` → AWARD 1,734 · PROPOSAL 1,361 · SUBAWARD 287
- unfiltered → AWARD 267,386 · NEGOTIATION 10,775 · PROPOSAL 119,788 · SUBAWARD 93,736
- `personName=SMITH` → AWARD 615 · PROPOSAL 425

### The sort-tie-breaker concern does not apply to these gates

`DEF5_FIX_AND_ACCEPTANCE.md` §6.2 flagged that the fix adds
`source_record_id` as a final sort tie-breaker, so a page boundary could
legitimately differ where rows share `(module, document_number)`.

**Measured: zero duplicate `(module, documentNumber)` pairs in every one
of the four captured result sets**, and zero duplicate rows across the
full 287-row Subaward multiset. So for these gates the ordered digest is
a valid comparison too, not only the sorted one. The caveat remains
correct in general; it simply is not triggered here.

The Subaward row is a **true multiset** check: all 287 matches were
enumerated across pages, not sampled.

## 4. Run-to-run variance is large, and it changes the protocol

This host is noisy. Same query, same session, minutes apart:

| Observation | Values |
|---|---|
| Gate 9, within one 5-sample series | 36,086 → 45,362 ms (**26% spread**) |
| Gate 9 median, this session vs earlier | 38,014 vs 43,406 ms |
| Gate 10, earlier single sample vs this median | 19,763 vs 12,860 ms |
| Non-matching Global Search, B2 vs D1 (identical query) | 28,097 vs 15,000 ms |
| B4 Award leg samples | 3,272 / 11,316 / 11,474 ms |

0.5 vCPU on a shared host. **Consequence: a before/after comparison
against a baseline captured an hour earlier is not reliable.** The
after-measurement must be taken in the **same session, back to back**
with a re-measured before, or the comparison can be swamped by noise.

## 5. Threshold revisions this baseline forces

| Gate | Original threshold | Problem | Revised |
|---|---|---|---|
| 14 | median ≤ 100 ms | baseline median is **99 ms**, max 387 ms — the threshold is inside the noise | median **≤ 500 ms** |
| 13 | within +10% of its before-value | baseline median 35,499 but max 42,065 — ±10% is narrower than observed spread | median **≤ +25%**, and re-measured in the same session |
| 10 | ≤ 9,000 ms | was set against 19,763 ms; the real baseline is **12,860 ms**, so this is a 30% ask, not 55% | keep **≤ 9,000 ms**, noted as measured against 12,860 |
| 9 | ≤ 15,000 ms | baseline 38,014 ms | keep **≤ 15,000 ms** |
| 11 | ≤ 2,200 ms | baseline 1,587 ms | keep |
| 12 | ≤ 8,000 ms | baseline 6,405 ms | keep |
