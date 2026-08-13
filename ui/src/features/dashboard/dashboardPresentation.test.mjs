import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  futureModuleCards,
  historicalActivityCards,
  primaryBusinessCards,
} from "./dashboardPresentation.mjs";

function findCard(cards, key) {
  const card = cards.find((candidate) => candidate.key === key);
  assert.ok(card, `expected a card with key "${key}"`);
  return card;
}

test("Awards card navigates to the current Award Search route, not the retired legacy listing", () => {
  const card = findCard(primaryBusinessCards, "awards");

  assert.equal(card.path, "/awards/search");
  assert.notEqual(card.path, "/awards");
});

test("Historical Award Records card routes to the version-level explorer, not the Awards family search", () => {
  const awards = findCard(primaryBusinessCards, "awards");
  const history = findCard(historicalActivityCards, "awardHistoryRecords");

  assert.equal(history.path, "/awards/versions/search");
  assert.notEqual(history.path, awards.path);
  assert.notEqual(history.path, "/awards");
});

test("Awards and Historical Award Records describe different grains, matching their different dashboard counts", () => {
  const awards = findCard(primaryBusinessCards, "awards");
  const history = findCard(historicalActivityCards, "awardHistoryRecords");

  assert.match(awards.description, /one current record per/i);
  assert.match(history.description, /all archived .*version/i);
});

test("every card config has a non-empty key/title/description/path", () => {
  for (const cards of [
    primaryBusinessCards,
    historicalActivityCards,
    futureModuleCards,
  ]) {
    for (const card of cards) {
      assert.ok(card.key, "card.key must be set");
      assert.ok(card.title, `${card.key}: card.title must be set`);
      assert.ok(
        card.description,
        `${card.key}: card.description must be set`,
      );
      assert.ok(
        card.path.startsWith("/"),
        `${card.key}: card.path must be an absolute route`,
      );
    }
  }
});

// The legacy Award Families/History pages predated Award Search and
// bypassed it entirely - this proves they're actually gone from the
// router (not just unlinked), rather than merely trusting that no card
// points at them anymore. Same static-source-inspection approach
// already used in awardSectionsPresentation.test.mjs for properties
// that can't be checked without a component-render harness.
test("App.tsx no longer imports or routes to the retired AwardFamiliesPage/AwardHistoryPage, and redirects their old paths to Award Search", () => {
  const appSourcePath = fileURLToPath(
    new URL("../../App.tsx", import.meta.url),
  );
  const source = readFileSync(appSourcePath, "utf8");

  assert.doesNotMatch(
    source,
    /AwardFamiliesPage|AwardHistoryPage/,
    "the retired legacy Award pages must not be imported or referenced",
  );

  assert.match(
    source,
    /path="awards"[\s\S]{0,80}Navigate to="\/awards\/search"/,
    'path="awards" must redirect to /awards/search',
  );
  assert.match(
    source,
    /path="awards\/history\/:awardNumber"[\s\S]{0,80}Navigate to="\/awards\/search"/,
    'path="awards/history/:awardNumber" must redirect to /awards/search',
  );
  assert.match(
    source,
    /path="awards\/search"[\s\S]{0,40}AwardSearchPage/,
    "the real Award Search route must still be present",
  );
});

// The retired routes must use react-router's `replace` so the dead
// path never lingers in browser history - landing on /awards/search
// via a redirect and pressing Back should return to wherever the user
// was *before* /awards, not bounce back through the retired route.
test("the retired route redirects use replace navigation (so Back skips the dead route, not just Award Search)", () => {
  const appSourcePath = fileURLToPath(
    new URL("../../App.tsx", import.meta.url),
  );
  const source = readFileSync(appSourcePath, "utf8");

  const redirectBlocks = source.match(
    /path="awards(\/history\/:awardNumber)?"\s*\n\s*element=\{<Navigate[^}]*\}/g,
  );

  assert.equal(
    redirectBlocks?.length,
    2,
    "expected exactly two retired-route redirects (awards, awards/history/:awardNumber)",
  );
  for (const block of redirectBlocks) {
    assert.match(block, /replace/, `redirect must use replace: ${block}`);
  }
});

// --- IRB removal (docs/DECISIONS.md: IRB is outside current
// implementation scope) - the four IRB-specific Dashboard cards must be
// gone, and no remaining card's title/description may reference IRB
// concepts. ---

test("no IRB cards appear on the Dashboard", () => {
  const allCards = [
    ...primaryBusinessCards,
    ...historicalActivityCards,
    ...futureModuleCards,
  ];

  const removedKeys = ["irb", "submissions", "fundingRecords", "timelineEvents"];
  for (const key of removedKeys) {
    assert.equal(
      allCards.some((card) => card.key === key),
      false,
      `card key "${key}" must not appear on the Dashboard`,
    );
  }

  for (const card of allCards) {
    assert.doesNotMatch(
      card.title,
      /irb|protocol|submission|funding relationship|timeline event/i,
      `card "${card.key}" title must not reference IRB concepts`,
    );
    assert.doesNotMatch(
      card.description,
      /irb|protocol|submission|funding relationship|timeline event/i,
      `card "${card.key}" description must not reference IRB concepts`,
    );
    assert.doesNotMatch(
      card.path,
      /^\/irb/,
      `card "${card.key}" must not route into the removed /irb feature`,
    );
  }
});

test("exactly the seven supported-module cards remain, across all three card groups", () => {
  const allKeys = [
    ...primaryBusinessCards,
    ...historicalActivityCards,
    ...futureModuleCards,
  ].map((card) => card.key);

  assert.deepEqual(
    allKeys.sort(),
    [
      "awardHistoryRecords",
      "awards",
      "documents",
      "negotiations",
      "proposalHistoryRecords",
      "proposals",
      "subawards",
    ].sort(),
  );
});

// DashboardPage.tsx renders primaryBusinessCards and
// historicalActivityCards together under one "Awards and Proposals"
// heading/Grid (not two separate near-empty sections) specifically so
// removing IRB's cards doesn't leave a visibly sparse section - proven
// via source inspection since there's no component-render harness.
test("Dashboard reflows Awards/Proposals current and historical cards into a single combined section, not two sparse ones", () => {
  const dashboardPagePath = fileURLToPath(
    new URL("../../pages/DashboardPage.tsx", import.meta.url),
  );
  const source = readFileSync(dashboardPagePath, "utf8");

  assert.match(
    source,
    /\[\s*\.\.\.primaryBusinessCards,\s*\.\.\.historicalActivityCards\s*\]\.map/,
    "primaryBusinessCards and historicalActivityCards must be rendered together in one Grid",
  );
  assert.match(source, /Awards and Proposals/);
});

test("Dashboard no longer imports or renders the Latest IRB archive load card", () => {
  const dashboardPagePath = fileURLToPath(
    new URL("../../pages/DashboardPage.tsx", import.meta.url),
  );
  const source = readFileSync(dashboardPagePath, "utf8");

  assert.doesNotMatch(source, /Latest IRB archive load/);
  assert.doesNotMatch(source, /dashboard\.irb\b/);
  assert.doesNotMatch(source, /dashboard\.submissions\b/);
  assert.doesNotMatch(source, /dashboard\.timelineEvents\b/);
});

test("Dashboard introduction and search placeholder no longer mention protocols", () => {
  const dashboardPagePath = fileURLToPath(
    new URL("../../pages/DashboardPage.tsx", import.meta.url),
  );
  const source = readFileSync(dashboardPagePath, "utf8");

  assert.match(
    source,
    /Search and review archived Awards, Proposals, Negotiations,\s*\n\s*Subawards, and Kuali business documents\./,
  );
  assert.doesNotMatch(source, /protocol/i);
});

test("the sidebar contains no IRB navigation item or footer IRB count", () => {
  const appLayoutPath = fileURLToPath(
    new URL("../../layout/AppLayout.tsx", import.meta.url),
  );
  const source = readFileSync(appLayoutPath, "utf8");

  assert.doesNotMatch(source, /irb/i);
});

test("App.tsx no longer imports or routes to any IRB/protocol/investigator page - old links fall through to the catch-all redirect", () => {
  const appSourcePath = fileURLToPath(
    new URL("../../App.tsx", import.meta.url),
  );
  const source = readFileSync(appSourcePath, "utf8");

  assert.doesNotMatch(
    source,
    /IrbPage|IrbDetailPage|IrbHistoryDetailPage|IrbFamiliesPage|IrbHistoryPage|InvestigatorProfilePage/,
  );
  assert.doesNotMatch(source, /path="irb/);
  assert.doesNotMatch(source, /path="protocols/);
  assert.doesNotMatch(source, /path="investigators/);

  // The catch-all redirect is what old IRB bookmarks/links fall through
  // to once their explicit routes are gone.
  assert.match(
    source,
    /path="\*"[\s\S]{0,60}Navigate to="\/" replace/,
  );
});

test("supported Award, Historical Award, Proposal, Negotiation, Subaward, Document, and Explorer routes remain available", () => {
  const appSourcePath = fileURLToPath(
    new URL("../../App.tsx", import.meta.url),
  );
  const source = readFileSync(appSourcePath, "utf8");

  assert.match(source, /path="awards\/search"[\s\S]{0,40}AwardSearchPage/);
  assert.match(
    source,
    /path="awards\/versions\/search"[\s\S]{0,40}AwardVersionSearchPage/,
  );
  assert.match(source, /path="proposals"[\s\S]{0,40}ProposalFamiliesPage/);
  assert.match(
    source,
    /path="negotiations"[\s\S]{0,40}NegotiationFamiliesPage/,
  );
  assert.match(source, /path="subawards"[\s\S]{0,40}SubawardFamiliesPage/);
  assert.match(source, /path="documents"[\s\S]{0,40}DocumentsPage/);
  assert.match(source, /path="explorer"/);
});
