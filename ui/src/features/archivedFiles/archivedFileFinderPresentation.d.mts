export type RecordType = "ALL" | "AWARD" | "PROPOSAL";

export interface RecordTypeOption {
  value: RecordType;
  label: string;
}

export const RECORD_TYPES: RecordType[];
export const RECORD_TYPE_OPTIONS: RecordTypeOption[];

export function recordTypeLabel(recordType: string): string;

export type ArchivedFileFinderField =
  | "recordNumber"
  | "documentNumber"
  | "recordId"
  | "attachmentId"
  | "fileId";

export function visibleFieldsForRecordType(
  recordType: string,
): ArchivedFileFinderField[];

export function recordNumberFieldLabel(recordType: string): string;

export function recordIdFieldLabel(recordType: string): string;

export interface ArchivedFileFinderFilters {
  recordType: string;
  recordNumber: string;
  documentNumber: string;
  recordId: string;
  attachmentId: string;
  fileId: string;
  versionFilter: string;
}

export function hasAnyIdentifierSupplied(
  filters: ArchivedFileFinderFilters,
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
  recordType: string | null;
  parentId: number | null;
  attachmentId: number | null;
}): string;

export function formatSourceDateLabel(
  sourceDate: string | null,
): string;

export function resolveRecordViewPath(result: {
  recordType: string | null;
  parentId: number | null;
}): string | null;

export function parseRecordTypeParam(value: string | null): RecordType;

export function parseVersionFilterParam(
  value: string | null,
): "all" | "current" | "historical";

export function dispatchArchivedFileDownload<T>(
  recordType: string | null | undefined,
  parentId: number,
  attachmentId: number,
  downloadAward: (parentId: number, attachmentId: number) => Promise<T>,
  downloadProposal: (parentId: number, attachmentId: number) => Promise<T>,
): Promise<T>;
