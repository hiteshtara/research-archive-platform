// Archived File Finder - pure presentation helpers, mirroring
// documentsPresentation.mjs/statusPresentation.mjs's style (plain
// functions, no React/JSX, unit-testable with node:test). This page is
// deliberately distinct from Kuali Documents (DocumentsPage): it
// searches archived attachment FILES by exact identifier, not business
// RECORDS by free-text query.
//
// Phase 2 adds Proposal support and a recordType=ALL cross-domain
// search behind the same one page/one nav item - see
// AttachmentSearchService's own header comment on the backend for the
// matching validation rules this module mirrors client-side (never as
// a replacement for server-side validation, only to disable the
// Search action / show a field before a round trip would fail).

export const RECORD_TYPES = ["ALL", "AWARD", "PROPOSAL"];

export const RECORD_TYPE_OPTIONS = [
  { value: "ALL", label: "All records" },
  { value: "AWARD", label: "Awards" },
  { value: "PROPOSAL", label: "Proposals" },
];

export function recordTypeLabel(recordType) {
  const option = RECORD_TYPE_OPTIONS.find((entry) => entry.value === recordType);
  return option ? option.label : recordType;
}

// Which filter fields are visible/meaningful for a given record type -
// drives both what the UI renders and what hasAnyIdentifierSupplied
// checks. ALL never shows recordId/attachmentId/fileId (ambiguous
// across domains - the backend rejects them with recordType=ALL).
// PROPOSAL never shows fileId (Award-specific - Proposal's own file
// identifier, file_data_id, is a different concept entirely: an Oracle
// blob key, not a numeric id, and is never exposed to the client).
export function visibleFieldsForRecordType(recordType) {
  if (recordType === "PROPOSAL") {
    return ["recordNumber", "documentNumber", "recordId", "attachmentId"];
  }
  if (recordType === "ALL") {
    return ["recordNumber", "documentNumber"];
  }
  return ["recordNumber", "documentNumber", "recordId", "attachmentId", "fileId"];
}

export function recordNumberFieldLabel(recordType) {
  if (recordType === "AWARD") {
    return "Award number";
  }
  if (recordType === "PROPOSAL") {
    return "Proposal number";
  }
  return "Record number";
}

export function recordIdFieldLabel(recordType) {
  return recordType === "PROPOSAL" ? "Proposal ID" : "Award ID";
}

// At least one exact identifier - among the fields actually visible
// for the current recordType - is required before a search is
// submitted. This mirrors the backend's own rejection of an all-blank
// request (400, never "match everything"), applied client-side so the
// Search action can be disabled instead of round-tripping to the API
// only to fail.
export function hasAnyIdentifierSupplied(filters) {
  const fields = visibleFieldsForRecordType(filters.recordType);
  return fields.some((field) => {
    const value = filters[field];
    return typeof value === "string" && value.trim().length > 0;
  });
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
    return "That search could not be understood. Check the identifiers and try again.";
  }
  return "Archived file search could not be reached. Check your connection and try again.";
}

// availabilityStatus is always one of exactly four server-derived
// values (see AttachmentSearchService.resolveAvailabilityStatus on the
// backend, shared by both Award and Proposal results) - this never
// invents a fifth, falling back to "default" for anything unrecognized
// rather than guessing a color.
const AVAILABILITY_CHIP_COLORS = {
  Available: "success",
  "Pending upload": "warning",
  Failed: "error",
  "Source file unavailable": "default",
};

export function resolveAvailabilityChipColor(availabilityStatus) {
  return AVAILABILITY_CHIP_COLORS[availabilityStatus] ?? "default";
}

// One result row is one authoritative attachment relationship
// (attachmentId within its own recordType), not one physical file -
// recordType is included because an Award attachmentId and a Proposal
// attachmentId are independent id spaces that could otherwise collide
// in a recordType=ALL result set.
export function archivedFileResultKey(result) {
  return `${result.recordType ?? "unknown"}-${result.parentId ?? "unknown"}-${result.attachmentId ?? "unknown"}`;
}

export function formatSourceDateLabel(sourceDate) {
  return sourceDate ?? "Source date unknown";
}

// The exact-version route each recordType opens - Award's own
// AwardDashboardPage (/awards/:awardId) and Proposal's own
// ProposalDashboardPage (/proposals/dashboard/:proposalId), both
// already keyed by the exact surrogate id (one specific version), the
// same id this result's own parentId already carries. Returns null
// for a recordType this page doesn't know how to view (defensive -
// should never happen given RECORD_TYPES, but never guesses a route).
export function resolveRecordViewPath(result) {
  if (result.parentId === null || result.parentId === undefined) {
    return null;
  }
  if (result.recordType === "AWARD") {
    return `/awards/${result.parentId}`;
  }
  if (result.recordType === "PROPOSAL") {
    return `/proposals/dashboard/${result.parentId}`;
  }
  return null;
}

export function parseRecordTypeParam(value) {
  const upper = (value ?? "").toUpperCase();
  return RECORD_TYPES.includes(upper) ? upper : "ALL";
}

export function parseVersionFilterParam(value) {
  const lower = (value ?? "").toLowerCase();
  return lower === "current" || lower === "historical" ? lower : "all";
}
