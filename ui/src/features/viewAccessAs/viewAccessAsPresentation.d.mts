/*
 * Types for the temporary View Access As QA route. DEVELOPMENT ONLY — see
 * docs/VIEW_ACCESS_AS_PAGE.md.
 *
 * These describe what the local test-scope harness returns. They are not
 * production API types and nothing in src/types/api.ts refers to them.
 */

export type Verdict = "PERMIT" | "DENY" | "INDETERMINATE";
export type FamilyReadVerdict = "ALLOWED" | "REFUSED";
export type VersionScope = "PER_VERSION" | "FAMILY_WIDE";
export type DepartmentMatch = "EXACT_LEAD_UNIT" | "LEAD_UNIT_WITH_DESCENDANTS";

export interface VersionVerdict {
  versionKey: string;
  verdict: Verdict;
  /** The engine's own rule or denial code. Never computed in the UI. */
  rule: string;
}

export interface FamilyResult {
  familyKey: string;
  label: string;
  leadUnit: string;
  versions: VersionVerdict[];
  permitted: number;
  denied: number;
  partialFamily: boolean;
  /** Computed by evaluating the same profile under both version scopes. */
  changesUnderOtherScope: boolean;
  otherScope: VersionScope;
  aiVerdict: FamilyReadVerdict;
  aiRule: string;
}

export interface ModuleResult {
  id: number;
  label: string;
  verdict: Verdict;
  rule: string;
}

export interface ProfileResult {
  profile: string;
  description: string;
  grants: string[];
  outcome: string;
  awards: FamilyResult[];
  subawards: ModuleResult[];
  negotiations: ModuleResult[];
  renderedSql: Record<string, string>;
}

export interface EngineProvenance {
  evaluator: string;
  service: string;
  sqlBuilder: string;
  loadedFrom: string;
  serviceLoadedFrom: string;
}

export interface HarnessModel {
  profiles: { name: string; description: string }[];
  families: { familyKey: string; label: string; leadUnit: string; versionKeys: string[] }[];
  versionScopes: VersionScope[];
  departmentMatches: DepartmentMatch[];
  engine: EngineProvenance;
}

export interface EvaluationResponse {
  settings: { versionScope: VersionScope; departmentMatch: DepartmentMatch };
  evaluatedAt: string;
  engine: EngineProvenance;
  results: ProfileResult[];
}

export interface ComparisonRow {
  id: number;
  label: string;
  rule: string;
  verdicts: { profile: string; verdict: Verdict }[];
}

export interface HarnessUnavailable {
  title: string;
  detail: string;
  command: string;
}

export const HARNESS_ORIGIN: string;
export const HARNESS_START_COMMAND: string;

export function harnessUrl(path: string): string;
export function harnessUnavailableMessage(): HarnessUnavailable;
export function verdictTone(
  verdict: string,
): "success" | "warning" | "error" | "default";
export function familyFor(result: ProfileResult | null, familyKey: string): FamilyResult | null;
export function comparisonRows(
  results: ProfileResult[],
  key: "subawards" | "negotiations",
): ComparisonRow[];
export function familyTally(
  family: FamilyResult | null,
): { permitted: number; denied: number; total: number };
export function scopeSensitivityLabel(family: FamilyResult | null): string | null;
export function isPartialFamily(family: FamilyResult | null): boolean;
export function requestedProfiles(profileA: string, profileB: string): string[];
export function evaluateQuery(request: {
  profileA: string;
  profileB: string;
  versionScope: VersionScope;
  departmentMatch: DepartmentMatch;
}): string;
