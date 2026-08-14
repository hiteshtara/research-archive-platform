import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  archivedFileResultKey,
  archivedFileResultsCountLabel,
  archivedFileSearchErrorMessage,
  formatSourceDateLabel,
  hasAnyIdentifierSupplied,
  resolveAvailabilityChipColor,
} from "./archivedFileFinderPresentation.mjs";

function readSource(relativePath) {
  return readFileSync(
    fileURLToPath(new URL(relativePath, import.meta.url)),
    "utf8",
  );
}

test("hasAnyIdentifierSupplied is false when every filter is blank or whitespace", () => {
  assert.equal(
    hasAnyIdentifierSupplied({
      awardNumber: "",
      documentNumber: "  ",
      awardId: undefined,
      attachmentId: undefined,
      fileId: undefined,
    }),
    false,
  );
});

test("hasAnyIdentifierSupplied is true when only awardNumber is supplied", () => {
  assert.equal(
    hasAnyIdentifierSupplied({ awardNumber: "200086-00001" }),
    true,
  );
});

test("hasAnyIdentifierSupplied is true when only a numeric-identifier field is supplied", () => {
  assert.equal(hasAnyIdentifierSupplied({ fileId: "5001" }), true);
});

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

test("resolveAvailabilityChipColor maps every real server-derived status to a distinct color", () => {
  assert.equal(resolveAvailabilityChipColor("Available"), "success");
  assert.equal(resolveAvailabilityChipColor("Pending upload"), "warning");
  assert.equal(resolveAvailabilityChipColor("Failed"), "error");
  assert.equal(resolveAvailabilityChipColor("Source file unavailable"), "default");
});

test("resolveAvailabilityChipColor falls back to default for an unrecognized status rather than guessing", () => {
  assert.equal(resolveAvailabilityChipColor("Some New Status Never Seen Live"), "default");
});

test("archivedFileResultKey combines parentId and attachmentId so a shared physical file across two Award versions never collides", () => {
  const onOlderVersion = { parentId: 3035516, attachmentId: 8001 };
  const onCurrentVersion = { parentId: 3047454, attachmentId: 9001 };

  assert.notEqual(
    archivedFileResultKey(onOlderVersion),
    archivedFileResultKey(onCurrentVersion),
  );
});

test("archivedFileResultKey never throws on null identifiers", () => {
  assert.equal(
    archivedFileResultKey({ parentId: null, attachmentId: null }),
    "unknown-unknown",
  );
});

test("formatSourceDateLabel falls back to an honest label rather than inventing a date", () => {
  assert.equal(formatSourceDateLabel(null), "Source date unknown");
  assert.equal(formatSourceDateLabel("2026-08-04T12:00:00"), "2026-08-04T12:00:00");
});

// No component-render harness exists in this project, so route/nav
// wiring is verified by static source inspection - same approach
// documentsPresentation.test.mjs uses for App.tsx's /documents route.
test("App.tsx routes /archived-files to ArchivedFileFinderPage", () => {
  const source = readSource("../../App.tsx");
  const routeBlock = source.match(/path="archived-files"[\s\S]{0,80}/)?.[0];
  assert.ok(routeBlock, "expected an archived-files route block");
  assert.match(routeBlock, /ArchivedFileFinderPage/);
});

test("sidebarNavigationItems includes an Archived File Finder entry pointing at /archived-files", () => {
  const source = readSource("../navigation/navigationPresentation.mjs");
  assert.match(
    source,
    /label:\s*"Archived File Finder"[\s\S]{0,20}path:\s*"\/archived-files"/,
  );
});

test("AppLayout.tsx assigns an icon to the archivedFiles nav key", () => {
  const source = readSource("../../layout/AppLayout.tsx");
  assert.match(source, /archivedFiles:\s*</);
});
