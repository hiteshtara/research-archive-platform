import assert from "node:assert/strict";
import test from "node:test";

import {
  NEGOTIATION_FILTER_FIELDS,
  activeNegotiationFilters,
  buildNegotiationFilterChips,
  buildNegotiationPath,
  buildNegotiationSearchParams,
  clearNegotiationFilters,
  countActiveNegotiationFilters,
  describeAttributeSource,
  describeNegotiationResults,
  emptyNegotiationFilters,
  formatLeadUnit,
  hasActiveNegotiationFilters,
  removeNegotiationFilter,
} from "./negotiationSearchPresentation.mjs";

// --- general search is preserved -------------------------------------

test("general search alone still sends a query parameter", () => {
  const params = buildNegotiationSearchParams({ query: "Addgene" });
  assert.equal(params.query, "Addgene");
  assert.equal(params.page, 0);
  assert.equal(params.size, 25);
});

test("a blank general search sends no query parameter at all", () => {
  const params = buildNegotiationSearchParams({ query: "   " });
  assert.ok(!("query" in params));
});

test("general search is trimmed before it goes on the wire", () => {
  assert.equal(
    buildNegotiationSearchParams({ query: "  Addgene  " }).query,
    "Addgene",
  );
});

// --- AND semantics reflected in API parameters ------------------------

test("multiple simultaneous filters all appear as parameters", () => {
  const params = buildNegotiationSearchParams({
    filters: {
      principalInvestigator: "AHMAD KHALIL",
      sponsor: "Addgene",
      status: "Fully Executed",
    },
  });

  assert.equal(params.principalInvestigator, "AHMAD KHALIL");
  assert.equal(params.sponsor, "Addgene");
  assert.equal(params.status, "Fully Executed");
});

test("free text and structured filters travel together", () => {
  const params = buildNegotiationSearchParams({
    query: "agreement",
    filters: { principalInvestigator: "Smith", sponsor: "NIH" },
  });

  assert.equal(params.query, "agreement");
  assert.equal(params.principalInvestigator, "Smith");
  assert.equal(params.sponsor, "NIH");
});

test("an omitted filter is absent, never an empty string", () => {
  const params = buildNegotiationSearchParams({
    filters: { ...emptyNegotiationFilters(), sponsor: "NIH" },
  });

  assert.equal(params.sponsor, "NIH");
  assert.ok(!("principalInvestigator" in params));
  assert.ok(!("status" in params));
  assert.ok(!("startDateFrom" in params));
});

test("a whitespace-only filter is treated as absent", () => {
  const params = buildNegotiationSearchParams({
    filters: { principalInvestigator: "   " },
  });
  assert.ok(!("principalInvestigator" in params));
});

test("filter values are trimmed", () => {
  const params = buildNegotiationSearchParams({
    filters: { sponsor: "  Addgene  " },
  });
  assert.equal(params.sponsor, "Addgene");
});

test("date range filters are sent as supplied", () => {
  const params = buildNegotiationSearchParams({
    filters: {
      startDateFrom: "2014-01-01",
      startDateTo: "2014-12-31",
      endDateFrom: "2015-01-01",
      endDateTo: "2015-12-31",
    },
  });

  assert.equal(params.startDateFrom, "2014-01-01");
  assert.equal(params.startDateTo, "2014-12-31");
  assert.equal(params.endDateFrom, "2015-01-01");
  assert.equal(params.endDateTo, "2015-12-31");
});

// --- pagination preserves filters -------------------------------------

test("pagination preserves the query and every active filter", () => {
  const options = {
    query: "agreement",
    filters: { principalInvestigator: "Smith", sponsor: "NIH" },
    size: 25,
  };

  const first = buildNegotiationSearchParams({ ...options, page: 0 });
  const third = buildNegotiationSearchParams({ ...options, page: 2 });

  assert.equal(third.page, 2);
  assert.equal(third.query, first.query);
  assert.equal(third.principalInvestigator, first.principalInvestigator);
  assert.equal(third.sponsor, first.sponsor);
});

test("page and size default when not supplied", () => {
  const params = buildNegotiationSearchParams();
  assert.equal(params.page, 0);
  assert.equal(params.size, 25);
});

// --- active filter chips ----------------------------------------------

test("chips carry the exact Kuali label and the value", () => {
  const chips = buildNegotiationFilterChips({
    principalInvestigator: "AHMAD KHALIL",
    sponsor: "Addgene",
    status: "Fully Executed",
  });

  assert.deepEqual(
    chips.map((chip) => chip.label),
    [
      "Principal Investigator (BU): AHMAD KHALIL",
      "Sponsor: Addgene",
      "Negotiation Status: Fully Executed",
    ],
  );
});

test("chips appear in filter panel order, not insertion order", () => {
  const chips = buildNegotiationFilterChips({
    status: "Fully Executed",
    principalInvestigator: "AHMAD KHALIL",
  });

  assert.deepEqual(
    chips.map((chip) => chip.key),
    ["principalInvestigator", "status"],
  );
});

test("no chips when nothing is filtered", () => {
  assert.deepEqual(buildNegotiationFilterChips(emptyNegotiationFilters()), []);
  assert.deepEqual(buildNegotiationFilterChips(null), []);
});

test("every filter field can produce a chip", () => {
  const filters = Object.fromEntries(
    NEGOTIATION_FILTER_FIELDS.map((field) => [field.key, "x"]),
  );
  assert.equal(
    buildNegotiationFilterChips(filters).length,
    NEGOTIATION_FILTER_FIELDS.length,
  );
});

// --- individual removal and Clear All ---------------------------------

test("removing one filter leaves the others untouched", () => {
  const filters = {
    ...emptyNegotiationFilters(),
    principalInvestigator: "AHMAD KHALIL",
    sponsor: "Addgene",
    status: "Fully Executed",
  };

  const next = removeNegotiationFilter(filters, "sponsor");

  assert.equal(next.sponsor, "");
  assert.equal(next.principalInvestigator, "AHMAD KHALIL");
  assert.equal(next.status, "Fully Executed");
  assert.equal(countActiveNegotiationFilters(next), 2);
});

test("removing a filter does not mutate the original object", () => {
  const filters = { ...emptyNegotiationFilters(), sponsor: "Addgene" };
  removeNegotiationFilter(filters, "sponsor");
  assert.equal(filters.sponsor, "Addgene");
});

test("Clear All clears every filter", () => {
  const cleared = clearNegotiationFilters();
  assert.equal(countActiveNegotiationFilters(cleared), 0);
  assert.equal(hasActiveNegotiationFilters(cleared), false);
});

test("Clear All does not remove the free-text term", () => {
  const params = buildNegotiationSearchParams({
    query: "Addgene",
    filters: clearNegotiationFilters(),
  });
  assert.equal(params.query, "Addgene");
});

test("active filter count and flag agree", () => {
  const filters = { ...emptyNegotiationFilters(), sponsor: "NIH" };
  assert.equal(countActiveNegotiationFilters(filters), 1);
  assert.equal(hasActiveNegotiationFilters(filters), true);
  assert.deepEqual(activeNegotiationFilters(filters), { sponsor: "NIH" });
});

// --- Lead Unit name + number presentation ------------------------------

test("lead unit shows the name first and the number as secondary", () => {
  assert.deepEqual(formatLeadUnit("ENG BIOMEDICAL ENG", "1242040000"), {
    primary: "ENG BIOMEDICAL ENG",
    secondary: "1242040000",
  });
});

test("an unresolved lead unit shows the number alone, never a fake name", () => {
  assert.deepEqual(formatLeadUnit(null, "1242040000"), {
    primary: "1242040000",
    secondary: null,
  });
});

test("a lead unit with neither value renders an em dash", () => {
  assert.deepEqual(formatLeadUnit(null, null), {
    primary: "—",
    secondary: null,
  });
});

test("a name with no number shows the name alone", () => {
  assert.deepEqual(formatLeadUnit("ENG BIOMEDICAL ENG", null), {
    primary: "ENG BIOMEDICAL ENG",
    secondary: null,
  });
});

// --- attribute source --------------------------------------------------

test("attribute source is described for both resolved sources", () => {
  assert.equal(
    describeAttributeSource("UNASSOCIATED_DETAIL"),
    "Negotiation attributes",
  );
  assert.equal(describeAttributeSource("AWARD"), "From associated Award");
});

test("an unresolved attribute source is not labelled", () => {
  assert.equal(describeAttributeSource("NONE"), null);
  assert.equal(describeAttributeSource(null), null);
});

// --- result count phrasing ---------------------------------------------

test("result count describes the visible range", () => {
  assert.equal(
    describeNegotiationResults({
      totalElements: 132,
      page: 0,
      size: 25,
      count: 25,
    }),
    "Showing 1-25 of 132 Negotiations",
  );
});

test("result count follows the page offset", () => {
  assert.equal(
    describeNegotiationResults({
      totalElements: 132,
      page: 2,
      size: 25,
      count: 25,
    }),
    "Showing 51-75 of 132 Negotiations",
  );
});

test("a single result is singular", () => {
  assert.equal(
    describeNegotiationResults({
      totalElements: 1,
      page: 0,
      size: 25,
      count: 1,
    }),
    "Showing 1-1 of 1 Negotiation",
  );
});

test("an empty result set says so", () => {
  assert.equal(
    describeNegotiationResults({ totalElements: 0, count: 0 }),
    "No Negotiations found",
  );
  assert.equal(describeNegotiationResults(), "No Negotiations found");
});

// --- navigation ---------------------------------------------------------

test("navigation targets the negotiation by id", () => {
  assert.equal(buildNegotiationPath(120), "/negotiations/120");
});

test("navigation encodes an unexpected identifier", () => {
  assert.equal(buildNegotiationPath("a b/c"), "/negotiations/a%20b%2Fc");
});

// --- URL-backed search state and initial-load gating -------------------
//
// The Negotiations page used to query on mount, putting the first page of
// 10,775 records on screen before anyone had searched. These pin the
// gating decision and the URL round-trip that makes it survivable.

import {
  buildNegotiationUrlParams,
  hasNegotiationSearchCriteria,
  negotiationFiltersFromParams,
  negotiationPageFromParams,
  negotiationQueryFromParams,
} from "./negotiationSearchPresentation.mjs";

const params = (init) => new URLSearchParams(init);

test("1. initial state does not enable the query", () => {
  assert.equal(
    hasNegotiationSearchCriteria({
      query: negotiationQueryFromParams(params("")),
      filters: negotiationFiltersFromParams(params("")),
    }),
    false,
  );
});

test("2. free-text search enables the query", () => {
  assert.equal(
    hasNegotiationSearchCriteria({
      query: negotiationQueryFromParams(params("q=addgene")),
      filters: negotiationFiltersFromParams(params("q=addgene")),
    }),
    true,
  );
});

test("3. a structured filter with no free text enables the query", () => {
  const search = params("principalInvestigator=SIMMS");
  assert.equal(
    hasNegotiationSearchCriteria({
      query: negotiationQueryFromParams(search),
      filters: negotiationFiltersFromParams(search),
    }),
    true,
  );
});

test("4. free text plus structured filters enables the query", () => {
  const search = params("q=mta&principalInvestigator=SIMMS&sponsor=NIH");
  const filters = negotiationFiltersFromParams(search);
  assert.equal(
    hasNegotiationSearchCriteria({
      query: negotiationQueryFromParams(search),
      filters,
    }),
    true,
  );
  assert.equal(filters.principalInvestigator, "SIMMS");
  assert.equal(filters.sponsor, "NIH");
});

test("5. Clear All disables the query again", () => {
  const cleared = clearNegotiationFilters();
  assert.equal(
    hasNegotiationSearchCriteria({ query: "", filters: cleared }),
    false,
  );
  // and produces a bare URL, so a reload lands on the empty state
  assert.deepEqual(
    buildNegotiationUrlParams({ query: "", filters: cleared }),
    {},
  );
});

test("6. existing URL search/filter state enables the query", () => {
  const search = params(
    "q=addgene&sponsor=Addgene&status=Fully%20Executed&page=2",
  );
  const filters = negotiationFiltersFromParams(search);
  assert.equal(negotiationQueryFromParams(search), "addgene");
  assert.equal(filters.sponsor, "Addgene");
  assert.equal(filters.status, "Fully Executed");
  assert.equal(negotiationPageFromParams(search), 2);
  assert.equal(
    hasNegotiationSearchCriteria({
      query: negotiationQueryFromParams(search),
      filters,
    }),
    true,
  );
});

test("7. filter chips remain removable", () => {
  const search = params("principalInvestigator=SIMMS&sponsor=NIH");
  const applied = negotiationFiltersFromParams(search);
  const chips = buildNegotiationFilterChips(applied);
  assert.equal(chips.length, 2);
  const next = removeNegotiationFilter(applied, "sponsor");
  assert.equal(next.sponsor, "");
  assert.equal(next.principalInvestigator, "SIMMS");
  assert.equal(buildNegotiationFilterChips(next).length, 1);
  // one filter still set, so the page keeps querying
  assert.equal(
    hasNegotiationSearchCriteria({ query: "", filters: next }),
    true,
  );
});

test("8. Clear All clears every structured filter", () => {
  const search = params(
    "principalInvestigator=SIMMS&sponsor=NIH&status=Fully%20Executed" +
      "&leadUnit=MED&startDateFrom=2016-01-01&endDateTo=2016-12-31",
  );
  const applied = negotiationFiltersFromParams(search);
  assert.equal(countActiveNegotiationFilters(applied), 6);
  const cleared = clearNegotiationFilters();
  assert.equal(countActiveNegotiationFilters(cleared), 0);
  for (const field of NEGOTIATION_FILTER_FIELDS) {
    assert.equal(cleared[field.key], "");
  }
});

test("9. ResultCard route remains correct", () => {
  assert.equal(buildNegotiationPath(120), "/negotiations/120");
  assert.equal(buildNegotiationPath(2676), "/negotiations/2676");
});

test("10. structured filter request serialization is unchanged", () => {
  // URL serialization is a separate concern from REQUEST serialization;
  // migrating the page must not have altered what the API receives.
  const search = params(
    "q=mta&principalInvestigator=SIMMS&sponsor=NIH&status=Fully%20Executed",
  );
  const requestParams = buildNegotiationSearchParams({
    query: negotiationQueryFromParams(search),
    filters: negotiationFiltersFromParams(search),
    page: 0,
    size: 25,
  });
  assert.deepEqual(requestParams, {
    page: 0,
    size: 25,
    query: "mta",
    principalInvestigator: "SIMMS",
    sponsor: "NIH",
    status: "Fully Executed",
  });
  // omitted filters are absent entirely, never sent as empty strings
  assert.equal("leadUnit" in requestParams, false);
});

test("URL params round-trip back to the same applied state", () => {
  const filters = clearNegotiationFilters();
  filters.principalInvestigator = "SIMMS";
  filters.sponsor = "NIH";
  const url = buildNegotiationUrlParams({ query: "mta", filters, page: 3 });
  assert.deepEqual(url, {
    q: "mta",
    principalInvestigator: "SIMMS",
    sponsor: "NIH",
    page: "3",
  });
  const search = params(url);
  assert.equal(negotiationQueryFromParams(search), "mta");
  assert.equal(negotiationPageFromParams(search), 3);
  assert.deepEqual(
    negotiationFiltersFromParams(search).principalInvestigator,
    "SIMMS",
  );
});
