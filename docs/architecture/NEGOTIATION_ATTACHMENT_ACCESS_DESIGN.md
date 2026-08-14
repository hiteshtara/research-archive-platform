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
