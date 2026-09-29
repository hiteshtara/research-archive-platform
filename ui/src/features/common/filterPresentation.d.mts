export interface FilterOption {
  value: string;
  label: string;
}

export interface FilterFieldDefinition<Key extends string = string> {
  key: Key;
  label: string;
  type?: "text" | "date" | "select";
  options?: readonly FilterOption[];
  defaultValue?: string;
  helperText?: string;
}

export interface DateRangeDefinition<Key extends string = string> {
  from: Key;
  to: Key;
  label: string;
}

export interface BuiltFilterChip<Key extends string = string> {
  key: Key;
  label: string;
  value: string;
}

export type FilterErrors<Key extends string = string> = Partial<Record<Key, string>>;

interface SearchParamsLike {
  get(key: string): string | null;
}

export function isValidIsoDate(value: unknown): boolean;

export function emptyFilters<Key extends string>(
  fields: readonly FilterFieldDefinition<Key>[],
): Record<Key, string>;

export function activeFilters<Key extends string>(
  filters: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): Partial<Record<Key, string>>;

export function countActiveFilters<Key extends string>(
  filters: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): number;

export function hasActiveFilters<Key extends string>(
  filters: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): boolean;

export function buildFilterChips<Key extends string>(
  filters: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): BuiltFilterChip<Key>[];

export function removeFilter<Key extends string>(
  filters: Record<Key, string>,
  key: Key,
  fields?: readonly FilterFieldDefinition<Key>[],
): Record<Key, string>;

export function clearFilters<Key extends string>(
  fields: readonly FilterFieldDefinition<Key>[],
): Record<Key, string>;

export function sameFilters<Key extends string>(
  a: Partial<Record<Key, string>> | null | undefined,
  b: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): boolean;

export function filtersFromSearchParams<Key extends string>(
  searchParams: SearchParamsLike | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
): Record<Key, string>;

export function queryFromSearchParams(
  searchParams: SearchParamsLike | null | undefined,
  key?: string,
): string;

export function pageFromSearchParams(
  searchParams: SearchParamsLike | null | undefined,
): number;

export function buildFilterUrlParams<Key extends string>(options: {
  query?: string;
  filters?: Partial<Record<Key, string>>;
  fields: readonly FilterFieldDefinition<Key>[];
  page?: number;
  extra?: Record<string, string | number | null | undefined>;
  extraDefaults?: Record<string, string>;
}): Record<string, string>;

export function buildFilterRequestParams<Key extends string>(options: {
  query?: string;
  filters?: Partial<Record<Key, string>>;
  fields: readonly FilterFieldDefinition<Key>[];
  page?: number;
  size?: number;
  queryParam?: string;
}): Record<string, string | number>;

export function validateFilters<Key extends string>(
  filters: Partial<Record<Key, string>> | null | undefined,
  fields: readonly FilterFieldDefinition<Key>[],
  dateRanges?: readonly DateRangeDefinition<Key>[],
): FilterErrors<Key>;

export function hasFilterErrors(errors: object | null | undefined): boolean;

export function emptyResultsMessage(options: {
  noun: string;
  query?: string;
  filterCount?: number;
}): string;
