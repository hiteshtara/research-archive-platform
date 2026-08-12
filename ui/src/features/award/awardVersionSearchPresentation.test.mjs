import assert from "node:assert/strict";
import test from "node:test";

import {
  describeVersionSearchResults,
  isValidAwardIdInput,
  versionCurrentLabel,
  versionDetailPath,
} from "./awardVersionSearchPresentation.mjs";

// CARB-X 204713-00001 regression fixture: current award_id 3561610
// (sequence 544) and non-current award_id 3561589 (sequence 543),
// distinguished by status ("Closed" vs "Approved Award") - the same
// pair used by the API-side tests.
const CURRENT_HIT = {
  awardId: 3561610,
  awardNumber: "204713-00001",
  sequenceNumber: 544,
  documentNumber: "DOC-544",
  title: "CARB-X",
  status: "Closed",
  sponsor: "Boston University",
  principalInvestigator: "PI NAME",
  leadUnit: "MEDICINE",
  awardEffectiveDate: null,
  updateTimestamp: "2023-11-30T14:37:54",
  primaryCurrent: true,
};

const HISTORICAL_HIT = {
  awardId: 3561589,
  awardNumber: "204713-00001",
  sequenceNumber: 543,
  documentNumber: "DOC-543",
  title: "CARB-X",
  status: "Approved Award",
  sponsor: "Boston University",
  principalInvestigator: "PI NAME",
  leadUnit: "MEDICINE",
  awardEffectiveDate: null,
  updateTimestamp: "2023-11-30T14:33:35",
  primaryCurrent: false,
};

test("version search: both current and historical rows are returned side by side", () => {
  const response = {
    content: [CURRENT_HIT, HISTORICAL_HIT],
    page: 0,
    size: 25,
    totalElements: 2,
    totalPages: 1,
    first: true,
    last: true,
  };

  const described = describeVersionSearchResults(response);

  assert.equal(described.totalElements, 2);
  assert.deepEqual(described.content, [CURRENT_HIT, HISTORICAL_HIT]);
});

test("pagination does not lose versions sharing an Award/document number - content is a flat page, never deduped by award_number", () => {
  const sameFamilyPage = {
    content: [CURRENT_HIT, HISTORICAL_HIT],
    totalElements: 545,
    totalPages: 22,
  };

  const described = describeVersionSearchResults(sameFamilyPage);

  assert.equal(described.content.length, 2);
  assert.equal(described.content[0].awardNumber, described.content[1].awardNumber);
  assert.notEqual(described.content[0].awardId, described.content[1].awardId);
  assert.equal(described.totalElements, 545);
  assert.equal(described.totalPages, 22);
});

test("missing/optional fields never throw - null and undefined response", () => {
  assert.deepEqual(describeVersionSearchResults(null), {
    totalElements: 0,
    totalPages: 0,
    content: [],
  });
  assert.deepEqual(describeVersionSearchResults(undefined), {
    totalElements: 0,
    totalPages: 0,
    content: [],
  });
});

test("selecting an old award_id opens that exact version - the detail path is always keyed by award_id, never award_number alone", () => {
  assert.equal(versionDetailPath(HISTORICAL_HIT), "/awards/3561589");
  assert.equal(versionDetailPath(CURRENT_HIT), "/awards/3561610");
  assert.notEqual(versionDetailPath(HISTORICAL_HIT), versionDetailPath(CURRENT_HIT));
});

test("current-version and historical-version labels are correct", () => {
  assert.equal(versionCurrentLabel(CURRENT_HIT), "Current");
  assert.equal(versionCurrentLabel(HISTORICAL_HIT), "Historical");
});

test("Award ID validation: real CARB-X ids and other whole numbers are valid", () => {
  assert.equal(isValidAwardIdInput("3561589"), true);
  assert.equal(isValidAwardIdInput("3561610"), true);
  assert.equal(isValidAwardIdInput("1"), true);
  assert.equal(isValidAwardIdInput("999999999"), true);
});

test("Award ID validation: blank or whitespace-only counts as valid (no filter), not an error", () => {
  assert.equal(isValidAwardIdInput(""), true);
  assert.equal(isValidAwardIdInput("   "), true);
  assert.equal(isValidAwardIdInput(null), true);
  assert.equal(isValidAwardIdInput(undefined), true);
});

test("Award ID validation: non-numeric or partial-looking input is invalid, never a silent substring search", () => {
  assert.equal(isValidAwardIdInput("abc"), false);
  assert.equal(isValidAwardIdInput("356*"), false);
  assert.equal(isValidAwardIdInput("35615.89"), false);
  assert.equal(isValidAwardIdInput("-3561589"), false);
  assert.equal(isValidAwardIdInput("3561589 "), true, "trims surrounding whitespace before validating");
});
