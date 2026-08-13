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
  hasAnyCentralAdministrationContacts,
  hasAnyComments,
  hasAnyPeople,
  hasAnySponsorContacts,
  hasAnyTerms,
  hasAnyTransmissions,
  hasAnyUnitContacts,
  groupAwardCustomData,
  matchesAwardCustomDataQuery,
  parseDownloadFilename,
  resolveAwardCustomDataLabel,
  xmlDisplayText,
} from "./awardSectionsPresentation.mjs";

test("formats currency amounts to the cent and treats null as an em dash", () => {
  assert.equal(formatCurrencyAmount(17551.63), "$17,551.63");
  assert.equal(formatCurrencyAmount(0), "$0.00");
  assert.equal(formatCurrencyAmount(-1250.5), "-$1,250.50");
  assert.equal(formatCurrencyAmount(null), "—");
  assert.equal(formatCurrencyAmount(undefined), "—");
  assert.equal(formatCurrencyAmount(1000000.01), "$1,000,000.01");
});

test("Award Summary and Time & Money both render amounts through the one shared currency formatter", () => {
  const sharedModuleSource = readFileSync(
    fileURLToPath(new URL("./awardSectionsPresentation.mjs", import.meta.url)),
    "utf8",
  );
  const summarySource = readFileSync(
    fileURLToPath(
      new URL("../../components/award/AwardSummarySection.tsx", import.meta.url),
    ),
    "utf8",
  );
  const timeAndMoneySource = readFileSync(
    fileURLToPath(
      new URL(
        "../../components/award/AwardTimeAndMoneySection.tsx",
        import.meta.url,
      ),
    ),
    "utf8",
  );
  const hierarchySource = readFileSync(
    fileURLToPath(
      new URL("../../components/award/AwardHierarchyTree.tsx", import.meta.url),
    ),
    "utf8",
  );

  // The shared module must own the only maximumFractionDigits override for
  // money - this is the exact code path that previously truncated cents.
  assert.equal(
    (sharedModuleSource.match(/maximumFractionDigits/g) ?? []).length,
    1,
    "the shared module should define the currency formatter exactly once",
  );

  for (const [label, source] of [
    ["AwardSummarySection", summarySource],
    ["AwardTimeAndMoneySection", timeAndMoneySource],
    ["AwardHierarchyTree", hierarchySource],
  ]) {
    assert.match(
      source,
      /from\s+"..\/..\/features\/award\/awardSectionsPresentation\.mjs"/,
      `${label} must import its currency formatter from the shared module`,
    );
    assert.equal(
      /maximumFractionDigits/.test(source),
      false,
      `${label} must not redefine its own currency formatting`,
    );
  }
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
  assert.equal(hasAnyTransmissions([]), false);
  assert.equal(hasAnyAttachments([]), false);
  assert.equal(hasAnyUnitContacts([]), false);
  assert.equal(hasAnySponsorContacts([]), false);
  assert.equal(hasAnyCentralAdministrationContacts([]), false);
});

test("empty-state helpers report non-empty when any one list has rows", () => {
  assert.equal(hasAnyPeople([{}]), true);
  assert.equal(hasAnyTerms([{}], []), true);
  assert.equal(hasAnyTerms([], [{}]), true);
  assert.equal(hasAnyTransmissions([{}]), true);
  assert.equal(hasAnyAttachments([{}]), true);
  assert.equal(hasAnyUnitContacts([{}]), true);
  assert.equal(hasAnySponsorContacts([{}]), true);
  assert.equal(hasAnyCentralAdministrationContacts([{}]), true);
});

test("hasAnyComments treats categories with no current entry as empty, even when the category itself exists", () => {
  assert.equal(hasAnyComments([], []), false);
  assert.equal(
    hasAnyComments(
      [{ commentTypeCode: "3", current: null, history: [] }],
      [],
    ),
    false,
    "a category with no real comments (current: null) is not \"any comments\"",
  );
  assert.equal(
    hasAnyComments(
      [{ commentTypeCode: "2", current: { commentText: "text" }, history: [] }],
      [],
    ),
    true,
  );
  assert.equal(hasAnyComments([], [{}]), true);
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

// --- Custom Data -------------------------------------------------------
//
// A separate Award section from Terms, never merged - see
// AwardArchiveRepository.findCustomData's header comment.

test("resolveAwardCustomDataLabel prefers the resolved label", () => {
  const row = {
    customAttributeId: 480,
    label: "Submitted Date",
    name: "ip_submission_date",
    value: "08/09/2011",
  };

  assert.equal(resolveAwardCustomDataLabel(row), "Submitted Date");
});

test("resolveAwardCustomDataLabel falls back to name when label is missing", () => {
  const row = {
    customAttributeId: 1214,
    label: null,
    name: "OppNum",
    value: null,
  };

  assert.equal(resolveAwardCustomDataLabel(row), "OppNum");
});

test("resolveAwardCustomDataLabel never renders only the bare custom-attribute ID", () => {
  const row = {
    customAttributeId: 424242,
    label: null,
    name: null,
    value: "some value",
  };

  const label = resolveAwardCustomDataLabel(row);

  assert.notEqual(label, "424242");
  assert.match(label, /424242/);
  assert.ok(label.length > String(424242).length);
});

test("groupAwardCustomData groups by the proven groupName, preserving row order", () => {
  const rows = [
    { customAttributeId: 1, groupName: "Sponsor Info", value: "a" },
    { customAttributeId: 2, groupName: "Sponsor Info", value: "b" },
    { customAttributeId: 3, groupName: "Compliance", value: "c" },
  ];

  const grouped = groupAwardCustomData(rows);

  assert.deepEqual(
    grouped.map((group) => group.groupName),
    ["Sponsor Info", "Compliance"],
  );
  assert.equal(grouped[0].rows.length, 2);
  assert.equal(grouped[0].rows[0].customAttributeId, 1);
});

test("groupAwardCustomData collapses rows with no groupName into one trailing 'Other' group", () => {
  const rows = [
    { customAttributeId: 1, groupName: null, value: "a" },
    { customAttributeId: 2, groupName: "Sponsor Info", value: "b" },
    { customAttributeId: 3, groupName: null, value: "c" },
  ];

  const grouped = groupAwardCustomData(rows);

  assert.deepEqual(
    grouped.map((group) => group.groupName),
    ["Sponsor Info", "Other"],
  );
  assert.equal(grouped[1].rows.length, 2);
});

test("matchesAwardCustomDataQuery matches against the resolved label", () => {
  const row = {
    customAttributeId: 480,
    label: "Submitted Date",
    name: "ip_submission_date",
    value: "08/09/2011",
  };

  assert.equal(matchesAwardCustomDataQuery(row, "submitted"), true);
  assert.equal(matchesAwardCustomDataQuery(row, "unrelated"), false);
});

test("matchesAwardCustomDataQuery treats a blank query as matching everything", () => {
  const row = {
    customAttributeId: 480,
    label: null,
    name: null,
    value: null,
  };

  assert.equal(matchesAwardCustomDataQuery(row, ""), true);
  assert.equal(matchesAwardCustomDataQuery(row, "   "), true);
});

test("matchesAwardCustomDataQuery does not blow up on a real persisted blank value", () => {
  const row = {
    customAttributeId: 1209,
    label: "Opportunity Title",
    name: "OppTitle",
    value: null,
  };

  assert.equal(matchesAwardCustomDataQuery(row, "opportunity"), true);
  assert.equal(matchesAwardCustomDataQuery(row, "nonsense"), false);
});
