// Pure presentation-helper functions for AwardVersionSearchPage (the
// Historical Award Records explorer) - kept dependency-free, plain JS,
// and node:test-able the same way awardSearchPresentation.mjs is, since
// this project has no component-render test setup.
//
// describeVersionSearchResults exists specifically so rendering never
// throws when the response shape drifts from what the current UI build
// expects - every field is derived with a safe fallback instead of
// being read directly off a response object that might not have the
// expected nested shape yet.

export function describeVersionSearchResults(response) {
  const content = Array.isArray(response?.content) ? response.content : [];

  return {
    totalElements:
      typeof response?.totalElements === "number"
        ? response.totalElements
        : 0,
    totalPages:
      typeof response?.totalPages === "number" ? response.totalPages : 0,
    content,
  };
}

// A version row's own detail route - always keyed by the exact award_id
// selected, never by award_number alone, so opening a historical row
// never silently lands on the current version instead.
export function versionDetailPath(hit) {
  return `/awards/${hit.awardId}`;
}

export function versionCurrentLabel(hit) {
  return hit?.primaryCurrent ? "Current" : "Historical";
}
