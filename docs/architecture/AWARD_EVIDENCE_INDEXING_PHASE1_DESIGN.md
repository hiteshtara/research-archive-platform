# Award Evidence Indexing — Phase 1 Implementation Design

**Status:** Design only. No migration applied, no ETL code written, no
chatbot. Source of truth is
[`AWARD_RAG_EVIDENCE_GRAPH_AUDIT.md`](AWARD_RAG_EVIDENCE_GRAPH_AUDIT.md)
— this document is not a re-audit; it designs Phase 1 implementation on
top of that audit's conclusions and only revisits them where
implementation-level detail surfaced something the audit didn't reach
(see "Contradicts/refines the audit" callouts below).

**Scope for Phase 1** (per instruction — the READY document types only):
`AWARD_SUMMARY`, `AWARD_VERSION`, `AWARD_PERSON`, `AWARD_AMOUNT`,
`AWARD_COMMENT`, `AWARD_TERM`, `RELATED_PROPOSAL`, `RELATED_NEGOTIATION`,
`RELATED_SUBAWARD`. Excluded: `AWARD_ATTACHMENT_CONTENT`,
`SAP_TRANSMISSION`. Budget and Time & Money are **not** in this phase at
all (family-scoped, `version_label = NULL` — deferred to a later phase
per the audit's caveat that they must never be attached to a historical
version unless proven).

**Fixture:** `204713-00133` (award_id `3187665`) throughout, per
instruction — the golden fixture, with financial semantics now
trustworthy end to end.

**A note on data access for this document:** AWS credentials expired
mid-session (Shibboleth-assumed-role token timeout, an interactive SSO
step outside this session's control) partway through gathering fixture
rows, then were refreshed and the query completed. Every fixture example
in section 4 below is now real, live-queried data for `204713-00133` —
none invented. One genuine finding from that query: **`204713-00133` has
zero linked Proposals, Negotiations, or Subawards** (all three
relationship queries returned empty) — see the callout in section 4.

---

## 1. Migration design

Additive only, on the existing `archive.search_embedding` (V070) — no
new table, no new vector store.

```sql
-- V0NN__extend_search_embedding_for_evidence_documents.sql

ALTER TABLE archive.search_embedding
    ADD COLUMN IF NOT EXISTS document_type             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_module              VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_business_identifier VARCHAR(255),
    ADD COLUMN IF NOT EXISTS exact_record_id            BIGINT,
    ADD COLUMN IF NOT EXISTS version_label              VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_table               VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_primary_key         BIGINT,
    ADD COLUMN IF NOT EXISTS source_row_hash             VARCHAR(64);

-- Backfill existing Global Search summary rows so document_type is
-- never NULL for a row that predates this migration. One UPDATE per
-- module - all four already only ever contain current/family-grain
-- summary rows (see build_search_embedding.py's DOMAIN_QUERIES), so
-- the mapping is unambiguous and lossless.
UPDATE archive.search_embedding SET document_type = 'AWARD_SUMMARY',       parent_module = 'AWARD'       WHERE module = 'AWARD'       AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'PROPOSAL_SUMMARY',    parent_module = 'PROPOSAL'    WHERE module = 'PROPOSAL'    AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'NEGOTIATION_SUMMARY', parent_module = 'NEGOTIATION' WHERE module = 'NEGOTIATION' AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'SUBAWARD_SUMMARY',   parent_module = 'SUBAWARD'    WHERE module = 'SUBAWARD'    AND document_type IS NULL;

-- record_id IS the exact record for every existing summary row (see
-- DOMAIN_QUERIES' own comment: "record_id is already the canonical
-- current/family identifier"), so exact_record_id backfills verbatim.
UPDATE archive.search_embedding
SET exact_record_id = record_id,
    parent_business_identifier = business_number
WHERE exact_record_id IS NULL;

-- The existing uniqueness constraint assumed one row per (module,
-- record_id) - true only because every existing row is a summary.
-- Widening it to include document_type is what actually allows
-- multiple document types per family without a separate table.
DROP INDEX IF EXISTS archive.ix_search_embedding_record;
CREATE UNIQUE INDEX IF NOT EXISTS ix_search_embedding_module_type_record
    ON archive.search_embedding (module, document_type, exact_record_id);

-- Evidence retrieval will filter/join on these constantly - separate,
-- narrower indexes rather than widening the existing canonical_family
-- index, so Global Search's own family-lookup query plan is untouched.
CREATE INDEX IF NOT EXISTS ix_search_embedding_document_type
    ON archive.search_embedding (document_type);
CREATE INDEX IF NOT EXISTS ix_search_embedding_parent
    ON archive.search_embedding (parent_module, parent_business_identifier);
CREATE INDEX IF NOT EXISTS ix_search_embedding_source_row
    ON archive.search_embedding (source_table, source_primary_key);
```

**Why additive-only is safe:** `GlobalSearchService`'s semantic branch
only ever reads `module`, `record_id`, `canonical_family_id`,
`business_number`, `embedding` — none of the new columns. Every existing
query keeps working unmodified; new columns are simply invisible to it
until (if ever) Global Search itself is changed to filter by
`document_type`.

**Uniqueness constraint risk, called out explicitly:** widening
`(module, record_id)` → `(module, document_type, exact_record_id)` is
the one genuinely structural part of this migration — a wrong backfill
here would let a future evidence-document upsert silently collide with
or shadow a Global Search summary row. The backfill above is written to
run before the index swap so the old unique index still protects the
table during backfill, and the new index is created before the old one
would be needed again.

---

## 2. Document-builder design

Mirrors `build_search_embedding.py`'s existing shape exactly
(`DOMAIN_QUERIES` dict → per-type SQL, `build_source_text` → per-type
text builder, `source_hash`/`EXISTING_HASH_SQL` → unchanged, `UPSERT_SQL`
→ gains the new columns). Each type below: source SQL (real, copied from
`AwardArchiveRepository.java`'s existing queries where one already
exists), the deterministic text template, and the provenance field
mapping.

### `AWARD_SUMMARY`
Already implemented — no change. `parent_module = NULL` (it IS the
parent), `parent_business_identifier = award_number`,
`exact_record_id = award_id` (current version only), `version_label =
NULL` (current-flagged, not a specific historical version by design),
`source_table = 'archive.award_version'`, `source_primary_key =
award_id`.

### `AWARD_VERSION`
```sql
SELECT award_id AS exact_record_id, award_number, sequence_number,
       title, status_description, sponsor_name, lead_unit_name,
       award_effective_date, begin_date, closeout_date,
       workflow_document_number
FROM archive.award_version
WHERE award_number = :award_number
```
**Text template:**
`"Award {award_number} version {sequence_number} (document
{workflow_document_number}): {title}. Sponsor: {sponsor_name}. Lead
unit: {lead_unit_name}. Status: {status_description}. Effective
{award_effective_date}, begins {begin_date}"` (+ `, closes
{closeout_date}` if present).
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = award_id`, `version_label =
sequence_number`, `source_table = 'archive.award_version'`,
`source_primary_key = award_id`.
**One doc per row** — all 125 versions of `204713-00133`, not just
current.

### `AWARD_PERSON`
```sql
SELECT award_person_id AS exact_record_id, award_id, award_number,
       full_name, contact_role_code, key_person_project_role
FROM archive.award_person ap
JOIN archive.award_version av ON av.award_id = ap.award_id
WHERE av.award_number = :award_number
```
**Text template:** `"{full_name} — {key_person_project_role} ({role})
on Award {award_number} version {sequence_number}"` (role = `contact_role_code`).
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = award_person_id`, `version_label =
sequence_number` (resolved via the joined `award_id`), `source_table =
'archive.award_person'`, `source_primary_key = award_person_id`.

### `AWARD_AMOUNT`
```sql
SELECT award_amount_info_id AS exact_record_id, award_id, award_number,
       sequence_number, obligated_total_amount, anticipated_total_amount,
       tnm_document_number
FROM archive.award_amount_info
WHERE award_number = :award_number
```
**Text template:** `"Award {award_number} version {sequence_number},
amount record {award_amount_info_id}{, document {tnm_document_number} if
present}: obligated ${obligated_total_amount}, anticipated
${anticipated_total_amount}"`.
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = award_amount_info_id`, `version_label
= sequence_number`, `source_table = 'archive.award_amount_info'`,
`source_primary_key = award_amount_info_id`.
**Critical:** ordering/selection for "current" must use the corrected
rule — `MAX(award_amount_info_id)` only, never `source_version_number`
— but this document type embeds **every row**, not just current, so the
rule only matters for which row gets labeled current in the text, not
for which rows get indexed at all.

### `AWARD_COMMENT`
```sql
SELECT award_comment_id AS exact_record_id, award_id, award_number,
       sequence_number, comment_type_code, comments
FROM archive.award_comment
WHERE award_number = :award_number AND comments IS NOT NULL
```
**Text template:** `"Comment ({comment_type_code}) on Award
{award_number} version {sequence_number}: {comments}"`.
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = award_comment_id`, `version_label =
sequence_number`, `source_table = 'archive.award_comment'`,
`source_primary_key = award_comment_id`.
**Note (does not affect this phase, in scope for later):** `award_notepad`
is the free-text sibling table but has no `sequence_number` at all — per
the audit, it needs `version_label = NULL` and is deliberately excluded
from `AWARD_COMMENT` in Phase 1 to avoid conflating a version-scoped
document type with a family-scoped one. If notepad is wanted, it should
be its own `AWARD_NOTEPAD` type in a later phase, not folded into this one.

### `AWARD_TERM`
```sql
-- Sponsor terms
SELECT award_sponsor_term_id AS exact_record_id, award_id, award_number,
       sequence_number, sponsor_term_id
FROM archive.award_sponsor_term
WHERE award_number = :award_number
-- Report terms
SELECT award_report_term_id AS exact_record_id, award_id, award_number,
       sequence_number, report_class_code, report_code, frequency_code,
       due_date
FROM archive.award_report_term
WHERE award_number = :award_number
```

> **Contradicts/refines the audit.** The audit marked `AWARD_TERM`
> READY without inspecting text content. It is structurally READY
> (clean version-scoped grain, real PK/FK), but **`archive.award_sponsor_term`
> stores only a numeric `sponsor_term_id` with no joined description
> anywhere in the schema** — confirmed directly against
> `findSponsorTerms()` (`AwardArchiveRepository.java`), which returns
> just the raw ID, and `AwardTermsSection.tsx`, which renders it as the
> literal label `"Sponsor Term {id}"`. `archive.award_report_term` is
> similarly all codes (`report_class_code`, `report_code`,
> `frequency_code`) with no reference-table join for descriptions
> anywhere in this repository. **Recommendation:** still build
> `AWARD_TERM` in Phase 1 — the codes are real, exact-match-queryable
> provenance and cost nothing to index — but do not expect it to
> contribute meaningfully to *semantic* (natural-language) retrieval
> until a sponsor/report term code-to-description reference table
> exists. This is exactly the kind of implementation-level finding the
> audit couldn't surface without going as deep as the document-builder
> design; it doesn't invalidate the audit's grain/provenance
> conclusions, only adds a text-quality caveat.

**Text template:** `"Sponsor term {sponsor_term_id} on Award
{award_number} version {sequence_number}"` /
`"Report term: class {report_class_code}, code {report_code}, frequency
{frequency_code}, due {due_date}, on Award {award_number} version
{sequence_number}"`.
**Provenance:** `source_table = 'archive.award_sponsor_term'` or
`'archive.award_report_term'` respectively; otherwise same shape as
`AWARD_COMMENT`.

### `RELATED_PROPOSAL`
```sql
SELECT proposal.award_funding_proposal_id AS exact_record_id,
       proposal.award_id, award.award_number, award.sequence_number,
       linked_proposal.proposal_number, linked_proposal.title,
       proposal.active_flag
FROM archive.award_funding_proposal proposal
JOIN archive.award_version award ON award.award_id = proposal.award_id
JOIN archive.proposal_version linked_proposal
    ON linked_proposal.proposal_id = proposal.proposal_id
WHERE award.award_number = :award_number
```
**Text template:** `"Award {award_number} version {sequence_number} is
funded by Proposal {proposal_number}: {title}"` (+
`" (inactive relationship)"` if `active_flag` is not truthy).
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = award_funding_proposal_id`,
`version_label = sequence_number`, `source_table =
'archive.award_funding_proposal'`, `source_primary_key =
award_funding_proposal_id`. This is a relationship **edge** — it does
not replace the Proposal's own `AWARD_SUMMARY`-equivalent, which is
embedded independently under `module = 'PROPOSAL'`.

### `RELATED_NEGOTIATION`
```sql
SELECT negotiation_id AS exact_record_id, document_number,
       negotiation_agreement_type_description, negotiator_full_name,
       negotiation_status_description
FROM archive.negotiation
WHERE negotiation_association_type_code = 'AWD'
  AND associated_document_id = :award_number
```
**Text template:** `"Negotiation {document_number}
({negotiation_agreement_type_description}) associated with Award
{award_number}, negotiator {negotiator_full_name}, status
{negotiation_status_description}"`.
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = negotiation_id`, **`version_label =
NULL`** — per the existing code comment, Negotiation "has no
version/family concept of its own," so there is no version to label.
`source_table = 'archive.negotiation'`, `source_primary_key =
negotiation_id`.

### `RELATED_SUBAWARD`
```sql
SELECT funding.subaward_funding_source_id AS exact_record_id,
       linked_subaward.subaward_code, current_subaward.status_description,
       current_subaward.document_number
FROM archive.subaward_funding funding
JOIN archive.subaward linked_subaward ON linked_subaward.subaward_id = funding.subaward_id
LEFT JOIN archive.subaward current_subaward
    ON current_subaward.subaward_code = linked_subaward.subaward_code
    AND current_subaward.subaward_sequence_status = 'ACTIVE'
WHERE funding.award_number = :award_number
```
**Text template:** `"Subaward {subaward_code} (document
{document_number}) is linked to Award {award_number}, status
{status_description}"`.
**Provenance:** `parent_module = 'AWARD'`, `parent_business_identifier =
award_number`, `exact_record_id = subaward_funding_source_id`,
`version_label = NULL` (the link is family-wide by `award_number`, per
`findFundingSubwardRows`'s own `WHERE funding.award_number =
:awardNumber` — no `award_id` scoping), `source_table =
'archive.subaward_funding'`, `source_primary_key =
subaward_funding_source_id`.

---

## 3. Indexing/ETL pipeline

New script `etl/build_evidence_embedding.py`, structured as a direct
extension of `etl/build_search_embedding.py` — same idempotency
mechanism, same ECS one-off orchestration, same CLI shape:

```
DOCUMENT_TYPE_QUERIES: dict[str, str]   # one entry per type in section 2
    # key = document_type string, value = the SQL above (parameterized
    # by :award_number for Phase 1's award-scoped run mode)

def build_evidence_text(document_type: str, row: Mapping) -> str:
    # dispatches to the per-type template in section 2 - one function
    # per type, not a single generic formatter, so a future template
    # change to one type can never silently affect another

def source_row_hash(source_text: str) -> str:
    return hashlib.sha256(source_text.encode("utf-8")).hexdigest()
    # identical mechanism to today's source_hash, renamed only to match
    # the new column name - genuinely the same idempotency guarantee

def populate_evidence(engine, bedrock_client, award_number, dry_run):
    for document_type, sql in DOCUMENT_TYPE_QUERIES.items():
        rows = <run sql with :award_number>
        for row in rows:
            text_value = build_evidence_text(document_type, row)
            hash_value = source_row_hash(text_value)
            existing = <SELECT source_row_hash FROM archive.search_embedding
                         WHERE module = 'AWARD' AND document_type = :dt
                         AND exact_record_id = :id>
            if existing == hash_value:
                skip  # unchanged - identical skip logic to today
                continue
            if dry_run:
                print the row instead of embedding
                continue
            embedding = embed_text(bedrock_client, text_value)  # same Bedrock call, unchanged
            UPSERT into archive.search_embedding (
                module, document_type, record_id, canonical_family_id,
                business_number, parent_module, parent_business_identifier,
                exact_record_id, version_label, source_table,
                source_primary_key, source_row_hash, source_text,
                embedding, embedding_model
            )
            ON CONFLICT (module, document_type, exact_record_id) DO UPDATE ...
```

**Key design decisions:**
- **`record_id` / `canonical_family_id` stay populated for every
  evidence row too** (both set to the resolved `award_id` of the
  current version for that `award_number`, or the specific version's
  `award_id` for `AWARD_VERSION`/`AWARD_PERSON`/etc.) — so an evidence
  row can still participate in Global Search's existing
  family-level dedup key (`module:(awardId or recordId)`) without any
  change to `GlobalSearchService.deduplicate()`. This is what
  "preserve compatibility" means concretely, not just "don't drop
  columns."
- **Award-scoped run mode for Phase 1**, not full-population — the
  script takes `--award-number` (mirroring `postgres_award_performance.py`'s
  own `--award-id`/`--award-number` pattern from this session's earlier
  work) rather than looping every Award in the archive. Full-archive
  population is a deliberate, separate, later decision (real Bedrock
  cost at ~13x today's summary-only volume, going by the ~24.5K
  summary-row baseline from the semantic-search integration plan) —
  out of scope for "design Phase 1," which is about proving the shape
  works on one fixture first.
- **Skip logic is per-row, not per-type** — a single unchanged
  `AWARD_PERSON` row doesn't block re-embedding a changed
  `AWARD_COMMENT` row in the same run, identical to today's per-record
  behavior in `build_search_embedding.py`.
- **ECS orchestration**: new `scripts/run-evidence-embedding.sh`,
  copying `run-search-embedding.sh`'s exact structure (build/push
  loader image, register task-def revision, `run-task` with
  `--award-number` in the container override command, poll, tail logs,
  exit-code passthrough).

---

## 4. Fixture examples — `204713-00133` (all real, live-queried)

### `AWARD_SUMMARY` (real)
**Source row** (`archive.award_version`, `award_id = 3187665`, current):
`award_number = '204713-00133'`, `title = 'CARB-X'`, `sponsor_name =
'HHS/Assistant Secretary for Preparedness and Response'`, `lead_unit_name
= 'LAW CARB-X Grant'`, `status_description = 'Approved Award'`, PI =
`MICHAEL KEVIN OUTTERSON`, `obligated_total_amount = 0.00` (verified
correct — see Time and Money.md), `award_effective_date = 2016-08-01`,
`workflow_document_number = '923140'`.
**Generated text (existing template, unchanged):**
`"module: AWARD | business number: 204713-00133 | title: CARB-X |
PI/person: MICHAEL KEVIN OUTTERSON | sponsor: HHS/Assistant Secretary
for Preparedness and Response | lead unit: LAW CARB-X Grant | status:
Approved Award"`
**Provenance:** `document_type='AWARD_SUMMARY'`, `parent_module=NULL`,
`parent_business_identifier='204713-00133'`, `exact_record_id=3187665`,
`version_label=NULL`, `source_table='archive.award_version'`,
`source_primary_key=3187665`.
**Expected vector row:** already exists in `archive.search_embedding`
today (`module='AWARD'`) — this fixture confirms the migration's backfill
UPDATE correctly relabels it, not a new row.
**Citation target:** `GET /api/v1/awards/3187665/summary` → `AwardSummarySection.tsx`.

### `AWARD_AMOUNT` (real, fully verified this session)
**Source rows** (`archive.award_amount_info`, `award_id = 3187665`):

| exact_record_id | tnm_document_number | obligated_total_direct |
|---|---|---|
| 3187674 | *(null)* | 280607.11 |
| 3187908 | 923179 | 0.00 |
| 3195981 | 925932 | 0.00 |
| 3195982 | 925932 | 0.00 |

**Generated text, row 3195982 (the current row):**
`"Award 204713-00133 version 125, amount record 3195982, document
925932: obligated $0.00, anticipated $0.00"`
**Generated text, row 3187674 (a superseded row):**
`"Award 204713-00133 version 125, amount record 3187674: obligated
$280607.11, anticipated $280607.11"`
**Provenance (row 3195982):** `document_type='AWARD_AMOUNT'`,
`parent_module='AWARD'`, `parent_business_identifier='204713-00133'`,
`exact_record_id=3195982`, `version_label=125`, `source_table=
'archive.award_amount_info'`, `source_primary_key=3195982`.
**Expected vector row:** 4 new rows, one per `award_amount_info_id`
above — this is the concrete proof that `AWARD_AMOUNT` indexing uses the
corrected `MAX(award_amount_info_id)` rule only for *labeling* which row
is current, never for *filtering* which rows get embedded.
**Citation target:** `GET /api/v1/awards/3187665/amounts` →
`AwardAmountsSection.tsx`, cross-checkable against
`AwardAmountInfoCurrentRowSelectionTest.test_case_a_award_204713_00133_true_zero_is_current`.

### `AWARD_PERSON` (real)
**Source row** (`archive.award_person`, `award_id = 3187665`): exactly
one person on the current version — `award_person_id = 3187666`,
`person_id = 'U04690146'`, `full_name = 'MICHAEL KEVIN OUTTERSON'`,
`contact_role_code = 'PI'`, `key_person_project_role = NULL` (Oracle
genuinely has no role text beyond the code for this row — not a data gap
introduced by the archive).
**Generated text:** `"MICHAEL KEVIN OUTTERSON — PI on Award
204713-00133 version 125"` (role-text clause omitted when
`key_person_project_role` is null, per the template's conditional
shape).
**Provenance:** `document_type='AWARD_PERSON'`, `parent_module='AWARD'`,
`parent_business_identifier='204713-00133'`, `exact_record_id=3187666`,
`version_label=125`, `source_table='archive.award_person'`,
`source_primary_key=3187666`.
**Expected vector row:** 1 new row for this version (× however many of
the 125 versions changed the PI/person list — not enumerated here).
**Citation target:** `GET /api/v1/awards/3187665/people` →
`AwardPeopleSection.tsx`.

### `AWARD_VERSION` (real)
**Source row** (`archive.award_version`, `award_id = 3187665`, current):
identical fields to the `AWARD_SUMMARY` row above — same source table,
different document type and grain (one row here vs. the family-level
current-only summary).
**Generated text:** `"Award 204713-00133 version 125 (document 923140):
CARB-X. Sponsor: HHS/Assistant Secretary for Preparedness and Response.
Lead unit: LAW CARB-X Grant. Status: Approved Award. Effective
2016-08-01, begins None"` (illustrates the template needs a null-guard
for `begin_date`/`closeout_date`, both `None` on this real row — a real
implementation detail this fixture surfaced, not a hypothetical edge case).
**Provenance:** `document_type='AWARD_VERSION'`, `parent_module='AWARD'`,
`parent_business_identifier='204713-00133'`, `exact_record_id=3187665`,
`version_label=125`, `source_table='archive.award_version'`,
`source_primary_key=3187665`.
**Expected vector row:** 125 rows total for this family in a full run
(one per `award_id`); this is the current-version one.
**Citation target:** `GET /api/v1/awards/3187665/versions` →
`AwardVersionsSection.tsx`.

### `AWARD_COMMENT` (real)
**Source rows** (`archive.award_comment`, `award_id = 3187665`): 12
rows total, but **only 1 has actual text** — the other 11 are
placeholder rows for fixed comment categories (`comment_type_code`
values like `'CG1'`, `'CG2'`, `'CG3'`, `'1'`, `'2'`, `'3'`, `'8'`, `'9'`,
`'12'`, `'13'`, `'16'`) with `comments = NULL`. The one real row:
`award_comment_id = 1801726`, `comment_type_code = '21'`, `comments =
'Rebudget 5-18-22_Pattern Opt1_3156_funder realloc_BARDA & WT'`.
**Generated text:** `"Comment (21) on Award 204713-00133 version 125:
Rebudget 5-18-22_Pattern Opt1_3156_funder realloc_BARDA & WT"`.
**Provenance:** `document_type='AWARD_COMMENT'`, `parent_module='AWARD'`,
`parent_business_identifier='204713-00133'`, `exact_record_id=1801726`,
`version_label=125`, `source_table='archive.award_comment'`,
`source_primary_key=1801726`.
**Expected vector row:** exactly 1 for this version — the query's own
`WHERE comments IS NOT NULL` filter (section 2) correctly excludes the
other 11 empty-category rows from ever reaching Bedrock, confirmed by
this real data rather than assumed.
**Citation target:** `GET /api/v1/awards/3187665/comments` →
`AwardCommentsSection.tsx`.

### `AWARD_TERM` (real — confirms the text-quality caveat above)
**Source rows** (`archive.award_sponsor_term`, `award_id = 3187665`, 5
of N shown): `sponsor_term_id` values `449`, `456`, `504`, `431`, `435`
— no other content field. **Source rows**
(`archive.award_report_term`, same `award_id`, 3 rows): e.g.
`report_class_code='3', report_code='21', frequency_code='6',
frequency_base_code='4', osp_distribution_code='2', due_date=NULL`.
**Generated text (sponsor term 449):** `"Sponsor term 449 on Award
204713-00133 version 125"`.
**Generated text (report term row 1):** `"Report term: class 3, code
21, frequency 6, due None, on Award 204713-00133 version 125"`.
**Provenance (sponsor term 449):** `document_type='AWARD_TERM'`,
`exact_record_id=3025610`, `version_label=125`, `source_table=
'archive.award_sponsor_term'`, `source_primary_key=3025610`.
**Real confirmation of the caveat above:** these five sponsor-term rows
and three report-term rows are exactly as code-only as predicted — no
description text exists anywhere in the source data to embed. A
semantic query like "what reporting requirements does this Award have"
cannot be meaningfully answered by these embeddings today; an exact
lookup by `sponsor_term_id`/`report_code` can be.
**Citation target:** `GET /api/v1/awards/3187665/terms` →
`AwardTermsSection.tsx`.

### `RELATED_PROPOSAL`, `RELATED_NEGOTIATION`, `RELATED_SUBAWARD` — zero rows for this fixture (real finding)
All three live queries against `204713-00133` returned **zero rows**:
no `archive.award_funding_proposal` row, no `archive.negotiation` row
with `associated_document_id = '204713-00133'`, no
`archive.subaward_funding` row with `award_number = '204713-00133'`.
This Award has no linked Proposal, Negotiation, or Subaward in the
archive at all. This is a genuine, useful finding about the golden
fixture, not a bug in the design: it means `204713-00133` alone cannot
serve as the fixture proving these three relationship-edge document
types actually produce a row — Phase 1 implementation should run the
indexing script against a *second* Award known to have at least one of
each relationship (e.g. any Award already confirmed to have Proposal
links from the funding-proposals endpoint work earlier in this project)
before considering `RELATED_PROPOSAL`/`RELATED_NEGOTIATION`/
`RELATED_SUBAWARD` proven end-to-end. The SQL, text template, and
provenance mapping in section 2 remain correct and unchanged — this is
a coverage gap in the fixture choice, not in the design.

---

## 5. Rollback plan

- **Migration:** every new column is nullable and additive; a rollback
  is `ALTER TABLE archive.search_embedding DROP COLUMN IF EXISTS ...`
  for each of the 8 columns, plus recreating the original
  `ix_search_embedding_record` unique index and dropping the 3 new
  indexes. Because nothing existing reads the new columns (see
  Compatibility below), this is safe to run even after evidence rows
  exist — it simply deletes the columns those rows were using, which is
  equivalent to deleting the evidence rows' extra context, not to
  corrupting Global Search.
- **Evidence rows:** every evidence-document row is distinguishable
  from a summary row by `document_type <> 'AWARD_SUMMARY'` (post-backfill,
  every legacy row is explicitly `'AWARD_SUMMARY'`/`'PROPOSAL_SUMMARY'`/
  etc.). Full rollback of Phase 1's data: `DELETE FROM
  archive.search_embedding WHERE document_type NOT IN
  ('AWARD_SUMMARY','PROPOSAL_SUMMARY','NEGOTIATION_SUMMARY','SUBAWARD_SUMMARY')`.
  This is safe specifically because Phase 1 is award-scoped (one
  fixture) — the blast radius of a mistaken rollback is small by
  construction.
- **ETL script:** `build_evidence_embedding.py` and
  `run-evidence-embedding.sh` are new files with no callers elsewhere in
  the codebase (unlike `AwardArchiveRepository.java`, which had 4 live
  call sites for the amount-selection bug) — deleting them is a
  complete, zero-blast-radius rollback of the code itself.

---

## 6. Compatibility with current Global Search

- `GlobalSearchService.searchSemantic()` queries `archive.search_embedding`
  filtering only by `module` and ordering by cosine distance
  (`<=>`) — it never references `document_type` or any of the other new
  columns, so it will return evidence rows exactly as it returns summary
  rows today: same rank tier (`RANK_SEMANTIC`), same dedup key shape.
- **This is itself a real, load-bearing risk to flag, not just a
  reassurance:** once evidence rows exist, a Global Search query could
  start surfacing an `AWARD_PERSON` or `AWARD_COMMENT` row as a "Related
  match" alongside (or instead of) the `AWARD_SUMMARY` row for the same
  Award, since `searchSemantic()` doesn't yet filter to summaries only.
  Two options, neither implemented here: (a) add `AND document_type IN
  ('AWARD_SUMMARY', 'PROPOSAL_SUMMARY', ...)` to
  `searchSemantic()`'s WHERE clause before Phase 1 evidence rows are
  populated in a shared/dev database Global Search actually queries, or
  (b) accept evidence rows surfacing in Global Search as a feature, not
  a bug, and rely on `deduplicate()`'s existing family-level key to
  collapse an evidence hit onto its structured twin when one exists.
  **Recommendation: (a), as an explicit one-line follow-up before this
  phase's data is populated anywhere Global Search reads from** — cheap,
  removes the ambiguity, and keeps this phase's "prove the shape works"
  goal from accidentally changing production Global Search behavior.
- Existing `search_embedding_poc` (V069) and its benchmark script are
  untouched — this design only touches the production V070 table.

---

## Summary of what's proven vs. designed vs. pending

- **Proven this session, real data:** the corrected `AWARD_AMOUNT`
  current-row rule and all 4 real `award_amount_info` rows; real
  `AWARD_SUMMARY`/`AWARD_VERSION`/`AWARD_PERSON` field values (title
  "CARB-X", PI Michael Kevin Outterson, sponsor, lead unit); the real
  `AWARD_COMMENT` content (1 real comment of 12 category rows); the real
  `AWARD_TERM` code-only content (5 sponsor terms, 3 report terms, zero
  description text anywhere).
- **Designed, not yet built:** migration SQL, all 9 document-builder
  templates, the indexing pipeline shape, the rollback plan, the
  Global Search compatibility risk and recommended mitigation.
- **Contradicts/refines the audit:** `AWARD_TERM`'s source tables carry
  only codes, no descriptions — confirmed with real data, still worth
  building, but semantically thin for natural-language retrieval until a
  code-lookup table exists.
- **New finding from real data, not anticipated by the audit:**
  `204713-00133` has **zero** linked Proposals, Negotiations, or
  Subawards. The golden fixture alone cannot prove `RELATED_PROPOSAL`/
  `RELATED_NEGOTIATION`/`RELATED_SUBAWARD` produce a real row end-to-end
  — a second Award with at least one of each relationship is needed
  before those three document types are considered proven, not just
  designed. Everything else in the audit held up unchanged at this
  level of detail.

No migration was applied, no ETL script was written, and no chatbot was
built — design only, per instruction.
