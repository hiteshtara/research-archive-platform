import { formatCurrencyAmount } from "../award/awardSectionsPresentation.mjs";

// Shared, tested resolution logic for what every RelationshipCard
// consumer needs to compute before rendering: is the target navigable
// right now, what should the action button say, and (for Negotiation's
// polymorphic association) what the target even is. Domain-specific
// action labels live here once, as a single source of truth, instead
// of being a repeated string literal per consumer file.
export const RELATIONSHIP_ACTION_LABEL = {
  award: "Open Award",
  proposal: "Open Proposal",
  subaward: "Open Subaward",
  negotiation: "Open Negotiation",
};

// The one rule every migrated consumer applies: a relationship card is
// "archived" (its target is currently navigable) exactly when the
// server-resolved navigable*Id is a real id, never null/undefined.
// Deliberately takes just the id, not a whole row shape, so it applies
// uniformly across every domain's differently-named navigable*Id field.
export function resolveRelationshipCardState(navigableId) {
  return {
    archived: navigableId !== null && navigableId !== undefined,
  };
}

// Negotiation's associated record is polymorphic (Award/Proposal/
// Subaward/none/unsupported) and its own "is this navigable" rule
// needs both fields to agree - `clickable` alone isn't enough without
// a real id, matching the source data's own two-field shape rather
// than collapsing it into one field the way the other domains' APIs
// already do server-side.
export function resolveNegotiationAssociationArchived(association) {
  return (
    Boolean(association?.clickable) &&
    association?.navigableId !== null &&
    association?.navigableId !== undefined
  );
}

// "Negotiation {documentNumber}" - falls back to the internal
// negotiationId only when no real business identifier (documentNumber)
// was ever archived for this row, never as the first choice.
export function resolveNegotiationCardTitle(negotiation) {
  return `Negotiation ${negotiation.documentNumber ?? negotiation.negotiationId}`;
}

// Which of the three Associated Record tab presentations applies:
// "none" (no association exists at all - a real, valid state, not an
// error), "unsupported" (an association type the archive doesn't
// recognize yet), or "card" (a real AWARD/PROPOSAL/SUBAWARD
// association - render the RelationshipCard). Both non-card cases are
// deliberately shown via the shared EmptyState component, never
// silently hidden, so a genuine "no relationship" state stays visible
// for audit purposes.
export function resolveNegotiationAssociationDisplayKind(association) {
  if (association?.kind === "NONE") {
    return "none";
  }
  if (association?.kind === "UNSUPPORTED") {
    return "unsupported";
  }
  return "card";
}

// Award's Funding Proposals card caption: PI, sponsor, requested cost -
// only the pieces that actually exist, in this exact order, joined the
// same way the original inline JSX always has.
export function buildFundingProposalMetaText(link) {
  return [
    link.principalInvestigatorName ?? "—",
    link.sponsorName,
    link.requestedTotalCost !== null
      ? `Requested ${formatCurrencyAmount(link.requestedTotalCost)}`
      : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

// Proposal's Funded Awards card caption: current version, optionally
// noting a different version was linked at submission time, then the
// Proposal's own version.
export function buildFundedAwardMetaText(fundedAward) {
  const versionNote =
    fundedAward.linkedAwardVersion !== fundedAward.currentAwardVersion
      ? ` (linked at version ${fundedAward.linkedAwardVersion ?? "—"})`
      : "";

  return (
    `Current version ${fundedAward.currentAwardVersion ?? "—"}` +
    versionNote +
    ` · Proposal version ${fundedAward.proposalVersion ?? "—"}`
  );
}
