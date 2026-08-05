import assert from "node:assert/strict";
import test from "node:test";

import {
  groupCustomData,
  matchesCustomDataQuery,
  resolveCustomDataLabel,
} from "./proposalCustomDataPresentation.mjs";

test("resolveCustomDataLabel prefers the resolved label", () => {
  const row = {
    customAttributeId: 480,
    label: "Submitted Date",
    name: "ip_submission_date",
    value: "08/09/2011",
  };

  assert.equal(resolveCustomDataLabel(row), "Submitted Date");
});

test("resolveCustomDataLabel falls back to name when label is missing", () => {
  const row = {
    customAttributeId: 1214,
    label: null,
    name: "OppNum",
    value: null,
  };

  assert.equal(resolveCustomDataLabel(row), "OppNum");
});

test("resolveCustomDataLabel never renders only the bare custom-attribute ID", () => {
  const row = {
    customAttributeId: 424242,
    label: null,
    name: null,
    value: "some value",
  };

  const label = resolveCustomDataLabel(row);

  assert.notEqual(label, "424242");
  assert.match(label, /424242/);
  assert.ok(label.length > String(424242).length);
});

test("groupCustomData groups by the proven groupName, preserving row order", () => {
  const rows = [
    { customAttributeId: 1, groupName: "Sponsor Info", value: "a" },
    { customAttributeId: 2, groupName: "Sponsor Info", value: "b" },
    { customAttributeId: 3, groupName: "Compliance", value: "c" },
  ];

  const grouped = groupCustomData(rows);

  assert.deepEqual(
    grouped.map((group) => group.groupName),
    ["Sponsor Info", "Compliance"],
  );
  assert.equal(grouped[0].rows.length, 2);
  assert.equal(grouped[0].rows[0].customAttributeId, 1);
});

test("groupCustomData collapses rows with no groupName into one trailing 'Other' group", () => {
  const rows = [
    { customAttributeId: 1, groupName: null, value: "a" },
    { customAttributeId: 2, groupName: "Sponsor Info", value: "b" },
    { customAttributeId: 3, groupName: null, value: "c" },
  ];

  const grouped = groupCustomData(rows);

  assert.deepEqual(
    grouped.map((group) => group.groupName),
    ["Sponsor Info", "Other"],
  );
  assert.equal(grouped[1].rows.length, 2);
});

test("groupCustomData handles a large, ungrouped custom-data set without dropping rows", () => {
  // Mirrors fixture 01157400: 161 rows, no groupName metadata proven
  // for this document type.
  const rows = Array.from({ length: 161 }, (_, index) => ({
    customAttributeId: index,
    groupName: null,
    value: `value ${index}`,
  }));

  const grouped = groupCustomData(rows);

  assert.equal(grouped.length, 1);
  assert.equal(grouped[0].rows.length, 161);
});

test("matchesCustomDataQuery matches against the resolved label", () => {
  const row = {
    customAttributeId: 480,
    label: "Submitted Date",
    name: "ip_submission_date",
    value: "08/09/2011",
  };

  assert.equal(matchesCustomDataQuery(row, "submitted"), true);
  assert.equal(matchesCustomDataQuery(row, "unrelated"), false);
});

test("matchesCustomDataQuery matches against the value", () => {
  const row = {
    customAttributeId: 480,
    label: "Submitted Date",
    name: "ip_submission_date",
    value: "08/09/2011",
  };

  assert.equal(matchesCustomDataQuery(row, "08/09/2011"), true);
});

test("matchesCustomDataQuery treats a blank query as matching everything", () => {
  const row = {
    customAttributeId: 480,
    label: null,
    name: null,
    value: null,
  };

  assert.equal(matchesCustomDataQuery(row, ""), true);
  assert.equal(matchesCustomDataQuery(row, "   "), true);
});

test("matchesCustomDataQuery does not blow up on a real persisted blank value", () => {
  // Real fixture: proposal_custom_data_id 1495997 (attribute 1209,
  // "Opportunity Title") has a genuinely null value.
  const row = {
    customAttributeId: 1209,
    label: "Opportunity Title",
    name: "OppTitle",
    value: null,
  };

  assert.equal(matchesCustomDataQuery(row, "opportunity"), true);
  assert.equal(matchesCustomDataQuery(row, "nonsense"), false);
});
