// Global Search presentation helpers - pure logic only (no JSX), tested
// the same way every other presentation-helper module in this project
// is (node:test, no @testing-library/react).
//
// IRB is outside current implementation scope (see docs/DECISIONS.md).
// GlobalSearchService (backend, unaffected by this UI-only change) still
// fans out to an IRB branch server-side and can legitimately return
// module: "IRB" rows or "IRB" in failedModules - filterOutIrbResults is
// the one place that strips those out client-side before anything
// renders, so no IRB result, chip, or failure message ever reaches the
// screen. totalResults/failedModules are recomputed from the filtered
// set rather than trusting the backend's raw totalResults, so the
// displayed count always matches what's actually shown.
export function filterOutIrbResults(response) {
  const results = Array.isArray(response?.results) ? response.results : [];
  const failedModules = Array.isArray(response?.failedModules)
    ? response.failedModules
    : [];

  const filteredResults = results.filter((result) => result?.module !== "IRB");
  const filteredFailedModules = failedModules.filter(
    (module) => module !== "IRB",
  );

  return {
    query: response?.query ?? "",
    totalResults: filteredResults.length,
    results: filteredResults,
    failedModules: filteredFailedModules,
  };
}

const SEMANTIC_MATCH_CHIP_LABEL = "Semantic match";

// Describes what a single result card should render, given the exact
// GlobalSearchItem the backend returned - the API is the source of
// truth for real data (identifier/title/PI/sponsor/status; see
// GlobalSearchService's semantic-search integration for how AWARD/
// PROPOSAL semantic matches get enriched with it). This only decides
// which optional lines/badges apply, so GlobalSearchPage never has to
// re-derive backend enrichment/dedup decisions in JSX.
//
// matchedCaption is deliberately null once a semantic result carries
// real enrichment (matchedField/matchedValue come back null from the
// backend in that case) - showing "Matched on: Semantic (<identifier>)"
// underneath an identifier line that already shows the same value is a
// duplicate, not new information.
export function describeResultCard(result) {
  const identifier = result?.identifier ?? "";
  const subtitle = result?.subtitle ?? null;
  const principalInvestigator = result?.principalInvestigator ?? null;
  const matchedField = result?.matchedField ?? null;
  const matchedValue = result?.matchedValue ?? null;
  const isSemanticMatch = result?.matchType === "RELATED";

  return {
    identifier,
    title: result?.title ?? identifier,
    identifierLine: subtitle ? `${identifier} • ${subtitle}` : identifier,
    showSemanticChip: isSemanticMatch,
    semanticChipLabel: SEMANTIC_MATCH_CHIP_LABEL,
    piLine: principalInvestigator ? `PI: ${principalInvestigator}` : null,
    matchedCaption: matchedField
      ? `Matched on: ${matchedField}${matchedValue ? ` (${matchedValue})` : ""}`
      : null,
  };
}
