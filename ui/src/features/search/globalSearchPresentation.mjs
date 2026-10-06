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
    // The identifier is the result card's own first line, so a card
    // that shows it there uses subtitleLine for the secondary detail
    // instead of identifierLine, which would repeat the identifier.
    subtitleLine: subtitle,
    showSemanticChip: isSemanticMatch,
    semanticChipLabel: SEMANTIC_MATCH_CHIP_LABEL,
    piLine: principalInvestigator ? `PI: ${principalInvestigator}` : null,
    matchedCaption: matchedField
      ? `Matched on: ${matchedField}${matchedValue ? ` (${matchedValue})` : ""}`
      : null,
  };
}

/*
 * Direct and related results are different kinds of answer and are
 * presented as such (QA TC-041).
 *
 * A semantic result is a suggestion: it comes from meaning rather than
 * from a name, number or field the searcher typed, and it is useful
 * exactly when someone used a different word than the archive does.
 * Withholding those was tried and rejected - the fix is to stop them
 * being mistaken for direct matches, not to throw them away.
 */
const RELATED_MATCH_TYPE = "RELATED";

export function isRelatedResult(result) {
  return result?.matchType === RELATED_MATCH_TYPE;
}

export function splitDirectAndRelated(results = []) {
  const direct = [];
  const related = [];
  for (const result of results) {
    (isRelatedResult(result) ? related : direct).push(result);
  }
  return { direct, related };
}

/*
 * What the page should say and offer, given what came back.
 *
 * The distinction that matters most here: a module that FAILED is not
 * a module that found nothing. Telling someone "no matches" when the
 * search did not actually run is a wrong answer dressed as a confident
 * one, so a failure is always reported and never collapsed into an
 * empty result.
 */
export function describeGlobalSearchOutcome({
  results = [],
  failedModules = [],
  relatedRevealed = false,
} = {}) {
  const { direct, related } = splitDirectAndRelated(results);
  const searchIncomplete = failedModules.length > 0;

  return {
    direct,
    related,
    directCount: direct.length,
    relatedCount: related.length,
    searchIncomplete,
    failedModules,
    // Only ever claimed when every module actually answered.
    showNoDirectMatches: direct.length === 0 && !searchIncomplete,
    // Offered rather than shown: with nothing direct, related results
    // stay behind a deliberate action.
    offerRelatedToggle: direct.length === 0 && related.length > 0,
    showRelatedSection:
      related.length > 0 && (direct.length > 0 || relatedRevealed),
    relatedHeading: "Related results",
    relatedExplanation:
      "Found by meaning rather than by an exact word, so these may not be what you meant.",
    showRelatedActionLabel:
      related.length === 1
        ? "Show 1 related result"
        : `Show ${related.length} related results`,
  };
}

export function incompleteSearchMessage(failedModules = []) {
  if (failedModules.length === 0) {
    return null;
  }
  const names = failedModules.join(", ");
  return (
    `${names} could not be searched just now, so this is not the whole ` +
    "archive. Try again shortly before concluding there is nothing to find."
  );
}

export function noDirectMatchesMessage(query) {
  const trimmed = String(query ?? "").trim();
  return trimmed
    ? `No direct matches for \u201c${trimmed}\u201d.`
    : "No direct matches.";
}
