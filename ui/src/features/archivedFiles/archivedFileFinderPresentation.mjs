// Archived File Finder (Phase 1: Award only) - pure presentation helpers,
// mirroring documentsPresentation.mjs/statusPresentation.mjs's style
// (plain functions, no React/JSX, unit-testable with node:test). This
// page is deliberately distinct from Kuali Documents (DocumentsPage):
// it searches archived attachment FILES by exact identifier, not
// business RECORDS by free-text query.

// At least one exact identifier is required before a search is
// submitted - this mirrors the backend's own rejection of an all-blank
// request (400, never "match everything"), applied client-side so the
// Search action can be disabled instead of round-tripping to the API
// only to fail.
export function hasAnyIdentifierSupplied(filters) {
  return [
    filters.awardNumber,
    filters.documentNumber,
    filters.awardId,
    filters.attachmentId,
    filters.fileId,
  ].some((value) => typeof value === "string" && value.trim().length > 0);
}

export function archivedFileResultsCountLabel(totalElements) {
  const count = totalElements ?? 0;
  return `${count.toLocaleString()} file${count === 1 ? "" : "s"} found`;
}

export function archivedFileSearchErrorMessage(status) {
  if (status === 401) {
    return "Your session has expired. Sign in again to search archived files.";
  }
  if (status === 400) {
    return "That search could not be understood. Check the identifier and try again.";
  }
  return "Archived file search could not be reached. Check your connection and try again.";
}

// availabilityStatus is always one of exactly four server-derived
// values (see AwardArchiveService.resolveAvailabilityStatus on the
// backend) - this never invents a fifth, falling back to "default" for
// anything unrecognized rather than guessing a color.
const AVAILABILITY_CHIP_COLORS = {
  Available: "success",
  "Pending upload": "warning",
  Failed: "error",
  "Source file unavailable": "default",
};

export function resolveAvailabilityChipColor(availabilityStatus) {
  return AVAILABILITY_CHIP_COLORS[availabilityStatus] ?? "default";
}

// One result row is one authoritative award_attachment relationship
// (attachmentId), not one physical file (fileId) - a shared physical
// file legitimately appears as multiple rows with different
// attachmentId/parentId, so the key must combine both to stay unique
// and stable.
export function archivedFileResultKey(result) {
  return `${result.parentId ?? "unknown"}-${result.attachmentId ?? "unknown"}`;
}

export function formatSourceDateLabel(sourceDate) {
  return sourceDate ?? "Source date unknown";
}
