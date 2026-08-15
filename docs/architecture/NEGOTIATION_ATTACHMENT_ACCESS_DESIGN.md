# Negotiation Attachment Access Design

## Purpose

Records the product decision governing who can see Negotiation
attachments and how the legacy Kuali `RESTRICTED` flag is (and is not)
used, plus the centralized attachment-authorization model this decision
was folded into. Written so a future session does not re-derive this
from scratch or, worse, reintroduce access blocking based on
`RESTRICTED` - a real risk, since a naive reading of "RESTRICTED='Y'"
looks like an access-control field even though this project has
deliberately decided it is not one.

## Decision history (both parts final; the second supersedes only the
## *breadth* of the first, not its RESTRICTED-handling rule)

1. **Initial decision**: the Research Archive is an open-book internal
   archive - every authenticated user may list/view/download Negotiation
   attachments regardless of the legacy `RESTRICTED` value.
2. **Superseding decision**: attachment access across every domain
   (Award, Proposal, Subaward, Negotiation) - not just Negotiation - is
   restricted to members of a Cognito group, `ArchiveAttachmentViewer`.
   Business-record access (everything that isn't an attachment) is
   unaffected and remains available to every authenticated user, exactly
   as before. Initially the group contains only one verified account.

**What did not change between these two decisions, and must never
change**: the legacy `RESTRICTED` flag itself is never an access-control
signal, in either version of the policy. An `ArchiveAttachmentViewer`
member sees `Y` and `N` Negotiation attachments identically - same
list behavior, same download behavior, same 200 response. The flag is
preserved and displayed as historical Kuali metadata only. **Do not
gate any endpoint, query, or UI element on this flag's value** - the
group membership is the only gate.

## Authorization model

- `AttachmentAuthorizationService`
  (`api/src/main/java/edu/bu/archive/application/security/AttachmentAuthorizationService.java`)
  is the single authorization point. Its `requireAttachmentAccess(Authentication)`
  method is called as the first line of every attachment-related
  controller method, before any repository/service call runs - so no
  attachment metadata (filenames, descriptions, IDs, counts,
  availability) is ever computed for an unauthorized caller.
- Gated endpoints: `AwardV1Controller` (`/attachments`,
  `/attachments/{id}/download`), `ProposalV1Controller` (same shape),
  `SubawardArchiveController` (same shape), `NegotiationArchiveController`
  (same shape), `AttachmentSearchController` (`/api/v1/attachments/search`
  - the cross-domain Archived File Finder), and `ExplorerController`'s
  `/attachments` endpoint (dev-only, `app.explorer.enabled`-gated, but
  still covered for defense in depth).
- **Cognito wiring already existed and needed no changes**:
  `SecurityConfiguration.jwtAuthenticationConverter()` already mapped
  every `cognito:groups` claim entry to a `ROLE_<group>` Spring
  authority. Creating a Cognito group named exactly
  `ArchiveAttachmentViewer` and adding a user to it is sufficient for
  that user's next-issued JWT to carry `ROLE_ArchiveAttachmentViewer`
  automatically - no code change needed for the claim-to-authority
  mapping itself.
- A missing or malformed `cognito:groups` claim already produces zero
  group authorities in the existing converter, so
  `AttachmentAuthorizationService` denies by default in that case with
  no special-casing required.
- Unauthenticated request: 401 (unchanged, global
  `SecurityConfiguration` rule, runs before any controller code).
  Authenticated without the group: 403
  (`AttachmentAccessDeniedException`, mapped by `GlobalExceptionHandler`).
  Authenticated with the group: normal response.
- Frontend hiding (`ui/src/auth.ts`'s `hasAttachmentAccess()`, used by
  `AppLayout.tsx` to hide the "Archived File Finder" nav entry, and by
  `NegotiationWorkspacePage.tsx` to skip the attachments query and show
  an explicit "Access denied" message) is a **convenience only** -
  every attachment endpoint re-checks the real Cognito group on every
  request regardless of what the frontend shows or hides. Award/
  Proposal/Subaward attachment-section frontend hiding was **not**
  implemented in this pass (backend enforcement for those three is
  complete and tested; only the UI-hiding convenience is outstanding) -
  a reasonable follow-up, not a security gap.

## RESTRICTED storage

- Source: `KCOEUS.NEGOTIATION_ATTACHMENT.RESTRICTED` (`'Y'`/`'N'`, no
  nulls observed - live Oracle staging count: 20,406 `Y`, 8,517 `N`,
  28,923 total).
- Already captured end-to-end before this design doc existed:
  `NegotiationAttachmentPlugin` (`etl/archive_etl/attachments/plugins/negotiation.py`)
  puts the raw value into `AttachmentRecord.attributes["restricted"]`,
  which `attachment_file.py`'s generic sync path folds into
  `archive.archived_attachment.source_metadata` (JSONB) for every
  module, Negotiation included. Verified live: all 28,923 already-loaded
  Negotiation attachment rows have `source_metadata->>'restricted'`
  populated as a clean JSON string ("Y"/"N", zero nulls), exactly
  matching Oracle.
- **New in this change**: `V076__add_archived_attachment_legacy_restricted_flag.sql`
  promotes this into a dedicated, indexed column,
  `archive.archived_attachment.legacy_restricted_flag`, for reliable
  typed API access (matching how `archive.negotiation_activity.restricted`
  is already a first-class column, not JSONB, for the *activity's* own
  restricted flag - a different, unrelated field). The migration
  backfills the new column from the existing `source_metadata` JSONB
  for already-loaded rows (no Oracle re-extraction needed) and never
  removes or overwrites the JSONB value. `attachment_file.py`'s
  `_postgres_values`/`sync_postgres` now also populate this column
  directly for every future load/sync, so it stays in sync going
  forward without relying solely on the one-time backfill.
- API: `NegotiationAttachmentResponse.restrictedFlag` (String, nullable)
  and the same field surfaced by `NegotiationArchiveRepository.findAttachments`'s
  `legacy_restricted_flag AS restricted_flag` column.
- UI: `resolveRestrictedLabel` (`ui/src/features/negotiation/negotiationAttachmentPresentation.mjs`)
  renders "Marked restricted in legacy Kuali" for `Y`, "Not restricted
  in legacy Kuali" for `N`, an honest "unknown" label for null/missing,
  and the raw value verbatim for anything else - never silently coerced
  to Y/N. Shown as a plain column in `NegotiationWorkspacePage.tsx`'s
  attachments table for every row, restricted or not; filenames and
  descriptions are never hidden based on this value.

## Reconciliation findings this design relies on (verified live, dev RDS
## via approved read-only ECS diagnostics, never local Postgres)

- Negotiation business data (`archive.negotiation` and its 4 child
  tables) is completely and exactly loaded: 10,775 = 10,775 Negotiations,
  exact ID-set match against Oracle (0 missing, 0 extra), exact status/
  type-distribution match, 0 orphan child rows. Re-confirmed live a
  second time after an unrelated report of "87 results for a '420'
  search" turned out to be ordinary substring-search behavior (`ILIKE
  '%420%'` against several columns, not a total-count comparison) -
  negotiation_id 420 itself is a real, present record.
- Negotiation attachment metadata is completely loaded and exactly
  reconciled against Oracle at the row level (attachment_id, activity_id,
  negotiation_id, file_id, restricted value - all 28,923 rows, 0
  mismatches, 0 orphaned parents).
- **Important, honest limitation this design does not attempt to fix**:
  of the 20,406 `RESTRICTED='Y'` attachments, **0** have a real S3
  object (`archive_status='ARCHIVED'`) - all 20,406 are `MISSING`
  because the source Oracle BLOB (`KCOEUS.ATTACHMENT_FILE.FILE_DATA`)
  was never captured for any of them (verified directly in Oracle: 0 of
  20,406 `Y` rows have a non-null `FILE_DATA`). Only the 2,342 `N`/
  `ARCHIVED` attachments have an actual downloadable file today. This
  access-control work makes the `Y` attachments *eligible* to be listed
  and downloaded by an authorized viewer - it does not and cannot make
  their binaries exist. `downloadable`/View-Download suppression already
  and correctly reflects this (unrelated to `RESTRICTED`).

## Explicit non-goals (do not do these without a fresh, deliberate
## decision)

- Never derive a 403, a filtered result, or a hidden field from
  `restrictedFlag`/`RESTRICTED`/`legacy_restricted_flag` anywhere.
- Never re-run the Negotiation ETL to "unlock" the `Y` attachments - no
  amount of reloading recovers a BLOB that was never captured in Oracle.
- Never assume Award/Proposal/Subaward attachment access predates this
  design's group requirement - as of this change, it does not; all four
  domains are gated identically.

## Data model: how an attachment links back to its Negotiation

```
NEGOTIATION_ATTACHMENT.ACTIVITY_ID
    -> NEGOTIATION_ACTIVITY.NEGOTIATION_ACTIVITY_ID
    -> NEGOTIATION_ACTIVITY.NEGOTIATION_ID
```

An attachment never references its Negotiation directly in Oracle - it
hangs off the attachment's parent `NEGOTIATION_ACTIVITY` row, which in
turn belongs to exactly one `NEGOTIATION`. The archive schema mirrors
this: `archive.archived_attachment.source_metadata->>'activity_id'`
(exposed as `activityId` in `NegotiationAttachmentResponse`) is the
`NEGOTIATION_ACTIVITY_ID`, and `archive.archived_attachment.parent_record_id`
is the `NEGOTIATION_ID` directly (denormalized at load time - the
generic `archived_attachment` table does not itself join through
`negotiation_activity`).

**Negotiation ID is the sole business identifier.** `NEGOTIATION.NEGOTIATION_ID`
(and, in the archive, `archive.negotiation.negotiation_id`) is what
Archived File Finder's "Negotiation ID" field searches
(`AttachmentSearchRepository.searchNegotiationAttachments`'s
`n.negotiation_id = :recordId`) and what every UI route
(`/negotiations/{id}`) and API path (`/api/negotiations/{id}/...`) key
on. **Negotiation Association ID is a different value entirely** -
`NEGOTIATION.ASSOCIATED_DOCUMENT_ID` (`archive.negotiation.associated_document_id`),
interpreted per `NEGOTIATION_ASSC_TYPE_ID`/`negotiationAssociationTypeCode`
(e.g. `AWD`→an Award number, `IP`→a Proposal number, `NO`→no
association at all). It identifies whatever record the Negotiation is
*associated with*, not the Negotiation itself, and must never be
substituted for Negotiation ID in a query, a route, a test fixture, or
a UI label - see the fixture below for how close the two values can
coincidentally be.

## Reference fixture (real, live-verified 2026-08-14 - Oracle staging
## and dev RDS, exact match)

```
negotiation_id       = 420
association_id       = 419   (a different field/value - see above; never
                               confused with negotiation_id in code or tests)
activity_id           = 10134
attachment_id          = 101   (source_attachment_id / oracleAttachmentId)
file_id                 = 24828  (source_file_id / oracleFileId)
description              = "Kotton Proteostasis"
restricted                = "N"
```

Used throughout `NegotiationArchiveRepositoryTest`,
`NegotiationAttachmentContractTest`, and
`NegotiationArchiveRepositorySchemaIntegrationTest` as the canonical
real-data regression fixture - prefer extending coverage with this
exact record over inventing a new synthetic one, so a real production
row keeps getting exercised by the suite.

## Oracle/RDS population (live-verified 2026-08-14, post-`V076`,
## post-alias-fix - see "Two incidents" below)

| Metric | Count |
|---|---|
| Total attachment metadata rows (`archive.archived_attachment`, all `NEGOTIATION`) | 28,923 |
| `legacy_restricted_flag = 'N'` | 8,517 |
| `legacy_restricted_flag = 'Y'` | 20,406 |
| `archive_status = 'ARCHIVED'` (real binary present) | 2,342 |
| `archive_status = 'MISSING'` (source Oracle BLOB never captured) | 26,581 |

All four counts are exact row-level reconciliations against Oracle
staging (0 mismatches, 0 orphaned parents) - not estimates. Every `Y`
row is `MISSING` (0 of 20,406 have a real BLOB); the 2,342 downloadable
rows are all `N`. This is a permanent source-data gap, not a load
defect - see "Reconciliation findings" above.

## Two incidents, same release, both fixed and deployed (2026-08-14)

Deploying the API before its dependent migration reached dev RDS, then
fixing that, surfaced a second, unrelated bug - useful to read together
since both produced the same symptom (a 500 on
`GET /api/negotiations/{id}/attachments`) from two independent causes.

**Root cause #1 - migration never applied.** The API expected
`archive.archived_attachment.legacy_restricted_flag` to exist
(`NegotiationArchiveRepository.findAttachments`'s
`legacy_restricted_flag AS restricted_flag`), but `V076` had only been
committed, not applied - **API startup does not run migrations** (see
CLAUDE.md's "Migrations are not run by Spring Boot"; this is that fact's
concrete failure mode, not a new mechanism). Fixed by running the ETL
loader's `--migrate-only` mode from a freshly built image containing
`V076` (the previously-registered loader image predated the commit
that added it - loader images do not auto-rebuild when a migration is
merely committed).

**Root cause #2 - three unaliased SELECT columns.** Once `V076` was
applied, the same endpoint kept failing for every Negotiation with real
attachment rows while succeeding for one with zero rows - the
signature of a row-mapping bug, not a SQL bug.
`NegotiationArchiveRepository.findAttachments` selected
`archived_attachment_id`, `original_file_name`, and `byte_size`
**without aliases**, but `NegotiationAttachmentResponse`'s record
components are `attachmentId`/`fileName`/`fileSize`. Spring's
`JdbcClient` row mapper only does underscore/camelCase conversion
between a ResultSet column and a DTO property - it cannot infer an
arbitrarily different name - so it threw `PSQLException: The column
name attachment_id was not found in this ResultSet`, but **only once a
real row existed to map** (an empty `ResultSet` never calls
`RowMapper.mapRow()` at all, which is exactly why this went undetected
through every mocked-`JdbcClient` test and even through this session's
own first real-schema integration test, which queried an empty table).
Fixed with three aliases, no SQL logic change:

```sql
archived_attachment_id AS attachment_id
original_file_name AS file_name
byte_size AS file_size
```

**Testing rule this incident established**: a repository schema
integration test must seed and map **at least one real row** through
the actual `RowMapper` - an empty-result test proves the SQL is
syntactically valid against the real schema, but cannot detect a
ResultSet-to-DTO mapping failure, since the mapper is never invoked for
zero rows. `NegotiationArchiveRepositorySchemaIntegrationTest` now
seeds three regression fixtures for exactly this reason:

- **negotiation_id=257** - zero attachments (the real "empty is
  correct" case - proves the endpoint doesn't error just because a
  Negotiation happens to have no attachments).
- **negotiation_id=420** - one attachment (the reference fixture
  above; every field asserted against its real value).
- **negotiation_id=786** - five attachments across two activities
  (`10293`, `10294`) - proves multi-row mapping and the
  `ORDER BY activity_id, archived_attachment_id` ordering, not just a
  single lucky row.

## Deployment verification record (2026-08-14, both incidents' fixes)

- API task-definition revision **59** (following revision 58's V076-
  only fix, itself following revision 57's pre-fix build), service
  stable.
- `GET /actuator/health` → `200 {"status":"UP"}`.
- Unauthenticated attachment requests → `401` (unchanged throughout
  both incidents).
- Authenticated, not in `ArchiveAttachmentViewer` → `403
  ATTACHMENT_ACCESS_DENIED`, zero metadata in the response body
  (unchanged throughout).
- Authorized (`ArchiveAttachmentViewer` member) browser verification:
  successful - negotiation 420 loads its attachment, matching the
  reference fixture above.

## Tracked, unresolved: two stray local migration files (not part of
## this release, not repaired here)

Discovered while confirming `V076` was the only pending migration -
both are local-only and were never committed to any branch
(`git log --all` shows zero history for either), so neither was ever
shipped in a deployed loader image.

- **`V073__extend_subaward_attachment_archive_status.sql`** -
  uncommitted and correctly unapplied on dev RDS (confirmed via
  `pg_get_constraintdef`: the live constraint is still in its
  pre-`V073` form, no partial application). Harmless as long as it
  stays uncommitted; unrelated to Negotiation.
- **`V071__extend_search_embedding_for_evidence_documents.sql`** - the
  actual open concern. Uncommitted, yet **already marked applied** in
  dev RDS's `public.schema_migration` (`version = 71` present). Some
  commit's DDL effect reached dev RDS and then the commit itself
  disappeared from git history (most plausibly a squash/rebase/reset)
  - a reproducibility gap: a fresh clone cannot currently reconstruct
  dev RDS's real schema from git history alone. Not investigated or
  repaired as part of this release (out of scope - semantic search/
  evidence indexing, not Negotiation). If picked up later: start with
  `git reflog`/dangling-commit search for a lost commit touching this
  exact filename before deciding whether to formally commit, reconstruct,
  or drop it.
