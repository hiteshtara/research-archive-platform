export const RESERVED_PROPOSAL_API_SEGMENTS: readonly string[];

export function isReservedProposalIdentifier(identifier: unknown): boolean;

export function isProposalWorkspacePayload(payload: unknown): boolean;

export function proposalWorkspaceErrorMessage(
  kind: "not-found" | "unavailable",
  identifier: string,
): string;
