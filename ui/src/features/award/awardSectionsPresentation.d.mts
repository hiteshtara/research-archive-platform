import type { AwardCreditSplitV1 } from "../../types/api";

export function formatCurrencyAmount(amount: number | null): string;

export function formatByteSize(bytes: number | null): string;

export function formatCreditSplitLabel(split: AwardCreditSplitV1): string;

export function formatEffortNote(
  label: string,
  value: number | null,
): string | null;

export function parseDownloadFilename(
  contentDisposition: string | null,
  fallback: string,
): string;

export function xmlDisplayText(xml: string | null): string | null;

export function hasAnyPeople(people: unknown[]): boolean;

export function hasAnyTerms(
  sponsorTerms: unknown[],
  reportTerms: unknown[],
): boolean;

export function hasAnyComments(
  comments: unknown[],
  notepadEntries: unknown[],
): boolean;

export function hasAnyTransmissions(transmissions: unknown[]): boolean;

export function hasAnyAttachments(attachments: unknown[]): boolean;

export type AttachmentTypeCategory =
  | "pdf"
  | "word"
  | "excel"
  | "powerpoint"
  | "archive"
  | "image"
  | "text"
  | "file";

export function classifyAttachmentType(
  contentType: string | null,
  fileName: string | null,
): { category: AttachmentTypeCategory; label: string };

export function downloadUnavailableReason(
  uploadStatus: string | null,
): string;

export function formatAttachmentCountLabel(count: number): string;
