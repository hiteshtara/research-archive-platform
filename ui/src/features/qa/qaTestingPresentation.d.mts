export interface TesterCase {
  id: string;
  category: string;
  title: string;
  steps: string;
  expected: string;
  priority: string;
  status: string;
  note?: string;
  scope?: string;
  environment?: string;
}

export interface TesterStatus {
  key: string;
  chipColor: "success" | "warning" | "error" | "info" | "default";
  headline: string;
  plain: string;
}

export const TESTER_STATUS_TEXT: Record<string, { headline: string; plain: string }>;
export function testerStatus(key: string): TesterStatus;
export function testerStatusGuide(): TesterStatus[];
export function attentionCases(cases: TesterCase[]): TesterCase[];
export function coordinatedCases(cases: TesterCase[]): TesterCase[];
export function needsCoordination(item: TesterCase | null): boolean;
export function testerEnvironment(
  item: TesterCase | null,
): { site: string | null; testedOn: string | null };
export function extractTestedOn(environment: string | null): string | null;
export function moduleOptions(cases: TesterCase[]): string[];
export function applyFilters(
  cases: TesterCase[],
  filters?: { caseId?: string; status?: string; module?: string },
): TesterCase[];
export function summary(cases: TesterCase[]): {
  total: number;
  counts: Record<string, number>;
  open: number;
  coordinated: number;
};
export function containsEngineeringDetail(text: string | null): boolean;

export function testerProgress(item: TesterCase | null): {
  stage: string | null;
  verifiedOn: string | null;
  verifiedIn: string | null;
  requiredEnvironment: string | null;
  results: string | null;
  limitations: string | null;
} | null;
export function testerHistory(
  item: TesterCase | null,
): { on: string | null; stage: string | null; summary: string | null }[];
export function withoutBuildDetail(text: string | null): string | null;
