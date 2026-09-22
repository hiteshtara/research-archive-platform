import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSearchParams,
  describeResultCount,
  formatNamedIdentifier,
  joinMetadata,
  readSearchParams,
  resolveSearchState,
  splitStatusCode,
} from "./searchPresentation.mjs";

// --- status normalization ------------------------------------------

test("a numbered Subaward status splits into label and code", () => {
  assert.deepEqual(splitStatusCode("07. Executed"), {
    code: "07",
    label: "Executed",
  });
  assert.deepEqual(splitStatusCode("09. Temporarily Cancelled"), {
    code: "09",
    label: "Temporarily Cancelled",
  });
});

test("an unnumbered status is returned unchanged - Awards must not shift", () => {
  assert.deepEqual(splitStatusCode("Active"), { code: null, label: "Active" });
  assert.deepEqual(splitStatusCode("Closed"), { code: null, label: "Closed" });
  assert.deepEqual(splitStatusCode("Fully Executed"), {
    code: null,
    label: "Fully Executed",
  });
});

test("a status containing a period but no leading ordinal is untouched", () => {
  assert.deepEqual(splitStatusCode("Pending Dept. Review"), {
    code: null,
    label: "Pending Dept. Review",
  });
});

test("a null status stays null rather than becoming a string", () => {
  assert.deepEqual(splitStatusCode(null), { code: null, label: null });
  assert.deepEqual(splitStatusCode(undefined), { code: null, label: null });
});

// --- result count ---------------------------------------------------

test("result count pluralizes and groups thousands", () => {
  assert.equal(
    describeResultCount({ total: 24, singular: "award" }),
    "24 awards found",
  );
  assert.equal(
    describeResultCount({ total: 1, singular: "award" }),
    "1 award found",
  );
  assert.equal(
    describeResultCount({ total: 0, singular: "award" }),
    "0 awards found",
  );
  assert.equal(
    describeResultCount({ total: 10775, singular: "negotiation" }),
    "10,775 negotiations found",
  );
});

test("an irregular plural can be supplied", () => {
  assert.equal(
    describeResultCount({ total: 3, singular: "entry", plural: "entries" }),
    "3 entries found",
  );
});

// --- human-readable value first --------------------------------------

test("a verified name is primary and its code secondary", () => {
  assert.deepEqual(formatNamedIdentifier("ENG BIOMEDICAL ENG", "1242040000"), {
    primary: "ENG BIOMEDICAL ENG",
    secondary: "1242040000",
  });
  assert.deepEqual(formatNamedIdentifier("Addgene", "303630"), {
    primary: "Addgene",
    secondary: "303630",
  });
});

test("a missing name never gets faked from the code", () => {
  assert.deepEqual(formatNamedIdentifier(null, "1242040000"), {
    primary: "1242040000",
    secondary: null,
  });
});

test("identifiers keep leading zeroes and exact string form", () => {
  assert.equal(formatNamedIdentifier("Unit", "0042").secondary, "0042");
  assert.equal(formatNamedIdentifier("Unit", 42).secondary, "42");
});

test("neither value present renders the fallback", () => {
  assert.deepEqual(formatNamedIdentifier(null, null), {
    primary: "—",
    secondary: null,
  });
});

// --- metadata line ----------------------------------------------------

test("metadata drops missing parts instead of leaving gaps", () => {
  assert.equal(
    joinMetadata(["AHMAD KHALIL", null, "Addgene", "", undefined]),
    "AHMAD KHALIL · Addgene",
  );
  assert.equal(joinMetadata([]), "");
  assert.equal(joinMetadata(null), "");
});

// --- URL-backed search state -------------------------------------------

test("search state is read from the URL", () => {
  const params = new URLSearchParams("q=105698&page=2");
  assert.deepEqual(readSearchParams(params), { query: "105698", page: 2 });
});

test("a missing or malformed page falls back to zero", () => {
  assert.equal(readSearchParams(new URLSearchParams("q=x")).page, 0);
  assert.equal(readSearchParams(new URLSearchParams("q=x&page=-3")).page, 0);
  assert.equal(readSearchParams(new URLSearchParams("q=x&page=abc")).page, 0);
});

test("an absent query reads as an empty string, not null", () => {
  assert.equal(readSearchParams(new URLSearchParams("")).query, "");
});

test("building params omits empty query and page zero", () => {
  assert.deepEqual(buildSearchParams({ query: "105698", page: 0 }), {
    q: "105698",
  });
  assert.deepEqual(buildSearchParams({ query: "", page: 0 }), {});
  assert.deepEqual(buildSearchParams({ query: "  ", page: 0 }), {});
});

test("building params keeps a non-zero page and trims the query", () => {
  assert.deepEqual(buildSearchParams({ query: "  105698 ", page: 3 }), {
    q: "105698",
    page: "3",
  });
});

test("extra params are carried and empties dropped", () => {
  assert.deepEqual(
    buildSearchParams({
      query: "x",
      extra: { sponsor: "Addgene", status: "", pi: null },
    }),
    { q: "x", sponsor: "Addgene" },
  );
});

test("a URL round-trips through build and read", () => {
  const built = buildSearchParams({ query: "Orsmond", page: 4 });
  const round = readSearchParams(new URLSearchParams(built));
  assert.deepEqual(round, { query: "Orsmond", page: 4 });
});

// --- search states -------------------------------------------------------

test("a page that has not been searched is initial, never empty", () => {
  assert.equal(resolveSearchState({ hasSearched: false }), "initial");
  assert.equal(
    resolveSearchState({ hasSearched: false, resultCount: 0 }),
    "initial",
  );
});

test("error outranks loading", () => {
  assert.equal(
    resolveSearchState({ hasSearched: true, isLoading: true, isError: true }),
    "error",
  );
});

test("loading, empty and results are distinguished", () => {
  assert.equal(
    resolveSearchState({ hasSearched: true, isLoading: true }),
    "loading",
  );
  assert.equal(
    resolveSearchState({ hasSearched: true, resultCount: 0 }),
    "empty",
  );
  assert.equal(
    resolveSearchState({ hasSearched: true, resultCount: 7 }),
    "results",
  );
});
