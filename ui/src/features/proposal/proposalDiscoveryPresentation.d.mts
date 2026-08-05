import type {
  ExplorerProposalDiscovery,
  ExplorerProposalDiscoveryFilters,
} from "../../types/api";

export const SAVED_SEARCHES: ReadonlyArray<{
  key: string;
  label: string;
  filters: ExplorerProposalDiscoveryFilters;
}>;

export function formatCompactCurrency(
  value: number | null | undefined,
): string | null;

export function effectiveAwardAmount(
  row: ExplorerProposalDiscovery,
): number | null;

export function effectiveAwardAmountBasis(
  row: ExplorerProposalDiscovery,
): "obligated" | "anticipated" | null;
