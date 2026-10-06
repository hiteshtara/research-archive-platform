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

/*
 * Wildcard guidance for the free-text Award search box (QA TC-009).
 *
 * The old copy advertised "*text*", the one wildcard form that changes
 * nothing: AwardSearchPattern already wraps a plain term in %...%, so
 * "*105698*" and "105698" run the identical query. What a wildcard
 * actually buys is anchoring, and that was never mentioned - so a
 * reader following the hint could not tell wildcards did anything.
 *
 * SHARED BY THE TWO AWARD PAGES AND NO OTHERS. Awards and Historical
 * Awards both run their free-text box through AwardSearchPattern, so
 * this is true on both. Proposal, Negotiation, Subaward and Global
 * Search bind the raw term into '%' || :query || '%' in SQL and never
 * translate '*' - there "105698*" would match those literal characters
 * and find nothing, so this sentence must never reach those pages.
 */
export const AWARD_WILDCARD_HINT =
  "Searches match anywhere in the field. Use 105698* for starts-with, "
  + "or *105698 for ends-with.";
