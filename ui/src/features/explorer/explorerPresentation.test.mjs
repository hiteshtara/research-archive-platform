import assert from "node:assert/strict";
import test from "node:test";

import {
  RESOURCE_DEFINITIONS,
  resourceDefinition,
  toCsv,
  buildCrossLinks,
} from "./explorerPresentation.mjs";

test("RESOURCE_DEFINITIONS: exactly the 10 Explorer resources, each with a usable identifier", () => {
  assert.equal(RESOURCE_DEFINITIONS.length, 10);
  for (const entry of RESOURCE_DEFINITIONS) {
    assert.equal(typeof entry.key, "string");
    assert.equal(typeof entry.label, "string");
    assert.equal(typeof entry.identifierField, "string");
    assert.equal(typeof entry.identifierLabel, "string");
    assert.ok(["string", "number"].includes(entry.identifierKind));
    assert.equal(typeof entry.isList, "boolean");
  }
});

test("resourceDefinition: looks up a known resource, null for unknown", () => {
  assert.equal(resourceDefinition("award")?.identifierField, "awardNumber");
  assert.equal(resourceDefinition("unit-administrators")?.isList, true);
  assert.equal(resourceDefinition("not-a-resource"), null);
});

test("toCsv: empty/missing input never throws", () => {
  assert.equal(toCsv([]), "");
  assert.equal(toCsv(null), "");
  assert.equal(toCsv(undefined), "");
});

test("toCsv: header is the union of every row's keys, first-seen order", () => {
  const csv = toCsv([
    { a: 1, b: 2 },
    { b: 3, c: 4 },
  ]);
  const lines = csv.split("\n");
  assert.equal(lines[0], "a,b,c");
  assert.equal(lines[1], "1,2,");
  assert.equal(lines[2], ",3,4");
});

test("toCsv: quotes values containing commas, quotes, or newlines", () => {
  const csv = toCsv([
    { name: "Smith, Jane", note: 'Said "hi"', text: "line1\nline2" },
  ]);
  assert.equal(
    csv,
    'name,note,text\n"Smith, Jane","Said ""hi""","line1\nline2"',
  );
});

test("toCsv: null/undefined cells render as empty, not the literal string", () => {
  const csv = toCsv([{ a: null, b: undefined, c: 0 }]);
  const lines = csv.split("\n");
  assert.equal(lines[1], ",,0");
});

test("buildCrossLinks: award links to its workflow, unit, contacts, and attachments", () => {
  const award = {
    awardId: 985585,
    awardNumber: "100012-00002",
    workflowDocumentNumber: "300940",
    leadUnitNumber: "1203250000",
    leadUnitName: "CAS SPACE PHYSICS",
  };

  const links = buildCrossLinks("award", award);

  assert.deepEqual(
    links.find((link) => link.resource === "award-contacts"),
    { label: "Award Contacts", resource: "award-contacts", identifier: "985585" },
  );
  assert.deepEqual(
    links.find((link) => link.resource === "attachments"),
    { label: "Attachments", resource: "attachments", identifier: "985585" },
  );
  assert.deepEqual(
    links.find((link) => link.resource === "workflow"),
    { label: "Workflow 300940", resource: "workflow", identifier: "300940" },
  );
  assert.deepEqual(
    links.find((link) => link.resource === "unit"),
    {
      label: "Unit 1203250000 (CAS SPACE PHYSICS)",
      resource: "unit",
      identifier: "1203250000",
    },
  );
});

test("buildCrossLinks: award-version reuses the same award-shaped links", () => {
  const links = buildCrossLinks("award-version", {
    awardId: 1,
    workflowDocumentNumber: "1",
    leadUnitNumber: null,
  });
  assert.ok(links.some((link) => link.resource === "award-contacts"));
  assert.ok(!links.some((link) => link.resource === "unit"));
});

test("buildCrossLinks: unit links to its administrators list and each administrator's Person", () => {
  const unit = {
    unitNumber: "1203250000",
    administrators: [
      { personId: "U98756203", fullName: "ANTHONY J MOY" },
      { personId: null, fullName: "No ID Row" },
    ],
  };

  const links = buildCrossLinks("unit", unit);

  assert.deepEqual(links[0], {
    label: "Unit Administrators",
    resource: "unit-administrators",
    identifier: "1203250000",
  });
  assert.deepEqual(links[1], {
    label: "Person: ANTHONY J MOY",
    resource: "person",
    identifier: "U98756203",
  });
  assert.equal(links.length, 2);
});

test("buildCrossLinks: unit-administrators (a bare list) links each row to its Person", () => {
  const links = buildCrossLinks("unit-administrators", [
    { personId: "U1", fullName: "A" },
    { personId: "U2", fullName: "B" },
  ]);
  assert.equal(links.length, 2);
  assert.equal(links[0].identifier, "U1");
  assert.equal(links[1].identifier, "U2");
});

test("buildCrossLinks: award-contacts links its award, unit, and every contact's Person", () => {
  const data = {
    award: { awardId: 5, awardNumber: "A-5" },
    unitDetails: { unitNumber: "1200000000" },
    keyPersonnel: [{ personId: "P1", fullName: "PI Person" }],
    unitContacts: [{ personId: "P2", fullName: "Unit Contact" }],
    centralAdministrationContacts: [{ personId: "P3", fullName: "OSP Admin" }],
    sponsorContacts: [],
  };

  const links = buildCrossLinks("award-contacts", data);

  assert.ok(links.some((link) => link.resource === "award-contacts" && link.identifier === "5"));
  assert.ok(links.some((link) => link.resource === "unit" && link.identifier === "1200000000"));
  assert.deepEqual(
    links.filter((link) => link.resource === "person").map((link) => link.identifier),
    ["P1", "P2", "P3"],
  );
});

test("buildCrossLinks: sponsor (a list of awards) prefixes each link with its own award number", () => {
  const links = buildCrossLinks("sponsor", [
    { awardId: 1, awardNumber: "A-1", workflowDocumentNumber: "10" },
    { awardId: 2, awardNumber: "A-2", workflowDocumentNumber: null },
  ]);

  assert.ok(links.some((link) => link.label === "A-1: Award Contacts"));
  assert.ok(links.some((link) => link.label === "A-1: Workflow 10"));
  assert.ok(links.some((link) => link.label === "A-2: Award Contacts"));
  assert.ok(!links.some((link) => link.label.startsWith("A-2: Workflow")));
});

test("buildCrossLinks: attachments (a list) links to the owning Award, deduplicated", () => {
  const links = buildCrossLinks("attachments", [
    { awardNumber: "100068-00001" },
    { awardNumber: "100068-00001" },
  ]);
  assert.deepEqual(links, [
    { label: "Award 100068-00001", resource: "award", identifier: "100068-00001" },
  ]);
});

test("buildCrossLinks: person, rolodex, workflow, and missing data never produce links", () => {
  assert.deepEqual(buildCrossLinks("person", { personId: "P1" }), []);
  assert.deepEqual(buildCrossLinks("rolodex", { rolodexId: 1 }), []);
  assert.deepEqual(buildCrossLinks("workflow", { awardId: 1 }), []);
  assert.deepEqual(buildCrossLinks("award", null), []);
  assert.deepEqual(buildCrossLinks("award", undefined), []);
});
