// Presentation helper for the Subaward Attachments tab - groups the
// flat archive.subaward_attachment rows by business attachment type
// instead of listing them as one undifferentiated table. Kept
// framework-free so it can be unit-tested with plain node:test,
// matching this repo's existing features/*/*.mjs convention.
//
// Grouping key mirrors the resolution already used elsewhere in this
// tab (attachmentTypeDescription, falling back to the raw code only
// when no description was ever archived for it - never blank).
// Attachments without any archived type land in a final "Unspecified
// Type" group rather than being hidden or silently merged into
// another group.

const UNSPECIFIED_LABEL = "Unspecified Type";

function resolveTypeLabel(attachment) {
  return attachment.attachmentTypeDescription ?? attachment.attachmentTypeCode ?? null;
}

export function groupAttachmentsByType(attachments) {
  const groups = new Map();

  for (const attachment of attachments) {
    const label = resolveTypeLabel(attachment) ?? UNSPECIFIED_LABEL;
    if (!groups.has(label)) {
      groups.set(label, []);
    }
    groups.get(label).push(attachment);
  }

  const sortedLabels = [...groups.keys()].sort((a, b) => {
    if (a === UNSPECIFIED_LABEL) return 1;
    if (b === UNSPECIFIED_LABEL) return -1;
    return a.localeCompare(b);
  });

  return sortedLabels.map((typeLabel) => ({
    typeLabel,
    attachments: [...groups.get(typeLabel)].sort((a, b) => {
      const aName = a.fileName ?? "";
      const bName = b.fileName ?? "";
      return aName.localeCompare(bName);
    }),
  }));
}
