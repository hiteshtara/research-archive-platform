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

export const RECORD_TYPES = ["ALL", "AWARD", "PROPOSAL", "NEGOTIATION"];

export const RECORD_TYPE_OPTIONS = [
  { value: "ALL", label: "All records" },
  { value: "AWARD", label: "Awards" },
  { value: "PROPOSAL", label: "Proposals" },
  { value: "NEGOTIATION", label: "Negotiations" },
];

export function recordTypeLabel(recordType) {
  const option = RECORD_TYPE_OPTIONS.find((entry) => entry.value === recordType);
  return option ? option.label : recordType;
}

// Which filter fields are visible/meaningful for a given record type -
// drives both what the UI renders and what hasAnyIdentifierSupplied
// checks. ALL never shows recordId/attachmentId/fileId (ambiguous
// across domains - the backend rejects them with recordType=ALL).
// PROPOSAL/NEGOTIATION never show fileId (Award-specific - neither
// Proposal's file_data_id nor Negotiation's source_file_id is a
// comparable numeric id, and neither is ever exposed to the client).
//
// NEGOTIATION never shows recordNumber either - unlike Award/Proposal,
// Kuali has no separate "Negotiation number" business identifier.
// KCOEUS.NEGOTIATION's own business identifier is NEGOTIATION_ID (the
// same value recordId already searches); the only other per-record
// value is document_number, already covered by the Workflow document
// number field. Do not confuse this with associated_document_id (a
// different value, describing whatever record negotiationAsscTypeId
// says this negotiation is associated with, e.g. an Award or Proposal
// number) - see NegotiationRowResponse.associatedDocumentId, never
// used as a search identifier here. Live-verified 2026-08-14:
// negotiation_id 420 has document_number "367821" and
// associated_document_id "419" - three different values.
export function visibleFieldsForRecordType(recordType) {
  if (recordType === "NEGOTIATION") {
    return ["documentNumber", "recordId", "attachmentId"];
  }
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
  if (recordType === "PROPOSAL") {
    return "Proposal ID";
  }
  if (recordType === "NEGOTIATION") {
    return "Negotiation ID";
  }
  return "Award ID";
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
  if (status === 403) {
    return "Access denied. Searching archived files requires membership in the ArchiveAttachmentViewer group - contact an administrator if you believe this is wrong.";
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
  if (result.recordType === "NEGOTIATION") {
    return `/negotiations/${result.parentId}`;
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

// The one place recordType decides which download client function runs -
// extracted out of ArchivedFileFinderPage.tsx's handleDownload so it is a
// plain, independently unit-testable function (no component-render
// harness exists in this codebase). downloadAward/downloadProposal are
// injected rather than imported so a test can pass spies instead of the
// real authenticated API client calls - the caller (the page) is
// responsible for currying in fileName/whatever else its real downloader
// needs, so this function's own contract stays exactly recordType +
// parentId + attachmentId in, exactly one downloader call out. Never
// catches - a downloader's rejection propagates to the caller unchanged,
// so the page's existing try/catch error handling still applies.
export function dispatchArchivedFileDownload(
  recordType,
  parentId,
  attachmentId,
  downloadAward,
  downloadProposal,
  downloadNegotiation,
) {
  if (recordType === "AWARD") {
    return downloadAward(parentId, attachmentId);
  }
  if (recordType === "PROPOSAL") {
    return downloadProposal(parentId, attachmentId);
  }
  if (recordType === "NEGOTIATION") {
    return downloadNegotiation(parentId, attachmentId);
  }
  return Promise.reject(
    new Error(`Unsupported record type for download: ${recordType}`),
  );
}
