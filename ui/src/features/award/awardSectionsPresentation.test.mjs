import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  classifyAttachmentType,
  downloadUnavailableReason,
  formatAdvanceNotice,
  formatAttachmentCountLabel,
  formatByteSize,
  formatCreditSplitLabel,
  formatCurrencyAmount,
  formatEffortNote,
  groupAwardSponsorTerms,
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
  resolveAwardReportTermFieldLabel,
  resolveAwardReportTermHeading,
  resolveAwardReportTermRecipientLabel,
  resolveAwardSponsorTermLabel,
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

// --- Terms ---------------------------------------------------------
//
// Sponsor Term fixture rows below are the real, live-verified
// award_sponsor_term rows for award_id 2727052 (12 rows across all 10
// Kuali categories, Prior Approval and Property each having 2) - see
// AWARD_TERMS_DESIGN.md.

const REAL_SPONSOR_TERMS_2727052 = [
  {
    awardSponsorTermId: 2479168, sponsorTermId: 375, sponsorTermCode: "69",
    description: "Converted Record.  Please refer to sponsor award documentation for any Reference Document terms.",
    sponsorTermTypeCode: "1", categoryDescription: "Referenced Document Terms",
  },
  {
    awardSponsorTermId: 2479172, sponsorTermId: 379, sponsorTermCode: "73",
    description: "Converted Record.  Please refer to sponsor award documentation for any Special Award Restriction terms.",
    sponsorTermTypeCode: "10", categoryDescription: "Special Award Restrictions Terms",
  },
  {
    awardSponsorTermId: 2479164, sponsorTermId: 371, sponsorTermCode: "65",
    description: "Converted Record.  Please refer to sponsor award documentation for any Invention terms.",
    sponsorTermTypeCode: "2", categoryDescription: "Invention Terms",
  },
  {
    awardSponsorTermId: 2479165, sponsorTermId: 372, sponsorTermCode: "66",
    description: "Converted Record.  Please refer to sponsor award documentation for any Prior Approval terms.",
    sponsorTermTypeCode: "3", categoryDescription: "Prior Approval Terms",
  },
  {
    awardSponsorTermId: 2479174, sponsorTermId: 456, sponsorTermCode: "150",
    description: "No-cost extension requires Sponsor prior approval",
    sponsorTermTypeCode: "3", categoryDescription: "Prior Approval Terms",
  },
  {
    awardSponsorTermId: 2479166, sponsorTermId: 373, sponsorTermCode: "67",
    description: "Converted Record.  Please refer to sponsor award documentation for any Property terms.",
    sponsorTermTypeCode: "4", categoryDescription: "Property Terms",
  },
  {
    awardSponsorTermId: 2479173, sponsorTermId: 420, sponsorTermCode: "114",
    description: "Property Owned by BU",
    sponsorTermTypeCode: "4", categoryDescription: "Property Terms",
  },
  {
    awardSponsorTermId: 2479167, sponsorTermId: 374, sponsorTermCode: "68",
    description: "Converted Record.  Please refer to sponsor award documentation for any Publication terms.",
    sponsorTermTypeCode: "5", categoryDescription: "Publication Terms",
  },
  {
    awardSponsorTermId: 2479163, sponsorTermId: 370, sponsorTermCode: "64",
    description: "Converted Record.  Please refer to sponsor award documentation for any Equipment Approval terms.",
    sponsorTermTypeCode: "6", categoryDescription: "Equipment Approval Terms",
  },
  {
    awardSponsorTermId: 2479169, sponsorTermId: 376, sponsorTermCode: "70",
    description: "Converted Record.  Please refer to sponsor award documentation for any Rights in Data Terms terms.",
    sponsorTermTypeCode: "7", categoryDescription: "Rights In Data Terms",
  },
  {
    awardSponsorTermId: 2479170, sponsorTermId: 377, sponsorTermCode: "71",
    description: "Converted Record.  Please refer to sponsor award documentation for any Subaward Approval terms.",
    sponsorTermTypeCode: "8", categoryDescription: "Subaward Approval Terms",
  },
  {
    awardSponsorTermId: 2479171, sponsorTermId: 378, sponsorTermCode: "72",
    description: "Converted Record.  Please refer to sponsor award documentation for any Travel Restriction terms.",
    sponsorTermTypeCode: "9", categoryDescription: "Travel Restrictions Terms",
  },
];

test("resolveAwardSponsorTermLabel renders the code and full description when resolved", () => {
  const label = resolveAwardSponsorTermLabel(REAL_SPONSOR_TERMS_2727052[8]);

  assert.equal(
    label,
    "64: Converted Record.  Please refer to sponsor award documentation for any Equipment Approval terms.",
  );
});

test("resolveAwardSponsorTermLabel falls back to the raw sponsorTermId when unresolved", () => {
  const term = {
    awardSponsorTermId: 1,
    sponsorTermId: 370,
    sponsorTermCode: null,
    description: null,
    sponsorTermTypeCode: null,
    categoryDescription: null,
  };

  assert.equal(resolveAwardSponsorTermLabel(term), "Sponsor Term 370");
});

test("groupAwardSponsorTerms reproduces the real award_id 2727052 fixture: 10 categories in authoritative numeric order, Prior Approval and Property each with 2", () => {
  const grouped = groupAwardSponsorTerms(REAL_SPONSOR_TERMS_2727052);

  assert.equal(grouped.length, 10);
  assert.deepEqual(
    grouped.map((group) => group.categoryDescription),
    [
      "Referenced Document Terms",
      "Invention Terms",
      "Prior Approval Terms",
      "Property Terms",
      "Publication Terms",
      "Equipment Approval Terms",
      "Rights In Data Terms",
      "Subaward Approval Terms",
      "Travel Restrictions Terms",
      "Special Award Restrictions Terms",
    ],
    "codes 1..10 in numeric order, not alphabetic (which would put \"10\" before \"2\")",
  );

  const counts = Object.fromEntries(
    grouped.map((group) => [group.categoryDescription, group.terms.length]),
  );
  assert.equal(counts["Prior Approval Terms"], 2);
  assert.equal(counts["Property Terms"], 2);
  assert.equal(counts["Referenced Document Terms"], 1);
  assert.equal(counts["Equipment Approval Terms"], 1);
});

test("groupAwardSponsorTerms collapses unresolved terms into one trailing 'Uncategorized' group", () => {
  const terms = [
    ...REAL_SPONSOR_TERMS_2727052.slice(0, 2),
    {
      awardSponsorTermId: 999,
      sponsorTermId: 88888,
      sponsorTermCode: null,
      description: null,
      sponsorTermTypeCode: null,
      categoryDescription: null,
    },
  ];

  const grouped = groupAwardSponsorTerms(terms);

  assert.equal(grouped.at(-1).categoryDescription, "Uncategorized");
  assert.equal(grouped.at(-1).categoryCode, null);
  assert.equal(grouped.at(-1).terms.length, 1);
});

// Report Term fixture values below are the real, live-verified
// award_report_term rows for award_id 2727052 (award_report_term_id
// 2727057/2727058) - see AWARD_TERMS_DESIGN.md. AWARD_REP_TERMS_RECNT
// is genuinely empty archive-wide, so both report terms have zero
// recipients - a real Oracle fact, not a load gap.

test("resolveAwardReportTermFieldLabel prefers the resolved description", () => {
  assert.equal(
    resolveAwardReportTermFieldLabel("43", "Converted Record  - See Sponsor Documentation"),
    "Converted Record  - See Sponsor Documentation",
  );
});

test("resolveAwardReportTermFieldLabel falls back to the raw code when unresolved", () => {
  assert.equal(resolveAwardReportTermFieldLabel("43", null), "43");
});

test("resolveAwardReportTermFieldLabel returns null only when both code and description are missing", () => {
  assert.equal(resolveAwardReportTermFieldLabel(null, null), null);
  assert.equal(resolveAwardReportTermFieldLabel(null, undefined), null);
});

test("resolveAwardReportTermFieldLabel treats the real distribution value \"No\" as a valid resolved value, never as blank", () => {
  assert.equal(resolveAwardReportTermFieldLabel("2", "No"), "No");
});

test("resolveAwardReportTermHeading uses the resolved report description", () => {
  const term = {
    awardReportTermId: 2727057,
    reportCode: "43",
    reportDescription: "Converted Record  - See Sponsor Documentation",
  };

  assert.equal(
    resolveAwardReportTermHeading(term),
    "Converted Record  - See Sponsor Documentation",
  );
});

test("resolveAwardReportTermHeading falls back to a synthetic heading, never the bare row ID alone, when unresolved", () => {
  const term = {
    awardReportTermId: 2727057,
    reportCode: null,
    reportDescription: null,
  };

  const heading = resolveAwardReportTermHeading(term);

  assert.notEqual(heading, "2727057");
  assert.match(heading, /2727057/);
});

test("resolveAwardReportTermRecipientLabel prefers the resolved contact type description", () => {
  const recipient = {
    awardReportTermRecipientId: 900,
    contactTypeCode: "34",
    contactTypeDescription: "Administrative Contact",
  };

  assert.equal(
    resolveAwardReportTermRecipientLabel(recipient),
    "Administrative Contact",
  );
});

test("formatAdvanceNotice returns null when both days and months are null, the real fixture value for award_id 2727052's own frequencyCode \"5\"", () => {
  assert.equal(formatAdvanceNotice(null, null), null);
});

test("formatAdvanceNotice formats days and months together with correct pluralization", () => {
  assert.equal(formatAdvanceNotice(1, null), "1 day");
  assert.equal(formatAdvanceNotice(30, null), "30 days");
  assert.equal(formatAdvanceNotice(null, 1), "1 month");
  assert.equal(formatAdvanceNotice(null, 3), "3 months");
  assert.equal(formatAdvanceNotice(15, 1), "1 month, 15 days");
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
