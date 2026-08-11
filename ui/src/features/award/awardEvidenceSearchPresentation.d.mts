export const EVIDENCE_TYPE_LABELS: Readonly<Record<string, string>>;

export const EVIDENCE_TYPES: readonly string[];

export function canSubmitEvidenceSearch(
  query: string,
  pending: boolean,
): boolean;

export function toggleEvidenceType(
  selectedTypes: string[],
  type: string,
): string[];

export function evidenceTypeLabel(documentType: string): string;

export function evidenceScorePercentLabel(score: number): string;

export function evidenceSearchErrorMessage(status: number | undefined): string;

export function resultsBelongToAward(
  results: readonly { awardNumber: string }[],
  awardNumber: string,
): boolean;
