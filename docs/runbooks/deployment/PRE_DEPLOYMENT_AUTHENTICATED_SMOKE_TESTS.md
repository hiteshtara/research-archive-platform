# Pre-deployment authenticated smoke tests

Some UI behaviour cannot be verified locally, because it sits behind a
real authorization boundary that local development deliberately does not
weaken. Those items are listed here and must be exercised by a signed-in
user with the required group membership, in an authorized environment,
**before** the change ships.

This list is a gate, not a suggestion. An item here has passing unit
tests, a clean build and a rendered shell, but its protected interaction
has never actually run.

## Why these cannot be covered locally

`AuthGate` requires a real Cognito session and has no dev bypass. A
temporary local stub of `accessToken()` will get a page to render, but a
stubbed token carries no group membership, so anything guarded by
`ArchiveAttachmentViewer` correctly returns "Access denied".

That boundary must not be weakened or bypassed to make a local check
pass. The correct response is to verify in an environment where the
tester genuinely holds the role.

---

## Open items

### Archived File Finder — protected result cards and downloads

Branch: `ui/historical-awards-and-file-finder-shared-search` (`ef4c700`)

The page was migrated to the shared `SearchPageLayout` and its results
now render through `ResultSurface` with explicit View and Download
actions rather than the navigational `ResultCard` — a file's business
action is Download, not navigation, so it deliberately has no `href`.

Verified locally: the shared page shell, the title/subtitle, every
identifier filter, record-type-dependent field visibility, the explicit
Search action, Clear filters, and the "Access denied" state itself.

**Not verified locally — requires a user in `ArchiveAttachmentViewer`:**

1. A result row renders with the file name as the primary line, the
   record-type chip, the availability status chip, the parent
   record/sequence/document line and the type/size/date metadata line.
2. The **Download** action downloads the correct file, with the correct
   file name, for an `ARCHIVED` result.
3. The Download action is **disabled** for a result that is not
   downloadable (no attachment id, or a non-`ARCHIVED` availability
   status), and its tooltip explains why.
4. The in-progress spinner replaces the download icon while a download
   runs, and only for the row being downloaded.
5. A download failure surfaces the error banner and can be dismissed.
6. The **View** action opens the correct parent record, and is disabled
   when no record path resolves.
7. Pagination preserves the identifier filters across pages.

Until items 1-7 pass, treat the Archived File Finder migration as
unverified for its primary interaction, even though its tests, lint and
build are clean.

### Attachment downloads in other modules

The same `ArchiveAttachmentViewer` boundary guards Award, Proposal,
Negotiation and Subaward attachment downloads. Any change touching those
surfaces inherits this gate.

---

## Recording a result

When an item is verified, record the environment, the date, the account
used (never a credential) and what was observed, then remove it from the
open list above.
