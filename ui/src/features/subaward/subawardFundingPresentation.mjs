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

// The Funding tab heading. Always exactly "Associated Awards" - no
// count, no singular form, no indication that anything was withheld.
//
// DO NOT reintroduce a count here. This deliberately ignores its
// argument, and the reason is a security decision, not a style choice.
//
// Business decision D7 (approved 2026-10-26): display only authorized
// funding Awards, and never reveal the existence or number of restricted
// ones. Record authorization omits funding rows the caller may not see, so
// any count derived from the rows that arrived describes the caller's view,
// not the record. Printing it as the record's number of Associated Awards
// would be false - a Subaward funded by three Awards with one visible would
// read "Associated Award", singular. Printing "1 shown; others restricted"
// instead would be true but would disclose that more exist, which D7
// forbids. Dropping the count is the only option that is neither false nor
// disclosing.
//
// It also carries the indistinguishability property D7 is really about:
// two Subawards whose authorized funding is identical render an identical
// heading, even when one has further funding Awards the caller cannot see.
// A count - of any kind, in either direction - would break that.
//
// The real count remains visible to anyone entitled to it: the cards below
// are rendered one per authorized funding row.
export function resolveAssociatedAwardsSectionLabel() {
  return "Associated Awards";
}
