import type {
  GlobalSearchItem,
  GlobalSearchModuleTotal,
  GlobalSearchResponse,
} from "../../types/api";

export function filterOutIrbResults(
  response: GlobalSearchResponse | null | undefined,
): {
  query: string;
  totalResults: number;
  results: GlobalSearchItem[];
  failedModules: string[];
  moduleTotals: GlobalSearchModuleTotal[];
};

export function describeResultCard(
  result: GlobalSearchItem | null | undefined,
): {
  identifier: string;
  title: string;
  identifierLine: string;
  subtitleLine: string | null;
  showSemanticChip: boolean;
  semanticChipLabel: string;
  piLine: string | null;
  matchedCaption: string | null;
};

export interface GlobalSearchOutcome<T = unknown> {
  direct: T[];
  related: T[];
  directCount: number;
  relatedCount: number;
  searchIncomplete: boolean;
  failedModules: string[];
  showNoDirectMatches: boolean;
  offerRelatedToggle: boolean;
  showRelatedSection: boolean;
  relatedHeading: string;
  relatedExplanation: string;
  showRelatedActionLabel: string;
}

export function isRelatedResult(result: unknown): boolean;
export function splitDirectAndRelated<T>(results?: T[]): {
  direct: T[];
  related: T[];
};
export function describeGlobalSearchOutcome<T>(options?: {
  results?: T[];
  failedModules?: string[];
  relatedRevealed?: boolean;
}): GlobalSearchOutcome<T>;
export function incompleteSearchMessage(failedModules?: string[]): string | null;
export function noDirectMatchesMessage(query?: unknown): string;

export interface ResultCountDescription {
  showing: number;
  summary: string;
  truncated: boolean;
  perModule: string[];
  truncationNote?: string;
}

export function describeResultCounts(
  moduleTotals?: GlobalSearchModuleTotal[],
  directCount?: number,
): ResultCountDescription;
