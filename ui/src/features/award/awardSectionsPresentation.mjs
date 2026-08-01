// Pure presentation-helper functions for the Phase 2 Award dashboard
// sections (People and Units, Amounts, Terms, Comments and Notepad,
// SAP Transmission History, Attachments) - kept dependency-free, plain
// JS, and node:test-able the same way ../ai/awardAiPresentation.mjs is,
// since this project has no component-render test setup.

export function formatCurrencyAmount(amount) {
  if (amount === null || amount === undefined) {
    return "—";
  }
  return amount.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });
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
