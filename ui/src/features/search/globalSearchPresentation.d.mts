import type { GlobalSearchItem, GlobalSearchResponse } from "../../types/api";

export function filterOutIrbResults(
  response: GlobalSearchResponse | null | undefined,
): {
  query: string;
  totalResults: number;
  results: GlobalSearchItem[];
  failedModules: string[];
};

export function describeResultCard(
  result: GlobalSearchItem | null | undefined,
): {
  identifier: string;
  title: string;
  identifierLine: string;
  showSemanticChip: boolean;
  semanticChipLabel: string;
  piLine: string | null;
  matchedCaption: string | null;
};
