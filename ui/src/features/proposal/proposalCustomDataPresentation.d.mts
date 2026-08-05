import type { ProposalCustomDataV1 } from "../../types/api";

export function resolveCustomDataLabel(row: ProposalCustomDataV1): string;

export function groupCustomData(
  rows: ProposalCustomDataV1[],
): { groupName: string; rows: ProposalCustomDataV1[] }[];

export function matchesCustomDataQuery(
  row: ProposalCustomDataV1,
  query: string,
): boolean;
