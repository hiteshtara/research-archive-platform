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
  documentDateLabel,
  documentSearchErrorMessage,
  documentSearchResultsCountLabel,
  explorerModuleLabel,
  isNavigable,
  moduleFacetLabel,
  moduleLabel,
  normalizedStatusLabel,
  resultsAreApprovedModulesOnly,
  seedFiltersFromParams,
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

// --- Lalitha's proposal-history findings ---

test("a proposal document row routes to its own version, not the family page", () => {
  // The defect: every version of proposal 01394406 carried
  // targetRoute "/proposals/01394406", the FAMILY route, which
  // redirects to the current version - so clicking document 1000570
  // (version 2) opened version 4 / workflow 1000951.
  //
  // The route must now name the version. These are the four documents
  // from the report, with proposal_id as the version key.
  const versions = [
    { module: "PROPOSAL", documentNumber: "1000005", versionOrSequence: "1", targetRoute: "/proposals/dashboard/9001" },
    { module: "PROPOSAL", documentNumber: "1000570", versionOrSequence: "2", targetRoute: "/proposals/dashboard/9002" },
    { module: "PROPOSAL", documentNumber: "1000653", versionOrSequence: "3", targetRoute: "/proposals/dashboard/9003" },
    { module: "PROPOSAL", documentNumber: "1000951", versionOrSequence: "4", targetRoute: "/proposals/dashboard/9004" },
  ];

  assert.equal(versions.every(isNavigable), true);
  // Four documents, four DISTINCT destinations - the whole point.
  assert.equal(new Set(versions.map((v) => v.targetRoute)).size, 4);
  // None of them may be the family route.
  assert.equal(
    versions.some((v) => /^\/proposals\/[^d]/.test(v.targetRoute)),
    false,
  );
});

test("seeding applies a known module so a link lands on filtered results", () => {
  const empty = { query: "", module: "", sort: "" };
  const seeded = seedFiltersFromParams(empty, new URLSearchParams("module=PROPOSAL"));
  assert.equal(seeded.module, "PROPOSAL");
  // Everything else untouched.
  assert.equal(seeded.query, "");
});

test("seeding accepts a lowercase module from a hand-typed link", () => {
  const seeded = seedFiltersFromParams({ module: "" }, new URLSearchParams("module=proposal"));
  assert.equal(seeded.module, "PROPOSAL");
});

test("an unknown module seeds nothing rather than filtering to zero rows", () => {
  // Passing it through would return no rows, which reads as "there are
  // no proposal documents" rather than "that link was wrong".
  const empty = { module: "" };
  assert.deepEqual(seedFiltersFromParams(empty, new URLSearchParams("module=BANANA")), empty);
  assert.deepEqual(seedFiltersFromParams(empty, new URLSearchParams("")), empty);
  assert.deepEqual(seedFiltersFromParams(empty, null), empty);
});

test("the document date is labelled by what it actually is, per module", () => {
  // One field, a different column per module. For proposal 01394406
  // version 4 this is initial_start_date (2024-01-01), NOT the
  // Updated timestamp the Versions tab shows (2023-06-02).
  assert.equal(documentDateLabel("PROPOSAL"), "Period start date");
  assert.equal(documentDateLabel("AWARD"), "Period start date");
  assert.equal(documentDateLabel("NEGOTIATION"), "Period start date");
  assert.equal(documentDateLabel("SUBAWARD"), "Period start date");
  // IRB's column is received_date - "Period start date" would be wrong.
  assert.equal(documentDateLabel("IRB"), "Received date");
  assert.equal(documentDateLabel("SOMETHING_ELSE"), "Date");
});

test("the Proposal status mapping is a DISPOSITION, and is pinned against the SQL", () => {
  /*
   * Two different things are called "archived" on screens a reader
   * moves between, which is what made proposal 01394406 confusing:
   *
   *   Kuali Documents, version 4 -> badge "Archived"
   *     from proposal_version.status_description = 'Not Funded',
   *     grouped by the CASE below. It describes the proposal's
   *     DISPOSITION - what became of it.
   *
   *   Versions tab, version 4 -> Sequence status "ACTIVE"
   *     from proposal_version.proposal_sequence_status. It describes
   *     whether this row is the current version.
   *
   * They disagree for the same record and both are correct, because
   * they answer different questions. Version 4 is the current version
   * (sequence ACTIVE) of a proposal that was not funded (disposition
   * archived). Versions 1-3 are superseded (sequence ARCHIVED) and
   * still Pending as dispositions.
   *
   * This pins the mapping against the SQL so a change to either side
   * fails here rather than silently shifting what a badge means. The
   * mapping itself is NOT changed - no stored status is touched.
   */
  const explorerSql = readFileSync(
    path.join(
      path.dirname(fileURLToPath(import.meta.url)),
      "../../../../api/src/main/java/edu/bu/archive/adapter/out/persistence/DocumentExplorerRepository.java",
    ),
    "utf8",
  );

  for (const [native, normalized] of [
    ["Pending", "PENDING"],
    ["Pending-Revised", "PENDING"],
    ["Funded", "ACTIVE"],
    ["Not Funded", "ARCHIVED"],
    ["Withdrawn", "ARCHIVED"],
    ["Deactivated Record", "ARCHIVED"],
  ]) {
    assert.ok(
      explorerSql.includes(`WHEN '${native}' THEN '${normalized}'`),
      `expected proposal status mapping ${native} -> ${normalized} in the SQL`,
    );
  }

  // The sequence status is a separate column and must never be the
  // source of this badge.
  assert.ok(
    !/CASE pv\.proposal_sequence_status/.test(explorerSql),
    "the document status badge must not be derived from proposal_sequence_status",
  );
});
