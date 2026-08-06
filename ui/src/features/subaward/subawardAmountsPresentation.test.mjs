import assert from "node:assert/strict";
import test from "node:test";

import {
  buildAmendmentTimeline,
  isPlausibleMimeType,
  resolveAttachmentLabel,
  sumAmendmentTotals,
} from "./subawardAmountsPresentation.mjs";

// Real fixture (subaward_code 1012, subaward_id 16279) - matches the
// live Kuali Financial tab exactly (screenshot verified 2026-08-06):
// four amendments, obligated 293412/293412/185399/79916, the corrupted
// mime_type on amount 23973 confirmed genuine Oracle source data via
// DUMP().
const AMOUNT_23974 = {
  subawardAmountInfoId: 23974,
  effectiveDate: "2017-04-01",
  obligatedAmount: 293412,
  obligatedChange: 0,
  anticipatedAmount: 293412,
  anticipatedChange: 0,
  modificationNumber: "003",
  modificationTypeDescription: "No Cost Extension",
  performanceStartDate: "2017-04-01",
  performanceEndDate: "2018-03-31",
  fileName: null,
  mimeType: null,
  comments: null,
};

const AMOUNT_23973 = {
  subawardAmountInfoId: 23973,
  effectiveDate: "2016-04-01",
  obligatedAmount: 293412,
  obligatedChange: 108013,
  anticipatedAmount: 293412,
  anticipatedChange: 108013,
  modificationNumber: "002",
  modificationTypeDescription: "Continuation",
  performanceStartDate: "2016-04-01",
  performanceEndDate: "2017-03-31",
  fileName: "FFATA_Sub 4500001614 Amend 002_KC 1012.pdf",
  mimeType:
    "\"\\\"\\\\\\\"\\\\\\\\\\\\\\\"application/pdf\\\\\\\\\\\\\\\"\\\\\\\"\\\"\"",
  comments: null,
};

const AMOUNT_23972 = {
  subawardAmountInfoId: 23972,
  effectiveDate: "2015-03-31",
  obligatedAmount: 185399,
  obligatedChange: 105483,
  anticipatedAmount: 185399,
  anticipatedChange: 105483,
  modificationNumber: "001",
  modificationTypeDescription: "Continuation",
  performanceStartDate: "2015-04-01",
  performanceEndDate: "2016-03-31",
  fileName: null,
  mimeType: null,
  comments: null,
};

const AMOUNT_23971 = {
  subawardAmountInfoId: 23971,
  effectiveDate: "2014-04-30",
  obligatedAmount: 79916,
  obligatedChange: 79916,
  anticipatedAmount: 79916,
  anticipatedChange: 79916,
  modificationNumber: null,
  modificationTypeDescription: "Converted Record",
  performanceStartDate: "2014-04-30",
  performanceEndDate: "2015-03-31",
  fileName: null,
  mimeType: null,
  comments: "Balance as of Conversion on 01/09/2015.",
};

const SUBAWARD_1012_AMOUNTS = [
  AMOUNT_23974,
  AMOUNT_23973,
  AMOUNT_23972,
  AMOUNT_23971,
];

test("isPlausibleMimeType accepts a real type/subtype token", () => {
  assert.equal(isPlausibleMimeType("application/pdf"), true);
});

test("isPlausibleMimeType rejects the confirmed corrupted Oracle source value", () => {
  assert.equal(isPlausibleMimeType(AMOUNT_23973.mimeType), false);
});

test("isPlausibleMimeType rejects null/missing values without throwing", () => {
  assert.equal(isPlausibleMimeType(null), false);
  assert.equal(isPlausibleMimeType(undefined), false);
});

test("resolveAttachmentLabel shows the filename with a clean mime type when plausible", () => {
  const label = resolveAttachmentLabel({
    fileName: "report.pdf",
    mimeType: "application/pdf",
  });

  assert.equal(label, "report.pdf (application/pdf)");
});

test("resolveAttachmentLabel falls back to the filename alone rather than rendering a corrupted mime type", () => {
  const label = resolveAttachmentLabel(AMOUNT_23973);

  assert.equal(label, "FFATA_Sub 4500001614 Amend 002_KC 1012.pdf");
});

test("resolveAttachmentLabel returns null when there is no file at all", () => {
  assert.equal(
    resolveAttachmentLabel({ fileName: null, mimeType: null }),
    null,
  );
});

test("buildAmendmentTimeline maps every real Subaward 1012 amendment, matching the live Kuali screen field-for-field", () => {
  const timeline = buildAmendmentTimeline(SUBAWARD_1012_AMOUNTS);

  assert.equal(timeline.length, 4);
  assert.deepEqual(timeline[0], {
    subawardAmountInfoId: 23974,
    amendmentNumber: "003",
    modificationType: "No Cost Extension",
    effectiveDate: "2017-04-01",
    budgetPeriodStart: "2017-04-01",
    budgetPeriodEnd: "2018-03-31",
    obligatedChange: 0,
    anticipatedChange: 0,
    comments: null,
    attachmentLabel: null,
  });
  assert.deepEqual(timeline[1], {
    subawardAmountInfoId: 23973,
    amendmentNumber: "002",
    modificationType: "Continuation",
    effectiveDate: "2016-04-01",
    budgetPeriodStart: "2016-04-01",
    budgetPeriodEnd: "2017-03-31",
    obligatedChange: 108013,
    anticipatedChange: 108013,
    comments: null,
    attachmentLabel: "FFATA_Sub 4500001614 Amend 002_KC 1012.pdf",
  });
});

test("sumAmendmentTotals reproduces SubAwardServiceImpl.calculateAmountInfo()'s obligated/anticipated accumulation", () => {
  const totals = sumAmendmentTotals(SUBAWARD_1012_AMOUNTS);

  // 0 + 108013 + 105483 + 79916 = 293412, matching the live Kuali
  // "Total Obligated Amount" field for this exact Subaward.
  assert.equal(totals.totalObligatedChange, 293412);
  assert.equal(totals.totalAnticipatedChange, 293412);
});

test("sumAmendmentTotals ignores null change values instead of producing NaN", () => {
  const totals = sumAmendmentTotals([
    { obligatedChange: null, anticipatedChange: 50 },
    { obligatedChange: 25, anticipatedChange: null },
  ]);

  assert.equal(totals.totalObligatedChange, 25);
  assert.equal(totals.totalAnticipatedChange, 50);
});
