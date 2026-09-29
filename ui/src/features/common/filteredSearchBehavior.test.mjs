import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  activeFilters,
  buildFilterChips,
  buildFilterRequestParams,
  buildFilterUrlParams,
  clearFilters,
  countActiveFilters,
  emptyFilters,
  emptyResultsMessage,
  filtersFromSearchParams,
  hasFilterErrors,
  isValidIsoDate,
  pageFromSearchParams,
  queryFromSearchParams,
  removeFilter,
  sameFilters,
  validateFilters,
} from "./filterPresentation.mjs";

// The shared search/filter behaviour behind every archive search page,
// tested through the pure functions useFilteredSearch composes (this repo
// has no component-render harness).

const FIELDS = [
  { key: "status", label: "Status" },
  { key: "sponsor", label: "Sponsor" },
  {
    key: "versionFilter",
    label: "Versions",
    type: "select",
    defaultValue: "all",
    options: [
      { value: "all", label: "All versions" },
      { value: "current", label: "Current only" },
      { value: "historical", label: "Historical only" },
    ],
  },
  { key: "startDateFrom", label: "Start Date From", type: "date" },
  { key: "startDateTo", label: "Start Date To", type: "date" },
];
const RANGES = [{ from: "startDateFrom", to: "startDateTo", label: "Start Date" }];

const params = (query) => new URLSearchParams(query);

// --- select defaults ---------------------------------------------------

test("a select starts at its default, which is never active, chipped or sent", () => {
  const empty = emptyFilters(FIELDS);
  assert.equal(empty.versionFilter, "all");
  assert.equal(countActiveFilters(empty, FIELDS), 0);
  assert.deepEqual(buildFilterChips(empty, FIELDS), []);
  assert.deepEqual(buildFilterUrlParams({ query: "", filters: empty, fields: FIELDS }), {});
});

test("a non-default select is active and its chip shows the option label", () => {
  const filters = { ...emptyFilters(FIELDS), versionFilter: "historical" };
  assert.deepEqual(activeFilters(filters, FIELDS), { versionFilter: "historical" });
  assert.deepEqual(buildFilterChips(filters, FIELDS), [
    { key: "versionFilter", label: "Versions: Historical only", value: "historical" },
  ]);
});

test("removing a select chip returns it to its default, not to blank", () => {
  const filters = { ...emptyFilters(FIELDS), versionFilter: "current", sponsor: "NIH" };
  const next = removeFilter(filters, "versionFilter", FIELDS);
  assert.equal(next.versionFilter, "all");
  assert.equal(next.sponsor, "NIH");
});

// --- URL round trips -----------------------------------------------------

test("applied state round-trips through the URL unchanged", () => {
  const filters = {
    ...emptyFilters(FIELDS),
    status: "Closed",
    sponsor: "NIH",
    versionFilter: "current",
    startDateFrom: "2020-01-01",
    startDateTo: "2020-12-31",
  };
  const url = params(
    new URLSearchParams(
      buildFilterUrlParams({ query: "cancer", filters, fields: FIELDS, page: 3 }),
    ).toString(),
  );
  assert.equal(queryFromSearchParams(url), "cancer");
  assert.deepEqual(filtersFromSearchParams(url, FIELDS), filters);
  assert.equal(pageFromSearchParams(url), 3);
});

test("a hand-edited URL cannot produce a request the panel could not", () => {
  const url = params(
    "versionFilter=everything&startDateFrom=2024-02-30&startDateTo=yesterday&status=%20Closed%20",
  );
  const filters = filtersFromSearchParams(url, FIELDS);
  assert.equal(filters.versionFilter, "all");
  assert.equal(filters.startDateFrom, "");
  assert.equal(filters.startDateTo, "");
  assert.equal(filters.status, "Closed");
});

test("select values from older links match case-insensitively to the canonical value", () => {
  const filters = filtersFromSearchParams(params("versionFilter=HISTORICAL"), FIELDS);
  assert.equal(filters.versionFilter, "historical");
});

test("garbage, negative and fractional pages fall back to the first page", () => {
  for (const raw of ["-2", "1.5", "abc", "", "0", "1e3"]) {
    assert.equal(pageFromSearchParams(params(`page=${raw}`)), 0, raw);
  }
  assert.equal(pageFromSearchParams(params("page=4")), 4);
});

test("extra state such as sort is written only when it differs from its default", () => {
  const base = { query: "x", filters: emptyFilters(FIELDS), fields: FIELDS };
  assert.deepEqual(
    buildFilterUrlParams({ ...base, extra: { sort: "sequence" }, extraDefaults: { sort: "sequence" } }),
    { q: "x" },
  );
  assert.deepEqual(
    buildFilterUrlParams({ ...base, extra: { sort: "date" }, extraDefaults: { sort: "sequence" } }),
    { q: "x", sort: "date" },
  );
});

// --- request serialization ----------------------------------------------

test("requests carry page, size, trimmed text under the endpoint's name, and only active filters", () => {
  const filters = { ...emptyFilters(FIELDS), sponsor: "  NIH ", status: "" };
  assert.deepEqual(
    buildFilterRequestParams({ query: "  cancer ", filters, fields: FIELDS, page: 2, size: 25 }),
    { page: 2, size: 25, query: "cancer", sponsor: "NIH" },
  );
  assert.deepEqual(
    buildFilterRequestParams({ query: "", filters, fields: FIELDS, queryParam: "q" }),
    { page: 0, size: 25, sponsor: "NIH" },
  );
});

// --- Apply / chips / Clear All / pagination (useFilteredSearch transitions)

test("Apply combines the text query with the draft filters and resets to page 1", () => {
  // apply() commits buildFilterUrlParams(draftQuery, draftFilters, page 0)
  const draft = { ...emptyFilters(FIELDS), sponsor: "NIH" };
  assert.deepEqual(
    buildFilterUrlParams({ query: "cancer", filters: draft, fields: FIELDS, page: 0 }),
    { q: "cancer", sponsor: "NIH" },
  );
});

test("removing an applied chip keeps the text and other filters and resets pagination", () => {
  const applied = { ...emptyFilters(FIELDS), sponsor: "NIH", status: "Closed" };
  const next = removeFilter(applied, "status", FIELDS);
  assert.deepEqual(
    buildFilterUrlParams({ query: "cancer", filters: next, fields: FIELDS, page: 0 }),
    { q: "cancer", sponsor: "NIH" },
  );
});

test("Clear All clears structured filters and keeps the text query", () => {
  const cleared = clearFilters(FIELDS);
  assert.equal(countActiveFilters(cleared, FIELDS), 0);
  assert.deepEqual(
    buildFilterUrlParams({ query: "cancer", filters: cleared, fields: FIELDS, page: 0 }),
    { q: "cancer" },
  );
});

test("draft-versus-applied comparison ignores whitespace and defaults", () => {
  const applied = { ...emptyFilters(FIELDS), sponsor: "NIH" };
  assert.equal(sameFilters({ ...applied, sponsor: " NIH " }, applied, FIELDS), true);
  assert.equal(sameFilters({ ...applied, versionFilter: "all" }, applied, FIELDS), true);
  assert.equal(sameFilters({ ...applied, status: "Closed" }, applied, FIELDS), false);
});

test("the hook resets to page 0 on every result-changing transition and keeps the text on Clear All", () => {
  const source = readFileSync(
    new URL("../../hooks/useFilteredSearch.ts", import.meta.url),
    "utf8",
  );
  assert.match(source, /commit\(draftQuery\.trim\(\), draftFilters, 0, appliedExtra\)/);
  assert.match(source, /commit\(appliedQuery, removeFilter\(appliedFilters, key, fields\), 0, appliedExtra\)/);
  assert.match(source, /commit\(appliedQuery, cleared, 0, appliedExtra\)/);
  assert.match(source, /commit\(appliedQuery, appliedFilters, 0, \{ \.\.\.appliedExtra, \[key\]: value \}\)/);
  // Back/Forward re-syncs the draft from the URL.
  assert.match(source, /setDraftFilters\(appliedFilters\);/);
});

// --- date validation ----------------------------------------------------

test("only real calendar dates are valid", () => {
  assert.equal(isValidIsoDate("2024-02-29"), true);
  assert.equal(isValidIsoDate("2023-02-29"), false);
  assert.equal(isValidIsoDate("2024-13-01"), false);
  assert.equal(isValidIsoDate("2024-1-1"), false);
  assert.equal(isValidIsoDate(""), false);
});

test("a From date after its To date blocks Apply with a message on the To field", () => {
  const errors = validateFilters(
    { ...emptyFilters(FIELDS), startDateFrom: "2021-01-01", startDateTo: "2020-12-31" },
    FIELDS,
    RANGES,
  );
  assert.equal(hasFilterErrors(errors), true);
  assert.equal(errors.startDateTo, "Must be on or after From.");
});

test("equal From and To dates are valid - both bounds are inclusive", () => {
  const errors = validateFilters(
    { ...emptyFilters(FIELDS), startDateFrom: "2020-06-01", startDateTo: "2020-06-01" },
    FIELDS,
    RANGES,
  );
  assert.deepEqual(errors, {});
});

test("an open-ended range (only From or only To) is valid", () => {
  assert.deepEqual(
    validateFilters({ ...emptyFilters(FIELDS), startDateFrom: "2020-06-01" }, FIELDS, RANGES),
    {},
  );
});

test("a malformed date is an error on its own field", () => {
  const errors = validateFilters(
    { ...emptyFilters(FIELDS), startDateFrom: "2020-02-31" },
    FIELDS,
    RANGES,
  );
  assert.match(errors.startDateFrom, /complete date/);
  assert.equal(errors.startDateTo, undefined);
});

// --- empty results wording ---------------------------------------------

test("the empty-results line says what was searched", () => {
  assert.equal(emptyResultsMessage({ noun: "awards", query: "x" }), 'No awards match "x".');
  assert.equal(
    emptyResultsMessage({ noun: "awards", query: "x", filterCount: 2 }),
    'No awards match "x" with the applied filters.',
  );
  assert.equal(
    emptyResultsMessage({ noun: "awards", filterCount: 1 }),
    "No awards match the applied filter.",
  );
});
