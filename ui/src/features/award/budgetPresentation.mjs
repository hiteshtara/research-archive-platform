// Pure presentation-helper functions for the Award Budget section -
// kept dependency-free, plain JS, and node:test-able the same way
// ./awardSectionsPresentation.mjs is, since this project has no
// component-render test setup. See docs/kuali-business-rules/Budget.md.

// Budget versions are family-wide (award_number), bounded to sequences
// <= the Award version being viewed - never scoped to just the exact
// awardId. This note makes that scope visible in the UI rather than
// leaving it implicit, per the explicit requirement that a historical
// Award version must be seen to show less history, never more.
export function budgetScopeNote(summary) {
  if (!summary?.awardNumber) {
    return null;
  }
  return `Budget versions include Award ${summary.awardNumber} through sequence ${summary.viewedSequenceNumber}.`;
}

// The archive-facing "selected" Budget is deliberately not Kuali's own
// live getCurrentBudget() concept (see the design doc) - this label
// makes that distinction visible rather than saying "Current Budget"
// unqualified.
export function selectedBudgetLabel(summary) {
  if (!summary?.selectedBudgetId) {
    return "No Budget recorded for this Award.";
  }
  return `Budget Version ${summary.selectedBudgetVersionNumber}`;
}

// Every Budget version's row shows which Award sequence actually owns
// it (budget_version_number is a family-wide counter, so consecutive
// versions routinely belong to different Award sequences).
export function owningSequenceLabel(version) {
  if (!version) {
    return "—";
  }
  return `Award sequence ${version.owningAwardSequenceNumber}`;
}

export function hasAnyBudgetVersions(versions) {
  return Array.isArray(versions) && versions.length > 0;
}

// archive.award_budget_personnel_detail/_calculated_amount are empty
// across the ENTIRE Oracle source database (every Award and every
// Proposal, not just this archive's loaded population) - confirmed by a
// direct, unfiltered COUNT(*) against production Oracle. BU's real
// Kuali usage records personnel costs as a bulk non-personnel line item
// ("Personnel (ONLY IF PERSONNEL TAB IS NOT USED)") rather than through
// this per-person mechanism, so an empty Personnel panel reflects real
// upstream data, not a gap in this archive. See
// docs/kuali-business-rules/Budget.md's Personnel investigation.
export function personnelEmptyStateMessage() {
  return "No per-person Budget entries recorded for this Award's selected Budget. Personnel costs for this Award were entered as a bulk line item rather than itemized by person - see the Line Items tab.";
}
