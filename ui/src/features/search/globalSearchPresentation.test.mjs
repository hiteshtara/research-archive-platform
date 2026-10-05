import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  describeGlobalSearchOutcome,
  describeResultCard,
  filterOutIrbResults,
  incompleteSearchMessage,
  isRelatedResult,
  noDirectMatchesMessage,
  splitDirectAndRelated,
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
  // The shared ResultCard shows the identifier as the card's own first
  // line, so the secondary detail is card.subtitleLine - identifierLine
  // would repeat it. Both still come from describeResultCard, which is
  // what this test exists to enforce.
  assert.match(source, /card\.subtitleLine/);
  assert.match(source, /card\.piLine/);
  assert.match(source, /card\.matchedCaption/);
  assert.doesNotMatch(source, /"Related match"/);
});


test("describeResultCard exposes the subtitle separately from the identifier line", () => {
  const withSubtitle = describeResultCard({
    identifier: "100013-00001",
    subtitle: "The Children's Hospital Corporation",
  });

  assert.equal(
    withSubtitle.identifierLine,
    "100013-00001 • The Children's Hospital Corporation",
  );
  assert.equal(
    withSubtitle.subtitleLine,
    "The Children's Hospital Corporation",
  );

  // No subtitle means no secondary line at all, rather than the
  // identifier repeated underneath itself.
  const withoutSubtitle = describeResultCard({ identifier: "100013-00001" });
  assert.equal(withoutSubtitle.identifierLine, "100013-00001");
  assert.equal(withoutSubtitle.subtitleLine, null);
});

// --- Direct vs related results (QA TC-041) -------------------------------

const direct = (id) => ({ module: "AWARD", identifier: id, matchType: null });
const related = (id, module = "NEGOTIATION") => ({ module, identifier: id, matchType: "RELATED" });

test("a semantic result is recognised by its match type", () => {
  assert.equal(isRelatedResult(related("x")), true);
  assert.equal(isRelatedResult(direct("x")), false);
  assert.equal(isRelatedResult(undefined), false);
});

test("results split into direct and related, each keeping its order", () => {
  const { direct: d, related: r } = splitDirectAndRelated([
    direct("a"), related("b"), direct("c"), related("d"),
  ]);
  assert.deepEqual(d.map((i) => i.identifier), ["a", "c"]);
  assert.deepEqual(r.map((i) => i.identifier), ["b", "d"]);
});

test("semantic-only results are kept, not discarded", () => {
  // The rejected approach returned nothing here. A searcher who used a
  // synonym must still be offered something.
  const outcome = describeGlobalSearchOutcome({ results: [related("1"), related("2")] });
  assert.equal(outcome.relatedCount, 2);
  assert.equal(outcome.directCount, 0);
});

test("with no direct matches, related results are offered rather than shown", () => {
  const outcome = describeGlobalSearchOutcome({ results: [related("1"), related("2")] });
  assert.equal(outcome.showNoDirectMatches, true);
  assert.equal(outcome.offerRelatedToggle, true);
  assert.equal(outcome.showRelatedSection, false, "hidden until asked for");
  assert.equal(outcome.showRelatedActionLabel, "Show 2 related results");
});

test("once revealed, the related section is shown", () => {
  const outcome = describeGlobalSearchOutcome({
    results: [related("1")], relatedRevealed: true,
  });
  assert.equal(outcome.showRelatedSection, true);
  assert.equal(outcome.showRelatedActionLabel, "Show 1 related result", "singular");
});

test("a mixed result shows direct matches and the related section together", () => {
  const outcome = describeGlobalSearchOutcome({ results: [direct("a"), related("b")] });
  assert.equal(outcome.directCount, 1);
  assert.equal(outcome.relatedCount, 1);
  assert.equal(outcome.showNoDirectMatches, false);
  assert.equal(outcome.showRelatedSection, true, "no action needed when something matched directly");
  assert.equal(outcome.offerRelatedToggle, false);
});

test("a failed module is never reported as nothing found", () => {
  // The distinction that matters: the search did not run, so claiming
  // "no matches" would be a confident wrong answer.
  const outcome = describeGlobalSearchOutcome({ results: [], failedModules: ["AWARD", "PROPOSAL"] });
  assert.equal(outcome.searchIncomplete, true);
  assert.equal(outcome.showNoDirectMatches, false);
  assert.match(incompleteSearchMessage(["AWARD", "PROPOSAL"]), /AWARD, PROPOSAL/);
  assert.match(incompleteSearchMessage(["AWARD"]), /not the whole archive/);
  assert.equal(incompleteSearchMessage([]), null);
});

test("a partial failure still reports the failure alongside what was found", () => {
  const outcome = describeGlobalSearchOutcome({
    results: [direct("a"), related("b")], failedModules: ["NEGOTIATION"],
  });
  assert.equal(outcome.searchIncomplete, true);
  assert.equal(outcome.directCount, 1);
  assert.equal(outcome.showRelatedSection, true);
});

test("a genuinely empty search says no direct matches, naming the query", () => {
  const outcome = describeGlobalSearchOutcome({ results: [] });
  assert.equal(outcome.showNoDirectMatches, true);
  assert.equal(outcome.offerRelatedToggle, false, "nothing to offer");
  assert.match(noDirectMatchesMessage("zzzznotfound123"), /zzzznotfound123/);
  assert.match(noDirectMatchesMessage("  "), /^No direct matches\.$/);
});

test("the related section explains what it is without overclaiming", () => {
  const outcome = describeGlobalSearchOutcome({ results: [related("1")], relatedRevealed: true });
  assert.equal(outcome.relatedHeading, "Related results");
  assert.match(outcome.relatedExplanation, /may not be what you meant/);
  // It must not promise relevance it cannot establish.
  assert.doesNotMatch(outcome.relatedExplanation, /relevant|best|closest|accurate/i);
});

test("the page renders direct and related separately and gates the reveal", () => {
  const page = readFileSync(
    fileURLToPath(new URL("../../pages/GlobalSearchPage.tsx", import.meta.url)),
    "utf8",
  );
  assert.match(page, /describeGlobalSearchOutcome\(/);
  assert.match(page, /outcome\.direct\.map/);
  assert.match(page, /outcome\.related\.map/);
  assert.match(page, /setRelatedRevealed\(true\)/);
  assert.match(page, /incompleteSearchMessage\(/);
  assert.match(page, /noDirectMatchesMessage\(/);
});
