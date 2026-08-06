import assert from "node:assert/strict";
import test from "node:test";

import { groupAttachmentsByType } from "./subawardAttachmentsPresentation.mjs";

function attachment(overrides = {}) {
  return {
    attachmentId: 1,
    subawardId: 17206,
    subawardCode: "1363",
    sequenceNumber: 8,
    attachmentTypeCode: 1,
    attachmentTypeDescription: null,
    documentId: null,
    fileName: null,
    mimeType: null,
    documentStatusCode: null,
    description: null,
    lastUpdateTimestamp: null,
    lastUpdateUser: null,
    sourceUpdateTimestamp: null,
    sourceUpdateUser: null,
    archived: false,
    ...overrides,
  };
}

test("groupAttachmentsByType groups by the resolved business type label", () => {
  const groups = groupAttachmentsByType([
    attachment({ attachmentId: 1, attachmentTypeDescription: "Subaward Agreement", fileName: "agreement.pdf" }),
    attachment({ attachmentId: 2, attachmentTypeDescription: "Invoice", fileName: "invoice.pdf" }),
    attachment({ attachmentId: 3, attachmentTypeDescription: "Subaward Agreement", fileName: "amendment-1.pdf" }),
  ]);

  assert.deepEqual(
    groups.map((g) => g.typeLabel),
    ["Invoice", "Subaward Agreement"],
  );
  const agreements = groups.find((g) => g.typeLabel === "Subaward Agreement");
  assert.equal(agreements.attachments.length, 2);
});

test("groupAttachmentsByType falls back to the raw type code only when no description was archived", () => {
  const groups = groupAttachmentsByType([
    attachment({ attachmentTypeCode: 7, attachmentTypeDescription: null }),
  ]);

  assert.equal(groups.length, 1);
  assert.equal(groups[0].typeLabel, 7);
});

test("groupAttachmentsByType puts attachments with no archived type in a trailing Unspecified group, not hidden", () => {
  const groups = groupAttachmentsByType([
    attachment({ attachmentTypeCode: null, attachmentTypeDescription: null, fileName: "mystery.pdf" }),
    attachment({ attachmentTypeDescription: "Invoice", fileName: "invoice.pdf" }),
  ]);

  assert.deepEqual(
    groups.map((g) => g.typeLabel),
    ["Invoice", "Unspecified Type"],
  );
  assert.equal(groups[1].attachments[0].fileName, "mystery.pdf");
});

test("groupAttachmentsByType sorts attachments within a group by file name", () => {
  const groups = groupAttachmentsByType([
    attachment({ attachmentTypeDescription: "Invoice", fileName: "z-invoice.pdf" }),
    attachment({ attachmentTypeDescription: "Invoice", fileName: "a-invoice.pdf" }),
  ]);

  assert.deepEqual(
    groups[0].attachments.map((a) => a.fileName),
    ["a-invoice.pdf", "z-invoice.pdf"],
  );
});
