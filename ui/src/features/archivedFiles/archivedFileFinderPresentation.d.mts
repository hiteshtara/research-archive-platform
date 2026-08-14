export interface ArchivedFileSearchFilters {
  awardNumber?: string;
  documentNumber?: string;
  awardId?: string;
  attachmentId?: string;
  fileId?: string;
}

export function hasAnyIdentifierSupplied(
  filters: ArchivedFileSearchFilters,
): boolean;

export function archivedFileResultsCountLabel(
  totalElements: number | null | undefined,
): string;

export function archivedFileSearchErrorMessage(
  status: number | undefined,
): string;

export type AvailabilityChipColor =
  | "success"
  | "warning"
  | "error"
  | "default";

export function resolveAvailabilityChipColor(
  availabilityStatus: string,
): AvailabilityChipColor;

export function archivedFileResultKey(result: {
  parentId: number | null;
  attachmentId: number | null;
}): string;

export function formatSourceDateLabel(
  sourceDate: string | null,
): string;
