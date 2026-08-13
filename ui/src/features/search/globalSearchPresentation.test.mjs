import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { filterOutIrbResults } from "./globalSearchPresentation.mjs";

function readGlobalSearchPageSource() {
  const pagePath = fileURLToPath(
    new URL("../../pages/GlobalSearchPage.tsx", import.meta.url),
  );
  return readFileSync(pagePath, "utf8");
}

test("filterOutIrbResults strips IRB rows and recomputes totalResults from the filtered set", () => {
  const response = {
    query: "smith",
    totalResults: 3,
    results: [
      { module: "AWARD", identifier: "1" },
      { module: "IRB", identifier: "2" },
      { module: "PROPOSAL", identifier: "3" },
    ],
    failedModules: [],
  };

  const filtered = filterOutIrbResults(response);

  assert.equal(filtered.results.length, 2);
  assert.deepEqual(
    filtered.results.map((result) => result.module),
    ["AWARD", "PROPOSAL"],
  );
  // Must be recomputed from what's actually shown, not the backend's
  // raw pre-filter total - otherwise the displayed count (3) would
  // disagree with the two cards actually rendered.
  assert.equal(filtered.totalResults, 2);
});

test("filterOutIrbResults removes IRB from failedModules so an IRB outage is never surfaced to the user", () => {
  const response = {
    query: "smith",
    totalResults: 0,
    results: [],
    failedModules: ["IRB", "NEGOTIATION"],
  };

  const filtered = filterOutIrbResults(response);

  assert.deepEqual(filtered.failedModules, ["NEGOTIATION"]);
});

test("filterOutIrbResults never throws on a null/undefined/malformed response", () => {
  assert.deepEqual(filterOutIrbResults(null), {
    query: "",
    totalResults: 0,
    results: [],
    failedModules: [],
  });
  assert.deepEqual(filterOutIrbResults(undefined), {
    query: "",
    totalResults: 0,
    results: [],
    failedModules: [],
  });
  assert.deepEqual(filterOutIrbResults({}), {
    query: "",
    totalResults: 0,
    results: [],
    failedModules: [],
  });
});

test("filterOutIrbResults leaves an all-non-IRB response unchanged in content", () => {
  const response = {
    query: "cancer",
    totalResults: 2,
    results: [
      { module: "AWARD", identifier: "1" },
      { module: "SUBAWARD", identifier: "2" },
    ],
    failedModules: [],
  };

  const filtered = filterOutIrbResults(response);

  assert.equal(filtered.totalResults, 2);
  assert.deepEqual(filtered.results, response.results);
});

// No component-render harness exists in this project (see CLAUDE.md), so
// these prove the page copy genuinely no longer mentions IRB/protocol
// concepts and that results are routed through the IRB filter, rather
// than merely trusting a mock - same static-source-inspection approach
// used throughout this project's other presentation-helper test files.

test("Global Search does not offer IRB as a module - description text lists only the supported modules", () => {
  const source = readGlobalSearchPageSource();

  assert.doesNotMatch(source, /\bIRB\b/);
  assert.match(source, /Awards, Proposals, Negotiations, and\s*\n?\s*Subawards/);
});

test("Global Search placeholders and empty-state text do not mention protocols or studies", () => {
  const source = readGlobalSearchPageSource();

  assert.doesNotMatch(source, /protocol/i);
  assert.doesNotMatch(source, /study id/i);
  assert.doesNotMatch(source, /crc number/i);
  assert.doesNotMatch(source, /funding source/i);
  assert.doesNotMatch(source, /review type/i);
});

test("Global Search results are routed through filterOutIrbResults before rendering", () => {
  const source = readGlobalSearchPageSource();

  assert.match(source, /filterOutIrbResults\(await globalSearch\(/);
});
