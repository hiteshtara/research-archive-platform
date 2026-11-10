export type AccessState =
  | "NOT_PROVISIONED"
  | "IDENTITY_DENIED"
  | "REAUTHENTICATION_REQUIRED"
  | "NOT_FOUND"
  | "OTHER";

export interface ApiErrorLike {
  status?: number;
  code?: string;
  correlationId?: string;
}

export interface AccessNoticeContent {
  state: AccessState;
  severity: "info" | "warning" | "error";
  title: string;
  body: string;
  action: string | null;
  retryable: boolean;
}

export const ACCESS_STATES: Record<AccessState, AccessState>;
export function classifyAccessError(error: ApiErrorLike | null): AccessState;
export function accessNotice(
  error: ApiErrorLike | null,
  options?: { recordLabel?: string },
): AccessNoticeContent;
export function shouldReplaceEmptyState(error: ApiErrorLike | null): boolean;
export function supportReferenceFor(error: ApiErrorLike | null): string | null;
