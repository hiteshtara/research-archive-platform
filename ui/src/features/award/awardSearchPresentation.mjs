// Pure presentation-helper functions for AwardSearchPage - kept
// dependency-free, plain JS, and node:test-able the same way
// ./awardSectionsPresentation.mjs is, since this project has no
// component-render test setup.
//
// describeSearchResults exists specifically so rendering never throws
// when the search response shape drifts from what the current UI build
// expects (e.g. an old UI bundle briefly serving requests against a
// newly-deployed API during a rollout window) - every field is derived
// with a safe fallback instead of being read directly off a response
// object that might not have the expected nested shape yet.

export function describeSearchResults(response) {
  const results = response?.results;
  const content = Array.isArray(results?.content) ? results.content : [];

  return {
    totalElements:
      typeof results?.totalElements === "number" ? results.totalElements : 0,
    totalPages:
      typeof results?.totalPages === "number" ? results.totalPages : 0,
    content,
    exactDocumentMatch: response?.exactDocumentMatch ?? null,
  };
}
