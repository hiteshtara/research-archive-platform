import assert from "node:assert/strict";
import { test } from "node:test";

import {
  REPORT_ONLY,
  REPORT_WITH_ATTACHMENTS,
  buildAwardReportFallbackFileName,
  buildAwardReportPath,
  reportDownloadErrorMessage,
  resolveReportActionState,
} from "./awardReportDownloadPresentation.mjs";

// --- endpoint routing: the thing most worth pinning ------------------
test("the existing report action still calls /report.pdf", () => {
  assert.equal(
    buildAwardReportPath(2727052, REPORT_ONLY),
    "/api/v1/awards/2727052/report.pdf",
  );
});

test("report-only is the default, so the original behaviour cannot drift", () => {
  assert.equal(
    buildAwardReportPath(2727052),
    "/api/v1/awards/2727052/report.pdf",
  );
});

test("the new action calls /report-with-attachments.pdf", () => {
  assert.equal(
    buildAwardReportPath(2727052, REPORT_WITH_ATTACHMENTS),
    "/api/v1/awards/2727052/report-with-attachments.pdf",
  );
});

test("the two actions never share an endpoint", () => {
  assert.notEqual(
    buildAwardReportPath(1, REPORT_ONLY),
    buildAwardReportPath(1, REPORT_WITH_ATTACHMENTS),
  );
});

test("the awardId is used and encoded", () => {
  assert.equal(
    buildAwardReportPath(9001, REPORT_WITH_ATTACHMENTS),
    "/api/v1/awards/9001/report-with-attachments.pdf",
  );
  assert.equal(
    buildAwardReportPath("a b/c", REPORT_ONLY),
    "/api/v1/awards/a%20b%2Fc/report.pdf",
    "a path segment must never be injectable",
  );
});

// --- loading / disabled state ----------------------------------------
test("idle labels", () => {
  assert.equal(
    resolveReportActionState(REPORT_ONLY, false).label,
    "Download Award Report",
  );
  assert.equal(
    resolveReportActionState(REPORT_WITH_ATTACHMENTS, false).label,
    "Download Report + Attachments",
  );
});

test("loading state disables and announces busy", () => {
  const state = resolveReportActionState(REPORT_WITH_ATTACHMENTS, true);
  assert.equal(state.label, "Preparing report…");
  assert.equal(state.disabled, true);
  assert.equal(state.busy, true);
});

test("each action's state is independent of the other", () => {
  // The page holds separate flags; resolving one busy must never make
  // the other look busy.
  const busy = resolveReportActionState(REPORT_WITH_ATTACHMENTS, true);
  const idle = resolveReportActionState(REPORT_ONLY, false);
  assert.equal(busy.disabled, true);
  assert.equal(idle.disabled, false);
  assert.equal(idle.label, "Download Award Report");
});

// --- version-scoped wording ------------------------------------------
test("help text says this Award version, never 'all attachments'", () => {
  const tooltip = resolveReportActionState(
    REPORT_WITH_ATTACHMENTS, false,
  ).tooltip;
  assert.match(tooltip, /this Award version/);
  assert.doesNotMatch(tooltip, /all Award attachments/i);
  assert.doesNotMatch(tooltip, /\ball attachments\b/i);
});

// --- error state ------------------------------------------------------
test("404 gives a clear report error", () => {
  assert.match(
    reportDownloadErrorMessage(404, REPORT_WITH_ATTACHMENTS),
    /could not be generated/,
  );
});

test("a failure states that attachments were not downloaded", () => {
  const message = reportDownloadErrorMessage(500, REPORT_WITH_ATTACHMENTS);
  assert.match(message, /500/);
  assert.match(message, /report with attachments was not downloaded/);
});

test("error messages never mention storage internals", () => {
  for (const status of [403, 404, 500, 503]) {
    for (const kind of [REPORT_ONLY, REPORT_WITH_ATTACHMENTS]) {
      const message = reportDownloadErrorMessage(status, kind);
      assert.doesNotMatch(message, /s3|bucket|key|amazonaws/i);
    }
  }
});

// --- filenames --------------------------------------------------------
test("fallback filenames differ per action", () => {
  assert.equal(
    buildAwardReportFallbackFileName("105698-00001", REPORT_ONLY),
    "Award_105698-00001_Complete_Report.pdf",
  );
  assert.equal(
    buildAwardReportFallbackFileName("105698-00001", REPORT_WITH_ATTACHMENTS),
    "Award_105698-00001_Complete_Report_With_Attachments.pdf",
  );
});

test("fallback filename is sanitized", () => {
  assert.equal(
    buildAwardReportFallbackFileName("../../etc/passwd", REPORT_ONLY),
    "Award_.._.._etc_passwd_Complete_Report.pdf",
  );
});
