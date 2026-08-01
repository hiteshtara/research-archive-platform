# Award Search / Hierarchy / Summary / Versions API — Design and Implementation Record

## Status

Implemented, manually verified end-to-end against the real archive
schema (production-loaded batch-7 dataset, 282,214 `archive.award_version`
rows), and revised for long-term multi-client extensibility per a
dedicated review — see "Extensibility review" below. Routes now live
under `/api/v1/awards`. 46 unit/contract tests added across the two
passes; full API suite (170 tests) passes. Image build/ECS deploy is
blocked by a pre-existing, unrelated infrastructure incident — see
`AWARD_IMPLEMENTATION_ROADMAP.md`'s corresponding same-day entry.

## Scope

Phase 1 of the Award API build, per explicit instruction: only these four
endpoints, nothing else.

- `GET /api/v1/awards/search?q=&page=&size=`
- `GET /api/v1/awards/{awardNumber}/hierarchy`
- `GET /api/v1/awards/{awardId}/summary`
- `GET /api/v1/awards/{awardId}/versions?page=&size=`

Budget, Time and Money, Terms, Comments, SAP transmission history, and
Attachments endpoints are explicitly deferred to a later phase — planned
as further composable resources under the same `/api/v1/awards/{awardId}/…`
family (see "Composability" below), not folded into `summary`. No ETL,
Terraform, or AWS infrastructure changes. No React UI changes — the
`docs/design/award-ui-redesign-mockup.html` mockup was read only to
determine what fields the frontend will eventually need.

## Architecture

Mirrors the existing Award concrete-service pattern (no ports/use-case
layer — see `CLAUDE.md`'s "Hexagonal layout is only fully implemented for
IRB" note): `AwardV1Controller` → `AwardArchiveService` →
`AwardArchiveRepository` (`JdbcClient`, no Spring Data JPA). Not-found
propagates as plain `java.util.NoSuchElementException`, mapped to 404 by
the existing unscoped `GlobalExceptionHandler`. New DTOs live in
`adapter/in/web/dto/award/`; no JPA entity is ever returned directly from
a controller.

The four endpoints live on a new `AwardV1Controller` (`/api/v1/awards`),
separate from the pre-existing `AwardArchiveController` (`/api/awards`,
unversioned) that the current React UI already calls directly
(`ui/src/api/client.ts`) — see "API versioning" below for why these were
deliberately not merged into one controller/prefix.

Two internal-only DTOs (`AwardHierarchyEdgeRow`, `AwardSummaryCardRow`)
exist purely to move data between the repository and the service's
tree-building code — they are never serialized to a client.

## Endpoints

### `GET /api/v1/awards/search?q=&page=&size=`

Supports exact/partial/wildcard (`*text*`) award number, PI/person name,
title, sponsor code/name, lead unit number/name, and document number
(`modification_number`). `page` defaults to 0 (`@Min(0)`), `size` defaults
to 25 (`@Min(1)`, `@Max(100)`).

```
GET /api/v1/awards/search?q=Cancer&size=2
```

```json
{
  "content": [
    {
      "awardId": 1207589,
      "awardNumber": "100004-00001",
      "latestSequenceNumber": 9,
      "title": "The Boston Collaborative Oral Cancer Study",
      "status": "Closed",
      "principalInvestigator": "MICHAEL MCCLEAN",
      "sponsor": "Brown University",
      "leadUnit": "SPH ENVIRONMENTAL HEALTH",
      "currentObligatedAmount": 1181116.49,
      "rootAwardNumber": null,
      "parentAwardNumber": null
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 1615,
  "totalPages": 808,
  "first": true,
  "last": false
}
```

`rootAwardNumber`/`parentAwardNumber` come from a `LEFT JOIN
archive.award_hierarchy` and are `null` whenever the award has no
hierarchy row at all — the common case (0 rows in the local dev dataset
at the time of writing; see "Hierarchy data availability" below).

### `GET /api/v1/awards/{awardNumber}/hierarchy`

Returns the full recursive family tree rooted at
`archive.award_hierarchy.root_award_number`, resolved from whatever
award_number is requested (root, mid-tree, or leaf all resolve to the
same tree). 404 if the award_number doesn't exist in `award_version` at
all.

```
GET /api/v1/awards/100004-00003/hierarchy
```

```json
{
  "rootAwardNumber": "100004-00001",
  "requestedAwardNumber": "100004-00003",
  "root": {
    "awardNumber": "100004-00001",
    "awardId": 1207589,
    "latestSequenceNumber": 9,
    "parentAwardNumber": null,
    "active": true,
    "title": "The Boston Collaborative Oral Cancer Study",
    "status": "Closed",
    "principalInvestigator": "MICHAEL MCCLEAN",
    "sponsor": "Brown University",
    "leadUnit": "SPH ENVIRONMENTAL HEALTH",
    "currentObligatedAmount": 1181116.49,
    "children": [
      {
        "awardNumber": "100004-00002",
        "parentAwardNumber": "100004-00001",
        "active": true,
        "children": [
          {
            "awardNumber": "100004-00003",
            "parentAwardNumber": "100004-00002",
            "active": false,
            "children": []
          }
        ]
      }
    ]
  },
  "selectedAwardPath": ["100004-00001", "100004-00002", "100004-00003"]
}
```

(Fields elided above with `…` for brevity in the child nodes — the real
response repeats every `AwardHierarchyNodeResponse` field at every
level.)

If the requested award has no hierarchy row at all, the endpoint returns
a single-node tree (the award as its own root) rather than 404ing — an
award with no recorded hierarchy relationship is not the same as an
award that doesn't exist.

#### Root detection and `archive.award_hierarchy`'s NOT NULL columns

`root_award_number`, `parent_award_number`, and `originating_award_number`
are all `NOT NULL` on `archive.award_hierarchy` (`V049`) — there is no
"this row has no parent" marker. A real root row still carries a
`parent_award_number` value, typically a self-reference back to its own
`award_number` (confirmed by manual verification with seeded synthetic
data mirroring this convention — see "Manual verification" below; no
real hierarchy rows existed in the local dataset to observe directly).
The service therefore identifies the root by matching
`award_number == root_award_number` within the resolved edge set, not by
any null check, and:

- excludes a self-referencing row from being treated as its own child;
- falls back to promoting the *requested* award's own row to root if the
  hierarchy's nominal root row is itself missing from the edge set
  (malformed/incomplete historical data, or a `root_award_number`/
  `parent_award_number` pointing at an award family outside the current
  batch — the extraction query's own comment notes these columns "may
  point at a different Award family entirely, not necessarily loaded in
  the same batch");
- nulls out the resolved root node's own `parentAwardNumber` in the
  response (a self-reference, or a pointer outside this tree, is not a
  meaningful "parent" at the top of a rendered tree);
- guards tree construction and `selectedAwardPath` walking with a
  `visited` set, so a cycle in malformed historical data (including a
  2-node cycle, not just a direct self-loop) terminates instead of
  recursing forever.

#### Hierarchy data availability

`archive.award_hierarchy` has **0 rows** in the current local dev
database despite 282,214 loaded `award_version` rows — this batch's
Awards simply have no recorded hierarchy relationships yet (hierarchy
population happens as part of the same Award load, from a
version-agnostic, `AWARD_NUMBER`-keyed Oracle source — see
`sql/extract/award/30_award_hierarchy.sql`). The single-node fallback
path was exercised directly against real data; the multi-level
recursive/self-referencing/inactive-link/cross-family-child behavior was
verified by temporarily seeding 4 synthetic rows against the real local
Postgres instance (root self-reference, one active child, one inactive
grandchild, one cross-family sibling under the same parent), confirming
correct tree shape and `selectedAwardPath` resolution from three
different starting points, then deleting the synthetic rows — see
"Manual verification" below. This should be re-confirmed against a
production dataset that actually has populated hierarchy rows before
the React hierarchy UI ships.

### `GET /api/v1/awards/{awardId}/summary`

Keyed by the surrogate `award_id` (one specific version), not
`award_number` — deliberately different from every other endpoint here.
Compact only: no comments, Budget, Time and Money, SAP transmission
history, or attachments.

```
GET /api/v1/awards/3/summary
```

```json
{
  "awardId": 3,
  "awardNumber": "100004-00003",
  "sequenceNumber": 1,
  "title": "The Boston Collaborative Oral Cancer Study",
  "status": "Approved Award",
  "sponsor": "Brown University",
  "primeSponsor": "NIH/National Cancer Institute",
  "principalInvestigator": "MICHAEL MCCLEAN",
  "leadUnit": "SPH ENVIRONMENTAL HEALTH",
  "awardEffectiveDate": "2007-09-15",
  "awardExecutionDate": null,
  "beginDate": null,
  "closeoutDate": null,
  "obligatedTotalAmount": 1143907.00,
  "anticipatedTotalAmount": 1143907.00,
  "basisOfPaymentCode": "1",
  "basisOfPaymentDescription": "Cost reimbursement (Resource Related Billing)",
  "methodOfPaymentCode": "28",
  "methodOfPaymentDescription": "Invoice",
  "rootAwardNumber": null,
  "parentAwardNumber": null
}
```

404 if `awardId` doesn't exist.

### `GET /api/v1/awards/{awardId}/versions?page=&size=`

Resolves `awardId` to its `award_number` family, then returns every
version row for that exact `award_number` (not sibling award_numbers —
see "Award number vs. sequence number" below), newest sequence first.
Wrapped in the same `PageResponse` envelope as `search` (`page` defaults
to 0, `size` defaults to 50, max 100) — added during the extensibility
review for pagination-metadata consistency across every list-shaped
endpoint; the original Phase 1 pass returned a bare JSON array here.

```
GET /api/v1/awards/1207589/versions?size=2
```

```json
{
  "content": [
    {
      "awardId": 1207589,
      "awardNumber": "100004-00001",
      "sequenceNumber": 9,
      "status": "Closed",
      "transactionTypeCode": "13",
      "transactionType": "Other -- See Comments",
      "awardEffectiveDate": "2007-09-15",
      "updateTimestamp": "2015-02-11T14:48:29",
      "documentNumber": null
    },
    {
      "awardId": 1207482,
      "awardNumber": "100004-00001",
      "sequenceNumber": 8,
      "status": "PAFO/OSP (Closing)",
      "transactionTypeCode": "13",
      "transactionType": "Other -- See Comments",
      "awardEffectiveDate": "2007-09-15",
      "updateTimestamp": "2015-02-11T13:53:01",
      "documentNumber": null
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 9,
  "totalPages": 5,
  "first": true,
  "last": false
}
```

(Real response for this award_number has `totalElements: 9`; abbreviated
to `size=2` above.) 404 if `awardId` doesn't exist.

#### Award number vs. sequence number

Confirmed against real data during manual verification: `award_number`
already fully identifies the family this endpoint operates on (e.g.
`100004-00001`, `100004-00002`, `100004-00003` are three *different*
`award_number`s, each independently versioned via `sequence_number`).
`documentNumber` maps to `archive.award_version.modification_number`, a
real per-version column — not `award_amount_info.tnm_document_number`
(Time-and-Money-specific).

## Search pattern normalization (`AwardSearchPattern`)

Package-private utility in `application.award`. Rules, in order:

1. Escape literal `\`, `%`, `_` (Postgres ILIKE metacharacters) so a
   literal percent/underscore/backslash in a search term is matched
   literally, not misread as a wildcard.
2. Translate the API's own `*` wildcard syntax (e.g. `*105698*`) into an
   unescaped `%`.
3. If the *original* query contained no `*`, default-wrap the whole
   (already-escaped) term in `%...%` for a substring match.

Step 3 is checked against the **original** input, not the
escaped/translated result — an initial implementation checked
`withWildcards.contains("%")`, which is also true for an escaped literal
`\%`, silently skipping the substring wrap for any query containing a
literal percent sign (e.g. searching for `"50%"` would incorrectly
require an *exact* match instead of a substring match). Caught by
`AwardSearchPatternTest` before this reached production; fixed to check
`rawQuery.contains("*")` instead.

The resulting pattern is always bound as a single JDBC parameter
(`.param("pattern", pattern)`), never concatenated into SQL text — no
SQL-injection surface. `AwardSearchPatternTest` includes an explicit
SQL-injection-attempt case confirming the pattern is treated as inert
literal text.

## Index and query review (`V053`)

Applied to the real local dev database via the ETL's own migration
runner (`apply_migrations` — Spring's Flyway integration is disabled per
`CLAUDE.md`), not `mvn spring-boot:run`.

| Index | Table.column(s) | Purpose |
|---|---|---|
| `ix_award_hierarchy_root` | `award_hierarchy(root_award_number)` | Hierarchy edge fetch by root |
| `ix_award_version_title_lower` | `award_version(lower(title))` | Case-insensitive exact/prefix title lookups |
| `ix_award_version_title_trgm` | `award_version(title)` gin trgm | Substring `ILIKE '%...%'` on title |
| `ix_award_version_sponsor_name_trgm` | `award_version(sponsor_name)` gin trgm | Substring sponsor name search |
| `ix_award_version_lead_unit_number` | `award_version(lead_unit_number)` | Exact/prefix unit number search |
| `ix_award_version_lead_unit_name_trgm` | `award_version(lead_unit_name)` gin trgm | Substring unit name search |
| `ix_award_version_modification_number_trgm` | `award_version(modification_number)` gin trgm | Substring document number search |
| `ix_award_person_full_name_trgm` | `award_person(full_name)` gin trgm | Substring PI/person name search |

`pg_trgm` was already an established extension in this codebase (IRB,
research record — `V001`/`V003`/`V004`/`V007`/`V021`), reused here rather
than introducing a new search technique. `sponsor_code`, `award_number`,
and `status` already had adequate existing indexes (`V011`–`V013`) for
exact/prefix lookups and were left alone.

Verified via `EXPLAIN` against 60,000 rows of realistically varied
synthetic data (an initial naive *uniform* synthetic seed showed `Seq
Scan` — correct Postgres behavior for a non-selective predicate over
mostly-identical rows, not a defect; re-seeded with 8 title templates +
md5 suffixes, 6 sponsor names, 5 unit names cycled by modulo to get
genuinely selective predicates):

- `title ILIKE '%...%'` → `Bitmap Heap Scan` via
  `Bitmap Index Scan on ix_award_version_title_trgm`.
- `sponsor_name ILIKE '%...%'` → `Bitmap Heap Scan` via
  `Bitmap Index Scan on ix_award_version_sponsor_name_trgm`.
- `award_hierarchy WHERE root_award_number = '...'` → `Bitmap Heap Scan`
  via `Bitmap Index Scan on ix_award_hierarchy_root`.

## Fields deliberately omitted (never invent Oracle columns)

Per `CLAUDE.md`'s "never invent Oracle table/column names" rule:

- **FAIN** — confirmed absent from the archive schema entirely (grepped
  every migration; cross-referenced against
  `AWARD_EXTENSION_CGB_DESIGN.md` and
  `SAP_AWARD_TRANSMISSION_ASSESSMENT.md`'s own open questions about
  `Award.fainId`). Omitted from `AwardSearchResultResponse` and
  `AwardSummaryResponse` even though both were requested to include it.
- **"Account type"** — the only `account_type_code` in this schema is
  `archive.award_transmission`'s own SAP-specific field (`V052`), not a
  general Award attribute. Omitted from `AwardSummaryResponse`.
- **"Final expiration date" / "current fund effective date"** — invented
  field names from `docs/design/award-ui-redesign-mockup.html` with no
  backing column. `closeoutDate` (`award_version.closeout_date`) is
  exposed in their place as the real, analogous column.

## Manual verification

Ran the API locally (`SPRING_PROFILES_ACTIVE=local`, real Postgres —
`app.security.enabled=false`, so no Cognito token needed) against the
production batch-7 dataset already loaded in the local dev database.
Confirmed:

- Exact award number search (`q=100004-00003`), substring search
  (`q=Cancer` → 1,615 total matches, correctly paginated), and
  application wildcard search (`q=*Learning*` → 945 total matches).
- SQL-injection-style input (`q=' OR '1'='1`) returns an empty, valid
  result set — no error, no injected behavior.
- `page`/`size` out-of-range values (`size=500`, `page=-1`) return
  `400 Bad Request` with a clear message — **not** a 500. Caught during
  this verification pass: `@Validated` + `@Min`/`@Max` on
  `@RequestParam`s throws `jakarta.validation.ConstraintViolationException`,
  which `GlobalExceptionHandler` did not previously handle at all,
  falling through to the default Spring Boot 500 response. Fixed by
  adding a `ConstraintViolationException` → 400 handler to
  `GlobalExceptionHandler` (this is an app-wide fix — it also silently
  benefits every other `@Validated` controller, e.g. `GlobalSearchController`,
  none of which had a test covering this path either).
- Missing award/awardId on all four endpoints (`hierarchy`, `summary`,
  `versions`) returns `404` with a clear message.
- Versions endpoint returns all 9 sequence rows for a real multi-version
  award_number, correctly ordered newest-first.
- Hierarchy endpoint's single-node fallback, multi-level recursive tree,
  inactive-link exposure, and cross-family child resolution all verified
  against temporarily-seeded real Postgres rows (see "Hierarchy data
  availability" above); rows deleted afterward, dev database left
  otherwise unchanged.

## Tests

46 tests across both passes (38 Phase 1 + 8 extensibility-review
additions/replacements), all passing; full API suite (170 tests) passes.

- `AwardSearchPatternTest` (8): substring default, application wildcard
  (leading/trailing/both), literal `%`/`_`/`\` escaping, SQL-injection
  inertness, empty query.
- `AwardArchiveRepositoryTest` (11): search/count SQL shape and bound
  parameters (never concatenated), hierarchy root/edges queries,
  summary-cards empty-collection short-circuit (no query issued) and
  `IN (:awardNumbers)` binding, summary-by-id column mapping (asserting
  absence of any `fain`/`account_type` reference), award-number
  resolution, version ordering and pagination, version count.
- `AwardArchiveServiceTest` (13): pagination clamping (search and
  versions), wildcard/empty query normalization, single-node hierarchy
  fallback, 404s, full recursive tree construction with a
  self-referencing root, root-row promotion when the nominal root is
  missing from the edge set, 2-cycle termination guard, summary/versions
  delegation and 404s.
- `AwardV1ControllerTest` (8): routing/parameter delegation for all four
  `/api/v1/awards` endpoints, default paging, versions pagination, and
  404 propagation with the consistent error shape.
- `AwardV1ContractTest` (5, new): golden-shape serialization tests for
  all four response DTOs (including the `PageResponse` envelope and the
  nested hierarchy node) — asserts the exact field-name set for each,
  and that dates/timestamps serialize as ISO strings, not numeric
  timestamp arrays. Guards against an accidental field rename/removal/
  retype going unnoticed, since nothing else in the type system
  surfaces a DTO's JSON shape.

(`AwardArchiveControllerTest`, the Phase 1 controller test file, was
deleted and its cases moved into `AwardV1ControllerTest` when the four
endpoints moved controllers — see "API versioning" below.)

## Open questions

- Real production hierarchy data hasn't been directly observed (0 rows
  loaded locally) — the NOT-NULL self-reference convention assumed for
  root rows should be confirmed against an Oracle-loaded batch that
  actually contains `archive.award_hierarchy` rows before the React
  hierarchy UI ships.
- `findSummaryCards`' batched `IN (:awardNumbers)` binding via
  `JdbcClient`'s automatic Collection-to-list parameter expansion has no
  prior precedent elsewhere in this codebase (confirmed via grep) — it
  is standard, documented `NamedParameterJdbcTemplate`/`JdbcClient`
  behavior and was exercised directly in manual verification, but is
  worth calling out as the first use of this specific binding pattern
  here.
- See "Open questions" at the end of the extensibility review below for
  additional items raised by that pass specifically.

---

# Extensibility review (2026-08-01 addendum)

Prompted by an explicit request to check the Phase 1 design against
multi-client-extensibility principles before deployment — the API must
serve more than the one React UI that happens to exist today. Each
principle below states what was checked, what (if anything) was
corrected, and why.

## API versioning

**Correction made.** All four new endpoints moved to `/api/v1/awards/...`
on a new `AwardV1Controller`. The pre-existing, unversioned
`AwardArchiveController` (`/api/awards/...`) was left completely
unchanged and unversioned.

This was a deliberate, scoped choice, not an oversight:

- `ui/src/api/client.ts` already calls `AwardArchiveController`'s
  existing endpoints directly (`/api/awards/families`, `/{awardNumber}`,
  `/history`, `/people`, `/amounts`, `/proposals`, `/funding`) from the
  live React UI. Moving those under `/api/v1` in the same pass would
  silently break the currently-working frontend with no corresponding
  UI change — out of scope for "do not build the UI."
- The four *new* endpoints have zero existing consumers (the React
  hierarchy UI is explicitly paused pending this API), so giving them a
  versioned home from day one costs nothing and avoids ever having to
  version them later under load.
- Versioning the entire app in one pass (every other domain controller —
  Subaward, Proposal, Negotiation, IRB — is also unversioned) is a much
  larger, cross-cutting migration than "Award API extensibility," and
  was not requested.

**Recommendation, not yet actioned:** migrate `AwardArchiveController`'s
existing endpoints to `/api/v1/awards/...` (with the UI's `client.ts`
updated in lockstep) in a dedicated, explicitly-scoped pass, then
formalize `/api/v1` as the whole app's convention going forward. Until
then, the app has one versioned and one unversioned Award surface
side by side — documented here so it isn't mistaken for an accident.

## DTOs stay domain-oriented

**Confirmed, no correction needed.** Every new DTO (`AwardSearchResultResponse`,
`AwardHierarchyResponse`/`AwardHierarchyNodeResponse`, `AwardSummaryResponse`,
`AwardVersionSummaryResponse`) is a plain Java record with domain-shaped
field names (`principalInvestigator`, `sponsor`, `obligatedTotalAmount`,
...) — none reference a JPA entity, an archive table/column name
verbatim (`sponsor_name` → `sponsor`, not left as-is), or a React
component/prop name. `AwardHierarchyEdgeRow`/`AwardSummaryCardRow` are
internal-only (never serialized) precisely so the repository↔service
data-shuttling doesn't leak into the public contract.

## No presentation fields

**Confirmed, no correction needed.** Grepped every new DTO for anything
resembling a UI concern (color, icon, expansion/collapse state, tab
name, display label, sort-arrow direction, CSS class) — none exist. The
mockup's own invented display-only fields (`cgb_indicator` styling,
etc.) were never carried into any DTO in the first place.

## Consistent pagination metadata

**Correction made.** `/versions` previously returned a bare JSON array;
now wrapped in the same generic `PageResponse<T>` envelope as `/search`
(see "Endpoints" above). `/hierarchy` and `/summary` remain unwrapped —
a recursive tree and a single object are not "list" endpoints, and
forcing a `PageResponse` around either would be actively misleading (a
tree has no single flat page of results; a summary has no plurality at
all). Every endpoint in this API that returns a flat list of records now
uses the identical envelope shape.

## Stable sort, documented default

**Confirmed, documented, no code change needed** — both existing sorts
were already deterministic, now called out explicitly:

- `search`: `ORDER BY award_number` — stable on its own, since
  `ux_award_one_primary_current` guarantees at most one
  `is_primary_current` row per `award_number` (no ties possible).
- `versions`: `ORDER BY sequence_number DESC, source_update_timestamp DESC
  NULLS LAST, award_id DESC` — the `award_id` tiebreaker guarantees a
  total order even if `sequence_number`/timestamp collide.

Neither endpoint accepts a client-supplied sort parameter yet; if one is
added later, the default must remain exactly these orders for backward
compatibility (see "Backward compatibility rules" below).

## Consistent error responses

**Correction made.** `GlobalExceptionHandler` (the single, app-wide
`@RestControllerAdvice` — this fix benefits every controller, not just
Award) previously returned `{timestamp, status, error, message}`. Now
additionally returns `code` (a short machine-readable string —
`NOT_FOUND`, `BAD_REQUEST`, `VALIDATION_ERROR` — so a client can branch
without parsing `message` text) and `path` (the request URI, from
`HttpServletRequest`), matching this shape:

```json
{
  "timestamp": "2026-08-01T19:02:20.662234Z",
  "status": 404,
  "error": "Not Found",
  "code": "NOT_FOUND",
  "message": "Award not found: 999999999",
  "path": "/api/v1/awards/999999999/summary",
  "correlationId": "38874465-7f5e-4586-839c-750e69b04f6f"
}
```

All additive — no existing field was renamed or removed, so this cannot
break an existing consumer that reads `message`/`status` today (confirmed
no test in the suite asserts a *closed* error-body shape that a new field
would break).

**Known simplification, flagged rather than built out further:**
`correlationId` is generated fresh, inline, per error response — there is
no app-wide request-entry filter yet that reads an inbound
`X-Correlation-Id`-style header or establishes one in MDC for the whole
request lifecycle (the only pre-existing `correlationId` convention in
this codebase is the AI subsystem's own, generated inside
`AwardAiSummaryService`/`AwardAiQuestionService` for AI-call tracing
specifically — unrelated to generic HTTP request tracing). A real
distributed-tracing correlation ID — accepted from the caller if
present, otherwise generated at request entry and attached to every log
line for that request, not just error responses — is a larger,
app-wide observability change and was intentionally not built here
without a separate, explicit ask.

## OpenAPI documentation

**Correction made and validated.** `springdoc-openapi` was already on the
classpath and enabled (`/v3/api-docs`, `/swagger-ui.html`) but unused by
any controller in this codebase — no existing precedent for
`@Operation`/`@Parameter` annotations anywhere. Added `@Tag`/`@Operation`/
`@Parameter`/`@ApiResponse` annotations to `AwardV1Controller` only (the
legacy `AwardArchiveController` and every other domain controller remain
undocumented — a pre-existing condition, not something this pass
introduced or was asked to fix app-wide).

Validated by starting the app locally and inspecting the live
`/v3/api-docs` output:

- All four `/api/v1/awards/...` paths appear, each with its intended
  `summary`, `description`, parameter descriptions, and declared
  response codes (confirmed for `search`: `summary: "Search Awards"`,
  parameter descriptions for `q`/`page`/`size`, responses `200`/`400`).
- `AwardSummaryResponse`'s generated schema lists exactly its 20 real
  fields — no `fain`, no `accountType` — confirming the DTO-level
  omissions survive into the generated contract, not just the Java type.

## Backward-compatibility rules

No breaking change has shipped yet, but for every future change to this
API:

- **Adding a field** to any response DTO is backward compatible — do it
  freely, at the end of the record's component list is not required
  (JSON is keyed by name, not position) but keeps diffs readable.
- **Deprecating a field**: keep serializing it (do not remove), document
  it as deprecated in the field's Javadoc-style comment and in this
  design doc, and only actually remove it in a new API version
  (`/api/v2/awards/...`) — never silently drop or repurpose a field's
  meaning within `/api/v1`.
- **Removing/renaming a field, or changing a field's JSON type** (e.g.
  `String` → object, `LocalDate` → epoch number) is a breaking change.
  It requires a new version prefix (`/api/v2`), not an in-place edit to
  `/api/v1` — this is exactly what `AwardV1ContractTest` exists to catch
  before it happens by accident.
- **Adding a new endpoint** (a new composable resource, e.g.
  `/api/v1/awards/{awardId}/budget` in a later phase) is always
  backward compatible and needs no version bump.
- **Changing default sort order or default page size** is a breaking
  change for any consumer relying on the current default and must be
  called out explicitly in this doc's changelog, even though it doesn't
  change the JSON shape.
- **Structured filters** (see below) must be additive query parameters
  alongside `q`, never a replacement for it — an existing `?q=...` caller
  must keep working exactly as before.

## Composable resources, not a dashboard endpoint

**Confirmed, no correction needed.** `search`, `hierarchy`, `summary`,
and `versions` are four independent resources today; Phase 2's Budget,
Time and Money, Terms, Comments, SAP transmission history, and
Attachments are already planned (per `CLAUDE.md`) as further independent
`/api/v1/awards/{awardId}/...` resources, not as fields folded into
`summary`. A client that only needs the summary never pays for fetching
Budget or SAP history, and vice versa.

## Authorization can extend to per-resource scopes later

**Confirmed, no code change needed.** Today's security posture is a
single blanket rule (`SecurityConfiguration`: `.requestMatchers("/api/**").authenticated()`)
with no per-resource distinction. Because every resource already has its
own distinct route (`/search`, `/{awardNumber}/hierarchy`,
`/{awardId}/summary`, `/{awardId}/versions`) rather than being multiplexed
through one endpoint with a `?resource=` parameter, a future move to
per-resource scopes/roles (e.g. `@PreAuthorize("hasAuthority('SCOPE_award:summary')")`
on `summary` specifically) is a purely additive method-level annotation
change — it does not require redesigning any route.

## Internal identifiers

`awardId` (`archive.award_version`'s surrogate primary key) is exposed
directly in two URLs (`/{awardId}/summary`, `/{awardId}/versions`) and in
every `search`/hierarchy-node response. This is a normal internal
database primary key, not a business identifier (`award_number` is the
business identifier - see `CLAUDE.md`'s "Research object model and
business grain" section) — but exposing it is deliberate and safe here,
specifically because:

- A single `award_number` can have multiple `award_id` rows (one per
  historical version), and the original spec requires addressing one
  *specific version*, not just the family - award_id is the only column
  that does that.
- This archive is **read-only and never rewrites history** (per
  `CLAUDE.md`'s core premise) - `award_id` values are never renumbered,
  recycled, or merged once loaded, so a URL containing one remains valid
  indefinitely, unlike a surrogate key in a live transactional system
  that might be regenerated after a data migration.

Documented here explicitly per the review's own instruction to "document
them clearly" rather than silently rely on it being obvious.

## Contract tests

**Added.** `AwardV1ContractTest` (5 tests) serializes a representative
instance of each response DTO with an `ObjectMapper` configured to match
Spring Boot's own Jackson defaults (`JavaTimeModule` registered,
`WRITE_DATES_AS_TIMESTAMPS` disabled) and asserts the exact field-name
set, plus that date/timestamp fields render as ISO strings. This is
deliberately narrow — a shape/regression guard, not a re-test of business
logic (that's `AwardArchiveServiceTest`/`AwardV1ControllerTest`) — so it
stays cheap to run and fails loudly the moment a field is accidentally
renamed, removed, or retyped.

## Client examples

**curl**

```bash
curl -s "https://api.example.edu/api/v1/awards/search?q=Cancer&size=25" \
  -H "Authorization: Bearer $TOKEN"
```

**JavaScript (fetch)**

```javascript
async function searchAwards(query, { page = 0, size = 25 } = {}) {
  const url = new URL("https://api.example.edu/api/v1/awards/search");
  url.searchParams.set("q", query);
  url.searchParams.set("page", String(page));
  url.searchParams.set("size", String(size));

  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!response.ok) {
    const problem = await response.json();
    throw new Error(`${problem.code}: ${problem.message}`);
  }

  return response.json(); // PageResponse<AwardSearchResultResponse>
}
```

**Python (requests)**

```python
import requests

def search_awards(query, page=0, size=25, token=None):
    response = requests.get(
        "https://api.example.edu/api/v1/awards/search",
        params={"q": query, "page": page, "size": size},
        headers={"Authorization": f"Bearer {token}"},
    )
    if not response.ok:
        problem = response.json()
        raise RuntimeError(f"{problem['code']}: {problem['message']}")
    return response.json()  # dict matching PageResponse<AwardSearchResultResponse>
```

## Planned: structured search filters (not implemented this pass)

Explicitly deferred — "worth planning," not building now. Free-text `q`
serves human search well; independent programmatic clients (dashboards,
data pulls, other BU systems) will want to filter on named fields
without constructing a text query:

```
GET /api/v1/awards?pi=Orsmond&sponsor=NIH&status=ACTIVE&page=0&size=25
```

Design intent for when this is built:

- **Additive, not a replacement.** `?q=` keeps working exactly as today;
  structured parameters are a second, independent way to query the same
  underlying `search` resource (likely the same `/search` route gaining
  optional `pi=`/`sponsor=`/`status=`/`leadUnit=` parameters alongside
  `q=`, rather than a new route — avoids forcing clients to pick one
  endpoint or the other up front).
  - Open question at implementation time: should `q` and structured
    filters be mutually exclusive per request, or combinable (AND)? The
    example above with a bare `/api/v1/awards?...` (no `/search`) also
    raises whether structured-only queries deserve their own path
    entirely - worth deciding deliberately rather than defaulting to
    whichever is easiest to wire up first.
- **Each filter parameter needs its own verified column mapping** before
  implementation - `sponsor` likely maps to `sponsor_code` (exact) with
  `sponsor_name` as a secondary substring match, `status` to
  `status_description` (an exact-match enum-like field, not a wildcard
  pattern), `pi` to the same `archive.award_person` substring match
  `search` already uses. Per `CLAUDE.md`, none of these should be
  assumed without checking `information_schema` first.
- **Pagination/sort/error-shape conventions above apply unchanged** - a
  structured-filter response is still a `PageResponse<AwardSearchResultResponse>`
  wrapping the exact same DTO `search` already returns, not a new shape.

## Open questions (extensibility review)

- Should `AwardArchiveController`'s legacy endpoints be migrated to
  `/api/v1` now, later, or never (kept permanently as a deprecated
  unversioned surface)? Needs a decision paired with a `client.ts`
  update - not made unilaterally here.
- Should error responses eventually carry a real, request-entry-level
  correlation ID (propagated via a servlet filter + MDC, accepting an
  inbound `X-Correlation-Id` header) instead of the current per-error
  generated UUID? Would also let every log line for a request share one
  ID, not just its final error.
- Structured search filters: exact query-parameter names, whether they
  combine with `q` or replace it, and verified column mappings for each
  (see above) all still need deciding before implementation.

## Date last updated

2026-08-01
