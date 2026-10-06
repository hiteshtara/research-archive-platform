import assert from "node:assert/strict";
import { test } from "node:test";

import {
  SEARCH_RETURN_LABEL,
  buildSearchReturn,
  forwardSearchReturn,
  readSearchReturn,
} from "./searchReturnContext.mjs";

/*
 * QA TC-021. The breadcrumb moves between Awards in a hierarchy; it is
 * not a way back to the result list. These tests cover the separate
 * return link: that it carries the exact applied search, that it
 * disappears rather than guessing when there is no search context, and
 * that hand-crafted router state cannot turn it into an off-site link.
 */

test("the captured target is the exact applied search, filters and page included", () => {
  // The decisive case: every part of the applied state lives in the
  // query string, so a round trip must not drop the page number or a
  // filter. If this ever returns only the pathname, a reader on page 4
  // of a filtered search is silently sent back to page 1 of an
  // unfiltered one.
  const search =
    "?q=Orsmond&sponsor=NIH&leadUnit=SAR&versionFilter=historical&sort=sequence&page=3";
  const state = buildSearchReturn("/awards/search", search);

  assert.equal(readSearchReturn(state), `/awards/search${search}`);
});

test("a search with no criteria in the URL still round-trips", () => {
  const state = buildSearchReturn("/awards/search", "");
  assert.equal(readSearchReturn(state), "/awards/search");
});

test("the search string is optional", () => {
  assert.equal(readSearchReturn(buildSearchReturn("/awards/search")), "/awards/search");
});

test("no context means no link, never a guessed destination", () => {
  // The arrival routes that genuinely carry no state, measured in a
  // browser: a new tab from a middle- or Cmd-click, and a pasted or
  // bookmarked link. NOT a reload - router state lives in the history
  // entry, which survives one, so a refreshed dashboard keeps its link.
  // See the module comment for the full measured matrix.
  assert.equal(readSearchReturn(undefined), null);
  assert.equal(readSearchReturn(null), null);
  assert.equal(readSearchReturn({}), null);
  assert.equal(readSearchReturn({ searchReturn: "" }), null);
  assert.equal(readSearchReturn({ somethingElse: "/awards/search" }), null);
});

test("a non-string target is refused rather than coerced", () => {
  assert.equal(readSearchReturn({ searchReturn: 42 }), null);
  assert.equal(readSearchReturn({ searchReturn: ["/awards/search"] }), null);
  assert.equal(readSearchReturn({ searchReturn: { to: "/awards/search" } }), null);
});

test("only a site-internal absolute path is accepted", () => {
  // Router state persists in the history entry and can be hand-crafted,
  // so this is untrusted input. A "back" link is exactly the control a
  // reader clicks without reading, which is why an off-site target must
  // be impossible rather than merely unlikely.
  assert.equal(readSearchReturn({ searchReturn: "https://example.invalid" }), null);
  assert.equal(readSearchReturn({ searchReturn: "http://example.invalid" }), null);
  assert.equal(
    readSearchReturn({ searchReturn: "javascript:alert(1)" }), // eslint-disable-line no-script-url
    null,
  );
  assert.equal(readSearchReturn({ searchReturn: "//example.invalid/awards" }), null);
  assert.equal(readSearchReturn({ searchReturn: "/\\example.invalid/awards" }), null);
  assert.equal(readSearchReturn({ searchReturn: "awards/search" }), null);
  assert.equal(readSearchReturn({ searchReturn: "../awards/search" }), null);
});

test("a control character in the target is refused", () => {
  assert.equal(readSearchReturn({ searchReturn: "/awards/search\u0000" }), null);
  assert.equal(readSearchReturn({ searchReturn: "/awards/\u001bsearch" }), null);
});

test("a query string may legitimately contain an encoded slash or colon", () => {
  // Filter values are URL-encoded, so this must not be caught by the
  // host-shape checks above - refusing it would break the return link
  // for any search carrying a date range or a unit name with a slash.
  const state = buildSearchReturn("/awards/search", "?q=a%2Fb&from=2020-01-01");
  assert.equal(readSearchReturn(state), "/awards/search?q=a%2Fb&from=2020-01-01");
});

test("forwarding survives each in-app hop and stays validated", () => {
  // search -> hierarchy -> dashboard -> breadcrumb -> dashboard. The
  // link has to still be there at the end.
  const atSearch = buildSearchReturn("/awards/search", "?q=Orsmond&page=2");
  const atHierarchy = forwardSearchReturn(atSearch);
  const atDashboard = forwardSearchReturn(atHierarchy);
  const afterBreadcrumbHop = forwardSearchReturn(atDashboard);

  assert.equal(readSearchReturn(afterBreadcrumbHop), "/awards/search?q=Orsmond&page=2");
});

test("forwarding nothing yields undefined, not an empty state object", () => {
  // Link and navigate() treat undefined as "no state"; an object with a
  // junk value would be carried along and then refused on arrival.
  assert.equal(forwardSearchReturn(undefined), undefined);
  assert.equal(forwardSearchReturn({}), undefined);
  assert.equal(forwardSearchReturn({ searchReturn: "https://example.invalid" }), undefined);
});

test("the label is fixed so the control reads the same everywhere", () => {
  assert.equal(SEARCH_RETURN_LABEL, "Back to search results");
});
