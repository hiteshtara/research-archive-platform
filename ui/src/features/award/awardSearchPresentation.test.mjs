import assert from "node:assert/strict";
import test from "node:test";

import { describeSearchResults } from "./awardSearchPresentation.mjs";

test("ordinary Award search: no exact document match, results present", () => {
  const response = {
    exactDocumentMatch: null,
    results: {
      content: [{ awardId: 3, awardNumber: "100004-00003" }],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    },
  };

  const described = describeSearchResults(response);

  assert.equal(described.totalElements, 1);
  assert.equal(described.totalPages, 1);
  assert.deepEqual(described.content, [
    { awardId: 3, awardNumber: "100004-00003" },
  ]);
  assert.equal(described.exactDocumentMatch, null);
});

test("exact workflow-document match: surfaced alongside ordinary results", () => {
  const response = {
    exactDocumentMatch: {
      awardId: 1135067,
      awardNumber: "100567-00001",
      sequenceNumber: 6,
      workflowDocumentNumber: "328797",
      documentType: "Award",
      title: "Title",
      status: "Approved Award",
    },
    results: {
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    },
  };

  const described = describeSearchResults(response);

  assert.equal(described.exactDocumentMatch.awardId, 1135067);
  assert.equal(described.exactDocumentMatch.sequenceNumber, 6);
  assert.equal(described.exactDocumentMatch.workflowDocumentNumber, "328797");
  assert.equal(described.totalElements, 0);
  assert.deepEqual(described.content, []);
});

test("no results: empty content and zero totals, no crash", () => {
  const response = {
    exactDocumentMatch: null,
    results: {
      content: [],
      page: 0,
      size: 25,
      totalElements: 0,
      totalPages: 0,
      first: true,
      last: true,
    },
  };

  const described = describeSearchResults(response);

  assert.equal(described.totalElements, 0);
  assert.equal(described.totalPages, 0);
  assert.deepEqual(described.content, []);
  assert.equal(described.exactDocumentMatch, null);
});

test("missing/optional fields never throw - old flat-shape response falls back safely", () => {
  // Simulates exactly the regression this guards against: an old UI
  // build's expected flat PageResponse shape (top-level totalElements/
  // content) rather than the current {exactDocumentMatch, results}
  // wrapper - `results` is undefined here, same as it would be if the
  // response shape ever regresses or is served stale during a rollout.
  const staleShapeResponse = {
    content: [{ awardId: 1 }],
    totalElements: 1,
  };

  const described = describeSearchResults(staleShapeResponse);

  assert.equal(described.totalElements, 0);
  assert.equal(described.totalPages, 0);
  assert.deepEqual(described.content, []);
  assert.equal(described.exactDocumentMatch, null);
});

test("missing/optional fields never throw - null and undefined response", () => {
  assert.deepEqual(describeSearchResults(null), {
    totalElements: 0,
    totalPages: 0,
    content: [],
    exactDocumentMatch: null,
  });
  assert.deepEqual(describeSearchResults(undefined), {
    totalElements: 0,
    totalPages: 0,
    content: [],
    exactDocumentMatch: null,
  });
});

test("missing/optional fields never throw - results present but content/totals missing", () => {
  const described = describeSearchResults({
    exactDocumentMatch: null,
    results: {},
  });

  assert.equal(described.totalElements, 0);
  assert.equal(described.totalPages, 0);
  assert.deepEqual(described.content, []);
});
