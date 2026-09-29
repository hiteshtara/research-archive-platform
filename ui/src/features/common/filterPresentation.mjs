/**
 * Generic structured-filter logic, shared by every archive module.
 *
 * FilterPanel and FilterChips own the visual treatment; this owns the
 * pure behaviour behind them - which filters count as active, what the
 * chips say, what goes on the wire and into the URL, how dates are
 * validated - so each module supplies only its own field definitions and
 * Kuali labels.
 *
 * A module keeps its exact business terminology by passing its own
 * `fields`; nothing here invents or translates a label.
 *
 * Field definition:
 *   { key, label, type?: "text" | "date" | "select",
 *     options?: [{ value, label }],   // select only
 *     defaultValue?: string,          // select only; never "active"
 *     helperText?: string }
 *
 * A select's defaultValue ("all", "ALL") means "no condition", so it is
 * never counted, chipped or written to the URL - exactly like an empty
 * text field.
 */

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function normalize(value) {
  return typeof value === "string" ? value.trim() : "";
}

function defaultOf(field) {
  return typeof field?.defaultValue === "string" ? field.defaultValue : "";
}

/** A real calendar date in yyyy-MM-dd form (rejects 2024-02-30). */
export function isValidIsoDate(value) {
  if (typeof value !== "string" || !ISO_DATE.test(value)) {
    return false;
  }
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
  );
}

/** Every field at its default - the initial state, and what Clear All returns. */
export function emptyFilters(fields) {
  return Object.fromEntries(
    (fields ?? []).map((field) => [field.key, defaultOf(field)]),
  );
}

/**
 * Only the filters the user actually set.
 *
 * A filter cleared in the UI arrives as "" (or a select's default) and
 * must be treated exactly like one that was never set - otherwise it
 * travels to the API and becomes a condition matching nothing.
 */
export function activeFilters(filters, fields) {
  const active = {};
  for (const field of fields ?? []) {
    const value = normalize(filters?.[field.key]);
    if (value && value !== defaultOf(field)) {
      active[field.key] = value;
    }
  }
  return active;
}

export function countActiveFilters(filters, fields) {
  return Object.keys(activeFilters(filters, fields)).length;
}

export function hasActiveFilters(filters, fields) {
  return countActiveFilters(filters, fields) > 0;
}

function displayValue(field, value) {
  if (field.type === "select") {
    const option = (field.options ?? []).find((o) => o.value === value);
    return option ? option.label : value;
  }
  return value;
}

/**
 * One removable chip per active filter, in the panel's own field order
 * rather than the order the user happened to fill them in, each
 * carrying that module's exact label: "Sponsor: Addgene". A select shows
 * its option label, never its wire value.
 */
export function buildFilterChips(filters, fields) {
  const active = activeFilters(filters, fields);
  return (fields ?? [])
    .filter((field) => active[field.key] !== undefined)
    .map((field) => ({
      key: field.key,
      label: `${field.label}: ${displayValue(field, active[field.key])}`,
      value: active[field.key],
    }));
}

/**
 * Clears one filter, leaving the others untouched. With `fields`, a
 * select returns to its default rather than to "".
 */
export function removeFilter(filters, key, fields) {
  const field = (fields ?? []).find((candidate) => candidate.key === key);
  return { ...filters, [key]: defaultOf(field) };
}

/** Clears every filter. A free-text term is separate and survives. */
export function clearFilters(fields) {
  return emptyFilters(fields);
}

/** Whether two filter states apply the same conditions. */
export function sameFilters(a, b, fields) {
  const left = activeFilters(a, fields);
  const right = activeFilters(b, fields);
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  for (const key of keys) {
    if (left[key] !== right[key]) {
      return false;
    }
  }
  return true;
}

/*
 * --- URL state --------------------------------------------------------
 *
 * Free text is ?q= on every archive search page. Structured filters use
 * their own keys, which are the API's own parameter names, so a URL
 * reads the same way as the request it produces. Values that could not
 * have come from the UI (an unknown select option, a malformed date) are
 * dropped rather than sent - a hand-edited URL can never produce a
 * request the panel could not.
 */

function readParam(searchParams, key) {
  const value = searchParams?.get?.(key);
  return typeof value === "string" ? value : "";
}

function sanitize(field, raw) {
  const value = normalize(raw);
  if (field.type === "select") {
    // Case-insensitive, returning the canonical option value, so older
    // links written in another case ("recordType=award") keep working.
    const option = (field.options ?? []).find(
      (candidate) => candidate.value.toLowerCase() === value.toLowerCase(),
    );
    return option ? option.value : defaultOf(field);
  }
  if (field.type === "date") {
    return isValidIsoDate(value) ? value : "";
  }
  return value;
}

export function filtersFromSearchParams(searchParams, fields) {
  const filters = emptyFilters(fields);
  for (const field of fields ?? []) {
    filters[field.key] = sanitize(field, readParam(searchParams, field.key));
  }
  return filters;
}

export function queryFromSearchParams(searchParams, key = "q") {
  return readParam(searchParams, key);
}

/** Non-positive, fractional or garbage page values become page 0. */
export function pageFromSearchParams(searchParams) {
  const raw = readParam(searchParams, "page");
  if (!/^\d+$/.test(raw)) {
    return 0;
  }
  const parsed = Number.parseInt(raw, 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

/**
 * URL parameters for an applied search. Empty criteria are omitted, so
 * clearing everything returns a bare URL; page 0 is omitted too.
 * `extra` carries non-filter state such as a sort order; an entry equal
 * to its declared default is omitted.
 */
export function buildFilterUrlParams({
  query,
  filters,
  fields,
  page = 0,
  extra = {},
  extraDefaults = {},
} = {}) {
  const params = {};
  const trimmedQuery = normalize(query);
  if (trimmedQuery) {
    params.q = trimmedQuery;
  }
  for (const [key, value] of Object.entries(activeFilters(filters, fields))) {
    params[key] = value;
  }
  for (const [key, value] of Object.entries(extra ?? {})) {
    const text = normalize(value == null ? "" : String(value));
    if (text && text !== extraDefaults?.[key]) {
      params[key] = text;
    }
  }
  if (Number.isInteger(page) && page > 0) {
    params.page = String(page);
  }
  return params;
}

/**
 * Request parameters for a module's search endpoint: page, size, the
 * trimmed free text under that endpoint's own name, and only the active
 * filters. Omitted filters are absent, never empty strings.
 */
export function buildFilterRequestParams({
  query,
  filters,
  fields,
  page = 0,
  size = 25,
  queryParam = "query",
} = {}) {
  const params = { page, size };
  const trimmedQuery = normalize(query);
  if (trimmedQuery) {
    params[queryParam] = trimmedQuery;
  }
  return { ...params, ...activeFilters(filters, fields) };
}

/*
 * --- Validation -------------------------------------------------------
 *
 * Date ranges: [{ from, to, label }]. Bounds are inclusive on both ends
 * server-side, so equal dates are valid; a From later than its To could
 * only ever return nothing and is rejected before it is applied.
 */
export function validateFilters(filters, fields, dateRanges = []) {
  const errors = {};
  for (const field of fields ?? []) {
    const value = normalize(filters?.[field.key]);
    if (field.type === "date" && value && !isValidIsoDate(value)) {
      errors[field.key] = "Enter a complete date (yyyy-mm-dd).";
    }
  }
  for (const range of dateRanges ?? []) {
    const from = normalize(filters?.[range.from]);
    const to = normalize(filters?.[range.to]);
    if (
      from && to
      && !errors[range.from] && !errors[range.to]
      && from > to
    ) {
      errors[range.to] = "Must be on or after From.";
    }
  }
  return errors;
}

export function hasFilterErrors(errors) {
  return Object.keys(errors ?? {}).length > 0;
}

/**
 * The "no results" line, worded for what was actually searched: free
 * text, filters, or both. `noun` is plural and lower-case ("awards").
 */
export function emptyResultsMessage({ noun, query, filterCount = 0 } = {}) {
  const text = normalize(query);
  const filters = filterCount === 1 ? "the applied filter" : "the applied filters";
  if (text && filterCount > 0) {
    return `No ${noun} match "${text}" with ${filters}.`;
  }
  if (text) {
    return `No ${noun} match "${text}".`;
  }
  if (filterCount > 0) {
    return `No ${noun} match ${filters}.`;
  }
  return `No ${noun} found.`;
}
