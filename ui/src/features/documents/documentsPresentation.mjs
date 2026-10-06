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

// --- Kuali Document Explorer (extends the search above with facets,
// normalized status, unit/person/sponsor filters) - see
// docs/architecture/KUALI_DOCUMENT_EXPLORER_DESIGN.md. IRB is
// deliberately absent here (decision 7: zero rows in dev, routing/
// status/unit/person behavior unverifiable end to end) - a separate
// constant from MODULES above, which still reflects the older, simpler
// Document Search feature's own five-module schema-level union.
export const EXPLORER_MODULE_LABELS = Object.freeze({
  AWARD: "Award",
  PROPOSAL: "Proposal",
  NEGOTIATION: "Negotiation",
  SUBAWARD: "Subaward",
});

export const EXPLORER_MODULES = Object.freeze(
  Object.keys(EXPLORER_MODULE_LABELS),
);

export const NORMALIZED_STATUS_LABELS = Object.freeze({
  ACTIVE: "Active",
  PENDING: "Pending",
  ARCHIVED: "Archived",
  CANCELLED: "Cancelled",
  CLOSED: "Closed",
  UNKNOWN: "Unknown",
});

export const NORMALIZED_STATUSES = Object.freeze(
  Object.keys(NORMALIZED_STATUS_LABELS),
);

export function normalizedStatusLabel(status) {
  return NORMALIZED_STATUS_LABELS[status] ?? status;
}

/*
 * What the badge on a document row IS, so "Archived" here is not read
 * as the sequence status that also says ARCHIVED on a record's Versions
 * tab. A disposition says what became of the record; a sequence status
 * says whether a version is the current one. For proposal 01394406 the
 * two disagree and both are right - version 4 is the current version
 * (sequence ACTIVE) of a proposal that was not funded (disposition
 * Archived).
 *
 * Naming only. No status mapping is changed.
 */
export const DOCUMENT_STATUS_KIND_LABEL = "Disposition";

export function explorerModuleLabel(module) {
  return EXPLORER_MODULE_LABELS[module] ?? module;
}

// Only presets whose underlying status mapping is verified (per the
// design doc's §2 confident table) are offered - "Archived Subawards"
// is deliberately omitted because no Subaward native status currently
// maps to ARCHIVED in the approved mapping (it would silently return
// zero results, which is worse than not offering the preset at all).
export const DOCUMENT_EXPLORER_PRESETS = Object.freeze([
  {
    key: "activeAwards",
    label: "Active Awards",
    filters: { module: "AWARD", normalizedStatus: "ACTIVE" },
  },
  {
    key: "pendingProposals",
    label: "Pending Proposals",
    filters: { module: "PROPOSAL", normalizedStatus: "PENDING" },
  },
  {
    key: "negotiationsInProgress",
    label: "Negotiations in Progress",
    filters: { module: "NEGOTIATION", normalizedStatus: "PENDING" },
  },
  {
    key: "documentsByPi",
    label: "Documents by PI",
    filters: { piOnly: true },
  },
]);

export function moduleFacetLabel(facet) {
  const count = facet.count ?? 0;
  return `${explorerModuleLabel(facet.value)}: ${count.toLocaleString()}`;
}

// A result with unitCount/personCount/sponsorCount greater than the
// single primary value already shown gets a "+N other" indicator
// rather than a second visible row for the same document - never
// duplicate a (module, documentNumber) pair into multiple cards.
export function additionalRelationshipsLabel(count, noun) {
  if (count <= 0) {
    return null;
  }
  return `+${count} other ${noun}${count === 1 ? "" : "s"}`;
}

// A Negotiation's unit/sponsor, when present, was always inherited
// from its associated Award/Subaward/Proposal - Negotiation itself has
// no native unit or sponsor column at all (see design doc §3). The UI
// must label this clearly rather than presenting it as Negotiation's
// own data.
export function unitSourceLabel(module) {
  return module === "NEGOTIATION" ? "Unit (via associated Award)" : "Unit";
}

/*
 * Seeds the Kuali Documents filters from the address.
 *
 * "Historical Proposal Records" on the dashboard links here with
 * ?module=PROPOSAL and expects proposal document rows on arrival. The
 * seeded value goes into both the draft (so the Module control shows
 * it) and the applied filters (so the search has already run) - a
 * filter that is visible but not applied is worse than none, because
 * the page then contradicts itself.
 *
 * Only a known module is honoured. An unrecognised or absent value
 * leaves the filters untouched rather than being passed through: the
 * API treats an unknown module as an ordinary equality parameter and
 * would return zero rows, which reads as "there are no proposal
 * documents" rather than "that link was wrong".
 */
export function seedFiltersFromParams(emptyFilters, searchParams) {
  if (!searchParams || typeof searchParams.get !== "function") {
    return emptyFilters;
  }
  const requested = (searchParams.get("module") ?? "").trim().toUpperCase();
  if (!EXPLORER_MODULES.includes(requested)) {
    return emptyFilters;
  }
  return { ...emptyFilters, module: requested };
}

/*
 * What a document row's date actually is, per module.
 *
 * There is one `documentDate` field on the row, but it is NOT one
 * concept - the union behind it selects a different column per module:
 *
 *   AWARD        award_version.begin_date
 *   PROPOSAL     proposal_version.initial_start_date
 *   NEGOTIATION  negotiation.negotiation_start_date
 *   SUBAWARD     subaward.start_date
 *   IRB          irb_protocol_version.received_date
 *
 * Four of those are the start of a period; IRB's is when a protocol was
 * received. None of them is an update timestamp, which is what made the
 * unlabelled value confusing: for proposal 01394406 version 4 this
 * shows 2024-01-01 (the period start) while the Versions tab shows
 * Updated 2023-06-02, and with no label the two look like the same
 * thing disagreeing.
 *
 * Labelled per module rather than renaming the shared field, because
 * "Period start date" would be wrong for IRB.
 */
export function documentDateLabel(module) {
  switch (module) {
    case "AWARD":
    case "PROPOSAL":
    case "NEGOTIATION":
    case "SUBAWARD":
      return "Period start date";
    case "IRB":
      return "Received date";
    default:
      return "Date";
  }
}
