# Award RAG Demo Dataset

Read-only demo-readiness data audit. Every number in this document was
queried directly against the **dev** Postgres database (`archive` schema)
via a one-off, read-only ECS task, on the date and at the query timestamp
recorded below. Nothing in this document was invented, estimated, or
reused from a prior session's stale numbers.

- **Environment**: `research-archive-platform-dev` (RDS Postgres reached via
  the `research-archive-platform-dev-loader` ECS task, standard read-only
  investigation pattern used throughout this project)
- **Query date**: 2026-08-11
- **Query timestamp** (`SELECT now()`, server clock): `2026-08-11 14:02:08.757946+00:00`
- **Repository HEAD at query time**: `e76e95f08a0e23b5fde5471e5bc33481af7d3385`
- **Schema migrations applied**: 72 of 72, through `V072 fix award attachment
  file data id type` — confirmed via `public.schema_migration`

All counts below are **logical/business-grain unless explicitly labeled
historical or physical-file grain** — see the note under Demo Award 1,
which is the one case in this dataset where the distinction is load-bearing
(per this repo's own documented grain-preservation rule).

---

## 1. Full-dataset coverage (proves this is a full archive, not a 4-Award prototype)

| Metric | Count | Source table | Grain |
|---|---:|---|---|
| Award version rows | 49,827 | `archive.award_version` | Historical (every archived version row) |
| Distinct Award families (business objects) | 8,773 | `archive.award_version` (`COUNT(DISTINCT award_number)`) | Business |
| Award-person rows | 57,023 | `archive.award_person` | Historical |
| Amount records | 198,937 | `archive.award_amount_info` | Historical |
| Sponsor term rows | 610,476 | `archive.award_sponsor_term` | Historical |
| Reporting term rows | 106,179 | `archive.award_report_term` | Historical |
| Comment rows (total) | 199,129 | `archive.award_comment` | Historical |
| Comment rows (non-blank text) | 94,834 | `archive.award_comment` (`comments IS NOT NULL AND TRIM(comments) <> ''`) | Historical |
| Award–Funding-Proposal link rows (total) | 372 | `archive.award_funding_proposal` | Historical |
| ...of which resolve to a real `proposal_version` row | 60 | `award_funding_proposal` ✖ `proposal_version` join | Business (usable links only — see §5 caveat) |
| Negotiation rows (total, all types) | 10,775 | `archive.negotiation` | Historical |
| ...of which are Award-linked (`association_type_code = 'AWD'`) | 2,223 | `archive.negotiation` | Business |
| Subaward-funding link rows | 35 | `archive.subaward_funding` | Historical |
| Subaward rows | 513 | `archive.subaward` | Historical |
| Award attachment metadata rows (historical references) | 720,428 | `archive.award_attachment` | Historical — many rows can point at the same physical file |
| Distinct physical attachment files (deduplicated) | 37,777 | `archive.attachment_object` | Physical file |
| ...of which are uploaded to S3 and downloadable today | 37,777 | `archive.attachment_object` (`upload_status = 'UPLOADED'`) | Physical file |
| Existing full-dataset `AWARD_SUMMARY` embeddings | 8,597 | `archive.search_embedding` (`module='AWARD', document_type='AWARD_SUMMARY'`) | Business |
| Existing `PROPOSAL_SUMMARY` embeddings | 5,159 | `archive.search_embedding` | Business |
| Existing `NEGOTIATION_SUMMARY` embeddings | 10,775 | `archive.search_embedding` | Business |
| Existing `SUBAWARD_SUMMARY` embeddings | 27 | `archive.search_embedding` | Business |
| Evidence-level embeddings (`AWARD_VERSION`/`AWARD_PERSON`/etc.) | **0** | `archive.search_embedding` | n/a — `build_evidence_embedding.py` has never been run against real data |

**All attachment metadata rows currently in the database are already marked
`UPLOADED`** — there is no `PENDING`/`FAILED` backlog at this snapshot; the
archive-wide EXTERNAL-blob fix (commit `5be6a6b`) and its backfill are fully
reflected in this data.

---

## 2. Fixture-selection SQL (exact, as run)

### 2.1 Demo Award 1 verification (CARB-X)

```sql
SELECT award_number, sequence_number, award_id, title, is_primary_current
FROM archive.award_version
WHERE award_number IN ('204713-00001', '204713-00133')
ORDER BY award_number;

-- per-award_number breakdown actually used in the matrix below
SELECT
  (SELECT COUNT(*) FROM archive.award_version WHERE award_number = :n) AS versions,
  (SELECT COUNT(*) FROM archive.award_person WHERE award_number = :n) AS people,
  (SELECT COUNT(*) FROM archive.award_amount_info WHERE award_number = :n) AS amounts,
  (SELECT COUNT(*) FROM archive.award_sponsor_term WHERE award_number = :n)
    + (SELECT COUNT(*) FROM archive.award_report_term WHERE award_number = :n) AS terms,
  (SELECT COUNT(*) FROM archive.award_comment WHERE award_number = :n
     AND comments IS NOT NULL AND TRIM(comments) <> '') AS comments,
  (SELECT COUNT(*) FROM archive.award_attachment WHERE award_number = :n) AS attachments,
  (SELECT COUNT(*) FROM archive.award_attachment aa
     JOIN archive.attachment_object ao ON ao.file_id = aa.file_id
     WHERE aa.award_number = :n AND ao.upload_status = 'UPLOADED') AS uploaded_rows;
-- run once with :n = '204713-00133', once with :n = '204713-00001'

-- unique physical file count backing 204713-00001's attachments
SELECT COUNT(DISTINCT ao.file_id) AS unique_files,
       SUM(CASE WHEN ao.upload_status = 'UPLOADED' THEN 1 ELSE 0 END) AS unique_uploaded,
       SUM(ao.file_size_bytes) AS total_bytes
FROM (SELECT DISTINCT file_id FROM archive.award_attachment WHERE award_number = '204713-00001') f
JOIN archive.attachment_object ao ON ao.file_id = f.file_id;

-- related proposal
SELECT afp.award_funding_proposal_id, av.award_number, pv.proposal_number, pv.title, afp.active_flag
FROM archive.award_funding_proposal afp
JOIN archive.award_version av ON av.award_id = afp.award_id
JOIN archive.proposal_version pv ON pv.proposal_id = afp.proposal_id
WHERE av.award_number = '204713-00001';
```

### 2.2 Demo Award 2 verification (104949-00002)

```sql
SELECT negotiation_id, document_number, negotiation_agreement_type_description,
       negotiation_status_description, associated_document_id
FROM archive.negotiation WHERE negotiation_id = 11241;

SELECT sf.subaward_funding_source_id, sf.award_number, s.subaward_code, s.status_description, s.document_number
FROM archive.subaward_funding sf JOIN archive.subaward s ON s.subaward_id = sf.subaward_id
WHERE sf.subaward_funding_source_id = 11185;
```

### 2.3 Scoring query for Demo Awards 3 & 4

Read-only, family-level aggregation excluding the already-selected `204713-%`
and `104949-00002` families, scoring versions + people + amounts + terms +
comments + 3×(proposals + negotiations + subawards) + 5×(uploaded
attachments), filtered to families with ≥2 versions, ≥1 uploaded
attachment, ≥1 person, ≥1 amount, ≥1 term, ≥1 comment, ≥1
related record (proposal/negotiation/subaward), and a non-blank current
title:

```sql
WITH base AS (
    SELECT award_number FROM archive.award_version
    WHERE award_number NOT LIKE '204713-%' AND award_number <> '104949-00002'
    GROUP BY award_number
),
versions AS (
    SELECT award_number, COUNT(*) AS version_count,
           MAX(title) FILTER (WHERE is_primary_current) AS current_title
    FROM archive.award_version GROUP BY award_number
),
-- people / amounts / terms / comments / proposals / negotiations / subawards /
-- attach_meta / attach_uploaded: one COUNT(*)-per-award_number CTE each,
-- joined the same way (see etl/ scratch script used for this run;
-- omitted here for brevity, logic is a straightforward LEFT JOIN aggregate)
SELECT base.award_number, v.version_count, v.current_title, ...
       (COALESCE(v.version_count,0) + COALESCE(p.person_count,0) + COALESCE(am.amount_count,0)
        + COALESCE(t.term_count,0) + COALESCE(c.comment_count,0) + COALESCE(pr.proposal_count,0)*3
        + COALESCE(n.negotiation_count,0)*3 + COALESCE(s.subaward_count,0)*3
        + COALESCE(atu.uploaded_count,0)*5) AS score
FROM base LEFT JOIN versions v ON v.award_number = base.award_number ...
WHERE COALESCE(v.version_count,0) >= 2
  AND COALESCE(atu.uploaded_count,0) >= 1
  AND COALESCE(p.person_count,0) >= 1
  AND COALESCE(am.amount_count,0) >= 1
  AND COALESCE(t.term_count,0) >= 1
  AND COALESCE(c.comment_count,0) >= 1
  AND (COALESCE(pr.proposal_count,0) + COALESCE(n.negotiation_count,0) + COALESCE(s.subaward_count,0)) >= 1
  AND v.current_title IS NOT NULL AND TRIM(v.current_title) <> ''
ORDER BY score DESC, base.award_number LIMIT 15;
```

Top results (real, unedited):

| Award | Versions | People | Amounts | Terms | Comments | Proposals | Negotiations | Attachments | Score |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100589-00001 (SLC CENTER: CELEST...) | 60 | 171 | 2542 | 1171 | 210 | 4 | 0 | 1587 | 12,101 |
| **101929-00001 (NSF Engineering Research Center for Smart Lighting)** | 58 | 58 | 349 | 793 | 246 | 2 | 0 | 1243 | 7,725 |
| 200058-00001 (Public Health Evaluation Study) | 48 | 48 | 131 | 1007 | 123 | 2 | 0 | 619 | 4,458 |
| 100916-00001 (UCLA–BU Lung Cancer Biomarker Development Laboratory) | 48 | 48 | 311 | 913 | 131 | 4 | 0 | 423 | 3,578 |
| **103162-00001 (Behavioral Surveillance of Acetaminophen Users and Non-Users)** | 42 | 42 | 208 | 504 | 72 | 0 | 6 | 509 | 3,431 |
| 105587-00001 (Alzheimer's Disease Genetics Consortium) | 45 | 45 | 376 | 767 | 103 | 0 | 1 | 316 | 2,919 |

Full 15-row result is in the query log; only the selected two and their
closest alternates are reproduced here.

---

## 3. Selected demonstration Award families

**The selected demonstration set spans five distinct Award numbers, not
four**: `204713-00133`, `204713-00001`, `104949-00002`, `101929-00001`,
and `103162-00001`. This is because "Demo Award 1" (CARB-X) is really two
separate Award business objects used for different purposes — see the
grain caveat immediately below — not one Award shown twice. Every other
"Demo Award" (2, 3, 4) is exactly one Award number.

### Demo Award 1 — CARB-X (two related Award objects, one program)

**Grain caveat (read this before presenting):** `204713-00133` and
`204713-00001` are **two distinct Award business objects** (two different
`award_number`s), not two versions of one Award — per this repo's own
grain rule, business grain is `COUNT(DISTINCT award_number)`. Both belong to
the same underlying CARB-X program (177 distinct `award_number`s share the
`204713-` prefix, 1,888 version rows total across that whole program). The
demo uses **two specific Awards** within that program:

- **204713-00133** — the primary/current showcase Award (`award_id`
  3187665, `sequence_number` 125, `is_primary_current = TRUE`). Use this one
  for version history, investigators, amounts, terms, comments.
- **204713-00001** — a sibling Award in the same program. Use this one
  **specifically** for the related-proposal and attachments demonstrations
  — `204713-00133` itself has **zero** attachment rows.

| | 204713-00133 | 204713-00001 |
|---|---:|---:|
| Versions (this Award's own sequence) | 125 | 545 |
| People | 125 | 545 |
| Amounts | 259 | 1,369 |
| Terms (sponsor + report) | 2,500 | 10,864 |
| Comments (non-blank) | 125 | 1,091 |
| Attachment metadata rows | 0 | 198,194 |
| Uploaded attachment rows | 0 | 198,194 |
| Unique physical files (deduplicated) | 0 | **836** |
| Total attachment bytes | — | 311,602,862 |
| Related proposal | — | `01128961` “CARB-X” (`award_funding_proposal_id` 1768708, `active_flag='Y'`; a second, inactive link `1768701` also exists on this Award) |

Sample real, demo-safe attachment (204713-00001): `award_attachment_id`
297106, filename `Preaward_Email dtd 7-22-16_Outterson.pdf`, 641,272 bytes,
`upload_status = 'UPLOADED'`. **Known data caveat**: this file's
`content_type` column contains corrupted/garbled text (many escaped
backslashes/quotes) — confirmed directly from the database. Do not read
`content_type` aloud or rely on it looking clean in the live demo; use the
filename instead, and verify how the UI actually renders it before the
meeting (see Readiness Checks).

### Demo Award 2 — Connected records (104949-00002)

| Versions | People | Amounts | Terms | Comments | Proposals | Negotiations | Subawards | Attachments | Uploaded |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | 16 | 78 | 257 | 22 | 0 | 2 | 1 | 32 | 32 |

- Negotiation `11241`: document `1060608`, “Data Use Agreement,” status
  **Fully Executed**.
- A second negotiation also exists on this Award (`negotiation_id` 11471,
  status **Abandoned**, per this session's earlier investigation) — useful
  as a natural contrast if the client asks "what does a negotiation that
  didn't complete look like."
- Subaward funding `11185` → subaward `1008`, status **07. Executed**,
  document `433858`.
- All 32 attachment rows are uploaded and downloadable.

### Demo Award 3 — 101929-00001 (NSF Engineering Research Center for Smart Lighting)

| Versions | People | Amounts | Terms | Comments | Proposals | Negotiations | Subawards | Attachments | Uploaded |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 58 | 58 | 349 | 793 | 246 | 2 | 0 | 0 | 1,243 | 1,243 |

Chosen for: an NSF-sponsored engineering program (sponsor diversity vs.
CARB-X's HHS funding), a clean, immediately understandable title, real
scale (58 versions, 1,243 uploaded attachments), and a working related
proposal (unlike Demo Award 2, which has none).

### Demo Award 4 — 103162-00001 (Behavioral Surveillance of Acetaminophen Users and Non-Users)

| Versions | People | Amounts | Terms | Comments | Proposals | Negotiations | Subawards | Attachments | Uploaded |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 42 | 42 | 208 | 504 | 72 | 0 | 6 | 0 | 509 | 509 |

Chosen for: a different research domain again (pharmacoepidemiology/public
health), a second, independent real example of Award-linked negotiations
(6 of them) to fall back on if Demo Award 2's negotiation tab is unavailable
(see the Deployment Readiness document's blocker), and a clean title.

Neither candidate title, nor any reviewed row in the top 15, contains
personally sensitive or inappropriate content for a client audience.

---

## 4. Required coverage matrix

| Award family | Versions | People | Amounts | Terms | Comments | Proposals | Negotiations | Subawards | Attachments | Uploaded files | Demo purpose |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 204713-00133 (CARB-X, primary) | 125 | 125 | 259 | 2,500 | 125 | — | — | — | 0 | 0 | History, investigators, amounts, terms, comments |
| 204713-00001 (CARB-X, sibling) | 545 | 545 | 1,369 | 10,864 | 1,091 | 2 | — | — | 198,194 (836 unique files) | 198,194 (836 unique files) | Related proposal, attachment inventory & download |
| 104949-00002 | 16 | 16 | 78 | 257 | 22 | 0 | 2 | 1 | 32 | 32 | Related negotiation & subaward, cross-module citations |
| 101929-00001 | 58 | 58 | 349 | 793 | 246 | 2 | 0 | 0 | 1,243 | 1,243 | Scale, sponsor diversity, working proposal link |
| 103162-00001 | 42 | 42 | 208 | 504 | 72 | 0 | 6 | 0 | 509 | 509 | Second research domain, negotiation backup example |

**Evidence-indexing caution**: `204713-00001`'s 10,864 term rows (and
1,369 amount rows) make it unsuitable for a full, unfiltered
`build_evidence_embedding.py` run — do not recommend indexing every
evidence type for this Award. See
`AWARD_RAG_DEPLOYMENT_READINESS.md` §6 for the scoped `--document-types`
recommendation. No indexing has been run as part of this checkpoint.

---

## 5. Known, previously-documented data-completeness facts (not new to this audit)

- Only 60 of 372 `award_funding_proposal` link rows resolve to a real
  `proposal_version` row (no FK enforces this relationship at the database
  level) — confirmed again this session, unchanged from the earlier
  Phase 2 fixture-selection investigation. This is why "Related proposals"
  is demonstrated on 204713-00001 and 101929-00001 specifically, not an
  arbitrary Award.
- `archive.award_attachment` intentionally carries many historical
  reference rows per physical file (the version-relinking fan-out already
  documented in this session's attachment inventory work) — 720,428
  metadata rows vs. 37,777 unique physical files archive-wide. Never
  present the metadata-row count as "number of documents" to the client;
  always pair it with the unique-file count.
