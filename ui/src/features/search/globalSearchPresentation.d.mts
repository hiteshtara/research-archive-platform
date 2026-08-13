import type { GlobalSearchItem, GlobalSearchResponse } from "../../types/api";

export function filterOutIrbResults(
  response: GlobalSearchResponse | null | undefined,
): {
  query: string;
  totalResults: number;
  results: GlobalSearchItem[];
  failedModules: string[];
};
