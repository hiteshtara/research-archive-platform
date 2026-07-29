import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  currentAwardFacts,
  estimatedReadingSeconds,
  orderAwardTimeline,
  sequenceLabelFromChange,
  showDevelopmentMetadata,
  timelineLabel,
  visibleAwardTimeline,
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

test("builds compact current Award facts and omits missing dates", () => {
  const facts = currentAwardFacts(
    {
      status: "Closed",
      sequenceNumber: 21,
      sponsor: "University Sponsor",
      leadUnit: "SAR OCC",
      principalInvestigators: ["GAEL ORSMOND"],
      anticipatedTotalAmount: 699246.57,
      obligatedTotalAmount: 650000,
      beginDate: null,
      closeoutDate: null,
    },
    (amount) => `$${amount.toFixed(2)}`,
  );

  assert.deepEqual(
    facts.map((fact) => fact.label),
    [
      "Status",
      "Current Sequence",
      "Sponsor",
      "Lead Unit",
      "Principal Investigator(s)",
      "Current Amounts",
    ],
  );
  assert.equal(
    facts.find((fact) => fact.label === "Current Amounts").value,
    "$699246.57 anticipated · $650000.00 obligated",
  );
  assert.equal(facts.some((fact) => fact.label === "Dates"), false);
});

test("includes a date card when either authoritative date exists", () => {
  const facts = currentAwardFacts(
    {
      status: null,
      sequenceNumber: 1,
      sponsor: null,
      leadUnit: null,
      principalInvestigators: [],
      anticipatedTotalAmount: null,
      obligatedTotalAmount: null,
      beginDate: "2020-01-01",
      closeoutDate: null,
    },
    String,
  );

  assert.deepEqual(facts, [
    { label: "Current Sequence", value: "1" },
    { label: "Dates", value: "Begins 2020-01-01" },
  ]);
});

test("timeline initially shows five recent records plus Original", () => {
  const ordered = Array.from({ length: 10 }, (_, index) => ({
    awardId: 110 - index,
    sequenceNumber: 10 - index,
  }));

  assert.deepEqual(
    visibleAwardTimeline(ordered, false).map(
      (record) => record.sequenceNumber,
    ),
    [10, 9, 8, 7, 6, 1],
  );
  assert.deepEqual(
    visibleAwardTimeline(ordered, true).map(
      (record) => record.sequenceNumber,
    ),
    [10, 9, 8, 7, 6, 5, 4, 3, 2, 1],
  );
});

test("extracts only explicit sequence labels for Key Change cards", () => {
  assert.equal(
    sequenceLabelFromChange("Sequence 16 entered closing."),
    "Sequence 16",
  );
  assert.equal(
    sequenceLabelFromChange("The award entered closing."),
    null,
  );
});

test("uses deterministic reading time with a ten-second minimum", () => {
  assert.equal(estimatedReadingSeconds(["Short summary."]), 10);
  assert.equal(
    estimatedReadingSeconds([Array.from({ length: 60 }, () => "word").join(" ")]),
    20,
  );
});

test("Sources render collapsed with the response count and Key Change cards", () => {
  const component = readFileSync(
    new URL("./AwardAiSummaryPanel.tsx", import.meta.url),
    "utf8",
  );

  assert.match(component, /Sources \(\{orderedCitations\.length\}\)/);
  assert.match(component, /component="details"/);
  assert.doesNotMatch(component, /component="details"\s+open/);
  assert.match(component, />\s*Key Changes\s*</);
  assert.match(component, />\s*Key Change\s*</);
  assert.match(component, /xs: "1fr"/);
});
