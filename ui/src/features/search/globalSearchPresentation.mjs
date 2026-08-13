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
