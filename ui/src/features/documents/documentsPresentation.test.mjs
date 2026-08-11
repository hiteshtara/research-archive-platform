import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  DOCUMENT_EXPLORER_PRESETS,
  EXPLORER_MODULES,
  MODULES,
  NORMALIZED_STATUSES,
  additionalRelationshipsLabel,
  documentSearchErrorMessage,
  documentSearchResultsCountLabel,
  explorerModuleLabel,
  isNavigable,
  moduleFacetLabel,
  moduleLabel,
  normalizedStatusLabel,
  resultsAreApprovedModulesOnly,
  unitSourceLabel,
} from "./documentsPresentation.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readSource(relativePath) {
  return readFileSync(path.join(__dirname, relativePath), "utf8");
}

// --- Pure logic ---

test("exposes exactly the five approved core modules, never attachments", () => {
  assert.deepEqual(MODULES, [
    "AWARD",
    "PROPOSAL",
    "NEGOTIATION",
    "SUBAWARD",
    "IRB",
  ]);
  assert.equal(MODULES.includes("AWARD_ATTACHMENT"), false);
  assert.equal(MODULES.includes("ATTACHMENT"), false);
});

test("moduleLabel maps known modules and falls back for unknown ones", () => {
  assert.equal(moduleLabel("AWARD"), "Award");
  assert.equal(moduleLabel("IRB"), "IRB");
  assert.equal(moduleLabel("SOMETHING_ELSE"), "SOMETHING_ELSE");
});

test("formats the results count label with correct pluralization", () => {
  assert.equal(documentSearchResultsCountLabel(0), "0 documents found");
  assert.equal(documentSearchResultsCountLabel(1), "1 document found");
  assert.equal(documentSearchResultsCountLabel(2), "2 documents found");
  assert.equal(
    documentSearchResultsCountLabel(78854),
    "78,854 documents found",
  );
  assert.equal(documentSearchResultsCountLabel(undefined), "0 documents found");
});

test("maps error status codes to specific messages", () => {
  assert.match(documentSearchErrorMessage(401), /session has expired/i);
  assert.match(documentSearchErrorMessage(400), /could not be understood/i);
  assert.match(documentSearchErrorMessage(500), /could not be reached/i);
  assert.match(documentSearchErrorMessage(undefined), /could not be reached/i);
});

test("resultsAreApprovedModulesOnly rejects anything outside the five modules", () => {
  assert.equal(
    resultsAreApprovedModulesOnly([
      { module: "AWARD" },
      { module: "IRB" },
    ]),
    true,
  );
  assert.equal(
    resultsAreApprovedModulesOnly([
      { module: "AWARD" },
      { module: "AWARD_ATTACHMENT" },
    ]),
    false,
  );
  assert.equal(resultsAreApprovedModulesOnly([]), true);
});

test("isNavigable is true only when targetRoute is present", () => {
  assert.equal(isNavigable({ targetRoute: "/awards/123" }), true);
  assert.equal(isNavigable({ targetRoute: null }), false);
  assert.equal(isNavigable({ targetRoute: "" }), false);
});

// --- Kuali Document Explorer pure logic ---

test("EXPLORER_MODULES excludes IRB per decision 7 (zero rows in dev, unverifiable end to end)", () => {
  assert.deepEqual(EXPLORER_MODULES, [
    "AWARD",
    "PROPOSAL",
    "NEGOTIATION",
    "SUBAWARD",
  ]);
  assert.equal(EXPLORER_MODULES.includes("IRB"), false);
});

test("explorerModuleLabel maps the four approved modules", () => {
  assert.equal(explorerModuleLabel("AWARD"), "Award");
  assert.equal(explorerModuleLabel("SUBAWARD"), "Subaward");
  assert.equal(explorerModuleLabel("IRB"), "IRB");
});

test("NORMALIZED_STATUSES exposes exactly the six approved buckets", () => {
  assert.deepEqual(NORMALIZED_STATUSES, [
    "ACTIVE",
    "PENDING",
    "ARCHIVED",
    "CANCELLED",
    "CLOSED",
    "UNKNOWN",
  ]);
});

test("normalizedStatusLabel maps known statuses and falls back for unknown ones", () => {
  assert.equal(normalizedStatusLabel("ACTIVE"), "Active");
  assert.equal(normalizedStatusLabel("UNKNOWN"), "Unknown");
  assert.equal(normalizedStatusLabel("SOMETHING_ELSE"), "SOMETHING_ELSE");
});

test("DOCUMENT_EXPLORER_PRESETS only offers presets with a verified status mapping (no Archived Subawards)", () => {
  const keys = DOCUMENT_EXPLORER_PRESETS.map((preset) => preset.key);
  assert.equal(keys.includes("activeAwards"), true);
  assert.equal(keys.includes("pendingProposals"), true);
  assert.equal(keys.includes("negotiationsInProgress"), true);
  assert.equal(keys.includes("documentsByPi"), true);
  // No Subaward status maps to ARCHIVED in the approved table - this
  // preset is deliberately absent, not silently returning zero results.
  assert.equal(keys.includes("archivedSubawards"), false);
});

test("moduleFacetLabel formats a facet count with a locale-formatted number", () => {
  assert.equal(
    moduleFacetLabel({ value: "AWARD", count: 49827 }),
    "Award: 49,827",
  );
});

test("additionalRelationshipsLabel is null at zero or below, and pluralizes correctly", () => {
  assert.equal(additionalRelationshipsLabel(0, "unit"), null);
  assert.equal(additionalRelationshipsLabel(-1, "person"), null);
  assert.equal(additionalRelationshipsLabel(1, "person"), "+1 other person");
  assert.equal(additionalRelationshipsLabel(3, "unit"), "+3 other units");
});

test("unitSourceLabel marks a Negotiation's unit as inherited from its associated Award, never native", () => {
  assert.equal(unitSourceLabel("NEGOTIATION"), "Unit (via associated Award)");
  assert.equal(unitSourceLabel("AWARD"), "Unit");
  assert.equal(unitSourceLabel("SUBAWARD"), "Unit");
});

// --- CARB-X fixture, per
// docs/architecture/KUALI_DOCUMENT_METRIC_INVESTIGATION.md ---

test("CARB-X: two Proposal document numbers both belong to the approved module set and both navigate to the same Proposal route", () => {
  const carbxProposalVersions = [
    {
      module: "PROPOSAL",
      documentNumber: "430102",
      businessRecordNumber: "01128961",
      targetRoute: "/proposals/01128961",
    },
    {
      module: "PROPOSAL",
      documentNumber: "451704",
      businessRecordNumber: "01128961",
      targetRoute: "/proposals/01128961",
    },
  ];

  assert.equal(resultsAreApprovedModulesOnly(carbxProposalVersions), true);
  assert.equal(carbxProposalVersions.every(isNavigable), true);
  assert.deepEqual(
    carbxProposalVersions.map((row) => row.targetRoute),
    ["/proposals/01128961", "/proposals/01128961"],
  );
  // Two distinct Kuali documents, same underlying business record.
  assert.notEqual(
    carbxProposalVersions[0].documentNumber,
    carbxProposalVersions[1].documentNumber,
  );
});

// --- Structural assertions against DocumentsPage.tsx (this project's
// established pattern for verifying component wiring without a
// component-render test harness - mirrors
// awardEvidenceSearchPresentation.test.mjs's own use of this
// technique) ---

test("DocumentsPage renders loading, empty, and error states", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /searchQuery\.isLoading/);
  assert.match(source, /searchQuery\.isError/);
  assert.match(source, /No documents match these filters/);
  assert.match(source, /<LoadingState/);
  assert.match(source, /<ErrorState/);
  assert.match(source, /<EmptyState/);
});

test("DocumentsPage exposes a combined search field plus module and status filters", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(
    source,
    /Search by document number, record number, title, person or sponsor/,
  );
  assert.match(source, /label="Module"/);
  assert.match(source, /label="Status"/);
});

test("DocumentsPage exposes unit, person, sponsor, subrecipient, date, and sort filters", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /label="Lead unit number"/);
  assert.match(source, /Include any associated unit/);
  assert.match(source, /label="Person name"/);
  assert.match(source, /label="Person role"/);
  assert.match(source, /PI only/);
  assert.match(source, /label="Sponsor code"/);
  assert.match(source, /label="Subrecipient organization"/);
  assert.match(source, /label="Date from"/);
  assert.match(source, /label="Date to"/);
  assert.match(source, /label="Sort by"/);
  assert.match(source, /Clear filters/);
});

test("DocumentsPage offers presets and shows module facet counts", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /DOCUMENT_EXPLORER_PRESETS/);
  assert.match(source, /moduleFacets/);
  assert.match(source, /moduleFacetLabel/);
});

test("DocumentsPage does not offer IRB as a module option", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /EXPLORER_MODULES/);
  assert.doesNotMatch(source, />IRB</);
});

test("DocumentsPage calls the Explorer endpoint, not the simpler Document Search endpoint", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /searchDocumentExplorer/);
});

test("DocumentsPage paginates results and shows a results count", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /<PaginationFooter/);
  assert.match(source, /documentSearchResultsCountLabel/);
});

test("DocumentsPage never renders attachment-specific fields as documents", () => {
  // The page's own copy explains that attachments live elsewhere (that
  // explanatory text is expected and fine) - what must never appear is
  // actual attachment data being rendered as if it were a document:
  // file identifiers, S3 references, or an attachment result type.
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.doesNotMatch(source, /fileId/);
  assert.doesNotMatch(source, /s3Bucket|s3Key/);
  assert.doesNotMatch(source, /AWARD_ATTACHMENT/);
  assert.match(
    source.replace(/\s+/g, " "),
    /Attachments are separate files reached from the owning record/,
  );
});

test("DocumentsPage navigates using the backend-computed targetRoute, never a hand-built path", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /navigate\(result\.targetRoute/);
  assert.match(source, /isNavigable\(result\)/);
});

test("Dashboard card is renamed to Kuali Documents and routes to /documents", () => {
  const source = readSource("../dashboard/dashboardPresentation.mjs");
  assert.match(source, /title: "Kuali Documents"/);
  assert.match(
    source,
    /Archived workflow and business documents across all modules/,
  );
  assert.match(source, /path: "\/documents"/);
});

test("App.tsx routes /documents to DocumentsPage, not ComingSoonPage", () => {
  const source = readSource("../../App.tsx");
  const documentsRouteBlock = source.match(
    /path="documents"[\s\S]{0,80}/,
  )?.[0];
  assert.ok(documentsRouteBlock, "expected a documents route block");
  assert.match(documentsRouteBlock, /DocumentsPage/);
  assert.doesNotMatch(documentsRouteBlock, /ComingSoonPage/);
});
