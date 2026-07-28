import assert from "node:assert/strict";
import test from "node:test";

import {
  orderAwardTimeline,
  showDevelopmentMetadata,
  timelineLabel,
} from "./awardAiPresentation.mjs";

test("orders timeline descending and labels its history", () => {
  const ordered = orderAwardTimeline([
    { awardId: 101, sequenceNumber: 1 },
    { awardId: 108, sequenceNumber: 8 },
    { awardId: 109, sequenceNumber: 9 },
  ]);

  assert.deepEqual(
    ordered.map((record) => record.sequenceNumber),
    [9, 8, 1],
  );
  assert.deepEqual(
    ordered.map((record) =>
      timelineLabel(record.sequenceNumber, 9, 1),
    ),
    ["Current", "Previous", "Original"],
  );
});

test("hides developer metadata outside development mode", () => {
  assert.equal(showDevelopmentMetadata(false), false);
  assert.equal(showDevelopmentMetadata(true), true);
});
