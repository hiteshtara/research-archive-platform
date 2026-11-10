export interface QaOpenFinding {
  caseId: string;
  openedOn: string;
  headline: string;
  whatWasVerified: string;
  whatRemainsOpen: string;
  evidence: string;
  blockedBy: string;
  whatWouldCloseIt: string;
}

export interface CurrentQaCase {
  id: string;
  status: string;
  recordedStatus: string;
  currentStatus: string;
  reopened: boolean;
  finding: QaOpenFinding | null;
  [key: string]: unknown;
}

export function openFindingFor(
  caseId: string,
  findings?: QaOpenFinding[],
): QaOpenFinding | null;

export function currentQaStatusFor(
  recordedCase: unknown,
  findings?: QaOpenFinding[],
): CurrentQaCase;

export function currentQaCases(
  cases?: unknown[],
  findings?: QaOpenFinding[],
): CurrentQaCase[];

export function orphanedFindings(
  cases?: unknown[],
  findings?: QaOpenFinding[],
): QaOpenFinding[];

export function reopenedNotice(finding: QaOpenFinding | null): {
  headline: string;
  verified: string;
  open: string;
  evidence: string;
  nextStep: string;
  openedOn: string;
} | null;

export function currentQaTotals(derived?: CurrentQaCase[]): {
  total: number;
  currentlyPassed: number;
  reopened: number;
  open: number;
};
