import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  MODULES,
  documentSearchErrorMessage,
  documentSearchResultsCountLabel,
  isNavigable,
  moduleLabel,
  resultsAreApprovedModulesOnly,
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

test("DocumentsPage exposes document number, module, title, and status filters", () => {
  const source = readSource("../../pages/DocumentsPage.tsx");
  assert.match(source, /label="Document number"/);
  assert.match(source, /label="Module"/);
  assert.match(source, /label="Title"/);
  assert.match(source, /label="Status"/);
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
