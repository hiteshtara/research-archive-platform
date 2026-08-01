# Award Search / Hierarchy / Summary / Versions API — Design and Implementation Record

## Status

Implemented and manually verified end-to-end against the real archive
schema, running locally against the production-loaded batch-7 dataset
(282,214 `archive.award_version` rows). 38 new unit tests added; full API
suite (163 tests) passes. Not yet deployed to ECS.

## Scope

Phase 1 of the Award API build, per explicit instruction: only these four
endpoints, nothing else.

- `GET /api/awards/search?q=&page=&size=`
- `GET /api/awards/{awardNumber}/hierarchy`
- `GET /api/awards/{awardId}/summary`
- `GET /api/awards/{awardId}/versions`

Budget, Time and Money, Terms, Comments, SAP transmission history, and
Attachments endpoints are explicitly deferred to a later phase. No ETL,
Terraform, or AWS infrastructure changes. No React UI changes — the
`docs/design/award-ui-redesign-mockup.html` mockup was read only to
determine what fields the frontend will eventually need.

## Architecture

Mirrors the existing Award concrete-service pattern (no ports/use-case
layer — see `CLAUDE.md`'s "Hexagonal layout is only fully implemented for
IRB" note): `AwardArchiveController` → `AwardArchiveService` →
`AwardArchiveRepository` (`JdbcClient`, no Spring Data JPA). Not-found
propagates as plain `java.util.NoSuchElementException`, mapped to 404 by
the existing unscoped `GlobalExceptionHandler`. New DTOs live in
`adapter/in/web/dto/award/`; no JPA entity is ever returned directly from
a controller.

Two internal-only DTOs (`AwardHierarchyEdgeRow`, `AwardSummaryCardRow`)
exist purely to move data between the repository and the service's
tree-building code — they are never serialized to a client.

## Endpoints

### `GET /api/awards/search?q=&page=&size=`

Supports exact/partial/wildcard (`*text*`) award number, PI/person name,
title, sponsor code/name, lead unit number/name, and document number
(`modification_number`). `page` defaults to 0 (`@Min(0)`), `size` defaults
to 25 (`@Min(1)`, `@Max(100)`).

```
GET /api/awards/search?q=Cancer&size=2
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

### `GET /api/awards/{awardNumber}/hierarchy`

Returns the full recursive family tree rooted at
`archive.award_hierarchy.root_award_number`, resolved from whatever
award_number is requested (root, mid-tree, or leaf all resolve to the
same tree). 404 if the award_number doesn't exist in `award_version` at
all.

```
GET /api/awards/100004-00003/hierarchy
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

### `GET /api/awards/{awardId}/summary`

Keyed by the surrogate `award_id` (one specific version), not
`award_number` — deliberately different from every other endpoint here.
Compact only: no comments, Budget, Time and Money, SAP transmission
history, or attachments.

```
GET /api/awards/3/summary
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

### `GET /api/awards/{awardId}/versions`

Resolves `awardId` to its `award_number` family, then returns every
version row for that exact `award_number` (not sibling award_numbers —
see "Award number vs. sequence number" below), newest sequence first.

```
GET /api/awards/1207589/versions
```

```json
[
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
  }
]
```

(Real response for this award_number has 9 entries, sequence 9 down to
1; abbreviated here.) 404 if `awardId` doesn't exist.

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

38 new tests, all passing; full API suite (163 tests) passes.

- `AwardSearchPatternTest` (8): substring default, application wildcard
  (leading/trailing/both), literal `%`/`_`/`\` escaping, SQL-injection
  inertness, empty query.
- `AwardArchiveRepositoryTest` (10): search/count SQL shape and bound
  parameters (never concatenated), hierarchy root/edges queries,
  summary-cards empty-collection short-circuit (no query issued) and
  `IN (:awardNumbers)` binding, summary-by-id column mapping (asserting
  absence of any `fain`/`account_type` reference), award-number
  resolution, version ordering.
- `AwardArchiveServiceTest` (12): pagination clamping, wildcard/empty
  query normalization, single-node hierarchy fallback, 404s, full
  recursive tree construction with a self-referencing root, root-row
  promotion when the nominal root is missing from the edge set, 2-cycle
  termination guard, summary/versions delegation and 404s.
- `AwardArchiveControllerTest` (8): routing/parameter delegation for all
  four endpoints, default paging, and 404 propagation via
  `GlobalExceptionHandler`.

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

## Date last updated

2026-08-01
