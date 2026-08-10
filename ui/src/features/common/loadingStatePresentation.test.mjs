import assert from "node:assert/strict";
import test from "node:test";

import { resolveSkeletonRowHeights } from "./loadingStatePresentation.mjs";

test("resolveSkeletonRowHeights defaults to a single 220px row - LoadingState's own default", () => {
  assert.deepEqual(resolveSkeletonRowHeights(), [220]);
  assert.deepEqual(resolveSkeletonRowHeights({}), [220]);
});

test("resolveSkeletonRowHeights returns one row at the given height when count is 1", () => {
  assert.deepEqual(resolveSkeletonRowHeights({ height: 72 }), [72]);
});

test("resolveSkeletonRowHeights repeats the same height count times - e.g. AwardPeopleSection's 3 identical rows", () => {
  assert.deepEqual(resolveSkeletonRowHeights({ height: 96, count: 3 }), [
    96, 96, 96,
  ]);
});

test("resolveSkeletonRowHeights uses explicit heights verbatim, ignoring height/count - e.g. AwardTermsSection's [64, 160]", () => {
  assert.deepEqual(
    resolveSkeletonRowHeights({ height: 999, count: 5, heights: [64, 160] }),
    [64, 160],
  );
});
