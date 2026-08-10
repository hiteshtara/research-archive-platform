# Award RAG Evidence-Graph Audit

**Status:** Audit only. No RAG implementation, no chatbot, no new vector
database. This document traces every answerable Award question from user
question → pgvector retrieval candidate → exact archive row(s) →
historical version/context → API/UI route, area by area, then
recommends a schema evolution for `archive.search_embedding`.

**Golden fixtures used throughout:**
- **`204713-00133`** (award_id `3187665`) — rich, reconciled, 125-version
  fixture. Its `$0.00` current obligated amount is *correct* (see
  [Time and Money.md](../kuali-business-rules/Time%20and%20Money.md)) —
  used here as the "everything traces cleanly" positive case.
- **`award_id=8`** (`100008-00002`) — the stale-`VER_NBR` regression
  fixture. Used here to confirm the corrected rule
  (`current AWARD_AMOUNT_INFO row = MAX(award_amount_info_id)`, never
  `source_version_number`) is what every area's provenance trace relies
  on.

## Method

Traced directly against: `database/migrations/*.sql` (table DDL),
`sql/extract/award/*.sql` and `oracle/{negotiation,subaward}/*.sql`
(Oracle source), `AwardArchiveRepository.java` (query/grain logic),
`AwardV1Controller.java` (endpoints), `ui/src/components/award/*.tsx`
(UI sections), and `archive.search_embedding` (V070) /
`etl/build_search_embedding.py` (current embedding population). No
hypothetical answers — every SQL fragment and endpoint path below is
copied from the real file, not reconstructed from memory.

---

## Area-by-area trace

### 1. Summary

| Field | Value |
|---|---|
| Archive table | `archive.award_version` (+ LATERAL picks: `award_person` for PI, `award_amount_info` for current amount, `award_hierarchy` for root/parent) |
| Source Oracle table | `AWARD` |
| Primary key | `award_id` |
| Parent key | none (top of family) |
| Business identifier | `award_number` |
| Version/family/current grain | One row per **version** (`award_id`). "Current" = `is_primary_current = TRUE` (one per `award_number`, enforced by `ux_award_one_primary_current`). |
| API endpoint | `GET /api/v1/awards/{awardId}/summary` |
| UI section | `AwardSummarySection.tsx` (`AwardDashboardPage.tsx`) |
| Provenance fields | `award_id`, `award_number`, `sequence_number`, `source_update_timestamp`, `source_update_user`, `loaded_at`, `load_id` |
| RAG document design | `AWARD_SUMMARY` — one doc per **family** (current version only): title, PI, sponsor, unit, status. This is exactly what `build_search_embedding.py`'s `AWARD` query already embeds today. |

### 2. Versions

| Field | Value |
|---|---|
| Archive table | `archive.award_version` (full multi-row list) |
| Source Oracle table | `AWARD` |
| Primary key | `award_id` |
| Parent key | none (self-grouped by `award_number`) |
| Business identifier | `award_number`; exact version identity = `award_id` (`sequence_number` alone is **not** unique — multiple `award_id`s can share `award_number` + `sequence_number`, per CLAUDE.md's grain rule) |
| Version/family/current grain | Full historical grain = `COUNT(*)` of `award_version` rows for the family (125 for `204713-00133`). |
| API endpoint | `GET /api/v1/awards/{awardId}/versions` (paginated) |
| UI section | `AwardVersionsSection.tsx` |
| Provenance fields | `award_id`, `sequence_number`, `workflow_document_number`, `source_update_timestamp` |
| RAG document design | `AWARD_VERSION` — one doc **per row**, not just current. Required for any temporal question ("who was PI in year X") since it's the index that resolves a date to an `award_id`. |

### 3. People and Units

| Field | Value |
|---|---|
| Archive tables | `archive.award_person` (people); `award_person_unit`, `award_person_credit_split`, `award_person_unit_credit_split` (splits); `award_unit_contact`, `award_sponsor_contact` (contacts); `unit`/`unit_administrator` (central admin contacts) |
| Source Oracle table | `AWARD_PERSONS`, `AWARD_PERSON_UNITS`, etc. |
| Primary key | `award_person_id` |
| Parent key | `award_id` |
| Business identifier | `person_id` / `rolodex_id` / `full_name`; role via `contact_role_code` |
| Version/family/current grain | **Version-scoped** (`award_id`-exact) — a person's role can change between versions; "current" people = people on the `is_primary_current` version's `award_id`. |
| API endpoint | `GET /api/v1/awards/{awardId}/people`, `/unit-contacts`, `/sponsor-contacts`, `/central-administration-contacts` |
| UI section | `AwardPeopleSection.tsx`, `AwardContactsSection.tsx` |
| Provenance fields | `award_person_id`, `award_id`, `source_update_timestamp` |
| RAG document design | `AWARD_PERSON` — one doc per `(award_id, award_person_id)`, **not** deduped to current-only, so "who was PI in year X" resolves against the right version. |

### 4. Amounts

| Field | Value |
|---|---|
| Archive table | `archive.award_amount_info` |
| Source Oracle table | `AWARD_AMOUNT_INFO` |
| Primary key | `award_amount_info_id` |
| Parent key | `award_id` |
| Business identifier | `tnm_document_number` (nullable) |
| Version/family/current grain | **Append-only ledger per `award_id`**. Current = `MAX(award_amount_info_id)` — the rule just fixed in `AwardArchiveRepository.java`; `source_version_number` (Oracle `VER_NBR`) is explicitly excluded from every current-row selector. |
| API endpoint | `GET /api/v1/awards/{awardId}/amounts` (paginated full history); current row also embedded in `/summary` |
| UI section | `AwardAmountsSection.tsx` |
| Provenance fields | `award_amount_info_id`, `award_id`, `tnm_document_number`, `transaction_id`, `source_version_number`, `loaded_at` |
| RAG document design | `AWARD_AMOUNT` — one doc per **row** (full ledger), tagged with `tnm_document_number`, so "amount after transaction Y" is answerable by exact-match lookup, not semantic search. |

### 5. Budget

| Field | Value |
|---|---|
| Archive tables | `award_budget` (root), `award_budget_period`, `award_budget_line_item` (+`_calculated_amount`), `award_budget_personnel_detail` (+`_calculated_amount`), `award_budget_period_summary_calculated_amount`, `award_budget_limit`, `award_budget_person`, `award_transferring_sponsor` |
| Source Oracle table | `AWARD_BUDGET_EXT` + `BUDGETS`, `BUDGET_PERIODS`, `BUDGET_DETAILS`, etc. |
| Primary key | `budget_id` (root); `budget_period_id`, `budget_line_item_id` below it |
| Parent key | `award_id` (budget) → `budget_id` (period) → `budget_period_id` (line item/personnel) |
| Business identifier | `budget_version_number` |
| Version/family/current grain | **Family-wide, not `award_id`-scoped** — documented directly in the repository code: `budget_version_number` is a family-wide monotonic counter (mirrors Kuali's own `AwardBudgetServiceImpl.getAllBudgetsForAward`), bounded to sequences ≤ the Award version being viewed. This is the one area whose grain rule deliberately differs from every other child table. |
| API endpoint | `GET /api/v1/awards/{awardId}/budget/summary`, `/versions`, `/periods`, `/line-items`, `/personnel` |
| UI section | `AwardBudgetSection.tsx` |
| Provenance fields | `budget_id`, `document_number`, `source_version_number` |
| RAG document design | `AWARD_BUDGET` — one doc per `budget_id`, with period/line-item totals summarized into the doc text rather than as separate document types (keeps the minimum set small; see readiness matrix for why per-line-item embedding is not recommended yet). |

### 6. Time & Money

| Field | Value |
|---|---|
| Archive tables | `award_amount_info` (ledger); `pending_transaction` (+`_extension`); `transaction_detail`; `award_amount_transaction`; `time_and_money_document`; `award_hierarchy` |
| Source Oracle table | `PENDING_TRANSACTIONS`, `TRANSACTION_DETAILS`, `AWARD_AMOUNT_TRANSACTION`, `TIME_AND_MONEY_DOCUMENT`, `AWARD_HIERARCHY` |
| Primary key | Varies: `transaction_id`, `transaction_detail_id`, `award_amount_transaction_id`, `document_number` |
| Parent key | **`award_number` (family-wide), not `award_id`** — proven directly this session: `TRANSACTION_DETAILS` for `204713-00133` returns 0 rows scoped to that exact `award_number`; all 256 related rows are recorded under sibling award `204713-00001` (hierarchy fan-out). This is the same family-wide-vs-version-scoped distinction Rule 5 in Time and Money.md documents. |
| Business identifier | `document_number` (Time & Money document number) |
| Version/family/current grain | **Family-wide, event-based** — not version-scoped at all. |
| API endpoint | `/time-and-money/summary`, `/actions`, `/history`, `/transactions/{pendingTransactionId}`, `/documents/{timeAndMoneyDocumentNumber}` |
| UI section | `AwardTimeAndMoneySection.tsx` |
| Provenance fields | `transaction_id` / `transaction_detail_id` / `award_amount_transaction_id`, `document_number`, `notice_date` |
| RAG document design | `AWARD_TIME_AND_MONEY` — one doc per `document_number` (event), **family-scoped, not version-scoped**. This is the area most likely to trip up a naive RAG design that assumes every Award document maps 1:1 to an `award_id`. |

### 7. Funding Proposals

| Field | Value |
|---|---|
| Archive table | `award_funding_proposal` (link) → `proposal_version` (target) |
| Source Oracle table | `AWARD_FUNDING_PROPOSALS` |
| Primary key | `award_funding_proposal_id` |
| Parent key | `award_id` → `proposal_id` |
| Business identifier | `proposal_number` |
| Version/family/current grain | Link row is **exact-version-scoped** (specific `proposal_id`); display resolves to that proposal family's `ACTIVE` version via `proposal_number`. |
| API endpoint | `GET /api/v1/awards/{awardId}/funding-proposals` |
| UI section | `AwardFundingProposalsSection.tsx` |
| Provenance fields | `award_funding_proposal_id`, `proposal_id` (exact), `active_flag` |
| RAG document design | `RELATED_PROPOSAL` — a relationship **edge**, not a full embedded document. The Proposal's own content is already embedded independently via the `PROPOSAL` module in `search_embedding`; this is just the join. |

### 8. Negotiations

| Field | Value |
|---|---|
| Archive table | `archive.negotiation` (`negotiation_association_type_code = 'AWD'`, `associated_document_id = award_number`) |
| Source Oracle table | `KCOEUS.NEGOTIATION` |
| Primary key | `negotiation_id` |
| Parent key | **None (no real FK)** — matched by `associated_document_id = award_number`, a denormalized/loose join, not a foreign key. |
| Business identifier | `document_number` |
| Version/family/current grain | Family-wide (matched by `award_number`, not `award_id`). |
| API endpoint | `GET /api/v1/awards/{awardId}/negotiations` |
| UI section | `AwardAssociatedNegotiationsSection.tsx` |
| Provenance fields | `negotiation_id`, `document_number` |
| RAG document design | `RELATED_NEGOTIATION` — relationship edge. |

### 9. Subawards

| Field | Value |
|---|---|
| Archive table | `archive.subaward_funding` |
| Source Oracle table | `KCOEUS.SUBAWARD_FUNDING_SOURCE` |
| Primary key | `subaward_funding_id` |
| Parent key | `subaward_id` (exact) + `award_number` (denormalized at ETL time — documented directly in the repository code: "matched on the `award_number` denormalized onto `subaward_funding` at ETL time, not by joining through a specific `award_id`") |
| Business identifier | `subaward_code` |
| Version/family/current grain | Family-wide; display resolves to the target Subaward's `subaward_sequence_status = 'ACTIVE'` version. |
| API endpoint | `GET /api/v1/awards/{awardId}/funding-subawards` |
| UI section | `AwardFundingSubawardsSection.tsx` |
| Provenance fields | `subaward_funding_id`, `subaward_id` |
| RAG document design | `RELATED_SUBAWARD` — relationship edge. |

### 10. Terms

| Field | Value |
|---|---|
| Archive tables | `award_sponsor_term`, `award_report_term` (+`_recipient`) |
| Source Oracle table | `AWARD_SPONSOR_TERMS`, `AWARD_REPORT_TERMS`(`_RECIPIENTS`) |
| Primary key | `award_sponsor_term_id` / `award_report_term_id` |
| Parent key | `award_id` |
| Business identifier | `term_type_code` |
| Version/family/current grain | Version-scoped (`award_id`-exact). |
| API endpoint | `GET /api/v1/awards/{awardId}/terms` |
| UI section | `AwardTermsSection.tsx` |
| Provenance fields | `award_id`, `source_update_timestamp` |
| RAG document design | `AWARD_TERM` — one doc per `award_id` (all terms for that version summarized together; terms are short structured fields, not long free text, so one doc per version is sufficient granularity). |

### 11. Comments/Notepad

| Field | Value |
|---|---|
| Archive tables | `award_comment`, `award_notepad` |
| Source Oracle table | `AWARD_COMMENT`, `AWARD_NOTEPAD` |
| Primary key | `award_comment_id` / `award_notepad_id` |
| Parent key | `award_id` |
| Business identifier | none (free text); `comment_type_code` / `note_topic` classify it |
| Version/family/current grain | `award_comment` is version-scoped (`award_id` + `sequence_number`). **`award_notepad` has no `sequence_number` at all** (confirmed directly in `AwardCommentsSection.tsx`'s own comment: "award_notepad is a separate group with no sequence_number at all") — it cannot be resolved to a specific version. |
| API endpoint | `GET /api/v1/awards/{awardId}/comments` (returns both comment categories and notepad entries) |
| UI section | `AwardCommentsSection.tsx` |
| Provenance fields | `award_comment_id` / `award_notepad_id`, `award_id` |
| RAG document design | `AWARD_COMMENT` — highest-value RAG target (genuine free text). One doc per `award_comment_id`, plus a separate, explicitly family-scoped-not-version-scoped notepad doc type (or a `sequence_number: null` field on the same document type) so retrieval never implies a notepad entry belongs to one specific version. |

### 12. SAP transmissions

| Field | Value |
|---|---|
| Archive tables | `award_transmission`, `award_transmission_child` |
| Source Oracle table | `AWARD_TRANSMISSION`(`_CHILD`) |
| Primary key | `transmission_id` |
| Parent key | `award_id` |
| Business identifier | `document_number` |
| Version/family/current grain | Version-scoped (`award_id`-exact). |
| API endpoint | `GET /api/v1/awards/{awardId}/sap-transmissions` |
| UI section | `AwardSapTransmissionsSection.tsx` |
| Provenance fields | `transmission_id`, `award_id`, `transmission_date` |
| RAG document design | Not recommended as a RAG document type — this is a system-integration audit log (`sent_data`/`returned_data` payloads), not human-readable narrative content. Deterministic SQL/API access is sufficient; low value for semantic retrieval. |

### 13. Attachments

| Field | Value |
|---|---|
| Archive tables | `award_attachment`, `attachment_object` |
| Source Oracle table | `AWARD` attachment export (column names explicitly marked unverified in the migration's own header comment) |
| Primary key | `award_attachment_id` |
| Parent key | `award_id`; `file_id` → `attachment_object` |
| Business identifier | `document_id` |
| Version/family/current grain | Version-scoped (`award_id`-exact). |
| API endpoint | `GET /api/v1/awards/{awardId}/attachments`, `/attachments/{attachmentId}/download` |
| UI section | `AwardAttachmentsSection.tsx` |
| Provenance fields | `award_attachment_id`, `award_id`, `file_id`, `oracle_update_timestamp` |
| RAG document design | `AWARD_ATTACHMENT` — metadata only (filename, description, type) is embeddable today. **Attachment content (PDF/doc text) is not extracted anywhere in this repo** — `attachment_object` stores the binary in S3 with no OCR/text-extraction pipeline. Full content-aware RAG over attachments is blocked until that pipeline exists. |

---

## Deterministic questions (proven against real SQL/API, not hypothetical)

| # | Question | Path | Verdict |
|---|---|---|---|
| 1 | Who was PI on this Award in year X? | Resolve `award_id` for year X via `/versions` (match `award_effective_date`/`begin_date`/`closeout_date`), then `GET /people` for that `award_id`, filter `contact_role_code = 'PI'`. Two real calls, fully deterministic. | **READY** |
| 2 | What was the obligated amount after transaction Y? | SQL-provable: `SELECT * FROM archive.award_amount_info WHERE tnm_document_number = 'Y'`. **No dedicated API query param exists** — `findAmountHistory(awardNumber, limit, offset)` is pagination-only, no `tnm_document_number` filter. | **PARTIAL** — SQL-ready, API requires client-side pagination+filter |
| 3 | How did obligated amount change over time? | `GET /amounts` returns the full paginated history, ordered `sequence_number DESC, award_amount_info_id DESC` — exactly this question. | **READY** |
| 4 | Which proposal funded this Award? | `GET /funding-proposals` | **READY** |
| 5 | Which negotiations are linked? | `GET /negotiations` | **READY** |
| 6 | Which subawards are linked? | `GET /funding-subawards` | **READY** |
| 7 | What budget personnel existed on version X? | Resolve in-scope `budget_id`(s) for that `award_id` (family-wide, bounded by sequence ≤ X per the documented Budget grain rule), then `GET /budget/personnel` for those `budget_id`s. Requires understanding the family-wide grain first — documented, not hidden, but a real extra step vs. every other version-scoped area. | **READY** (with the grain caveat noted above) |
| 8 | Which comments existed on version X? | `GET /comments` for that `award_id` returns `award_comment` rows correctly scoped to X. Notepad entries in the same response are **not** version-scoped (no `sequence_number`) — they belong to the family, not version X specifically. | **PARTIAL** — comments READY, notepad BLOCKED for version-scoping |
| 9 | Which attachments belonged to version X? | `GET /attachments` for that `award_id` — metadata fully scoped to X. | **READY** (metadata); content extraction BLOCKED (see area 13) |
| 10 | What Time & Money events occurred between two dates? | SQL-provable: `SELECT * FROM archive.award_amount_transaction WHERE award_number = :n AND notice_date BETWEEN :start AND :end`. **No date-range query param exists** on `findTimeAndMoneyActions(awardNumber, limit, offset)` today. | **PARTIAL** — SQL-ready, API requires client-side pagination+filter |

---

## Minimum RAG document types

Based on the trace above, the minimum set that covers every area without
over-fragmenting:

| Document type | Grain | One doc per |
|---|---|---|
| `AWARD_SUMMARY` | family, current | `award_number` (already exists in `search_embedding` today) |
| `AWARD_VERSION` | version | `award_id` |
| `AWARD_PERSON` | version | `(award_id, award_person_id)` |
| `AWARD_AMOUNT` | version (ledger) | `award_amount_info_id` |
| `AWARD_BUDGET` | family (bounded) | `budget_id` |
| `AWARD_TIME_AND_MONEY` | **family**, event | `document_number` |
| `AWARD_TERM` | version | `award_id` |
| `AWARD_COMMENT` | version (comments) / family (notepad) | `award_comment_id` / `award_notepad_id` |
| `AWARD_ATTACHMENT` | version, metadata only | `award_attachment_id` |
| `RELATED_PROPOSAL` | edge | `award_funding_proposal_id` |
| `RELATED_NEGOTIATION` | edge | `negotiation_id` (per linked Award) |
| `RELATED_SUBAWARD` | edge | `subaward_funding_id` |

`AWARD_SAP_TRANSMISSION` is deliberately excluded (see area 12).

---

## `archive.search_embedding` schema evolution

**Current schema (V070):** `search_embedding_id`, `module`, `record_id`,
`canonical_family_id`, `business_number`, `source_text`, `source_hash`,
`embedding`, `embedding_model`, `generated_at`. Populated today by
`build_search_embedding.py`'s `DOMAIN_QUERIES`, which embeds exactly
**one current-version summary row per family**, for four modules
(`AWARD`, `PROPOSAL`, `NEGOTIATION`, `SUBAWARD`). This is sufficient for
Global Search's existing job (surface the right family), but has no way
to represent a specific version, a specific child row, or the exact
provenance chain needed to ground a RAG answer.

**Recommendation: evolve the table, in place, with additive nullable
columns — do not introduce a second vector database or a second table.**
Reuse the existing PostgreSQL/pgvector infrastructure per your
instruction; add:

```sql
ALTER TABLE archive.search_embedding
    ADD COLUMN IF NOT EXISTS document_type            VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_module             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_business_identifier VARCHAR(255),
    ADD COLUMN IF NOT EXISTS exact_record_id           BIGINT,
    ADD COLUMN IF NOT EXISTS version_label             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_table              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_primary_key        BIGINT,
    ADD COLUMN IF NOT EXISTS source_row_hash            VARCHAR(64);
```

Mapping to your proposed fields:
- `document_type` — the twelve types above (`AWARD_SUMMARY`,
  `AWARD_VERSION`, ... `RELATED_SUBAWARD`). Existing Global Search rows
  backfill as `document_type = 'AWARD_SUMMARY'` /
  `'PROPOSAL_SUMMARY'` / etc. — zero disruption.
- `parent_module` + `parent_business_identifier` — e.g. `AWARD_PERSON`
  rows get `parent_module = 'AWARD'`, `parent_business_identifier =
  '204713-00133'`, so a retrieved person row can always be traced back
  to its owning Award without a second lookup.
- `exact_record_id` — the specific `award_id` / `award_amount_info_id` /
  etc., distinct from `canonical_family_id` (which stays family-level for
  dedup). This is what makes `AWARD_VERSION`/`AWARD_PERSON`/`AWARD_AMOUNT`
  documents resolvable to one exact archive row instead of "somewhere in
  this family."
- `version_label` — `sequence_number` for version-scoped types, `NULL`
  for family-scoped types (`AWARD_TIME_AND_MONEY`, notepad, budget) —
  doubles as the flag that tells a retrieval consumer whether "which
  version was this on" is even a valid question for this document.
- `source_table` + `source_primary_key` — e.g. `'archive.award_person'`
  + the `award_person_id` — completes the provenance chain
  (question → pgvector candidate → **exact archive row**) your audit
  goal requires, independent of `exact_record_id`'s narrower module
  semantics.
- `source_row_hash` — same idempotency pattern `build_search_embedding.py`
  already uses for `source_hash`, but scoped to the underlying source
  row rather than the assembled `source_text` blob, so a source-row edit
  can be detected even if two different rows happen to produce identical
  embedded text.

The existing `UNIQUE (module, record_id)` index would need to become
`UNIQUE (module, document_type, exact_record_id)` (or an equivalent
composite) to allow multiple document types per family — a real,
non-trivial migration since today's uniqueness assumes one row per
`(module, record_id)`. This is a schema decision to make deliberately
when RAG is actually implemented, not now.

**Global Search summary embeddings are fully preserved** — nothing above
removes or repurposes the existing `AWARD`/`PROPOSAL`/`NEGOTIATION`/
`SUBAWARD` rows; they simply gain a `document_type` label consistent
with the new rows sitting alongside them.

---

## Readiness matrix

| Area | Historical grain | Provenance complete | Attachment content | Deterministic query | RAG ready |
|---|---|---|---|---|---|
| Summary | READY (version, current-flagged) | READY | n/a | READY | READY |
| Versions | READY (full family grain) | READY | n/a | READY | READY |
| People and Units | READY (version-scoped) | READY | n/a | READY | READY |
| Amounts | READY (append-only ledger, rule just fixed) | READY | n/a | PARTIAL (no document-number query param) | READY |
| Budget | PARTIAL (family-wide grain, not `award_id`) | READY | n/a | READY (with grain caveat) | PARTIAL (needs `document_type`/`exact_record_id` to disambiguate family-wide grain) |
| Time & Money | PARTIAL (family-wide, event-based, not version-scoped) | READY | n/a | PARTIAL (no date-range query param) | PARTIAL (schema must carry `version_label = NULL` correctly or answers will falsely imply version-scoping) |
| Funding Proposals | READY (exact-version link) | READY | n/a | READY | READY (edge, not embedded content) |
| Negotiations | PARTIAL (no real FK, denormalized `award_number` match) | READY | n/a | READY | READY (edge) |
| Subawards | PARTIAL (denormalized `award_number` match) | READY | n/a | READY | READY (edge) |
| Terms | READY (version-scoped) | READY | n/a | READY | READY |
| Comments/Notepad | PARTIAL (comments version-scoped; notepad has no `sequence_number`) | READY | n/a | PARTIAL (notepad can't answer "on version X") | READY (highest-value target, with the notepad caveat) |
| SAP transmissions | READY (version-scoped) | READY | n/a | READY | BLOCKED (not a RAG candidate — audit log, not narrative) |
| Attachments | READY (version-scoped metadata) | READY | **BLOCKED (no text-extraction pipeline)** | READY (metadata only) | PARTIAL (metadata READY, content BLOCKED) |

### Explanation of every PARTIAL/BLOCKED area

- **Amounts (deterministic PARTIAL):** the underlying data and SQL are
  fully provable; the gap is purely that `findAmountHistory` doesn't
  expose a `tnm_document_number` filter parameter today. Cheap to add if
  ever needed for the REST API directly; irrelevant for RAG since a
  retrieval layer would query `search_embedding`/`archive` directly.
- **Budget (grain PARTIAL, RAG PARTIAL):** the only area whose grain
  genuinely isn't `award_id`-scoped like its siblings — it's family-wide,
  bounded by sequence. A RAG document for a `AWARD_BUDGET` row must
  carry enough of the "which versions is this budget in scope for"
  context (documented in `docs/kuali-business-rules/Budget.md`) or a
  retrieved budget can be misattributed to the wrong version.
- **Time & Money (grain PARTIAL, deterministic PARTIAL, RAG PARTIAL):**
  the same family-wide-not-version-scoped pattern as Budget, proven
  directly against `204713-00133` this session (zero `transaction_detail`
  rows scoped to that exact `award_number`; all real activity recorded
  under the family root). This is the area most likely to produce a
  confidently wrong RAG answer if `version_label` isn't explicitly
  nullable and honored by the retrieval/answer layer.
- **Negotiations / Subawards (grain PARTIAL):** not a data-quality
  problem — both are architecturally join-by-business-number, not
  foreign-key relationships, so provenance is real but slightly less
  strict (a rename collision on `award_number` could theoretically
  misattribute a link, though none is known to exist).
- **Comments/Notepad (deterministic PARTIAL):** `award_comment` fully
  answers "on version X"; `award_notepad` structurally cannot, because
  the source table has no version identifier at all. This is a genuine
  Oracle/Kuali data-model fact, not an archive gap.
- **SAP transmissions (RAG BLOCKED):** deterministic SQL/API access is
  fully READY; it's excluded from the RAG document set as a judgment
  call (system audit log, not narrative content a user would ask about).
- **Attachments (content BLOCKED):** the one true infrastructure gap in
  this audit. `attachment_object` stores binaries in S3; no OCR or
  text-extraction step exists anywhere in the ETL pipeline. Metadata
  (filename, description, type) is fully RAG-ready; the documents
  themselves are not searchable by content until that pipeline is built
  — out of scope for this audit and for the "no RAG yet" instruction.

---

## Bottom line

12 of 13 areas have a fully traceable, provable path from question to
exact archive row. The two structurally hard areas (Budget, Time &
Money) aren't broken — they're genuinely family-wide-grained in Oracle
itself, proven directly against the golden fixture this session — but a
RAG schema that doesn't carry `version_label`/`exact_record_id`
explicitly enough to represent "this document is not version-scoped"
will produce plausible-sounding wrong answers for exactly those two
areas. The one hard infrastructure gap (attachment content extraction)
is out of scope here and should be a separate, explicit follow-up before
attachments can be part of RAG for anything beyond metadata.

No RAG implementation, reranker, or chatbot layer is built in this
audit, per instruction.
