// Semantic categorization for status text, shared by StatusPill across
// all four domains. Never a single global keyword rule - Award and
// Proposal share one (the original AwardStatusPill's own logic,
// unchanged), Negotiation and Subaward each get an explicit map built
// from real, live-queried archive.negotiation.negotiation_status_
// description / archive.subaward.status_description values, never a
// guess extrapolated from Award's vocabulary.

// Award/Proposal - byte-for-byte the original AwardStatusPill's
// variantFor(). Deliberately NOT extended with an "error" category
// here: Award's real data includes a "Cancelled" status (1,213 rows)
// that this rule already (mis)classifies as "success" today. Fixing
// that is a real, separate decision, not something to change as a
// side effect of this migration - so it stays exactly as it was.
export function resolveAwardStyleStatusVariant(status) {
  const normalized = (status ?? "").toLowerCase();

  if (normalized.includes("closed")) {
    return "default";
  }

  if (normalized.includes("pending")) {
    return "warning";
  }

  return "success";
}

// archive.negotiation.negotiation_status_description - all 5 real
// distinct values in the archive, confirmed via a live query
// (2026-08-10). No fallback keyword guessing: any value not in this
// map (including one never seen live) renders "neutral".
const NEGOTIATION_STATUS_VARIANTS = {
  "Fully Executed": "success",
  "In Negotiation": "warning",
  "Under Review": "warning",
  "Signature In Process": "warning",
  Abandoned: "error",
};

export function resolveNegotiationStatusVariant(status) {
  if (status === null || status === undefined) {
    return "neutral";
  }
  return NEGOTIATION_STATUS_VARIANTS[status] ?? "neutral";
}

// archive.subaward.status_description - all 10 real distinct values,
// confirmed via a live query (2026-08-10). "04. PI/DA" and
// "09. Temporarily Cancelled" are deliberately left out of this map
// (falling through to "neutral") - neither can be confidently placed
// in a category from the text alone: "PI/DA" names no clear state,
// and "Temporarily" undermines treating it the same as a real
// "Cancelled".
const SUBAWARD_STATUS_VARIANTS = {
  "01. RA Review": "warning",
  "02. Subaward RA initial review": "warning",
  "03. Pending new or updated FRN": "warning",
  "05. Sent to subrecipient": "warning",
  "06. Subrecipient requesting revision": "warning",
  "07. Executed": "success",
  "08. Closed": "default",
  "10. Cancelled": "error",
};

export function resolveSubawardStatusVariant(status) {
  if (status === null || status === undefined) {
    return "neutral";
  }
  return SUBAWARD_STATUS_VARIANTS[status] ?? "neutral";
}

export function resolveStatusVariant(domain, status) {
  switch (domain) {
    case "award":
    case "proposal":
      return resolveAwardStyleStatusVariant(status);
    case "negotiation":
      return resolveNegotiationStatusVariant(status);
    case "subaward":
      return resolveSubawardStatusVariant(status);
    default:
      return "neutral";
  }
}
