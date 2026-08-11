# Kuali Document Metric Investigation

Investigation-only, per an explicit user request. No source code, migrations,
or data were changed. All queries below are read-only and were run against
the dev Postgres database (account 770203350335, `bu-nprd`) via the existing
`research-archive-platform-dev-loader` ECS task family. Dev counts are a
point-in-time snapshot (2026-08-11) — a future loader run can change them.

## 1. Current zero-count root cause

Traced end-to-end:

- UI: [`ui/src/pages/DashboardPage.tsx`](../../ui/src/pages/DashboardPage.tsx)
  renders `futureModuleCards` from
  [`ui/src/features/dashboard/dashboardPresentation.mjs`](../../ui/src/features/dashboard/dashboardPresentation.mjs),
  which defines the `documents` card as `{ title: "Documents", description:
  "Legacy files and attachments", path: "/documents" }`.
- API client: `getDashboard()` in `ui/src/api/client.ts` calls `GET
  /api/dashboard` and returns it typed as `DashboardSummary` (`ui/src/types/api.ts`),
  which declares `documents: number`.
- Response DTO:
  [`DashboardDto.java`](../../api/src/main/java/edu/bu/archive/adapter/in/web/dto/DashboardDto.java)
  — plain record, `long documents`.
- Controller/repository (no separate service layer — `DashboardController`
  runs the SQL directly via `JdbcClient`):
  [`DashboardController.java`](../../api/src/main/java/edu/bu/archive/adapter/in/web/DashboardController.java),
  line 56: **`0 AS documents`** — a hardcoded SQL literal, not derived from
  any table.

**Root cause: the value is a literal constant in the SQL, not a query
result.** There is no bug to fix in the sense of a wrong join or filter —
the field was never wired to real data in the first place.

Route: `documents` in `ui/src/App.tsx` maps to `<ComingSoonPage />` — the
card does not currently open anything real.

## 2. Document-bearing table inventory

### Core business-record modules

Each of these has `document_number` living directly on the module's own
version/business row (no separate document table) — this is the "real
workflow document identifier following the `KREW_DOC_HDR_T` pattern"
already established and cited in
[`docs/kuali-business-rules/Workflow Documents.md`](../kuali-business-rules/Workflow%20Documents.md).

| Module | Source table | Document-number column | Business identifier | Version behavior | Row count | Distinct documents |
| --- | --- | --- | --- | --- | ---: | ---: |
| AWARD | `archive.award_version` | `workflow_document_number` | `award_number` + `sequence_number` | One row per version; each version has its own document number | 49,827 | 49,827 |
| PROPOSAL | `archive.proposal_version` | `document_number` | `proposal_number` + `version_number` | One row per version; each version has its own document number | 17,739 | 17,739 |
| NEGOTIATION | `archive.negotiation` | `document_number` | `negotiation_id` | Not versioned — one row per negotiation | 10,775 | 10,775 |
| SUBAWARD | `archive.subaward` | `document_number` | `subaward_code` + `sequence_number` | One row per version; each version has its own document number | 513 | 513 |
| IRB | `archive.irb_protocol_version` | `document_number` | `protocol_number` + `sequence_number` | One row per version | **0** | **0** |

"Institutional Proposal" is not a separate module in this schema —
`archive.proposal_version` already is the Institutional Proposal record
(confirmed by `V058`'s own migration comment, which cites
`docs/kuali-business-rules/InstitutionalProposal.md`).

**IRB currently has zero rows in every IRB table in this dev database**
(`irb_protocol`, `irb_protocol_version`, `irb_submission`,
`irb_funding_source`, `irb_timeline_event` all return `COUNT(*) = 0`). The
schema and column model are valid and ready (`irb_protocol_version.document_number`
is the correct future join point), but IRB contributes 0 to today's dev
count purely because no IRB data is currently loaded in this environment —
not because the model is wrong.

`archive.protocol_version` (from `V034`, the newer "Protocol Archive"
rebuild) also has 0 rows and, per `docs/DECISIONS.md`, is not wired into any
API controller or UI route — it is out of scope; legacy IRB
(`irb_protocol_version`) is the live human-subjects domain.

### Transactional / financial document tables (nested under Award)

These are real, separately KEW-routed workflow documents, but they are
**child financial artifacts of an Award**, not top-level business records
comparable to Award/Proposal/Negotiation/Subaward/IRB:

| Table | Document-number column(s) | Row count | Distinct document numbers | Notes |
| --- | --- | ---: | ---: | --- |
| `archive.award_budget` | `document_number` | 13,203 | 13,203 | 100% unique in this table |
| `archive.time_and_money_document` | `document_number` (table's own PRIMARY KEY) | 14,615 | 14,615 | Structurally unique — it's the PK |
| `archive.pending_transaction` | `document_number` | 13,395 | 9,983 | **Not 1:1** — one T&M document can have multiple pending-transaction rows (source/destination pairs) |
| `archive.award_transmission` | `document_number` | 20,366 | 17,312 | Not 1:1 |
| `archive.award_transmission_child` | `parent_document_number` / `child_document_number` | 16,822 | 10,382 (parent) | Not 1:1; these are the real SAP transmission tables (`V052`'s filename says "sap_transmission_history" but the actual table names, verified via `grep`, are `award_transmission`/`award_transmission_child` — no "sap" substring) |

### No archived workflow/document-header registry table exists

Searched migrations, ETL, and docs for any archived `KREW_DOC_HDR_T`-style
table (`grep -rli "krew|doc_hdr|document_header"`). The only hits are
**references to it as the Oracle-side foreign-key target** that
`workflow_document_number`/`document_number` point into — the header table
itself is never extracted into Postgres. There is no single authoritative
document registry in this archive; every module owns its own
`document_number` column independently.

## 3. Document identity

- **Within each core module**: 100% populated (0 NULL, 0 blank, 0
  placeholder-like values matching `^(0+|N/A|NA|NONE|null)$`), and 100%
  distinct (0 document-number values shared by more than one row in the
  same table) — verified by full-table queries, not a sample.
- **Across modules**: 0 collisions. The union of all 4 populated core
  modules' document numbers has exactly 78,854 distinct values whether
  counted globally (`document_number` alone) or compound
  (`module, document_number`) — i.e., `document_number` is *empirically*
  globally unique across Award/Proposal/Negotiation/Subaward today.
  **Recommendation stands anyway**: use the compound
  `module + document_number` key, per your instruction — it's free, and
  today's empirical uniqueness is not a schema guarantee for future loads.
- **One business record can have multiple document numbers** — proven
  directly, not inferred: Award 204713-00001's related proposal 01128961
  has **two** distinct document numbers across its own versions
  (`430102` at `version_number=3`, `451704` at `version_number=4`). A
  document number identifies one *version* of a business record, not the
  business record itself.
- **Transactional tables are not 1:1** — `pending_transaction` (13,395 rows
  / 9,983 distinct) and `award_transmission`/`award_transmission_child`
  show the same document number reused across multiple transaction rows.
  This is expected for financial transaction documents and is a different
  identity shape than the core modules'.

## 4. Workflow registry vs. module union

**Option A (authoritative workflow registry): not available.** No such
table is archived in this schema (see §2). This option cannot be built
without a new Oracle extraction of `KREW_DOC_HDR_T`/`KREW_DOC_TYP_T` — out
of scope here and not requested.

**Option B (module union): the only viable design today.**

| | Module union |
| --- | --- |
| Coverage | 4 of 5 requested core modules populated today (Award, Proposal, Negotiation, Subaward); IRB schema-ready but 0 rows |
| Missing modules | None structurally missing — IRB is empty for data-loading reasons, not a schema gap |
| Duplicate risk | None observed (0 cross-module collisions, 0 in-table duplicates) for core modules |
| Historical-version behavior | Correctly preserved — each version's own document number stays distinct, matching this repo's own grain rules (never collapse historical rows) |
| Routing capability | 3 of 4 populated modules route with the document's own row data alone (Award, Negotiation, Subaward); Proposal needs one extra lookup (see §6) |
| Query performance | Each source table already has an index on its document-number column (`ix_negotiation_document_number`, `ix_subaward_document_number`, `idx_proposal_version_document_number`, `ix_award_version_workflow_document_number`); a `UNION ALL` across 4-5 indexed, moderately-sized tables (max ~50K rows) is cheap |
| Maintenance cost | Low — each module's ETL loader already populates its own `document_number` column; no new pipeline needed |
| Title/type/status/date richness | Uneven: Award/Proposal/Negotiation/Subaward rows carry title, status, and dates directly; the transactional tables (Budget/T&M/SAP) carry status but not a comparable "title" |

**Recommendation: Option B**, scoped to the 5 core business-record modules
only (Award, Proposal, Negotiation, Subaward, IRB) — not the transactional
Budget/Time-and-Money/SAP tables, which are child artifacts of an Award
already reachable from the Award record itself, not independent business
documents a user would search for by name. This is a judgment call, flagged
in §12 as an open decision rather than assumed.

## 5. Verified counts (do not combine)

| Metric | Definition | Verified dev count |
| --- | --- | ---: |
| Kuali business documents | Distinct `document_number` across the 5 core modules (Award, Proposal, Negotiation, Subaward, IRB) | **78,854** (49,827 + 17,739 + 10,775 + 513 + 0) |
| Business-record versions | Row count of the same 5 core module tables | 78,854 (identical today, since every core-module row is 100% populated — this equality is a fact about today's data, not a structural guarantee) |
| Attachment references | `archive.award_attachment` rows (Award only; other modules' attachment tables exist separately, e.g. `subaward_attachment` — not counted here) | 198,194 for Award 204713-00001 alone (see §7); full-archive total not re-verified this pass — the user's own prior message cites 720,428 archive-wide |
| Physical files | Distinct `archive.attachment_object.file_id` | 836 for Award 204713-00001 alone; full-archive total not re-verified this pass — the user's own prior message cites 37,777 archive-wide |
| Downloadable files | Physical files with `upload_status = 'UPLOADED'` | Not verified this pass — open item, see §12 |

## 6. Document-to-business-record and document-to-attachment relationships

Verified directly, not assumed:

```
Kuali document (document_number, e.g. Award "1037915")
    → lives on the same row as the business/version identity
      (award_id / award_number / sequence_number)
    → archive.award_attachment rows reference that row via
      award_id + award_number + sequence_number — NOT via document_number
    → archive.award_attachment.file_id → archive.attachment_object
      (the physical file, deduplicated)
```

**Important finding**: `archive.award_attachment` has its own
`document_id` column, but it is a *different* concept from
`award_version.workflow_document_number` — the attachment linkage is keyed
by `award_id`/`award_number`/`sequence_number`, never by
`workflow_document_number`. A document number does not directly link to
attachments; you go through the business record's version identity first,
exactly as your architecture diagram describes.

## 7. Routing matrix

| Module | Document number (real example) | Target business ID | Existing UI route | Route verified? |
| --- | --- | --- | --- | --- |
| AWARD | `1037915` | `award_id = 3561610` | `/awards/:awardId` (`AwardDashboardPage`) | **Yes** — `award_id` is on the same row as the document number; `AwardSearchPage` already has an exact-document-number search that resolves and navigates this way today |
| PROPOSAL | `340086` | `proposal_number = "01096824"` | `/proposals/:proposalNumber` (`ProposalWorkspacePage`) | **Needs one extra step** — the route takes `proposal_number`, not `proposal_id`/`document_number` directly; a document-number search must first resolve `document_number → proposal_number` via `proposal_version` |
| NEGOTIATION | `367756` | `negotiation_id = 355` | `/negotiations/:negotiationId` (`NegotiationWorkspacePage`) | **Yes** — `negotiation_id` is on the same row as `document_number` |
| SUBAWARD | `343156` | `subaward_id = 1363` | `/subawards/:subawardId` (`SubawardWorkspacePage`) | **Yes** — `subaward_id` is on the same row as `document_number` |
| IRB | *(no rows to sample — 0 in dev)* | `protocol_id` / `record_id` | `/irb/history/:protocolId` or `/irb/record/:recordId` | **Not verified** — schema supports it (`irb_protocol_version.protocol_id`), but there is no live row in this dev DB to confirm end-to-end |

No new routes were created — this only inventories what already exists.

## 8. CARB-X example: Award 204713-00001

- **Award versions**: 544 rows (`sequence_number` 1–544), each with its own
  `workflow_document_number`. Sequence 1 → document `451699`. The current
  version (`is_primary_current = true`) is `sequence_number = 544`,
  `award_id = 3561610`, document `1037915`.
- **Related proposal `01128961`**: linked via `archive.award_funding_proposal`
  to `archive.proposal_version`, which returns **two** rows for this one
  proposal number — `proposal_id 1129222` (`version_number 3`, document
  `430102`) and `proposal_id 1139478` (`version_number 4`, document
  `451704`). One business record (the proposal), two document numbers.
- **Attachments**: 198,194 `archive.award_attachment` reference rows,
  **836** distinct physical files (`attachment_object.file_id`) — matches
  this session's earlier-confirmed figure for this Award's Attachments
  tab.
- **Existing routes**: Award → `/awards/3561610` (works today via
  `AwardDashboardPage`); Proposal → `/proposals/01128961` (works today via
  `ProposalWorkspacePage`, using `proposal_number` not a document number).

Which identifiers are business documents vs. attachments: the
`workflow_document_number`/`document_number` values above (`451699`,
`1037915`, `430102`, `451704`) are Kuali business documents. The 836
`file_id` values and 198,194 `award_attachment` reference rows are a wholly
separate concept — physical files and their historical linkage rows, never
identified by a document number. No attachment contents or S3 locations
were read or exposed in this investigation.

## 9. Proposed Dashboard meaning (recommendation only)

- **Card title**: "Kuali Documents"
- **Subtitle**: "Archived workflow and business documents across all
  modules"
- **Count definition**: distinct `(module, document_number)` compound
  identity, unioned across the 5 core module tables (`archive.award_version.workflow_document_number`,
  `archive.proposal_version.document_number`, `archive.negotiation.document_number`,
  `archive.subaward.document_number`, `archive.irb_protocol_version.document_number`),
  excluding NULL/blank values. **Today's verified dev count: 78,854.**
- **Proposed query shape**:

  ```sql
  WITH documents AS (
      SELECT 'AWARD' AS module, workflow_document_number AS document_number
        FROM archive.award_version
       WHERE workflow_document_number IS NOT NULL
      UNION ALL
      SELECT 'PROPOSAL', document_number FROM archive.proposal_version
       WHERE document_number IS NOT NULL
      UNION ALL
      SELECT 'NEGOTIATION', document_number FROM archive.negotiation
       WHERE document_number IS NOT NULL
      UNION ALL
      SELECT 'SUBAWARD', document_number FROM archive.subaward
       WHERE document_number IS NOT NULL
      UNION ALL
      SELECT 'IRB', document_number FROM archive.irb_protocol_version
       WHERE document_number IS NOT NULL
  )
  SELECT COUNT(DISTINCT (module, document_number)) FROM documents;
  ```

- **Clickable**: yes — to a Document Search page, per your original request.
- **Modules that can route successfully today**: Award, Negotiation,
  Subaward (document number's row already carries the target ID).
- **Modules needing additional implementation for search/routing**:
  Proposal (needs a `document_number → proposal_number` resolver, small —
  Award already has this exact pattern built for its own document-number
  search in `AwardSearchPage`/`AwardSearchResponse` to mirror); IRB (schema
  ready, but unverifiable until IRB data is loaded in this environment).

## 10. Proposed implementation files (not changed)

Listed for a future implementation pass only — nothing below was edited:

- `api/src/main/java/edu/bu/archive/adapter/in/web/DashboardController.java` — replace `0 AS documents` with the union query above
- `ui/src/features/dashboard/dashboardPresentation.mjs` — rename the `documents` card's title/description
- `ui/src/App.tsx` — replace the `documents` → `ComingSoonPage` route with a real Document Search page
- New: a `DocumentSearchController`/service/repository (mirroring the existing per-module search patterns, especially Award's exact-document-number search) and a corresponding `DocumentSearchPage.tsx`

## 11. Risks and unresolved decisions

- **Should Budget/Time-and-Money/SAP transmission document numbers count
  toward "Kuali Documents"?** This report recommends excluding them (they
  are child financial artifacts of an Award, not independent business
  records), but that is a judgment call, not a schema fact — worth
  confirming before implementing.
- **IRB is currently empty in this dev database.** The recommended query
  is written to include IRB, but it will contribute 0 until IRB data is
  loaded here — this should not be mistaken for a bug in the query.
  Confirm this is expected before treating a 0 IRB contribution as broken.
  IRB's row count on the live Dashboard `irb` card should be checked for
  consistency with this same finding (if it's also showing 0 currently,
  that's consistent; if it's showing a nonzero number, something else is
  going on and should be investigated before this metric is treated as
  reliable end-to-end).
- **Full-archive attachment/physical-file counts** (720,428 references /
  37,777 physical files, per your earlier message) were **not
  independently re-verified in this pass** — this investigation only
  confirmed the CARB-X-scoped numbers (198,194 / 836). If those
  archive-wide figures matter for a Dashboard "Attachment References" /
  "Physical Files" card, they should be verified directly before display.
- **"Downloadable files"** (physical files with `upload_status =
  'UPLOADED'`) was not queried this pass.
- **Proposal routing needs a small resolver** before a Document Search
  result for a Proposal document number can link anywhere — not yet built.

## Read-only queries executed

All queries ran via the existing `research-archive-platform-dev-loader`
ECS task (family revision 182, image
`20260811T163054Z-d354791-evidence`) against dev Postgres. Every query was
a `SELECT`/`COUNT`/`information_schema` lookup — zero `INSERT`/`UPDATE`/`DELETE`/DDL.
No Bedrock calls were made. No application code, migrations, or data were
changed.
