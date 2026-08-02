import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  classifyAttachmentType,
  downloadUnavailableReason,
  formatAttachmentCountLabel,
  formatByteSize,
  formatCreditSplitLabel,
  formatCurrencyAmount,
  formatEffortNote,
  hasAnyAttachments,
  hasAnyComments,
  hasAnyPeople,
  hasAnyTerms,
  hasAnyTransmissions,
  parseDownloadFilename,
  xmlDisplayText,
} from "./awardSectionsPresentation.mjs";

test("formats currency amounts and treats null as an em dash", () => {
  assert.equal(formatCurrencyAmount(1200), "$1,200");
  assert.equal(formatCurrencyAmount(0), "$0");
  assert.equal(formatCurrencyAmount(null), "—");
});

test("formats byte sizes across B/KB/MB and treats null as unknown", () => {
  assert.equal(formatByteSize(500), "500 B");
  assert.equal(formatByteSize(2048), "2.0 KB");
  assert.equal(formatByteSize(5 * 1024 * 1024), "5.0 MB");
  assert.equal(formatByteSize(null), "Size unknown");
});

test("formats a credit split label, falling back when credit is missing", () => {
  assert.equal(
    formatCreditSplitLabel({ creditTypeCode: "PROJECT", credit: 50 }),
    "PROJECT: 50%",
  );
  assert.equal(
    formatCreditSplitLabel({ creditTypeCode: "PROJECT", credit: null }),
    "PROJECT",
  );
  assert.equal(
    formatCreditSplitLabel({ creditTypeCode: null, credit: null }),
    "Credit",
  );
});

test("formats an effort note only when a value is present", () => {
  assert.equal(formatEffortNote("Academic year", 25), "Academic year 25%");
  assert.equal(formatEffortNote("Academic year", null), null);
});

test("parses the RFC 5987 filename*= form first", () => {
  assert.equal(
    parseDownloadFilename(
      "attachment; filename*=UTF-8''budget%20justification.pdf",
      "fallback.bin",
    ),
    "budget justification.pdf",
  );
});

test("falls back to the plain filename= form when filename*= is absent", () => {
  assert.equal(
    parseDownloadFilename('attachment; filename="budget.pdf"', "fallback.bin"),
    "budget.pdf",
  );
});

test("falls back to the caller-supplied name when no header is present", () => {
  assert.equal(parseDownloadFilename(null, "fallback.bin"), "fallback.bin");
  assert.equal(parseDownloadFilename("attachment", "fallback.bin"), "fallback.bin");
});

test("returns SAP transmission XML completely unchanged, never parsed", () => {
  const maliciousLookingPayload =
    '<Envelope><script>alert("xss")</script>&amp;<tag attr="v"/></Envelope>';

  assert.equal(xmlDisplayText(maliciousLookingPayload), maliciousLookingPayload);
  assert.equal(xmlDisplayText(null), null);
});

test("AwardSapTransmissionsSection never uses dangerouslySetInnerHTML", () => {
  const sourcePath = fileURLToPath(
    new URL(
      "../../components/award/AwardSapTransmissionsSection.tsx",
      import.meta.url,
    ),
  );
  const source = readFileSync(sourcePath, "utf8");

  assert.equal(
    /dangerouslySetInnerHTML\s*=/.test(source),
    false,
    "sent/returned XML must render as text content, never as raw HTML",
  );
});

test("empty-state helpers report empty when every list is empty", () => {
  assert.equal(hasAnyPeople([]), false);
  assert.equal(hasAnyTerms([], []), false);
  assert.equal(hasAnyComments([], []), false);
  assert.equal(hasAnyTransmissions([]), false);
  assert.equal(hasAnyAttachments([]), false);
});

test("empty-state helpers report non-empty when any one list has rows", () => {
  assert.equal(hasAnyPeople([{}]), true);
  assert.equal(hasAnyTerms([{}], []), true);
  assert.equal(hasAnyTerms([], [{}]), true);
  assert.equal(hasAnyComments([{}], []), true);
  assert.equal(hasAnyComments([], [{}]), true);
  assert.equal(hasAnyTransmissions([{}]), true);
  assert.equal(hasAnyAttachments([{}]), true);
});

test("classifies attachment type from MIME content type first", () => {
  assert.deepEqual(classifyAttachmentType("application/pdf", "x.bin"), {
    category: "pdf",
    label: "PDF",
  });
  assert.deepEqual(
    classifyAttachmentType(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      null,
    ),
    { category: "word", label: "DOCX" },
  );
  assert.deepEqual(
    classifyAttachmentType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      null,
    ),
    { category: "excel", label: "XLSX" },
  );
  assert.deepEqual(classifyAttachmentType("image/png", null), {
    category: "image",
    label: "Image",
  });
});

test("classifies attachment type from the filename extension when content type is missing", () => {
  assert.deepEqual(classifyAttachmentType(null, "budget.xlsx"), {
    category: "excel",
    label: "XLSX",
  });
  assert.deepEqual(classifyAttachmentType("", "report.PDF"), {
    category: "pdf",
    label: "PDF",
  });
});

test("classifies an unrecognized type as a generic File rather than guessing", () => {
  assert.deepEqual(
    classifyAttachmentType("application/octet-stream", "data.xyz"),
    { category: "file", label: "File" },
  );
  assert.deepEqual(classifyAttachmentType(null, null), {
    category: "file",
    label: "File",
  });
});

test("explains why download is unavailable, matching the server's upload_status", () => {
  assert.equal(
    downloadUnavailableReason("PENDING"),
    "This file hasn't been uploaded to storage yet.",
  );
  assert.equal(
    downloadUnavailableReason("FAILED"),
    "The upload for this file failed and hasn't been retried yet.",
  );
  assert.equal(
    downloadUnavailableReason("MISSING_SOURCE_CONTENT"),
    "No source content exists for this file in the archive.",
  );
  assert.equal(
    downloadUnavailableReason(null),
    "This file isn't available for download yet.",
  );
});

test("formats the attachment count badge with correct pluralization", () => {
  assert.equal(formatAttachmentCountLabel(0), "0 Attachments");
  assert.equal(formatAttachmentCountLabel(1), "1 Attachment");
  assert.equal(formatAttachmentCountLabel(24), "24 Attachments");
});
