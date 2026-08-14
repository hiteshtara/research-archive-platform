// Negotiation attachment presentation helpers - pure functions, no
// React/JSX, unit-testable with node:test (mirrors
// archivedFileFinderPresentation.mjs's own convention). The legacy
// Kuali RESTRICTED value is informational only - it is displayed, never
// used to hide, disable, or gate anything. See
// docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md.

// Oracle's real, observed values are exactly "Y"/"N" (confirmed live -
// 20,406 Y, 8,517 N, 0 null/other, out of 28,923) - but this is
// deliberately honest about any other raw value ever appearing (a
// future Kuali export drift, a legacy code this project hasn't seen
// yet) rather than assuming only two values are possible.
export function resolveRestrictedLabel(restrictedFlag) {
  if (restrictedFlag === "Y") {
    return "Marked restricted in legacy Kuali";
  }
  if (restrictedFlag === "N") {
    return "Not restricted in legacy Kuali";
  }
  if (restrictedFlag === null || restrictedFlag === undefined || restrictedFlag === "") {
    return "Restricted status unknown in legacy Kuali";
  }
  // An unexpected/other raw legacy value - shown verbatim rather than
  // silently coerced into Y/N, so a future real value is never hidden.
  return `Legacy Kuali RESTRICTED value: ${restrictedFlag}`;
}

// The description is the primary, human-meaningful label (e.g. "Kotton
// Proteostasis") - fileName is just the archived file's on-disk name,
// often uninformative. When description is missing/blank, fall back to
// the raw Oracle attachment ID rather than showing nothing, since that
// ID is still a stable way for a user to identify the record with staff
// who have Oracle/Kuali access.
export function resolveAttachmentDisplayLabel(attachment) {
  const description = attachment?.description;
  if (typeof description === "string" && description.trim() !== "") {
    return description;
  }
  const oracleAttachmentId = attachment?.oracleAttachmentId;
  if (oracleAttachmentId !== null && oracleAttachmentId !== undefined) {
    return `Attachment ${oracleAttachmentId}`;
  }
  return "Untitled attachment";
}

// Secondary metadata line: Oracle-native identifiers a user can quote
// back to support staff, never storage internals (S3 bucket/key,
// checksum, BLOB id).
export function resolveAttachmentIdentifierSummary(attachment) {
  const parts = [];
  if (attachment?.activityId !== null && attachment?.activityId !== undefined) {
    parts.push(`Activity ${attachment.activityId}`);
  }
  if (
    attachment?.oracleAttachmentId !== null &&
    attachment?.oracleAttachmentId !== undefined
  ) {
    parts.push(`Attachment ${attachment.oracleAttachmentId}`);
  }
  if (
    typeof attachment?.oracleFileId === "string" &&
    attachment.oracleFileId.trim() !== ""
  ) {
    parts.push(`File ${attachment.oracleFileId}`);
  }
  return parts.join(" · ");
}
