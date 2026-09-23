/**
 * Generic structured-filter logic, shared by every archive module.
 *
 * FilterPanel and FilterChips own the visual treatment; this owns the
 * pure behaviour behind them - which filters count as active, what the
 * chips say, what goes on the wire - so each module supplies only its
 * own field definitions and Kuali labels.
 *
 * A module keeps its exact business terminology by passing its own
 * `fields`; nothing here invents or translates a label.
 */

function normalize(value) {
  return typeof value === "string" ? value.trim() : "";
}

/** Every field empty - the initial state, and what Clear All returns. */
export function emptyFilters(fields) {
  return Object.fromEntries((fields ?? []).map((field) => [field.key, ""]));
}

/**
 * Only the filters the user actually set.
 *
 * A filter cleared in the UI arrives as "" and must be treated exactly
 * like one that was never set - otherwise it travels to the API and
 * becomes a condition matching nothing.
 */
export function activeFilters(filters, fields) {
  const active = {};
  for (const field of fields ?? []) {
    const value = normalize(filters?.[field.key]);
    if (value) {
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

/**
 * One removable chip per active filter, in the panel's own field order
 * rather than the order the user happened to fill them in, each
 * carrying that module's exact label: "Sponsor: Addgene".
 */
export function buildFilterChips(filters, fields) {
  const active = activeFilters(filters, fields);
  return (fields ?? [])
    .filter((field) => active[field.key] !== undefined)
    .map((field) => ({
      key: field.key,
      label: `${field.label}: ${active[field.key]}`,
      value: active[field.key],
    }));
}

/** Clears one filter, leaving the others untouched. */
export function removeFilter(filters, key) {
  return { ...filters, [key]: "" };
}

/** Clears every filter. A free-text term is separate and survives. */
export function clearFilters(fields) {
  return emptyFilters(fields);
}
