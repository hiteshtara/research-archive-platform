/**
 * Layout rules for the Award hierarchy tree (AwardHierarchyTree.tsx).
 *
 * A family's widest row of children can be far wider than the content
 * area - a root with six children needs about 1,540 px. The tree used to
 * be centred with `justifyContent: center` and no overflow handling, so a
 * wide row spilled out on BOTH sides: nodes on the left slid under the
 * sidebar, where they could not be clicked or scrolled to, and the right
 * side widened the whole page.
 *
 * The fix is a contained horizontal scroll area:
 *
 *   - the scroll container fills its parent (`minWidth: 100%`) but
 *     declares `width: 0`, so the tree's width never widens the page
 *     through min-content sizing, and scrolls anything wider;
 *   - the canvas inside is exactly as wide as the tree (`max-content`)
 *     and centred with auto margins. Auto margins centre a narrow tree
 *     and collapse to 0 for a wide one, so it starts at the left edge
 *     and every node is reachable by scrolling - unlike flex centring,
 *     which pushes the overflow to a negative offset.
 */

/** The scroll area. Vertical padding keeps focus rings and hover lift visible. */
export const HIERARCHY_SCROLL_CONTAINER_SX = Object.freeze({
  overflowX: "auto",
  width: 0,
  minWidth: "100%",
  py: 2,
});

/** The tree canvas inside the scroll area. */
export const HIERARCHY_TREE_CANVAS_SX = Object.freeze({
  width: "max-content",
  mx: "auto",
  px: 1,
});

/**
 * Where a selected node is scrolled on first render, so the Award the user
 * asked for is in view even in a very wide family. `nearest` vertically,
 * so the page itself does not jump.
 */
export const SELECTED_NODE_SCROLL_OPTIONS = Object.freeze({
  block: "nearest",
  inline: "center",
});

/**
 * Where a node is scrolled when it receives keyboard focus. The browser's
 * own focus scrolling can leave a card partly outside the scroll area in a
 * wide row, so each card brings itself fully into view: `nearest` on both
 * axes moves the tree only as far as needed and never re-centres a node
 * that is already visible (so clicking a visible card does not jump).
 */
export const FOCUSED_NODE_SCROLL_OPTIONS = Object.freeze({
  block: "nearest",
  inline: "nearest",
});

/**
 * Scroll margins on each node card. The app header is `position: fixed`
 * (about 64 px), so without a top margin a card scrolled into view near the
 * top of the window lands underneath it. 80 px clears the header with a gap.
 */
export const HIERARCHY_NODE_SCROLL_MARGIN_SX = Object.freeze({
  scrollMarginTop: 80,
  scrollMarginBottom: 16,
  scrollMarginLeft: 8,
  scrollMarginRight: 8,
});
