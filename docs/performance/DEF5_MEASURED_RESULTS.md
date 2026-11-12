# DEF-5 — measured results (authenticated DEV, revision 93)

Every timing below was recorded only for an **HTTP 200 whose body also
passed a content check**. Responses that did not validate are listed
separately as discarded, never as timings. No 401 is recorded as a
measurement anywhere in this document.

Endpoint: `GET /api/v1/documents` on
`https://api-dev.app-nprd.aws-cloud.bu.edu` (the **absolute API
origin** — see "Method correction" below).

## Documents search — verified timings

| Row | Request | Repeats | Median | Min | Max | Content check |
|---|---|---:|---:|---:|---:|---|
| Documents, text query, all modules | `query=NSF` | 5 | **43,406** | 40,326 | 45,757 | 200, paged body |
| Documents, text query, AWARD only | `query=NSF&module=AWARD` | 1 | **19,763** | 19,763 | 19,763 | 200; 25/25 rows `module=AWARD`; every row contains NSF in documentNumber, title or businessRecordNumber; `totalElements`=1,734; facets `[{AWARD,1734}]` |
| Documents, text query, PROPOSAL only | `query=NSF&module=PROPOSAL` | 1 | **1,801** | 1,801 | 1,801 | 200, paged body |
| Documents, no text query (default page) | `size=25` | 1 | **~6,700** | — | — | 200, paged body |
| Documents, no text query, `size=1` | `size=1` | 1 | **10,522** | — | — | 200; `{results, moduleFacets}` |
| Documents, module filter only | `module=PROPOSAL` | 1 | **462** | — | — | 200, paged body |
| Payload-size control | `size=1` vs `size=25`, `query=NSF` | 1 each | 40,909 / 41,629 | — | — | 200, paged body |
| Baseline (unrelated endpoint) | award detail | 1 | **32** | — | — | 200 |

Sample counts are stated as recorded. Rows with a single sample are
labelled as such and are **not** presented as medians of a series.

## Global Search — verified timings (earlier authenticated run, revision 93)

| Leg | Median (ms) |
|---|---:|
| Global Search, `NSF` | 17,312 |
| Global Search, non-matching query | 18,110 |
| Global Search, identifier query | 10,571 |
| …Award leg | 11,776 |
| …Proposal leg | 1,042 |
| …Negotiation leg | 722 |
| …Subaward leg | 2,533 |

## Discarded, not recorded as timings

| Attempt | Why discarded |
|---|---|
| 5 × `/api/v1/documents` on the **UI** origin (`nonprod.kuali-archive.bu.edu`), 11–203 ms | HTTP 200 but `content-type: text/html` — the SPA's `index.html` via the static fallback, not the API. Content check failed; all five rejected. |

### Method correction

A relative `/api/...` fetch issued from the UI origin returns the
single-page app's `index.html` with **HTTP 200**. A status-code check
alone would have recorded five "11–203 ms" Documents timings that were
never Documents responses at all. Two consequences, both now closed:

1. Measurements must use the absolute API origin. The committed harness
   (`scripts/performance/def5-blackbox.mjs`) already does this.
2. A liveness probe that only asserts `status === 200` on a relative
   path proves nothing about the session. The probe must assert the
   response body shape too.

## What the numbers establish

- The cost is **not** cold start: five consecutive warm repeats all
  exceeded 40 s.
- The cost is **not** payload size: `size=1` (40,909 ms) ≈ `size=25`
  (41,629 ms).
- The cost is **not** general application overhead: an unrelated
  authenticated endpoint answered in 32 ms.
- The cost scales with **which modules are in the union**, not with
  rows returned: AWARD-only 19,763 ms vs PROPOSAL-only 1,801 ms.
- All-modules (43,406 ms) is **substantially more than the sum of the
  individual module timings** (≈19.8 s + 1.8 s + Negotiation +
  Subaward). The cross-module union and its sort therefore carry their
  own large cost and are not merely the Award branch repeated.
