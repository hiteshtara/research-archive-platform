export const REPORT_ONLY: "REPORT_ONLY";
export const REPORT_WITH_ATTACHMENTS: "REPORT_WITH_ATTACHMENTS";

export type AwardReportKind =
  | typeof REPORT_ONLY
  | typeof REPORT_WITH_ATTACHMENTS;

export interface AwardReportActionState {
  label: string;
  idleLabel: string;
  disabled: boolean;
  busy: boolean;
  tooltip: string;
}

export function buildAwardReportPath(
  awardId: number | string,
  kind?: AwardReportKind,
): string;

export function resolveReportActionState(
  kind: AwardReportKind,
  downloading: boolean,
): AwardReportActionState;

export function buildAwardReportFallbackFileName(
  awardNumber: string | null | undefined,
  kind?: AwardReportKind,
): string;

export function reportDownloadErrorMessage(
  status: number,
  kind?: AwardReportKind,
): string;
