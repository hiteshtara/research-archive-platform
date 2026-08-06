import type { SubawardAmount } from "../../types/api";

export function isPlausibleMimeType(value: unknown): boolean;

export function resolveAttachmentLabel(row: SubawardAmount): string | null;

export interface AmendmentTimelineCard {
  subawardAmountInfoId: number;
  amendmentNumber: string | null;
  modificationType: string | null;
  effectiveDate: string | null;
  budgetPeriodStart: string | null;
  budgetPeriodEnd: string | null;
  obligatedChange: number | null;
  anticipatedChange: number | null;
  comments: string | null;
  attachmentLabel: string | null;
}

export function buildAmendmentTimeline(
  amountRows: SubawardAmount[],
): AmendmentTimelineCard[];

export function sumAmendmentTotals(amountRows: SubawardAmount[]): {
  totalObligatedChange: number;
  totalAnticipatedChange: number;
};
