// Pure presentation-helper functions for the Award Time and Money
// section - kept dependency-free, plain JS, and node:test-able the
// same way ./awardSectionsPresentation.mjs is, since this project has
// no component-render test setup. See
// docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md.

// A History Timeline row's own sequenceNumber (the Award version it
// belongs to) and originatingAwardVersion (the version a Time and
// Money-created snapshot was produced against) are independently set
// and can differ - this never derives one from the other, only
// reports whether they agree.
export function describeHistoryEntry(entry) {
  if (!entry) {
    return {
      timeAndMoneyCreated: false,
      versionsAgree: true,
      versionNote: null,
    };
  }

  const timeAndMoneyCreated = Boolean(entry.timeAndMoneyCreated);
  const originatingAwardVersion = entry.originatingAwardVersion ?? null;
  const sequenceNumber = entry.sequenceNumber ?? null;

  const versionsAgree =
    !timeAndMoneyCreated ||
    originatingAwardVersion === null ||
    originatingAwardVersion === sequenceNumber;

  return {
    timeAndMoneyCreated,
    versionsAgree,
    versionNote: versionsAgree
      ? null
      : `Recorded against version ${originatingAwardVersion}, viewing version ${sequenceNumber}`,
  };
}

// Summary's "last action" line is family-wide (every version of this
// Award number) - most ordinary Awards' current version has never
// itself been Time and Money-created even when their family has real
// history, so a null document number here means no Time and Money
// action has EVER touched this Award number, not just this version.
export function describeLastAction(summary) {
  if (!summary?.lastFamilyTimeAndMoneyDocumentNumber) {
    return "No Time and Money action recorded for this Award.";
  }

  const parts = [`Document ${summary.lastFamilyTimeAndMoneyDocumentNumber}`];
  if (summary.lastFamilyTransactionTypeDescription) {
    parts.push(summary.lastFamilyTransactionTypeDescription);
  }
  if (summary.lastFamilyNoticeDate) {
    parts.push(summary.lastFamilyNoticeDate);
  }
  return parts.join(" · ");
}

// BUDGET_PERIOD (real Kuali column name) is an F&A cost-distribution
// period identifier, never a Budget Version - labeled explicitly here
// so the UI never repeats the naming collision this project's own
// research flagged.
export function fandaDistributionPeriodLabel(period) {
  if (period === null || period === undefined || period === "") {
    return "—";
  }
  return `${period} (F&A distribution period)`;
}
