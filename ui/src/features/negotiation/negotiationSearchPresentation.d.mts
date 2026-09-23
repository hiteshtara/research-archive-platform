export type NegotiationFilterKey =
  | "principalInvestigator"
  | "sponsor"
  | "negotiator"
  | "agreementType"
  | "status"
  | "leadUnit"
  | "associationType"
  | "associationId"
  | "startDateFrom"
  | "startDateTo"
  | "endDateFrom"
  | "endDateTo";

export type NegotiationFilters = Record<NegotiationFilterKey, string>;

export interface NegotiationFilterField {
  key: NegotiationFilterKey;
  label: string;
  type?: "date";
}

export interface NegotiationFilterChip {
  key: NegotiationFilterKey;
  label: string;
  value: string;
}

export interface LeadUnitDisplay {
  primary: string;
  secondary: string | null;
}

export const NEGOTIATION_FILTER_FIELDS: NegotiationFilterField[];

export function emptyNegotiationFilters(): NegotiationFilters;

export function activeNegotiationFilters(
  filters: Partial<NegotiationFilters> | null | undefined,
): Partial<Record<NegotiationFilterKey, string>>;

export function countActiveNegotiationFilters(
  filters: Partial<NegotiationFilters> | null | undefined,
): number;

export function hasActiveNegotiationFilters(
  filters: Partial<NegotiationFilters> | null | undefined,
): boolean;

export function buildNegotiationSearchParams(options?: {
  query?: string;
  filters?: Partial<NegotiationFilters> | null;
  page?: number;
  size?: number;
}): Record<string, string | number>;

export function buildNegotiationFilterChips(
  filters: Partial<NegotiationFilters> | null | undefined,
): NegotiationFilterChip[];

export function removeNegotiationFilter(
  filters: NegotiationFilters,
  key: NegotiationFilterKey,
): NegotiationFilters;

export function clearNegotiationFilters(): NegotiationFilters;

export function formatLeadUnit(
  leadUnitName: string | null | undefined,
  leadUnitNumber: string | null | undefined,
): LeadUnitDisplay;

export function describeAttributeSource(
  attributeSource: string | null | undefined,
): string | null;

export function describeNegotiationResults(options?: {
  totalElements?: number;
  page?: number;
  size?: number;
  count?: number;
}): string;

export function buildNegotiationPath(
  negotiationId: number | string,
): string;
