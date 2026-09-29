import type {
  DateRangeDefinition,
  FilterFieldDefinition,
  FilterOption,
} from "../common/filterPresentation.mjs";

export type AwardFilterKey =
  | "status"
  | "sponsor"
  | "principalInvestigator"
  | "leadUnit"
  | "projectStartDateFrom"
  | "projectStartDateTo";

export type AwardVersionFilterKey =
  | AwardFilterKey
  | "awardNumber"
  | "documentNumber"
  | "awardId"
  | "versionFilter";

export type ProposalFilterKey = "sponsor" | "principalInvestigator" | "leadUnit";

export type SubawardFilterKey =
  | "status"
  | "sponsor"
  | "organizationId"
  | "startDateFrom"
  | "startDateTo"
  | "endDateFrom"
  | "endDateTo";

export type GlobalSearchFilterKey = "modules";

export const AWARD_FILTER_FIELDS: readonly FilterFieldDefinition<AwardFilterKey>[];
export const AWARD_DATE_RANGES: readonly DateRangeDefinition<AwardFilterKey>[];
export const VERSION_FILTER_OPTIONS: readonly FilterOption[];
export const AWARD_VERSION_FILTER_FIELDS: readonly FilterFieldDefinition<AwardVersionFilterKey>[];
export const AWARD_VERSION_DATE_RANGES: readonly DateRangeDefinition<AwardVersionFilterKey>[];
export const AWARD_VERSION_SORT_OPTIONS: readonly FilterOption[];
export const PROPOSAL_FILTER_FIELDS: readonly FilterFieldDefinition<ProposalFilterKey>[];
export const SUBAWARD_FILTER_FIELDS: readonly FilterFieldDefinition<SubawardFilterKey>[];
export const SUBAWARD_DATE_RANGES: readonly DateRangeDefinition<SubawardFilterKey>[];
export const GLOBAL_SEARCH_FILTER_FIELDS: readonly FilterFieldDefinition<GlobalSearchFilterKey>[];
