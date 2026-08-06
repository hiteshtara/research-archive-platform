// Presentation helpers for the Subaward Amounts tab, redesigned as a
// "History of Changes" timeline - matching the business concept shown
// on Kuali's real Financial tab (subAwardHistoryOfChanges.tag), not
// just a raw dump of archive.subaward_amount rows. Kept framework-free
// so it can be unit-tested with plain node:test, matching this repo's
// existing features/*/*.mjs convention.
//
// Verified against the Kuali tag source: the per-amendment fields
// (modification number/type, effective date, obligated/anticipated
// change, performance period, comments, file) are already fully
// captured by archive.subaward_amount - no new ETL or table needed.
// Kuali's *summary* totals (Total Obligated/Anticipated Amount) are
// computed by SubAwardServiceImpl.calculateAmountInfo() by summing
// these same rows' obligatedChange/anticipatedChange - reproduced here
// client-side rather than re-implemented server-side, since the raw
// rows already carry everything required. Kuali's "Total Amount
// Released"/"Total Available Amount" fields are NOT reproduced here:
// they are computed from SUBAWARD_AMOUNT_RELEASED
// (SubAwardAmountReleased), a table this archive does not capture
// (out of v1 scope) - never fabricated as zero or omitted silently.

// A mime_type value is occasionally corrupted at the Oracle source
// (proven live: subaward_amount_info_id 23973's MIME_TYPE is a
// multiply-escaped literal string, confirmed via Oracle DUMP() to be
// genuine source data, not an ETL artifact). Rather than render that
// garbled string, detect anything that isn't a plausible
// "type/subtype" MIME token and treat it as absent.
const PLAUSIBLE_MIME_TYPE = /^[\w.+-]+\/[\w.+-]+$/;

export function isPlausibleMimeType(value) {
  return typeof value === "string" && PLAUSIBLE_MIME_TYPE.test(value.trim());
}

export function resolveAttachmentLabel(row) {
  if (!row.fileName) {
    return null;
  }
  if (isPlausibleMimeType(row.mimeType)) {
    return `${row.fileName} (${row.mimeType})`;
  }
  return row.fileName;
}

// One timeline card per real archive.subaward_amount row - ordered
// exactly as the existing findAmounts query already returns them
// (effective_date DESC, subaward_amount_info_id DESC), matching
// Kuali's own most-recent-first History of Changes list.
export function buildAmendmentTimeline(amountRows) {
  return amountRows.map((row) => ({
    subawardAmountInfoId: row.subawardAmountInfoId,
    amendmentNumber: row.modificationNumber,
    modificationType: row.modificationTypeDescription ?? row.modificationTypeCode,
    effectiveDate: row.effectiveDate,
    budgetPeriodStart: row.performanceStartDate,
    budgetPeriodEnd: row.performanceEndDate,
    obligatedChange: row.obligatedChange,
    anticipatedChange: row.anticipatedChange,
    comments: row.comments,
    attachmentLabel: resolveAttachmentLabel(row),
  }));
}

// Reproduces SubAwardServiceImpl.calculateAmountInfo()'s
// totalObligatedAmount/totalAnticipatedAmount accumulation (summing
// every row's obligatedChange/anticipatedChange) - the two summary
// totals fully derivable from data this archive already has.
export function sumAmendmentTotals(amountRows) {
  let totalObligatedChange = 0;
  let totalAnticipatedChange = 0;

  for (const row of amountRows) {
    if (typeof row.obligatedChange === "number") {
      totalObligatedChange += row.obligatedChange;
    }
    if (typeof row.anticipatedChange === "number") {
      totalAnticipatedChange += row.anticipatedChange;
    }
  }

  return { totalObligatedChange, totalAnticipatedChange };
}
