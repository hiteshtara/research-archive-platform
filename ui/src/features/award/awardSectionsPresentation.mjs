// Pure presentation-helper functions for the Phase 2 Award dashboard
// sections (People and Units, Amounts, Terms, Comments and Notepad,
// SAP Transmission History, Attachments) - kept dependency-free, plain
// JS, and node:test-able the same way ../ai/awardAiPresentation.mjs is,
// since this project has no component-render test setup.

const CURRENCY_FORMATTER = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatCurrencyAmount(amount) {
  if (amount === null || amount === undefined) {
    return "—";
  }
  return CURRENCY_FORMATTER.format(amount);
}

export function formatByteSize(bytes) {
  if (bytes === null || bytes === undefined) {
    return "Size unknown";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatCreditSplitLabel(split) {
  const label = split.creditTypeCode ?? "Credit";
  if (split.credit === null || split.credit === undefined) {
    return label;
  }
  return `${label}: ${split.credit}%`;
}

export function formatEffortNote(label, value) {
  if (value === null || value === undefined) {
    return null;
  }
  return `${label} ${value}%`;
}

// Parses the download filename from a Content-Disposition header,
// preferring the RFC 5987 filename*= form. Shared by every
// authenticated-proxy attachment download (Subaward, Award) so the
// parsing behavior itself is tested once, in one place.
export function parseDownloadFilename(contentDisposition, fallback) {
  const encoded = contentDisposition?.match(/filename\*=UTF-8''([^;]+)/i);
  if (encoded?.[1]) {
    return decodeURIComponent(encoded[1].replace(/^"|"$/g, ""));
  }
  const plain = contentDisposition?.match(/filename="?([^";]+)"?/i);
  return plain?.[1] ?? fallback;
}

// Returns the SAP transmission XML payload completely unchanged - this
// is a deliberate identity function, not a no-op left by mistake: it
// exists so callers render its return value as plain text content
// (e.g. React's default text-node escaping, or a <pre> block) and
// documents that this value must never be parsed, unescaped, or handed
// to dangerouslySetInnerHTML. See AwardSapTransmissionsSection.tsx's
// XmlViewer, which is the only renderer for this value.
export function xmlDisplayText(xml) {
  return xml ?? null;
}

export function hasAnyPeople(people) {
  return people.length > 0;
}

export function hasAnyTerms(sponsorTerms, reportTerms) {
  return sponsorTerms.length > 0 || reportTerms.length > 0;
}

// --- Terms -------------------------------------------------------------
//
// sponsorTermId (SPONSOR_TERM's own surrogate PK) and sponsorTermCode
// (the human-readable code Kuali's own UI displays) are deliberately
// different values - see AWARD_TERMS_DESIGN.md and the live-verified
// award_id 2727052 fixture (sponsorTermId 370 -> sponsorTermCode "64").
// Neither archive.sponsor_term nor archive.sponsor_term_type (V074) has
// a foreign key from award_sponsor_term, so sponsorTermCode/description
// can both be null - fall back to the bare sponsorTermId (never the
// internal awardSponsorTermId row identifier) so Kuali's raw code stays
// findable even when this archive hasn't loaded its lookup row yet.
export function resolveAwardSponsorTermLabel(term) {
  if (term.sponsorTermCode && term.description) {
    return `${term.sponsorTermCode}: ${term.description}`;
  }
  return `Sponsor Term ${term.sponsorTermId ?? term.awardSponsorTermId ?? "—"}`;
}

const UNCATEGORIZED_SPONSOR_TERM_KEY = "__uncategorized__";
const UNCATEGORIZED_SPONSOR_TERM_LABEL = "Uncategorized";

// Groups by sponsorTermTypeCode in the 10 Kuali categories' own
// authoritative numeric order (Referenced Document Terms=1 ... Special
// Award Restrictions Terms=10 - live-verified against BU Oracle
// staging 2026-08-14), never alphabetic order (which would sort "10"
// before "2"). A term whose sponsorTermTypeCode has no matching
// archive.sponsor_term_type row (or is entirely unresolved) collapses
// into a trailing "Uncategorized" group, mirroring
// groupAwardCustomData's "Other" convention, rather than one throwaway
// group per row.
export function groupAwardSponsorTerms(terms) {
  const groups = new Map();

  for (const term of terms) {
    const resolved = term.sponsorTermTypeCode && term.categoryDescription;
    const key = resolved
      ? term.sponsorTermTypeCode
      : UNCATEGORIZED_SPONSOR_TERM_KEY;
    if (!groups.has(key)) {
      groups.set(key, {
        categoryCode: resolved ? term.sponsorTermTypeCode : null,
        categoryDescription: resolved
          ? term.categoryDescription
          : UNCATEGORIZED_SPONSOR_TERM_LABEL,
        terms: [],
      });
    }
    groups.get(key).terms.push(term);
  }

  const categorized = [...groups.entries()]
    .filter(([key]) => key !== UNCATEGORIZED_SPONSOR_TERM_KEY)
    .sort(([a], [b]) => Number(a) - Number(b))
    .map(([, group]) => group);

  const uncategorized = groups.has(UNCATEGORIZED_SPONSOR_TERM_KEY)
    ? [groups.get(UNCATEGORIZED_SPONSOR_TERM_KEY)]
    : [];

  return [...categorized, ...uncategorized];
}

// Every archive.report/report_class/frequency/frequency_base/
// distribution LEFT JOIN (V074) can miss - code/description come back
// as a pair with description null in that case. Falls back to the raw
// code so it stays findable rather than showing nothing; returns null
// only when both are missing entirely, so callers can omit the field.
// "No" is a real, valid resolved archive.distribution description and
// must never be treated as blank/falsy the way an empty string is -
// this only checks for null/undefined/"", never falsiness.
export function resolveAwardReportTermFieldLabel(code, description) {
  if (description !== null && description !== undefined && description !== "") {
    return description;
  }
  if (code !== null && code !== undefined && code !== "") {
    return code;
  }
  return null;
}

export function resolveAwardReportTermHeading(term) {
  return (
    resolveAwardReportTermFieldLabel(term.reportCode, term.reportDescription) ??
    `Report term ${term.awardReportTermId ?? "—"}`
  );
}

export function resolveAwardReportTermRecipientLabel(recipient) {
  return (
    resolveAwardReportTermFieldLabel(
      recipient.contactTypeCode,
      recipient.contactTypeDescription,
    ) ?? `Recipient ${recipient.awardReportTermRecipientId ?? "—"}`
  );
}

// Renders null (never "0 days"/an empty string) when both are
// null/zero - the real, live-verified Oracle value for e.g. award_id
// 2727052's own frequencyCode "5"/"As required" is null/null, not a
// load gap - so AwardTermsSection can omit "Advance Notice" entirely
// rather than show an empty or zero-length notice period.
export function formatAdvanceNotice(days, months) {
  const parts = [];
  if (months) {
    parts.push(`${months} month${months === 1 ? "" : "s"}`);
  }
  if (days) {
    parts.push(`${days} day${days === 1 ? "" : "s"}`);
  }
  return parts.length > 0 ? parts.join(", ") : null;
}

// --- Custom Data -----------------------------------------------------
//
// A separate Award section from Terms, never merged - see
// AwardArchiveRepository.findCustomData's header comment. Mirrors
// features/proposal/proposalCustomDataPresentation.mjs's logic
// (kept as its own copy per this file's existing per-domain
// convention - see budgetPresentation.mjs/timeAndMoneyPresentation.mjs
// as prior examples of the same pattern).

const UNGROUPED_CUSTOM_DATA_LABEL = "Other";

// custom_attribute_id has no foreign key (database migration V064) -
// a row can arrive with label and name both null when Oracle has an
// attribute this archive hasn't loaded into archive.custom_attribute
// yet. Never render the bare numeric ID as the only visible text -
// fall back to name, then a synthetic label that still names the
// attribute.
export function resolveAwardCustomDataLabel(row) {
  if (row.label && row.label.trim() !== "") {
    return row.label;
  }
  if (row.name && row.name.trim() !== "") {
    return row.name;
  }
  return `Custom Field ${row.customAttributeId ?? "?"}`;
}

// Groups rows by their proven groupName, preserving each group's
// first-seen order and each row's order within its group. Rows with
// no groupName (null, or a lookup miss) collapse into a single
// "Other" group placed last, rather than one throwaway group per
// null row.
export function groupAwardCustomData(rows) {
  const groups = new Map();

  for (const row of rows) {
    const key =
      row.groupName && row.groupName.trim() !== ""
        ? row.groupName
        : UNGROUPED_CUSTOM_DATA_LABEL;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(row);
  }

  const ordered = [...groups.entries()].filter(
    ([groupName]) => groupName !== UNGROUPED_CUSTOM_DATA_LABEL,
  );
  if (groups.has(UNGROUPED_CUSTOM_DATA_LABEL)) {
    ordered.push([
      UNGROUPED_CUSTOM_DATA_LABEL,
      groups.get(UNGROUPED_CUSTOM_DATA_LABEL),
    ]);
  }

  return ordered.map(([groupName, groupRows]) => ({
    groupName,
    rows: groupRows,
  }));
}

// Case-insensitive match against the resolved label, the raw name,
// and the value - so a search box can find a field by any of the
// three, including awards where most rows lack a resolved label.
export function matchesAwardCustomDataQuery(row, query) {
  const normalizedQuery = query.trim().toLowerCase();
  if (normalizedQuery === "") {
    return true;
  }

  const haystack = [
    resolveAwardCustomDataLabel(row),
    row.name ?? "",
    row.value ?? "",
  ]
    .join(" ")
    .toLowerCase();

  return haystack.includes(normalizedQuery);
}

// commentCategories always has one entry per screen_flag='Y' comment
// type, even when this Award family has never used it (current: null,
// history: []) - so "any comments" means at least one category has a
// real current entry, not merely that the categories list is non-empty.
export function hasAnyComments(commentCategories, notepadEntries) {
  return (
    commentCategories.some((category) => category.current != null) ||
    notepadEntries.length > 0
  );
}

export function hasAnyTransmissions(transmissions) {
  return transmissions.length > 0;
}

export function hasAnyAttachments(attachments) {
  return attachments.length > 0;
}

export function hasAnyUnitContacts(unitContacts) {
  return unitContacts.length > 0;
}

export function hasAnySponsorContacts(sponsorContacts) {
  return sponsorContacts.length > 0;
}

export function hasAnyCentralAdministrationContacts(contacts) {
  return contacts.length > 0;
}

// Classifies an attachment's type from its MIME content type (preferred)
// or filename extension (fallback), returning a short display label and
// a stable category key the UI maps to an icon. Never fabricates a type
// - "File" covers anything unrecognized rather than guessing.
const CONTENT_TYPE_CATEGORIES = [
  { category: "pdf", label: "PDF", test: (type) => type === "application/pdf" },
  {
    category: "word",
    label: "DOCX",
    test: (type) =>
      type === "application/msword" ||
      type.includes("wordprocessingml"),
  },
  {
    category: "excel",
    label: "XLSX",
    test: (type) =>
      type === "application/vnd.ms-excel" || type.includes("spreadsheetml"),
  },
  {
    category: "powerpoint",
    label: "PPTX",
    test: (type) =>
      type === "application/vnd.ms-powerpoint" ||
      type.includes("presentationml"),
  },
  {
    category: "archive",
    label: "ZIP",
    test: (type) =>
      type.includes("zip") || type.includes("compressed"),
  },
  { category: "image", label: "Image", test: (type) => type.startsWith("image/") },
  { category: "text", label: "Text", test: (type) => type.startsWith("text/") },
];

const EXTENSION_LABELS = {
  pdf: { category: "pdf", label: "PDF" },
  doc: { category: "word", label: "DOC" },
  docx: { category: "word", label: "DOCX" },
  xls: { category: "excel", label: "XLS" },
  xlsx: { category: "excel", label: "XLSX" },
  ppt: { category: "powerpoint", label: "PPT" },
  pptx: { category: "powerpoint", label: "PPTX" },
  zip: { category: "archive", label: "ZIP" },
  png: { category: "image", label: "PNG" },
  jpg: { category: "image", label: "JPG" },
  jpeg: { category: "image", label: "JPEG" },
  gif: { category: "image", label: "GIF" },
  txt: { category: "text", label: "Text" },
};

export function classifyAttachmentType(contentType, fileName) {
  const normalizedType = (contentType ?? "").toLowerCase().trim();

  if (normalizedType) {
    const match = CONTENT_TYPE_CATEGORIES.find((entry) =>
      entry.test(normalizedType),
    );
    if (match) {
      return { category: match.category, label: match.label };
    }
  }

  const extension = (fileName ?? "").split(".").pop()?.toLowerCase();
  if (extension && EXTENSION_LABELS[extension]) {
    return EXTENSION_LABELS[extension];
  }

  return { category: "file", label: "File" };
}

// Human explanation for why the download control is disabled, keyed off
// archive.attachment_object.upload_status - matches the server-side
// downloadable computation in AwardArchiveRepository.findAttachments so
// the UI never claims a more specific reason than the backend actually
// enforces.
export function downloadUnavailableReason(uploadStatus) {
  switch (uploadStatus) {
    case "PENDING":
      return "This file hasn't been uploaded to storage yet.";
    case "UPLOADING":
      return "This file is currently being uploaded.";
    case "FAILED":
      return "The upload for this file failed and hasn't been retried yet.";
    case "MISSING_SOURCE_CONTENT":
      return "No source content exists for this file in the archive.";
    default:
      return "This file isn't available for download yet.";
  }
}

// Builds the client-side fallback filename for the Complete Award
// Report download - mirrors AwardV1Controller#sanitizeFilenameSegment
// exactly (Award_<award-number>_Complete_Report.pdf) so the two stay
// in lockstep even though the server's Content-Disposition header is
// what actually names the saved file in the common case.
export function buildAwardReportFileName(awardNumber) {
  const safeAwardNumber = (awardNumber ?? "Unknown").replace(
    /[^A-Za-z0-9._-]/g,
    "_",
  );
  return `Award_${safeAwardNumber}_Complete_Report.pdf`;
}

export function awardReportDownloadErrorMessage(status) {
  if (status === 404) {
    return "This Award's report could not be generated.";
  }
  return "Unable to download the Award report. Please try again.";
}

export function formatAttachmentCountLabel(count) {
  return `${count} Attachment${count === 1 ? "" : "s"}`;
}
