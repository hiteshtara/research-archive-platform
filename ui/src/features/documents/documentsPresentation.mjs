// Kuali Document Search presentation helpers - pure logic only (no
// JSX), tested the same way every other presentation-helper module in
// this project is (node:test, no @testing-library/react). See
// docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md for the
// approved five-module definition this UI searches: Award, Proposal,
// Negotiation, Subaward, IRB - never attachments, never the
// Award-nested transactional/financial document tables (Budget, Time
// and Money, Pending Transaction, SAP transmission).
export const MODULE_LABELS = Object.freeze({
  AWARD: "Award",
  PROPOSAL: "Proposal",
  NEGOTIATION: "Negotiation",
  SUBAWARD: "Subaward",
  IRB: "IRB",
});

export const MODULES = Object.freeze(Object.keys(MODULE_LABELS));

export function moduleLabel(module) {
  return MODULE_LABELS[module] ?? module;
}

export function documentSearchResultsCountLabel(totalElements) {
  const count = totalElements ?? 0;
  return `${count.toLocaleString()} document${count === 1 ? "" : "s"} found`;
}

export function documentSearchErrorMessage(status) {
  if (status === 401) {
    return "Your session has expired. Sign in again to search documents.";
  }
  if (status === 400) {
    return "That search could not be understood. Adjust your filters and try again.";
  }
  return "Document search could not be reached. Check your connection and try again.";
}

// A result set never mixes attachment rows into the document list -
// every real response only ever contains the five approved module
// values, but this makes the invariant directly testable rather than
// only true by construction (mirrors resultsBelongToAward's role for
// Award Evidence Search).
export function resultsAreApprovedModulesOnly(results) {
  return results.every((result) => MODULES.includes(result.module));
}

// Whether a document search result should be clickable - a null
// targetRoute (never expected in practice, since every approved module
// always has a routable identifier on the same row) must render as
// non-navigable rather than throw or navigate to "undefined".
export function isNavigable(result) {
  return Boolean(result.targetRoute);
}
