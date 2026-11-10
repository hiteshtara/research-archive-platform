export interface ReadinessStatement {
  key: string;
  heading: string;
  text: string;
  supersededBy?: string | null;
}

export function revisionsNamedIn(text: string): number[];
export function deployedRevision(deployment: { apiTaskDefinition?: string } | null): number | null;
export function isSuperseded(text: string, deployment: { apiTaskDefinition?: string } | null): boolean;
export function classifyStatements(
  securityData: Record<string, unknown> | null,
  deployment: { apiTaskDefinition?: string } | null,
): { current: ReadinessStatement[]; historical: ReadinessStatement[] };
export function requirementsClaimingVerification<T>(requirements: T[]): T[];
export function acceptanceSummary(
  requirements: readonly { status?: { acceptanceVerified?: string; decisionApproved?: string } }[],
): { total: number; verified: number; decisionsOpen: number };
