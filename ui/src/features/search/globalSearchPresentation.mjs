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

  // Carried through, with IRB's own entry dropped for the same reason its
  // rows are: the page must not report a count for records it removed. The
  // remaining entries are the backend's own, unmodified - they describe what
  // each module matched, which filtering IRB out of the list does not change.
  const moduleTotals = Array.isArray(response?.moduleTotals)
    ? response.moduleTotals.filter((total) => total?.module !== "IRB")
    : [];

  return {
    query: response?.query ?? "",
    totalResults: filteredResults.length,
    results: filteredResults,
    failedModules: filteredFailedModules,
    moduleTotals,
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
/*
 * Where each record type's own search lives, so a capped result set can
 * offer the page that CAN show all of it (QA TC-040). Taken from the live
 * navigation, not guessed: Global Search itself is /search, and the module
 * pages are the routes the sidebar links to.
 *
 * All four were checked on DEV on 2026-11-10, including that each ACCEPTS
 * ?q= and actually runs the search - a link that lands on an empty page
 * would be worse than no link:
 *
 *   /awards/search?q=   -> /api/v1/awards/search?...&q=
 *   /proposals?q=       -> /api/proposals/search?...&query=
 *   /negotiations?q=    -> /api/negotiations?...&query=
 *   /subawards?q=       -> /api/subawards?...&query=
 *
 * Note /negotiations ignores ?query= and reads ?q= only, which is why the
 * link below uses q for every module rather than matching each API's own
 * parameter name.
 */
const MODULE_SEARCH_ROUTES = {
  AWARD: "/awards/search",
  PROPOSAL: "/proposals",
  NEGOTIATION: "/negotiations",
  SUBAWARD: "/subawards",
};

const MODULE_LABELS = {
  AWARD: "Awards",
  PROPOSAL: "Proposals",
  NEGOTIATION: "Negotiations",
  SUBAWARD: "Subawards",
};

export function moduleSearchRoute(module) {
  return MODULE_SEARCH_ROUTES[module] ?? null;
}

export function moduleLabel(module) {
  return MODULE_LABELS[module] ?? module;
}

/*
 * A Negotiation is addressed by its Negotiation ID - /negotiations/6447 -
 * but the API's `identifier` for it is the DOCUMENT number, so the card
 * showed 760067 and the ID appeared nowhere (QA TC-040). A tester had no way
 * to get from the card to the record they were reading about.
 *
 * Both are shown, each labelled, rather than picking a winner: the document
 * number is what people quote from Kuali, and the ID is what the archive is
 * addressed by.
 */
function negotiationIdentifierLine(result) {
  const id = result?.recordId;
  const document = result?.identifier;
  const parts = [];
  if (id !== null && id !== undefined) {
    parts.push(`Negotiation ${id}`);
  }
  if (document) {
    parts.push(`Document ${document}`);
  }
  return parts.join(" \u00b7 ");
}

export function describeResultCard(result) {
  const identifier = result?.identifier ?? "";
  const subtitle = result?.subtitle ?? null;
  const principalInvestigator = result?.principalInvestigator ?? null;
  const matchedField = result?.matchedField ?? null;
  const matchedValue = result?.matchedValue ?? null;
  const isSemanticMatch = result?.matchType === "RELATED";

  /*
   * The Negotiation ID, finally rendered.
   *
   * negotiationIdentifierLine was written for QA TC-040 and then never
   * reached a screen: the page showed `identifier` as the heading and a
   * "Doc <number>" chip beside it, and since the API's `identifier` for a
   * Negotiation IS its document number, the card printed that number twice
   * and the Negotiation ID nowhere. Verified against the running revision
   * on 2026-11-10: recordId 12724 and document 1149373 are different
   * values, and only 1149373 was ever displayed.
   *
   * So the heading becomes the labelled pair, and the Doc chip is dropped
   * for this module alone - it would now repeat what the heading says. Each
   * identifier appears exactly once.
   */
  const negotiationLine =
    result?.module === "NEGOTIATION" ? negotiationIdentifierLine(result) : "";

  return {
    identifier,
    /*
     * What the card prints as its heading. Identical to `identifier` for
     * every module but Negotiation, which needs both of its identifiers.
     */
    identifierHeading: negotiationLine || identifier,
    /*
     * Whether the card should add its own "Doc <number>" chip. False only
     * when the heading already names the document number.
     */
    showDocumentChip: !negotiationLine,
    title: result?.title ?? identifier,
    identifierLine:
      result?.module === "NEGOTIATION"
        ? [negotiationLine, subtitle].filter(Boolean).join(" • ")
        : subtitle
          ? `${identifier} • ${subtitle}`
          : identifier,
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

/*
 * What the result count actually means (QA TC-040).
 *
 * The page used to print one number - "104 results found" - taken from the
 * length of the result list. A searcher reasonably read that as "the archive
 * contains 104 NSF records". It did not: every module is capped at 25, and
 * the Negotiation module alone matched 234. The number was a page size
 * wearing the clothes of a finding.
 *
 * So the count is now stated as what it is, and where a module reported its
 * real total that total is named. Where a module could not count (Proposal
 * and IRB return a bare list), nothing is invented - "at least" is used,
 * which is the strongest true statement available.
 */
export function describeResultCounts(moduleTotals = [], directCount = 0) {
  const totals = Array.isArray(moduleTotals) ? moduleTotals : [];
  const capped = totals.filter((t) => t?.capped);

  if (capped.length === 0) {
    return {
      showing: directCount,
      summary:
        directCount === 1 ? "1 result found" : `${directCount} results found`,
      truncated: false,
      perModule: [],
    };
  }

  // Known totals can be added up; an unknown one makes the whole figure a
  // lower bound rather than a total, and it has to read as one.
  const anyUnknown = capped.some(
    (t) => t.totalMatches === null || t.totalMatches === undefined,
  );
  const knownTotal = totals.reduce(
    (sum, t) =>
      t?.totalMatches === null || t?.totalMatches === undefined
        ? sum
        : sum + Number(t.totalMatches),
    0,
  );

  const perModule = totals
    .filter((t) => t?.capped)
    .map((t) => ({
      module: t.module,
      label: moduleLabel(t.module),
      text:
        t.totalMatches === null || t.totalMatches === undefined
          ? `${moduleLabel(t.module)}: showing ${t.returned}, more exist`
          : `${moduleLabel(t.module)}: showing ${t.returned} of ${t.totalMatches}`,
      // Where to go to see all of them. Telling someone their results are
      // capped without saying where the rest are is only half an answer.
      href: moduleSearchRoute(t.module),
    }));

  return {
    showing: directCount,
    summary: anyUnknown
      ? `Showing ${directCount} results — at least ${knownTotal} match`
      : `Showing ${directCount} of ${knownTotal} matching records`,
    truncated: true,
    perModule,
    truncationNote:
      "Each record type returns at most 25 results, so this is the first " +
      "page rather than everything that matched. Open a record type to " +
      "page through all of its matches.",
  };
}
