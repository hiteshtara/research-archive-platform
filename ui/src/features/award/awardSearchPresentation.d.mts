import type {
  AwardDocumentNumberMatchV1,
  AwardSearchHit,
  AwardSearchResponseV1,
} from "../../types/api";

export function describeSearchResults(
  response: AwardSearchResponseV1 | null | undefined,
): {
  totalElements: number;
  totalPages: number;
  content: AwardSearchHit[];
  exactDocumentMatch: AwardDocumentNumberMatchV1 | null;
};
