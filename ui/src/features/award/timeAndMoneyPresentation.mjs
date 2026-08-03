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

// Summary's "last action" line - null document number means no Time
// and Money action has ever touched this exact award_id/version yet
// (still on its original entry).
export function describeLastAction(summary) {
  if (!summary?.lastTimeAndMoneyDocumentNumber) {
    return "No Time and Money action recorded for this version yet.";
  }

  const parts = [`Document ${summary.lastTimeAndMoneyDocumentNumber}`];
  if (summary.lastTransactionTypeDescription) {
    parts.push(summary.lastTransactionTypeDescription);
  }
  if (summary.lastNoticeDate) {
    parts.push(summary.lastNoticeDate);
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
