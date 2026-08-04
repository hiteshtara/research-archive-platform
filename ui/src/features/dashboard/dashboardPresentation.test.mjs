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

test("Historical Award Records card also uses the current Award Search route", () => {
  const card = findCard(historicalActivityCards, "awardHistoryRecords");

  assert.equal(card.path, "/awards/search");
  assert.notEqual(card.path, "/awards");
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
