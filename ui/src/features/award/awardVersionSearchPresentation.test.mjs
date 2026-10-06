import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  describeVersionSearchResults,
  isValidAwardIdInput,
  startsVersionSearch,
  versionCurrentLabel,
  versionDetailPath,
} from "./awardVersionSearchPresentation.mjs";
import { activeFilters } from "../common/filterPresentation.mjs";
import { AWARD_VERSION_FILTER_FIELDS } from "../search/searchFilterFields.mjs";

function readAwardVersionSearchPageSource() {
  const pagePath = fileURLToPath(
    new URL("../../pages/award/AwardVersionSearchPage.tsx", import.meta.url),
  );
  return readFileSync(pagePath, "utf8");
}

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

// No component-render harness exists in this project (see CLAUDE.md),
// so this proves the page copy/link genuinely exist in source rather
// than merely trusting a mock - same static-source-inspection approach
// dashboardPresentation.test.mjs uses for App.tsx's router config.
test("Historical Award Records heading/helper text says results are individual Award versions", () => {
  const source = readAwardVersionSearchPageSource();

  assert.match(
    source,
    /Historical Award Records/,
    "the page heading must still read Historical Award Records",
  );
  assert.match(
    source,
    /individual archived Award version/i,
    "the helper text must say results represent individual Award versions",
  );
});

test("Historical Award Records still exposes the dedicated exact Award ID field", async () => {
  // The field now lives in the shared filter definitions the page renders,
  // rather than as an inline TextField.
  const { AWARD_VERSION_FILTER_FIELDS, AWARD_FILTER_FIELDS } = await import(
    "../search/searchFilterFields.mjs"
  );
  const source = readAwardVersionSearchPageSource();

  const awardId = AWARD_VERSION_FILTER_FIELDS.find((field) => field.key === "awardId");
  assert.equal(awardId?.label, "Award ID (exact)");
  assert.match(source, /fields=\{AWARD_VERSION_FILTER_FIELDS\}/);
  // ...and the current-Award search never gains one.
  assert.equal(
    AWARD_FILTER_FIELDS.some((field) => field.key === "awardId"),
    false,
  );
});

test("the Historical Awards page's helper link goes back to the current Award-family search", () => {
  const source = readAwardVersionSearchPageSource();

  assert.match(
    source,
    /component=\{RouterLink\}\s*\n?\s*to="\/awards\/search"/,
    'the back-link must navigate to "/awards/search"',
  );
});

/*
 * Historical Awards: the Versions filter narrows a search but does not
 * start one, and an untouched default must stay distinguishable from an
 * explicit selection. Raised in review of the TC-015 hint, because the
 * hint tells the reader exactly this and would be wrong if the
 * underlying states were conflated.
 *
 * activeFilters() is the thing that separates them - it omits a value
 * equal to its field's default - so these tests use it rather than
 * hand-built filter objects, or they would be testing a fiction.
 */
test("the Versions filter alone does not start a search", () => {
  const applied = activeFilters(
    { versionFilter: "historical" },
    AWARD_VERSION_FILTER_FIELDS,
  );
  // Present, because it was explicitly chosen...
  assert.deepEqual(applied, { versionFilter: "historical" });
  // ...and still not a search, because "historical" alone means every
  // archived version in the archive.
  assert.equal(startsVersionSearch({ appliedActiveFilters: applied }), false);
});

test("an untouched Versions default is not an applied filter at all", () => {
  const applied = activeFilters(
    { versionFilter: "all" },
    AWARD_VERSION_FILTER_FIELDS,
  );
  assert.deepEqual(applied, {});
  assert.equal(startsVersionSearch({ appliedActiveFilters: applied }), false);
});

test("changing Versions after a real search keeps the search and applies the selection", () => {
  // The review case. Text was entered, then Versions was changed: the
  // search must still be a search, and the selection must reach the
  // request - which it does by being present in appliedActiveFilters,
  // the value the page both sends and keys its query on.
  const applied = activeFilters(
    { versionFilter: "historical" },
    AWARD_VERSION_FILTER_FIELDS,
  );

  assert.equal(
    startsVersionSearch({ appliedQuery: "smith", appliedActiveFilters: applied }),
    true,
  );
  assert.equal(applied.versionFilter, "historical");
});

test("another filter plus a Versions selection is a search", () => {
  const applied = activeFilters(
    { sponsor: "NIH", versionFilter: "current" },
    AWARD_VERSION_FILTER_FIELDS,
  );
  assert.equal(startsVersionSearch({ appliedActiveFilters: applied }), true);
  assert.equal(applied.versionFilter, "current");
});

test("explicitly choosing All versions collapses to the default", () => {
  // Correct rather than lossy: it asks for the same rows the default
  // asks for, so dropping it changes nothing that reaches the API.
  const applied = activeFilters(
    { sponsor: "NIH", versionFilter: "all" },
    AWARD_VERSION_FILTER_FIELDS,
  );
  assert.deepEqual(applied, { sponsor: "NIH" });
  assert.equal(startsVersionSearch({ appliedActiveFilters: applied }), true);
});

test("an invalid Award ID blocks the search whatever else is applied", () => {
  // The page shows the reason instead, and the initial hint stays out
  // of the way so the error reads alone.
  assert.equal(
    startsVersionSearch({
      appliedQuery: "smith",
      appliedActiveFilters: { sponsor: "NIH" },
      awardIdError: "Award ID must be a whole number.",
    }),
    false,
  );
});
