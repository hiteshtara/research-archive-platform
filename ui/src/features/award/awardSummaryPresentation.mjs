// Award Workspace Summary field/label definitions.
//
// These live here, not inline in AwardSummarySection.tsx, because this
// repo has no component-render test setup (see CLAUDE.md) - keeping the
// labels and value derivation in a plain module is the only way the
// exact Kuali business labels can be pinned by a test.
//
// THE RULE THESE ENCODE: modernize the presentation, preserve the exact
// Kuali business label and source semantics. BU staff moving from the
// legacy Kuali Award screen must recognise the same field names. Do not
// "modernize" a label here when an established Kuali label exists.

export const EMPTY = "—"; // em dash

// Null/undefined/blank render as an em dash. Everything else is passed
// through VERBATIM - notably the literal string "unknown", which is a
// real archived Kuali FAIN_ID value (Award 105698-00001 carries exactly
// that) and must never be coerced to null or an em dash.
export function displayValue(value) {
  if (value === null || value === undefined) {
    return EMPTY;
  }
  const text = String(value);
  return text.trim() === "" ? EMPTY : text;
}

// Sponsor/Prime Sponsor render the human-readable NAME as the card
// value and carry the ID/code as secondary caption metadata, so the
// name is visually primary while the Kuali "Sponsor ID" terminology
// stays visible and exact.
function identifiedBy(label, name, idLabel, idValue) {
  const id = displayValue(idValue);
  return {
    label,
    value: displayValue(name),
    caption: id === EMPTY ? undefined : `${idLabel}: ${id}`,
  };
}

export function buildAwardSummaryGroups(summary) {
  if (!summary) {
    return [];
  }

  return [
    {
      title: "Institution",
      fields: [
        { label: "Award ID", value: displayValue(summary.awardNumber) },
        { label: "Version", value: displayValue(summary.sequenceNumber) },
        { label: "Award Status", value: displayValue(summary.status) },
        { label: "Grant Number", value: displayValue(summary.grantNumber) },
        { label: "Award Title", value: displayValue(summary.title) },
        { label: "Lead Unit", value: displayValue(summary.leadUnit) },
        { label: "Account Type", value: displayValue(summary.accountType) },
        { label: "Activity Type", value: displayValue(summary.activityType) },
        { label: "Award Type", value: displayValue(summary.awardType) },
        {
          label: "Federal Clinical Trial",
          value: displayValue(summary.federalClinicalTrial),
        },
      ],
    },
    {
      title: "Sponsor",
      fields: [
        identifiedBy(
          "Sponsor Name",
          summary.sponsor,
          "Sponsor ID",
          summary.sponsorCode,
        ),
        {
          label: "Sponsor Award ID",
          value: displayValue(summary.sponsorAwardNumber),
        },
        identifiedBy(
          "Prime Sponsor Name",
          summary.primeSponsor,
          "Prime Sponsor ID",
          summary.primeSponsorCode,
        ),
        {
          label: "Prime Sponsor Award ID",
          value: displayValue(summary.primeSponsorAwardId),
        },
        {
          label: "Modification ID",
          value: displayValue(summary.modificationNumber),
        },
        { label: "FAIN ID", value: displayValue(summary.fainId) },
        {
          label: "NSF Science Code",
          value: displayValue(summary.nsfScienceCode),
        },
      ],
    },
    {
      // Number and title are one block: they come from the same
      // archive.award_cfda row and the title is never sourced from
      // anywhere else. A missing title is a known property of the
      // source (populated in 36,779 of 177,317 rows), not an error.
      title: "ALN",
      fields: [
        { label: "ALN Number", value: displayValue(summary.alnNumber) },
        {
          label: "ALN Program Title Name",
          value: displayValue(summary.alnProgramTitleName),
        },
      ],
    },
    {
      title: "Project / Dates",
      fields: [
        // Project Start Date is award_effective_date, NOT begin_date -
        // see V078 and AwardSummaryResponse for the evidence.
        {
          label: "Project Start Date",
          value: displayValue(summary.awardEffectiveDate),
        },
        {
          label: "Obligation Start Date",
          value: displayValue(summary.obligationStartDate),
        },
      ],
    },
  ];
}

// Labels retired from Summary. Kept as an explicit list so the
// regression test asserting their absence names them, rather than
// matching on incidental text.
export const RETIRED_SUMMARY_LABELS = Object.freeze([
  "Begin Date",
  "Begin date",
  "Closeout Date",
  "Closeout date",
  "Award Effective Date",
  "Award effective date",
  "Federal Award Year",
]);
