import type { AwardSummaryV1 } from "../../types/api";

export const EMPTY: string;

export interface AwardSummaryField {
  label: string;
  value: string;
  caption?: string;
}

export interface AwardSummaryGroup {
  title: string;
  fields: AwardSummaryField[];
}

export function displayValue(value: unknown): string;

export function buildAwardSummaryGroups(
  summary: AwardSummaryV1 | null | undefined,
): AwardSummaryGroup[];

export const RETIRED_SUMMARY_LABELS: readonly string[];
