import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  HIERARCHY_SCROLL_CONTAINER_SX,
  HIERARCHY_TREE_CANVAS_SX,
  SELECTED_NODE_SCROLL_OPTIONS,
} from "./awardHierarchyLayout.mjs";

const treeSource = readFileSync(
  fileURLToPath(
    new URL("../../components/award/AwardHierarchyTree.tsx", import.meta.url),
  ),
  "utf8",
);

test("the hierarchy scrolls inside its own area instead of widening the page", () => {
  assert.equal(HIERARCHY_SCROLL_CONTAINER_SX.overflowX, "auto");
  // width 0 + minWidth 100%: fills the parent, contributes no min-content
  // width, so a wide family can never push the page (or main) wider.
  assert.equal(HIERARCHY_SCROLL_CONTAINER_SX.width, 0);
  assert.equal(HIERARCHY_SCROLL_CONTAINER_SX.minWidth, "100%");
});

test("the tree is centred with auto margins, which cannot push nodes off the left edge", () => {
  assert.equal(HIERARCHY_TREE_CANVAS_SX.width, "max-content");
  assert.equal(HIERARCHY_TREE_CANVAS_SX.mx, "auto");
});

test("the selected Award is scrolled into view without moving the page vertically", () => {
  assert.equal(SELECTED_NODE_SCROLL_OPTIONS.block, "nearest");
  assert.equal(SELECTED_NODE_SCROLL_OPTIONS.inline, "center");
});

test("AwardHierarchyTree uses the contained layout (QA-D1 regression)", () => {
  assert.match(treeSource, /sx=\{HIERARCHY_SCROLL_CONTAINER_SX\}/);
  assert.match(treeSource, /sx=\{HIERARCHY_TREE_CANVAS_SX\}/);
  assert.match(treeSource, /role="region"/);
  // The QA-D1 cause: flex-centring an overflowing row splits the overflow
  // across both sides, and the left side can never be scrolled to.
  assert.equal(
    /justifyContent:\s*"center"/.test(treeSource),
    false,
    "the tree container must not flex-centre an overflowing row",
  );
});

test("every node stays a keyboard-operable button with an accessible name", () => {
  assert.match(treeSource, /role="button"/);
  assert.match(treeSource, /tabIndex=\{0\}/);
  assert.match(treeSource, /aria-label=\{`Open Award \$\{node\.awardNumber\}`\}/);
  assert.match(treeSource, /event\.key === "Enter" \|\| event\.key === " "/);
});
