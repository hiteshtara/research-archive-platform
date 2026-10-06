import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  INITIAL_SEARCH_HINT,
  SEARCH_INPUT_REJECTED_MESSAGE,
  SEARCH_TEXT_MAX_LENGTH,
  buildSearchParams,
  canSubmitSearchText,
  describeResultCount,
  formatNamedIdentifier,
  isSearchInputRejection,
  isSearchTextTooLong,
  joinMetadata,
  readSearchParams,
  resolveSearchState,
  searchErrorMessage,
  searchLengthHelperText,
  searchTextLength,
  shouldShowSearchLengthCounter,
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

// --- Free-text search length (QA TC-018) ---------------------------------

const AT_LIMIT = "a".repeat(SEARCH_TEXT_MAX_LENGTH);
const OVER_LIMIT = "a".repeat(SEARCH_TEXT_MAX_LENGTH + 1);

test("the UI limit is the API's own constant, not a second opinion", () => {
  // Read SearchTextLimits.java directly: if either side is edited alone
  // the browser would allow a search the server refuses, or refuse one
  // it would accept.
  const java = readFileSync(
    fileURLToPath(
      new URL(
        "../../../../api/src/main/java/edu/bu/archive/adapter/in/web/SearchTextLimits.java",
        import.meta.url,
      ),
    ),
    "utf8",
  );
  const max = java.match(/MAX_SEARCH_TEXT_LENGTH\s*=\s*(\d+)/);
  assert.ok(max, "could not read MAX_SEARCH_TEXT_LENGTH from the API");
  assert.equal(Number(max[1]), SEARCH_TEXT_MAX_LENGTH);
  // Global Search's existing minimum must survive untouched.
  const min = java.match(/MIN_GLOBAL_SEARCH_TEXT_LENGTH\s*=\s*(\d+)/);
  assert.ok(min);
  assert.equal(Number(min[1]), 2);
});

test("the boundary is inclusive: exactly the limit is allowed, one more is not", () => {
  assert.equal(searchTextLength(AT_LIMIT), SEARCH_TEXT_MAX_LENGTH);
  assert.equal(isSearchTextTooLong(AT_LIMIT), false);
  assert.equal(canSubmitSearchText(AT_LIMIT), true);

  assert.equal(searchTextLength(OVER_LIMIT), SEARCH_TEXT_MAX_LENGTH + 1);
  assert.equal(isSearchTextTooLong(OVER_LIMIT), true);
  assert.equal(canSubmitSearchText(OVER_LIMIT), false);
});

test("ordinary and wildcard searches are unaffected by the limit", () => {
  for (const value of ["", "105698", "*105698*", "smith", "50%", "A_B", "autism"]) {
    assert.equal(isSearchTextTooLong(value), false, `${value} must still be searchable`);
    assert.equal(canSubmitSearchText(value), true);
    assert.equal(searchLengthHelperText(value), null, "no counter on an ordinary search");
  }
});

test("the counter appears before the limit is reached, not after", () => {
  assert.equal(shouldShowSearchLengthCounter("a".repeat(149)), false);
  assert.equal(shouldShowSearchLengthCounter("a".repeat(150)), true);
  assert.equal(searchLengthHelperText("a".repeat(150)), "150 of 200 characters");
  assert.equal(searchLengthHelperText(AT_LIMIT), "200 of 200 characters");
});

test("over the limit, the message says how many characters to remove", () => {
  assert.equal(
    searchLengthHelperText(OVER_LIMIT),
    "201 characters. Searches are limited to 200; remove 1 character to search.",
  );
  assert.equal(
    searchLengthHelperText("a".repeat(250)),
    "250 characters. Searches are limited to 200; remove 50 characters to search.",
  );
});

test("pasted input is never silently truncated", () => {
  // The helpers only measure and describe - nothing here shortens a
  // value, and the box deliberately carries no maxLength.
  const pasted = "x".repeat(900);
  assert.equal(searchTextLength(pasted), 900);
  assert.match(searchLengthHelperText(pasted), /remove 700 characters/);

  const box = readFileSync(
    fileURLToPath(new URL("../../components/common/search/SearchBox.tsx", import.meta.url)),
    "utf8",
  );
  // Match an actual prop assignment, not the comment explaining why it
  // is absent: maxLength={...} or maxLength: ... would truncate a paste.
  assert.doesNotMatch(
    box,
    /maxLength\s*[:=]/,
    "a maxLength prop would truncate a paste silently",
  );
  assert.match(box, /canSubmitSearchText\(/, "the box must refuse an over-long submit");
});

test("the UI and the API count the same units, including astral characters", () => {
  // An emoji is two UTF-16 code units in both JavaScript and Java, so
  // the two sides agree on the length of this string.
  const emoji = "\u{1F600}";
  assert.equal(searchTextLength(emoji), 2);
  const hundredEmoji = emoji.repeat(100);
  assert.equal(searchTextLength(hundredEmoji), SEARCH_TEXT_MAX_LENGTH);
  assert.equal(isSearchTextTooLong(hundredEmoji), false);
  assert.equal(isSearchTextTooLong(hundredEmoji + emoji), true);
});

test("an empty or missing search is not treated as over-long", () => {
  for (const value of ["", null, undefined]) {
    assert.equal(isSearchTextTooLong(value), false);
    assert.equal(canSubmitSearchText(value), true);
  }
});

test("an over-long search submitted through the URL is still handled by TC-017's path", () => {
  // The box can only guard what is typed into it. A URL-borne query
  // reaches the API, which refuses it with the shared code, and that
  // keeps producing the general guidance rather than a server error.
  const apiRefusal = { status: 400, code: "VALIDATION_ERROR" };
  assert.equal(isSearchInputRejection(apiRefusal), true);
  assert.equal(
    searchErrorMessage(apiRefusal, "Unable to search Awards right now. Try again in a moment."),
    SEARCH_INPUT_REJECTED_MESSAGE,
  );
});

// --- Every submission path, not just the search box (QA TC-018) ----------

/*
 * There is no component-render harness in this project, so these drive
 * the same decision function the components call, and pin the wiring by
 * reading the sources - the way the navigation and Archived File Finder
 * suites do.
 */

function simulateSharedSubmit(draftQuery) {
  // What FilteredSearchBar's shared `submit` does: both Enter in the
  // box and Apply Filters in the panel go through this.
  let ran = false;
  const runSearch = () => { ran = true; };
  const queryTooLong = !canSubmitSearchText(draftQuery);
  const submit = () => { if (queryTooLong) return; runSearch(); };
  submit();
  return { ran, applyDisabled: queryTooLong };
}

test("Enter and Apply Filters are both refused once the query is too long", () => {
  const over = "a".repeat(SEARCH_TEXT_MAX_LENGTH + 1);
  const result = simulateSharedSubmit(over);
  assert.equal(result.ran, false, "the search must not run");
  assert.equal(result.applyDisabled, true, "Apply Filters must be disabled");
});

test("at exactly the limit both paths still run the search", () => {
  const atLimit = "a".repeat(SEARCH_TEXT_MAX_LENGTH);
  const result = simulateSharedSubmit(atLimit);
  assert.equal(result.ran, true);
  assert.equal(result.applyDisabled, false);
});

test("an ordinary search is unaffected by the guard", () => {
  for (const value of ["", "105698", "*105698*", "smith"]) {
    assert.equal(simulateSharedSubmit(value).ran, true, `${value} must still search`);
  }
});

test("the guard lives in the shared handler, so Apply Filters cannot bypass it", () => {
  const bar = readFileSync(
    fileURLToPath(new URL("../../components/common/search/FilteredSearchBar.tsx", import.meta.url)),
    "utf8",
  );
  // One `submit` is handed to the search box AND to the panel's onApply.
  assert.match(bar, /canSubmitSearchText\(/, "the shared handler must check the length");
  assert.match(bar, /onSubmit=\{submit\}/, "the box must use the guarded handler");
  assert.match(bar, /onApply=\{submit\}/, "Apply Filters must use the guarded handler");
  assert.match(bar, /applyDisabled=\{![^}]*queryTooLong\}/, "Apply must be disabled when too long");
  // A page supplying its own onSubmit is wrapped, not trusted.
  assert.match(bar, /const runSearch = onSubmit \?\?/);
});

function simulateDashboardSubmit(searchText) {
  // What DashboardPage.submitSearch does.
  const normalized = searchText.trim();
  const navigated = normalized.length >= 2 && canSubmitSearchText(searchText);
  const buttonDisabled = normalized.length < 2 || isSearchTextTooLong(searchText);
  return { navigated, buttonDisabled };
}

test("the dashboard search applies the same limit and keeps its own minimum", () => {
  const over = "a".repeat(SEARCH_TEXT_MAX_LENGTH + 1);
  assert.deepEqual(simulateDashboardSubmit(over), { navigated: false, buttonDisabled: true });

  const atLimit = "a".repeat(SEARCH_TEXT_MAX_LENGTH);
  assert.deepEqual(simulateDashboardSubmit(atLimit), { navigated: true, buttonDisabled: false });

  // The existing two-character minimum is unchanged.
  assert.deepEqual(simulateDashboardSubmit("a"), { navigated: false, buttonDisabled: true });
  assert.deepEqual(simulateDashboardSubmit("ab"), { navigated: true, buttonDisabled: false });
});

test("the dashboard search is wired to the shared helpers and shows guidance", () => {
  const page = readFileSync(
    fileURLToPath(new URL("../../pages/DashboardPage.tsx", import.meta.url)),
    "utf8",
  );
  assert.match(page, /canSubmitSearchText\(/, "its submit must check the length");
  assert.match(page, /searchLengthHelperText\(/, "it must show the counter and guidance");
  assert.match(page, /error=\{searchTooLong\}/);
  assert.match(page, /searchTooLong\}/, "its Search button must be disabled when too long");
  assert.doesNotMatch(page, /maxLength\s*[:=]/, "it must not truncate a paste either");
});

test("no search input anywhere truncates a paste", () => {
  for (const file of [
    "../../components/common/search/SearchBox.tsx",
    "../../components/common/search/FilteredSearchBar.tsx",
    "../../pages/DashboardPage.tsx",
  ]) {
    const source = readFileSync(fileURLToPath(new URL(file, import.meta.url)), "utf8");
    assert.doesNotMatch(source, /maxLength\s*[:=]/, `${file} must not set maxLength`);
  }
  // And the value is carried intact however long it is.
  const pasted = "x".repeat(1200);
  assert.equal(searchTextLength(pasted), 1200);
});

/*
 * QA TC-015 / TC-009. These pin agreed copy, so a later reword is a
 * deliberate decision rather than a silent drift.
 */
test("the initial-search hint is the agreed sentence and names both routes", () => {
  assert.equal(
    INITIAL_SEARCH_HINT,
    "Enter a search term, or apply a filter, to see results.",
  );
  // Filter-only searching is supported, so the hint must not imply that
  // typing is the only way in.
  assert.match(INITIAL_SEARCH_HINT, /filter/);
});

test("the initial hint is guidance, not a validation error", () => {
  // An empty box is an ordinary starting state. Wording that blames the
  // reader would be wrong, and would also contradict TC-015's finding
  // that nothing erroneous happens.
  assert.doesNotMatch(INITIAL_SEARCH_HINT, /error|invalid|must|required/i);
});
