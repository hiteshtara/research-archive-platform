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

export function hasAnyComments(comments, notepadEntries) {
  return comments.length > 0 || notepadEntries.length > 0;
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

export function formatAttachmentCountLabel(count) {
  return `${count} Attachment${count === 1 ? "" : "s"}`;
}
