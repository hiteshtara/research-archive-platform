import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  SEARCH_INPUT_REJECTED_MESSAGE,
  buildSearchParams,
  describeResultCount,
  formatNamedIdentifier,
  isSearchInputRejection,
  joinMetadata,
  readSearchParams,
  resolveSearchState,
  searchErrorMessage,
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

// --- Rejected input vs. a broken search (QA TC-017) ----------------------

const GENERIC = "Unable to search Awards right now. Try again in a moment.";

/*
 * The three VALIDATION_ERROR responses measured against dev on
 * 2026-10-05. They share one code and have three different causes,
 * which is exactly why the guidance may not name one.
 */
const NUL_IN_QUERY = {
  status: 400,
  code: "VALIDATION_ERROR",
  message: "Parameter 'q' contains a character that is not allowed: U+0000. Remove it and search again.",
};
const NUL_IN_FILTER = {
  status: 400,
  code: "VALIDATION_ERROR",
  message: "Parameter 'sponsor' contains a character that is not allowed: U+0000. Remove it and search again.",
};
const GLOBAL_SEARCH_TOO_LONG = {
  status: 400,
  code: "VALIDATION_ERROR",
  message: "search.query: size must be between 2 and 200",
};

test("a NUL byte in the search text is treated as refused input", () => {
  assert.equal(isSearchInputRejection(NUL_IN_QUERY), true);
  assert.equal(searchErrorMessage(NUL_IN_QUERY, GENERIC), SEARCH_INPUT_REJECTED_MESSAGE);
});

test("a NUL byte in a structured filter is treated the same way", () => {
  assert.equal(isSearchInputRejection(NUL_IN_FILTER), true);
  assert.equal(searchErrorMessage(NUL_IN_FILTER, GENERIC), SEARCH_INPUT_REJECTED_MESSAGE);
});

test("an ordinary Global Search query past its length limit gets the same guidance", () => {
  // Nothing invisible about it - 250 plain characters. The shared code
  // means the page cannot tell this apart from the two above, so the
  // wording has to be true of all three.
  assert.equal(isSearchInputRejection(GLOBAL_SEARCH_TOO_LONG), true);
  assert.equal(
    searchErrorMessage(GLOBAL_SEARCH_TOO_LONG, GENERIC),
    SEARCH_INPUT_REJECTED_MESSAGE,
  );
});

test("the guidance names no cause it has not established", () => {
  const message = SEARCH_INPUT_REJECTED_MESSAGE.toLowerCase();
  for (const claim of [
    "invisible",
    "control character",
    "character the archive",
    "pasted",
    "too long",
    "length",
    "limit",
  ]) {
    assert.ok(!message.includes(claim), `must not assert "${claim}"`);
  }
});

test("the guidance covers the filters as well as the search box", () => {
  const message = SEARCH_INPUT_REJECTED_MESSAGE.toLowerCase();
  assert.ok(message.includes("search box"));
  assert.ok(message.includes("filter"));
});

test("the guidance never tells the reader to retry the same input", () => {
  const message = SEARCH_INPUT_REJECTED_MESSAGE.toLowerCase();
  assert.ok(!message.includes("try again in a moment"));
  assert.ok(!message.includes("right now"));
  assert.ok(!/\bwait\b/.test(message));
  assert.ok(/adjust|change/.test(message), "it must name an action");
  assert.ok(message.includes("search again"));
});

test("both the status and the code are required, not either alone", () => {
  // The code on its own does not establish that the input was refused.
  assert.equal(isSearchInputRejection({ code: "VALIDATION_ERROR" }), false);
  assert.equal(isSearchInputRejection({ status: 500, code: "VALIDATION_ERROR" }), false);
  // And a 400 on its own can still be a genuine fault.
  assert.equal(isSearchInputRejection({ status: 400 }), false);
  assert.equal(isSearchInputRejection({ status: 400, code: "BAD_REQUEST" }), false);
});

test("a server or network failure is never mistaken for bad input", () => {
  for (const error of [
    { status: 500 },
    { status: 503, code: "SERVICE_UNAVAILABLE" },
    { status: 504 },
    new TypeError("Failed to fetch"),
    undefined,
    null,
  ]) {
    assert.equal(isSearchInputRejection(error), false);
    assert.equal(searchErrorMessage(error, GENERIC), GENERIC);
  }
});

test("no submitted value or internal detail reaches the message", () => {
  // The API's own sentences carry parameter names, code points and
  // constraint internals. None of that belongs under a search box.
  for (const error of [NUL_IN_QUERY, NUL_IN_FILTER, GLOBAL_SEARCH_TOO_LONG]) {
    const message = searchErrorMessage(error, GENERIC);
    assert.ok(!message.includes("U+"));
    assert.ok(!/parameter/i.test(message));
    assert.ok(!message.includes("search.query"));
    assert.ok(!message.includes("'q'"));
    assert.ok(!message.includes("sponsor"));
  }
  // Nothing interpolates the query, so a hostile value cannot be echoed.
  const withValue = searchErrorMessage(
    { status: 400, code: "VALIDATION_ERROR", message: "Parameter 'q' ... smithPAYLOAD" },
    GENERIC,
  );
  assert.ok(!withValue.includes("smithPAYLOAD"));
});

test("every search page routes its error through the resolver", () => {
  // Each page keeps its own generic sentence for real failures, but no
  // page may hand that sentence straight to SearchStates any more.
  const pages = [
    "../../pages/award/AwardSearchPage.tsx",
    "../../pages/award/AwardVersionSearchPage.tsx",
    "../../pages/ProposalFamiliesPage.tsx",
    "../../pages/NegotiationFamiliesPage.tsx",
    "../../pages/SubawardFamiliesPage.tsx",
    "../../pages/GlobalSearchPage.tsx",
  ];
  for (const page of pages) {
    const source = readFileSync(fileURLToPath(new URL(page, import.meta.url)), "utf8");
    assert.match(source, /searchErrorMessage\(/, `${page} must use the resolver`);
    assert.doesNotMatch(
      source,
      /errorMessage="/,
      `${page} must not pass a fixed message straight through`,
    );
  }
});
