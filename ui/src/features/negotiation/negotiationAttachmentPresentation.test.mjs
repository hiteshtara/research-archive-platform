import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveRestrictedLabel,
  resolveAttachmentDisplayLabel,
  resolveAttachmentIdentifierSummary,
} from "./negotiationAttachmentPresentation.mjs";

test("resolveRestrictedLabel shows Y as marked restricted", () => {
  assert.equal(
    resolveRestrictedLabel("Y"),
    "Marked restricted in legacy Kuali",
  );
});

test("resolveRestrictedLabel shows N as not restricted", () => {
  assert.equal(
    resolveRestrictedLabel("N"),
    "Not restricted in legacy Kuali",
  );
});

test("resolveRestrictedLabel is honest about a null/missing value rather than guessing Y or N", () => {
  assert.equal(
    resolveRestrictedLabel(null),
    "Restricted status unknown in legacy Kuali",
  );
  assert.equal(
    resolveRestrictedLabel(undefined),
    "Restricted status unknown in legacy Kuali",
  );
  assert.equal(
    resolveRestrictedLabel(""),
    "Restricted status unknown in legacy Kuali",
  );
});

test("resolveRestrictedLabel surfaces an unexpected legacy value verbatim rather than coercing it to Y/N", () => {
  assert.equal(
    resolveRestrictedLabel("MAYBE"),
    "Legacy Kuali RESTRICTED value: MAYBE",
  );
});

test("resolveAttachmentDisplayLabel prefers the description - real fixture negotiation_id=420/attachment_id=101", () => {
  assert.equal(
    resolveAttachmentDisplayLabel({
      description: "Kotton Proteostasis",
      oracleAttachmentId: 101,
      oracleFileId: "24828",
      activityId: 10134,
    }),
    "Kotton Proteostasis",
  );
});

test("resolveAttachmentDisplayLabel falls back to the raw Oracle attachment ID when description is missing", () => {
  assert.equal(
    resolveAttachmentDisplayLabel({
      description: null,
      oracleAttachmentId: 101,
    }),
    "Attachment 101",
  );
  assert.equal(
    resolveAttachmentDisplayLabel({
      description: "   ",
      oracleAttachmentId: 202,
    }),
    "Attachment 202",
  );
});

test("resolveAttachmentDisplayLabel never returns blank when both description and the raw ID are missing", () => {
  assert.equal(
    resolveAttachmentDisplayLabel({ description: null, oracleAttachmentId: null }),
    "Untitled attachment",
  );
  assert.equal(resolveAttachmentDisplayLabel(null), "Untitled attachment");
  assert.equal(resolveAttachmentDisplayLabel(undefined), "Untitled attachment");
});

test("resolveAttachmentIdentifierSummary surfaces activity/attachment/file IDs only - real fixture negotiation_id=420", () => {
  assert.equal(
    resolveAttachmentIdentifierSummary({
      activityId: 10134,
      oracleAttachmentId: 101,
      oracleFileId: "24828",
    }),
    "Activity 10134 · Attachment 101 · File 24828",
  );
});

test("resolveAttachmentIdentifierSummary omits any part that is missing rather than showing a blank placeholder", () => {
  assert.equal(
    resolveAttachmentIdentifierSummary({
      activityId: null,
      oracleAttachmentId: 101,
      oracleFileId: null,
    }),
    "Attachment 101",
  );
  assert.equal(resolveAttachmentIdentifierSummary({}), "");
});

test("resolveAttachmentIdentifierSummary never includes storage internals like s3 bucket/key or checksum", () => {
  const summary = resolveAttachmentIdentifierSummary({
    activityId: 10134,
    oracleAttachmentId: 101,
    oracleFileId: "24828",
    s3Bucket: "some-bucket",
    s3Key: "some/key",
    checksum: "deadbeef",
  });
  assert.doesNotMatch(summary, /bucket|key|checksum|deadbeef/i);
});
