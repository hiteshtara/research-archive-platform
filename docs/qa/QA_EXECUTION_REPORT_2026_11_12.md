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
| 12 | Performance | 20+ timings | **systemic finding** (§6, DEF-5) |
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

Measured, single observations, uncached:

| Endpoint | Time |
|---|---|
| `/api/v1/documents?query=NSF` | **42,505 ms** |
| `/api/v1/documents?module=AWARD` | 18,057 ms |
| `/api/global-search?query=zzzqqqxyz123` | **20,780 ms** |
| `/api/v1/documents` (size=2) | 11,588 ms |
| `/api/global-search?query=NSF` | 8,088 ms |
| `/api/v1/documents?q=ZZQQXX9999` | 8,817 ms |
| `/api/v1/documents` (size=2, repeat) | 6,386 ms |
| `/api/global-search?query=212137-00001` | 5,003 ms |
| `/api/dashboard` | 3,083 ms |
| `/api/subawards?query=9500310349` | 3,892 ms |

For contrast, record reads are fast: award detail 42 ms, negotiation detail
103 ms, award people 78 ms.

**This materially exceeds the figure behind open decision TC-042**, which
recorded a Global Search median of about 11.5 s. A `size=2` Documents request
taking 11.6 s indicates the cost is in the query, not the payload. Two
observations stand out as worth investigating first: the **42.5 s** Documents
query, and a **nonsense** Global Search costing 20.8 s — more than a matching
one, which suggests the semantic leg runs hardest when nothing matches.

Single measurements on a shared DEV host; they indicate magnitude, not a
benchmark.

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
2. **TC-042 response-time acceptability.** DEF-5 gives the numbers; what is
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
