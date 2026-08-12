import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  isNavItemActive,
  sidebarNavigationItems,
} from "./navigationPresentation.mjs";

function findItem(key) {
  const item = sidebarNavigationItems.find((candidate) => candidate.key === key);
  assert.ok(item, `expected a sidebar item with key "${key}"`);
  return item;
}

test("sidebar displays both Awards and Historical Awards", () => {
  const awards = findItem("awards");
  const historicalAwards = findItem("historicalAwards");

  assert.equal(awards.label, "Awards");
  assert.equal(historicalAwards.label, "Historical Awards");
});

test("Awards routes to the family/current-record search", () => {
  const awards = findItem("awards");

  assert.equal(awards.path, "/awards/search");
});

test("Historical Awards routes to the version-level explorer, the same route the Dashboard card uses", () => {
  const historicalAwards = findItem("historicalAwards");

  assert.equal(historicalAwards.path, "/awards/versions/search");
  assert.notEqual(historicalAwards.path, findItem("awards").path);
});

test("Historical Awards sits immediately below Awards in the sidebar", () => {
  const awardsIndex = sidebarNavigationItems.findIndex((item) => item.key === "awards");
  const historicalIndex = sidebarNavigationItems.findIndex(
    (item) => item.key === "historicalAwards",
  );

  assert.equal(historicalIndex, awardsIndex + 1);
});

test("only the correct sidebar item is active on each route - the two active states never overlap", () => {
  const awards = findItem("awards");
  const historicalAwards = findItem("historicalAwards");

  assert.equal(isNavItemActive(awards.path, "/awards/search"), true);
  assert.equal(isNavItemActive(historicalAwards.path, "/awards/search"), false);

  assert.equal(isNavItemActive(historicalAwards.path, "/awards/versions/search"), true);
  assert.equal(isNavItemActive(awards.path, "/awards/versions/search"), false);

  // Neither the current-record search nor the historical explorer
  // should ever light up on an unrelated Award detail route.
  assert.equal(isNavItemActive(awards.path, "/awards/3561589"), false);
  assert.equal(isNavItemActive(historicalAwards.path, "/awards/3561589"), false);
});

test("isNavItemActive matches sub-routes at path-segment boundaries, never a raw substring", () => {
  assert.equal(isNavItemActive("/awards/search", "/awards/search/nested"), true);
  // "/awards/search" must never match "/awards/searchxyz" just because
  // the string happens to start with the same characters.
  assert.equal(isNavItemActive("/awards/search", "/awards/searchxyz"), false);
});

test("every sidebar item has a non-empty key/label and an absolute path", () => {
  for (const item of sidebarNavigationItems) {
    assert.ok(item.key, "item.key must be set");
    assert.ok(item.label, `${item.key}: item.label must be set`);
    assert.ok(item.path.startsWith("/"), `${item.key}: item.path must be an absolute route`);
  }
});

// No component-render harness exists in this project, so this proves
// AppLayout.tsx actually renders from the shared config (not a
// duplicated inline array that could drift from it) - same
// static-source-inspection approach dashboardPresentation.test.mjs uses
// to verify App.tsx's router configuration.
test("AppLayout.tsx renders the sidebar from sidebarNavigationItems, not a duplicated inline list", () => {
  const appLayoutPath = fileURLToPath(
    new URL("../../layout/AppLayout.tsx", import.meta.url),
  );
  const source = readFileSync(appLayoutPath, "utf8");

  assert.match(
    source,
    /import\s*\{[^}]*sidebarNavigationItems[^}]*\}\s*from\s*"[^"]*navigationPresentation\.mjs"/,
    "AppLayout.tsx must import sidebarNavigationItems from navigationPresentation.mjs",
  );
});
