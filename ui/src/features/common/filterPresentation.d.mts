export interface FilterFieldDefinition<Key extends string = string> {
  key: Key;
  label: string;
  type?: "date";
}

export interface BuiltFilterChip<Key extends string = string> {
  key: Key;
  label: string;
  value: string;
}

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
): Record<Key, string>;

export function clearFilters<Key extends string>(
  fields: readonly FilterFieldDefinition<Key>[],
): Record<Key, string>;
