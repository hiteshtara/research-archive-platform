import { useCallback, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";

import {
  buildSearchParams,
  readSearchParams,
} from "../features/common/searchPresentation.mjs";

/**
 * Search state held in the URL rather than in local-only component
 * state, so refresh, browser Back/Forward and a copied link all restore
 * the same search instead of silently resetting it.
 *
 * `draft` is what is currently typed in the box; `query` is what was
 * actually searched for. They differ between keystroke and Enter, which
 * is what stops every keystroke firing a request.
 */
export function useSearchQueryParam({
  extra,
}: { extra?: Record<string, string | number | null | undefined> } = {}) {
  const [searchParams, setSearchParams] = useSearchParams();

  const { query, page } = useMemo(
    () => readSearchParams(searchParams),
    [searchParams],
  );

  const [draft, setDraft] = useState(query);

  const submit = useCallback(
    (value: string) => {
      const trimmed = value.trim();
      setDraft(value);
      setSearchParams(buildSearchParams({ query: trimmed, page: 0, extra }));
    },
    [setSearchParams, extra],
  );

  const goToPage = useCallback(
    (nextPage: number) => {
      setSearchParams(buildSearchParams({ query, page: nextPage, extra }));
    },
    [setSearchParams, query, extra],
  );

  return {
    draft,
    setDraft,
    query,
    page,
    submit,
    goToPage,
    hasSearched: query.trim().length > 0,
  };
}
