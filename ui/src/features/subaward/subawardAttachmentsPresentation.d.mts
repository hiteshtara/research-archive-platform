import type { SubawardAttachment } from "../../types/api";

export interface AttachmentTypeGroup {
  typeLabel: string;
  attachments: SubawardAttachment[];
}

export function groupAttachmentsByType(
  attachments: SubawardAttachment[],
): AttachmentTypeGroup[];
