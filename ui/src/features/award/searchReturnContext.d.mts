export const SEARCH_RETURN_LABEL: string;

/** Router state a search page attaches to each result link. */
export interface SearchReturnState {
  searchReturn: string;
}

export function buildSearchReturn(
  pathname: string,
  search?: string,
): SearchReturnState | undefined;

/** The validated internal path to return to, or null when absent. */
export function readSearchReturn(state: unknown): string | null;

export function forwardSearchReturn(
  state: unknown,
): SearchReturnState | undefined;
