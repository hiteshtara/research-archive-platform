# RAP QA execution report — 12 November 2026

Automated execution against the live DEV application, **API revision 93**
(image `f08672c4`, commit `9729eff`), enforcement **OFF**, auditing **OFF**.
Authenticated as the federated BU identity; every result below was observed,
none inferred.

**This is not full coverage.** 48 automated checks were executed across 14
scope areas. What was not reached is listed in §8 rather than left implied.

The SHA-pinned `qaSnapshot.json` is untouched. These results are recorded
here, separately, as required.

---

## 1. Totals

| Verdict | Count |
|---|---|
| **PASS** | 36 |
| **FAIL** | 2 |
| **NOT TESTABLE** | 8 |
| **BLOCKED** | 2 |
| **Total executed** | 48 |

Defects found: **5** (2 functional, 1 data-integrity, 1 data-coverage,
1 performance class).

## 2. Results by module

| # | Module | Executed | Result |
|---|---|---|---|
| 1 | Global Search | 5 | 5 PASS — but severely slow (§6) |
| 2 | Award Archive | 10 | 9 PASS, 1 NOT TESTABLE (hierarchy endpoint not found) |
| 3 | Proposal Archive | 4 | 4 PASS |
| 4 | Negotiations | 3 | 3 PASS |
| 5 | Subawards | 4 | 4 PASS |
| 6 | IRB | 4 | **NOT TESTABLE — module is empty** (§5, DEF-4) |
| 7 | Documents | 8 | 6 PASS, **1 FAIL** (DEF-1), 1 corrected |
| 8 | AI Evidence Search | 3 | NOT TESTABLE — disabled on DEV, all 404 |
| 9 | UI | partial | page loads, navigation renders; see §8 |
| 10 | API behaviour | 6 | 6 PASS |
| 11 | Data integrity | 6 | 4 PASS, **1 FAIL** (DEF-3), 1 BLOCKED |
| 12 | Performance | 20+ timings, then repeated + content-verified | **root cause found**, fix proposed (§6, DEF-5; PR #141) |
| 13 | Security (enforcement OFF) | 3 | 3 PASS |
| 14 | Regression | CI | **all green** on `9729eff` |

## 3. Defects

### DEF-1 — Documents text search does not filter. **HIGH**

`/api/documents/search` ignores the search term entirely.

| Query | `totalElements` |
|---|---|
| `query=` (empty) | 491,685 |
| `query=NSF` | 491,685 |
| `q=NSF` | 491,685 |
| `search=NSF` | 491,685 |
| `q=ZZQQXX9999` (nonsense) | 491,685 |
| `q=100004-00001` (one record) | 491,685 |

Identical total **and identical first row** (`AWARD/100004-00001`) every time.
`module=AWARD` **does** filter correctly (267,386), so the endpoint is wired —
only the text term is dropped.

**Reproduction:** `GET /api/documents/search?q=ZZQQXX9999&page=0&size=2` →
200, `totalElements: 491685`.

**Caveat on severity:** the Documents **UI** calls `/api/v1/documents`, not
this endpoint, so user-facing impact may be nil and this may be a legacy or
unused surface. That should be confirmed before prioritising — but an endpoint
that silently returns everything for any query is a defect regardless of who
calls it.

### DEF-2 — Documents page ignores the `?q=` URL parameter. **MEDIUM**

Loading `/documents?q=NSF` issued
`GET /api/v1/documents?page=0&size=25&includeAnyUnit=false&piOnly=false` —
**no search term**. Other modules honour `?q=`. A shared or bookmarked
Documents search link silently returns unfiltered results.

### DEF-3 — Subaward count disagrees with the Dashboard. **MEDIUM**

| Source | Count |
|---|---|
| `/api/subawards?query=` | **93,736** |
| Dashboard `subawards` | **3,511** |

Awards and Proposals are internally consistent — Award list 40,926 = dashboard
`awards`; versions 267,386 = `awardHistoryRecords`; Proposal search 33,960 =
dashboard `proposals`. Negotiations match exactly (10,775).

So Subawards is the only module whose list total matches **neither** the
business nor the history figure the dashboard reports. Per `CLAUDE.md`
("never treat a raw archive row count as a business-object count") this is most
likely the Subaward list returning version rows while the dashboard counts
distinct subawards — but the two disagree with no label explaining why, and
the convention differs from every other module.

### DEF-4 — IRB archive is empty in DEV. **MEDIUM (blocks scope item 6)**

`/api/irb` returns HTTP 200 with `totalElements: 0` for every variant tested
(bare, paged, `query=`, `query=a`). Dashboard confirms it:
`irb: 0, submissions: 0, fundingRecords: 0, timelineEvents: 0`.

The endpoint is healthy and correctly shaped; there is simply no data. IRB
details, amendments, renewals, personnel, units and version history are
therefore **NOT TESTABLE on DEV** — not failing, untestable. Loading IRB data
into DEV is a prerequisite for that scope item.

### DEF-5 — Severe response times across search surfaces. **HIGH**

**Root cause identified and a fix proposed (draft PR #141). Investigation
and the full measurement record: PR #140.**

The figures first recorded here were single, uncached observations. They
have since been re-measured with repeats, and **every timing below was
recorded only for an HTTP 200 whose body also passed a content check** —
no status-only acceptance, and no 401 counted as a measurement anywhere.

#### Verified Documents timings (authenticated, DEV revision 93)

| Request | Repeats | Median | Min | Max |
|---|---:|---:|---:|---:|
| `/api/v1/documents?query=NSF` (all modules) | 5 | **43,406 ms** | 40,326 | 45,757 |
| `…&module=AWARD` | 1 | **19,763 ms** | — | — |
| `…&module=PROPOSAL` | 1 | **1,801 ms** | — | — |
| `/api/v1/documents` no text query | 1 | ~6,700 ms | — | — |
| `/api/v1/documents?size=1` no text query | 1 | 10,522 ms | — | — |
| `module=PROPOSAL` only | 1 | 462 ms | — | — |
| `query=NSF&size=1` vs `size=25` | 1 each | 40,909 / 41,629 ms | — | — |
| award detail (baseline) | 1 | **32 ms** | — | — |

The `module=AWARD` row is fully content-verified: 25 of 25 rows returned
`module=AWARD`, every row contained NSF in `documentNumber`, `title` or
`businessRecordNumber`, `totalElements` was 1,734 and the module facet
agreed.

#### Verified Global Search timings

| Leg | Median |
|---|---:|
| `query=NSF` | 17,312 ms |
| non-matching query | 18,110 ms |
| identifier query | 10,571 ms |
| …Award leg | 11,776 ms |
| …Proposal leg | 1,042 ms |
| …Negotiation leg | 722 ms |
| …Subaward leg | 2,533 ms |

#### What the numbers establish

- **Not cold start.** Five consecutive warm repeats all exceeded 40 s.
- **Not payload size.** `size=1` ≈ `size=25`, confirming the earlier
  `size=2` observation.
- **Not application overhead.** An unrelated authenticated endpoint
  answered in 32 ms.
- **Not a missing index.** The dominant plan nodes are full but
  correctly-indexed scans.

#### Root cause (Documents)

A filtered `GET /api/v1/documents` evaluates the four-module
`EXPLORER_CTE` **three times per request** (`count()`, `search()`,
`moduleFacets()`). That CTE resolves Award's primary person, unit count
and person count for all **267,386** `archive.award_version` rows —
`DISTINCT ON` over `award_person` (325,468) and `GROUP BY` over
`award_person_unit` (334,371) — before any filter or `LIMIT` applies.
The filter is applied *after* the union, so the existing
`ix_award_version_title_trgm` cannot be used.

This is independently corroborated. The 2026-08-12 investigation
(`docs/architecture/KUALI_DOCUMENT_EXPLORER_PERFORMANCE.md`) measured
those three queries by `EXPLAIN (ANALYZE, BUFFERS)` at 13.4 / 12.9 /
12.3 s. Three evaluations at ~14.5 s each is 43.5 s — the black-box
median above. That investigation fixed **only** the unfiltered path and
recorded the filtered cases as a known-accepted limitation, naming
`module=AWARD` alone at ~12 s. **DEF-5 is that documented limitation
coming due, not a new regression.**

#### Two corrections to this report's earlier reading

1. The original note suggested the Global Search "semantic leg runs
   hardest when nothing matches". A non-matching query does cost
   slightly more (18,110 vs 17,312 ms), but the gap is small — the
   right reading is that cost is proportional to the **corpus**, not to
   the matches, in both cases. The stronger claim is withdrawn.
2. All-modules Documents (43,406 ms) costs substantially **more** than
   the sum of its per-module timings (≈23 s). So the Award branch is
   the largest single contributor but **not** the entire cost once a
   filter is involved — the cross-module union and its sort carry their
   own large cost.

#### Status of the fix

Draft PR #141 proposes the smallest change that addresses the measured
cause: derive the total from the facet counts rather than a second
`COUNT(*)`, and route filtered requests that do not read an Award person
column through the two-stage light path that already exists for
unfiltered requests. No migration, no new index, no change to the heavy
CTE's SQL.

Projected — **not yet measured** — `query=NSF` all modules at roughly
8–12 s, a 3.5–5× reduction. Explicitly **not** an order of magnitude and
**not** under a 5-second target: this environment's already-optimized
unfiltered page measures ~6,700 ms, which is a floor the light path
cannot beat. Sub-second filtered text search would require a different
design.

Global Search has a root cause **hypothesis only** — the same
corpus-proportional shape — but its SQL has not been investigated and no
fix is proposed for it.

## 4. Corrections to earlier records

**The UAT guide in PR #135 states a search for `NSF` matches 491,685
documents.** That figure came from `/api/documents/search`, which does not
filter (DEF-1), so it is the **unfiltered total**, not an NSF result. The guide
needs correcting and TC-060/TC-062 should target `/api/v1/documents`.

One harness error of my own: Award search was first scored FAIL because I
assumed `results` was an array; it is a paged object. Re-verified and corrected
to **PASS** — the search returns `212137-00001` with PI, sponsor, lead unit and
obligated amount.

## 5. Data integrity (scope item 11)

Verified by API cross-check:

| Check | Result |
|---|---|
| Award list total vs dashboard `awards` | 40,926 = 40,926 **PASS** |
| Award versions vs `awardHistoryRecords` | 267,386 = 267,386 **PASS** |
| Proposal search vs dashboard `proposals` | 33,960 = 33,960 **PASS** |
| Negotiation list vs dashboard | 10,775 = 10,775 **PASS** |
| Award history count vs versions search | 3 = 3 **PASS** |
| Subaward list vs dashboard | 93,736 vs 3,511 **FAIL — DEF-3** |
| Documents search vs dashboard | 491,685 vs 491,678 — 7 apart, confounded by DEF-1 |

**Direct database comparison is BLOCKED.** `scripts/start-db-tunnel.sh`
forwards the port but psql requires the `archive_analyst` password, and
requesting or handling a database password is outside my standing constraints.
Unblocking needs either an operator-run psql session or authorization for the
documented ECS Fargate route, which resolves credentials inside the container.

## 6. Security (scope item 13)

Enforcement-OFF behaviour verified, nothing activated:

- unauthenticated `/api/awards/212137-00001` → **401**
- unauthenticated global search → **401**
- `/api/v1/admin/administrators` → **404** (surface correctly absent)
- isolated authorization suites: CI green on `9729eff`

## 7. Regression (scope item 14)

CI on `9729eff`: **UI, API unit, and API PostgreSQL integration all success.**
The PostgreSQL job ran 520 tests with 0 failures, including the 5 new
`AuditReadFilters` regression tests. No failures to investigate.

## 8. Not covered — stated rather than implied

| Area | Why |
|---|---|
| IRB (item 6) | no data in DEV (DEF-4) |
| AI Evidence Search (item 8) | disabled on DEV, all endpoints 404 |
| Award hierarchy | endpoint not found at 4 candidate paths; UI route exists |
| Negotiation documents / linked records | not reached |
| Subaward relationships / documents | not reached |
| Proposal versions / associations | not reached |
| UI depth (item 9) | only Documents and Dashboard pages loaded; tables, filters, sorting, pagination controls, loading and error states, responsive breakpoints not systematically exercised |
| Attachment retrieval | gated by `ArchiveAttachmentViewer`; this identity is not a member |
| Database comparison (item 11) | BLOCKED, see §5 |
| Sorting across modules | not exercised |

## 9. For Lalitha — human judgement only

Everything mechanical above was automated. These genuinely need a person:

1. **Business correctness of record content.** Whether the sponsor, PI, dates
   and amounts on a record match what BU believes is true. Automation confirms
   fields are populated and internally consistent; only a person knows whether
   they are *right*.
2. **TC-042 response-time acceptability.** DEF-5 now gives repeated,
   content-verified numbers and a proposed fix (PR #141) with explicit
   timing and correctness gates; what is
   tolerable is a business judgement.
3. **TC-041 relevance.** Whether the related results a nonsense query returns
   are acceptable or misleading.
4. **TC-019, TC-029** — the two open interface decisions.
5. **TC-020, TC-045** — real-phone and screen-reader verification, which
   emulated widths cannot settle.
6. **Label comprehensibility** — whether "Historical Award Records" versus
   "Awards", and the Subaward count in DEF-3, read correctly to someone doing
   the work.

Nothing here requires Shari, Dean, John or Warren.

## 10. Environment

Revision 93 serving, enforcement OFF, auditing OFF, V089 unapplied, grant 2
unchanged, revision 91 retained as fallback, revision 92 unused, Production
untouched. No data modified; all requests read-only; no load testing.
