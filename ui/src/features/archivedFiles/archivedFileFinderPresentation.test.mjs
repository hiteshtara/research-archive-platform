import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  archivedFileResultKey,
  archivedFileResultsCountLabel,
  archivedFileSearchErrorMessage,
  dispatchArchivedFileDownload,
  formatSourceDateLabel,
  hasAnyIdentifierSupplied,
  parseRecordTypeParam,
  parseVersionFilterParam,
  RECORD_TYPE_OPTIONS,
  recordIdFieldLabel,
  recordNumberFieldLabel,
  recordTypeLabel,
  resolveAvailabilityChipColor,
  resolveRecordViewPath,
  visibleFieldsForRecordType,
} from "./archivedFileFinderPresentation.mjs";

function spy(returnValue) {
  const calls = [];
  const fn = (...args) => {
    calls.push(args);
    if (returnValue instanceof Error) {
      return Promise.reject(returnValue);
    }
    return Promise.resolve(returnValue);
  };
  fn.calls = calls;
  return fn;
}

function readSource(relativePath) {
  return readFileSync(
    fileURLToPath(new URL(relativePath, import.meta.url)),
    "utf8",
  );
}

// --- RECORD_TYPE_OPTIONS / recordTypeLabel --------------------------------

test("RECORD_TYPE_OPTIONS offers exactly All records, Awards, Proposals, and Negotiations - no Subaward this phase", () => {
  assert.deepEqual(
    RECORD_TYPE_OPTIONS.map((option) => option.value),
    ["ALL", "AWARD", "PROPOSAL", "NEGOTIATION"],
  );
  assert.deepEqual(
    RECORD_TYPE_OPTIONS.map((option) => option.label),
    ["All records", "Awards", "Proposals", "Negotiations"],
  );
});

test("recordTypeLabel names each real recordType and falls back honestly for an unknown one", () => {
  assert.equal(recordTypeLabel("AWARD"), "Awards");
  assert.equal(recordTypeLabel("PROPOSAL"), "Proposals");
  assert.equal(recordTypeLabel("NEGOTIATION"), "Negotiations");
  assert.equal(recordTypeLabel("ALL"), "All records");
  assert.equal(recordTypeLabel("SUBAWARD"), "SUBAWARD");
});

// --- visibleFieldsForRecordType / field labels (dynamic form fields) -----

test("visibleFieldsForRecordType shows Record ID/Attachment ID/File ID for AWARD", () => {
  assert.deepEqual(visibleFieldsForRecordType("AWARD"), [
    "recordNumber",
    "documentNumber",
    "recordId",
    "attachmentId",
    "fileId",
  ]);
});

test("visibleFieldsForRecordType hides File ID for PROPOSAL - Award-only, no safe equivalent", () => {
  const fields = visibleFieldsForRecordType("PROPOSAL");
  assert.deepEqual(fields, ["recordNumber", "documentNumber", "recordId", "attachmentId"]);
  assert.ok(!fields.includes("fileId"));
});

test("visibleFieldsForRecordType hides both File ID and recordNumber for NEGOTIATION - no separate Negotiation number exists in Kuali", () => {
  const fields = visibleFieldsForRecordType("NEGOTIATION");
  assert.deepEqual(fields, ["documentNumber", "recordId", "attachmentId"]);
  assert.ok(!fields.includes("fileId"));
  assert.ok(!fields.includes("recordNumber"));
});

test("visibleFieldsForRecordType only offers recordNumber/documentNumber for ALL - recordId/attachmentId/fileId are domain-ambiguous", () => {
  assert.deepEqual(visibleFieldsForRecordType("ALL"), ["recordNumber", "documentNumber"]);
});

test("recordNumberFieldLabel names the field per recordType - no Negotiation number label since NEGOTIATION never shows this field", () => {
  assert.equal(recordNumberFieldLabel("AWARD"), "Award number");
  assert.equal(recordNumberFieldLabel("PROPOSAL"), "Proposal number");
  assert.equal(recordNumberFieldLabel("ALL"), "Record number");
});

test("recordIdFieldLabel names the field per recordType", () => {
  assert.equal(recordIdFieldLabel("AWARD"), "Award ID");
  assert.equal(recordIdFieldLabel("PROPOSAL"), "Proposal ID");
  assert.equal(recordIdFieldLabel("NEGOTIATION"), "Negotiation ID");
});

// --- Negotiation ID (420) must never be confused with the negotiation's
// associated_document_id (419) - a different value entirely, describing
// whatever record this negotiation is associated with, not the
// negotiation itself. Live-verified fixture, 2026-08-14. ------------------

test("Negotiation ID 420 is never mapped to associated document/association ID 419: field visibility", () => {
  const fields = visibleFieldsForRecordType("NEGOTIATION");
  // recordId is the only Negotiation ID search field; recordNumber
  // (which never existed as a distinct concept) stays absent, so there
  // is exactly one place a numeric Negotiation ID can be entered.
  assert.deepEqual(fields, ["documentNumber", "recordId", "attachmentId"]);
});

test("Negotiation ID 420 is never mapped to associated document/association ID 419: view routing uses recordId/parentId, never associatedDocumentId", () => {
  assert.equal(
    resolveRecordViewPath({ recordType: "NEGOTIATION", parentId: 420 }),
    "/negotiations/420",
  );
  assert.notEqual(
    resolveRecordViewPath({ recordType: "NEGOTIATION", parentId: 420 }),
    "/negotiations/419",
  );
});

// --- hasAnyIdentifierSupplied (recordType-aware) --------------------------

test("hasAnyIdentifierSupplied is false when every visible filter is blank or whitespace", () => {
  assert.equal(
    hasAnyIdentifierSupplied({
      recordType: "AWARD",
      recordNumber: "",
      documentNumber: "  ",
      recordId: "",
      attachmentId: "",
      fileId: "",
    }),
    false,
  );
});

test("hasAnyIdentifierSupplied is true when only recordNumber is supplied", () => {
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "AWARD", recordNumber: "200086-00001" }),
    true,
  );
});

test("hasAnyIdentifierSupplied is true when only fileId is supplied for AWARD", () => {
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "AWARD", fileId: "5001" }),
    true,
  );
});

test("hasAnyIdentifierSupplied ignores fileId for PROPOSAL - it is not a visible field there", () => {
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "PROPOSAL", fileId: "5001" }),
    false,
  );
});

test("hasAnyIdentifierSupplied ignores fileId and recordNumber for NEGOTIATION - neither is a visible field there", () => {
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "NEGOTIATION", fileId: "5001" }),
    false,
  );
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "NEGOTIATION", recordNumber: "231427" }),
    false,
  );
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "NEGOTIATION", recordId: "420" }),
    true,
  );
});

test("hasAnyIdentifierSupplied ignores recordId/attachmentId/fileId for ALL - only recordNumber/documentNumber count", () => {
  assert.equal(
    hasAnyIdentifierSupplied({
      recordType: "ALL",
      recordId: "123",
      attachmentId: "456",
      fileId: "789",
    }),
    false,
  );
  assert.equal(
    hasAnyIdentifierSupplied({ recordType: "ALL", recordNumber: "879423" }),
    true,
  );
});

// --- archivedFileResultsCountLabel / archivedFileSearchErrorMessage ------

test("archivedFileResultsCountLabel pluralizes correctly", () => {
  assert.equal(archivedFileResultsCountLabel(0), "0 files found");
  assert.equal(archivedFileResultsCountLabel(1), "1 file found");
  assert.equal(archivedFileResultsCountLabel(165), "165 files found");
});

test("archivedFileResultsCountLabel treats a missing total as zero rather than throwing", () => {
  assert.equal(archivedFileResultsCountLabel(null), "0 files found");
  assert.equal(archivedFileResultsCountLabel(undefined), "0 files found");
});

test("archivedFileSearchErrorMessage distinguishes an expired session from a bad request", () => {
  assert.match(archivedFileSearchErrorMessage(401), /session has expired/);
  assert.match(archivedFileSearchErrorMessage(400), /could not be understood/);
  assert.match(archivedFileSearchErrorMessage(500), /could not be reached/);
  assert.match(archivedFileSearchErrorMessage(undefined), /could not be reached/);
});

// --- resolveAvailabilityChipColor (shared by both domains) ---------------

test("resolveAvailabilityChipColor maps every real server-derived status to a distinct color", () => {
  assert.equal(resolveAvailabilityChipColor("Available"), "success");
  assert.equal(resolveAvailabilityChipColor("Pending upload"), "warning");
  assert.equal(resolveAvailabilityChipColor("Failed"), "error");
  assert.equal(resolveAvailabilityChipColor("Source file unavailable"), "default");
});

test("resolveAvailabilityChipColor falls back to default for an unrecognized status rather than guessing", () => {
  assert.equal(resolveAvailabilityChipColor("Some New Status Never Seen Live"), "default");
});

// --- archivedFileResultKey (recordType-aware to avoid cross-domain id collisions) ---

test("archivedFileResultKey combines recordType, parentId, and attachmentId so a shared physical file across two Award versions never collides", () => {
  const onOlderVersion = { recordType: "AWARD", parentId: 3035516, attachmentId: 8001 };
  const onCurrentVersion = { recordType: "AWARD", parentId: 3047454, attachmentId: 9001 };

  assert.notEqual(
    archivedFileResultKey(onOlderVersion),
    archivedFileResultKey(onCurrentVersion),
  );
});

test("archivedFileResultKey never collides an Award result with a Proposal result sharing the same numeric ids", () => {
  const awardResult = { recordType: "AWARD", parentId: 100, attachmentId: 200 };
  const proposalResult = { recordType: "PROPOSAL", parentId: 100, attachmentId: 200 };

  assert.notEqual(
    archivedFileResultKey(awardResult),
    archivedFileResultKey(proposalResult),
  );
});

test("archivedFileResultKey never throws on null identifiers", () => {
  assert.equal(
    archivedFileResultKey({ recordType: null, parentId: null, attachmentId: null }),
    "unknown-unknown-unknown",
  );
});

test("formatSourceDateLabel falls back to an honest label rather than inventing a date", () => {
  assert.equal(formatSourceDateLabel(null), "Source date unknown");
  assert.equal(formatSourceDateLabel("2026-08-04T12:00:00"), "2026-08-04T12:00:00");
});

// --- resolveRecordViewPath (routes automatically per recordType) ---------

test("resolveRecordViewPath opens the exact Award version for an AWARD result", () => {
  assert.equal(
    resolveRecordViewPath({ recordType: "AWARD", parentId: 3047454 }),
    "/awards/3047454",
  );
});

test("resolveRecordViewPath opens the exact Proposal version for a PROPOSAL result", () => {
  assert.equal(
    resolveRecordViewPath({ recordType: "PROPOSAL", parentId: 7125 }),
    "/proposals/dashboard/7125",
  );
});

test("resolveRecordViewPath opens the Negotiation record for a NEGOTIATION result", () => {
  assert.equal(
    resolveRecordViewPath({ recordType: "NEGOTIATION", parentId: 374 }),
    "/negotiations/374",
  );
});

test("resolveRecordViewPath returns null rather than guessing when parentId is missing", () => {
  assert.equal(resolveRecordViewPath({ recordType: "AWARD", parentId: null }), null);
});

test("resolveRecordViewPath returns null for an unrecognized recordType rather than guessing a route", () => {
  assert.equal(resolveRecordViewPath({ recordType: "SUBAWARD", parentId: 1 }), null);
});

// --- URL parameter parsing (serialization/restoration) -------------------

test("parseRecordTypeParam accepts ALL/AWARD/PROPOSAL case-insensitively and defaults to ALL", () => {
  assert.equal(parseRecordTypeParam("AWARD"), "AWARD");
  assert.equal(parseRecordTypeParam("award"), "AWARD");
  assert.equal(parseRecordTypeParam("proposal"), "PROPOSAL");
  assert.equal(parseRecordTypeParam(null), "ALL");
  assert.equal(parseRecordTypeParam("SUBAWARD"), "ALL");
});

test("parseVersionFilterParam accepts current/historical case-insensitively and defaults to all", () => {
  assert.equal(parseVersionFilterParam("current"), "current");
  assert.equal(parseVersionFilterParam("HISTORICAL"), "historical");
  assert.equal(parseVersionFilterParam(null), "all");
  assert.equal(parseVersionFilterParam("not-a-real-filter"), "all");
});

// --- dispatchArchivedFileDownload (Award/Proposal download dispatch) -----

test("an AWARD result calls only the Award downloader, with the correct parentId and attachmentId", async () => {
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");

  const result = await dispatchArchivedFileDownload(
    "AWARD",
    3047454,
    9001,
    award,
    proposal,
  );

  assert.equal(result, "award-ok");
  assert.deepEqual(award.calls, [[3047454, 9001]]);
  assert.equal(proposal.calls.length, 0);
});

test("a PROPOSAL result calls only the Proposal downloader, with the correct parentId and attachmentId", async () => {
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");

  const result = await dispatchArchivedFileDownload(
    "PROPOSAL",
    1092721,
    31173,
    award,
    proposal,
  );

  assert.equal(result, "proposal-ok");
  assert.deepEqual(proposal.calls, [[1092721, 31173]]);
  assert.equal(award.calls.length, 0);
});

test("a NEGOTIATION result calls only the Negotiation downloader, with the correct parentId and attachmentId", async () => {
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");
  const negotiation = spy("negotiation-ok");

  const result = await dispatchArchivedFileDownload(
    "NEGOTIATION",
    374,
    501,
    award,
    proposal,
    negotiation,
  );

  assert.equal(result, "negotiation-ok");
  assert.deepEqual(negotiation.calls, [[374, 501]]);
  assert.equal(award.calls.length, 0);
  assert.equal(proposal.calls.length, 0);
});

test("IDs are passed through without conversion or swapping - parentId and attachmentId never trade places", async () => {
  const award = spy("ok");
  const proposal = spy("ok");
  const negotiation = spy("ok");

  await dispatchArchivedFileDownload("AWARD", 111, 222, award, proposal, negotiation);
  assert.deepEqual(award.calls[0], [111, 222]);

  await dispatchArchivedFileDownload("PROPOSAL", 333, 444, award, proposal, negotiation);
  assert.deepEqual(proposal.calls[0], [333, 444]);

  await dispatchArchivedFileDownload("NEGOTIATION", 555, 666, award, proposal, negotiation);
  assert.deepEqual(negotiation.calls[0], [555, 666]);
});

test("a Negotiation downloader failure propagates to the caller rather than being swallowed", async () => {
  const failure = new Error("Negotiation download failed: 403");
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");
  const negotiation = spy(failure);

  await assert.rejects(
    () => dispatchArchivedFileDownload("NEGOTIATION", 1, 2, award, proposal, negotiation),
    /Negotiation download failed: 403/,
  );
  assert.equal(award.calls.length, 0);
  assert.equal(proposal.calls.length, 0);
});

test("an Award downloader failure propagates to the caller rather than being swallowed", async () => {
  const failure = new Error("Award download failed: 503");
  const award = spy(failure);
  const proposal = spy("proposal-ok");

  await assert.rejects(
    () => dispatchArchivedFileDownload("AWARD", 1, 2, award, proposal),
    /Award download failed: 503/,
  );
  assert.equal(proposal.calls.length, 0);
});

test("a Proposal downloader failure propagates to the caller rather than being swallowed", async () => {
  const failure = new Error("Proposal download failed: 401");
  const award = spy("award-ok");
  const proposal = spy(failure);

  await assert.rejects(
    () => dispatchArchivedFileDownload("PROPOSAL", 1, 2, award, proposal),
    /Proposal download failed: 401/,
  );
  assert.equal(award.calls.length, 0);
});

test("an unsupported record type fails safely - rejects without calling either downloader", async () => {
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");

  await assert.rejects(
    () => dispatchArchivedFileDownload("SUBAWARD", 1, 2, award, proposal),
    /Unsupported record type/,
  );
  assert.equal(award.calls.length, 0);
  assert.equal(proposal.calls.length, 0);
});

test("a missing record type fails safely - rejects without calling either downloader", async () => {
  const award = spy("award-ok");
  const proposal = spy("proposal-ok");

  await assert.rejects(
    () => dispatchArchivedFileDownload(null, 1, 2, award, proposal),
    /Unsupported record type/,
  );
  assert.equal(award.calls.length, 0);
  assert.equal(proposal.calls.length, 0);

  await assert.rejects(
    () => dispatchArchivedFileDownload(undefined, 1, 2, award, proposal),
    /Unsupported record type/,
  );
});

// --- Route/nav wiring (static source inspection - no component-render harness) ---

test("App.tsx routes /archived-files to ArchivedFileFinderPage", () => {
  const source = readSource("../../App.tsx");
  const routeBlock = source.match(/path="archived-files"[\s\S]{0,80}/)?.[0];
  assert.ok(routeBlock, "expected an archived-files route block");
  assert.match(routeBlock, /ArchivedFileFinderPage/);
});

test("sidebarNavigationItems includes exactly one Archived File Finder entry pointing at /archived-files - no separate Award/Proposal/Subaward nav items", () => {
  const source = readSource("../navigation/navigationPresentation.mjs");
  assert.match(
    source,
    /label:\s*"Archived File Finder"[\s\S]{0,20}path:\s*"\/archived-files"/,
  );
  const occurrences = source.match(/path:\s*"\/archived-files"/g) ?? [];
  assert.equal(occurrences.length, 1);
});

test("AppLayout.tsx assigns an icon to the archivedFiles nav key", () => {
  const source = readSource("../../layout/AppLayout.tsx");
  assert.match(source, /archivedFiles:\s*</);
});
