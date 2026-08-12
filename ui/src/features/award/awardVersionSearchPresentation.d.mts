import type { AwardVersionSearchHit, AwardVersionSearchPageResponse } from "../../types/api";

export function describeVersionSearchResults(
  response: AwardVersionSearchPageResponse | null | undefined,
): {
  totalElements: number;
  totalPages: number;
  content: AwardVersionSearchHit[];
};

export function versionDetailPath(hit: AwardVersionSearchHit): string;

export function versionCurrentLabel(
  hit: AwardVersionSearchHit | null | undefined,
): string;
