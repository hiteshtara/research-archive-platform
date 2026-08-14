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
