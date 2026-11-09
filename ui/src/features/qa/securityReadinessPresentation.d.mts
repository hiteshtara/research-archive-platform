export type ReadinessState = "yes" | "no" | "partial" | "n/a";
export type ReadinessTone =
  | "complete"
  | "partial"
  | "outstanding"
  | "notApplicable"
  | "unknown";

export type ReadinessStage =
  | "implemented"
  | "merged"
  | "deployed"
  | "migrationApplied"
  | "configured"
  | "tested"
  | "enforcing";

export interface SecurityCapability {
  id: string;
  name: string;
  businessSummary: string;
  states: Record<ReadinessStage, ReadinessState>;
  evidence: Partial<Record<ReadinessStage, string>>;
  pendingApproval: string | null;
}

export interface DeploymentFacts {
  environment: string;
  apiTaskDefinition: string;
  taskDefinitionIsNewest: boolean;
  imageCommit: string;
  imageCommitDate: string;
  imageDigest: string;
  mainCommit: string;
  commitsBehindMain: number;
  runningTasks: string;
  verifiedAt: string;
  verifiedHow: string;
  note?: string;
}

export interface SecurityReadiness {
  verifiedAt: string;
  verifiedBy: string;
  deployment: DeploymentFacts;
  enforcement: Record<string, string>;
  database: Record<string, unknown>;
  knownMismatch: Record<string, string>;
  stateLabels: Record<ReadinessStage, string>;
  capabilities: SecurityCapability[];
}

export const READINESS_STAGES: ReadinessStage[];

export function stateTone(value: string): ReadinessTone;
export function stateLabel(value: string): string;
export function readinessSummary(
  capability: SecurityCapability | null,
): { tone: ReadinessTone; text: string };
export function awaitingBusinessApproval(
  capabilities: SecurityCapability[],
): SecurityCapability[];
export function readinessTotals(capabilities: SecurityCapability[]): {
  total: number;
  enforcing: number;
  deployed: number;
  awaitingApproval: number;
};
export function verificationNotice(
  readiness: { verifiedAt?: string; verifiedBy?: string } | null,
  today: string,
): { label: string; detail: string; stale: boolean; days?: number | null };
export function daysBetween(from: string, to: string): number | null;
export function deploymentSummary(deployment: DeploymentFacts | null): {
  environment: string;
  revision: string;
  commit: string;
  commitDate: string;
  digest: string;
  shortDigest: string;
  behind: number;
  isCurrent: boolean;
  verifiedAt: string;
} | null;
export function shortenDigest(digest: string): string;
export function grantFigures(database: Record<string, unknown> | null): {
  verified: boolean;
  asOf: string | null;
  administrators: number | null;
  identityLinks: number | null;
  activeGrants: number | null;
  caveat: string | null;
};
