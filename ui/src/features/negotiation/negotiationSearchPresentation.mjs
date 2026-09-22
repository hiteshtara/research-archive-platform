import {
  activeFilters,
  buildFilterChips,
  clearFilters,
  countActiveFilters,
  emptyFilters,
  hasActiveFilters,
  removeFilter,
} from "../common/filterPresentation.mjs";

/**
 * Presentation helpers for Negotiation search and structured filtering.
 *
 * The generic filter behaviour - which filters count as active, what the
 * chips say and in what order, single-filter removal, Clear All - lives
 * in features/common/filterPresentation and is consumed here rather than
 * reimplemented. This module keeps only what is genuinely Negotiation's:
 * which fields exist, their exact Kuali labels, and how a search request
 * is shaped.
 *
 * All filtering is server-side; nothing here filters rows. These helpers
 * decide what goes on the wire, what the active-filter chips say, and how
 * a Lead Unit is presented - the parts that are pure enough to unit test
 * without a component-render harness, which this repo does not have.
 *
 * Labels are the exact Kuali business terms BU staff recognise. Only the
 * layout is modernised, never the terminology.
 */

/**
 * The filterable fields, in the order the filter panel shows them.
 *
 * Deliberately absent, because their verified source population is too
 * sparse to justify a dedicated filter: Anticipated Award Date (7 of
 * 10,775 rows), Sponsor Award ID (1 of 8,554), Prime Sponsor (36 of
 * 8,554) and Principal Investigator (Non-BU) (25 of 8,554). They remain
 * display-only. Subaward Organization (0 rows) is not surfaced at all,
 * and Negotiation Age in Days is out of scope entirely.
 */
export const NEGOTIATION_FILTER_FIELDS = [
  { key: "principalInvestigator", label: "Principal Investigator (BU)" },
  { key: "sponsor", label: "Sponsor" },
  { key: "negotiator", label: "Negotiator" },
  { key: "agreementType", label: "Agreement Type" },
  { key: "status", label: "Negotiation Status" },
  { key: "leadUnit", label: "Lead Unit" },
  { key: "associationType", label: "Negotiation Association Type" },
  { key: "associationId", label: "Negotiation Association ID" },
  { key: "startDateFrom", label: "Negotiation Start Date From", type: "date" },
  { key: "startDateTo", label: "Negotiation Start Date To", type: "date" },
  { key: "endDateFrom", label: "Negotiation End Date From", type: "date" },
  { key: "endDateTo", label: "Negotiation End Date To", type: "date" },
];

/** Every filter empty - the initial and the "Clear All" state. */
export function emptyNegotiationFilters() {
  return emptyFilters(NEGOTIATION_FILTER_FIELDS);
}

/**
 * Only the filters the user actually set. A filter cleared in the UI
 * arrives as "" and is treated exactly like one that was never set.
 */
export function activeNegotiationFilters(filters) {
  return activeFilters(filters, NEGOTIATION_FILTER_FIELDS);
}

export function countActiveNegotiationFilters(filters) {
  return countActiveFilters(filters, NEGOTIATION_FILTER_FIELDS);
}

export function hasActiveNegotiationFilters(filters) {
  return hasActiveFilters(filters, NEGOTIATION_FILTER_FIELDS);
}

/**
 * One removable chip per active filter, in panel order, each carrying
 * the exact Kuali label: "Principal Investigator (BU): AHMAD KHALIL".
 */
export function buildNegotiationFilterChips(filters) {
  return buildFilterChips(filters, NEGOTIATION_FILTER_FIELDS);
}

/** Clears one filter, leaving the others untouched. */
export function removeNegotiationFilter(filters, key) {
  return removeFilter(filters, key);
}

/** Clears every filter. The free-text term is separate and survives. */
export function clearNegotiationFilters() {
  return clearFilters(NEGOTIATION_FILTER_FIELDS);
}

function normalize(value) {
  return typeof value === "string" ? value.trim() : "";
}

/**
 * The request parameters for the Negotiation search endpoint.
 *
 * Omitted filters are absent from the object entirely rather than sent as
 * empty strings, so the API applies no condition for them. Supplied ones
 * are ANDed server-side.
 */
export function buildNegotiationSearchParams({
  query,
  filters,
  page = 0,
  size = 25,
} = {}) {
  const params = { page, size };
  const trimmedQuery = normalize(query);
  if (trimmedQuery) {
    params.query = trimmedQuery;
  }
  return { ...params, ...activeNegotiationFilters(filters) };
}

/**
 * Lead Unit for display: name first, number as secondary metadata.
 *
 * BU explicitly asked for the actual unit name rather than only the
 * number. The name is resolved through archive.unit; it is never derived
 * from the number. When it cannot be resolved (57 of 10,775 records) the
 * number stands alone rather than inventing a placeholder name.
 */
export function formatLeadUnit(leadUnitName, leadUnitNumber) {
  const name = normalize(leadUnitName);
  const number = normalize(leadUnitNumber);

  if (!name && !number) {
    return { primary: "—", secondary: null };
  }
  if (!name) {
    return { primary: number, secondary: null };
  }
  if (!number) {
    return { primary: name, secondary: null };
  }
  return { primary: name, secondary: number };
}

/**
 * Where a row's Title/PI/Sponsor/Lead Unit came from.
 *
 * NONE is a real, expected outcome for Subaward- and Institutional-
 * Proposal-associated Negotiations, which have no attribute source at
 * all. Those rows still appear; they are never dropped.
 */
export function describeAttributeSource(attributeSource) {
  switch (attributeSource) {
    case "UNASSOCIATED_DETAIL":
      return "Negotiation attributes";
    case "AWARD":
      return "From associated Award";
    default:
      return null;
  }
}

/** "Showing 1-25 of 132 Negotiations", matching the Awards phrasing. */
export function describeNegotiationResults({
  totalElements = 0,
  page = 0,
  size = 25,
  count = 0,
} = {}) {
  if (totalElements === 0) {
    return "No Negotiations found";
  }
  const first = page * size + 1;
  const last = page * size + count;
  const noun = totalElements === 1 ? "Negotiation" : "Negotiations";
  return `Showing ${first.toLocaleString()}-${last.toLocaleString()} of ${totalElements.toLocaleString()} ${noun}`;
}

/** Route for a Negotiation row. */
export function buildNegotiationPath(negotiationId) {
  return `/negotiations/${encodeURIComponent(negotiationId)}`;
}
