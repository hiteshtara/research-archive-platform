// Presentation helpers for the Subaward Funding tab's Associated
// Award(s) cards. Kept framework-free so it can be unit-tested with
// plain node:test, matching this repo's existing features/*/*.mjs
// convention.
//
// A Subaward can legitimately have MULTIPLE concurrent Award funding
// sources, each to a different Award family - proven live from
// Kuali's own business rule
// (SubAwardDocumentRule.processSaveSubAwardFundingSourceBusinessRules,
// which only rejects adding the SAME award_number twice) and confirmed
// in the real archived data (several Subawards already have 2-3
// distinct concurrent relationships). There is no primary/current
// designation among them anywhere in the schema - every funding row
// is an independent, co-equal relationship, never a "current +
// history" model. The UI must render one card per row, always, and
// label the section with the real count.
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
      awardSponsor: funding.awardSponsor,
      awardAmount: funding.awardAmount,
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
// preserved, including duplicates/multiples. Rows with no Award link
// at all (kind "NONE") are still counted in the list but render no
// card - resolveAssociatedAwardsSectionLabel counts only real links.
export function resolveFundingAssociationCards(fundingRows) {
  return fundingRows.map((funding) => ({
    subawardFundingSourceId: funding.subawardFundingSourceId,
    card: resolveAssociatedAwardCardState(funding),
  }));
}

// "Associated Award" for exactly one real link, "Associated Awards
// (N)" for zero or multiple - the plural signals the real count up
// front rather than implying a single relationship exists.
export function resolveAssociatedAwardsSectionLabel(fundingRows) {
  const linkedCount = fundingRows.filter(
    (funding) => funding.exactLinkedAwardId != null,
  ).length;

  return linkedCount === 1
    ? "Associated Award"
    : `Associated Awards (${linkedCount})`;
}
