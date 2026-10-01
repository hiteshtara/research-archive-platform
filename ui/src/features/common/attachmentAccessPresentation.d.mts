export const ATTACHMENT_VIEWER_GROUP: string;
export function attachmentControlsAvailable(
  accessStatus: { mode?: string; problem?: string | null } | null | undefined,
  groups: unknown,
): boolean;
