export interface StatusDimension {
  key: string;
  label: string;
  detail: string;
}

export interface CurrentStatus {
  id: string;
  codeComplete: string;
  deployed: string;
  enabled: string;
  enforcing: string;
  tested: string;
  accepted: string;
  deployedRevision: string | null;
  deployedCommit: string | null;
  verifiedAt: string | null;
  headline: { label: string; tone: "complete" | "partial" | "outstanding" | "notApplicable" };
}

export interface SupersededRecord {
  kind: "module" | "requirement";
  name: string;
  state: string;
  evidence: unknown;
  namedRevisions: number[];
}

export const STATUS_DIMENSIONS: StatusDimension[];
export function headlineFor(status: Partial<CurrentStatus>): CurrentStatus["headline"];
export function currentStatusFor(
  requirement: unknown, capability: unknown, deployment: unknown,
): CurrentStatus;
export function currentStatusMatrix(
  requirements: unknown[], capabilities: unknown[], deployment: unknown,
): CurrentStatus[];
export function supersededDeploymentRecords(
  securityData: unknown, deployment: unknown,
): SupersededRecord[];
export function currentStatusTotals(matrix: CurrentStatus[]): {
  total: number; deployed: number; enabled: number; enforcing: number; tested: number; accepted: number;
};
