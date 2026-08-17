import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  describeResultCard,
  filterOutIrbResults,
} from "./globalSearchPresentation.mjs";

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

// --- describeResultCard: semantic result card enrichment -----------------
//
// Fixtures mirror the live acceptance queries used to verify the
// semantic-result card enrichment feature:
// "rural mortality disparities cancer" -> Award 104628-00002, and
// "gonorrhea prevention vaccine research" -> Proposals 01117952/01099385.

test("describeResultCard renders full metadata for an enriched Award semantic match", () => {
  const card = describeResultCard({
    module: "AWARD",
    identifier: "104628-00002",
    title: "Cancer Disparities in California",
    subtitle: "National Cancer Institute",
    status: "Active",
    principalInvestigator: "Ulrike Boehmer",
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });

  assert.equal(card.title, "Cancer Disparities in California");
  assert.equal(card.identifier, "104628-00002");
  assert.equal(
    card.identifierLine,
    "104628-00002 • National Cancer Institute",
  );
  assert.equal(card.piLine, "PI: Ulrike Boehmer");
  assert.equal(card.showSemanticChip, true);
  assert.equal(card.semanticChipLabel, "Semantic match");
  // Real metadata is present, so no "Matched on: ..." duplicate caption.
  assert.equal(card.matchedCaption, null);
});

test("describeResultCard renders full metadata for an enriched Proposal semantic match", () => {
  const card = describeResultCard({
    module: "PROPOSAL",
    identifier: "01117952",
    title: "Gonorrhea Vaccine Development",
    subtitle: "NIH",
    status: "Funded",
    principalInvestigator: "Dr. Jerse",
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });

  assert.equal(card.title, "Gonorrhea Vaccine Development");
  assert.equal(card.identifierLine, "01117952 • NIH");
  assert.equal(card.piLine, "PI: Dr. Jerse");
  assert.equal(card.showSemanticChip, true);
  assert.equal(card.matchedCaption, null);
});

test("describeResultCard omits the PI line and subtitle when a semantic match has no PI or sponsor on record", () => {
  const card = describeResultCard({
    module: "AWARD",
    identifier: "104615-00002",
    title: "Untitled Pending Award",
    subtitle: null,
    status: "Pending",
    principalInvestigator: null,
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });

  assert.equal(card.title, "Untitled Pending Award");
  // No subtitle -> identifierLine is the bare identifier, not
  // "104615-00002 • null" or a trailing separator.
  assert.equal(card.identifierLine, "104615-00002");
  assert.equal(card.piLine, null);
});

test("describeResultCard removes the duplicated identifier caption once a semantic result has real enrichment", () => {
  // Before enrichment, a semantic result's matchedField/matchedValue
  // duplicated the identifier already shown on the line above
  // ("Matched on: Semantic (104628-00002)"). Once the backend resolves
  // real metadata, it leaves matchedField/matchedValue null specifically
  // to drop that duplicate - see GlobalSearchService.
  const unenriched = describeResultCard({
    module: "SUBAWARD",
    identifier: "3595",
    title: "3595",
    subtitle: null,
    matchedField: "Semantic",
    matchedValue: "3595",
    matchType: "RELATED",
  });
  const enriched = describeResultCard({
    module: "AWARD",
    identifier: "104628-00002",
    title: "Cancer Disparities in California",
    subtitle: "National Cancer Institute",
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });

  assert.equal(unenriched.matchedCaption, "Matched on: Semantic (3595)");
  assert.equal(enriched.matchedCaption, null);
});

test("describeResultCard preserves exact identifiers and leading zeroes", () => {
  const proposalCard = describeResultCard({
    module: "PROPOSAL",
    identifier: "01099385",
    title: "Gonorrhea Diagnostics Study",
    subtitle: "CDC",
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });
  const awardCard = describeResultCard({
    module: "AWARD",
    identifier: "104628-00002",
    title: "Cancer Disparities in California",
    subtitle: null,
    matchedField: null,
    matchedValue: null,
    matchType: "RELATED",
  });

  assert.equal(proposalCard.identifier, "01099385");
  assert.ok(proposalCard.identifier.startsWith("0"));
  assert.equal(awardCard.identifier, "104628-00002");
});

test("describeResultCard shows no semantic chip for a structured (non-semantic) result", () => {
  const card = describeResultCard({
    module: "AWARD",
    identifier: "100200-00001",
    title: "Campbell Research",
    subtitle: "NSF",
    matchedField: "Title",
    matchedValue: "Campbell Research",
    matchType: null,
  });

  assert.equal(card.showSemanticChip, false);
  assert.equal(
    card.matchedCaption,
    "Matched on: Title (Campbell Research)",
  );
});

test("describeResultCard never throws on a null/undefined result", () => {
  assert.doesNotThrow(() => describeResultCard(null));
  assert.doesNotThrow(() => describeResultCard(undefined));

  const card = describeResultCard(undefined);
  assert.equal(card.identifier, "");
  assert.equal(card.piLine, null);
  assert.equal(card.matchedCaption, null);
});

test("Global Search cards render through describeResultCard rather than raw backend fields", () => {
  const source = readGlobalSearchPageSource();

  assert.match(source, /describeResultCard\(result\)/);
  assert.match(source, /card\.title/);
  assert.match(source, /card\.identifierLine/);
  assert.match(source, /card\.piLine/);
  assert.match(source, /card\.matchedCaption/);
  assert.doesNotMatch(source, /"Related match"/);
});
