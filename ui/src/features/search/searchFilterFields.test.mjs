import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  AWARD_DATE_RANGES,
  AWARD_FILTER_FIELDS,
  AWARD_VERSION_FILTER_FIELDS,
  GLOBAL_SEARCH_FILTER_FIELDS,
  PROPOSAL_FILTER_FIELDS,
  SUBAWARD_DATE_RANGES,
  SUBAWARD_FILTER_FIELDS,
} from "./searchFilterFields.mjs";
import {
  NEGOTIATION_DATE_RANGES,
  NEGOTIATION_FILTER_FIELDS,
} from "../negotiation/negotiationSearchPresentation.mjs";
import {
  ARCHIVED_FILE_FILTER_FIELDS,
  archivedFileDisplayFields,
  hiddenIdentifierFields,
} from "../archivedFiles/archivedFileFinderPresentation.mjs";
import { buildFilterRequestParams, emptyFilters } from "../common/filterPresentation.mjs";

const keys = (fields) => fields.map((field) => field.key);

// Keys are the API's own @RequestParam names (see the Java controllers and
// docs/architecture/SEARCH_FILTER_MATRIX.md) - a typo here would silently
// send a parameter the server ignores.
test("each module's filter keys are exactly its API parameter names", () => {
  assert.deepEqual(keys(AWARD_FILTER_FIELDS), [
    "status", "sponsor", "principalInvestigator", "leadUnit",
    "projectStartDateFrom", "projectStartDateTo",
  ]);
  assert.deepEqual(keys(AWARD_VERSION_FILTER_FIELDS), [
    "awardNumber", "documentNumber", "awardId", "versionFilter",
    "status", "sponsor", "principalInvestigator", "leadUnit",
    "projectStartDateFrom", "projectStartDateTo",
  ]);
  assert.deepEqual(keys(PROPOSAL_FILTER_FIELDS), ["sponsor", "principalInvestigator", "leadUnit"]);
  assert.deepEqual(keys(SUBAWARD_FILTER_FIELDS), [
    "status", "sponsor", "organizationId",
    "startDateFrom", "startDateTo", "endDateFrom", "endDateTo",
  ]);
  assert.deepEqual(keys(GLOBAL_SEARCH_FILTER_FIELDS), ["modules"]);
});

test("the API parameter names really exist on the controllers", () => {
  const api = (path) => readFileSync(new URL(`../../../../api/src/main/java/edu/bu/archive/${path}`, import.meta.url), "utf8");
  const award = api("adapter/in/web/AwardV1Controller.java");
  for (const key of keys(AWARD_VERSION_FILTER_FIELDS)) {
    assert.match(award, new RegExp(`String ${key}|LocalDate ${key}`), `Award: ${key}`);
  }
  const subaward = api("adapter/in/web/SubawardArchiveController.java");
  for (const key of keys(SUBAWARD_FILTER_FIELDS)) {
    assert.match(subaward, new RegExp(`(String|LocalDate) ${key}\\b`), `Subaward: ${key}`);
  }
  const proposal = api("adapter/in/web/ProposalArchiveController.java");
  for (const key of keys(PROPOSAL_FILTER_FIELDS)) {
    assert.match(proposal, new RegExp(`String ${key}\\b`), `Proposal: ${key}`);
  }
  assert.match(api("adapter/in/web/GlobalSearchController.java"), /List<String> modules/);
});

test("every date range refers to two date fields of the same module", () => {
  const cases = [
    [AWARD_FILTER_FIELDS, AWARD_DATE_RANGES],
    [SUBAWARD_FILTER_FIELDS, SUBAWARD_DATE_RANGES],
    [NEGOTIATION_FILTER_FIELDS, NEGOTIATION_DATE_RANGES],
  ];
  for (const [fields, ranges] of cases) {
    for (const range of ranges) {
      for (const key of [range.from, range.to]) {
        const field = fields.find((candidate) => candidate.key === key);
        assert.equal(field?.type, "date", key);
      }
    }
    // and every date field belongs to a range, so it is validated
    const dateKeys = fields.filter((field) => field.type === "date").map((field) => field.key);
    const covered = ranges.flatMap((range) => [range.from, range.to]);
    assert.deepEqual([...dateKeys].sort(), [...covered].sort());
  }
});

test("every date field states that its bound is inclusive", () => {
  for (const fields of [AWARD_FILTER_FIELDS, SUBAWARD_FILTER_FIELDS, NEGOTIATION_FILTER_FIELDS]) {
    for (const field of fields.filter((candidate) => candidate.type === "date")) {
      assert.equal(field.helperText, "Inclusive", field.key);
    }
  }
});

test("labels are full words, never placeholder-only or truncated", () => {
  const all = [
    ...AWARD_VERSION_FILTER_FIELDS,
    ...PROPOSAL_FILTER_FIELDS,
    ...SUBAWARD_FILTER_FIELDS,
    ...GLOBAL_SEARCH_FILTER_FIELDS,
    ...NEGOTIATION_FILTER_FIELDS,
    ...ARCHIVED_FILE_FILTER_FIELDS,
  ];
  for (const field of all) {
    assert.ok(field.label && field.label.length >= 3, field.key);
    assert.doesNotMatch(field.label, /…|\.\.\./, field.key);
  }
});

test("Negotiation association filters are preserved and marked exact", () => {
  const byKey = Object.fromEntries(NEGOTIATION_FILTER_FIELDS.map((field) => [field.key, field]));
  assert.equal(byKey.associationType.label, "Negotiation Association Type");
  assert.equal(byKey.associationId.label, "Negotiation Association ID");
  assert.match(byKey.associationType.helperText, /Exact/);
  assert.match(byKey.associationId.helperText, /Exact/);
});

test("Global Search offers only Record Type, defaulting to every module", () => {
  const [field] = GLOBAL_SEARCH_FILTER_FIELDS;
  assert.equal(field.defaultValue, "ALL");
  assert.deepEqual(
    field.options.map((option) => option.value),
    ["ALL", "AWARD", "PROPOSAL", "NEGOTIATION", "SUBAWARD"],
  );
  assert.deepEqual(
    buildFilterRequestParams({ query: "x", filters: emptyFilters(GLOBAL_SEARCH_FILTER_FIELDS), fields: GLOBAL_SEARCH_FILTER_FIELDS }),
    { page: 0, size: 25, query: "x" },
  );
});

test("Subaward FRN search stays in the free-text box, not a separate filter", () => {
  assert.equal(SUBAWARD_FILTER_FIELDS.some((field) => /frn|purchase/i.test(field.key)), false);
  const page = readFileSync(new URL("../../pages/SubawardFamiliesPage.tsx", import.meta.url), "utf8");
  assert.match(page, /"FRN"/);
});

test("Archived File Finder shows only the fields that apply to the record type", () => {
  assert.deepEqual(keys(archivedFileDisplayFields("ALL")), [
    "recordType", "recordNumber", "documentNumber", "versionFilter",
  ]);
  assert.deepEqual(keys(archivedFileDisplayFields("AWARD")), [
    "recordType", "recordNumber", "documentNumber", "recordId", "attachmentId", "fileId", "versionFilter",
  ]);
  assert.deepEqual(keys(archivedFileDisplayFields("NEGOTIATION")), [
    "recordType", "documentNumber", "recordId", "attachmentId",
  ]);
  const award = Object.fromEntries(archivedFileDisplayFields("AWARD").map((field) => [field.key, field.label]));
  assert.equal(award.recordNumber, "Award Number");
  assert.equal(award.recordId, "Award ID");
  const negotiation = Object.fromEntries(archivedFileDisplayFields("NEGOTIATION").map((field) => [field.key, field.label]));
  assert.equal(negotiation.recordId, "Negotiation ID");
});

test("identifiers hidden for a record type are the ones dropped from the draft", () => {
  assert.deepEqual(hiddenIdentifierFields("ALL"), ["recordId", "attachmentId", "fileId"]);
  assert.deepEqual(hiddenIdentifierFields("PROPOSAL"), ["fileId"]);
  assert.deepEqual(hiddenIdentifierFields("NEGOTIATION"), ["recordNumber", "fileId"]);
  assert.deepEqual(hiddenIdentifierFields("AWARD"), []);
});

test("Historical Awards never sends an invalid Award ID that arrived via the URL", () => {
  const source = readFileSync(
    new URL("../../pages/award/AwardVersionSearchPage.tsx", import.meta.url),
    "utf8",
  );
  assert.match(source, /const appliedAwardIdError = validateAwardId\(search\.appliedFilters\)\.awardId;/);
  assert.match(source, /!appliedAwardIdError &&/);
});

test("every search page renders the one shared filtered search bar", () => {
  const pages = [
    "award/AwardSearchPage.tsx",
    "award/AwardVersionSearchPage.tsx",
    "ProposalFamiliesPage.tsx",
    "NegotiationFamiliesPage.tsx",
    "SubawardFamiliesPage.tsx",
    "GlobalSearchPage.tsx",
    "ArchivedFileFinderPage.tsx",
  ];
  for (const page of pages) {
    const source = readFileSync(new URL(`../../pages/${page}`, import.meta.url), "utf8");
    assert.match(source, /<FilteredSearchBar/, page);
    assert.match(source, /useFilteredSearch/, page);
    assert.doesNotMatch(source, /<FilterPanel/, `${page} must not render its own panel`);
  }
});
