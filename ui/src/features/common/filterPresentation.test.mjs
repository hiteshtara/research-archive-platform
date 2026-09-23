import assert from "node:assert/strict";
import test from "node:test";

import {
  activeFilters,
  buildFilterChips,
  clearFilters,
  countActiveFilters,
  emptyFilters,
  hasActiveFilters,
  removeFilter,
} from "./filterPresentation.mjs";

// A module's own fields and its own exact business labels.
const FIELDS = [
  { key: "principalInvestigator", label: "Principal Investigator (BU)" },
  { key: "sponsor", label: "Sponsor" },
  { key: "status", label: "Negotiation Status" },
  { key: "startDateFrom", label: "Start Date From", type: "date" },
];

test("empty filters cover every field", () => {
  assert.deepEqual(emptyFilters(FIELDS), {
    principalInvestigator: "",
    sponsor: "",
    status: "",
    startDateFrom: "",
  });
  assert.deepEqual(emptyFilters([]), {});
});

test("only set filters count as active", () => {
  const filters = { ...emptyFilters(FIELDS), sponsor: "Addgene" };
  assert.deepEqual(activeFilters(filters, FIELDS), { sponsor: "Addgene" });
  assert.equal(countActiveFilters(filters, FIELDS), 1);
  assert.equal(hasActiveFilters(filters, FIELDS), true);
});

test("a whitespace-only filter is treated as absent", () => {
  const filters = { ...emptyFilters(FIELDS), sponsor: "   " };
  assert.deepEqual(activeFilters(filters, FIELDS), {});
  assert.equal(hasActiveFilters(filters, FIELDS), false);
});

test("filter values are trimmed", () => {
  assert.deepEqual(
    activeFilters({ sponsor: "  Addgene  " }, FIELDS),
    { sponsor: "Addgene" },
  );
});

test("a value for a field this module does not define is ignored", () => {
  assert.deepEqual(activeFilters({ notAField: "x" }, FIELDS), {});
});

test("null and undefined filters are safe", () => {
  assert.deepEqual(activeFilters(null, FIELDS), {});
  assert.deepEqual(buildFilterChips(undefined, FIELDS), []);
});

test("chips carry the module's exact label and the value", () => {
  const chips = buildFilterChips(
    { principalInvestigator: "AHMAD KHALIL", sponsor: "Addgene" },
    FIELDS,
  );
  assert.deepEqual(
    chips.map((chip) => chip.label),
    ["Principal Investigator (BU): AHMAD KHALIL", "Sponsor: Addgene"],
  );
  assert.equal(chips[0].value, "AHMAD KHALIL");
});

test("chips follow field order, not the order filters were set", () => {
  const chips = buildFilterChips(
    { status: "Fully Executed", principalInvestigator: "AHMAD KHALIL" },
    FIELDS,
  );
  assert.deepEqual(
    chips.map((chip) => chip.key),
    ["principalInvestigator", "status"],
  );
});

test("removing one filter leaves the others untouched", () => {
  const filters = {
    ...emptyFilters(FIELDS),
    principalInvestigator: "AHMAD KHALIL",
    sponsor: "Addgene",
  };
  const next = removeFilter(filters, "sponsor");

  assert.equal(next.sponsor, "");
  assert.equal(next.principalInvestigator, "AHMAD KHALIL");
  assert.equal(countActiveFilters(next, FIELDS), 1);
});

test("removing a filter does not mutate the original", () => {
  const filters = { ...emptyFilters(FIELDS), sponsor: "Addgene" };
  removeFilter(filters, "sponsor");
  assert.equal(filters.sponsor, "Addgene");
});

test("Clear All clears every field", () => {
  assert.deepEqual(clearFilters(FIELDS), emptyFilters(FIELDS));
  assert.equal(countActiveFilters(clearFilters(FIELDS), FIELDS), 0);
});
