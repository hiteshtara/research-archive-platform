export const MODULE_LABELS: Readonly<Record<string, string>>;

export const MODULES: readonly string[];

export function moduleLabel(module: string): string;

export function documentSearchResultsCountLabel(
  totalElements: number | undefined,
): string;

export function documentSearchErrorMessage(
  status: number | undefined,
): string;

export function resultsAreApprovedModulesOnly(
  results: readonly { module: string }[],
): boolean;

export function isNavigable(result: {
  targetRoute: string | null;
}): boolean;
