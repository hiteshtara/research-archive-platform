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

export const EXPLORER_MODULE_LABELS: Readonly<Record<string, string>>;

export const EXPLORER_MODULES: readonly string[];

export const NORMALIZED_STATUS_LABELS: Readonly<Record<string, string>>;

export const NORMALIZED_STATUSES: readonly string[];

export function normalizedStatusLabel(status: string): string;

export function explorerModuleLabel(module: string): string;

export interface DocumentExplorerPreset {
  key: string;
  label: string;
  filters: Record<string, string | boolean>;
}

export const DOCUMENT_EXPLORER_PRESETS: readonly DocumentExplorerPreset[];

export function moduleFacetLabel(facet: {
  value: string;
  count: number;
}): string;

export function additionalRelationshipsLabel(
  count: number,
  noun: string,
): string | null;

export function unitSourceLabel(module: string): string;
