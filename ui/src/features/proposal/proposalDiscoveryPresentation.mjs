// Presentation helpers for Proposal Explorer (GET
// /api/v1/explorer/proposals). Kept framework-free so it can be unit-
// tested with plain node:test, matching this repo's existing
// features/*/*.mjs convention. Business-facing language only - no
// implementation detail (joins, embeddings, SQL) belongs in any string
// this module produces.

// One-click presets a research administrator actually asks for, each
// mapping directly to real, verified data: NSF is a single real
// sponsor_code (301573); NIH is fragmented across ~15 institute-
// specific codes in the source data, so it's matched by sponsorName
// substring instead of a single code (see
// AwardArchiveRepository.findProposalDiscoveryRows's own comment).
export const SAVED_SEARCHES = [
  { key: "funded", label: "Funded Awards", filters: { hasFundedAward: true } },
  { key: "over1m", label: "Over $1M", filters: { minimumAwardAmount: 1000000 } },
  { key: "nsf", label: "NSF", filters: { sponsorCode: "301573" } },
  { key: "nih", label: "NIH", filters: { sponsorName: "NIH" } },
  { key: "withAttachments", label: "With Attachments", filters: { hasAttachments: true } },
  { key: "missingAttachments", label: "Missing Attachments", filters: { hasAttachments: false } },
  { key: "noAward", label: "No Funded Award Yet", filters: { hasFundedAward: false } },
];

// "$19.4M" instead of "$19,399,383" - compact enough to scan a results
// column, matching the "💰 $19.4M" visual cue asked for. Falls back to
// a plain formatted number under $100K, where a compact suffix would
// read as imprecise rather than helpful.
export function formatCompactCurrency(value) {
  if (value === null || value === undefined) {
    return null;
  }
  const abs = Math.abs(value);
  if (abs >= 1_000_000) {
    return `$${(value / 1_000_000).toFixed(1)}M`;
  }
  if (abs >= 100_000) {
    return `$${(value / 1_000).toFixed(0)}K`;
  }
  return value.toLocaleString(undefined, {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  });
}

// A Proposal's "effective" amount for display - obligated once real,
// falling back to anticipated before that - mirrors exactly what the
// API's own minimumAwardAmount filter compares against, so the number
// shown in the results table is always the same number a filter would
// have matched on.
export function effectiveAwardAmount(row) {
  if (row.obligatedAmount !== null && row.obligatedAmount !== undefined) {
    return row.obligatedAmount;
  }
  return row.anticipatedAmount ?? null;
}

// Which of the two amount fields effectiveAwardAmount actually
// returned - lets the UI show a subtle "(anticipated)" qualifier
// rather than presenting a pre-obligation estimate as if it were
// final.
export function effectiveAwardAmountBasis(row) {
  if (row.obligatedAmount !== null && row.obligatedAmount !== undefined) {
    return "obligated";
  }
  if (row.anticipatedAmount !== null && row.anticipatedAmount !== undefined) {
    return "anticipated";
  }
  return null;
}
