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

export type ProgressStageKey =
  | "inProgress"
  | "fixedInCode"
  | "deployedAwaitingVerification"
  | "verified";

export interface ProgressStageMeta {
  key: ProgressStageKey;
  label: string;
  description: string;
}

export interface CaseObservation {
  on: string;
  uiBuild: string;
  apiBuild: string;
  behaviour: string;
}

export interface CaseProgress {
  stage: ProgressStageKey;
  verificationStatus?: string;
  lastObservation?: CaseObservation;
  requiredEnvironment?: string;
  change: string;
  deployedBuild: string;
  verifiedOn: string;
  verifiedIn: string;
  results: string;
  limitations: string;
}

export interface CaseHistoryEntry {
  on: string;
  stage: string;
  summary: string;
  environment: string;
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
  progress?: CaseProgress;
  history?: CaseHistoryEntry[];
}

export interface SecurityStatus {
  decisionApproved: string;
  codeImplemented: string;
  testsPassed: string;
  mergedToMain: string;
  deployed: string;
  enforcementEnabled: string;
  acceptanceVerified: string;
}

export interface SecurityRequirement {
  id: string;
  csvId: number;
  group: string;
  requirement: string;
  accessScope: string;
  audience: string;
  acceptance: string;
  businessRule: string;
  status: SecurityStatus;
  tests: string;
  blockers: string;
  evidence: string;
  decisions: string;
  conflict: string;
}

export interface SecurityModule {
  name: string;
  detail: string;
  state: string;
  evidence: string;
}

export interface SecurityStage {
  key: string;
  label: string;
  detail: string;
  tone: string;
  text: string;
}

export interface SecurityProgress {
  total: number;
  decided: number;
  implemented: number;
  merged: number;
  verified: number;
  open: number;
}

export const SECURITY_STAGES: { key: string; label: string; detail: string }[];
export function securityStageState(value: unknown): { tone: string; text: string };
export function securityStages(requirement: SecurityRequirement): SecurityStage[];
export function securityProgress(requirements: SecurityRequirement[]): SecurityProgress;
export function enforcementIsOffEverywhere(requirements: SecurityRequirement[]): boolean;
export function pendingModules(modules: SecurityModule[]): SecurityModule[];

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

export const PROGRESS_STAGES: ProgressStageMeta[];
export function progressStage(key: string): ProgressStageMeta | null;
export function isKnownProgressStage(key: string): boolean;
export function casesClaimingAnUnverifiedPass(cases: QaCase[]): QaCase[];
export function casesWithUnevidencedVerification(cases: QaCase[]): QaCase[];
export function trackedCases(cases: QaCase[]): QaCase[];
export function isPlaceholderEvidence(value: unknown): boolean;
export function requiredEnvironment(item: QaCase): string;
export function verificationShortfalls(item: QaCase): string[];
export function isRealIsoDate(value: unknown): boolean;
