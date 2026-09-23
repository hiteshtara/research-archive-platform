export interface StatusParts {
  code: string | null;
  label: string | null;
}

export interface NamedIdentifier {
  primary: string;
  secondary: string | null;
}

export type SearchState = "initial" | "loading" | "error" | "empty" | "results";

export function splitStatusCode(status: string | null | undefined): StatusParts;

export function describeResultCount(options?: {
  total?: number;
  singular?: string;
  plural?: string;
}): string;

export function formatNamedIdentifier(
  name: string | null | undefined,
  identifier: string | number | null | undefined,
  options?: { fallback?: string },
): NamedIdentifier;

export function joinMetadata(
  parts: ReadonlyArray<string | null | undefined>,
  separator?: string,
): string;

export function readSearchParams(
  searchParams: URLSearchParams,
  options?: { pageKey?: string },
): { query: string; page: number };

export function buildSearchParams(options?: {
  query?: string;
  page?: number;
  extra?: Record<string, string | number | null | undefined>;
}): Record<string, string>;

export function resolveSearchState(options?: {
  hasSearched?: boolean;
  isLoading?: boolean;
  isError?: boolean;
  resultCount?: number;
}): SearchState;
