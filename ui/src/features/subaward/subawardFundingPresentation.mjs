// Presentation helpers for the Subaward Funding tab's Associated Award
// card. Kept framework-free so it can be unit-tested with plain
// node:test, matching this repo's existing features/*/*.mjs convention.
//
// SubawardFundingResponse.exactLinkedAwardId is the raw, authoritative
// SUBAWARD_FUNDING_SOURCE.AWARD_ID (see
// SubawardArchiveRepository.findFunding) - never assumed current.
// navigableCurrentAwardId/archived describe whether that Award
// family's *current* version has actually been archived, resolved
// independently by award_number. Never hide the underlying
// relationship: a funding row with an award link but no archived
// current version still renders a card, just a non-clickable one.

// One card state per funding-source row:
//   "NONE"          - the funding row has no Award link at all
//                      (exactLinkedAwardId is null)
//   "NOT_ARCHIVED"  - linked, but the current Award version has not
//                      been archived yet - honest, non-clickable
//   "LOADED"        - linked and the current Award version is
//                      archived - clickable
export function resolveAssociatedAwardCardState(funding) {
  if (funding.exactLinkedAwardId == null) {
    return { kind: "NONE" };
  }

  if (funding.archived && funding.navigableCurrentAwardId != null) {
    return {
      kind: "LOADED",
      awardNumber: funding.awardNumber,
      awardTitle: funding.awardTitle,
      awardStatus: funding.awardStatus,
      navigableCurrentAwardId: funding.navigableCurrentAwardId,
    };
  }

  return {
    kind: "NOT_ARCHIVED",
    awardNumber: funding.awardNumber,
  };
}

// One card state per row in the Funding tab's data, in the same order
// as the API returned them - every real funding-source row is
// preserved, including duplicates/multiples.
export function resolveFundingAssociationCards(fundingRows) {
  return fundingRows.map((funding) => ({
    subawardFundingSourceId: funding.subawardFundingSourceId,
    card: resolveAssociatedAwardCardState(funding),
  }));
}
