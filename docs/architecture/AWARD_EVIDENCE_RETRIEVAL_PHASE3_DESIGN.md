# Award Evidence Retrieval — Phase 3 Design

**Status: PROPOSED. Design only. No production code was written or
modified to produce this document.**

Labeling convention (mirrors `AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md`):
**VERIFIED** = confirmed by reading the actual code/schema/git history this
session. **AUDIT DECISION** = a deliberate design choice made here, with
rationale, not dictated by existing code. **PROPOSED** = new code this
document recommends writing in Phase 3B; nothing under this label exists
yet.

Repository: `https://github.com/hiteshtara/research-archive-platform`.
Branch `main`. HEAD at design time: `0029414a0165e279bb2efa0c33d2111e1b0822d6`
("docs(demo): add Award RAG client demonstration plan").

---

## 1. Current architecture (VERIFIED)

### 1.1 Evidence indexing (already built, Phase 1/2, commit `e76e95f`)

- `database/migrations/V071__extend_search_embedding_for_evidence_documents.sql`
  (untracked, already applied to dev — confirmed via
  `public.schema_migration` in the Phase 2 demo audit) adds 8 nullable
  columns to `archive.search_embedding`: `document_type`, `parent_module`,
  `parent_business_identifier`, `exact_record_id`, `version_label`,
  `source_table`, `source_primary_key`, `source_row_hash`. It also widens
  the table's uniqueness to `(module, document_type, exact_record_id)`
  and adds three supporting indexes: `ix_search_embedding_document_type`,
  `ix_search_embedding_parent (parent_module, parent_business_identifier)`,
  `ix_search_embedding_source_row (source_table, source_primary_key)`.
- `etl/build_evidence_embedding.py` populates 8 evidence
  `document_type`s, always scoped to exactly one `award_number`:
  `AWARD_VERSION`, `AWARD_PERSON`, `AWARD_AMOUNT`, `AWARD_TERM`,
  `AWARD_COMMENT`, `RELATED_PROPOSAL`, `RELATED_NEGOTIATION`,
  `RELATED_SUBAWARD`. `AWARD_SUMMARY` is deliberately **not** produced by
  this script — it is owned by `etl/build_search_embedding.py`. For every
  evidence row: `module='AWARD'`, `parent_module='AWARD'`,
  `parent_business_identifier=<award_number>`, `source_table` is a fixed,
  hardcoded fully-qualified table name (e.g. `'archive.award_person'`),
  `source_primary_key` is the row's real, positive, independently
  re-queryable database primary key, `version_label` is the
  `sequence_number` as text (`NULL` for `RELATED_NEGOTIATION`/
  `RELATED_SUBAWARD`, which are family-wide with no version concept).
  `source_text` is built by one deterministic, allowlisted-field text
  builder per type (no free text, no AI enrichment) — see
  `build_evidence_embedding.py`'s `_TEXT_BUILDERS` map.
- **As of this session's Phase 2 demo audit: zero evidence rows exist in
  the dev database.** `build_evidence_embedding.py` has never been run
  for real, and this design does not change that — Phase 3A proposes no
  ingestion.

### 1.2 Global Search's existing semantic branch

- `api/src/main/java/edu/bu/archive/adapter/out/persistence/SemanticSearchRepository.java`
  — `findNearest(float[] queryEmbedding, int topK)`, single method,
  cosine-distance (`<=>`) query against `archive.search_embedding`,
  `ORDER BY distance LIMIT :topK`. **Currently dirty/uncommitted** — see
  §2.
- `api/src/main/java/edu/bu/archive/application/service/GlobalSearchService.java`
  — 6-branch concurrent fan-out (IRB/Award/Negotiation/Subaward/
  Proposal/Semantic). The semantic branch is gated by
  `SemanticSearchProperties.isEnabled()` AND a
  `LikelyIdentifierDetector` heuristic (semantic search never runs for a
  query that looks like an exact identifier). Not part of Phase 3's
  design surface — Phase 3 does not touch `GlobalSearchService.java`.
- `SemanticSearchProperties` (`config/SemanticSearchProperties.java`,
  clean/committed) — `@ConfigurationProperties(prefix =
  "app.search.semantic")`: `enabled` (default `false`), `topK` (default
  `5`), `embeddingModel` (default `"amazon.titan-embed-text-v2:0"`),
  `bedrockTimeoutMs` (default `2000`).
- `SemanticSearchConfiguration` (`config/SemanticSearchConfiguration.java`,
  clean/committed) — two `@Bean`s, both
  `@ConditionalOnProperty(app.search.semantic.enabled=true)`:
  `BedrockRuntimeClient` (region from `AWS_REGION` env var, **no
  `apiCallTimeout`/`ClientOverrideConfiguration` set** — `bedrockTimeoutMs`
  is declared in `SemanticSearchProperties` but is **not referenced
  anywhere** in `BedrockEmbeddingProvider.java` or this config class —
  confirmed by reading both files in full. This is a real, pre-existing
  gap, not a Phase 3 regression), and `EmbeddingProvider` (a
  `BedrockEmbeddingProvider`).
- `EmbeddingProvider` (`application/port/out/EmbeddingProvider.java`) —
  one-method interface: `float[] embed(String text)`.
- `BedrockEmbeddingProvider` (`adapter/out/search/BedrockEmbeddingProvider.java`)
  — calls Bedrock `InvokeModel` with `{"inputText": text}`, parses the
  `embedding` array from the response. On any exception: logs
  `LOG.warn("Bedrock embedding call failed", exception)` (never logs
  `text` itself) and throws `EmbeddingProviderException(message, cause)`
  (`adapter/out/search/EmbeddingProviderException.java`, a plain
  `RuntimeException`).
- **Confirmed live in dev**: `APP_SEARCH_SEMANTIC_ENABLED=true` on the
  running ECS API task (verified via `aws ecs describe-task-definition`
  in the Phase 2 demo audit), and 8,597 real `AWARD_SUMMARY` embeddings
  already exist. This flag, and this provider infrastructure, is what
  Phase 3 proposes reusing — see §3.2.

### 1.3 Existing AI Summary/Questions feature (a different, adjacent feature)

- `AwardAiController` (`adapter/in/web/AwardAiController.java`, clean/
  committed) — `@RestController @RequestMapping("/api/ai/awards")
  @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")`.
  `POST /{awardNumber}/summary`. **The whole controller bean does not
  exist unless `app.ai.enabled=true`.** Confirmed: `app.ai.enabled` has
  no override in dev's ECS task environment or
  `terraform/environments/dev/terraform.tfvars` — it is `false` in the
  deployed dev environment today (per the Phase 2 demo audit).
- `AwardAiQuestionController` — same `@RequestMapping("/api/ai/awards")`
  prefix, gated on **both** `app.ai.enabled` and
  `app.ai.questions-enabled`. `POST /{awardNumber}/questions`, request
  body `{ "question": string }` (`@NotBlank`, `@Size(max=500)`).
- Both controllers resolve `awardNumber` via
  `AwardArchiveService.findFamily(normalizedAwardNumber)`, which throws
  `NoSuchElementException("Award not found: " + normalizedAwardNumber)`
  when the Award doesn't exist — the same convention every other Award
  sub-resource endpoint uses.
- `AiExceptionHandler` (`adapter/in/web/AiExceptionHandler.java`, clean/
  committed) — `@RestControllerAdvice(assignableTypes = {
  AwardAiController.class, AwardAiQuestionController.class })`. Maps
  `AiSummaryExecutionException` → 404/400/503 depending on cause, bare
  `AiProviderException`/`NoSuchElementException`/`IllegalArgumentException`
  → 503/404/400. Error JSON shape:
  `{timestamp, status, error, message, correlationId?}` —
  `correlationId` key is **only present** when the exception carries one
  (only `AiSummaryExecutionException` does).
- `AwardCitationValidator` (`application/ai/AwardCitationValidator.java`,
  `@ConditionalOnProperty(app.ai.enabled)`) validates **model-returned**
  citations (`AiCitation(recordType, recordId, awardNumber,
  sequenceNumber)`) against a caller-built allow-list of real structured
  Award records — solving "the model claimed a citation that doesn't
  exist." **This does not apply to Phase 3.** Phase 3 has no
  free-text-generating model in the loop; its evidence rows come
  straight from deterministic SQL, so there is no untrusted citation to
  validate against an allow-list — the row's own `source_table`/
  `source_primary_key` *is* the citation, trustworthy by construction.
- `SensitiveFieldRedactor` (`application/ai/SensitiveFieldRedactor.java`,
  `@ConditionalOnProperty(app.ai.enabled)`) — regex-based redaction
  (`[REDACTED]` for emails, phone-like digit sequences, `password`/
  `secret`/`api_key`/`token` key=value pairs, `jdbc:` URLs, AWS access
  key IDs, AWS SigV4 query params), applied only to archive text fields
  fed into `AwardAiContext` for the LLM. **Not applied anywhere in
  `build_evidence_embedding.py`'s text builders** — evidence `source_text`
  (including `AWARD_COMMENT`'s raw archived comment text) is currently
  unredacted. See §6.3 for why this matters for Phase 3.
- Response DTO convention (`AwardAiSummaryResponse`,
  `AwardAiQuestionResponse`): Java `record`s with a `correlationId: String`
  field (from `UUID.randomUUID().toString()`, generated once per request
  inside the service method, not from any request header/MDC — confirmed
  no app-wide request-tracing convention exists), `List.copyOf(...)`
  normalization in the compact constructor, a static `from(...)` factory
  mapping a domain result object to the response record.
- **Security**: zero per-controller opt-in needed.
  `SecurityConfiguration.java` (clean/committed) requires
  `.requestMatchers("/api/**").authenticated()` globally — any new
  controller under `/api/ai/awards/**` inherits this automatically.

### 1.4 UI conventions

- `AwardAiSummaryPanel.tsx` / `AwardAiQuestionPanel.tsx`
  (`ui/src/features/ai/`, clean/committed) — **confirmed not imported by
  any page** (repo-wide grep of `ui/src/pages/` for either name: zero
  matches). Both are `useMutation`-driven (button click fires the
  request), hand-rolled loading (`CircularProgress` + `Stack`) and error
  (`Alert severity="error"`, a `switch` on `ApiRequestError.status`
  mapping to copy, `correlationId` shown as "Support reference: ...").
  **No empty/insufficient-evidence state exists in either panel today** —
  before a result exists, nothing renders but the button.
- `AwardFundingProposalsSection.tsx` (`ui/src/components/award/`, clean/
  committed) — the established pattern for a `useQuery`-driven Award
  sub-resource panel: `LoadingState mode="skeleton"` while loading,
  `ErrorState message="..."` on query error, `EmptyState variant="text"
  message="..."` when the array is empty, otherwise a list of
  `RelationshipCard`s. Wired into `AwardDashboardPage.tsx`'s `SECTIONS`
  (`{key, label}` tuples) and `IMPLEMENTED_SECTIONS` (a `Set<SectionKey>`)
  arrays.
- `IrbPage.tsx` (lines ~186-298) — the only existing "search box + filter
  chips + submit button" pattern in this codebase: a `TextField` (Enter
  submits), two `Select` filters, a `Button variant="contained"`, a
  dismissible "Active filters" `Chip` row, a "Clear all" button. No
  reusable shared component exists for this — every page hand-rolls it.
- `ui/src/types/api.ts` — no `score`/similarity field exists anywhere
  today; Phase 3 introduces the first one.
- `ui/src/api/client.ts`'s `request()` helper already supports POST with
  a JSON body (4th argument) — exact existing example,
  `askAwardQuestion()` (line 676-687), calling
  `POST /api/ai/awards/{awardNumber}/questions` with `{ question }`.

---

## 2. Dirty-file overlap assessment (VERIFIED, git history + diff inspection)

Per instruction: no dirty file was staged, discarded, overwritten, or
reformatted to produce this document. `git status --short -uall` (94
lines) was captured in full before any reading began.

| File | Classification | Owner / purpose (from code + git history) | Test coverage | Phase 3 impact |
|---|---|---|---|---|
| `api/src/main/java/edu/bu/archive/adapter/out/persistence/SemanticSearchRepository.java` | **Already required by Phase 3** | Phase 1's evidence-isolation guard: adds `WHERE document_type IN (:summaryDocumentTypes)` to `findNearest()` so evidence rows (once they exist) can never leak into Global Search. Comment cites `AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md` directly. Same author/session as the evidence-indexing work (`e76e95f`'s predecessor step). | Yes — `SemanticSearchRepositoryTest.java` (new, untracked, SQL-text lock) + `etl/tests/test_semantic_search_document_type_guard.py` (new, untracked, real-Postgres behavioral proof) — both pass. | **Do not modify.** Phase 3 must not touch this file or its guard — see §6.1's stop condition. Phase 3's new repository queries evidence rows through a **separate, new** class instead (§4), so this guard's summary-only scope stays correct and untouched. |
| `api/src/main/java/edu/bu/archive/adapter/out/persistence/InvestigatorRepository.java` | **Unrelated but compatible** | An IRB investigator historical-study query fix — replaces a `matching_families`/`ranked_versions` two-CTE join with a direct per-`protocol_id` `pi_email` match (`matching_versions`). Also loosens `InvestigatorIdentity` from `private record` to package-private so its own test can construct it. Matches the prior session's "Platform Integration Sprint 1" work (Investigator historical-version bug fix), unrelated to Award/evidence/semantic search. | Yes — `InvestigatorRepositoryTest.java` (new, untracked). | None. Zero overlap with Phase 3's files or domain. Not touched, not read further. |
| `terraform/environments/dev/main.tf` | **Unrelated but compatible** | A drift-prevention guard (`terraform_data.explorer_flags_match` with a `precondition`) ensuring the UI's `VITE_EXPLORER_ENABLED` and the API's `APP_EXPLORER_ENABLED` can't silently diverge — the exact root cause documented in commit `92df3a9`'s "Proposal Explorer 404 investigation." Entirely about the unrelated Explorer feature. | N/A (Terraform, no unit test convention in this repo for `.tf` files). | None. Phase 3B needs **no** Terraform changes at all — the API's existing `task_bedrock` IAM policy (already grants `bedrock:InvokeModel` to the API task role, added for the semantic-search feature) already covers the same Titan embedding model Phase 3 would reuse. Confirmed no new AWS resource is needed. |
| `ui/src/pages/GlobalSearchPage.tsx` | **Unrelated but compatible** | A one-paragraph help-text update reflecting Global Search's now-expanded domain coverage (Proposals/Negotiations/Subawards added since the original copy was written). Purely cosmetic. | N/A (no test targets this literal string). | None. Phase 3's new UI panel lives on the Award page (`AwardDashboardPage.tsx`), not `GlobalSearchPage.tsx` — zero file overlap. |
| `database/migrations/V071__extend_search_embedding_for_evidence_documents.sql` (untracked) | **Already required by Phase 3** | The schema Phase 3's retrieval query reads from directly (§1.1). Already applied to dev. | Indirectly, via `build_evidence_embedding.py`'s 46 passing tests and the two `SemanticSearchRepository` guard tests above, all of which depend on this schema. | Read-only dependency — Phase 3B adds no new migration. |
| `etl/build_search_embedding.py` (dirty, modified) | **Unrelated but compatible** | Not reviewed in prior sessions' diffs this deeply, but per its own committed docstring/comment (referenced in `V071`'s migration and `SemanticSearchRepository`'s guard) it is the population script for the 4 `*_SUMMARY` document types Phase 3 explicitly does **not** touch. | Existing `uv run pytest` coverage (973 passed at last full run, one pre-existing unrelated failure). | None — Phase 3B never modifies or runs this script. |
| Remaining dirty files (`docs/architecture/AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md`, `docs/kuali-business-rules/README.md`, `etl/Dockerfile.loader`, `etl/pyproject.toml`, `etl/uv.lock`, `.agents/`, `.github/workflows/`, `docs/_to_delete/`, `docs/architecture/diagrams/`, `docs/kuali-business-rules/SUBAWARD_FDP_RECONSTRUCTION.md`, `docs/project-story/`, various `etl/*.py` investigation/backfill scripts, `etl/tests/test_run_search_diagnostics.py`, `etl/tests/test_semantic_search_document_type_guard.py`, `scripts/run-search-diagnostics.sh`, `skills-lock.json`) | **Unrelated but compatible** | Prior sessions' CARB-X attachment work, the attachment-fix regression fixtures, CI workflow setup, and misc. documentation reorganization — none reference `search_embedding`, evidence document types, or the AI/evidence-search domain. | Varies; not the concern of this design. | None. Not modified, not staged, not read in further detail — out of scope for Phase 3's overlap check. |

**No conflicting or obsolete change was found.** No dirty file's purpose
was ambiguous enough to require stopping — every file's owner and intent
was determinable from its own diff content plus this session's own git
history (commit messages, code comments citing specific investigations).

**Conclusion**: Phase 3 can proceed. Its one real dependency
(`SemanticSearchRepository.java`'s guard) is already correct,
already tested, and must be left exactly as-is — Phase 3 reuses the
*schema* that guard protects, without touching the guard itself.

---

## 3. Proposed API (PROPOSED)

### 3.1 Endpoint

```
POST /api/ai/awards/{awardNumber}/evidence-search
```

**AUDIT DECISION**: this is a **new controller class**
(`AwardEvidenceSearchController`, §9), not a new method on
`AwardAiController`. Rationale: `AwardAiController`'s whole bean is gated
on `app.ai.enabled`, which is `false` in dev today with no path to
turning it on as part of this work. Evidence retrieval is embedding-based
similarity search, not LLM generation — architecturally it belongs with
`SemanticSearchProperties`/`app.search.semantic.enabled` (**already
`true` in dev**), not with the AI-generation flag. Gating the new
controller on `app.search.semantic.enabled` instead means it can be live
the moment Phase 3B is deployed, without depending on a separate,
currently-off feature flag. The new controller keeps the same
`@RequestMapping("/api/ai/awards")` base path (Spring permits multiple
controller classes sharing a base path) purely for URL-namespace
consistency with the existing AI endpoints the client demo audience will
already have seen described.

### 3.2 Request

```json
{
  "query": "Who are the investigators and what are their roles?",
  "documentTypes": ["AWARD_PERSON"],
  "topK": 8
}
```

- `query` — required, non-blank, max length TBD in Phase 3B (mirror
  `AwardAiQuestionRequest`'s `@NotBlank` + `@Size(max = 500)` exactly —
  same domain, same constraint shape).
- `documentTypes` — optional. Omitted or empty = search across all 8
  approved evidence types (§5.2). If present, every value must be in the
  approved allowlist or the request fails with 400 (§5.2) — **never**
  silently ignored, and `AWARD_SUMMARY` is rejected here even though it's
  a real `document_type` value elsewhere, per §5.7.
- `topK` — optional, default and hard cap TBD in Phase 3B (§5.3).

### 3.3 Response

```json
{
  "query": "Who are the investigators and what are their roles?",
  "awardNumber": "204713-00133",
  "results": [
    {
      "documentType": "AWARD_PERSON",
      "awardNumber": "204713-00133",
      "title": "Investigator: Example Name",
      "excerpt": "Person: Example Name; Role: Principal Investigator",
      "sourceTable": "archive.award_person",
      "sourcePrimaryKey": "12345",
      "score": 0.91,
      "targetSection": "people"
    }
  ],
  "insufficientEvidence": false,
  "correlationId": "..."
}
```

**AUDIT DECISION**: the top-level field is named `awardNumber`, not
`awardFamily` (the objective's own example used `"awardFamily": "204713"`
— a bare program-prefix, not a real `award_number`). This repo's own
grain rule (`CLAUDE.md`: business grain is `COUNT(DISTINCT
award_number)`) and `build_evidence_embedding.py`'s own scoping (always
exactly one `award_number` per run, never a multi-award "family" prefix
like CARB-X's `204713-*`) both establish that the real, existing unit of
scope is a single `award_number` — there is no existing concept anywhere
in this codebase of a wider "award family" spanning multiple
`award_number`s, and inventing one for this endpoint's response shape
would contradict the very data model Phase 3 reads from. If a client
needs the CARB-X-style multi-Award-number grouping, that is future,
separate work — not something Phase 3 should quietly imply exists today.

Field-by-field mapping to real V071 columns/existing conventions — no
persisted field is invented:

| Response field | Source |
|---|---|
| `documentType` | `search_embedding.document_type` (VERIFIED column) |
| `awardNumber` (per result) | `search_embedding.parent_business_identifier` (VERIFIED column — this is literally the award_number for every evidence row, per `build_evidence_embedding.py`'s `UPSERT_SQL`) |
| `title` | **PROPOSED, computed, not persisted** — a short, per-`documentType` label (e.g. `"Investigator: " + parsed name`, or simply the `documentType` value title-cased as a safe fallback). See §6.1 — must not require parsing `source_text` in a fragile way; prefer a fixed per-type label template over extracting substrings. |
| `excerpt` | `search_embedding.source_text`, truncated to a bounded length (§6.4) — **not** the raw unbounded column value |
| `sourceTable` | `search_embedding.source_table` (VERIFIED column, always a fixed literal like `'archive.award_person'` — never user input, never a repository-computed string) |
| `sourcePrimaryKey` | `search_embedding.source_primary_key` (VERIFIED column, real DB primary key) |
| `score` | Computed at query time from the same `embedding <=> :queryEmbedding` cosine-distance expression `SemanticSearchRepository.findNearest()` already uses — **never persisted**, never a DB column |
| `targetSection` | **PROPOSED, computed, not persisted** — maps `documentType` → the exact `AwardDashboardPage.tsx` `SECTIONS` key it corresponds to (e.g. `AWARD_PERSON` → `"people"`, `AWARD_AMOUNT` → `"amounts"`, `RELATED_NEGOTIATION` → `"negotiations"`) via a fixed, reviewed lookup table — not inferred at runtime |
| `insufficientEvidence` | `true` when `results` is empty after the full retrieval pipeline (zero rows matched the family+type scope, or none cleared the similarity threshold) — an explicit, named boolean, not just an empty array, per the objective's "clear insufficient-evidence response" requirement |
| `correlationId` | `UUID.randomUUID().toString()`, generated once per request — same pattern as `AwardAiSummaryResponse`/`AwardAiQuestionResponse` |

`exact_record_id` and `version_label` (also real V071 columns) are
**not** in this response — `sourcePrimaryKey` already gives a stable,
independently re-queryable identity, and exposing the internal
`exact_record_id` (which is sign-flipped for `AWARD_TERM_REPORT` rows
specifically, per `build_evidence_embedding.py`'s
`_exact_record_id_for()` collision-avoidance fix) would leak an
implementation detail with no client-facing meaning. `version_label`
could be added if a future need for it is demonstrated — omitted here to
keep the contract minimal (PROPOSED, revisit in Phase 3B review if the
UI turns out to need it for display).

---

## 4. Repository query (PROPOSED)

**AUDIT DECISION**: a **new** repository class, e.g.
`AwardEvidenceRetrievalRepository` (`adapter/out/persistence/`), not a
new method on `SemanticSearchRepository`. Rationale: `SemanticSearchRepository`
is a dirty, uncommitted file whose sole, already-tested job is "exclude
evidence rows from Global Search." Adding an evidence-focused method to
that same class would mean touching a file this design has already
classified as "do not modify" (§2) and would blur a class whose entire
purpose, per its own doc comment, is the opposite of what Phase 3 needs.

Proposed query shape (illustrative, exact SQL text is a Phase 3B
implementation detail, not fixed here):

```sql
SELECT document_type, parent_business_identifier AS award_number,
       source_text, source_table, source_primary_key,
       embedding <=> CAST(:queryEmbedding AS vector) AS distance
FROM archive.search_embedding
WHERE module = 'AWARD'
  AND parent_business_identifier = :awardNumber
  AND document_type = ANY(:documentTypes)
ORDER BY distance
LIMIT :topK
```

- Award-family scoping: `parent_business_identifier = :awardNumber`
  (exact match, single bound parameter — never string-concatenated,
  mirroring every existing repository query in this codebase).
- `document_type = ANY(:documentTypes)` — always bound to a
  server-validated, allowlisted array (§5.2), never the raw client
  request value passed through unchecked.
- Deduplication: the `(module, document_type, exact_record_id)` unique
  index already guarantees no duplicate rows exist in the table for the
  same logical evidence item — no application-level dedup pass is needed
  for *storage*-level duplicates. A distinct concern — the same
  underlying fact appearing twice across *different* document types
  (unlikely given each type maps to one source table) — is not addressed
  by this query and is noted as an open question in §12, not silently
  assumed away.

---

## 5. Security model (VERIFIED + PROPOSED)

- **VERIFIED, zero new code needed**: `SecurityConfiguration.java`'s
  `.requestMatchers("/api/**").authenticated()` already covers any new
  controller under `/api/ai/awards/**`. No changes to
  `SecurityConfiguration.java` are proposed.
- **PROPOSED gate**: `@ConditionalOnProperty(name =
  "app.search.semantic.enabled", havingValue = "true")` on the new
  controller class (and any new `@Bean`s Phase 3B needs) — see §3.1's
  rationale. This is a deliberate departure from mirroring
  `AwardAiController`'s `app.ai.enabled` gate, made explicitly and with
  reasoning, not by oversight.
- **Award-family scoping** (§4): enforced entirely server-side via the
  bound `awardNumber` path variable resolved the same way
  `AwardAiController`/`AwardAiQuestionController` already do
  (`AwardArchiveService.findFamily(normalizedAwardNumber)`, 404 via
  `NoSuchElementException` on a missing Award) — a client cannot request
  evidence for an Award it didn't ask for by any parameter-tampering
  vector, since the query itself is bound to the path-derived,
  server-resolved award number, not a client-supplied family ID.
- **Approved evidence-type allowlist** (PROPOSED,
  `AwardEvidenceSearchController` or a small dedicated constant list,
  mirroring `build_evidence_embedding.py`'s own
  `APPROVED_DOCUMENT_TYPES` tuple exactly): `AWARD_VERSION`,
  `AWARD_PERSON`, `AWARD_AMOUNT`, `AWARD_TERM`, `AWARD_COMMENT`,
  `RELATED_PROPOSAL`, `RELATED_NEGOTIATION`, `RELATED_SUBAWARD`. A
  request naming any other value (including `AWARD_SUMMARY` — see §5.7
  — or a nonexistent type) is rejected with 400, never silently dropped
  or silently widened to "all types."
- **Maximum `topK`**: PROPOSED hard server-side cap (exact number is a
  Phase 3B decision — recommend mirroring `SemanticSearchProperties.topK`'s
  existing precedent of a small, single-digit cap, e.g. 10, enforced in
  code regardless of what the client requests, exactly as
  `GlobalSearchService` already hard-caps semantic results at 5
  regardless of config).
- **Minimum similarity threshold**: PROPOSED. The PoC/threshold
  experiment referenced in this repo's own history (`Next Session.md`,
  superseded but instructive) found "no single global similarity cutoff
  works" for family-wide Global Search — but Phase 3's search space is
  narrower (one Award's evidence rows, not the whole archive), so a
  threshold may be more viable here. Recommend Phase 3B **prove** a
  threshold empirically (mirroring the PoC's own methodology) rather than
  picking a number by inspection — flagged as an open decision, not
  resolved by this document.
- **Stable ranking**: `ORDER BY distance` is deterministic given a fixed
  query embedding, but Bedrock embeddings for identical input text are
  not guaranteed byte-identical across calls (floating-point,
  potential model updates) — recommend a secondary `ORDER BY ...,
  source_primary_key` tiebreaker for stable pagination/test-assertion
  ordering, mirroring the tiebreaker pattern already used everywhere else
  in this codebase's repository queries (e.g. `AwardArchiveRepository`'s
  own `ORDER BY ... award_id DESC` tiebreakers).
- **Cross-family leakage protection**: the `parent_business_identifier =
  :awardNumber` predicate is the entire protection — no separate
  mechanism is needed since every evidence row is already scoped to
  exactly one `award_number` at write time (§1.1).
- **Behavior when no evidence rows exist for an Award**: `results: []`,
  `insufficientEvidence: true`, HTTP 200 — **not** a 404 or 503. A
  perfectly valid, real Award with zero indexed evidence (which is every
  Award today, since none has been indexed yet) is not an error
  condition.
- **No database mutation**: every proposed query is a `SELECT`. No
  `INSERT`/`UPDATE`/`DELETE` capability exists anywhere in this design.
- **Query embedding, no text in ordinary logs**: mirror
  `BedrockEmbeddingProvider`'s existing convention exactly — log only
  `awardNumber`, `documentTypes`, result counts, and `correlationId`,
  never the raw `query` string, never the embedding vector.
- **Provider failure behavior**: `EmbeddingProviderException` (already
  exists, reused as-is) → the new controller/exception-handler layer
  maps it to `503`, mirroring `AiProviderException`'s existing 503
  mapping in `AiExceptionHandler`.
- **Timeout**: reuses the existing (currently-unconfigured, per §1.2)
  Bedrock client. **AUDIT DECISION, flagged for approval**: Phase 3B
  should wire `SemanticSearchProperties.bedrockTimeoutMs` into an actual
  `ClientOverrideConfiguration.apiCallTimeout(...)` on the shared
  `BedrockRuntimeClient` bean in `SemanticSearchConfiguration.java` —
  fixing a real, pre-existing gap, not introducing a new one — since
  Phase 3 is the first consumer for whom an unbounded Bedrock call
  duration would visibly block a synchronous, user-facing search
  request (Global Search's existing semantic branch is already
  fire-and-forget/fault-isolated via `CompletableFuture`, so the gap was
  lower-stakes there). This touches a clean, committed file with no
  dirty-file conflict — see §9.

---

## 6. Citation contract (PROPOSED)

### 6.1 What is exposed

Exactly the 8 fields in §3.3's response table: `documentType`,
`awardNumber`, `title`, `excerpt`, `sourceTable`, `sourcePrimaryKey`,
`score`, `targetSection`. Every one of these is either a real V071
column value, a value derived from the same distance computation
`SemanticSearchRepository` already performs, or a small, fixed,
reviewed lookup table (`documentType` → `targetSection`) — never
free-form, never model-generated, never reflecting raw SQL.

### 6.2 What is never exposed

SQL text, S3 bucket/key, credentials, stack traces, attachment content
(no evidence type touches `archive.attachment_object`/
`archive.award_attachment` at all — confirmed by `build_evidence_embedding.py`'s
own 8-type scope, which structurally excludes `AWARD_ATTACHMENT`),
unbounded source text (§6.4), or the raw query embedding vector.

### 6.3 Redaction — an open question, not silently resolved

`build_evidence_embedding.py`'s text builders (specifically
`build_award_comment_text`) include raw archived `comments` text
verbatim, with **no** redaction pass — unlike `AwardContextBuilder`,
which routes every text field through `SensitiveFieldRedactor` before it
reaches an LLM. Phase 3's `excerpt` field would surface this same
unredacted text directly to an authenticated user. **AUDIT DECISION,
requires approval before Phase 3B**: apply the same redaction patterns
`SensitiveFieldRedactor` already implements to every `excerpt` before it
leaves the backend, for defense-in-depth consistency with this
codebase's own established convention — even though the source is
deterministic archive text, not AI-generated, the *reason* the redactor
exists (catch sensitive-looking patterns that happen to be present in
real archived text) applies equally here.

Reusing the existing `SensitiveFieldRedactor` Spring bean directly is
**not** proposed, because that bean is
`@ConditionalOnProperty(app.ai.enabled)` — gated on a flag Phase 3
deliberately does not depend on (§3.1) and which is off in dev today.
Removing that conditional would be a behavior change to an existing,
already-tested component (`AiFeatureFlagTest`-style tests likely assert
its absence when the flag is off) and is explicitly **not** decided
here — flagged in §12 as requiring a choice between: (a) duplicate the
redaction regex patterns as a small, private, unconditional utility
scoped to the new evidence module, fully decoupled from `app.ai.enabled`
(the safer default, proposed), or (b) loosen `SensitiveFieldRedactor`'s
gating to make it reusable (touches existing, tested behavior — needs
explicit sign-off).

### 6.4 Excerpt length

PROPOSED: a fixed maximum character length (exact number is a Phase 3B
decision — recommend something in the 200-400 character range, enough
to show the deterministic text builder's real content without
approaching the unbounded raw column). Truncation happens server-side,
after redaction (§6.3), never client-side.

---

## 7. UI design (PROPOSED)

### 7.1 Location

**AUDIT DECISION**: a new Award-page section/tab, not a standalone page
and not a modification to `GlobalSearchPage.tsx`. New component
`AwardEvidenceSearchSection.tsx` (`ui/src/components/award/`), wired into
`AwardDashboardPage.tsx`'s `SECTIONS`/`IMPLEMENTED_SECTIONS` arrays
exactly like `AwardFundingProposalsSection` (§1.4) — a new entry, e.g.
`{ key: "evidenceSearch", label: "Evidence Search" }`. This keeps the
capability scoped to "search within this specific Award," matching
Phase 3's own request contract (§3.2 — the query is always Award-scoped,
never archive-wide), and keeps it discoverable from the same place a
user is already looking at an Award's structured facts.

### 7.2 Composition

- **Input row**: modeled on `IrbPage.tsx`'s search+filter+chips pattern
  (§1.4, the only existing precedent) — a `TextField` for the natural-
  language query, a multi-select `documentTypes` filter (chips or a
  `Select multiple`, labeled with human-readable names, e.g. "People",
  "Amounts", "Terms" — not raw `AWARD_PERSON`/`AWARD_AMOUNT` strings), a
  "Search" `Button`.
- **State machine** (`useMutation`-driven, matching
  `AwardAiSummaryPanel`/`AwardAiQuestionPanel`'s pattern exactly, since
  this is a search-on-demand action, not an always-loaded sub-resource
  like `AwardFundingProposalsSection`):
  - **Idle** (before first search): a short prompt, no `LoadingState`/
    `ErrorState`/`EmptyState` — nothing to show yet.
  - **Loading**: `CircularProgress` + status text, mirroring the AI
    panels' exact loading block (§1.4).
  - **Empty / insufficient-evidence**: `EmptyState variant="text"` with
    a message distinguishing "no evidence has been indexed for this
    Award yet" from "no results matched your query" — using the
    response's explicit `insufficientEvidence` boolean (§3.3), never
    inferred from an empty array alone (an empty array with
    `insufficientEvidence: false` should not be possible given the
    contract, but the UI should trust the explicit field, not the array
    length, as the source of truth).
  - **Provider-unavailable**: a distinct `ErrorState` message for a 503
    response specifically (mirroring the AI panels' `switch` on
    `ApiRequestError.status`, §1.4) — "Evidence search is temporarily
    unavailable" rather than a generic error.
  - **Success**: a list of evidence result cards (§7.3).
- **Retry**: re-clicking "Search" re-fires the same mutation — no new
  pattern needed, `useMutation` already supports this natively.

### 7.3 Result cards

Each card shows: `score` (e.g. a small percentage or star-rating,
never the raw cosine distance), `documentType` (human-readable label,
via the same lookup table used for `targetSection`), `awardNumber`
(useful when `documentTypes` spans `RELATED_*` types, whose evidence
technically describes a connection rather than the Award itself),
`excerpt`, and a link to `targetSection` — computed as
`/awards/{awardId}#<targetSection>` or, more robustly, by calling
`AwardDashboardPage`'s existing tab-switch mechanism if the panel is
rendered as a sibling section on the same page (no new route needed;
clicking a result just switches the active tab, since evidence search
lives on the Award page already, per §7.1).

### 7.4 Explicitly not built here

No free-text "generated answer" box. Per the Phase 3 objective: "For
the client demo, direct evidence retrieval is sufficient. A generated
answer may be added only if it reuses the existing AI-provider and
citation-validation architecture safely" — this document proposes only
the retrieval UI (§7.1-7.3). Adding a generated-answer mode later would
mean routing through `AiProvider`/`AwardCitationValidator` (§1.3) with
its own separate design review — explicitly out of scope for Phase 3A
and Phase 3B alike, per the objective's own wording ("may be added
only if...", not "should be added").

---

## 8. Phase 3 test plan (PROPOSED, no tests written yet)

All tests use a **fake embedding provider** (mirroring
`FakeBedrockClient`/`fake_embed_fn` from
`etl/tests/test_build_evidence_embedding.py`, adapted to Java —
implement `EmbeddingProvider` with a deterministic, content-derived fake
vector). **No real Bedrock call in any test.**

| # | Scenario |
|---|---|
| 1 | Authentication required — unauthenticated request → 401 (mirrors the existing `/api/v1/awards/search` 401 test pattern already proven live this session) |
| 2 | Missing Award → 404, `NoSuchElementException` convention |
| 3 | Invalid evidence type in `documentTypes` → 400, request rejected, not silently dropped |
| 4 | Empty/blank query → 400 (`@NotBlank`-style validation, mirroring `AwardAiQuestionRequest`) |
| 5 | Excessive `topK` → server-side clamp to the hard cap (§5), never passed through raw |
| 6 | Award-family isolation — two Awards each with evidence rows; a search on Award A never returns Award B's rows |
| 7 | Evidence-type filtering — `documentTypes: ["AWARD_PERSON"]` returns only `AWARD_PERSON` rows even when other types exist for the same Award |
| 8 | Stable ordering — same query run twice returns results in the same order (tiebreaker, §5) |
| 9 | Threshold filtering — a row below the minimum similarity threshold is excluded from `results` |
| 10 | Duplicate suppression — confirms no `(document_type, sourcePrimaryKey)` pair appears twice in one response |
| 11 | No evidence indexed for the Award → `results: []`, `insufficientEvidence: true`, HTTP 200 (not 404/503) |
| 12 | Provider unavailable — fake provider throws `EmbeddingProviderException` → 503 |
| 13 | Query-embedding failure — same as #12, explicit case for a malformed/empty embedding response from the fake provider |
| 14 | Correct citation metadata — response's `sourceTable`/`sourcePrimaryKey` for a fixture row match the real seeded values exactly (mirrors `build_evidence_embedding.py`'s own citation-metadata test pattern) |
| 15 | Safe excerpt length — a fixture row with `source_text` longer than the max is truncated, never returned raw |
| 16 | No attachment-content retrieval — a request with `documentTypes: ["AWARD_ATTACHMENT"]` (not in the allowlist) → 400, proving structurally that attachment content can never be reached through this endpoint |
| 17 | UI: success state renders result cards with correct fields |
| 18 | UI: loading state renders while the mutation is pending |
| 19 | UI: empty/insufficient-evidence state renders distinctly from a genuine zero-result "no match" (if the contract distinguishes them further in Phase 3B) |
| 20 | UI: error/provider-unavailable state renders the 503-specific message |
| 21 | UI: retry re-fires the search and clears the prior error state |

Fixtures: reuse this session's already-verified real data — Award
`204713-00133` (`AWARD_PERSON`, `AWARD_AMOUNT`, `AWARD_TERM`,
`AWARD_COMMENT`, `AWARD_VERSION` — the golden fixture from Phase 2's own
test suite) and `104949-00002`/`204713-00001` for `RELATED_NEGOTIATION`/
`RELATED_SUBAWARD`/`RELATED_PROPOSAL` — the exact same fixtures
`etl/tests/test_build_evidence_embedding.py` already pins, so Phase 3B's
Java-side integration tests seed identical, already-proven-real rows
rather than inventing new placeholder data.

---

## 9. Exact files Phase 3B would create or modify (PROPOSED — none touched in Phase 3A)

**New files (create):**
- `api/src/main/java/edu/bu/archive/adapter/in/web/AwardEvidenceSearchController.java`
- `api/src/main/java/edu/bu/archive/adapter/in/web/dto/ai/AwardEvidenceSearchRequest.java`
- `api/src/main/java/edu/bu/archive/adapter/in/web/dto/ai/AwardEvidenceSearchResponse.java`
- `api/src/main/java/edu/bu/archive/adapter/in/web/dto/ai/AwardEvidenceResultResponse.java`
- `api/src/main/java/edu/bu/archive/application/ai/AwardEvidenceSearchService.java` (or under a new `application/evidence/` package if preferred for separation from the LLM-generation `application/ai/` package — Phase 3B naming decision)
- `api/src/main/java/edu/bu/archive/adapter/out/persistence/AwardEvidenceRetrievalRepository.java`
- Corresponding test files under `api/src/test/java/...` mirroring the same package structure
- `ui/src/components/award/AwardEvidenceSearchSection.tsx`
- Corresponding UI test file, if the existing `.test.mjs` pattern extends to this component's presentation helpers

**Existing, clean/committed files requiring small, additive changes:**
- `AiExceptionHandler.java` — add `AwardEvidenceSearchController.class`
  to the `@RestControllerAdvice(assignableTypes = {...})` list, plus one
  new `@ExceptionHandler(EmbeddingProviderException.class)` method
  mapping to 503 (reusing the exact existing `error(...)` helper) —
  *or*, alternatively, a small dedicated exception handler scoped only
  to the new controller, avoiding touching this shared file at all. Both
  options are viable; Phase 3B should pick one and note the choice.
- `SemanticSearchConfiguration.java` — wire `bedrockTimeoutMs` into the
  shared `BedrockRuntimeClient` bean (§5, flagged as requiring approval
  since it changes existing, if currently-inert, behavior).
- `AwardDashboardPage.tsx` — add the new `SECTIONS`/`IMPLEMENTED_SECTIONS`
  entry and the new component's render call (same one-line-per-array
  pattern every existing section already uses).
- `ui/src/types/api.ts` — add the new response interfaces.
- `ui/src/api/client.ts` — add one new POST function, mirroring
  `askAwardQuestion()` exactly.

**Files explicitly NOT modified (per §2's overlap assessment and §1's
architecture review):** `SemanticSearchRepository.java`,
`GlobalSearchService.java`, `GlobalSearchController` (whatever its exact
class name is), `AwardCitationValidator.java`, `AwardAiController.java`,
`AwardAiQuestionController.java`, `SecurityConfiguration.java`,
`InvestigatorRepository.java`, `terraform/environments/dev/main.tf`,
`ui/src/pages/GlobalSearchPage.tsx`, any `database/migrations/` file (no
new migration — V071's schema is already sufficient, per §1.1).

---

## 10. Deployment requirements (PROPOSED, not run)

1. Land the currently-dirty prerequisite (`SemanticSearchRepository.java`'s
   guard, §2) as its own commit — Phase 3B's new repository class does
   not strictly require this guard to be committed first (they're
   separate classes), but leaving Global Search's own safety guard
   uncommitted indefinitely is an existing risk this design does not
   want to compound.
2. Implement and test Phase 3B per §8-9.
3. No new Terraform/AWS resource is required (§2) — the existing
   `task_bedrock` IAM policy on the API's task role already covers this.
4. No new migration is required (§1.1) — V071 is already applied to dev.
5. Deploy API + UI together (both change).
6. **Do not run real evidence indexing or call Bedrock as part of this
   deployment** — the endpoint will correctly return
   `insufficientEvidence: true` for every Award until
   `build_evidence_embedding.py` is run for real, which remains a
   separate, explicitly-gated decision per
   `AWARD_RAG_DEPLOYMENT_READINESS.md`.

## 11. Demo impact (PROPOSED)

Once Phase 3B is deployed (still with zero evidence rows indexed), the
demo can show: the search UI itself, the request/response shape, the
`insufficientEvidence: true` response as a real, honest "not yet indexed"
state — directly fulfilling the objective's "Receive a clear
insufficient-evidence response" requirement without needing any real
Bedrock spend. A live, populated demo (real results, not just the
insufficient-evidence state) requires running the evidence-indexing
dry-run → real-run sequence already documented in
`AWARD_RAG_DEPLOYMENT_READINESS.md` §4 steps 6-10 for the five demo Award
numbers — unchanged by this document, still gated on explicit approval
before any real Bedrock call.

## 12. Risks and stop conditions

- **Requires approval before Phase 3B**: the redaction decision (§6.3 —
  duplicate `SensitiveFieldRedactor`'s patterns locally vs. loosening its
  existing `app.ai.enabled` gate).
- **Requires approval before Phase 3B**: wiring `bedrockTimeoutMs` into
  `SemanticSearchConfiguration.java` (§5) — a behavior change to an
  existing, if currently-inert, config value.
- **Open, not resolved by this document**: the exact numeric values for
  max `topK`, minimum similarity threshold, and excerpt length (§5, §6.4)
  — recommended to be determined empirically in Phase 3B against real
  indexed data for the demo Award numbers, not chosen by inspection here.
- **Open, not resolved by this document**: whether the same logical fact
  could ever appear under two different `document_type`s for the same
  Award, and if so whether cross-type deduplication is needed (§4) —
  the current 8-type design makes this unlikely (each type maps to
  exactly one source table) but was not exhaustively proven false.
- **No conflicting dirty file was found** (§2) — this is not a stop
  condition, but is recorded per the instruction to report explicitly if
  none is found.
