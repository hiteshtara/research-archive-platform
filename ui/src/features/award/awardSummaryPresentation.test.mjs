import assert from "node:assert/strict";
import { test } from "node:test";

import {
  EMPTY,
  RETIRED_SUMMARY_LABELS,
  buildAwardSummaryGroups,
  displayValue,
} from "./awardSummaryPresentation.mjs";

// Reconciliation fixture: the real archived values for Award
// 105698-00001 (latest sequence, award_id 2727052), verified against
// both live Oracle staging and dev RDS. These are EXPECTATIONS, not
// hardcoded production values - the code must never special-case them.
const FIXTURE = {
  awardNumber: "105698-00001",
  sequenceNumber: 20,
  status: "Closed",
  title: "IMPACTS OF PARENTING ADOLESCENTS AND ADULTS WITH AUTISM",
  leadUnit: "SAR OCCUPATIONAL THERAPY",
  grantNumber: null,
  accountType: "Federal",
  activityType: "Research",
  awardType: "Sub-award - Grant",
  federalClinicalTrial: null,
  sponsor: "University of Wisconsin System",
  sponsorCode: "302379",
  sponsorAwardNumber: "X329792",
  primeSponsor: "NIH/National Institute on Aging",
  primeSponsorCode: "301045",
  primeSponsorAwardId: "R01AG008768",
  modificationNumber: "7",
  fainId: "unknown",
  nsfScienceCode: null,
  alnNumber: "93.866",
  alnProgramTitleName: null,
  awardEffectiveDate: "2007-04-01",
  obligationStartDate: "2007-04-01",
  beginDate: null,
  closeoutDate: null,
};

function flatten(summary) {
  return buildAwardSummaryGroups(summary).flatMap((group) =>
    group.fields.map((field) => ({ group: group.title, ...field })),
  );
}

function find(summary, label) {
  return flatten(summary).find((field) => field.label === label);
}

test("uses the exact Kuali label Project Start Date, sourced from awardEffectiveDate", () => {
  const field = find(FIXTURE, "Project Start Date");
  assert.ok(field, "Project Start Date card must exist");
  assert.equal(field.value, "2007-04-01");
  assert.equal(field.group, "Project / Dates");
});

test("Project Start Date does NOT fall back to beginDate", () => {
  // begin_date is NULL for this award in the real archive; if the card
  // were wired to beginDate it would render an em dash instead.
  const field = find({ ...FIXTURE, awardEffectiveDate: null }, "Project Start Date");
  assert.equal(field.value, EMPTY);
  const stillNull = find(
    { ...FIXTURE, awardEffectiveDate: null, beginDate: "1999-01-01" },
    "Project Start Date",
  );
  assert.equal(
    stillNull.value,
    EMPTY,
    "beginDate must never be used to populate Project Start Date",
  );
});

test("uses the exact Kuali label Obligation Start Date", () => {
  const field = find(FIXTURE, "Obligation Start Date");
  assert.ok(field);
  assert.equal(field.value, "2007-04-01");
});

test("Obligation Start Date is not sourced from closeoutDate", () => {
  const field = find(
    { ...FIXTURE, obligationStartDate: null, closeoutDate: "2011-06-30" },
    "Obligation Start Date",
  );
  assert.equal(field.value, EMPTY);
});

test("retired labels never appear on Summary", () => {
  const labels = flatten(FIXTURE).map((field) => field.label);
  for (const retired of RETIRED_SUMMARY_LABELS) {
    assert.ok(
      !labels.includes(retired),
      `retired label ${retired} must not appear on Summary`,
    );
  }
});

test("no Federal Award Year card exists", () => {
  const labels = flatten(FIXTURE).map((field) => field.label);
  assert.ok(!labels.some((label) => /federal award year/i.test(label)));
});

test("FAIN ID passes the literal string 'unknown' through verbatim", () => {
  const field = find(FIXTURE, "FAIN ID");
  assert.equal(field.value, "unknown");
  assert.notEqual(field.value, EMPTY);
});

test("FAIN ID renders an em dash only when genuinely absent", () => {
  assert.equal(find({ ...FIXTURE, fainId: null }, "FAIN ID").value, EMPTY);
  assert.equal(find({ ...FIXTURE, fainId: "  " }, "FAIN ID").value, EMPTY);
});

test("ALN number and title stay in one group, title degrades to an em dash", () => {
  const groups = buildAwardSummaryGroups(FIXTURE);
  const aln = groups.find((group) => group.title === "ALN");
  assert.ok(aln, "ALN group must exist");
  assert.deepEqual(
    aln.fields.map((field) => field.label),
    ["ALN Number", "ALN Program Title Name"],
  );
  assert.equal(aln.fields[0].value, "93.866");
  assert.equal(
    aln.fields[1].value,
    EMPTY,
    "a missing ALN title is a source property, not an error",
  );
});

test("ALN title is shown when the source has one", () => {
  const field = find(
    { ...FIXTURE, alnProgramTitleName: "Aging Research" },
    "ALN Program Title Name",
  );
  assert.equal(field.value, "Aging Research");
});

test("Sponsor name is primary with the Sponsor ID as secondary metadata", () => {
  const field = find(FIXTURE, "Sponsor Name");
  assert.equal(field.value, "University of Wisconsin System");
  assert.equal(field.caption, "Sponsor ID: 302379");
});

test("Prime Sponsor name is primary with the Prime Sponsor ID secondary", () => {
  const field = find(FIXTURE, "Prime Sponsor Name");
  assert.equal(field.value, "NIH/National Institute on Aging");
  assert.equal(field.caption, "Prime Sponsor ID: 301045");
});

test("sponsor award identifiers use exact Kuali labels", () => {
  assert.equal(find(FIXTURE, "Sponsor Award ID").value, "X329792");
  assert.equal(find(FIXTURE, "Prime Sponsor Award ID").value, "R01AG008768");
  assert.equal(find(FIXTURE, "Modification ID").value, "7");
});

test("NSF Science Code uses the exact Kuali label and the resolved code", () => {
  assert.equal(find(FIXTURE, "NSF Science Code").value, EMPTY);
  assert.equal(
    find({ ...FIXTURE, nsfScienceCode: "J8" }, "NSF Science Code").value,
    "J8",
  );
});

test("Institution group carries the Kuali institution fields", () => {
  const groups = buildAwardSummaryGroups(FIXTURE);
  const institution = groups.find((group) => group.title === "Institution");
  assert.deepEqual(institution.fields.map((field) => field.label), [
    "Award ID",
    "Version",
    "Award Status",
    "Grant Number",
    "Award Title",
    "Lead Unit",
    "Account Type",
    "Activity Type",
    "Award Type",
    "Federal Clinical Trial",
  ]);
  assert.equal(find(FIXTURE, "Account Type").value, "Federal");
  assert.equal(find(FIXTURE, "Activity Type").value, "Research");
  assert.equal(find(FIXTURE, "Award Type").value, "Sub-award - Grant");
});

test("displayValue never coerces meaningful falsy-looking values", () => {
  assert.equal(displayValue(0), "0");
  assert.equal(displayValue("unknown"), "unknown");
  assert.equal(displayValue(null), EMPTY);
  assert.equal(displayValue(undefined), EMPTY);
});

test("an absent summary yields no groups", () => {
  assert.deepEqual(buildAwardSummaryGroups(null), []);
});
