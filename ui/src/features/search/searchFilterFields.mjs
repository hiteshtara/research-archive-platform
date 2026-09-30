/**
 * Structured-filter field definitions for every archive search page that
 * uses the shared filter panel (Negotiations keeps its own definitions in
 * features/negotiation, where they already lived).
 *
 * Each `key` is the API's own parameter name and the URL key; each label
 * is the business term shown in the panel and its chips. Matching
 * semantics are the server's (see docs/architecture/SEARCH_FILTER_MATRIX.md);
 * helper text states them where a user could otherwise guess wrong -
 * exact versus contains, name versus code, inclusive dates.
 */

const INCLUSIVE = "Inclusive";

// Awards, Historical Award Records and Proposals match people whose role is
// PI or MPI - BU's Kuali labels MPI "Co-PI" (Req 12). Co-Investigators and
// Key Persons never match. Negotiations and Subawards keep their own labels.
const PI_CO_PI = "PI / Co-PI";

/* --- Awards (current Award families) --------------------------------- */

const AWARD_ATTRIBUTE_FIELDS = [
  {
    key: "status",
    label: "Status",
    helperText: "Exact, e.g. Approved Award",
  },
  { key: "sponsor", label: "Sponsor", helperText: "Name or code" },
  { key: "principalInvestigator", label: PI_CO_PI },
  { key: "leadUnit", label: "Lead Unit", helperText: "Name or number" },
  {
    key: "projectStartDateFrom",
    label: "Project Start Date From",
    type: "date",
    helperText: INCLUSIVE,
  },
  {
    key: "projectStartDateTo",
    label: "Project Start Date To",
    type: "date",
    helperText: INCLUSIVE,
  },
];

export const AWARD_FILTER_FIELDS = AWARD_ATTRIBUTE_FIELDS;

export const AWARD_DATE_RANGES = [
  {
    from: "projectStartDateFrom",
    to: "projectStartDateTo",
    label: "Project Start Date",
  },
];

/* --- Historical Award Records (every Award version) ------------------ */

export const VERSION_FILTER_OPTIONS = [
  { value: "all", label: "All versions" },
  { value: "current", label: "Current only" },
  { value: "historical", label: "Historical only" },
];

export const AWARD_VERSION_FILTER_FIELDS = [
  { key: "awardNumber", label: "Award Number (exact)" },
  { key: "documentNumber", label: "Document Number (exact)" },
  {
    key: "awardId",
    label: "Award ID (exact)",
    helperText: "Whole number",
  },
  {
    key: "versionFilter",
    label: "Versions",
    type: "select",
    options: VERSION_FILTER_OPTIONS,
    defaultValue: "all",
  },
  ...AWARD_ATTRIBUTE_FIELDS,
];

export const AWARD_VERSION_DATE_RANGES = AWARD_DATE_RANGES;

export const AWARD_VERSION_SORT_OPTIONS = [
  { value: "sequence", label: "Sequence number" },
  { value: "date", label: "Last updated" },
];

/* --- Proposals ------------------------------------------------------- */

export const PROPOSAL_FILTER_FIELDS = [
  { key: "sponsor", label: "Sponsor", helperText: "Name or code" },
  { key: "principalInvestigator", label: PI_CO_PI },
  { key: "leadUnit", label: "Lead Unit", helperText: "Name or number" },
];

/* --- Subawards ------------------------------------------------------- */

export const SUBAWARD_FILTER_FIELDS = [
  {
    key: "status",
    label: "Status",
    helperText: "Exact, e.g. Executed",
  },
  {
    key: "sponsor",
    label: "Sponsor",
    helperText: "Includes prime sponsor",
  },
  {
    key: "organizationId",
    label: "Organization ID",
    helperText: "Exact match",
  },
  { key: "startDateFrom", label: "Start Date From", type: "date", helperText: INCLUSIVE },
  { key: "startDateTo", label: "Start Date To", type: "date", helperText: INCLUSIVE },
  { key: "endDateFrom", label: "End Date From", type: "date", helperText: INCLUSIVE },
  { key: "endDateTo", label: "End Date To", type: "date", helperText: INCLUSIVE },
];

export const SUBAWARD_DATE_RANGES = [
  { from: "startDateFrom", to: "startDateTo", label: "Start Date" },
  { from: "endDateFrom", to: "endDateTo", label: "End Date" },
];

/* --- Global Search --------------------------------------------------- */

/**
 * Record Type is the only filter offered here: it is the only field with
 * the same meaning in every module's Global Search result. Sponsor, PI,
 * status and dates mean different things per module (or are absent), so
 * presenting them as cross-module filters would be misleading.
 */
export const GLOBAL_SEARCH_FILTER_FIELDS = [
  {
    key: "modules",
    label: "Record Type",
    type: "select",
    defaultValue: "ALL",
    options: [
      { value: "ALL", label: "All record types" },
      { value: "AWARD", label: "Awards" },
      { value: "PROPOSAL", label: "Proposals" },
      { value: "NEGOTIATION", label: "Negotiations" },
      { value: "SUBAWARD", label: "Subawards" },
    ],
  },
];
