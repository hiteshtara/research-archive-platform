import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";

import {
  activeFilters,
  buildFilterChips,
  buildFilterUrlParams,
  clearFilters,
  countActiveFilters,
  filtersFromSearchParams,
  hasFilterErrors,
  pageFromSearchParams,
  queryFromSearchParams,
  removeFilter,
  sameFilters,
  validateFilters,
} from "../features/common/filterPresentation.mjs";
import type {
  DateRangeDefinition,
  FilterErrors,
  FilterFieldDefinition,
} from "../features/common/filterPresentation.mjs";

export interface ExtraParamDefinition {
  key: string;
  defaultValue: string;
  /** Allowed values; anything else in the URL falls back to the default. */
  allowed: readonly string[];
}

const NO_RANGES: readonly never[] = [];
const NO_EXTRA: readonly ExtraParamDefinition[] = [];

/**
 * The shared search + structured-filter state behind every archive
 * search page.
 *
 * APPLIED state lives only in the URL (`?q=`, one key per active filter,
 * `?page=`, plus any `extra` such as a sort order), so refresh, a
 * bookmarked or shared link and browser Back/Forward all reproduce the
 * same result set. DRAFT state is what the inputs are editing; it only
 * reaches the URL through apply(), so typing never fires a request and
 * closing the panel never silently applies or discards anything.
 *
 * Every transition that changes the result set - apply, removing a chip,
 * Clear All, changing an extra such as sort - resets to the first page.
 * Clear All clears the structured filters, draft and applied, and keeps
 * the text query.
 *
 * `fields`, `dateRanges` and `extra` must be stable (module constants):
 * the draft re-syncs whenever the applied state they describe changes.
 */
export function useFilteredSearch<Key extends string>({
  fields,
  dateRanges = NO_RANGES as readonly DateRangeDefinition<Key>[],
  extra = NO_EXTRA,
  extraValidate,
  initialPanelOpen = false,
}: {
  fields: readonly FilterFieldDefinition<Key>[];
  dateRanges?: readonly DateRangeDefinition<Key>[];
  extra?: readonly ExtraParamDefinition[];
  /** Page-specific rules beyond dates (e.g. a whole-number ID). Must be stable. */
  extraValidate?: (filters: Record<Key, string>) => FilterErrors<Key>;
  initialPanelOpen?: boolean;
}) {
  const [searchParams, setSearchParams] = useSearchParams();

  const appliedQuery = useMemo(() => queryFromSearchParams(searchParams), [searchParams]);
  const appliedFilters = useMemo(
    () => filtersFromSearchParams(searchParams, fields),
    [searchParams, fields],
  );
  const page = useMemo(() => pageFromSearchParams(searchParams), [searchParams]);
  const appliedExtra = useMemo(() => {
    const values: Record<string, string> = {};
    for (const definition of extra) {
      const raw = searchParams.get(definition.key) ?? "";
      values[definition.key] = definition.allowed.includes(raw) ? raw : definition.defaultValue;
    }
    return values;
  }, [searchParams, extra]);

  const [draftQuery, setDraftQuery] = useState(appliedQuery);
  const [draftFilters, setDraftFilters] = useState<Record<Key, string>>(appliedFilters);
  const [panelOpen, setPanelOpen] = useState(initialPanelOpen);

  // Back/Forward (or any other navigation) changes the URL without going
  // through apply(): re-sync the inputs so they always describe the
  // result set on screen, never a stale draft from another history entry.
  useEffect(() => {
    setDraftQuery(appliedQuery);
    setDraftFilters(appliedFilters);
  }, [appliedQuery, appliedFilters]);

  const errors = useMemo(
    () => ({
      ...(extraValidate?.(draftFilters) ?? {}),
      ...validateFilters(draftFilters, fields, dateRanges),
    }) as FilterErrors<Key>,
    [draftFilters, fields, dateRanges, extraValidate],
  );
  const canApply = !hasFilterErrors(errors);

  const extraDefaults = useMemo(
    () => Object.fromEntries(extra.map((definition) => [definition.key, definition.defaultValue])),
    [extra],
  );

  const commit = useCallback(
    (
      nextQuery: string,
      nextFilters: Record<Key, string>,
      nextPage: number,
      nextExtra: Record<string, string>,
    ) => {
      setSearchParams(
        buildFilterUrlParams({
          query: nextQuery,
          filters: nextFilters,
          fields,
          page: nextPage,
          extra: nextExtra,
          extraDefaults,
        }),
      );
    },
    [setSearchParams, fields, extraDefaults],
  );

  /**
   * Applies the draft text AND the draft filters together, from page 1.
   * Refused (panel opened to show why) while any draft value is invalid.
   */
  const apply = useCallback((): boolean => {
    if (!canApply) {
      setPanelOpen(true);
      return false;
    }
    commit(draftQuery.trim(), draftFilters, 0, appliedExtra);
    return true;
  }, [canApply, commit, draftQuery, draftFilters, appliedExtra]);

  const changeFilter = useCallback((key: Key, value: string) => {
    setDraftFilters((current) => ({ ...current, [key]: value }));
  }, []);

  /** Removes one APPLIED filter immediately (and from the draft), from page 1. */
  const removeAppliedFilter = useCallback(
    (key: Key) => {
      setDraftFilters((current) => removeFilter(current, key, fields));
      commit(appliedQuery, removeFilter(appliedFilters, key, fields), 0, appliedExtra);
    },
    [appliedFilters, appliedQuery, appliedExtra, commit, fields],
  );

  /** Clears structured filters, draft and applied; keeps the text query. */
  const clearAll = useCallback(() => {
    const cleared = clearFilters(fields);
    setDraftFilters(cleared);
    commit(appliedQuery, cleared, 0, appliedExtra);
  }, [appliedQuery, appliedExtra, commit, fields]);

  const goToPage = useCallback(
    (nextPage: number) => commit(appliedQuery, appliedFilters, nextPage, appliedExtra),
    [appliedQuery, appliedFilters, appliedExtra, commit],
  );

  /** Non-filter state such as sort: applies immediately, from page 1. */
  const setExtra = useCallback(
    (key: string, value: string) =>
      commit(appliedQuery, appliedFilters, 0, { ...appliedExtra, [key]: value }),
    [appliedQuery, appliedFilters, appliedExtra, commit],
  );

  const appliedCount = countActiveFilters(appliedFilters, fields);

  return {
    // applied (URL) state - what the results on screen describe
    appliedQuery,
    appliedFilters,
    appliedActiveFilters: activeFilters(appliedFilters, fields),
    appliedExtra,
    page,
    appliedCount,
    chips: buildFilterChips(appliedFilters, fields),
    hasCriteria: appliedQuery.trim().length > 0 || appliedCount > 0,
    // draft state - what the inputs are editing
    draftQuery,
    setDraftQuery,
    draftFilters,
    setDraftFilters,
    changeFilter,
    hasUnappliedChanges: !sameFilters(draftFilters, appliedFilters, fields),
    errors,
    canApply,
    // panel
    panelOpen,
    togglePanel: () => setPanelOpen((open) => !open),
    // transitions
    apply,
    removeAppliedFilter,
    clearAll,
    goToPage,
    setExtra,
  };
}

export type FilteredSearchState<Key extends string> = ReturnType<typeof useFilteredSearch<Key>>;
