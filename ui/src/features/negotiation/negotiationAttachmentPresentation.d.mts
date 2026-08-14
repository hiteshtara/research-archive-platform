export function resolveRestrictedLabel(
  restrictedFlag: string | null | undefined,
): string;

interface AttachmentLike {
  activityId?: number | null;
  oracleAttachmentId?: number | null;
  oracleFileId?: string | null;
  description?: string | null;
}

export function resolveAttachmentDisplayLabel(
  attachment: AttachmentLike | null | undefined,
): string;

export function resolveAttachmentIdentifierSummary(
  attachment: AttachmentLike | null | undefined,
): string;
