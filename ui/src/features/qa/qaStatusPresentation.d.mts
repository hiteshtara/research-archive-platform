export type QaStatusKey =
  | "passed"
  | "evidence"
  | "issue"
  | "decision"
  | "blocked"
  | "notTested";

export interface QaStatusMeta {
  key: QaStatusKey;
  label: string;
  chipColor: "success" | "warning" | "error" | "info" | "default";
  description: string;
}

export interface QaCase {
  id: string;
  category: string;
  title: string;
  steps: string;
  expected: string;
  priority: string;
  status: QaStatusKey;
  note: string;
  scope: string;
  environment: string;
}

export interface SecurityRequirement {
  id: string;
  csvId: number;
  group: string;
  requirement: string;
  accessScope: string;
  audience: string;
  acceptance: string;
  implementation: string;
  deployment: string;
  verification: string;
  decisions: string;
  conflict: string;
  recommendation: string;
}

export interface SecuritySummary {
  total: number;
  verified: number;
  conflicts: number;
  headline: string;
}

export interface CaseFilter {
  search?: string;
  status?: QaStatusKey | "all";
  area?: string;
}

export const STATUS_META: QaStatusMeta[];

export function statusMeta(key: string): QaStatusMeta | null;
export function isKnownStatus(key: string): boolean;
export function countByStatus(cases: QaCase[]): Record<QaStatusKey, number>;
export function openCaseCount(cases: QaCase[]): number;
export function caseAreas(cases: QaCase[]): string[];
export function filterCases(cases: QaCase[], filter?: CaseFilter): QaCase[];
export function resultsLabel(visibleCount: number, totalCount: number): string;
export function deployedEvidenceCount(cases: QaCase[]): number;
export function scopesPresent(cases: QaCase[]): string[];
export function securitySummary(requirements: SecurityRequirement[]): SecuritySummary;
export function requirementsWithConflicts(
  requirements: SecurityRequirement[],
): SecurityRequirement[];
